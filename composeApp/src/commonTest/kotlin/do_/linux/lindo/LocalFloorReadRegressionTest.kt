package do_.linux.lindo

import do_.linux.lindo.core.connect.ScreenTrackEngine
import do_.linux.lindo.core.connect.UserPresenceService
import do_.linux.lindo.ui.resolveDiscourseTopicUnread
import kotlin.test.*

class LocalFloorReadRegressionTest {
    @BeforeTest fun reset() {
        ScreenTrackEngine.resetForTest()
        UserPresenceService.setAppForeground(true)
    }
    @AfterTest fun cleanup() { reset() }

    @Test fun onlyVisibleFloorsFadeAfterReadingThreshold() {
        ScreenTrackEngine.onTopicEntered(101, setOf(2, 4), 1_000L)
        ScreenTrackEngine.tickAndEnqueue(1_500L)
        assertFalse(ScreenTrackEngine.isPostViewedLocally(101, 2))
        val version = ScreenTrackEngine.localReadVersion
        ScreenTrackEngine.tickAndEnqueue(2_000L)
        assertTrue(ScreenTrackEngine.localReadVersion > version)
        assertTrue(ScreenTrackEngine.isPostViewedLocally(101, 2))
        assertTrue(ScreenTrackEngine.isPostViewedLocally(101, 4))
        assertFalse(ScreenTrackEngine.isPostViewedLocally(101, 3))
        assertFalse(ScreenTrackEngine.isPostConfirmedReadByServer(101, 4))
        assertTrue(ScreenTrackEngine.hasPendingWork())
    }

    @Test fun failedTimingUploadsDoNotRelightViewedFloors() {
        for (status in listOf(403, 429, ScreenTrackEngine.STATUS_SITE_VERIFICATION_REQUIRED, 422)) {
            reset()
            ScreenTrackEngine.onTopicEntered(101, setOf(2), 1_000L)
            ScreenTrackEngine.tickAndEnqueue(2_200L)
            val batch = assertNotNull(ScreenTrackEngine.popNextSendableBatch(2_200L))
            val version = ScreenTrackEngine.localReadVersion
            ScreenTrackEngine.onBatchSendResult(batch, status, 2_300L)
            assertTrue(ScreenTrackEngine.isPostViewedLocally(101, 2), "status=$status")
            assertFalse(ScreenTrackEngine.isPostConfirmedReadByServer(101, 2))
            assertEquals(version, ScreenTrackEngine.localReadVersion)
            ScreenTrackEngine.onBatchSendResult(batch, 200, 100_000L)
            assertTrue(ScreenTrackEngine.isPostConfirmedReadByServer(101, 2))
            assertTrue(ScreenTrackEngine.isPostViewedLocally(101, 2))
        }
    }

    @Test fun localReadsSurviveReopeningButNeverCrossTopicsOrAccounts() {
        ScreenTrackEngine.onTopicEntered(101, setOf(2), 1_000L)
        ScreenTrackEngine.tickAndEnqueue(2_200L)
        ScreenTrackEngine.onTopicExited(101, 2_200L)
        ScreenTrackEngine.onTopicEntered(202, emptySet(), 3_000L)
        assertFalse(ScreenTrackEngine.isPostViewedLocally(202, 2))
        ScreenTrackEngine.onTopicEntered(101, emptySet(), 4_000L)
        assertTrue(ScreenTrackEngine.isPostViewedLocally(101, 2))
        val version = ScreenTrackEngine.localReadVersion
        ScreenTrackEngine.resetSessionState()
        assertFalse(ScreenTrackEngine.isPostViewedLocally(101, 2))
        assertTrue(ScreenTrackEngine.localReadVersion > version)
    }

    @Test fun backgroundTimeDoesNotClearUnreadMarkers() {
        ScreenTrackEngine.onTopicEntered(101, setOf(2), 1_000L)
        UserPresenceService.setAppForeground(false)
        ScreenTrackEngine.tickAndEnqueue(10_000L)
        assertFalse(ScreenTrackEngine.isPostViewedLocally(101, 2))
        UserPresenceService.setAppForeground(true)
        ScreenTrackEngine.tickAndEnqueue(10_500L)
        assertFalse(ScreenTrackEngine.isPostViewedLocally(101, 2))
        ScreenTrackEngine.tickAndEnqueue(11_000L)
        assertTrue(ScreenTrackEngine.isPostViewedLocally(101, 2))
    }

    @Test fun discourseTopicUnreadClearsUnseenOnVisitAndTracksUnreadRepliesLocally() {
        // 1. Brand-new unseen 50-floor topic (unreadPosts = 0): clears dot immediately upon visit
        val beforeVisit = resolveDiscourseTopicUnread(
            rawUnseen = true,
            rawUnreadPosts = 0,
            highestPostNumber = 50,
            postsCount = 50,
            visitedLocally = false,
            effectiveReadFloor = 0
        )
        assertTrue(beforeVisit.isUnread)

        ScreenTrackEngine.markTopicVisitedLocally(topicId = 701, initialFloor = 0)
        assertTrue(ScreenTrackEngine.isTopicVisitedLocally(701))
        val afterVisit = resolveDiscourseTopicUnread(
            rawUnseen = true,
            rawUnreadPosts = 0,
            highestPostNumber = 50,
            postsCount = 50,
            visitedLocally = ScreenTrackEngine.isTopicVisitedLocally(701),
            effectiveReadFloor = ScreenTrackEngine.getLocalViewedMaxFloor(701)
        )
        assertFalse(afterVisit.effectiveUnseen)
        assertFalse(afterVisit.isUnread)

        // 2. Tracked topic with 3 unread replies (highest = 15, lastRead = 12): stays unread until floor 15 (or tail #14 if #15 deleted)
        ScreenTrackEngine.markTopicVisitedLocally(topicId = 702, initialFloor = 12)
        val midRead = resolveDiscourseTopicUnread(
            rawUnseen = false,
            rawUnreadPosts = 3,
            highestPostNumber = 15,
            postsCount = 14,
            visitedLocally = ScreenTrackEngine.isTopicVisitedLocally(702),
            effectiveReadFloor = ScreenTrackEngine.getLocalViewedMaxFloor(702)
        )
        assertTrue(midRead.isUnread)
        assertEquals(3, midRead.effectiveUnreadPosts)

        // Reader reaches final visible floor #14 (while #15 was deleted) -> aligns to highestPostNumber=15
        ScreenTrackEngine.recordLocalTopicFloorViewed(topicId = 702, floor = 15)
        val finishedRead = resolveDiscourseTopicUnread(
            rawUnseen = false,
            rawUnreadPosts = 3,
            highestPostNumber = 15,
            postsCount = 14,
            visitedLocally = ScreenTrackEngine.isTopicVisitedLocally(702),
            effectiveReadFloor = ScreenTrackEngine.getLocalViewedMaxFloor(702)
        )
        assertFalse(finishedRead.isUnread)
        assertEquals(0, finishedRead.effectiveUnreadPosts)

        // 3. Quick 600ms read before exiting topic records onscreen floor locally
        ScreenTrackEngine.onTopicEntered(703, setOf(1, 2), 1_000L)
        ScreenTrackEngine.onTopicExited(703, 1_600L)
        assertEquals(2, ScreenTrackEngine.getLocalViewedMaxFloor(703))
    }
}
