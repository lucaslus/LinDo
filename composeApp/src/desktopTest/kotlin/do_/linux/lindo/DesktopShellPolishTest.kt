package do_.linux.lindo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopShellPolishTest {
    @Test
    fun trayCanvasRendersRetinaAndUnreadBadgesCleanly() {
        val base1x = DesktopTrayController.renderTrayCanvas(sizePx = 22, scale = 1f, unreadCount = 0)
        val base2x = DesktopTrayController.renderTrayCanvas(sizePx = 44, scale = 2f, unreadCount = 0)
        val badgeSingle = DesktopTrayController.renderTrayCanvas(sizePx = 44, scale = 2f, unreadCount = 3)
        val badgeOverflow = DesktopTrayController.renderTrayCanvas(sizePx = 44, scale = 2f, unreadCount = 24)

        assertEquals(22, base1x.width)
        assertEquals(22, base1x.height)
        assertEquals(44, base2x.width)
        assertEquals(44, base2x.height)

        // Ensure center mark pixels are non-transparent
        var nonTransparentPixels = 0
        for (y in 8 until 36) {
            for (x in 8 until 36) {
                if ((base2x.getRGB(x, y) ushr 24) > 24) {
                    nonTransparentPixels++
                }
            }
        }
        assertTrue(nonTransparentPixels > 120, "Expected tray icon mark pixels to be rendered")

        // Ensure top-right unread badge paints Apple HIG crimson (#FF3B30) pixels when unreadCount > 0
        fun countCrimsonBadgePixels(img: java.awt.image.BufferedImage): Int {
            var count = 0
            for (y in 0 until 24) {
                for (x in 20 until 44) {
                    val argb = img.getRGB(x, y)
                    val a = (argb ushr 24) and 0xFF
                    val r = (argb ushr 16) and 0xFF
                    val g = (argb ushr 8) and 0xFF
                    val b = argb and 0xFF
                    if (a > 200 && r > 220 && g < 95 && b < 85) {
                        count++
                    }
                }
            }
            return count
        }

        val baseRed = countCrimsonBadgePixels(base2x)
        val singleRed = countCrimsonBadgePixels(badgeSingle)
        val overflowRed = countCrimsonBadgePixels(badgeOverflow)
        assertTrue(singleRed > baseRed + 120, "Expected red badge for single-digit unread count (base=$baseRed, single=$singleRed)")
        assertTrue(overflowRed > singleRed, "Expected wider red badge for 9+ unread count (single=$singleRed, overflow=$overflowRed)")
    }
}
