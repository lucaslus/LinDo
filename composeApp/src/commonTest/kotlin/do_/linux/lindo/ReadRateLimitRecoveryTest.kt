package do_.linux.lindo

import do_.linux.lindo.core.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ReadRateLimitRecoveryTest {
    @Test fun aNewRefreshSurvivesCancellationOfTheOriginalSharedRead() = runTest {
        val owner = async(start = CoroutineStart.UNDISPATCHED) {
            NetworkRecoveryPolicy.shareRead("cancel-owner") { awaitCancellation() }
        }
        val follower = async(start = CoroutineStart.UNDISPATCHED) {
            NetworkRecoveryPolicy.shareRead("cancel-owner") { 200 to "new refresh" }
        }
        owner.cancelAndJoin()
        assertEquals(200 to "new refresh", follower.await())
        assertFalse(NetworkRecoveryPolicy.reads.containsKey("cancel-owner"))
    }

    @Test fun cancellingOneWaiterDoesNotCancelTheSharedRead() = runTest {
        val release = CompletableDeferred<Unit>()
        val owner = async(start = CoroutineStart.UNDISPATCHED) {
            NetworkRecoveryPolicy.shareRead("cancel-waiter") { release.await(); 200 to "original" }
        }
        val follower = async(start = CoroutineStart.UNDISPATCHED) {
            NetworkRecoveryPolicy.shareRead("cancel-waiter") { error("Must reuse the original read") }
        }
        follower.cancelAndJoin()
        release.complete(Unit)
        assertEquals(200 to "original", owner.await())
    }
    @Test fun foregroundStaysPendingThenRetriesOnceAfterTwoSeconds() = runTest {
        var until = 0L
        var calls = 0
        val gate = ReadRateLimitRecovery({ until - testScheduler.currentTime }, clock = { testScheduler.currentTime })
        val pending = async {
            gate.read(true) {
                calls++
                if (calls == 1) { until = testScheduler.currentTime + 2_000; 429 to "{}" }
                else 200 to "success"
            }
        }
        runCurrent()
        assertFalse(pending.isCompleted)
        advanceTimeBy(1_999)
        assertEquals(1, calls)
        advanceUntilIdle()
        assertEquals(200, pending.await()?.first)
        assertEquals(2_000, testScheduler.currentTime)
        assertEquals(2, calls)
    }

    @Test fun concurrentReadersShareOneRecoveryProbeAndStopAfterAnother429() = runTest {
        var until = 0L
        var calls = 0
        val gate = ReadRateLimitRecovery({ until - testScheduler.currentTime }, clock = { testScheduler.currentTime })
        val readers = List(8) { async {
            gate.read(true) { calls++; until = testScheduler.currentTime + 2_000; 429 to "{}" }
        } }
        advanceUntilIdle()
        readers.forEach { assertEquals(429, it.await()?.first) }
        assertEquals(2, calls, "One initial read and one probe, not eight separate retry loops")
        gate.read(true) { calls++; 200 to "should not send" }
        assertEquals(2, calls, "Repeated refresh must not restart retries during the cooldown")
    }

    @Test fun aLongServerCooldownIsNotShortenedOrTurnedIntoAnEndlessSpinner() = runTest {
        var until = 0L
        var calls = 0
        val gate = ReadRateLimitRecovery({ until - testScheduler.currentTime }, clock = { testScheduler.currentTime })
        assertEquals(429, gate.read(true) { calls++; until = 60_000; 429 to "{}" }?.first)
        assertEquals(1, calls)
        assertEquals(0, testScheduler.currentTime)
        assertEquals(60_000, until)
    }

    @Test fun backgroundWorkDoesNotRetryOrWait() = runTest {
        var until = 0L
        var calls = 0
        val gate = ReadRateLimitRecovery({ until - testScheduler.currentTime }, clock = { testScheduler.currentTime })
        repeat(8) { gate.read(false) { calls++; until = 2_000; 429 to "{}" } }
        assertEquals(1, calls)
        assertEquals(0, testScheduler.currentTime)
    }

    @Test fun cancellingNavigationCancelsThePendingRetry() = runTest {
        var until = 0L
        var calls = 0
        val gate = ReadRateLimitRecovery({ until - testScheduler.currentTime }, clock = { testScheduler.currentTime })
        val job = launch { gate.read(true) { calls++; until = 2_000; 429 to "{}" } }
        runCurrent()
        job.cancelAndJoin()
        advanceUntilIdle()
        assertEquals(1, calls)
    }

    @Test fun cloudflare429IsNotTreatedAsAnOrdinaryRateLimit() = runTest {
        var calls = 0
        val gate = ReadRateLimitRecovery({ 0 }, clock = { testScheduler.currentTime })
        val response = gate.read(true) { calls++; 429 to "${CF_MITIGATED_CHALLENGE_PREFIX}<html>challenge</html>" }
        assertTrue(looksLikeCloudflareChallenge(response!!.second))
        assertEquals(1, calls)
        assertEquals(0, testScheduler.currentTime)
    }

    @Test fun aChallengeDetectedWhileWaitingStopsTheRecoveryProbe() = runTest {
        var until = 0L
        var challenged = false
        var calls = 0
        val gate = ReadRateLimitRecovery(
            { until - testScheduler.currentTime },
            { if (challenged) 403 to "${CF_MITIGATED_CHALLENGE_PREFIX}<html>challenge</html>" else null },
            clock = { testScheduler.currentTime }
        )
        val job = async { gate.read(true) { calls++; until = 2_000; 429 to "{}" } }
        runCurrent(); challenged = true
        advanceUntilIdle()
        assertEquals(403, job.await()?.first)
        assertEquals(1, calls)
    }
}
