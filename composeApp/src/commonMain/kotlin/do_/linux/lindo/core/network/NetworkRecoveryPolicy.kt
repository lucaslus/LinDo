package do_.linux.lindo.core.network

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Shared read recovery; Android keeps its own WebView verification lifecycle. */
object NetworkRecoveryPolicy {
    @Volatile var androidEnabled = false
    val enabled: Boolean get() = androidEnabled || DesktopNetworkPolicy.enabled
    internal val reads = ConcurrentHashMap<String, CompletableDeferred<Pair<Int, String>?>>()
    internal suspend fun shareRead(key: String, send: suspend () -> Pair<Int, String>?): Pair<Int, String>? {
        var result: CompletableDeferred<Pair<Int, String>?>
        while (true) {
            result = CompletableDeferred()
            val existing = reads.putIfAbsent(key, result) ?: break
            try { return existing.await() }
            catch (cancelled: CancellationException) {
                // A newer refresh can outlive the cancelled owner of an identical request.
                currentCoroutineContext().ensureActive()
                reads.remove(key, existing)
            }
        }
        try { return send().also { result.complete(it) } }
        catch (error: Throwable) { result.completeExceptionally(error); throw error }
        finally { reads.remove(key, result) }
    }
    internal fun retryAfterMillis(value: String?, now: Long = System.currentTimeMillis()): Long {
        val raw = value?.trim().orEmpty()
        val seconds = raw.toLongOrNull()
        if (seconds != null) return (seconds.coerceIn(0, Long.MAX_VALUE / 1000) * 1000).coerceAtLeast(1_000)
        return runCatching { (ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now).coerceAtLeast(1_000) }.getOrDefault(2_000)
    }
}
