package do_.linux.lindo.ui

import androidx.compose.runtime.*

/** Opt-in desktop policy. Android and other desktop platforms retain their existing behavior. */
object MacDesktopExperience {
    var enabled = false
    var appearance by mutableStateOf("system")
    var iconAppearance by mutableStateOf("system")
    var systemDark by mutableStateOf(false)
    var onPersist: (() -> Unit)? = null
    var loginSession: (suspend () -> Boolean)? = null
    var verifySession: (suspend () -> Boolean)? = null
    var verificationMessage by mutableStateOf<String?>(null)
    val fontScales = listOf(0.95f, 1f, 1.12f)
    val dark: Boolean get() = when (appearance) { "dark" -> true; "light" -> false; else -> systemDark }
    val darkIcon: Boolean get() = when (iconAppearance) { "dark" -> true; "light" -> false; else -> systemDark }
    fun selectAppearance(value: String) { appearance = value; onPersist?.invoke() }
    fun selectIconAppearance(value: String) { iconAppearance = value; onPersist?.invoke() }
}
