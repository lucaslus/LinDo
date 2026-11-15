package do_.linux.lindo

import do_.linux.lindo.core.connect.ScreenTrackEngine
import do_.linux.lindo.core.connect.UserPresenceService
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
}
