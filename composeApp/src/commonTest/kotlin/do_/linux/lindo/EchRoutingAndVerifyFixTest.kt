package do_.linux.lindo

import do_.linux.lindo.core.diagnostics.LinDoDiagnosticLogger
import do_.linux.lindo.core.network.CF_MITIGATED_CHALLENGE_PREFIX
import do_.linux.lindo.core.network.CfClearanceAuthority
import do_.linux.lindo.core.network.CookieSentinel
import do_.linux.lindo.core.network.EchRoutingMode
import do_.linux.lindo.core.network.EchRoutingPolicy
import do_.linux.lindo.core.network.EchRuntimeSnapshot
import do_.linux.lindo.core.network.LinuxDoRepository
import do_.linux.lindo.core.network.NetworkPolicyEngine
import do_.linux.lindo.core.network.UserApiKeyAuthManager
import do_.linux.lindo.core.network.looksLikeCloudflareChallenge
import do_.linux.lindo.core.network.webVerificationProbeScript
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EchRoutingAndVerifyFixTest {

    @BeforeTest
    fun reset() {
        EchRoutingPolicy.resetForTest()
        CookieSentinel.clearAll()
        CfClearanceAuthority.reset()
        NetworkPolicyEngine.resetForTesting()
        LinuxDoRepository.clearChallengeCooldown()
        LinuxDoRepository.clearTransientRateLimit()
        UserApiKeyAuthManager.logout()
        UserApiKeyAuthManager.webViewHttpExecutor = null
        UserApiKeyAuthManager.onRequestClearancePrewarm = null
        UserApiKeyAuthManager.requireBrowserTransportOnly = false
        LinDoDiagnosticLogger.resetForTest()
    }

    @AfterTest
    fun teardown() = reset()

    @Test
    fun echMitmPolicyTargetsForumAndCdnWhileExcludingTurnstileAndThirdParty() {
        assertTrue(EchRoutingPolicy.shouldMitmHost("linux.do"))
        assertTrue(EchRoutingPolicy.shouldMitmHost("LINUX.DO."))
        assertTrue(EchRoutingPolicy.shouldMitmHost("connect.linux.do"))
        assertTrue(EchRoutingPolicy.shouldMitmHost("cdn.ldstatic.com"))
        assertTrue(EchRoutingPolicy.shouldMitmHost("ldstatic.com"))
        assertTrue(EchRoutingPolicy.shouldMitmHost("idcflare.com"))

        assertFalse(EchRoutingPolicy.shouldMitmHost("challenges.cloudflare.com"))
        assertFalse(EchRoutingPolicy.shouldMitmHost("static.cloudflareinsights.com"))
        assertFalse(EchRoutingPolicy.shouldMitmHost("github.com"))
        assertFalse(EchRoutingPolicy.shouldMitmHost(""))
        assertFalse(EchRoutingPolicy.shouldMitmHost(null))
    }

    @Test
    fun echGatewayLeafVerificationIsStrictAndRequiresActiveGatewayAndMatchingMitmHost() {
        var verifierCalls = 0
        EchRoutingPolicy.leafVerifier = { der, host ->
            verifierCalls += 1
            der.contentEquals(byteArrayOf(1, 2, 3)) && host == "linux.do"
        }

        // Inactive gateway rejects even with matching host and bytes
        assertFalse(EchRoutingPolicy.verifyGatewayLeaf(byteArrayOf(1, 2, 3), "linux.do"))
        assertEquals(0, verifierCalls)

        EchRoutingPolicy.publishSnapshot(
            EchRuntimeSnapshot(
                supported = true,
                active = true,
                mode = EchRoutingMode.SMART_AUTO,
                listenPort = 18443,
                stickyIp = "104.16.132.229",
                bypassVpnRequested = false,
                bypassVpnBound = false,
                physicalIface = "wlan0",
                vpnActive = false,
                echHandshakeCount = 4L,
                lastEchAccepted = true
            )
        )

        // Never delegates non-MITM or empty certs
        assertFalse(EchRoutingPolicy.verifyGatewayLeaf(byteArrayOf(), "linux.do"))
        assertFalse(EchRoutingPolicy.verifyGatewayLeaf(byteArrayOf(1, 2, 3), "challenges.cloudflare.com"))
        assertEquals(0, verifierCalls)

        // Valid normalized host passes through to cryptographic verifier
        assertTrue(EchRoutingPolicy.verifyGatewayLeaf(byteArrayOf(1, 2, 3), "LINUX.DO."))
        assertEquals(1, verifierCalls)

        val diag = LinDoDiagnosticLogger.buildExportReport()
        assertTrue(diag.contains("Version: Lindo v0.2.53 · build 87"))
        assertTrue(diag.contains("ECH State    : mode=smart_auto active=true port=18443 stickyIp=104.16.132.229"))
        assertTrue(EchRoutingPolicy.statusSubtitle().contains("104.16.132.229"))
    }

    @Test
    fun echSelectModeUpdatesObservableStateAndNotifiesManagerHook() {
        var captured: EchRoutingMode? = null
        EchRoutingPolicy.onSelectMode = { captured = it }

        EchRoutingPolicy.selectMode(EchRoutingMode.ECH_PRIORITY)
        assertEquals(EchRoutingMode.ECH_PRIORITY, EchRoutingPolicy.mode)
        assertEquals(EchRoutingMode.ECH_PRIORITY, captured)

        assertEquals(EchRoutingMode.SMART_AUTO, EchRoutingMode.fromPref(null))
        assertEquals(EchRoutingMode.SMART_AUTO, EchRoutingMode.fromPref("smart_bypass"))
        assertEquals(EchRoutingMode.ECH_PRIORITY, EchRoutingMode.fromPref("direct_only"))
        assertEquals(EchRoutingMode.SYSTEM_DEFAULT, EchRoutingMode.fromPref("system_default"))
    }

    @Test
    fun existingClearanceIsPreservedAfterMarkRotationOpenAndSuppressesBackgroundPrewarmLoop() = runTest {
        val incumbent = "cf_incumbent_healthy_123"
        CookieSentinel.syncFromAuthoritativeBrowserCookieString("cf_clearance=$incumbent", verified = true)
        CfClearanceAuthority.noteChallenged(incumbent)
        assertTrue(CfClearanceAuthority.isChallenged(incumbent))

        // When /challenge lands directly on origin 404 without minting a replacement,
        // markRotationOpen restores incumbent trust instead of abandoning it as failed.
        CfClearanceAuthority.markRotationOpen()
        assertFalse(CfClearanceAuthority.isChallenged(incumbent))
        CookieSentinel.syncFromAuthoritativeBrowserCookieString("cf_clearance=$incumbent", verified = true)
        assertEquals(
            incumbent,
            CookieSentinel.resolveWinningCookies(targetHost = "linux.do")["cf_clearance"]?.value
        )

        var prewarmReasons = mutableListOf<String>()
        UserApiKeyAuthManager.requireBrowserTransportOnly = true
        UserApiKeyAuthManager.onRequestClearancePrewarm = { reason ->
            prewarmReasons += reason
        }
        UserApiKeyAuthManager.webViewHttpExecutor = { _, _, _, _ ->
            403 to "<html><title>Just a moment...</title><div id=\"challenge-running\"></div></html>"
        }

        // Background excerpt challenge MUST NOT fire prewarm when cf_clearance already exists
        LinuxDoRepository.fetchTopicExcerpt(999111)
        assertTrue(prewarmReasons.isEmpty(), "Expected no prewarm when cf_clearance exists, got $prewarmReasons")

        // When cf_clearance is genuinely absent, background challenge triggers prewarm
        CookieSentinel.clearAll()
        LinuxDoRepository.fetchTopicExcerpt(999112)
        assertTrue(prewarmReasons.isNotEmpty() && prewarmReasons.all { it == "background_challenge" })
    }

    @Test
    fun echStatusSubtitleReportsLockedVpnAutoYieldAndErrno() {
        EchRoutingPolicy.publishSnapshot(
            EchRuntimeSnapshot(
                supported = true,
                active = false,
                mode = EchRoutingMode.SMART_AUTO,
                physicalIface = "wlan0",
                vpnActive = true
            )
        )
        assertEquals(
            "检测到代理/VPN · 已自动使用代理通道（关闭代理后自动启用直连）",
            EchRoutingPolicy.statusSubtitle()
        )

        EchRoutingPolicy.publishSnapshot(
            EchRuntimeSnapshot(
                supported = true,
                active = false,
                mode = EchRoutingMode.ECH_PRIORITY,
                physicalIface = "wlan0",
                vpnActive = true,
                vpnLockdownYielded = true,
                lastBindErrno = 1
            )
        )
        assertEquals(
            "当前 VPN 禁止绕过 · 已自动回退代理通道（在 VPN 分应用绕过 Lindo 可固定直连）",
            EchRoutingPolicy.statusSubtitle()
        )
        val diag = EchRoutingPolicy.toDiagnosticLine()
        assertTrue(diag.contains("vpnYield=true"), "Missing vpnYield in $diag")
        assertTrue(diag.contains("bindErrno=1"), "Missing bindErrno in $diag")
    }

    @Test
    fun verificationProbeChecksChallengeWidgetBeforeLoadingReadyStateGuard() {
        val script = webVerificationProbeScript()
        val challengeIdx = script.indexOf("#challenge-stage")
        val readyLoadingIdx = script.indexOf("document.readyState === 'loading'")
        assertTrue(challengeIdx >= 0, "Expected #challenge-stage selector in probe script")
        assertTrue(readyLoadingIdx > challengeIdx, "Expected #challenge-stage check before document.readyState === 'loading'")
        assertTrue(script.contains("cfErrorPage"), "Expected cfErrorPage detector for broken CF challenge pages")
    }

    @Test
    fun escalatedChallenge429MarkerIsRecognizedAsCloudflareChallenge() {
        val prefixed = "${CF_MITIGATED_CHALLENGE_PREFIX}<html><head><title>Just a moment...</title></head></html>"
        assertTrue(looksLikeCloudflareChallenge(prefixed))
        assertTrue(looksLikeCloudflareChallenge("<html><head><title>Something went wrong</title></head></html>"))
    }
}
