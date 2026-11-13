package do_.linux.lindo

import com.sun.jna.Library
import com.sun.jna.Native
import java.awt.Toolkit
import java.awt.event.KeyEvent

/**
 * Called by the version-checked desktop Compose patch. AWT's macOS Caps Lock
 * getter synchronously waits for AppKit on every mouse event. CoreGraphics
 * exposes the same current session flag without that AppKit round trip.
 * Keyboard/IME events and all other modifier flags keep their original path.
 */
object MacKeyboardState {
    private interface CoreGraphics : Library {
        fun CGEventSourceFlagsState(stateId: Int): Long
    }

    private val coreGraphics: CoreGraphics? by lazy {
        if (!IS_MAC_OS) null else runCatching {
            Native.load(
                "/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics",
                CoreGraphics::class.java
            )
        }.onFailure {
            System.err.println("Lindo: CoreGraphics keyboard flags unavailable; using AWT fallback: ${it.javaClass.simpleName}")
        }.getOrNull()
    }

    /** Warm up native loading before the first interactive frame. */
    fun warmUp() {
        if (IS_MAC_OS) getLockingKeyState(KeyEvent.VK_CAPS_LOCK)
    }

    @JvmStatic
    fun getLockingKeyState(keyCode: Int): Boolean {
        if (IS_MAC_OS && keyCode == KeyEvent.VK_CAPS_LOCK) {
            coreGraphics?.let { native ->
                // kCGEventSourceStateCombinedSessionState = 0. Read on demand:
                // no polling, stale cache, fabricated key state or input monitoring permission.
                return capsLockFromFlags(native.CGEventSourceFlagsState(0))
            }
        }
        return runCatching { Toolkit.getDefaultToolkit().getLockingKeyState(keyCode) }.getOrDefault(false)
    }

    internal fun capsLockFromFlags(flags: Long): Boolean = flags and (1L shl 16) != 0L
    internal fun nativeAvailable(): Boolean = coreGraphics != null
}
