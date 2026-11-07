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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import do_.linux.lindo.core.network.AuthenticatedUserProfile
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
import do_.linux.lindo.ui.LinDoWelcomeScreen
import do_.linux.lindo.core.connect.UserPresenceService
import do_.linux.lindo.ui.PlatformWebLoginHost
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.cancel

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
    private var debugPreviewGeneration by mutableStateOf(0)

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val prefs by lazy {
        KeystorePreferences(this)
    }

    // 常驻同源 Chromium WebView 引擎：共享 BoringSSL JA3/JA4 指纹、cf_clearance、_t 与 CSRF Token
    private var apiWebView: WebView? = null
    private var apiWebViewReady = CompletableDeferred<Boolean>()
    private val pendingHttpCallbacks = ConcurrentHashMap<String, CompletableDeferred<Pair<Int, String>>>()
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
            runCatching {
                val cr = contentResolver
                val mime = cr.getType(uri) ?: "image/png"
                var displayName = "upload_${System.currentTimeMillis()}"
                cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) {
                            val name = cursor.getString(idx)
                            if (!name.isNullOrBlank()) displayName = name
                        }
                    }
                }
                val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                if (bytes.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        callback(displayName, mime, bytes)
                    }
                }
            }
        }
    }

    inner class LinDoJsBridge {
        @JavascriptInterface
        fun onHttpResult(reqId: String, status: Int, body: String) {
            pendingHttpCallbacks.remove(reqId)?.complete(status to body)
        }

        @JavascriptInterface
        fun onPreloadedUserDetected(userJson: String) {
            if (userJson.isBlank()) return
            runCatching {
                val u = JSONObject(userJson)
                val username = u.optString("username").trim()
                if (username.isNotBlank() && username != "linuxdo_user") {
                    val userId = u.optInt("id", 0)
                    val displayName = u.optString("name").ifBlank { username }
                    val rawTpl = u.optString("avatar_template")
                    val avatarUrl = if (rawTpl.isNotBlank()) {
                        if (rawTpl.startsWith("http")) rawTpl.replace("{size}", "240")
                        else "https://linux.do${rawTpl.replace("{size}", "240")}"
                    } else ""
                    val trustLevel = u.optInt("trust_level", 1)
                    val title = u.optString("title", "")
                    activityScope.launch {
                        val rawCookies = CookieManager.getInstance().getCookie("https://linux.do").orEmpty()
                        if (rawCookies.isNotBlank()) {
                            CookieSentinel.ingestRawCookieString(rawCookies)
                        }
                        UserApiKeyAuthManager.recordWebSessionLogin(
                            username = username,
                            displayName = displayName,
                            avatarUrl = avatarUrl,
                            trustLevel = trustLevel,
                            userId = userId,
                            userTitle = title
                        )
                        persistSessionToDisk()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                Color.TRANSPARENT,
                Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.light(
                Color.TRANSPARENT,
                Color.TRANSPARENT
            )
        )
        super.onCreate(savedInstanceState)

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

        // 2. 初始化常驻同源 WebView API 桥接引擎
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
                    .putFloat("ui_font_scale", fontScale)
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
                prefs.edit()
                    .remove("api_key")
                    .remove("username")
                    .remove("display_name")
                    .remove("avatar_template")
                    .remove("animated_avatar")
                    .remove("user_id")
                    .remove("user_title")
                    .remove("trust_level")
                    .putString("raw_cookies", CookieSentinel.buildCookieHeader(targetHost = "linux.do"))
                    .apply()
            }
        }

        // 5. 注册内置 WebView 登录器
        PlatformWebLoginHost.webLoginRenderer = { initialUrl, onDismiss, onLoginSuccess ->
            AndroidWebLoginModal(
                initialUrl = initialUrl,
                onDismiss = onDismiss,
                onLoginSuccess = {
                    persistSessionToDisk()
                    // 同步最新 Cookie 至内存守卫（严禁调用 apiWebView.reload() 整页导航，避免 1x1 后台视口触发 Cloudflare 风控）
                    val latestCookies = CookieManager.getInstance().getCookie("https://linux.do").orEmpty()
                    if (latestCookies.isNotBlank()) {
                        CookieSentinel.ingestRawCookieString(latestCookies)
                    }
                    activityScope.launch {
                        LinuxDoRepository.fetchCurrentUserProfile()
                        persistSessionToDisk()
                    }
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
        UserApiKeyAuthManager.webViewConnectHtmlFetcher = {
            fetchConnectHtmlViaWebView()
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
                InstalledIconPreview()
            } else if (debugPreview == "welcome") {
                var dark by remember { mutableStateOf(false) }
                LaunchedEffect(dark) { updateSystemBars(dark) }
                LinDoWelcomeScreen(isDark = dark, onPrimaryWebLogin = closePreview,
                    onBrowserOneClickAuth = closePreview, onToggleDark = { dark = !dark })
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
                LinDoApp(onThemeChanged = updateSystemBars, systemAnimationsEnabled = systemAnimationsEnabled)
            }
        }

        // Explicit debug-only, app-private import. Never put session values in Intent extras or logs.
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
            intent.getBooleanExtra("lindo.import_cookies", false)) {
            activityScope.launch {
                importPrivateDebugCookies()
            }
        }

        // Attach transparent apiWebView with realistic mobile viewport dimensions so Chromium keeps timers & fetch active
        // without ever exposing a 1x1 headless viewport dimension to scripts.
        window.decorView.post {
            runCatching {
                val root = window.decorView.findViewById<ViewGroup>(android.R.id.content)
                val wv = apiWebView
                if (root != null && wv != null && wv.parent == null) {
                    val dm = resources.displayMetrics
                    val lp = FrameLayout.LayoutParams(
                        dm.widthPixels.coerceAtLeast(360),
                        dm.heightPixels.coerceAtLeast(640)
                    )
                    wv.alpha = 0f
                    wv.isClickable = false
                    wv.isFocusable = false
                    wv.visibility = View.VISIBLE
                    root.addView(wv, 0, lp)
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initPersistentApiWebView() {
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
                    releaseNativeWebView(view, rendererGone = true)
                    apiWebViewReady = CompletableDeferred()
                    activityScope.launch {
                        delay(250L)
                        if (!isFinishing && !isDestroyed) {
                            initPersistentApiWebView()
                            val fresh = apiWebView
                            val parent = window.decorView.findViewById<ViewGroup>(android.R.id.content)
                            if (fresh != null && fresh.parent == null && parent != null) {
                                val dm = resources.displayMetrics
                                fresh.alpha = 0f
                                fresh.isClickable = false
                                fresh.isFocusable = false
                                fresh.visibility = View.VISIBLE
                                parent.addView(
                                    fresh,
                                    0,
                                    FrameLayout.LayoutParams(
                                        dm.widthPixels.coerceAtLeast(360),
                                        dm.heightPixels.coerceAtLeast(640)
                                    )
                                )
                            }
                        }
                    }
                    return true
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    val cookies = cm.getCookie("https://linux.do").orEmpty()
                    if (cookies.isNotBlank()) {
                        CookieSentinel.ingestRawCookieString(cookies)
                    }
                    if (!apiWebViewReady.isCompleted) {
                        apiWebViewReady.complete(true)
                    }
                }
            }
            apiWebView = wv
            // 使用轻量级同源空文档初始化 https://linux.do 上下文（historyUrl 传 https://linux.do/ 确保 SiteForCookies 与 SameSite=Lax Cookie 100% 生效）
            wv.loadDataWithBaseURL(
                "https://linux.do/",
                "<!DOCTYPE html><html><head><meta charset=\"utf-8\"></head><body></body></html>",
                "text/html",
                "UTF-8",
                "https://linux.do/"
            )
        }
    }

    private suspend fun executeHttpViaWebViewBridge(
        url: String,
        method: String,
        formParams: Map<String, String>?,
        headers: Map<String, String>
    ): Pair<Int, String>? {
        val wv = apiWebView ?: return null
        withTimeoutOrNull(3500L) { apiWebViewReady.await() }

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

        val jsCode = """
            (async function() {
                const controller = new AbortController();
                const timer = setTimeout(() => controller.abort(), 10000);
                try {
                    const method = ${JSONObject.quote(method.uppercase())};
                    const hdrs = $headersJson;
                    hdrs['Accept'] = 'application/json, text/javascript, */*; q=0.01';
                    hdrs['X-Requested-With'] = 'XMLHttpRequest';
                    async function refreshCsrfToken() {
                        try {
                            const csrfResp = await fetch('https://linux.do/session/csrf.json', {
                                headers: { 'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest' },
                                credentials: 'include',
                                cache: 'no-store',
                                signal: controller.signal
                            });
                            if (csrfResp.ok) {
                                const csrfData = await csrfResp.json();
                                if (csrfData && csrfData.csrf) {
                                    window.__lindoCsrf = csrfData.csrf;
                                    return csrfData.csrf;
                                }
                            }
                        } catch (_) {}
                        return null;
                    }
                    if (method !== 'GET' && method !== 'HEAD') {
                        const metaCsrf = document.querySelector('meta[name="csrf-token"]');
                        const cachedCsrf = (metaCsrf && metaCsrf.content) ? metaCsrf.content : window.__lindoCsrf;
                        const token = cachedCsrf || (await refreshCsrfToken());
                        if (token) hdrs['X-CSRF-Token'] = token;
                    }
                    const bodyStr = ${JSONObject.quote(formBodyEncoded)};
                    if (method !== 'GET' && method !== 'HEAD') {
                        hdrs['Content-Type'] = 'application/x-www-form-urlencoded; charset=UTF-8';
                    }
                    function buildOpts() {
                        const o = {
                            method: method,
                            headers: Object.assign({}, hdrs),
                            credentials: 'include',
                            cache: 'no-store',
                            signal: controller.signal
                        };
                        if (method !== 'GET' && method !== 'HEAD') {
                            o.body = bodyStr;
                        }
                        return o;
                    }
                    let resp = await fetch(${JSONObject.quote(url)}, buildOpts());
                    let text = await resp.text();
                    if (method !== 'GET' && method !== 'HEAD' && (resp.status === 403 || (resp.status === 422 && text.indexOf('CSRF') >= 0))) {
                        const freshToken = await refreshCsrfToken();
                        if (freshToken) {
                            hdrs['X-CSRF-Token'] = freshToken;
                            resp = await fetch(${JSONObject.quote(url)}, buildOpts());
                            text = await resp.text();
                        }
                    }
                    clearTimeout(timer);
                    window.LinDoJsBridge.onHttpResult(${JSONObject.quote(reqId)}, resp.status, text);
                } catch (err) {
                    clearTimeout(timer);
                    window.LinDoJsBridge.onHttpResult(${JSONObject.quote(reqId)}, 0, '');
                }
            })();
        """.trimIndent()

        withContext(Dispatchers.Main) {
            val cm = CookieManager.getInstance()
            val cookieStr = cm.getCookie("https://linux.do").orEmpty()
            val sentinelWinners = CookieSentinel.resolveWinningCookies(targetHost = "linux.do")
            val sentinelT = sentinelWinners["_t"]?.value
            if (!sentinelT.isNullOrBlank() && sentinelT != "demo_session_token" && !cookieStr.contains("_t=")) {
                cm.setCookie("https://linux.do", "_t=$sentinelT; Path=/; Secure; HttpOnly; SameSite=Lax")
                sentinelWinners["_forum_session"]?.value?.takeIf { it.isNotBlank() }?.let { fs ->
                    cm.setCookie("https://linux.do", "_forum_session=$fs; Path=/; Secure; HttpOnly; SameSite=Lax")
                }
                cm.flush()
            } else if (cookieStr.isNotBlank()) {
                CookieSentinel.ingestRawCookieString(cookieStr)
            }
            wv.evaluateJavascript(jsCode, null)
        }

        return try {
            val res = withTimeoutOrNull(11000L) {
                deferred.await()
            }
            val latestCookies = runCatching { CookieManager.getInstance().getCookie("https://linux.do") }.getOrNull().orEmpty()
            if (latestCookies.isNotBlank()) {
                CookieSentinel.ingestRawCookieString(latestCookies)
            }
            res
        } finally {
            pendingHttpCallbacks.remove(reqId)
        }
    }

    private suspend fun executeMultipartUploadViaWebViewBridge(
        fileName: String,
        mimeType: String,
        base64Bytes: String
    ): Pair<Int, String>? {
        val wv = apiWebView ?: return null
        withTimeoutOrNull(3500L) { apiWebViewReady.await() }

        val reqId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Pair<Int, String>>()
        pendingHttpCallbacks[reqId] = deferred

        val jsCode = """
            (async function() {
                const controller = new AbortController();
                const timer = setTimeout(() => controller.abort(), 14000);
                try {
                    const hdrs = {
                        'Accept': 'application/json, text/javascript, */*; q=0.01',
                        'X-Requested-With': 'XMLHttpRequest'
                    };
                    const metaCsrf = document.querySelector('meta[name="csrf-token"]');
                    if (metaCsrf && metaCsrf.content) {
                        hdrs['X-CSRF-Token'] = metaCsrf.content;
                    } else {
                        try {
                            const csrfResp = await fetch('https://linux.do/session/csrf.json', {
                                headers: { 'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest' },
                                credentials: 'include',
                                signal: controller.signal
                            });
                            if (csrfResp.ok) {
                                const csrfData = await csrfResp.json();
                                if (csrfData && csrfData.csrf) hdrs['X-CSRF-Token'] = csrfData.csrf;
                            }
                        } catch (_) {}
                    }
                    const b64 = ${JSONObject.quote(base64Bytes)};
                    const binStr = atob(b64);
                    const len = binStr.length;
                    const bytes = new Uint8Array(len);
                    for (let i = 0; i < len; i++) {
                        bytes[i] = binStr.charCodeAt(i);
                    }
                    const blob = new Blob([bytes], { type: ${JSONObject.quote(mimeType)} });
                    const fd = new FormData();
                    fd.append('type', 'composer');
                    fd.append('synchronous', 'true');
                    fd.append('files[]', blob, ${JSONObject.quote(fileName)});
                    const resp = await fetch('https://linux.do/uploads.json', {
                        method: 'POST',
                        headers: hdrs,
                        body: fd,
                        credentials: 'include',
                        signal: controller.signal
                    });
                    const text = await resp.text();
                    clearTimeout(timer);
                    window.LinDoJsBridge.onHttpResult(${JSONObject.quote(reqId)}, resp.status, text);
                } catch (err) {
                    clearTimeout(timer);
                    window.LinDoJsBridge.onHttpResult(${JSONObject.quote(reqId)}, 0, '');
                }
            })();
        """.trimIndent()

        withContext(Dispatchers.Main) {
            wv.evaluateJavascript(jsCode, null)
        }

        return try {
            withTimeoutOrNull(15000L) {
                deferred.await()
            }
        } finally {
            pendingHttpCallbacks.remove(reqId)
        }
    }

    /**
     * 通过共享 CookieManager 的后台 WebView 拉取 `https://connect.linux.do/` HTML：
     * - 若尚未建立 connect.linux.do 会话且页面出现 `/login`，自动跟随 `/login` -> `linux.do/oauth2/authorize` 静默换取会话并读取真实 Connect 表格
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun fetchConnectHtmlViaWebView(): String? {
        val deferred = CompletableDeferred<String?>()
        var tempWv: WebView? = null
        var redirectedLogin = false

        withContext(Dispatchers.Main) {
            runCatching {
                val wv = WebView(this@MainActivity)
                tempWv = wv
                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
                cm.setAcceptThirdPartyCookies(wv, true)
                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    // 保持原生 User-Agent 与 Sec-CH-UA 完全一致
                }
                wv.webViewClient = object : WebViewClient() {
                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        tempWv = null
                        releaseNativeWebView(view, rendererGone = true)
                        if (!deferred.isCompleted) deferred.complete(null)
                        return true
                    }
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        val currentUrl = url.orEmpty()
                        view?.evaluateJavascript(
                            """(function(){ return JSON.stringify({html: document.documentElement?.outerHTML || '',
                                hasMetrics: !!document.querySelector('.tl3-ring, .tl3-bar-item, table td')}); })()"""
                        ) { rawJsResult ->
                            val snapshot = runCatching {
                                if (!rawJsResult.isNullOrBlank() && rawJsResult != "null") {
                                    JSONObject(org.json.JSONTokener(rawJsResult).nextValue().toString())
                                } else JSONObject()
                            }.getOrDefault(JSONObject())
                            val unescaped = snapshot.optString("html")

                            if (snapshot.optBoolean("hasMetrics")) {
                                val syncCookies = cm.getCookie("https://connect.linux.do")
                                if (!syncCookies.isNullOrBlank()) {
                                    CookieSentinel.ingestRawCookieString(syncCookies, domain = "connect.linux.do", hostOnly = false)
                                }
                                if (!deferred.isCompleted) deferred.complete(unescaped)
                            } else if (
                                !redirectedLogin &&
                                currentUrl.startsWith("https://connect.linux.do") &&
                                (unescaped.contains("/login") || unescaped.contains("登录"))
                            ) {
                                redirectedLogin = true
                                view.loadUrl("https://connect.linux.do/login")
                            } else if (currentUrl.contains("/oauth2/authorize")) {
                                // 若停留在 Discourse OAuth2 授权确认页，自动点击授权按钮
                                view.evaluateJavascript(
                                    """
                                    (function(){
                                        var btn = document.querySelector('form button[type="submit"], a.btn-primary, button.btn-primary');
                                        if (btn) btn.click();
                                    })();
                                    """.trimIndent(),
                                    null
                                )
                            } else if (redirectedLogin && currentUrl.startsWith("https://connect.linux.do")) {
                                if (!deferred.isCompleted) deferred.complete(unescaped)
                            }
                        }
                    }
                }
                // Attach the temporary browser with realistic mobile viewport dimensions so Cloudflare never sees a 1x1 viewport.
                val parent = window.decorView.findViewById<ViewGroup>(android.R.id.content)
                val dm = resources.displayMetrics
                wv.alpha = 0f
                wv.isClickable = false
                wv.isFocusable = false
                wv.visibility = View.VISIBLE
                parent.addView(
                    wv,
                    0,
                    FrameLayout.LayoutParams(
                        dm.widthPixels.coerceAtLeast(360),
                        dm.heightPixels.coerceAtLeast(640)
                    )
                )
                wv.loadUrl("https://connect.linux.do/")
            }.onFailure {
                if (!deferred.isCompleted) deferred.complete(null)
            }
        }

        return try {
            withTimeoutOrNull(15_000L) {
                deferred.await()
            }
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                runCatching {
                    tempWv?.let(::releaseNativeWebView)
                    tempWv = null
                }
            }
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
            val defaultUa = WebSettings.getDefaultUserAgent(this)
                .replace("; wv", "")
            UserApiKeyAuthManager.customUserAgent = prefs.getString("user_agent", defaultUa) ?: defaultUa

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

            // 恢复用户的阅读密度/字号、深色模式、动效与主题色偏好（默认官方黑白黄）
            UserApiKeyAuthManager.uiFontScale = prefs.getFloat("ui_font_scale", 1.0f)
            UserApiKeyAuthManager.uiDarkTheme = prefs.getBoolean("ui_dark_theme", false)
            UserApiKeyAuthManager.uiEnableAnimations = prefs.getBoolean("ui_enable_animations", true)
            UserApiKeyAuthManager.uiThemeAccent = prefs.getString("ui_theme_accent", "OFFICIAL_GOLD") ?: "OFFICIAL_GOLD"
            UserApiKeyAuthManager.uiCompactFeed = prefs.getBoolean("ui_compact_feed", false)

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            val webKitCookies = cookieManager.getCookie("https://linux.do").orEmpty()
            val savedCookies = prefs.getString("raw_cookies", "").orEmpty()
            val hasLiveWebKitSession = webKitCookies.contains("_t=") && !webKitCookies.contains("_t=demo_session_token")
            val mergedCookies = if (hasLiveWebKitSession) {
                webKitCookies
            } else {
                savedCookies.ifBlank { webKitCookies }
            }
            // 将持久化在磁盘中的会话 Cookie 反向写回系统 CookieManager，严格保持原生的 HttpOnly 与 Host-only/Domain 边界：
            // 1) _t 与 _forum_session 为 Host-only + HttpOnly Cookie（仅当系统 CookieManager 缺失会话时写回，避免误删在线会话）；
            // 2) cf_clearance / __cf_bm / _cfuvid 为 .linux.do + HttpOnly Cookie（必须带 HttpOnly，严禁让 document.cookie 读到明文 cf_clearance 触发 Turnstile 重验）。
            if (mergedCookies.isNotBlank()) {
                val cfCookieNames = setOf("cf_clearance", "__cf_bm", "_cfuvid", "cf_chl_rc_i", "cf_chl_rc_ni")
                val discourseHostOnlyNames = setOf("_t", "_forum_session")
                val dedupedPairs = LinkedHashMap<String, String>()
                mergedCookies.split(";").forEach { segment ->
                    val pair = segment.trim()
                    if (pair.contains("=") && !pair.startsWith("_t=demo_session_token")) {
                        val name = pair.substringBefore("=").trim()
                        val value = pair.substringAfter("=").trim()
                        if (name.isNotEmpty() && value.isNotEmpty()) {
                            dedupedPairs[name] = "$name=$value"
                        }
                    }
                }
                dedupedPairs.forEach { (name, pair) ->
                    when (name) {
                        in discourseHostOnlyNames -> {
                            if (!hasLiveWebKitSession) {
                                cookieManager.setCookie("https://linux.do", "$pair; Path=/; Secure; HttpOnly; SameSite=Lax")
                            }
                        }
                        in cfCookieNames -> {
                            // 仅当系统 CookieManager 尚未持有该 Cloudflare 凭证时才以 HttpOnly 恢复，避免破坏原生的 Partitioned 属性
                            if (!webKitCookies.contains("$name=")) {
                                cookieManager.setCookie("https://linux.do", "$pair; Domain=.linux.do; Path=/; Secure; HttpOnly; SameSite=None")
                            }
                        }
                        else -> {
                            if (!webKitCookies.contains("$name=")) {
                                cookieManager.setCookie("https://linux.do", "$pair; Path=/; Secure")
                            }
                        }
                    }
                }
                cookieManager.flush()
            }

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

            UserApiKeyAuthManager.restorePersistedSession(
                profile = userProfile,
                apiKey = savedApiKey,
                rawCookies = mergedCookies
            )
        }
    }

    private fun persistSessionToDisk() {
        runCatching {
            val cookieManager = CookieManager.getInstance()
            cookieManager.flush()
            val latestWebKitCookies = cookieManager.getCookie("https://linux.do").orEmpty()
            if (latestWebKitCookies.isNotBlank()) {
                CookieSentinel.ingestRawCookieString(latestWebKitCookies)
            }

            val editor = prefs.edit()
            val mergedCookieHeader = CookieSentinel.buildCookieHeader().ifBlank { latestWebKitCookies }
            if (mergedCookieHeader.isNotBlank()) {
                editor.putString("raw_cookies", mergedCookieHeader)
            }
            val apiKey = UserApiKeyAuthManager.getApiKey()
            editor.putString("api_key", apiKey)
            val pendingPrivKey = UserApiKeyAuthManager.exportPendingPrivateKeyBase64()
            editor.putString("pending_rsa_priv_b64", pendingPrivKey)
            editor.putString("client_id", UserApiKeyAuthManager.getClientId())
            editor.putString("user_agent", UserApiKeyAuthManager.customUserAgent)
            val user = UserApiKeyAuthManager.currentUser
            if (user != null && user.username != "linuxdo_user") {
                editor.putInt("user_id", user.userId)
                editor.putString("username", user.username)
                editor.putString("display_name", user.displayName)
                editor.putString("avatar_template", user.avatarTemplate)
                editor.putString("animated_avatar", user.animatedAvatar)
                editor.putString("user_title", user.userTitle)
                editor.putInt("trust_level", user.trustLevel)
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
        do_.linux.lindo.core.compose.LocalComposerDrafts.onPersist = null
        UserApiKeyAuthManager.onPersistPendingKey = null
        UserApiKeyAuthManager.openExternalUrlLauncher = null
        UserApiKeyAuthManager.onPersistSession = null
        UserApiKeyAuthManager.onPersistUiPrefs = null
        UserApiKeyAuthManager.onPersistThemeAndDensity = null
        UserApiKeyAuthManager.onSyncCookieToPlatform = null
        UserApiKeyAuthManager.onLogoutPlatformClear = null
        UserApiKeyAuthManager.webViewHttpExecutor = null
        UserApiKeyAuthManager.webViewJsonFetcher = null
        UserApiKeyAuthManager.webViewConnectHtmlFetcher = null
        PlatformMediaPicker.launchPicker = null
        PlatformMediaPicker.webViewUploadExecutor = null
        PlatformWebLoginHost.webLoginRenderer = null
        apiWebView?.let { releaseNativeWebView(it) }
        apiWebView = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        UserPresenceService.setAppForeground(true)
        if (android.os.Build.VERSION.SDK_INT >= 35) {
            window.decorView.requestedFrameRate = View.REQUESTED_FRAME_RATE_CATEGORY_HIGH
        }
    }

    override fun onPause() {
        UserPresenceService.setAppForeground(false)
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

/**
 * 从 Discourse `<data id="data-preloaded">` 或 `/session/current.json` 提取 100% 真实的 `currentUser` JSON
 */
private val EXTRACT_PRELOADED_USER_JS = """
    (async function() {
        try {
            var el = document.getElementById('data-preloaded');
            if (el && el.dataset && el.dataset.preloaded) {
                var p = JSON.parse(el.dataset.preloaded);
                if (p && p.currentUser) {
                    var cu = JSON.parse(p.currentUser);
                    if (cu && cu.username) {
                        window.LinDoJsBridge.onPreloadedUserDetected(JSON.stringify(cu));
                        return;
                    }
                }
            }
            if (window.location.pathname.indexOf('/session/otp/') === 0 ||
                window.location.pathname.indexOf('/user-api-key/') === 0) {
                return;
            }
            var resp = await fetch('/session/current.json', {
                headers: { 'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest' },
                credentials: 'include'
            });
            if (resp.ok) {
                var data = await resp.json();
                if (data && data.current_user && data.current_user.username) {
                    window.LinDoJsBridge.onPreloadedUserDetected(JSON.stringify(data.current_user));
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
    var pageLoadError by remember(initialUrl) { mutableStateOf<String?>(null) }
    var rendererGone by remember { mutableStateOf(false) }
    BackHandler {
        if (webViewRef?.canGoBack() == true) webViewRef?.goBack() else onDismiss()
    }
    val isDark = remember { UserApiKeyAuthManager.uiDarkTheme }


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
            CookieSentinel.ingestRawCookieString(rawCookies)
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

        // 关键防风控守卫：在原生 CookieManager 尚未检测到 Discourse `_t=` 登录凭证前，
        // 严禁向页面注入任何探测脚本或发起 `/session/current.json` (404) 轮询请求，
        // 保持 `/login` 与 Cloudflare Turnstile 页面 100% 纯净零干扰！
        if (!rawCookies.contains("_t=")) {
            return
        }

        // 已检测到 `_t=` 会话 Cookie：通过单次无副作用 evaluateJavascript 提取当前用户资料
        val probeJs = """
            (function() {
                try {
                    var el = document.getElementById('data-preloaded');
                    if (el && el.dataset && el.dataset.preloaded) {
                        var p = JSON.parse(el.dataset.preloaded);
                        if (p && p.currentUser) {
                            var u = JSON.parse(p.currentUser);
                            if (u && u.username) {
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
                                title: du.title || '',
                                trust_level: typeof du.trust_level === 'number' ? du.trust_level : 1
                            });
                        }
                    }
                } catch (e) {}
                return '';
            })();
        """.trimIndent()

        webView.evaluateJavascript(probeJs) { rawJson ->
            if (completed) return@evaluateJavascript
            val decoded = decodeJsStringResult(rawJson)?.takeIf { it.isNotBlank() }
            if (decoded != null) {
                runCatching {
                    completeWithUserObject(JSONObject(decoded))
                }
            } else if (!completed && rawCookies.contains("_t=")) {
                // `_t` Cookie 已落盘但 DOM 尚在跳转中：直接关闭登录弹窗并由后台拉取完整 `/session/current.json`
                completed = true
                cookieManager.flush()
                CookieSentinel.ingestRawCookieString(rawCookies, domain = "linux.do", hostOnly = false)
                UserApiKeyAuthManager.onAuthStateChanged?.invoke()
                onLoginSuccess()
            }
        }
    }

    // 当 initialUrl 变化（例如外部浏览器回调后注入 /session/otp/$otp）时立即加载
    LaunchedEffect(initialUrl, webViewRef) {
        val wv = webViewRef ?: return@LaunchedEffect
        if (initialUrl.isNotBlank() && wv.url != initialUrl) {
            wv.loadUrl(initialUrl)
        }
    }

    // 轮询探测登录完成状态（处理 SPA 无刷新跳转或 Passkey/GitHub/OTP 登录完成）
    LaunchedEffect(webViewRef, completed) {
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
    var currentPageTitle by remember(initialUrl) {
        val host = runCatching { Uri.parse(initialUrl).host }.getOrNull().orEmpty()
        mutableStateOf(
            when {
                host.contains("connect.linux.do", ignoreCase = true) -> "LINUX DO Connect"
                host.contains("credit.linux.do", ignoreCase = true) -> "LINUX DO 社区积分"
                else -> host.ifBlank { "LINUX DO" }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(barBg)
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            if (!isLoginFlow) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .background(barBg)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable { onDismiss() },
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = do_.linux.lindo.ui.components.LinDoIcons.Close,
                            contentDescription = "关闭网页",
                            modifier = Modifier.size(18.dp),
                            tint = barFg
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = currentPageTitle,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = barFg,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable {
                                pageLoadError = null
                                rendererGone = false
                                webViewRef?.reload()
                            },
                        contentAlignment = Alignment.Center
                    ) {
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
                        .background(barBorder)
                )
            }

            // Full-screen browser content; system Back returns to LinDo.
            if (!rendererGone) AndroidView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                factory = { ctx ->
                    WebView(ctx).apply {
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
                        // CookieManager already owns the browser session, including domain and path scope.
                        // Rebuilding these as Domain=.linux.do would widen host-only session cookies.
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
                                webViewRef = null
                                rendererGone = true
                                pageLoadError = "网页渲染进程已停止，可以返回应用或重新打开。"
                                releaseNativeWebView(view, rendererGone = true)
                                return true
                            }
                            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                super.onReceivedError(view, request, error)
                                if (request?.isForMainFrame == true) {
                                    pageLoadError = "网络暂时不可用，请检查连接后再试一次。"
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
                                            tryExtractSessionAndComplete(view)
                                        }
                                    }
                                    return true
                                }
                                val scheme = request.url?.scheme?.lowercase().orEmpty()
                                if (scheme.isNotBlank() && scheme != "http" && scheme != "https" && scheme != "about" && scheme != "javascript") {
                                    UserApiKeyAuthManager.openExternalUrlLauncher?.invoke(target)
                                    return true
                                }
                                return false
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
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
                onRelease = { view ->
                    webViewRef = null
                    if (!rendererGone) releaseNativeWebView(view)
                }
            )
        }

        // 登录加载与核验身份过渡层：零度娘「我要验牌」全屏过渡页
        if (pageLoadError != null) {
            LinDoMascotLoginTransitionView(
                isDark = isDark,
                progress = Float.NaN,
                statusTitle = if (rendererGone) "网页已停止运行" else "暂时连接不上 LINUX DO",
                statusSubtitle = pageLoadError.orEmpty(),
                onCancel = onDismiss,
                onRetry = {
                    pageLoadError = null
                    rendererGone = false
                    webViewRef?.reload()
                }
            )
        }
    }
}

private fun decodeJsStringResult(raw: String?): String? {
    if (raw.isNullOrBlank() || raw == "null") return null
    return runCatching {
        JSONObject("{\"v\":$raw}").optString("v")
    }.getOrNull()
}
