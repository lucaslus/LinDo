package do_.linux.lindo

import do_.linux.lindo.core.connect.ScreenTrackEngine
import do_.linux.lindo.core.diagnostics.LinDoDiagnosticLogger
import do_.linux.lindo.core.network.CF_MITIGATED_CHALLENGE_PREFIX
import do_.linux.lindo.core.network.CfClearanceAuthority
import do_.linux.lindo.core.network.CookieSentinel
import do_.linux.lindo.core.network.EchRouteDecision
import do_.linux.lindo.core.network.EchRoutingMode
import do_.linux.lindo.core.network.EchRoutingPolicy
import do_.linux.lindo.core.network.EchRuntimeSnapshot
import do_.linux.lindo.core.network.LinuxDoRepository
import do_.linux.lindo.core.network.NetworkPolicyEngine
import do_.linux.lindo.core.network.SilentVerifyPolicy
import do_.linux.lindo.core.network.UserApiKeyAuthManager
import do_.linux.lindo.core.network.decideEchGatewayRoute
import do_.linux.lindo.core.network.isFakeOrTunIpv4Bytes
import do_.linux.lindo.core.network.isFakeOrTunIpv4String
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
        assertTrue(diag.contains("Version: ${LinDoDiagnosticLogger.APP_VERSION_DISPLAY}"))
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

        assertTrue(EchRoutingMode.SMART_AUTO.labelZh in setOf("智能推荐", "智能模式（推荐）"))
        assertTrue(EchRoutingMode.ECH_PRIORITY.labelZh in setOf("直连优先", "直连优先（穿透代理）"))
        assertTrue(EchRoutingMode.SYSTEM_DEFAULT.labelZh in setOf("系统默认", "系统默认网络"))
    }

    @Test
    fun networkChangeResetsClearanceAuthorityWithoutWashingChallengedTicketAndHealthyClearanceSuppressesNoise() = runTest {
        val incumbent = "cf_incumbent_healthy_123"
        CookieSentinel.syncFromAuthoritativeBrowserCookieString("cf_clearance=$incumbent", verified = true)
        assertTrue(CfClearanceAuthority.hasHealthyIncumbent())

        val prewarmReasons = mutableListOf<String>()
        UserApiKeyAuthManager.requireBrowserTransportOnly = true
        UserApiKeyAuthManager.onRequestClearancePrewarm = { reason ->
            prewarmReasons += reason
        }
        UserApiKeyAuthManager.webViewHttpExecutor = { _, _, _, _ ->
            403 to "<html><title>Just a moment...</title><div id=\"challenge-running\"></div></html>"
        }

        // Single background excerpt challenge MUST NOT fire prewarm when healthy cf_clearance exists
        LinuxDoRepository.fetchTopicExcerpt(999111)
        assertTrue(prewarmReasons.isEmpty(), "Expected no prewarm on 1st hit when cf_clearance exists, got $prewarmReasons")

        // Network/route/profile change MUST call CfClearanceAuthority.reset() rather than washing stale incumbent clean
        CfClearanceAuthority.noteChallenged(incumbent)
        assertTrue(CfClearanceAuthority.isChallenged(incumbent))
        NetworkPolicyEngine.resetOnNetworkChange()
        assertFalse(CfClearanceAuthority.hasHealthyIncumbent(), "Network reset must never resurrect stale incumbent")

        // Browser cookie snapshot carrying _t without cf_clearance also resets CfClearanceAuthority.incumbent
        CfClearanceAuthority.confirmHealthy("ghost_from_previous_profile")
        CookieSentinel.syncFromAuthoritativeBrowserCookieString("_t=valid_session_token_abc")
        assertFalse(CfClearanceAuthority.hasHealthyIncumbent(), "Missing cf_clearance in authoritative _t snapshot must clear incumbent")

        // When cf_clearance is genuinely absent, background challenge triggers prewarm immediately
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
        assertTrue(script.contains("widget:false"), "Expected explicit widget:false on challenge title without rendered Turnstile")
        assertTrue(script.contains("interactive:hasInteractiveSignal"), "Expected interactive signal reporting in probe script")
    }

    @Test
    fun escalatedChallenge429MarkerIsRecognizedAsCloudflareChallenge() {
        val prefixed = "${CF_MITIGATED_CHALLENGE_PREFIX}<html><head><title>Just a moment...</title></head></html>"
        assertTrue(looksLikeCloudflareChallenge(prefixed))
        assertTrue(looksLikeCloudflareChallenge("<html><head><title>Something went wrong</title></head></html>"))
    }

    @Test
    fun smartAutoYieldsToHealthyVpnAndAutomaticallyPromotesToEchOnFakeOrDeadVpn() {
        // Healthy VPN under SMART_AUTO yields cleanly
        assertEquals(
            EchRouteDecision(shouldRun = false, bypassVpn = false, yielded = false),
            decideEchGatewayRoute(
                mode = EchRoutingMode.SMART_AUTO,
                vpnNow = true,
                hasSysProxy = false,
                canBindPhys = true,
                proxyTunnelUnreachable = false
            )
        )
        // Fake/local VPN (e.g. AdGuard) with bypass allowed -> SMART_AUTO promotes to ECH + physical NIC bind
        assertEquals(
            EchRouteDecision(shouldRun = true, bypassVpn = true, yielded = false),
            decideEchGatewayRoute(
                mode = EchRoutingMode.SMART_AUTO,
                vpnNow = true,
                hasSysProxy = false,
                canBindPhys = true,
                proxyTunnelUnreachable = true
            )
        )
        // Fake/local VPN with lockdown (`canBindPhys = false`) -> SMART_AUTO still promotes to ECH over encrypted IP literals
        assertEquals(
            EchRouteDecision(shouldRun = true, bypassVpn = false, yielded = false),
            decideEchGatewayRoute(
                mode = EchRoutingMode.SMART_AUTO,
                vpnNow = true,
                hasSysProxy = false,
                canBindPhys = false,
                proxyTunnelUnreachable = true
            )
        )
        // SYSTEM_DEFAULT never activates ECH even if proxy is unreachable
        assertEquals(
            EchRouteDecision(shouldRun = false, bypassVpn = false, yielded = false),
            decideEchGatewayRoute(
                mode = EchRoutingMode.SYSTEM_DEFAULT,
                vpnNow = true,
                hasSysProxy = true,
                canBindPhys = true,
                proxyTunnelUnreachable = true
            )
        )
    }

    @Test
    fun smartAutoYieldsWhenUpstreamFakeIpDetectedWhileEchPriorityAndUnreachableFallbackStillWork() {
        // 1. Fake-IP classification (RFC 2544 198.18.0.0/15 Clash/Mihomo/Sing-box TUN range)
        assertTrue(isFakeOrTunIpv4Bytes(byteArrayOf(198.toByte(), 18, 1, 183.toByte())))
        assertTrue(isFakeOrTunIpv4Bytes(byteArrayOf(198.toByte(), 19, 255.toByte(), 254.toByte())))
        assertFalse(isFakeOrTunIpv4Bytes(byteArrayOf(198.toByte(), 17, 0, 1)))
        assertFalse(isFakeOrTunIpv4Bytes(byteArrayOf(198.toByte(), 20, 0, 1)))
        assertFalse(isFakeOrTunIpv4Bytes(byteArrayOf(104, 18, 10, 118)))
        assertFalse(isFakeOrTunIpv4Bytes(byteArrayOf(10, 0, 2, 15)))
        assertFalse(isFakeOrTunIpv4Bytes(null))
        assertFalse(isFakeOrTunIpv4Bytes(byteArrayOf(198.toByte(), 18, 1)))

        assertTrue(isFakeOrTunIpv4String("198.18.1.183"))
        assertTrue(isFakeOrTunIpv4String("198.19.0.1"))
        assertFalse(isFakeOrTunIpv4String("198.17.1.1"))
        assertFalse(isFakeOrTunIpv4String("104.18.10.118"))
        assertFalse(isFakeOrTunIpv4String("not-an-ip"))

        // 2. SMART_AUTO yields ECH cleanly when upstream Fake-IP DNS is detected (no Android VPN/http_proxy)
        assertEquals(
            EchRouteDecision(shouldRun = false, bypassVpn = false, yielded = false),
            decideEchGatewayRoute(
                mode = EchRoutingMode.SMART_AUTO,
                vpnNow = false,
                hasSysProxy = false,
                canBindPhys = false,
                proxyTunnelUnreachable = false,
                upstreamFakeIpDetected = true
            )
        )

        // 3. SMART_AUTO promotes back to ECH if the upstream Fake-IP proxy tunnel is confirmed unreachable
        assertEquals(
            EchRouteDecision(shouldRun = true, bypassVpn = false, yielded = false),
            decideEchGatewayRoute(
                mode = EchRoutingMode.SMART_AUTO,
                vpnNow = false,
                hasSysProxy = false,
                canBindPhys = false,
                proxyTunnelUnreachable = true,
                upstreamFakeIpDetected = true
            )
        )

        // 4. Switching off Fake-IP Wi-Fi back to direct cellular re-enables ECH seamlessly under SMART_AUTO
        assertEquals(
            EchRouteDecision(shouldRun = true, bypassVpn = false, yielded = false),
            decideEchGatewayRoute(
                mode = EchRoutingMode.SMART_AUTO,
                vpnNow = false,
                hasSysProxy = false,
                canBindPhys = false,
                proxyTunnelUnreachable = false,
                upstreamFakeIpDetected = false
            )
        )

        // 5. ECH_PRIORITY still forces ECH even when upstream Fake-IP DNS is present
        assertEquals(
            EchRouteDecision(shouldRun = true, bypassVpn = false, yielded = false),
            decideEchGatewayRoute(
                mode = EchRoutingMode.ECH_PRIORITY,
                vpnNow = false,
                hasSysProxy = false,
                canBindPhys = false,
                proxyTunnelUnreachable = false,
                upstreamFakeIpDetected = true
            )
        )

        // 6. Status subtitle & diagnostic line accurately reflect upstream Fake-IP proxy yield
        EchRoutingPolicy.publishSnapshot(
            EchRuntimeSnapshot(
                supported = true,
                active = false,
                mode = EchRoutingMode.SMART_AUTO,
                physicalIface = "wlan0",
                vpnActive = true,
                upstreamFakeIpDetected = true
            )
        )
        assertEquals(
            "检测到代理/VPN · 已自动使用代理通道（关闭代理后自动启用直连）",
            EchRoutingPolicy.statusSubtitle()
        )
        assertTrue(EchRoutingPolicy.toDiagnosticLine().contains("fakeIp=true"))
    }

    @Test
    fun unauthenticatedWelcomeScreenNeverAllowsProactiveColdStartPrewarm() {
        assertFalse(SilentVerifyPolicy.shouldAllowProactivePrewarm(isAuthorized = false, reason = "cold_start"))
        assertFalse(SilentVerifyPolicy.shouldAllowProactivePrewarm(isAuthorized = false, reason = "resume"))
        assertFalse(SilentVerifyPolicy.shouldAllowProactivePrewarm(isAuthorized = false, reason = "route_changed"))
        assertFalse(SilentVerifyPolicy.shouldAllowProactivePrewarm(isAuthorized = false, reason = "background_challenge"))
        assertFalse(SilentVerifyPolicy.shouldAllowProactivePrewarm(isAuthorized = true, reason = ""))

        assertTrue(SilentVerifyPolicy.shouldAllowProactivePrewarm(isAuthorized = true, reason = "cold_start"))
        assertTrue(SilentVerifyPolicy.shouldAllowProactivePrewarm(isAuthorized = true, reason = "resume"))
        assertTrue(SilentVerifyPolicy.shouldAllowProactivePrewarm(isAuthorized = true, reason = "route_changed"))
    }

    @Test
    fun screenTrackEngineKeeps1sUnreadRushWhilePacingSteadyStateFlushesTo60s() {
        ScreenTrackEngine.resetForTest()
        try {
            val t0 = 10_000L
            ScreenTrackEngine.onTopicEntered(topicId = 77001, initialVisiblePosts = setOf(1, 2), nowMs = t0)
            // First unread floors flush immediately at 1.2s via hasUnreadRush
            val rushBatch = ScreenTrackEngine.tick(nowMs = t0 + 1_200L)
            kotlin.test.assertNotNull(rushBatch, "Unread floors must still flush at ~1s via hasUnreadRush")

            // Continued dwell on ALREADY-read floors must NOT flush at 15s or 45s (aligned to 60s official interval)
            var now = t0 + 1_200L
            for (sec in 1..45) {
                now += 1_000L
                ScreenTrackEngine.onUserScrolled(now)
                kotlin.test.assertNull(
                    ScreenTrackEngine.tick(nowMs = now),
                    "Steady-state dwell on already-seen floors should not flush at ${sec}s (< 60s)"
                )
            }
            // At 60s total accumulated dwell, steady-state batch flushes cleanly
            var flushedAt60s = false
            for (sec in 46..61) {
                now += 1_000L
                ScreenTrackEngine.onUserScrolled(now)
                if (ScreenTrackEngine.tick(nowMs = now) != null) {
                    flushedAt60s = true
                    break
                }
            }
            assertTrue(flushedAt60s, "Steady-state dwell must flush once 60s interval is reached")
        } finally {
            ScreenTrackEngine.resetForTest()
        }
    }

    @Test
    fun cookieSentinelPreservesPreviousRotatedTTokenForOneShotFallbackAndProtectsWebViewUa() {
        val gen = UserApiKeyAuthManager.sessionGeneration
        CookieSentinel.ingestRawCookieString("_t=token_v1_good", domain = "linux.do", hostOnly = true)
        CookieSentinel.ingestRawCookieString("_t=token_v2_half_rotated", domain = "linux.do", hostOnly = true)

        assertEquals(
            "token_v1_good",
            CookieSentinel.consumeFallbackTToken(expectedGeneration = gen, failedCurrentT = "token_v2_half_rotated")
        )
        // Single-shot consumption prevents infinite fallback loops
        kotlin.test.assertNull(
            CookieSentinel.consumeFallbackTToken(expectedGeneration = gen, failedCurrentT = "token_v2_half_rotated")
        )

        // Explicit logout / clearAuthCookiesOnly wipes any fallback token
        CookieSentinel.ingestRawCookieString("_t=token_a", domain = "linux.do", hostOnly = true)
        CookieSentinel.ingestRawCookieString("_t=token_b", domain = "linux.do", hostOnly = true)
        CookieSentinel.clearAuthCookiesOnly()
        kotlin.test.assertNull(
            CookieSentinel.consumeFallbackTToken(expectedGeneration = gen, failedCurrentT = "token_b")
        )

        // Native WebView UA must not be overwritten by pasted desktop cURL UA
        val nativeWebViewUa = "Mozilla/5.0 (Linux; Android 16; SM-S9380 Build/BP2A; wv) AppleWebKit/537.36 Chrome/157.0.8103.50 Mobile Safari/537.36"
        UserApiKeyAuthManager.customUserAgent = nativeWebViewUa
        UserApiKeyAuthManager.requireBrowserTransportOnly = true
        val imported = LinuxDoRepository.importCloudflareClearanceInput(
            "curl 'https://linux.do/' -H 'user-agent: Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)' -H 'cookie: cf_clearance=test_cf_123'"
        )
        assertTrue(imported)
        assertEquals(nativeWebViewUa, UserApiKeyAuthManager.customUserAgent)
    }
}
