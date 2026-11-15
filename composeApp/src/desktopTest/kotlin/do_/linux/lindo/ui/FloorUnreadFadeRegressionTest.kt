package do_.linux.lindo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import do_.linux.lindo.core.connect.ScreenTrackEngine
import do_.linux.lindo.core.connect.UserPresenceService
import do_.linux.lindo.core.model.PostItemModel
import do_.linux.lindo.core.network.LinuxDoRepository
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class FloorUnreadFadeRegressionTest {
    private fun <T> edt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }
    @BeforeTest fun reset() {
        ScreenTrackEngine.resetForTest()
        LinuxDoRepository.clearAllLocalCaches()
        UserPresenceService.setAppForeground(true)
    }
    @AfterTest fun cleanup() { reset() }

    @Test fun visibleReplyFadesSmoothlyBeforeServerAcknowledges() = runDesktopComposeUiTest(width = 390, height = 700) {
        val post = PostItemModel(id = 9001, postNumber = 3, userId = 42, username = "reader",
            cookedHtml = "<p>阅读后，未读标记应自然淡出。</p>", isRead = false)
        setContent {
            val palette = buildLinDoPalette(true, BrandThemeOption.OFFICIAL_GOLD)
            CompositionLocalProvider(LocalPalette provides palette, LocalLinDoAnimations provides false) {
                MaterialTheme(colorScheme = buildMaterialSchemeFromPalette(palette)) {
                    Box(Modifier.fillMaxSize().background(palette.canvas)) {
                        CommunityPostFloor(post = post, isUnread = rememberFloorUnread(42001, post, true),
                            opUsername = "owner", fontScale = 1f, enableAnimations = true,
                            onInspectBadge = {}, onInspectUser = {}, onReplyFloor = {}, onQuoteFloor = { _, _ -> }, onRequestLogin = {})
                    }
                }
            }
        }
        waitForIdle()
        fun dotColor(): Color = edt {
            val image = onNodeWithTag("post_unread_9001", useUnmergedTree = true).captureToImage()
            image.toPixelMap()[image.width / 2, image.height / 2]
        }
        fun distance(a: Color, b: Color) = abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue)
        val before = dotColor()
        mainClock.autoAdvance = false
        edt {
            ScreenTrackEngine.onTopicEntered(42001, setOf(3), 1_000L)
            ScreenTrackEngine.tickAndEnqueue(2_200L)
            val batch = assertNotNull(ScreenTrackEngine.popNextSendableBatch(2_200L))
            ScreenTrackEngine.onBatchSendResult(batch, ScreenTrackEngine.STATUS_SITE_VERIFICATION_REQUIRED, 2_300L)
        }
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeBy(250L)
        waitForIdle()
        val middle = dotColor()
        mainClock.advanceTimeBy(900L)
        waitForIdle()
        val after = dotColor()
        assertTrue(distance(before, after) > 0.5f, "Unread dot must disappear without waiting for the server")
        assertTrue(distance(middle, after) > 0.01f, "Must fade rather than disappear immediately")
        assertTrue(distance(middle, after) < distance(before, after), "Opacity must decrease during the fade")
        assertFalse(ScreenTrackEngine.isPostConfirmedReadByServer(42001, 3))
        assertTrue(ScreenTrackEngine.hasPendingWork(), "Visual acknowledgement must not cancel server synchronization")
    }
}
