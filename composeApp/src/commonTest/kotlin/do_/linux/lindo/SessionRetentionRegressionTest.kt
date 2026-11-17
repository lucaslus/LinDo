package do_.linux.lindo

import do_.linux.lindo.core.network.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SessionRetentionRegressionTest {
    private val user = AuthenticatedUserProfile(userId = 7, username = "reader", displayName = "Reader")
    private val guestPage = """<meta name="csrf-token" content="csrf"><script id="data-preloaded" type="application/json">{"site":{}}</script>"""
    private val userJson = """{"id":7,"username":"reader","name":"Reader"}"""

    @BeforeTest fun reset() {
        SavedAccounts.resetForTest()
        UserApiKeyAuthManager.onPersistSession = null
        UserApiKeyAuthManager.onLogoutPlatformClear = null
        UserApiKeyAuthManager.onAuthStateChanged = null
        UserApiKeyAuthManager.onPrepareManualRecovery = null
        UserApiKeyAuthManager.onSyncCookieToPlatform = null
        UserApiKeyAuthManager.webViewHttpExecutor = null
        UserApiKeyAuthManager.logout()
        CookieSentinel.clearAll()
        LinuxDoRepository.clearChallengeCooldown()
        LinuxDoRepository.clearTransientRateLimit()
        UserApiKeyAuthManager.requireBrowserTransportOnly = true
    }
    @AfterTest fun cleanup() { reset(); UserApiKeyAuthManager.requireBrowserTransportOnly = false }
    private fun login() {
        SavedAccounts.configure(null) {}
        UserApiKeyAuthManager.onSessionAuthenticated(user, "_t=saved-token")
    }

    @Test fun partialBrowserSnapshotRestoresAccountButNeverOldCloudflareTickets() {
        val cookies = sessionCookiesForRestore("theme=dark; cf_clearance=live", "_t=saved; _forum_session=session; cf_clearance=old; __cf_bm=old")
        assertTrue(cookies.contains("_t=saved"))
        assertTrue(cookies.contains("_forum_session=session"))
        assertTrue(cookies.contains("cf_clearance=live"))
        assertFalse(cookies.contains("old"))
        CookieSentinel.clearAll()
        UserApiKeyAuthManager.restorePersistedSession(user, null, cookies)
        assertTrue(UserApiKeyAuthManager.hasValidWebSessionCookie())
        assertEquals("reader", UserApiKeyAuthManager.currentUser?.username)
    }
    @Test fun currentBrowserSessionWinsOverSavedAccountCookies() {
        assertEquals("_t=rotated; _forum_session=new", sessionCookiesForRestore(
            "_t=rotated; _forum_session=new", "_t=stale; _forum_session=old"))
        assertEquals("", sessionCookiesForRestore("", "cf_clearance=expired"))
    }
    @Test fun twoEndpointFailuresDoNotDestroySavedAccountWithoutIndependentGuestEvidence() = runTest {
        login()
        var probes = 0
        UserApiKeyAuthManager.webViewHttpExecutor = { url, _, _, _ ->
            if (url.endsWith("/session/current.json")) { probes++; 404 to "" }
            else 503 to "upstream unavailable"
        }
        assertNull(LinuxDoRepository.fetchCurrentUserProfile())
        assertEquals(2, probes)
        assertTrue(UserApiKeyAuthManager.isAuthorized())
        assertFalse(SavedAccounts.accounts.single().needsLogin)
        assertEquals("saved-token", CookieSentinel.resolveWinningCookies()["_t"]?.value)
    }
    @Test fun authenticatedForumPreloadRecoversWhenSessionEndpointIsUnavailable() = runTest {
        login()
        UserApiKeyAuthManager.webViewHttpExecutor = { url, _, _, _ ->
            when {
                url.endsWith("/session/current.json") -> 404 to ""
                url == "https://linux.do/" -> 200 to """<script id="data-preloaded" type="application/json">{"currentUser":$userJson}</script>"""
                else -> 200 to "{}"
            }
        }
        assertNotNull(LinuxDoRepository.fetchCurrentUserProfile())
        assertEquals("reader", UserApiKeyAuthManager.currentUser?.username)
        assertFalse(SavedAccounts.accounts.single().needsLogin)
    }
    @Test fun actualGuestDocumentStillExpiresAnInvalidSession() = runTest {
        login()
        UserApiKeyAuthManager.webViewHttpExecutor = { url, _, _, _ ->
            if (url == "https://linux.do/") 200 to guestPage else 404 to ""
        }
        assertNull(LinuxDoRepository.fetchCurrentUserProfile())
        assertFalse(UserApiKeyAuthManager.isAuthorized())
        assertTrue(SavedAccounts.accounts.single().needsLogin)
        assertEquals("reader", UserApiKeyAuthManager.expiredSessionUsername)
    }

    @Test fun expiredSessionNoticeClearsOnReauthOrExplicitLogoutAndErrorKindPrioritizesRelogin() = runTest {
        login()
        UserApiKeyAuthManager.handleServerAuthInvalidated()
        assertEquals("reader", UserApiKeyAuthManager.expiredSessionUsername)

        // Even before local isAuthorized flips, explicit not_logged_in / expired copy must offer relogin rather than lacks-trust-level
        assertEquals(
            do_.linux.lindo.core.copy.UiErrorKind.GUEST_LOGIN_REQUIRED,
            do_.linux.lindo.core.copy.UiErrorKind.classify("登录状态已过期，请重新登录", isAuthorized = true)
        )
        assertEquals(
            do_.linux.lindo.core.copy.UiErrorKind.GUEST_LOGIN_REQUIRED,
            do_.linux.lindo.core.copy.UiErrorKind.classify("""{"errors":["您需要登录才能执行此操作。"],"error_type":"not_logged_in"}""", isAuthorized = true)
        )

        UserApiKeyAuthManager.onSessionAuthenticated(user, "_t=fresh-token")
        assertNull(UserApiKeyAuthManager.expiredSessionUsername)

        UserApiKeyAuthManager.handleServerAuthInvalidated()
        assertEquals("reader", UserApiKeyAuthManager.expiredSessionUsername)
        UserApiKeyAuthManager.logout(removeSavedAccount = true)
        assertNull(UserApiKeyAuthManager.expiredSessionUsername)
    }

    @Test fun webViewReadNotLoggedInWithLoggedInHeaderRetriesPublicReadWithoutHeader() = runTest {
        login()
        val seenLoggedInHeaders = mutableListOf<Boolean>()
        UserApiKeyAuthManager.webViewHttpExecutor = { url, _, _, headers ->
            when {
                url.startsWith("https://linux.do/latest.json") -> {
                    val hasLoggedInHdr = headers.containsKey("Discourse-Logged-In")
                    seenLoggedInHeaders += hasLoggedInHdr
                    if (hasLoggedInHdr) {
                        403 to """{"errors":["You need to be logged in to do that."],"error_type":"not_logged_in"}"""
                    } else {
                        200 to """{"users":[],"topic_list":{"topics":[]}}"""
                    }
                }
                else -> 200 to """{"current_user":$userJson}"""
            }
        }
        val topics = LinuxDoRepository.fetchTopics("/latest.json")
        assertTrue(topics.isEmpty())
        assertEquals(listOf(true, false), seenLoggedInHeaders)
    }
    @Test fun guestHtmlDoesNotInvalidateAnApiKeyOnlyAccount() = runTest {
        SavedAccounts.configure(null) {}
        UserApiKeyAuthManager.restorePersistedSession(user, "valid-api-key", "")
        SavedAccounts.captureCurrent()
        UserApiKeyAuthManager.webViewHttpExecutor = { url, _, _, _ ->
            if (url == "https://linux.do/") 200 to guestPage else 404 to ""
        }
        LinuxDoRepository.fetchCurrentUserProfile()
        assertEquals("valid-api-key", UserApiKeyAuthManager.getApiKey())
        assertFalse(SavedAccounts.accounts.single().needsLogin)
    }
    @Test fun challengeDuringConfirmationPreservesTheAccount() = runTest {
        login()
        UserApiKeyAuthManager.webViewHttpExecutor = { url, _, _, _ ->
            if (url == "https://linux.do/") 403 to "<html>cf-browser-verification</html>" else 404 to ""
        }
        LinuxDoRepository.fetchCurrentUserProfile()
        assertTrue(UserApiKeyAuthManager.isAuthorized())
        assertFalse(SavedAccounts.accounts.single().needsLogin)
    }
    @Test fun confirmationWaitsForPlatformCookieRestore() = runTest {
        login()
        var ready = false
        var probes = 0
        UserApiKeyAuthManager.onPrepareManualRecovery = { delay(250); ready = true }
        UserApiKeyAuthManager.webViewHttpExecutor = { url, _, _, _ ->
            if (url.endsWith("/session/current.json")) {
                probes++
                if (probes == 1) 404 to "" else { assertTrue(ready); 200 to """{"current_user":$userJson}""" }
            } else 200 to "{}"
        }
        assertNotNull(LinuxDoRepository.fetchCurrentUserProfile())
        assertEquals(2, probes)
        assertTrue(UserApiKeyAuthManager.isAuthorized())
    }
    @Test fun oldFailureCannotInvalidateNewTokenForSameAccount() = runTest {
        login()
        UserApiKeyAuthManager.webViewHttpExecutor = { _, _, _, _ ->
            CookieSentinel.ingestRawCookieString("_t=new-token")
            404 to ""
        }
        LinuxDoRepository.fetchCurrentUserProfile()
        assertTrue(UserApiKeyAuthManager.isAuthorized())
        assertEquals("new-token", CookieSentinel.resolveWinningCookies()["_t"]?.value)
        assertFalse(SavedAccounts.accounts.single().needsLogin)
    }
    @Test fun preloadParserSupportsLegacyAndRejectsChallengeAndMalformedHtml() {
        val legacy = "<div id='data-preloaded' data-preloaded='{\"currentUser\":$userJson}'></div>"
        val modernAnonGuest = """<!DOCTYPE html><html lang="en" class="text-size-normal anon"><head><meta name="discourse_current_homepage" content="latest"></head><body><div id="data-preloaded" data-preloaded="{&quot;site&quot;:&quot;{}&quot;}"></div></body></html>"""
        assertIs<DiscourseSessionPage.Authenticated>(readDiscourseSessionPage(200, legacy))
        assertEquals(DiscourseSessionPage.Guest, readDiscourseSessionPage(200, guestPage))
        assertEquals(DiscourseSessionPage.Guest, readDiscourseSessionPage(200, modernAnonGuest))
        assertEquals(DiscourseSessionPage.Unknown, readDiscourseSessionPage(200, "<html>Just a moment...</html>"))
        assertEquals(DiscourseSessionPage.Unknown, readDiscourseSessionPage(200, "<div id='data-preloaded' data-preloaded='{}'></div>"))
        assertEquals(DiscourseSessionPage.Unknown, readDiscourseSessionPage(200, "<script id='data-preloaded'>broken</script>"))
        assertEquals(DiscourseSessionPage.Unknown, readDiscourseSessionPage(503, guestPage))
    }
}
