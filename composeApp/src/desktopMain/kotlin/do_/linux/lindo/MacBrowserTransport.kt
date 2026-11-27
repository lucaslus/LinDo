package do_.linux.lindo

import do_.linux.lindo.core.network.*
import do_.linux.lindo.core.diagnostics.DiagnosticCategory
import do_.linux.lindo.core.diagnostics.LinDoDiagnosticLogger
import do_.linux.lindo.ui.PlatformSessionRecovery
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.net.URI
import java.util.concurrent.*

/** Login, challenge UI and requests share Lindo's persistent in-process WebKit session. */
internal object MacBrowserTransport {
    private val json = Json { ignoreUnknownKeys = true }
    private val recovery = Mutex()
    private val connectionLock = Mutex()
    @Volatile private var connection: MacWebSession? = null
    @Volatile private var lastChallengeUrl: String? = null
    @Volatile private var lastSeededToken: String? = "__unseeded__"
    @Volatile private var lastCookieSyncAtMs: Long = 0L
    private var persist: ((Boolean) -> Unit)? = null
    private var onReady: (() -> Unit)? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile internal var verificationDiagnostic = "idle"
        private set(value) {
            if (field != value) LinDoDiagnosticLogger.info(DiagnosticCategory.AUTH, "MacVerification", value)
            field = value
        }
    fun install(persistEnabled: (Boolean) -> Unit, ready: () -> Unit) {
        persist = persistEnabled; onReady = ready
        SavedAccounts.refreshBrowserCookies = { syncCookies() }
        SavedAccounts.replaceBrowserCookies = { raw -> replaceAccountCookies(raw) }
        PlatformSessionRecovery.verifySession = { verify(login = false) }
        PlatformSessionRecovery.loginSession = { verify(login = true) }
        bind() // Every macOS login/request uses the same WebKit store, including first launch.
        scope.launch { SavedAccounts.recoverPendingSwitch() }
        val previousLogout = UserApiKeyAuthManager.onLogoutPlatformClear
        UserApiKeyAuthManager.onLogoutPlatformClear = {
            previousLogout?.invoke()
            lastSeededToken = "__unseeded__"
            // Clear Lindo's WebKit auth cookies while keeping Cloudflare trust cookies intact; Safari and Chrome are never accessed.
            val generation = UserApiKeyAuthManager.sessionGeneration
            scope.launch { AccountSessionGate.replace {
                if (generation == UserApiKeyAuthManager.sessionGeneration) runCatching { replaceAccountCookies("") }
            } }
        }
    }
    /** Authorization and subsequent API calls must use the same browser session. */
    suspend fun openAuthorization(url: String): Boolean {
        if (!isAuthorizationUrl(url)) return false
        return withContext(Dispatchers.IO) {
            val web = attach(true) ?: return@withContext false
            seedSession(web)
            web.call("navigate", buildJsonObject { put("url", url) })
            bind()
            persist?.invoke(true)
            web.call("show", buildJsonObject { put("dark", UserApiKeyAuthManager.uiDarkTheme) })
            true
        }
    }

    internal fun isAuthorizationUrl(url: String): Boolean = allowed(url) &&
        runCatching { URI(url).path == "/user-api-key/new" }.getOrDefault(false)

