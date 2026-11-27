package do_.linux.lindo

import do_.linux.lindo.ui.VerificationDialogResult
import do_.linux.lindo.ui.PlatformSessionRecovery
import do_.linux.lindo.core.network.AndroidForumRead
import do_.linux.lindo.core.network.AndroidVerificationTarget
import do_.linux.lindo.core.network.ForumConnectionStage
import coil3.disk.directory
import do_.linux.lindo.core.network.SavedAccounts
import do_.linux.lindo.core.network.AccountSessionGate

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import do_.linux.lindo.core.network.AuthenticatedUserProfile
import do_.linux.lindo.core.network.CF_MITIGATED_CHALLENGE_PREFIX
import do_.linux.lindo.core.network.CfClearanceAuthority
import do_.linux.lindo.core.network.CookieSentinel
import do_.linux.lindo.core.network.NetworkRecoveryPolicy
import do_.linux.lindo.core.network.LinuxDoRepository
import do_.linux.lindo.core.network.PlatformMediaPicker
import do_.linux.lindo.core.network.QrLoginManager
import do_.linux.lindo.core.network.UserApiKeyAuthManager
import do_.linux.lindo.ui.PlatformQrScanner
import do_.linux.lindo.core.site.LinuxDoSiteRules
import do_.linux.lindo.ui.AppAppearance
import do_.linux.lindo.ui.LinDoIconBackground
import do_.linux.lindo.ui.BackNavigationBridge
import do_.linux.lindo.ui.LinDoReadRegressionPreview
import do_.linux.lindo.ui.LinDoApp
import do_.linux.lindo.ui.LinDoBrandWordmark
import do_.linux.lindo.ui.LinDoMascotLoginTransitionView
import do_.linux.lindo.ui.LinDoLoadingIndicator
import do_.linux.lindo.ui.LinDoMascotGallery
import do_.linux.lindo.ui.LinDoMascotState
import do_.linux.lindo.ui.LinDoStatePanel
import do_.linux.lindo.ui.LocalLinDoDarkTheme
import do_.linux.lindo.ui.LinDoWelcomeScreen
import do_.linux.lindo.core.connect.ScreenTrackEngine
import do_.linux.lindo.core.connect.UserPresenceService
import do_.linux.lindo.core.copy.DebugPreviewScene
import do_.linux.lindo.core.copy.LinDoAuthCopy
import do_.linux.lindo.core.copy.LinDoCommonCopy
import do_.linux.lindo.core.copy.LinDoMessagesCopy
import do_.linux.lindo.core.copy.LinDoNetworkCopy
import do_.linux.lindo.core.diagnostics.DiagnosticCategory
import do_.linux.lindo.core.diagnostics.LinDoDiagnosticLogger
import do_.linux.lindo.core.update.AppUpdateManager
import do_.linux.lindo.ui.PlatformWebLoginHost
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.cancel

private fun getAllCookieValues(rawCookieHeader: String?, targetName: String): List<String> {
    if (rawCookieHeader.isNullOrBlank()) return emptyList()
    val values = mutableListOf<String>()
    rawCookieHeader.split(';').forEach { segment ->
        val trimmed = segment.trim()
        val eq = trimmed.indexOf('=')
        if (eq > 0) {
            val name = trimmed.substring(0, eq).trim()
            if (name == targetName) {
                trimmed.substring(eq + 1).trim().takeIf { it.isNotEmpty() }?.let { values += it }
            }
        }
    }
    return values
}

private fun getCookieValue(rawCookieHeader: String?, targetName: String): String? {
    return getAllCookieValues(rawCookieHeader, targetName).lastOrNull()
}

private fun hasCookieNamed(rawCookieHeader: String?, targetName: String): Boolean {
    val v = getCookieValue(rawCookieHeader, targetName) ?: return false
    return !(targetName == "_t" && v == "demo_session_token")
}

private fun csrfOwnerTag(rawCookieHeader: String?): String {
    val t = getCookieValue(rawCookieHeader, "_t")?.takeUnless { it == "demo_session_token" }.orEmpty()
    val fs = getCookieValue(rawCookieHeader, "_forum_session").orEmpty()
    return if (t.isEmpty() && fs.isEmpty()) "" else "${t.hashCode()}_${t.length}_${fs.hashCode()}_${fs.length}"
}

private fun releaseNativeWebView(view: WebView, rendererGone: Boolean = false) {
    (view.parent as? ViewGroup)?.removeView(view)
    if (!rendererGone) {
        view.stopLoading()
        view.removeJavascriptInterface("LinDoJsBridge")
        view.webChromeClient = null
        view.webViewClient = WebViewClient()
        view.loadUrl("about:blank")
    }
    view.destroy()
}

class MainActivity : ComponentActivity() {
    companion object {
        private const val REBUILD_ABORTED_STATUS = -2
        private const val REBUILD_ABORTED_BODY = "__REBUILD_ABORTED__"
        private const val BRIDGE_BOOTSTRAP_URL = "https://linux.do/about"
        private const val ROTATED_PROFILE_PREFIX = "lindo_net_"

        @Volatile
        internal var activeInstance: MainActivity? = null

        @Volatile
        private var activeWebViewProfileName: String = androidx.webkit.Profile.DEFAULT_PROFILE_NAME

        @Volatile
        private var activeProfileCookieManager: CookieManager? = null

        @Volatile
        private var rotatedProfileSeq: Int = 0

        @Volatile
        private var lastProfileRotateAtMs: Long = 0L

        internal fun isMultiProfileSupported(): Boolean = runCatching {
            androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.MULTI_PROFILE)
        }.getOrDefault(false)

        internal fun currentProfileName(): String = activeWebViewProfileName

        internal fun currentWebViewCookieManager(): CookieManager =
            activeProfileCookieManager ?: CookieManager.getInstance()

        /**
         * 专用后台 Looper 线程处理所有 Android `CookieManager` IPC（getCookie / setCookie / flush），
         * 借鉴 FluxDO Android 掉帧治理经验：彻底撤离 UI 主线程，杜绝 120Hz 滚动与 vsync 竞争。
         */
        private val cookieIoThread: android.os.HandlerThread by lazy {
            android.os.HandlerThread("LindoCookieIo").apply { start() }
        }

        private val cookieIoHandler: android.os.Handler by lazy {
            android.os.Handler(cookieIoThread.looper)
        }

