package do_.linux.lindo

import do_.linux.lindo.core.network.*
import do_.linux.lindo.ui.communityMembershipDays
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import kotlin.test.*

class CommunityDataTest {
    @BeforeTest fun reset() {
        UserApiKeyAuthManager.webViewHttpExecutor = null
        UserApiKeyAuthManager.requireBrowserTransportOnly = false
        UserApiKeyAuthManager.onAuthStateChanged = null
        UserApiKeyAuthManager.onLogoutPlatformClear = null
        UserApiKeyAuthManager.onPersistSession = null
        UserApiKeyAuthManager.onSyncCookieToPlatform = null
        UserApiKeyAuthManager.logout()
        CookieSentinel.clearAll()
        LinuxDoRepository.clearChallengeCooldown()
        LinuxDoRepository.clearTransientRateLimit()
        NetworkRecoveryPolicy.androidEnabled = false
        DesktopNetworkPolicy.enabled = false
    }
    @AfterTest fun cleanup() = reset()

    @Test fun membershipUsesCalendarDaysAndRejectsInvalidOrFutureDates() {
        val today = LocalDate.parse("2026-11-16")
        assertEquals(1L, communityMembershipDays("2026-11-16", today))
        assertEquals(2L, communityMembershipDays("2026-11-15", today))
        assertEquals(3L, communityMembershipDays("2024-02-28", LocalDate.parse("2024-03-01")))
        assertNull(communityMembershipDays("", today))
        assertNull(communityMembershipDays("2026-11-17", today))
    }

    @Test fun exportQueuesExactlyOneOfficialArchiveAndDoesNotHideQuotaErrors() = runTest {
        UserApiKeyAuthManager.requireBrowserTransportOnly = true
        var calls = 0
        var quota = false
        UserApiKeyAuthManager.webViewHttpExecutor = { url, method, form, _ ->
            calls++
            assertEquals("https://linux.do/export_csv/export_entity.json", url)
            assertEquals("POST", method)
            assertEquals(mapOf("entity" to "user_archive"), form)
            if (quota) 200 to """{"errors":["每天只能导出一次"]}"""
            else 200 to """{"success":"OK"}"""
        }
        LinuxDoRepository.requestUserArchive()
        assertEquals(1, calls)
        quota = true
        val error = assertFailsWith<ApiMutationException> { LinuxDoRepository.requestUserArchive() }
        assertEquals("每天只能导出一次", error.message)
        assertEquals(2, calls, "a failed export must not automatically replay")
    }

    @Test fun profileUsesServerTotalsAndPreservesUnknownInsteadOfDisplayingZero() = runTest {
        UserApiKeyAuthManager.requireBrowserTransportOnly = true
        var summaryPresent = true
        UserApiKeyAuthManager.webViewHttpExecutor = { url, _, _, _ ->
            when {
                url.endsWith("/session/current.json") -> 200 to """{"current_user":{"id":9,"username":"reader"}}"""
                url.endsWith("/u/reader.json") -> 200 to """{"user":{"id":9,"username":"reader","created_at":"2020-01-01T23:00:00Z","gamification_score":1234}}"""
                url.endsWith("/summary.json") -> if (summaryPresent) 200 to """{"user_summary":{"days_visited":42}}""" else 503 to "{}"
                else -> 200 to "{}"
            }
        }
        val full = assertNotNull(LinuxDoRepository.fetchCurrentUserProfile())
        assertEquals(42, full.reportedDaysVisited)
        assertEquals(1234L, full.communityScore)
        summaryPresent = false
        val partial = assertNotNull(LinuxDoRepository.fetchCurrentUserProfile())
        assertNull(partial.reportedDaysVisited)
    }
}
