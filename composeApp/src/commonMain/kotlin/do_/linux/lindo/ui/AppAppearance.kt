package do_.linux.lindo.ui

import androidx.compose.runtime.*
import do_.linux.lindo.core.copy.ReaderFontScalePreset

/** One appearance policy for every platform; window constraints choose the layout. */
object AppAppearance {
    var appearance by mutableStateOf("system")
    var iconAppearance by mutableStateOf("system")
    var systemDark by mutableStateOf(false)
    var onSystemAppearanceChanged: (() -> Unit)? = null
    var onPersist: (() -> Unit)? = null
    var onApplyIconAppearance: ((String) -> Boolean)? = null
    var iconChangeFailed by mutableStateOf(false)
    val fontScales: List<Float> get() = ReaderFontScalePreset.entries.map { it.scale }
    val dark: Boolean get() = resolveDark(appearance, systemDark)
    val darkIcon: Boolean get() = resolveDark(iconAppearance, systemDark)
    fun resolveDark(mode: String, system: Boolean): Boolean = when (mode) { "dark" -> true; "light" -> false; else -> system }
    fun selectAppearance(value: String) { appearance = value; onPersist?.invoke() }
    fun selectIconAppearance(value: String) {
        iconChangeFailed = onApplyIconAppearance?.invoke(value) == false
        if (!iconChangeFailed) { iconAppearance = value; onPersist?.invoke() }
    }
    fun migrateFontScale(current: Float?, legacy: Float): Float = current?.takeIf { it.isFinite() && it > 0f }
        ?: when { legacy < .885f -> .95f; legacy < .96f -> 1f; else -> 1.12f }
}

/** Native browser adapters provide capabilities, never a different product design. */
object PlatformSessionRecovery {
    var loginSession: (suspend () -> Boolean)? = null
    var verifySession: (suspend () -> Boolean)? = null
    var verificationMessage by mutableStateOf<String?>(null)
}
