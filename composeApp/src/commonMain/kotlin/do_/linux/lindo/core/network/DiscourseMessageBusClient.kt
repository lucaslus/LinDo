package do_.linux.lindo.core.network

import do_.linux.lindo.core.connect.UserPresenceService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.min

/**
 * Disciplined Discourse MessageBus live-sync client (Android / KMP parity with Desktop `messageBus.ts`).
 *
 * Design guarantees:
 * - Foreground-only: immediately pauses polling while app is in background (`UserPresenceService.isForeground == false`).
 * - Calm 12s cadence with exponential backoff on 403/429/errors and respects global rate-limit cooldowns.
 * - Routes through `LinuxDoRepository.pollMessageBus` (shares authoritative cookies & `X-Silence-Logger` + `Discourse-Background`).
 */
class DiscourseMessageBusClient(
    private val pollTransport: suspend (clientId: String, cursors: Map<String, Long>) -> Pair<Int, String>?,
    private val isForegroundProvider: () -> Boolean = { UserPresenceService.isForeground },
    private val rateLimitRemainingMsProvider: () -> Long = { LinuxDoRepository.getTransientRateLimitRemainingMs() },
    private val initialDelayMs: Long = 4_500L,
    private val steadyIntervalMs: Long = 12_000L,
    private val backgroundSleepMs: Long = 18_000L,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    val clientId: String = "lindo_${System.currentTimeMillis().toString(36)}_${UUID.randomUUID().toString().take(8)}"

    private val handlers = ConcurrentHashMap<String, CopyOnWriteArraySet<(JsonElement?, Long) -> Unit>>()
    private val cursors = ConcurrentHashMap<String, Long>()
    private val lock = Any()

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    private var inFlight: Boolean = false

    @Volatile
    var consecutiveFailures: Int = 0
        private set

    private var loopJob: Job? = null

    fun subscribe(
        channel: String,
        initialCursor: Long = -1L,
        handler: (data: JsonElement?, messageId: Long) -> Unit
    ): () -> Unit {
        val set = handlers.computeIfAbsent(channel) { CopyOnWriteArraySet() }
        cursors.putIfAbsent(channel, initialCursor)
        set.add(handler)
        ensureRunning()
        return {
            val current = handlers[channel]
            current?.remove(handler)
            if (current != null && current.isEmpty()) {
                handlers.remove(channel, current)
                cursors.remove(channel)
            }
            if (handlers.isEmpty()) {
                stop()
            }
        }
    }

    fun activeChannels(): Set<String> = handlers.keys.toSet()

    fun cursorFor(channel: String): Long? = cursors[channel]

    fun onForegroundResumed() {
        synchronized(lock) {
            if (!isRunning || handlers.isEmpty() || inFlight) return
            loopJob?.cancel()
            loopJob = launchLoop(1_500L)
        }
    }

    fun stop() {
        synchronized(lock) {
            isRunning = false
            loopJob?.cancel()
            loopJob = null
        }
    }

    fun resetForTest() {
        stop()
        handlers.clear()
        cursors.clear()
        consecutiveFailures = 0
        inFlight = false
    }

    private fun ensureRunning() {
        synchronized(lock) {
            if (isRunning) return
            isRunning = true
            consecutiveFailures = 0
            loopJob?.cancel()
            loopJob = launchLoop(initialDelayMs)
        }
    }

    private fun launchLoop(firstDelayMs: Long): Job = scope.launch {
        var nextDelay = firstDelayMs.coerceAtLeast(200L)
        while (isActive && isRunning && handlers.isNotEmpty()) {
            delay(nextDelay)
            if (!isActive || !isRunning || handlers.isEmpty()) break
            nextDelay = pollOnceInternal()
        }
    }

    /**
     * Executes a single disciplined MessageBus poll cycle and returns the computed next delay in ms.
     * Exposed `internal` so unit tests can deterministically verify cursors, dispatch, background pause, and backoff.
     */
    internal suspend fun pollOnceInternal(): Long {
        if (handlers.isEmpty()) return steadyIntervalMs
        if (!isForegroundProvider()) {
            return backgroundSleepMs
        }
        val globalRateLimitMs = rateLimitRemainingMsProvider()
        if (globalRateLimitMs > 0L) {
            return globalRateLimitMs.coerceIn(5_000L, 180_000L)
        }
        if (inFlight) return steadyIntervalMs
        inFlight = true
        try {
            val snapshot = LinkedHashMap<String, Long>()
            for ((ch, cur) in cursors) {
                if (handlers.containsKey(ch)) {
                    snapshot[ch] = cur
                }
            }
            if (snapshot.isEmpty()) return steadyIntervalMs

            val resp = pollTransport(clientId, snapshot)
            val status = resp?.first ?: 0
            val body = resp?.second.orEmpty()
            if (status == 200 && body.trimStart().startsWith("[")) {
                val parsed = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull()
                if (parsed != null) {
                    consecutiveFailures = 0
                    for (elem in parsed) {
                        val obj = elem as? JsonObject ?: continue
                        val ch = obj["channel"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
                        val mid = obj["message_id"]?.jsonPrimitive?.longOrNull ?: -1L
                        val data = obj["data"]
                        if (ch == "/__status" && data is JsonObject) {
                            for ((statusCh, statusVal) in data) {
                                val n = statusVal.jsonPrimitive.longOrNull
                                if (n != null && cursors.containsKey(statusCh)) {
                                    cursors[statusCh] = n
                                }
                            }
                            continue
                        }
                        if (mid >= 0L && cursors.containsKey(ch)) {
                            cursors[ch] = mid
                        }
                        handlers[ch]?.forEach { fn ->
                            runCatching { fn(data, mid) }
                        }
                    }
                    return steadyIntervalMs
                }
            }
            consecutiveFailures += 1
            val expFactor = 1L shl (consecutiveFailures - 1).coerceAtMost(4)
            return if (status == 429 || status == 403) {
                min(180_000L, 45_000L * expFactor)
            } else {
                min(90_000L, 18_000L * expFactor)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            consecutiveFailures += 1
            return min(120_000L, 22_000L * consecutiveFailures)
        } finally {
            inFlight = false
        }
    }
}

val discourseMessageBus: DiscourseMessageBusClient by lazy {
    DiscourseMessageBusClient(
        pollTransport = { cid, cursors ->
            LinuxDoRepository.pollMessageBus(cid, cursors)
        }
    )
}
