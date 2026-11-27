package do_.linux.lindo.core.network

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.*

internal enum class ForumConnectionStage(val label: String) {
    Idle(""), Checking("正在恢复连接…"), Retry("连接未恢复 · 重试连接"), VerifyAgain("站点仍需验证")
}

internal class AndroidForumRead private constructor(val url: String, val headers: Map<String, String>, val topic: Int?) {
    @Volatile var response: Pair<Int, String>? = null

    fun reachedForum(reply: Pair<Int, String>?): Boolean {
        if (reply == null) return false
        val body = runCatching { Json.parseToJsonElement(reply.second) as? JsonObject }.getOrNull() ?: return false
        if (reply.first in setOf(401, 403, 404, 422, 429)) {
            val errorType = body["error_type"] as? JsonPrimitive
            return body["errors"] is JsonArray || (errorType?.isString == true && errorType.content.isNotBlank())
        }
        if (reply.first !in 200..299) return false
        return if (topic == null) (body["topic_list"] as? JsonObject)?.get("topics") is JsonArray
        else (body["id"] as? JsonPrimitive)?.intOrNull == topic && (body["post_stream"] as? JsonObject)?.get("posts") is JsonArray
    }

    companion object {
        private val topicPath = Regex("""/t/(\d+)(?:/\d+)?\.json""")
        private const val sort = "(?:latest|top|hot|new|unread|read)"
        private const val category = """c/(?:[^/]+/)*\d+"""
        private val feedPath = Regex("""/(?:$sort|(?:$category|tag/[^/]+|tags/$category/[^/]+)(?:/l/$sort)?)\.json""")
        fun latest() = AndroidForumRead("https://linux.do/latest.json?no_definitions=true", emptyMap(), null)
        fun fromRequest(url: String, method: String, headers: Map<String, String>): AndroidForumRead? {
            if (!method.equals("GET", ignoreCase = true)) return null
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            if (uri.scheme != "https" || uri.rawAuthority != "linux.do") return null
            val topic = topicPath.matchEntire(uri.rawPath)?.groupValues?.get(1)?.toIntOrNull()
            if (topic == null && !feedPath.matches(uri.rawPath)) return null
            return AndroidForumRead(url, headers, topic)
        }
    }
}

/** The first foreground reader joining a verification round owns the reusable GET response. */
internal class AndroidVerificationTarget {
    private var round = -1L
    private var generation = -1L
    private var request: AndroidForumRead? = null

    @Synchronized fun offer(round: Long, generation: Long, original: AndroidForumRead?) {
        if (original == null) return
        if (this.round != round || this.generation != generation || request == null) {
            this.round = round
            this.generation = generation
            request = original
        }
    }
    @Synchronized fun get(round: Long, generation: Long) = request?.takeIf { this.round == round && this.generation == generation }
    @Synchronized fun release(round: Long) {
        if (this.round == round) request = null
    }
}

/** Called on the main dispatcher. Only an explicit retry can start a second connection check. */
internal class AndroidForumConnection {
    var stage by mutableStateOf(ForumConnectionStage.Idle)
        private set
    private var owner: Long? = null
    private var target: AndroidForumRead? = null
    private var checking: CompletableDeferred<Boolean>? = null

    fun reset() {
        checking?.complete(false)
        checking = null
        owner = null
        target = null
        stage = ForumConnectionStage.Idle
    }

    fun beginRequestedVerification() {
        if (stage == ForumConnectionStage.VerifyAgain) stage = ForumConnectionStage.Idle
    }

    fun discardUnfinishedVerification() {
        if (stage == ForumConnectionStage.Idle) target = null
    }

    suspend fun check(
        generation: Long,
        original: AndroidForumRead?,
        explicitRetry: Boolean,
        currentGeneration: () -> Long,
        isChallenge: (Pair<Int, String>?) -> Boolean,
        read: suspend (AndroidForumRead) -> Pair<Int, String>?
    ): Boolean {
        if (owner != generation) reset()
        checking?.let { return it.await() }
        if (stage != ForumConnectionStage.Idle && !explicitRetry) return false
        val request = target ?: original ?: AndroidForumRead.latest()
        val flight = CompletableDeferred<Boolean>()
        checking = flight
        target = request
        owner = generation
        stage = ForumConnectionStage.Checking
        try {
            val reply = read(request)
            if (checking !== flight || currentGeneration() != generation) return false
            val challenged = isChallenge(reply)
            val ready = !challenged && withContext(Dispatchers.Default) { request.reachedForum(reply) }
            if (checking !== flight || currentGeneration() != generation) return false
            stage = when {
                ready -> ForumConnectionStage.Idle
                explicitRetry && challenged -> ForumConnectionStage.VerifyAgain
                else -> ForumConnectionStage.Retry
            }
            if (ready) {
                request.response = reply
                target = null
            }
            flight.complete(ready)
            return ready
        } finally {
            if (checking === flight) {
                checking = null
                if (currentGeneration() != generation) reset()
                else if (stage == ForumConnectionStage.Checking) stage = ForumConnectionStage.Retry
            }
            flight.complete(false)
        }
    }
}
