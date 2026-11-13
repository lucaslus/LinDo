package do_.linux.lindo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import do_.linux.lindo.core.compose.LocalComposerDrafts
import do_.linux.lindo.core.connect.ConsolidatedTimingBatch
import do_.linux.lindo.core.connect.ScreenTrackEngine
import do_.linux.lindo.core.connect.UserPresenceService
import do_.linux.lindo.core.copy.LinDoCommonCopy
import do_.linux.lindo.core.copy.LinDoCopyEntry
import do_.linux.lindo.core.copy.LinDoFeedCopy
import do_.linux.lindo.core.copy.LinDoMessagesCopy
import do_.linux.lindo.core.diagnostics.DiagnosticCategory
import do_.linux.lindo.core.diagnostics.LinDoDiagnosticLogger
import do_.linux.lindo.core.network.AuthenticatedUserProfile
import do_.linux.lindo.core.network.CookieSentinel
import do_.linux.lindo.core.network.LinuxDoRepository
import do_.linux.lindo.core.network.PlatformMediaPicker
import do_.linux.lindo.core.network.UserApiKeyAuthManager
import do_.linux.lindo.ui.BackNavigationBridge
import do_.linux.lindo.ui.DesktopTrayCommand
import do_.linux.lindo.ui.LinDoApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.AWTEvent
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Desktop
import java.awt.Dimension
import java.awt.DisplayMode
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Font
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.Taskbar
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.Window as AwtWindow
import java.awt.desktop.AppReopenedListener
import java.awt.event.AWTEventListener
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.InputEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.geom.RoundRectangle2D
import java.awt.image.BaseMultiResolutionImage
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilenameFilter
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URL
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileSystemException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sign

internal enum class DesktopOsKind(val defaultUserAgent: String) {
    MACOS("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"),
    WINDOWS("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"),
    LINUX("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");

    companion object {
        val current: DesktopOsKind = run {
            val osName = System.getProperty("os.name").orEmpty()
            when {
                osName.contains("Mac", ignoreCase = true) -> MACOS
                osName.contains("Windows", ignoreCase = true) -> WINDOWS
                else -> LINUX
            }
        }
    }
}

internal val IS_MAC_OS: Boolean = DesktopOsKind.current == DesktopOsKind.MACOS

internal val IS_WINDOWS: Boolean = DesktopOsKind.current == DesktopOsKind.WINDOWS

private val SUPPORTS_POSIX: Boolean = runCatching {
    FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
}.getOrDefault(false)

@Volatile
internal var currentMainWindow: AwtWindow? = null

internal const val DESKTOP_MIN_WINDOW_WIDTH_DP = 420
internal const val DESKTOP_MIN_WINDOW_HEIGHT_DP = 580
internal const val DESKTOP_DEFAULT_WINDOW_WIDTH_DP = 1240
internal const val DESKTOP_DEFAULT_WINDOW_HEIGHT_DP = 820
private const val MAX_DESKTOP_UPLOAD_BYTES = 20L * 1024L * 1024L

internal fun defaultDesktopUserAgent(): String = DesktopOsKind.current.defaultUserAgent

internal fun extractDeepLinkArg(args: Array<String>): String? {
    for (raw in args) {
        val cleaned = raw.trim().trim('"', '\'').trim()
        if (cleaned.startsWith("discourse://", ignoreCase = true) ||
            cleaned.startsWith("lindo://", ignoreCase = true)
        ) {
            return cleaned
        }
    }
    return null
}

internal fun restrictFilePermissionsToOwner(file: File, isDirectory: Boolean = file.isDirectory) {
    if (!SUPPORTS_POSIX) return
    runCatching {
        val perms = PosixFilePermissions.fromString(if (isDirectory) "rwx------" else "rw-------")
        Files.setPosixFilePermissions(file.toPath(), perms)
    }
}

internal fun parseSafeExternalDesktopUri(rawUrl: String): URI? {
    val trimmed = rawUrl.trim()
    if (trimmed.isEmpty()) return null
    val uri = runCatching {
        val direct = URI(trimmed)
        URI(direct.toASCIIString())
    }.getOrElse {
        runCatching {
            val u = URL(trimmed)
            URI(URI(u.protocol, u.userInfo, u.host, u.port, u.path, u.query, u.ref).toASCIIString())
        }.getOrNull()
    } ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme !in setOf("http", "https", "mailto")) return null
    if ((scheme == "http" || scheme == "https") && uri.host.isNullOrBlank()) return null
    return uri
}

private val IMAGE_EXTENSION_SET = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "svg", "avif", "bmp", "ico", "heic", "heif"
)

