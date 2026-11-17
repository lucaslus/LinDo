package do_.linux.lindo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MacBrowserAuthorizationTest {
    @Test fun onlyForumAuthorizationUsesEmbeddedSession() {
        assertTrue(MacBrowserTransport.isAuthorizationUrl("https://linux.do/user-api-key/new?nonce=test"))
        for (url in listOf("https://linux.do/", "http://linux.do/user-api-key/new", "https://linux.do.evil.example/user-api-key/new", "https://linux.do:8443/user-api-key/new", "https://other@linux.do/user-api-key/new")) {
            assertFalse(MacBrowserTransport.isAuthorizationUrl(url), url)
        }
    }
    @Test fun challengeUsesHtmlWithoutApiParameters() {
        do_.linux.lindo.core.network.UserApiKeyAuthManager.logout()
        do_.linux.lindo.core.network.CookieSentinel.clearAll()
        assertEquals("https://linux.do/t/123", MacBrowserTransport.verificationPageUrl("https://linux.do/t/123.json?track_visit=true"))
        assertEquals("https://linux.do/t/topic/123", MacBrowserTransport.verificationPageUrl("https://linux.do/t/topic/123/4.json"))
        assertEquals("https://linux.do/t/123", MacBrowserTransport.verificationPageUrl("https://linux.do/t/123/posts.json?post_ids[]=8"))
        assertEquals("https://linux.do/", MacBrowserTransport.verificationPageUrl("https://linux.do/session/current.json"))
        assertEquals("https://linux.do/", MacBrowserTransport.verificationPageUrl("https://linux.do/notifications.json?offset=0"))
        assertEquals("https://linux.do/", MacBrowserTransport.verificationPageUrl("https://other.example/t/123.json"))
    }
}
