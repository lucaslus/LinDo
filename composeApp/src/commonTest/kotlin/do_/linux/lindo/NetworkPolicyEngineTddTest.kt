package do_.linux.lindo

import do_.linux.lindo.core.network.AdaptiveTokenBucket
import do_.linux.lindo.core.network.AttemptBudget
import do_.linux.lindo.core.network.Cf429AdaptiveClassifier
import do_.linux.lindo.core.network.Cf429Decision
import do_.linux.lindo.core.network.CfClearanceAuthority
import do_.linux.lindo.core.network.NetworkPolicyEngine
import do_.linux.lindo.core.network.PriorityGate
import do_.linux.lindo.core.network.RequestPriority
import do_.linux.lindo.core.network.TimeoutBlackholeDetector
import do_.linux.lindo.core.network.VerifyCoordinator
import do_.linux.lindo.core.network.VerifyJoin
import do_.linux.lindo.core.network.VerifyPresentation
import do_.linux.lindo.core.network.SilentProbeSignal
import do_.linux.lindo.core.network.SilentVerifyPolicy
import do_.linux.lindo.core.network.SilentVerifyStep
import do_.linux.lindo.core.network.SilentVerifyTrigger
import do_.linux.lindo.core.network.CF_MITIGATED_CHALLENGE_PREFIX
import do_.linux.lindo.core.network.CookieSentinel
import do_.linux.lindo.core.network.LinuxDoRepository
import do_.linux.lindo.core.network.UserApiKeyAuthManager
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NetworkPolicyEngineTddTest {

    @BeforeTest
    fun setUp() {
        CfClearanceAuthority.reset()
        NetworkPolicyEngine.resetForTesting()
    }

    @AfterTest
    fun tearDown() {
        CfClearanceAuthority.reset()
        NetworkPolicyEngine.resetForTesting()
    }

    @Test
    fun t1_cfClearanceAuthorityV2_noPermanentBlacklist_and_11hNaturalRotation() {
        val t0 = 1_700_000_000_000L
        CfClearanceAuthority.confirmHealthy("cf_good", t0)
        assertTrue(CfClearanceAuthority.hasHealthyIncumbent())

        // FluxDO 2026-08-19 incident replay:
        // cf_good briefly gets challenged once -> opens rotation, then cf_new takes over.
        CfClearanceAuthority.noteChallenged("cf_good")
        assertTrue(CfClearanceAuthority.isChallenged("cf_good"))
        assertEquals("cf_new", CfClearanceAuthority.selectWinner(listOf("cf_good", "cf_new"), verified = true, nowMs = t0 + 1_000L))
        // After verified rotation clears lastChallenged, cf_good must NOT be permanently blacklisted!
        assertFalse(CfClearanceAuthority.isChallenged("cf_good"))

        // Sticky incumbent within 11h stays locked even if another value appears first/last
        assertEquals("cf_new", CfClearanceAuthority.selectWinner(listOf("cf_other", "cf_new"), verified = false, nowMs = t0 + 3_600_000L))

        // After 11h natural rotation window, newer value can rotate cleanly
        val after11h = t0 + CfClearanceAuthority.CF_NATURAL_ROTATION_AGE_MS + 60_000L
        assertEquals("cf_fresh_11h", CfClearanceAuthority.selectWinner(listOf("cf_new", "cf_fresh_11h"), verified = false, nowMs = after11h))

        // Transient empty browser cookie read within 12h preserves healthy incumbent
        assertEquals("cf_fresh_11h", CfClearanceAuthority.selectWinner(emptyList(), verified = false, nowMs = after11h + 5_000L))
    }

    @Test
    fun t2_adaptiveTwoStageCf429Classifier_protectsHealthyIncumbentThenEscalates() {
        val classifier = Cf429AdaptiveClassifier()

        // Healthy incumbent + first 429 challenge -> short cooldown to protect ticket
        val first = classifier.classify(hasHealthyIncumbent = true, nowMs = 10_000L)
        assertTrue(first is Cf429Decision.ShortCooldown, "1st burst with healthy incumbent should short-cooldown, got $first")

        // Second consecutive 429 challenge within 12s -> escalate immediately to single-flight verify!
        val second = classifier.classify(hasHealthyIncumbent = true, nowMs = 13_000L)
        assertEquals(Cf429Decision.EscalateVerify, second)

        // Healthy 2xx resets burst state
        classifier.noteHealthy2xx()
        assertTrue(classifier.classify(hasHealthyIncumbent = true, nowMs = 20_000L) is Cf429Decision.ShortCooldown)

        // Without healthy incumbent -> escalate on very first hit!
        classifier.noteHealthy2xx()
        assertEquals(Cf429Decision.EscalateVerify, classifier.classify(hasHealthyIncumbent = false, nowMs = 30_000L))
    }

    @Test
    fun t3_verifyCoordinator_4TierMonotonic_roundDedup_and_60sIneffectiveBreaker() {
        val coord = VerifyCoordinator()
        val t0 = 100_000L

        val leader = coord.joinOrStart(VerifyPresentation.SILENT, nowMs = t0) as VerifyJoin.Leader
        assertEquals(VerifyPresentation.SILENT, leader.currentPresentation())

        // Escalate monotonically: SILENT -> PILL -> BLOCKING -> MANUAL (never downgrades!)
        val f1 = coord.joinOrStart(VerifyPresentation.PILL, nowMs = t0 + 100L)
        assertTrue(f1 is VerifyJoin.Follower)
        assertEquals(VerifyPresentation.PILL, leader.currentPresentation())

        coord.joinOrStart(VerifyPresentation.SILENT, nowMs = t0 + 150L)
        assertEquals(VerifyPresentation.PILL, leader.currentPresentation())

        coord.joinOrStart(VerifyPresentation.BLOCKING, nowMs = t0 + 200L)
        assertEquals(VerifyPresentation.BLOCKING, leader.currentPresentation())

        // Round-deduplicated failure counting: calling recordRoundFailure 5 times for round 1 counts ONCE!
        coord.finishFlight(leader.round, passed = false, finalPresentation = VerifyPresentation.BLOCKING, nowMs = t0 + 500L)
        repeat(4) { coord.recordRoundFailure(leader.round, nowMs = t0 + 600L) }
        assertEquals(1, coord.snapshot(t0 + 600L).consecutiveFailures)

        // Fail 2 more rounds -> 3 consecutive failures trigger 30s cooldown
        for (expectedRound in 2L..3L) {
            val l = coord.joinOrStart(VerifyPresentation.BLOCKING, nowMs = t0 + expectedRound * 1_000L) as VerifyJoin.Leader
            coord.finishFlight(l.round, passed = false, finalPresentation = VerifyPresentation.BLOCKING, nowMs = t0 + expectedRound * 1_000L + 200L)
        }
        val blocked = coord.joinOrStart(VerifyPresentation.BLOCKING, nowMs = t0 + 4_000L)
        assertTrue(blocked is VerifyJoin.BlockedByCooldown && !blocked.ineffective)

        // MANUAL bypasses cooldown cleanly
        val manual = coord.joinOrStart(VerifyPresentation.MANUAL, nowMs = t0 + 4_100L) as VerifyJoin.Leader
        coord.finishFlight(manual.round, passed = true, finalPresentation = VerifyPresentation.MANUAL, nowMs = t0 + 5_000L)

        // Post-pass immediate challenge -> 60s ineffective clearance breaker
        val remain = coord.recordIneffectiveClearance(manual.round, nowMs = t0 + 6_000L)
        assertTrue(remain >= 55_000L)
        val breakerHit = coord.joinOrStart(VerifyPresentation.BLOCKING, nowMs = t0 + 6_500L)
        assertTrue(breakerHit is VerifyJoin.BlockedByCooldown && breakerHit.ineffective)

        // Silent failure never counts as user failure
        coord.resetOnSessionChange()
        val silent = coord.joinOrStart(VerifyPresentation.SILENT, nowMs = t0 + 10_000L) as VerifyJoin.Leader
        coord.finishFlight(silent.round, passed = false, finalPresentation = VerifyPresentation.SILENT, nowMs = t0 + 10_500L)
        assertEquals(0, coord.snapshot(t0 + 10_600L).consecutiveFailures)
        assertTrue(coord.snapshot(t0 + 10_600L).silentBackoffRemainMs > 0L)
    }

    @Test
    fun t4_aimdTokenBucket_priorityGate_attemptBudget_and_4SourceRetryAfter() = runBlocking {
        // AIMD
        val bucket = AdaptiveTokenBucket(initialRate = 4.0, minRate = 0.8, maxRate = 6.0)
        val before = bucket.currentRate()
        bucket.onTrue429(nowMs = 1_000L)
        assertTrue(bucket.currentRate() < before * 0.65, "429 must multiplicatively reduce rate")
        val after429 = bucket.currentRate()
        bucket.onSuccess2xx()
        assertTrue(bucket.currentRate() > after429, "2xx must additively recover rate")

        // AttemptBudget: idempotent only for transient retries
        val getBudget = AttemptBudget(method = "GET")
        assertTrue(getBudget.canRetryTransient())
        assertTrue(getBudget.consumeTransient())
        val postBudget = AttemptBudget(method = "POST")
        assertFalse(postBudget.canRetryTransient())
        assertFalse(postBudget.consumeTransient())

        // 4-source Retry-After parser
        assertEquals(12_000L, NetworkPolicyEngine.parseRetryAfterMillis("12", null, null, nowMs = 1_700_000_000_000L))
        assertEquals(18_000L, NetworkPolicyEngine.parseRetryAfterMillis(null, null, """{"errors":["操作频繁"],"extras":{"wait_seconds":18}}""", nowMs = 1_700_000_000_000L))
        assertEquals(25_000L, NetworkPolicyEngine.parseRetryAfterMillis(null, null, "请等待 25 秒后再试", nowMs = 1_700_000_000_000L))
        assertEquals(15_000L, NetworkPolicyEngine.parseRetryAfterMillis(null, null, "Please try again in 15 seconds", nowMs = 1_700_000_000_000L))
        assertEquals(9_000L, NetworkPolicyEngine.parseRetryAfterMillis(null, "9", null, nowMs = 1_700_000_000_000L))

        // PriorityGate: HIGH executes before LOW
        val gate = PriorityGate(maxInflight = 1, maxLowInflight = 1)
        val holder = gate.acquire(RequestPriority.NORMAL)
        val order = mutableListOf<String>()
        val lowJob = async {
            val p = gate.acquire(RequestPriority.LOW)
            order += "LOW"
            p.release()
        }
        val highJob = async {
            delay(15L)
            val p = gate.acquire(RequestPriority.HIGH)
            order += "HIGH"
            p.release()
        }
        delay(35L)
        holder.release()
        lowJob.await()
        highJob.await()
        assertEquals(listOf("HIGH", "LOW"), order)
        assertNotEquals(listOf("LOW", "HIGH"), order)
    }

    @Test
    fun t5_p1_callDeadline_csrfCooldown_logoutExemption_and_nearExpiryRefresh() {
        val dl = do_.linux.lindo.core.network.CallDeadline(budgetMs = 28_000L, startedAtMs = 1_000L)
        assertFalse(dl.isExpired(11_000L))
        assertEquals(18_000L, dl.remainingMs(11_000L))

        // Pause during CF checkbox interaction (25s) -> budget must NOT drain!
        dl.pause(11_000L)
        assertFalse(dl.isExpired(36_000L))
        dl.resume(36_000L)
        assertEquals(18_000L, dl.remainingMs(36_000L))
        assertTrue(dl.isExpired(55_000L))

        // CSRF 30s failure cooldown
        assertFalse(NetworkPolicyEngine.isCsrfInCooldown(1_000L))
        NetworkPolicyEngine.noteCsrfRefreshFailed(1_000L)
        assertTrue(NetworkPolicyEngine.isCsrfInCooldown(15_000L))
        assertFalse(NetworkPolicyEngine.isCsrfInCooldown(32_000L))
        NetworkPolicyEngine.noteCsrfRefreshFailed(40_000L)
        NetworkPolicyEngine.clearCsrfCooldown()
        assertFalse(NetworkPolicyEngine.isCsrfInCooldown(41_000L))

        // Logout exemption
        assertTrue(NetworkPolicyEngine.isLogoutExemptFromSessionRecovery("DELETE", "https://linux.do/session/alice"))
        assertFalse(NetworkPolicyEngine.isLogoutExemptFromSessionRecovery("GET", "https://linux.do/session/current.json"))

        // Near-expiry zero-resident silent refresh (>= 10.5h, idle)
        assertFalse(NetworkPolicyEngine.shouldTriggerNearExpirySilentRefresh(ageSecs = 36_000L, activeFgRequests = 0, isVerifying = false))
        assertFalse(NetworkPolicyEngine.shouldTriggerNearExpirySilentRefresh(ageSecs = 38_500L, activeFgRequests = 1, isVerifying = false))
        assertTrue(NetworkPolicyEngine.shouldTriggerNearExpirySilentRefresh(ageSecs = 38_500L, activeFgRequests = 0, isVerifying = false))
    }

    @Test
    fun t6_p2_unifiedNetworkHealthSnapshot_exposesFullDiagnosticState() {
        val t0 = 1_700_000_000_000L
        CfClearanceAuthority.confirmHealthy("cf_live_token", t0)
        NetworkPolicyEngine.apiTokenBucket.onTrue429(t0)
        val snap = NetworkPolicyEngine.buildHealthSnapshot(
            profileName = "lindo_net_1",
            webViewGeneration = 3,
            networkSignature = "wifi:10.0.0.1",
            nowMs = t0 + 120_000L
        )
        assertEquals("lindo_net_1", snap.profileName)
        assertEquals(3, snap.webViewGeneration)
        assertEquals("wifi:10.0.0.1", snap.networkSignature)
        assertTrue(snap.hasHealthyCfClearance)
        assertEquals(120L, snap.cfClearanceAgeSecs)
        assertEquals(1, snap.api429Total)
        assertTrue(snap.toDiagnosticLine().contains("profile=lindo_net_1"))
    }

    @Test
    fun t7_timeoutBlackholeDetector_ignoresBodySlowReads_coalescesSameWave_andEnforcesCooldown() {
        val detector = TimeoutBlackholeDetector(
            waveWindowMs = 4_000L,
            requiredWaves = 2,
            rotateCooldownMs = 300_000L
        )
        // Body read timeout proves socket is alive — must NEVER count as connect blackhole
        assertFalse(detector.recordConnectTimeout("http_fetch_body", nowMs = 10_000L))
        assertEquals(0, detector.waveCount())

        // Two concurrent profile-page GETs failing at the exact same millisecond = 1 wave only!
        assertFalse(detector.recordConnectTimeout("http_fetch", nowMs = 20_000L))
        assertFalse(detector.recordConnectTimeout("http_fetch", nowMs = 20_010L))
        assertEquals(1, detector.waveCount())
        assertFalse(detector.shouldRotateProfile(nowMs = 20_010L))

        // Second independent wave > 4s later confirms genuine connect blackhole
        assertTrue(detector.recordConnectTimeout("http_fetch", nowMs = 25_000L))
        assertEquals(2, detector.waveCount())
        assertTrue(detector.shouldRotateProfile(nowMs = 25_000L))

        // After rotation, 5-minute cooldown prevents back-to-back profile thrashing
        detector.markProfileRotated(nowMs = 25_000L)
        assertEquals(0, detector.waveCount())
        assertFalse(detector.recordConnectTimeout("http_fetch", nowMs = 60_000L))
        assertFalse(detector.recordConnectTimeout("http_fetch", nowMs = 66_000L))
        assertFalse(detector.shouldRotateProfile(nowMs = 66_000L))

        // After cooldown elapses and 2 independent waves occur, rotation is allowed again
        detector.noteHealthyResponse()
        assertEquals(0, detector.waveCount())
        assertFalse(detector.recordConnectTimeout("http_fetch", nowMs = 330_000L))
        assertTrue(detector.recordConnectTimeout("http_fetch", nowMs = 336_000L))
        assertTrue(detector.shouldRotateProfile(nowMs = 336_000L))
    }

    @Test
    fun t8_profileCookieMigrationPolicy_andVpnEffectiveSignature_preventCfCorruptionAndFlapStorms() {
        // Never forge/migrate Cloudflare CHIPS Partitioned cookies across profiles
        assertTrue(NetworkPolicyEngine.isSessionCookieAllowedForProfileMigration("_t"))
        assertTrue(NetworkPolicyEngine.isSessionCookieAllowedForProfileMigration("_forum_session"))
        assertTrue(NetworkPolicyEngine.isSessionCookieAllowedForProfileMigration("authentication_data"))
        assertFalse(NetworkPolicyEngine.isSessionCookieAllowedForProfileMigration("cf_clearance"))
        assertFalse(NetworkPolicyEngine.isSessionCookieAllowedForProfileMigration("__cf_bm"))
        assertFalse(NetworkPolicyEngine.isSessionCookieAllowedForProfileMigration("_cfuvid"))

        // Samsung VPN underlying wifi<->cell oscillations under identical tun0 + DNS collapse to same effective signature
        val wifiVpn = NetworkPolicyEngine.normalizeEffectiveNetworkSignature("wifi,vpn|tun0|198.18.0.2")
        val cellVpn = NetworkPolicyEngine.normalizeEffectiveNetworkSignature("cell,vpn|tun0|198.18.0.2")
        val directWifi = NetworkPolicyEngine.normalizeEffectiveNetworkSignature("wifi|wlan0|223.6.6.6")
        val changedDnsVpn = NetworkPolicyEngine.normalizeEffectiveNetworkSignature("wifi,vpn|tun0|198.18.0.5")
        assertEquals(wifiVpn, cellVpn)
        assertNotEquals(wifiVpn, directWifi)
        assertNotEquals(wifiVpn, changedDnsVpn)

        // Compact verification card height must fit full native Turnstile /challenge without clipping
        val compactH = do_.linux.lindo.ui.computeVerificationCardHeightDp(maxHeightDp = 820f, expanded = false)
        assertTrue(compactH >= 380f, "Compact card height ($compactH) must accommodate native CF /challenge page")
        val crampedH = do_.linux.lindo.ui.computeVerificationCardHeightDp(maxHeightDp = 360f, expanded = false)
        assertEquals(328f, crampedH)
    }

    /**
     * 回归：build 77 真机「删重装 + 网页授权 → 刷几下就弹验证」。
     * 根因 1：静默过盾看到 Turnstile 组件（所有托管挑战都有）550ms 就判“需人工”中止 → 静默几乎从不成功。
     */
    @Test
    fun t9_silentVerify_managedChallengeWidgetIsPending_onlyInteractiveBeginOrBudgetEscalates() {
        val fg = SilentVerifyTrigger.FOREGROUND
        val pre = SilentVerifyTrigger.PREWARM
        // 托管挑战页（含 Turnstile 组件）只是进行中：预算内一律继续等待
        for (t in listOf(200L, 550L, 750L, 2_000L, 5_000L, SilentVerifyPolicy.FOREGROUND_BUDGET_MS - 1)) {
            assertEquals(SilentVerifyStep.Continue, SilentVerifyPolicy.decide(fg, SilentProbeSignal.PENDING, t, appForeground = true))
        }
        assertEquals(SilentVerifyStep.Pass, SilentVerifyPolicy.decide(fg, SilentProbeSignal.PASSED, 300L, appForeground = true))
        // 只有 interactiveBegin 才立即升舱（前台）
        assertEquals(SilentVerifyStep.Escalate("interactive_required"), SilentVerifyPolicy.decide(fg, SilentProbeSignal.INTERACTIVE_REQUIRED, 400L, appForeground = true))
        // 前台预算耗尽：交棒面板继续完成，而不是甩静态错误页
        assertEquals(SilentVerifyStep.Escalate("foreground_budget_exhausted"), SilentVerifyPolicy.decide(fg, SilentProbeSignal.PENDING, SilentVerifyPolicy.FOREGROUND_BUDGET_MS, appForeground = true))
        // 用户点开胶囊
        assertEquals(SilentVerifyStep.Escalate("user_expanded_pill"), SilentVerifyPolicy.decide(fg, SilentProbeSignal.PENDING, 2_500L, appForeground = true, userRequestedPanel = true))
        // 预热永不弹 UI，且不进入退避（Abandon）
        assertEquals(SilentVerifyStep.Abandon("interactive_required_prewarm"), SilentVerifyPolicy.decide(pre, SilentProbeSignal.INTERACTIVE_REQUIRED, 400L, appForeground = true))
        assertEquals(SilentVerifyStep.Continue, SilentVerifyPolicy.decide(pre, SilentProbeSignal.PENDING, 15_000L, appForeground = true))
        assertEquals(SilentVerifyStep.Abandon("prewarm_timeout"), SilentVerifyPolicy.decide(pre, SilentProbeSignal.PENDING, SilentVerifyPolicy.PREWARM_BUDGET_MS, appForeground = true))
        assertEquals(SilentVerifyStep.Continue, SilentVerifyPolicy.decide(pre, SilentProbeSignal.PENDING, 2_500L, appForeground = true, userRequestedPanel = true))
        // 切去系统浏览器授权：放弃但不计失败
        assertEquals(SilentVerifyStep.Abandon("app_backgrounded"), SilentVerifyPolicy.decide(fg, SilentProbeSignal.PENDING, 1_000L, appForeground = false))
        assertEquals(SilentVerifyStep.Fail("webview_gone"), SilentVerifyPolicy.decide(fg, SilentProbeSignal.GONE, 1_000L, appForeground = true))
        // 胶囊：仅前台、≥2s
        assertFalse(SilentVerifyPolicy.shouldShowPill(fg, 1_999L))
        assertTrue(SilentVerifyPolicy.shouldShowPill(fg, 2_000L))
        assertFalse(SilentVerifyPolicy.shouldShowPill(pre, 10_000L))
        // 预算比旧实现（2.6s / 3.8s）宽裕得多
        assertTrue(SilentVerifyPolicy.FOREGROUND_BUDGET_MS >= 8_000L)
        assertTrue(SilentVerifyPolicy.PREWARM_BUDGET_MS >= 15_000L)
    }

    /** 根因 2：预热因切去浏览器授权而中止，却记了 45s 静默退避 → 回来后的第一次真实挑战无法静默续期。 */
    @Test
    fun t10_verifyCoordinator_abandonedFlightDoesNotArmSilentBackoff() {
        val c = VerifyCoordinator()
        val t0 = 1_000_000L
        val r1 = (c.joinOrStart(VerifyPresentation.SILENT, t0) as VerifyJoin.Leader).round
        c.finishFlight(r1, passed = false, finalPresentation = VerifyPresentation.SILENT, nowMs = t0 + 500L, abandoned = true)
        assertEquals(0L, c.snapshot(t0 + 600L).silentBackoffRemainMs)
        assertEquals(0, c.snapshot(t0 + 600L).consecutiveFailures)
        assertTrue(c.joinOrStart(VerifyPresentation.SILENT, t0 + 1_000L) is VerifyJoin.Leader)

        // 真实静默失败仍然退避，防止 /challenge 风暴
        val c2 = VerifyCoordinator()
        val r2 = (c2.joinOrStart(VerifyPresentation.SILENT, t0) as VerifyJoin.Leader).round
        c2.finishFlight(r2, passed = false, finalPresentation = VerifyPresentation.SILENT, nowMs = t0 + 500L)
        assertTrue(c2.joinOrStart(VerifyPresentation.SILENT, t0 + 1_000L) is VerifyJoin.BlockedByCooldown)
    }

    /**
     * 根因 3（日志里 0 条网络错误却直接显示「需要完成站点验证」）：
     * 回前台瞬间的后台请求（timings / 通知轮询）撞盾 → noteChallengeHit() 拉 12s 全局熔断 + 把票据标为失效，
     * 用户紧接着点开帖子被直接短路，连请求都没发。
     */
    @Test
    fun t11_backgroundChallenge_neverArmsGlobalPause_orPoisonsClearance_butRequestsPrewarm() = runBlocking {
        val prevExec = UserApiKeyAuthManager.webViewHttpExecutor
        val prevPrewarm = UserApiKeyAuthManager.onRequestClearancePrewarm
        val prewarmReasons = mutableListOf<String>()
        try {
            LinuxDoRepository.clearChallengeCooldown()
            CookieSentinel.ingestRawCookieString("cf_clearance=cf_live_value", domain = "linux.do")
            CfClearanceAuthority.confirmHealthy("cf_live_value")
            UserApiKeyAuthManager.onRequestClearancePrewarm = { prewarmReasons += it }
            UserApiKeyAuthManager.webViewHttpExecutor = { _, _, _, _ ->
                403 to "${CF_MITIGATED_CHALLENGE_PREFIX}<!DOCTYPE html><title>Just a moment...</title>"
            }
            assertTrue(SilentVerifyPolicy.backgroundChallengeArmsGlobalPause(isBackgroundRequest = false))
            assertFalse(SilentVerifyPolicy.backgroundChallengeArmsGlobalPause(isBackgroundRequest = true))

            runCatching { LinuxDoRepository.fetchTopics(path = "latest.json", background = true) }
            assertFalse(LinuxDoRepository.isChallengeCooldownActive(), "background challenge must not pause foreground traffic")
            assertFalse(CfClearanceAuthority.isChallenged("cf_live_value"), "background challenge must not poison the clearance")
            assertTrue(prewarmReasons.isEmpty(), "healthy cf_clearance must suppress forced background prewarm loop")

            CookieSentinel.removeCookieByName("cf_clearance", domain = "linux.do")
            CfClearanceAuthority.reset()
            runCatching { LinuxDoRepository.fetchTopics(path = "latest.json", background = true) }
            assertTrue("background_challenge" in prewarmReasons)

            // 前台请求撞盾仍然正常进入熔断（交由静默过盾 / 面板处理）
            runCatching { LinuxDoRepository.fetchTopics(path = "latest.json", background = false) }
            assertTrue(LinuxDoRepository.isChallengeCooldownActive())
        } finally {
            UserApiKeyAuthManager.webViewHttpExecutor = prevExec
            UserApiKeyAuthManager.onRequestClearancePrewarm = prevPrewarm
            LinuxDoRepository.clearChallengeCooldown()
            CookieSentinel.removeCookieByName("cf_clearance", domain = "linux.do")
        }
    }
}