internal fun guessDesktopMimeType(file: File): String {
    return when (file.extension.lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "avif" -> "image/avif"
        "bmp" -> "image/bmp"
        "ico" -> "image/x-icon"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        "gz", "tgz" -> "application/gzip"
        "7z" -> "application/x-7z-compressed"
        "json" -> "application/json"
        "md", "markdown" -> "text/markdown"
        "txt", "log" -> "text/plain"
        "csv" -> "text/csv"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "m4a" -> "audio/mp4"
        else -> runCatching { Files.probeContentType(file.toPath()) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "application/octet-stream"
    }
}

/**
 * 桌面单实例互斥与本地回环 (`127.0.0.1`) 深度链接转发桥：
 * - Windows / Linux 下点击 `discourse://auth_redirect` 或 `lindo://auth_redirect` 会拉起第二个进程并传入 CLI 参数；
 * - 次实例通过本地回环认证通道将深度链接转发给主实例并立即退出，杜绝双开窗口与遗漏一次性 OTP 凭证。
 */
internal object DesktopSingleInstanceBridge {
    private class PrimarySession(
        val raf: RandomAccessFile,
        val fileLock: FileLock,
        val server: ServerSocket,
        val endpointFile: File
    ) {
        val closed = AtomicBoolean(false)

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { server.close() }
            runCatching { if (fileLock.isValid) fileLock.release() }
            runCatching { raf.close() }
            runCatching { Files.deleteIfExists(endpointFile.toPath()) }
        }
    }

    private val stateLock = Any()
    private var activeSession: PrimarySession? = null

    fun acquireOrForward(
        lockDir: File = DesktopStateStore.stateDir,
        deepLink: String? = null,
        onDeepLink: (String) -> Unit = {},
        onActivate: () -> Unit = {}
    ): Boolean {
        runCatching {
            lockDir.mkdirs()
            restrictFilePermissionsToOwner(lockDir, isDirectory = true)
        }
        val lockFile = File(lockDir, "instance.lock")
        val endpointFile = File(lockDir, "instance.endpoint")

        var raf: RandomAccessFile? = null
        val fileLock: FileLock? = try {
            val opened = RandomAccessFile(lockFile, "rw")
            raf = opened
            restrictFilePermissionsToOwner(lockFile, isDirectory = false)
            opened.channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        } catch (_: Throwable) {
            null
        }

        if (fileLock != null && raf != null) {
            val server = try {
                ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
            } catch (_: Throwable) {
                runCatching { fileLock.release() }
                runCatching { raf.close() }
                return true
            }
            val token = UUID.randomUUID().toString()
            val tokenBytes = token.toByteArray(Charsets.UTF_8)
            runCatching {
                endpointFile.writeText("port=${server.localPort}\ntoken=$token\n", Charsets.UTF_8)
                restrictFilePermissionsToOwner(endpointFile, isDirectory = false)
            }
            val session = PrimarySession(
                raf = raf,
                fileLock = fileLock,
                server = server,
                endpointFile = endpointFile
            )
            synchronized(stateLock) {
                activeSession?.close()
                activeSession = session
            }
            Thread(
                {
                    while (!session.closed.get() && !server.isClosed) {
                        val client = try {
                            server.accept()
                        } catch (_: Throwable) {
                            break
                        }
                        try {
                            client.use { sock ->
                                sock.soTimeout = 1_500
                                val line = readBoundedUtf8Line(sock.getInputStream(), maxBytes = 8_192)
                                if (line != null && line.startsWith("AUTH ")) {
                                    val rest = line.substring(5)
                                    val sp = rest.indexOf(' ')
                                    if (sp > 0) {
                                        val incomingToken = rest.substring(0, sp)
                                        val payload = rest.substring(sp + 1).trim()
                                        if (MessageDigest.isEqual(
                                                incomingToken.toByteArray(Charsets.UTF_8),
                                                tokenBytes
                                            )
                                        ) {
                                            if (payload.startsWith("discourse://", ignoreCase = true) ||
                                                payload.startsWith("lindo://", ignoreCase = true)
                                            ) {
                                                runCatching { onDeepLink(payload) }
                                            }
                                            runCatching { onActivate() }
                                            runCatching {
                                                sock.getOutputStream().write("OK\n".toByteArray(Charsets.UTF_8))
                                                sock.getOutputStream().flush()
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (_: Throwable) {
                        }
                    }
                },
                "lindo-single-instance-ipc"
            ).apply {
                isDaemon = true
                start()
            }
            return true
        }

        runCatching { raf?.close() }
        val forwarded = forwardToPrimary(endpointFile, deepLink)
        return !forwarded
    }

    private fun forwardToPrimary(endpointFile: File, deepLink: String?): Boolean {
        if (!endpointFile.isFile) return false
        return runCatching {
            var port = 0
            var token = ""
            endpointFile.readLines(Charsets.UTF_8).forEach { rawLine ->
                val line = rawLine.trim()
                when {
                    line.startsWith("port=") -> port = line.substringAfter("port=").trim().toIntOrNull() ?: 0
                    line.startsWith("token=") -> token = line.substringAfter("token=").trim()
                }
            }
            if (port !in 1..65535 || token.isBlank()) return false
            val sanitizedPayload = deepLink
                ?.replace("\r", "")
                ?.replace("\n", "")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: "FOCUS"
            Socket().use { sock ->
                sock.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 1_200)
                sock.soTimeout = 1_200
                val out = sock.getOutputStream()
                out.write("AUTH $token $sanitizedPayload\n".toByteArray(Charsets.UTF_8))
                out.flush()
                runCatching {
                    sock.soTimeout = 250
                    sock.getInputStream().read()
                }
            }
            true
        }.getOrDefault(false)
    }

    private fun readBoundedUtf8Line(input: InputStream, maxBytes: Int): String? {
        val buf = ByteArrayOutputStream(minOf(maxBytes, 256))
        while (buf.size() < maxBytes) {
            val b = input.read()
            if (b < 0) {
                return if (buf.size() == 0) null else buf.toString(Charsets.UTF_8).trimEnd('\r')
            }
            if (b == '\n'.code) {
                return buf.toString(Charsets.UTF_8).trimEnd('\r')
            }
            buf.write(b)
        }
        return buf.toString(Charsets.UTF_8).trimEnd('\r')
    }

    fun close() {
        val session = synchronized(stateLock) {
            val s = activeSession
            activeSession = null
            s
        }
        session?.close()
    }
}

internal fun shouldRegisterDiscourseProtocolOnWindows(
    queryExitCode: Int,
    queryOutput: String,
    fileExists: (String) -> Boolean = { File(it).isFile }
): Boolean {
    if (queryExitCode != 0 || queryOutput.isBlank()) return true
    val regLine = queryOutput.lineSequence()
        .firstOrNull { it.contains("REG_SZ", ignoreCase = true) || it.contains("REG_EXPAND_SZ", ignoreCase = true) }
        ?: return true
    val valuePart = regLine.replace(Regex("""^.*?\bREG_(?:EXPAND_)?SZ\s+""", RegexOption.IGNORE_CASE), "").trim()
    if (valuePart.isEmpty()) return true
    val exePath = if (valuePart.startsWith('"')) {
        valuePart.substring(1).substringBefore('"').trim()
    } else {
        Regex("""([A-Za-z]:[^"\r\n]*?\.exe)""", RegexOption.IGNORE_CASE)
            .find(valuePart)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?: valuePart.substringBefore(' ').trim()
    }
    if (exePath.isEmpty()) return true
    if (exePath.endsWith("\\Lindo.exe", ignoreCase = true) ||
        exePath.endsWith("/Lindo.exe", ignoreCase = true) ||
        exePath.equals("Lindo.exe", ignoreCase = true)
    ) {
        return true
    }
    return !fileExists(exePath)
}

private fun ensureWindowsProtocolHandlersRegistered() {
    if (!IS_WINDOWS) return
    val appPath = System.getProperty("jpackage.app-path")
        ?.trim()
        ?.takeIf { it.endsWith(".exe", ignoreCase = true) && File(it).isFile }
        ?: return

    fun runRegCommand(vararg cmd: String): Pair<Int, String> {
        val proc = ProcessBuilder(*cmd)
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val finished = proc.waitFor(5, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            return -1 to out
        }
        return proc.exitValue() to out
    }

    fun registerScheme(scheme: String) {
        val baseKey = "HKCU\\Software\\Classes\\$scheme"
        runCatching {
            runRegCommand("reg.exe", "add", baseKey, "/ve", "/t", "REG_SZ", "/d", "URL:Lindo Protocol", "/f")
            runRegCommand("reg.exe", "add", baseKey, "/v", "URL Protocol", "/t", "REG_SZ", "/d", "", "/f")
            runRegCommand(
                "reg.exe", "add", "$baseKey\\DefaultIcon",
                "/ve", "/t", "REG_SZ", "/d", "\"$appPath\",0", "/f"
            )
            runRegCommand(
                "reg.exe", "add", "$baseKey\\shell\\open\\command",
                "/ve", "/t", "REG_SZ", "/d", "\"$appPath\" \"%1\"", "/f"
            )
        }
    }

    registerScheme("lindo")
    runCatching {
        val (exitCode, output) = runRegCommand(
            "reg.exe", "query", "HKCU\\Software\\Classes\\discourse\\shell\\open\\command", "/ve"
        )
        if (shouldRegisterDiscourseProtocolOnWindows(exitCode, output)) {
            registerScheme("discourse")
        }
    }
}

/**
 * 约束桌面窗口最小宽高（420×580dp）：
 * - 既允许用户将窗口缩窄至手机侧栏形态（420~639dp 单栏模式）贴靠屏幕边缘阅读，
 * - 又由原生窗口管理器（macOS NSWindow.minSize / Windows WM_GETMINMAXINFO）硬性拦截过窄拖拽，防止文字挤压成单字竖排。
 */
internal fun applyDesktopMinimumWindowSize(window: AwtWindow?) {
    if (window == null) return
    val minDim = Dimension(DESKTOP_MIN_WINDOW_WIDTH_DP, DESKTOP_MIN_WINDOW_HEIGHT_DP)
    runCatching {
        if (window.minimumSize != minDim) {
            window.minimumSize = minDim
        }
        if (window.width in 1 until DESKTOP_MIN_WINDOW_WIDTH_DP ||
            window.height in 1 until DESKTOP_MIN_WINDOW_HEIGHT_DP
        ) {
            window.setSize(
                max(window.width, DESKTOP_MIN_WINDOW_WIDTH_DP),
                max(window.height, DESKTOP_MIN_WINDOW_HEIGHT_DP)
            )
        }
    }
}

private object DesktopStateStore {
    private val lock = Any()
    val stateDir: File by lazy {
        File(System.getProperty("user.home"), ".lindo").apply {
            mkdirs()
            restrictFilePermissionsToOwner(this, isDirectory = true)
        }
    }
    val imageCacheDir: File by lazy {
        File(stateDir, "image_cache").apply { mkdirs() }
    }
    private val stateFile: File by lazy { File(stateDir, "session.properties") }

    private val store by lazy { DesktopPreferenceStore(stateFile) }
    fun load(): Properties = store.load()
    fun update(immediate: Boolean = false, mutator: Properties.() -> Unit) = store.update(immediate, mutator)
    fun flush() = store.flush()

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

        UserApiKeyAuthManager.customUserAgent =
            props.getProperty("custom_user_agent")?.takeIf { it.isNotBlank() } ?: defaultDesktopUserAgent()

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
        UserApiKeyAuthManager.uiSplitFraction =
            (props.getProperty("ui_split_fraction")?.toFloatOrNull() ?: 0.42f).coerceIn(0.28f, 0.62f)
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
        val rawSavedCookies = props.getProperty("raw_cookies").orEmpty()
        val savedCookies = if (profile != null || savedApiKey != null || rawSavedCookies.contains("_t=")) {
            rawSavedCookies
        } else {
            ""
        }
        UserApiKeyAuthManager.restorePersistedSession(
            profile = profile,
            apiKey = savedApiKey,
            rawCookies = savedCookies
        )
        if (UserApiKeyAuthManager.isAuthorized()) {
            props.getProperty("unread_notifications")
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
                ?.let { UserApiKeyAuthManager.updateCachedUnreadNotifications(it) }
        }

        LocalComposerDrafts.restore(props.getProperty("composer_drafts"))
        LocalComposerDrafts.onPersist = { encoded ->
            update { putOrRemove("composer_drafts", encoded) }
        }

        UserApiKeyAuthManager.openExternalUrlLauncher = { url ->
            val safeUri = parseSafeExternalDesktopUri(url)
            if (safeUri != null) {
                Thread(
                    {
                        runCatching {
                            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                                Desktop.getDesktop().browse(safeUri)
                            }
                        }
                    },
                    "lindo-external-browser"
                ).apply {
                    isDaemon = true
                    start()
                }
            }
        }
        PlatformMediaPicker.launchPicker = { mimeType, onPicked ->
            SwingUtilities.invokeLater {
                runCatching {
                    val dialog = FileDialog(
                        currentMainWindow as? Frame,
                        LinDoMessagesCopy.PICK_ATTACHMENT_DIALOG_TITLE.text,
                        FileDialog.LOAD
                    )
                    if (mimeType.startsWith("image/", ignoreCase = true)) {
                        dialog.filenameFilter = FilenameFilter { _, name ->
                            val ext = name.substringAfterLast('.', "").lowercase()
                            ext in IMAGE_EXTENSION_SET
                        }
                        if (IS_WINDOWS) {
                            dialog.file = "*.png;*.jpg;*.jpeg;*.gif;*.webp;*.svg;*.avif;*.bmp"
                        }
                    }
                    dialog.isVisible = true
                    val dir = dialog.directory
                    val fileName = dialog.file
                    if (dir != null && fileName != null) {
                        val selected = File(dir, fileName)
                        Thread(
                            {
                                runCatching {
                                    if (!selected.isFile) return@runCatching
                                    val size = selected.length()
                                    if (size <= 0L) return@runCatching
                                    val resolvedMime = guessDesktopMimeType(selected)
                                    if (size > MAX_DESKTOP_UPLOAD_BYTES) {
                                        SwingUtilities.invokeLater {
                                            onPicked(selected.name, resolvedMime, ByteArray(0))
                                        }
                                        return@runCatching
                                    }
                                    val bytes = selected.inputStream().use { input ->
                                        val out = ByteArrayOutputStream(size.toInt().coerceIn(4096, MAX_DESKTOP_UPLOAD_BYTES.toInt()))
                                        val buf = ByteArray(16 * 1024)
                                        var total = 0L
                                        while (true) {
                                            val n = input.read(buf)
                                            if (n < 0) break
                                            total += n
                                            if (total > MAX_DESKTOP_UPLOAD_BYTES) {
                                                return@use ByteArray(0)
                                            }
                                            out.write(buf, 0, n)
                                        }
                                        out.toByteArray()
                                    }
                                    SwingUtilities.invokeLater {
                                        onPicked(selected.name, resolvedMime, bytes)
                                    }
                                }
                            },
                            "lindo-media-picker-io"
                        ).apply {
                            isDaemon = true
                            start()
                        }
                    }
                }
            }
        }
        UserApiKeyAuthManager.onPersistPendingKey = { pkB64, nonce ->
            update(immediate = true) {
                putOrRemove("pending_rsa_priv_b64", pkB64)
                putOrRemove("pending_rsa_nonce", nonce)
                setProperty("pending_rsa_created_at", UserApiKeyAuthManager.pendingAuthorizationCreatedAt().toString())
                setProperty("client_id", UserApiKeyAuthManager.getClientId())
            }
        }
        UserApiKeyAuthManager.onPersistUiPrefs = { fontScale, darkTheme, enableAnimations ->
            update {
                setProperty(if (IS_MAC_OS) "ui_font_scale_mac_v3" else "ui_font_scale_v2", fontScale.toString())
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
        UserApiKeyAuthManager.onPersistSplitFraction = { splitFraction ->
            update {
                setProperty("ui_split_fraction", splitFraction.coerceIn(0.28f, 0.62f).toString())
            }
        }
        UserApiKeyAuthManager.onPersistReadTopics = { encoded ->
            update { putOrRemove("read_topic_posts", encoded) }
        }
        UserApiKeyAuthManager.onSyncCookieToPlatform = { rawSetCookie ->
            if (rawSetCookie.contains("_t=") ||
                rawSetCookie.contains("cf_clearance=") ||
                rawSetCookie.contains("_forum_session=")
            ) {
                val cookies = CookieSentinel.buildCookieHeader(targetHost = "linux.do")
                update {
                    putOrRemove("raw_cookies", cookies.takeIf { it.isNotBlank() })
                }
            }
        }
        UserApiKeyAuthManager.onPersistSession = { currentProfile, apiKey ->
            update {
                putOrRemove("api_key", apiKey)
                putOrRemove("custom_user_agent", UserApiKeyAuthManager.customUserAgent?.takeIf { it.isNotBlank() })
                val cookies = CookieSentinel.buildCookieHeader(targetHost = "linux.do")
                if (currentProfile != null && currentProfile.username != "linuxdo_user") {
                    setProperty("user_id", currentProfile.userId.toString())
                    setProperty("username", currentProfile.username)
                    setProperty("display_name", currentProfile.displayName)
                    setProperty("avatar_template", currentProfile.avatarTemplate)
                    putOrRemove("animated_avatar", currentProfile.animatedAvatar)
                    putOrRemove("user_title", currentProfile.userTitle)
                    setProperty("trust_level", currentProfile.trustLevel.toString())
                    setProperty("unread_notifications", UserApiKeyAuthManager.cachedUnreadNotificationCount.toString())
                } else {
                    remove("user_id")
                    remove("username")
                    remove("display_name")
                    remove("avatar_template")
                    remove("animated_avatar")
                    remove("user_title")
                    remove("trust_level")
                    remove("unread_notifications")
                }
                putOrRemove("raw_cookies", cookies.takeIf { it.isNotBlank() })
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
                remove("unread_notifications")
                remove("read_topic_posts")
                remove("composer_drafts")
                val remaining = CookieSentinel.buildCookieHeader(targetHost = "linux.do")
                putOrRemove("raw_cookies", remaining.takeIf { it.isNotBlank() })
            }
        }
    }
}

/**
 * Ghostty 风格一体化窗口栏配置：
 * 隐藏独立系统灰白标题栏文字与底色，使应用画布延伸至整个窗口顶部，并将窗口边框外观（Aqua / DarkAqua）与当前主题实时同步。
 */
private fun applyMacSeamlessWindowChrome(window: AwtWindow?, isDark: Boolean) {
    if (!IS_MAC_OS) return
    val appearance = if (isDark) "NSAppearanceNameDarkAqua" else "NSAppearanceNameAqua"
    runCatching {
        System.setProperty("apple.awt.application.appearance", appearance)
    }
    if (window == null) return
    val bg = if (isDark) Color(0x14, 0x14, 0x16) else Color(0xF8, 0xF8, 0xFA)
    runCatching { window.background = bg }
    val rootContainer = window as? RootPaneContainer ?: return
    val rootPane = rootContainer.rootPane ?: return
    runCatching {
        rootPane.background = bg
        rootPane.putClientProperty("apple.awt.windowAppearance", appearance)
        rootPane.putClientProperty("apple.awt.fullWindowContent", true)
        rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
        rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
        rootPane.putClientProperty("apple.awt.fullscreenable", true)
    }
}

private fun isMacWindowNativeFullScreen(window: AwtWindow): Boolean {
    if (!IS_MAC_OS) return false
    val gc = window.graphicsConfiguration ?: return false
    val screenBounds = gc.bounds ?: return false
    return window.width >= screenBounds.width && window.height >= screenBounds.height &&
        abs(window.x - screenBounds.x) <= 2 && abs(window.y - screenBounds.y) <= 2
}

/**
 * 为一体化无标题栏窗口顶部区域（避开左侧 🔴🟡🟢 红绿灯）提供原生顺滑拖拽移动与双击缩放支持，
 * 同时仅在指针实际产生 >= 4px 位移时才接管拖拽，不拦截顶部右侧按钮（如登录、收藏、菜单）的正常点击。
 */
private fun installMacTitleBarDragSupport(
    window: AwtWindow,
    dragZoneHeightPt: Int = 28,
    trafficLightsRightClearancePt: Int = 74
): () -> Unit {
    if (!IS_MAC_OS) return {}
    var armedInTitleZone = false
    var draggingWindow = false
    var pressScreenX = 0
    var pressScreenY = 0
    var startWindowX = 0
    var startWindowY = 0
    var restoreBoundsBeforeZoom: Rectangle? = null

    val listener = AWTEventListener { rawEvent ->
        val me = rawEvent as? MouseEvent ?: return@AWTEventListener
        val src = me.component ?: return@AWTEventListener
        val ownerWindow = if (src is AwtWindow) src else SwingUtilities.getWindowAncestor(src)
        if (ownerWindow !== window) return@AWTEventListener
        if (isMacWindowNativeFullScreen(window)) {
            armedInTitleZone = false
            draggingWindow = false
            return@AWTEventListener
        }

        val ptInWin = runCatching { SwingUtilities.convertPoint(src, me.point, window) }.getOrNull() ?: return@AWTEventListener
        val inTopDragBand = ptInWin.y in 0..dragZoneHeightPt && ptInWin.x >= trafficLightsRightClearancePt

        when (me.id) {
            MouseEvent.MOUSE_PRESSED -> {
                if (SwingUtilities.isLeftMouseButton(me) && inTopDragBand) {
                    armedInTitleZone = true
                    draggingWindow = false
                    pressScreenX = me.xOnScreen
                    pressScreenY = me.yOnScreen
                    startWindowX = window.x
                    startWindowY = window.y
                } else {
                    armedInTitleZone = false
                    draggingWindow = false
                }
            }
            MouseEvent.MOUSE_DRAGGED -> {
                if (armedInTitleZone) {
                    val dx = me.xOnScreen - pressScreenX
                    val dy = me.yOnScreen - pressScreenY
                    if (!draggingWindow && (abs(dx) + abs(dy) >= 4)) {
                        draggingWindow = true
                    }
                    if (draggingWindow) {
                        window.setLocation(startWindowX + dx, startWindowY + dy)
                        me.consume()
                    }
                }
            }
            MouseEvent.MOUSE_RELEASED -> {
                val wasDragging = draggingWindow
                armedInTitleZone = false
                draggingWindow = false
                if (wasDragging) {
                    me.consume()
                }
            }
            MouseEvent.MOUSE_CLICKED -> {
                if (SwingUtilities.isLeftMouseButton(me) && me.clickCount == 2 && inTopDragBand && window is Frame) {
                    val gc = window.graphicsConfiguration
                    val insets = if (gc != null) Toolkit.getDefaultToolkit().getScreenInsets(gc) else null
                    val screenBounds = gc?.bounds
                    if (screenBounds != null && insets != null) {
                        val maxRect = Rectangle(
                            screenBounds.x + insets.left,
                            screenBounds.y + insets.top,
                            screenBounds.width - insets.left - insets.right,
                            screenBounds.height - insets.top - insets.bottom
                        )
                        val cur = window.bounds
                        val nearlyZoomed = abs(cur.width - maxRect.width) <= 24 && abs(cur.height - maxRect.height) <= 24
                        if (nearlyZoomed && restoreBoundsBeforeZoom != null) {
                            window.bounds = restoreBoundsBeforeZoom!!
                            restoreBoundsBeforeZoom = null
                        } else {
                            restoreBoundsBeforeZoom = Rectangle(cur)
                            window.bounds = maxRect
                        }
                    } else {
                        window.extendedState = if (window.extendedState == Frame.MAXIMIZED_BOTH) {
                            Frame.NORMAL
                        } else {
                            Frame.MAXIMIZED_BOTH
                        }
                    }
                }
            }
        }
    }

    val mask = AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_MOTION_EVENT_MASK
    Toolkit.getDefaultToolkit().addAWTEventListener(listener, mask)
    return {
        runCatching { Toolkit.getDefaultToolkit().removeAWTEventListener(listener) }
    }
}

private class SmoothFrameWheelEvent(
    source: Component,
    id: Int,
    `when`: Long,
    modifiersEx: Int,
    x: Int,
    y: Int,
    xAbs: Int,
    yAbs: Int,
    clickCount: Int,
    popupTrigger: Boolean,
    scrollType: Int,
    scrollAmount: Int,
    wheelRotation: Int,
    preciseWheelRotation: Double
) : MouseWheelEvent(
    source,
    id,
    `when`,
    modifiersEx,
    x,
    y,
    xAbs,
    yAbs,
    clickCount,
    popupTrigger,
    scrollType,
    scrollAmount,
    wheelRotation,
    preciseWheelRotation
)

/**
 * Frame-driven smoothing for recognized notched wheels. Fractional trackpad
 * input keeps the native path. The macOS 0.1 ratio is a conservative heuristic,
 * not a guarantee for every mouse/driver. Event frequency is not a device type.
 */
internal object DesktopSmoothWheelEngine {
    private var installed = false

    @Volatile
    private var trackedWindow: AwtWindow? = null

    private var activeTargetHz: Int = if (IS_MAC_OS) 120 else 60
    private val frameRequests = Channel<Unit>(Channel.CONFLATED)

    // 以下状态仅在 AWT EventDispatchThread（亦即 Compose Desktop UI 主线程）访问
    private var remainingUnitsY: Double = 0.0
    private var remainingUnitsX: Double = 0.0
    private var lastFrameNano: Long = 0L
    private var lastRawEventMs: Long = 0L
    private var lastNotchMs: Long = 0L
    private var fastNotchVelocity: Double = 1.0
    private var trackpadGestureActive: Boolean = false

    private var anchorComponent: Component? = null
    private var anchorX: Int = 0
    private var anchorY: Int = 0
    private var anchorXAbs: Int = 0
    private var anchorYAbs: Int = 0
    private var anchorBaseModifiers: Int = 0

    fun attachToWindow(window: AwtWindow?) {
        if (trackedWindow !== window) {
            remainingUnitsY = 0.0
            remainingUnitsX = 0.0
            lastFrameNano = 0L
            lastRawEventMs = 0L
            lastNotchMs = 0L
            fastNotchVelocity = 1.0
            trackpadGestureActive = false
            anchorComponent = null
            while (frameRequests.tryReceive().isSuccess) { }
        }
        trackedWindow = window
        if (!installed) {
            installed = true
            runCatching {
                Toolkit.getDefaultToolkit().systemEventQueue.push(object : EventQueue() {
                    override fun dispatchEvent(event: AWTEvent) {
                        if (event is MouseWheelEvent && event !is SmoothFrameWheelEvent) {
                            if (interceptRawWheelIfNeeded(event)) {
                                return
                            }
                        }
                        super.dispatchEvent(event)
                    }
                })
            }
        }
    }

    @Composable
    fun VSyncDriver() {
        // Keep one coroutine for this window. Additional notches only add motion;
        // they must not cancel the frame await or reset its elapsed time.
        LaunchedEffect(Unit) {
            for (request in frameRequests) {
                lastFrameNano = 0L
                do {
                    val keepGoing = withFrameNanos { frameTimeNanos ->
                        while (frameRequests.tryReceive().isSuccess) { /* coalesce wakeups, not motion */ }
                        stepOnVsyncFrame(frameTimeNanos)
                    }
                } while (keepGoing)
            }
        }
    }

    internal fun clampRefreshRateHz(reported: Int, isMacOs: Boolean = IS_MAC_OS): Int {
        return if (reported == DisplayMode.REFRESH_RATE_UNKNOWN || reported <= 0) {
            if (isMacOs) 120 else 60
        } else {
            reported.coerceIn(60, 240)
        }
    }

    internal fun isTrackpadInitialFrame(rawInt: Int, rawPrecise: Double): Boolean {
        val absPrecise = abs(rawPrecise)
        return rawInt == 0 && absPrecise in 1e-4..0.49
    }

    /** Preserve continuous trackpad input; never reject a macOS notch just because it arrived quickly. */
    internal fun isDiscreteMouseWheelNotch(
        rawInt: Int,
        rawPrecise: Double,
        dtMs: Long,
        trackpadActive: Boolean,
        isMacOs: Boolean = IS_MAC_OS
    ): Boolean {
        if (trackpadActive || rawInt == 0) return false
        return if (isMacOs) {
            abs(rawPrecise - rawInt * 0.1) < 1e-3
        } else {
            abs(rawPrecise - rawInt.toDouble()) < 1e-3 && dtMs >= 10L
        }
    }

    internal fun computeWheelStepDelta(
        rawInt: Int,
        rawPrecise: Double,
        velocity: Double = 1.0,
        isMacOs: Boolean = IS_MAC_OS
    ): Double {
        val absPrecise = abs(rawPrecise)
        if (absPrecise < 1e-5 && rawInt == 0) return 0.0
        val direction = when {
            absPrecise > 1e-4 -> sign(rawPrecise)
            else -> rawInt.toDouble().sign
        }
        if (direction == 0.0) return 0.0
        return if (isMacOs) {
            if (rawInt == 0 && absPrecise < 1.0) {
                direction * absPrecise * velocity
            } else {
                // MacOSCocoaConfig 每 1.0 precise 单位 (scrollAmount=1) 对应 10dp；
                // 单格慢速滚轮映射约 5.4 单位 (~54dp)，连续快速拨动平滑加速。
                val normalizedNotch = max(1.0, absPrecise / 0.12).coerceAtMost(3.2)
                val step = (5.4 + (normalizedNotch - 1.0) * 1.8) * velocity
                direction * step.coerceAtMost(26.0)
            }
        } else {
            // Windows / Linux: 仅在实体离散刻度 (rawInt != 0) 时应用 max(1.0, absPrecise)，绝不放大子刻度精确触控板微幅位移！
            val notches = if (rawInt != 0) {
                max(1.0, absPrecise).coerceAtMost(4.0)
            } else {
                absPrecise.coerceAtMost(4.0)
            }
            direction * notches * 1.15 * velocity
        }
    }

    private fun resolveTargetRefreshHz(window: AwtWindow?): Int {
        val reported = runCatching {
            val dev = window?.graphicsConfiguration?.device
                ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
            dev?.displayMode?.refreshRate ?: DisplayMode.REFRESH_RATE_UNKNOWN
        }.getOrDefault(DisplayMode.REFRESH_RATE_UNKNOWN)
        return clampRefreshRateHz(reported)
    }

    private fun interceptRawWheelIfNeeded(event: MouseWheelEvent): Boolean {
        val targetWindow = trackedWindow ?: return false
        val src = event.component ?: return false
        val owner = if (src is AwtWindow) src else SwingUtilities.getWindowAncestor(src)
        if (owner !== targetWindow) return false

        // 保留 Ctrl / Cmd / Option 组合滚轮（如缩放快捷键）原样行为
        if (event.isControlDown || event.isMetaDown || event.isAltDown) {
            return false
        }
        if (event.scrollType == MouseWheelEvent.WHEEL_BLOCK_SCROLL) {
            return false
        }

        // Use event creation time, not the time an overloaded EDT finally handles it.
        val nowMs = event.`when`.takeIf { it > 0L } ?: System.currentTimeMillis()
        val dtMs = if (lastRawEventMs == 0L) Long.MAX_VALUE else (nowMs - lastRawEventMs).coerceAtLeast(0L)
        lastRawEventMs = nowMs

        val rawPrecise = event.preciseWheelRotation
        val absPrecise = abs(rawPrecise)
        val rawInt = event.wheelRotation

        if (absPrecise < 1e-5 && rawInt == 0) {
            return false
        }

        // 更新触控板连续手势窗口状态（160ms 内保持原生触控板直通锁）
        val looksLikeContinuousTrackpad = if (IS_MAC_OS) {
            rawInt == 0 || abs(rawPrecise - rawInt * 0.1) >= 1e-3
        } else {
            isTrackpadInitialFrame(rawInt, rawPrecise) || abs(rawPrecise - rawInt.toDouble()) >= 1e-3
        }
        if (dtMs > 160L) {
            trackpadGestureActive = looksLikeContinuousTrackpad
        } else if (looksLikeContinuousTrackpad) {
            trackpadGestureActive = true
        }

        if (!isDiscreteMouseWheelNotch(rawInt, rawPrecise, dtMs, trackpadGestureActive, IS_MAC_OS)) {
            if (remainingUnitsY != 0.0 || remainingUnitsX != 0.0) {
                remainingUnitsY = 0.0
                remainingUnitsX = 0.0
            }
            return false
        }

        val direction = when {
            absPrecise > 1e-4 -> sign(rawPrecise)
            else -> rawInt.toDouble().sign
        }
        if (direction == 0.0) return false

        val notchGapMs = if (lastNotchMs == 0L) Long.MAX_VALUE else (nowMs - lastNotchMs).coerceAtLeast(0L)
        lastNotchMs = nowMs
        fastNotchVelocity = when {
            notchGapMs <= 45L -> (fastNotchVelocity * 1.28).coerceAtMost(2.55)
            notchGapMs <= 95L -> (fastNotchVelocity * 1.14).coerceAtMost(2.10)
            notchGapMs <= 180L -> max(1.0, fastNotchVelocity * 0.90)
            else -> 1.0
        }

        val deltaUnits = computeWheelStepDelta(
            rawInt = rawInt,
            rawPrecise = rawPrecise,
            velocity = fastNotchVelocity
        )
        if (deltaUnits == 0.0) return false

        anchorComponent = src
        anchorX = event.x
        anchorY = event.y
        anchorXAbs = event.xOnScreen
        anchorYAbs = event.yOnScreen
        anchorBaseModifiers = event.modifiersEx and InputEvent.SHIFT_DOWN_MASK.inv()

        if (event.isShiftDown) {
            if (remainingUnitsX != 0.0 && sign(remainingUnitsX) != direction) {
                remainingUnitsX = 0.0
            }
            remainingUnitsX = (remainingUnitsX + deltaUnits).coerceIn(-120.0, 120.0)
        } else {
            if (remainingUnitsY != 0.0 && sign(remainingUnitsY) != direction) {
                remainingUnitsY = 0.0
            }
            remainingUnitsY = (remainingUnitsY + deltaUnits).coerceIn(-140.0, 140.0)
        }

        if (lastFrameNano == 0L) activeTargetHz = resolveTargetRefreshHz(targetWindow)
        frameRequests.trySend(Unit)
        return true
    }

    private fun stepOnVsyncFrame(frameTimeNanos: Long): Boolean {
        val comp = anchorComponent
        if (comp == null || !comp.isShowing) {
            remainingUnitsY = 0.0
            remainingUnitsX = 0.0
            lastFrameNano = 0L
            return false
        }

        val dtSec = if (lastFrameNano == 0L) {
            1.0 / activeTargetHz.coerceIn(60, 240).toDouble()
        } else {
            ((frameTimeNanos - lastFrameNano) / 1_000_000_000.0).coerceIn(0.002, 0.032)
        }
        lastFrameNano = frameTimeNanos

        // 指数弹簧阻尼系数 k = 22.0：锁定真实 VSync 帧间隔推进子像素步长
        val alpha = 1.0 - exp(-dtSec * 22.0)

        var active = false
        if (abs(remainingUnitsY) > 0.012) {
            val stepY = if (abs(remainingUnitsY) < 0.045) {
                remainingUnitsY
            } else {
                remainingUnitsY * alpha
            }
            remainingUnitsY -= stepY
            dispatchSyntheticWheelStep(comp, stepY, horizontal = false)
            if (abs(remainingUnitsY) > 0.012) active = true else remainingUnitsY = 0.0
        } else {
            remainingUnitsY = 0.0
        }

        if (abs(remainingUnitsX) > 0.012) {
            val stepX = if (abs(remainingUnitsX) < 0.045) {
                remainingUnitsX
            } else {
                remainingUnitsX * alpha
            }
            remainingUnitsX -= stepX
            dispatchSyntheticWheelStep(comp, stepX, horizontal = true)
            if (abs(remainingUnitsX) > 0.012) active = true else remainingUnitsX = 0.0
        } else {
            remainingUnitsX = 0.0
        }

        if (!active) {
            lastFrameNano = 0L
        }
        return active
    }

    private fun dispatchSyntheticWheelStep(comp: Component, units: Double, horizontal: Boolean) {
        // 确保 preciseWheelRotation 与 wheelRotation(0) 差值 > 0.001，触发 Compose 子像素即时位移分支
        val safeUnits = if (abs(units) < 0.002) sign(units) * 0.002 else units
        val mods = if (horizontal) {
            anchorBaseModifiers or InputEvent.SHIFT_DOWN_MASK
        } else {
            anchorBaseModifiers
        }
        val synthetic = SmoothFrameWheelEvent(
            source = comp,
            id = MouseEvent.MOUSE_WHEEL,
            `when` = System.currentTimeMillis(),
            modifiersEx = mods,
            x = anchorX,
            y = anchorY,
            xAbs = anchorXAbs,
            yAbs = anchorYAbs,
            clickCount = 0,
            popupTrigger = false,
            scrollType = MouseWheelEvent.WHEEL_UNIT_SCROLL,
            scrollAmount = 1,
            wheelRotation = 0,
            preciseWheelRotation = safeUnits
        )
        runCatching { comp.dispatchEvent(synthetic) }
    }
}

private enum class DesktopTrayMenuItemKind(
    val defaultLabelCopy: LinDoCopyEntry,
    val separatorAfter: Boolean = false
) {
    OPEN_MAIN(LinDoMessagesCopy.TRAY_OPEN_LINDO),
    MESSAGES(LinDoFeedCopy.NOTIFICATIONS_DESC),
    PUBLISH_TOPIC(LinDoMessagesCopy.TRAY_PUBLISH_NEW_TOPIC, separatorAfter = true),
    QUIT(LinDoMessagesCopy.TRAY_QUIT_LINDO);

    fun formatLabel(unreadCount: Int = 0): String {
        if (this == MESSAGES && unreadCount > 0) {
            val badge = if (unreadCount > 99) LinDoCommonCopy.BADGE_OVERFLOW_99.text else unreadCount.toString()
            return LinDoMessagesCopy.TRAY_MESSAGES_UNREAD_ITEM.format(badge)
        }
        return defaultLabelCopy.text
    }
}

/**
 * 桌面托盘图标与 Dock 未读角标渲染管理：
 * 支持 macOS Retina 2x 菜单栏图标、实时红色未读通知角标、Dock 图标角标同步与快捷操作菜单。
 */
internal object DesktopTrayController {
    internal val TRAY_VARIANT_SIZES = intArrayOf(16, 20, 22, 24, 32, 44, 48)

    private val baseMark: BufferedImage? by lazy {
        runCatching {
            DesktopTrayController::class.java.getResourceAsStream("/tray_mark.png")?.use { ImageIO.read(it) }
        }.getOrNull()
    }

    private var trayIcon: TrayIcon? = null
    private var unreadMenuItem: MenuItem? = null
    private var lastRenderedUnread: Int = -1
    private var lastRenderedUsername: String? = null
    @Volatile
    private var lastOpenTriggerMs: Long = 0L

    private fun dispatchDebouncedOpen(
        onOpenMainWindow: () -> Unit,
        onOpenMessages: () -> Unit
    ) {
        val now = System.currentTimeMillis()
        if (now - lastOpenTriggerMs < 250L) return
        lastOpenTriggerMs = now
        SwingUtilities.invokeLater {
            if (lastRenderedUnread > 0) onOpenMessages() else onOpenMainWindow()
        }
    }

    fun install(
        onOpenMainWindow: () -> Unit,
        onOpenMessages: () -> Unit,
        onOpenPublish: () -> Unit,
        onQuitApp: () -> Unit
    ): Boolean {
        if (!SystemTray.isSupported()) return false
        if (trayIcon != null) return true
        return runCatching {
            val popup = PopupMenu()
            val menuFont = if (DesktopOsKind.current == DesktopOsKind.WINDOWS) {
                Font("Microsoft YaHei UI", Font.PLAIN, 12)
            } else {
                null
            }
            if (menuFont != null) {
                popup.font = menuFont
            }
            var messagesItemRef: MenuItem? = null
            DesktopTrayMenuItemKind.entries.forEach { itemKind ->
                val action: () -> Unit = when (itemKind) {
                    DesktopTrayMenuItemKind.OPEN_MAIN -> onOpenMainWindow
                    DesktopTrayMenuItemKind.MESSAGES -> onOpenMessages
                    DesktopTrayMenuItemKind.PUBLISH_TOPIC -> onOpenPublish
                    DesktopTrayMenuItemKind.QUIT -> onQuitApp
                }
                val item = MenuItem(itemKind.formatLabel()).apply {
                    if (menuFont != null) font = menuFont
                    addActionListener { SwingUtilities.invokeLater(action) }
                }
                if (itemKind == DesktopTrayMenuItemKind.MESSAGES) {
                    messagesItemRef = item
                }
                popup.add(item)
                if (itemKind.separatorAfter) {
                    popup.addSeparator()
                }
            }

            val initialImage = buildMultiResTrayImage(unreadCount = 0)
            val icon = TrayIcon(initialImage, LinDoMessagesCopy.TRAY_TOOLTIP_DEFAULT.text, popup).apply {
                isImageAutoSize = DesktopOsKind.current != DesktopOsKind.MACOS
                if (DesktopOsKind.current == DesktopOsKind.MACOS) {
                    addActionListener {
                        dispatchDebouncedOpen(onOpenMainWindow, onOpenMessages)
                    }
                } else {
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseReleased(e: MouseEvent) {
                            if (e.button == MouseEvent.BUTTON1 && !e.isPopupTrigger) {
                                dispatchDebouncedOpen(onOpenMainWindow, onOpenMessages)
                            }
                        }
                    })
                }
            }
            SystemTray.getSystemTray().add(icon)
            trayIcon = icon
            unreadMenuItem = messagesItemRef
            lastRenderedUnread = 0
            true
        }.getOrDefault(false)
    }

    fun updateUnreadState(unreadCount: Int, username: String?, targetWindow: AwtWindow? = currentMainWindow) {
        val normalized = unreadCount.coerceAtLeast(0)
        // 同步更新 macOS Dock / Windows 任务栏角标
        runCatching {
            if (Taskbar.isTaskbarSupported()) {
                val taskbar = Taskbar.getTaskbar()
                if (taskbar.isSupported(Taskbar.Feature.ICON_BADGE_TEXT)) {
                    val badgeText = when {
                        normalized <= 0 -> null
                        normalized > 99 -> LinDoCommonCopy.BADGE_OVERFLOW_99.text
                        else -> normalized.toString()
                    }
                    taskbar.setIconBadge(badgeText)
                }
                if (DesktopOsKind.current == DesktopOsKind.WINDOWS &&
                    targetWindow != null &&
                    taskbar.isSupported(Taskbar.Feature.ICON_BADGE_IMAGE_WINDOW)
                ) {
                    val badgeImg = renderWindowsTaskbarBadgeImage(normalized)
                    SwingUtilities.invokeLater {
                        runCatching {
                            taskbar.setWindowIconBadge(targetWindow, badgeImg)
                        }
                    }
                }
            }
        }

        val icon = trayIcon ?: return
        if (normalized == lastRenderedUnread && username == lastRenderedUsername) return
        lastRenderedUnread = normalized
        lastRenderedUsername = username

        SwingUtilities.invokeLater {
            runCatching {
                icon.image = buildMultiResTrayImage(normalized)
                icon.toolTip = buildString {
                    append(LinDoCommonCopy.APP_NAME.text)
                    if (!username.isNullOrBlank()) append(" · ${LinDoCommonCopy.AT_USERNAME.format(username)}")
                    if (normalized > 0) append(LinDoMessagesCopy.TRAY_UNREAD_SUFFIX.format(normalized))
                }
                unreadMenuItem?.label = DesktopTrayMenuItemKind.MESSAGES.formatLabel(normalized)
            }
        }
    }

    fun uninstall() {
        val icon = trayIcon ?: return
        trayIcon = null
        unreadMenuItem = null
        runCatching { SystemTray.getSystemTray().remove(icon) }
    }

    internal fun buildMultiResTrayImage(unreadCount: Int): Image {
        if (DesktopOsKind.current == DesktopOsKind.MACOS) {
            return BaseMultiResolutionImage(*intArrayOf(22, 44, 66).map { renderMacTrayCanvas(it, unreadCount) }.toTypedArray())
        }
        val variants = TRAY_VARIANT_SIZES.map { sizePx ->
            renderTrayCanvas(sizePx = sizePx, scale = sizePx / 22f, unreadCount = unreadCount)
        }.toTypedArray()
        return BaseMultiResolutionImage(*variants)
    }

    private val macTemplate by lazy {
        DesktopTrayController::class.java.getResourceAsStream("/tray_mascot_template.png")!!.use { ImageIO.read(it) }
    }
    internal fun renderMacTrayCanvas(sizePx: Int, unreadCount: Int): BufferedImage {
        val image = BufferedImage(sizePx, sizePx, BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            drawImage(macTemplate, 0, 0, sizePx, sizePx, null)
            if (unreadCount > 0) {
                scale(sizePx / 22.0, sizePx / 22.0)
                composite = java.awt.AlphaComposite.Clear
                fill(java.awt.geom.Ellipse2D.Double(16.0, 0.5, 5.0, 5.0))
                composite = java.awt.AlphaComposite.SrcOver
                color = Color.BLACK
                fill(java.awt.geom.Ellipse2D.Double(17.0, 1.5, 3.0, 3.0))
            }
            dispose()
        }
        return image
    }

    internal fun renderWindowsTaskbarBadgeImage(unreadCount: Int): Image? {
        if (unreadCount <= 0) return null
        val img16 = renderWindowsTaskbarBadgeCanvas(sizePx = 16, unreadCount = unreadCount)
        val img32 = renderWindowsTaskbarBadgeCanvas(sizePx = 32, unreadCount = unreadCount)
        return BaseMultiResolutionImage(img16, img32)
    }

    private fun renderWindowsTaskbarBadgeCanvas(sizePx: Int, unreadCount: Int): BufferedImage {
        val out = BufferedImage(sizePx, sizePx, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val label = if (unreadCount > 99) LinDoCommonCopy.BADGE_OVERFLOW_99.text else unreadCount.toString()
            val inset = (sizePx * 0.06f).coerceAtLeast(1f)
            val d = sizePx - inset * 2f
            g.color = Color(0xFF, 0xFF, 0xFF, 235)
            g.fill(RoundRectangle2D.Float(0f, 0f, sizePx.toFloat(), sizePx.toFloat(), sizePx.toFloat(), sizePx.toFloat()))
            g.color = Color(0xFF, 0x3B, 0x30)
            g.fill(RoundRectangle2D.Float(inset, inset, d, d, d, d))

            val fontPx = when {
                label.length >= 3 -> sizePx * 0.40f
                label.length == 2 -> sizePx * 0.48f
                else -> sizePx * 0.58f
            }.roundToInt().coerceAtLeast(8)
            g.font = Font("SansSerif", Font.BOLD, fontPx)
            val fm = g.fontMetrics
            val tx = (sizePx - fm.stringWidth(label)) / 2f
            val ty = (sizePx - fm.height) / 2f + fm.ascent
            g.color = Color.WHITE
            g.drawString(label, tx, ty)
        } finally {
            g.dispose()
        }
        return out
    }

    internal fun renderTrayCanvas(sizePx: Int, scale: Float, unreadCount: Int): BufferedImage {
        val out = BufferedImage(sizePx, sizePx, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            val mark = baseMark
            if (mark != null) {
                val pad = (2.0f * scale).roundToInt()
                val drawSize = sizePx - pad * 2
                g.drawImage(mark, pad, pad, drawSize, drawSize, null)
            } else {
                val pad = (3f * scale).roundToInt()
                g.color = Color(0xF5, 0xB8, 0x2E)
                g.fillOval(pad, pad, sizePx - pad * 2, sizePx - pad * 2)
            }

            if (unreadCount > 0) {
                val label = when {
                    unreadCount > 9 -> LinDoCommonCopy.BADGE_OVERFLOW_9.text
                    else -> unreadCount.toString()
                }
                val badgeH = (10.5f * scale).roundToInt().coerceAtLeast(10)
                val badgeW = if (label.length > 1) {
                    (13.5f * scale).roundToInt().coerceAtLeast(13)
                } else {
                    badgeH
                }
                val bx = (sizePx - badgeW - (0.5f * scale).roundToInt()).coerceAtLeast(0)
                val by = (0.5f * scale).roundToInt()

                // 外描边增强深浅菜单栏对比度
                val halo = (1.2f * scale).coerceAtLeast(1f)
                g.color = Color(0x18, 0x18, 0x1B, 225)
                g.stroke = BasicStroke(halo * 2f)
                g.draw(
                    RoundRectangle2D.Float(
                        bx.toFloat(),
                        by.toFloat(),
                        badgeW.toFloat(),
                        badgeH.toFloat(),
                        badgeH.toFloat(),
                        badgeH.toFloat()
                    )
                )

                // Apple HIG System Red 角标底色
                g.color = Color(0xFF, 0x3B, 0x30)
                g.fill(
                    RoundRectangle2D.Float(
                        bx.toFloat(),
                        by.toFloat(),
                        badgeW.toFloat(),
                        badgeH.toFloat(),
                        badgeH.toFloat(),
                        badgeH.toFloat()
                    )
                )

                val fontPx = (if (label.length > 1) 6.8f else 7.6f) * scale
                g.font = Font("SansSerif", Font.BOLD, fontPx.roundToInt().coerceAtLeast(7))
                val fm = g.fontMetrics
                val tx = bx + (badgeW - fm.stringWidth(label)) / 2f
                val ty = by + (badgeH - fm.height) / 2f + fm.ascent - (0.2f * scale)
                g.color = Color.WHITE
                g.drawString(label, tx, ty)
            }
        } finally {
            g.dispose()
        }
        return out
    }
}

internal fun isDesktopBackShortcut(event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    if (event.key == Key.Escape &&
        !event.isAltPressed &&
        !event.isMetaPressed &&
        !event.isCtrlPressed &&
        !event.isShiftPressed
    ) {
        return true
    }
    if (event.key == Key.DirectionLeft &&
        event.isAltPressed &&
        !event.isMetaPressed &&
        !event.isCtrlPressed &&
        !event.isShiftPressed
    ) {
        return true
    }
    if (event.key == Key.LeftBracket &&
        event.isMetaPressed &&
        !event.isAltPressed &&
        !event.isCtrlPressed &&
        !event.isShiftPressed
    ) {
        return true
    }
    return false
}

private fun installDesktopMouseBackSupport(window: AwtWindow): () -> Unit {
    val listener = AWTEventListener { rawEvent ->
        val me = rawEvent as? MouseEvent ?: return@AWTEventListener
        if (me.id != MouseEvent.MOUSE_PRESSED || me.button != 4 || me.isConsumed) return@AWTEventListener
        val src = me.component ?: return@AWTEventListener
        val ownerWindow = if (src is AwtWindow) src else SwingUtilities.getWindowAncestor(src)
        if (ownerWindow !== window) return@AWTEventListener
        me.consume()
        SwingUtilities.invokeLater {
            BackNavigationBridge.dispatchBack()
        }
    }
    Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK)
    return {
        runCatching { Toolkit.getDefaultToolkit().removeAWTEventListener(listener) }
    }
}

private fun flushDesktopStateBeforeQuit() {
    runCatching { LocalComposerDrafts.flushPendingPersist() }
    runCatching {
        val popped = ScreenTrackEngine.flushCurrentTopic()
        if (popped == null && !ScreenTrackEngine.hasPendingWork()) return@runCatching
        if (!UserApiKeyAuthManager.isAuthorized()) return@runCatching
        runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(400L) {
                var next: ConsolidatedTimingBatch? = popped ?: ScreenTrackEngine.popNextSendableBatch()
                while (next != null) {
                    val batch = next
                    var status: Int? = null
                    try {
                        status = LinuxDoRepository.sendTopicTimings(batch)
                    } finally {
                        ScreenTrackEngine.onBatchSendResult(batch, status)
                    }
                    if (status == null || status !in 200..299) break
                    next = ScreenTrackEngine.popNextSendableBatch()
                }
            }
        }
    }
}

fun main(args: Array<String> = emptyArray()) {
    // 在 Skiko / AWT 初始化前注入高刷渲染与 GPU 缓存调优参数：
    // 消除 macOS Metal / Windows DirectX 在连续触控板/滚轮重绘时等待上一帧 VSync 导致的 60fps 减半锁帧
    if (System.getProperty("skiko.vsync.enabled") == null) {
        System.setProperty("skiko.vsync.enabled", "true")
    }
    if (System.getProperty("skiko.rendering.macos.waitForPreviousFrameVsyncOnRedrawImmediately") == null) {
        System.setProperty("skiko.rendering.macos.waitForPreviousFrameVsyncOnRedrawImmediately", "false")
    }
    if (System.getProperty("skiko.rendering.windows.waitForVsyncOnRedrawImmediately") == null) {
        System.setProperty("skiko.rendering.windows.waitForVsyncOnRedrawImmediately", "false")
    }
    if (System.getProperty("skiko.gpu.resourceCacheLimit") == null) {
        System.setProperty("skiko.gpu.resourceCacheLimit", "268435456")
    }

    MacKeyboardState.warmUp()
    val initialDeepLink = extractDeepLinkArg(args)
    var reopenMainWindowAction: (() -> Unit)? = null
    var quitApplicationAction: (() -> Unit)? = null
    val stateInitLatch = CountDownLatch(1)

    val isPrimaryInstance = DesktopSingleInstanceBridge.acquireOrForward(
        lockDir = DesktopStateStore.stateDir,
        deepLink = initialDeepLink,
        onDeepLink = { uri ->
            runCatching { stateInitLatch.await(2, TimeUnit.SECONDS) }
            UserApiKeyAuthManager.handleAuthRedirectUri(uri)
        },
        onActivate = {
            SwingUtilities.invokeLater { reopenMainWindowAction?.invoke() }
        }
    )
    if (!isPrimaryInstance) {
        return
    }

    val logsDir = File(DesktopStateStore.stateDir, "logs")
    val desktopPlatformSummary = buildString {
        append(System.getProperty("os.name").orEmpty())
        append(' ')
        append(System.getProperty("os.version").orEmpty())
        append(" (")
        append(System.getProperty("os.arch").orEmpty())
        append(", JVM ")
        append(System.getProperty("java.version").orEmpty())
        append(')')
    }
    LinDoDiagnosticLogger.configurePersistence(logsDir, platformInfo = desktopPlatformSummary)
    restrictFilePermissionsToOwner(logsDir, isDirectory = true)
    LinDoDiagnosticLogger.installUncaughtExceptionHandler()
    LinDoDiagnosticLogger.exportSaveHandler = { suggestedFileName, content, onResult ->
        SwingUtilities.invokeLater {
            try {
                val ownerFrame = currentMainWindow as? Frame
                val dialog = FileDialog(
                    ownerFrame,
                    LinDoMessagesCopy.EXPORT_DIAG_DIALOG_TITLE.text,
                    FileDialog.SAVE
                ).apply {
                    file = suggestedFileName
                    isVisible = true
                }
                val dir = dialog.directory
                val fileName = dialog.file
                if (dir != null && fileName != null) {
                    val outFile = File(dir, fileName)
                    outFile.writeText(content, Charsets.UTF_8)
                    LinDoDiagnosticLogger.info(
                        DiagnosticCategory.SYSTEM,
                        "LogExport",
                        "Exported diagnostic report to ${outFile.name} (${content.length} chars)"
                    )
                    onResult(true, LinDoMessagesCopy.EXPORT_SUCCESS_PATH.format(outFile.absolutePath))
                } else {
                    onResult(false, LinDoMessagesCopy.EXPORT_CANCELLED.text)
                }
            } catch (err: Throwable) {
                onResult(false, LinDoMessagesCopy.EXPORT_FAILED_REASON.format(err.message ?: err::class.simpleName))
            }
        }
    }

    Runtime.getRuntime().addShutdownHook(
        Thread({ DesktopSingleInstanceBridge.close() }, "lindo-single-instance-cleanup")
    )

    when (DesktopOsKind.current) {
        DesktopOsKind.MACOS -> {
            System.setProperty("apple.awt.application.name", LinDoCommonCopy.APP_NAME.text)
            System.setProperty("apple.awt.enableTemplateImages", "true")
        }
        DesktopOsKind.WINDOWS -> {
            Thread(::ensureWindowsProtocolHandlersRegistered, "lindo-win-protocol-reg").apply {
                isDaemon = true
                start()
            }
        }
        DesktopOsKind.LINUX -> Unit
    }

    DesktopStateStore.initAndWire()
    if (IS_MAC_OS) {
        MacDesktopSettings.install(DesktopStateStore.load()) { change -> DesktopStateStore.update(mutator = change) }
        MacBrowserTransport.install(DesktopStateStore.load().getProperty("mac_browser_session") == "true", { enabled ->
            DesktopStateStore.update { setProperty("mac_browser_session", enabled.toString()) }
        }, { currentMainWindow?.apply { isVisible = true; toFront(); requestFocus() } })
    }
    Runtime.getRuntime().addShutdownHook(Thread({ runCatching { DesktopStateStore.flush() } }, "lindo-preferences-flush"))
    stateInitLatch.countDown()
    if (initialDeepLink != null) {
        UserApiKeyAuthManager.handleAuthRedirectUri(initialDeepLink)
    }
    applyMacSeamlessWindowChrome(window = null, isDark = UserApiKeyAuthManager.uiDarkTheme)

    runCatching {
        if (Desktop.isDesktopSupported()) {
            val desktop = Desktop.getDesktop()
            if (desktop.isSupported(Desktop.Action.APP_OPEN_URI)) {
                desktop.setOpenURIHandler { event ->
                    event.uri?.toString()?.let { uri ->
                        UserApiKeyAuthManager.handleAuthRedirectUri(uri)
                        SwingUtilities.invokeLater { reopenMainWindowAction?.invoke() }
                    }
                }
            }
            desktop.addAppEventListener(AppReopenedListener {
                SwingUtilities.invokeLater { reopenMainWindowAction?.invoke() }
            })
            if (desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
                desktop.setQuitHandler { _, response ->
                    flushDesktopStateBeforeQuit()
                    DesktopTrayController.uninstall()
                    DesktopSingleInstanceBridge.close()
                    quitApplicationAction?.invoke()
                    response.performQuit()
                }
            }
        }
    }

    application {
        val windowState = rememberWindowState(
            size = DpSize(DESKTOP_DEFAULT_WINDOW_WIDTH_DP.dp, DESKTOP_DEFAULT_WINDOW_HEIGHT_DP.dp),
            position = WindowPosition.Aligned(Alignment.Center)
        )
        var isWindowVisible by remember { mutableStateOf(true) }
        var isMacFullScreen by remember { mutableStateOf(false) }
        var trayInstalled by remember { mutableStateOf(false) }
        var pendingTrayCommand by remember { mutableStateOf<DesktopTrayCommand?>(null) }
        var activeWindowRef by remember { mutableStateOf<AwtWindow?>(null) }
        var latestUnreadCount by remember { mutableIntStateOf(UserApiKeyAuthManager.cachedUnreadNotificationCount) }
        var latestUsername by remember {
            mutableStateOf(
                UserApiKeyAuthManager.currentUser?.username?.takeIf { it.isNotBlank() && it != "linuxdo_user" }
            )
        }

        fun restoreAndFocusWindow() {
            isWindowVisible = true
            UserPresenceService.setAppForeground(true)
            SwingUtilities.invokeLater {
                val win = activeWindowRef ?: return@invokeLater
                if (win is Frame && (win.extendedState and Frame.ICONIFIED) != 0) {
                    win.extendedState = win.extendedState and Frame.ICONIFIED.inv()
                }
                if (!win.isVisible) {
                    win.isVisible = true
                }
                runCatching {
                    if (Desktop.isDesktopSupported() &&
                        Desktop.getDesktop().isSupported(Desktop.Action.APP_REQUEST_FOREGROUND)
                    ) {
                        Desktop.getDesktop().requestForeground(false)
                    }
                }
                win.toFront()
                win.requestFocus()
            }
        }

        fun quitCleanly() {
            flushDesktopStateBeforeQuit()
            DesktopTrayController.uninstall()
            DesktopSingleInstanceBridge.close()
            exitApplication()
        }

        reopenMainWindowAction = ::restoreAndFocusWindow
        quitApplicationAction = ::quitCleanly

        DisposableEffect(Unit) {
            trayInstalled = DesktopTrayController.install(
                onOpenMainWindow = { restoreAndFocusWindow() },
                onOpenMessages = {
                    pendingTrayCommand = DesktopTrayCommand.OPEN_MESSAGES
                    restoreAndFocusWindow()
                },
                onOpenPublish = {
                    pendingTrayCommand = DesktopTrayCommand.OPEN_PUBLISH
                    restoreAndFocusWindow()
                },
                onQuitApp = { quitCleanly() }
            )
            if (latestUnreadCount > 0 || !latestUsername.isNullOrBlank()) {
                DesktopTrayController.updateUnreadState(latestUnreadCount, latestUsername, activeWindowRef)
            }
            onDispose {
                DesktopTrayController.uninstall()
            }
        }

        // 窗口退入后台期间，每 60 秒静默轮询一次未读通知数，实时刷新菜单栏托盘角标与 Dock 角标
        LaunchedEffect(isWindowVisible) {
            if (isWindowVisible) return@LaunchedEffect
            while (!isWindowVisible) {
                delay(60_000L)
                if (isWindowVisible) break
                if (!UserApiKeyAuthManager.isAuthorized() || LinuxDoRepository.isChallengeCooldownActive()) {
                    continue
                }
                val unread = runCatching {
                    LinuxDoRepository.refreshUnreadNotifications(background = true)
                }.getOrNull() ?: runCatching {
                    LinuxDoRepository.fetchCurrentUserProfile(background = true)?.unreadNotifications
                }.getOrNull()
                if (unread != null) {
                    latestUnreadCount = unread
                    latestUsername = UserApiKeyAuthManager.currentUser?.username
                        ?.takeIf { it.isNotBlank() && it != "linuxdo_user" }
                        ?: latestUsername
                    DesktopTrayController.updateUnreadState(latestUnreadCount, latestUsername, activeWindowRef)
                    UserApiKeyAuthManager.persistSessionQuietly()
                }
            }
        }

        Window(
            visible = isWindowVisible,
            onCloseRequest = {
                LocalComposerDrafts.flushPendingPersist()
                if (IS_MAC_OS || trayInstalled) {
                    isWindowVisible = false
                    UserPresenceService.setAppForeground(false)
                } else {
                    val win = activeWindowRef
                    if (win is Frame) {
                        win.extendedState = Frame.ICONIFIED
                    } else {
                        quitCleanly()
                    }
                }
            },
            state = windowState,
            title = LinDoCommonCopy.APP_NAME.text,
            onKeyEvent = { event ->
                if (isDesktopBackShortcut(event)) {
                    BackNavigationBridge.dispatchBack()
                } else {
                    false
                }
            }
        ) {
            DisposableEffect(window) {
                activeWindowRef = window
                currentMainWindow = window
                applyDesktopMinimumWindowSize(window)
                applyMacSeamlessWindowChrome(window, isDark = UserApiKeyAuthManager.uiDarkTheme)
                DesktopSmoothWheelEngine.attachToWindow(window)
                val removeDrag = installMacTitleBarDragSupport(window)
                val removeMouseBack = installDesktopMouseBackSupport(window)
                if (latestUnreadCount > 0) {
                    DesktopTrayController.updateUnreadState(latestUnreadCount, latestUsername, window)
                }

                val compListener = object : ComponentAdapter() {
                    override fun componentResized(e: ComponentEvent?) {
                        applyDesktopMinimumWindowSize(window)
                        isMacFullScreen = isMacWindowNativeFullScreen(window)
                    }

                    override fun componentMoved(e: ComponentEvent?) {
                        isMacFullScreen = isMacWindowNativeFullScreen(window)
                    }
                }
                val winListener = object : WindowAdapter() {
                    override fun windowActivated(e: WindowEvent?) {
                        if (isWindowVisible) {
                            UserPresenceService.setAppForeground(true)
                        }
                    }
                }
                window.addComponentListener(compListener)
                window.addWindowListener(winListener)
                isMacFullScreen = isMacWindowNativeFullScreen(window)

                onDispose {
                    removeDrag()
                    removeMouseBack()
                    window.removeComponentListener(compListener)
                    window.removeWindowListener(winListener)
                    if (activeWindowRef === window) {
                        activeWindowRef = null
                    }
                    if (currentMainWindow === window) {
                        DesktopSmoothWheelEngine.attachToWindow(null)
                        currentMainWindow = null
                    }
                }
            }

            LaunchedEffect(window, isWindowVisible) {
                if (isWindowVisible) {
                    applyDesktopMinimumWindowSize(window)
                    applyMacSeamlessWindowChrome(window, isDark = UserApiKeyAuthManager.uiDarkTheme)
                }
            }

            val topTitleInset = if (IS_MAC_OS && !isMacFullScreen) 28.dp else 0.dp
            DesktopSmoothWheelEngine.VSyncDriver()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onKeyEvent { event ->
                        if (isDesktopBackShortcut(event)) {
                            BackNavigationBridge.dispatchBack()
                        } else {
                            false
                        }
                    }
            ) {
                LinDoApp(
                    onThemeChanged = { isDark ->
                        applyMacSeamlessWindowChrome(window, isDark = isDark)
                    },
                    onApplyAppIcon = null,
                    imageCacheDir = DesktopStateStore.imageCacheDir,
                    macTitleBarInset = topTitleInset,
                    onUnreadStateChanged = { unreadCount, username ->
                        latestUnreadCount = unreadCount
                        latestUsername = username
                        DesktopTrayController.updateUnreadState(unreadCount, username, window)
                        DesktopStateStore.update {
                            if (UserApiKeyAuthManager.isAuthorized()) {
                                setProperty("unread_notifications", unreadCount.coerceAtLeast(0).toString())
                            }
                        }
                    },
                    externalDesktopCommand = pendingTrayCommand,
                    onConsumeDesktopCommand = { pendingTrayCommand = null }
                )
            }
        }
    }
}
