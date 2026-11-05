package do_.linux.lindo

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.foundation.shape.CircleShape
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
import do_.linux.lindo.core.network.UserApiKeyAuthManager
import do_.linux.lindo.ui.BackNavigationBridge
import do_.linux.lindo.ui.LinDoApp
import do_.linux.lindo.ui.PlatformWebLoginHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {

    private val prefs by lazy {
        getSharedPreferences("lindo_auth_session", Context.MODE_PRIVATE)
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

        // 1. 恢复持久化的登录状态与 WebView Cookie
        restoreSessionFromDisk()

        // 2. 注册系统浏览器跳转桥接器
        UserApiKeyAuthManager.openExternalUrlLauncher = { url: String ->
            runCatching {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(browserIntent)
            }
        }

        // 3. 注册会话变更持久化钩子
        UserApiKeyAuthManager.onPersistSession = { _, _ ->
            persistSessionToDisk()
        }
        UserApiKeyAuthManager.onLogoutPlatformClear = {
            runCatching {
                val cm = CookieManager.getInstance()
                cm.removeAllCookies(null)
                cm.flush()
                prefs.edit().clear().apply()
            }
        }

        // 4. 注册内置 WebView 登录器
        PlatformWebLoginHost.webLoginRenderer = { initialUrl, onDismiss, onLoginSuccess ->
            AndroidWebLoginModal(
                initialUrl = initialUrl,
                onDismiss = onDismiss,
                onLoginSuccess = {
                    persistSessionToDisk()
                    onLoginSuccess()
                }
            )
        }

        // 5. 注册 WebView 同源 JSON 兜底抓取器（当 OkHttp 遇到 Cloudflare 质询时自动使用 WebView 会话拉取）
        UserApiKeyAuthManager.webViewJsonFetcher = { targetUrl ->
            fetchJsonViaWebViewSession(targetUrl)
        }

        // 6. 检查冷启动是否由 discourse://auth_redirect 唤起
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
            val handled = UserApiKeyAuthManager.handleAuthRedirectUri(dataUri)
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

            val userProfile = if (!username.isNullOrBlank()) {
                AuthenticatedUserProfile(
                    username = username,
                    displayName = displayName ?: username,
                    avatarTemplate = avatarTemplate.orEmpty(),
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

            val isAuth = UserApiKeyAuthManager.isAuthorized()
            val editor = prefs.edit()
            if (!isAuth) {
                cookieManager.removeAllCookies(null)
                cookieManager.flush()
                editor.clear().apply()
                return
            }

            editor.putString("raw_cookies", CookieSentinel.buildCookieHeader().ifBlank { latestWebKitCookies })
            editor.putString("api_key", UserApiKeyAuthManager.getApiKey())
            editor.putString("user_agent", UserApiKeyAuthManager.customUserAgent)
            val user = UserApiKeyAuthManager.currentUser
            if (user != null) {
                editor.putString("username", user.username)
                editor.putString("display_name", user.displayName)
                editor.putString("avatar_template", user.avatarTemplate)
                editor.putInt("trust_level", user.trustLevel)
            }
            editor.apply()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun fetchJsonViaWebViewSession(url: String): String? = withContext(Dispatchers.Main) {
        val cookieStr = CookieManager.getInstance().getCookie("https://linux.do").orEmpty()
        if (cookieStr.isNotBlank()) {
            CookieSentinel.ingestRawCookieString(cookieStr)
        }
        suspendCancellableCoroutine { cont ->
            val headlessWebView = WebView(this@MainActivity)
            headlessWebView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = UserApiKeyAuthManager.customUserAgent
                    ?: userAgentString.replace("; wv", "")
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(headlessWebView, true)

            val script = """
                (async function() {
                    try {
                        const resp = await fetch(${JSONObject.quote(url)}, {
                            headers: { 'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest' },
                            credentials: 'include'
                        });
                        if (!resp.ok) return '';
                        return await resp.text();
                    } catch (e) {
                        return '';
                    }
                })();
            """.trimIndent()

            headlessWebView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    view?.evaluateJavascript(script) { rawResult ->
                        val decoded = decodeJsStringResult(rawResult)
                        headlessWebView.destroy()
                        if (cont.isActive) {
                            cont.resume(decoded?.takeIf { it.startsWith("{") || it.startsWith("[") })
                        }
                    }
                }
            }
            headlessWebView.loadUrl("https://linux.do/")
            cont.invokeOnCancellation {
                headlessWebView.destroy()
            }
        }
    }
}

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

    fun tryExtractSessionAndComplete(webView: WebView?) {
        if (completed || webView == null) return
        val cookieManager = CookieManager.getInstance()
        val rawCookies = cookieManager.getCookie("https://linux.do").orEmpty()
        if (rawCookies.isNotBlank()) {
            CookieSentinel.ingestRawCookieString(rawCookies)
        }

        val probeJs = """
            (function() {
                try {
                    var u = null;
                    if (window.Discourse && Discourse.User && Discourse.User.current) {
                        u = Discourse.User.current();
                    }
                    if (!u && window.PreloadStore && PreloadStore.get) {
                        u = PreloadStore.get('currentUser');
                    }
                    if (u && u.username) {
                        return JSON.stringify({
                            loggedIn: true,
                            username: u.username || '',
                            name: u.name || u.username || '',
                            avatar: u.avatar_template || '',
                            trust: u.trust_level || 1
                        });
                    }
                } catch (e) {}
                return JSON.stringify({ loggedIn: false });
            })();
        """.trimIndent()

        webView.evaluateJavascript(probeJs) { rawJson ->
            if (completed) return@evaluateJavascript
            val decoded = decodeJsStringResult(rawJson) ?: return@evaluateJavascript
            runCatching {
                val obj = JSONObject(decoded)
                val jsLoggedIn = obj.optBoolean("loggedIn", false)
                val hasAuthCookie = rawCookies.contains("_t=")
                if (jsLoggedIn || hasAuthCookie) {
                    val username = obj.optString("username").ifBlank { "linuxdo_user" }
                    val displayName = obj.optString("name").ifBlank { username }
                    val avatar = obj.optString("avatar")
                    val trust = obj.optInt("trust", 2)
                    completed = true
                    cookieManager.flush()
                    UserApiKeyAuthManager.onSessionAuthenticated(
                        profile = AuthenticatedUserProfile(
                            username = username,
                            displayName = displayName,
                            avatarTemplate = avatar,
                            trustLevel = trust
                        ),
                        rawCookies = rawCookies
                    )
                    onLoginSuccess()
                }
            }
        }
    }

    // 轮询探测登录完成状态（处理 SPA 无刷新跳转或 Passkey/GitHub 登录完成）
    LaunchedEffect(webViewRef, completed) {
        while (!completed && webViewRef != null) {
            delay(1000L)
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
                    text = "linux.do 官方安全会话 · 登录成功后自动返回",
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
                                    val otp = UserApiKeyAuthManager.pendingOtpToken
                                    if (!otp.isNullOrBlank()) {
                                        UserApiKeyAuthManager.pendingOtpToken = null
                                        view?.loadUrl("https://linux.do/session/otp/$otp")
                                    } else {
                                        completed = true
                                        onLoginSuccess()
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