        internal val cookieIoDispatcher: kotlinx.coroutines.CoroutineDispatcher by lazy {
            object : kotlinx.coroutines.CoroutineDispatcher() {
                override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                    cookieIoHandler.post(block)
                }
            }
        }

        internal fun postOnCookieIo(block: () -> Unit) {
            cookieIoHandler.post { runCatching(block) }
        }

        internal suspend fun <T> runOnCookieIo(block: suspend () -> T): T =
            withContext(cookieIoDispatcher) { block() }

        internal fun bindWebViewToActiveProfile(wv: WebView) {
            val profileName = activeWebViewProfileName
            if (profileName != androidx.webkit.Profile.DEFAULT_PROFILE_NAME && isMultiProfileSupported()) {
                runCatching { androidx.webkit.WebViewCompat.setProfile(wv, profileName) }
            }
        }

        /** 单实例静默→前台原位升舱暂存池：防止静默验证检测到交互式 Turnstile 后销毁并二次重载 `/challenge` */
        @Volatile
        private var handoffVerifyWebView: WebView? = null
        @Volatile
        private var handoffVerifyWebViewAtMs: Long = 0L
        @Volatile
        private var handoffVerifyTransportSig: String = ""
        private val modalSessionNonceCounter = java.util.concurrent.atomic.AtomicLong(1L)

        internal fun nextModalSessionNonce(): Long = modalSessionNonceCounter.getAndIncrement()

        internal fun discardHandoffVerifyWebView() {
            val prev = handoffVerifyWebView ?: return
            handoffVerifyWebView = null
            handoffVerifyTransportSig = ""
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                runCatching { releaseNativeWebView(prev) }
            } else {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    runCatching { releaseNativeWebView(prev) }
                }
            }
        }

        internal fun stashHandoffVerifyWebView(wv: WebView) {
            val prev = handoffVerifyWebView
            if (prev != null && prev !== wv) {
                runCatching { releaseNativeWebView(prev) }
            }
            (wv.parent as? ViewGroup)?.removeView(wv)
            handoffVerifyWebView = wv
            handoffVerifyWebViewAtMs = System.currentTimeMillis()
            handoffVerifyTransportSig = activeInstance?.activeTransportSignature.orEmpty()
            // 8s 安全 TTL：若前台弹窗因用户切走/取消未接管，自动回收防止 WebView 泄漏或复用过期挑战态
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (handoffVerifyWebView === wv && System.currentTimeMillis() - handoffVerifyWebViewAtMs >= 7_500L) {
                    handoffVerifyWebView = null
                    handoffVerifyTransportSig = ""
                    runCatching { releaseNativeWebView(wv) }
                }
            }, 8_000L)
        }

        internal fun takeHandoffVerifyWebView(): WebView? {
            val wv = handoffVerifyWebView ?: return null
            val savedSig = handoffVerifyTransportSig
            handoffVerifyWebView = null
            handoffVerifyTransportSig = ""
            val curSig = activeInstance?.activeTransportSignature.orEmpty()
            val sigChanged = savedSig.isNotEmpty() && curSig.isNotEmpty() && savedSig != curSig
            if (System.currentTimeMillis() - handoffVerifyWebViewAtMs > 8_000L || sigChanged) {
                runCatching { releaseNativeWebView(wv) }
                return null
            }
            (wv.parent as? ViewGroup)?.removeView(wv)
            return wv
        }

        /**
         * 严格按白名单仅迁移 Discourse 账号会话 Cookie（`_t` / `_forum_session` / `authentication_data`），
         * 并逐条 await `ValueCallback<Boolean>` 后再 `flush()`；
         * **严禁手工合成或回写 `cf_clearance` / `__cf_bm` / `_cfuvid`**，避免丢失 CHIPS `Partitioned` 属性导致持续 403！
         */
        internal suspend fun copySessionCookiesInto(sourceRaw: String, targetCm: CookieManager) {
            withContext(cookieIoDispatcher) {
                runCatching {
                    targetCm.setAcceptCookie(true)
                    val merged = LinkedHashMap<String, String>()
                    if (sourceRaw.isNotBlank()) {
                        sourceRaw.split(';').forEach { segment ->
                            val trimmed = segment.trim()
                            val eq = trimmed.indexOf('=')
                            if (eq > 0) {
                                val name = trimmed.substring(0, eq).trim()
                                val value = trimmed.substring(eq + 1).trim()
                                if (name.isNotEmpty() && value.isNotEmpty() &&
                                    do_.linux.lindo.core.network.NetworkPolicyEngine.isSessionCookieAllowedForProfileMigration(name)
                                ) {
                                    merged[name] = value
                                }
                            }
                        }
                    }
                    CookieSentinel.resolveWinningCookies(targetHost = "linux.do").forEach { (name, cookie) ->
                        if (name.isNotBlank() && cookie.value.isNotBlank() &&
                            cookie.value != "demo_session_token" &&
                            do_.linux.lindo.core.network.NetworkPolicyEngine.isSessionCookieAllowedForProfileMigration(name)
                        ) {
                            merged.putIfAbsent(name, cookie.value)
                        }
                    }
                    for ((name, value) in merged) {
                        if (value == "demo_session_token") continue
                        val cookieStr = "$name=$value; Path=/; Max-Age=5184000; Secure; HttpOnly; SameSite=Lax"
                        withTimeoutOrNull(3_000L) {
                            suspendCancellableCoroutine<Unit> { cont ->
                                targetCm.setCookie("https://linux.do", cookieStr) {
                                    if (cont.isActive) cont.resume(Unit)
                                }
                            }
                        }
                    }
                    targetCm.flush()
                }
            }
        }

        internal fun mirrorActiveCookiesToDefaultProfile() {
            if (activeWebViewProfileName == androidx.webkit.Profile.DEFAULT_PROFILE_NAME) return
            postOnCookieIo {
                val src = runCatching { currentWebViewCookieManager().getCookie("https://linux.do").orEmpty() }.getOrDefault("")
                if (src.isNotBlank()) {
                    val defaultCm = CookieManager.getInstance()
                    defaultCm.setAcceptCookie(true)
                    src.split(';').forEach { segment ->
                        val trimmed = segment.trim()
                        val eq = trimmed.indexOf('=')
                        if (eq > 0) {
                            val name = trimmed.substring(0, eq).trim()
                            val value = trimmed.substring(eq + 1).trim()
                            if (value.isNotEmpty() && value != "demo_session_token" &&
                                do_.linux.lindo.core.network.NetworkPolicyEngine.isSessionCookieAllowedForProfileMigration(name)
                            ) {
                                defaultCm.setCookie("https://linux.do", "$name=$value; Path=/; Max-Age=5184000; Secure; HttpOnly; SameSite=Lax", null)
                            }
                        }
                    }
                    defaultCm.flush()
                }
            }
        }

        internal fun cleanupStaleRotatedProfilesOnStartup() {
            activeWebViewProfileName = androidx.webkit.Profile.DEFAULT_PROFILE_NAME
            activeProfileCookieManager = null
            if (!isMultiProfileSupported()) return
            runCatching {
                val store = androidx.webkit.ProfileStore.getInstance()
                val stale = store.allProfileNames.filter { it.startsWith(ROTATED_PROFILE_PREFIX) }
                for (name in stale) {
                    runCatching { store.deleteProfile(name) }
                }
            }
        }
    }

    private var debugPreviewGeneration by mutableStateOf(0)

    internal val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val verificationTarget = AndroidVerificationTarget()
    private var passedWebSession: Long? = null
    private val iconManager by lazy { AndroidAppIconManager(this) }

    private val prefs by lazy {
        KeystorePreferences(this)
    }

    // 常驻同源 Chromium WebView 引擎：共享 BoringSSL JA3/JA4 指纹、cf_clearance、_t 与 CSRF Token
    @Volatile
    internal var apiWebView: WebView? = null
    @Volatile
    private var apiWebViewGeneration: Long = 0L
    private var apiWebViewHost: android.widget.FrameLayout? = null
    @Volatile
    private var apiWebViewReady = CompletableDeferred<Boolean>()
    private val apiWebViewDocEpoch = java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile
    private var apiWebViewOriginHealthy: Boolean = false
    @Volatile private var apiMainDocumentCommitted = false
    @Volatile private var apiMainDocumentFailed = false
    @Volatile
    private var apiWebViewCommitSignal: CompletableDeferred<Boolean>? = null
    // Only callbacks following the requested main-frame navigation can release its barrier.
    private var restoreNavigationStarted = false
    private val rebuildApiWebViewMutex = kotlinx.coroutines.sync.Mutex()
    private val apiWebViewNavMutex = kotlinx.coroutines.sync.Mutex()
    @Volatile
    private var apiWebViewNavBarrier: CompletableDeferred<Boolean>? = null
    private val consecutiveBridgeFailures = java.util.concurrent.atomic.AtomicInteger(0)
    private val consecutiveTimeoutBlackholeCount = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile
    private var lastApiWebViewRebuildAtMs: Long = 0L
    @Volatile
    private var pendingTransportRecycleOnNextUse: Boolean = false
    private data class PendingHttpMeta(
        val isBackground: Boolean,
        val isRead: Boolean,
        val createdAtWallMs: Long,
        val docEpoch: Long = 0L
    )
    private val pendingHttpCallbacks = ConcurrentHashMap<String, CompletableDeferred<Pair<Int, String>>>()
    private val pendingHttpMeta = ConcurrentHashMap<String, PendingHttpMeta>()
    private val pendingBinaryCallbacks = ConcurrentHashMap<String, CompletableDeferred<Triple<Int, String, String>>>()
    private val pendingUploadPayloads = ConcurrentHashMap<String, ByteArray>()
    // 控制同源 Chromium WebView 并发槽位（对齐真实浏览器节流节奏，防止多路并发 fetch 触发 Cloudflare 瞬时突发挑战或挤占同源连接池）：
    // - 前台交互请求专享 3 路并发槽位，后台静默预取/曝光上报隔离在独立 2 路槽位；
    // - 同源二进制图片桥接收敛为 4+2+2=8 路，确保帖子配图/表情不会饿死同源 API/发帖请求。
    private val webViewForegroundHttpSemaphore = kotlinx.coroutines.sync.Semaphore(3)
    private val webViewBackgroundHttpSemaphore = kotlinx.coroutines.sync.Semaphore(2)
    private val webViewEmojiSemaphore = kotlinx.coroutines.sync.Semaphore(4)
    private val webViewAvatarSemaphore = kotlinx.coroutines.sync.Semaphore(2)
    private val webViewMediaSemaphore = kotlinx.coroutines.sync.Semaphore(2)
    private val silentTimingReqIds = ConcurrentHashMap.newKeySet<String>()
    /** 用户点开「安全校验中…」胶囊：请求静默轮次立即把同一 WebView 交棒给验证面板 */
    @Volatile
    private var silentPanelRequested: Boolean = false
    /** 首个前台等待者加入当前静默轮次的时间（0 = 当前轮次纯后台预热） */
    private val silentForegroundJoinedAtMs = java.util.concurrent.atomic.AtomicLong(0L)
    /** 是否有主动前台写请求（发帖/回复/助力等）加入当前验证轮次 */
    private val silentForegroundWriteJoined = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun checkIsUserActivelyTypingNow(nowMs: Long = System.currentTimeMillis()): Boolean {
        val owner = UserApiKeyAuthManager.currentUser?.username.orEmpty()
        val latestDraftMs = if (owner.isNotBlank()) {
            runCatching {
                do_.linux.lindo.core.compose.LocalComposerDrafts.all(owner).maxOfOrNull { it.updatedAt } ?: 0L
            }.getOrDefault(0L)
        } else 0L
        return do_.linux.lindo.core.network.NetworkPolicyEngine.isUserActivelyTyping(
            nowMs = nowMs,
            latestDraftUpdatedAtMs = latestDraftMs
        )
    }
    private var silentVerifyHost: android.widget.FrameLayout? = null
    @Volatile
    internal var apiWebViewBootstrapChallenged: Boolean = false
    private var pendingQrImageCallback: ((Result<String>) -> Unit)? = null

    private val qrImagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        val callback = pendingQrImageCallback
        pendingQrImageCallback = null
        if (callback == null || uri == null) return@registerForActivityResult
        activityScope.launch(Dispatchers.IO) {
            val decoded = runCatching {
                AndroidQrDecoder.decodeFromImageUri(applicationContext, uri)
            }.getOrNull()
            withContext(Dispatchers.Main) {
                if (decoded != null) {
                    callback(Result.success(decoded))
                } else {
                    callback(Result.failure(IllegalStateException("未能从图片中识别到有效二维码")))
                }
            }
        }
    }

    private var connectivityCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile
    private var activeValidatedNetworkHandle: Long = 0L
    @Volatile
    private var activeTransportSignature: String = ""
    @Volatile
    private var hadNetworkInterruption: Boolean = false
    @Volatile
    private var lastNetworkRestoreNotifyAtMs: Long = 0L
    @Volatile
    private var trailingNetworkRestoreJob: Job? = null
    @Volatile
    private var networkChangeDebounceJob: Job? = null
    @Volatile
    private var physicalUplinkDebounceJob: Job? = null
    @Volatile
    private var lastCommittedEffectiveNetSig: String = ""
    @Volatile
    private var lastCommittedPhysicalTransports: String = ""
    @Volatile
    private var pendingVpnRouteFlipped: Boolean = false
    @Volatile
    private var pauseWebViewJob: Job? = null
    @Volatile
    private var lastPausedAtEpochMs: Long = 0L
    private var pendingMediaPickCallback: ((fileName: String, mimeType: String, bytes: ByteArray) -> Unit)? = null
    private var pendingMultiMediaPickCallback: ((List<do_.linux.lindo.ui.PickedMediaFile>) -> Unit)? = null
    private var pendingWebFileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var pendingLogExportContent: String? = null
    private var pendingLogExportCallback: ((Boolean, String) -> Unit)? = null
    private var pendingApkInstallFile: java.io.File? = null

    private fun extractSigningDigests(pkg: android.content.pm.PackageInfo?): Set<String> {
        if (pkg == null) return emptySet()
        val rawSigs = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val info = pkg.signingInfo
            when {
                info == null -> emptyArray()
                info.hasMultipleSigners() -> info.apkContentsSigners
                else -> info.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            pkg.signatures
        } ?: emptyArray()
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return rawSigs.mapNotNull { sig ->
            runCatching {
                md.reset()
                md.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
            }.getOrNull()
        }.toSet()
    }

    private fun hasCompatibleApkSignature(apkFile: java.io.File): Boolean = runCatching {
        val installedPkg = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                packageName,
                android.content.pm.PackageManager.PackageInfoFlags.of(
                    android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES.toLong()
                )
            )
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.GET_SIGNATURES)
        }

        val archivePkg = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageArchiveInfo(
                apkFile.absolutePath,
                android.content.pm.PackageManager.PackageInfoFlags.of(
                    android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES.toLong()
                )
            )
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            packageManager.getPackageArchiveInfo(apkFile.absolutePath, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageArchiveInfo(apkFile.absolutePath, android.content.pm.PackageManager.GET_SIGNATURES)
        }

        val installedDigests = extractSigningDigests(installedPkg)
        val archiveDigests = extractSigningDigests(archivePkg)
        if (installedDigests.isEmpty() || archiveDigests.isEmpty()) {
            true
        } else {
            installedDigests.intersect(archiveDigests).isNotEmpty()
        }
    }.getOrDefault(true)

    private fun saveApkToSystemDownloads(apkFile: java.io.File): Boolean = runCatching {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return@runCatching false
        val resolver = applicationContext.contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, apkFile.name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return@runCatching false
        try {
            resolver.openOutputStream(uri)?.use { out ->
                apkFile.inputStream().use { input -> input.copyTo(out) }
                out.flush()
            } ?: return@runCatching false
            val done = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, done, null, null)
            true
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }.getOrDefault(false)

    private fun launchApkViewInstaller(apkFile: java.io.File) {
        val apkUri = androidx.core.content.FileProvider.getUriForFile(
            applicationContext,
            "${applicationContext.packageName}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val host = activeInstance
        if (host != null && !host.isFinishing && !host.isDestroyed) {
            host.startActivity(installIntent)
        } else {
            applicationContext.startActivity(installIntent)
        }
    }

    private fun tryResumePendingApkInstall() {
        val pendingApk = pendingApkInstallFile ?: return
        if (!pendingApk.exists()) {
            pendingApkInstallFile = null
            return
        }
        val allowed = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            packageManager.canRequestPackageInstalls()
        } else {
            true
        }
        if (allowed) {
            pendingApkInstallFile = null
            runCatching { launchApkViewInstaller(pendingApk) }
        }
    }

    private val unknownAppSourcesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        tryResumePendingApkInstall()
    }

    private val logExportDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        val content = pendingLogExportContent
        val cb = pendingLogExportCallback
        pendingLogExportContent = null
        pendingLogExportCallback = null
        if (cb == null) return@registerForActivityResult
        if (uri == null || content == null) {
            cb(false, LinDoMessagesCopy.EXPORT_CANCELLED.text)
            return@registerForActivityResult
        }
        activityScope.launch(Dispatchers.IO) {
            val result = runCatching {
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                    out.flush()
                } ?: error(LinDoMessagesCopy.CANNOT_WRITE_TARGET_FILE.text)
            }
            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    LinDoDiagnosticLogger.info(
                        DiagnosticCategory.SYSTEM,
                        "LogExport",
                        "Exported diagnostic report via SAF (${content.length} chars)"
                    )
                    cb(true, LinDoMessagesCopy.EXPORT_SUCCESS_ANDROID.text)
                } else {
                    cb(
                        false,
                        LinDoMessagesCopy.EXPORT_FAILED_REASON.format(
                            result.exceptionOrNull()?.message ?: LinDoMessagesCopy.WRITE_ERROR_SHORT.text
                        )
                    )
                }
            }
        }
    }

    private val webFileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = pendingWebFileChooserCallback
        pendingWebFileChooserCallback = null
        if (callback == null) return@registerForActivityResult
        val uris = if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.clipData?.let { clip ->
                (0 until clip.itemCount)
                    .mapNotNull { idx -> clip.getItemAt(idx)?.uri }
                    .takeIf { it.isNotEmpty() }
                    ?.toTypedArray()
            }
                ?: WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
                ?: result.data?.data?.let { arrayOf(it) }
        } else {
            null
        }
        callback.onReceiveValue(uris)
    }

    private fun launchWebFileChooser(
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: WebChromeClient.FileChooserParams?
    ): Boolean {
        if (filePathCallback == null) return false
        pendingWebFileChooserCallback?.onReceiveValue(null)
        pendingWebFileChooserCallback = filePathCallback
        return runCatching {
            val allowMultiple = fileChooserParams?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
            val intent = (fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }).apply {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
            }
            webFileChooserLauncher.launch(intent)
            true
        }.getOrElse {
            pendingWebFileChooserCallback = null
            filePathCallback.onReceiveValue(null)
            false
        }
    }

    private val multiMediaPickerLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
        val callback = pendingMultiMediaPickCallback
        pendingMultiMediaPickCallback = null
        if (uris.isEmpty() || callback == null) return@registerForActivityResult
        activityScope.launch(Dispatchers.IO) {
            val maxUploadBytes = 20L * 1024L * 1024L
            val picked = mutableListOf<do_.linux.lindo.ui.PickedMediaFile>()
            for ((idx, uri) in uris.take(9).withIndex()) {
                val item = runCatching {
                    val cr = contentResolver
                    val mime = cr.getType(uri) ?: "image/png"
                    var displayName = "upload_${System.currentTimeMillis()}_$idx.png"
                    var reportedSize = -1L
                    cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                                cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let { displayName = it }
                            }
                            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                                reportedSize = cursor.getLong(sizeIdx)
                            }
                        }
                    }
                    if (reportedSize > maxUploadBytes) return@runCatching null
                    val bytes = cr.openInputStream(uri)?.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(16 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > maxUploadBytes) return@use null
                            out.write(buf, 0, n)
                        }
                        out.toByteArray()
                    } ?: return@runCatching null
                    if (bytes.isEmpty()) null else do_.linux.lindo.ui.PickedMediaFile(displayName, mime, bytes)
                }.getOrNull()
                if (item != null) picked += item
            }
            if (picked.isNotEmpty()) {
                withContext(Dispatchers.Main) { callback(picked) }
            }
        }
    }

    private val mediaPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val callback = pendingMediaPickCallback
        pendingMediaPickCallback = null
        if (uri == null || callback == null) return@registerForActivityResult
        activityScope.launch(Dispatchers.IO) {
            val maxUploadBytes = 20L * 1024L * 1024L
            var oversize = false
            val readResult = runCatching {
                val cr = contentResolver
                val mime = cr.getType(uri) ?: "image/png"
                var displayName = "upload_${System.currentTimeMillis()}"
                var reportedSize = -1L
                cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                            val name = cursor.getString(nameIdx)
                            if (!name.isNullOrBlank()) displayName = name
                        }
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                            reportedSize = cursor.getLong(sizeIdx)
                        }
                    }
                }
                if (reportedSize > maxUploadBytes) {
                    oversize = true
                    return@runCatching Triple(displayName, mime, ByteArray(0))
                }
                val bytes = cr.openInputStream(uri)?.use { input ->
                    val out = java.io.ByteArrayOutputStream(
                        if (reportedSize in 1..maxUploadBytes) reportedSize.toInt() else 64 * 1024
                    )
                    val buf = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxUploadBytes) {
                            oversize = true
                            return@use ByteArray(0)
                        }
                        out.write(buf, 0, n)
                    }
                    out.toByteArray()
                } ?: ByteArray(0)
                Triple(displayName, mime, bytes)
            }
            withContext(Dispatchers.Main) {
                when {
                    oversize -> {
                        android.widget.Toast.makeText(
                            this@MainActivity,
                            LinDoMessagesCopy.ATTACHMENT_OVER_20MB.text,
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    readResult.isSuccess && readResult.getOrNull()?.third?.isNotEmpty() == true -> {
                        val (displayName, mime, bytes) = readResult.getOrThrow()
                        callback(displayName, mime, bytes)
                    }
                    else -> {
                        android.widget.Toast.makeText(
                            this@MainActivity,
                            LinDoMessagesCopy.CANNOT_READ_ATTACHMENT.text,
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    private var lastObservedCookieSnapshot: String = ""
    private var lastCookieFlushEpochMs: Long = 0L

    inner class LinDoJsBridge {
        @JavascriptInterface
        fun onHttpResult(reqId: String, status: Int, body: String) {
            pendingHttpCallbacks.remove(reqId)?.complete(status to body)
        }

        @JavascriptInterface
        fun onHttpResultWithMeta(reqId: String, status: Int, body: String, cfMitigated: String) {
            onHttpResultWithRetryAfter(reqId, status, body, cfMitigated, "")
        }

        @JavascriptInterface
        fun onHttpResultWithRetryAfter(reqId: String, status: Int, body: String, cfMitigated: String, retryAfter: String) {
            onHttpResultFull(reqId, status, body, cfMitigated, retryAfter, "")
        }

        @JavascriptInterface
        fun onHttpResultFull(
            reqId: String,
            status: Int,
            body: String,
            cfMitigated: String,
            retryAfter: String,
            loggedOutHeader: String
        ) {
            val isChallengeMitigated = cfMitigated.contains("challenge", ignoreCase = true)
            val isPendingActive = pendingHttpCallbacks.containsKey(reqId)
            val isTiming = silentTimingReqIds.contains(reqId)

            if (status in 200..299 && !isChallengeMitigated) {
                do_.linux.lindo.core.network.NetworkPolicyEngine.cf429Classifier.noteHealthy2xx()
                do_.linux.lindo.core.network.NetworkPolicyEngine.timeoutBlackholeDetector.noteHealthyResponse()
                if (!isTiming) {
                    do_.linux.lindo.core.network.NetworkPolicyEngine.apiTokenBucket.onSuccess2xx()
                }
                if (loggedOutHeader.equals("true", ignoreCase = true) && UserApiKeyAuthManager.isAuthorized()) {
                    LinuxDoRepository.scheduleAuthVerificationIfNeeded()
                }
            }

            var tagAsChallenge = status != 429 && isChallengeMitigated
            if (isPendingActive && status == 429 && !isTiming) {
                if (isChallengeMitigated) {
                    val hasHealthy = do_.linux.lindo.core.network.CfClearanceAuthority.hasHealthyIncumbent()
                    when (val decision = do_.linux.lindo.core.network.NetworkPolicyEngine.cf429Classifier.classify(hasHealthy)) {
                        is do_.linux.lindo.core.network.Cf429Decision.ShortCooldown -> {
                            // 第 1 次突发 429 挑战且持有健康票据：短退让保护票据不被误杀
                            LinuxDoRepository.noteChallengeRateLimitHit(retryAfter, shortCooldownMs = decision.cooldownMs)
                            tagAsChallenge = false
                        }
                        do_.linux.lindo.core.network.Cf429Decision.EscalateVerify -> {
                            // 12s 内连续第 2 次命中或无健康票据：立即打挑战标升舱单飞轮换！
                            tagAsChallenge = true
                        }
                    }
                } else {
                    do_.linux.lindo.core.network.NetworkPolicyEngine.apiTokenBucket.onTrue429()
                    LinuxDoRepository.noteRetryAfter(retryAfter)
                }
            }

            val taggedBody = if (tagAsChallenge) {
                "$CF_MITIGATED_CHALLENGE_PREFIX$body"
            } else {
                body
            }
            pendingHttpCallbacks.remove(reqId)?.complete(status to taggedBody)
        }

        @JavascriptInterface
        fun onBinaryResult(reqId: String, status: Int, contentType: String, base64Body: String) {
            pendingBinaryCallbacks.remove(reqId)?.complete(Triple(status, contentType, base64Body))
        }

        @JavascriptInterface
        fun getUploadByteLength(reqId: String): Int = pendingUploadPayloads[reqId]?.size ?: -1

        @JavascriptInterface
        fun readUploadChunkBase64(reqId: String, offset: Int, length: Int): String {
            val data = pendingUploadPayloads[reqId] ?: return ""
            if (offset < 0 || offset >= data.size || length <= 0) return ""
            val end = (offset + length).coerceAtMost(data.size)
            return android.util.Base64.encodeToString(data, offset, end - offset, android.util.Base64.NO_WRAP)
        }

        @JavascriptInterface
        fun onCsrfTokenUpdated(token: String) {
            val clean = token.trim()
            if (clean.isNotEmpty()) {
                LinuxDoRepository.updateCsrfToken(clean)
                runCatching { MainActivity.currentWebViewCookieManager().flush() }
            } else {
                LinuxDoRepository.updateCsrfToken(null)
            }
        }

        @JavascriptInterface
        fun onBootstrapChallengeDetected() {
            apiWebViewBootstrapChallenged = true
            LinuxDoRepository.updateCsrfToken(null)
            if (UserPresenceService.isForeground &&
                !CfClearanceAuthority.hasHealthyIncumbent() &&
                do_.linux.lindo.core.network.SilentVerifyPolicy.shouldAllowProactivePrewarm(
                    isAuthorized = UserApiKeyAuthManager.isAuthorized(),
                    reason = "cold_start"
                )
            ) {
                scheduleSilentChallengePrewarmIfNeeded(delayMs = 500L, reason = "cold_start")
            }
        }

        @JavascriptInterface
        fun onPreloadedUserDetected(userJson: String) {
            if (userJson.isBlank()) return
            runCatching {
                val u = JSONObject(userJson)
                val cleanUsername = u.optString("username").trim()
                if (cleanUsername.isNotBlank() && cleanUsername != "linuxdo_user") {
                    val userId = u.optInt("id", 0)
                    val displayName = u.optString("name").ifBlank { cleanUsername }
                    val rawTpl = u.optString("avatar_template")
                    val avatarUrl = LinuxDoSiteRules.resolveStaticAvatarUrl(rawTpl, 120)
                    val animatedAvatar = LinuxDoSiteRules.resolveAnimatedAvatarUrl(u.optString("animated_avatar"))
                        ?: UserApiKeyAuthManager.currentUser?.takeIf { it.username.equals(cleanUsername, true) }?.animatedAvatar
                    val trustLevel = u.optInt("trust_level", 1)
                    val title = u.optString("title", "")
                    activityScope.launch {
                        val rawCookies = MainActivity.currentWebViewCookieManager().getCookie("https://linux.do").orEmpty()
                        if (rawCookies.isNotBlank()) {
                            CookieSentinel.syncFromAuthoritativeBrowserCookieString(rawCookies)
                        }
                        UserApiKeyAuthManager.recordWebSessionLogin(
                            username = cleanUsername,
                            displayName = displayName,
                            avatarUrl = avatarUrl,
                            trustLevel = trustLevel,
                            userId = userId,
                            animatedAvatar = animatedAvatar,
                            userTitle = title
                        )
                        persistSessionToDisk()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        NetworkRecoveryPolicy.androidEnabled = true
        activeInstance = this
        cleanupStaleRotatedProfilesOnStartup()
        UserApiKeyAuthManager.requireBrowserTransportOnly = true
        coil3.SingletonImageLoader.setSafe { context ->
            coil3.ImageLoader.Builder(context)
                .memoryCache {
                    coil3.memory.MemoryCache.Builder()
                        .maxSizePercent(context, 0.22)
                        .build()
                }
                .diskCache {
                    coil3.disk.DiskCache.Builder()
                        .directory(java.io.File(cacheDir, "lindo_image_cache"))
                        .maxSizeBytes(128L * 1024L * 1024L)
                        .build()
                }
                .components {
                    add(coil3.network.ktor3.KtorNetworkFetcherFactory(httpClient = { LinuxDoRepository.imageHttpClient }))
                    add(coil3.svg.SvgDecoder.Factory())
                    if (android.os.Build.VERSION.SDK_INT >= 28) add(coil3.gif.AnimatedImageDecoder.Factory())
                    else add(coil3.gif.GifDecoder.Factory())
                }
                .build()
        }
        val initialDark = runCatching { prefs.getBoolean("ui_dark_theme", false) }.getOrDefault(false)
        val initialBarStyle = if (initialDark) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        }
        enableEdgeToEdge(
            statusBarStyle = initialBarStyle,
            navigationBarStyle = initialBarStyle
        )
        super.onCreate(savedInstanceState)
        runCatching {
            val pkgInfo = packageManager.getPackageInfo(packageName, 0)
            val installedCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                pkgInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pkgInfo.versionCode
            }
            LinDoDiagnosticLogger.bindInstalledVersion(pkgInfo.versionName, installedCode)
        }
        LinDoDiagnosticLogger.configurePersistence(
            directory = java.io.File(filesDir, "logs"),
            platformInfo = "Android SDK ${android.os.Build.VERSION.SDK_INT} (${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL})"
        )
        LinDoDiagnosticLogger.installUncaughtExceptionHandler()
        do_.linux.lindo.core.network.SmartDohDns.vpnOrProxyDetector = {
            do_.linux.lindo.ech.EchGatewayManager.isUpstreamFakeIpDetected() ||
                runCatching {
                    val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    val activeNet = cm?.activeNetwork
                    val caps = activeNet?.let { cm.getNetworkCapabilities(it) }
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                }.getOrDefault(false)
        }
        val updatePrefs = prefs
        AppUpdateManager.lastPromptedRelease = updatePrefs.getString("last_prompted_release", "").orEmpty()
        AppUpdateManager.persistPromptedRelease = { release ->
            updatePrefs.edit().putString("last_prompted_release", release).apply()
        }
        AppUpdateManager.cacheDirProvider = { java.io.File(cacheDir, "updates") }
        activityScope.launch(Dispatchers.IO) {
            AppUpdateManager.cleanupObsoleteCache()
        }
        runCatching {
            val savedTrustedHosts = prefs.getString("trusted_ext_hosts_v1", "")
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
            LinuxDoSiteRules.hydrateTrustedExternalHosts(savedTrustedHosts)
        }
        LinuxDoSiteRules.onPersistTrustedHosts = { hostSet ->
            runCatching {
                prefs.edit()
                    .putString("trusted_ext_hosts_v1", hostSet.take(200).joinToString(","))
                    .apply()
            }
        }
        AppUpdateManager.apkInstaller = { apkFile ->
            runCatching {
                if (!hasCompatibleApkSignature(apkFile)) {
                    pendingApkInstallFile = null
                    val exported = saveApkToSystemDownloads(apkFile)
                    if (exported) {
                        error("当前旧版测试包签名不一致，无法直接覆盖。新安装包已保存至系统「下载」目录，请先卸载旧版再安装（后续版本均可直接升级）")
                    } else {
                        error("当前旧版测试包签名不一致，请先卸载旧版后再下载安装新版本（后续版本均可直接升级）")
                    }
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
                    !packageManager.canRequestPackageInstalls()
                ) {
                    pendingApkInstallFile = apkFile
                    runOnUiThread {
                        android.widget.Toast.makeText(
                            applicationContext,
                            "请先允许安装未知应用，授权后将自动继续升级",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                        val settingsIntent = Intent(
                            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:$packageName")
                        )
                        val host = activeInstance
                        if (host != null && !host.isFinishing && !host.isDestroyed) {
                            host.unknownAppSourcesLauncher.launch(settingsIntent)
                        } else {
                            settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            applicationContext.startActivity(settingsIntent)
                        }
                    }
                } else {
                    pendingApkInstallFile = null
                    launchApkViewInstaller(apkFile)
                }
            }
        }
        LinDoDiagnosticLogger.exportSaveHandler = { suggestedFileName, content, onResult ->
            runOnUiThread {
                try {
                    pendingLogExportContent = content
                    pendingLogExportCallback = onResult
                    logExportDocumentLauncher.launch(suggestedFileName)
                } catch (err: Throwable) {
                    pendingLogExportContent = null
                    pendingLogExportCallback = null
                    onResult(false, LinDoMessagesCopy.CANNOT_OPEN_FILE_PICKER.format(err.message ?: err::class.simpleName))
                }
            }
        }
        requestHighRefreshRateDisplay()
        var appIcon by mutableStateOf(iconManager.initialIcon())

        // 1. 注册待授权 RSA 私钥同步持久化钩子，并恢复持久化的登录状态与 WebView Cookie
        UserApiKeyAuthManager.onPersistPendingKey = { pkB64, nonce ->
            runCatching {
                prefs.edit()
                    .putString("pending_rsa_priv_b64", pkB64)
                    .putString("pending_rsa_nonce", nonce)
                    .putLong("pending_rsa_created_at", UserApiKeyAuthManager.pendingAuthorizationCreatedAt())
                    .putString("client_id", UserApiKeyAuthManager.getClientId())
                    .commit()
            }
        }
        UserApiKeyAuthManager.onPersistQrClientId = { savedQrId ->
            runCatching { prefs.edit().putString("qr_client_id", savedQrId).apply() }
        }
        restoreSessionFromDisk()
        AppAppearance.appearance = prefs.getString("ui_appearance", null)
            ?: if (prefs.contains("ui_dark_theme")) { if (UserApiKeyAuthManager.uiDarkTheme) "dark" else "light" } else "system"
        AppAppearance.iconAppearance = appIcon.background.appearanceMode
        AppAppearance.onPersist = {
            prefs.edit().putString("ui_appearance", AppAppearance.appearance)
                .putString("ui_icon_appearance", AppAppearance.iconAppearance).apply()
        }
        AppAppearance.onApplyIconAppearance = { mode ->
            val background = LinDoIconBackground.fromAppearance(mode)
            val nextIcon = appIcon.copy(background = background)
            iconManager.apply(appIcon, nextIcon).also { applied ->
                if (applied) {
                    appIcon = nextIcon
                }
            }
        }
        do_.linux.lindo.core.compose.LocalComposerDrafts.restore(prefs.getString("composer_drafts", null))
        do_.linux.lindo.core.compose.LocalComposerDrafts.onPersist = { encoded -> prefs.edit().putString("composer_drafts", encoded).apply() }

        // Initialize BoringSSL ECH + DoH + physical-socket gateway before booting the shared WebView.
        do_.linux.lindo.ech.EchGatewayManager.initialize(applicationContext) {
            pendingTransportRecycleOnNextUse = true
            runOnUiThread {
                abortPendingHttpRequests("ech_route_changed") { true }
            }
            dispatchNetworkRestoreRecycle()
        }
        initPersistentApiWebView()

        // 3. 注册系统浏览器 & Chrome Custom Tabs 授权跳转桥接器
        UserApiKeyAuthManager.openExternalUrlLauncher = { url: String ->
            runCatching {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(browserIntent)
            }
        }
        UserApiKeyAuthManager.openAuthTabLauncher = { url: String ->
            launchBrowserAuthCustomTab(url)
        }
        PlatformQrScanner.scannerContent = { onDecodedText, onError ->
            AndroidQrScannerPreview(onDecodedText = onDecodedText, onError = onError)
        }
        PlatformQrScanner.pickImageAndDecodeQr = { onResult ->
            pendingQrImageCallback = onResult
            runCatching { qrImagePickerLauncher.launch("image/*") }.onFailure { err ->
                pendingQrImageCallback = null
                onResult(Result.failure(IllegalStateException(err.message ?: "无法打开相册")))
            }
        }

        // 4. 注册会话变更与 UI 偏好持久化钩子
        UserApiKeyAuthManager.onPersistSession = { _, _ ->
            persistSessionToDisk()
        }
        UserApiKeyAuthManager.onPersistUiPrefs = { fontScale, darkTheme, enableAnimations ->
            runCatching {
                prefs.edit()
                    .putFloat("ui_font_scale_v4", fontScale)
                    .putBoolean("ui_dark_theme", darkTheme)
                    .putBoolean("ui_enable_animations", enableAnimations)
                    .apply()
            }
        }
        UserApiKeyAuthManager.onPersistHomeSearchExpanded = { expanded ->
            prefs.edit().putBoolean("ui_home_search_expanded", expanded).apply()
        }
        UserApiKeyAuthManager.onPersistThemeAndDensity = { themeAccent, compactFeed ->
            runCatching {
                prefs.edit()
                    .putString("ui_theme_accent", themeAccent)
                    .putBoolean("ui_compact_feed", compactFeed)
                    .apply()
            }
        }
        UserApiKeyAuthManager.onPersistWideSplit = { enabled ->
            runCatching { prefs.edit().putBoolean("ui_wide_split", enabled).apply() }
        }
        UserApiKeyAuthManager.onPersistSplitFraction = { splitFraction ->
            runCatching {
                prefs.edit()
                    .putFloat("ui_split_fraction", splitFraction)
                    .apply()
            }
        }
        registerNetworkRecoveryMonitor()
        do_.linux.lindo.core.network.NetworkPolicyEngine.liveRuntimeMetaProvider = {
            Triple(
                activeWebViewProfileName,
                apiWebViewGeneration.toInt(),
                activeTransportSignature.ifBlank { "none" }
            )
        }
        UserApiKeyAuthManager.onPrepareManualRecovery = {
            // 仅在非 ECH 网关激活态、且波次黑洞检测器确认「连续 >= 2 个独立建连波次超时 + 5 分钟轮换冷却已过」时才动用 ProfileStore 逃生舱；
            // ECH 网关激活时只轮换 Sticky IP 并回收 WebView，严禁切入 lindo_net_* 造成跨桶分裂！
            val echActive = do_.linux.lindo.core.network.EchRoutingPolicy.snapshot.active
            if (do_.linux.lindo.core.network.NetworkPolicyEngine.timeoutBlackholeDetector.shouldRotateProfile() &&
                UserPresenceService.isForeground &&
                activeValidatedNetworkHandle != 0L &&
                !LinuxDoRepository.isRateLimitedNow() &&
                !LinuxDoRepository.isChallengeCooldownActive() &&
                !NetworkRecoveryPolicy.verifying &&
                !do_.linux.lindo.core.network.NetworkPolicyEngine.verifyCoordinator.isActive()
            ) {
                if (echActive) {
                    do_.linux.lindo.ech.EchGatewayManager.rotateStickyIpAsync()
                    pendingTransportRecycleOnNextUse = true
                } else if (isMultiProfileSupported()) {
                    rotateWebViewNetworkProfile(
                        reason = "confirmed_connect_blackhole_escape",
                        expectedGeneration = apiWebViewGeneration
                    )
                }
            }
            ensureHealthyApiWebView(forceCheck = true)
            runOnCookieIo {
                val cm = MainActivity.currentWebViewCookieManager()
                ensureAuthCookiesInWebKit(cm)
            }
        }
        UserApiKeyAuthManager.onRequestClearancePrewarm = { reason ->
            requestClearancePrewarmFromCore(reason)
        }
        do_.linux.lindo.ui.PlatformSessionRecovery.verifySession = {
            verifyInteractiveChallengeSession()
        }
        PlatformSessionRecovery.checkForumConnection = ::checkVerifiedForumConnection
        do_.linux.lindo.ui.PlatformSessionRecovery.onExpandVerifyPill = {
            do_.linux.lindo.core.network.NetworkPolicyEngine.clearUserDismissedVerify()
            if (do_.linux.lindo.core.network.NetworkPolicyEngine.verifyCoordinator.isActive()) {
                // 静默轮次进行中：下一个探测周期（≤200ms）把同一 WebView 交棒给面板并自动展开
                silentPanelRequested = true
            } else {
                activityScope.launch { verifyInteractiveChallengeSession() }
            }
        }
        UserApiKeyAuthManager.onPrepareFreshAuthExchange = {
            withContext(Dispatchers.Main) {
                val cm = MainActivity.currentWebViewCookieManager()
                nukeAuthCookieVariantsInWebKit(cm)
                CookieSentinel.clearAuthCookiesOnly()
                apiWebView?.evaluateJavascript(
                    "window.__lindoCsrf = null; window.__lindoCsrfPromise = null; window.__lindoCsrfCookieOwner = null; window.__lindoIgnoreDomCsrf = true;",
                    null
                )
            }
        }
        UserApiKeyAuthManager.onPersistReadTopics = { encoded ->
            runCatching {
                prefs.edit().putString("read_topic_posts", encoded).apply()
            }
        }
        UserApiKeyAuthManager.onSyncCookieToPlatform = { rawSetCookie ->
            runCatching {
                val cm = MainActivity.currentWebViewCookieManager()
                cm.setCookie("https://linux.do", rawSetCookie)
                cm.flush()
            }
        }
        UserApiKeyAuthManager.onLogoutPlatformClear = {
            activityScope.launch(Dispatchers.Main.immediate) { passedWebSession = null; PlatformSessionRecovery.forumConnection.reset() }
            runCatching {
                // 仅清除 Discourse 账号会话 Cookie (_t / _forum_session / authentication_data)，保留 Cloudflare 信任通行证 (cf_clearance / __cf_bm / _cfuvid)
                val cm = MainActivity.currentWebViewCookieManager()
                val managersToClear = if (cm !== CookieManager.getInstance()) listOf(cm, CookieManager.getInstance()) else listOf(cm)
                for (mgr in managersToClear) {
                    for (origin in listOf("https://linux.do", "https://www.linux.do")) {
                        for (name in listOf("_t", "_forum_session", "authentication_data")) {
                            mgr.setCookie(origin, "$name=; Max-Age=0; Path=/; Secure; HttpOnly")
                            mgr.setCookie(origin, "$name=; Max-Age=0; Path=/; Secure")
                            mgr.setCookie(origin, "$name=; Max-Age=0; Domain=.linux.do; Path=/; Secure; HttpOnly")
                            mgr.setCookie(origin, "$name=; Max-Age=0; Domain=.linux.do; Path=/; Secure")
                            mgr.setCookie(origin, "$name=; Max-Age=0; Domain=linux.do; Path=/; Secure; HttpOnly")
                        }
                    }
                    mgr.flush()
                }
                runOnUiThread {
                    apiWebView?.evaluateJavascript(
                        "window.__lindoCsrf = null; window.__lindoCsrfPromise = null; window.__lindoCsrfCookieOwner = null; window.__lindoIgnoreDomCsrf = true;",
                        null
                    )
                }
                val remainingCookies = CookieSentinel.buildCookieHeader(targetHost = "linux.do")
                prefs.edit().apply {
                    remove("api_key")
                    remove("username")
                    remove("display_name")
                    remove("avatar_template")
                    remove("animated_avatar")
                    remove("user_id")
                    remove("user_title")
                    remove("can_use_templates")
                    remove("trust_level")
                    remove("unread_notifications")
                    remove("read_topic_posts")
                    remove("composer_drafts")
                    if (remainingCookies.isBlank()) {
                        remove("raw_cookies")
                    } else {
                        putString("raw_cookies", remainingCookies)
                    }
                }.apply()
            }
        }

        // 5. 注册内置 WebView 登录器
        PlatformWebLoginHost.webLoginRenderer = { initialUrl, onDismiss, onLoginSuccess ->
            val isVerifyUrl = remember(initialUrl) {
                do_.linux.lindo.core.network.webPagePurpose(initialUrl) == do_.linux.lindo.core.network.WebPagePurpose.VERIFICATION
            }
            val modalSessionNonce = remember { MainActivity.nextModalSessionNonce() }
            val oneShotForceRefresh = remember(initialUrl, modalSessionNonce) {
                PlatformWebLoginHost.consumeOneShotForceRefresh(isVerifyUrl)
            }
            val openedClearance = remember(initialUrl, modalSessionNonce) {
                runCatching {
                    getCookieValue(MainActivity.currentWebViewCookieManager().getCookie("https://linux.do"), "cf_clearance")
                }.getOrNull()
            }
            val openedAllClearances = remember(initialUrl, modalSessionNonce) {
                runCatching {
                    getAllCookieValues(MainActivity.currentWebViewCookieManager().getCookie("https://linux.do").orEmpty(), "cf_clearance").toSet()
                }.getOrDefault(emptySet())
            }
            val purgedStaleClearances = remember(initialUrl, modalSessionNonce, isVerifyUrl, oneShotForceRefresh, openedClearance, openedAllClearances) {
                if (!(isVerifyUrl && oneShotForceRefresh)) emptySet()
                else buildSet {
                    openedClearance?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
                    openedAllClearances.forEach { v -> v.trim().takeIf { it.isNotEmpty() }?.let { add(it) } }
                }
            }
            AndroidWebLoginModal(
                initialUrl = initialUrl,
                sessionNonce = modalSessionNonce,
                openedClearance = openedClearance,
                openedAllClearances = openedAllClearances,
                forceRefresh = oneShotForceRefresh,
                onDismiss = dismissed@{
                    NetworkRecoveryPolicy.androidVerifying = false
                    MainActivity.discardHandoffVerifyWebView()
                    if (isVerifyUrl) {
                        PlatformSessionRecovery.forumConnection.discardUnfinishedVerification()
                        onDismiss()
                        return@dismissed
                    }
                    runCatching {
                        val cm = MainActivity.currentWebViewCookieManager()
                        val latestCookies = cm.getCookie("https://linux.do").orEmpty()
                        val effectiveCookies = if (purgedStaleClearances.isNotEmpty()) {
                            do_.linux.lindo.core.network.ManualForceRefreshPolicy.stripRejectedStaleClearance(
                                rawCookies = latestCookies,
                                purgedStaleClearances = purgedStaleClearances
                            )
                        } else {
                            latestCookies
                        }
                        val latestClearance = getCookieValue(effectiveCookies, "cf_clearance")
                        val rotated = !latestClearance.isNullOrBlank() &&
                            latestClearance != openedClearance &&
                            latestClearance !in purgedStaleClearances
                        if (effectiveCookies.isNotBlank()) {
                            CookieSentinel.syncFromAuthoritativeBrowserCookieString(effectiveCookies, verified = rotated)
                        }
                        if (rotated) {
                            LinuxDoRepository.clearChallengeCooldown()
                            val freshCsrf = JSONObject.quote(LinuxDoRepository.getCachedCsrfToken().orEmpty())
                            runOnUiThread {
                                apiWebView?.evaluateJavascript(
                                    "window.__lindoCsrf = $freshCsrf || null; window.__lindoCsrfPromise = null;",
                                    null
                                )
                            }
                        }
                        activityScope.launch(cookieIoDispatcher) {
                            runCatching { cm.flush() }
                            persistSessionToDisk()
                        }
                    }
                    onDismiss()
                },
                onLoginSuccess = {
                    NetworkRecoveryPolicy.androidVerifying = false
                    val cm = MainActivity.currentWebViewCookieManager()
                    val latestCookies = runCatching { cm.getCookie("https://linux.do").orEmpty() }.getOrDefault("")
                    val effectiveCookies = if (purgedStaleClearances.isNotEmpty()) {
                        do_.linux.lindo.core.network.ManualForceRefreshPolicy.stripRejectedStaleClearance(
                            rawCookies = latestCookies,
                            purgedStaleClearances = purgedStaleClearances
                        )
                    } else {
                        latestCookies
                    }
                    if (effectiveCookies.isNotBlank()) {
                        CookieSentinel.syncFromAuthoritativeBrowserCookieString(effectiveCookies, verified = true)
                    }
                    LinuxDoRepository.clearChallengeCooldown()
                    val freshCsrf = JSONObject.quote(LinuxDoRepository.getCachedCsrfToken().orEmpty())
                    runOnUiThread {
                        apiWebView?.evaluateJavascript(
                            "window.__lindoCsrf = $freshCsrf || window.__lindoCsrf || null; window.__lindoCsrfPromise = null;",
                            null
                        )
                    }
                    activityScope.launch(cookieIoDispatcher) {
                        runCatching { cm.flush() }
                        runCatching { ensureAuthCookiesInWebKit(cm) }
                        persistSessionToDisk()
                    }
                    if (isVerifyUrl) passedWebSession = modalSessionNonce
                    onLoginSuccess()
                },
                onLaunchFileChooser = ::launchWebFileChooser
            )
        }

        // 6. 注册异步 Promise 级 WebView HTTP 执行器与 JSON 兜底器 + 媒体文件选择器与 Multipart 上传器
        UserApiKeyAuthManager.webViewHttpExecutor = { url, method, formParams, headers ->
            AccountSessionGate.read { executeHttpViaWebViewBridge(url, method, formParams, headers) }
        }
        UserApiKeyAuthManager.webViewJsonFetcher = { targetUrl ->
            AccountSessionGate.read { executeHttpViaWebViewBridge(targetUrl, "GET", null, emptyMap())?.second }
        }
        UserApiKeyAuthManager.webViewBinaryFetcher = { targetUrl ->
            AccountSessionGate.read { fetchBinaryViaWebViewBridge(targetUrl) }
        }
        PlatformMediaPicker.launchPicker = { mimeType, onPicked ->
            pendingMediaPickCallback = onPicked
            runCatching {
                mediaPickerLauncher.launch(mimeType.ifBlank { "*/*" })
            }
        }
        do_.linux.lindo.ui.PlatformRichMediaBridge.pickMultipleMedia = { mimeType, onPicked ->
            pendingMultiMediaPickCallback = onPicked
            runCatching {
                multiMediaPickerLauncher.launch(mimeType.ifBlank { "image/*" })
            }
        }
        do_.linux.lindo.ui.PlatformRichMediaBridge.saveImageToGallery = { url, fileName ->
            saveImageToAndroidGallery(applicationContext, url, fileName)
        }
        do_.linux.lindo.ui.PlatformRichMediaBridge.audioControllerFactory = {
            AndroidMediaPlayerAudioController(applicationContext)
        }
        do_.linux.lindo.ui.PlatformRichMediaBridge.inlineVideoPlayerContent = { url, onOpenExternal ->
            AndroidInlineVideoPlayer(url = url, onOpenExternal = onOpenExternal)
        }
        PlatformMediaPicker.webViewBinaryUploadExecutor = { fileName, mimeType, rawBytes ->
            AccountSessionGate.read { executeMultipartUploadViaWebViewBridge(fileName, mimeType, rawBytes) }
        }
        PlatformMediaPicker.webViewUploadExecutor = { fileName, mimeType, base64Bytes ->
            AccountSessionGate.read { executeMultipartUploadViaWebViewBridge(fileName, mimeType, base64Bytes) }
        }

        SavedAccounts.refreshBrowserCookies = {
            runOnCookieIo {
                val cm = MainActivity.currentWebViewCookieManager()
                runCatching { cm.flush() }
                CookieSentinel.syncFromAuthoritativeBrowserCookieString(cm.getCookie("https://linux.do").orEmpty())
            }
        }
        SavedAccounts.replaceBrowserCookies = { cookies -> replaceAccountCookies(cookies) }
        SavedAccounts.configure(prefs.getString("saved_accounts", null)) { encoded ->
            check(prefs.edit().putString("saved_accounts", encoded).commit()) { "Could not save account list" }
        }

        activityScope.launch { SavedAccounts.recoverPendingSwitch() }

        // 7. 检查冷启动是否由 discourse://auth_redirect 唤起
        handleIncomingAuthIntent(intent)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val consumed = BackNavigationBridge.dispatchBack()
                    if (!consumed) {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        )

        val systemAnimationsEnabled = android.provider.Settings.Global.getFloat(
            contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) > 0f
        val updateSystemBars: (Boolean) -> Unit = { dark ->
            val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
            enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        }
        setContent {
            val currentIntent = remember(debugPreviewGeneration) { intent }
            val isDebuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            val previewScene = if (isDebuggable) {
                DebugPreviewScene.fromKey(
                    currentIntent?.getStringExtra("lindo.preview")
                        ?: currentIntent?.getStringExtra("lindo_preview")
                )
            } else {
                null
            }
            val closePreview: () -> Unit = {
                intent.removeExtra("lindo.preview")
                intent.removeExtra("lindo_preview")
                intent.removeExtra("lindo.brand_preview")
                debugPreviewGeneration++
            }
            when (previewScene) {
                DebugPreviewScene.ICON -> {
                    InstalledIconPreview(iconManager.component(appIcon))
                }
                DebugPreviewScene.WELCOME -> {
                    var dark by remember { mutableStateOf(false) }
                    LaunchedEffect(dark) { updateSystemBars(dark) }
                    LinDoWelcomeScreen(
                        isDark = dark,
                        onPrimaryWebLogin = closePreview,
                        onBrowserOneClickAuth = closePreview,
                        onToggleDark = { dark = !dark },
                        onOpenAppIcon = closePreview,
                        onGuestBrowse = closePreview
                    )
                }
                DebugPreviewScene.RICH,
                DebugPreviewScene.SEARCH,
                DebugPreviewScene.BOOKMARKS,
                DebugPreviewScene.NOTIFICATIONS,
                DebugPreviewScene.DRAFTS,
                DebugPreviewScene.HISTORY,
                DebugPreviewScene.ACTIVITY,
                DebugPreviewScene.MESSAGES -> {
                    var openedLink by remember { mutableStateOf<String?>(null) }
                    val cooked = remember {
                        java.io.File(filesDir, "lindo-rich-preview.html")
                            .takeIf { it.isFile && it.length() < 262144 }
                            ?.readText()
                            .orEmpty()
                    }
                    if (openedLink != null) {
                        AndroidWebLoginModal(openedLink!!, { openedLink = null }, { openedLink = null }, ::launchWebFileChooser)
                    } else {
                        LinDoReadRegressionPreview(previewScene.wireKey, cooked, closePreview, { openedLink = it })
                    }
                }
                DebugPreviewScene.WEB,
                DebugPreviewScene.WEB_CRASH,
                DebugPreviewScene.CONNECT -> {
                    val targetUrl = when (previewScene) {
                        DebugPreviewScene.WEB_CRASH -> "chrome://crash"
                        DebugPreviewScene.CONNECT -> "https://connect.linux.do/"
                        else -> "https://linux.do/"
                    }
                    AndroidWebLoginModal(
                        initialUrl = targetUrl,
                        onDismiss = closePreview,
                        onLoginSuccess = closePreview,
                        onLaunchFileChooser = ::launchWebFileChooser
                    )
                }
                null -> {
                    if (isDebuggable && currentIntent?.getBooleanExtra("lindo.brand_preview", false) == true) {
                        LinDoMascotGallery(
                            onClose = closePreview,
                            onThemeChanged = updateSystemBars,
                            systemAnimationsEnabled = systemAnimationsEnabled
                        )
                    } else {
                        LinDoApp(
                            onThemeChanged = updateSystemBars,
                            systemAnimationsEnabled = systemAnimationsEnabled,
                            appIcon = appIcon,
                            onApplyAppIcon = { selected ->
                                iconManager.apply(appIcon, selected).also { applied ->
                                    if (applied) {
                                        appIcon = selected
                                        AppAppearance.iconAppearance = selected.background.appearanceMode
                                        AppAppearance.onPersist?.invoke()
                                        android.widget.Toast.makeText(
                                            this@MainActivity,
                                            LinDoMessagesCopy.SWITCH_ICON_APPLIED.text,
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            },
                            imageCacheDir = java.io.File(cacheDir, "lindo_image_cache"),
                            onUnreadStateChanged = { unreadCount, _ ->
                                runCatching {
                                    if (UserApiKeyAuthManager.isAuthorized()) {
                                        prefs.edit().putInt("unread_notifications", unreadCount.coerceAtLeast(0)).apply()
                                    }
                                }
                            },
                            addPlatformImageDecoders = {
                                if (android.os.Build.VERSION.SDK_INT >= 28) add(coil3.gif.AnimatedImageDecoder.Factory())
                                else add(coil3.gif.GifDecoder.Factory())
                            }
                        )
                    }
                }
            }
        }

        // Explicit debug-only, app-private import. Never put session values in Intent extras or logs.
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
            intent.getBooleanExtra("lindo.import_cookies", false)) {
            activityScope.launch {
                importPrivateDebugCookies()
            }
        }

        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
            intent.getBooleanExtra("lindo.self_test", false)) {
            activityScope.launch {
                val reportFile = java.io.File(filesDir, "network-self-test.log")
                fun logLine(msg: String) {
                    runCatching {
                        reportFile.appendText("[${System.currentTimeMillis()}] $msg\n")
                    }
                }
                runCatching { reportFile.writeText("") }
                withContext(Dispatchers.Main) {
                    apiWebView?.onResume()
                    apiWebView?.resumeTimers()
                }
                val startCookies = withContext(Dispatchers.Main) {
                    runCatching { MainActivity.currentWebViewCookieManager().getCookie("https://linux.do").orEmpty() }.getOrDefault("")
                }
                logLine("START has_cf_clearance=${startCookies.contains("cf_clearance=")} cookie_len=${startCookies.length}")
                val topics = runCatching {
                    withContext(Dispatchers.IO) {
                        LinuxDoRepository.fetchTopics(
                            path = "/latest.json?no_definitions=true",
                            categoryId = null,
                            page = 0
                        )
                    }
                }.getOrElse { e ->
                    logLine("ERROR fetchTopics: ${e::class.simpleName}: ${e.message}")
                    emptyList()
                }
                val endCookies = withContext(Dispatchers.Main) {
                    runCatching { MainActivity.currentWebViewCookieManager().getCookie("https://linux.do").orEmpty() }.getOrDefault("")
                }
                logLine("RESULT topics_count=${topics.size} has_cf_clearance=${endCookies.contains("cf_clearance=")}")
                topics.take(3).forEachIndexed { idx, t ->
                    logLine("TOPIC[$idx] id=${t.id} title=${t.title} posts=${t.postsCount}")
                }
                if (topics.isNotEmpty()) {
                    val page1 = runCatching {
                        withContext(Dispatchers.IO) {
                            LinuxDoRepository.fetchTopics(
                                path = "/latest.json?no_definitions=true",
                                categoryId = null,
                                page = 1
                            )
                        }
                    }.getOrDefault(emptyList())
                    logLine("PAGE1 topics_count=${page1.size}")
                    val detail = runCatching {
                        withContext(Dispatchers.IO) {
                            LinuxDoRepository.fetchTopicDetail(topics.first().id, topics.first())
                        }
                    }.getOrNull()
                    logLine("DETAIL id=${detail?.id} posts=${detail?.posts?.size ?: 0}")
                }
            }
        }

        // 将常驻同源 apiWebView 挂载在 1x1 零透明度无交互容器内：
        // 1. 保持 isAttachedToWindow == true 且 windowVisibility == VISIBLE，防止后台切回或锁屏唤醒后 Blink 调度器/Promise 被系统冻结；
        // 2. 1x1 极微尺寸 + alpha=0f 绝不命中各家国产 ROM 对「全屏可见 WebView」的 60Hz 降频规则，保障主界面满血 120Hz/144Hz。
        window.decorView.post {
            requestHighRefreshRateDisplay()
            runCatching {
                apiWebView?.let { attachApiWebViewInTinyHost(it) }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        window.decorView.post {
            requestHighRefreshRateDisplay()
            runCatching {
                apiWebView?.onResume()
                apiWebView?.resumeTimers()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            requestHighRefreshRateDisplay()
        }
    }

    /**
     * 主动向系统 WindowManager 申请当前物理分辨率下的最高刷新率档位（自适应 60 / 90 / 120 / 144 / 165Hz 高刷屏，含双折叠内外屏切换）
     */
    private fun requestHighRefreshRateDisplay() {
        runCatching {
            @Suppress("DEPRECATION")
            val activeDisplay = if (android.os.Build.VERSION.SDK_INT >= 30) {
                display ?: windowManager.defaultDisplay
            } else {
                windowManager.defaultDisplay
            } ?: return
            val modes = activeDisplay.supportedModes
            if (modes.isNullOrEmpty()) return
            val currentMode = activeDisplay.mode
            val sameResModes = modes.filter {
                it.physicalWidth == currentMode.physicalWidth &&
                    it.physicalHeight == currentMode.physicalHeight
            }
            val bestMode = (sameResModes.ifEmpty { modes.toList() })
                .maxByOrNull { it.refreshRate } ?: return
            val lp = window.attributes
            if (lp.preferredDisplayModeId != bestMode.modeId ||
                lp.preferredRefreshRate != bestMode.refreshRate
            ) {
                lp.preferredDisplayModeId = bestMode.modeId
                lp.preferredRefreshRate = bestMode.refreshRate
                window.attributes = lp
            }
            if (android.os.Build.VERSION.SDK_INT >= 35) {
                window.frameRateBoostOnTouchEnabled = true
            }
        }
    }

    private fun attachApiWebViewInTinyHost(wv: WebView) {
        if (wv !== apiWebView) return
        if (isFinishing || isDestroyed) return
        val decorGroup = window?.decorView as? ViewGroup ?: return
        val host = apiWebViewHost?.takeIf { it.parent === decorGroup } ?: android.widget.FrameLayout(this).apply {
            alpha = 0f
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            layoutParams = ViewGroup.LayoutParams(1, 1)
            decorGroup.addView(this)
            apiWebViewHost = this
        }
        if (wv.parent !== host) {
            (wv.parent as? ViewGroup)?.removeView(wv)
            host.removeAllViews()
            host.addView(wv, android.widget.FrameLayout.LayoutParams(1, 1))
        }
    }

    private suspend fun rebuildApiWebView(
        reason: String,
        force: Boolean = false,
        expectedGeneration: Long? = null
    ): Boolean {
        if (isFinishing || isDestroyed) return false
        return rebuildApiWebViewMutex.withLock {
            if (isFinishing || isDestroyed) return@withLock false
            if (expectedGeneration != null &&
                !pendingTransportRecycleOnNextUse &&
                apiWebView != null &&
                apiWebViewReady.isCompleted &&
                apiWebViewOriginHealthy &&
                apiWebViewGeneration != expectedGeneration
            ) {
                return@withLock true
            }
            val now = System.currentTimeMillis()
            if (!force && apiWebView != null && apiWebViewReady.isCompleted && now - lastApiWebViewRebuildAtMs < 2_500L) {
                return@withLock true
            }
            lastApiWebViewRebuildAtMs = now
            pendingTransportRecycleOnNextUse = false
            consecutiveBridgeFailures.set(0)
            val pendingHttpCount = pendingHttpCallbacks.size
            android.util.Log.i("LinDoNetwork", "Rebuilding persistent API WebView ($reason)")
            LinDoDiagnosticLogger.warn(
                DiagnosticCategory.NETWORK,
                "WebViewBridge",
                "Rebuild apiWebView (reason=$reason, gen=$apiWebViewGeneration, abortedHttp=$pendingHttpCount, net=${activeTransportSignature.ifBlank { "none" }})"
            )
            withContext(Dispatchers.Main) {
                runCatching {
                    val cm = MainActivity.currentWebViewCookieManager()
                    cm.flush()
                    val cookies = cm.getCookie("https://linux.do").orEmpty()
                    if (cookies.isNotBlank()) {
                        CookieSentinel.syncFromAuthoritativeBrowserCookieString(cookies)
                    }
                }
                val old = apiWebView
                apiWebView = null
                // 用非级联中止哨兵唤醒挂起回调而非 cancel() 或 0，确保外层 executeHttp 安静在已重建实例上自愈重试且不触发二次连环重建
                pendingHttpCallbacks.values.forEach { it.complete(REBUILD_ABORTED_STATUS to REBUILD_ABORTED_BODY) }
                pendingHttpCallbacks.clear()
                pendingHttpMeta.clear()
                pendingBinaryCallbacks.values.forEach { it.complete(Triple(REBUILD_ABORTED_STATUS, "", "")) }
                pendingBinaryCallbacks.clear()
                if (old != null) {
                    runCatching { releaseNativeWebView(old) }
                }
                apiWebViewReady = CompletableDeferred()
                initPersistentApiWebView()
            }
            withTimeoutOrNull(8_000L) { apiWebViewReady.await() } == true
        }
    }

    /**
     * 进程内无感重置 Chromium NetworkContext / QuicSessionPool / ClientSocketPool：
     * 通过 androidx.webkit ProfileStore 轮换到全新隔离 Profile（同步全部会话与 CF Cookie，并双向回写 Default），
     * 彻底破除 Android WebView 在 VPN 切节点或 WiFi↔5G 切换后的进程级连接池黑洞，同时 100% 保持 Chromium BoringSSL TLS 指纹。
     */
    private suspend fun rotateWebViewNetworkProfile(
        reason: String,
        expectedGeneration: Long
    ): Boolean {
        if (isFinishing || isDestroyed || !isMultiProfileSupported()) return false
        if (do_.linux.lindo.core.network.EchRoutingPolicy.snapshot.active) return false
        if (do_.linux.lindo.core.network.NetworkPolicyEngine.verifyCoordinator.isActive()) return false
        return rebuildApiWebViewMutex.withLock {
            if (isFinishing || isDestroyed) return@withLock false
            if (do_.linux.lindo.core.network.EchRoutingPolicy.snapshot.active ||
                do_.linux.lindo.core.network.NetworkPolicyEngine.verifyCoordinator.isActive()
            ) {
                return@withLock false
            }
            if (apiWebView != null && apiWebViewReady.isCompleted && apiWebViewGeneration != expectedGeneration) {
                return@withLock true
            }
            val now = System.currentTimeMillis()
            if (now - lastProfileRotateAtMs < 300_000L) {
                return@withLock false
            }
            val oldProfileName = activeWebViewProfileName
            val nextProfileName = "$ROTATED_PROFILE_PREFIX${++rotatedProfileSeq}"
            val pendingHttpCount = pendingHttpCallbacks.size
            val prevCm = currentWebViewCookieManager()
            val snapshotCookies = runOnCookieIo {
                runCatching { prevCm.flush() }
                runCatching { prevCm.getCookie("https://linux.do").orEmpty() }.getOrDefault("")
            }
            if (snapshotCookies.isNotBlank()) {
                CookieSentinel.syncFromAuthoritativeBrowserCookieString(snapshotCookies)
                if (oldProfileName != androidx.webkit.Profile.DEFAULT_PROFILE_NAME) {
                    copySessionCookiesInto(snapshotCookies, CookieManager.getInstance())
                }
            }
            val createdPair = withContext(Dispatchers.Main) {
                runCatching {
                    val store = androidx.webkit.ProfileStore.getInstance()
                    val newProfile = store.getOrCreateProfile(nextProfileName)
                    store to newProfile.cookieManager
                }.getOrNull()
            } ?: return@withLock false
            val (store, newCm) = createdPair
            // 严格等待所有会话 Cookie 的 ValueCallback 提交完毕后再激活并初始化新 WebView（绝不回写 cf_clearance）
            copySessionCookiesInto(snapshotCookies, newCm)

            val switched = withContext(Dispatchers.Main) {
                runCatching {
                    takeHandoffVerifyWebView()?.let { runCatching { releaseNativeWebView(it) } }
                    CfClearanceAuthority.reset()
                    CookieSentinel.removeCookieByName("cf_clearance", domain = "linux.do")
                    activeWebViewProfileName = nextProfileName
                    activeProfileCookieManager = newCm
                    lastProfileRotateAtMs = now
                    lastApiWebViewRebuildAtMs = now
                    pendingTransportRecycleOnNextUse = false
                    consecutiveBridgeFailures.set(0)
                    consecutiveTimeoutBlackholeCount.set(0)
                    do_.linux.lindo.core.network.NetworkPolicyEngine.timeoutBlackholeDetector.markProfileRotated(now)

                    val old = apiWebView
                    apiWebView = null
                    pendingHttpCallbacks.values.forEach { it.complete(REBUILD_ABORTED_STATUS to REBUILD_ABORTED_BODY) }
                    pendingHttpCallbacks.clear()
                    pendingHttpMeta.clear()
                    pendingBinaryCallbacks.values.forEach { it.complete(Triple(REBUILD_ABORTED_STATUS, "", "")) }
                    pendingBinaryCallbacks.clear()
                    if (old != null) {
                        runCatching { releaseNativeWebView(old) }
                    }
                    if (oldProfileName.startsWith(ROTATED_PROFILE_PREFIX)) {
                        runCatching { store.deleteProfile(oldProfileName) }
                    }
                    apiWebViewReady = CompletableDeferred()
                    initPersistentApiWebView()
                    true
                }.getOrDefault(false)
            }
            if (switched) {
                LinDoDiagnosticLogger.warn(
                    DiagnosticCategory.NETWORK,
                    "WebViewBridge",
                    "Rotated Chromium network profile ($oldProfileName -> $nextProfileName, reason=$reason, abortedHttp=$pendingHttpCount, net=${activeTransportSignature.ifBlank { "none" }})"
                )
                withTimeoutOrNull(8_000L) { apiWebViewReady.await() } == true
            } else {
                false
            }
        }
    }

    private suspend fun ensureHealthyApiWebView(forceCheck: Boolean = false) {
        if (isFinishing || isDestroyed) return
        apiWebViewNavBarrier?.let {
            it.await()
            return
        }
        if (apiWebView != null && !pendingTransportRecycleOnNextUse && !apiWebViewReady.isCompleted) {
            withTimeoutOrNull(8_000L) { apiWebViewReady.await() }
        }
        val observedGen = apiWebViewGeneration
        val current = apiWebView
        if (current == null || pendingTransportRecycleOnNextUse || !apiWebViewOriginHealthy || consecutiveBridgeFailures.get() >= 1) {
            val forceRecycle = pendingTransportRecycleOnNextUse || !apiWebViewOriginHealthy
            if (pendingTransportRecycleOnNextUse) {
                do_.linux.lindo.ech.EchGatewayManager.awaitReconcileAndProxyReady()
            }
            rebuildApiWebView(
                reason = when {
                    current == null -> "missing_webview"
                    pendingTransportRecycleOnNextUse -> "network_changed_recycle"
                    !apiWebViewOriginHealthy -> "unhealthy_origin_doc"
                    else -> "prior_bridge_failure"
                },
                force = forceRecycle,
                expectedGeneration = observedGen
            )
            return
        }
        withContext(Dispatchers.Main) {
            runCatching {
                attachApiWebViewInTinyHost(current)
                if (UserPresenceService.isForeground) {
                    current.onResume()
                    current.resumeTimers()
                }
            }
        }
        if (!forceCheck) return
        val pingDeferred = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {
            runCatching {
                val live = apiWebView
                if (live == null) {
                    pingDeferred.complete(false)
                } else {
                    live.evaluateJavascript(
                        "(function(){return (window.location && window.location.origin === 'https://linux.do' && window.location.protocol === 'https:') ? 'ok' : 'bad';})();"
                    ) { res ->
                        pingDeferred.complete(decodeJsStringResult(res) == "ok")
                    }
                }
            }.onFailure {
                pingDeferred.complete(false)
            }
        }
        val alive = withTimeoutOrNull(1_200L) { pingDeferred.await() } == true
        if (!alive) {
            rebuildApiWebView(reason = "js_origin_liveness_failed", force = false, expectedGeneration = observedGen)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initPersistentApiWebView() {
        if (apiWebView != null) return
        runCatching {
            val wv = WebView(this)
            bindWebViewToActiveProfile(wv)
            // 严禁手动删改 "; wv" 或覆盖 userAgentString：
            // 所有 WebView 保持原生 User-Agent，避免应用自行制造 UA / Client Hints 差异；是否放行仍由站点决定
            val nativeUa = wv.settings.userAgentString
            UserApiKeyAuthManager.customUserAgent = nativeUa
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            }
            val cm = currentWebViewCookieManager()
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(wv, true)

            apiWebViewBootstrapChallenged = false
            apiWebViewOriginHealthy = false
            apiMainDocumentCommitted = false
            fun neutralizeBootstrapChallengePage(target: WebView?) {
                val live = target ?: return
                runCatching { live.stopLoading() }
                live.evaluateJavascript(
                    """
                    (function() {
                        try { window.stop(); } catch (_) {}
                        try {
                            if (document && document.documentElement) {
                                document.documentElement.innerHTML = '<head><title>LinDo Bridge</title></head><body></body>';
                            }
                        } catch (_) {}
                        window.__lindoCsrf = null;
                        window.__lindoCsrfPromise = null;
                        window.__lindoIgnoreDomCsrf = true;
                    })();
                    """.trimIndent(),
                    null
                )
                live.evaluateJavascript(installBinaryBridgeHelperJs, null)
            }
            wv.addJavascriptInterface(LinDoJsBridge(), "LinDoJsBridge")
            wv.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (view != null && view !== apiWebView) return
                    if (apiWebViewCommitSignal != null && url == BRIDGE_BOOTSTRAP_URL) restoreNavigationStarted = true
                    apiMainDocumentFailed = false
                    apiMainDocumentCommitted = false
                    val nextEpoch = apiWebViewDocEpoch.incrementAndGet()
                    // 捕获非预期主文档跳转，立即结算旧 docEpoch 挂起请求，杜绝 V8 上下文销毁后盲等 19s/28.5s
                    if (pendingHttpMeta.isNotEmpty()) {
                        val staleIds = pendingHttpMeta.entries
                            .filter { it.value.docEpoch in 1L until nextEpoch }
                            .map { it.key }
                        for (staleId in staleIds) {
                            val meta = pendingHttpMeta.remove(staleId)
                            val cb = pendingHttpCallbacks.remove(staleId) ?: continue
                            if (meta?.isRead == true) {
                                cb.complete(REBUILD_ABORTED_STATUS to REBUILD_ABORTED_BODY)
                            } else {
                                cb.complete(0 to "[BRIDGE_DIAG:doc_nav_aborted] oldEpoch=${meta?.docEpoch ?: 0L} newEpoch=$nextEpoch")
                            }
                        }
                    }
                    if (pendingBinaryCallbacks.isNotEmpty()) {
                        val binIds = pendingBinaryCallbacks.keys.toList()
                        for (binId in binIds) {
                            pendingBinaryCallbacks.remove(binId)?.complete(Triple(REBUILD_ABORTED_STATUS, "", ""))
                        }
                    }
                }

                override fun onPageCommitVisible(view: WebView?, url: String?) {
                    super.onPageCommitVisible(view, url)
                    if (view != null && view !== apiWebView) return
                    if (do_.linux.lindo.core.network.AndroidWebViewBridgeContract.isValidLinuxDoDocumentUrl(url)) {
                        apiMainDocumentCommitted = true
                        apiWebViewOriginHealthy = true
                        apiWebViewDocEpoch.incrementAndGet()
                        if (apiWebViewBootstrapChallenged) {
                            neutralizeBootstrapChallengePage(view)
                        } else {
                            view?.evaluateJavascript(installBinaryBridgeHelperJs, null)
                        }
                        if (restoreNavigationStarted) apiWebViewCommitSignal?.complete(true)
                        if (!apiWebViewReady.isCompleted) {
                            apiWebViewReady.complete(true)
                        }
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: android.webkit.WebResourceResponse?
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    if (view != null && view !== apiWebView) return
                    if (request?.isForMainFrame == true &&
                        request.url?.host.equals("linux.do", ignoreCase = true)
                    ) {
                        val status = errorResponse?.statusCode ?: 0
                        apiMainDocumentFailed = status >= 400
                        val cfHdr = errorResponse?.responseHeaders?.entries?.firstOrNull {
                            it.key.equals("cf-mitigated", ignoreCase = true)
                        }?.value.orEmpty()
                        if (status in setOf(403, 429, 503) && cfHdr.equals("challenge", ignoreCase = true)) {
                            apiWebViewBootstrapChallenged = true
                            // 若当前已处于同源文档，立即停止后台挑战子资源与脚本自刷新
                            if (do_.linux.lindo.core.network.AndroidWebViewBridgeContract.isValidLinuxDoDocumentUrl(view?.url)) {
                                neutralizeBootstrapChallengePage(view)
                            }
                        }
                        apiWebViewOriginHealthy = true
                        apiWebViewDocEpoch.incrementAndGet()
                        apiWebViewCommitSignal?.complete(false)
                        if (!apiWebViewReady.isCompleted) {
                            apiWebViewReady.complete(true)
                        }
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (view != null && view !== apiWebView) return
                    if (request?.isForMainFrame == true &&
                        request.url?.host.equals("linux.do", ignoreCase = true)
                    ) {
                        // 主框架网络错误会落入 chrome-error://chromewebdata/（origin === "null"）：
                        // 严禁将其标为 true 放行，否则后续所有同源 fetch() 将以 3~8ms TypeError: Failed to fetch 连续报错！
                        apiMainDocumentFailed = true
                        apiWebViewOriginHealthy = false
                        apiWebViewCommitSignal?.complete(false)
                        if (!apiWebViewReady.isCompleted) {
                            apiWebViewReady.complete(false)
                        }
                    }
                }

                override fun onReceivedSslError(
                    view: WebView?,
                    handler: android.webkit.SslErrorHandler?,
                    error: android.net.http.SslError?
                ) {
                    val host = error?.url?.let { runCatching { Uri.parse(it).host }.getOrNull() }
                    if (do_.linux.lindo.ech.EchGatewayManager.verifyWebViewSslError(error, host)) {
                        handler?.proceed()
                    } else {
                        handler?.cancel()
                    }
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    if (apiWebView !== view) {
                        runCatching { releaseNativeWebView(view, rendererGone = true) }
                        return true
                    }
                    LinDoDiagnosticLogger.error(
                        DiagnosticCategory.SYSTEM,
                        "WebViewBridge",
                        "Persistent apiWebView renderer process gone (didCrash=${detail.didCrash()}, gen=$apiWebViewGeneration, pendingHttp=${pendingHttpCallbacks.size})"
                    )
                    apiWebView = null
                    apiWebViewOriginHealthy = false
                    apiWebViewCommitSignal?.complete(false)
                    pendingHttpCallbacks.values.forEach { it.complete(REBUILD_ABORTED_STATUS to REBUILD_ABORTED_BODY) }
                    pendingHttpCallbacks.clear()
                    pendingHttpMeta.clear()
                    pendingBinaryCallbacks.values.forEach { it.complete(Triple(REBUILD_ABORTED_STATUS, "", "")) }
                    pendingBinaryCallbacks.clear()
                    releaseNativeWebView(view, rendererGone = true)
                    apiWebViewReady = CompletableDeferred()
                    activityScope.launch {
                        delay(200L)
                        if (apiWebView == null && UserPresenceService.isForeground && !isFinishing && !isDestroyed) {
                            initPersistentApiWebView()
                        }
                    }
                    return true
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (view != null && view !== apiWebView) return
                    val validLinuxDoDoc = do_.linux.lindo.core.network.AndroidWebViewBridgeContract.isValidLinuxDoDocumentUrl(url)
                    if (!validLinuxDoDoc) {
                        if (url?.startsWith("chrome-error://", ignoreCase = true) == true) {
                            apiWebViewOriginHealthy = false
                            apiWebViewCommitSignal?.complete(false)
                            if (!apiWebViewReady.isCompleted) {
                                apiWebViewReady.complete(false)
                            }
                        }
                        return
                    }
                    apiWebViewOriginHealthy = true
                    apiWebViewDocEpoch.incrementAndGet()
                    val cookies = cm.getCookie("https://linux.do").orEmpty()
                    if (cookies.isNotBlank()) {
                        CookieSentinel.syncFromAuthoritativeBrowserCookieString(cookies)
                    }
                    view?.evaluateJavascript(installBinaryBridgeHelperJs, null)
                    if (!apiWebViewReady.isCompleted) {
                        apiWebViewReady.complete(true)
                    }
                    if (apiWebViewBootstrapChallenged) {
                        neutralizeBootstrapChallengePage(view)
                        if (!CfClearanceAuthority.hasHealthyIncumbent() &&
                            do_.linux.lindo.core.network.SilentVerifyPolicy.shouldAllowProactivePrewarm(
                                isAuthorized = UserApiKeyAuthManager.isAuthorized(),
                                reason = "cold_start"
                            )
                        ) {
                            scheduleSilentChallengePrewarmIfNeeded(delayMs = 600L, reason = "cold_start")
                        }
                        return
                    }
                    val seeded = LinuxDoRepository.getCachedCsrfToken().orEmpty()
                    val ownerTag = csrfOwnerTag(cookies)
                    val allowDomCsrf = hasCookieNamed(cookies, "_t") || !UserApiKeyAuthManager.isAuthorized()
                    view?.evaluateJavascript(
                        """
                        (function() {
                            try {
                                var isChl = !!document.querySelector('#challenge-running,#challenge-stage,#challenge-form,.cf-turnstile,iframe[src*="challenges.cloudflare.com"]') ||
                                    /^(Just a moment|请稍候)/i.test(document.title || '');
                                if (isChl) {
                                    try { window.stop(); } catch (_) {}
                                    try {
                                        if (document && document.documentElement) {
                                            document.documentElement.innerHTML = '<head><title>LinDo Bridge</title></head><body></body>';
                                        }
                                    } catch (_) {}
                                    window.__lindoCsrf = null;
                                    window.__lindoCsrfPromise = null;
                                    window.__lindoIgnoreDomCsrf = true;
                                    if (window.LinDoJsBridge && window.LinDoJsBridge.onBootstrapChallengeDetected) {
                                        window.LinDoJsBridge.onBootstrapChallengeDetected();
                                    }
                                    return 'challenge';
                                }
                                var sp = document.getElementById('d-splash');
                                if (sp) sp.remove();
                                if (window.MessageBus && typeof window.MessageBus.stop === 'function') window.MessageBus.stop();
                                var owner = ${JSONObject.quote(ownerTag)};
                                var ownerChanged = (window.__lindoCsrfCookieOwner !== owner);
                                if (ownerChanged) {
                                    window.__lindoCsrf = null;
                                    window.__lindoCsrfPromise = null;
                                    window.__lindoCsrfCookieOwner = owner;
                                }
                                var m = document.querySelector('meta[name="csrf-token"]');
                                var domCsrf = (m && m.content) ? m.content : '';
                                var allowDom = $allowDomCsrf;
                                var seeded = ${JSONObject.quote(seeded)};
                                if (domCsrf && allowDom) {
                                    window.__lindoIgnoreDomCsrf = false;
                                    window.__lindoCsrf = domCsrf;
                                    if (window.LinDoJsBridge && window.LinDoJsBridge.onCsrfTokenUpdated) {
                                        window.LinDoJsBridge.onCsrfTokenUpdated(domCsrf);
                                    }
                                } else if (seeded && !window.__lindoCsrf && !ownerChanged) {
                                    window.__lindoCsrf = seeded;
                                }
                                return domCsrf ? 'ready' : 'no_csrf';
                            } catch (_) {
                                return 'err';
                            }
                        })();
                        """.trimIndent(),
                        null
                    )
                    if (apiWebViewBootstrapChallenged &&
                        !hasCookieNamed(cookies, "cf_clearance") &&
                        UserApiKeyAuthManager.isAuthorized()
                    ) {
                        scheduleSilentChallengePrewarmIfNeeded(delayMs = 800L, reason = "cold_start")
                    }
                }
            }
            apiWebView = wv
            apiWebViewGeneration += 1L
            attachApiWebViewInTinyHost(wv)
            wv.measure(
                View.MeasureSpec.makeMeasureSpec(1, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1, View.MeasureSpec.EXACTLY)
            )
            wv.layout(0, 0, 1, 1)
            if (UserPresenceService.isForeground) {
                wv.onResume()
                wv.resumeTimers()
            }
            // 若冷启动或路由翻转时 ProxyController 正在异步下发 ECH 代理规则，等待代理挂载完成后再导航 /about，
            // 防止首包在代理切换中途撞上 ERR_PROXY_CONNECTION_FAILED / ERR_NETWORK_CHANGED 落入 chrome-error://。
            if (do_.linux.lindo.ech.EchGatewayManager.hasPendingProxyOverride()) {
                activityScope.launch(Dispatchers.Main.immediate) {
                    do_.linux.lindo.ech.EchGatewayManager.awaitPendingProxyOverride(1_200L)
                    if (apiWebView === wv && !isFinishing && !isDestroyed) {
                        wv.loadUrl(BRIDGE_BOOTSTRAP_URL)
                    }
                }
            } else {
                wv.loadUrl(BRIDGE_BOOTSTRAP_URL)
            }
        }.onFailure {
            apiWebView = null
            apiWebViewOriginHealthy = false
            if (!apiWebViewReady.isCompleted) {
                apiWebViewReady.complete(false)
            }
            android.util.Log.w("LinDoNetwork", "Could not initialize API WebView", it)
        }
    }

    /**
     * 过盾成功拿到新 `cf_clearance` 或检测到 `origin_lost` 后，确保常驻 `apiWebView` 处于健康真实 `/about` 上下文：
     * - 快路径：已处于健康 `/about` 时仅注入最新 CSRF，绝不调用 `loadUrl` 腰斩并发在途 `fetch()`；
     * - 重载路径：使用权威 `apiWebViewCommitSignal` + `apiWebViewDocEpoch` 严格等待**新导航真正 Commit**，
     *   彻底消除旧 403 文档尚未卸载时 `readyState !== 'loading'` 提前假就绪放行屏障导致的 24s `kotlin_timeout`！
     */
    internal suspend fun refreshApiWebViewOriginAfterVerify(forceReload: Boolean = false): Boolean =
        apiWebViewNavMutex.withLock {
            if (isFinishing || isDestroyed) return@withLock false
            val target = withContext(Dispatchers.Main) {
                if (apiWebView == null) initPersistentApiWebView()
                apiWebView
            } ?: return@withLock false
            if (!forceReload && !apiWebViewReady.isCompleted) {
                withTimeoutOrNull(5_000L) { apiWebViewReady.await() }
            }
            suspend fun documentReady(): Boolean = withContext(Dispatchers.Main) {
                apiWebView === target && apiWebViewOriginHealthy && !apiWebViewBootstrapChallenged &&
                    apiMainDocumentCommitted && !apiMainDocumentFailed &&
                    do_.linux.lindo.core.network.AndroidWebViewBridgeContract.isValidLinuxDoDocumentUrl(target.url)
            }
            if (!forceReload && documentReady()) return@withLock true
            val drainDeadline = System.currentTimeMillis() + 1_200L
            while (System.currentTimeMillis() < drainDeadline &&
                (pendingHttpCallbacks.isNotEmpty() || pendingBinaryCallbacks.isNotEmpty())
            ) {
                delay(50L)
            }
            val barrier = CompletableDeferred<Boolean>()
            val commitSignal = CompletableDeferred<Boolean>()
            val epochBefore = apiWebViewDocEpoch.get()
            apiWebViewNavBarrier = barrier
            try {
                withContext(Dispatchers.Main) {
                    if (apiWebView === target) {
                        restoreNavigationStarted = false
                        apiWebViewCommitSignal = commitSignal
                        apiWebViewBootstrapChallenged = false
                        target.loadUrl(BRIDGE_BOOTSTRAP_URL)
                    } else commitSignal.complete(false)
                }
                val committed = withTimeoutOrNull(5_000L) { commitSignal.await() } == true
                val ready = committed && apiWebViewDocEpoch.get() > epochBefore && documentReady()
                barrier.complete(ready)
                ready
            } finally {
                if (apiWebViewCommitSignal === commitSignal) apiWebViewCommitSignal = null
                if (!barrier.isCompleted) barrier.complete(false)
                if (apiWebViewNavBarrier === barrier) apiWebViewNavBarrier = null
            }
        }

    @Volatile
    private var lastPrewarmFinishedAtMs: Long = 0L

    private suspend fun checkVerifiedForumConnection(explicitRetry: Boolean): Boolean = withContext(Dispatchers.Main.immediate) {
        val generation = UserApiKeyAuthManager.sessionGeneration
        val round = do_.linux.lindo.core.network.NetworkPolicyEngine.verifyCoordinator.currentRound()
        val modalSession = passedWebSession
        var bootstrapNeedsVerification = false
        PlatformSessionRecovery.forumConnection.check(
            generation, verificationTarget.get(round, generation), explicitRetry,
            { UserApiKeyAuthManager.sessionGeneration }, { reply -> bootstrapNeedsVerification || isChallengeResponse(reply) }
        ) { request ->
            try {
                // The persistent Android WebView needs a committed forum document to issue a same-origin read.
                when {
                    LinuxDoRepository.isRateLimitedNow() -> null
                    !refreshApiWebViewOriginAfterVerify() -> {
                        bootstrapNeedsVerification = apiWebViewBootstrapChallenged
                        null
                    }
                    else -> {
                        LinuxDoRepository.clearChallengeCooldown()
                        executeHttpViaWebViewBridge(request.url, "GET", null, request.headers,
                            allowSilentChallengeRetry = false, allowOriginLostRetry = false)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }.also {
            if (it && passedWebSession == modalSession) passedWebSession = null
            LinDoDiagnosticLogger.info(DiagnosticCategory.NETWORK, "SiteVerify",
                "Forum connection result (session=${modalSession ?: "background"}, round=$round, ready=$it, retry=$explicitRetry, stage=${PlatformSessionRecovery.forumConnection.stage})")
        }
    }

    /**
     * 前台静默 `/challenge` 预检与按需主动升舱：
     * - 浏览器自动过盾时 0 胶囊、0 弹窗；
     * - 后台预检需要人工操作时显示提示，由用户决定何时打开。
     */
    private fun scheduleSilentChallengePrewarmIfNeeded(
        delayMs: Long = 800L,
        reason: String = "prewarm"
    ) {
        activityScope.launch {
            if (!do_.linux.lindo.core.network.SilentVerifyPolicy.shouldAllowProactivePrewarm(
                    isAuthorized = UserApiKeyAuthManager.isAuthorized(),
                    reason = reason
                )
            ) {
                return@launch
            }
            if (delayMs > 0L) delay(delayMs)
            if (isFinishing || isDestroyed || !UserPresenceService.isForeground) return@launch
            if (!do_.linux.lindo.core.network.SilentVerifyPolicy.shouldAllowProactivePrewarm(
                    isAuthorized = UserApiKeyAuthManager.isAuthorized(),
                    reason = reason
                )
            ) {
                return@launch
            }
            if (LinuxDoRepository.isRateLimitedNow() || NetworkRecoveryPolicy.verifying) return@launch
            if (do_.linux.lindo.core.network.NetworkPolicyEngine.verifyCoordinator.isActive()) return@launch
            val existingCf = runOnCookieIo {
                runCatching {
                    getCookieValue(MainActivity.currentWebViewCookieManager().getCookie("https://linux.do"), "cf_clearance")
                }.getOrNull()
            }
            val hasHealthyCf = !existingCf.isNullOrBlank() && !CfClearanceAuthority.isChallenged(existingCf)
            if (hasHealthyCf && reason != "near_expiry") return@launch
            if (do_.linux.lindo.core.network.NetworkPolicyEngine.isProactiveVerifySnoozed()) {
                do_.linux.lindo.core.network.NetworkPolicyEngine.updateVerifyAttentionNeeded(true)
                withContext(Dispatchers.Main) {
                    do_.linux.lindo.ui.PlatformSessionRecovery.verifyPillVisible = true
                }
                return@launch
            }
            // 普通回前台触发的预热 60s 内最多一轮（切网 / 待处理交互 / 后台实测挑战 / 冷启动不受限）
            if (reason == "resume" && System.currentTimeMillis() - lastPrewarmFinishedAtMs < 60_000L) return@launch
            try {
                silentRevalidateChallenge(
                    previousClearance = existingCf,
                    trigger = do_.linux.lindo.core.network.SilentVerifyTrigger.PREWARM,
                    reason = reason
                )
            } finally {
                lastPrewarmFinishedAtMs = System.currentTimeMillis()
            }
        }
    }

    /** 共享网络层请求的预检入口（已移除 5m 硬节流锁，由 VerifyCoordinator 单飞与 180s 免打扰门控收敛）。 */
    private fun requestClearancePrewarmFromCore(reason: String) {
        if (do_.linux.lindo.core.network.NetworkPolicyEngine.verifyCoordinator.isActive()) return
        val delayMs = when (reason) {
            "background_challenge", "composer_open" -> 400L
            else -> 900L
        }
        scheduleSilentChallengePrewarmIfNeeded(delayMs = delayMs, reason = reason)
    }

    /**
     * 静默验证专用宿主：1px 裁剪 + 非 0 透明度，内部 WebView 按真实手机视口尺寸布局并正常参与绘制，
     * 对用户完全不可见，也不拦截任何触摸。与常驻 API WebView 的宿主隔离，互不 removeAllViews。
     */
    private fun ensureSilentVerifyHost(): ViewGroup? {
        val decorGroup = window?.decorView as? ViewGroup ?: return null
        silentVerifyHost?.takeIf { it.parent === decorGroup }?.let { return it }
        val host = object : android.widget.FrameLayout(this@MainActivity) {
            override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean = false
        }.apply {
            alpha = 0.01f
            clipChildren = true
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            layoutParams = ViewGroup.LayoutParams(1, 1)
        }
        decorGroup.addView(host)
        silentVerifyHost = host
        return host
    }

    private fun launchBrowserAuthCustomTab(url: String) {
        val targetUri = Uri.parse(url)
        val launched = runCatching {
            val isDark = UserApiKeyAuthManager.uiDarkTheme || AppAppearance.resolveDark(
                AppAppearance.appearance,
                (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
            )
            val barColor = if (isDark) 0xFF14161A.toInt() else 0xFFF4F6F8.toInt()
            val colorScheme = if (isDark) CustomTabsIntent.COLOR_SCHEME_DARK else CustomTabsIntent.COLOR_SCHEME_LIGHT
            val colorParams = CustomTabColorSchemeParams.Builder()
                .setToolbarColor(barColor)
                .setNavigationBarColor(barColor)
                .build()
            val customTabsIntent = CustomTabsIntent.Builder()
                .setColorScheme(colorScheme)
                .setDefaultColorSchemeParams(colorParams)
                .setShowTitle(true)
                .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
                .build()
            customTabsIntent.launchUrl(this, targetUri)
            true
        }.getOrDefault(false)
        if (!launched) {
            UserApiKeyAuthManager.openExternalUrlLauncher?.invoke(url)
        }
    }

    private suspend fun awaitEmbeddedVerificationModal(): VerificationDialogResult = withContext(Dispatchers.Main) {
        if (isFinishing || isDestroyed) return@withContext VerificationDialogResult.Unavailable
        val opener = do_.linux.lindo.ui.PlatformSessionRecovery.openEmbeddedVerifyModal
            ?: return@withContext VerificationDialogResult.Unavailable
        val result = CompletableDeferred<VerificationDialogResult>()
        val detach = opener { result.complete(it) }
        try {
            withTimeoutOrNull(120_000L) { result.await() } ?: VerificationDialogResult.Expired
        } finally {
            detach()
        }
    }

    /**
     * Android 统一前台交互过盾入口（`PlatformSessionRecovery.verifySession`）：
     * - 若静默轮次已在跑，把该轮升舱并合流等待结果，杜绝重复开窗；
     * - 否则以 `MANUAL` 绕过冷却单飞弹出内置验证窗并等待结果。
     */
    private suspend fun verifyInteractiveChallengeSession(): Boolean {
        if (isFinishing || isDestroyed) return false
        if (PlatformSessionRecovery.forumConnection.stage != ForumConnectionStage.Idle) return false
        do_.linux.lindo.core.network.NetworkPolicyEngine.clearUserDismissedVerify()
        val callerDeadline = kotlin.coroutines.coroutineContext[do_.linux.lindo.core.network.ActiveCallDeadlineContext]?.deadline
        val coordinator = do_.linux.lindo.core.network.NetworkPolicyEngine.verifyCoordinator
        if (coordinator.isActive()) {
            silentPanelRequested = true
        }
        callerDeadline?.pause()
        try {
            return when (
                val decision = coordinator.joinOrStart(
                    requested = do_.linux.lindo.core.network.VerifyPresentation.MANUAL,
                    nowMs = System.currentTimeMillis()
                )
            ) {
                is do_.linux.lindo.core.network.VerifyJoin.Follower -> decision.deferred.await().passed
                is do_.linux.lindo.core.network.VerifyJoin.RecentPass -> true
                is do_.linux.lindo.core.network.VerifyJoin.BlockedByCooldown -> false
                is do_.linux.lindo.core.network.VerifyJoin.Leader -> {
                    var passed = false
                    var abandoned = false
                    try {
                        val result = awaitEmbeddedVerificationModal()
                        passed = result == VerificationDialogResult.Passed
                        abandoned = result == VerificationDialogResult.Expired || result == VerificationDialogResult.Dismissed ||
                            PlatformSessionRecovery.forumConnection.stage != ForumConnectionStage.Idle
                    } catch (ce: CancellationException) {
                        abandoned = true
                        throw ce
                    } finally {
                        coordinator.finishFlight(
                            round = decision.round,
                            passed = passed,
                            finalPresentation = do_.linux.lindo.core.network.VerifyPresentation.MANUAL,
                            abandoned = abandoned && !passed
                        )
                    }
                    if (passed) {
                        do_.linux.lindo.core.network.NetworkPolicyEngine.clearVerifyAttentionAndSnooze()
                        do_.linux.lindo.core.network.NetworkPolicyEngine.cf429Classifier.reset()
                        do_.linux.lindo.core.network.NetworkPolicyEngine.backgroundChallengeTracker.reset()
                        LinuxDoRepository.clearChallengeCooldown()
                        LinuxDoRepository.drainPendingNotificationReadsAsync()
                    }
                    passed
                }
            }
        } finally {
            callerDeadline?.resume()
        }
    }

    /**
     * 单飞静默 `/challenge` 通行证刷新与前台优雅升舱：
     * - 非交互托管挑战自动放行：等待重定向真正落地源站并提取新 CSRF 后再释放 `tempWv`，全程 0 胶囊、0 弹窗；
     * - 一旦确认需要人工点击（`interactiveBegin` 或 `widget == true` 满 3.5s 未自动通过）：
     *   - 正在打字（`< 2.5s`）：暂缓收键盘，展示右下角小提示框并保活 `tempWv`，停笔 `>= 2.5s` 或点提示框 / 点发送立即交棒弹出；
     *   - 点 `×` 180s 免打扰期：非主动写操作保留右下角「需安全确认」小提示框；主动写操作兜底弹出；
     *   - 其余前台状态：直接主动原位交棒弹出「安全确认」（仅当 `interactiveBegin` 或 `widget == true` 已挂载时才无重载交棒撤遮罩）。
     */
    private suspend fun silentRevalidateChallenge(
        previousClearance: String? = null,
        trigger: do_.linux.lindo.core.network.SilentVerifyTrigger = do_.linux.lindo.core.network.SilentVerifyTrigger.FOREGROUND,
        reason: String = "foreground_challenge",
        isForegroundWrite: Boolean = false,
        originalRead: AndroidForumRead? = null
    ): Boolean {
        val isForegroundCaller = trigger == do_.linux.lindo.core.network.SilentVerifyTrigger.FOREGROUND
        if (PlatformSessionRecovery.forumConnection.stage != ForumConnectionStage.Idle) return false
        if (isFinishing || isDestroyed || (!isForegroundCaller && LinuxDoRepository.isRateLimitedNow())) return false
        val callerDeadline = kotlin.coroutines.coroutineContext[do_.linux.lindo.core.network.ActiveCallDeadlineContext]?.deadline
        val coordinator = do_.linux.lindo.core.network.NetworkPolicyEngine.verifyCoordinator
        // 用户自己打开着验证面板（不属于任何静默轮次）时，不再并发起第二个静默 WebView
        if (NetworkRecoveryPolicy.verifying && !coordinator.isActive()) return false
        val previousWasChallenged = !previousClearance.isNullOrBlank() &&
            CfClearanceAuthority.isChallenged(previousClearance)
        val initialPres = if (isForegroundCaller) {
            do_.linux.lindo.core.network.VerifyPresentation.BLOCKING
        } else {
            do_.linux.lindo.core.network.VerifyPresentation.SILENT
        }
        callerDeadline?.pause()
        try {
            val activeRound = when (
                val decision = coordinator.joinOrStart(
                    requested = initialPres,
                    nowMs = System.currentTimeMillis()
                )
            ) {
                is do_.linux.lindo.core.network.VerifyJoin.Leader -> decision.round
                is do_.linux.lindo.core.network.VerifyJoin.BlockedByCooldown -> return false
                is do_.linux.lindo.core.network.VerifyJoin.RecentPass -> return true
                is do_.linux.lindo.core.network.VerifyJoin.Follower -> {
                    // 前台请求加入进行中的预热轮次：把该轮升级为前台语义（可显示胶囊 / 需人工时展开面板）
                    verificationTarget.offer(decision.round, UserApiKeyAuthManager.sessionGeneration, originalRead)
                    if (isForegroundCaller) silentForegroundJoinedAtMs.compareAndSet(0L, System.currentTimeMillis())
                    if (isForegroundWrite) silentForegroundWriteJoined.set(true)
                    return decision.deferred.await().passed
                }
            }
            val startAt = System.currentTimeMillis()
            verificationTarget.offer(activeRound, UserApiKeyAuthManager.sessionGeneration, originalRead)
            if (isForegroundCaller) silentForegroundJoinedAtMs.compareAndSet(0L, startAt)
            if (isForegroundWrite) silentForegroundWriteJoined.set(true)

            var succeeded = false
            var abandoned = false
            var finalPres = initialPres
            var foregroundDialogPassed = false
            var outcome = "unknown"
            var pillShown = false
            var keepAttentionPillOnExit = false
            var tempWv: WebView? = null
            var effectiveTrigger = trigger
            val originLanded = java.util.concurrent.atomic.AtomicBoolean(false)
            val originLandedAtMs = java.util.concurrent.atomic.AtomicLong(0L)
            val mainFrameFailed = java.util.concurrent.atomic.AtomicBoolean(false)
            val jsConsoleWidgetReady = java.util.concurrent.atomic.AtomicBoolean(false)
            val probeWakeSignal = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
            var staleClearancePurgedOnce = false
            var fallbackOriginPageTried = false
            var interactiveWidgetReady = false
            var widgetFirstSeenAtMs = 0L
            var freshCfDetectedAtMs = 0L
            var typingHoldStartedAtMs = 0L
            try {
                val compactFrameJs = buildVerificationCompactFrameJs(isDark = AppAppearance.dark)
                runOnCookieIo {
                    val cm = MainActivity.currentWebViewCookieManager()
                    // Never purge an incumbent cf_clearance during silent background PREWARM:
                    // if Turnstile demands human interaction, PREWARM abandons without UI and
                    // purging the cookie would break foreground reads into a verification loop.
                    if (isForegroundCaller && previousWasChallenged) {
                        purgeCfCookiesInWebKit(cm)
                        staleClearancePurgedOnce = true
                    }
                    ensureAuthCookiesInWebKit(cm)
                }
                val created = withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext false
                    val host = ensureSilentVerifyHost() ?: return@withContext false
                    val cm = currentWebViewCookieManager()
                    val dm = resources.displayMetrics
                    val viewportW = dm.widthPixels.coerceAtLeast(320)
                    val viewportH = minOf(dm.heightPixels, (640f * dm.density).toInt()).coerceAtLeast(480)
                    val wv = WebView(this@MainActivity).apply {
                        bindWebViewToActiveProfile(this)
                        isFocusable = false
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        }
                        cm.setAcceptCookie(true)
                        cm.setAcceptThirdPartyCookies(this, true)
                        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
                            runCatching {
                                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                                    this,
                                    compactFrameJs,
                                    setOf("https://linux.do")
                                )
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                                val msg = consoleMessage?.message().orEmpty()
                                if (msg == "__lindo_cf_widget_ready__") {
                                    jsConsoleWidgetReady.set(true)
                                    probeWakeSignal.trySend(Unit)
                                    return true
                                }
                                return super.onConsoleMessage(consoleMessage)
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                mainFrameFailed.set(true)
                                probeWakeSignal.trySend(Unit)
                                if (tempWv === view) tempWv = null
                                releaseNativeWebView(view, rendererGone = true)
                                return true
                            }
                            override fun onPageCommitVisible(view: WebView?, url: String?) {
                                super.onPageCommitVisible(view, url)
                                view?.evaluateJavascript(compactFrameJs, null)
                                probeWakeSignal.trySend(Unit)
                            }
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                view?.evaluateJavascript(compactFrameJs, null)
                                probeWakeSignal.trySend(Unit)
                            }
                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: android.webkit.WebResourceError?
                            ) {
                                super.onReceivedError(view, request, error)
                                if (request?.isForMainFrame == true) {
                                    mainFrameFailed.set(true)
                                    probeWakeSignal.trySend(Unit)
                                }
                            }
                            override fun onReceivedHttpError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                errorResponse: android.webkit.WebResourceResponse?
                            ) {
                                super.onReceivedHttpError(view, request, errorResponse)
                                if (request?.isForMainFrame == true) {
                                    val status = errorResponse?.statusCode ?: 0
                                    if (status in setOf(502, 504)) {
                                        mainFrameFailed.set(true)
                                        probeWakeSignal.trySend(Unit)
                                    } else if (request.url?.host.equals("linux.do", ignoreCase = true) &&
                                        request.url?.path?.startsWith("/challenge") == true &&
                                        status == 404 &&
                                        errorResponse?.responseHeaders?.entries?.none {
                                            it.key.equals("cf-mitigated", ignoreCase = true) &&
                                                it.value.contains("challenge", ignoreCase = true)
                                        } != false
                                    ) {
                                        // 抵达源站 /challenge 轻量 404 = 已穿过 Cloudflare WAF
                                        originLandedAtMs.compareAndSet(0L, System.currentTimeMillis())
                                        originLanded.set(true)
                                        probeWakeSignal.trySend(Unit)
                                    }
                                }
                            }
                            override fun onReceivedSslError(
                                view: WebView?,
                                handler: android.webkit.SslErrorHandler?,
                                error: android.net.http.SslError?
                            ) {
                                val host = error?.url?.let { runCatching { Uri.parse(it).host }.getOrNull() }
                                if (do_.linux.lindo.ech.EchGatewayManager.verifyWebViewSslError(error, host)) {
                                    handler?.proceed()
                                } else {
                                    handler?.cancel()
                                }
                            }
                        }
                    }
                    tempWv = wv
                    host.removeAllViews()
                    host.addView(wv, android.widget.FrameLayout.LayoutParams(viewportW, viewportH))
                    wv.onResume()
                    wv.resumeTimers()
                    wv.loadUrl("https://linux.do/challenge")
                    true
                }
                if (!created) {
                    outcome = "abandoned:host_unavailable"
                    abandoned = true
                    return false
                }

                suspend fun evalJs(wv: WebView, script: String): String? = withTimeoutOrNull(1_500L) {
                    suspendCancellableCoroutine<String?> { cont ->
                        wv.evaluateJavascript(script) { raw -> if (cont.isActive) cont.resume(raw) }
                    }
                }

                var cfErrorRecoveries = 0
                while (true) {
                    withTimeoutOrNull(200L) { probeWakeSignal.receive() }
                    val now = System.currentTimeMillis()
                    val fgJoinedAt = silentForegroundJoinedAtMs.get()
                    effectiveTrigger = if (isForegroundCaller || fgJoinedAt > 0L) {
                        do_.linux.lindo.core.network.SilentVerifyTrigger.FOREGROUND
                    } else {
                        do_.linux.lindo.core.network.SilentVerifyTrigger.PREWARM
                    }
                    val elapsed = if (effectiveTrigger == do_.linux.lindo.core.network.SilentVerifyTrigger.FOREGROUND && fgJoinedAt > 0L) {
                        now - fgJoinedAt
                    } else {
                        now - startAt
                    }
                    val effectiveIsForegroundWrite = isForegroundWrite || silentForegroundWriteJoined.get()
                    val signal = withContext(Dispatchers.Main) {
                        val wv = tempWv ?: return@withContext do_.linux.lindo.core.network.SilentProbeSignal.GONE
                        if (mainFrameFailed.get()) return@withContext do_.linux.lindo.core.network.SilentProbeSignal.GONE
                        val latestCookies = runCatching { currentWebViewCookieManager().getCookie("https://linux.do").orEmpty() }.getOrDefault("")
                        val freshCf = getCookieValue(latestCookies, "cf_clearance")
                        val cfRotatedNow = !freshCf.isNullOrBlank() &&
                            freshCf != previousClearance &&
                            !CfClearanceAuthority.isChallenged(freshCf)
                        if (cfRotatedNow && freshCfDetectedAtMs == 0L) {
                            freshCfDetectedAtMs = now
                        }

                        val parsed = decodeJsStringResult(evalJs(wv, do_.linux.lindo.core.network.webVerificationProbeScript()))
                            ?.let { runCatching { JSONObject(it) }.getOrNull() }

                        if (parsed?.optBoolean("cfErrorPage") == true) {
                            if (cfErrorRecoveries < 2) {
                                cfErrorRecoveries += 1
                                widgetFirstSeenAtMs = 0L
                                interactiveWidgetReady = false
                                freshCfDetectedAtMs = 0L
                                originLanded.set(false)
                                originLandedAtMs.set(0L)
                                do_.linux.lindo.ech.EchGatewayManager.rotateStickyIpAsync()
                                purgeCfCookiesInWebKit(currentWebViewCookieManager())
                                if (tempWv === wv && !mainFrameFailed.get()) {
                                    wv.loadUrl("https://linux.do/challenge")
                                }
                                return@withContext do_.linux.lindo.core.network.SilentProbeSignal.PENDING
                            }
                            return@withContext do_.linux.lindo.core.network.SilentProbeSignal.GONE
                        }

                        if (originLanded.get()) {
                            val isFgNow = isForegroundCaller ||
                                effectiveTrigger == do_.linux.lindo.core.network.SilentVerifyTrigger.FOREGROUND
                            // 若旧票据已被实测 403/429 证伪，而 /challenge 未换新即直达 404，仅在前台验证时清理一次残留旧票据以无票态重探一次
                            if (isFgNow && previousWasChallenged && freshCf == previousClearance && !staleClearancePurgedOnce) {
                                staleClearancePurgedOnce = true
                                originLanded.set(false)
                                originLandedAtMs.set(0L)
                                widgetFirstSeenAtMs = 0L
                                interactiveWidgetReady = false
                                purgeCfCookiesInWebKit(currentWebViewCookieManager())
                                if (tempWv === wv && !mainFrameFailed.get()) {
                                    wv.loadUrl("https://linux.do/challenge")
                                }
                                return@withContext do_.linux.lindo.core.network.SilentProbeSignal.PENDING
                            }
                            // 当 /challenge 未被边缘 WAF 质询直达 404 且未签发 cf_clearance 时，前台请求单次切往真实页面路径触发同源路由规则或确认直通
                            if (isFgNow && freshCf.isNullOrBlank() && !fallbackOriginPageTried) {
                                fallbackOriginPageTried = true
                                originLanded.set(false)
                                originLandedAtMs.set(0L)
                                widgetFirstSeenAtMs = 0L
                                interactiveWidgetReady = false
                                if (tempWv === wv && !mainFrameFailed.get()) {
                                    wv.loadUrl(originalRead?.pageUrl() ?: "https://linux.do/")
                                }
                                return@withContext do_.linux.lindo.core.network.SilentProbeSignal.PENDING
                            }
                            val csrfFromProbe = parsed?.optString("csrf").orEmpty().ifBlank {
                                decodeJsStringResult(
                                    evalJs(
                                        wv,
                                        "(function(){var m=document.querySelector('meta[name=\"csrf-token\"]');return (m&&m.content)?m.content:'';})();"
                                    )
                                ).orEmpty().trim()
                            }
                            if (csrfFromProbe.isNotBlank()) {
                                LinuxDoRepository.updateCsrfToken(csrfFromProbe)
                                return@withContext do_.linux.lindo.core.network.SilentProbeSignal.PASSED
                            }
                            // 给源站 404 HTML 最多 800ms 完成 DOM 解析提取最新 CSRF，随后放行
                            val landedAt = originLandedAtMs.get()
                            if (landedAt > 0L && (now - landedAt) < 800L && parsed?.optBoolean("waiting") == true) {
                                return@withContext do_.linux.lindo.core.network.SilentProbeSignal.PENDING
                            }
                            return@withContext do_.linux.lindo.core.network.SilentProbeSignal.PASSED
                        }

                        if (parsed?.optBoolean("ok") == true) {
                            val isFgNow = isForegroundCaller ||
                                effectiveTrigger == do_.linux.lindo.core.network.SilentVerifyTrigger.FOREGROUND
                            if (isFgNow && previousWasChallenged && freshCf == previousClearance && !staleClearancePurgedOnce) {
                                staleClearancePurgedOnce = true
                                originLanded.set(false)
                                originLandedAtMs.set(0L)
                                widgetFirstSeenAtMs = 0L
                                interactiveWidgetReady = false
                                purgeCfCookiesInWebKit(currentWebViewCookieManager())
                                if (tempWv === wv && !mainFrameFailed.get()) {
                                    wv.loadUrl("https://linux.do/challenge")
                                }
                                return@withContext do_.linux.lindo.core.network.SilentProbeSignal.PENDING
                            }
                            parsed.optString("csrf").takeIf { it.isNotBlank() }?.let { LinuxDoRepository.updateCsrfToken(it) }
                            return@withContext do_.linux.lindo.core.network.SilentProbeSignal.PASSED
                        }

                        // 若新 cf_clearance 已签发，严格等待过盾表单重定向落地源站（originLanded 或 parsed.ok == true），
                        // 仅当已离开挑战页且等待超过 4.5s 时兜底放行，杜绝早退强断重定向！
                        if (cfRotatedNow &&
                            freshCfDetectedAtMs > 0L &&
                            (now - freshCfDetectedAtMs) >= 4_500L &&
                            parsed?.optBoolean("challenge") != true
                        ) {
                            return@withContext do_.linux.lindo.core.network.SilentProbeSignal.PASSED
                        }

                        val jsInteractive = jsConsoleWidgetReady.get() ||
                            parsed?.optBoolean("interactive") == true ||
                            decodeJsStringResult(evalJs(wv, "window.__lindoTurnstileInteractiveBegin === true ? '1' : '0'")) == "1"
                        val hasWidget = parsed?.optBoolean("widget") == true
                        if (hasWidget) {
                            interactiveWidgetReady = true
                            if (widgetFirstSeenAtMs == 0L) widgetFirstSeenAtMs = now
                        } else {
                            widgetFirstSeenAtMs = 0L
                        }
                        if (jsInteractive) interactiveWidgetReady = true
                        val mountedMs = if (widgetFirstSeenAtMs > 0L) now - widgetFirstSeenAtMs else 0L
                        if (!cfRotatedNow && do_.linux.lindo.core.network.SilentVerifyPolicy.isInteractiveConfirmed(jsInteractive, mountedMs)) {
                            return@withContext do_.linux.lindo.core.network.SilentProbeSignal.INTERACTIVE_REQUIRED
                        }
                        do_.linux.lindo.core.network.SilentProbeSignal.PENDING
                    }
                    if (!pillShown && do_.linux.lindo.core.network.SilentVerifyPolicy.shouldShowPill(effectiveTrigger, elapsed)) {
                        pillShown = true
                        withContext(Dispatchers.Main) { do_.linux.lindo.ui.PlatformSessionRecovery.verifyPillVisible = true }
                    }
                    val snoozedNow = do_.linux.lindo.core.network.NetworkPolicyEngine.isProactiveVerifySnoozed(nowMs = now)
                    val typingNow = checkIsUserActivelyTypingNow(nowMs = now)
                    val step = do_.linux.lindo.core.network.SilentVerifyPolicy.decide(
                        trigger = effectiveTrigger,
                        signal = signal,
                        elapsedMs = elapsed,
                        appForeground = UserPresenceService.isForeground,
                        userRequestedPanel = silentPanelRequested,
                        isForegroundWrite = effectiveIsForegroundWrite,
                        isUserDismissSnoozed = snoozedNow,
                        isUserActivelyTyping = typingNow
                    )
                    when (step) {
                        do_.linux.lindo.core.network.SilentVerifyStep.Continue -> continue
                        do_.linux.lindo.core.network.SilentVerifyStep.HoldForTyping -> {
                            if (typingHoldStartedAtMs == 0L) typingHoldStartedAtMs = now
                            keepAttentionPillOnExit = true
                            pillShown = true
                            do_.linux.lindo.core.network.NetworkPolicyEngine.updateVerifyAttentionNeeded(true)
                            withContext(Dispatchers.Main) {
                                do_.linux.lindo.ui.PlatformSessionRecovery.verifyPillVisible = true
                            }
                            if ((now - typingHoldStartedAtMs) >= 90_000L) {
                                outcome = "abandoned:typing_hold_cap"
                                abandoned = true
                                break
                            }
                            continue
                        }
                        do_.linux.lindo.core.network.SilentVerifyStep.Pass -> {
                            keepAttentionPillOnExit = false
                            outcome = "passed"
                            break
                        }
                        is do_.linux.lindo.core.network.SilentVerifyStep.Escalate -> {
                            keepAttentionPillOnExit = false
                            // 仅当 interactiveBegin 已触发或探针确认 widget == true 时才无重载交棒 tempWv 并撤遮罩，彻底根治 00:00 空白窗！
                            withContext(Dispatchers.Main) {
                                tempWv?.let { wv ->
                                    tempWv = null
                                    val canHandoff = interactiveWidgetReady &&
                                        !mainFrameFailed.get() &&
                                        wv.url?.startsWith("https://linux.do") == true
                                    if (canHandoff) {
                                        stashHandoffVerifyWebView(wv)
                                    } else {
                                        runCatching { releaseNativeWebView(wv) }
                                    }
                                }
                                do_.linux.lindo.ui.PlatformSessionRecovery.verifyPillVisible = false
                                pillShown = false
                            }
                            finalPres = do_.linux.lindo.core.network.VerifyPresentation.BLOCKING
                            val result = awaitEmbeddedVerificationModal()
                            foregroundDialogPassed = result == VerificationDialogResult.Passed
                            abandoned = result == VerificationDialogResult.Expired || result == VerificationDialogResult.Dismissed ||
                                PlatformSessionRecovery.forumConnection.stage != ForumConnectionStage.Idle
                            outcome = when (result) {
                                VerificationDialogResult.Passed -> "passed_after_escalate:${step.reason}"
                                VerificationDialogResult.Expired -> "abandoned:dialog_expired"
                                VerificationDialogResult.Dismissed -> "abandoned:user_dismissed"
                                VerificationDialogResult.Unavailable -> "failed:dialog_unavailable"
                            }
                            break
                        }
                        is do_.linux.lindo.core.network.SilentVerifyStep.Abandon -> {
                            if (step.reason == "snoozed_by_user_dismiss") {
                                keepAttentionPillOnExit = true
                                do_.linux.lindo.core.network.NetworkPolicyEngine.updateVerifyAttentionNeeded(true)
                                withContext(Dispatchers.Main) {
                                    do_.linux.lindo.ui.PlatformSessionRecovery.verifyPillVisible = true
                                }
                            } else if (step.reason == "app_backgrounded" &&
                                signal == do_.linux.lindo.core.network.SilentProbeSignal.INTERACTIVE_REQUIRED
                            ) {
                                do_.linux.lindo.core.network.NetworkPolicyEngine.pendingInteractiveVerifyFromBackground = true
                            }
                            outcome = "abandoned:${step.reason}"
                            abandoned = true
                            break
                        }
                        is do_.linux.lindo.core.network.SilentVerifyStep.Fail -> {
                            outcome = "failed:${step.reason}"
                            break
                        }
                    }
                }

                if (foregroundDialogPassed) {
                    succeeded = true
                } else if (outcome == "passed") {
                    val latest = runOnCookieIo {
                        val cm = MainActivity.currentWebViewCookieManager()
                        runCatching { cm.flush() }
                        cm.getCookie("https://linux.do").orEmpty()
                    }
                    val freshCf = getCookieValue(latest, "cf_clearance")
                    val isFgFlow = isForegroundCaller ||
                        effectiveTrigger == do_.linux.lindo.core.network.SilentVerifyTrigger.FOREGROUND
                    when (CfClearanceAuthority.evaluateVerifyOutcome(freshCf, previousClearance, previousWasChallenged)) {
                        do_.linux.lindo.core.network.VerifyTicketOutcome.ROTATED -> {
                            if (latest.isNotBlank()) {
                                CookieSentinel.syncFromAuthoritativeBrowserCookieString(latest, verified = true)
                            }
                            succeeded = true
                            if (outcome == "passed") outcome = "passed:rotated"
                        }
                        do_.linux.lindo.core.network.VerifyTicketOutcome.EXISTING_VALID -> {
                            if (latest.isNotBlank()) {
                                CookieSentinel.syncFromAuthoritativeBrowserCookieString(latest, verified = true)
                            }
                            if (isFgFlow) {
                                succeeded = true
                                outcome = "passed:existing_cf_valid"
                            } else {
                                succeeded = false
                                abandoned = true
                                outcome = "skipped:existing_cf_valid"
                            }
                        }
                        do_.linux.lindo.core.network.VerifyTicketOutcome.STALE_UNCHALLENGED_404 -> {
                            // 旧票据刚被实测拒绝且 /challenge 未换新：严禁洗白旧票据！
                            CfClearanceAuthority.noteChallenged(previousClearance)
                            succeeded = false
                            outcome = "failed:stale_clearance_not_rotated"
                        }
                        do_.linux.lindo.core.network.VerifyTicketOutcome.ABSENT -> {
                            if (!isFgFlow) {
                                succeeded = false
                                abandoned = true
                                outcome = "skipped:origin_not_challenged"
                            } else {
                                // Clean origin HTML rendered without issuing cf_clearance; let checkVerifiedForumConnection
                                // run a single real JSON read to confirm whether the API channel is now open.
                                succeeded = true
                                outcome = "passed:clean_origin_verified"
                            }
                        }
                    }
                }
                if (succeeded && !foregroundDialogPassed && !checkVerifiedForumConnection(false)) {
                    val wasCleanOriginProbe = outcome == "passed:clean_origin_verified"
                    succeeded = false
                    abandoned = !wasCleanOriginProbe
                    outcome = if (wasCleanOriginProbe) "failed:passed_without_cf_clearance" else "abandoned:connection_not_ready"
                }
                if (succeeded) {
                    keepAttentionPillOnExit = false
                    do_.linux.lindo.core.network.NetworkPolicyEngine.clearVerifyAttentionAndSnooze()
                    do_.linux.lindo.core.network.NetworkPolicyEngine.cf429Classifier.reset()
                    do_.linux.lindo.core.network.NetworkPolicyEngine.backgroundChallengeTracker.reset()
                    LinuxDoRepository.clearChallengeCooldown()
                    withContext(Dispatchers.Main) {
                        do_.linux.lindo.ui.PlatformSessionRecovery.verifyPillVisible = false
                    }
                }
            } catch (cancelled: CancellationException) {
                outcome = "abandoned:cancelled"
                abandoned = true
                throw cancelled
            } catch (err: Throwable) {
                outcome = "failed:${err::class.java.simpleName}"
                succeeded = false
            } finally {
                val preservePill = keepAttentionPillOnExit || do_.linux.lindo.core.network.NetworkPolicyEngine.verifyAttentionNeeded
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                    tempWv?.let { wv ->
                        tempWv = null
                        runCatching { releaseNativeWebView(wv) }
                    }
                    if (pillShown && !preservePill) {
                        do_.linux.lindo.ui.PlatformSessionRecovery.verifyPillVisible = false
                    }
                }
                verificationTarget.release(activeRound)
                coordinator.finishFlight(
                    round = activeRound,
                    passed = succeeded,
                    finalPresentation = finalPres,
                    abandoned = abandoned && !succeeded
                )
                silentForegroundJoinedAtMs.set(0L)
                silentForegroundWriteJoined.set(false)
                silentPanelRequested = false
                val elapsedMs = System.currentTimeMillis() - startAt
                val summary = "round=$activeRound trigger=${effectiveTrigger.name.lowercase()} reason=$reason outcome=$outcome elapsed=${elapsedMs}ms hasCf=$succeeded profile=$activeWebViewProfileName net=${activeTransportSignature.ifBlank { "none" }}"
                if (succeeded || outcome.startsWith("skipped:")) {
                    LinDoDiagnosticLogger.info(DiagnosticCategory.AUTH, "SilentVerify", "Cloudflare verification completed ($summary)")
                } else {
                    LinDoDiagnosticLogger.warn(DiagnosticCategory.AUTH, "SilentVerify", "Silent verification not completed ($summary)")
                }
            }
            if (succeeded) {
                if (!foregroundDialogPassed) withContext(Dispatchers.Main) {
                    do_.linux.lindo.ui.PlatformSessionRecovery.onSilentVerifyPassed?.invoke()
                }
                LinuxDoRepository.drainPendingNotificationReadsAsync()
            }
            return succeeded
        } finally {
            callerDeadline?.resume()
        }
    }

    private suspend fun executeHttpViaWebViewBridge(
        url: String,
        method: String,
        formParams: Map<String, String>?,
        headers: Map<String, String>,
        allowSilentChallengeRetry: Boolean = true,
        allowOriginLostRetry: Boolean = true
    ): Pair<Int, String>? {
        if (LinuxDoRepository.isRateLimitedNow()) return 429 to """{"errors":["请求较频繁，请稍后再试"]}"""
        val isRead = method.equals("GET", true) || method.equals("HEAD", true)
        val isBackground = headers.entries.any {
            it.key.equals("Discourse-Background", ignoreCase = true) && it.value.equals("true", ignoreCase = true)
        }
        val isPauseFlushAllowed = url.contains("/topics/timings", ignoreCase = true) && pauseWebViewJob?.isActive == true
        // App 已切入后台/Doze 休眠态时，跳过所有非切后台瞬时 flush 的后台请求，杜绝 WebView 冻结导致假超时或唤醒风暴
        if (!UserPresenceService.isForeground && isBackground && !isPauseFlushAllowed) {
            return 0 to "[BRIDGE_DIAG:app_paused] skippedInBg=true bg=true fg=false gen=$apiWebViewGeneration net=${activeTransportSignature.ifBlank { "none" }}"
        }
        if (pendingTransportRecycleOnNextUse && !isFinishing && !isDestroyed) {
            val recycleGen = apiWebViewGeneration
            do_.linux.lindo.ech.EchGatewayManager.awaitReconcileAndProxyReady()
            rebuildApiWebView(reason = "pending_network_recycle", force = true, expectedGeneration = recycleGen)
        }
        if (apiWebView == null && !isFinishing && !isDestroyed) {
            withContext(Dispatchers.Main) {
                if (apiWebView == null && !isFinishing && !isDestroyed) {
                    initPersistentApiWebView()
                }
            }
        }
        if (apiWebView == null) {
            return 0 to "[BRIDGE_DIAG:webview_null_pre] fg=${UserPresenceService.isForeground} gen=$apiWebViewGeneration net=${activeTransportSignature.ifBlank { "none" }}"
        }
        val preWaitGen = apiWebViewGeneration
        val preReadyStartMs = System.currentTimeMillis()
        val isReady = withTimeoutOrNull(8_000L) { apiWebViewReady.await() }
        if (isReady != true || !apiWebViewOriginHealthy) {
            val preReadyWaitMs = System.currentTimeMillis() - preReadyStartMs
            if (!UserPresenceService.isForeground) {
                return 0 to "[BRIDGE_DIAG:app_paused] readyWait=${preReadyWaitMs}ms bg=$isBackground fg=false gen=$preWaitGen net=${activeTransportSignature.ifBlank { "none" }}"
            }
            val recovered = !isFinishing && !isDestroyed &&
                rebuildApiWebView(
                    reason = if (isReady == false) "bootstrap_origin_error" else "ready_await_timeout",
                    force = isReady == false,
                    expectedGeneration = preWaitGen
                ) &&
                apiWebViewOriginHealthy
            if (!recovered) {
                return 0 to "[BRIDGE_DIAG:webview_not_ready_pre] readyWait=${preReadyWaitMs}ms fg=${UserPresenceService.isForeground} gen=$preWaitGen net=${activeTransportSignature.ifBlank { "none" }}"
            }
        }

        val reqId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Pair<Int, String>>()

        val formBodyEncoded = if (!formParams.isNullOrEmpty()) {
            do_.linux.lindo.core.network.formEntries(formParams).joinToString("&") { (k, v) ->
                "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
            }
        } else {
            ""
        }

        val sessionCookieManager = MainActivity.currentWebViewCookieManager()
        val preExecCookies = ensureAuthCookiesInWebKit(sessionCookieManager)
        val hasBrowserSessionCookie = hasCookieNamed(preExecCookies, "_t")
        val preferredReferer = headers.entries.firstOrNull {
            it.key.equals("Referer", ignoreCase = true)
        }?.value?.takeIf { it.startsWith("https://linux.do/") } ?: "https://linux.do/"

        val sanitizedHeaders = do_.linux.lindo.core.network.AndroidWebViewBridgeContract.sanitizeJsHeaders(
            rawHeaders = headers,
            hasBrowserSessionCookie = hasBrowserSessionCookie
        )
        val initialCachedCsrf = LinuxDoRepository.getCachedCsrfToken().orEmpty()
        val currentCsrfOwner = csrfOwnerTag(preExecCookies)
        val reqIdQuoted = JSONObject.quote(reqId)
        val timeoutBudget = do_.linux.lindo.core.network.AndroidWebViewBridgeContract.computeTimeouts(
            isRead = isRead,
            isBackground = isBackground
        )
        val jsCode = do_.linux.lindo.core.network.AndroidWebViewBridgeContract.buildHttpBridgeScript(
            do_.linux.lindo.core.network.AndroidHttpBridgeConfig(
                reqId = reqId,
                url = url,
                method = method,
                headers = sanitizedHeaders,
                formBodyEncoded = formBodyEncoded,
                preferredReferer = preferredReferer,
                csrfOwnerTag = currentCsrfOwner,
                initialCachedCsrf = initialCachedCsrf,
                csrfInCooldown = do_.linux.lindo.core.network.NetworkPolicyEngine.isCsrfInCooldown(),
                csrfFallbackErrorMsg = LinDoNetworkCopy.JS_BRIDGE_VERIFY_SESSION_FAILED.text,
                timeouts = timeoutBudget
            )
        )

        val httpSemaphore = if (isBackground) {
            webViewBackgroundHttpSemaphore
        } else {
            webViewForegroundHttpSemaphore
        }
        val bridgeTimeoutMs = timeoutBudget.kotlinTimeoutMs
        val queueStartMs = System.currentTimeMillis()
        httpSemaphore.acquire()
        val queueWaitMs = System.currentTimeMillis() - queueStartMs
        var readyWaitMs = 0L
        var completedFromJs = false
        var bridgeResult: Pair<Int, String>? = null
        var dispatchedWv: WebView? = null
        var observedGen = apiWebViewGeneration
        fun buildKotlinDiagContext(): String {
            val netSig = activeTransportSignature.ifBlank { "none" }
            return "queueWait=${queueWaitMs}ms readyWait=${readyWaitMs}ms bg=$isBackground fg=${UserPresenceService.isForeground} gen=$observedGen inflight=${pendingHttpCallbacks.size} consecFail=${consecutiveBridgeFailures.get()} net=$netSig"
        }
        try {
            if (apiWebView == null && !isFinishing && !isDestroyed) {
                withContext(Dispatchers.Main) {
                    if (apiWebView == null && !isFinishing && !isDestroyed) {
                        initPersistentApiWebView()
                    }
                }
            }
            if (!apiWebViewReady.isCompleted || !apiWebViewOriginHealthy) {
                val postAcquireGen = apiWebViewGeneration
                val readyStartMs = System.currentTimeMillis()
                val readyAfterAcquire = withTimeoutOrNull(8_000L) { apiWebViewReady.await() }
                readyWaitMs = System.currentTimeMillis() - readyStartMs
                if (readyAfterAcquire != true || !apiWebViewOriginHealthy) {
                    if (!UserPresenceService.isForeground) {
                        return 0 to "[BRIDGE_DIAG:app_paused] ${buildKotlinDiagContext()}"
                    }
                    val recovered = !isFinishing && !isDestroyed &&
                        rebuildApiWebView(
                            reason = if (readyAfterAcquire == false) "bootstrap_origin_error" else "ready_await_timeout",
                            force = readyAfterAcquire == false,
                            expectedGeneration = postAcquireGen
                        ) &&
                        apiWebViewOriginHealthy
                    if (!recovered) {
                        return 0 to "[BRIDGE_DIAG:webview_not_ready] ${buildKotlinDiagContext()}"
                    }
                }
            }
            apiWebViewNavBarrier?.let { if (!it.await()) return 0 to "[BRIDGE_DIAG:origin_not_ready]" }
            // 用户刚授权的 OTP 与主动前台写操作（未处于验证弹窗内）不受读暂停短路，确保发帖/回复/助力兜底弹出验证
            val isOtpExchangeUrl = url.contains("/session/otp/", ignoreCase = true)
            val isExplicitFgWrite = !isRead && !isBackground && !NetworkRecoveryPolicy.verifying
            if (!isOtpExchangeUrl && !isExplicitFgWrite && LinuxDoRepository.shouldPauseForSiteVerification(url)) {
                return 403 to "${CF_MITIGATED_CHALLENGE_PREFIX}<html>需要完成站点验证</html>"
            }
            if (LinuxDoRepository.isRateLimitedNow()) return 429 to """{"errors":["请求较频繁，请稍后再试"]}"""
            val isSilentTimingUrl = url.contains("/topics/timings", ignoreCase = true)
            if (isSilentTimingUrl) silentTimingReqIds.add(reqId)
            val createdWallMs = System.currentTimeMillis()
            pendingHttpCallbacks[reqId] = deferred
            pendingHttpMeta[reqId] = PendingHttpMeta(
                isBackground = isBackground,
                isRead = isRead,
                createdAtWallMs = createdWallMs,
                docEpoch = apiWebViewDocEpoch.get()
            )
            // 将 CookieManager 读写下沉到专用 HandlerThread（CookieIoThread），主线程零 Binder/SQLite 竞争
            runOnCookieIo {
                val preCm = MainActivity.currentWebViewCookieManager()
                val cookieStr = ensureAuthCookiesInWebKit(preCm)
                if (cookieStr.isNotBlank()) {
                    CookieSentinel.syncFromAuthoritativeBrowserCookieString(cookieStr)
                }
            }
            val launched = withContext(Dispatchers.Main) {
                val liveWv = apiWebView ?: return@withContext false
                dispatchedWv = liveWv
                observedGen = apiWebViewGeneration
                pendingHttpMeta[reqId] = PendingHttpMeta(
                    isBackground = isBackground,
                    isRead = isRead,
                    createdAtWallMs = createdWallMs,
                    docEpoch = apiWebViewDocEpoch.get()
                )
                attachApiWebViewInTinyHost(liveWv)
                if (UserPresenceService.isForeground) {
                    liveWv.onResume()
                    liveWv.resumeTimers()
                }
                liveWv.evaluateJavascript(jsCode, null)
                true
            }
            if (!launched) {
                return 0 to "[BRIDGE_DIAG:webview_null] ${buildKotlinDiagContext()}"
            }

            val response = withTimeoutOrNull(bridgeTimeoutMs) {
                deferred.await()
            }
            if (response != null) {
                completedFromJs = true
            }
            bridgeResult = response
            // 认证/写请求回包后的 Cookie 同步必须在返回前完成（避免上层立即读 CookieSentinel 时出现竞态空读）；
            // 普通读 GET 继续走异步 CookieIoThread 投递，保持列表滑动零阻塞。
            val mustAwaitCookieSync = !isRead || url.contains("/session/", ignoreCase = true)
            val syncResponseCookies = {
                val cm = runCatching { MainActivity.currentWebViewCookieManager() }.getOrNull()
                val latestCookies = runCatching { cm?.getCookie("https://linux.do") }.getOrNull().orEmpty()
                if (latestCookies.isNotBlank()) {
                    CookieSentinel.syncFromAuthoritativeBrowserCookieString(latestCookies)
                    val now = System.currentTimeMillis()
                    val sessionRotated = hasCookieNamed(latestCookies, "_t") &&
                        getCookieValue(latestCookies, "_t") != getCookieValue(lastObservedCookieSnapshot, "_t")
                    if (sessionRotated || !isRead || (latestCookies != lastObservedCookieSnapshot && now - lastCookieFlushEpochMs > 5_000L)) {
                        lastObservedCookieSnapshot = latestCookies
                        lastCookieFlushEpochMs = now
                        runCatching { cm?.flush() }
                    }
                    if (sessionRotated && UserApiKeyAuthManager.currentUser != null) {
                        UserApiKeyAuthManager.persistSessionQuietly()
                    }
                } else if (!isRead) {
                    runCatching { cm?.flush() }
                }
            }
            if (mustAwaitCookieSync) {
                runOnCookieIo { syncResponseCookies() }
            } else {
                postOnCookieIo { syncResponseCookies() }
            }
        } finally {
            silentTimingReqIds.remove(reqId)
            pendingHttpCallbacks.remove(reqId)
            pendingHttpMeta.remove(reqId)
            val targetWv = dispatchedWv
            if (!completedFromJs && targetWv != null) {
                runOnUiThread {
                    if (targetWv === apiWebView) {
                        runCatching {
                            targetWv.evaluateJavascript(
                                "if(window.__lindoCancelHttp)window.__lindoCancelHttp($reqIdQuoted);",
                                null
                            )
                        }
                    }
                }
            }
            httpSemaphore.release()
        }

        if (bridgeResult?.first == REBUILD_ABORTED_STATUS) {
            return 0 to "[BRIDGE_DIAG:rebuild_aborted] ${buildKotlinDiagContext()}"
        }

        if (bridgeResult != null && bridgeResult!!.first > 0) {
            consecutiveBridgeFailures.set(0)
            consecutiveTimeoutBlackholeCount.set(0)
        } else if (!completedFromJs) {
            do_.linux.lindo.ech.EchGatewayManager.rotateStickyIpAsync()
            // 仅在 JS 硬上限（jsHardCapMs）之后再过 6s 仍彻底无回调（Blink/渲染进程真挂死）时才重建 WebView；
            // 若当前为后台请求超时且存在在途前台请求（尤其写操作），严禁立即 rebuildApiWebView 腰斩在途前台请求！
            if (UserPresenceService.isForeground) {
                consecutiveBridgeFailures.incrementAndGet()
                val hasInFlightForeground = pendingHttpMeta.values.any { !it.isBackground }
                if (isBackground && hasInFlightForeground) {
                    pendingTransportRecycleOnNextUse = true
                    LinDoDiagnosticLogger.warn(
                        DiagnosticCategory.NETWORK,
                        "WebViewBridge",
                        "Deferred apiWebView rebuild on background timeout to protect in-flight foreground request (gen=$observedGen)"
                    )
                } else {
                    val echActive = do_.linux.lindo.core.network.EchRoutingPolicy.snapshot.active
                    val promotedFakeVpnToEch = !echActive &&
                        !isBackground &&
                        do_.linux.lindo.ech.EchGatewayManager.noteProxyTunnelUnreachable()
                    val shouldRotate = !echActive &&
                        !promotedFakeVpnToEch &&
                        !isBackground &&
                        activeValidatedNetworkHandle != 0L &&
                        !LinuxDoRepository.isRateLimitedNow() &&
                        !LinuxDoRepository.isChallengeCooldownActive() &&
                        !NetworkRecoveryPolicy.verifying &&
                        !do_.linux.lindo.core.network.NetworkPolicyEngine.verifyCoordinator.isActive() &&
                        isMultiProfileSupported() &&
                        do_.linux.lindo.core.network.NetworkPolicyEngine.timeoutBlackholeDetector.recordConnectTimeout("kotlin_timeout")
                    activityScope.launch {
                        if (promotedFakeVpnToEch) {
                            do_.linux.lindo.ech.EchGatewayManager.awaitReconcileAndProxyReady()
                            rebuildApiWebView(
                                reason = "smart_auto_vpn_fallback_ech",
                                force = true,
                                expectedGeneration = observedGen
                            )
                        } else if (shouldRotate) {
                            rotateWebViewNetworkProfile(
                                reason = "confirmed_connect_blackhole",
                                expectedGeneration = observedGen
                            )
                        } else {
                            rebuildApiWebView(reason = "js_bridge_no_callback", expectedGeneration = observedGen)
                        }
                    }
                }
            }
            return 0 to "[BRIDGE_DIAG:kotlin_timeout] timeout=${bridgeTimeoutMs}ms ${buildKotlinDiagContext()}"
        }

        if (bridgeResult != null && bridgeResult!!.first == 0) {
            val rawZero = bridgeResult!!.second
            if (rawZero.startsWith("[BRIDGE_DIAG:origin_lost]")) {
                apiWebViewOriginHealthy = false
                if (allowOriginLostRetry && UserPresenceService.isForeground && !isFinishing && !isDestroyed) {
                    val restored = refreshApiWebViewOriginAfterVerify(forceReload = true)
                    if (restored && isRead && !url.contains("/session/otp/", ignoreCase = true)) {
                        return executeHttpViaWebViewBridge(
                            url = url,
                            method = method,
                            formParams = formParams,
                            headers = headers,
                            allowSilentChallengeRetry = allowSilentChallengeRetry,
                            allowOriginLostRetry = false
                        )
                    }
                }
            }
            val isRealNetTimeoutOrDrop = rawZero.startsWith("[BRIDGE_DIAG:js_timeout]") ||
                rawZero.startsWith("[BRIDGE_DIAG:csrf_timeout]") ||
                rawZero.startsWith("[BRIDGE_DIAG:js_fetch_err]") ||
                rawZero.startsWith("[BRIDGE_DIAG:csrf_net_err]")
            if (isRealNetTimeoutOrDrop) {
                do_.linux.lindo.ech.EchGatewayManager.rotateStickyIpAsync()
            }
            if (isRealNetTimeoutOrDrop &&
                !isBackground &&
                UserPresenceService.isForeground &&
                activeValidatedNetworkHandle != 0L &&
                !LinuxDoRepository.isRateLimitedNow() &&
                !LinuxDoRepository.isChallengeCooldownActive() &&
                !NetworkRecoveryPolicy.verifying &&
                !do_.linux.lindo.core.network.NetworkPolicyEngine.verifyCoordinator.isActive()
            ) {
                val diagPhase = Regex("""phase=([a-zA-Z0-9_]+)""").find(rawZero)?.groupValues?.getOrNull(1)
                if (do_.linux.lindo.core.network.NetworkPolicyEngine.timeoutBlackholeDetector.recordConnectTimeout(diagPhase)) {
                    val echActive = do_.linux.lindo.core.network.EchRoutingPolicy.snapshot.active
                    val hasInFlightFgWrite = pendingHttpMeta.values.any { !it.isBackground && !it.isRead }
                    val promotedFakeVpnToEch = !echActive &&
                        do_.linux.lindo.ech.EchGatewayManager.noteProxyTunnelUnreachable()
                    if (echActive || promotedFakeVpnToEch) {
                        // ECH 激活或假 VPN 自动升舱 ECH 时：不切 lindo_net_* Profile，等待 ECH 代理就绪并重建 apiWebView
                        if (hasInFlightFgWrite) {
                            pendingTransportRecycleOnNextUse = true
                        } else {
                            activityScope.launch {
                                if (promotedFakeVpnToEch) {
                                    do_.linux.lindo.ech.EchGatewayManager.awaitReconcileAndProxyReady()
                                }
                                rebuildApiWebView(
                                    reason = if (promotedFakeVpnToEch) "smart_auto_vpn_fallback_ech" else "ech_connect_blackhole_recycle",
                                    force = true,
                                    expectedGeneration = observedGen
                                )
                            }
                        }
                    } else if (isMultiProfileSupported()) {
                        if (hasInFlightFgWrite) {
                            pendingTransportRecycleOnNextUse = true
                        } else {
                            activityScope.launch {
                                rotateWebViewNetworkProfile(
                                    reason = "confirmed_connect_blackhole",
                                    expectedGeneration = observedGen
                                )
                            }
                        }
                    }
                }
            }
            val enriched = if (rawZero.startsWith("[BRIDGE_DIAG:")) {
                "$rawZero ${buildKotlinDiagContext()} profile=$activeWebViewProfileName"
            } else {
                "[BRIDGE_DIAG:js_status_zero] ${buildKotlinDiagContext()} profile=$activeWebViewProfileName" + (if (rawZero.isNotBlank()) " raw=$rawZero" else "")
            }
            return 0 to enriched
        }

        // 前台请求首次命中 Cloudflare 403/503 或已升级的持续 429 边缘挑战时（请求在 CDN 边缘即被拦截，从未抵达源站 Rails，故写请求重放同样幂等安全），
        // 先单飞尝试静默 `/challenge` 续期：若非交互式挑战自动通过，立即无感重放原请求；若检测需人工点击则原位交棒同一 WebView 给前台弹窗！
        if (allowSilentChallengeRetry && !isBackground && isChallengeResponse(bridgeResult) &&
            (isRead || bridgeResult?.second?.startsWith(CF_MITIGATED_CHALLENGE_PREFIX) == true)) {
            val staleCf = CookieSentinel.resolveWinningCookies(targetHost = "linux.do")["cf_clearance"]?.value
            CfClearanceAuthority.noteChallenged(staleCf)
            val cleanPath = LinDoDiagnosticLogger.sanitizeUrl(url)
                .removePrefix("https://linux.do")
                .substringBefore('?')
                .ifBlank { "/" }
            val triggerReason = (if (isRead) "foreground_read:" else "foreground_write:") + "${method.uppercase()} $cleanPath"
            val originalRead = AndroidForumRead.fromRequest(url, method, headers)
            if (silentRevalidateChallenge(
                    previousClearance = staleCf,
                    trigger = do_.linux.lindo.core.network.SilentVerifyTrigger.FOREGROUND,
                    reason = triggerReason,
                    isForegroundWrite = !isRead,
                    originalRead = originalRead
                )
            ) {
                originalRead?.response?.let { return it }
                val passedRound = do_.linux.lindo.core.network.NetworkPolicyEngine.verifyCoordinator.lastPassedRound()
                val replayResult = executeHttpViaWebViewBridge(
                    url = url,
                    method = method,
                    formParams = formParams,
                    headers = headers,
                    allowSilentChallengeRetry = false,
                    allowOriginLostRetry = allowOriginLostRetry
                )
                if (isChallengeResponse(replayResult)) {
                    val postReplayCf = CookieSentinel.resolveWinningCookies(targetHost = "linux.do")["cf_clearance"]?.value
                    CfClearanceAuthority.noteChallenged(postReplayCf)
                    do_.linux.lindo.core.network.NetworkPolicyEngine.verifyCoordinator.recordIneffectiveClearance(passedRound)
                    do_.linux.lindo.core.network.NetworkPolicyEngine.updateVerifyAttentionNeeded(true)
                }
                return replayResult
            }
        }

        return bridgeResult
    }

    private val installBinaryBridgeHelperJs = """
        if (typeof window.__lindoFetchBinary !== 'function') {
            window.__lindoActiveBinary = window.__lindoActiveBinary || {};
            window.__lindoCancelBinary = window.__lindoCancelBinary || function(id) {
                const fn = window.__lindoActiveBinary && window.__lindoActiveBinary[id];
                if (fn) {
                    delete window.__lindoActiveBinary[id];
                    try { fn(); } catch (_) {}
                }
            };
            window.__lindoFetchBinary = async function(reqId, targetUrl, forceCache, skipRetry, maxBytes) {
                let cancelled = false;
                let activeCtrl = null;
                window.__lindoActiveBinary[reqId] = function() {
                    cancelled = true;
                    if (activeCtrl) {
                        try { activeCtrl.abort(); } catch (_) {}
                    }
                };
                function reportBin(status, ct, b64) {
                    if (cancelled) return;
                    window.LinDoJsBridge.onBinaryResult(reqId, status, ct || '', b64 || '');
                }
                try {
                    let isSameOrigin = true;
                    try {
                        isSameOrigin = new URL(targetUrl, window.location.origin).origin === window.location.origin;
                    } catch (_) {}
                    const maxAttempts = skipRetry ? 1 : 2;
                    const cacheMode = forceCache ? 'force-cache' : 'default';
                    let resp = null;
                    let ct = 'image/png';
                    let buf = null;
                    for (let attempt = 0; attempt < maxAttempts; attempt++) {
                        if (cancelled) return;
                        const attemptCtrl = new AbortController();
                        activeCtrl = attemptCtrl;
                        // 超时覆盖 headers + arrayBuffer() 全过程，防止慢图卡死在响应体流式读取阶段并长期占死 Chromium 同源连接
                        const attemptTimer = setTimeout(() => {
                            try { attemptCtrl.abort(); } catch (_) {}
                        }, 8500);
                        try {
                            const credMode = isSameOrigin ? 'same-origin' : 'omit';
                            const binOpts = {
                                method: 'GET',
                                credentials: credMode,
                                cache: cacheMode,
                                referrerPolicy: 'strict-origin-when-cross-origin',
                                signal: attemptCtrl.signal
                            };
                            if (isSameOrigin && window.location.origin === 'https://linux.do') {
                                binOpts.referrer = 'https://linux.do/';
                            }
                            resp = await fetch(targetUrl, binOpts);
                            if (!resp) continue;
                            if (!resp.ok) {
                                if (resp.status !== 502 && resp.status !== 503 && resp.status !== 504) {
                                    reportBin(resp.status, '', '');
                                    return;
                                }
                                continue;
                            }
                            ct = (resp.headers.get('Content-Type') || 'image/png').toLowerCase();
                            if (ct.includes('text/html')) {
                                const htmlFailStatus = resp.status >= 400 ? resp.status : 415;
                                reportBin(htmlFailStatus, ct, '');
                                return;
                            }
                            const clHeader = parseInt(resp.headers.get('Content-Length') || '0', 10);
                            if (!isNaN(clHeader) && clHeader > maxBytes) {
                                reportBin(-2, ct, '');
                                return;
                            }
                            buf = await resp.arrayBuffer();
                            break;
                        } catch (e) {
                            buf = null;
                            if (cancelled || attempt + 1 >= maxAttempts) throw e;
                        } finally {
                            clearTimeout(attemptTimer);
                            if (activeCtrl === attemptCtrl) activeCtrl = null;
                        }
                        await new Promise(r => setTimeout(r, 200));
                    }
                    if (!resp || !resp.ok || !buf) {
                        reportBin(resp ? resp.status : 0, '', '');
                        return;
                    }
                    if (buf.byteLength > maxBytes) {
                        reportBin(-2, ct, '');
                        return;
                    }
                    const bytes = new Uint8Array(buf);
                    const chunkSize = 0x6000;
                    const parts = [];
                    for (let i = 0; i < bytes.length; i += chunkSize) {
                        parts.push(String.fromCharCode.apply(null, bytes.subarray(i, i + chunkSize)));
                    }
                    const b64 = btoa(parts.join(''));
                    reportBin(resp.status, ct, b64);
                } catch (err) {
                    reportBin(0, '', '');
                } finally {
                    if (window.__lindoActiveBinary) {
                        delete window.__lindoActiveBinary[reqId];
                    }
                }
            };
        }
    """.trimIndent()

    private suspend fun fetchBinaryViaWebViewBridge(
        url: String
    ): UserApiKeyAuthManager.BinaryHttpPayload? {
        if (LinuxDoRepository.shouldPauseForSiteVerification(url) || LinuxDoRepository.isRateLimitedNow()) {
            return null
        }
        if (pendingTransportRecycleOnNextUse && !isFinishing && !isDestroyed) {
            val recycleGen = apiWebViewGeneration
            do_.linux.lindo.ech.EchGatewayManager.awaitReconcileAndProxyReady()
            rebuildApiWebView(reason = "pending_network_recycle", force = true, expectedGeneration = recycleGen)
        }
        if (apiWebView == null && !isFinishing && !isDestroyed) {
            withContext(Dispatchers.Main) {
                if (apiWebView == null && !isFinishing && !isDestroyed) {
                    initPersistentApiWebView()
                }
            }
        }
        if (apiWebView == null) return null
        val preBinWaitGen = apiWebViewGeneration
        val isReady = withTimeoutOrNull(8_000L) { apiWebViewReady.await() }
        if (isReady != true || !apiWebViewOriginHealthy) {
            if (!UserPresenceService.isForeground) return null
            val recovered = !isFinishing && !isDestroyed &&
                rebuildApiWebView(
                    reason = if (isReady == false) "bootstrap_origin_error" else "ready_await_timeout",
                    force = isReady == false,
                    expectedGeneration = preBinWaitGen
                ) &&
                apiWebViewOriginHealthy
            if (!recovered) return null
        }

        val maxBridgeBytes = 15 * 1024 * 1024
        val lowerUrl = url.lowercase()
        val forceCache = "/user_avatar/" in lowerUrl ||
            "/letter_avatar_proxy/" in lowerUrl ||
            "/images/emoji/" in lowerUrl ||
            "/svg-sprite/" in lowerUrl
        val skipRetry = "/letter_avatar_proxy/" in lowerUrl ||
            "/images/emoji/" in lowerUrl ||
            "/svg-sprite/" in lowerUrl

        suspend fun invokeOnce(injectHelperFirst: Boolean): Triple<Int, String, String>? {
            val reqId = UUID.randomUUID().toString()
            val deferred = CompletableDeferred<Triple<Int, String, String>>()
            val quotedReq = JSONObject.quote(reqId)
            val quotedUrl = JSONObject.quote(url)
            val callExpr = "if (typeof window.__lindoFetchBinary === 'function') { window.__lindoFetchBinary($quotedReq, $quotedUrl, $forceCache, $skipRetry, $maxBridgeBytes); } else { window.LinDoJsBridge.onBinaryResult($quotedReq, -3, '', ''); }"
            pendingBinaryCallbacks[reqId] = deferred
            var done = false
            var dispatchedWv: WebView? = null
            return try {
                if (!apiWebViewReady.isCompleted) {
                    val ready = withTimeoutOrNull(4500L) { apiWebViewReady.await() } == true
                    if (!ready) return null
                }
                apiWebViewNavBarrier?.let { if (!it.await()) return null }
                val liveWv = withContext(Dispatchers.Main) {
                    val wv = apiWebView ?: return@withContext null
                    dispatchedWv = wv
                    attachApiWebViewInTinyHost(wv)
                    if (injectHelperFirst) {
                        wv.evaluateJavascript(installBinaryBridgeHelperJs, null)
                    }
                    wv.evaluateJavascript(callExpr, null)
                    wv
                }
                if (liveWv == null) return null
                val out = withTimeoutOrNull(12_500L) {
                    deferred.await()
                }
                if (out != null) done = true
                out
            } finally {
                pendingBinaryCallbacks.remove(reqId)
                val targetWv = dispatchedWv
                if (!done && targetWv != null) {
                    runOnUiThread {
                        if (targetWv === apiWebView) {
                            runCatching {
                                targetWv.evaluateJavascript("if(window.__lindoCancelBinary)window.__lindoCancelBinary($quotedReq);", null)
                            }
                        }
                    }
                }
            }
        }

        var fgYieldMs = 0L
        while (fgYieldMs < 240L && LinuxDoRepository.hasInFlightForegroundApi()) {
            delay(80L)
            fgYieldMs += 80L
        }
        val mediaLaneSemaphore = when {
            "/images/emoji/" in lowerUrl || "/svg-sprite/" in lowerUrl || "twemoji" in lowerUrl || "noto" in lowerUrl -> webViewEmojiSemaphore
            "/user_avatar/" in lowerUrl || "/letter_avatar" in lowerUrl -> webViewAvatarSemaphore
            else -> webViewMediaSemaphore
        }
        mediaLaneSemaphore.acquire()
        val rawTriple = try {
            val mediaPaceDelayMs = do_.linux.lindo.core.network.NetworkPolicyEngine.mediaTokenBucket.reserveDelayMs()
            if (mediaPaceDelayMs > 0L) delay(mediaPaceDelayMs)
            val first = invokeOnce(injectHelperFirst = false)
            if (first != null && first.first == -3) {
                invokeOnce(injectHelperFirst = true)
            } else {
                first
            }
        } finally {
            mediaLaneSemaphore.release()
        } ?: return null

        val (status, contentType, b64Body) = rawTriple
        if (status in 200..299) {
            do_.linux.lindo.core.network.NetworkPolicyEngine.mediaTokenBucket.onSuccess2xx()
        } else if (status == 429) {
            do_.linux.lindo.core.network.NetworkPolicyEngine.mediaTokenBucket.onTrue429()
        }
        if (status <= 0) return null
        if (status !in 200..299) {
            return UserApiKeyAuthManager.BinaryHttpPayload(status, contentType, ByteArray(0))
        }
        if (b64Body.isEmpty()) return null
        return withContext(Dispatchers.Default) {
            try {
                val decoded = android.util.Base64.decode(b64Body, android.util.Base64.DEFAULT)
                if (decoded.isEmpty()) null else UserApiKeyAuthManager.BinaryHttpPayload(status, contentType, decoded)
            } catch (_: Throwable) {
                null
            }
        }
    }

    fun isChallengeResponse(candidate: Pair<Int, String>?): Boolean {
        if (candidate == null) return false
        val escalated429 = candidate.first == 429 && candidate.second.startsWith(CF_MITIGATED_CHALLENGE_PREFIX)
        return (candidate.first in setOf(403, 503) || escalated429) &&
            do_.linux.lindo.core.network.looksLikeCloudflareChallenge(candidate.second)
    }

    private suspend fun executeMultipartUploadViaWebViewBridge(
        fileName: String,
        mimeType: String,
        base64Bytes: String
    ): Pair<Int, String>? {
        val rawBytes = withContext(Dispatchers.Default) {
            runCatching { android.util.Base64.decode(base64Bytes, android.util.Base64.DEFAULT) }.getOrNull()
        } ?: return null
        return executeMultipartUploadViaWebViewBridge(fileName, mimeType, rawBytes)
    }

    private suspend fun executeMultipartUploadViaWebViewBridge(
        fileName: String,
        mimeType: String,
        rawBytes: ByteArray
    ): Pair<Int, String>? {
        val first = uploadOnce(fileName, mimeType, rawBytes)
        if (!isChallengeResponse(first) || first?.second?.startsWith(CF_MITIGATED_CHALLENGE_PREFIX) != true ||
            NetworkRecoveryPolicy.verifying) return first
        val staleCf = CookieSentinel.resolveWinningCookies(targetHost = "linux.do")["cf_clearance"]?.value
        CfClearanceAuthority.noteChallenged(staleCf)
        val passed = silentRevalidateChallenge(
            previousClearance = staleCf,
            trigger = do_.linux.lindo.core.network.SilentVerifyTrigger.FOREGROUND,
            reason = "foreground_write:POST /uploads.json",
            isForegroundWrite = true
        )
        if (!passed) return first
        val coordinator = do_.linux.lindo.core.network.NetworkPolicyEngine.verifyCoordinator
        val round = coordinator.lastPassedRound()
        val replay = uploadOnce(fileName, mimeType, rawBytes)
        if (isChallengeResponse(replay)) {
            coordinator.recordIneffectiveClearance(round)
            do_.linux.lindo.core.network.NetworkPolicyEngine.updateVerifyAttentionNeeded(true)
        }
        return replay
    }

    private suspend fun uploadOnce(fileName: String, mimeType: String, rawBytes: ByteArray): Pair<Int, String>? {
        if (NetworkRecoveryPolicy.verifying && LinuxDoRepository.shouldPauseForSiteVerification("https://linux.do/uploads.json")) {
            return 403 to "${CF_MITIGATED_CHALLENGE_PREFIX}<html>需要完成站点验证</html>"
        }
        if (LinuxDoRepository.isRateLimitedNow()) return 429 to """{"errors":["请求较频繁，请稍后再试"]}"""
        if (pendingTransportRecycleOnNextUse && !isFinishing && !isDestroyed) {
            val recycleGen = apiWebViewGeneration
            do_.linux.lindo.ech.EchGatewayManager.awaitReconcileAndProxyReady()
            rebuildApiWebView(reason = "pending_network_recycle", force = true, expectedGeneration = recycleGen)
        }
        if (apiWebView == null && !isFinishing && !isDestroyed) {
            withContext(Dispatchers.Main) {
                if (apiWebView == null && !isFinishing && !isDestroyed) {
                    initPersistentApiWebView()
                }
            }
        }
        if (apiWebView == null) return null
        val preWaitGen = apiWebViewGeneration
        val isReady = withTimeoutOrNull(8_000L) { apiWebViewReady.await() }
        if (isReady != true || !apiWebViewOriginHealthy) {
            val recovered = UserPresenceService.isForeground &&
                !isFinishing &&
                !isDestroyed &&
                rebuildApiWebView(
                    reason = if (isReady == false) "bootstrap_origin_error" else "ready_await_timeout",
                    force = isReady == false,
                    expectedGeneration = preWaitGen
                ) &&
                apiWebViewOriginHealthy
            if (!recovered) {
                return null
            }
        }

        val reqId = UUID.randomUUID().toString()
        val reqIdQuoted = JSONObject.quote(reqId)
        val deferred = CompletableDeferred<Pair<Int, String>>()

        webViewForegroundHttpSemaphore.acquire()
        var completedFromJs = false
        var dispatchedWv: WebView? = null
        try {
            if (LinuxDoRepository.isRateLimitedNow()) return 429 to """{"errors":["请求较频繁，请稍后再试"]}"""
            pendingUploadPayloads[reqId] = rawBytes
            pendingHttpCallbacks[reqId] = deferred
            if (apiWebView == null && !isFinishing && !isDestroyed) {
                withContext(Dispatchers.Main) {
                    if (apiWebView == null && !isFinishing && !isDestroyed) {
                        initPersistentApiWebView()
                    }
                }
            }
            if (!apiWebViewReady.isCompleted || !apiWebViewOriginHealthy) {
                val postAcquireGen = apiWebViewGeneration
                val readyAfterAcquire = withTimeoutOrNull(8_000L) { apiWebViewReady.await() }
                if (readyAfterAcquire != true || !apiWebViewOriginHealthy) {
                    val recovered = UserPresenceService.isForeground &&
                        !isFinishing &&
                        !isDestroyed &&
                        rebuildApiWebView(
                            reason = if (readyAfterAcquire == false) "bootstrap_origin_error" else "ready_await_timeout",
                            force = readyAfterAcquire == false,
                            expectedGeneration = postAcquireGen
                        ) &&
                        apiWebViewOriginHealthy
                    if (!recovered) {
                        return null
                    }
                }
            }

            apiWebViewNavBarrier?.let { if (!it.await()) return null }
            val launched = withContext(Dispatchers.Main) {
                val liveWv = apiWebView ?: return@withContext false
                dispatchedWv = liveWv
                attachApiWebViewInTinyHost(liveWv)
                if (UserPresenceService.isForeground) {
                    liveWv.onResume()
                    liveWv.resumeTimers()
                }
                val cm = MainActivity.currentWebViewCookieManager()
                val cookieStr = ensureAuthCookiesInWebKit(cm)
                if (cookieStr.isNotBlank()) {
                    CookieSentinel.syncFromAuthoritativeBrowserCookieString(cookieStr)
                }
                val initialCachedCsrf = LinuxDoRepository.getCachedCsrfToken().orEmpty()
                val currentCsrfOwner = csrfOwnerTag(cookieStr)
                val hasBrowserSessionCookie = hasCookieNamed(cookieStr, "_t")

                val jsCode = """
                    (async function() {
                        window.__lindoActiveHttp = window.__lindoActiveHttp || {};
                        window.__lindoCancelHttp = window.__lindoCancelHttp || function(id) {
                            const cancelFn = window.__lindoActiveHttp && window.__lindoActiveHttp[id];
                            if (cancelFn) {
                                delete window.__lindoActiveHttp[id];
                                try { cancelFn(); } catch (_) {}
                            }
                        };
                        const reqKey = $reqIdQuoted;
                        let cancelledByKotlin = false;
                        const controller = new AbortController();
                        window.__lindoActiveHttp[reqKey] = function() {
                            cancelledByKotlin = true;
                            try { controller.abort(); } catch (_) {}
                        };
                        const timer = setTimeout(() => {
                            try { controller.abort(); } catch (_) {}
                        }, 90000);
                        let csrfLastStatus = 0;
                        let csrfLastBody = '';
                        let csrfLastMitigated = '';
                        let csrfLastRetryAfter = '';
                        function reportUpload(status, body, cfMitigated, retryAfter) {
                            if (cancelledByKotlin) return;
                            if (window.LinDoJsBridge && window.LinDoJsBridge.onHttpResultWithRetryAfter) {
                                window.LinDoJsBridge.onHttpResultWithRetryAfter(reqKey, status, body || '', cfMitigated || '', retryAfter || '');
                            } else if (window.LinDoJsBridge && window.LinDoJsBridge.onHttpResultWithMeta) {
                                window.LinDoJsBridge.onHttpResultWithMeta(reqKey, status, body || '', cfMitigated || '');
                            } else if (window.LinDoJsBridge) {
                                window.LinDoJsBridge.onHttpResult(reqKey, status, body || '');
                            }
                        }
                        async function refreshCsrfToken(forceFresh) {
                            if (!forceFresh) {
                                const metaCsrf = document.querySelector('meta[name="csrf-token"]');
                                const cached = window.__lindoCsrf || (metaCsrf && metaCsrf.content);
                                if (cached) return cached;
                            }
                            try {
                                const csrfOpts = {
                                    headers: { 'Accept': 'application/json, text/javascript, */*; q=0.01', 'X-Requested-With': 'XMLHttpRequest' },
                                    credentials: 'include',
                                    cache: 'no-cache',
                                    referrerPolicy: 'strict-origin-when-cross-origin',
                                    signal: controller.signal
                                };
                                if (window.location.origin === 'https://linux.do') {
                                    csrfOpts.referrer = 'https://linux.do/';
                                }
                                const csrfResp = await fetch('https://linux.do/session/csrf.json', csrfOpts);
                                csrfLastStatus = csrfResp.status || 0;
                                csrfLastMitigated = csrfResp.headers.get('cf-mitigated') || '';
                                csrfLastRetryAfter = csrfResp.headers.get('Retry-After') || '';
                                if (csrfResp.ok && !csrfLastMitigated) {
                                    const csrfData = await csrfResp.json();
                                    if (csrfData && csrfData.csrf) {
                                        window.__lindoCsrf = csrfData.csrf;
                                        if (window.LinDoJsBridge && window.LinDoJsBridge.onCsrfTokenUpdated) {
                                            window.LinDoJsBridge.onCsrfTokenUpdated(csrfData.csrf);
                                        }
                                        return csrfData.csrf;
                                    }
                                } else {
                                    csrfLastBody = await csrfResp.text();
                                }
                            } catch (_) {}
                            return '';
                        }
                        try {
                            const hdrs = {
                                'Accept': 'application/json, text/javascript, */*; q=0.01',
                                'X-Requested-With': 'XMLHttpRequest',
                                'Discourse-Present': 'true'
                            };
                            if ($hasBrowserSessionCookie) {
                                hdrs['Discourse-Logged-In'] = 'true';
                            }
                            const currentOwner = ${JSONObject.quote(currentCsrfOwner)};
                            let ownerChanged = false;
                            if (window.__lindoCsrfCookieOwner !== currentOwner) {
                                ownerChanged = true;
                                window.__lindoCsrf = null;
                                window.__lindoCsrfPromise = null;
                                window.__lindoCsrfCookieOwner = currentOwner;
                            }
                            const kotlinCsrf = ${JSONObject.quote(initialCachedCsrf)};
                            if (!kotlinCsrf) {
                                window.__lindoCsrf = null;
                            } else if (!ownerChanged) {
                                window.__lindoCsrf = kotlinCsrf;
                            }
                            const csrf = await refreshCsrfToken(false);
                            if (!csrf) {
                                reportUpload(csrfLastStatus || 0, csrfLastBody, csrfLastMitigated, csrfLastRetryAfter);
                                return;
                            }
                            hdrs['X-CSRF-Token'] = csrf;
                            const totalLen = (window.LinDoJsBridge && window.LinDoJsBridge.getUploadByteLength)
                                ? window.LinDoJsBridge.getUploadByteLength(reqKey)
                                : -1;
                            if (totalLen < 0) {
                                reportUpload(0, '', '');
                                return;
                            }
                            const chunkBytes = 196608;
                            const chunks = [];
                            for (let offset = 0; offset < totalLen; offset += chunkBytes) {
                                if (cancelledByKotlin) return;
                                const chunkB64 = window.LinDoJsBridge.readUploadChunkBase64(reqKey, offset, chunkBytes);
                                if (!chunkB64) {
                                    reportUpload(0, '', '');
                                    return;
                                }
                                const binStr = atob(chunkB64);
                                const len = binStr.length;
                                const part = new Uint8Array(len);
                                for (let i = 0; i < len; i++) {
                                    part[i] = binStr.charCodeAt(i);
                                }
                                chunks.push(part);
                            }
                            const blob = new Blob(chunks, { type: ${JSONObject.quote(mimeType)} });
                            function buildFormData() {
                                const fd = new FormData();
                                fd.append('type', 'composer');
                                fd.append('synchronous', 'true');
                                fd.append('files[]', blob, ${JSONObject.quote(fileName)});
                                return fd;
                            }
                            function buildUploadOpts() {
                                const uo = {
                                    method: 'POST',
                                    headers: hdrs,
                                    body: buildFormData(),
                                    credentials: 'include',
                                    referrerPolicy: 'strict-origin-when-cross-origin',
                                    signal: controller.signal
                                };
                                if (window.location.origin === 'https://linux.do') {
                                    uo.referrer = 'https://linux.do/';
                                }
                                return uo;
                            }
                            let resp = await fetch('https://linux.do/uploads.json', buildUploadOpts());
                            let text = await resp.text();
                            let cfMitigated = (resp && resp.headers && resp.headers.get('cf-mitigated')) || '';
                            if (!cfMitigated && (resp.status === 403 || resp.status === 422) && /csrf|invalid authenticity token|bad csrf/i.test(text)) {
                                window.__lindoCsrf = null;
                                if (window.LinDoJsBridge && window.LinDoJsBridge.onCsrfTokenUpdated) {
                                    window.LinDoJsBridge.onCsrfTokenUpdated('');
                                }
                                const freshCsrf = await refreshCsrfToken(true);
                                if (freshCsrf) {
                                    hdrs['X-CSRF-Token'] = freshCsrf;
                                    resp = await fetch('https://linux.do/uploads.json', buildUploadOpts());
                                    text = await resp.text();
                                    cfMitigated = (resp && resp.headers && resp.headers.get('cf-mitigated')) || '';
                                } else if (csrfLastMitigated || csrfLastStatus === 403 || csrfLastStatus === 503 || csrfLastStatus === 429) {
                                    reportUpload(csrfLastStatus || resp.status, csrfLastBody || text, csrfLastMitigated, csrfLastRetryAfter);
                                    return;
                                }
                            }
                            reportUpload(resp.status, text, cfMitigated, resp.headers.get('Retry-After'));
                        } catch (err) {
                            reportUpload(0, '', '');
                        } finally {
                            clearTimeout(timer);
                            if (window.__lindoActiveHttp) {
                                delete window.__lindoActiveHttp[reqKey];
                            }
                        }
                    })();
                """.trimIndent()

                liveWv.evaluateJavascript(jsCode, null)
                true
            }
            if (!launched) return null

            val response = withTimeoutOrNull(92_000L) {
                deferred.await()
            }
            if (response != null) {
                completedFromJs = true
            }
            val cm = runCatching { MainActivity.currentWebViewCookieManager() }.getOrNull()
            val latestCookies = runCatching { cm?.getCookie("https://linux.do") }.getOrNull().orEmpty()
            if (latestCookies.isNotBlank()) {
                CookieSentinel.syncFromAuthoritativeBrowserCookieString(latestCookies)
                lastObservedCookieSnapshot = latestCookies
                lastCookieFlushEpochMs = System.currentTimeMillis()
            }
            runCatching { cm?.flush() }
            if (response?.first == REBUILD_ABORTED_STATUS) return null
            return response
        } finally {
            pendingUploadPayloads.remove(reqId)
            pendingHttpCallbacks.remove(reqId)
            val targetWv = dispatchedWv
            if (!completedFromJs && targetWv != null) {
                runOnUiThread {
                    if (targetWv === apiWebView) {
                        runCatching {
                            targetWv.evaluateJavascript(
                                "if(window.__lindoCancelHttp)window.__lindoCancelHttp($reqIdQuoted);",
                                null
                            )
                        }
                    }
                }
            }
            webViewForegroundHttpSemaphore.release()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        debugPreviewGeneration++
        setIntent(intent)
        handleIncomingAuthIntent(intent)
    }

    private fun handleIncomingAuthIntent(intent: Intent?) {
        val dataUri = intent?.data?.toString() ?: return
        if (dataUri.startsWith("discourse://auth_redirect") ||
            dataUri.startsWith("lindo://auth_redirect")
        ) {
            println("LinDoAuth: handleIncomingAuthIntent received auth_redirect URI")
            val handled = UserApiKeyAuthManager.handleAuthRedirectUri(dataUri)
            // 清空 intent.data 防止旋转或重建重复消费一次性 OTP
            intent.data = null
            if (handled) {
                persistSessionToDisk()
            }
        } else if (QrLoginManager.isQrLoginUri(dataUri)) {
            val accepted = QrLoginManager.acceptScannedQrPayload(dataUri)
            intent.data = null
            if (accepted.isSuccess) {
                persistSessionToDisk()
            } else {
                LinDoDiagnosticLogger.warn(
                    DiagnosticCategory.AUTH,
                    "QrLogin",
                    "Rejected incoming QR deep link: ${accepted.exceptionOrNull()?.message ?: "invalid"}"
                )
            }
        }
    }

    private suspend fun replaceAccountCookies(raw: String) = withContext(Dispatchers.Main) {
        passedWebSession = null
        PlatformSessionRecovery.forumConnection.reset()
        val cm = MainActivity.currentWebViewCookieManager()
        nukeAuthCookieVariantsInWebKit(cm)
        suspend fun set(origin: String, value: String) = suspendCancellableCoroutine<Unit> { continuation ->
            cm.setCookie(origin, value) { if (continuation.isActive) continuation.resume(Unit) }
        }
        for (host in listOf("linux.do", "connect.linux.do", "credit.linux.do", "cdk.linux.do", "hub.linux.do", "lottery.linux.do")) {
            val origin = "https://$host"
            val names = cm.getCookie(origin).orEmpty().split(';').map { it.trim().substringBefore('=') }.toSet() + setOf("_t", "_forum_session")
            for (name in names.filter { it.isNotBlank() && it !in CookieSentinel.cfTrustCookieNames }) {
                set(origin, "$name=; Max-Age=0; Path=/; Secure; HttpOnly")
                set(origin, "$name=; Max-Age=0; Domain=$host; Path=/; Secure; HttpOnly")
                set(origin, "$name=; Max-Age=0; Domain=.$host; Path=/; Secure; HttpOnly")
            }
        }
        raw.split(';').map { it.trim() }.filter { it.substringBefore('=') in setOf("_t", "_forum_session") }.forEach {
            val attr = if (it.startsWith("_t=")) {
                "Path=/; Max-Age=5184000; Secure; HttpOnly; SameSite=Lax"
            } else {
                "Path=/; Secure; HttpOnly; SameSite=Lax"
            }
            set("https://linux.do", "$it; $attr")
        }
        cm.flush()
        val expected = getCookieValue(raw, "_t").orEmpty()
        check(getCookieValue(cm.getCookie("https://linux.do"), "_t").orEmpty() == expected) { "Account cookie replacement failed" }
        check(rebuildApiWebView(reason = "account_changed", force = true)) { "Account transport did not become ready" }
    }

    private fun restoreSessionFromDisk() {
        runCatching {
            // 始终使用与系统 WebView 100% 原生一致的 User-Agent（不删改 "; wv"），
            // 确保与底层 Sec-CH-UA Client Hints 及 cf_clearance 签名完全吻合；
            // 若系统 Android System WebView 发生升级导致 UA 变化，立即异步清理绑定旧 UA 的失效 cf_clearance。
            val nativeUa = WebSettings.getDefaultUserAgent(this)
            val previousPersistedUa = prefs.getString("user_agent", null)
            UserApiKeyAuthManager.customUserAgent = nativeUa
            val uaUpgraded = !previousPersistedUa.isNullOrBlank() && previousPersistedUa != nativeUa
            if (uaUpgraded) {
                prefs.edit().putString("user_agent", nativeUa).apply()
            }

            val savedClientId = prefs.getString("client_id", null)
            if (!savedClientId.isNullOrBlank()) {
                UserApiKeyAuthManager.restoreClientId(savedClientId)
            } else {
                prefs.edit().putString("client_id", UserApiKeyAuthManager.getClientId()).apply()
            }
            val savedQrClientId = prefs.getString("qr_client_id", null)
            if (!savedQrClientId.isNullOrBlank()) {
                UserApiKeyAuthManager.restoreQrClientId(savedQrClientId)
            } else {
                prefs.edit().putString("qr_client_id", UserApiKeyAuthManager.getOrCreateQrClientId()).apply()
            }

            // 恢复跳转到系统浏览器前生成的 RSA 私钥与 nonce
            val savedPendingPrivKey = prefs.getString("pending_rsa_priv_b64", null)
            val savedPendingNonce = prefs.getString("pending_rsa_nonce", null)
            UserApiKeyAuthManager.restorePendingPrivateKeyBase64(savedPendingPrivKey, savedPendingNonce, prefs.getLong("pending_rsa_created_at", 0L))

            // 恢复用户的阅读密度/字号、深色模式、动效与主题色偏好（默认官方黑白黄，标准字号默认 0.92f）
            UserApiKeyAuthManager.uiFontScale = AppAppearance.restoreFontScale(
                if (prefs.contains("ui_font_scale_v4")) prefs.getFloat("ui_font_scale_v4", 1f) else null,
                if (prefs.contains("ui_font_scale_v3")) prefs.getFloat("ui_font_scale_v3", 1f) else null,
                prefs.getFloat("ui_font_scale_v2", 0.92f)
            )
            UserApiKeyAuthManager.uiDarkTheme = prefs.getBoolean("ui_dark_theme", false)
            UserApiKeyAuthManager.uiEnableAnimations = prefs.getBoolean("ui_enable_animations", true)
            UserApiKeyAuthManager.uiThemeAccent = prefs.getString("ui_theme_accent", "WARM_ORANGE") ?: "WARM_ORANGE"
            UserApiKeyAuthManager.uiCompactFeed = prefs.getBoolean("ui_compact_feed", true)
            UserApiKeyAuthManager.uiHomeSearchExpanded = prefs.getBoolean("ui_home_search_expanded", true)
            // 移动端默认对半分栏：分隔线与折叠屏铰链对齐，仍可拖拽调整
            UserApiKeyAuthManager.uiSplitFraction = prefs.getFloat("ui_split_fraction", 0.5f).coerceIn(0.28f, 0.62f)
            UserApiKeyAuthManager.uiWideSplitEnabled = prefs.getBoolean("ui_wide_split", true)
            LinuxDoRepository.restoreReadTopics(prefs.getString("read_topic_posts", null))

            val savedApiKey = prefs.getString("api_key", null)
            val username = prefs.getString("username", null)
            val displayName = prefs.getString("display_name", null)
            val avatarTemplate = prefs.getString("avatar_template", null)
            val trustLevel = prefs.getInt("trust_level", 1)
            val userId = prefs.getInt("user_id", 0)
            val userTitle = prefs.getString("user_title", null)

            // 彻底过滤掉旧版假占位符 linuxdo_user
            val userProfile = if (!username.isNullOrBlank() && username != "linuxdo_user") {
                AuthenticatedUserProfile(
                    userId = userId,
                    username = username,
                    displayName = displayName ?: username,
                    avatarTemplate = avatarTemplate.orEmpty(),
                    animatedAvatar = prefs.getString("animated_avatar", null),
                    userTitle = userTitle,
                    canUseTemplates = if (prefs.contains("can_use_templates")) prefs.getBoolean("can_use_templates", false) else null,
                    trustLevel = trustLevel
                )
            } else {
                null
            }

            val cookieManager = MainActivity.currentWebViewCookieManager()
            cookieManager.setAcceptCookie(true)
            if (uaUpgraded) {
                LinDoDiagnosticLogger.info(
                    DiagnosticCategory.AUTH,
                    "WebViewUaUpgrade",
                    "System WebView UA updated; purging stale UA-bound CF cookies"
                )
                activityScope.launch {
                    runCatching { purgeCfCookiesInWebKit(cookieManager) }
                }
            }
            // 始终以原生 Chromium WebKit SQLite CookieJar 为权威源（原生保留真实过期时间与 SameSite/HttpOnly 属性）；
            // 严禁从 SharedPreferences 反写 cf_clearance / __cf_bm / _cfuvid（会丢失原始属性或注入已过期挑战票据），
            // 仅当存在已保存有效 `_t` 会话且 WebKit 意外丢失 `_t` 时才回填 host-only 会话 Cookie。
            val webKitCookies = cookieManager.getCookie("https://linux.do").orEmpty()
            val savedCookies = prefs.getString("raw_cookies", "").orEmpty()
            // Seed the account in memory even when Chromium currently returns only analytics/CF cookies.
            // The request bridge awaits CookieManager.setCookie before sending the first request.
            val authoritativeCookies = do_.linux.lindo.core.network.sessionCookiesForRestore(webKitCookies, savedCookies)

            UserApiKeyAuthManager.restorePersistedSession(
                profile = userProfile,
                apiKey = savedApiKey,
                rawCookies = authoritativeCookies
            )
            if (UserApiKeyAuthManager.isAuthorized()) {
                prefs.getInt("unread_notifications", 0)
                    .takeIf { it > 0 }
                    ?.let { UserApiKeyAuthManager.updateCachedUnreadNotifications(it) }
            }
        }
    }

    private suspend fun nukeAuthCookieVariantsInWebKit(cookieManager: CookieManager) =
        withContext(Dispatchers.Main) {
            suspend fun expire(origin: String, cookieStr: String) = withTimeoutOrNull(3_000L) {
                suspendCancellableCoroutine<Unit> { cont ->
                    cookieManager.setCookie(origin, cookieStr) {
                        if (cont.isActive) cont.resume(Unit)
                    }
                }
            }
            val authNames = listOf("_t", "_forum_session", "authentication_data")
            for (origin in listOf("https://linux.do", "https://www.linux.do")) {
                for (name in authNames) {
                    expire(origin, "$name=; Max-Age=0; Path=/; Secure; HttpOnly")
                    expire(origin, "$name=; Max-Age=0; Path=/; Secure")
                    expire(origin, "$name=; Max-Age=0; Domain=linux.do; Path=/; Secure; HttpOnly")
                    expire(origin, "$name=; Max-Age=0; Domain=.linux.do; Path=/; Secure; HttpOnly")
                    expire(origin, "$name=; Max-Age=0; Domain=.linux.do; Path=/; Secure")
                }
            }
            cookieManager.flush()
        }

    private suspend fun ensureAuthCookiesInWebKit(cookieManager: CookieManager): String =
        withContext(cookieIoDispatcher) {
            // 始终在专用 LindoCookieIo HandlerThread 上操作 CookieManager（零主线程 Binder/SQLite 竞争），
            // 并保持 Chromium CookieManager 原生管理 cf_clearance / __cf_bm / _cfuvid（保留真实 Partitioned / Expires 属性），严禁手动删改或合成重插。
            suspend fun setCanonicalHostCookie(value: String) = kotlinx.coroutines.withTimeout(5_000L) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    cookieManager.setCookie("https://linux.do", value) { accepted ->
                        if (continuation.isActive) {
                            if (accepted) continuation.resume(Unit)
                            else continuation.resumeWith(Result.failure(IllegalStateException("Session cookie restore failed")))
                        }
                    }
                }
            }
            val latestCookies = cookieManager.getCookie("https://linux.do").orEmpty()
            val tValues = getAllCookieValues(latestCookies, "_t").filter { it != "demo_session_token" }

            // 1. 未登录 / 已过期状态下若 Chromium CookieManager 仍残留失效 _t，立即彻底清除，杜绝回流至 CookieSentinel 造成无限「登录已过期」
            if (!UserApiKeyAuthManager.isAuthorized()) {
                if (tValues.isNotEmpty()) {
                    nukeAuthCookieVariantsInWebKit(cookieManager)
                    return@withContext cookieManager.getCookie("https://linux.do").orEmpty()
                }
                return@withContext latestCookies
            }

            // 2. 正常单条 _t 快路径（零额外写开销）
            if (tValues.size == 1) {
                return@withContext latestCookies
            }

            // 3. 检测到多条重复 _t（如旧 Domain=.linux.do 与新 Host-Only linux.do 并存）：清除全部变体并仅重写入唯一胜出的 Host-Only _t！
            if (tValues.size > 1) {
                LinDoDiagnosticLogger.warn(
                    DiagnosticCategory.AUTH,
                    "CookieSweep",
                    "Collapsed ${tValues.size} duplicate _t variants in Chromium CookieManager"
                )
                val winning = CookieSentinel.resolveWinningCookies(targetHost = "linux.do")
                val winnerT = winning["_t"]?.value?.takeIf { it in tValues } ?: tValues.last()
                val fsValues = getAllCookieValues(latestCookies, "_forum_session")
                val winnerFs = winning["_forum_session"]?.value?.takeIf { it in fsValues } ?: fsValues.lastOrNull()
                nukeAuthCookieVariantsInWebKit(cookieManager)
                setCanonicalHostCookie("_t=$winnerT; Path=/; Max-Age=5184000; Secure; HttpOnly; SameSite=Lax")
                if (!winnerFs.isNullOrBlank()) {
                    setCanonicalHostCookie("_forum_session=$winnerFs; Path=/; Secure; HttpOnly; SameSite=Lax")
                }
                cookieManager.flush()
                return@withContext cookieManager.getCookie("https://linux.do").orEmpty()
            }

            // 4. 已登录但 WebKit 意外丢失 _t：从加密持久化快照恢复规范 Host-Only _t
            val winning = CookieSentinel.resolveWinningCookies(targetHost = "linux.do")
            val savedT = winning["_t"]?.value?.takeIf { it.isNotBlank() && it != "demo_session_token" }
                ?: return@withContext latestCookies
            setCanonicalHostCookie("_t=$savedT; Path=/; Max-Age=5184000; Secure; HttpOnly; SameSite=Lax")
            winning["_forum_session"]?.value?.takeIf { it.isNotBlank() }?.let { fs ->
                setCanonicalHostCookie("_forum_session=$fs; Path=/; Secure; HttpOnly; SameSite=Lax")
            }
            cookieManager.flush()
            val restored = cookieManager.getCookie("https://linux.do").orEmpty()
            check(hasCookieNamed(restored, "_t")) { "Session cookie is not ready" }
            restored
        }

    private fun persistSessionToDisk() {
        runCatching {
            val cookieManager = MainActivity.currentWebViewCookieManager()
            cookieManager.flush()
            // Persist the latest browser snapshot without rewriting persistent cookies as session-only cookies.
            val latestWebKitCookies = cookieManager.getCookie("https://linux.do").orEmpty()
            if (latestWebKitCookies.isNotBlank()) {
                CookieSentinel.syncFromAuthoritativeBrowserCookieString(latestWebKitCookies)
            }

            val editor = prefs.edit()
            val user = UserApiKeyAuthManager.currentUser?.takeIf { it.username != "linuxdo_user" }
            val apiKey = UserApiKeyAuthManager.getApiKey()
            val mergedCookieHeader = CookieSentinel.buildCookieHeader(targetHost = "linux.do")
            if (mergedCookieHeader.isNotBlank() &&
                (user != null || !apiKey.isNullOrBlank() || hasCookieNamed(mergedCookieHeader, "_t"))
            ) {
                editor.putString("raw_cookies", mergedCookieHeader)
            } else {
                editor.remove("raw_cookies")
            }
            editor.putString("api_key", apiKey)
            val pendingPrivKey = UserApiKeyAuthManager.exportPendingPrivateKeyBase64()
            editor.putString("pending_rsa_priv_b64", pendingPrivKey)
            editor.putString("client_id", UserApiKeyAuthManager.getClientId())
            editor.putString("user_agent", UserApiKeyAuthManager.customUserAgent)
            if (user != null) {
                editor.putInt("user_id", user.userId)
                editor.putString("username", user.username)
                editor.putString("display_name", user.displayName)
                editor.putString("avatar_template", user.avatarTemplate)
                editor.putString("animated_avatar", user.animatedAvatar)
                editor.putString("user_title", user.userTitle)
                if (user.canUseTemplates == null) editor.remove("can_use_templates")
                else editor.putBoolean("can_use_templates", user.canUseTemplates)
                editor.putInt("trust_level", user.trustLevel)
                editor.putInt("unread_notifications", UserApiKeyAuthManager.cachedUnreadNotificationCount)
            } else {
                editor.remove("user_id")
                editor.remove("username")
                editor.remove("display_name")
                editor.remove("avatar_template")
                editor.remove("animated_avatar")
                editor.remove("user_title")
                editor.remove("can_use_templates")
                editor.remove("trust_level")
                if (UserApiKeyAuthManager.isAuthorized()) {
                    editor.putInt("unread_notifications", UserApiKeyAuthManager.cachedUnreadNotificationCount)
                } else {
                    editor.remove("unread_notifications")
                }
            }
            check(editor.commit()) { "Could not persist account session" }
        }
    }

    private suspend fun importPrivateDebugCookies() {
        val source = java.io.File(filesDir, "lindo-session-import.json")
        if (!source.isFile) return
        try {
            val entries = withContext(Dispatchers.IO) { JSONArray(source.readText()) }
            val manager = MainActivity.currentWebViewCookieManager()
            manager.setAcceptCookie(true)
            suspend fun set(value: String) = suspendCancellableCoroutine<Unit> { continuation ->
                manager.setCookie("https://linux.do/", value) {
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
            val accepted = mutableListOf<String>()
            for (index in 0 until entries.length()) {
                val cookie = entries.getJSONObject(index)
                if (cookie.optString("domain").removePrefix(".") != "linux.do") continue
                if (cookie.has("expirationDate") && cookie.optDouble("expirationDate") * 1000 < System.currentTimeMillis()) continue
                val name = cookie.optString("name")
                val value = cookie.optString("value")
                if (name.isBlank() || value.isBlank() || name.any { it in "\r\n;=" } || value.any { it in "\r\n;" }) continue
                set("$name=; Max-Age=0; Path=/; Secure")
                set("$name=; Max-Age=0; Domain=.linux.do; Path=/; Secure")
                val domain = if (cookie.optBoolean("hostOnly", true)) "" else "; Domain=.linux.do"
                val httpOnly = if (cookie.optBoolean("httpOnly")) "; HttpOnly" else ""
                set("$name=$value; Path=/${domain}; Secure$httpOnly")
                accepted += "$name=$value"
            }
            manager.flush()
            CookieSentinel.clear()
            CookieSentinel.ingestRawCookieString(accepted.joinToString("; "))
            prefs.edit().putString("raw_cookies", accepted.joinToString("; ")).apply()
            rebuildApiWebView(reason = "debug_cookie_import", force = true)
            withContext(Dispatchers.IO) { LinuxDoRepository.fetchCurrentUserProfile() }
            persistSessionToDisk()
            UserApiKeyAuthManager.onAuthStateChanged?.invoke()
        } finally {
            withContext(Dispatchers.IO) { source.delete() }
        }
    }

    private fun computeTransportSignature(
        cm: ConnectivityManager,
        network: Network?,
        caps: NetworkCapabilities?
    ): String {
        if (network == null || caps == null) return ""
        val transports = buildList {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cell")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("eth")
        }.joinToString(",")
        val lp = runCatching { cm.getLinkProperties(network) }.getOrNull()
        val iface = lp?.interfaceName.orEmpty()
        val dns = lp?.dnsServers?.joinToString(",") { it.hostAddress.orEmpty() }.orEmpty()
        return "$transports|$iface|$dns"
    }

    internal suspend fun purgeCfCookiesInWebKit(cookieManager: CookieManager) =
        withContext(cookieIoDispatcher) {
            val cfCookieNames = do_.linux.lindo.core.network.ManualForceRefreshPolicy.CF_PURGEABLE_COOKIE_NAMES
            val jobs = ArrayList<Pair<String, String>>(160)
            for (origin in listOf("https://linux.do", "https://www.linux.do")) {
                for (name in cfCookieNames) {
                    jobs += origin to "$name=; Max-Age=0; Path=/; Secure; HttpOnly"
                    jobs += origin to "$name=; Max-Age=0; Path=/; Secure"
                    jobs += origin to "$name=; Max-Age=0; Path=/; Secure; HttpOnly; SameSite=None; Partitioned"
                    jobs += origin to "$name=; Max-Age=0; Path=/; Secure; SameSite=None; Partitioned"
                    jobs += origin to "$name=; Max-Age=0; Domain=linux.do; Path=/; Secure; HttpOnly"
                    jobs += origin to "$name=; Max-Age=0; Domain=.linux.do; Path=/; Secure; HttpOnly"
                    jobs += origin to "$name=; Max-Age=0; Domain=.linux.do; Path=/; Secure"
                    jobs += origin to "$name=; Max-Age=0; Domain=.linux.do; Path=/; Secure; HttpOnly; SameSite=None; Partitioned"
                    jobs += origin to "$name=; Max-Age=0; Domain=.linux.do; Path=/; Secure; SameSite=None; Partitioned"
                }
            }
            for (name in cfCookieNames) {
                jobs += "https://challenges.cloudflare.com" to "$name=; Max-Age=0; Path=/; Secure; HttpOnly"
                jobs += "https://challenges.cloudflare.com" to "$name=; Max-Age=0; Path=/; Secure"
                jobs += "https://challenges.cloudflare.com" to "$name=; Max-Age=0; Path=/; Secure; HttpOnly; SameSite=None; Partitioned"
                jobs += "https://challenges.cloudflare.com" to "$name=; Max-Age=0; Path=/; Secure; SameSite=None; Partitioned"
                jobs += "https://challenges.cloudflare.com" to "$name=; Max-Age=0; Domain=.cloudflare.com; Path=/; Secure; HttpOnly"
                jobs += "https://challenges.cloudflare.com" to "$name=; Max-Age=0; Domain=.cloudflare.com; Path=/; Secure; SameSite=None; Partitioned"
            }
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.GET_COOKIE_INFO)) {
                for (origin in listOf("https://linux.do", "https://challenges.cloudflare.com")) {
                    val infos = runCatching {
                        androidx.webkit.CookieManagerCompat.getCookieInfo(cookieManager, origin)
                    }.getOrDefault(emptyList())
                    for (rawSetCookie in infos) {
                        val firstPart = rawSetCookie.substringBefore(';').trim()
                        val cName = firstPart.substringBefore('=').trim()
                        if (cName in cfCookieNames) {
                            val attrs = rawSetCookie.substringAfter(';', "").trim()
                            jobs += origin to do_.linux.lindo.core.network.ManualForceRefreshPolicy.buildExpiredSetCookieSpec(
                                cookieName = cName,
                                rawAttrs = attrs
                            )
                        }
                    }
                }
            }
            if (jobs.isNotEmpty()) {
                withTimeoutOrNull(2_500L) {
                    suspendCancellableCoroutine<Unit> { cont ->
                        val remaining = java.util.concurrent.atomic.AtomicInteger(jobs.size)
                        for ((origin, spec) in jobs) {
                            cookieManager.setCookie(origin, spec) {
                                if (remaining.decrementAndGet() == 0 && cont.isActive) {
                                    cont.resume(Unit)
                                }
                            }
                        }
                    }
                }
            }
            runCatching { cookieManager.flush() }
            CookieSentinel.purgeCfCookiesOnly(domain = "linux.do")
            CfClearanceAuthority.reset()
        }

    private suspend fun handleNetworkRestoreRecycle() {
        do_.linux.lindo.ech.EchGatewayManager.awaitReconcileAndProxyReady()
        val routeFlipped = do_.linux.lindo.ech.EchGatewayManager.consumeRouteStackFlip() ||
            pendingVpnRouteFlipped
        pendingVpnRouteFlipped = false
        if (routeFlipped) {
            discardHandoffVerifyWebView()
            pendingTransportRecycleOnNextUse = true
        }
        ensureHealthyApiWebView(forceCheck = true)
        withContext(Dispatchers.Main) {
            UserApiKeyAuthManager.notifyNetworkRestored()
        }
        if (routeFlipped && UserPresenceService.isForeground && UserApiKeyAuthManager.isAuthorized()) {
            scheduleSilentChallengePrewarmIfNeeded(delayMs = 500L, reason = "route_changed")
        }
    }

    private fun dispatchNetworkRestoreRecycle() {
        do_.linux.lindo.ech.EchGatewayManager.refreshStatusAsync()
        val now = System.currentTimeMillis()
        val elapsed = now - lastNetworkRestoreNotifyAtMs
        if (elapsed >= 2_500L) {
            trailingNetworkRestoreJob?.cancel()
            trailingNetworkRestoreJob = null
            lastNetworkRestoreNotifyAtMs = now
            activityScope.launch {
                handleNetworkRestoreRecycle()
            }
        } else {
            val waitMs = (2_500L - elapsed).coerceAtLeast(600L)
            trailingNetworkRestoreJob?.cancel()
            trailingNetworkRestoreJob = activityScope.launch {
                delay(waitMs)
                lastNetworkRestoreNotifyAtMs = System.currentTimeMillis()
                handleNetworkRestoreRecycle()
            }
        }
    }

    private fun registerNetworkRecoveryMonitor() {
        if (connectivityCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                if (network.networkHandle == activeValidatedNetworkHandle) {
                    val prevSig = activeTransportSignature.ifBlank { "none" }
                    activeValidatedNetworkHandle = 0L
                    activeTransportSignature = ""
                    hadNetworkInterruption = true
                    LinDoDiagnosticLogger.warn(
                        DiagnosticCategory.NETWORK,
                        "NetMonitor",
                        "Active network lost (prev=$prevSig)"
                    )
                }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                val hasValidatedInternet =
                    networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (!hasValidatedInternet) {
                    if (network.networkHandle == activeValidatedNetworkHandle) {
                        hadNetworkInterruption = true
                    }
                    return
                }
                val handle = network.networkHandle
                val rawSig = computeTransportSignature(cm, network, networkCapabilities)
                val effectiveSig = do_.linux.lindo.core.network.NetworkPolicyEngine.normalizeEffectiveNetworkSignature(rawSig)
                val physNow = do_.linux.lindo.core.network.NetworkPolicyEngine.extractPhysicalTransportSet(rawSig)
                if (activeValidatedNetworkHandle == 0L && !hadNetworkInterruption) {
                    activeValidatedNetworkHandle = handle
                    activeTransportSignature = rawSig
                    lastCommittedEffectiveNetSig = effectiveSig
                    lastCommittedPhysicalTransports = physNow
                    return
                }
                val wasInterrupted = hadNetworkInterruption
                activeValidatedNetworkHandle = handle
                activeTransportSignature = rawSig
                hadNetworkInterruption = false
                // VPN 活跃且 tun 接口与 DNS 不变时：
                // 1) 三星 77ms 内的 wifi,vpn <-> cell,vpn 瞬态抖动不立即触发切网；
                // 2) 若物理承载真正发生持续迁移（如走出门从 Wi-Fi 切换到 5G 并保持 >= 2.0s），执行无感连接池回收与票据轮换放行！
                if (!wasInterrupted && effectiveSig == lastCommittedEffectiveNetSig) {
                    if (physNow.isNotEmpty() &&
                        lastCommittedPhysicalTransports.isNotEmpty() &&
                        physNow != lastCommittedPhysicalTransports
                    ) {
                        physicalUplinkDebounceJob?.cancel()
                        physicalUplinkDebounceJob = activityScope.launch {
                            delay(2_000L)
                            val settledRaw = activeTransportSignature
                            val settledPhys = do_.linux.lindo.core.network.NetworkPolicyEngine.extractPhysicalTransportSet(settledRaw)
                            if (settledPhys.isEmpty() || settledPhys == lastCommittedPhysicalTransports) return@launch
                            val prevPhys = lastCommittedPhysicalTransports
                            lastCommittedPhysicalTransports = settledPhys
                            pendingTransportRecycleOnNextUse = true
                            do_.linux.lindo.core.network.NetworkPolicyEngine.resetOnNetworkChange()
                            PlatformSessionRecovery.forumConnection.clearIfNotChecking()
                            LinDoDiagnosticLogger.info(
                                DiagnosticCategory.NETWORK,
                                "NetMonitor",
                                "Physical uplink changed under VPN ($prevPhys -> $settledPhys, raw=$settledRaw)"
                            )
                            dispatchNetworkRestoreRecycle()
                        }
                    } else if (physNow == lastCommittedPhysicalTransports) {
                        physicalUplinkDebounceJob?.cancel()
                    }
                    return
                }
                physicalUplinkDebounceJob?.cancel()
                networkChangeDebounceJob?.cancel()
                networkChangeDebounceJob = activityScope.launch {
                    delay(400L)
                    val settledRaw = activeTransportSignature.ifBlank { rawSig }
                    val settledEffective = do_.linux.lindo.core.network.NetworkPolicyEngine.normalizeEffectiveNetworkSignature(settledRaw)
                    if (!wasInterrupted && settledEffective == lastCommittedEffectiveNetSig) return@launch
                    val prevSig = lastCommittedEffectiveNetSig.ifBlank { "none" }
                    lastCommittedEffectiveNetSig = settledEffective
                    lastCommittedPhysicalTransports = do_.linux.lindo.core.network.NetworkPolicyEngine.extractPhysicalTransportSet(settledRaw)
                    if (prevSig != "none" && prevSig.contains("vpn") != settledEffective.contains("vpn")) {
                        pendingVpnRouteFlipped = true
                    }
                    do_.linux.lindo.core.network.NetworkPolicyEngine.resetOnNetworkChange()
                    PlatformSessionRecovery.forumConnection.clearIfNotChecking()
                    val healthLine = do_.linux.lindo.core.network.NetworkPolicyEngine.buildHealthSnapshot(
                        profileName = activeWebViewProfileName,
                        webViewGeneration = apiWebViewGeneration.toInt(),
                        networkSignature = settledRaw
                    ).toDiagnosticLine()
                    LinDoDiagnosticLogger.info(
                        DiagnosticCategory.NETWORK,
                        "NetMonitor",
                        "Network changed ($prevSig -> $settledEffective, raw=$settledRaw, interrupted=$wasInterrupted) [$healthLine]"
                    )
                    dispatchNetworkRestoreRecycle()
                }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) {
                if (network.networkHandle != activeValidatedNetworkHandle || activeValidatedNetworkHandle == 0L) return
                val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull() ?: return
                val hasValidatedInternet =
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (!hasValidatedInternet) return
                val rawSig = computeTransportSignature(cm, network, caps)
                val effectiveSig = do_.linux.lindo.core.network.NetworkPolicyEngine.normalizeEffectiveNetworkSignature(rawSig)
                activeTransportSignature = rawSig
                if (lastCommittedEffectiveNetSig.isNotEmpty() && effectiveSig != lastCommittedEffectiveNetSig) {
                    networkChangeDebounceJob?.cancel()
                    networkChangeDebounceJob = activityScope.launch {
                        delay(400L)
                        val settledRaw = activeTransportSignature.ifBlank { rawSig }
                        val settledEffective = do_.linux.lindo.core.network.NetworkPolicyEngine.normalizeEffectiveNetworkSignature(settledRaw)
                        if (settledEffective == lastCommittedEffectiveNetSig) return@launch
                        val prevSig = lastCommittedEffectiveNetSig
                        lastCommittedEffectiveNetSig = settledEffective
                        if (prevSig.contains("vpn") != settledEffective.contains("vpn")) {
                            pendingVpnRouteFlipped = true
                        }
                        LinDoDiagnosticLogger.info(
                            DiagnosticCategory.NETWORK,
                            "NetMonitor",
                            "Link properties changed ($prevSig -> $settledEffective, raw=$settledRaw)"
                        )
                        dispatchNetworkRestoreRecycle()
                    }
                }
            }
        }
        val initialNet = runCatching { cm.activeNetwork }.getOrNull()
        val initialCaps = runCatching { initialNet?.let { cm.getNetworkCapabilities(it) } }.getOrNull()
        val initiallyValidated = initialCaps != null &&
            initialCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            initialCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        if (initiallyValidated && initialNet != null) {
            activeValidatedNetworkHandle = initialNet.networkHandle
            activeTransportSignature = computeTransportSignature(cm, initialNet, initialCaps)
            lastCommittedEffectiveNetSig = do_.linux.lindo.core.network.NetworkPolicyEngine.normalizeEffectiveNetworkSignature(activeTransportSignature)
            lastCommittedPhysicalTransports = do_.linux.lindo.core.network.NetworkPolicyEngine.extractPhysicalTransportSet(activeTransportSignature)
            hadNetworkInterruption = false
        } else {
            activeValidatedNetworkHandle = 0L
            activeTransportSignature = ""
            lastCommittedEffectiveNetSig = ""
            lastCommittedPhysicalTransports = ""
            hadNetworkInterruption = true
        }
        runCatching {
            cm.registerDefaultNetworkCallback(callback)
            connectivityCallback = callback
        }
    }

    private fun unregisterNetworkRecoveryMonitor() {
        networkChangeDebounceJob?.cancel()
        networkChangeDebounceJob = null
        physicalUplinkDebounceJob?.cancel()
        physicalUplinkDebounceJob = null
        trailingNetworkRestoreJob?.cancel()
        trailingNetworkRestoreJob = null
        val cb = connectivityCallback ?: return
        connectivityCallback = null
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching { cm.unregisterNetworkCallback(cb) }
    }

    override fun onDestroy() {
        unregisterNetworkRecoveryMonitor()
        takeHandoffVerifyWebView()?.let { runCatching { releaseNativeWebView(it) } }
        pauseWebViewJob?.cancel()
        pauseWebViewJob = null
        activityScope.cancel()
        pendingMediaPickCallback = null
        pendingMultiMediaPickCallback = null
        pendingQrImageCallback = null
        pendingWebFileChooserCallback?.onReceiveValue(null)
        pendingWebFileChooserCallback = null
        pendingUploadPayloads.clear()
        pendingHttpCallbacks.values.forEach { it.cancel() }
        pendingHttpCallbacks.clear()
        pendingHttpMeta.clear()
        pendingBinaryCallbacks.values.forEach { it.cancel() }
        pendingBinaryCallbacks.clear()
        if (activeInstance === this) {
            activeInstance = null
            SavedAccounts.refreshBrowserCookies = null
            SavedAccounts.replaceBrowserCookies = null
            AppAppearance.onPersist = null
            AppAppearance.onApplyIconAppearance = null
            do_.linux.lindo.core.compose.LocalComposerDrafts.onPersist = null
            UserApiKeyAuthManager.onPersistPendingKey = null
            UserApiKeyAuthManager.openExternalUrlLauncher = null
            UserApiKeyAuthManager.openAuthTabLauncher = null
            UserApiKeyAuthManager.onPersistQrClientId = null
            UserApiKeyAuthManager.onPersistSession = null
            UserApiKeyAuthManager.onPersistReadTopics = null
            UserApiKeyAuthManager.onPersistUiPrefs = null
            UserApiKeyAuthManager.onPersistThemeAndDensity = null
            UserApiKeyAuthManager.onPersistHomeSearchExpanded = null
            UserApiKeyAuthManager.onPersistSplitFraction = null
            UserApiKeyAuthManager.onPrepareManualRecovery = null
            UserApiKeyAuthManager.onPrepareFreshAuthExchange = null
            UserApiKeyAuthManager.onSyncCookieToPlatform = null
            UserApiKeyAuthManager.onLogoutPlatformClear = null
            UserApiKeyAuthManager.webViewHttpExecutor = null
            UserApiKeyAuthManager.webViewJsonFetcher = null
            UserApiKeyAuthManager.webViewBinaryFetcher = null
            PlatformMediaPicker.launchPicker = null
            PlatformMediaPicker.webViewBinaryUploadExecutor = null
            PlatformMediaPicker.webViewUploadExecutor = null
            PlatformWebLoginHost.webLoginRenderer = null
            PlatformQrScanner.scannerContent = null
            PlatformQrScanner.pickImageAndDecodeQr = null
            UserApiKeyAuthManager.onRequestClearancePrewarm = null
            do_.linux.lindo.ui.PlatformSessionRecovery.verifySession = null
            PlatformSessionRecovery.checkForumConnection = null
            PlatformSessionRecovery.forumConnection.reset()
            do_.linux.lindo.ui.PlatformSessionRecovery.onExpandVerifyPill = null
        }
        apiWebView?.let { releaseNativeWebView(it) }
        apiWebView = null
        apiWebViewHost?.let { host ->
            (host.parent as? ViewGroup)?.removeView(host)
        }
        apiWebViewHost = null
        silentVerifyHost?.let { host ->
            (host.parent as? ViewGroup)?.removeView(host)
        }
        silentVerifyHost = null
        iconManager.flushPendingOnBackground(isChangingConfigurations)
        super.onDestroy()
    }

    private fun abortPendingHttpRequests(
        tag: String,
        extraInfo: String = "",
        predicate: (PendingHttpMeta) -> Boolean
    ): Int {
        val toAbort = mutableListOf<String>()
        for ((reqId, meta) in pendingHttpMeta.entries) {
            if (predicate(meta)) {
                toAbort += reqId
            }
        }
        if (toAbort.isEmpty()) return 0
        val nowWall = System.currentTimeMillis()
        var aborted = 0
        val wv = apiWebView
        for (reqId in toAbort) {
            val meta = pendingHttpMeta.remove(reqId)
            val deferred = pendingHttpCallbacks.remove(reqId)
            if (deferred != null && !deferred.isCompleted) {
                val ageMs = meta?.let { (nowWall - it.createdAtWallMs).coerceAtLeast(0L) } ?: 0L
                val diag = buildString {
                    append("[BRIDGE_DIAG:")
                    append(tag)
                    append("] wallAge=")
                    append(ageMs)
                    append("ms")
                    if (extraInfo.isNotBlank()) {
                        append(' ')
                        append(extraInfo)
                    }
                }
                if (deferred.complete(0 to diag)) {
                    aborted++
                }
                if (wv != null) {
                    val quoted = JSONObject.quote(reqId)
                    runOnUiThread {
                        if (wv === apiWebView) {
                            runCatching {
                                wv.evaluateJavascript("if(window.__lindoCancelHttp)window.__lindoCancelHttp($quoted);", null)
                            }
                        }
                    }
                }
            }
        }
        return aborted
    }

    override fun onResume() {
        super.onResume()
        tryResumePendingApkInstall()
        pauseWebViewJob?.cancel()
        pauseWebViewJob = null
        val pausedAt = lastPausedAtEpochMs
        lastPausedAtEpochMs = 0L
        UserPresenceService.setAppForeground(true)
        do_.linux.lindo.ech.EchGatewayManager.refreshStatusAsync()
        requestHighRefreshRateDisplay()
        runCatching {
            apiWebView?.onResume()
            apiWebView?.resumeTimers()
        }
        val pausedMs = if (pausedAt > 0L) (System.currentTimeMillis() - pausedAt).coerceAtLeast(0L) else 0L
        var reapedStale = 0
        if (pausedMs >= 60_000L) {
            // 休眠满 60s（如通勤切网解锁）：清理休眠前暂存的旧网络 WebView 与挂起验证窗，杜绝拿着旧网络态反复卡盾
            discardHandoffVerifyWebView()
            do_.linux.lindo.ui.PlatformSessionRecovery.dismissStaleVerifyModalOnLongResume?.invoke()
        }
        if (pausedMs >= 15_000L) {
            val nowWall = System.currentTimeMillis()
            reapedStale = abortPendingHttpRequests(
                tag = "stale_after_pause",
                extraInfo = "pausedMs=${pausedMs}ms"
            ) { meta ->
                val ageMs = nowWall - meta.createdAtWallMs
                val maxAge = if (meta.isRead || meta.isBackground) 12_000L else 20_000L
                ageMs >= maxAge
            }
        }
        if (pausedMs >= 5_000L || reapedStale > 0) {
            LinDoDiagnosticLogger.info(
                DiagnosticCategory.SYSTEM,
                "Lifecycle",
                "App resumed after ${pausedMs / 1000L}s (reapedStale=$reapedStale, pendingHttp=${pendingHttpCallbacks.size}, net=${activeTransportSignature.ifBlank { "none" }}, gen=$apiWebViewGeneration)"
            )
        }
        if (pausedMs >= 300_000L) {
            // 长休眠 >= 5m 后 NAT/H2/QUIC 连接常已半开黑洞化：标记下次请求前干净回收重建
            pendingTransportRecycleOnNextUse = true
        }
        if (pausedMs >= 60_000L) {
            activityScope.launch {
                ensureHealthyApiWebView(forceCheck = true)
                runOnCookieIo {
                    runCatching { ensureAuthCookiesInWebKit(MainActivity.currentWebViewCookieManager()) }
                }
            }
        }
        val hadPendingInteractive = do_.linux.lindo.core.network.NetworkPolicyEngine.consumePendingInteractiveVerify()
        // 空闲临期（>= 10.5h）无感静默续期：即使当前票据仍“健康”也强制换新
        if (do_.linux.lindo.core.network.NetworkPolicyEngine.shouldTriggerNearExpirySilentRefresh(
                ageSecs = CfClearanceAuthority.ageSecs(),
                activeFgRequests = LinuxDoRepository.inFlightForegroundCount(),
                isVerifying = NetworkRecoveryPolicy.verifying
            )
        ) {
            scheduleSilentChallengePrewarmIfNeeded(delayMs = 2_000L, reason = "near_expiry")
        } else if (hadPendingInteractive) {
            scheduleSilentChallengePrewarmIfNeeded(delayMs = 450L, reason = "resume_pending_interactive")
        } else if (pausedMs >= 5_000L && UserApiKeyAuthManager.isAuthorized()) {
            scheduleSilentChallengePrewarmIfNeeded(delayMs = 1_200L, reason = "resume")
        }
    }

    override fun onPause() {
        lastPausedAtEpochMs = System.currentTimeMillis()
        val firstPoppedBatch = ScreenTrackEngine.flushCurrentTopic()
        UserPresenceService.setAppForeground(false)
        runCatching {
            do_.linux.lindo.core.compose.LocalComposerDrafts.flushPendingPersist()
            currentWebViewCookieManager().flush()
            mirrorActiveCookiesToDefaultProfile()
            persistSessionToDisk()
        }
        // 切后台前主动释放仍在途的后台轮询/草稿及图片二进制拉取，杜绝 WebView 冻结后占死并发槽位
        abortPendingHttpRequests(tag = "app_paused") { meta -> meta.isBackground && meta.isRead }
        if (pendingBinaryCallbacks.isNotEmpty()) {
            val wv = apiWebView
            val binIds = pendingBinaryCallbacks.keys.toList()
            for (binId in binIds) {
                pendingBinaryCallbacks.remove(binId)?.complete(Triple(0, "", ""))
                if (wv != null) {
                    val q = JSONObject.quote(binId)
                    runCatching { wv.evaluateJavascript("if(window.__lindoCancelBinary)window.__lindoCancelBinary($q);", null) }
                }
            }
        }
        val hasTimingFlush = firstPoppedBatch != null || ScreenTrackEngine.hasPendingWork()
        pauseWebViewJob?.cancel()
        if (hasTimingFlush && UserApiKeyAuthManager.isAuthorized() && apiWebView != null) {
            pauseWebViewJob = activityScope.launch {
                try {
                    withTimeoutOrNull(500L) {
                        var next = firstPoppedBatch ?: ScreenTrackEngine.popNextSendableBatch()
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
                } finally {
                    if (!UserPresenceService.isForeground) {
                        runCatching { apiWebView?.onPause() }
                    }
                }
            }
        } else {
            if (firstPoppedBatch != null) {
                ScreenTrackEngine.consolidateBatch(
                    firstPoppedBatch.topicId,
                    firstPoppedBatch.topicTimeMs,
                    firstPoppedBatch.timingsMs
                )
            }
            runCatching { apiWebView?.onPause() }
        }
        super.onPause()
    }

    override fun onStop() {
        abortPendingHttpRequests(tag = "app_paused") { meta -> meta.isBackground }
        super.onStop()
        iconManager.flushPendingOnBackground(isChangingConfigurations)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        UserPresenceService.markUserActivity()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN &&
            event.keyCode != android.view.KeyEvent.KEYCODE_BACK &&
            event.keyCode != android.view.KeyEvent.KEYCODE_VOLUME_UP &&
            event.keyCode != android.view.KeyEvent.KEYCODE_VOLUME_DOWN
        ) {
            val imeOpen = runCatching {
                androidx.core.view.ViewCompat.getRootWindowInsets(window.decorView)
                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
            }.getOrDefault(false)
            if (imeOpen) {
                do_.linux.lindo.core.network.NetworkPolicyEngine.noteUserTyping()
            }
        }
        return super.dispatchKeyEvent(event)
    }
}

/**
 * 在 `/session/otp/<token>` 页面自动提交 Discourse OTP POST 确认表单以立即完成会话兑换
 */
private val AUTO_SUBMIT_OTP_FORM_JS = """
    (function() {
        try {
            if (window.location.pathname.indexOf('/session/otp/') === 0) {
                var form = document.querySelector('form[action*="/session/otp/"]')
                    || document.querySelector('form[method="post"]')
                    || document.querySelector('form');
                if (form && !window.__lindoOtpSubmitted) {
                    window.__lindoOtpSubmitted = true;
                    var btn = form.querySelector('input[type="submit"], button[type="submit"], .btn-primary, button');
                    if (btn) {
                        btn.click();
                    } else {
                        form.submit();
                    }
                }
            }
        } catch (e) {}
    })();
""".trimIndent()

private fun buildVerificationCompactFrameJs(isDark: Boolean): String {
    val bg = if (isDark) "#1A1E28" else "#FFFFFF"
    return """
        (function() {
          try {
            var emitReady = (typeof console !== 'undefined' && console.debug) ? console.debug.bind(console) : function() {};
            var notifiedWidget = false;
            var signalWidgetReady = function() {
              window.__lindoTurnstileInteractiveBegin = true;
              if (!notifiedWidget) {
                notifiedWidget = true;
                try { emitReady('__lindo_cf_widget_ready__'); } catch (_) {}
              }
            };
            var checkChallengeDom = function() {
              try {
                if (document.querySelector('#challenge-stage, .cf-turnstile, #turnstile-wrapper, iframe[src*="challenges.cloudflare.com"]')) {
                  signalWidgetReady();
                  return true;
                }
              } catch (_) {}
              return false;
            };
            if (!window.__lindoTurnstileInteractiveHook) {
              window.__lindoTurnstileInteractiveHook = true;
              window.__lindoTurnstileInteractiveBegin = false;
              window.addEventListener('message', function(ev) {
                try {
                  var d = ev && ev.data;
                  var s = typeof d === 'string' ? d : (d && typeof d === 'object' ? JSON.stringify(d) : '');
                  if (s && (s.indexOf('interactiveBegin') !== -1 || s.indexOf('interactive-begin') !== -1)) {
                    signalWidgetReady();
                  }
                } catch (_) {}
              }, true);
              if (!checkChallengeDom() && typeof MutationObserver !== 'undefined' && document.documentElement) {
                var mo = new MutationObserver(function() {
                  if (checkChallengeDom()) {
                    try { mo.disconnect(); } catch (_) {}
                  }
                });
                mo.observe(document.documentElement, { childList: true, subtree: true });
              }
            } else {
              checkChallengeDom();
            }
            if (!window.__lindoCompactVerifyMaskInstalled) {
              window.__lindoCompactVerifyMaskInstalled = true;
              var hideDoc = function() {
                try {
                  document.documentElement.style.opacity = '0';
                  document.documentElement.style.background = '$bg';
                } catch (e) {}
              };
              window.addEventListener('beforeunload', hideDoc, true);
              window.addEventListener('pagehide', hideDoc, true);
              document.addEventListener('submit', hideDoc, true);
            }
            // 不再使用 display:none !important 隐藏 Cloudflare 原生节点，防止触发 Turnstile 环境异常检测，
            // 仅保持主题底色一致与无横向滚动条。
            var styleId = '__lindo_cf_theme_bg';
            if (!document.getElementById(styleId)) {
              var st = document.createElement('style');
              st.id = styleId;
              st.textContent = 'html, body { background: $bg !important; overflow-x: hidden !important; }';
              (document.head || document.documentElement).appendChild(st);
            }
          } catch (e) {}
        })();
    """.trimIndent()
}

private const val FORCE_REFRESH_CSRF_PROBE_JS = """
(function() {
    if (location.origin !== 'https://linux.do' || document.readyState === 'loading') return JSON.stringify({pending:true});
    if (window.__lindoForceRefreshCsrfResult) return JSON.stringify(window.__lindoForceRefreshCsrfResult);
    if (window.__lindoForceRefreshCsrfRunning) return JSON.stringify({pending:true});
    window.__lindoForceRefreshCsrfRunning = true;
    (async function() {
        var controller = new AbortController();
        var timer = setTimeout(function() { controller.abort(); }, 10000);
        try {
            var r = await fetch('/session/csrf.json', { credentials: 'include', cache: 'no-store', signal: controller.signal });
            var mitigated = (r.headers.get('cf-mitigated') || '').toLowerCase();
            if (mitigated.indexOf('challenge') !== -1) {
                window.__lindoForceRefreshCsrfResult = { status: r.status, challenged: true, ok: false };
                return;
            }
            if (r.status === 200) {
                var data = await r.json().catch(function() { return null; });
                var csrf = (data && typeof data.csrf === 'string') ? data.csrf : '';
                window.__lindoForceRefreshCsrfResult = { status: 200, challenged: false, ok: true, csrf: csrf };
                return;
            }
            window.__lindoForceRefreshCsrfResult = { status: r.status, challenged: false, ok: false };
        } catch (_) {
            window.__lindoForceRefreshCsrfResult = { status: 0, challenged: false, ok: false };
        } finally {
            clearTimeout(timer);
        }
    })();
    return JSON.stringify({pending:true});
})();
"""

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun AndroidWebLoginModal(
    initialUrl: String,
    onDismiss: () -> Unit,
    onLoginSuccess: () -> Unit,
    onLaunchFileChooser: ((ValueCallback<Array<Uri>>?, WebChromeClient.FileChooserParams?) -> Boolean)? = null,
    openedClearance: String? = null,
    openedAllClearances: Set<String> = emptySet(),
    forceRefresh: Boolean = false,
    sessionNonce: Long = 0L
) {
    val purpose = remember(initialUrl, sessionNonce) { do_.linux.lindo.core.network.webPagePurpose(initialUrl) }
    val isVerificationFlow = purpose == do_.linux.lindo.core.network.WebPagePurpose.VERIFICATION
    val isLoginFlow = purpose == do_.linux.lindo.core.network.WebPagePurpose.LOGIN
    val effectiveForceRefresh = isVerificationFlow && forceRefresh
    val purgedStaleClearances = remember(initialUrl, sessionNonce, effectiveForceRefresh, openedClearance, openedAllClearances) {
        if (!effectiveForceRefresh) emptySet()
        else buildSet {
            openedClearance?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
            openedAllClearances.forEach { v -> v.trim().takeIf { it.isNotEmpty() }?.let { add(it) } }
        }
    }
    fun sanitizeCookiesForSync(raw: String): String {
        if (!effectiveForceRefresh || purgedStaleClearances.isEmpty()) return raw
        return do_.linux.lindo.core.network.ManualForceRefreshPolicy.stripRejectedStaleClearance(
            rawCookies = raw,
            purgedStaleClearances = purgedStaleClearances
        )
    }
    fun resolveFreshClearanceForForceRefresh(raw: String): String? {
        val allCf = getAllCookieValues(raw, "cf_clearance")
        return allCf.firstOrNull { it !in purgedStaleClearances } ?: allCf.firstOrNull()
    }
    val openedWasChallenged = remember(initialUrl, sessionNonce, openedClearance, effectiveForceRefresh) {
        !openedClearance.isNullOrBlank() && (CfClearanceAuthority.isChallenged(openedClearance) || effectiveForceRefresh)
    }
    var forceRefreshCsrfStatus by remember(initialUrl, sessionNonce) { mutableStateOf<Int?>(null) }
    var forceRefreshCsrfChallenged by remember(initialUrl, sessionNonce) { mutableStateOf(false) }
    var forceRefreshCsrfValue by remember(initialUrl, sessionNonce) { mutableStateOf<String?>(null) }
    var forceRefreshCsrfProbeInFlight by remember(initialUrl, sessionNonce) { mutableStateOf(false) }

    var verificationExpanded by remember(initialUrl, sessionNonce) { mutableStateOf(false) }
    var interactiveChallengeActive by remember(initialUrl, sessionNonce) { mutableStateOf(false) }
    var challengeWidgetMounted by remember(initialUrl, sessionNonce) { mutableStateOf(false) }
    var challengeStageMounted by remember(initialUrl, sessionNonce) { mutableStateOf(false) }
    var modalWebViewGeneration by remember(initialUrl, sessionNonce) { mutableStateOf(0) }
    var showVerifyChannelRepairHint by remember(initialUrl, sessionNonce) { mutableStateOf(false) }
    var verificationNavigatingAway by remember(initialUrl, sessionNonce) { mutableStateOf(false) }
    var challengeOrigin404Reached by remember(initialUrl, sessionNonce) { mutableStateOf(false) }
    var staleClearancePurgedInModal by remember(initialUrl, sessionNonce) { mutableStateOf(false) }
    var fallbackMainSiteTried by remember(initialUrl, sessionNonce) { mutableStateOf(false) }
    var pageProgress by remember(initialUrl, sessionNonce) { mutableStateOf(0) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var usedHandoffWebView by remember(initialUrl, sessionNonce) { mutableStateOf(false) }
    var completed by remember(initialUrl, sessionNonce) { mutableStateOf(false) }
    var verificationProbeRunning by remember { mutableStateOf(false) }
    var csrfSyncedInModal by remember(initialUrl, sessionNonce) { mutableStateOf(false) }
    var loginDocumentReady by remember(initialUrl, sessionNonce) { mutableStateOf(false) }
    var loginNavigationEpoch by remember(initialUrl, sessionNonce) { mutableStateOf(0) }
    var confirmingLogin by remember(initialUrl, sessionNonce) { mutableStateOf(false) }
    var loginProbeRunning by remember(initialUrl, sessionNonce) { mutableStateOf(false) }
    var loginRateLimitEvent by remember(initialUrl, sessionNonce) { mutableStateOf<String?>(null) }
    var pageLoadError by remember { mutableStateOf<String?>(null) }
    var rendererGone by remember { mutableStateOf(false) }
    var pendingUrl by remember { mutableStateOf<String?>(null) }
    var initialLoadDispatched by remember(initialUrl, sessionNonce) { mutableStateOf(false) }
    val isDark = LocalLinDoDarkTheme.current
    val modalKeyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val modalFocusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val modalLocalView = androidx.compose.ui.platform.LocalView.current

    LaunchedEffect(initialUrl, isVerificationFlow) {
        if (isVerificationFlow) {
            runCatching { modalFocusManager.clearFocus(force = true) }
            runCatching { modalKeyboardController?.hide() }
            runCatching {
                val imm = modalLocalView.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.hideSoftInputFromWindow(modalLocalView.windowToken, 0)
            }
        }
    }

    fun reloadVerificationPage() {
        if (completed) return
        pendingUrl = initialUrl
        pageLoadError = null
        rendererGone = false
        usedHandoffWebView = false
        interactiveChallengeActive = false
        challengeWidgetMounted = false
        challengeStageMounted = false
        showVerifyChannelRepairHint = false
        verificationNavigatingAway = false
        challengeOrigin404Reached = false
        fallbackMainSiteTried = false
        staleClearancePurgedInModal = false
        forceRefreshCsrfStatus = null
        forceRefreshCsrfChallenged = false
        forceRefreshCsrfValue = null
        forceRefreshCsrfProbeInFlight = false
        initialLoadDispatched = false
        pageProgress = 0
        modalWebViewGeneration++
    }

    fun tryFallbackToMainSiteOnce(targetWebView: WebView?, purgeStaleFirst: Boolean): Boolean {
        if (!isVerificationFlow || completed || fallbackMainSiteTried || targetWebView == null) return false
        fallbackMainSiteTried = true
        val fallbackUrl = "https://linux.do/#lindo-verify"
        pendingUrl = fallbackUrl
        pageLoadError = null
        challengeOrigin404Reached = false
        forceRefreshCsrfStatus = null
        forceRefreshCsrfChallenged = false
        forceRefreshCsrfValue = null
        forceRefreshCsrfProbeInFlight = false
        if (purgeStaleFirst) {
            staleClearancePurgedInModal = true
            MainActivity.activeInstance?.activityScope?.launch(Dispatchers.Main) {
                runCatching {
                    MainActivity.activeInstance?.purgeCfCookiesInWebKit(MainActivity.currentWebViewCookieManager())
                }
                if (!completed && webViewRef === targetWebView) {
                    targetWebView.loadUrl(fallbackUrl)
                }
            }
        } else {
            targetWebView.loadUrl(fallbackUrl)
        }
        return true
    }

    fun retryPage() {
        if (isVerificationFlow) {
            if (!completed) reloadVerificationPage()
            return
        }
        val view = webViewRef
        val url = (if (pageLoadError != null) pendingUrl else view?.url) ?: initialUrl
        pendingUrl = url
        pageLoadError = null
        rendererGone = false
        usedHandoffWebView = false
        interactiveChallengeActive = false
        verificationNavigatingAway = false
        challengeOrigin404Reached = false
        confirmingLogin = false
        loginRateLimitEvent = null
        if (view?.url == url) view.reload() else view?.loadUrl(url)
    }

    fun completeSiteVerification(
        freshCsrf: String? = null,
        userJson: JSONObject? = null
    ) {
        if (completed) return
        interactiveChallengeActive = false
        freshCsrf?.takeIf { it.isNotBlank() }?.let { csrf ->
            csrfSyncedInModal = true
            LinuxDoRepository.updateCsrfToken(csrf)
        }
        completed = true
        val cm = MainActivity.currentWebViewCookieManager()
        val latestAfterVerify = runCatching { cm.getCookie("https://linux.do").orEmpty() }.getOrDefault("")
        val effectiveAfterVerify = sanitizeCookiesForSync(latestAfterVerify)
        val latestCf = getCookieValue(effectiveAfterVerify, "cf_clearance")
        val ticketOutcome = CfClearanceAuthority.evaluateVerifyOutcome(
            freshClearance = latestCf,
            previousClearance = openedClearance,
            previousWasChallenged = openedWasChallenged
        )
        val isUnchangedPurgedStale = effectiveForceRefresh && !latestCf.isNullOrBlank() && latestCf in purgedStaleClearances
        val canPromoteAsVerified = !isUnchangedPurgedStale && (
            ticketOutcome == do_.linux.lindo.core.network.VerifyTicketOutcome.ROTATED ||
                ticketOutcome == do_.linux.lindo.core.network.VerifyTicketOutcome.EXISTING_VALID
            )
        if (effectiveAfterVerify.isNotBlank()) {
            CookieSentinel.syncFromAuthoritativeBrowserCookieString(effectiveAfterVerify, verified = canPromoteAsVerified)
        }
        userJson?.let { u ->
            val uname = u.optString("username").trim()
            val uid = u.optInt("id", 0)
            val curAccount = UserApiKeyAuthManager.currentUser?.username
            if (uname.isNotBlank() && uname != "linuxdo_user" && uid > 0 &&
                (curAccount == null || curAccount.equals(uname, ignoreCase = true))
            ) {
                UserApiKeyAuthManager.recordWebSessionLogin(
                    username = uname,
                    displayName = u.optString("name").ifBlank { uname },
                    avatarUrl = LinuxDoSiteRules.resolveStaticAvatarUrl(u.optString("avatar_template"), 144),
                    trustLevel = u.optInt("trust_level", 1),
                    userId = uid,
                    animatedAvatar = LinuxDoSiteRules.resolveAnimatedAvatarUrl(u.optString("animated_avatar")),
                    userTitle = u.optString("title", "")
                )
            }
        }
        val hasCfAfterVerify = !latestCf.isNullOrBlank()
        LinDoDiagnosticLogger.info(
            DiagnosticCategory.AUTH,
            "SiteVerify",
            "Cloudflare verification completed (session=$sessionNonce, hasCf=$hasCfAfterVerify, origin404=$challengeOrigin404Reached, profile=${MainActivity.currentProfileName()}, handoff=$usedHandoffWebView)"
        )
        MainActivity.activeInstance?.activityScope?.launch(MainActivity.cookieIoDispatcher) {
            runCatching { cm.flush() }
            MainActivity.mirrorActiveCookiesToDefaultProfile()
            UserApiKeyAuthManager.persistSessionQuietly()
        }
        onLoginSuccess()
    }

    fun handleUserDismiss() = onDismiss()

    BackHandler {
        if (!isVerificationFlow && pageLoadError == null && webViewRef?.canGoBack() == true) webViewRef?.goBack() else handleUserDismiss()
    }

    fun completeWithUserObject(u: JSONObject) {
        if (completed) return
        val username = u.optString("username").trim()
        if (username.isBlank() || username == "linuxdo_user") return
        val userId = u.optInt("id", 0)
        if (userId <= 0) return
        val displayName = u.optString("name").ifBlank { username }
        val rawTpl = u.optString("avatar_template").ifBlank { u.optString("avatar") }
        val avatarUrl = LinuxDoSiteRules.resolveStaticAvatarUrl(rawTpl, 144)
        val title = u.optString("title", "")
        val trust = if (u.has("trust_level")) u.optInt("trust_level", 1) else u.optInt("trust", 1)

        completed = true
        val cookieManager = MainActivity.currentWebViewCookieManager()
        cookieManager.flush()
        MainActivity.mirrorActiveCookiesToDefaultProfile()
        val rawCookies = cookieManager.getCookie("https://linux.do").orEmpty()
        CookieSentinel.syncFromAuthoritativeBrowserCookieString(rawCookies, verified = true)
        LinDoDiagnosticLogger.info(DiagnosticCategory.AUTH, "WebLogin", "Session authenticated via in-app WebView")
        UserApiKeyAuthManager.onSessionAuthenticated(
            profile = AuthenticatedUserProfile(
                userId = userId,
                username = username,
                displayName = displayName,
                avatarTemplate = avatarUrl,
                animatedAvatar = LinuxDoSiteRules.resolveAnimatedAvatarUrl(u.optString("animated_avatar")),
                userTitle = title,
                trustLevel = trust
            ),
            rawCookies = rawCookies
        )
        onLoginSuccess()
    }

    fun tryExtractSessionAndComplete(webView: WebView?, isPageFinished: Boolean = false) {
        if (completed || webView == null) return
        val cookieManager = MainActivity.currentWebViewCookieManager()
        val rawCookies = cookieManager.getCookie("https://linux.do").orEmpty()

        // 若当前在 /session/otp/ 兑换页，仅在此路径下自动触发表单 POST 提交
        if (webView.url?.contains("/session/otp/") == true) {
            webView.evaluateJavascript(AUTO_SUBMIT_OTP_FORM_JS, null)
        }

        // 无论是否为登录流，只要页面加载完成，立即同步刷盘最新 Cookie（如通过 Cloudflare 验证后的 cf_clearance）
        if (isPageFinished) {
            val latestSynced = sanitizeCookiesForSync(cookieManager.getCookie("https://linux.do").orEmpty().ifBlank { rawCookies })
            if (latestSynced.isNotBlank()) {
                CookieSentinel.syncFromAuthoritativeBrowserCookieString(latestSynced)
            }
        }

        fun dispatchSingleForceRefreshCsrfProbe(targetWebView: WebView) {
            if (forceRefreshCsrfProbeInFlight || forceRefreshCsrfStatus != null || completed) return
            forceRefreshCsrfProbeInFlight = true
            val probeEpoch = loginNavigationEpoch
            targetWebView.evaluateJavascript(FORCE_REFRESH_CSRF_PROBE_JS) { raw ->
                if (probeEpoch != loginNavigationEpoch || targetWebView !== webViewRef) return@evaluateJavascript
                forceRefreshCsrfProbeInFlight = false
                val obj = decodeJsStringResult(raw)?.let { runCatching { JSONObject(it) }.getOrNull() }
                if (obj == null || obj.optBoolean("pending", false)) return@evaluateJavascript
                forceRefreshCsrfStatus = obj.optInt("status", 0)
                forceRefreshCsrfChallenged = obj.optBoolean("challenged", false)
                forceRefreshCsrfValue = obj.optString("csrf", "").takeIf { it.isNotBlank() }
                if (!completed) {
                    val evalAfterProbe = do_.linux.lindo.core.network.ManualForceRefreshPolicy.evaluateOutcome(
                        forceRefresh = true,
                        freshClearance = resolveFreshClearanceForForceRefresh(
                            MainActivity.currentWebViewCookieManager().getCookie("https://linux.do").orEmpty()
                        ),
                        purgedStaleClearances = purgedStaleClearances,
                        originReached = true,
                        hasChallengeOrWidget = false,
                        csrfProbeStatus = forceRefreshCsrfStatus,
                        csrfProbeChallenged = forceRefreshCsrfChallenged
                    )
                    when (evalAfterProbe) {
                        do_.linux.lindo.core.network.ForceRefreshEval.PASS_NEW_TICKET,
                        do_.linux.lindo.core.network.ForceRefreshEval.PASS_CLEAN_IP_NO_COOKIE -> {
                            completeSiteVerification(freshCsrf = forceRefreshCsrfValue)
                        }
                        do_.linux.lindo.core.network.ForceRefreshEval.REJECT_STALE_UNCHANGED -> {
                            if (!tryFallbackToMainSiteOnce(targetWebView, purgeStaleFirst = true)) {
                                pageLoadError = "站点安全校验尚未通过，请点刷新重试或稍后再试"
                            }
                        }
                        do_.linux.lindo.core.network.ForceRefreshEval.REJECT_CHALLENGED_OR_FAILED -> {
                            if (!tryFallbackToMainSiteOnce(targetWebView, purgeStaleFirst = false)) {
                                pageLoadError = if (forceRefreshCsrfStatus == 429 && !forceRefreshCsrfChallenged) {
                                    "请求较频繁，请稍候片刻再试"
                                } else {
                                    "站点安全校验尚未通过，请点刷新重试或稍后再试"
                                }
                            }
                        }
                        else -> Unit
                    }
                }
            }
        }

        if (isVerificationFlow && challengeOrigin404Reached && !completed) {
            val latestCf = if (effectiveForceRefresh) {
                resolveFreshClearanceForForceRefresh(rawCookies)
            } else {
                getCookieValue(rawCookies, "cf_clearance")
            }
            if (effectiveForceRefresh) {
                val eval = do_.linux.lindo.core.network.ManualForceRefreshPolicy.evaluateOutcome(
                    forceRefresh = true,
                    freshClearance = latestCf,
                    purgedStaleClearances = purgedStaleClearances,
                    originReached = true,
                    hasChallengeOrWidget = false,
                    csrfProbeStatus = forceRefreshCsrfStatus,
                    csrfProbeChallenged = forceRefreshCsrfChallenged
                )
                when (eval) {
                    do_.linux.lindo.core.network.ForceRefreshEval.PASS_NEW_TICKET,
                    do_.linux.lindo.core.network.ForceRefreshEval.PASS_CLEAN_IP_NO_COOKIE -> {
                        completeSiteVerification(freshCsrf = forceRefreshCsrfValue)
                        return
                    }
                    do_.linux.lindo.core.network.ForceRefreshEval.PROBE_CSRF_ONCE -> {
                        if (isPageFinished || loginDocumentReady) {
                            dispatchSingleForceRefreshCsrfProbe(webView)
                        }
                        return
                    }
                    do_.linux.lindo.core.network.ForceRefreshEval.REJECT_STALE_UNCHANGED -> {
                        if (!tryFallbackToMainSiteOnce(webView, purgeStaleFirst = true)) {
                            pageLoadError = "站点安全校验尚未通过，请点刷新重试或稍后再试"
                        }
                        return
                    }
                    do_.linux.lindo.core.network.ForceRefreshEval.REJECT_CHALLENGED_OR_FAILED -> {
                        if (!tryFallbackToMainSiteOnce(webView, purgeStaleFirst = false)) {
                            pageLoadError = "站点安全校验尚未通过，请点刷新重试或稍后再试"
                        }
                        return
                    }
                    else -> Unit
                }
            } else {
                val outcome = CfClearanceAuthority.evaluateVerifyOutcome(
                    freshClearance = latestCf,
                    previousClearance = openedClearance,
                    previousWasChallenged = openedWasChallenged
                )
                if (outcome == do_.linux.lindo.core.network.VerifyTicketOutcome.STALE_UNCHALLENGED_404) {
                    if (!tryFallbackToMainSiteOnce(webView, purgeStaleFirst = true)) {
                        pageLoadError = "连接尚未恢复，请稍后重试"
                    }
                    return
                }
                if (!latestCf.isNullOrBlank() || isPageFinished) {
                    completeSiteVerification()
                    return
                }
            }
        }

        // 验证流被动观察真实 Discourse DOM 放行结果（零额外网络请求，杜绝验证刚过又被 /session/csrf.json 429 拦截导致的无限刷新）
        if (isVerificationFlow && pageLoadError == null && !verificationProbeRunning) {
            verificationProbeRunning = true
            val probeEpoch = loginNavigationEpoch
            webView.evaluateJavascript(do_.linux.lindo.core.network.webVerificationProbeScript()) { raw ->
                if (probeEpoch != loginNavigationEpoch || webView !== webViewRef) return@evaluateJavascript
                verificationProbeRunning = false
                if (!completed) {
                    val result = decodeJsStringResult(raw)?.let { runCatching { JSONObject(it) }.getOrNull() }
                    if (result?.optBoolean("cfErrorPage") == true) {
                        pageLoadError = "验证页面暂时无法加载，请稍后重试"
                        return@evaluateJavascript
                    }
                    val isChallengeNow = result?.optBoolean("challenge") == true
                    val hasWidgetNow = result?.optBoolean("widget") == true || result?.optBoolean("interactive") == true
                    val hasStageDomNow = result?.optBoolean("stageDom") == true
                    if (isChallengeNow) {
                        verificationNavigatingAway = false
                        webView.evaluateJavascript(buildVerificationCompactFrameJs(isDark), null)
                    }
                    if (hasWidgetNow) {
                        challengeWidgetMounted = true
                        showVerifyChannelRepairHint = false
                    }
                    if (hasStageDomNow) {
                        challengeStageMounted = true
                        showVerifyChannelRepairHint = false
                    }
                    // 当挑战组件或真实挑战舞台（兼容 Cloudflare Closed ShadowRoot）已渲染时立即展示交互框，杜绝误判为空白页反复重刷
                    interactiveChallengeActive = isChallengeNow &&
                        (challengeWidgetMounted || challengeStageMounted || showVerifyChannelRepairHint)
                    if (result?.optInt("status") == 429) {
                        LinuxDoRepository.noteRetryAfter(result.optString("retryAfter"))
                    }
                    if (result?.optBoolean("ok") == true) {
                        if (effectiveForceRefresh) {
                            val latestCfNow = resolveFreshClearanceForForceRefresh(
                                cookieManager.getCookie("https://linux.do").orEmpty()
                            )
                            val domCsrfReady = fallbackMainSiteTried && result.optString("csrf").isNotBlank()
                            val eval = do_.linux.lindo.core.network.ManualForceRefreshPolicy.evaluateOutcome(
                                forceRefresh = true,
                                freshClearance = latestCfNow,
                                purgedStaleClearances = purgedStaleClearances,
                                originReached = true,
                                hasChallengeOrWidget = false,
                                csrfProbeStatus = forceRefreshCsrfStatus ?: (if (domCsrfReady) 200 else null),
                                csrfProbeChallenged = forceRefreshCsrfChallenged
                            )
                            when (eval) {
                                do_.linux.lindo.core.network.ForceRefreshEval.PASS_NEW_TICKET,
                                do_.linux.lindo.core.network.ForceRefreshEval.PASS_CLEAN_IP_NO_COOKIE -> {
                                    completeSiteVerification(
                                        freshCsrf = forceRefreshCsrfValue ?: result.optString("csrf"),
                                        userJson = result.optJSONObject("user")
                                    )
                                }
                                do_.linux.lindo.core.network.ForceRefreshEval.PROBE_CSRF_ONCE -> {
                                    dispatchSingleForceRefreshCsrfProbe(webView)
                                }
                                do_.linux.lindo.core.network.ForceRefreshEval.REJECT_STALE_UNCHANGED -> {
                                    if (!tryFallbackToMainSiteOnce(webView, purgeStaleFirst = true)) {
                                        pageLoadError = "站点安全校验尚未通过，请点刷新重试或稍后再试"
                                    }
                                }
                                do_.linux.lindo.core.network.ForceRefreshEval.REJECT_CHALLENGED_OR_FAILED -> {
                                    if (!tryFallbackToMainSiteOnce(webView, purgeStaleFirst = false)) {
                                        pageLoadError = if (forceRefreshCsrfStatus == 429 && !forceRefreshCsrfChallenged) {
                                            "请求较频繁，请稍候片刻再试"
                                        } else {
                                            "站点安全校验尚未通过，请点刷新重试或稍后再试"
                                        }
                                    }
                                }
                                else -> Unit
                            }
                        } else {
                            completeSiteVerification(
                                freshCsrf = result.optString("csrf"),
                                userJson = result.optJSONObject("user")
                            )
                        }
                    }
                }
            }
        }
        if (!isLoginFlow) {
            val latestCookies = sanitizeCookiesForSync(cookieManager.getCookie("https://linux.do").orEmpty().ifBlank { rawCookies })
            if (latestCookies.isNotBlank()) {
                CookieSentinel.syncFromAuthoritativeBrowserCookieString(latestCookies)
            }
            // 若用户已完成安全验证并进入真实主站 HTML（含 Discourse meta csrf-token），同步最新 CSRF Token 至仓库
            if (isPageFinished &&
                webView.url?.startsWith("https://linux.do") == true &&
                hasCookieNamed(latestCookies, "_t")
            ) {
                webView.evaluateJavascript(
                    "(function(){var m=document.querySelector('meta[name=\"csrf-token\"]');return (m&&m.content)?m.content:'';})();"
                ) { raw ->
                    val csrf = decodeJsStringResult(raw)?.trim().orEmpty()
                    if (csrf.isNotEmpty()) {
                        csrfSyncedInModal = true
                        LinuxDoRepository.updateCsrfToken(csrf)
                    }
                }
            }
            return
        }

        // An unchanged _t is normal when the browser already has a valid session.
        // Confirm the real user instead of requiring cookie rotation or trusting _t alone.
        if (pageLoadError != null || loginProbeRunning || (!loginDocumentReady && webView.progress < 100)) return
        val probeEpoch = loginNavigationEpoch
        loginProbeRunning = true
        val owner = getCookieValue(rawCookies, "_t").orEmpty().let { "${it.hashCode()}_${it.length}" }
        webView.evaluateJavascript(do_.linux.lindo.core.network.webLoginSessionProbeScript(owner)) { raw ->
            if (probeEpoch != loginNavigationEpoch) return@evaluateJavascript
            loginProbeRunning = false
            if (completed || webView !== webViewRef) return@evaluateJavascript
            val currentT = cookieManager.getCookie("https://linux.do").orEmpty().let { getCookieValue(it, "_t").orEmpty() }
            if (owner != "${currentT.hashCode()}_${currentT.length}") return@evaluateJavascript
            val result = runCatching { JSONObject(decodeJsStringResult(raw).orEmpty()) }.getOrNull()
                ?: return@evaluateJavascript
            val event = result.optInt("rateLimitAttempt", 0)
            if (event > 0 && loginRateLimitEvent != "$owner:$event") {
                loginRateLimitEvent = "$owner:$event"
                LinuxDoRepository.noteRetryAfter(result.optString("retryAfter"))
            }
            confirmingLogin = result.optString("state") == "waiting"
            when (result.optString("state")) {
                "ready" -> {
                    result.optString("csrf").takeIf { it.isNotBlank() }?.let {
                        csrfSyncedInModal = true
                        LinuxDoRepository.updateCsrfToken(it)
                    }
                    result.optJSONObject("user")?.let(::completeWithUserObject)
                }
                "error" -> pageLoadError = if (event > 0) "网站暂时繁忙，请稍后重试" else "暂时无法确认登录状态，请重试"
            }
        }
    }

    // 每个 initialUrl / 通道重建代数仅分发一次导航（确保 Cloudflare Turnstile 提交重定向至 https://linux.do/ 后绝不因重组被二次 loadUrl 打断）
    LaunchedEffect(webViewRef, initialUrl, sessionNonce, modalWebViewGeneration) {
        val wv = webViewRef ?: return@LaunchedEffect
        if (initialLoadDispatched || initialUrl.isBlank()) return@LaunchedEffect
        initialLoadDispatched = true
        pendingUrl = initialUrl
        pageLoadError = null
        if ((isLoginFlow && (!UserApiKeyAuthManager.isAuthorized() || SavedAccounts.adding)) ||
            initialUrl.contains("/session/otp/", ignoreCase = true)
        ) {
            runCatching {
                val cm = MainActivity.currentWebViewCookieManager()
                for (origin in listOf("https://linux.do", "https://www.linux.do")) {
                    for (name in listOf("_t", "_forum_session", "authentication_data")) {
                        for (spec in listOf(
                            "$name=; Max-Age=0; Path=/; Secure; HttpOnly",
                            "$name=; Max-Age=0; Path=/; Secure",
                            "$name=; Max-Age=0; Domain=linux.do; Path=/; Secure; HttpOnly",
                            "$name=; Max-Age=0; Domain=.linux.do; Path=/; Secure; HttpOnly",
                            "$name=; Max-Age=0; Domain=.linux.do; Path=/; Secure"
                        )) {
                            suspendCancellableCoroutine<Unit> { cont ->
                                cm.setCookie(origin, spec) { if (cont.isActive) cont.resume(Unit) }
                            }
                        }
                    }
                }
                cm.flush()
                CookieSentinel.clearAuthCookiesOnly()
            }
        } else if (!SavedAccounts.adding && UserApiKeyAuthManager.isAuthorized()) {
            runCatching {
                val cm = MainActivity.currentWebViewCookieManager()
                val cur = cm.getCookie("https://linux.do").orEmpty()
                val tList = getAllCookieValues(cur, "_t").filter { it != "demo_session_token" }
                if (tList.size > 1) {
                    val winning = CookieSentinel.resolveWinningCookies(targetHost = "linux.do")
                    val winnerT = winning["_t"]?.value?.takeIf { it in tList } ?: tList.last()
                    for (origin in listOf("https://linux.do", "https://www.linux.do")) {
                        for (spec in listOf(
                            "_t=; Max-Age=0; Path=/; Secure; HttpOnly",
                            "_t=; Max-Age=0; Domain=linux.do; Path=/; Secure; HttpOnly",
                            "_t=; Max-Age=0; Domain=.linux.do; Path=/; Secure; HttpOnly"
                        )) {
                            suspendCancellableCoroutine<Unit> { cont ->
                                cm.setCookie(origin, spec) { if (cont.isActive) cont.resume(Unit) }
                            }
                        }
                    }
                    suspendCancellableCoroutine<Unit> { cont ->
                        cm.setCookie("https://linux.do", "_t=$winnerT; Path=/; Max-Age=5184000; Secure; HttpOnly; SameSite=Lax") {
                            if (cont.isActive) cont.resume(Unit)
                        }
                    }
                    cm.flush()
                } else if (tList.isEmpty()) {
                    val winning = CookieSentinel.resolveWinningCookies(targetHost = "linux.do")
                    val savedT = winning["_t"]?.value?.takeIf { it.isNotBlank() && it != "demo_session_token" }
                    if (savedT != null) {
                        suspendCancellableCoroutine<Unit> { cont ->
                            cm.setCookie("https://linux.do", "_t=$savedT; Path=/; Max-Age=5184000; Secure; HttpOnly; SameSite=Lax") {
                                if (cont.isActive) cont.resume(Unit)
                            }
                        }
                        winning["_forum_session"]?.value?.takeIf { it.isNotBlank() }?.let { fs ->
                            suspendCancellableCoroutine<Unit> { cont ->
                                cm.setCookie("https://linux.do", "_forum_session=$fs; Path=/; Secure; HttpOnly; SameSite=Lax") {
                                    if (cont.isActive) cont.resume(Unit)
                                }
                            }
                        }
                        cm.flush()
                    }
                }
            }
        }
        if (usedHandoffWebView) {
            // 已从静默验证原位交接同一 WebView 实例：零二次重载，直接探测并展示交互框！
            tryExtractSessionAndComplete(wv)
        } else {
            val needSinglePreloadPurge = do_.linux.lindo.core.network.ManualForceRefreshPolicy.shouldPurgeBeforeInitialLoad(
                forceRefresh = effectiveForceRefresh,
                alreadyPurgedInModal = staleClearancePurgedInModal,
                isVerificationFlow = isVerificationFlow
            ) || (isVerificationFlow && openedWasChallenged && !staleClearancePurgedInModal)
            if (needSinglePreloadPurge) {
                staleClearancePurgedInModal = true
                runCatching {
                    MainActivity.activeInstance?.purgeCfCookiesInWebKit(MainActivity.currentWebViewCookieManager())
                }
            }
            wv.loadUrl(initialUrl)
        }
    }

    // Network changes and slow rendering may offer a retry, but never navigate a visible page.
    LaunchedEffect(webViewRef, loginNavigationEpoch, isVerificationFlow, completed, challengeWidgetMounted,
        challengeStageMounted, challengeOrigin404Reached, pageLoadError) {
        if (isVerificationFlow && webViewRef != null && !completed && !challengeWidgetMounted &&
            !challengeStageMounted && !challengeOrigin404Reached && pageLoadError == null) {
            delay(12_000L)
            showVerifyChannelRepairHint = true
        }
    }

    // 仅在登录或验证流开启时轮询探测完成状态（验证流 320ms 敏捷检测，一旦真实页面渲染即秒级自动收起并恢复原帖）
    LaunchedEffect(webViewRef, completed, isLoginFlow, isVerificationFlow) {
        if (!isLoginFlow && !isVerificationFlow) return@LaunchedEffect
        val pollDelayMs = if (isVerificationFlow) 320L else 650L
        while (!completed && webViewRef != null) {
            delay(pollDelayMs)
            tryExtractSessionAndComplete(webViewRef)
        }
    }

    DisposableEffect(isVerificationFlow) {
        if (isVerificationFlow) NetworkRecoveryPolicy.androidVerifying = true
        onDispose {
            if (isVerificationFlow) {
                NetworkRecoveryPolicy.androidVerifying = false
                LinDoDiagnosticLogger.info(DiagnosticCategory.AUTH, "SiteVerify", "Verification window closed (session=$sessionNonce, completed=$completed)")
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (!isVerificationFlow) runCatching {
                val cm = MainActivity.currentWebViewCookieManager()
                val latest = sanitizeCookiesForSync(cm.getCookie("https://linux.do").orEmpty())
                if (latest.isNotBlank()) {
                    CookieSentinel.syncFromAuthoritativeBrowserCookieString(latest)
                }
                if (initialUrl.contains("linux.do", ignoreCase = true) && !csrfSyncedInModal && !completed) {
                    LinuxDoRepository.updateCsrfToken(null)
                }
            }
            webViewRef = null
        }
    }

    val barBg = when {
        isVerificationFlow && isDark -> androidx.compose.ui.graphics.Color(0xFF1A1E28)
        isDark -> androidx.compose.ui.graphics.Color(0xFF13161C)
        else -> androidx.compose.ui.graphics.Color(0xFFF4F6F8)
    }
    val barFg = if (isDark) androidx.compose.ui.graphics.Color(0xFFF3F4F6) else androidx.compose.ui.graphics.Color(0xFF161A22)
    val barSubFg = if (isDark) androidx.compose.ui.graphics.Color(0xFFA1A8B5) else androidx.compose.ui.graphics.Color(0xFF4F5665)
    val barBorder = if (isDark) androidx.compose.ui.graphics.Color(0xFF232833) else androidx.compose.ui.graphics.Color(0xFFD9DCE2)
    val currentPageTitle = do_.linux.lindo.core.network.webPageToolbarLabel(
        initialUrl, pendingUrl ?: initialUrl
    )

    do_.linux.lindo.ui.VerificationPageSurface(
        verification = isVerificationFlow,
        expanded = verificationExpanded,
        onDismiss = ::handleUserDismiss
    ) {
        Column(Modifier.fillMaxSize().background(barBg)) {
            if (isVerificationFlow) {
                // 紧凑无分割线「🛡️ 安全确认」顶栏（与 PC 端视觉严格一致）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .zIndex(1f)
                        .background(barBg)
                        .padding(start = 16.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = do_.linux.lindo.ui.components.LinDoIcons.ShieldCheck,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = do_.linux.lindo.ui.LocalPalette.current.brandGold
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = "安全确认",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = barFg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    androidx.compose.material3.IconButton(
                        onClick = ::retryPage,
                        modifier = Modifier.size(34.dp)
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = do_.linux.lindo.ui.components.LinDoIcons.Refresh,
                            contentDescription = LinDoAuthCopy.REFRESH_WEB_PAGE.text,
                            modifier = Modifier.size(15.dp),
                            tint = barSubFg
                        )
                    }
                    androidx.compose.material3.IconButton(
                        onClick = ::handleUserDismiss,
                        modifier = Modifier.size(34.dp)
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = do_.linux.lindo.ui.components.LinDoIcons.Close,
                            contentDescription = LinDoAuthCopy.CLOSE_WEB_PAGE.text,
                            modifier = Modifier.size(15.5.dp),
                            tint = barSubFg
                        )
                    }
                }
                if (showVerifyChannelRepairHint && !completed) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(barBorder.copy(alpha = 0.55f))
                            .clickable {
                                reloadVerificationPage()
                            }
                            .padding(horizontal = 16.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "页面加载较慢，可以继续等待或重新加载",
                            fontSize = 12.sp,
                            color = barSubFg,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "重新加载",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = do_.linux.lindo.ui.LocalPalette.current.brandGold,
                            maxLines = 1
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .zIndex(1f)
                        .background(barBg)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.IconButton(onClick = ::handleUserDismiss) {
                        androidx.compose.material3.Icon(
                            imageVector = do_.linux.lindo.ui.components.LinDoIcons.Close,
                            contentDescription = LinDoAuthCopy.CLOSE_WEB_PAGE.text,
                            modifier = Modifier.size(18.dp),
                            tint = barFg
                        )
                    }
                    Text(
                        text = currentPageTitle,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal,
                        color = barSubFg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    androidx.compose.material3.IconButton(onClick = ::retryPage) {
                        androidx.compose.material3.Icon(
                            imageVector = do_.linux.lindo.ui.components.LinDoIcons.Refresh,
                            contentDescription = LinDoAuthCopy.REFRESH_WEB_PAGE.text,
                            modifier = Modifier.size(16.5.dp),
                            tint = barSubFg
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .zIndex(1f)
                        .background(barBorder)
                )

                Box(Modifier.fillMaxWidth().height(2.dp)) {
                    if (pageProgress in 0..99 && pageLoadError == null) {
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { pageProgress / 100f }, modifier = Modifier.fillMaxSize(),
                            color = do_.linux.lindo.ui.LocalPalette.current.accent,
                            trackColor = androidx.compose.ui.graphics.Color.Transparent
                        )
                    }
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds(), contentAlignment = Alignment.Center) {
                if (!rendererGone) androidx.compose.runtime.key(modalWebViewGeneration) {
                    AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        // Explicit forceRefresh from Settings MUST NOT reuse a pre-purge handoff WebView
                        val handedOff = if (isVerificationFlow && !effectiveForceRefresh) MainActivity.takeHandoffVerifyWebView() else null
                        if (handedOff != null) {
                            usedHandoffWebView = true
                            challengeWidgetMounted = true
                            interactiveChallengeActive = true
                            pageProgress = 100
                        }
                        (handedOff ?: WebView(ctx)).apply {
                            if (handedOff == null) {
                                MainActivity.bindWebViewToActiveProfile(this)
                            }
                            isFocusable = true
                            isFocusableInTouchMode = true
                            alpha = 1f
                            setBackgroundColor(barBg.toArgb())
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            val cleanUa = settings.userAgentString
                            UserApiKeyAuthManager.customUserAgent = cleanUa

                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                // 保持 WebView 原生 User-Agent 与 Sec-CH-UA Client Hints 完全一致，不暴露任何 window.LinDoJsBridge 全局变量
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            }

                            val cookieManager = MainActivity.currentWebViewCookieManager()
                            cookieManager.setAcceptCookie(true)
                            cookieManager.setAcceptThirdPartyCookies(this, true)
                            if (isVerificationFlow &&
                                handedOff == null &&
                                androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)
                            ) {
                                runCatching {
                                    androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                                        this,
                                        buildVerificationCompactFrameJs(isDark),
                                        setOf("https://linux.do")
                                    )
                                }
                            }

                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    if (view === webViewRef) pageProgress = newProgress
                                }
                                override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                                    val msg = consoleMessage?.message().orEmpty()
                                    if (isVerificationFlow && msg == "__lindo_cf_widget_ready__" && viewOrSelfMatches()) {
                                        challengeWidgetMounted = true
                                        showVerifyChannelRepairHint = false
                                        verificationNavigatingAway = false
                                        interactiveChallengeActive = true
                                        return true
                                    }
                                    return super.onConsoleMessage(consoleMessage)
                                }
                                private fun viewOrSelfMatches(): Boolean = !completed && webViewRef != null
                                override fun onShowFileChooser(
                                    webView: WebView?,
                                    filePathCallback: ValueCallback<Array<Uri>>?,
                                    fileChooserParams: FileChooserParams?
                                ): Boolean {
                                    val handler = onLaunchFileChooser ?: return false
                                    return handler(filePathCallback, fileChooserParams)
                                }
                            }

                            setDownloadListener { url, _, _, _, _ ->
                                if (!url.isNullOrBlank()) {
                                    UserApiKeyAuthManager.openExternalUrlLauncher?.invoke(url)
                                }
                            }

                            webViewClient = object : WebViewClient() {
                                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                    if (webViewRef === view) {
                                        webViewRef = null
                                        rendererGone = true
                                        pageLoadError = LinDoAuthCopy.WEB_MODAL_CRASHED.text
                                    }
                                    releaseNativeWebView(view, rendererGone = true)
                                    return true
                                }
                                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                    super.onReceivedError(view, request, error)
                                    if (view === webViewRef && request?.isForMainFrame == true) {
                                        val code = error?.errorCode ?: WebViewClient.ERROR_UNKNOWN
                                        // 忽略 Cloudflare Turnstile 挑战表单提交重定向中断时偶发上报的 ERROR_UNKNOWN (-1)，防止误盖错误遮罩导致重复刷新
                                        if (isVerificationFlow && code == WebViewClient.ERROR_UNKNOWN && request.isRedirect) {
                                            return
                                        }
                                        val failedUrl = request.url.toString()
                                        if (failedUrl == pendingUrl || failedUrl == view?.url || request.isRedirect) {
                                            pendingUrl = failedUrl
                                            pageLoadError = LinDoCommonCopy.NETWORK_CONNECTION_FAILED.text
                                        }
                                    }
                                }

                                override fun onReceivedSslError(
                                    view: WebView?,
                                    handler: android.webkit.SslErrorHandler?,
                                    error: android.net.http.SslError?
                                ) {
                                    val host = error?.url?.let { runCatching { Uri.parse(it).host }.getOrNull() }
                                    if (do_.linux.lindo.ech.EchGatewayManager.verifyWebViewSslError(error, host)) {
                                        handler?.proceed()
                                    } else {
                                        handler?.cancel()
                                    }
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val target = request?.url?.toString() ?: return false
                                    if (target.startsWith("discourse://auth_redirect", ignoreCase = true) ||
                                        target.startsWith("lindo://auth_redirect", ignoreCase = true)
                                    ) {
                                        // The shared OTP collector is the sole consumer of this one-time token.
                                        if (!UserApiKeyAuthManager.handleAuthRedirectUri(target)) {
                                            pageLoadError = "授权已失效，请重新登录"
                                        }
                                        return true
                                    }
                                    val scheme = request.url?.scheme?.lowercase().orEmpty()
                                    if (scheme == "blob" || scheme == "data" || scheme == "about" || scheme == "javascript") {
                                        return false
                                    }
                                    if (scheme == "intent") {
                                        runCatching {
                                            val parsedIntent = Intent.parseUri(target, Intent.URI_INTENT_SCHEME).apply {
                                                addCategory(Intent.CATEGORY_BROWSABLE)
                                                component = null
                                                selector = null
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            val fallbackUrl = parsedIntent.getStringExtra("browser_fallback_url")
                                            try {
                                                view?.context?.startActivity(parsedIntent)
                                            } catch (_: android.content.ActivityNotFoundException) {
                                                if (!fallbackUrl.isNullOrBlank() &&
                                                    (fallbackUrl.startsWith("https://", ignoreCase = true) ||
                                                        fallbackUrl.startsWith("http://", ignoreCase = true))
                                                ) {
                                                    view?.loadUrl(fallbackUrl)
                                                }
                                            }
                                        }
                                        return true
                                    }
                                    if (scheme.isNotBlank() && scheme != "http" && scheme != "https") {
                                        UserApiKeyAuthManager.openExternalUrlLauncher?.invoke(target)
                                        return true
                                    }
                                    if (view === webViewRef && request.isForMainFrame && !request.isRedirect &&
                                        (scheme == "http" || scheme == "https")
                                    ) {
                                        pendingUrl = target
                                        pageLoadError = null
                                    }
                                    return false
                                }

                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    if (view === webViewRef) {
                                        pageProgress = 0
                                        loginDocumentReady = false
                                        loginNavigationEpoch++
                                        loginProbeRunning = false
                                        verificationProbeRunning = false
                                        confirmingLogin = false
                                        challengeOrigin404Reached = false
                                        forceRefreshCsrfStatus = null
                                        forceRefreshCsrfChallenged = false
                                        forceRefreshCsrfValue = null
                                        forceRefreshCsrfProbeInFlight = false
                                        if (isVerificationFlow) {
                                            if (interactiveChallengeActive) {
                                                verificationNavigatingAway = true
                                            }
                                            interactiveChallengeActive = false
                                            challengeWidgetMounted = false
                                            challengeStageMounted = false
                                            showVerifyChannelRepairHint = false
                                        }
                                        if (url != null &&
                                            (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true))
                                        ) {
                                            pendingUrl = url
                                        }
                                        pageLoadError = null
                                    }
                                }

                                override fun onReceivedHttpError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    errorResponse: android.webkit.WebResourceResponse?
                                ) {
                                    super.onReceivedHttpError(view, request, errorResponse)
                                    if (view === webViewRef && request?.isForMainFrame == true) {
                                        val status = errorResponse?.statusCode ?: 0
                                        if (status in setOf(502, 504)) {
                                            pendingUrl = request.url.toString()
                                            pageLoadError = LinDoCommonCopy.NETWORK_CONNECTION_FAILED.text
                                            return
                                        }
                                        if (isVerificationFlow &&
                                            request.url?.host.equals("linux.do", ignoreCase = true) &&
                                            request.url?.path?.startsWith("/challenge") == true &&
                                            status == 404 &&
                                            errorResponse?.responseHeaders?.entries?.none {
                                                it.key.equals("cf-mitigated", ignoreCase = true) &&
                                                    it.value.contains("challenge", ignoreCase = true)
                                            } != false
                                        ) {
                                            challengeOrigin404Reached = true
                                            interactiveChallengeActive = false
                                            val rawNow = runCatching {
                                                MainActivity.currentWebViewCookieManager().getCookie("https://linux.do").orEmpty()
                                            }.getOrDefault("")
                                            val latestCf = getCookieValue(rawNow, "cf_clearance")
                                            if (effectiveForceRefresh) {
                                                // Brand-new cf_clearance issued -> fast-pass immediately; otherwise let single /session/csrf.json probe decide (zero reload loop!)
                                                if (!latestCf.isNullOrBlank() && latestCf !in purgedStaleClearances) {
                                                    runCatching { view?.stopLoading() }
                                                    completeSiteVerification()
                                                }
                                            } else {
                                                val outcome = CfClearanceAuthority.evaluateVerifyOutcome(
                                                    freshClearance = latestCf,
                                                    previousClearance = openedClearance,
                                                    previousWasChallenged = openedWasChallenged
                                                )
                                                if (!latestCf.isNullOrBlank() &&
                                                    (outcome == do_.linux.lindo.core.network.VerifyTicketOutcome.ROTATED ||
                                                        outcome == do_.linux.lindo.core.network.VerifyTicketOutcome.EXISTING_VALID)
                                                ) {
                                                    // 新 cf_clearance 已落入 CookieMonster：收到非质询 404 响应头瞬间即终止 HTML 渲染并秒关弹窗 (<16ms)
                                                    runCatching { view?.stopLoading() }
                                                    completeSiteVerification()
                                                } else if (outcome == do_.linux.lindo.core.network.VerifyTicketOutcome.STALE_UNCHALLENGED_404 &&
                                                    !staleClearancePurgedInModal
                                                ) {
                                                    tryExtractSessionAndComplete(view, isPageFinished = true)
                                                }
                                            }
                                        }
                                    }
                                }

                                override fun onPageCommitVisible(view: WebView?, url: String?) {
                                    super.onPageCommitVisible(view, url)
                                    if (view === webViewRef) loginDocumentReady = true
                                    tryExtractSessionAndComplete(view)
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    if (view === webViewRef && url == view?.url) loginDocumentReady = true
                                    tryExtractSessionAndComplete(view, isPageFinished = true)
                                }
                            }

                            webViewRef = this
                        }
                    },
                    update = { view ->
                        view.importantForAccessibility = if (pageLoadError != null) {
                            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                        } else {
                            View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                        }
                    },
                    onRelease = { view ->
                        if (webViewRef === view) webViewRef = null
                        if (!rendererGone) releaseNativeWebView(view)
                    }
                )
                }
                if ((confirmingLogin || (isVerificationFlow && (completed || verificationNavigatingAway || (!interactiveChallengeActive && !showVerifyChannelRepairHint)))) && pageLoadError == null) {
                    Box(Modifier.fillMaxSize().background(barBg).pointerInput(Unit) {
                        awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
                    }, contentAlignment = Alignment.Center) {
                        LinDoLoadingIndicator(
                            label = when {
                                isVerificationFlow && (completed || verificationNavigatingAway) -> "安全确认已完成，正在恢复…"
                                isVerificationFlow -> "正在加载安全确认…"
                                else -> "正在完成登录…"
                            }
                        )
                    }
                }
                val loadError = pageLoadError
                if (loadError != null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Box(Modifier.matchParentSize().background(barBg).pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        })
                        CompositionLocalProvider(LocalLinDoDarkTheme provides isDark) {
                            if (isLoginFlow) {
                                LinDoMascotLoginTransitionView(
                                    isDark = isDark, progress = Float.NaN, statusTitle = loadError,
                                    onCancel = onDismiss, onRetry = ::retryPage
                                )
                            } else {
                                LinDoStatePanel(
                                    state = LinDoMascotState.NetworkError, detail = loadError, onRetry = ::retryPage
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun decodeJsStringResult(raw: String?): String? {
    if (raw.isNullOrBlank() || raw == "null") return null
    return runCatching {
        JSONObject("{\"v\":$raw}").optString("v")
    }.getOrNull()
}
