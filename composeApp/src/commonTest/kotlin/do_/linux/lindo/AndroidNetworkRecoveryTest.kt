package do_.linux.lindo

import do_.linux.lindo.core.network.*
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Exercise Android policy with its browser executor, without enabling Mac behavior. */
class AndroidNetworkRecoveryTest {
    @BeforeTest fun setup() {
        DesktopNetworkPolicy.enabled = false
        NetworkRecoveryPolicy.androidEnabled = true
        UserApiKeyAuthManager.requireBrowserTransportOnly = true
        LinuxDoRepository.clearTransientRateLimit()
        LinuxDoRepository.clearChallengeCooldown()
    }

    @AfterTest fun cleanup() {
        NetworkRecoveryPolicy.androidEnabled = false
        UserApiKeyAuthManager.requireBrowserTransportOnly = false
        UserApiKeyAuthManager.webViewHttpExecutor = null
        LinuxDoRepository.clearTransientRateLimit()
        LinuxDoRepository.clearChallengeCooldown()
    }

    @Test fun androidConcurrentReadsWaitTwoSecondsAndRetryOnlyOnce() = runBlocking {
        val calls = AtomicInteger()
        val times = java.util.Collections.synchronizedList(mutableListOf<Long>())
        UserApiKeyAuthManager.webViewHttpExecutor = { _, _, _, _ ->
            calls.incrementAndGet(); times.add(System.nanoTime()); delay(50); 429 to "{}"
        }
        coroutineScope { List(8) { async { LinuxDoRepository.fetchCurrentUserProfile() } }.awaitAll() }
        assertEquals(2, calls.get())
        assertTrue((times[1] - times[0]) / 1_000_000 >= 2_000)
        LinuxDoRepository.prepareForManualRecovery()
        UserApiKeyAuthManager.notifyNetworkRestored()
        LinuxDoRepository.fetchCurrentUserProfile()
        assertEquals(2, calls.get(), "Refresh and connectivity callbacks must preserve the cooldown")
    }

    @Test fun androidHonorsServerCooldownWithoutStartingItsOldInnerRetryLoop() = runBlocking {
        val calls = AtomicInteger()
        UserApiKeyAuthManager.webViewHttpExecutor = { _, _, _, _ ->
            calls.incrementAndGet(); LinuxDoRepository.noteRetryAfter("60"); 429 to "{}"
        }
        LinuxDoRepository.fetchCurrentUserProfile()
        assertTrue(LinuxDoRepository.isRateLimitedNow())
        LinuxDoRepository.fetchCurrentUserProfile()
        assertEquals(1, calls.get())
    }

    @Test fun androidBackgroundReadsDoNotQueueRetries() = runBlocking {
        val calls = AtomicInteger()
        UserApiKeyAuthManager.webViewHttpExecutor = { _, _, _, _ -> calls.incrementAndGet(); 429 to "{}" }
        repeat(8) { LinuxDoRepository.fetchCurrentUserProfile(background = true) }
        assertEquals(1, calls.get())
    }

    @Test fun androidChallenge429DoesNotEnterRateLimitRetry() = runBlocking {
        val calls = AtomicInteger()
        UserApiKeyAuthManager.webViewHttpExecutor = { _, _, _, _ ->
            calls.incrementAndGet(); 429 to "${CF_MITIGATED_CHALLENGE_PREFIX}<html>challenge</html>"
        }
        LinuxDoRepository.fetchCurrentUserProfile()
        assertEquals(1, calls.get())
    }

    @Test fun androidInFlightSuccessCannotClearAnotherRequestsCooldown() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        UserApiKeyAuthManager.webViewHttpExecutor = { _, _, _, _ ->
            started.complete(Unit); release.await()
            200 to """{"current_user":{"id":1,"username":"android_test","name":"Test","avatar_template":"","trust_level":1}}"""
        }
        val read = async { LinuxDoRepository.fetchCurrentUserProfile() }
        started.await(); LinuxDoRepository.noteRetryAfter("60"); release.complete(Unit)
        read.await()
        assertTrue(LinuxDoRepository.isRateLimitedNow())
    }
}
