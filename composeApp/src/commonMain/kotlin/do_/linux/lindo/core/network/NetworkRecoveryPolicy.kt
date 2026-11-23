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
    @Volatile var androidVerifying = false
    val verifying: Boolean get() = androidVerifying || DesktopNetworkPolicy.verifying
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
    internal fun retryAfterMillis(
        value: String?,
        now: Long = System.currentTimeMillis(),
        rateLimitReset: String? = null,
        bodyText: String? = null
    ): Long = NetworkPolicyEngine.parseRetryAfterMillis(
        retryAfterHeader = value,
        rateLimitResetHeader = rateLimitReset,
        bodyText = bodyText,
        nowMs = now
    )
}
