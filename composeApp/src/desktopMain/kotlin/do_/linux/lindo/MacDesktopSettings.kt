package do_.linux.lindo

import com.sun.jna.*
import do_.linux.lindo.ui.MacDesktopExperience
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
        MacDesktopExperience.enabled = true
        do_.linux.lindo.core.network.DesktopNetworkPolicy.enabled = true
        MacDesktopExperience.appearance = props.getProperty("mac_appearance")
            ?: if (props.containsKey("ui_dark_theme")) { if (UserApiKeyAuthManager.uiDarkTheme) "dark" else "light" } else "system"
        MacDesktopExperience.iconAppearance = props.getProperty("mac_icon_appearance", "system")
        MacDesktopExperience.systemDark = systemDark()
        UserApiKeyAuthManager.uiDarkTheme = MacDesktopExperience.dark
        UserApiKeyAuthManager.uiFontScale = props.getProperty("ui_font_scale_mac_v3")?.toFloatOrNull()
            ?: when { UserApiKeyAuthManager.uiFontScale < .89f -> .95f; UserApiKeyAuthManager.uiFontScale < .97f -> 1f; else -> 1.12f }
        MacDesktopExperience.onPersist = {
            persist { setProperty("mac_appearance", MacDesktopExperience.appearance); setProperty("mac_icon_appearance", MacDesktopExperience.iconAppearance) }
            updateDock()
        }
        SwingUtilities.invokeLater { updateDock() }
        val observer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "lindo-system-appearance").apply { isDaemon = true } }
        observer.scheduleWithFixedDelay({
            val dark = systemDark()
            if (dark != MacDesktopExperience.systemDark) SwingUtilities.invokeLater { MacDesktopExperience.systemDark = dark; updateDock() }
        }, 2, 2, TimeUnit.SECONDS)
    }
    private var lastDockDark: Boolean? = null
    private fun updateDock() {
        val dark = MacDesktopExperience.darkIcon
        if (dark == lastDockDark) return
        runCatching {
            if (!Taskbar.isTaskbarSupported() || !Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)) return
            val art = ImageIO.read(javaClass.getResourceAsStream("/tray_mark.png"))
            val image = BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB)
            image.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                color = if (dark) Color(0x121212) else Color.WHITE
                fillRoundRect(32, 32, 448, 448, 100, 100)
                drawImage(art, 52, 48, 408, 408, null)
                dispose()
            }
            Taskbar.getTaskbar().iconImage = image
            lastDockDark = dark
        }
    }
}
