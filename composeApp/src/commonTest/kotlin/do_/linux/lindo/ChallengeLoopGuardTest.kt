package do_.linux.lindo

import do_.linux.lindo.core.copy.isSiteVerificationError
import do_.linux.lindo.core.network.ChallengeLoopGuard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChallengeLoopGuardTest {

    @Test
    fun replayV1114Timeline_armsBackoffImmediatelyOnFirstRecurrence() {
        val guard = ChallengeLoopGuard()
        val t0 = 1_000_000L

        // 19:45:16.449 GET /session/current.json -> 403 challenge
        assertEquals(
            ChallengeLoopGuard.Decision.Verify,
            guard.onForegroundChallenge("https://linux.do/session/current.json", "GET", 403, nowMs = t0)
        )
        // 19:45:17.876 session=1 completed (hasCf=false)
        guard.onVerifyCompleted(effective = false, nowMs = t0 + 1_427L)

        // 19:45:33.391 (+15.5s) GET /t/3262685.json -> 429 challenge -> MUST arm 20s backoff!
        val d2 = guard.onForegroundChallenge("https://linux.do/t/3262685.json?track_visit=true", "GET", 429, nowMs = t0 + 16_942L)
        assertTrue(d2 is ChallengeLoopGuard.Decision.Backoff, "Expected Backoff on recurrence 15.5s after verify, got $d2")
        assertEquals(20_000L, d2.remainMs)
        assertEquals(1, d2.strikes)
        assertTrue(d2.newlyArmed)
        assertFalse(d2.lastVerifyEffective)
        assertTrue(d2.elapsedSinceVerifyMs in 15_000L..16_000L)

        // Subsequent hits within 20s window stay in backoff and do not re-arm or extend
        val d3 = guard.onForegroundChallenge("https://linux.do/emojis.json", "GET", 429, nowMs = t0 + 26_000L)
        assertTrue(d3 is ChallengeLoopGuard.Decision.Backoff)
        assertFalse(d3.newlyArmed)
        assertTrue(d3.remainMs in 10_000L..11_500L)
    }

    @Test
    fun persistentChallengeFor3Minutes_allowsAtMost3Verifications() {
        val guard = ChallengeLoopGuard()
        val start = 5_000_000L
        var verificationsAllowed = 0
        var backoffCount = 0

        for (step in 0..18) { // t = 0s, 10s, ..., 180s (3 minutes)
            val now = start + step * 10_000L
            when (guard.onForegroundChallenge("https://linux.do/discourse-boosts/posts/25379241/boosts.json", "POST", 429, nowMs = now)) {
                ChallengeLoopGuard.Decision.Verify -> {
                    verificationsAllowed += 1
                    guard.onVerifyCompleted(effective = false, nowMs = now + 1_000L)
                }
                is ChallengeLoopGuard.Decision.Backoff -> {
                    backoffCount += 1
                }
            }
        }
        assertTrue(verificationsAllowed <= 3, "Expected <= 3 verifications in 3 minutes, got $verificationsAllowed")
        assertTrue(backoffCount >= 15, "Expected >= 15 backoff decisions, got $backoffCount")
    }

    @Test
    fun pathFamilyNormalization_groupsTopicsAndSeparatesDistinctEndpoints() {
        assertEquals("/t/*", ChallengeLoopGuard.pathFamilyOf("https://linux.do/t/3262685.json?track_visit=true"))
        assertEquals("/t/*", ChallengeLoopGuard.pathFamilyOf("https://linux.do/t/some-slug/3262685/4"))
        assertEquals("/emojis.json", ChallengeLoopGuard.pathFamilyOf("https://linux.do/emojis.json"))
        assertEquals("/discourse-boosts/*", ChallengeLoopGuard.pathFamilyOf("https://linux.do/discourse-boosts/posts/25379241/boosts.json"))
        assertEquals("/session/*", ChallengeLoopGuard.pathFamilyOf("https://linux.do/session/current.json"))
    }

    @Test
    fun shouldFailFast_blocksBackgroundAndSameFamilyOnlyDuringBackoff() {
        val guard = ChallengeLoopGuard()
        val t0 = 10_000_000L
        guard.onForegroundChallenge("https://linux.do/t/100.json", "GET", 429, nowMs = t0)
        guard.onVerifyCompleted(effective = false, nowMs = t0 + 1_000L)
        guard.onForegroundChallenge("https://linux.do/t/200.json", "GET", 429, nowMs = t0 + 5_000L)

        assertTrue(guard.isBackingOff(t0 + 6_000L))
        // Background requests always fail fast during backoff
        assertTrue(guard.shouldFailFast("https://linux.do/message-bus/abc/poll?dlp=t", isBackground = true, nowMs = t0 + 6_000L))
        assertTrue(guard.shouldFailFast("https://linux.do/topics/timings", isBackground = true, nowMs = t0 + 6_000L))
        // Foreground same path family fails fast
        assertTrue(guard.shouldFailFast("https://linux.do/t/999.json?track_visit=true", isBackground = false, nowMs = t0 + 6_000L))
        // Foreground other path family stays allowed
        assertFalse(guard.shouldFailFast("https://linux.do/latest.json?no_definitions=true", isBackground = false, nowMs = t0 + 6_000L))
        assertFalse(guard.shouldFailFast("https://linux.do/notifications.json", isBackground = false, nowMs = t0 + 6_000L))

        // After 20s backoff expires, nothing fails fast
        val after = t0 + 26_000L
        assertFalse(guard.isBackingOff(after))
        assertFalse(guard.shouldFailFast("https://linux.do/t/999.json", isBackground = false, nowMs = after))
        assertFalse(guard.shouldFailFast("https://linux.do/topics/timings", isBackground = true, nowMs = after))
    }

    @Test
    fun onHealthyResponse_resetsStrikesOutsideBackoffOnly() {
        val guard = ChallengeLoopGuard()
        val t0 = 20_000_000L
        guard.onForegroundChallenge("https://linux.do/t/100.json", "GET", 429, nowMs = t0)
        guard.onVerifyCompleted(effective = true, nowMs = t0 + 1_000L)
        val b1 = guard.onForegroundChallenge("https://linux.do/t/100.json", "GET", 429, nowMs = t0 + 3_000L) as ChallengeLoopGuard.Decision.Backoff
        assertEquals(20_000L, b1.remainMs)

        // Healthy response during backoff must NOT clear backoff or strikes
        guard.onHealthyResponse("https://linux.do/t/100.json", nowMs = t0 + 10_000L)
        assertTrue(guard.isBackingOff(t0 + 10_000L))

        // After backoff expires, healthy response on same family clears strikes & target
        val after = t0 + 25_000L
        guard.onHealthyResponse("https://linux.do/t/200.json", nowMs = after)
        assertNull(guard.currentTarget(after))

        // Next recurrence starts fresh at strike 1 (20s), not strike 2 (60s)
        assertEquals(ChallengeLoopGuard.Decision.Verify, guard.onForegroundChallenge("https://linux.do/t/300.json", "GET", 429, nowMs = after + 1_000L))
        guard.onVerifyCompleted(effective = false, nowMs = after + 2_000L)
        val bFresh = guard.onForegroundChallenge("https://linux.do/t/300.json", "GET", 429, nowMs = after + 4_000L) as ChallengeLoopGuard.Decision.Backoff
        assertEquals(1, bFresh.strikes)
        assertEquals(20_000L, bFresh.remainMs)
    }

    @Test
    fun navigationUrlFor_rewritesMethodsQueriesAndExcludesFallbackPaths() {
        assertEquals(
            "https://linux.do/t/3262685.json?track_visit=false#lindo-verify",
            ChallengeLoopGuard.navigationUrlFor("https://linux.do/t/3262685.json?track_visit=true", "GET")
        )
        assertEquals(
            "https://linux.do/latest.json?no_definitions=true#lindo-verify",
            ChallengeLoopGuard.navigationUrlFor("https://linux.do/latest.json?no_definitions=true", "GET")
        )
        assertEquals(
            "https://linux.do/discourse-boosts/posts/25379241/boosts.json#lindo-verify",
            ChallengeLoopGuard.navigationUrlFor("https://linux.do/discourse-boosts/posts/25379241/boosts.json?foo=1", "POST")
        )
        assertNull(ChallengeLoopGuard.navigationUrlFor("https://linux.do/", "GET"))
        assertNull(ChallengeLoopGuard.navigationUrlFor("https://linux.do/challenge", "GET"))
        assertNull(ChallengeLoopGuard.navigationUrlFor("https://linux.do/challenge#lindo-verify", "GET"))
        assertNull(ChallengeLoopGuard.navigationUrlFor("https://linux.do/message-bus/abc/poll?dlp=t", "POST"))
        assertNull(ChallengeLoopGuard.navigationUrlFor("https://example.com/t/1.json", "GET"))
    }

    @Test
    fun strikeDecayAfter5Minutes_resetsBackoffTo20sAndMessageNeverTriggersVerifyButton() {
        val guard = ChallengeLoopGuard()
        val t0 = 30_000_000L
        guard.onForegroundChallenge("https://linux.do/emojis.json", "GET", 429, nowMs = t0)
        guard.onVerifyCompleted(effective = false, nowMs = t0 + 1_000L)
        val b1 = guard.onForegroundChallenge("https://linux.do/emojis.json", "GET", 429, nowMs = t0 + 5_000L) as ChallengeLoopGuard.Decision.Backoff
        assertEquals(20_000L, b1.remainMs)

        // Wait > 5 minutes (300s) without recurrence
        val later = t0 + 5_000L + 305_000L
        assertEquals(ChallengeLoopGuard.Decision.Verify, guard.onForegroundChallenge("https://linux.do/emojis.json", "GET", 429, nowMs = later))
        guard.onVerifyCompleted(effective = false, nowMs = later + 1_000L)
        val bAfterDecay = guard.onForegroundChallenge("https://linux.do/emojis.json", "GET", 429, nowMs = later + 5_000L) as ChallengeLoopGuard.Decision.Backoff
        assertEquals(1, bAfterDecay.strikes)
        assertEquals(20_000L, bAfterDecay.remainMs)

        val msg = ChallengeLoopGuard.backoffMessage(bAfterDecay.remainMs)
        assertEquals("站点暂时限制访问，约 20 秒后自动恢复", msg)
        assertFalse(msg.isSiteVerificationError(), "Backoff message must NOT trigger UI '去验证' button")
    }
}
