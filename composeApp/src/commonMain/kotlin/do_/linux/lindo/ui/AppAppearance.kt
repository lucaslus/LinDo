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
    /** Preserve the selected size name while moving from uneven v3 steps to even v4 steps. */
    fun restoreFontScale(v4: Float?, v3: Float?, legacy: Float): Float {
        if (v4 != null && v4.isFinite() && v4 > 0f) return ReaderFontScalePreset.fromScale(v4).scale
        val old = migrateFontScale(v3, legacy)
        val oldSteps = listOf(.90f, .95f, 1f, 1.12f, 1.24f)
        val index = oldSteps.indices.minBy { kotlin.math.abs(oldSteps[it] - old) }
        return ReaderFontScalePreset.entries[index].scale
    }
    fun migrateFontScale(current: Float?, legacy: Float): Float = current?.takeIf { it.isFinite() && it > 0f }
        ?: when { legacy < .885f -> .95f; legacy < .96f -> 1f; else -> 1.12f }
}

/** Native browser adapters provide capabilities, never a different product design. */
object PlatformSessionRecovery {
    var loginSession: (suspend () -> Boolean)? = null
    var verifySession: (suspend () -> Boolean)? = null
    var verificationMessage by mutableStateOf<String?>(null)
    var verifying by mutableStateOf(false)
    var verifyPillVisible by mutableStateOf(false)
    var onExpandVerifyPill: (() -> Unit)? = null
    /** 打开应用内验证面板（置于任何加载遮罩之上），并在用户通过或关闭后以 true/false 回调。 */
    var openEmbeddedVerifyModal: (((Boolean) -> Unit) -> Unit)? = null
    /** 平台静默过盾成功：UI 自动重试当前因站点验证失败的视图。 */
    var onSilentVerifyPassed: (() -> Unit)? = null
    var cancelVerification: (() -> Unit)? = null
}
