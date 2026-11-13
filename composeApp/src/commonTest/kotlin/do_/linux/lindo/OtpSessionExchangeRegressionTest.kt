package do_.linux.lindo

import do_.linux.lindo.core.network.*
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class OtpSessionExchangeRegressionTest {
    @BeforeTest fun reset() {
        UserApiKeyAuthManager.onAuthStateChanged = null
        UserApiKeyAuthManager.onPersistSession = null
        UserApiKeyAuthManager.onPersistPendingKey = null
        UserApiKeyAuthManager.onLogoutPlatformClear = null
        UserApiKeyAuthManager.onSyncCookieToPlatform = null
        UserApiKeyAuthManager.webViewHttpExecutor = null
        UserApiKeyAuthManager.requireBrowserTransportOnly = false
        UserApiKeyAuthManager.logout()
        CookieSentinel.clearAll()
        LinuxDoRepository.clearChallengeCooldown()
        LinuxDoRepository.clearTransientRateLimit()
    }
    @AfterTest fun cleanUp() = reset()

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

    @Test fun twoEmptySession404sClearStaleLocalLogin() = runTest {
        UserApiKeyAuthManager.restorePersistedSession(
            AuthenticatedUserProfile(username="old_user", displayName="Old", trustLevel=1), null, "_t=expired")
        var probes = 0
        UserApiKeyAuthManager.requireBrowserTransportOnly = true
        UserApiKeyAuthManager.webViewHttpExecutor = { url, _, _, _ ->
            assertTrue(url.endsWith("/session/current.json"))
            probes++
            404 to ""
        }
        assertNull(LinuxDoRepository.fetchCurrentUserProfile())
        assertEquals(2, probes)
        assertFalse(UserApiKeyAuthManager.isAuthorized())
    }
}
