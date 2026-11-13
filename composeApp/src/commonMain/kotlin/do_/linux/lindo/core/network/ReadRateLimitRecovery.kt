package do_.linux.lindo.core.network

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One foreground read probes recovery; background work never queues retries. */
internal class ReadRateLimitRecovery(
    private val remainingMillis: () -> Long,
    private val interruptedResponse: () -> Pair<Int, String>? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val maxAutomaticWaitMillis: Long = 10_000L
) {
    private val recovery = Mutex()
    @Volatile private var retryExhausted = false

    private fun limited() = 429 to """{"errors":["请求较频繁，请稍后再试"]}"""
    private fun isOrdinaryLimit(response: Pair<Int, String>?) =
        response?.first == 429 && !looksLikeCloudflareChallenge(response.second)

    fun reset() { retryExhausted = false }

    suspend fun read(foreground: Boolean, send: suspend () -> Pair<Int, String>?): Pair<Int, String>? {
        interruptedResponse()?.let { return it }
        if (remainingMillis() > 0 || recovery.isLocked) {
            return if (foreground) resume(send) else limited()
        }
        retryExhausted = false
        val response = send()
        return if (foreground && isOrdinaryLimit(response)) resume(send) else response
    }

    private suspend fun resume(send: suspend () -> Pair<Int, String>?): Pair<Int, String>? = recovery.withLock {
        interruptedResponse()?.let { return@withLock it }
        // Other waiters must not each start another retry after the recovery probe failed.
        if (retryExhausted && remainingMillis() > 0) return@withLock limited()
        val started = clock()
        while (true) {
            val remaining = remainingMillis().coerceAtLeast(0L)
            if (remaining == 0L) break
            // A long server cooldown remains enforced, but does not leave the UI spinning indefinitely.
            if (remaining > maxAutomaticWaitMillis - (clock() - started)) return@withLock limited()
            pause(remaining)
            interruptedResponse()?.let { return@withLock it }
        }
        val response = send()
        if (isOrdinaryLimit(response)) retryExhausted = true
        response
    }
}
