package do_.linux.lindo

import do_.linux.lindo.core.model.UserNotificationItem
import do_.linux.lindo.core.network.CF_MITIGATED_CHALLENGE_PREFIX
import do_.linux.lindo.core.network.CookieSentinel
import do_.linux.lindo.core.network.DesktopNetworkPolicy
import do_.linux.lindo.core.network.LinuxDoRepository
import do_.linux.lindo.core.network.NetworkRecoveryPolicy
import do_.linux.lindo.core.network.NotificationReadCoordinator
import do_.linux.lindo.core.network.UserApiKeyAuthManager
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class NotificationAndLoginRefactorTest {

    private fun notif(
        id: Int,
        read: Boolean = false,
        topicId: Int = 100,
        postNumber: Int = 2,
        notificationType: Int = 2,
        count: Int = 0
    ) = UserNotificationItem(
        id = id,
        notificationType = notificationType,
        read = read,
        createdAtText = "2026-11-21T10:00:00Z",
        topicId = topicId,
        postNumber = postNumber,
        title = "Notification #$id",
        count = count
    )

    @BeforeTest
    fun reset() {
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
        UserApiKeyAuthManager.onRequestClearancePrewarm = null
        UserApiKeyAuthManager.webViewHttpExecutor = null
        UserApiKeyAuthManager.requireBrowserTransportOnly = false
        UserApiKeyAuthManager.logout()
        CookieSentinel.clearAll()
        NotificationReadCoordinator.clear()
        LinuxDoRepository.clearUserSessionCaches()
        LinuxDoRepository.clearChallengeCooldown()
        LinuxDoRepository.clearTransientRateLimit()
    }

    @AfterTest
    fun cleanup() = reset()

    @Test
    fun acknowledgeSingleReadKeepsOverlayedItemReadUntilServerConfirmsAndClearsOnConfirm() {
        NotificationReadCoordinator.ensureOwner("alice")
        assertTrue(NotificationReadCoordinator.acknowledgeSingleRead(101))
        assertEquals(listOf(101), NotificationReadCoordinator.snapshotPendingIds())

        val overlayed = NotificationReadCoordinator.overlayNotifications(
            items = listOf(notif(id = 101, read = false), notif(id = 102, read = false)),
            confirmedFloorForTopic = { 0 }
        )
        assertTrue(overlayed.first { it.id == 101 }.read)
        assertFalse(overlayed.first { it.id == 102 }.read)

        NotificationReadCoordinator.onServerMarkReadConfirmed(101)
        assertTrue(NotificationReadCoordinator.snapshotPendingIds().isEmpty())
        assertTrue(NotificationReadCoordinator.isLocallyRead(101))

        val secondOverlay = NotificationReadCoordinator.overlayNotifications(
            items = listOf(notif(id = 101, read = false)),
            confirmedFloorForTopic = { 0 }
        )
        assertTrue(secondOverlay.single().read)
    }

    @Test
    fun confirmedTopicFloorAutomaticallyMarksNotificationReadAndQueuesServerSync() {
        NotificationReadCoordinator.ensureOwner("alice")
        val items = listOf(
            notif(id = 201, read = false, topicId = 77, postNumber = 8, notificationType = 2, count = 1),
            notif(id = 202, read = false, topicId = 77, postNumber = 12, notificationType = 2, count = 1),
            notif(id = 203, read = false, topicId = 77, postNumber = 2, notificationType = 5, count = 1),
            notif(id = 204, read = false, topicId = 77, postNumber = 2, notificationType = 25, count = 1),
            notif(id = 205, read = false, topicId = 77, postNumber = 2, notificationType = 2, count = 3)
        )
        val overlayed = NotificationReadCoordinator.overlayNotifications(
            items = items,
            confirmedFloorForTopic = { topicId -> if (topicId == 77) 10 else 0 }
        )
        assertTrue(overlayed.first { it.id == 201 }.read)
        assertFalse(overlayed.first { it.id == 202 }.read)
        assertFalse(overlayed.first { it.id == 203 }.read, "Likes must not be auto-marked read by topic floor")
        assertFalse(overlayed.first { it.id == 204 }.read, "Reactions must not be auto-marked read by topic floor")
        assertFalse(overlayed.first { it.id == 205 }.read, "Consolidated replies must not be auto-marked read by topic floor")
        assertEquals(listOf(201), NotificationReadCoordinator.snapshotPendingIds())

        assertTrue(NotificationReadCoordinator.acknowledgeSingleRead(203))
        val afterManualRead = NotificationReadCoordinator.overlayNotifications(
            items = listOf(items.first { it.id == 203 }),
            confirmedFloorForTopic = { 10 }
        )
        assertTrue(afterManualRead.single().read)
    }

    @Test
    fun acknowledgeAllReadMarksKnownIdsAndClearsPendingQueue() {
        NotificationReadCoordinator.ensureOwner("alice")
        NotificationReadCoordinator.acknowledgeSingleRead(301)
        NotificationReadCoordinator.acknowledgeAllRead(listOf(301, 302))
        assertTrue(NotificationReadCoordinator.snapshotPendingIds().isEmpty())
        assertTrue(NotificationReadCoordinator.isLocallyRead(301))
        assertTrue(NotificationReadCoordinator.isLocallyRead(302))
    }

    @Test
    fun switchingAccountClearsPreviousUsersNotificationLedger() {
        NotificationReadCoordinator.ensureOwner("alice")
        NotificationReadCoordinator.acknowledgeSingleRead(401)
        assertTrue(NotificationReadCoordinator.isLocallyRead(401))

        NotificationReadCoordinator.ensureOwner("bob")
        assertFalse(NotificationReadCoordinator.isLocallyRead(401))
        assertTrue(NotificationReadCoordinator.snapshotPendingIds().isEmpty())
    }

    @Test
    fun resolveAuthoritativeUnreadCountFixes60PageStickyMaxOfBug() {
        val smallPage = (1..10).map { idx -> notif(id = idx, read = idx > 2) }
        assertEquals(
            2,
            NotificationReadCoordinator.resolveAuthoritativeUnreadCount(smallPage, previousCachedCount = 15, pageSizeLimit = 60)
        )

        val fullMixedPage = (1..60).map { idx -> notif(id = idx, read = idx > 5) }
        assertEquals(
            5,
            NotificationReadCoordinator.resolveAuthoritativeUnreadCount(fullMixedPage, previousCachedCount = 42, pageSizeLimit = 60)
        )

        val fullAllUnreadPage = (1..60).map { idx -> notif(id = idx, read = false) }
        assertEquals(
            85,
            NotificationReadCoordinator.resolveAuthoritativeUnreadCount(fullAllUnreadPage, previousCachedCount = 85, pageSizeLimit = 60)
        )
    }

    @Test
    fun otpRedemptionUsesSingleCurrentSessionCallWithoutProfileBurst() = runTest {
        NetworkRecoveryPolicy.androidEnabled = true
        UserApiKeyAuthManager.requireBrowserTransportOnly = true
        val hitUrls = mutableListOf<String>()
        var prewarmRequested = false
        UserApiKeyAuthManager.onRequestClearancePrewarm = { prewarmRequested = true }

        UserApiKeyAuthManager.webViewHttpExecutor = { url, method, _, _ ->
            hitUrls += url
            when {
                url.contains("/session/otp/otp_123") -> {
                    assertEquals("POST", method)
                    CookieSentinel.ingestRawCookieString("_t=lightweight_session_token", domain = "linux.do", hostOnly = true)
                    200 to "<html>OK</html>"
                }
                url.endsWith("/session/current.json") -> {
                    200 to """{"current_user":{"id":42,"username":"alice","name":"Alice","avatar_template":"","trust_level":2,"unread_notifications":3}}"""
                }
                else -> 403 to "${CF_MITIGATED_CHALLENGE_PREFIX}<html>unexpected-burst</html>"
            }
        }

        assertTrue(LinuxDoRepository.redeemOtpSilently("otp_123"))
        assertEquals("alice", UserApiKeyAuthManager.currentUser?.username)
        assertEquals(3, UserApiKeyAuthManager.cachedUnreadNotificationCount)
        assertFalse(prewarmRequested, "OTP redemption must not trigger eager post-login clearance prewarm")
        assertTrue(hitUrls.none { it.contains("/notifications.json") || it.contains("/u/") }, "hitUrls=$hitUrls")
        assertFalse(LinuxDoRepository.isChallengeCooldownActive())
    }
}
