package do_.linux.lindo

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Base64
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : FlutterActivity() {

    companion object {
        private const val BROWSER_CHANNEL_NAME = "do_.linux.lindo/browser"
        private const val PLATFORM_CHANNEL_NAME = "do_.linux.lindo/platform"
        private const val LINUX_DO_BASE_URL = "https://linux.do/"
        private const val BRIDGE_EMPTY_HTML =
            "<!doctype html><html><head><meta charset='utf-8'></head><body></body></html>"

        private const val REQ_SAVE_DIAGNOSTIC = 4101
        private const val REQ_PICK_ATTACHMENT = 4102
        private const val REQ_WEB_FILE_CHOOSER = 4103

        private val LAUNCHER_ICON_KEYS = listOf(
            "happy_white",
            "happy_black",
            "classic_white",
            "classic_black",
            "classic_amber",
            "wink_white",
            "wink_black",
            "surprised_white",
            "surprised_black",
            "sleepy_white",
            "sleepy_black",
            "official_white",
            "official_black",
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bgExecutor = Executors.newCachedThreadPool()

    private var browserChannel: MethodChannel? = null
    private var platformChannel: MethodChannel? = null

    private val pendingDeepLinks = ArrayList<String>()

    // File / SAF ActivityResult state
    private var pendingDiagnosticContent: String? = null
    private var pendingDiagnosticResult: MethodChannel.Result? = null
    private var pendingAttachmentResult: MethodChannel.Result? = null
    private var pendingWebFileChooserCallback: ValueCallback<Array<Uri>>? = null

    // Hidden same-origin WKWebView-equivalent Chromium WebView (`https://linux.do/`)
    private var apiHostContainer: FrameLayout? = null
    private var apiWebView: WebView? = null
    private var apiWebViewReady = false
    private var cachedUserAgent: String = ""
    private val apiReadyWaiters = ArrayList<() -> Unit>()
    private val pendingJsRequests = ConcurrentHashMap<String, (String) -> Unit>()
    private val pendingUploadBase64 = ConcurrentHashMap<String, String>()

    // Interactive rounded WebView modal state
    private var modalCardContainer: FrameLayout? = null
    private var modalWebView: WebView? = null
    private var modalMode: String = "login"
    private var modalNormLeft: Double = 0.08
    private var modalNormTop: Double = 0.10
    private var modalNormWidth: Double = 0.84
    private var modalNormHeight: Double = 0.80
    private var modalIsDark: Boolean = false
    private var modalProbeInFlight: Boolean = false
    private var modalProbeStartedAtMs: Long = 0L
    private var modalCompletionSent: Boolean = false
    private var rootLayoutListener: View.OnLayoutChangeListener? = null

    private val modalPeriodicProbeRunnable = object : Runnable {
        override fun run() {
            val wv = modalWebView ?: return
            if (modalCompletionSent) return
            probeModalCompletion(wv)
            mainHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        configureEdgeToEdgeWindow()
        super.onCreate(savedInstanceState)
        requestHighRefreshRateDisplay()
        captureAuthDeepLinkFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureAuthDeepLinkFromIntent(intent)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        val messenger = flutterEngine.dartExecutor.binaryMessenger

        platformChannel = MethodChannel(messenger, PLATFORM_CHANNEL_NAME).also { channel ->
            channel.setMethodCallHandler(::handlePlatformCall)
        }

        browserChannel = MethodChannel(messenger, BROWSER_CHANNEL_NAME).also { channel ->
            channel.setMethodCallHandler(::handleBrowserCall)
        }

        flushPendingDeepLinks()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        closeModalInternal(notifyFlutter = false)
        destroyApiWebView()
        bgExecutor.shutdownNow()
        browserChannel?.setMethodCallHandler(null)
        platformChannel?.setMethodCallHandler(null)
        browserChannel = null
        platformChannel = null
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // Window & System UI
    // -------------------------------------------------------------------------

    private fun configureEdgeToEdgeWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        applySystemBarsAppearance(isDark = false)
    }

    private fun applySystemBarsAppearance(isDark: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = !isDark
        controller.isAppearanceLightNavigationBars = !isDark
    }

    private fun requestHighRefreshRateDisplay() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        runCatching {
            @Suppress("DEPRECATION")
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                this.display
            } else {
                windowManager.defaultDisplay
            } ?: return
            val currentMode = display.mode
            val bestMode = display.supportedModes
                .filter {
                    it.physicalWidth == currentMode.physicalWidth &&
                        it.physicalHeight == currentMode.physicalHeight
                }
                .maxByOrNull { it.refreshRate }
                ?: return
            if (bestMode.modeId != currentMode.modeId) {
                val attrs = window.attributes
                attrs.preferredDisplayModeId = bestMode.modeId
                window.attributes = attrs
            }
        }
    }

    private fun rootOverlayContainer(): FrameLayout? {
        return findViewById<FrameLayout>(android.R.id.content)
            ?: (window?.decorView as? FrameLayout)
    }

    // -------------------------------------------------------------------------
    // Deep Links (`lindo://` and `discourse://`)
    // -------------------------------------------------------------------------

    private fun captureAuthDeepLinkFromIntent(incoming: Intent?) {
        val data = incoming?.data ?: return
        val scheme = data.scheme?.lowercase() ?: return
        if (scheme != "lindo" && scheme != "discourse") return
        val uriStr = data.toString()
        if (uriStr.isBlank()) return
        dispatchAuthDeepLink(uriStr)
    }

    private fun dispatchAuthDeepLink(uri: String) {
        mainHandler.post {
            val channel = platformChannel
            if (channel != null) {
                channel.invokeMethod("onAuthDeepLink", mapOf("uri" to uri))
            } else {
                pendingDeepLinks.add(uri)
            }
        }
    }

    private fun flushPendingDeepLinks() {
        if (pendingDeepLinks.isEmpty()) return
        val channel = platformChannel ?: return
        val copy = ArrayList(pendingDeepLinks)
        pendingDeepLinks.clear()
        for (uri in copy) {
            channel.invokeMethod("onAuthDeepLink", mapOf("uri" to uri))
        }
    }

    // -------------------------------------------------------------------------
    // Platform Channel (`do_.linux.lindo/platform`)
    // -------------------------------------------------------------------------

    private fun handlePlatformCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "getStateDirectory" -> {
                val dir = File(filesDir, "lindo_state").apply { mkdirs() }
                result.success(dir.absolutePath)
            }

            "syncWindowTheme" -> {
                val isDark = call.argument<Boolean>("isDark") ?: false
                applySystemBarsAppearance(isDark)
                result.success(null)
            }

            "updateTrayState" -> {
                // Desktop-only capability; safe no-op on Android.
                result.success(null)
            }

            "applyLauncherIcon" -> {
                val rawKey = call.argument<String>("iconKey").orEmpty()
                val ok = applyLauncherIconAlias(rawKey)
                result.success(ok)
            }

            "saveDiagnosticFile" -> {
                val suggestedName = call.argument<String>("suggestedName")
                    ?.takeIf { it.isNotBlank() }
                    ?: "lindo-diagnostics.log"
                val content = call.argument<String>("content").orEmpty()
                launchSaveDiagnosticDocument(suggestedName, content, result)
            }

            "pickAttachmentFile" -> {
                launchPickAttachment(result)
            }

            "openExternalUrl" -> {
                val url = call.argument<String>("url").orEmpty().trim()
                if (url.isEmpty()) {
                    result.success(false)
                    return
                }
                val opened = runCatching {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    true
                }.getOrDefault(false)
                result.success(opened)
            }

            else -> result.notImplemented()
        }
    }

    private fun applyLauncherIconAlias(rawIconKey: String): Boolean {
        val normalized = rawIconKey.trim()
            .removePrefix("${packageName}.")
            .removePrefix(".LauncherIcon_")
            .removePrefix("LauncherIcon_")
            .trim()
        if (normalized !in LAUNCHER_ICON_KEYS) return false

        return runCatching {
            val pm = packageManager
            val flags = PackageManager.DONT_KILL_APP

            // Enable replacement alias FIRST so the app always keeps an active LAUNCHER entry.
            val orderedKeys = listOf(normalized) + LAUNCHER_ICON_KEYS.filterNot { it == normalized }
            val updates = orderedKeys.map { key ->
                val component = ComponentName(packageName, "${packageName}.LauncherIcon_$key")
                val targetState = if (key == normalized) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                }
                component to targetState
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.setComponentEnabledSettings(
                    updates.map { (component, state) ->
                        PackageManager.ComponentEnabledSetting(component, state, flags)
                    }
                )
            } else {
                for ((component, state) in updates) {
                    pm.setComponentEnabledSetting(component, state, flags)
                }
            }
            true
        }.getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    private fun launchSaveDiagnosticDocument(
        suggestedName: String,
        content: String,
        result: MethodChannel.Result,
    ) {
        pendingDiagnosticResult?.success(null)
        pendingDiagnosticContent = content
        pendingDiagnosticResult = result

        val launched = runCatching {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, suggestedName)
            }
            startActivityForResult(intent, REQ_SAVE_DIAGNOSTIC)
            true
        }.getOrDefault(false)

        if (!launched) {
            pendingDiagnosticContent = null
            pendingDiagnosticResult = null
            result.success(null)
        }
    }

    @Suppress("DEPRECATION")
    private fun launchPickAttachment(result: MethodChannel.Result) {
        pendingAttachmentResult?.success(null)
        pendingAttachmentResult = result

        val launched = runCatching {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(intent, REQ_PICK_ATTACHMENT)
            true
        }.getOrDefault(false)

        if (!launched) {
            pendingAttachmentResult = null
            result.success(null)
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            REQ_SAVE_DIAGNOSTIC -> {
                val content = pendingDiagnosticContent
                val pendingResult = pendingDiagnosticResult
                pendingDiagnosticContent = null
                pendingDiagnosticResult = null
                if (pendingResult == null) return
                val targetUri = data?.data
                if (resultCode != Activity.RESULT_OK || targetUri == null || content == null) {
                    pendingResult.success(null)
                    return
                }
                bgExecutor.execute {
                    val savedPath = runCatching {
                        contentResolver.openOutputStream(targetUri)?.use { out ->
                            out.write(content.toByteArray(Charsets.UTF_8))
                            out.flush()
                        } ?: error("Cannot open output stream")
                        targetUri.toString()
                    }.getOrNull()
                    mainHandler.post { pendingResult.success(savedPath) }
                }
                return
            }

            REQ_PICK_ATTACHMENT -> {
                val pendingResult = pendingAttachmentResult
                pendingAttachmentResult = null
                if (pendingResult == null) return
                val pickedUri = data?.data
                if (resultCode != Activity.RESULT_OK || pickedUri == null) {
                    pendingResult.success(null)
                    return
                }
                bgExecutor.execute {
                    val payload = runCatching {
                        val cr = contentResolver
                        var displayName = "attachment_${System.currentTimeMillis()}"
                        cr.query(
                            pickedUri,
                            arrayOf(OpenableColumns.DISPLAY_NAME),
                            null,
                            null,
                            null,
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                if (idx >= 0 && !cursor.isNull(idx)) {
                                    val candidate = cursor.getString(idx)?.trim()
                                    if (!candidate.isNullOrEmpty()) {
                                        displayName = candidate
                                    }
                                }
                            }
                        }
                        val mimeType = cr.getType(pickedUri)
                            ?.takeIf { it.isNotBlank() }
                            ?: guessMimeTypeFromName(displayName)
                        val bytes = cr.openInputStream(pickedUri)?.use { input ->
                            val buffer = ByteArrayOutputStream()
                            val chunk = ByteArray(16 * 1024)
                            while (true) {
                                val read = input.read(chunk)
                                if (read < 0) break
                                buffer.write(chunk, 0, read)
                            }
                            buffer.toByteArray()
                        }
                        if (bytes == null || bytes.isEmpty()) {
                            null
                        } else {
                            mapOf(
                                "name" to displayName,
                                "mimeType" to mimeType,
                                "bytes" to bytes,
                            )
                        }
                    }.getOrNull()
                    mainHandler.post { pendingResult.success(payload) }
                }
                return
            }

            REQ_WEB_FILE_CHOOSER -> {
                val callback = pendingWebFileChooserCallback
                pendingWebFileChooserCallback = null
                if (callback != null) {
                    val uris = if (resultCode == Activity.RESULT_OK) {
                        data?.clipData?.let { clip ->
                            (0 until clip.itemCount)
                                .mapNotNull { idx -> clip.getItemAt(idx)?.uri }
                                .takeIf { it.isNotEmpty() }
                                ?.toTypedArray()
                        }
                            ?: WebChromeClient.FileChooserParams.parseResult(resultCode, data)
                            ?: data?.data?.let { arrayOf(it) }
                    } else {
                        null
                    }
                    callback.onReceiveValue(uris)
                }
                return
            }
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun guessMimeTypeFromName(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        }
        return "application/octet-stream"
    }

    // -------------------------------------------------------------------------
    // Browser Channel (`do_.linux.lindo/browser`)
    // -------------------------------------------------------------------------

    private fun handleBrowserCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "initialize" -> {
                ensureApiWebViewReady {
                    result.success(
                        mapOf(
                            "ok" to true,
                            "userAgent" to cachedUserAgent,
                        )
                    )
                }
            }

            "executeFetch" -> {
                val method = (call.argument<String>("method") ?: "GET").uppercase()
                val rawUrl = call.argument<String>("url").orEmpty()
                val resolvedUrl = resolveTargetUrl(rawUrl)
                val headersMap = HashMap<String, String>()
                (call.argument<Map<*, *>>("headers"))?.forEach { (k, v) ->
                    if (k != null && v != null) {
                        headersMap[k.toString()] = v.toString()
                    }
                }
                val body = call.argument<String>("body")
                executeBrowserFetch(method, resolvedUrl, headersMap, body, result)
            }

            "uploadMultipart" -> {
                val rawUrl = call.argument<String>("url").orEmpty()
                val resolvedUrl = resolveTargetUrl(rawUrl)
                val headersMap = HashMap<String, String>()
                (call.argument<Map<*, *>>("headers"))?.forEach { (k, v) ->
                    if (k != null && v != null) {
                        headersMap[k.toString()] = v.toString()
                    }
                }
                val fieldsMap = HashMap<String, String>()
                (call.argument<Map<*, *>>("fields"))?.forEach { (k, v) ->
                    if (k != null && v != null) {
                        fieldsMap[k.toString()] = v.toString()
                    }
                }
                val fileField = call.argument<String>("fileField") ?: "file"
                val fileName = call.argument<String>("fileName") ?: "upload.bin"
                val mimeType = call.argument<String>("mimeType") ?: "application/octet-stream"
                val base64Data = call.argument<String>("base64Data").orEmpty()
                executeBrowserMultipartUpload(
                    url = resolvedUrl,
                    headers = headersMap,
                    fields = fieldsMap,
                    fileField = fileField,
                    fileName = fileName,
                    mimeType = mimeType,
                    base64Data = base64Data,
                    result = result,
                )
            }

            "fetchBinary" -> {
                val rawUrl = call.argument<String>("url").orEmpty()
                val resolvedUrl = resolveTargetUrl(rawUrl)
                executeBrowserFetchBinary(resolvedUrl, result)
            }

            "getCookies" -> {
                val rawUrl = call.argument<String>("url") ?: LINUX_DO_BASE_URL
                val resolvedUrl = resolveTargetUrl(rawUrl)
                result.success(readMergedCookies(resolvedUrl))
            }

            "setCookies" -> {
                val rawUrl = call.argument<String>("url") ?: LINUX_DO_BASE_URL
                val resolvedUrl = resolveTargetUrl(rawUrl)
                val rawCookies = call.argument<String>("cookies").orEmpty()
                writeCookiesToStore(resolvedUrl, rawCookies)
                result.success(null)
            }

            "clearSession" -> {
                val cm = CookieManager.getInstance()
                cm.removeAllCookies {
                    runCatching { cm.flush() }
                    runCatching { WebStorage.getInstance().deleteAllData() }
                    apiWebViewReady = false
                    apiWebView?.loadDataWithBaseURL(
                        LINUX_DO_BASE_URL,
                        BRIDGE_EMPTY_HTML,
                        "text/html",
                        "utf-8",
                        null,
                    )
                    result.success(null)
                }
            }

            "openModal" -> {
                val rawUrl = call.argument<String>("url") ?: LINUX_DO_BASE_URL
                val resolvedUrl = resolveTargetUrl(rawUrl)
                val mode = call.argument<String>("mode") ?: "login"
                val left = call.argument<Double>("left") ?: 0.08
                val top = call.argument<Double>("top") ?: 0.10
                val width = call.argument<Double>("width") ?: 0.84
                val height = call.argument<Double>("height") ?: 0.80
                val isDark = call.argument<Boolean>("isDark") ?: false
                openInteractiveModal(resolvedUrl, mode, left, top, width, height, isDark)
                result.success(null)
            }

            "updateModalRect" -> {
                val left = call.argument<Double>("left") ?: modalNormLeft
                val top = call.argument<Double>("top") ?: modalNormTop
                val width = call.argument<Double>("width") ?: modalNormWidth
                val height = call.argument<Double>("height") ?: modalNormHeight
                modalNormLeft = left.coerceIn(0.0, 1.0)
                modalNormTop = top.coerceIn(0.0, 1.0)
                modalNormWidth = width.coerceIn(0.05, 1.0)
                modalNormHeight = height.coerceIn(0.05, 1.0)
                syncModalCardBounds()
                result.success(null)
            }

            "reloadModal" -> {
                modalWebView?.reload()
                result.success(null)
            }

            "goBackModal" -> {
                val wv = modalWebView
                if (wv != null && wv.canGoBack()) {
                    wv.goBack()
                }
                result.success(null)
            }

            "closeModal" -> {
                closeModalInternal(notifyFlutter = false)
                result.success(null)
            }

            else -> result.notImplemented()
        }
    }

    private fun resolveTargetUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return LINUX_DO_BASE_URL
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        if (trimmed.startsWith("//")) return "https:$trimmed"
        return if (trimmed.startsWith("/")) {
            "https://linux.do$trimmed"
        } else {
            "https://linux.do/$trimmed"
        }
    }

    private fun isSameOriginLinuxDo(url: String): Boolean {
        val host = runCatching { Uri.parse(url).host?.lowercase().orEmpty() }.getOrDefault("")
        return host == "linux.do" || host == "www.linux.do"
    }

    private fun sanitizeStandardBrowserUserAgent(rawUa: String): String {
        return rawUa
            .replace("; wv)", ")")
            .replace(Regex("\\s+Version/\\d+\\.\\d+"), "")
            .trim()
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun ensureApiWebViewReady(onReady: () -> Unit) {
        if (apiWebView != null && apiWebViewReady) {
            onReady()
            return
        }
        apiReadyWaiters.add(onReady)
        if (apiWebView != null) {
            return
        }

        val webView = WebView(this)
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = false
            blockNetworkImage = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            val cleanedUa = sanitizeStandardBrowserUserAgent(userAgentString.orEmpty())
            if (cleanedUa.isNotEmpty()) {
                userAgentString = cleanedUa
                cachedUserAgent = cleanedUa
            } else {
                cachedUserAgent = userAgentString.orEmpty()
            }
        }

        webView.addJavascriptInterface(ApiJsBridge(), "__LinDoAndroidBridge")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!apiWebViewReady) {
                    apiWebViewReady = true
                    drainApiReadyWaiters()
                }
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?,
            ): Boolean {
                // Recover transparently if the background WebView renderer is reclaimed by Android OS.
                apiWebView = null
                apiWebViewReady = false
                (view?.parent as? ViewGroup)?.removeView(view)
                runCatching { view?.destroy() }
                failAllPendingJsRequests("WebView renderer restarted")
                return true
            }
        }

        // Attach a 1x1 invisible container so Android Chromium never suspends Promise microtasks.
        val root = rootOverlayContainer()
        if (root != null) {
            val host = FrameLayout(this).apply {
                visibility = View.INVISIBLE
                isClickable = false
                isFocusable = false
            }
            host.addView(webView, FrameLayout.LayoutParams(1, 1))
            root.addView(host, FrameLayout.LayoutParams(1, 1))
            apiHostContainer = host
        }

        apiWebView = webView
        // Zero external network traffic on startup while establishing `https://linux.do` origin!
        webView.loadDataWithBaseURL(
            LINUX_DO_BASE_URL,
            BRIDGE_EMPTY_HTML,
            "text/html",
            "utf-8",
            null,
        )

        // Safety fallback timer in case onPageFinished is coalesced on certain OEM WebViews.
        mainHandler.postDelayed({
            if (apiWebView === webView && !apiWebViewReady) {
                apiWebViewReady = true
                drainApiReadyWaiters()
            }
        }, 1200L)
    }

    private fun drainApiReadyWaiters() {
        if (apiReadyWaiters.isEmpty()) return
        val waiters = ArrayList(apiReadyWaiters)
        apiReadyWaiters.clear()
        for (cb in waiters) {
            cb()
        }
    }

    private fun failAllPendingJsRequests(reason: String) {
        val keys = ArrayList(pendingJsRequests.keys)
        for (key in keys) {
            pendingUploadBase64.remove(key)
            val cb = pendingJsRequests.remove(key) ?: continue
            val errJson = JSONObject()
                .put("status", 0)
                .put("error", reason)
                .toString()
            mainHandler.post { cb(errJson) }
        }
    }

    private fun destroyApiWebView() {
        val view = apiWebView
        apiWebView = null
        apiWebViewReady = false
        val host = apiHostContainer
        apiHostContainer = null
        if (view != null) {
            (view.parent as? ViewGroup)?.removeView(view)
            runCatching {
                view.stopLoading()
                view.removeJavascriptInterface("__LinDoAndroidBridge")
                view.webChromeClient = null
                view.webViewClient = WebViewClient()
                view.destroy()
            }
        }
        if (host != null) {
            (host.parent as? ViewGroup)?.removeView(host)
        }
    }

    private inner class ApiJsBridge {
        @JavascriptInterface
        fun onResult(reqId: String, payloadJson: String) {
            pendingUploadBase64.remove(reqId)
            val callback = pendingJsRequests.remove(reqId) ?: return
            mainHandler.post { callback(payloadJson) }
        }

        @JavascriptInterface
        fun takeUploadBase64(reqId: String): String {
            return pendingUploadBase64.remove(reqId).orEmpty()
        }
    }

    private fun executeBrowserFetch(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
        result: MethodChannel.Result,
    ) {
        if (!isSameOriginLinuxDo(url)) {
            bgExecutor.execute {
                val resMap = executeNativeHttpConnection(method, url, headers, body)
                mainHandler.post { result.success(resMap) }
            }
            return
        }

        ensureApiWebViewReady {
            val wv = apiWebView
            if (wv == null) {
                bgExecutor.execute {
                    val resMap = executeNativeHttpConnection(method, url, headers, body)
                    mainHandler.post { result.success(resMap) }
                }
                return@ensureApiWebViewReady
            }

            val reqId = UUID.randomUUID().toString()
            val timeoutRunnable = Runnable {
                val cb = pendingJsRequests.remove(reqId)
                if (cb != null) {
                    result.success(
                        mapOf(
                            "status" to 0,
                            "url" to url,
                            "headers" to emptyMap<String, String>(),
                            "body" to "",
                            "csrfToken" to "",
                            "error" to "WebView fetch timed out",
                        )
                    )
                }
            }
            mainHandler.postDelayed(timeoutRunnable, 30_000L)

            pendingJsRequests[reqId] = { rawJson ->
                mainHandler.removeCallbacks(timeoutRunnable)
                val parsed = runCatching { JSONObject(rawJson) }.getOrNull()
                val status = parsed?.optInt("status", 0) ?: 0
                val errText = parsed?.optString("error", "").orEmpty()
                if (status == 0 && errText.isNotEmpty()) {
                    // Cross-subdomain redirect (e.g. connect.linux.do OAuth) hit CORS inside fetch();
                    // transparently fall back to CookieManager-synced HttpURLConnection.
                    bgExecutor.execute {
                        val fallback = executeNativeHttpConnection(method, url, headers, body)
                        mainHandler.post { result.success(fallback) }
                    }
                } else {
                    val respHeaders = HashMap<String, String>()
                    parsed?.optJSONObject("headers")?.let { hdrObj ->
                        val keys = hdrObj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            respHeaders[k.lowercase()] = hdrObj.optString(k, "")
                        }
                    }
                    runCatching { CookieManager.getInstance().flush() }
                    val map = HashMap<String, Any?>()
                    map["status"] = status
                    map["url"] = parsed?.optString("url", url) ?: url
                    map["headers"] = respHeaders
                    map["body"] = parsed?.optString("body", "") ?: ""
                    map["csrfToken"] = parsed?.optString("csrfToken", "") ?: ""
                    if (errText.isNotEmpty()) {
                        map["error"] = errText
                    }
                    result.success(map)
                }
            }

            val payloadObj = JSONObject().apply {
                put("reqId", reqId)
                put("method", method)
                put("url", url)
                val hdrJson = JSONObject()
                for ((k, v) in headers) {
                    hdrJson.put(k, v)
                }
                put("headers", hdrJson)
                if (body != null && method != "GET" && method != "HEAD") {
                    put("body", body)
                } else {
                    put("body", JSONObject.NULL)
                }
            }

            val js = """
                (async function(cfg) {
                  try {
                    const forbidden = new Set(['cookie','host','user-agent','origin','referer','content-length','connection']);
                    const cleanHeaders = {};
                    if (cfg.headers) {
                      for (const [k, v] of Object.entries(cfg.headers)) {
                        const lk = String(k).toLowerCase();
                        if (!forbidden.has(lk) && !lk.startsWith('sec-')) {
                          cleanHeaders[k] = String(v);
                        }
                      }
                    }
                    const init = {
                      method: cfg.method || 'GET',
                      headers: cleanHeaders,
                      credentials: 'include',
                      redirect: 'follow'
                    };
                    if (cfg.body !== null && cfg.body !== undefined && init.method !== 'GET' && init.method !== 'HEAD') {
                      init.body = cfg.body;
                    }
                    const resp = await fetch(cfg.url, init);
                    const text = await resp.text();
                    const hdrs = {};
                    resp.headers.forEach((val, key) => { hdrs[key.toLowerCase()] = val; });
                    let csrf = hdrs['x-csrf-token'] || '';
                    if (!csrf) {
                      const meta = document.querySelector('meta[name="csrf-token"]');
                      if (meta && meta.content) csrf = meta.content;
                    }
                    if (!csrf && text && text.indexOf('csrf-token') !== -1) {
                      const m = text.match(/meta\s+name=["']csrf-token["']\s+content=["']([^"']+)["']/i)
                             || text.match(/content=["']([^"']+)["']\s+name=["']csrf-token["']/i);
                      if (m && m[1]) csrf = m[1];
                    }
                    if (csrf) window.__lindoCsrf = csrf;
                    else if (window.__lindoCsrf) csrf = window.__lindoCsrf;
                    window.__LinDoAndroidBridge.onResult(cfg.reqId, JSON.stringify({
                      status: resp.status,
                      url: resp.url || cfg.url,
                      headers: hdrs,
                      body: text,
                      csrfToken: csrf || ''
                    }));
                  } catch (err) {
                    window.__LinDoAndroidBridge.onResult(cfg.reqId, JSON.stringify({
                      status: 0,
                      url: cfg.url,
                      headers: {},
                      body: '',
                      csrfToken: window.__lindoCsrf || '',
                      error: String(err && err.message ? err.message : err)
                    }));
                  }
                })(${payloadObj});
            """.trimIndent()

            wv.evaluateJavascript(js, null)
        }
    }

    private fun executeBrowserMultipartUpload(
        url: String,
        headers: Map<String, String>,
        fields: Map<String, String>,
        fileField: String,
        fileName: String,
        mimeType: String,
        base64Data: String,
        result: MethodChannel.Result,
    ) {
        ensureApiWebViewReady {
            val wv = apiWebView
            if (wv == null) {
                result.success(
                    mapOf(
                        "status" to 0,
                        "url" to url,
                        "body" to "",
                        "csrfToken" to "",
                        "error" to "WebView unavailable for upload",
                    )
                )
                return@ensureApiWebViewReady
            }

            val reqId = UUID.randomUUID().toString()
            pendingUploadBase64[reqId] = base64Data

            val timeoutRunnable = Runnable {
                pendingUploadBase64.remove(reqId)
                val cb = pendingJsRequests.remove(reqId)
                if (cb != null) {
                    result.success(
                        mapOf(
                            "status" to 0,
                            "url" to url,
                            "body" to "",
                            "csrfToken" to "",
                            "error" to "Multipart upload timed out",
                        )
                    )
                }
            }
            mainHandler.postDelayed(timeoutRunnable, 60_000L)

            pendingJsRequests[reqId] = { rawJson ->
                mainHandler.removeCallbacks(timeoutRunnable)
                val parsed = runCatching { JSONObject(rawJson) }.getOrNull()
                runCatching { CookieManager.getInstance().flush() }
                val errText = parsed?.optString("error", "").orEmpty()
                val map = HashMap<String, Any?>()
                map["status"] = parsed?.optInt("status", 0) ?: 0
                map["url"] = parsed?.optString("url", url) ?: url
                map["body"] = parsed?.optString("body", "") ?: ""
                map["csrfToken"] = parsed?.optString("csrfToken", "") ?: ""
                if (errText.isNotEmpty()) {
                    map["error"] = errText
                }
                result.success(map)
            }

            val cfgJson = JSONObject().apply {
                put("reqId", reqId)
                put("url", url)
                put("fileField", fileField)
                put("fileName", fileName)
                put("mimeType", mimeType)
                val hdrObj = JSONObject()
                for ((k, v) in headers) {
                    hdrObj.put(k, v)
                }
                put("headers", hdrObj)
                val fieldsObj = JSONObject()
                for ((k, v) in fields) {
                    fieldsObj.put(k, v)
                }
                put("fields", fieldsObj)
            }

            val js = """
                (async function(cfg) {
                  try {
                    const b64 = window.__LinDoAndroidBridge.takeUploadBase64(cfg.reqId) || '';
                    const binStr = atob(b64);
                    const bytes = new Uint8Array(binStr.length);
                    for (let i = 0; i < binStr.length; i++) {
                      bytes[i] = binStr.charCodeAt(i);
                    }
                    const blob = new Blob([bytes], { type: cfg.mimeType || 'application/octet-stream' });
                    const form = new FormData();
                    if (cfg.fields) {
                      for (const [k, v] of Object.entries(cfg.fields)) {
                        form.append(k, String(v));
                      }
                    }
                    form.append(cfg.fileField || 'file', blob, cfg.fileName || 'upload.bin');

                    const forbidden = new Set(['cookie','host','user-agent','origin','referer','content-length','content-type','connection']);
                    const cleanHeaders = {};
                    if (cfg.headers) {
                      for (const [k, v] of Object.entries(cfg.headers)) {
                        const lk = String(k).toLowerCase();
                        if (!forbidden.has(lk) && !lk.startsWith('sec-')) {
                          cleanHeaders[k] = String(v);
                        }
                      }
                    }
                    if (!cleanHeaders['X-CSRF-Token'] && !cleanHeaders['x-csrf-token'] && window.__lindoCsrf) {
                      cleanHeaders['X-CSRF-Token'] = window.__lindoCsrf;
                    }
                    const resp = await fetch(cfg.url, {
                      method: 'POST',
                      headers: cleanHeaders,
                      body: form,
                      credentials: 'include'
                    });
                    const text = await resp.text();
                    const csrf = resp.headers.get('x-csrf-token') || window.__lindoCsrf || '';
                    if (csrf) window.__lindoCsrf = csrf;
                    window.__LinDoAndroidBridge.onResult(cfg.reqId, JSON.stringify({
                      status: resp.status,
                      url: resp.url || cfg.url,
                      body: text,
                      csrfToken: csrf
                    }));
                  } catch (err) {
                    window.__LinDoAndroidBridge.onResult(cfg.reqId, JSON.stringify({
                      status: 0,
                      url: cfg.url,
                      body: '',
                      csrfToken: window.__lindoCsrf || '',
                      error: String(err && err.message ? err.message : err)
                    }));
                  }
                })(${cfgJson});
            """.trimIndent()

            wv.evaluateJavascript(js, null)
        }
    }

    private fun executeBrowserFetchBinary(url: String, result: MethodChannel.Result) {
        if (!isSameOriginLinuxDo(url)) {
            bgExecutor.execute {
                val bytes = fetchNativeBinaryBytes(url)
                mainHandler.post { result.success(bytes) }
            }
            return
        }

        ensureApiWebViewReady {
            val wv = apiWebView
            if (wv == null) {
                bgExecutor.execute {
                    val bytes = fetchNativeBinaryBytes(url)
                    mainHandler.post { result.success(bytes) }
                }
                return@ensureApiWebViewReady
            }

            val reqId = UUID.randomUUID().toString()
            val timeoutRunnable = Runnable {
                val cb = pendingJsRequests.remove(reqId)
                if (cb != null) {
                    result.success(null)
                }
            }
            mainHandler.postDelayed(timeoutRunnable, 20_000L)

            pendingJsRequests[reqId] = { rawJson ->
                mainHandler.removeCallbacks(timeoutRunnable)
                val parsed = runCatching { JSONObject(rawJson) }.getOrNull()
                val ok = parsed?.optBoolean("ok", false) == true
                val b64 = parsed?.optString("base64", "").orEmpty()
                if (ok && b64.isNotEmpty()) {
                    val decoded = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
                    result.success(decoded)
                } else {
                    bgExecutor.execute {
                        val fallbackBytes = fetchNativeBinaryBytes(url)
                        mainHandler.post { result.success(fallbackBytes) }
                    }
                }
            }

            val cfgJson = JSONObject()
                .put("reqId", reqId)
                .put("url", url)

            val js = """
                (async function(cfg) {
                  try {
                    const resp = await fetch(cfg.url, { credentials: 'include' });
                    if (!resp.ok) {
                      window.__LinDoAndroidBridge.onResult(cfg.reqId, JSON.stringify({ ok: false }));
                      return;
                    }
                    const buf = await resp.arrayBuffer();
                    const bytes = new Uint8Array(buf);
                    const chunkSize = 0x8000;
                    const parts = [];
                    for (let i = 0; i < bytes.length; i += chunkSize) {
                      parts.push(String.fromCharCode.apply(null, bytes.subarray(i, i + chunkSize)));
                    }
                    const b64 = btoa(parts.join(''));
                    window.__LinDoAndroidBridge.onResult(cfg.reqId, JSON.stringify({ ok: true, base64: b64 }));
                  } catch (e) {
                    window.__LinDoAndroidBridge.onResult(cfg.reqId, JSON.stringify({ ok: false }));
                  }
                })(${cfgJson});
            """.trimIndent()

            wv.evaluateJavascript(js, null)
        }
    }

    private fun executeNativeHttpConnection(
        method: String,
        initialUrl: String,
        headers: Map<String, String>,
        body: String?,
    ): Map<String, Any?> {
        var currentUrl = initialUrl
        var currentMethod = method.uppercase()
        var currentBody = body
        var redirects = 0

        try {
            while (redirects <= 8) {
                val urlObj = URL(currentUrl)
                val conn = (urlObj.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 15_000
                    readTimeout = 25_000
                    requestMethod = currentMethod
                }

                if (cachedUserAgent.isNotEmpty()) {
                    conn.setRequestProperty("User-Agent", cachedUserAgent)
                }
                for ((k, v) in headers) {
                    if (!k.equals("cookie", ignoreCase = true) &&
                        !k.equals("host", ignoreCase = true) &&
                        !k.equals("content-length", ignoreCase = true)
                    ) {
                        conn.setRequestProperty(k, v)
                    }
                }
                val cookieHeader = readMergedCookies(currentUrl)
                if (cookieHeader.isNotEmpty()) {
                    conn.setRequestProperty("Cookie", cookieHeader)
                }

                if (currentBody != null && currentMethod != "GET" && currentMethod != "HEAD") {
                    conn.doOutput = true
                    val payloadBytes = currentBody.toByteArray(Charsets.UTF_8)
                    conn.outputStream.use { out ->
                        out.write(payloadBytes)
                        out.flush()
                    }
                }

                val status = conn.responseCode
                syncResponseCookiesToStore(currentUrl, conn.headerFields)

                if (status in listOf(301, 302, 303, 307, 308)) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (location.isNullOrBlank()) break
                    currentUrl = URL(urlObj, location).toString()
                    if (status == 301 || status == 302 || status == 303) {
                        currentMethod = "GET"
                        currentBody = null
                    }
                    redirects++
                    continue
                }

                val respHeaders = HashMap<String, String>()
                for ((k, values) in conn.headerFields) {
                    if (k != null && !values.isNullOrEmpty()) {
                        respHeaders[k.lowercase()] = values.joinToString(", ")
                    }
                }

                val stream = if (status in 200..399) conn.inputStream else conn.errorStream
                val bodyText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                conn.disconnect()

                var csrfToken = respHeaders["x-csrf-token"].orEmpty()
                if (csrfToken.isEmpty() && bodyText.contains("csrf-token")) {
                    val m = Regex(
                        """meta\s+name=["']csrf-token["']\s+content=["']([^"']+)["']""",
                        RegexOption.IGNORE_CASE,
                    ).find(bodyText)
                    if (m != null) {
                        csrfToken = m.groupValues[1]
                    }
                }

                return mapOf(
                    "status" to status,
                    "url" to currentUrl,
                    "headers" to respHeaders,
                    "body" to bodyText,
                    "csrfToken" to csrfToken,
                )
            }
            return mapOf(
                "status" to 0,
                "url" to currentUrl,
                "headers" to emptyMap<String, String>(),
                "body" to "",
                "csrfToken" to "",
                "error" to "Too many redirects",
            )
        } catch (e: Throwable) {
            return mapOf(
                "status" to 0,
                "url" to currentUrl,
                "headers" to emptyMap<String, String>(),
                "body" to "",
                "csrfToken" to "",
                "error" to (e.message ?: e::class.java.simpleName),
            )
        }
    }

    private fun fetchNativeBinaryBytes(targetUrl: String): ByteArray? {
        return runCatching {
            val conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 20_000
                requestMethod = "GET"
                if (cachedUserAgent.isNotEmpty()) {
                    setRequestProperty("User-Agent", cachedUserAgent)
                }
                val host = Uri.parse(targetUrl).host?.lowercase().orEmpty()
                if (host == "linux.do" || host.endsWith(".linux.do")) {
                    val cookies = readMergedCookies(targetUrl)
                    if (cookies.isNotEmpty()) {
                        setRequestProperty("Cookie", cookies)
                    }
                }
            }
            val code = conn.responseCode
            syncResponseCookiesToStore(targetUrl, conn.headerFields)
            if (code !in 200..299) {
                conn.disconnect()
                return null
            }
            val bytes = conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
            conn.disconnect()
            bytes.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun syncResponseCookiesToStore(
        requestUrl: String,
        headerFields: Map<String?, List<String>?>?,
    ) {
        if (headerFields.isNullOrEmpty()) return
        val cm = CookieManager.getInstance()
        var wroteAny = false
        for ((name, values) in headerFields) {
            if (name != null && name.equals("Set-Cookie", ignoreCase = true) && values != null) {
                for (setCookieLine in values) {
                    if (setCookieLine.isNotBlank()) {
                        cm.setCookie(requestUrl, setCookieLine)
                        wroteAny = true
                    }
                }
            }
        }
        if (wroteAny) {
            runCatching { cm.flush() }
        }
    }

    private fun readMergedCookies(targetUrl: String): String {
        val cm = CookieManager.getInstance()
        val primary = cm.getCookie(targetUrl).orEmpty()
        val host = runCatching { Uri.parse(targetUrl).host?.lowercase().orEmpty() }.getOrDefault("")
        if (host != "linux.do" && host.endsWith(".linux.do")) {
            val rootCookies = cm.getCookie(LINUX_DO_BASE_URL).orEmpty()
            if (rootCookies.isNotEmpty() && primary.isNotEmpty()) {
                val merged = LinkedHashMap<String, String>()
                for (src in listOf(rootCookies, primary)) {
                    src.split(';').forEach { part ->
                        val trimmed = part.trim()
                        val eq = trimmed.indexOf('=')
                        if (eq > 0) {
                            merged[trimmed.substring(0, eq).trim()] = trimmed.substring(eq + 1).trim()
                        }
                    }
                }
                return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
            } else if (primary.isEmpty()) {
                return rootCookies
            }
        }
        return primary
    }

    private fun writeCookiesToStore(targetUrl: String, rawCookies: String) {
        val trimmed = rawCookies.trim()
        if (trimmed.isEmpty()) return
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)

        val origin = runCatching {
            val u = Uri.parse(targetUrl)
            "${u.scheme ?: "https"}://${u.host ?: "linux.do"}"
        }.getOrDefault("https://linux.do")

        val lines = trimmed.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val attributeKeywords = setOf(
            "path",
            "domain",
            "expires",
            "max-age",
            "samesite",
            "secure",
            "httponly",
            "priority",
            "partitioned",
        )

        for (line in lines) {
            val cleanedLine = line.removePrefix("Cookie:").removePrefix("cookie:").trim()
            val segments = cleanedLine.split(';').map { it.trim() }.filter { it.isNotEmpty() }
            val looksLikeSingleSetCookie = segments.drop(1).any { seg ->
                val attrName = seg.substringBefore('=').trim().lowercase()
                attrName in attributeKeywords
            }
            if (looksLikeSingleSetCookie) {
                cm.setCookie(origin, cleanedLine)
            } else {
                for (pair in segments) {
                    if (pair.contains('=')) {
                        cm.setCookie(origin, "$pair; Path=/; Secure")
                    }
                }
            }
        }
        runCatching { cm.flush() }
    }

    // -------------------------------------------------------------------------
    // Interactive Rounded WebView Modal (`openModal`, `updateModalRect`, etc.)
    // -------------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun openInteractiveModal(
        url: String,
        mode: String,
        left: Double,
        top: Double,
        width: Double,
        height: Double,
        isDark: Boolean,
    ) {
        closeModalInternal(notifyFlutter = false)

        val root = rootOverlayContainer() ?: return

        modalMode = mode
        modalNormLeft = left.coerceIn(0.0, 1.0)
        modalNormTop = top.coerceIn(0.0, 1.0)
        modalNormWidth = width.coerceIn(0.05, 1.0)
        modalNormHeight = height.coerceIn(0.05, 1.0)
        modalIsDark = isDark
        modalProbeInFlight = false
        modalCompletionSent = false

        val density = resources.displayMetrics.density
        val cornerRadiusPx = 16f * density
        val bgColor = if (isDark) Color.parseColor("#1A1B1E") else Color.WHITE

        val card = FrameLayout(this).apply {
            setBackgroundColor(bgColor)
            elevation = 12f * density
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
                }
            }
        }

        val webView = WebView(this)
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, true)

        webView.setBackgroundColor(bgColor)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            val cleanedUa = sanitizeStandardBrowserUserAgent(userAgentString.orEmpty())
            if (cleanedUa.isNotEmpty()) {
                userAgentString = cleanedUa
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                isAlgorithmicDarkeningAllowed = isDark
            }
        }

        webView.addJavascriptInterface(ModalJsBridge(), "__LinDoModalBridge")

        webView.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    closeModalInternal(notifyFlutter = true)
                }
                true
            } else {
                false
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val targetUri = request?.url ?: return false
                val scheme = targetUri.scheme?.lowercase().orEmpty()
                if (scheme == "lindo" || scheme == "discourse") {
                    dispatchAuthDeepLink(targetUri.toString())
                    closeModalInternal(notifyFlutter = true)
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView?, startedUrl: String?, favicon: Bitmap?) {
                super.onPageStarted(view, startedUrl, favicon)
                modalProbeInFlight = false
                browserChannel?.invokeMethod(
                    "onModalPageStarted",
                    mapOf("url" to (startedUrl ?: "")),
                )
            }

            override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                super.onPageFinished(view, finishedUrl)
                runCatching { CookieManager.getInstance().flush() }
                val title = view?.title.orEmpty()
                if (title.isNotBlank()) {
                    browserChannel?.invokeMethod(
                        "onModalTitleChanged",
                        mapOf(
                            "title" to title,
                            "url" to (finishedUrl ?: ""),
                        ),
                    )
                }
                if (view != null) {
                    probeModalCompletion(view)
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    val code = error?.errorCode ?: 0
                    // Ignore transient navigation cancellation (-999 / ERROR_IO during redirect)
                    if (code == -999) return
                    val desc = error?.description?.toString().orEmpty()
                    if (desc == "net::ERR_ABORTED") return
                    browserChannel?.invokeMethod(
                        "onModalLoadError",
                        mapOf(
                            "url" to (request.url?.toString() ?: ""),
                            "message" to desc.ifBlank { "Web page load error ($code)" },
                        ),
                    )
                }
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?,
            ): Boolean {
                browserChannel?.invokeMethod(
                    "onModalLoadError",
                    mapOf(
                        "url" to "",
                        "message" to "WebView render process exited unexpectedly",
                    ),
                )
                closeModalInternal(notifyFlutter = false)
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                browserChannel?.invokeMethod(
                    "onModalProgress",
                    mapOf(
                        "progress" to (newProgress.coerceIn(0, 100) / 100.0),
                        "url" to (view?.url ?: ""),
                    ),
                )
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                if (!title.isNullOrBlank()) {
                    browserChannel?.invokeMethod(
                        "onModalTitleChanged",
                        mapOf(
                            "title" to title,
                            "url" to (view?.url ?: ""),
                        ),
                    )
                }
            }

            @Suppress("DEPRECATION")
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                if (filePathCallback == null) return false
                pendingWebFileChooserCallback?.onReceiveValue(null)
                pendingWebFileChooserCallback = filePathCallback
                return runCatching {
                    val intent = fileChooserParams?.createIntent()
                        ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                        }
                    startActivityForResult(intent, REQ_WEB_FILE_CHOOSER)
                    true
                }.getOrElse {
                    pendingWebFileChooserCallback = null
                    filePathCallback.onReceiveValue(null)
                    false
                }
            }
        }

        card.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        modalCardContainer = card
        modalWebView = webView
        root.addView(card, computeModalLayoutParams(root))

        val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            syncModalCardBounds()
        }
        rootLayoutListener = layoutListener
        root.addOnLayoutChangeListener(layoutListener)

        webView.loadUrl(url)
        mainHandler.removeCallbacks(modalPeriodicProbeRunnable)
        mainHandler.postDelayed(modalPeriodicProbeRunnable, 1000L)
    }

    private fun computeModalLayoutParams(root: FrameLayout): FrameLayout.LayoutParams {
        val parentW = root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val parentH = root.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels

        val pxLeft = (modalNormLeft * parentW).roundToInt().coerceAtLeast(0)
        val pxTop = (modalNormTop * parentH).roundToInt().coerceAtLeast(0)
        val pxWidth = (modalNormWidth * parentW).roundToInt().coerceIn(64, parentW)
        val pxHeight = (modalNormHeight * parentH).roundToInt().coerceIn(64, parentH)

        return FrameLayout.LayoutParams(pxWidth, pxHeight, Gravity.TOP or Gravity.START).apply {
            leftMargin = pxLeft
            topMargin = pxTop
        }
    }

    private fun syncModalCardBounds() {
        val card = modalCardContainer ?: return
        val root = rootOverlayContainer() ?: return
        card.layoutParams = computeModalLayoutParams(root)
        card.invalidateOutline()
    }

    private fun probeModalCompletion(webView: WebView) {
        if (modalCompletionSent) return
        val now = System.currentTimeMillis()
        if (modalProbeInFlight && now - modalProbeStartedAtMs < 4000L) return
        val mode = modalMode
        if (mode != "login" && mode != "siteVerify") return

        val currentUrl = webView.url.orEmpty()
        val parsedUri = runCatching { Uri.parse(currentUrl) }.getOrNull() ?: return
        val host = parsedUri.host?.lowercase().orEmpty()
        val path = parsedUri.path?.lowercase().orEmpty()

        // Only probe once navigated onto linux.do itself (never interfere with OAuth provider pages
        // or natural user interaction on Cloudflare verification screens).
        if (host != "linux.do" && host != "www.linux.do") return
        if (mode == "login" && (path.startsWith("/login") || path.startsWith("/session/sso"))) {
            return
        }

        modalProbeInFlight = true
        modalProbeStartedAtMs = now
        val modeEscaped = JSONObject.quote(mode)
        val probeJs = """
            (async function(mode) {
              try {
                const title = String(document.title || '');
                const isChallenge = title.includes('Just a moment')
                  || title.includes('请稍候')
                  || !!document.querySelector('#challenge-running, #cf-wrapper, [name="cf-turnstile-response"]');
                if (isChallenge) {
                  window.__LinDoModalBridge.onProbeResult(JSON.stringify({ done: false }));
                  return;
                }
                const meta = document.querySelector('meta[name="csrf-token"]');
                let csrf = (meta && meta.content) ? meta.content : '';
                if (mode === 'login') {
                  const r = await fetch('/session/current.json', {
                    credentials: 'include',
                    headers: {
                      'Accept': 'application/json',
                      'X-Requested-With': 'XMLHttpRequest'
                    }
                  });
                  if (!r.ok) {
                    window.__LinDoModalBridge.onProbeResult(JSON.stringify({ done: false }));
                    return;
                  }
                  const hdrCsrf = r.headers.get('x-csrf-token');
                  if (hdrCsrf) csrf = hdrCsrf;
                  const data = await r.json();
                  if (data && data.current_user && data.current_user.username) {
                    window.__LinDoModalBridge.onProbeResult(JSON.stringify({
                      done: true,
                      kind: 'login',
                      url: location.href,
                      csrfToken: csrf || '',
                      user: data.current_user
                    }));
                    return;
                  }
                  window.__LinDoModalBridge.onProbeResult(JSON.stringify({ done: false }));
                } else if (mode === 'siteVerify') {
                  const r = await fetch('/srv/status', {
                    credentials: 'include',
                    headers: { 'X-Requested-With': 'XMLHttpRequest' }
                  });
                  if (r.ok) {
                    const text = await r.text();
                    if (text.trim().startsWith('ok')) {
                      window.__LinDoModalBridge.onProbeResult(JSON.stringify({
                        done: true,
                        kind: 'siteVerify',
                        url: location.href,
                        csrfToken: csrf || ''
                      }));
                      return;
                    }
                  }
                  window.__LinDoModalBridge.onProbeResult(JSON.stringify({ done: false }));
                } else {
                  window.__LinDoModalBridge.onProbeResult(JSON.stringify({ done: false }));
                }
              } catch (e) {
                window.__LinDoModalBridge.onProbeResult(JSON.stringify({ done: false }));
              }
            })($modeEscaped);
        """.trimIndent()

        webView.evaluateJavascript(probeJs, null)
    }

    private inner class ModalJsBridge {
        @JavascriptInterface
        fun onProbeResult(payloadJson: String) {
            mainHandler.post {
                modalProbeInFlight = false
                if (modalCompletionSent || modalWebView == null) return@post
                val obj = runCatching { JSONObject(payloadJson) }.getOrNull() ?: return@post
                if (!obj.optBoolean("done", false)) return@post

                modalCompletionSent = true
                runCatching { CookieManager.getInstance().flush() }
                val finalUrl = obj.optString("url", modalWebView?.url ?: LINUX_DO_BASE_URL)
                val cookies = readMergedCookies(LINUX_DO_BASE_URL)
                val csrfToken = obj.optString("csrfToken", "")
                val kind = obj.optString("kind", "")

                if (csrfToken.isNotEmpty()) {
                    apiWebView?.evaluateJavascript(
                        "window.__lindoCsrf = ${JSONObject.quote(csrfToken)};",
                        null,
                    )
                }

                if (kind == "login") {
                    val userJson = obj.optJSONObject("user")?.toString() ?: "{}"
                    browserChannel?.invokeMethod(
                        "onModalLoginAuthenticated",
                        mapOf(
                            "url" to finalUrl,
                            "cookies" to cookies,
                            "csrfToken" to csrfToken,
                            "user" to userJson,
                        ),
                    )
                    closeModalInternal(notifyFlutter = false)
                } else if (kind == "siteVerify") {
                    browserChannel?.invokeMethod(
                        "onModalSiteVerified",
                        mapOf(
                            "url" to finalUrl,
                            "cookies" to cookies,
                            "csrfToken" to csrfToken,
                        ),
                    )
                    closeModalInternal(notifyFlutter = false)
                }
            }
        }
    }

    private fun closeModalInternal(notifyFlutter: Boolean) {
        mainHandler.removeCallbacks(modalPeriodicProbeRunnable)
        modalProbeInFlight = false

        val root = rootOverlayContainer()
        rootLayoutListener?.let { listener ->
            root?.removeOnLayoutChangeListener(listener)
        }
        rootLayoutListener = null

        pendingWebFileChooserCallback?.onReceiveValue(null)
        pendingWebFileChooserCallback = null

        val wv = modalWebView
        modalWebView = null
        val card = modalCardContainer
        modalCardContainer = null

        if (wv != null) {
            (wv.parent as? ViewGroup)?.removeView(wv)
            runCatching {
                wv.stopLoading()
                wv.removeJavascriptInterface("__LinDoModalBridge")
                wv.webChromeClient = null
                wv.webViewClient = WebViewClient()
                wv.loadUrl("about:blank")
                wv.destroy()
            }
        }
        if (card != null) {
            (card.parent as? ViewGroup)?.removeView(card)
        }

        if (notifyFlutter) {
            browserChannel?.invokeMethod("onModalClosed", emptyMap<String, Any>())
        }
    }
}