    private fun bind() {
        UserApiKeyAuthManager.requireBrowserTransportOnly = true
        UserApiKeyAuthManager.webViewHttpExecutor = { url, method, form, headers ->
            val result = AccountSessionGate.read { request(url, method, form, headers) }
            if ((url.contains("/session/otp/") || url.endsWith("/session.json") || url.endsWith("/session")) && result?.first in 200..399) {
                runCatching { syncCookies(expectAuthCookie = url.contains("/session/otp/")) }
            }
            result
        }
        UserApiKeyAuthManager.webViewBinaryFetcher = { url -> AccountSessionGate.read { binary(url) } }
        PlatformMediaPicker.webViewBinaryUploadExecutor = { name, mime, bytes -> AccountSessionGate.read { upload(name, mime, bytes) } }
    }
    private suspend fun replaceAccountCookies(raw: String) = withContext(Dispatchers.IO) {
        lastSeededToken = "__unseeded__"
        val web = attach(false) ?: if (UserApiKeyAuthManager.requireBrowserTransportOnly) attach(true) else null
        if (web != null) {
            // Discard the previous document and its CSRF/preloaded user before installing another session.
            web.call("navigate", buildJsonObject { put("url", "about:blank") })
            withTimeout(10_000L) {
                while ((web.evaluate("location.href === 'about:blank'") as? JsonPrimitive)?.booleanOrNull != true) delay(50)
            }
            val stored = web.call("cookies")["cookies"]?.jsonArray.orEmpty()
            for (item in stored) {
                val c = item.jsonObject
                val domain = c["domain"]?.jsonPrimitive?.content.orEmpty()
                val name = c["name"]?.jsonPrimitive?.content.orEmpty()
                if ((domain.trimStart('.') == "linux.do" || domain.endsWith(".linux.do")) && name !in CookieSentinel.cfTrustCookieNames) {
                    web.call("deleteCookie", buildJsonObject {
                        put("name", name); put("domain", domain); put("path", c["path"]?.jsonPrimitive?.content ?: "/")
                    })
                }
            }
            var installedToken = ""
            val cookies = buildJsonArray {
                raw.split(';').map { it.trim() }.filter { it.substringBefore('=') in setOf("_t", "_forum_session") }.forEach {
                    val cName = it.substringBefore('=')
                    val cVal = it.substringAfter('=')
                    if (cName == "_t" && cVal.isNotBlank()) installedToken = cVal
                    add(buildJsonObject { put("name", cName); put("value", cVal);
                        put("url", "https://linux.do/"); put("secure", true); put("httpOnly", true); put("sameSite", "Lax") })
                }
            }
            if (cookies.isNotEmpty()) web.call("setCookies", buildJsonObject { put("cookies", cookies) })
            web.call("navigate", buildJsonObject { put("url", "https://linux.do/") })
            lastSeededToken = installedToken
            withTimeout(15_000L) {
                while ((web.evaluate("location.origin === 'https://linux.do' && document.readyState === 'complete'") as? JsonPrimitive)?.booleanOrNull != true) delay(150)
            }
        }
    }
    private fun allowed(url: String): Boolean = runCatching {
        val uri = URI(url); uri.scheme == "https" && uri.host == "linux.do" && uri.port in listOf(-1,443) && uri.userInfo == null
    }.getOrDefault(false)
    internal fun verificationPageUrl(url: String?): String {
        if (url == null || !allowed(url)) return "https://linux.do/"
        val path = URI(url).path.removeSuffix(".json")
        // Use an HTML document for the challenge; API JSON is not a browser landing page.
        val topic = Regex("^/t/(?:[^/]+/)?[0-9]+(?=/|$)").find(path)?.value
        return when {
            topic != null -> "https://linux.do$topic"
            path == "/notifications" && UserApiKeyAuthManager.isAuthorized() -> "https://linux.do/my/notifications"
            else -> "https://linux.do/"
        }
    }
    private suspend fun attach(launch: Boolean): MacWebSession? = connectionLock.withLock {
        connection?.let { return@withLock it }
        if (!launch) return@withLock null
        MacWebSession().also { it.call("init"); connection = it }
    }
    private val bootstrap = Mutex()
    private suspend fun readySession(forceReload: Boolean = false): MacWebSession = bootstrap.withLock {
        val web = attach(true) ?: error("应用内网页不可用")
        seedSession(web)
        val onSite = !forceReload && (web.evaluate(
            "location.origin === 'https://linux.do' && document.readyState !== 'loading'", isolated = true
        ) as? JsonPrimitive)?.booleanOrNull == true
        if (!onSite) {
            val navigationId = JsonPrimitive(System.nanoTime().toString())
            web.evaluate("window.__lindoLeavingDocument = $navigationId", isolated = true)
            web.call("navigate", buildJsonObject { put("url", "https://linux.do/") })
            val committed = "location.origin === 'https://linux.do' && window.__lindoLeavingDocument !== $navigationId && document.readyState !== 'loading'"
            withTimeout(20_000) {
                while ((web.evaluate(committed, isolated = true) as? JsonPrimitive)?.booleanOrNull != true) delay(150)
            }
        }
        web
    }
    private suspend fun seedSession(web: MacWebSession) {
        val saved = CookieSentinel.resolveWinningCookies(targetHost = "linux.do")
        val appToken = saved["_t"]?.value?.takeIf { it.isNotBlank() && it != "demo_session_token" }
        if (lastSeededToken == (appToken ?: "")) return
        val current = web.call("cookies", buildJsonObject { put("urls", buildJsonArray { add("https://linux.do/") }) })["cookies"]?.jsonArray
        val browserToken = current?.firstOrNull { it.jsonObject["name"]?.jsonPrimitive?.content == "_t" }?.jsonObject?.get("value")?.jsonPrimitive?.content
            ?.takeIf { it.isNotBlank() }
        // WebKit's persistent store receives Discourse's live _t rotations first; never clobber a live browser _t with an older snapshot.
        // Explicit account switches always go through replaceAccountCookies(), which clears previous auth cookies first.
        if (browserToken != null) {
            lastSeededToken = browserToken
            if (browserToken != appToken) {
                syncCookies()
                SavedAccounts.captureCurrent()
            }
            return
        }
        val cookies = buildJsonArray {
            saved.filterKeys { it in setOf("_t", "_forum_session") }.forEach { (name, cookie) ->
                if (cookie.value.isNotBlank() && cookie.value != "demo_session_token") add(buildJsonObject {
                    put("name", name); put("value", cookie.value); put("url", "https://linux.do/"); put("secure", true); put("httpOnly", true); put("sameSite", "Lax")
                })
            }
        }
        if (cookies.isNotEmpty()) web.call("setCookies", buildJsonObject { put("cookies", cookies) })
        lastSeededToken = appToken ?: ""
    }
    private suspend fun verify(login: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (LinuxDoRepository.isRateLimitedNow()) {
            PlatformSessionRecovery.verificationMessage = "站点暂时限制了请求，请稍后再试。"
            return@withContext false
        }
        if (!recovery.tryLock()) return@withContext false
        DesktopNetworkPolicy.verifying = true
        val generation = UserApiKeyAuthManager.sessionGeneration
        val expectedUsername = UserApiKeyAuthManager.currentUser?.username
        var panelShown = false
        suspend fun ensurePanelShown(web: MacWebSession) {
            if (!panelShown) {
                panelShown = true
                web.call("show", buildJsonObject { put("dark", UserApiKeyAuthManager.uiDarkTheme) })
            }
        }
        try {
            verificationDiagnostic = "opening"
            PlatformSessionRecovery.verificationMessage = "完成验证后自动继续阅读。"
            val web = attach(true) ?: return@withContext false
            AccountSessionGate.read { seedSession(web) }
            // Snapshot the interrupted read. Recovery probes must not replace this target.
            val recoveryUrl = MacVerificationPolicy.probeUrl(login, lastChallengeUrl)
            val initialUrl = if (login) "https://linux.do/login" else verificationPageUrl(recoveryUrl)
            verificationDiagnostic = "opening-${if (login) "login" else "recovery"}"
            LinDoDiagnosticLogger.info(DiagnosticCategory.AUTH, "MacVerification", "probe-target", URI(recoveryUrl).path)
            web.call("navigate", buildJsonObject { put("url", initialUrl) })
            if (login) ensurePanelShown(web)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
            var requestedLogin = login
            var navigatedToChallengedApi = false
            var challengeWaitTicks = 0
            while (System.nanoTime() < deadline) {
                delay(if (panelShown) 1_100L else 450L)
                val state = web.call("status")
                if (state["cancelled"]?.jsonPrimitive?.booleanOrNull == true) {
                    verificationDiagnostic = "cancelled"
                    PlatformSessionRecovery.verificationMessage = null
                    return@withContext false
                }
                if (!state["error"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) {
                    verificationDiagnostic = "navigation-failed"
                    PlatformSessionRecovery.verificationMessage = "网页暂时无法加载，请重试"
                    return@withContext false
                }
                if (generation != UserApiKeyAuthManager.sessionGeneration) return@withContext false
                // Wait for the real site, never click or solve a site's verification widget.
                val probe = try {
                    web.evaluate("""({
                        site: location.origin === 'https://linux.do',
                        complete: document.readyState !== 'loading',
                        challenge: !!document.querySelector('#challenge-running, #challenge-stage, #challenge-form, .cf-turnstile, iframe[src*="challenges.cloudflare.com"]') || /^(Just a moment|请稍候)/i.test(document.title || '')
                    })""".trimIndent(), isolated = true) as? JsonObject
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { verificationDiagnostic = "site-probe-${error.javaClass.simpleName}"; continue }
                val inDomChallenge = probe?.get("challenge")?.jsonPrimitive?.booleanOrNull == true
                if (inDomChallenge) {
                    challengeWaitTicks++
                    if (challengeWaitTicks >= 2) ensurePanelShown(web)
                    verificationDiagnostic = "waiting-for-challenge"
                    continue
                }
                challengeWaitTicks = 0
                val ready = probe?.get("site")?.jsonPrimitive?.booleanOrNull == true && probe["complete"]?.jsonPrimitive?.booleanOrNull == true
                verificationDiagnostic = if (ready) "checking-request" else "waiting-for-site-$probe"
                if (!ready) continue
                val probeTarget = if (requestedLogin) "https://linux.do/session/current.json" else recoveryUrl
                val response = AccountSessionGate.read { request(probeTarget, "GET", null, emptyMap(), web) }
                if (response == null) { verificationDiagnostic = "probe-unavailable"; delay(2_500L); continue }
                if (generation != UserApiKeyAuthManager.sessionGeneration) return@withContext false
                verificationDiagnostic = "probe-status-${response.first}-challenge-${looksLikeCloudflareChallenge(response.second)}"
                if (looksLikeCloudflareChallenge(response.second)) {
                    if (!panelShown && !requestedLogin && !navigatedToChallengedApi && initialUrl != probeTarget) {
                        navigatedToChallengedApi = true
                        web.call("navigate", buildJsonObject { put("url", probeTarget) })
                        continue
                    }
                    ensurePanelShown(web)
                    // Do not reload/navigate the challenge while the visitor is completing it.
                    delay(2_500L); continue
                }
                if (response.first >= 500) { delay(3_000L); continue }
                if (response.first == 429) { PlatformSessionRecovery.verificationMessage = "站点暂时限制了请求，请稍后再试。"; return@withContext false }
                if (!login && !requestedLogin && MacVerificationPolicy.isReadRecovered(response)) {
                    syncCookies()
                    verificationDiagnostic = "recovered-original-request"
                    finish(web, resetPage = navigatedToChallengedApi)
                    return@withContext true
                }
                val identity = if (requestedLogin) response else null
                val user = identity?.let { runCatching { json.parseToJsonElement(it.second).jsonObject["current_user"] as? JsonObject }.getOrNull() }
                if (identity?.first in 200..299 && user != null && user["username"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true) {
                    val verifiedUsername = user.getValue("username").jsonPrimitive.content
                    if (!login && !expectedUsername.isNullOrBlank() && !expectedUsername.equals(verifiedUsername, ignoreCase = true)) {
                        PlatformSessionRecovery.verificationMessage = "当前网页账号与 App 不同，请在账号管理中切换"
                        return@withContext false
                    }
                    bind(); syncCookies(expectAuthCookie = true)
                    if (login || expectedUsername.isNullOrBlank()) {
                        UserApiKeyAuthManager.onSessionAuthenticated(AuthenticatedUserProfile(
                            userId = user["id"]?.jsonPrimitive?.intOrNull ?: 0,
                            username = verifiedUsername,
                            displayName = user["name"]?.jsonPrimitive?.contentOrNull ?: verifiedUsername,
                            avatarTemplate = user["avatar_template"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            trustLevel = user["trust_level"]?.jsonPrimitive?.intOrNull ?: 1
                        ))
                    } else {
                        SavedAccounts.captureCurrent()
                        UserApiKeyAuthManager.persistSessionQuietly()
                    }
                    finish(web, resetPage = navigatedToChallengedApi); return@withContext true
                }
                if (!requestedLogin && UserApiKeyAuthManager.isAuthorized() && MacVerificationPolicy.isUnauthenticatedResponse(response)) {
                    requestedLogin = true
                    PlatformSessionRecovery.verificationMessage = "会话已过期，请在网页中登录，完成后会自动返回。"
                    web.call("navigate", buildJsonObject { put("url", "https://linux.do/login") })
                    ensurePanelShown(web)
                    continue
                }
                if (requestedLogin) ensurePanelShown(web)
                delay(2_000L)
            }
            verificationDiagnostic = "timed-out"
            PlatformSessionRecovery.verificationMessage = "站点仍未放行，请稍后重试。"
            false
        } catch (_: TimeoutCancellationException) {
            // Native command timeouts are failures, not cancellation of the Compose effect.
            // Returning false lets the caller clear its verifying state.
            verificationDiagnostic = "command-timed-out"
            PlatformSessionRecovery.verificationMessage = "网页响应超时，请重试。"
            false
        } catch (cancelled: CancellationException) {
            verificationDiagnostic = "cancelled"
            throw cancelled
        }
        catch (error: Throwable) {
            verificationDiagnostic = "failed-${error.javaClass.simpleName}"
            PlatformSessionRecovery.verificationMessage = (error as? IllegalStateException)?.message?.takeIf { it.isNotBlank() }
                ?: "连接未完成，请重试。"
            false
        }
        finally {
            DesktopNetworkPolicy.verifying = false
            withContext(NonCancellable) { runCatching { connection?.call("hide") } }
            recovery.unlock()
        }
    }
    private suspend fun finish(web: MacWebSession, resetPage: Boolean = false) {
        web.call("hide")
        if (resetPage) readySession(forceReload = true)
        persist?.invoke(true)
        DesktopNetworkPolicy.verifying = false
        PlatformSessionRecovery.verifying = false
        LinuxDoRepository.clearChallengeCooldown()
        PlatformSessionRecovery.verificationMessage = null
        lastChallengeUrl = null
        UserApiKeyAuthManager.notifyNetworkRestored()
        javax.swing.SwingUtilities.invokeLater { onReady?.invoke() }
    }
    private suspend fun syncCookies(expectAuthCookie: Boolean = false) {
        val web = attach(false) ?: return
        var cookies = web.call("cookies", buildJsonObject { put("urls", buildJsonArray { add("https://linux.do/") }) })["cookies"]?.jsonArray ?: return
        if (expectAuthCookie && cookies.none { it.jsonObject["name"]?.jsonPrimitive?.content == "_t" }) {
            delay(150L)
            cookies = web.call("cookies", buildJsonObject { put("urls", buildJsonArray { add("https://linux.do/") }) })["cookies"]?.jsonArray ?: cookies
        }
        val allowedNames = setOf("_t", "_forum_session", "cf_clearance", "__cf_bm", "_cfuvid")
        var syncedToken: String? = null
        cookies.map { it.jsonObject }.filter { it["name"]?.jsonPrimitive?.content in allowedNames }.forEach {
            val name = it.getValue("name").jsonPrimitive.content; val value = it.getValue("value").jsonPrimitive.content
            if (name == "_t" && value.isNotBlank()) syncedToken = value
            CookieSentinel.ingestRawCookieString("$name=$value", domain = "linux.do", hostOnly = true)
        }
        if (syncedToken != null) lastSeededToken = syncedToken
        UserApiKeyAuthManager.onSyncCookieToPlatform?.invoke("_t=synchronized")
    }
    private suspend fun request(url: String, method: String, form: Map<String,String>?, headers: Map<String,String>, active: MacWebSession? = null): Pair<Int,String>? = withContext(Dispatchers.IO) {
        if (!allowed(url)) return@withContext null
        if (LinuxDoRepository.isRateLimitedNow()) return@withContext 429 to """{"errors":["请求较频繁，请稍后再试"]}"""
        val web = active ?: readySession()
        val expectsHtml = headers.entries.any { it.key.equals("Accept", true) && it.value.startsWith("text/html", true) }
        val defaultHeaders = if (expectsHtml) mapOf("Accept" to "text/html") else mapOf("Accept" to "application/json", "X-Requested-With" to "XMLHttpRequest")
        val safeHeaders = defaultHeaders + headers.filterKeys {
            val lower = it.lowercase()
            lower !in setOf("cookie", "host", "origin", "referer", "user-agent", "content-length") &&
                !(expectsHtml && lower == "x-requested-with")
        }
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
                if (!token) { const r = await fetch('/session/csrf.json',{credentials:'include',signal:AbortSignal.timeout(12000)}); if(!r.ok) return {status:r.status,body:await r.text(),retryAfter:r.headers.get('Retry-After'),challenge:r.headers.get('cf-mitigated')}; token=(await r.json()).csrf; }
                if (!token) return {status:422,body:'{"errors":["会话尚未就绪，请稍后再试"]}'};
                c.headers['X-CSRF-Token']=token;
            }
            const r=await fetch(c.url,{method:c.method,headers:c.headers,credentials:'include',body:c.form?new URLSearchParams(c.form):undefined,signal:AbortSignal.timeout(15000)});
            return {status:r.status,body:await r.text(),retryAfter:r.headers.get('Retry-After'),challenge:r.headers.get('cf-mitigated')};
        })()""".trimIndent()
        val idempotentRead = active == null && method.uppercase() in setOf("GET", "HEAD")
        try {
            val evaluated = try {
                web.evaluate(script)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (err: Throwable) {
                if (!idempotentRead) throw err
                val recoveredWeb = readySession(forceReload = true)
                recoveredWeb.evaluate(script)
            }
            val result = (evaluated as? JsonObject) ?: if (idempotentRead) {
                val recoveredWeb = readySession(forceReload = true)
                recoveredWeb.evaluate(script) as? JsonObject
            } else null
            if (result == null) return@withContext null
            val status = result["status"]?.jsonPrimitive?.intOrNull ?: return@withContext null
            val body = result["body"]?.jsonPrimitive?.content.orEmpty()
            val responseBody = if (result["challenge"]?.jsonPrimitive?.contentOrNull == "challenge") "$CF_MITIGATED_CHALLENGE_PREFIX$body" else body
            if (status == 429 && !looksLikeCloudflareChallenge(responseBody)) {
                LinuxDoRepository.noteRetryAfter(result["retryAfter"]?.jsonPrimitive?.contentOrNull)
            }
            val isBackground = headers.entries.any { it.key.equals("Discourse-Background", true) && it.value == "true" }
            if (active == null && !isBackground && looksLikeCloudflareChallenge(responseBody) &&
                method.equals("GET", true) && MacVerificationPolicy.isRecoverableApiUrl(url)
            ) {
                lastChallengeUrl = url
            }
            val nowMs = System.currentTimeMillis()
            if (active == null && status in 200..399 && !looksLikeCloudflareChallenge(responseBody) &&
                (!idempotentRead || nowMs - lastCookieSyncAtMs >= 10_000L)
            ) {
                lastCookieSyncAtMs = nowMs
                scope.launch {
                    runCatching {
                        syncCookies()
                        SavedAccounts.captureCurrent()
                    }
                }
            }
            status to responseBody
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Throwable) { null }
    }
    private suspend fun binary(url: String): UserApiKeyAuthManager.BinaryHttpPayload? = withContext(Dispatchers.IO) {
        if (!allowed(url) || LinuxDoRepository.isRateLimitedNow()) return@withContext null
        val web = readySession()
        runCatching {
            val quoted = JsonPrimitive(url).toString()
            val script = """(async()=>{if(location.origin!=='https://linux.do')return null;const r=await fetch($quoted,{credentials:'include',signal:AbortSignal.timeout(15000)});if(r.status===429)return {status:429,retryAfter:r.headers.get('Retry-After'),challenge:r.headers.get('cf-mitigated')};const b=new Uint8Array(await r.arrayBuffer());if(b.length>12582912)return null;let s='';for(let i=0;i<b.length;i+=8192)s+=String.fromCharCode(...b.subarray(i,i+8192));return {status:r.status,type:r.headers.get('content-type')||'',data:btoa(s)};})()"""
            val evaluated = try {
                web.evaluate(script)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                readySession(forceReload = true).evaluate(script)
            }
            val result = evaluated as? JsonObject ?: return@runCatching null
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
        val web = readySession()
        val config = buildJsonObject {
            put("name", name); put("mime", mime)
            put("data", java.util.Base64.getEncoder().encodeToString(bytes))
        }
        try {
            val result = web.evaluate("""(async()=>{
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
}
