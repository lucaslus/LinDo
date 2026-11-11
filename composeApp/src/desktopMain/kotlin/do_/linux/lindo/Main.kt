package do_.linux.lindo

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import do_.linux.lindo.core.compose.LocalComposerDrafts
import do_.linux.lindo.core.network.AuthenticatedUserProfile
import do_.linux.lindo.core.network.CookieSentinel
import do_.linux.lindo.core.network.LinuxDoRepository
import do_.linux.lindo.core.network.UserApiKeyAuthManager
import do_.linux.lindo.ui.LinDoApp
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

private object DesktopStateStore {
    private val lock = Any()
    private val stateDir: File by lazy {
        File(System.getProperty("user.home"), ".lindo").apply {
            mkdirs()
            runCatching {
                setReadable(false, false)
                setReadable(true, true)
                setWritable(false, false)
                setWritable(true, true)
                setExecutable(false, false)
                setExecutable(true, true)
            }
        }
    }
    private val stateFile: File by lazy { File(stateDir, "session.properties") }

    fun load(): Properties = synchronized(lock) {
        val props = Properties()
        if (stateFile.isFile) {
            runCatching {
                stateFile.inputStream().use { props.load(it) }
            }
        }
        props
    }

    fun update(mutator: Properties.() -> Unit) = synchronized(lock) {
        runCatching {
            val props = load()
            props.mutator()
            val tmp = File(stateDir, "session.properties.tmp")
            tmp.outputStream().use { out ->
                props.store(out, "Lindo Desktop State")
            }
            runCatching {
                tmp.setReadable(false, false)
                tmp.setReadable(true, true)
                tmp.setWritable(false, false)
                tmp.setWritable(true, true)
            }
            Files.move(tmp.toPath(), stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun Properties.putOrRemove(key: String, value: String?) {
        if (value == null) remove(key) else setProperty(key, value)
    }

    fun initAndWire() {
        val props = load()
        val savedClientId = props.getProperty("client_id")
        if (!savedClientId.isNullOrBlank()) {
            UserApiKeyAuthManager.restoreClientId(savedClientId)
        } else {
            update { setProperty("client_id", UserApiKeyAuthManager.getClientId()) }
        }

        UserApiKeyAuthManager.restorePendingPrivateKeyBase64(
            props.getProperty("pending_rsa_priv_b64"),
            props.getProperty("pending_rsa_nonce"),
            props.getProperty("pending_rsa_created_at")?.toLongOrNull() ?: 0L
        )

        UserApiKeyAuthManager.uiFontScale = props.getProperty("ui_font_scale_v2")?.toFloatOrNull() ?: 0.92f
        UserApiKeyAuthManager.uiDarkTheme = props.getProperty("ui_dark_theme")?.toBooleanStrictOrNull() ?: false
        UserApiKeyAuthManager.uiEnableAnimations = props.getProperty("ui_enable_animations")?.toBooleanStrictOrNull() ?: true
        UserApiKeyAuthManager.uiThemeAccent = props.getProperty("ui_theme_accent")?.takeIf { it.isNotBlank() } ?: "OFFICIAL_GOLD"
        UserApiKeyAuthManager.uiCompactFeed = props.getProperty("ui_compact_feed")?.toBooleanStrictOrNull() ?: false
        LinuxDoRepository.restoreReadTopics(props.getProperty("read_topic_posts"))

        val username = props.getProperty("username")?.trim()
        val profile = if (!username.isNullOrBlank() && username != "linuxdo_user") {
            AuthenticatedUserProfile(
                userId = props.getProperty("user_id")?.toIntOrNull() ?: 0,
                username = username,
                displayName = props.getProperty("display_name")?.takeIf { it.isNotBlank() } ?: username,
                avatarTemplate = props.getProperty("avatar_template").orEmpty(),
                animatedAvatar = props.getProperty("animated_avatar")?.takeIf { it.isNotBlank() },
                trustLevel = props.getProperty("trust_level")?.toIntOrNull() ?: 1,
                userTitle = props.getProperty("user_title")?.takeIf { it.isNotBlank() }
            )
        } else {
            null
        }
        val savedApiKey = props.getProperty("api_key")?.takeIf { it.isNotBlank() }
        val savedCookies = if (profile != null || savedApiKey != null) {
            props.getProperty("raw_cookies").orEmpty()
        } else {
            ""
        }
        UserApiKeyAuthManager.restorePersistedSession(
            profile = profile,
            apiKey = savedApiKey,
            rawCookies = savedCookies
        )

        LocalComposerDrafts.restore(props.getProperty("composer_drafts"))
        LocalComposerDrafts.onPersist = { encoded ->
            update { putOrRemove("composer_drafts", encoded) }
        }

        UserApiKeyAuthManager.openExternalUrlLauncher = { url ->
            runCatching { Desktop.getDesktop().browse(URI(url)) }.isSuccess
        }
        UserApiKeyAuthManager.onPersistPendingKey = { pkB64, nonce ->
            update {
                putOrRemove("pending_rsa_priv_b64", pkB64)
                putOrRemove("pending_rsa_nonce", nonce)
                setProperty("pending_rsa_created_at", UserApiKeyAuthManager.pendingAuthorizationCreatedAt().toString())
                setProperty("client_id", UserApiKeyAuthManager.getClientId())
            }
        }
        UserApiKeyAuthManager.onPersistUiPrefs = { fontScale, darkTheme, enableAnimations ->
            update {
                setProperty("ui_font_scale_v2", fontScale.toString())
                setProperty("ui_dark_theme", darkTheme.toString())
                setProperty("ui_enable_animations", enableAnimations.toString())
            }
        }
        UserApiKeyAuthManager.onPersistThemeAndDensity = { themeAccent, compactFeed ->
            update {
                setProperty("ui_theme_accent", themeAccent)
                setProperty("ui_compact_feed", compactFeed.toString())
            }
        }
        UserApiKeyAuthManager.onPersistReadTopics = { encoded ->
            update { putOrRemove("read_topic_posts", encoded) }
        }
        UserApiKeyAuthManager.onPersistSession = { currentProfile, apiKey ->
            update {
                putOrRemove("api_key", apiKey)
                val cookies = CookieSentinel.buildCookieHeader(targetHost = "linux.do")
                if (currentProfile != null && currentProfile.username != "linuxdo_user") {
                    setProperty("user_id", currentProfile.userId.toString())
                    setProperty("username", currentProfile.username)
                    setProperty("display_name", currentProfile.displayName)
                    setProperty("avatar_template", currentProfile.avatarTemplate)
                    putOrRemove("animated_avatar", currentProfile.animatedAvatar)
                    putOrRemove("user_title", currentProfile.userTitle)
                    setProperty("trust_level", currentProfile.trustLevel.toString())
                    putOrRemove("raw_cookies", cookies.takeIf { it.isNotBlank() })
                } else {
                    remove("user_id")
                    remove("username")
                    remove("display_name")
                    remove("avatar_template")
                    remove("animated_avatar")
                    remove("user_title")
                    remove("trust_level")
                    remove("raw_cookies")
                }
            }
        }
        UserApiKeyAuthManager.onLogoutPlatformClear = {
            update {
                remove("api_key")
                remove("username")
                remove("display_name")
                remove("avatar_template")
                remove("animated_avatar")
                remove("user_id")
                remove("user_title")
                remove("trust_level")
                remove("read_topic_posts")
                val remaining = CookieSentinel.buildCookieHeader(targetHost = "linux.do")
                putOrRemove("raw_cookies", remaining.takeIf { it.isNotBlank() })
            }
        }
    }
}

fun main() {
    DesktopStateStore.initAndWire()
    application {
        val windowState = rememberWindowState(
            size = DpSize(1240.dp, 820.dp),
            position = WindowPosition.Aligned(androidx.compose.ui.Alignment.Center)
        )
        Window(
            onCloseRequest = {
                LocalComposerDrafts.flushPendingPersist()
                exitApplication()
            },
            state = windowState,
            title = "Lindo — Linux.do High-Performance Client (macOS / Windows / Android)"
        ) {
            LinDoApp(onApplyAppIcon = null)
        }
    }
}
