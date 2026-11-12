package do_.linux.lindo

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import do_.linux.lindo.core.compose.LocalComposerDrafts
import do_.linux.lindo.core.connect.UserPresenceService
import do_.linux.lindo.core.network.AuthenticatedUserProfile
import do_.linux.lindo.core.network.CookieSentinel
import do_.linux.lindo.core.network.LinuxDoRepository
import do_.linux.lindo.core.network.UserApiKeyAuthManager
import do_.linux.lindo.ui.DesktopTrayCommand
import do_.linux.lindo.ui.LinDoApp
import kotlinx.coroutines.delay
import java.awt.AWTEvent
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Desktop
import java.awt.Dimension
import java.awt.EventQueue
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
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
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
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

private val IS_MAC_OS: Boolean =
    System.getProperty("os.name").orEmpty().contains("Mac", ignoreCase = true)

internal const val DESKTOP_MIN_WINDOW_WIDTH_DP = 420
internal const val DESKTOP_MIN_WINDOW_HEIGHT_DP = 580
internal const val DESKTOP_DEFAULT_WINDOW_WIDTH_DP = 1240
internal const val DESKTOP_DEFAULT_WINDOW_HEIGHT_DP = 820

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
    val imageCacheDir: File by lazy {
        File(stateDir, "image_cache").apply { mkdirs() }
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
                } else {
                    remove("user_id")
                    remove("username")
                    remove("display_name")
                    remove("avatar_template")
                    remove("animated_avatar")
                    remove("user_title")
                    remove("trust_level")
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
                remove("read_topic_posts")
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
 * 144Hz+ 高刷平滑滚轮引擎：
 *
 * 根因修复：OpenJDK macOS (`CPlatformResponder$DeltaAccumulator`) 在使用实体鼠标滚轮（`SCROLL_PHASE_UNSUPPORTED`）时，
 * 会同时发出 `wheelRotation = ±1` 与极小的 `preciseWheelRotation = ±0.1`、`scrollAmount = 1`。
 * Compose Desktop (`DesktopScrollable.desktop.kt`) 检查 `abs(precise - wheelRotation) > 0.001` 为 true，
 * 误判实体鼠标滚轮为触控板而关闭平滑插值，且每格仅步进 `0.1 * 10dp = 1dp`，在 144Hz 屏幕上呈现明显顿挫。
 *
 * 本引擎通过标准 `EventQueue` 在事件到达组件前精准区分：
 * 1. 原生触控板连续手势（首帧 `wheelRotation == 0 && abs(precise) < 0.5` 或帧间隔 `<= 14ms`）—— 零延迟原样直通；
 * 2. 实体鼠标离散滚轮刻度 —— 吸收原始跳变刻度，按显示器真实刷新率（144Hz–240Hz）以纳米级 `dt` 指数阻尼积分驱动子像素平滑滚动。
 */
private object DesktopSmoothWheelEngine {
    private var installed = false

    @Volatile
    private var trackedWindow: AwtWindow? = null

    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "lindo-144hz-wheel-driver").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY - 1
        }
    }

    private var frameTask: ScheduledFuture<*>? = null
    private var activeFramePeriodNs: Long = 0L
    private val edtDispatchPending = AtomicBoolean(false)

    // 以下状态仅在 AWT EventDispatchThread 访问
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

    private fun resolveTargetRefreshHz(window: AwtWindow?): Int {
        val reported = runCatching {
            val dev = window?.graphicsConfiguration?.device
                ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
            dev?.displayMode?.refreshRate ?: 0
        }.getOrDefault(0)
        return when {
            reported >= 60 -> reported.coerceIn(120, 240)
            else -> 144
        }
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

        val nowMs = System.currentTimeMillis()
        val dtMs = if (lastRawEventMs == 0L) Long.MAX_VALUE else (nowMs - lastRawEventMs).coerceAtLeast(0L)
        lastRawEventMs = nowMs

        val rawPrecise = event.preciseWheelRotation
        val absPrecise = abs(rawPrecise)
        val rawInt = event.wheelRotation

        if (absPrecise < 1e-5 && rawInt == 0) {
            return false
        }

        // 精准识别 Apple 触控板 / Magic Mouse 连续手势：
        // macOS CPlatformResponder 在触控板 SCROLL_PHASE_BEGAN/CONTINUED 且小位移时 wheelRotation == 0 而 precise != 0；
        // 实体滚轮 SCROLL_PHASE_UNSUPPORTED 则强制将非零位移设为 wheelRotation = ±1。
        val isTrackpadInitialSignature = IS_MAC_OS && rawInt == 0 && absPrecise in 1e-4..0.49
        if (dtMs > 130L) {
            trackpadGestureActive = isTrackpadInitialSignature
        } else if (isTrackpadInitialSignature || (IS_MAC_OS && dtMs <= 13L && abs(rawPrecise - rawInt) <= 0.501)) {
            trackpadGestureActive = true
        }

        if (trackpadGestureActive) {
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

        val deltaUnits = if (IS_MAC_OS) {
            // MacOSCocoaConfig 每 1.0 precise 单位 (scrollAmount=1) 对应 10dp；
            // 单格慢速滚轮映射约 6.0 单位 (~60dp)，连续快速拨动平滑加速至 ~150dp/格。
            val normalizedNotch = max(1.0, absPrecise / 0.12).coerceAtMost(3.2)
            val step = (5.4 + (normalizedNotch - 1.0) * 1.8) * fastNotchVelocity
            direction * step.coerceAtMost(26.0)
        } else {
            // Windows / Linux: 将整数格转换为高帧率浮点 precise 增量 (scrollAmount=1)
            val notches = max(1.0, absPrecise).coerceAtMost(4.0)
            direction * notches * 1.15 * fastNotchVelocity
        }

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

        ensureHighRefreshLoopRunning(targetWindow)
        return true
    }

    private fun ensureHighRefreshLoopRunning(window: AwtWindow) {
        val hz = resolveTargetRefreshHz(window)
        val periodNs = (1_000_000_000L / hz).coerceIn(4_166_666L, 8_333_333L)
        val currentTask = frameTask
        if (currentTask != null && !currentTask.isDone && activeFramePeriodNs == periodNs) {
            return
        }
        currentTask?.cancel(false)
        activeFramePeriodNs = periodNs
        lastFrameNano = System.nanoTime()
        frameTask = scheduler.scheduleAtFixedRate(
            {
                if (edtDispatchPending.compareAndSet(false, true)) {
                    SwingUtilities.invokeLater {
                        try {
                            stepOnEdt()
                        } finally {
                            edtDispatchPending.set(false)
                        }
                    }
                }
            },
            0L,
            periodNs,
            TimeUnit.NANOSECONDS
        )
    }

    private fun stopLoopOnEdt() {
        frameTask?.cancel(false)
        frameTask = null
        lastFrameNano = 0L
    }

    private fun stepOnEdt() {
        val comp = anchorComponent
        if (comp == null || !comp.isShowing) {
            remainingUnitsY = 0.0
            remainingUnitsX = 0.0
            stopLoopOnEdt()
            return
        }

        val nowNano = System.nanoTime()
        val dtSec = if (lastFrameNano == 0L) {
            1.0 / 144.0
        } else {
            ((nowNano - lastFrameNano) / 1_000_000_000.0).coerceIn(0.001, 0.032)
        }
        lastFrameNano = nowNano

        // 指数弹簧阻尼系数 k = 22.0：在 144Hz 下每帧推进约 14.1%，丝滑贴合人眼高刷惯性曲线
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
            stopLoopOnEdt()
        }
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

/**
 * 桌面托盘图标与 Dock 未读角标渲染管理：
 * 支持 macOS Retina 2x 菜单栏图标、实时红色未读通知角标、Dock 图标角标同步与快捷操作菜单。
 */
internal object DesktopTrayController {
    private val baseMark: BufferedImage? by lazy {
        runCatching {
            DesktopTrayController::class.java.getResourceAsStream("/tray_mark.png")?.use { ImageIO.read(it) }
        }.getOrNull()
    }

    private var trayIcon: TrayIcon? = null
    private var unreadMenuItem: MenuItem? = null
    private var lastRenderedUnread: Int = -1
    private var lastRenderedUsername: String? = null

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
            val openItem = MenuItem("打开 Lindo").apply {
                addActionListener { SwingUtilities.invokeLater(onOpenMainWindow) }
            }
            val messagesItem = MenuItem("消息通知").apply {
                addActionListener { SwingUtilities.invokeLater(onOpenMessages) }
            }
            val publishItem = MenuItem("发布新话题...").apply {
                addActionListener { SwingUtilities.invokeLater(onOpenPublish) }
            }
            val quitItem = MenuItem("退出 Lindo").apply {
                addActionListener { SwingUtilities.invokeLater(onQuitApp) }
            }
            popup.add(openItem)
            popup.add(messagesItem)
            popup.add(publishItem)
            popup.addSeparator()
            popup.add(quitItem)

            val initialImage = buildMultiResTrayImage(unreadCount = 0)
            val icon = TrayIcon(initialImage, "Lindo — Linux.do 原生客户端", popup).apply {
                isImageAutoSize = false
                addActionListener {
                    SwingUtilities.invokeLater {
                        if (lastRenderedUnread > 0) onOpenMessages() else onOpenMainWindow()
                    }
                }
                if (!IS_MAC_OS) {
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent) {
                            if (SwingUtilities.isLeftMouseButton(e)) {
                                SwingUtilities.invokeLater {
                                    if (lastRenderedUnread > 0) onOpenMessages() else onOpenMainWindow()
                                }
                            }
                        }
                    })
                }
            }
            SystemTray.getSystemTray().add(icon)
            trayIcon = icon
            unreadMenuItem = messagesItem
            lastRenderedUnread = 0
            true
        }.getOrDefault(false)
    }

    fun updateUnreadState(unreadCount: Int, username: String?) {
        val normalized = unreadCount.coerceAtLeast(0)
        // 同步更新 macOS Dock / Windows 任务栏角标
        runCatching {
            if (Taskbar.isTaskbarSupported()) {
                val taskbar = Taskbar.getTaskbar()
                if (taskbar.isSupported(Taskbar.Feature.ICON_BADGE_TEXT)) {
                    val badgeText = when {
                        normalized <= 0 -> null
                        normalized > 99 -> "99+"
                        else -> normalized.toString()
                    }
                    taskbar.setIconBadge(badgeText)
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
                    append("Lindo")
                    if (!username.isNullOrBlank()) append(" · @$username")
                    if (normalized > 0) append("（${normalized} 条未读消息）")
                }
                unreadMenuItem?.label = if (normalized > 0) {
                    "消息通知（${if (normalized > 99) "99+" else normalized} 条未读）"
                } else {
                    "消息通知"
                }
            }
        }
    }

    fun uninstall() {
        val icon = trayIcon ?: return
        trayIcon = null
        unreadMenuItem = null
        runCatching { SystemTray.getSystemTray().remove(icon) }
    }

    private fun buildMultiResTrayImage(unreadCount: Int): Image {
        val img1x = renderTrayCanvas(sizePx = 22, scale = 1f, unreadCount = unreadCount)
        val img2x = renderTrayCanvas(sizePx = 44, scale = 2f, unreadCount = unreadCount)
        return BaseMultiResolutionImage(img1x, img2x)
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
                    unreadCount > 9 -> "9+"
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

fun main() {
    if (IS_MAC_OS) {
        System.setProperty("apple.awt.application.name", "Lindo")
    }
    DesktopStateStore.initAndWire()
    applyMacSeamlessWindowChrome(window = null, isDark = UserApiKeyAuthManager.uiDarkTheme)

    var reopenMainWindowAction: (() -> Unit)? = null
    var quitApplicationAction: (() -> Unit)? = null

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
                    LocalComposerDrafts.flushPendingPersist()
                    DesktopTrayController.uninstall()
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
        var latestUnreadCount by remember { mutableIntStateOf(0) }
        var latestUsername by remember { mutableStateOf<String?>(null) }

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
            LocalComposerDrafts.flushPendingPersist()
            DesktopTrayController.uninstall()
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
            onDispose {
                DesktopTrayController.uninstall()
            }
        }

        // 窗口退入后台期间，每 90 秒静默轮询一次未读通知数，实时刷新菜单栏托盘角标与 Dock 角标
        LaunchedEffect(isWindowVisible) {
            if (isWindowVisible) return@LaunchedEffect
            while (!isWindowVisible) {
                delay(90_000L)
                if (isWindowVisible) break
                if (!UserApiKeyAuthManager.isAuthorized() || LinuxDoRepository.isChallengeCooldownActive()) {
                    continue
                }
                val profile = runCatching {
                    LinuxDoRepository.fetchCurrentUserProfile(background = true)
                }.getOrNull()
                if (profile != null) {
                    latestUnreadCount = profile.unreadNotifications
                    latestUsername = profile.username.takeIf { it.isNotBlank() }
                    DesktopTrayController.updateUnreadState(latestUnreadCount, latestUsername)
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
            title = "Lindo"
        ) {
            DisposableEffect(window) {
                activeWindowRef = window
                applyDesktopMinimumWindowSize(window)
                applyMacSeamlessWindowChrome(window, isDark = UserApiKeyAuthManager.uiDarkTheme)
                DesktopSmoothWheelEngine.attachToWindow(window)
                val removeDrag = installMacTitleBarDragSupport(window)

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
                    window.removeComponentListener(compListener)
                    window.removeWindowListener(winListener)
                    if (activeWindowRef === window) {
                        activeWindowRef = null
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
                    DesktopTrayController.updateUnreadState(unreadCount, username)
                },
                externalDesktopCommand = pendingTrayCommand,
                onConsumeDesktopCommand = { pendingTrayCommand = null }
            )
        }
    }
}
