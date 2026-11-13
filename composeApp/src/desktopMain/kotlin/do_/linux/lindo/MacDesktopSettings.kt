package do_.linux.lindo

import com.sun.jna.*
import do_.linux.lindo.ui.AppAppearance
import do_.linux.lindo.core.network.UserApiKeyAuthManager
import java.awt.*
import java.awt.image.BufferedImage
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

internal object MacDesktopSettings {
    private interface CF : Library {
        fun CFStringCreateWithCString(allocator: Pointer?, value: String, encoding: Int): Pointer
        fun CFPreferencesCopyAppValue(key: Pointer, app: Pointer): Pointer?
        fun CFStringGetCString(value: Pointer, buffer: ByteArray, size: Long, encoding: Int): Boolean
        fun CFRelease(value: Pointer)
    }
    private val cf by lazy { Native.load("CoreFoundation", CF::class.java) }
    private fun systemDark(): Boolean = runCatching {
        val key = cf.CFStringCreateWithCString(null, "AppleInterfaceStyle", 0x08000100)
        val app = cf.CFStringCreateWithCString(null, "NSGlobalDomain", 0x08000100)
        try {
            val value = cf.CFPreferencesCopyAppValue(key, app) ?: return@runCatching false
            try { val bytes = ByteArray(32); cf.CFStringGetCString(value, bytes, bytes.size.toLong(), 0x08000100); String(bytes).trimEnd('\u0000').equals("Dark", true) }
            finally { cf.CFRelease(value) }
        } finally { cf.CFRelease(key); cf.CFRelease(app) }
    }.getOrDefault(false)
    fun install(props: Properties, persist: (Properties.() -> Unit) -> Unit) {

        do_.linux.lindo.core.network.DesktopNetworkPolicy.enabled = true
        AppAppearance.systemDark = systemDark()
        UserApiKeyAuthManager.uiDarkTheme = AppAppearance.dark
        AppAppearance.onPersist = {
            persist { setProperty("ui_appearance", AppAppearance.appearance); setProperty("ui_icon_appearance", AppAppearance.iconAppearance) }
            updateDock()
        }
        SwingUtilities.invokeLater { updateDock() }
        val observer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "lindo-system-appearance").apply { isDaemon = true } }
        observer.scheduleWithFixedDelay({
            val dark = systemDark()
            SwingUtilities.invokeLater { AppAppearance.systemDark = dark; updateDock() }
        }, 2, 2, TimeUnit.SECONDS)
    }
    private fun updateDock() = DesktopAppIconManager.refresh()
}
