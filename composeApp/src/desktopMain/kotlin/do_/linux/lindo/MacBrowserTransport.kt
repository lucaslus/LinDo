package do_.linux.lindo

import do_.linux.lindo.core.network.*
import do_.linux.lindo.ui.AppAppearance
import do_.linux.lindo.ui.PlatformSessionRecovery
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/** Dedicated, user-visible browser session. Never reads the user's regular Chrome profile. */
internal object MacBrowserTransport {
    private val directory = File(System.getProperty("user.home"), ".lindo/browser-session")
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    private val recovery = Mutex()
    private val connectionLock = Mutex()
    @Volatile private var connection: Cdp? = null
    @Volatile private var lastChallengeUrl: String? = null
    private var seedDone = false
    private var persist: ((Boolean) -> Unit)? = null
    private var onReady: (() -> Unit)? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal var verificationDiagnostic = "idle"
        private set
    private val browsers = listOf(
        "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
        "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge"
    )
    fun install(wasEnabled: Boolean, persistEnabled: (Boolean) -> Unit, ready: () -> Unit) {
        persist = persistEnabled; onReady = ready
        SavedAccounts.refreshBrowserCookies = { syncCookies() }
        SavedAccounts.replaceBrowserCookies = { raw -> replaceAccountCookies(raw) }
        PlatformSessionRecovery.verifySession = { verify(login = false) }
        PlatformSessionRecovery.loginSession = { verify(login = true) }
        if (wasEnabled) bind()
        scope.launch { SavedAccounts.recoverPendingSwitch() }
        val previousLogout = UserApiKeyAuthManager.onLogoutPlatformClear
        UserApiKeyAuthManager.onLogoutPlatformClear = {
            previousLogout?.invoke()
            seedDone = false
            // Clear only Lindo's own browser profile, never the default browser's cookies.
            val generation = UserApiKeyAuthManager.sessionGeneration
            scope.launch { AccountSessionGate.replace {
                if (generation == UserApiKeyAuthManager.sessionGeneration) runCatching { attach(false)?.call("Network.clearBrowserCookies") }
            } }
        }
    }
    private fun bind() {
        UserApiKeyAuthManager.requireBrowserTransportOnly = true
        UserApiKeyAuthManager.webViewHttpExecutor = { url, method, form, headers ->
            val result = AccountSessionGate.read { request(url, method, form, headers) }
            if (url.contains("/session/otp/") && result?.first in 200..399) runCatching { syncCookies() }
            result
        }
        UserApiKeyAuthManager.webViewBinaryFetcher = { url -> AccountSessionGate.read { binary(url) } }
        PlatformMediaPicker.webViewBinaryUploadExecutor = { name, mime, bytes -> AccountSessionGate.read { upload(name, mime, bytes) } }
    }
    private suspend fun replaceAccountCookies(raw: String) = withContext(Dispatchers.IO) {
        seedDone = false
        val cdp = attach(false) ?: if (UserApiKeyAuthManager.requireBrowserTransportOnly) attach(true) else null
        if (cdp != null) {
            // Discard the previous document and its CSRF/preloaded user before installing another session.
            cdp.call("Page.navigate", buildJsonObject { put("url", "about:blank") })
            withTimeout(10_000L) {
                while ((cdp.evaluate("location.href === 'about:blank'") as? JsonPrimitive)?.booleanOrNull != true) delay(50)
            }
            val stored = cdp.call("Network.getAllCookies")["cookies"]?.jsonArray.orEmpty()
            for (item in stored) {
                val c = item.jsonObject
                val domain = c["domain"]?.jsonPrimitive?.content.orEmpty()
                val name = c["name"]?.jsonPrimitive?.content.orEmpty()
                if ((domain.trimStart('.') == "linux.do" || domain.endsWith(".linux.do")) && name !in CookieSentinel.cfTrustCookieNames) {
                    cdp.call("Network.deleteCookies", buildJsonObject {
                        put("name", name); put("domain", domain); put("path", c["path"]?.jsonPrimitive?.content ?: "/")
                    })
                }
            }
            val cookies = buildJsonArray {
                raw.split(';').map { it.trim() }.filter { it.substringBefore('=') in setOf("_t", "_forum_session") }.forEach {
                    add(buildJsonObject { put("name", it.substringBefore('=')); put("value", it.substringAfter('='));
                        put("url", "https://linux.do/"); put("secure", true); put("httpOnly", true); put("sameSite", "Lax") })
                }
            }
            if (cookies.isNotEmpty()) cdp.call("Network.setCookies", buildJsonObject { put("cookies", cookies) })
            cdp.call("Page.navigate", buildJsonObject { put("url", "https://linux.do/") })
            seedDone = true
            withTimeout(15_000L) {
                while ((cdp.evaluate("location.origin === 'https://linux.do' && document.readyState === 'complete'") as? JsonPrimitive)?.booleanOrNull != true) delay(150)
            }
        }
    }
    private fun allowed(url: String): Boolean = runCatching {
        val uri = URI(url); uri.scheme == "https" && uri.host == "linux.do" && uri.port in listOf(-1,443) && uri.userInfo == null
    }.getOrDefault(false)
    private suspend fun attach(launch: Boolean): Cdp? = connectionLock.withLock {
        connection?.takeIf { !it.closed }?.let { return@withLock it }
        if (launch) {
            directory.mkdirs()
            runCatching { Files.setPosixFilePermissions(directory.toPath(), PosixFilePermissions.fromString("rwx------")) }
        }
        suspend fun discover(createTabIfEmpty: Boolean): Cdp? = runCatching {
            val port = File(directory, "DevToolsActivePort").readLines().first().toInt()
            require(port in 1024..65535)
            val req = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/json/list")).timeout(Duration.ofSeconds(2)).GET().build()
            val targets = json.parseToJsonElement(http.send(req, HttpResponse.BodyHandlers.ofString()).body()).jsonArray
            var target = targets.map { it.jsonObject }.firstOrNull {
                it["type"]?.jsonPrimitive?.content == "page" && (it["url"]?.jsonPrimitive?.content?.let(::allowed) == true || it["url"]?.jsonPrimitive?.content in setOf("about:blank", "chrome://newtab/", "edge://newtab/"))
            }
            if (target == null && createTabIfEmpty) {
                val createReq = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/json/new?https://linux.do/")).timeout(Duration.ofSeconds(2)).PUT(HttpRequest.BodyPublishers.noBody()).build()
                target = json.parseToJsonElement(http.send(createReq, HttpResponse.BodyHandlers.ofString()).body()).jsonObject
            }
            target ?: return@runCatching null
            val ws = URI(target.getValue("webSocketDebuggerUrl").jsonPrimitive.content)
            require(ws.scheme == "ws" && ws.host in setOf("127.0.0.1", "localhost") && ws.port == port)
            Cdp(http, ws, target.getValue("id").jsonPrimitive.content).also { connection = it }
        }.getOrNull()
        discover(createTabIfEmpty = launch)?.let { return@withLock it }
        if (!launch) return@withLock null
        val executable = browsers.firstOrNull { File(it).canExecute() }
            ?: error("需要安装 Chrome 或 Edge 才能使用自动接续验证。")
        ProcessBuilder(executable, "--user-data-dir=${directory.absolutePath}", "--remote-debugging-address=127.0.0.1", "--remote-debugging-port=0", "--no-first-run", "--no-default-browser-check", "--app=https://linux.do/")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        repeat(40) { delay(250); discover(createTabIfEmpty = false)?.let { return@withLock it } }
        error("浏览器未能启动，请关闭验证窗口后重试。")
    }
    private suspend fun seedSession(cdp: Cdp) {
        if (seedDone) return
        val current = cdp.call("Network.getCookies", buildJsonObject { put("urls", buildJsonArray { add("https://linux.do/") }) })["cookies"]?.jsonArray
        val saved = CookieSentinel.resolveWinningCookies(targetHost = "linux.do")
        val appToken = saved["_t"]?.value?.takeIf { it.isNotBlank() && it != "demo_session_token" }
        val browserToken = current?.firstOrNull { it.jsonObject["name"]?.jsonPrimitive?.content == "_t" }?.jsonObject?.get("value")?.jsonPrimitive?.content
        // Recovery must keep the app's account, even when an old browser profile belongs to another account.
        if (browserToken != null && (appToken == null || appToken == browserToken)) { seedDone = true; return }
        val cookies = buildJsonArray {
            saved.filterKeys { it in setOf("_t", "_forum_session") }.forEach { (name, cookie) ->
                if (cookie.value.isNotBlank() && cookie.value != "demo_session_token") add(buildJsonObject {
                    put("name", name); put("value", cookie.value); put("url", "https://linux.do/"); put("secure", true); put("httpOnly", true); put("sameSite", "Lax")
                })
            }
        }
        if (cookies.isNotEmpty()) cdp.call("Network.setCookies", buildJsonObject { put("cookies", cookies) })
        seedDone = true
    }
    private suspend fun verify(login: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (LinuxDoRepository.isRateLimitedNow()) {
            PlatformSessionRecovery.verificationMessage = "站点暂时限制了请求，请稍后再试。"
            return@withContext false
        }
        if (!recovery.tryLock()) return@withContext false
        DesktopNetworkPolicy.verifying = true
        val generation = UserApiKeyAuthManager.sessionGeneration
        try {
            verificationDiagnostic = "opening"
            PlatformSessionRecovery.verificationMessage = "请在打开的网页中完成确认，完成后会自动接续。"
            val cdp = attach(true) ?: return@withContext false
            seedSession(cdp)
            val initialUrl = if (login) "https://linux.do/login" else lastChallengeUrl ?: "https://linux.do/session/current.json"
            val visitedChallenges = mutableSetOf(initialUrl)
            cdp.call("Page.navigate", buildJsonObject { put("url", initialUrl) })
            cdp.call("Page.bringToFront")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
            var requestedLogin = login
            while (System.nanoTime() < deadline) {
                delay(2000)
                if (generation != UserApiKeyAuthManager.sessionGeneration) return@withContext false
                // Wait for the real site, never click or solve a site's verification widget.
                val probe = try {
                    cdp.evaluate("({site:location.origin === 'https://linux.do',complete:document.readyState === 'complete',challenge:!!document.querySelector('#challenge-running, #challenge-stage, #challenge-form')})") as? JsonObject
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { verificationDiagnostic = "site-probe-${error.javaClass.simpleName}"; continue }
                val ready = probe?.get("site")?.jsonPrimitive?.booleanOrNull == true && probe["complete"]?.jsonPrimitive?.booleanOrNull == true && probe["challenge"]?.jsonPrimitive?.booleanOrNull != true
                verificationDiagnostic = if (ready) "checking-session" else "waiting-for-site-$probe"
                if (!ready) continue
                val response = AccountSessionGate.read { request("https://linux.do/session/current.json", "GET", null, emptyMap(), cdp) } ?: continue
                if (generation != UserApiKeyAuthManager.sessionGeneration) return@withContext false
                verificationDiagnostic = "session-status-${response.first}-challenge-${looksLikeCloudflareChallenge(response.second)}"
                if (looksLikeCloudflareChallenge(response.second)) {
                    val challenged = lastChallengeUrl ?: "https://linux.do/session/current.json"
                    if (visitedChallenges.add(challenged)) cdp.call("Page.navigate", buildJsonObject { put("url", challenged) })
                    delay(8000); continue
                }
                if (response.first >= 500) { delay(8000); continue }
                if (response.first == 429) { PlatformSessionRecovery.verificationMessage = "站点暂时限制了请求，请稍后再试。"; return@withContext false }
                val user = runCatching { json.parseToJsonElement(response.second).jsonObject["current_user"] as? JsonObject }.getOrNull()
                if (response.first in 200..299 && user != null && user["username"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true) {
                    bind(); syncCookies()
                    UserApiKeyAuthManager.onSessionAuthenticated(AuthenticatedUserProfile(
                        userId = user["id"]?.jsonPrimitive?.intOrNull ?: 0,
                        username = user.getValue("username").jsonPrimitive.content,
                        displayName = user["name"]?.jsonPrimitive?.contentOrNull ?: user.getValue("username").jsonPrimitive.content,
                        avatarTemplate = user["avatar_template"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        trustLevel = user["trust_level"]?.jsonPrimitive?.intOrNull ?: 1
                    ))
                    finish(cdp); return@withContext true
                }
                if (!login && !UserApiKeyAuthManager.isAuthorized() && response.first in setOf(401, 403, 404)) {
                    // Discourse deployments differ in their unauthenticated session status.
                    // A real public JSON response proves that site verification completed.
                    val public = request("https://linux.do/latest.json", "GET", null, emptyMap(), cdp)
                    verificationDiagnostic = "public-status-${public?.first}-challenge-${public?.let { looksLikeCloudflareChallenge(it.second) }}"
                    if (public?.first == 429 && !looksLikeCloudflareChallenge(public.second)) {
                        PlatformSessionRecovery.verificationMessage = "站点暂时限制了请求，请稍后再试。"
                        return@withContext false
                    }
                    if (public != null && looksLikeCloudflareChallenge(public.second) && visitedChallenges.add("https://linux.do/latest.json")) {
                        cdp.call("Page.navigate", buildJsonObject { put("url", "https://linux.do/latest.json") })
                        continue
                    }
                    val topics = public?.takeIf { it.first == 200 }?.let {
                        runCatching { json.parseToJsonElement(it.second).jsonObject["topic_list"] as? JsonObject }.getOrNull()
                    }
                    if (topics != null) { bind(); syncCookies(); finish(cdp); return@withContext true }
                }
                if (!requestedLogin && UserApiKeyAuthManager.isAuthorized() && response.first in setOf(401, 403, 404)) {
                    requestedLogin = true
                    PlatformSessionRecovery.verificationMessage = "会话已过期，请在网页中登录，完成后会自动返回。"
                    cdp.call("Page.navigate", buildJsonObject { put("url", "https://linux.do/login") })
                }
            }
            PlatformSessionRecovery.verificationMessage = "暂时没有收到完成结果。你可以继续网页确认，然后再试一次。"
            false
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Throwable) {
            verificationDiagnostic = "failed-${error.javaClass.simpleName}"
            PlatformSessionRecovery.verificationMessage = (error as? IllegalStateException)?.message?.takeIf { it.isNotBlank() }
                ?: "连接未完成，请确认 Chrome 或 Edge 可用后重试。"
            false
        }
        finally { DesktopNetworkPolicy.verifying = false; recovery.unlock() }
    }
    private suspend fun finish(cdp: Cdp) {
        persist?.invoke(true)
        LinuxDoRepository.clearChallengeCooldown()
        UserApiKeyAuthManager.notifyNetworkRestored()
        PlatformSessionRecovery.verificationMessage = null
        lastChallengeUrl = null
        runCatching {
            val window = cdp.call("Browser.getWindowForTarget", buildJsonObject { put("targetId", cdp.target) })
            cdp.call("Browser.setWindowBounds", buildJsonObject { put("windowId", window.getValue("windowId")); put("bounds", buildJsonObject { put("windowState", "minimized") }) })
        }
        javax.swing.SwingUtilities.invokeLater { onReady?.invoke() }
    }
    private suspend fun syncCookies() {
        val cdp = attach(false) ?: return
        val cookies = cdp.call("Network.getCookies", buildJsonObject { put("urls", buildJsonArray { add("https://linux.do/") }) })["cookies"]?.jsonArray ?: return
        val allowedNames = setOf("_t", "_forum_session", "cf_clearance", "__cf_bm", "_cfuvid")
        cookies.map { it.jsonObject }.filter { it["name"]?.jsonPrimitive?.content in allowedNames }.forEach {
            val name = it.getValue("name").jsonPrimitive.content; val value = it.getValue("value").jsonPrimitive.content
            CookieSentinel.ingestRawCookieString("$name=$value", domain = "linux.do", hostOnly = true)
        }
        UserApiKeyAuthManager.onSyncCookieToPlatform?.invoke("_t=synchronized")
    }
    private suspend fun request(url: String, method: String, form: Map<String,String>?, headers: Map<String,String>, active: Cdp? = null): Pair<Int,String>? = withContext(Dispatchers.IO) {
        if (!allowed(url)) return@withContext null
        if (LinuxDoRepository.isRateLimitedNow()) return@withContext 429 to """{"errors":["请求较频繁，请稍后再试"]}"""
        val cdp = active ?: attach(false) ?: run {
            PlatformSessionRecovery.verificationMessage = "浏览器会话已关闭，点击继续即可恢复连接。"
            return@withContext null
        }
        val safeHeaders = mapOf("Accept" to "application/json", "X-Requested-With" to "XMLHttpRequest") + headers.filterKeys { it.lowercase() !in setOf("cookie","host","origin","referer","user-agent","content-length") }
        val config = buildJsonObject {
            put("url",url);put("method",method);put("headers",buildJsonObject { safeHeaders.forEach { (k,v)->put(k,v) } });
            put("form", form?.let { buildJsonObject { it.forEach { (k,v)->put(k,v) } } } ?: JsonNull)
        }
        val script = """(async () => {
            const c = $config;
            if (location.origin !== 'https://linux.do') return null;
            const write = !['GET','HEAD'].includes(c.method.toUpperCase());
            if (write) {
                let token = document.querySelector('meta[name="csrf-token"]')?.content;
                if (!token) { const r = await fetch('/session/csrf.json',{credentials:'include',signal:AbortSignal.timeout(12000)}); if(!r.ok) return {status:r.status,body:await r.text()}; token=(await r.json()).csrf; }
                if (!token) return {status:422,body:'{"errors":["会话尚未就绪，请稍后再试"]}'};
                c.headers['X-CSRF-Token']=token;
            }
            const r=await fetch(c.url,{method:c.method,headers:c.headers,credentials:'include',body:c.form?new URLSearchParams(c.form):undefined,signal:AbortSignal.timeout(15000)});
            return {status:r.status,body:await r.text(),retryAfter:r.headers.get('Retry-After'),challenge:r.headers.get('cf-mitigated')};
        })()""".trimIndent()
        try {
            val result = cdp.evaluate(script) as? JsonObject ?: return@withContext null
            val status = result["status"]?.jsonPrimitive?.intOrNull ?: return@withContext null
            val body = result["body"]?.jsonPrimitive?.content.orEmpty()
            val responseBody = if (result["challenge"]?.jsonPrimitive?.contentOrNull == "challenge") "$CF_MITIGATED_CHALLENGE_PREFIX$body" else body
            if (status == 429 && !looksLikeCloudflareChallenge(responseBody)) {
                LinuxDoRepository.noteRetryAfter(result["retryAfter"]?.jsonPrimitive?.contentOrNull)
            }
            if (looksLikeCloudflareChallenge(responseBody) && method.equals("GET", true) && !URI(url).path.startsWith("/session/otp/")) lastChallengeUrl = url
            status to responseBody
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Throwable) { null }
    }
    private suspend fun binary(url: String): UserApiKeyAuthManager.BinaryHttpPayload? = withContext(Dispatchers.IO) {
        if (!allowed(url) || LinuxDoRepository.isRateLimitedNow()) return@withContext null
        val cdp = attach(false) ?: return@withContext null
        runCatching {
            val quoted = JsonPrimitive(url).toString()
            val result = cdp.evaluate("""(async()=>{if(location.origin!=='https://linux.do')return null;const r=await fetch($quoted,{credentials:'include',signal:AbortSignal.timeout(15000)});if(r.status===429)return {status:429,retryAfter:r.headers.get('Retry-After'),challenge:r.headers.get('cf-mitigated')};const b=new Uint8Array(await r.arrayBuffer());if(b.length>12582912)return null;let s='';for(let i=0;i<b.length;i+=8192)s+=String.fromCharCode(...b.subarray(i,i+8192));return {status:r.status,type:r.headers.get('content-type')||'',data:btoa(s)};})()""") as? JsonObject ?: return@runCatching null
            val status = result.getValue("status").jsonPrimitive.int
            if (status == 429) {
                if (result["challenge"]?.jsonPrimitive?.contentOrNull != "challenge") {
                    LinuxDoRepository.noteRetryAfter(result["retryAfter"]?.jsonPrimitive?.contentOrNull)
                }
                return@runCatching null
            }
            UserApiKeyAuthManager.BinaryHttpPayload(status, result.getValue("type").jsonPrimitive.content, java.util.Base64.getDecoder().decode(result.getValue("data").jsonPrimitive.content))
        }.getOrNull()
    }
    /** Keep uploads on the same browser session as reads and writes; never replay a timed-out POST. */
    private suspend fun upload(name: String, mime: String, bytes: ByteArray): Pair<Int, String>? = withContext(Dispatchers.IO) {
        if (LinuxDoRepository.isRateLimitedNow()) return@withContext 429 to """{"errors":["请求较频繁，请稍后再试"]}"""
        val cdp = attach(false) ?: return@withContext null
        val config = buildJsonObject {
            put("name", name); put("mime", mime)
            put("data", java.util.Base64.getEncoder().encodeToString(bytes))
        }
        try {
            val result = cdp.evaluate("""(async()=>{
                if(location.origin!=='https://linux.do')return null;
                const c=$config;
                const csrf=await fetch('/session/csrf.json',{credentials:'include',signal:AbortSignal.timeout(12000)});
                if(!csrf.ok)return {status:csrf.status,body:await csrf.text(),challenge:csrf.headers.get('cf-mitigated'),retryAfter:csrf.headers.get('Retry-After')};
                const token=(await csrf.json()).csrf;
                if(!token)return {status:422,body:'{"errors":["请先完成登录"]}'};
                const decoded=atob(c.data), data=new Uint8Array(decoded.length);
                for(let i=0;i<decoded.length;i++)data[i]=decoded.charCodeAt(i);
                const form=new FormData();form.append('type','composer');form.append('synchronous','true');
                form.append('files[]',new Blob([data],{type:c.mime}),c.name);
                const r=await fetch('/uploads.json',{method:'POST',credentials:'include',headers:{'X-CSRF-Token':token,'X-Requested-With':'XMLHttpRequest'},body:form,signal:AbortSignal.timeout(90000)});
                return {status:r.status,body:await r.text(),challenge:r.headers.get('cf-mitigated'),retryAfter:r.headers.get('Retry-After')};
            })()""", timeoutSeconds = 110) as? JsonObject ?: return@withContext null
            val status = result.getValue("status").jsonPrimitive.int
            val body = result.getValue("body").jsonPrimitive.content
            val responseBody = if (result["challenge"]?.jsonPrimitive?.contentOrNull == "challenge") "$CF_MITIGATED_CHALLENGE_PREFIX$body" else body
            if (status == 429 && !looksLikeCloudflareChallenge(responseBody)) {
                LinuxDoRepository.noteRetryAfter(result["retryAfter"]?.jsonPrimitive?.contentOrNull)
            }
            status to responseBody
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
    }
    private class Cdp(http: HttpClient, uri: URI, val target: String) : WebSocket.Listener {
        private val ids = AtomicInteger()
        private val pending = ConcurrentHashMap<Int, CompletableFuture<JsonObject>>()
        private val buffer = StringBuilder()
        @Volatile var closed = false
        private val socket = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(4)).buildAsync(uri,this).get(5,TimeUnit.SECONDS)
        override fun onOpen(webSocket: WebSocket) { webSocket.request(1) }
        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            buffer.append(data)
            if (last) {
                val message = runCatching { json.parseToJsonElement(buffer.toString()).jsonObject }.getOrNull(); buffer.setLength(0)
                message?.get("id")?.jsonPrimitive?.intOrNull?.let { id -> pending.remove(id)?.complete(message) }
            }
            webSocket.request(1); return null
        }
        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? { closePending(); return null }
        override fun onError(webSocket: WebSocket, error: Throwable) { closePending() }
        private fun closePending() { closed=true; pending.values.forEach { it.completeExceptionally(IllegalStateException("Browser connection closed")) }; pending.clear() }
        suspend fun call(method: String, params: JsonObject = buildJsonObject {}, timeoutSeconds: Long = 20): JsonObject = withContext(Dispatchers.IO) {
            check(!closed)
            val id=ids.incrementAndGet(); val future=CompletableFuture<JsonObject>(); pending[id]=future
            try {
                socket.sendText(buildJsonObject {put("id",id);put("method",method);put("params",params)}.toString(),true).get(5,TimeUnit.SECONDS)
                val response=runInterruptible { future.get(timeoutSeconds,TimeUnit.SECONDS) }
                check(response["error"]==null) { "Browser command failed" }
                response["result"]?.jsonObject ?: buildJsonObject {}
            } finally { pending.remove(id) }
        }
        suspend fun evaluate(script: String, timeoutSeconds: Long = 20): JsonElement? {
            val value=call("Runtime.evaluate",buildJsonObject {put("expression",script);put("awaitPromise",true);put("returnByValue",true)}, timeoutSeconds)
            check(value["exceptionDetails"]==null) { "Browser request failed" }
            return value["result"]?.jsonObject?.get("value")
        }
    }
}
