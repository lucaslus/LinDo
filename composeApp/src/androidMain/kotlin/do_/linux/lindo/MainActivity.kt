package do_.linux.lindo

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
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
    return if (t.isEmpty()) "" else "${t.hashCode()}_${t.length}"
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
    private var apiWebViewReady = CompletableDeferred<Boolean>()
    private val pendingHttpCallbacks = ConcurrentHashMap<String, CompletableDeferred<Pair<Int, String>>>()
    private val pendingBinaryCallbacks = ConcurrentHashMap<String, CompletableDeferred<Triple<Int, String, String>>>()
    private val webViewBinarySemaphore = kotlinx.coroutines.sync.Semaphore(6)
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
        fun onCsrfTokenUpdated(token: String) {
            val clean = token.trim()
            if (clean.isNotEmpty()) {
                LinuxDoRepository.updateCsrfToken(clean)
                runCatching { CookieManager.getInstance().flush() }
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
                    val avatarUrl = if (rawTpl.isNotBlank()) {
                        if (rawTpl.startsWith("http")) rawTpl.replace("{size}", "240")
                        else "https://linux.do${rawTpl.replace("{size}", "240")}"
                    } else ""
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
                onDismiss = onDismiss,
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

        // 保持后台同源 apiWebView 拥有真实屏幕视口尺寸但不挂载至主窗口 ViewTree，
        // 避免国产 ROM（HyperOS / ColorOS / OriginOS / MagicOS）检测到全屏可见 WebView 后将窗口强制降频锁死在 60Hz，同时消除 RenderThread 每帧遍历 WebView 图层的开销。
        window.decorView.post {
            requestHighRefreshRateDisplay()
            runCatching {
                val wv = apiWebView
                if (wv != null) {
                    val dm = resources.displayMetrics
                    val w = dm.widthPixels.coerceAtLeast(360)
                    val h = dm.heightPixels.coerceAtLeast(640)
                    wv.measure(
                        View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
                    )
                    wv.layout(0, 0, w, h)
                }
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
     * 主动向系统 WindowManager 申请当前物理分辨率下的最高刷新率档位（自适应 60 / 90 / 120 / 144 / 165Hz 高刷屏）
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

            wv.addJavascriptInterface(LinDoJsBridge(), "LinDoJsBridge")
            wv.webViewClient = object : WebViewClient() {
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    if (apiWebView === view) apiWebView = null
                    pendingHttpCallbacks.values.forEach { it.cancel() }
                    pendingHttpCallbacks.clear()
                    pendingBinaryCallbacks.values.forEach { it.cancel() }
                    pendingBinaryCallbacks.clear()
                    releaseNativeWebView(view, rendererGone = true)
                    apiWebViewReady = CompletableDeferred()
                    activityScope.launch {
                        delay(250L)
                        if (apiWebView == null && !isFinishing && !isDestroyed) {
                            initPersistentApiWebView()
                        }
                    }
                    return true
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    val cookies = cm.getCookie("https://linux.do").orEmpty()
                    if (cookies.isNotBlank()) {
                        CookieSentinel.syncFromAuthoritativeBrowserCookieString(cookies)
                    }
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
                                if (window.__lindoCsrfCookieOwner !== owner) {
                                    window.__lindoCsrf = null;
                                    window.__lindoCsrfPromise = null;
                                    window.__lindoCsrfCookieOwner = owner;
                                }
                                var seeded = ${JSONObject.quote(seeded)};
                                if (seeded && !window.__lindoCsrf) {
                                    window.__lindoCsrf = seeded;
                                }
                                if (!window.__lindoCsrf && !window.__lindoCsrfPromise) {
                                    window.__lindoCsrfPromise = fetch('https://linux.do/session/csrf.json', {
                                        headers: { 'Accept': 'application/json, text/javascript, */*; q=0.01', 'X-Requested-With': 'XMLHttpRequest' },
                                        credentials: 'include',
                                        cache: 'no-store'
                                    }).then(function(r) { return r.ok ? r.json() : null; }).then(function(d) {
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
            val dm = resources.displayMetrics
            val w = dm.widthPixels.coerceAtLeast(360)
            val h = dm.heightPixels.coerceAtLeast(640)
            wv.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
            )
            wv.layout(0, 0, w, h)
            apiWebView = wv
            wv.onResume()
            wv.resumeTimers()
            // 使用轻量级同源空文档初始化 https://linux.do 上下文（historyUrl 传 https://linux.do/ 确保 SiteForCookies 与 SameSite=Lax Cookie 100% 生效）
            wv.loadDataWithBaseURL(
                "https://linux.do/",
                "<!DOCTYPE html><html><head><meta charset=\"utf-8\"></head><body></body></html>",
                "text/html",
                "UTF-8",
                "https://linux.do/"
            )
        }.onFailure { android.util.Log.w("LinDoNetwork", "Could not initialize API WebView", it) }
    }

    private suspend fun executeHttpViaWebViewBridge(
        url: String,
        method: String,
        formParams: Map<String, String>?,
        headers: Map<String, String>
    ): Pair<Int, String>? {
        val isRead = method.equals("GET", true) || method.equals("HEAD", true)
        var wv = apiWebView
        if (wv == null && !isFinishing && !isDestroyed) {
            withContext(Dispatchers.Main) {
                if (apiWebView == null && !isFinishing && !isDestroyed) {
                    initPersistentApiWebView()
                }
                wv = apiWebView
            }
        }
        val activeWv = wv ?: return null
        val isReady = withTimeoutOrNull(4500L) { apiWebViewReady.await() } ?: return null
        if (!isReady) return null

        val reqId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Pair<Int, String>>()
        pendingHttpCallbacks[reqId] = deferred

        val formBodyEncoded = if (!formParams.isNullOrEmpty()) {
            do_.linux.lindo.core.network.formEntries(formParams).joinToString("&") { (k, v) ->
                "${Uri.encode(k)}=${Uri.encode(v)}"
            }
        } else {
            ""
        }

        // 过滤浏览器禁止脚本篡改的受保护头（Origin / Referer / Sec-Fetch-* / Cookie / User-Agent），
        // 且不在同源 WebView fetch 中强制注入 Discourse-Logged-In（避免 Cookie 尚未同步或失效时触发 Discourse 403 InvalidAccess）
        val forbiddenJsHeaders = setOf(
            "origin", "referer", "sec-fetch-dest", "sec-fetch-mode", "sec-fetch-site",
            "cookie", "user-agent", "discourse-logged-in"
        )
        val headersJson = JSONObject().apply {
            headers.forEach { (k, v) ->
                if (k.lowercase() !in forbiddenJsHeaders) {
                    put(k, v)
                }
            }
        }.toString()
        val initialCachedCsrf = LinuxDoRepository.getCachedCsrfToken().orEmpty()
        val preExecCookies = runCatching { CookieManager.getInstance().getCookie("https://linux.do") }.getOrNull().orEmpty()
        val currentCsrfOwner = csrfOwnerTag(preExecCookies)

        val jsCode = """
            (async function() {
                const controller = new AbortController();
                const method = ${JSONObject.quote(method.uppercase())};
                const isRead = method === 'GET' || method === 'HEAD';
                const timer = setTimeout(() => controller.abort(), isRead ? 9500 : 14000);
                function reportHttp(status, body, cfMitigated) {
                    if (window.LinDoJsBridge && window.LinDoJsBridge.onHttpResultWithMeta) {
                        window.LinDoJsBridge.onHttpResultWithMeta(${JSONObject.quote(reqId)}, status, body || '', cfMitigated || '');
                    } else {
                        window.LinDoJsBridge.onHttpResult(${JSONObject.quote(reqId)}, status, body || '');
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
                    if (window.__lindoCsrfCookieOwner !== currentOwner) {
                        window.__lindoCsrf = null;
                        window.__lindoCsrfPromise = null;
                        window.__lindoCsrfCookieOwner = currentOwner;
                    }
                    const kotlinCsrf = ${JSONObject.quote(initialCachedCsrf)};
                    if (!kotlinCsrf && !currentOwner) {
                        window.__lindoCsrf = null;
                    } else if (kotlinCsrf && !window.__lindoCsrf) {
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
                                try {
                                    const csrfResp = await fetch('https://linux.do/session/csrf.json', {
                                        headers: {
                                            'Accept': 'application/json, text/javascript, */*; q=0.01',
                                            'X-Requested-With': 'XMLHttpRequest'
                                        },
                                        credentials: 'include',
                                        cache: 'no-store',
                                        signal: controller.signal
                                    });
                                    csrfLastStatus = csrfResp.status || 0;
                                    csrfLastMitigated = csrfResp.headers.get('cf-mitigated') || '';
                                    if (csrfResp.ok) {
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
                                        if (csrfResp.status === 429 || csrfResp.status === 401 || csrfResp.status === 403) {
                                            break;
                                        }
                                    }
                                } catch (_) {}
                                if (attempt === 0) {
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
                            clearTimeout(timer);
                            const body = csrfLastBody || JSON.stringify({errors: ['暂时无法验证会话，请检查网络或登录状态']});
                            reportHttp(csrfLastStatus || 0, body, csrfLastMitigated);
                            return;
                        }
                        hdrs['X-CSRF-Token'] = token;
                    }
                    const bodyStr = ${JSONObject.quote(formBodyEncoded)};
                    if (!isRead) {
                        hdrs['Content-Type'] = 'application/x-www-form-urlencoded; charset=UTF-8';
                    }
                    const mustNoStore = !isRead || /\/session\/(csrf|current)\.json|\/notifications|\/drafts/i.test(targetUrl);
                    function buildOpts() {
                        const o = {
                            method: method,
                            headers: Object.assign({}, hdrs),
                            credentials: 'include',
                            cache: mustNoStore ? 'no-store' : 'default',
                            signal: controller.signal
                        };
                        if (!isRead) {
                            o.body = bodyStr;
                        }
                        return o;
                    }
                    const isBg = hdrs['Discourse-Background'] === 'true';
                    const maxFetchAttempts = (isRead && !isBg) ? 2 : 1;
                    let resp = null;
                    let text = '';
                    for (let attempt = 0; attempt < maxFetchAttempts; attempt++) {
                        try {
                            resp = await fetch(targetUrl, buildOpts());
                            text = await resp.text();
                            break;
                        } catch (fetchErr) {
                            if (attempt === maxFetchAttempts - 1) {
                                throw fetchErr;
                            }
                        }
                        await new Promise(r => setTimeout(r, 380));
                    }
                    if (!isRead && (resp.status === 403 || resp.status === 422) && /csrf|invalid authenticity token|bad csrf/i.test(text)) {
                        window.__lindoCsrf = null;
                        const freshToken = await refreshCsrfToken(true);
                        if (freshToken) {
                            hdrs['X-CSRF-Token'] = freshToken;
                            resp = await fetch(targetUrl, buildOpts());
                            text = await resp.text();
                        }
                    }
                    clearTimeout(timer);
                    const cfMitigated = (resp && resp.headers && resp.headers.get('cf-mitigated')) || '';
                    reportHttp(resp.status, text, cfMitigated);
                } catch (err) {
                    clearTimeout(timer);
                    reportHttp(0, '', '');
                }
            })();
        """.trimIndent()

        withContext(Dispatchers.Main) {
            val cm = CookieManager.getInstance()
            val cookieStr = cm.getCookie("https://linux.do").orEmpty()
            if (cookieStr.isNotBlank()) {
                CookieSentinel.syncFromAuthoritativeBrowserCookieString(cookieStr)
            }
            activeWv.evaluateJavascript(jsCode, null)
        }

        val res = try {
            val response = withTimeoutOrNull(if (isRead) 11_500L else 16_000L) {
                deferred.await()
            }
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
            response
        } finally {
            pendingHttpCallbacks.remove(reqId)
        }

        return res
    }

    private suspend fun fetchBinaryViaWebViewBridge(
        url: String
    ): UserApiKeyAuthManager.BinaryHttpPayload? {
        var wv = apiWebView
        if (wv == null && !isFinishing && !isDestroyed) {
            withContext(Dispatchers.Main) {
                if (apiWebView == null && !isFinishing && !isDestroyed) {
                    initPersistentApiWebView()
                }
                wv = apiWebView
            }
        }
        val activeWv = wv ?: return null
        val isReady = withTimeoutOrNull(4500L) { apiWebViewReady.await() } ?: return null
        if (!isReady) return null

        val reqId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Triple<Int, String, String>>()
        val maxBridgeBytes = 15 * 1024 * 1024

        val jsCode = """
            (async function() {
                const controller = new AbortController();
                const timer = setTimeout(() => controller.abort(), 11000);
                try {
                    const targetUrl = ${JSONObject.quote(url)};
                    let isSameOrigin = true;
                    try {
                        isSameOrigin = new URL(targetUrl, window.location.origin).origin === window.location.origin;
                    } catch (_) {}
                    let resp = null;
                    for (let attempt = 0; attempt < 2; attempt++) {
                        try {
                            const credMode = isSameOrigin
                                ? (attempt === 0 ? 'same-origin' : 'omit')
                                : (attempt === 0 ? 'omit' : 'include');
                            resp = await fetch(targetUrl, {
                                method: 'GET',
                                credentials: credMode,
                                cache: 'default',
                                signal: controller.signal
                            });
                            break;
                        } catch (e) {
                            if (attempt === 1) throw e;
                        }
                        await new Promise(r => setTimeout(r, 400));
                    }
                    if (!resp || !resp.ok) {
                        clearTimeout(timer);
                        window.LinDoJsBridge.onBinaryResult(${JSONObject.quote(reqId)}, resp ? resp.status : 0, '', '');
                        return;
                    }
                    const ct = (resp.headers.get('Content-Type') || 'image/png').toLowerCase();
                    if (ct.includes('text/html')) {
                        clearTimeout(timer);
                        const htmlFailStatus = resp.status >= 400 ? resp.status : 415;
                        window.LinDoJsBridge.onBinaryResult(${JSONObject.quote(reqId)}, htmlFailStatus, ct, '');
                        return;
                    }
                    const clHeader = parseInt(resp.headers.get('Content-Length') || '0', 10);
                    if (!isNaN(clHeader) && clHeader > $maxBridgeBytes) {
                        clearTimeout(timer);
                        window.LinDoJsBridge.onBinaryResult(${JSONObject.quote(reqId)}, -2, ct, '');
                        return;
                    }
                    const blob = await resp.blob();
                    if (blob.size > $maxBridgeBytes) {
                        clearTimeout(timer);
                        window.LinDoJsBridge.onBinaryResult(${JSONObject.quote(reqId)}, -2, ct, '');
                        return;
                    }
                    const b64 = await new Promise((resolve, reject) => {
                        const reader = new FileReader();
                        reader.onloadend = () => {
                            const res = typeof reader.result === 'string' ? reader.result : '';
                            const comma = res.indexOf(',');
                            resolve(comma >= 0 ? res.substring(comma + 1) : '');
                        };
                        reader.onerror = () => reject(reader.error || new Error('read failed'));
                        reader.readAsDataURL(blob);
                    });
                    clearTimeout(timer);
                    window.LinDoJsBridge.onBinaryResult(${JSONObject.quote(reqId)}, resp.status, ct, b64);
                } catch (err) {
                    clearTimeout(timer);
                    window.LinDoJsBridge.onBinaryResult(${JSONObject.quote(reqId)}, 0, '', '');
                }
            })();
        """.trimIndent()

        webViewBinarySemaphore.acquire()
        val rawTriple = try {
            pendingBinaryCallbacks[reqId] = deferred
            withContext(Dispatchers.Main) {
                activeWv.evaluateJavascript(jsCode, null)
            }
            withTimeoutOrNull(12_000L) {
                deferred.await()
            }
        } finally {
            pendingBinaryCallbacks.remove(reqId)
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
        var wv = apiWebView
        if (wv == null && !isFinishing && !isDestroyed) {
            withContext(Dispatchers.Main) {
                if (apiWebView == null && !isFinishing && !isDestroyed) {
                    initPersistentApiWebView()
                }
                wv = apiWebView
            }
        }
        val activeWv = wv ?: return null
        val isReady = withTimeoutOrNull(4500L) { apiWebViewReady.await() } ?: return null
        if (!isReady) return null

        val reqId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Pair<Int, String>>()
        pendingHttpCallbacks[reqId] = deferred
        val initialCachedCsrf = LinuxDoRepository.getCachedCsrfToken().orEmpty()
        val preUploadCookies = runCatching { CookieManager.getInstance().getCookie("https://linux.do") }.getOrNull().orEmpty()
        val currentCsrfOwner = csrfOwnerTag(preUploadCookies)

        val jsCode = """
            (async function() {
                const controller = new AbortController();
                const timer = setTimeout(() => controller.abort(), 90000);
                let csrfLastStatus = 0;
                let csrfLastBody = '';
                let csrfLastMitigated = '';
                function reportUpload(status, body, cfMitigated) {
                    if (window.LinDoJsBridge && window.LinDoJsBridge.onHttpResultWithMeta) {
                        window.LinDoJsBridge.onHttpResultWithMeta(${JSONObject.quote(reqId)}, status, body || '', cfMitigated || '');
                    } else {
                        window.LinDoJsBridge.onHttpResult(${JSONObject.quote(reqId)}, status, body || '');
                    }
                }
                async function refreshCsrfToken(forceFresh) {
                    if (!forceFresh) {
                        const metaCsrf = document.querySelector('meta[name="csrf-token"]');
                        const cached = window.__lindoCsrf || (metaCsrf && metaCsrf.content);
                        if (cached) return cached;
                    }
                    try {
                        const csrfResp = await fetch('https://linux.do/session/csrf.json', {
                            headers: { 'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest' },
                            credentials: 'include',
                            cache: 'no-store',
                            signal: controller.signal
                        });
                        csrfLastStatus = csrfResp.status || 0;
                        csrfLastMitigated = csrfResp.headers.get('cf-mitigated') || '';
                        if (csrfResp.ok) {
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
                        'X-Requested-With': 'XMLHttpRequest'
                    };
                    const currentOwner = ${JSONObject.quote(currentCsrfOwner)};
                    if (window.__lindoCsrfCookieOwner !== currentOwner) {
                        window.__lindoCsrf = null;
                        window.__lindoCsrfPromise = null;
                        window.__lindoCsrfCookieOwner = currentOwner;
                    }
                    const kotlinCsrf = ${JSONObject.quote(initialCachedCsrf)};
                    if (!kotlinCsrf && !currentOwner) {
                        window.__lindoCsrf = null;
                    } else if (kotlinCsrf && !window.__lindoCsrf) {
                        window.__lindoCsrf = kotlinCsrf;
                    }
                    const csrf = await refreshCsrfToken(false);
                    if (!csrf) {
                        clearTimeout(timer);
                        reportUpload(csrfLastStatus || 0, csrfLastBody, csrfLastMitigated);
                        return;
                    }
                    hdrs['X-CSRF-Token'] = csrf;
                    const b64 = ${JSONObject.quote(base64Bytes)};
                    const binStr = atob(b64);
                    const len = binStr.length;
                    const bytes = new Uint8Array(len);
                    for (let i = 0; i < len; i++) {
                        bytes[i] = binStr.charCodeAt(i);
                    }
                    const blob = new Blob([bytes], { type: ${JSONObject.quote(mimeType)} });
                    function buildFormData() {
                        const fd = new FormData();
                        fd.append('type', 'composer');
                        fd.append('synchronous', 'true');
                        fd.append('files[]', blob, ${JSONObject.quote(fileName)});
                        return fd;
                    }
                    let resp = await fetch('https://linux.do/uploads.json', {
                        method: 'POST',
                        headers: hdrs,
                        body: buildFormData(),
                        credentials: 'include',
                        signal: controller.signal
                    });
                    let text = await resp.text();
                    if ((resp.status === 403 || resp.status === 422) && /csrf|invalid authenticity token|bad csrf/i.test(text)) {
                        window.__lindoCsrf = null;
                        const freshCsrf = await refreshCsrfToken(true);
                        if (freshCsrf) {
                            hdrs['X-CSRF-Token'] = freshCsrf;
                            resp = await fetch('https://linux.do/uploads.json', {
                                method: 'POST',
                                headers: hdrs,
                                body: buildFormData(),
                                credentials: 'include',
                                signal: controller.signal
                            });
                            text = await resp.text();
                        }
                    }
                    clearTimeout(timer);
                    const cfMitigated = (resp && resp.headers && resp.headers.get('cf-mitigated')) || '';
                    reportUpload(resp.status, text, cfMitigated);
                } catch (err) {
                    clearTimeout(timer);
                    reportUpload(0, '', '');
                }
            })();
        """.trimIndent()

        withContext(Dispatchers.Main) {
            val cm = CookieManager.getInstance()
            val cookieStr = cm.getCookie("https://linux.do").orEmpty()
            if (cookieStr.isNotBlank()) {
                CookieSentinel.syncFromAuthoritativeBrowserCookieString(cookieStr)
            }
            activeWv.evaluateJavascript(jsCode, null)
        }

        return try {
            val response = withTimeoutOrNull(90_000L) {
                deferred.await()
            }
            val cm = runCatching { CookieManager.getInstance() }.getOrNull()
            val latestCookies = runCatching { cm?.getCookie("https://linux.do") }.getOrNull().orEmpty()
            if (latestCookies.isNotBlank()) {
                CookieSentinel.syncFromAuthoritativeBrowserCookieString(latestCookies)
                lastObservedCookieSnapshot = latestCookies
                lastCookieFlushEpochMs = System.currentTimeMillis()
            }
            runCatching { cm?.flush() }
            response
        } finally {
            pendingHttpCallbacks.remove(reqId)
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
            // 仅当用户存在已保存账号且 WebKit 意外丢失 `_t` 时才回填 host-only 会话 Cookie。
            val webKitCookies = cookieManager.getCookie("https://linux.do").orEmpty()
            val savedCookies = prefs.getString("raw_cookies", "").orEmpty()
            val hasLiveWebKitSession = hasCookieNamed(webKitCookies, "_t")
            if (!hasLiveWebKitSession && userProfile != null && savedCookies.contains("_t=") && hasCookieNamed(savedCookies, "_t")) {
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
            val authoritativeCookies = cookieManager.getCookie("https://linux.do").orEmpty()

            UserApiKeyAuthManager.restorePersistedSession(
                profile = userProfile,
                apiKey = savedApiKey,
                rawCookies = authoritativeCookies
            )
        }
    }

    private fun persistSessionToDisk() {
        runCatching {
            val cookieManager = CookieManager.getInstance()
            cookieManager.flush()
            val latestWebKitCookies = cookieManager.getCookie("https://linux.do").orEmpty()
            CookieSentinel.syncFromAuthoritativeBrowserCookieString(latestWebKitCookies)

            val editor = prefs.edit()
            val user = UserApiKeyAuthManager.currentUser?.takeIf { it.username != "linuxdo_user" }
            val apiKey = UserApiKeyAuthManager.getApiKey()
            val mergedCookieHeader = CookieSentinel.buildCookieHeader(targetHost = "linux.do")
            if ((user != null || !apiKey.isNullOrBlank()) && mergedCookieHeader.isNotBlank()) {
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
            apiWebView?.reload()
            withContext(Dispatchers.IO) { LinuxDoRepository.fetchCurrentUserProfile() }
            persistSessionToDisk()
            UserApiKeyAuthManager.onAuthStateChanged?.invoke()
        } finally {
            withContext(Dispatchers.IO) { source.delete() }
        }
    }

    override fun onDestroy() {
        activityScope.cancel()
        pendingMediaPickCallback = null
        pendingWebFileChooserCallback?.onReceiveValue(null)
        pendingWebFileChooserCallback = null
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
            UserApiKeyAuthManager.onSyncCookieToPlatform = null
            UserApiKeyAuthManager.onLogoutPlatformClear = null
            UserApiKeyAuthManager.webViewHttpExecutor = null
            UserApiKeyAuthManager.webViewJsonFetcher = null
            UserApiKeyAuthManager.webViewBinaryFetcher = null
            PlatformMediaPicker.launchPicker = null
            PlatformMediaPicker.webViewUploadExecutor = null
            PlatformWebLoginHost.webLoginRenderer = null
        }
        apiWebView?.let { releaseNativeWebView(it) }
        apiWebView = null
        super.onDestroy()
        iconManager.onActivityDestroyed()
    }

    override fun onResume() {
        super.onResume()
        UserPresenceService.setAppForeground(true)
        requestHighRefreshRateDisplay()
        runCatching {
            apiWebView?.onResume()
            apiWebView?.resumeTimers()
        }
    }

    override fun onPause() {
        UserPresenceService.setAppForeground(false)
        runCatching {
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
        val avatarUrl = LinuxDoSiteRules.resolveUrl(rawTpl)
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

    fun tryExtractSessionAndComplete(webView: WebView?) {
        if (completed || webView == null) return
        val cookieManager = CookieManager.getInstance()
        val rawCookies = cookieManager.getCookie("https://linux.do").orEmpty()
        if (rawCookies.isNotBlank()) {
            CookieSentinel.syncFromAuthoritativeBrowserCookieString(rawCookies)
        }

        // 若当前在 /session/otp/ 兑换页，仅在此路径下自动触发表单 POST 提交
        if (webView.url?.contains("/session/otp/") == true) {
            webView.evaluateJavascript(AUTO_SUBMIT_OTP_FORM_JS, null)
        }

        // 非登录流（如应用广场、聊天、看板、AI 机器人、Connect/Credit/CDK/Lottery、偏好设置等内置容器模式）：
        // 仅保持 Cookie 同步，不自动关闭窗口
        if (!isLoginFlow) {
            return
        }

        // 在 /user-api-key/ 授权确认页（如 /user-api-key/new）绝不自动关闭弹窗，等待用户点击“授权”按钮触发回调！
        val currentPath = runCatching { Uri.parse(webView.url ?: pendingUrl.orEmpty()).path.orEmpty() }.getOrDefault("")
        if (currentPath.startsWith("/user-api-key/")) {
            return
        }

        // 关键防风控守卫：在原生 CookieManager 尚未检测到真实 Discourse `_t` 凭证前，
        // 严禁向页面注入任何探测脚本或发起轮询请求，保持 `/login` 与 Cloudflare Turnstile 页面 100% 纯净零干扰！
        val currentTCookie = getCookieValue(rawCookies, "_t").takeUnless { it == "demo_session_token" }.orEmpty()
        if (currentTCookie.isEmpty()) {
            return
        }
        // 若用户原本已登录（如打开 /login 完成安全验证或切换账号），在 `_t` 未更新前绝不秒关窗口！
        if (wasAlreadyAuthorizedOnOpen && initialUrl.contains("/login") && currentTCookie == initialTCookie) {
            return
        }

        // 已检测到 `_t` 会话 Cookie：先确认当前并非 Cloudflare 验证挑战页，再无副作用提取当前用户资料与 CSRF Token
        val probeJs = """
            (function() {
                try {
                    var title = document.title || '';
                    if (/Just a moment|请稍候|Attention Required/i.test(title) ||
                        document.querySelector('#challenge-running, #challenge-stage, .cf-turnstile, iframe[src*="challenges.cloudflare.com"]')) {
                        return '__CF_CHALLENGE__';
                    }
                    if (window.location.pathname.indexOf('/login') === 0 ||
                        window.location.pathname.indexOf('/user-api-key/') === 0) {
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
                    if (window.Discourse && Discourse.User && Discourse.User.current) {
                        var du = Discourse.User.current();
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
                return@evaluateJavascript
            }
            if (decoded != null) {
                runCatching {
                    val obj = JSONObject(decoded)
                    val csrf = obj.optString("__csrf").trim()
                    if (csrf.isNotEmpty()) {
                        LinuxDoRepository.updateCsrfToken(csrf)
                    }
                    completeWithUserObject(obj)
                }
            } else if (!completed && hasCookieNamed(rawCookies, "_t") && currentTCookie != initialTCookie) {
                // 全新 `_t` Cookie 已落盘但 DOM 尚未渲染新用户资料：严禁携带旧账号 currentUser 直接关闭，
                // 在同源上下文单飞请求 `/session/current.json` 确认新账号资料后再调用 completeWithUserObject！
                webView.evaluateJavascript(
                    """
                    (function() {
                        if (window.location.origin !== 'https://linux.do') return;
                        if (window.__lindoCurrentUserProbeInFlight) return;
                        window.__lindoCurrentUserProbeInFlight = true;
                        var owner = ${JSONObject.quote(expectedOwnerTag)};
                        fetch('/session/current.json', {
                            headers: { 'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest' },
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
    LaunchedEffect(initialUrl, webViewRef) {
        val wv = webViewRef ?: return@LaunchedEffect
        if (initialUrl.isNotBlank() && wv.url != initialUrl) {
            pendingUrl = initialUrl
            pageLoadError = null
            wv.loadUrl(initialUrl)
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
            webViewRef = null
        }
    }

    val barBg = if (isDark) androidx.compose.ui.graphics.Color(0xFF13161C) else androidx.compose.ui.graphics.Color(0xFFFFFFFF)
    val barFg = if (isDark) androidx.compose.ui.graphics.Color(0xFFF3F4F6) else androidx.compose.ui.graphics.Color(0xFF111827)
    val barSubFg = if (isDark) androidx.compose.ui.graphics.Color(0xFFA1A8B5) else androidx.compose.ui.graphics.Color(0xFF525866)
    val barBorder = if (isDark) androidx.compose.ui.graphics.Color(0xFF232833) else androidx.compose.ui.graphics.Color(0xFFEDEFF3)
    val currentPageTitle = remember(initialUrl, isLoginFlow) {
        val host = runCatching { Uri.parse(initialUrl).host }.getOrNull().orEmpty()
        when {
            isLoginFlow -> "LINUX DO 登录与安全验证"
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
                                        pendingUrl = url
                                        pageLoadError = null
                                    }
                                    tryExtractSessionAndComplete(view)
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    tryExtractSessionAndComplete(view)
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
