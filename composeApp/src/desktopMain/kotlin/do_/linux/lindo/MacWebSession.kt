package do_.linux.lindo

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import do_.linux.lindo.core.network.UserApiKeyAuthManager
import do_.linux.lindo.core.diagnostics.DiagnosticCategory
import do_.linux.lindo.core.diagnostics.LinDoDiagnosticLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal suspend fun awaitMacWebReply(result: CompletableDeferred<JsonElement>, timeoutMillis: Long): JsonElement =
    withTimeoutOrNull(timeoutMillis) { result.await() }
        ?: throw IllegalStateException("网页响应超时，请重试。")

/** Private in-process WebKit session. No localhost server or external browser profile. */
internal class MacWebSession {
    private interface Reply : Callback { fun invoke(id: Long, json: String) }
    private interface Bridge : Library { fun lindo_web_command(id: Long, json: String, reply: Reply) }
    companion object {
        private val ids = AtomicLong()
        private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()
        private val callback = object : Reply {
            override fun invoke(id: Long, json: String) {
                runCatching {
                    val response = Json.parseToJsonElement(json).jsonObject
                    if (id == 0L) {
                        response["value"]?.jsonObject?.get("redirect")?.jsonPrimitive?.contentOrNull?.let {
                            UserApiKeyAuthManager.handleAuthRedirectUri(it)
                        }
                    } else {
                        val promise = pending.remove(id) ?: return
                        if (response["error"] != null) {
                            // Native messages contain only operation/error codes, never page content or cookies.
                            LinDoDiagnosticLogger.warn(DiagnosticCategory.AUTH, "MacWebSession", "native-command-failed", response["error"]?.jsonPrimitive?.contentOrNull)
                            promise.completeExceptionally(IllegalStateException("网页连接未完成，请重试"))
                        }
                        else promise.complete(response["value"] ?: JsonNull)
                    }
                }
            }
        }
        private val bridge: Bridge by lazy {
            val path = Files.createTempFile("lindo-webkit-", ".dylib").toFile().apply { deleteOnExit() }
            MacWebSession::class.java.getResourceAsStream("/native/libLindoWebView.dylib")
                ?.use { input -> path.outputStream().use { input.copyTo(it) } }
                ?: error("应用内网页组件缺失，请重新安装 Lindo")
            Native.load(path.absolutePath, Bridge::class.java)
        }
    }
    private suspend fun command(method: String, params: JsonObject, timeoutSeconds: Long): JsonElement = withContext(Dispatchers.IO) {
        val id = ids.incrementAndGet()
        val result = CompletableDeferred<JsonElement>()
        pending[id] = result
        try {
            bridge.lindo_web_command(id, buildJsonObject { put("method", method); put("params", params) }.toString(), callback)
            // A missing native callback is an operation failure. It must not cancel the UI's
            // LaunchedEffect, which would skip the result handler and leave loading visible.
            awaitMacWebReply(result, timeoutSeconds * 1000)
        } finally { pending.remove(id) }
    }
    suspend fun call(method: String, params: JsonObject = buildJsonObject {}): JsonObject =
        command(method, params, 20).jsonObject
    suspend fun evaluate(script: String, timeoutSeconds: Long = 20): JsonElement? =
        command("evaluate", buildJsonObject { put("script", script) }, timeoutSeconds).takeUnless { it == JsonNull }
}
