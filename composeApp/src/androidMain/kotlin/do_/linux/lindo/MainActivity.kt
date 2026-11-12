package do_.linux.lindo

import coil3.disk.directory

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
import do_.linux.lindo.core.network.CookieSentinel
import do_.linux.lindo.core.network.LinuxDoRepository
import do_.linux.lindo.core.network.PlatformMediaPicker
import do_.linux.lindo.core.network.UserApiKeyAuthManager
import do_.linux.lindo.core.site.LinuxDoSiteRules
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
import do_.linux.lindo.core.connect.UserPresenceService
import do_.linux.lindo.ui.PlatformWebLoginHost
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
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

private fun getCookieValue(rawCookieHeader: String?, targetName: String): String? {
    if (rawCookieHeader.isNullOrBlank()) return null
    rawCookieHeader.split(';').forEach { segment ->
        val trimmed = segment.trim()
        val eq = trimmed.indexOf('=')
        if (eq > 0) {
            val name = trimmed.substring(0, eq).trim()
            if (name == targetName) {
                return trimmed.substring(eq + 1).trim().takeIf { it.isNotEmpty() }
            }
        }
    }
    return null
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
        private const val BRIDGE_BOOTSTRAP_URL = "https://linux.do/"

        @Volatile
        private var activeInstance: MainActivity? = null
    }

    private var debugPreviewGeneration by mutableStateOf(0)

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val iconManager by lazy { AndroidAppIconManager(this) }

    private val prefs by lazy {
        KeystorePreferences(this)
    }

    // 常驻同源 Chromium WebView 引擎：共享 BoringSSL JA3/JA4 指纹、cf_clearance、_t 与 CSRF Token
    @Volatile
    private var apiWebView: WebView? = null
    @Volatile
    private var apiWebViewGeneration: Long = 0L
    private var apiWebViewHost: android.widget.FrameLayout? = null
    @Volatile
    private var apiWebViewReady = CompletableDeferred<Boolean>()
    private val rebuildApiWebViewMutex = kotlinx.coroutines.sync.Mutex()
    private val consecutiveBridgeFailures = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile
    private var lastApiWebViewRebuildAtMs: Long = 0L
    @Volatile
    private var pendingTransportRecycleOnNextUse: Boolean = false
    private val pendingHttpCallbacks = ConcurrentHashMap<String, CompletableDeferred<Pair<Int, String>>>()
    private val pendingBinaryCallbacks = ConcurrentHashMap<String, CompletableDeferred<Triple<Int, String, String>>>()
    private val pendingUploadPayloads = ConcurrentHashMap<String, ByteArray>()
    // 控制同源 Chromium WebView 并发槽位：
    // - 前台交互请求专享 6 路并发槽位，后台静默预取/曝光上报隔离在独立 2 路槽位，彻底消除后台任务队头阻塞前台刷帖/看帖；
    // - 12 路 HTTP/2 多路复用图片桥接兜底（头像已优先直连官方静态 CDN）。
    private val webViewForegroundHttpSemaphore = kotlinx.coroutines.sync.Semaphore(6)
    private val webViewBackgroundHttpSemaphore = kotlinx.coroutines.sync.Semaphore(2)
    private val webViewBinarySemaphore = kotlinx.coroutines.sync.Semaphore(12)
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
    private var lastPausedAtEpochMs: Long = 0L
    private var pendingMediaPickCallback: ((fileName: String, mimeType: String, bytes: ByteArray) -> Unit)? = null
    private var pendingWebFileChooserCallback: ValueCallback<Array<Uri>>? = null

    private val webFileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = pendingWebFileChooserCallback
        pendingWebFileChooserCallback = null
        if (callback == null) return@registerForActivityResult
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            ?: result.data?.clipData?.let { clip ->
                Array(clip.itemCount) { idx -> clip.getItemAt(idx).uri }
            }
            ?: result.data?.data?.let { arrayOf(it) }
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
            val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            webFileChooserLauncher.launch(intent)
            true
        }.getOrElse {
            pendingWebFileChooserCallback = null
            filePathCallback.onReceiveValue(null)
            false
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
                            "附件超过 20MB 上限，请压缩后再上传",
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
                            "无法读取所选附件，请重试",
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
            val taggedBody = if (cfMitigated.contains("challenge", ignoreCase = true)) {
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
                runCatching { CookieManager.getInstance().flush() }
            } else {
                LinuxDoRepository.updateCsrfToken(null)
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
                        val rawCookies = CookieManager.getInstance().getCookie("https://linux.do").orEmpty()
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
        activeInstance = this
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
        requestHighRefreshRateDisplay()
        var appIcon by mutableStateOf(iconManager.initialIcon())
        activityScope.launch { iconManager.finishRestart()?.let { appIcon = it } }

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
        restoreSessionFromDisk()
        do_.linux.lindo.core.compose.LocalComposerDrafts.restore(prefs.getString("composer_drafts", null))
        do_.linux.lindo.core.compose.LocalComposerDrafts.onPersist = { encoded -> prefs.edit().putString("composer_drafts", encoded).apply() }

        // Share the Android WebView session over the system network.
        initPersistentApiWebView()

        // 3. 注册系统浏览器跳转桥接器
        UserApiKeyAuthManager.openExternalUrlLauncher = { url: String ->
            runCatching {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(browserIntent)
            }
        }

        // 4. 注册会话变更与 UI 偏好持久化钩子
        UserApiKeyAuthManager.onPersistSession = { _, _ ->
            persistSessionToDisk()
        }
        UserApiKeyAuthManager.onPersistUiPrefs = { fontScale, darkTheme, enableAnimations ->
            runCatching {
                prefs.edit()
                    .putFloat("ui_font_scale_v2", fontScale)
                    .putBoolean("ui_dark_theme", darkTheme)
                    .putBoolean("ui_enable_animations", enableAnimations)
                    .apply()
            }
        }
        UserApiKeyAuthManager.onPersistThemeAndDensity = { themeAccent, compactFeed ->
            runCatching {
                prefs.edit()
                    .putString("ui_theme_accent", themeAccent)
                    .putBoolean("ui_compact_feed", compactFeed)
                    .apply()
            }
        }
        UserApiKeyAuthManager.onPersistSplitFraction = { splitFraction ->
            runCatching {
                prefs.edit()
                    .putFloat("ui_split_fraction", splitFraction)
                    .apply()
            }
        }
        registerNetworkRecoveryMonitor()
        UserApiKeyAuthManager.onPrepareManualRecovery = {
            ensureHealthyApiWebView(forceCheck = true)
        }
        UserApiKeyAuthManager.onPersistReadTopics = { encoded ->
            runCatching {
                prefs.edit().putString("read_topic_posts", encoded).apply()
            }
        }
        UserApiKeyAuthManager.onSyncCookieToPlatform = { rawSetCookie ->
            runCatching {
                val cm = CookieManager.getInstance()
                cm.setCookie("https://linux.do", rawSetCookie)
                cm.flush()
            }
        }
        UserApiKeyAuthManager.onLogoutPlatformClear = {
            runCatching {
                // 仅清除 Discourse 账号会话 Cookie (_t / _forum_session)，保留 Cloudflare 信任通行证 (cf_clearance / __cf_bm / _cfuvid)
                val cm = CookieManager.getInstance()
                listOf("_t", "_forum_session").forEach { name ->
                    cm.setCookie("https://linux.do", "$name=; Max-Age=0; Path=/; Secure; HttpOnly")
                    cm.setCookie("https://linux.do", "$name=; Max-Age=0; Domain=.linux.do; Path=/; Secure; HttpOnly")
                    cm.setCookie("https://linux.do", "$name=; Max-Age=0; Domain=linux.do; Path=/; Secure; HttpOnly")
                }
                cm.flush()
                runOnUiThread {
                    apiWebView?.evaluateJavascript(
                        "window.__lindoCsrf = null; window.__lindoCsrfPromise = null; window.__lindoCsrfCookieOwner = null;",
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
                    remove("trust_level")
                    remove("read_topic_posts")
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
            AndroidWebLoginModal(
                initialUrl = initialUrl,
                onDismiss = {
                    // 关闭内置网页（如完成站点验证后点关闭）前同步刷盘最新 cf_clearance / 会话 Cookie，确保紧随其后的自动重刷立即生效
                    runCatching {
                        val cm = CookieManager.getInstance()
                        cm.flush()
                        val latestCookies = cm.getCookie("https://linux.do").orEmpty()
                        if (latestCookies.isNotBlank()) {
                            CookieSentinel.syncFromAuthoritativeBrowserCookieString(latestCookies)
                        }
                        persistSessionToDisk()
                    }
                    onDismiss()
                },
                onLoginSuccess = {
                    // 同步最新 Cookie 至内存守卫（严禁调用 apiWebView.reload() 整页导航，避免后台视口触发 Cloudflare 风控）
                    val latestCookies = CookieManager.getInstance().getCookie("https://linux.do").orEmpty()
                    if (latestCookies.isNotBlank()) {
                        CookieSentinel.syncFromAuthoritativeBrowserCookieString(latestCookies)
                    }
                    persistSessionToDisk()
                    onLoginSuccess()
                },
                onLaunchFileChooser = ::launchWebFileChooser
            )
        }

        // 6. 注册异步 Promise 级 WebView HTTP 执行器与 JSON 兜底器 + 媒体文件选择器与 Multipart 上传器
        UserApiKeyAuthManager.webViewHttpExecutor = { url, method, formParams, headers ->
            executeHttpViaWebViewBridge(url, method, formParams, headers)
        }
        UserApiKeyAuthManager.webViewJsonFetcher = { targetUrl ->
            executeHttpViaWebViewBridge(targetUrl, "GET", null, emptyMap())?.second
        }
        UserApiKeyAuthManager.webViewBinaryFetcher = { targetUrl ->
            fetchBinaryViaWebViewBridge(targetUrl)
        }
        PlatformMediaPicker.launchPicker = { mimeType, onPicked ->
            pendingMediaPickCallback = onPicked
            runCatching {
                mediaPickerLauncher.launch(mimeType.ifBlank { "*/*" })
            }
        }
        PlatformMediaPicker.webViewBinaryUploadExecutor = { fileName, mimeType, rawBytes ->
            executeMultipartUploadViaWebViewBridge(fileName, mimeType, rawBytes)
        }
        PlatformMediaPicker.webViewUploadExecutor = { fileName, mimeType, base64Bytes ->
            executeMultipartUploadViaWebViewBridge(fileName, mimeType, base64Bytes)
        }

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
            val debugPreview = if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)
                currentIntent.getStringExtra("lindo.preview") else null
            val closePreview: () -> Unit = {
                intent.removeExtra("lindo.preview")
                intent.removeExtra("lindo.brand_preview")
                debugPreviewGeneration++
            }
            if (debugPreview == "icon") {
                InstalledIconPreview(iconManager.component(appIcon))
            } else if (debugPreview == "welcome") {
                var dark by remember { mutableStateOf(false) }
                LaunchedEffect(dark) { updateSystemBars(dark) }
                LinDoWelcomeScreen(isDark = dark, onPrimaryWebLogin = closePreview,
                    onBrowserOneClickAuth = closePreview, onToggleDark = { dark = !dark },
                    onOpenAppIcon = closePreview, onGuestBrowse = closePreview)
            } else if (debugPreview in listOf("rich", "search", "bookmarks", "notifications", "drafts", "history", "activity", "messages")) {
                var openedLink by remember { mutableStateOf<String?>(null) }
                val cooked = remember { java.io.File(filesDir, "lindo-rich-preview.html").takeIf { it.isFile && it.length() < 262144 }?.readText().orEmpty() }
                if (openedLink != null) AndroidWebLoginModal(openedLink!!, { openedLink = null }, { openedLink = null }, ::launchWebFileChooser)
                else LinDoReadRegressionPreview(debugPreview!!, cooked, closePreview, { openedLink = it })
            } else if (debugPreview in listOf("web", "web_crash", "connect")) {
                AndroidWebLoginModal(initialUrl = when (debugPreview) {
                    "web_crash" -> "chrome://crash"
                    "connect" -> "https://connect.linux.do/"
                    else -> "https://linux.do/"
                },
                    onDismiss = closePreview, onLoginSuccess = closePreview, onLaunchFileChooser = ::launchWebFileChooser)
            } else if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
                currentIntent.getBooleanExtra("lindo.brand_preview", false)) {
                LinDoMascotGallery(onClose = {
                    closePreview()
                }, onThemeChanged = updateSystemBars, systemAnimationsEnabled = systemAnimationsEnabled)
            } else {
                LinDoApp(
                    onThemeChanged = updateSystemBars,
                    systemAnimationsEnabled = systemAnimationsEnabled,
                    appIcon = appIcon,
                    onApplyAppIcon = { iconManager.apply(appIcon, it) },
                    imageCacheDir = java.io.File(cacheDir, "lindo_image_cache"),
                    addPlatformImageDecoders = {
                        if (android.os.Build.VERSION.SDK_INT >= 28) add(coil3.gif.AnimatedImageDecoder.Factory())
                        else add(coil3.gif.GifDecoder.Factory())
                    }
                )
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
                    runCatching { CookieManager.getInstance().getCookie("https://linux.do").orEmpty() }.getOrDefault("")
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
                    runCatching { CookieManager.getInstance().getCookie("https://linux.do").orEmpty() }.getOrDefault("")
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
                apiWebView != null &&
                apiWebViewReady.isCompleted &&
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
            android.util.Log.i("LinDoNetwork", "Rebuilding persistent API WebView ($reason)")
            withContext(Dispatchers.Main) {
                runCatching {
                    val cm = CookieManager.getInstance()
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
                pendingBinaryCallbacks.values.forEach { it.complete(Triple(REBUILD_ABORTED_STATUS, "", "")) }
                pendingBinaryCallbacks.clear()
                if (old != null) {
                    runCatching { releaseNativeWebView(old) }
                }
                apiWebViewReady = CompletableDeferred()
                initPersistentApiWebView()
            }
            withTimeoutOrNull(4_500L) { apiWebViewReady.await() } == true
        }
    }

    private suspend fun ensureHealthyApiWebView(forceCheck: Boolean = false) {
        if (isFinishing || isDestroyed) return
        val observedGen = apiWebViewGeneration
        val current = apiWebView
        if (current == null || pendingTransportRecycleOnNextUse || consecutiveBridgeFailures.get() >= 1) {
            val forceRecycle = pendingTransportRecycleOnNextUse
            rebuildApiWebView(
                reason = when {
                    current == null -> "missing_webview"
                    forceRecycle -> "network_changed_recycle"
                    else -> "prior_bridge_failure"
                },
                force = forceRecycle
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
                    live.evaluateJavascript("1") { res ->
                        pingDeferred.complete(res != null)
                    }
                }
            }.onFailure {
                pingDeferred.complete(false)
            }
        }
        val alive = withTimeoutOrNull(900L) { pingDeferred.await() } == true
        if (!alive) {
            rebuildApiWebView(reason = "js_liveness_timeout", force = false, expectedGeneration = observedGen)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initPersistentApiWebView() {
        if (apiWebView != null) return
        activityScope.launch(Dispatchers.IO) {
            runCatching {
                val legacyEch = java.io.File(filesDir, "ech")
                if (legacyEch.exists()) legacyEch.deleteRecursively()
            }
        }
        runCatching {
            val wv = WebView(this)
            // 严禁手动删改 "; wv" 或覆盖 userAgentString：
            // 保持所有 WebView 实例的 User-Agent 与底层 Sec-CH-UA Client Hints 100% 原生一致，确保 cf_clearance 签名永不因 UA 差异失效
            val nativeUa = wv.settings.userAgentString
            UserApiKeyAuthManager.customUserAgent = nativeUa
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            }
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(wv, true)

            val bootstrapIntercepted = java.util.concurrent.atomic.AtomicBoolean(false)
            wv.addJavascriptInterface(LinDoJsBridge(), "LinDoJsBridge")
            wv.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): android.webkit.WebResourceResponse? {
                    val reqUrl = request?.url
                    val encodedPath = reqUrl?.encodedPath
                    if (request != null &&
                        request.isForMainFrame &&
                        reqUrl?.scheme.equals("https", ignoreCase = true) &&
                        reqUrl?.host.equals("linux.do", ignoreCase = true) &&
                        (encodedPath.isNullOrEmpty() || encodedPath == "/") &&
                        bootstrapIntercepted.compareAndSet(false, true)
                    ) {
                        return android.webkit.WebResourceResponse(
                            "text/html",
                            "UTF-8",
                            200,
                            "OK",
                            mapOf("Cache-Control" to "no-store"),
                            "<!DOCTYPE html><html><head><meta charset=\"utf-8\"></head><body></body></html>"
                                .byteInputStream(Charsets.UTF_8)
                        )
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    if (apiWebView !== view) {
                        runCatching { releaseNativeWebView(view, rendererGone = true) }
                        return true
                    }
                    apiWebView = null
                    pendingHttpCallbacks.values.forEach { it.complete(REBUILD_ABORTED_STATUS to REBUILD_ABORTED_BODY) }
                    pendingHttpCallbacks.clear()
                    pendingBinaryCallbacks.values.forEach { it.complete(Triple(REBUILD_ABORTED_STATUS, "", "")) }
                    pendingBinaryCallbacks.clear()
                    releaseNativeWebView(view, rendererGone = true)
                    apiWebViewReady = CompletableDeferred()
                    activityScope.launch {
                        delay(200L)
                        if (apiWebView == null && !isFinishing && !isDestroyed) {
                            initPersistentApiWebView()
                        }
                    }
                    return true
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (view != null && view !== apiWebView) return
                    val cookies = cm.getCookie("https://linux.do").orEmpty()
                    if (cookies.isNotBlank()) {
                        CookieSentinel.syncFromAuthoritativeBrowserCookieString(cookies)
                    }
                    view?.evaluateJavascript(installBinaryBridgeHelperJs, null)
                    if (!apiWebViewReady.isCompleted) {
                        apiWebViewReady.complete(true)
                    }
                    // 若当前已存在 `_t` 登录会话，在同源 WebView 就绪后静默预热单飞 CSRF Token，
                    // 避免进入帖子时高频 timings POST 与点赞 PUT 并发争抢首次 /session/csrf.json
                    if (hasCookieNamed(cookies, "_t")) {
                        val seeded = LinuxDoRepository.getCachedCsrfToken().orEmpty()
                        val ownerTag = csrfOwnerTag(cookies)
                        view?.evaluateJavascript(
                            """
                            (function() {
                                var owner = ${JSONObject.quote(ownerTag)};
                                var ownerChanged = false;
                                if (window.__lindoCsrfCookieOwner !== owner) {
                                    ownerChanged = true;
                                    window.__lindoCsrf = null;
                                    window.__lindoCsrfPromise = null;
                                    window.__lindoCsrfCookieOwner = owner;
                                }
                                var seeded = ${JSONObject.quote(seeded)};
                                if (seeded && !window.__lindoCsrf && !ownerChanged) {
                                    window.__lindoCsrf = seeded;
                                }
                                if (!window.__lindoCsrf && !window.__lindoCsrfPromise) {
                                    var csrfInit = {
                                        headers: { 'Accept': 'application/json, text/javascript, */*; q=0.01', 'X-Requested-With': 'XMLHttpRequest' },
                                        credentials: 'include',
                                        cache: 'no-cache',
                                        referrerPolicy: 'strict-origin-when-cross-origin'
                                    };
                                    if (window.location.origin === 'https://linux.do') {
                                        csrfInit.referrer = 'https://linux.do/';
                                    }
                                    window.__lindoCsrfPromise = fetch('https://linux.do/session/csrf.json', csrfInit).then(function(r) { return r.ok ? r.json() : null; }).then(function(d) {
                                        if (d && d.csrf) {
                                            window.__lindoCsrf = d.csrf;
                                            if (window.LinDoJsBridge && window.LinDoJsBridge.onCsrfTokenUpdated) {
                                                window.LinDoJsBridge.onCsrfTokenUpdated(d.csrf);
                                            }
                                            return d.csrf;
                                        }
                                        return null;
                                    }).catch(function() { return null; }).finally(function() {
                                        window.__lindoCsrfPromise = null;
                                    });
                                }
                            })();
                            """.trimIndent(),
                            null
                        )
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
            // 通过单次本地拦截的真实 HTTPS 同源根路径初始化 https://linux.do/ 上下文（零启动网络请求）：
            // 相比 loadDataWithBaseURL（内部保留 data: 文档 URL 导致 Chromium 剥离 fetch Referer 标头、在 5G/VPN 下 POST /drafts.json 与 /posts.json 易触发 Cloudflare WAF），
            // 真实提交 https://linux.do/ 使 window.location、Origin、Referer 与 Sec-Fetch-Site 100% 对齐官方 Ember SPA！
            wv.loadUrl(BRIDGE_BOOTSTRAP_URL)
        }.onFailure {
            apiWebView = null
            if (!apiWebViewReady.isCompleted) {
                apiWebViewReady.complete(false)
            }
            android.util.Log.w("LinDoNetwork", "Could not initialize API WebView", it)
        }
    }

    private suspend fun executeHttpViaWebViewBridge(
        url: String,
        method: String,
        formParams: Map<String, String>?,
        headers: Map<String, String>
    ): Pair<Int, String>? {
        val isRead = method.equals("GET", true) || method.equals("HEAD", true)
        if (pendingTransportRecycleOnNextUse && !isFinishing && !isDestroyed) {
            rebuildApiWebView(reason = "pending_network_recycle", force = true)
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
        val isReady = withTimeoutOrNull(4500L) { apiWebViewReady.await() }
        if (isReady != true) {
            if (!isFinishing && !isDestroyed) {
                rebuildApiWebView(reason = "ready_await_timeout", expectedGeneration = preWaitGen)
            }
            return null
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

        val preExecCookies = runCatching {
            val cm = CookieManager.getInstance()
            ensureAuthCookiesInWebKit(cm, cm.getCookie("https://linux.do").orEmpty())
        }.getOrNull().orEmpty()
        val hasBrowserSessionCookie = hasCookieNamed(preExecCookies, "_t")
        val preferredReferer = headers.entries.firstOrNull {
            it.key.equals("Referer", ignoreCase = true)
        }?.value?.takeIf { it.startsWith("https://linux.do/") } ?: "https://linux.do/"

        // 过滤浏览器禁止脚本篡改的受保护头（Origin / Referer / Sec-Fetch-* / Cookie / User-Agent）；
        // 仅在浏览器确实持有有效 `_t` 会话 Cookie 时保留 Discourse-Logged-In: true（对齐官方 Ember SPA 标头组合）
        val forbiddenJsHeaders = setOf(
            "origin", "referer", "sec-fetch-dest", "sec-fetch-mode", "sec-fetch-site",
            "cookie", "user-agent"
        )
        val headersJson = JSONObject().apply {
            headers.forEach { (k, v) ->
                val lower = k.lowercase()
                if (lower !in forbiddenJsHeaders && (lower != "discourse-logged-in" || hasBrowserSessionCookie)) {
                    put(k, v)
                }
            }
        }.toString()
        val initialCachedCsrf = LinuxDoRepository.getCachedCsrfToken().orEmpty()
        val currentCsrfOwner = csrfOwnerTag(preExecCookies)
        val isBackground = headers.entries.any {
            it.key.equals("Discourse-Background", ignoreCase = true) && it.value.equals("true", ignoreCase = true)
        }
        val reqIdQuoted = JSONObject.quote(reqId)

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
                const method = ${JSONObject.quote(method.uppercase())};
                const isRead = method === 'GET' || method === 'HEAD';
                let cancelledByKotlin = false;
                let currentAttemptCtrl = null;
                window.__lindoActiveHttp[reqKey] = function() {
                    cancelledByKotlin = true;
                    if (currentAttemptCtrl) {
                        try { currentAttemptCtrl.abort(); } catch (_) {}
                    }
                };
                function reportHttp(status, body, cfMitigated) {
                    if (cancelledByKotlin) return;
                    if (window.LinDoJsBridge && window.LinDoJsBridge.onHttpResultWithMeta) {
                        window.LinDoJsBridge.onHttpResultWithMeta(reqKey, status, body || '', cfMitigated || '');
                    } else if (window.LinDoJsBridge) {
                        window.LinDoJsBridge.onHttpResult(reqKey, status, body || '');
                    }
                }
                async function fetchTextWithTimeout(inputUrl, baseOpts, timeoutMs) {
                    if (cancelledByKotlin) throw new Error('Cancelled');
                    const attemptCtrl = new AbortController();
                    currentAttemptCtrl = attemptCtrl;
                    const attemptTimer = setTimeout(() => {
                        try { attemptCtrl.abort(); } catch (_) {}
                    }, timeoutMs);
                    try {
                        const r = await fetch(inputUrl, Object.assign({}, baseOpts, { signal: attemptCtrl.signal }));
                        const bodyText = await r.text();
                        return { resp: r, text: bodyText };
                    } finally {
                        clearTimeout(attemptTimer);
                        if (currentAttemptCtrl === attemptCtrl) {
                            currentAttemptCtrl = null;
                        }
                    }
                }
                try {
                    const targetUrl = ${JSONObject.quote(url)};
                    const hdrs = $headersJson;
                    if (!hdrs['Accept'] && !hdrs['accept']) {
                        hdrs['Accept'] = 'application/json, text/javascript, */*; q=0.01';
                    }
                    if (!hdrs['X-Requested-With'] && !hdrs['x-requested-with']) {
                        hdrs['X-Requested-With'] = 'XMLHttpRequest';
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
                    let csrfLastStatus = 0;
                    let csrfLastBody = '';
                    let csrfLastMitigated = '';
                    async function refreshCsrfToken(force) {
                        if (!force && window.__lindoCsrf) {
                            return window.__lindoCsrf;
                        }
                        if (window.__lindoCsrfPromise) {
                            try {
                                const shared = await window.__lindoCsrfPromise;
                                if (shared) return shared;
                            } catch (_) {}
                        }
                        window.__lindoCsrfPromise = (async () => {
                            for (let attempt = 0; attempt < 2; attempt++) {
                                if (cancelledByKotlin) break;
                                try {
                                    const csrfOpts = {
                                        headers: {
                                            'Accept': 'application/json, text/javascript, */*; q=0.01',
                                            'X-Requested-With': 'XMLHttpRequest'
                                        },
                                        credentials: 'include',
                                        cache: 'no-cache',
                                        referrerPolicy: 'strict-origin-when-cross-origin'
                                    };
                                    if (window.location.origin === 'https://linux.do') {
                                        csrfOpts.referrer = 'https://linux.do/';
                                    }
                                    const csrfOut = await fetchTextWithTimeout('https://linux.do/session/csrf.json', csrfOpts, 7500);
                                    const csrfResp = csrfOut.resp;
                                    csrfLastStatus = csrfResp.status || 0;
                                    csrfLastMitigated = csrfResp.headers.get('cf-mitigated') || '';
                                    if (csrfResp.ok && !csrfLastMitigated) {
                                        const csrfData = JSON.parse(csrfOut.text || '{}');
                                        if (csrfData && csrfData.csrf) {
                                            window.__lindoCsrf = csrfData.csrf;
                                            if (window.LinDoJsBridge && window.LinDoJsBridge.onCsrfTokenUpdated) {
                                                window.LinDoJsBridge.onCsrfTokenUpdated(csrfData.csrf);
                                            }
                                            return csrfData.csrf;
                                        }
                                    } else {
                                        csrfLastBody = csrfOut.text || '';
                                        if (csrfLastMitigated || csrfResp.status === 429 || csrfResp.status === 401 || csrfResp.status === 403 || csrfResp.status === 503) {
                                            break;
                                        }
                                    }
                                } catch (_) {}
                                if (attempt === 0 && !cancelledByKotlin) {
                                    await new Promise(r => setTimeout(r, 220));
                                }
                            }
                            return null;
                        })();
                        try {
                            return await window.__lindoCsrfPromise;
                        } finally {
                            window.__lindoCsrfPromise = null;
                        }
                    }
                    if (!isRead) {
                        const metaCsrf = document.querySelector('meta[name="csrf-token"]');
                        const cachedCsrf = window.__lindoCsrf || (metaCsrf && metaCsrf.content);
                        const token = cachedCsrf || (await refreshCsrfToken(false));
                        if (!token) {
                            const body = csrfLastBody || JSON.stringify({errors: ['暂时无法验证会话，请检查网络或登录状态']});
                            reportHttp(csrfLastStatus || 0, body, csrfLastMitigated);
                            return;
                        }
                        hdrs['X-CSRF-Token'] = token;
                    }
                    const bodyStr = ${JSONObject.quote(formBodyEncoded)};
                    const hasBody = !isRead && (bodyStr.length > 0 || method === 'POST');
                    if (hasBody) {
                        hdrs['Content-Type'] = 'application/x-www-form-urlencoded; charset=UTF-8';
                    }
                    const mustNoStore = isRead && /\/session\/(csrf|current)\.json|\/notifications|\/drafts/i.test(targetUrl);
                    const targetReferer = ${JSONObject.quote(preferredReferer)};
                    function buildBaseOpts() {
                        const o = {
                            method: method,
                            headers: Object.assign({}, hdrs),
                            credentials: 'include',
                            cache: mustNoStore ? 'no-store' : 'default',
                            referrerPolicy: 'strict-origin-when-cross-origin'
                        };
                        if (window.location.origin === 'https://linux.do') {
                            o.referrer = targetReferer;
                        }
                        if (hasBody) {
                            o.body = bodyStr;
                        }
                        return o;
                    }
                    const isBg = hdrs['Discourse-Background'] === 'true' || hdrs['discourse-background'] === 'true';
                    // 单层重试策略：每次桥接调用仅发起 1 次有界超时 fetch，由外层 Kotlin executeHttp 在检测到故障后配合 WebView 自愈再重试，
                    // 彻底消除「JS 2 次 × Kotlin 2 次 = 4 倍风暴重试」及 50s 超时击穿外层 34s 阈值的问题！
                    const perAttemptTimeoutMs = isBg ? 7500 : (!isRead ? 15000 : 10000);
                    const out = await fetchTextWithTimeout(targetUrl, buildBaseOpts(), perAttemptTimeoutMs);
                    let resp = out.resp;
                    let text = out.text;
                    let cfMitigated = (resp && resp.headers && resp.headers.get('cf-mitigated')) || '';
                    if (!isRead && !cfMitigated && (resp.status === 403 || resp.status === 422) && /csrf|invalid authenticity token|bad csrf/i.test(text)) {
                        window.__lindoCsrf = null;
                        if (window.LinDoJsBridge && window.LinDoJsBridge.onCsrfTokenUpdated) {
                            window.LinDoJsBridge.onCsrfTokenUpdated('');
                        }
                        const freshToken = await refreshCsrfToken(true);
                        if (freshToken) {
                            hdrs['X-CSRF-Token'] = freshToken;
                            const retryOut = await fetchTextWithTimeout(targetUrl, buildBaseOpts(), perAttemptTimeoutMs);
                            resp = retryOut.resp;
                            text = retryOut.text;
                            cfMitigated = (resp && resp.headers && resp.headers.get('cf-mitigated')) || '';
                        } else if (csrfLastMitigated || csrfLastStatus === 403 || csrfLastStatus === 503 || csrfLastStatus === 429) {
                            reportHttp(csrfLastStatus || resp.status, csrfLastBody || text, csrfLastMitigated);
                            return;
                        }
                    }
                    reportHttp(resp.status, text, cfMitigated);
                } catch (err) {
                    reportHttp(0, '', '');
                } finally {
                    if (window.__lindoActiveHttp) {
                        delete window.__lindoActiveHttp[reqKey];
                    }
                }
            })();
        """.trimIndent()

        val httpSemaphore = if (isBackground) {
            webViewBackgroundHttpSemaphore
        } else {
            webViewForegroundHttpSemaphore
        }
        val bridgeTimeoutMs = when {
            isBackground -> 9_000L
            !isRead -> 18_500L
            else -> 12_000L
        }
        httpSemaphore.acquire()
        var completedFromJs = false
        var bridgeResult: Pair<Int, String>? = null
        var dispatchedWv: WebView? = null
        var observedGen = apiWebViewGeneration
        try {
            if (apiWebView == null && !isFinishing && !isDestroyed) {
                withContext(Dispatchers.Main) {
                    if (apiWebView == null && !isFinishing && !isDestroyed) {
                        initPersistentApiWebView()
                    }
                }
            }
            if (!apiWebViewReady.isCompleted) {
                val postAcquireGen = apiWebViewGeneration
                val readyAfterAcquire = withTimeoutOrNull(4500L) { apiWebViewReady.await() }
                if (readyAfterAcquire != true) {
                    if (!isFinishing && !isDestroyed) {
                        rebuildApiWebView(reason = "ready_await_timeout", expectedGeneration = postAcquireGen)
                    }
                    return null
                }
            }
            pendingHttpCallbacks[reqId] = deferred
            val launched = withContext(Dispatchers.Main) {
                val liveWv = apiWebView ?: return@withContext false
                dispatchedWv = liveWv
                observedGen = apiWebViewGeneration
                attachApiWebViewInTinyHost(liveWv)
                if (UserPresenceService.isForeground) {
                    liveWv.onResume()
                    liveWv.resumeTimers()
                }
                val cm = CookieManager.getInstance()
                val cookieStr = ensureAuthCookiesInWebKit(cm, cm.getCookie("https://linux.do").orEmpty())
                if (cookieStr.isNotBlank()) {
                    CookieSentinel.syncFromAuthoritativeBrowserCookieString(cookieStr)
                }
                liveWv.evaluateJavascript(jsCode, null)
                true
            }
            if (!launched) return null

            val response = withTimeoutOrNull(bridgeTimeoutMs) {
                deferred.await()
            }
            if (response != null) {
                completedFromJs = true
            }
            bridgeResult = response
            val cm = runCatching { CookieManager.getInstance() }.getOrNull()
            val latestCookies = runCatching { cm?.getCookie("https://linux.do") }.getOrNull().orEmpty()
            if (latestCookies.isNotBlank()) {
                CookieSentinel.syncFromAuthoritativeBrowserCookieString(latestCookies)
                val now = System.currentTimeMillis()
                if (!isRead || (latestCookies != lastObservedCookieSnapshot && now - lastCookieFlushEpochMs > 5_000L)) {
                    lastObservedCookieSnapshot = latestCookies
                    lastCookieFlushEpochMs = now
                    runCatching { cm?.flush() }
                }
            } else if (!isRead) {
                runCatching { cm?.flush() }
            }
        } finally {
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
            httpSemaphore.release()
        }

        if (bridgeResult?.first == REBUILD_ABORTED_STATUS) {
            return null
        }

        if (bridgeResult != null && bridgeResult!!.first > 0) {
            consecutiveBridgeFailures.set(0)
        } else if (!completedFromJs) {
            consecutiveBridgeFailures.incrementAndGet()
            activityScope.launch {
                rebuildApiWebView(reason = "js_bridge_no_callback", expectedGeneration = observedGen)
            }
        } else if (!isBackground) {
            if (consecutiveBridgeFailures.incrementAndGet() >= 2) {
                activityScope.launch {
                    rebuildApiWebView(reason = "consecutive_status_zero", expectedGeneration = observedGen)
                }
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
                    for (let attempt = 0; attempt < maxAttempts; attempt++) {
                        if (cancelled) return;
                        const attemptCtrl = new AbortController();
                        activeCtrl = attemptCtrl;
                        const attemptTimer = setTimeout(() => {
                            try { attemptCtrl.abort(); } catch (_) {}
                        }, 5500);
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
                            if (resp && (resp.ok || (resp.status !== 502 && resp.status !== 503 && resp.status !== 504))) {
                                break;
                            }
                        } catch (e) {
                            if (cancelled || attempt + 1 >= maxAttempts) throw e;
                        } finally {
                            clearTimeout(attemptTimer);
                            if (activeCtrl === attemptCtrl) activeCtrl = null;
                        }
                        await new Promise(r => setTimeout(r, 200));
                    }
                    if (!resp || !resp.ok) {
                        reportBin(resp ? resp.status : 0, '', '');
                        return;
                    }
                    const ct = (resp.headers.get('Content-Type') || 'image/png').toLowerCase();
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
                    const buf = await resp.arrayBuffer();
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
        if (pendingTransportRecycleOnNextUse && !isFinishing && !isDestroyed) {
            rebuildApiWebView(reason = "pending_network_recycle", force = true)
        }
        if (apiWebView == null && !isFinishing && !isDestroyed) {
            withContext(Dispatchers.Main) {
                if (apiWebView == null && !isFinishing && !isDestroyed) {
                    initPersistentApiWebView()
                }
            }
        }
        if (apiWebView == null) return null
        val isReady = withTimeoutOrNull(4500L) { apiWebViewReady.await() } ?: return null
        if (!isReady) return null

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

        webViewBinarySemaphore.acquire()
        val rawTriple = try {
            val first = invokeOnce(injectHelperFirst = false)
            if (first != null && first.first == -3) {
                invokeOnce(injectHelperFirst = true)
            } else {
                first
            }
        } finally {
            webViewBinarySemaphore.release()
        } ?: return null

        val (status, contentType, b64Body) = rawTriple
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
        if (pendingTransportRecycleOnNextUse && !isFinishing && !isDestroyed) {
            rebuildApiWebView(reason = "pending_network_recycle", force = true)
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
        val isReady = withTimeoutOrNull(4500L) { apiWebViewReady.await() }
        if (isReady != true) {
            if (!isFinishing && !isDestroyed) {
                rebuildApiWebView(reason = "ready_await_timeout", expectedGeneration = preWaitGen)
            }
            return null
        }

        val reqId = UUID.randomUUID().toString()
        val reqIdQuoted = JSONObject.quote(reqId)
        val deferred = CompletableDeferred<Pair<Int, String>>()

        webViewForegroundHttpSemaphore.acquire()
        var completedFromJs = false
        var dispatchedWv: WebView? = null
        try {
            pendingUploadPayloads[reqId] = rawBytes
            pendingHttpCallbacks[reqId] = deferred
            if (apiWebView == null && !isFinishing && !isDestroyed) {
                withContext(Dispatchers.Main) {
                    if (apiWebView == null && !isFinishing && !isDestroyed) {
                        initPersistentApiWebView()
                    }
                }
            }
            if (!apiWebViewReady.isCompleted) {
                val postAcquireGen = apiWebViewGeneration
                val readyAfterAcquire = withTimeoutOrNull(4500L) { apiWebViewReady.await() }
                if (readyAfterAcquire != true) {
                    if (!isFinishing && !isDestroyed) {
                        rebuildApiWebView(reason = "ready_await_timeout", expectedGeneration = postAcquireGen)
                    }
                    return null
                }
            }

            val launched = withContext(Dispatchers.Main) {
                val liveWv = apiWebView ?: return@withContext false
                dispatchedWv = liveWv
                attachApiWebViewInTinyHost(liveWv)
                if (UserPresenceService.isForeground) {
                    liveWv.onResume()
                    liveWv.resumeTimers()
                }
                val cm = CookieManager.getInstance()
                val cookieStr = ensureAuthCookiesInWebKit(cm, cm.getCookie("https://linux.do").orEmpty())
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
                        function reportUpload(status, body, cfMitigated) {
                            if (cancelledByKotlin) return;
                            if (window.LinDoJsBridge && window.LinDoJsBridge.onHttpResultWithMeta) {
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
                                reportUpload(csrfLastStatus || 0, csrfLastBody, csrfLastMitigated);
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
                                    reportUpload(csrfLastStatus || resp.status, csrfLastBody || text, csrfLastMitigated);
                                    return;
                                }
                            }
                            reportUpload(resp.status, text, cfMitigated);
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
            val cm = runCatching { CookieManager.getInstance() }.getOrNull()
            val latestCookies = runCatching { cm?.getCookie("https://linux.do") }.getOrNull().orEmpty()
            if (latestCookies.isNotBlank()) {
                CookieSentinel.syncFromAuthoritativeBrowserCookieString(latestCookies)
                lastObservedCookieSnapshot = latestCookies
                lastCookieFlushEpochMs = System.currentTimeMillis()
            }
            runCatching { cm?.flush() }
            return if (response?.first == REBUILD_ABORTED_STATUS) null else response
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
        }
    }

    private fun restoreSessionFromDisk() {
        runCatching {
            // 始终使用与系统 WebView 100% 原生一致的 User-Agent（不删改 "; wv"），
            // 确保与底层 Sec-CH-UA Client Hints 及 cf_clearance 签名完全吻合
            val nativeUa = WebSettings.getDefaultUserAgent(this)
            UserApiKeyAuthManager.customUserAgent = nativeUa

            val savedClientId = prefs.getString("client_id", null)
            if (!savedClientId.isNullOrBlank()) {
                UserApiKeyAuthManager.restoreClientId(savedClientId)
            } else {
                prefs.edit().putString("client_id", UserApiKeyAuthManager.getClientId()).apply()
            }

            // 恢复跳转到系统浏览器前生成的 RSA 私钥与 nonce
            val savedPendingPrivKey = prefs.getString("pending_rsa_priv_b64", null)
            val savedPendingNonce = prefs.getString("pending_rsa_nonce", null)
            UserApiKeyAuthManager.restorePendingPrivateKeyBase64(savedPendingPrivKey, savedPendingNonce, prefs.getLong("pending_rsa_created_at", 0L))

            // 恢复用户的阅读密度/字号、深色模式、动效与主题色偏好（默认官方黑白黄，标准字号默认 0.92f）
            UserApiKeyAuthManager.uiFontScale = prefs.getFloat("ui_font_scale_v2", 0.92f)
            UserApiKeyAuthManager.uiDarkTheme = prefs.getBoolean("ui_dark_theme", false)
            UserApiKeyAuthManager.uiEnableAnimations = prefs.getBoolean("ui_enable_animations", true)
            UserApiKeyAuthManager.uiThemeAccent = prefs.getString("ui_theme_accent", "OFFICIAL_GOLD") ?: "OFFICIAL_GOLD"
            UserApiKeyAuthManager.uiCompactFeed = prefs.getBoolean("ui_compact_feed", false)
            UserApiKeyAuthManager.uiSplitFraction = prefs.getFloat("ui_split_fraction", 0.42f).coerceIn(0.28f, 0.62f)
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
                    trustLevel = trustLevel
                )
            } else {
                null
            }

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            // 始终以原生 Chromium WebKit SQLite CookieJar 为权威源（原生保留真实过期时间与 SameSite/HttpOnly 属性）；
            // 严禁从 SharedPreferences 反写 cf_clearance / __cf_bm / _cfuvid（会丢失原始属性或注入已过期挑战票据），
            // 仅当存在已保存有效 `_t` 会话且 WebKit 意外丢失 `_t` 时才回填 host-only 会话 Cookie。
            val webKitCookies = cookieManager.getCookie("https://linux.do").orEmpty()
            val savedCookies = prefs.getString("raw_cookies", "").orEmpty()
            val hasLiveWebKitSession = hasCookieNamed(webKitCookies, "_t")
            if (!hasLiveWebKitSession && savedCookies.contains("_t=") && hasCookieNamed(savedCookies, "_t")) {
                val discourseHostOnlyNames = setOf("_t", "_forum_session")
                savedCookies.split(";").forEach { segment ->
                    val pair = segment.trim()
                    if (pair.contains("=") && !pair.startsWith("_t=demo_session_token")) {
                        val name = pair.substringBefore("=").trim()
                        val value = pair.substringAfter("=").trim()
                        if (name in discourseHostOnlyNames && value.isNotEmpty()) {
                            cookieManager.setCookie("https://linux.do", "$name=$value; Path=/; Secure; HttpOnly; SameSite=Lax")
                        }
                    }
                }
                cookieManager.flush()
            }
            val authoritativeCookies = cookieManager.getCookie("https://linux.do").orEmpty().ifBlank {
                if (hasCookieNamed(savedCookies, "_t")) savedCookies else ""
            }

            UserApiKeyAuthManager.restorePersistedSession(
                profile = userProfile,
                apiKey = savedApiKey,
                rawCookies = authoritativeCookies
            )
        }
    }

    private fun ensureAuthCookiesInWebKit(cookieManager: CookieManager, currentWebKitCookies: String): String {
        if (hasCookieNamed(currentWebKitCookies, "_t")) return currentWebKitCookies
        val hasAuthorizedUser = UserApiKeyAuthManager.currentUser?.takeIf { it.username != "linuxdo_user" } != null
        val winning = CookieSentinel.resolveWinningCookies(targetHost = "linux.do")
        val savedT = winning["_t"]?.value?.takeIf { it.isNotBlank() && it != "demo_session_token" } ?: return currentWebKitCookies
        if (!hasAuthorizedUser && !UserApiKeyAuthManager.isAuthorized()) return currentWebKitCookies
        cookieManager.setCookie("https://linux.do", "_t=$savedT; Path=/; Secure; HttpOnly; SameSite=Lax")
        winning["_forum_session"]?.value?.takeIf { it.isNotBlank() }?.let { fs ->
            cookieManager.setCookie("https://linux.do", "_forum_session=$fs; Path=/; Secure; HttpOnly; SameSite=Lax")
        }
        cookieManager.flush()
        return cookieManager.getCookie("https://linux.do").orEmpty()
    }

    private fun persistSessionToDisk() {
        runCatching {
            val cookieManager = CookieManager.getInstance()
            cookieManager.flush()
            val latestWebKitCookies = ensureAuthCookiesInWebKit(
                cookieManager,
                cookieManager.getCookie("https://linux.do").orEmpty()
            )
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
                editor.putInt("trust_level", user.trustLevel)
            } else {
                editor.remove("user_id")
                editor.remove("username")
                editor.remove("display_name")
                editor.remove("avatar_template")
                editor.remove("animated_avatar")
                editor.remove("user_title")
                editor.remove("trust_level")
            }
            editor.apply()
        }
    }

    private suspend fun importPrivateDebugCookies() {
        val source = java.io.File(filesDir, "lindo-session-import.json")
        if (!source.isFile) return
        try {
            val entries = withContext(Dispatchers.IO) { JSONArray(source.readText()) }
            val manager = CookieManager.getInstance()
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

    private fun dispatchNetworkRestoreRecycle() {
        pendingTransportRecycleOnNextUse = true
        val now = System.currentTimeMillis()
        val elapsed = now - lastNetworkRestoreNotifyAtMs
        if (elapsed >= 1_200L) {
            trailingNetworkRestoreJob?.cancel()
            trailingNetworkRestoreJob = null
            lastNetworkRestoreNotifyAtMs = now
            activityScope.launch {
                ensureHealthyApiWebView(forceCheck = true)
                withContext(Dispatchers.Main) {
                    UserApiKeyAuthManager.notifyNetworkRestored()
                }
            }
        } else {
            val waitMs = (1_200L - elapsed).coerceAtLeast(350L)
            trailingNetworkRestoreJob?.cancel()
            trailingNetworkRestoreJob = activityScope.launch {
                delay(waitMs)
                lastNetworkRestoreNotifyAtMs = System.currentTimeMillis()
                pendingTransportRecycleOnNextUse = true
                ensureHealthyApiWebView(forceCheck = true)
                withContext(Dispatchers.Main) {
                    UserApiKeyAuthManager.notifyNetworkRestored()
                }
            }
        }
    }

    private fun registerNetworkRecoveryMonitor() {
        if (connectivityCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                if (network.networkHandle == activeValidatedNetworkHandle) {
                    activeValidatedNetworkHandle = 0L
                    activeTransportSignature = ""
                    hadNetworkInterruption = true
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
                val sig = computeTransportSignature(cm, network, networkCapabilities)
                if (activeValidatedNetworkHandle == 0L && !hadNetworkInterruption) {
                    activeValidatedNetworkHandle = handle
                    activeTransportSignature = sig
                    return
                }
                if (handle != activeValidatedNetworkHandle || sig != activeTransportSignature || hadNetworkInterruption) {
                    activeValidatedNetworkHandle = handle
                    activeTransportSignature = sig
                    hadNetworkInterruption = false
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
                val sig = computeTransportSignature(cm, network, caps)
                if (activeTransportSignature.isNotEmpty() && sig != activeTransportSignature) {
                    activeTransportSignature = sig
                    dispatchNetworkRestoreRecycle()
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
            hadNetworkInterruption = false
        } else {
            activeValidatedNetworkHandle = 0L
            activeTransportSignature = ""
            hadNetworkInterruption = true
        }
        runCatching {
            cm.registerDefaultNetworkCallback(callback)
            connectivityCallback = callback
        }
    }

    private fun unregisterNetworkRecoveryMonitor() {
        trailingNetworkRestoreJob?.cancel()
        trailingNetworkRestoreJob = null
        val cb = connectivityCallback ?: return
        connectivityCallback = null
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching { cm.unregisterNetworkCallback(cb) }
    }

    override fun onDestroy() {
        unregisterNetworkRecoveryMonitor()
        activityScope.cancel()
        pendingMediaPickCallback = null
        pendingWebFileChooserCallback?.onReceiveValue(null)
        pendingWebFileChooserCallback = null
        pendingUploadPayloads.clear()
        pendingHttpCallbacks.values.forEach { it.cancel() }
        pendingHttpCallbacks.clear()
        pendingBinaryCallbacks.values.forEach { it.cancel() }
        pendingBinaryCallbacks.clear()
        if (activeInstance === this) {
            activeInstance = null
            do_.linux.lindo.core.compose.LocalComposerDrafts.onPersist = null
            UserApiKeyAuthManager.onPersistPendingKey = null
            UserApiKeyAuthManager.openExternalUrlLauncher = null
            UserApiKeyAuthManager.onPersistSession = null
            UserApiKeyAuthManager.onPersistReadTopics = null
            UserApiKeyAuthManager.onPersistUiPrefs = null
            UserApiKeyAuthManager.onPersistThemeAndDensity = null
            UserApiKeyAuthManager.onPersistSplitFraction = null
            UserApiKeyAuthManager.onPrepareManualRecovery = null
            UserApiKeyAuthManager.onSyncCookieToPlatform = null
            UserApiKeyAuthManager.onLogoutPlatformClear = null
            UserApiKeyAuthManager.webViewHttpExecutor = null
            UserApiKeyAuthManager.webViewJsonFetcher = null
            UserApiKeyAuthManager.webViewBinaryFetcher = null
            PlatformMediaPicker.launchPicker = null
            PlatformMediaPicker.webViewBinaryUploadExecutor = null
            PlatformMediaPicker.webViewUploadExecutor = null
            PlatformWebLoginHost.webLoginRenderer = null
        }
        apiWebView?.let { releaseNativeWebView(it) }
        apiWebView = null
        apiWebViewHost?.let { host ->
            (host.parent as? ViewGroup)?.removeView(host)
        }
        apiWebViewHost = null
        super.onDestroy()
        iconManager.onActivityDestroyed()
    }

    override fun onResume() {
        super.onResume()
        val pausedAt = lastPausedAtEpochMs
        lastPausedAtEpochMs = 0L
        UserPresenceService.setAppForeground(true)
        requestHighRefreshRateDisplay()
        runCatching {
            apiWebView?.onResume()
            apiWebView?.resumeTimers()
        }
        if (pausedAt > 0L && System.currentTimeMillis() - pausedAt >= 45_000L) {
            activityScope.launch {
                pendingTransportRecycleOnNextUse = true
                ensureHealthyApiWebView(forceCheck = true)
                withContext(Dispatchers.Main) {
                    UserApiKeyAuthManager.notifyNetworkRestored()
                }
            }
        }
    }

    override fun onPause() {
        lastPausedAtEpochMs = System.currentTimeMillis()
        UserPresenceService.setAppForeground(false)
        runCatching {
            apiWebView?.onPause()
            do_.linux.lindo.core.compose.LocalComposerDrafts.flushPendingPersist()
            CookieManager.getInstance().flush()
            persistSessionToDisk()
        }
        super.onPause()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        UserPresenceService.markUserActivity()
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

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun AndroidWebLoginModal(
    initialUrl: String,
    onDismiss: () -> Unit,
    onLoginSuccess: () -> Unit,
    onLaunchFileChooser: ((ValueCallback<Array<Uri>>?, WebChromeClient.FileChooserParams?) -> Boolean)? = null
) {
    val isLoginFlow = remember(initialUrl) {
        initialUrl.contains("/login") ||
            initialUrl.contains("/session/otp/") ||
            initialUrl.contains("/user-api-key/")
    }

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var completed by remember { mutableStateOf(false) }
    var csrfSyncedInModal by remember(initialUrl) { mutableStateOf(false) }
    var postLoginProbeMisses by remember(initialUrl) { mutableStateOf(0) }
    var pageLoadError by remember { mutableStateOf<String?>(null) }
    var rendererGone by remember { mutableStateOf(false) }
    var pendingUrl by remember { mutableStateOf<String?>(null) }
    val isDark = LocalLinDoDarkTheme.current
    val initialTCookie = remember(initialUrl) {
        getCookieValue(
            runCatching { CookieManager.getInstance().getCookie("https://linux.do") }.getOrNull(),
            "_t"
        ).takeUnless { it == "demo_session_token" }.orEmpty()
    }
    val wasAlreadyAuthorizedOnOpen = remember(initialUrl) {
        UserApiKeyAuthManager.isAuthorized()
    }

    fun retryPage() {
        val view = webViewRef
        val url = (if (pageLoadError != null) pendingUrl else view?.url) ?: initialUrl
        pendingUrl = url
        pageLoadError = null
        rendererGone = false
        if (view?.url == url) view.reload() else view?.loadUrl(url)
    }

    BackHandler {
        if (pageLoadError == null && webViewRef?.canGoBack() == true) webViewRef?.goBack() else onDismiss()
    }

    fun completeWithUserObject(u: JSONObject) {
        if (completed) return
        val username = u.optString("username").trim()
        if (username.isBlank() || username == "linuxdo_user") return
        val userId = u.optInt("id", 0)
        val displayName = u.optString("name").ifBlank { username }
        val rawTpl = u.optString("avatar_template").ifBlank { u.optString("avatar") }
        val avatarUrl = LinuxDoSiteRules.resolveStaticAvatarUrl(rawTpl, 144)
        val title = u.optString("title", "")
        val trust = if (u.has("trust_level")) u.optInt("trust_level", 1) else u.optInt("trust", 1)

        completed = true
        val cookieManager = CookieManager.getInstance()
        cookieManager.flush()
        val rawCookies = cookieManager.getCookie("https://linux.do").orEmpty()
        println("LinDoAuth: Modal authenticated user=$username, trustLevel=$trust")
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

    fun completeWithValidatedSessionCookie(rawCookies: String) {
        if (completed) return
        val tVal = getCookieValue(rawCookies, "_t").takeUnless { it == "demo_session_token" }.orEmpty()
        if (tVal.isEmpty()) return
        completed = true
        CookieManager.getInstance().flush()
        CookieSentinel.ingestRawCookieString(rawCookies, domain = "linux.do", hostOnly = false)
        UserApiKeyAuthManager.persistSessionQuietly()
        UserApiKeyAuthManager.onAuthStateChanged?.invoke()
        onLoginSuccess()
    }

    fun tryExtractSessionAndComplete(webView: WebView?, isPageFinished: Boolean = false) {
        if (completed || webView == null) return
        val cookieManager = CookieManager.getInstance()
        val rawCookies = cookieManager.getCookie("https://linux.do").orEmpty()

        // 若当前在 /session/otp/ 兑换页，仅在此路径下自动触发表单 POST 提交
        if (webView.url?.contains("/session/otp/") == true) {
            webView.evaluateJavascript(AUTO_SUBMIT_OTP_FORM_JS, null)
        }

        // 无论是否为登录流，只要页面加载完成，立即同步刷盘最新 Cookie（如通过 Cloudflare 验证后的 cf_clearance）
        if (isPageFinished) {
            runCatching { cookieManager.flush() }
            val latestSynced = cookieManager.getCookie("https://linux.do").orEmpty().ifBlank { rawCookies }
            if (latestSynced.isNotBlank()) {
                CookieSentinel.syncFromAuthoritativeBrowserCookieString(latestSynced)
            }
        }

        // 非登录流（如应用广场、官网验证、Connect/Credit 等内置容器模式）：
        // 同步最新 Cookie（如 cf_clearance），不自动关闭窗口
        if (!isLoginFlow) {
            val latestCookies = cookieManager.getCookie("https://linux.do").orEmpty().ifBlank { rawCookies }
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

        // 在登录表单、OTP 兑换或 /user-api-key/ 授权确认页绝不提前关闭弹窗，等待登录/授权跳回主站
        val currentUri = runCatching { Uri.parse(webView.url ?: pendingUrl.orEmpty()) }.getOrNull()
        val currentHost = currentUri?.host?.lowercase().orEmpty()
        val currentPath = currentUri?.path.orEmpty()
        if (currentHost != "linux.do" && !currentHost.endsWith(".linux.do")) {
            return
        }
        if (currentPath.startsWith("/user-api-key/") ||
            currentPath.startsWith("/login") ||
            currentPath.startsWith("/session/otp/") ||
            currentPath.startsWith("/session/sso") ||
            currentPath.startsWith("/auth/")
        ) {
            postLoginProbeMisses = 0
            return
        }

        // 关键防风控守卫：在原生 CookieManager 尚未检测到真实 Discourse `_t` 凭证前，
        // 严禁向页面注入任何探测脚本或发起轮询请求，保持 `/login` 与 Cloudflare Turnstile 页面 100% 纯净零干扰！
        val currentTCookie = getCookieValue(rawCookies, "_t").takeUnless { it == "demo_session_token" }.orEmpty()
        if (currentTCookie.isEmpty()) {
            postLoginProbeMisses = 0
            return
        }
        // 若用户原本已登录（如打开 /login 切换账号），在 `_t` 未更新前绝不秒关窗口！
        if (wasAlreadyAuthorizedOnOpen && initialUrl.contains("/login") && currentTCookie == initialTCookie) {
            return
        }

        // 已检测到 `_t` 会话 Cookie 且已离开 /login：先确认当前并非 Cloudflare 验证挑战页，再提取当前用户资料与 CSRF Token
        val probeJs = """
            (function() {
                try {
                    var title = document.title || '';
                    if (/Just a moment|请稍候|Attention Required/i.test(title) ||
                        document.querySelector('#challenge-running, #challenge-stage, .cf-turnstile, iframe[src*="challenges.cloudflare.com"]')) {
                        return '__CF_CHALLENGE__';
                    }
                    var path = window.location.pathname || '';
                    if (path.indexOf('/login') === 0 ||
                        path.indexOf('/user-api-key/') === 0 ||
                        path.indexOf('/session/otp/') === 0 ||
                        path.indexOf('/session/sso') === 0 ||
                        path.indexOf('/auth/') === 0) {
                        return '__ON_LOGIN_PATH__';
                    }
                    var metaCsrf = document.querySelector('meta[name="csrf-token"]');
                    var csrfVal = (metaCsrf && metaCsrf.content) ? metaCsrf.content : '';
                    if (window.__lindoResolvedCurrentUser && window.__lindoResolvedOwner === ${JSONObject.quote(csrfOwnerTag(rawCookies))}) {
                        var cachedU = JSON.parse(window.__lindoResolvedCurrentUser);
                        if (cachedU && cachedU.username) {
                            if (csrfVal && !cachedU.__csrf) cachedU.__csrf = csrfVal;
                            return JSON.stringify(cachedU);
                        }
                    }
                    var container = (window.Discourse && window.Discourse.__container__) || null;
                    var cuSvc = (container && typeof container.lookup === 'function')
                        ? container.lookup('service:current-user')
                        : null;
                    var du = cuSvc || ((window.Discourse && Discourse.User && Discourse.User.current) ? Discourse.User.current() : null);
                    if (du && du.username) {
                        return JSON.stringify({
                            id: du.id || 0,
                            username: du.username || '',
                            name: du.name || du.username || '',
                            avatar_template: du.avatar_template || '',
                            animated_avatar: du.animated_avatar || '',
                            title: du.title || '',
                            trust_level: typeof du.trust_level === 'number' ? du.trust_level : 1,
                            __csrf: csrfVal
                        });
                    }
                    var el = document.getElementById('data-preloaded');
                    if (el && el.dataset && el.dataset.preloaded) {
                        var p = JSON.parse(el.dataset.preloaded);
                        if (p && p.currentUser) {
                            var u = JSON.parse(p.currentUser);
                            if (u && u.username) {
                                if (csrfVal) u.__csrf = csrfVal;
                                return JSON.stringify(u);
                            }
                        }
                    }
                    if (csrfVal) {
                        return '__CSRF_ONLY__:' + csrfVal;
                    }
                } catch (e) {}
                return '';
            })();
        """.trimIndent()

        val expectedOwnerTag = csrfOwnerTag(rawCookies)
        webView.evaluateJavascript(probeJs) { rawJson ->
            if (completed) return@evaluateJavascript
            val decoded = decodeJsStringResult(rawJson)?.takeIf { it.isNotBlank() }
            if (decoded == "__CF_CHALLENGE__" || decoded == "__ON_LOGIN_PATH__") {
                postLoginProbeMisses = 0
                return@evaluateJavascript
            }
            if (decoded != null && !decoded.startsWith("__CSRF_ONLY__:")) {
                runCatching {
                    val obj = JSONObject(decoded)
                    val csrf = obj.optString("__csrf").trim()
                    if (csrf.isNotEmpty()) {
                        csrfSyncedInModal = true
                        LinuxDoRepository.updateCsrfToken(csrf)
                    }
                    completeWithUserObject(obj)
                }
            } else if (!completed && hasCookieNamed(rawCookies, "_t")) {
                if (decoded != null && decoded.startsWith("__CSRF_ONLY__:")) {
                    val csrf = decoded.removePrefix("__CSRF_ONLY__:").trim()
                    if (csrf.isNotEmpty()) {
                        csrfSyncedInModal = true
                        LinuxDoRepository.updateCsrfToken(csrf)
                    }
                }
                postLoginProbeMisses += 1
                // 若在主站已持有有效 `_t` 超过 3 次轮询但 SPA 尚未暴露用户对象，直接凭 `_t` 完成登录回跳并由后台补全资料，防止卡死在登录弹窗
                if (!wasAlreadyAuthorizedOnOpen && postLoginProbeMisses >= 4) {
                    completeWithValidatedSessionCookie(rawCookies)
                    return@evaluateJavascript
                }
                // 在同源上下文单飞请求 `/session/current.json` 提取当前登录账号资料
                webView.evaluateJavascript(
                    """
                    (function() {
                        if (window.location.origin !== 'https://linux.do') return;
                        if (window.__lindoCurrentUserProbeInFlight) return;
                        window.__lindoCurrentUserProbeInFlight = true;
                        var owner = ${JSONObject.quote(expectedOwnerTag)};
                        fetch('/session/current.json', {
                            headers: { 'Accept': 'application/json, text/javascript, */*; q=0.01', 'X-Requested-With': 'XMLHttpRequest' },
                            credentials: 'include',
                            cache: 'no-store'
                        }).then(function(r) { return r.ok ? r.json() : null; })
                          .then(function(d) {
                              if (d && d.current_user && d.current_user.username) {
                                  window.__lindoResolvedOwner = owner;
                                  window.__lindoResolvedCurrentUser = JSON.stringify(d.current_user);
                              }
                          })
                          .catch(function() {})
                          .finally(function() { window.__lindoCurrentUserProbeInFlight = false; });
                    })();
                    """.trimIndent(),
                    null
                )
            }
        }
    }

    // 当 initialUrl 变化（例如外部浏览器回调后注入 /session/otp/$otp）时立即加载
    LaunchedEffect(initialUrl) {
        if (initialUrl.isNotBlank()) {
            pendingUrl = initialUrl
            pageLoadError = null
            val wv = webViewRef
            if (wv != null && wv.url != initialUrl) {
                wv.loadUrl(initialUrl)
            }
        }
    }

    LaunchedEffect(webViewRef) {
        val wv = webViewRef ?: return@LaunchedEffect
        val target = (pendingUrl ?: initialUrl).takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        if (wv.url != target) {
            wv.loadUrl(target)
        }
    }

    // 仅在登录流开启时轮询探测登录完成状态（非登录容器页如 Connect / Credit 不轮询）
    LaunchedEffect(webViewRef, completed, isLoginFlow) {
        if (!isLoginFlow) return@LaunchedEffect
        while (!completed && webViewRef != null) {
            delay(800L)
            tryExtractSessionAndComplete(webViewRef)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                val cm = CookieManager.getInstance()
                cm.flush()
                val latest = cm.getCookie("https://linux.do").orEmpty()
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

    val barBg = if (isDark) androidx.compose.ui.graphics.Color(0xFF13161C) else androidx.compose.ui.graphics.Color(0xFFFFFFFF)
    val barFg = if (isDark) androidx.compose.ui.graphics.Color(0xFFF3F4F6) else androidx.compose.ui.graphics.Color(0xFF111827)
    val barSubFg = if (isDark) androidx.compose.ui.graphics.Color(0xFFA1A8B5) else androidx.compose.ui.graphics.Color(0xFF525866)
    val barBorder = if (isDark) androidx.compose.ui.graphics.Color(0xFF232833) else androidx.compose.ui.graphics.Color(0xFFEDEFF3)
    val currentPageTitle = remember(initialUrl, isLoginFlow) {
        val parsed = runCatching { Uri.parse(initialUrl) }.getOrNull()
        val host = parsed?.host.orEmpty()
        val path = parsed?.path.orEmpty()
        when {
            isLoginFlow -> "LINUX DO 登录与安全验证"
            host.equals("linux.do", ignoreCase = true) && (path.isEmpty() || path == "/") -> "LINUX DO 安全验证 · 完成后点左上角返回"
            host.equals("connect.linux.do", ignoreCase = true) -> "LINUX DO Connect"
            host.equals("credit.linux.do", ignoreCase = true) -> "LINUX DO 社区积分"
            else -> host.ifBlank { "LINUX DO" }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(barBg)
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .zIndex(1f)
                    .background(barBg)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.IconButton(onClick = onDismiss) {
                    androidx.compose.material3.Icon(
                        imageVector = do_.linux.lindo.ui.components.LinDoIcons.Close,
                        contentDescription = "关闭网页",
                        modifier = Modifier.size(18.dp),
                        tint = barFg
                    )
                }
                Text(
                    text = currentPageTitle,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = barFg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                androidx.compose.material3.IconButton(onClick = ::retryPage) {
                    androidx.compose.material3.Icon(
                        imageVector = do_.linux.lindo.ui.components.LinDoIcons.Refresh,
                        contentDescription = "刷新网页",
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

            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds(), contentAlignment = Alignment.Center) {
                if (!rendererGone) AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
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

                            val cookieManager = CookieManager.getInstance()
                            cookieManager.setAcceptCookie(true)
                            cookieManager.setAcceptThirdPartyCookies(this, true)
                            cookieManager.flush()

                            webChromeClient = object : WebChromeClient() {
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
                                        pageLoadError = "网页已停止运行"
                                    }
                                    releaseNativeWebView(view, rendererGone = true)
                                    return true
                                }
                                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                    super.onReceivedError(view, request, error)
                                    if (view === webViewRef && request?.isForMainFrame == true) {
                                        val failedUrl = request.url.toString()
                                        if (failedUrl == pendingUrl || failedUrl == view?.url || request.isRedirect) {
                                            pendingUrl = failedUrl
                                            pageLoadError = "网络连接失败"
                                        }
                                    }
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val target = request?.url?.toString() ?: return false
                                    if (target.startsWith("discourse://auth_redirect") ||
                                        target.startsWith("lindo://auth_redirect")
                                    ) {
                                        val ok = UserApiKeyAuthManager.handleAuthRedirectUri(target)
                                        if (ok) {
                                            val otp = UserApiKeyAuthManager.consumePendingOtpToken()
                                            if (!otp.isNullOrBlank()) {
                                                view?.loadUrl("https://linux.do/session/otp/$otp")
                                            } else {
                                                view?.loadUrl("https://linux.do/")
                                            }
                                        }
                                        return true
                                    }
                                    val scheme = request.url?.scheme?.lowercase().orEmpty()
                                    if (scheme.isNotBlank() && scheme != "http" && scheme != "https" && scheme != "about" && scheme != "javascript") {
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
                                        if (url != null &&
                                            (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true))
                                        ) {
                                            pendingUrl = url
                                        }
                                        pageLoadError = null
                                    }
                                    tryExtractSessionAndComplete(view)
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
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
