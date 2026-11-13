package do_.linux.lindo

import java.awt.Toolkit
import java.awt.Canvas
import java.awt.Frame
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.event.KeyEvent
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MacInputRegressionTest {
    @Test
    fun rapidNotchBurstDeliversAllRemainingMotionOnBothAxes() {
        if (!IS_MAC_OS) return
        SwingUtilities.invokeAndWait {
            val frame = Frame()
            // Exercise the real event routing/accumulator without opening a window.
            val canvas = object : Canvas() { override fun isShowing() = true }
            frame.add(canvas)
            val engine = DesktopSmoothWheelEngine
            val intercept = engine.javaClass.getDeclaredMethod("interceptRawWheelIfNeeded", MouseWheelEvent::class.java)
                .apply { isAccessible = true }
            val step = engine.javaClass.getDeclaredMethod("stepOnVsyncFrame", Long::class.javaPrimitiveType)
                .apply { isAccessible = true }
            try {
                for (horizontal in listOf(false, true)) {
                    engine.attachToWindow(null)
                    engine.attachToWindow(frame)
                    var delivered = 0.0
                    val listener = java.awt.event.MouseWheelListener {
                        assertEquals(horizontal, it.isShiftDown)
                        delivered += it.preciseWheelRotation
                    }
                    canvas.addMouseWheelListener(listener)
                    fun notch(time: Long) = MouseWheelEvent(
                        canvas, MouseEvent.MOUSE_WHEEL, time,
                        if (horizontal) InputEvent.SHIFT_DOWN_MASK else 0,
                        1, 1, 1, 1, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, 1, 0.1
                    )
                    assertEquals(true, intercept.invoke(engine, notch(1000L)))
                    step.invoke(engine, 1_000_000_000L)
                    assertEquals(true, intercept.invoke(engine, notch(1008L)))
                    for (i in 1..120) step.invoke(engine, 1_000_000_000L + i * 6_944_444L)
                    // 5.4 units for the first notch + 5.4 * 1.28 for the next.
                    assertTrue(abs(delivered - 12.312) < 0.02, "lost motion: delivered=$delivered")
                    canvas.removeMouseWheelListener(listener)
                }
            } finally {
                engine.attachToWindow(null)
                frame.dispose()
            }
        }
    }

    @Test
    fun rapidNotchesKeepTheSameRouteAsSlowNotches() {
        // A burst at 125 Hz, including events drained together after an EDT stall.
        // Previously intervals below 14 ms cleared the unconsumed smooth motion.
        for (interval in listOf(0L, 1L, 4L, 8L, 13L, 14L, 33L)) {
            for (direction in listOf(-1, 1)) {
                assertTrue(DesktopSmoothWheelEngine.isDiscreteMouseWheelNotch(
                    direction, direction * 0.1, interval, false, isMacOs = true
                ), "notch route changed at interval=$interval")
            }
        }
        assertFalse(DesktopSmoothWheelEngine.isDiscreteMouseWheelNotch(0, 0.04, 8L, false, true))
        assertFalse(DesktopSmoothWheelEngine.isDiscreteMouseWheelNotch(1, 0.68, 8L, false, true))
        assertFalse(DesktopSmoothWheelEngine.isDiscreteMouseWheelNotch(1, 0.1, 8L, true, true))
    }

    @Test
    fun capsLockFlagDoesNotConfuseShiftOrOtherModifiers() {
        val caps = 1L shl 16
        val shift = 1L shl 17
        val command = 1L shl 20
        assertFalse(MacKeyboardState.capsLockFromFlags(0))
        assertFalse(MacKeyboardState.capsLockFromFlags(shift or command))
        assertTrue(MacKeyboardState.capsLockFromFlags(caps))
        assertTrue(MacKeyboardState.capsLockFromFlags(caps or shift or command))
    }

    @Test
    fun macRuntimeActuallyLoadsPatchedComposeAndRealSystemKeyState() {
        if (!IS_MAC_OS) return
        assertTrue(MacKeyboardState.nativeAvailable(), "CoreGraphics must load; do not silently ship the slow fallback")
        val bridge = Class.forName("androidx.compose.ui.scene.ComposeSceneMediator_desktopKt")
        assertTrue(bridge.protectionDomain.codeSource.location.path.endsWith("ui-desktop-macos-input.jar"),
            "desktop runtime must not accidentally use the original unpatched JAR")
        val query = bridge.getDeclaredMethod("getLockingKeyStateSafe", Int::class.javaPrimitiveType)
        query.isAccessible = true
        val expected = Toolkit.getDefaultToolkit().getLockingKeyState(KeyEvent.VK_CAPS_LOCK)
        assertEquals(expected, query.invoke(null, KeyEvent.VK_CAPS_LOCK))
        for (code in listOf(KeyEvent.VK_NUM_LOCK, KeyEvent.VK_SCROLL_LOCK)) {
            val original = runCatching { Toolkit.getDefaultToolkit().getLockingKeyState(code) }.getOrDefault(false)
            assertEquals(original, query.invoke(null, code))
        }
    }
}
