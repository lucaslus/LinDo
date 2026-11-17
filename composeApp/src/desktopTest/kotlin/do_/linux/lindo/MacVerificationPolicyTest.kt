package do_.linux.lindo

import do_.linux.lindo.core.network.CF_MITIGATED_CHALLENGE_PREFIX
import kotlin.test.*

class MacVerificationPolicyTest {
    @Test fun recoveryChecksTheInterruptedReadInsteadOfTheSessionEndpoint() {
        val topic = "https://linux.do/t/123.json?track_visit=true"
        val notifications = "https://linux.do/notifications.json?limit=20"
        assertEquals(topic, MacVerificationPolicy.probeUrl(false, topic))
        assertEquals(notifications, MacVerificationPolicy.probeUrl(false, notifications))
        assertEquals("https://linux.do/session/current.json", MacVerificationPolicy.probeUrl(true, topic))
        // A previous recovery probe must never become the next recovery target.
        assertEquals("https://linux.do/latest.json", MacVerificationPolicy.probeUrl(false, "https://linux.do/session/current.json"))
    }
    @Test fun originalReadSuccessDoesNotDependOnASeparateBlockedSessionProbe() {
        val blockedSession = 429 to "${CF_MITIGATED_CHALLENGE_PREFIX}<html>challenge</html>"
        assertFalse(MacVerificationPolicy.isReadRecovered(blockedSession))
        assertTrue(MacVerificationPolicy.isReadRecovered(200 to """{"post_stream":{"posts":[]}}"""))
        assertTrue(MacVerificationPolicy.isReadRecovered(200 to """{"notifications":[]}"""))
    }
    @Test fun neverTreatChallengeRateLimitOrLoginLossAsSuccess() {
        for (response in listOf(
            200 to "<html>Loading...</html>", 200 to "null", 200 to "",
            200 to "${CF_MITIGATED_CHALLENGE_PREFIX}{}",
            429 to """{"errors":["slow down"]}""",
            401 to """{"errors":["login required"]}""",
            403 to """{"errors":["You need to be logged in to do that."],"error_type":"not_logged_in"}""",
            404 to "",
            503 to "{}"
        )) assertFalse(MacVerificationPolicy.isReadRecovered(response), response.first.toString())
        assertTrue(MacVerificationPolicy.isUnauthenticatedResponse(
            403 to """{"errors":["You need to be logged in to do that."],"error_type":"not_logged_in"}"""
        ))
        assertFalse(MacVerificationPolicy.isUnauthenticatedResponse(403 to """{"errors":["not permitted"],"error_type":"invalid_access"}"""))
        assertFalse(MacVerificationPolicy.isUnauthenticatedResponse(403 to "<html>Just a moment...</html>"))
    }
    @Test fun ordinaryPermissionErrorsStopAskingForVerification() {
        assertTrue(MacVerificationPolicy.isReadRecovered(403 to """{"errors":["not permitted"]}"""))
        assertTrue(MacVerificationPolicy.isReadRecovered(404 to """{"errors":["not found"]}"""))
        assertFalse(MacVerificationPolicy.isReadRecovered(403 to "<html>challenge</html>"))
    }
    @Test fun unsafeAndAuthenticationRoutesAreNotReplayed() {
        for (url in listOf(null, "https://linux.do/session/otp/secret.json", "https://linux.do/user-api-key/new.json", "https://linux.do/logout", "https://linux.do.evil.example/t/1.json", "http://linux.do/t/1.json", "https://user@linux.do/t/1.json")) {
            assertEquals("https://linux.do/latest.json", MacVerificationPolicy.probeUrl(false, url))
        }
    }
    @Test fun recoveryRetryDoesNotSelfBlockAfterVerificationClears() = kotlinx.coroutines.runBlocking {
        val prevExec = do_.linux.lindo.core.network.UserApiKeyAuthManager.webViewHttpExecutor
        val prevRequire = do_.linux.lindo.core.network.UserApiKeyAuthManager.requireBrowserTransportOnly
        val prevEnabled = do_.linux.lindo.core.network.DesktopNetworkPolicy.enabled
        try {
            do_.linux.lindo.core.network.DesktopNetworkPolicy.enabled = true
            do_.linux.lindo.core.network.UserApiKeyAuthManager.requireBrowserTransportOnly = true
            var networkCalls = 0
            do_.linux.lindo.core.network.UserApiKeyAuthManager.webViewHttpExecutor = { _, _, _, _ ->
                networkCalls++
                200 to """{"topic_list":{"topics":[{"id":777,"title":"Recovered topic","posts_count":1,"views":10,"like_count":0,"reply_count":0,"created_at":"2026-11-16T08:00:00Z","last_posted_at":"2026-11-16T08:00:00Z"}]}}"""
            }
            do_.linux.lindo.core.network.DesktopNetworkPolicy.verifying = true
            do_.linux.lindo.ui.PlatformSessionRecovery.verifying = true
            // Clearing both flags synchronously (as finish() + onRetryVerified now do) must let the immediate retry reach the network.
            do_.linux.lindo.core.network.DesktopNetworkPolicy.verifying = false
            do_.linux.lindo.ui.PlatformSessionRecovery.verifying = false
            do_.linux.lindo.core.network.LinuxDoRepository.prepareForManualRecovery()
            val topics = do_.linux.lindo.core.network.LinuxDoRepository.fetchTopics(page = 1)
            assertEquals(1, topics.size)
            assertTrue(networkCalls >= 1, "Expected real network request instead of synthetic 403 challenge")
        } finally {
            do_.linux.lindo.core.network.DesktopNetworkPolicy.enabled = prevEnabled
            do_.linux.lindo.core.network.DesktopNetworkPolicy.verifying = false
            do_.linux.lindo.ui.PlatformSessionRecovery.verifying = false
            do_.linux.lindo.core.network.UserApiKeyAuthManager.webViewHttpExecutor = prevExec
            do_.linux.lindo.core.network.UserApiKeyAuthManager.requireBrowserTransportOnly = prevRequire
        }
    }
}
