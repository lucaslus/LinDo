package do_.linux.lindo

import java.awt.Dimension
import java.awt.DisplayMode
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopShellPolishTest {

    @Test
    fun desktopWindowMinimumSizeConstantsAndHelperAreEnforced() {
        assertEquals(420, DESKTOP_MIN_WINDOW_WIDTH_DP)
        assertEquals(580, DESKTOP_MIN_WINDOW_HEIGHT_DP)
        assertTrue(DESKTOP_DEFAULT_WINDOW_WIDTH_DP >= DESKTOP_MIN_WINDOW_WIDTH_DP)
        assertTrue(DESKTOP_DEFAULT_WINDOW_HEIGHT_DP >= DESKTOP_MIN_WINDOW_HEIGHT_DP)

        applyDesktopMinimumWindowSize(null) // safe no-op on null

        if (!GraphicsEnvironment.isHeadless()) {
            val frame = Frame()
            try {
                frame.setSize(120, 300)
                applyDesktopMinimumWindowSize(frame)
                assertEquals(
                    Dimension(DESKTOP_MIN_WINDOW_WIDTH_DP, DESKTOP_MIN_WINDOW_HEIGHT_DP),
                    frame.minimumSize
                )
                assertTrue(frame.width >= DESKTOP_MIN_WINDOW_WIDTH_DP)
                assertTrue(frame.height >= DESKTOP_MIN_WINDOW_HEIGHT_DP)
            } finally {
                frame.dispose()
            }
        }
    }

    @Test
    fun trayCanvasRendersMacAndWindowsMultiDpiAndUnreadBadgesCleanly() {
        val sizes = listOf(16, 20, 22, 24, 32, 44, 48)
        for (px in sizes) {
            val scale = px / 22f
            val canvas = DesktopTrayController.renderTrayCanvas(sizePx = px, scale = scale, unreadCount = 5)
            assertEquals(px, canvas.width)
            assertEquals(px, canvas.height)
        }

        val base2x = DesktopTrayController.renderTrayCanvas(sizePx = 44, scale = 2f, unreadCount = 0)
        val badgeSingle = DesktopTrayController.renderTrayCanvas(sizePx = 44, scale = 2f, unreadCount = 3)
        val badgeOverflow = DesktopTrayController.renderTrayCanvas(sizePx = 44, scale = 2f, unreadCount = 24)

        var nonTransparentPixels = 0
        for (y in 8 until 36) {
            for (x in 8 until 36) {
                if ((base2x.getRGB(x, y) ushr 24) > 24) {
                    nonTransparentPixels++
                }
            }
        }
        assertTrue(nonTransparentPixels > 120, "Expected tray icon mark pixels to be rendered")

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
        assertTrue(singleRed > baseRed + 120, "Expected red badge for single-digit unread count")
        assertTrue(overflowRed > singleRed, "Expected wider red badge for 9+ unread count")
    }

    @Test
    fun singleInstanceLoopbackIpcForwardsWindowsAndLinuxDeepLinksToPrimary() {
        val tempDir = Files.createTempDirectory("lindo-ipc-test").toFile()
        val receivedUri = AtomicReference<String?>(null)
        val activateCount = AtomicInteger(0)
        val deepLinkLatch = CountDownLatch(1)
        val focusLatch = CountDownLatch(2)
        try {
            val isPrimary = DesktopSingleInstanceBridge.acquireOrForward(
                lockDir = tempDir,
                deepLink = null,
                onDeepLink = { uri ->
                    receivedUri.set(uri)
                    deepLinkLatch.countDown()
                },
                onActivate = {
                    activateCount.incrementAndGet()
                    focusLatch.countDown()
                }
            )
            assertTrue(isPrimary, "First instance must acquire primary lock")

            val callbackUri = "discourse://auth_redirect?payload=test_payload_b64&oneTimePassword=test_otp_b64"
            val secondShouldContinue = DesktopSingleInstanceBridge.acquireOrForward(
                lockDir = tempDir,
                deepLink = callbackUri
            )
            assertFalse(secondShouldContinue, "Secondary deep-link launch must forward to primary and exit")
            assertTrue(deepLinkLatch.await(3, TimeUnit.SECONDS), "Primary must receive forwarded deep link")
            assertEquals(callbackUri, receivedUri.get())

            val thirdShouldContinue = DesktopSingleInstanceBridge.acquireOrForward(
                lockDir = tempDir,
                deepLink = null
            )
            assertFalse(thirdShouldContinue, "Secondary focus launch must forward FOCUS and exit")
            assertTrue(focusLatch.await(3, TimeUnit.SECONDS), "Primary must receive activation callbacks")
            assertEquals(2, activateCount.get())
        } finally {
            DesktopSingleInstanceBridge.close()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun windowsPrecisionTouchpadAndHighRefreshWheelPhysicsBehaveAccurately() {
        // Windows Precision Touchpad emits rawInt=0 with small fractional preciseWheelRotation
        assertTrue(DesktopSmoothWheelEngine.isTrackpadInitialFrame(rawInt = 0, rawPrecise = 0.03))
        assertTrue(DesktopSmoothWheelEngine.isTrackpadInitialFrame(rawInt = 0, rawPrecise = -0.18))
        assertFalse(DesktopSmoothWheelEngine.isTrackpadInitialFrame(rawInt = 1, rawPrecise = 1.0))

        // Fractional delta on Windows must NEVER be inflated to >= 1.0 notch!
        val fractionalWinDelta = DesktopSmoothWheelEngine.computeWheelStepDelta(
            rawInt = 0,
            rawPrecise = 0.03,
            velocity = 1.0,
            isMacOs = false
        )
        assertTrue(
            abs(fractionalWinDelta) < 0.25,
            "Windows fractional touchpad step must not rocket-scroll (got $fractionalWinDelta)"
        )

        // Discrete mouse wheel notch produces smooth non-zero step on both Windows & macOS
        val discreteWinDelta = DesktopSmoothWheelEngine.computeWheelStepDelta(
            rawInt = 1,
            rawPrecise = 1.0,
            velocity = 1.0,
            isMacOs = false
        )
        val discreteMacDelta = DesktopSmoothWheelEngine.computeWheelStepDelta(
            rawInt = 1,
            rawPrecise = 1.0,
            velocity = 1.0,
            isMacOs = true
        )
        assertTrue(discreteWinDelta > 0.5 && discreteWinDelta < 12.0)
        assertTrue(discreteMacDelta > 2.0 && discreteMacDelta <= 26.0)

        // Refresh rate clamping preserves 60Hz, 120Hz, 144Hz, 240Hz accurately
        assertEquals(60, DesktopSmoothWheelEngine.clampRefreshRateHz(60, isMacOs = false))
        assertEquals(60, DesktopSmoothWheelEngine.clampRefreshRateHz(60, isMacOs = true))
        assertEquals(144, DesktopSmoothWheelEngine.clampRefreshRateHz(144, isMacOs = false))
        assertEquals(240, DesktopSmoothWheelEngine.clampRefreshRateHz(360, isMacOs = false))
        assertEquals(60, DesktopSmoothWheelEngine.clampRefreshRateHz(DisplayMode.REFRESH_RATE_UNKNOWN, isMacOs = false))
        assertEquals(120, DesktopSmoothWheelEngine.clampRefreshRateHz(DisplayMode.REFRESH_RATE_UNKNOWN, isMacOs = true))
    }

    @Test
    fun windowsProtocolRegistrationAndDesktopSecurityHelpersValidateProperly() {
        assertTrue(shouldRegisterDiscourseProtocolOnWindows(1, ""))
        assertTrue(
            shouldRegisterDiscourseProtocolOnWindows(
                0,
                """    (Default)    REG_SZ    "C:\Users\lucas\AppData\Local\Lindo\Lindo.exe" "%1" """,
                fileExists = { true }
            )
        )
        assertTrue(
            shouldRegisterDiscourseProtocolOnWindows(
                0,
                """    (Default)    REG_SZ    "C:\OldRemoved\DiscourseClient.exe" "%1" """,
                fileExists = { false }
            )
        )
        assertFalse(
            shouldRegisterDiscourseProtocolOnWindows(
                0,
                """    (Default)    REG_SZ    "C:\Program Files\OtherClient\Other.exe" "%1" """,
                fileExists = { true }
            )
        )

        // Deep link CLI arg extraction (handles Windows quoted "%1")
        assertEquals(
            "discourse://auth_redirect?payload=abc",
            extractDeepLinkArg(arrayOf("--flag", "\"discourse://auth_redirect?payload=abc\""))
        )
        assertEquals(
            "lindo://auth_redirect?payload=xyz",
            extractDeepLinkArg(arrayOf("'lindo://auth_redirect?payload=xyz'"))
        )
        assertNull(extractDeepLinkArg(arrayOf("--minimized", "https://linux.do")))

        // Safe external URL parsing (UTF-8 Chinese query + scheme allowlist)
        val encodedChineseUri = parseSafeExternalDesktopUri("https://linux.do/search?q=佬友 Lindo 测试")
        assertNotNull(encodedChineseUri)
        assertEquals("https", encodedChineseUri.scheme)
        assertEquals("linux.do", encodedChineseUri.host)
        assertNull(parseSafeExternalDesktopUri("javascript:alert(1)"))
        assertNull(parseSafeExternalDesktopUri("file:///C:/Windows/System32/calc.exe"))

        // MIME type detection & User-Agent sanity
        assertEquals("image/png", guessDesktopMimeType(File("shot.PNG")))
        assertEquals("image/webp", guessDesktopMimeType(File("anim.webp")))
        assertEquals("application/pdf", guessDesktopMimeType(File("doc.pdf")))
        assertTrue(defaultDesktopUserAgent().contains("Chrome/131"))
    }
}
