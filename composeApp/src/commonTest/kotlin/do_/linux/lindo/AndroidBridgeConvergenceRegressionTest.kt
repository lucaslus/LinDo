package do_.linux.lindo

import do_.linux.lindo.core.network.ActiveCallDeadlineContext
import do_.linux.lindo.core.network.AndroidBridgeTimeoutBudget
import do_.linux.lindo.core.network.AndroidWebViewBridgeContract
import do_.linux.lindo.core.network.CF_MITIGATED_CHALLENGE_PREFIX
import do_.linux.lindo.core.network.CallDeadline
import do_.linux.lindo.core.network.CfClearanceAuthority
import do_.linux.lindo.core.network.CookieSentinel
import do_.linux.lindo.core.network.LinuxDoRepository
import do_.linux.lindo.core.network.NetworkPolicyEngine
import do_.linux.lindo.core.network.UserApiKeyAuthManager
import do_.linux.lindo.core.network.VerifyTicketOutcome
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidBridgeConvergenceRegressionTest {

    @BeforeTest
    fun setUp() {
        CookieSentinel.clearAll()
        CfClearanceAuthority.reset()
        NetworkPolicyEngine.resetForTesting()
        LinuxDoRepository.clearChallengeCooldown()
        LinuxDoRepository.clearTransientRateLimit()
        UserApiKeyAuthManager.logout()
        UserApiKeyAuthManager.webViewHttpExecutor = null
        UserApiKeyAuthManager.onRequestClearancePrewarm = null
        UserApiKeyAuthManager.requireBrowserTransportOnly = false
    }

    @AfterTest
    fun tearDown() = setUp()

    @Test
    fun bridgeTimeoutMatrixGuaranteesKotlinTimeoutStrictlyExceedsJsHardCapAcrossAllLanes() {
        for (isRead in listOf(true, false)) {
            for (isBg in listOf(true, false)) {
                val budget = AndroidWebViewBridgeContract.computeTimeouts(isRead = isRead, isBackground = isBg)
                assertTrue(
                    budget.kotlinTimeoutMs >= budget.jsHardCapMs + AndroidBridgeTimeoutBudget.MIN_KOTLIN_GUARD_MARGIN_MS,
                    "Expected >=5s guard margin for isRead=$isRead isBg=$isBg, got $budget"
                )
                val singleStageSum = budget.headerTimeoutMs + budget.bodyTimeoutMs
                assertTrue(
                    budget.jsHardCapMs >= singleStageSum,
                    "jsHardCapMs (${budget.jsHardCapMs}) must cover single fetch stage ($singleStageSum)"
                )
            }
        }
        // Regression for 11-23T20:17:26 background PUT /notifications/mark-read.json 14s false kotlin_timeout
        val bgWrite = AndroidWebViewBridgeContract.computeTimeouts(isRead = false, isBackground = true)
        assertTrue(bgWrite.kotlinTimeoutMs >= 20_000L)

        // Inverted budget must fail fast at construction time
        assertFailsWith<IllegalArgumentException> {
            AndroidBridgeTimeoutBudget(
                csrfHeaderTimeoutMs = 4_000L,
                csrfBodyTimeoutMs = 4_000L,
                csrfMaxAttempts = 2,
                headerTimeoutMs = 7_500L,
                bodyTimeoutMs = 5_000L,
                jsHardCapMs = 16_000L,
                kotlinTimeoutMs = 14_000L
            )
        }
    }

    @Test
    fun validLinuxDoDocumentGuardRejectsChromeErrorAndBlankPages() {
        assertTrue(AndroidWebViewBridgeContract.isValidLinuxDoDocumentUrl("https://linux.do"))
        assertTrue(AndroidWebViewBridgeContract.isValidLinuxDoDocumentUrl("https://linux.do/about"))
        assertTrue(AndroidWebViewBridgeContract.isValidLinuxDoDocumentUrl("https://LINUX.DO/challenge"))

        assertFalse(AndroidWebViewBridgeContract.isValidLinuxDoDocumentUrl(null))
        assertFalse(AndroidWebViewBridgeContract.isValidLinuxDoDocumentUrl(""))
        assertFalse(AndroidWebViewBridgeContract.isValidLinuxDoDocumentUrl("about:blank"))
        assertFalse(AndroidWebViewBridgeContract.isValidLinuxDoDocumentUrl("chrome-error://chromewebdata/"))
        assertFalse(AndroidWebViewBridgeContract.isValidLinuxDoDocumentUrl("http://linux.do/about"))
        assertFalse(AndroidWebViewBridgeContract.isValidLinuxDoDocumentUrl("https://evil-linux.do/about"))
    }

    @Test
    fun evaluateVerifyOutcomePreventsWashingFalsifiedClearanceOnUnchallengedOrigin404() {
        // 1. Genuine rotation promotes fresh ticket
        assertEquals(
            VerifyTicketOutcome.ROTATED,
            CfClearanceAuthority.evaluateVerifyOutcome(
                freshClearance = "cf_rotated_new",
                previousClearance = "cf_old_rejected",
                previousWasChallenged = true
            )
        )
        // 2. Unchanged ticket during proactive prewarm (not challenged) stays valid
        assertEquals(
            VerifyTicketOutcome.EXISTING_VALID,
            CfClearanceAuthority.evaluateVerifyOutcome(
                freshClearance = "cf_healthy",
                previousClearance = "cf_healthy",
                previousWasChallenged = false
            )
        )
        // 3. Regression for 11-22T22:51:21: unchanged ticket that WAS just rejected by 403 MUST NOT be washed clean
        assertEquals(
            VerifyTicketOutcome.STALE_UNCHALLENGED_404,
            CfClearanceAuthority.evaluateVerifyOutcome(
                freshClearance = "cf_rejected_by_403",
                previousClearance = "cf_rejected_by_403",
                previousWasChallenged = true
            )
        )
        // 4. Absent clearance
        assertEquals(
            VerifyTicketOutcome.ABSENT,
            CfClearanceAuthority.evaluateVerifyOutcome(
                freshClearance = null,
                previousClearance = "cf_rejected_by_403",
                previousWasChallenged = true
            )
        )
    }

    @Test
    fun consecutiveBackgroundWriteChallengesBreakDeadlockEvenWhenGetsSucceed() = runTest {
        // Regression for 11-23 12:32~20:17 8-hour zombie loop:
        // GET /latest.json returns 200 OK while background PUT /notifications/mark-read.json repeatedly gets 403 challenge.
        val staleIpTicket = "cf_bound_to_yesterday_vpn_ip"
        CookieSentinel.syncFromAuthoritativeBrowserCookieString(
            "_t=valid_session_t; cf_clearance=$staleIpTicket",
            verified = true
        )
        UserApiKeyAuthManager.recordWebSessionLogin(
            username = "tester",
            displayName = "Tester",
            avatarUrl = "",
            trustLevel = 2,
            userId = 7
        )
        UserApiKeyAuthManager.requireBrowserTransportOnly = true

        val prewarmReasons = mutableListOf<String>()
        UserApiKeyAuthManager.onRequestClearancePrewarm = { prewarmReasons += it }
        UserApiKeyAuthManager.webViewHttpExecutor = { url, method, _, _ ->
            if (method.equals("GET", ignoreCase = true)) {
                200 to """{"topic_list":{"topics":[]}}"""
            } else {
                403 to "${CF_MITIGATED_CHALLENGE_PREFIX}<html><title>Just a moment...</title></html>"
            }
        }

        // 1st background write 403: conservative (no immediate poison or prewarm)
        LinuxDoRepository.markSingleNotificationRead(101)
        assertTrue(prewarmReasons.isEmpty(), "1st transient background hit should not immediately prewarm")
        assertFalse(CfClearanceAuthority.isChallenged(staleIpTicket))

        // Interleaved successful GET 200 MUST NOT wipe out consecutiveWriteHits!
        LinuxDoRepository.fetchTopics(path = "/latest.json?no_definitions=true", background = false)
        assertTrue(prewarmReasons.isEmpty())

        // 2nd background write 403: falsifies stale IP ticket and triggers zero-UI prewarm without arming global pause!
        LinuxDoRepository.markSingleNotificationRead(102)
        assertFalse(LinuxDoRepository.isChallengeCooldownActive(), "Background challenge must never block foreground clicks")
        assertTrue(CfClearanceAuthority.isChallenged(staleIpTicket), "2nd consecutive write challenge must falsify stale ticket")
        assertEquals(listOf("background_challenge"), prewarmReasons)
    }

    @Test
    fun physicalTransportExtractorDetectsWifiToCellularUnderStableVpnTunnel() {
        val wifiVpn = "wifi,vpn|tun0|198.18.0.2"
        val cellVpn = "cell,vpn|tun0|198.18.0.2"
        // Tunnel signature stays stable against 77ms multipathing flaps
        assertEquals(
            NetworkPolicyEngine.normalizeEffectiveNetworkSignature(wifiVpn),
            NetworkPolicyEngine.normalizeEffectiveNetworkSignature(cellVpn)
        )
        // Physical transport set distinguishes Wi-Fi vs Cellular for 2.0s sustained handoff recycle
        assertEquals("wifi", NetworkPolicyEngine.extractPhysicalTransportSet(wifiVpn))
        assertEquals("cell", NetworkPolicyEngine.extractPhysicalTransportSet(cellVpn))
        assertEquals("cell,wifi", NetworkPolicyEngine.extractPhysicalTransportSet("cell,wifi,vpn|tun0|198.18.0.2"))
        assertEquals("", NetworkPolicyEngine.extractPhysicalTransportSet("vpn|tun0|198.18.0.2"))
    }

    @Test
    fun activeCallDeadlineContextFreezesRequestBudgetDuringVerificationPause() = runTest {
        val deadline = CallDeadline(budgetMs = 28_000L, startedAtMs = 1_000L)
        withContext(ActiveCallDeadlineContext(deadline)) {
            val ctxDeadline = kotlin.coroutines.coroutineContext[ActiveCallDeadlineContext]?.deadline
            requireNotNull(ctxDeadline)
            ctxDeadline.pause(nowMs = 5_000L)
            // Simulate 36s user interactive Turnstile verification (11-21T14:42:30 elapsed=36214ms)
            assertFalse(ctxDeadline.isExpired(nowMs = 41_214L))
            ctxDeadline.resume(nowMs = 41_214L)
        }
        // Only 4s of active network time was consumed; 24s budget remains for replay!
        assertEquals(24_000L, deadline.remainingMs(nowMs = 41_214L))
    }
}
