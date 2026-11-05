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
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import do_.linux.lindo.core.network.UserApiKeyAuthManager
import do_.linux.lindo.ui.BackNavigationBridge
import do_.linux.lindo.ui.LinDoApp
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

class MainActivity : ComponentActivity() {

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val prefs by lazy {
        getSharedPreferences("lindo_auth_session", Context.MODE_PRIVATE)
    }

    // 常驻同源 Chromium WebView 引擎：共享 BoringSSL JA3/JA4 指纹、cf_clearance、_t 与 CSRF Token
    private var apiWebView: WebView? = null
    private val apiWebViewReady = CompletableDeferred<Boolean>()
    private val pendingHttpCallbacks = ConcurrentHashMap<String, CompletableDeferred<Pair<Int, String>>>()

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
                    .putString("client_id", UserApiKeyAuthManager.getClientId())
                    .commit()
            }
        }
        restoreSessionFromDisk()

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
        UserApiKeyAuthManager.onLogoutPlatformClear = {
            runCatching {
                val cm = CookieManager.getInstance()
                cm.removeAllCookies(null)
                cm.flush()
                prefs.edit().clear().apply()
            }
        }

        // 5. 注册内置 WebView 登录器
        PlatformWebLoginHost.webLoginRenderer = { initialUrl, onDismiss, onLoginSuccess ->
            AndroidWebLoginModal(
                initialUrl = initialUrl,
                onDismiss = onDismiss,
                onLoginSuccess = {
                    persistSessionToDisk()
                    // 刷新常驻 apiWebView 以同步最新 Cookie 与 CSRF
                    apiWebView?.reload()
                    activityScope.launch {
                        LinuxDoRepository.fetchCurrentUserProfile()
                        persistSessionToDisk()
                    }
                    onLoginSuccess()
                }
            )
        }

        // 6. 注册异步 Promise 级 WebView HTTP 执行器与 JSON 兜底器
        UserApiKeyAuthManager.webViewHttpExecutor = { url, method, formParams, headers ->
            executeHttpViaWebViewBridge(url, method, formParams, headers)
        }
        UserApiKeyAuthManager.webViewJsonFetcher = { targetUrl ->
            executeHttpViaWebViewBridge(targetUrl, "GET", null, emptyMap())?.second
        }

        // 7. 检查冷启动是否由 discourse://auth_redirect 唤起
        handleIncomingAuthIntent(intent)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val consumed = BackNavigationBridge.onBack?.invoke() ?: false
                    if (!consumed) {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        )

        setContent {
            LinDoApp()
        }

        // Attach 1x1 invisible apiWebView to window so Chromium keeps timers & fetch active
        window.decorView.post {
            runCatching {
                val root = window.decorView.findViewById<ViewGroup>(android.R.id.content)
                val wv = apiWebView
                if (root != null && wv != null && wv.parent == null) {
                    val lp = FrameLayout.LayoutParams(1, 1)
                    wv.visibility = View.INVISIBLE
                    root.addView(wv, 0, lp)
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initPersistentApiWebView() {
        runCatching {
            val wv = WebView(this)
            val cleanUa = wv.settings.userAgentString.replace("; wv", "")
            UserApiKeyAuthManager.customUserAgent = cleanUa
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                userAgentString = cleanUa
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            }
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(wv, true)

            wv.addJavascriptInterface(LinDoJsBridge(), "LinDoJsBridge")
            wv.webViewClient = object : WebViewClient() {
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
            // 使用轻量级同源空文档初始化 https://linux.do 上下文，瞬时就绪且避免触发整页 HTML 导航的 Cloudflare Turnstile 质询
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
        withTimeoutOrNull(1500L) { apiWebViewReady.await() }

        val reqId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Pair<Int, String>>()
        pendingHttpCallbacks[reqId] = deferred

        val formBodyEncoded = if (!formParams.isNullOrEmpty()) {
            formParams.entries.joinToString("&") { (k, v) ->
                "${Uri.encode(k)}=${Uri.encode(v)}"
            }
        } else {
            ""
        }

        val headersJson = JSONObject().apply {
            headers.forEach { (k, v) -> put(k, v) }
        }.toString()

        val jsCode = """
            (async function() {
                const controller = new AbortController();
                const timer = setTimeout(() => controller.abort(), 5500);
                try {
                    const method = ${JSONObject.quote(method.uppercase())};
                    const hdrs = $headersJson;
                    hdrs['Accept'] = 'application/json, text/javascript, */*; q=0.01';
                    hdrs['X-Requested-With'] = 'XMLHttpRequest';
                    const metaCsrf = document.querySelector('meta[name="csrf-token"]');
                    if (metaCsrf && metaCsrf.content) {
                        hdrs['X-CSRF-Token'] = metaCsrf.content;
                    } else if (method !== 'GET') {
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
                    const opts = {
                        method: method,
                        headers: hdrs,
                        credentials: 'include',
                        signal: controller.signal
                    };
                    const bodyStr = ${JSONObject.quote(formBodyEncoded)};
                    if (method !== 'GET' && method !== 'HEAD') {
                        hdrs['Content-Type'] = 'application/x-www-form-urlencoded; charset=UTF-8';
                        opts.body = bodyStr;
                    }
                    const resp = await fetch(${JSONObject.quote(url)}, opts);
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
            val cookieStr = CookieManager.getInstance().getCookie("https://linux.do").orEmpty()
            if (cookieStr.isNotBlank()) {
                CookieSentinel.ingestRawCookieString(cookieStr)
            }
            wv.evaluateJavascript(jsCode, null)
        }

        return try {
            withTimeoutOrNull(6000L) {
                deferred.await()
            }
        } finally {
            pendingHttpCallbacks.remove(reqId)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
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
            UserApiKeyAuthManager.restorePendingPrivateKeyBase64(savedPendingPrivKey, savedPendingNonce)

            // 恢复用户的阅读密度/字号、深色模式与动效偏好
            UserApiKeyAuthManager.uiFontScale = prefs.getFloat("ui_font_scale", 1.0f)
            UserApiKeyAuthManager.uiDarkTheme = prefs.getBoolean("ui_dark_theme", false)
            UserApiKeyAuthManager.uiEnableAnimations = prefs.getBoolean("ui_enable_animations", true)

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            val webKitCookies = cookieManager.getCookie("https://linux.do").orEmpty()
            val savedCookies = prefs.getString("raw_cookies", "").orEmpty()
            val mergedCookies = if (webKitCookies.contains("_t=")) webKitCookies else savedCookies

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
            if (!apiKey.isNullOrBlank()) {
                editor.putString("api_key", apiKey)
            }
            val pendingPrivKey = UserApiKeyAuthManager.exportPendingPrivateKeyBase64()
            if (!pendingPrivKey.isNullOrBlank()) {
                editor.putString("pending_rsa_priv_b64", pendingPrivKey)
            }
            editor.putString("client_id", UserApiKeyAuthManager.getClientId())
            editor.putString("user_agent", UserApiKeyAuthManager.customUserAgent)
            val user = UserApiKeyAuthManager.currentUser
            if (user != null && user.username != "linuxdo_user") {
                editor.putInt("user_id", user.userId)
                editor.putString("username", user.username)
                editor.putString("display_name", user.displayName)
                editor.putString("avatar_template", user.avatarTemplate)
                editor.putString("user_title", user.userTitle)
                editor.putInt("trust_level", user.trustLevel)
            }
            editor.apply()
        }
    }

    override fun onDestroy() {
        apiWebView?.destroy()
        apiWebView = null
        super.onDestroy()
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
    onLoginSuccess: () -> Unit
) {
    var pageTitle by remember { mutableStateOf("安全登录 linux.do") }
    var progress by remember { mutableStateOf(0.1f) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var completed by remember { mutableStateOf(false) }

    fun completeWithUserObject(u: JSONObject) {
        if (completed) return
        val username = u.optString("username").trim()
        if (username.isBlank() || username == "linuxdo_user") return
        val userId = u.optInt("id", 0)
        val displayName = u.optString("name").ifBlank { username }
        val rawTpl = u.optString("avatar_template").ifBlank { u.optString("avatar") }
        val avatarUrl = if (rawTpl.isNotBlank()) {
            if (rawTpl.startsWith("http")) rawTpl.replace("{size}", "240")
            else "https://linux.do${rawTpl.replace("{size}", "240")}"
        } else ""
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

        // 若当前在 /session/otp/ 兑换页，自动触发表单 POST 提交
        webView.evaluateJavascript(AUTO_SUBMIT_OTP_FORM_JS, null)
        // 触发同步 data-preloaded + 异步 /session/current.json 双通道探测
        webView.evaluateJavascript(EXTRACT_PRELOADED_USER_JS, null)

        // 同步兜底探测
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
            val decoded = decodeJsStringResult(rawJson)?.takeIf { it.isNotBlank() } ?: return@evaluateJavascript
            runCatching {
                completeWithUserObject(JSONObject(decoded))
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
            webViewRef?.stopLoading()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color(0xFFFFFFFF))
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        // 顶部 Linear 风格极简安全顶栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(androidx.compose.ui.graphics.Color(0xFFF4F4F5))
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "关闭",
                    modifier = Modifier.size(17.dp),
                    tint = androidx.compose.ui.graphics.Color(0xFF09090B)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = androidx.compose.ui.graphics.Color(0xFF09090B)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = pageTitle,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = androidx.compose.ui.graphics.Color(0xFF09090B),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = "linux.do 官方安全会话 · 登录成功后自动同步真实等级与资料",
                    fontSize = 11.sp,
                    color = androidx.compose.ui.graphics.Color(0xFF71717A),
                    maxLines = 1
                )
            }

            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(androidx.compose.ui.graphics.Color(0xFFF4F4F5))
                    .clickable { webViewRef?.reload() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "刷新",
                    modifier = Modifier.size(17.dp),
                    tint = androidx.compose.ui.graphics.Color(0xFF09090B)
                )
            }
        }

        if (progress in 0.01f..0.98f) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = androidx.compose.ui.graphics.Color(0xFFFFB003),
                trackColor = androidx.compose.ui.graphics.Color(0xFFE4E4E7)
            )
        }

        // 内置安全 WebView 容器
        AndroidView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    val cleanUa = settings.userAgentString.replace("; wv", "")
                    UserApiKeyAuthManager.customUserAgent = cleanUa

                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        userAgentString = cleanUa
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    }

                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    cookieManager.setAcceptThirdPartyCookies(this, true)

                    addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun onPreloadedUserDetected(userJson: String) {
                                if (userJson.isBlank() || completed) return
                                post {
                                    runCatching {
                                        completeWithUserObject(JSONObject(userJson))
                                    }
                                }
                            }
                        },
                        "LinDoJsBridge"
                    )

                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = (newProgress / 100f).coerceIn(0.05f, 1f)
                        }

                        override fun onReceivedTitle(view: WebView?, title: String?) {
                            if (!title.isNullOrBlank()) {
                                pageTitle = title
                            }
                        }
                    }

                    webViewClient = object : WebViewClient() {
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
                    loadUrl(initialUrl)
                }
            }
        )
    }
}

private fun decodeJsStringResult(raw: String?): String? {
    if (raw.isNullOrBlank() || raw == "null") return null
    return runCatching {
        JSONObject("{\"v\":$raw}").optString("v")
    }.getOrNull()
}
