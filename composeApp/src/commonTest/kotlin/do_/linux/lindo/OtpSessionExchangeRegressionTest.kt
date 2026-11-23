package do_.linux.lindo

import do_.linux.lindo.core.network.*
import do_.linux.lindo.ui.PlatformSessionRecovery
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class OtpSessionExchangeRegressionTest {
    @BeforeTest fun reset() {
        DesktopNetworkPolicy.enabled = false
        DesktopNetworkPolicy.verifying = false
        NetworkRecoveryPolicy.androidEnabled = false
        NetworkRecoveryPolicy.androidVerifying = false
        UserApiKeyAuthManager.onAuthStateChanged = null
        UserApiKeyAuthManager.onPersistSession = null
        UserApiKeyAuthManager.onPersistPendingKey = null
        UserApiKeyAuthManager.onLogoutPlatformClear = null
        UserApiKeyAuthManager.onSyncCookieToPlatform = null
        UserApiKeyAuthManager.onPrepareFreshAuthExchange = null
        UserApiKeyAuthManager.webViewHttpExecutor = null
        UserApiKeyAuthManager.requireBrowserTransportOnly = false
        UserApiKeyAuthManager.logout()
        CookieSentinel.clearAll()
        LinuxDoRepository.clearChallengeCooldown()
        LinuxDoRepository.clearTransientRateLimit()
    }
    @AfterTest fun cleanUp() = reset()

    @Test fun explicitOtpCanRecoverAnOldChallengeWithoutReplayingTheOtp() = runTest {
        NetworkRecoveryPolicy.androidEnabled = true
        UserApiKeyAuthManager.requireBrowserTransportOnly = true
        UserApiKeyAuthManager.webViewHttpExecutor = { _, _, _, _ ->
            403 to "${CF_MITIGATED_CHALLENGE_PREFIX}<html>challenge</html>"
        }
        LinuxDoRepository.fetchCurrentUserProfile()
        assertTrue(LinuxDoRepository.isChallengeCooldownActive())
        var exchanges = 0
        var sessionChecks = 0
        UserApiKeyAuthManager.webViewHttpExecutor = { url, method, _, _ ->
            when {
                url.contains("/session/otp/") -> {
                    exchanges++
                    assertEquals("POST", method)
                    CookieSentinel.ingestRawCookieString("_t=new_otp_session", domain = "linux.do", hostOnly = true)
                    200 to "<html>Welcome</html>"
                }
                url.endsWith("/session/current.json") -> {
                    sessionChecks++
                    200 to """{"current_user":{"id":9,"username":"new_user","name":"New","avatar_template":"","trust_level":1}}"""
                }
                else -> 200 to "{}"
            }
        }
        assertTrue(LinuxDoRepository.redeemOtpSilently("one_use"))
        assertEquals(1, exchanges)
        assertEquals(1, sessionChecks)
        assertEquals("new_user", UserApiKeyAuthManager.currentUser?.username)
        assertFalse(LinuxDoRepository.isChallengeCooldownActive())
    }

    @Test fun newCookieAloneDoesNotReportSuccessWhenSessionCheckIsChallenged() = runTest {
        NetworkRecoveryPolicy.androidEnabled = true
        UserApiKeyAuthManager.requireBrowserTransportOnly = true
        var exchanges = 0
        var checks = 0
        UserApiKeyAuthManager.webViewHttpExecutor = { url, _, _, _ ->
            if (url.contains("/session/otp/")) {
                exchanges++
                CookieSentinel.ingestRawCookieString("_t=new_but_unconfirmed", domain = "linux.do", hostOnly = true)
                200 to "<html>Welcome</html>"
            } else {
                checks++
                403 to "${CF_MITIGATED_CHALLENGE_PREFIX}<html>challenge</html>"
            }
        }
        var accepted = false
        assertFalse(LinuxDoRepository.redeemOtpSilently("one_use", onSessionAccepted = { accepted = true }))
        assertTrue(accepted, "Recovery must not replay the already consumed OTP")
        assertEquals(1, exchanges)
        assertEquals(1, checks)
        assertTrue(UserApiKeyAuthManager.isAuthorized(), "Keep the redeemed session for verification, do not log out")
        assertTrue(LinuxDoRepository.isChallengeCooldownActive())
        LinuxDoRepository.fetchCurrentUserProfile()
        assertEquals(1, checks, "Do not repeatedly probe a challenged session")
    }

    /**
     * build 78 真机回归：网页授权跳回 App 后停在登录页，再次登录得到 “Missing, invalid or expired token.”。
     * WebView 桥现以 redirect:'manual' 兑换 OTP（302），服务端已消费 OTP；即使 _t 同步稍晚，
     * 也必须先从浏览器 Cookie 罐同步拉取，且无论结果如何都要报告“已消费”，绝不回放 OTP 页面。
     */
    @Test fun acceptedOtp302SyncsBrowserCookiesAndNeverReportsUnconsumed() = runTest {
        NetworkRecoveryPolicy.androidEnabled = true
        UserApiKeyAuthManager.requireBrowserTransportOnly = true
        var exchanges = 0
        var cookieSyncs = 0
        val prevRefresh = SavedAccounts.refreshBrowserCookies
        try {
            SavedAccounts.refreshBrowserCookies = {
                cookieSyncs++
                CookieSentinel.ingestRawCookieString("_t=late_synced_session", domain = "linux.do", hostOnly = true)
            }
            UserApiKeyAuthManager.webViewHttpExecutor = { url, _, _, _ ->
                when {
                    url.contains("/session/otp/") -> { exchanges++; 302 to "" }
                    url.endsWith("/session/current.json") ->
                        200 to """{"current_user":{"id":7,"username":"late_user","name":"Late","avatar_template":"","trust_level":1}}"""
                    else -> 200 to "{}"
                }
            }
            var accepted = false
            assertTrue(LinuxDoRepository.redeemOtpSilently("one_use_302", onSessionAccepted = { accepted = true }))
            assertTrue(accepted)
            assertEquals(1, exchanges)
            assertTrue(cookieSyncs >= 1)
            assertEquals("late_user", UserApiKeyAuthManager.currentUser?.username)

            UserApiKeyAuthManager.logout()
            CookieSentinel.clearAll()
            SavedAccounts.refreshBrowserCookies = { cookieSyncs++ }
            var acceptedAgain = false
            assertFalse(LinuxDoRepository.redeemOtpSilently("one_use_302_b", onSessionAccepted = { acceptedAgain = true }))
            assertTrue(acceptedAgain, "A server-accepted OTP is consumed and must never be replayed")
        } finally {
            SavedAccounts.refreshBrowserCookies = prevRefresh
        }
    }

    @Test fun explicitOtpStillHonorsServerRateLimit() = runTest {
        NetworkRecoveryPolicy.androidEnabled = true
        UserApiKeyAuthManager.requireBrowserTransportOnly = true
        LinuxDoRepository.noteRetryAfter("60")
        UserApiKeyAuthManager.webViewHttpExecutor = { _, _, _, _ -> error("Must respect Retry-After") }
        assertFalse(LinuxDoRepository.redeemOtpSilently("not_consumed"))
        assertTrue(LinuxDoRepository.isRateLimitedNow())
    }

    @Test fun capturesSessionFrom302WithoutFollowingRedirectOrReusingOtp() = runTest {
        val requests = mutableListOf<String>()
        var persisted = false
        UserApiKeyAuthManager.onSyncCookieToPlatform = { if (it.startsWith("_t=")) persisted = true }
        val client = createOtpExchangeClient(MockEngine { request ->
            requests += request.url.encodedPath
            when (request.url.encodedPath) {
                "/session/csrf.json" -> respond("""{"csrf":"csrf_test"}""", HttpStatusCode.OK,
                    headersOf("Set-Cookie", "_forum_session=csrf_session; Path=/; Secure; HttpOnly"))
                "/session/otp/test_one_use_otp" -> {
                    assertEquals(HttpMethod.Post, request.method)
                    assertEquals("csrf_test", request.headers["X-CSRF-Token"])
                    assertTrue(request.headers["Cookie"].orEmpty().contains("_forum_session=csrf_session"))
                    respond("", HttpStatusCode.Found, headersOf(
                        "Location" to listOf("https://linux.do/"),
                        "Set-Cookie" to listOf("_t=new_session; Path=/; Secure; HttpOnly")))
                }
                else -> error("Must not follow the redirect and lose its Set-Cookie")
            }
        })
        try {
            assertTrue(exchangeOtpSessionCookies("test_one_use_otp", client))
            assertEquals(listOf("/session/csrf.json", "/session/otp/test_one_use_otp"), requests)
            assertEquals("new_session", CookieSentinel.resolveWinningCookies()["_t"]?.value)
            assertTrue(persisted)
        } finally { client.close() }
    }

    @Test fun challengedCsrfDoesNotConsumeOtp() = runTest {
        var count = 0
        val client = createOtpExchangeClient(MockEngine {
            count++
            respond("<html>Just a moment...</html>", HttpStatusCode.Forbidden,
                headersOf("cf-mitigated", "challenge"))
        })
        try {
            assertFalse(exchangeOtpSessionCookies("still_unused", client))
            assertEquals(1, count)
            assertFalse(UserApiKeyAuthManager.isAuthorized())
        } finally { client.close() }
    }

    @Test fun twoEmptySession404sAndGuestDocumentClearStaleLocalLogin() = runTest {
        UserApiKeyAuthManager.restorePersistedSession(
            AuthenticatedUserProfile(username="old_user", displayName="Old", trustLevel=1), null, "_t=expired")
        var probes = 0
        UserApiKeyAuthManager.requireBrowserTransportOnly = true
        UserApiKeyAuthManager.webViewHttpExecutor = { url, _, _, _ ->
            if (url == "https://linux.do/") {
                200 to """<meta name="csrf-token" content="csrf"><script id="data-preloaded" type="application/json">{"site":{}}</script>"""
            } else {
                assertTrue(url.endsWith("/session/current.json"))
                probes++
                404 to ""
            }
        }
        assertNull(LinuxDoRepository.fetchCurrentUserProfile())
        assertEquals(2, probes)
        assertFalse(UserApiKeyAuthManager.isAuthorized())
    }

    @Test fun duplicateTCookiesPreferKnownValidWinnerAndReauthBumpsSessionGeneration() = runTest {
        CookieSentinel.ingestRawCookieString("_t=fresh_host_session", domain = "linux.do", hostOnly = true)
        CookieSentinel.syncFromAuthoritativeBrowserCookieString(
            "_t=dead_domain_session; _t=fresh_host_session; cf_clearance=pass123"
        )
        assertEquals("fresh_host_session", CookieSentinel.resolveWinningCookies()["_t"]?.value)
        assertEquals("pass123", CookieSentinel.resolveWinningCookies()["cf_clearance"]?.value)

        UserApiKeyAuthManager.onSessionAuthenticated(
            AuthenticatedUserProfile(userId = 42, username = "lucas", displayName = "Lucas", trustLevel = 3),
            "_t=old_session"
        )
        val genBefore = UserApiKeyAuthManager.sessionGeneration
        UserApiKeyAuthManager.onSessionAuthenticated(
            AuthenticatedUserProfile(userId = 42, username = "lucas", displayName = "Lucas", trustLevel = 3),
            "_t=fresh_host_session"
        )
        assertTrue(UserApiKeyAuthManager.sessionGeneration > genBefore, "Same-user re-login with new _t must bump sessionGeneration")
        assertTrue(UserApiKeyAuthManager.isAuthorized())
        assertEquals("lucas", UserApiKeyAuthManager.currentUser?.username)
        assertNull(UserApiKeyAuthManager.expiredSessionUsername)
    }

    @Test fun redeemOtpSweepsStaleAuthBeforeExchangingSingleUseOtp() = runTest {
        var preparedBeforeOtp = false
        UserApiKeyAuthManager.requireBrowserTransportOnly = true
        UserApiKeyAuthManager.onPrepareFreshAuthExchange = {
            preparedBeforeOtp = true
            CookieSentinel.clearAuthCookiesOnly()
        }
        CookieSentinel.ingestRawCookieString("_t=dead_old_token; cf_clearance=keep_me", domain = "linux.do", hostOnly = true)
        UserApiKeyAuthManager.webViewHttpExecutor = { url, _, _, _ ->
            when {
                url.contains("/session/otp/") -> {
                    assertTrue(preparedBeforeOtp, "Must purge stale _t variants before spending single-use OTP")
                    assertNull(CookieSentinel.resolveWinningCookies()["_t"])
                    assertEquals("keep_me", CookieSentinel.resolveWinningCookies()["cf_clearance"]?.value)
                    CookieSentinel.ingestRawCookieString("_t=clean_new_session", domain = "linux.do", hostOnly = true)
                    200 to "<html>OK</html>"
                }
                url.endsWith("/session/current.json") -> {
                    200 to """{"current_user":{"id":7,"username":"clean_user","name":"Clean","avatar_template":"","trust_level":2}}"""
                }
                else -> 200 to "{}"
            }
        }
        assertTrue(LinuxDoRepository.redeemOtpSilently("otp_once"))
        assertEquals("clean_new_session", CookieSentinel.resolveWinningCookies()["_t"]?.value)
        assertEquals("keep_me", CookieSentinel.resolveWinningCookies()["cf_clearance"]?.value)
        assertEquals("clean_user", UserApiKeyAuthManager.currentUser?.username)
    }

    @Test fun authStateTickIncrementsOnCookieAndRedirectMutations() = runTest {
        val tick0 = UserApiKeyAuthManager.authStateTick.value
        CookieSentinel.ingestRawCookieString("_t=reactive_cookie_t1", domain = "linux.do", hostOnly = true)
        val tick1 = UserApiKeyAuthManager.authStateTick.value
        assertTrue(tick1 > tick0, "Ingesting _t must bump reactive authStateTick")

        // Repeated identical _t should not churn unnecessarily, removal must bump
        CookieSentinel.removeCookieByName("_t", domain = "linux.do")
        val tick2 = UserApiKeyAuthManager.authStateTick.value
        assertTrue(tick2 > tick1, "Removing _t must bump reactive authStateTick")

        // Generate RSA auth URL and simulate valid discourse://auth_redirect callback
        val (authUrl, nonce) = UserApiKeyAuthManager.generateAuthorizationUrl()
        val encodedPub = java.net.URI(authUrl).rawQuery.split('&')
            .first { it.startsWith("public_key=") }.substringAfter("public_key=")
            .let { java.net.URLDecoder.decode(it, "UTF-8") }
        val pemBody = encodedPub
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\\s+".toRegex(), "")
        val pubSpec = java.security.spec.X509EncodedKeySpec(java.util.Base64.getDecoder().decode(pemBody))
        val pubKey = java.security.KeyFactory.getInstance("RSA").generatePublic(pubSpec)
        val cipher = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding").apply {
            init(javax.crypto.Cipher.ENCRYPT_MODE, pubKey)
        }
        val payloadJson = """{"nonce":"$nonce","one_time_password":"otp_tick_test"}"""
        val encryptedB64 = java.util.Base64.getEncoder().encodeToString(cipher.doFinal(payloadJson.toByteArray()))
        val redirectUri = "discourse://auth_redirect?payload=${java.net.URLEncoder.encode(encryptedB64, "UTF-8")}"

        assertTrue(UserApiKeyAuthManager.handleAuthRedirectUri(redirectUri))
        val tick3 = UserApiKeyAuthManager.authStateTick.value
        assertTrue(tick3 > tick2, "handleAuthRedirectUri setting pendingOtpToken must bump reactive authStateTick")
        assertEquals("otp_tick_test", UserApiKeyAuthManager.consumePendingOtpToken())
    }

    @Test fun otpRejectedByServerHtmlMarksConsumedAndRejectsCleanly() = runTest {
        NetworkRecoveryPolicy.androidEnabled = true
        UserApiKeyAuthManager.requireBrowserTransportOnly = true
        var accepted = false
        var rejected = false
        UserApiKeyAuthManager.webViewHttpExecutor = { url, _, _, _ ->
            assertTrue(url.contains("/session/otp/"))
            200 to "<html><body><div class='alert alert-error'>Missing, invalid or expired token.</div></body></html>"
        }
        assertFalse(
            LinuxDoRepository.redeemOtpSilently(
                otp = "already_used_otp",
                onSessionAccepted = { accepted = true },
                onOtpRejected = { rejected = true }
            )
        )
        assertTrue(rejected, "Rejected OTP already reached Discourse and must invoke onOtpRejected")
        assertFalse(accepted, "Rejected OTP must never invoke onSessionAccepted")
        assertFalse(UserApiKeyAuthManager.isAuthorized())
    }

    @Test fun otpRejectedWith404NotFoundMarksRejectedAndNeverMarksAccepted() = runTest {
        NetworkRecoveryPolicy.androidEnabled = true
        UserApiKeyAuthManager.requireBrowserTransportOnly = true
        var accepted = false
        var rejected = false
        UserApiKeyAuthManager.webViewHttpExecutor = { url, _, _, _ ->
            assertTrue(url.contains("/session/otp/"))
            404 to """{"errors":["The requested URL or resource could not be found."],"error_type":"not_found"}"""
        }
        assertFalse(
            LinuxDoRepository.redeemOtpSilently(
                otp = "invalid_or_expired_otp",
                onSessionAccepted = { accepted = true },
                onOtpRejected = { rejected = true }
            )
        )
        assertTrue(rejected, "HTTP 404 not_found from Discourse /session/otp must mark terminal OTP rejection")
        assertFalse(accepted, "Rejected OTP must never invoke onSessionAccepted")
        assertFalse(UserApiKeyAuthManager.isAuthorized())
    }

    @Test fun coldStartOtpChallengeInvokesVerifySessionAndRetriesCleanly() = runTest {
        NetworkRecoveryPolicy.androidEnabled = true
        UserApiKeyAuthManager.requireBrowserTransportOnly = true
        val prevVerify = PlatformSessionRecovery.verifySession
        var verifyCalled = 0
        var verified = false
        try {
            PlatformSessionRecovery.verifySession = {
                verifyCalled++
                verified = true
                CookieSentinel.ingestRawCookieString("cf_clearance=verified_cf_after_modal", domain = "linux.do")
                LinuxDoRepository.clearChallengeCooldown()
                true
            }
            UserApiKeyAuthManager.webViewHttpExecutor = { url, _, _, _ ->
                if (!verified) {
                    403 to "${CF_MITIGATED_CHALLENGE_PREFIX}<html>Just a moment...</html>"
                } else when {
                    url.contains("/session/otp/") -> {
                        CookieSentinel.ingestRawCookieString("_t=verified_otp_session", domain = "linux.do", hostOnly = true)
                        302 to ""
                    }
                    url.endsWith("/session/current.json") ->
                        200 to """{"current_user":{"id":11,"username":"cf_otp_user","name":"CF User","avatar_template":"","trust_level":2}}"""
                    else -> 200 to "{}"
                }
            }
            var sessionAccepted = false
            var redeemed = LinuxDoRepository.redeemOtpSilently("cf_gated_otp", onSessionAccepted = { sessionAccepted = true })
            assertFalse(redeemed)
            assertFalse(sessionAccepted, "CF challenge occurred before Discourse consumed OTP")
            val verifyHandler = PlatformSessionRecovery.verifySession
            assertNotNull(verifyHandler)
            if (verifyHandler()) {
                redeemed = LinuxDoRepository.redeemOtpSilently("cf_gated_otp", onSessionAccepted = { sessionAccepted = true })
            }
            assertEquals(1, verifyCalled)
            assertTrue(redeemed)
            assertTrue(sessionAccepted)
            assertEquals("cf_otp_user", UserApiKeyAuthManager.currentUser?.username)
        } finally {
            PlatformSessionRecovery.verifySession = prevVerify
        }
    }
}
