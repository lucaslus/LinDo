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
        assertIs<DiscourseSessionPage.Authenticated>(readDiscourseSessionPage(200, legacy))
        assertEquals(DiscourseSessionPage.Guest, readDiscourseSessionPage(200, guestPage))
        assertEquals(DiscourseSessionPage.Unknown, readDiscourseSessionPage(200, "<html>Just a moment...</html>"))
        assertEquals(DiscourseSessionPage.Unknown, readDiscourseSessionPage(200, "<script id='data-preloaded'>broken</script>"))
        assertEquals(DiscourseSessionPage.Unknown, readDiscourseSessionPage(503, guestPage))
    }
}
