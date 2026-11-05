package do_.linux.lindo.core.network

import do_.linux.lindo.core.badge.UniversalBadgeEngine
import do_.linux.lindo.core.connect.ConnectStatsParser
import do_.linux.lindo.core.connect.ConnectTrustLevelReport
import do_.linux.lindo.core.connect.ConsolidatedTimingBatch
import do_.linux.lindo.core.connect.ScreenTrackEngine
import do_.linux.lindo.core.connect.UserPresenceService
import do_.linux.lindo.core.model.BadgeInjector
import do_.linux.lindo.core.model.CommunitySearchResult
import do_.linux.lindo.core.model.FlairInfo
import do_.linux.lindo.core.model.GrantedBadge
import do_.linux.lindo.core.model.PostItemModel
import do_.linux.lindo.core.model.PostReactionItem
import do_.linux.lindo.core.model.TopicDetailModel
import do_.linux.lindo.core.model.TopicSummary
import do_.linux.lindo.core.model.TopicUser
import do_.linux.lindo.core.model.UserActivityItem
import do_.linux.lindo.core.model.UserFullProfileSummary
import do_.linux.lindo.core.model.UserNotificationItem
import do_.linux.lindo.core.site.LinuxDoSiteRules
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import kotlinx.coroutines.CancellationException
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import java.net.InetAddress
import java.security.KeyPairGenerator
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Dns

/**
 * RFC 6265bis 4-元组 Cookie 实体 (`name`, `domain`, `path`, `hostOnly`)
 */
data class CookieQuadrupleKey(
    val name: String,
    val domain: String,
    val path: String = "/",
    val hostOnly: Boolean = true
)

data class StoredCookieRecord(
    val key: CookieQuadrupleKey,
    val value: String,
    val expiresAtEpochMs: Long? = null,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean {
        return expiresAtEpochMs != null && expiresAtEpochMs <= nowMs
    }
}

data class CookieVariant(
    val name: String,
    val value: String,
    val domain: String,
    val path: String = "/",
    val hostOnly: Boolean = true,
    val expiresAtEpochMs: Long? = null,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

/**
 * 防掉登录哨兵：`CookieSentinel`
 */
object CookieSentinel {
    val criticalCookieNames = setOf("_t", "_forum_session", "cf_clearance")
    private val store = LinkedHashMap<CookieQuadrupleKey, StoredCookieRecord>()
    private val sessionRefreshMutex = Mutex()

    @Synchronized
    fun putCookie(record: StoredCookieRecord) {
        if (record.key.name in criticalCookieNames && record.value.isBlank()) {
            return
        }
        store[record.key] = record
    }

    fun ingestAndDeduplicate(variants: List<CookieVariant>, nowMs: Long = System.currentTimeMillis()): List<CookieVariant> {
        variants.forEach { v ->
            putCookie(
                StoredCookieRecord(
                    key = CookieQuadrupleKey(name = v.name, domain = v.domain, path = v.path, hostOnly = v.hostOnly),
                    value = v.value,
                    expiresAtEpochMs = v.expiresAtEpochMs,
                    updatedAtEpochMs = v.updatedAtEpochMs
                )
            )
        }
        return resolveWinningCookies(nowMs).values.map { r ->
            CookieVariant(
                name = r.key.name,
                value = r.value,
                domain = r.key.domain,
                path = r.key.path,
                hostOnly = r.key.hostOnly,
                expiresAtEpochMs = r.expiresAtEpochMs,
                updatedAtEpochMs = r.updatedAtEpochMs
            )
        }
    }

    @Synchronized
    fun resolveWinningCookies(
        nowMs: Long = System.currentTimeMillis(),
        targetHost: String = "linux.do"
    ): Map<String, StoredCookieRecord> {
        val normalizedTarget = targetHost.trim().lowercase().trimStart('.')
        val grouped = store.values
            .filter { record ->
                !record.isExpired(nowMs) &&
                    record.value.isNotBlank() &&
                    matchesDomain(record.key, normalizedTarget)
            }
            .groupBy { it.key.name }

        val winners = LinkedHashMap<String, StoredCookieRecord>()
        for ((name, candidates) in grouped) {
            val winner = candidates.sortedWith(
                compareByDescending<StoredCookieRecord> { it.key.hostOnly }
                    .thenByDescending { it.key.path.length }
                    .thenByDescending { it.updatedAtEpochMs }
            ).first()
            winners[name] = winner
        }
        return winners
    }

    private fun matchesDomain(key: CookieQuadrupleKey, targetHost: String): Boolean {
        val cookieDomain = key.domain.trim().lowercase().trimStart('.')
        return if (key.hostOnly) {
            cookieDomain == targetHost
        } else {
            targetHost == cookieDomain || targetHost.endsWith(".$cookieDomain")
        }
    }

    fun buildCookieHeader(
        nowMs: Long = System.currentTimeMillis(),
        targetHost: String = "linux.do"
    ): String {
        return resolveWinningCookies(nowMs, targetHost)
            .entries
            .joinToString("; ") { "${it.key}=${it.value.value}" }
    }

    /**
     * 从系统 WebView / CookieManager 同步原始 Cookie 字符串（如 `_t=...; _forum_session=...; cf_clearance=...`）
     */
    fun ingestRawCookieString(
        rawCookieHeader: String?,
        domain: String = "linux.do",
        hostOnly: Boolean = false
    ) {
        if (rawCookieHeader.isNullOrBlank()) return
        val now = System.currentTimeMillis()
        rawCookieHeader.split(";").forEach { segment ->
            val trimmed = segment.trim()
            val eqIdx = trimmed.indexOf('=')
            if (eqIdx > 0) {
                val name = trimmed.substring(0, eqIdx).trim()
                val value = trimmed.substring(eqIdx + 1).trim()
                if (name.isNotEmpty() && value.isNotEmpty()) {
                    putCookie(
                        StoredCookieRecord(
                            key = CookieQuadrupleKey(
                                name = name,
                                domain = domain.trim().lowercase().trimStart('.'),
                                path = "/",
                                hostOnly = hostOnly
                            ),
                            value = value,
                            updatedAtEpochMs = now
                        )
                    )
                }
            }
        }
    }

    suspend fun <T> withSingleFlightRefresh(block: suspend () -> T): T {
        return sessionRefreshMutex.withLock {
            block()
        }
    }

    @Synchronized
    fun clear() {
        store.clear()
    }

    @Synchronized
    fun clearAll() {
        store.clear()
    }

    /**
     * 退出账号时仅清除 Discourse 会话 Cookie（`_t`、`_forum_session`），保留 Cloudflare 信任凭证（`cf_clearance`、`__cf_bm`、`_cfuvid`）以避免下次登录重新触发 Turnstile 验证
     */
    @Synchronized
    fun clearAuthCookiesOnly() {
        val cfTrustCookies = setOf("cf_clearance", "__cf_bm", "_cfuvid", "cf_chl_rc_i", "cf_chl_rc_ni")
        val keysToRemove = store.keys.filter { it.name !in cfTrustCookies }
        keysToRemove.forEach { store.remove(it) }
    }
}

/**
 * 已登录的 Linux.do 账号资料快照
 */
data class AuthenticatedUserProfile(
    val userId: Int = 0,
    val username: String,
    val displayName: String,
    val avatarTemplate: String = "",
    val animatedAvatar: String? = null,
    val trustLevel: Int = 1,
    val userTitle: String? = null
)

/**
 * 双引擎登录与会话认证管理器 (`UserApiKeyAuthManager`)：
 * 1. 支持内置 WebView 统一登录（密码 / GitHub / Passkey / 2FA）并自动捕获 `_t`、`_forum_session`、`cf_clearance` 与用户资料
 * 2. 支持系统浏览器一键授权回跳 (`discourse://auth_redirect` + `scopes=one_time_password` + RSA PKCS1 解密与 OTP 兑换)
 */
object UserApiKeyAuthManager {
    private var activeUserApiKey: String? = null
    private var clientId: String = "lindo-kmp-${UUID.randomUUID()}"
    private var pendingPrivateKey: java.security.PrivateKey? = null
    private var pendingNonce: String? = null

    @Volatile
    var currentUser: AuthenticatedUserProfile? = null
        private set

    @Volatile
    var fullProfileCache: UserFullProfileSummary? = null

    @Volatile
    var pendingOtpToken: String? = null

    @Volatile
    var customUserAgent: String? = null

    @Volatile
    var uiFontScale: Float = 1.0f

    @Volatile
    var uiDarkTheme: Boolean = false

    @Volatile
    var uiEnableAnimations: Boolean = true

    @Volatile
    var uiThemeAccent: String = "OFFICIAL_GOLD"

    @Volatile
    var uiCompactFeed: Boolean = false

    var onAuthStateChanged: (() -> Unit)? = null
    var openExternalUrlLauncher: ((String) -> Unit)? = null
    var onLogoutPlatformClear: (() -> Unit)? = null
    var onPersistSession: ((AuthenticatedUserProfile?, String?) -> Unit)? = null
    var onPersistPendingKey: ((String?, String?) -> Unit)? = null
    var onPersistUiPrefs: ((Float, Boolean, Boolean) -> Unit)? = null
    var onPersistThemeAndDensity: ((String, Boolean) -> Unit)? = null
    var onSyncCookieToPlatform: ((String) -> Unit)? = null

    fun updateUiPrefs(fontScale: Float, darkTheme: Boolean, enableAnimations: Boolean) {
        uiFontScale = fontScale
        uiDarkTheme = darkTheme
        uiEnableAnimations = enableAnimations
        onPersistUiPrefs?.invoke(fontScale, darkTheme, enableAnimations)
    }

    fun updateThemeAndDensityPrefs(themeAccent: String, compactFeed: Boolean) {
        uiThemeAccent = themeAccent
        uiCompactFeed = compactFeed
        onPersistThemeAndDensity?.invoke(themeAccent, compactFeed)
    }
    var webViewJsonFetcher: (suspend (String) -> String?)? = null
    var webViewConnectHtmlFetcher: (suspend () -> String?)? = null
    var webViewHttpExecutor: (suspend (
        url: String,
        method: String,
        formParams: Map<String, String>?,
        headers: Map<String, String>
    ) -> Pair<Int, String>?)? = null

    fun getClientId(): String = clientId

    fun restoreClientId(saved: String?) {
        if (!saved.isNullOrBlank()) {
            clientId = saved.trim()
        }
    }

    fun exportPendingPrivateKeyBase64(): String? {
        val pk = pendingPrivateKey?.encoded ?: return null
        return Base64.getEncoder().encodeToString(pk)
    }

    fun restorePendingPrivateKeyBase64(pkcs8B64: String?, nonce: String?) {
        if (pkcs8B64.isNullOrBlank()) return
        runCatching {
            val bytes = Base64.getMimeDecoder().decode(pkcs8B64.trim())
            val spec = java.security.spec.PKCS8EncodedKeySpec(bytes)
            val kf = java.security.KeyFactory.getInstance("RSA")
            pendingPrivateKey = kf.generatePrivate(spec)
            if (!nonce.isNullOrBlank()) {
                pendingNonce = nonce
            }
            println("LinDoAuth: Restored pendingPrivateKey from disk")
        }.onFailure {
            println("LinDoAuth: Failed to restore pendingPrivateKey: ${it.message}")
        }
    }

    @Synchronized
    fun consumePendingOtpToken(): String? {
        val token = pendingOtpToken
        pendingOtpToken = null
        return token
    }

    fun setApiKey(apiKey: String?) {
        activeUserApiKey = apiKey?.trim()?.takeIf { it.isNotEmpty() }
        onPersistSession?.invoke(currentUser, activeUserApiKey)
        onAuthStateChanged?.invoke()
    }

    fun getApiKey(): String? = activeUserApiKey

    fun onSessionAuthenticated(
        profile: AuthenticatedUserProfile,
        rawCookies: String? = null
    ) {
        if (!rawCookies.isNullOrBlank()) {
            CookieSentinel.ingestRawCookieString(rawCookies, domain = "linux.do", hostOnly = false)
        }
        currentUser = profile
        onPersistSession?.invoke(profile, activeUserApiKey)
        onAuthStateChanged?.invoke()
    }

    fun recordWebSessionLogin(
        username: String,
        displayName: String,
        avatarUrl: String,
        trustLevel: Int,
        userId: Int = 0,
        animatedAvatar: String? = null,
        userTitle: String? = null
    ) {
        if (username.isBlank() || username == "linuxdo_user") return
        val updated = AuthenticatedUserProfile(
            userId = userId,
            username = username,
            displayName = displayName.ifBlank { username },
            avatarTemplate = avatarUrl,
            animatedAvatar = animatedAvatar,
            trustLevel = trustLevel,
            userTitle = userTitle
        )
        currentUser = updated
        onPersistSession?.invoke(updated, activeUserApiKey)
    }

    fun restorePersistedSession(
        profile: AuthenticatedUserProfile?,
        apiKey: String?,
        rawCookies: String?
    ) {
        if (!rawCookies.isNullOrBlank()) {
            CookieSentinel.ingestRawCookieString(rawCookies, domain = "linux.do", hostOnly = false)
        }
        activeUserApiKey = apiKey?.trim()?.takeIf { it.isNotEmpty() }
        // 过滤掉旧版曾写入的假占位用户名，确保只保留真实账号
        currentUser = profile?.takeIf { it.username != "linuxdo_user" }
        onAuthStateChanged?.invoke()
    }

    fun isAuthorized(): Boolean {
        val realUser = currentUser?.takeIf { it.username != "linuxdo_user" }
        return realUser != null ||
            CookieSentinel.resolveWinningCookies(targetHost = "linux.do").containsKey("_t")
    }

    fun logout() {
        activeUserApiKey = null
        currentUser = null
        fullProfileCache = null
        pendingOtpToken = null
        CookieSentinel.clearAuthCookiesOnly()
        onLogoutPlatformClear?.invoke()
        onPersistSession?.invoke(null, null)
        onAuthStateChanged?.invoke()
    }

    /**
     * 生成对齐 Linux.do 服务端白名单的浏览器一键授权链接：
     * - `scopes=one_time_password`（Linux.do 现行允许范围）
     * - `auth_redirect=discourse://auth_redirect`（Discourse 官方白名单回跳协议）
     * - `otp=true`（随回调返回一次性登录兑换令牌）
     */
    fun generateAuthorizationUrl(
        applicationName: String = "LinDo",
        scopes: String = "one_time_password"
    ): Pair<String, String> {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()
        pendingPrivateKey = kp.private
        val publicPem = buildString {
            appendLine("-----BEGIN PUBLIC KEY-----")
            appendLine(Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(kp.public.encoded))
            append("-----END PUBLIC KEY-----")
        }
        val nonce = UUID.randomUUID().toString().replace("-", "")
        pendingNonce = nonce
        // 立即持久化待回调 RSA 私钥与 nonce，防止切换到 Chrome 期间进程或 Activity 被系统回收
        onPersistPendingKey?.invoke(exportPendingPrivateKeyBase64(), nonce)
        val encodedPub = java.net.URLEncoder.encode(publicPem, "UTF-8")
        val encodedApp = java.net.URLEncoder.encode(applicationName, "UTF-8")
        val encodedRedirect = java.net.URLEncoder.encode("discourse://auth_redirect", "UTF-8")
        val url = "https://linux.do/user-api-key/new" +
            "?application_name=$encodedApp" +
            "&client_id=$clientId" +
            "&scopes=$scopes" +
            "&public_key=$encodedPub" +
            "&nonce=$nonce" +
            "&auth_redirect=$encodedRedirect" +
            "&otp=true"
        return url to nonce
    }

    /**
     * 启动系统浏览器进行一键授权
     */
    fun startBrowserOneClickAuth(): String {
        val (url, _) = generateAuthorizationUrl()
        openExternalUrlLauncher?.invoke(url)
        return url
    }

    private fun decryptRsaBase64(rawB64: String, privKey: java.security.PrivateKey): String? {
        return try {
            val normalized = rawB64
                .trim()
                .replace(" ", "+")
                .replace("-", "+")
                .replace("_", "/")
            val encryptedBytes = Base64.getMimeDecoder().decode(normalized)
            val cipher = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, privKey)
            String(cipher.doFinal(encryptedBytes), Charsets.UTF_8)
        } catch (t: Throwable) {
            println("LinDoAuth: decryptRsaBase64 failed: ${t.message}")
            null
        }
    }

    /**
     * 处理 `discourse://auth_redirect?payload=...&oneTimePassword=...` 深链回调，RSA/ECB/PKCS1Padding 解密
     */
    fun handleAuthRedirectUri(uriString: String): Boolean {
        val privKey = pendingPrivateKey
        if (privKey == null) {
            println("LinDoAuth: handleAuthRedirectUri aborted because pendingPrivateKey is null")
            return false
        }
        return try {
            val query = uriString.substringAfter('?', "")
            if (query.isEmpty()) return false
            val params = query.split("&").associate { part ->
                val idx = part.indexOf('=')
                if (idx > 0) {
                    val k = part.substring(0, idx)
                    val v = java.net.URLDecoder.decode(part.substring(idx + 1), "UTF-8")
                    k to v
                } else {
                    part to ""
                }
            }
            println("LinDoAuth: handleAuthRedirectUri query keys=${params.keys}")

            var key: String? = null
            var otpInPayload: String? = null

            val payloadB64 = params["payload"]
            if (!payloadB64.isNullOrBlank()) {
                val decryptedJson = decryptRsaBase64(payloadB64, privKey)
                if (!decryptedJson.isNullOrBlank()) {
                    runCatching {
                        val jsonObj = Json { ignoreUnknownKeys = true }.parseToJsonElement(decryptedJson).jsonObject
                        key = jsonObj["key"]?.jsonPrimitive?.contentOrNull
                        otpInPayload = jsonObj["one_time_password"]?.jsonPrimitive?.contentOrNull
                            ?: jsonObj["oneTimePassword"]?.jsonPrimitive?.contentOrNull
                    }
                }
            }

            // Discourse UserApiKeysController 在 query 参数中以 `oneTimePassword` (camelCase) 或 `one_time_password` 携带 RSA 加密的一次性令牌
            val otpParamB64 = params["oneTimePassword"]
                ?: params["one_time_password"]
                ?: params["otp"]
                ?: params.entries.firstOrNull {
                    it.key.equals("oneTimePassword", ignoreCase = true) ||
                        it.key.equals("one_time_password", ignoreCase = true)
                }?.value

            val decryptedOtp = if (!otpParamB64.isNullOrBlank()) {
                decryptRsaBase64(otpParamB64, privKey)?.trim()?.takeIf { it.isNotEmpty() } ?: otpInPayload
            } else {
                otpInPayload
            }

            println("LinDoAuth: Decrypted keyPresent=${!key.isNullOrBlank()}, otpPresent=${!decryptedOtp.isNullOrBlank()}")

            if (key.isNullOrBlank() && decryptedOtp.isNullOrBlank()) {
                return false
            }

            if (!key.isNullOrBlank()) {
                activeUserApiKey = key
            }
            if (!decryptedOtp.isNullOrBlank()) {
                pendingOtpToken = decryptedOtp
            }
            onPersistSession?.invoke(currentUser, activeUserApiKey)
            onAuthStateChanged?.invoke()
            true
        } catch (t: Throwable) {
            println("LinDoAuth: handleAuthRedirectUri exception: ${t.message}")
            false
        }
    }
}

data class EdgeCandidateResult(
    val ip: String,
    val providerLabel: String,
    val rttMs: Long,
    val traceVerified: Boolean,
    val colo: String
)

/**
 * Cloudflare 边缘节点并发竞速与单一出口固定器 (`CloudflareEdgeRacer`)
 */
object CloudflareEdgeRacer {
    val defaultCandidates = listOf(
        "104.21.48.1" to "Cloudflare Primary",
        "172.67.182.1" to "Cloudflare Secondary",
        "104.16.132.229" to "Cloudflare APAC-1",
        "104.17.112.1" to "Cloudflare APAC-2"
    )

    @Volatile
    var pinnedEdgeIp: String? = null
        private set

    @Volatile
    var lastRaceResults: List<EdgeCandidateResult> = emptyList()
        private set

    suspend fun raceAndPinBestNode(): List<EdgeCandidateResult> = withContext(Dispatchers.IO) {
        val probeClient = HttpClient(OkHttp) {
            engine {
                config {
                    connectTimeout(2500, TimeUnit.MILLISECONDS)
                    readTimeout(2500, TimeUnit.MILLISECONDS)
                }
            }
        }

        val results = coroutineScope {
            defaultCandidates.map { (ip, label) ->
                async {
                    val start = System.nanoTime()
                    try {
                        val resp = probeClient.get("https://$ip/cdn-cgi/trace") {
                            header("Host", "linux.do")
                        }
                        val elapsedMs = ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(1L)
                        val body = resp.bodyAsText()
                        val colo = Regex("""colo=([A-Z]+)""").find(body)?.groupValues?.getOrNull(1) ?: "CF"
                        val verified = body.contains("h=linux.do") || body.contains("fl=")
                        EdgeCandidateResult(
                            ip = ip,
                            providerLabel = label,
                            rttMs = elapsedMs,
                            traceVerified = verified,
                            colo = colo
                        )
                    } catch (_: Throwable) {
                        val elapsedMs = ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(180L)
                        EdgeCandidateResult(
                            ip = ip,
                            providerLabel = label,
                            rttMs = elapsedMs,
                            traceVerified = false,
                            colo = "AUTO"
                        )
                    }
                }
            }.awaitAll().sortedWith(compareByDescending<EdgeCandidateResult> { it.traceVerified }.thenBy { it.rttMs })
        }

        probeClient.close()
        lastRaceResults = results
        results.firstOrNull()?.let { winner ->
            pinnedEdgeIp = winner.ip
        }
        results
    }
}

/**
 * Linux.do 板块元数据定义
 */
data class LinuxDoCategoryMeta(
    val id: Int,
    val name: String,
    val slug: String,
    val colorHex: String,
    val description: String
)

/**
 * Linux.do 核心数据仓库（100% 纯净线上社区内容）
 */
internal fun isSuccessfulApiResponse(status: Int, body: String): Boolean {
    val trimmed = body.trimStart()
    return status in 200..299 && (trimmed.isEmpty() || trimmed.startsWith("{") || trimmed.startsWith("["))
}

object LinuxDoRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    val categories: List<LinuxDoCategoryMeta> = listOf(
        LinuxDoCategoryMeta(4, "开发调优", "develop", "0E76BD", "软件开发与开源实践"),
        LinuxDoCategoryMeta(14, "资源荟萃", "resource", "12A89D", "软件工具与数字资源"),
        LinuxDoCategoryMeta(42, "文档共建", "wiki", "25AAE2", "技术沉淀与教程指南"),
        LinuxDoCategoryMeta(11, "搞七捻三", "gossip", "9EB83B", "日常交流与职场故事"),
        LinuxDoCategoryMeta(36, "福利羊毛", "welfare", "F7941D", "社区福利与公益接口"),
        LinuxDoCategoryMeta(34, "前沿快讯", "news", "BF1E2E", "AI 动态与前沿科技"),
        LinuxDoCategoryMeta(27, "非我莫属", "jobs", "652D90", "人才对接与远程协作"),
        LinuxDoCategoryMeta(10, "跳蚤市场", "market", "F1592A", "闲置数码与算力流转"),
        LinuxDoCategoryMeta(32, "读书成诗", "reading", "0088CC", "书评笔记与阅读推荐"),
        LinuxDoCategoryMeta(46, "扬帆起航", "startup", "3AB54A", "独立开发与产品出海"),
        LinuxDoCategoryMeta(2, "运营反馈", "feedback", "808281", "社区站务与功能建议")
    )

    private val categoryLookup: Map<Int, Pair<String, String>> = buildMap {
        categories.forEach { put(it.id, it.name to it.colorHex) }
        put(20, "开发调优" to "0E76BD")
        put(21, "资源荟萃" to "12A89D")
        put(30, "搞七捻三" to "9EB83B")
        put(45, "福利羊毛" to "F7941D")
        put(52, "前沿快讯" to "BF1E2E")
        put(64, "开发调优" to "0E76BD")
        put(72, "前沿快讯" to "BF1E2E")
        put(88, "资源荟萃" to "12A89D")
    }

    fun resolveCategoryMeta(categoryId: Int): Pair<String, String> {
        return categoryLookup[categoryId] ?: ("技术讨论" to "0E76BD")
    }

    private val dohBootstrapClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(2500, TimeUnit.MILLISECONDS)
            .readTimeout(2500, TimeUnit.MILLISECONDS)
            .build()
    }

    @Volatile
    private var cachedDohAddresses: List<InetAddress> = emptyList()

    @Volatile
    private var cachedDohExpireEpochMs: Long = 0L

    /**
     * 内置免配置加密 DoH 解析器（直连阿里 `223.5.5.5` / 腾讯 `1.12.12.12` DoH JSON 接口，无需通过传统 53 端口解析域名）
     */
    private fun resolveViaEncryptedDoh(hostname: String): List<InetAddress> {
        val now = System.currentTimeMillis()
        if (cachedDohAddresses.isNotEmpty() && now < cachedDohExpireEpochMs) {
            return cachedDohAddresses
        }
        val dohEndpoints = listOf(
            "https://223.5.5.5/resolve?name=$hostname&type=A",
            "https://1.12.12.12/resolve?name=$hostname&type=A"
        )
        for (endpoint in dohEndpoints) {
            try {
                val req = okhttp3.Request.Builder()
                    .url(endpoint)
                    .header("Accept", "application/dns-json, application/json")
                    .build()
                dohBootstrapClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        val root = json.parseToJsonElement(body).jsonObject
                        val answers = root["Answer"] as? JsonArray
                        val ips = answers?.mapNotNull { elem ->
                            val obj = elem as? JsonObject ?: return@mapNotNull null
                            val type = obj["type"]?.jsonPrimitive?.intOrNull
                            val data = obj["data"]?.jsonPrimitive?.contentOrNull?.trim()
                            if (type == 1 && !data.isNullOrBlank() && !data.startsWith("127.") && !data.startsWith("0.")) {
                                runCatching { InetAddress.getByName(data) }.getOrNull()
                            } else null
                        }.orEmpty()
                        if (ips.isNotEmpty()) {
                            cachedDohAddresses = ips
                            cachedDohExpireEpochMs = now + 300_000L // 缓存 5 分钟
                            return ips
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }
        return emptyList()
    }

    private val sharedClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            engine {
                config {
                    connectTimeout(6, TimeUnit.SECONDS)
                    readTimeout(8, TimeUnit.SECONDS)
                    dns(object : Dns {
                        override fun lookup(hostname: String): List<InetAddress> {
                            if (hostname == "linux.do" || hostname.endsWith(".linux.do")) {
                                // 1. 优先使用用户测速锁定的最优 Cloudflare 边缘节点 IP
                                val pinned = CloudflareEdgeRacer.pinnedEdgeIp
                                if (!pinned.isNullOrBlank()) {
                                    runCatching { listOf(InetAddress.getByName(pinned)) }
                                        .getOrNull()?.let { return it }
                                }
                                // 2. 自动走加密 DoH (223.5.5.5 / 1.12.12.12) 防运营商 DNS 污染
                                val dohList = resolveViaEncryptedDoh(hostname)
                                if (dohList.isNotEmpty()) {
                                    return dohList
                                }
                                // 3. 兜底使用内置 Cloudflare 边缘节点 IP + 系统 DNS
                                val fallbackEdgeIps = CloudflareEdgeRacer.defaultCandidates.mapNotNull { (ip, _) ->
                                    runCatching { InetAddress.getByName(ip) }.getOrNull()
                                }
                                val sysIps = runCatching { Dns.SYSTEM.lookup(hostname) }.getOrDefault(emptyList())
                                val combined = (sysIps + fallbackEdgeIps).distinct()
                                if (combined.isNotEmpty()) return combined
                            }
                            return Dns.SYSTEM.lookup(hostname)
                        }
                    })
                }
            }
        }
    }

    init {
        UniversalBadgeEngine.registerDiscourseSpriteBundle(
            """
            <symbol id="linuxdo-cyber-lotus" viewBox="0 0 24 24"><path d="M12 2L15 8L22 9L17 14L18 21L12 18L6 21L7 14L2 9L9 8Z"/></symbol>
            <symbol id="d-icon-ld-pioneer-2026" viewBox="0 0 24 24"><path d="M12 1L3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4z"/></symbol>
            """.trimIndent()
        )
    }

    /**
     * 统一 HTTP 执行器：
     * 1. 若平台已挂载 Chromium WebView 通道 (`webViewHttpExecutor`) 或 `webViewJsonFetcher`，优先走共享 TLS 指纹通道，天然携带 `cf_clearance`、`_t` 与 CSRF Token；
     * 2. 注意：当存在 `_t` 会话 Cookie 或走同源 WebView 时，绝不附加仅含 `one_time_password` 权限的 `User-Api-Key` 标头，避免 Discourse 因 Scope 限制返回 403 Forbidden！
     */
    private suspend fun executeHttp(
        url: String,
        method: String = "GET",
        formParams: Map<String, String>? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        preserveFailure: Boolean = false
    ): Pair<Int, String>? = withContext(Dispatchers.IO) {
        val hasWebCookie = CookieSentinel.resolveWinningCookies().containsKey("_t")
        val isLoggedIn = UserApiKeyAuthManager.isAuthorized() || hasWebCookie
        val presenceHeaders = UserPresenceService.buildPresenceHeaders(
            isBackgroundTimingRequest = extraHeaders["Discourse-Background"] == "true",
            isLoggedIn = isLoggedIn
        )
        val mergedHeaders = buildMap {
            put("Accept", "application/json")
            put("X-Requested-With", "XMLHttpRequest")
            presenceHeaders.forEach { (k, v) -> put(k, v) }
            // 仅当不存在 Web 会话 Cookie 且无 WebView 执行器时才附带 User-Api-Key
            if (!hasWebCookie && UserApiKeyAuthManager.webViewHttpExecutor == null) {
                UserApiKeyAuthManager.getApiKey()?.let { put("User-Api-Key", it) }
            }
            extraHeaders.forEach { (k, v) -> put(k, v) }
        }

        suspend fun viaWebView(): Pair<Int, String>? {
            val executor = UserApiKeyAuthManager.webViewHttpExecutor ?: return null
            return try {
                executor(url, method, formParams, mergedHeaders)?.takeIf {
                    preserveFailure || isSuccessfulApiResponse(it.first, it.second)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
        // Mutations need the browser session's CSRF token. Never retry a write through a second transport.
        if (!method.equals("GET", true) && !method.equals("HEAD", true) && UserApiKeyAuthManager.webViewHttpExecutor != null) {
            return@withContext viaWebView()
        }
        var lastResponse: Pair<Int, String>? = null
        try {
            val cookieHeader = CookieSentinel.buildCookieHeader(targetHost = "linux.do")
            val requestHeaders = mergedHeaders.toMutableMap()
            UserApiKeyAuthManager.customUserAgent?.let { requestHeaders["User-Agent"] = it }
            if (cookieHeader.isNotEmpty()) requestHeaders["Cookie"] = cookieHeader
            val response = executeApiRequest(sharedClient, url, method, formParams, requestHeaders)
            val status = response.status.value
            val text = response.bodyAsText()
            lastResponse = status to text
            if (isSuccessfulApiResponse(status, text)) {
                if (mergedHeaders["Discourse-Background"] != "true") UserPresenceService.recordSuccessfulForegroundRequest()
                return@withContext status to text
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A read can safely fall back to the existing authenticated browser transport.
        }
        val browserResult = viaWebView()
        if (browserResult != null && browserResult.first != 0) {
            if (mergedHeaders["Discourse-Background"] != "true") UserPresenceService.recordSuccessfulForegroundRequest()
            return@withContext browserResult
        }

        if (preserveFailure) lastResponse ?: browserResult else null
    }

    /**
     * 静默无感兑换系统浏览器回调的 OTP 令牌（免打开 WebView 页面，从而规避整页 HTML 导航的 Cloudflare Turnstile 验证）：
     * - 先通过 OkHttp `sharedClient` 获取 `/session/csrf.json` 与 `_forum_session`；
     * - 再 POST `/session/otp/$otp` 直接捕获服务端下发的 `_t` 会话 Cookie 并同步至系统 `CookieManager`；
     * - 若直连兑换成功返回 `true`（直接完成登录），若被拦截则返回 `false` 交由 WebView 兜底。
     */
    suspend fun redeemOtpSilently(otp: String): Boolean = withContext(Dispatchers.IO) {
        if (otp.isBlank()) return@withContext false
        try {
            val initialCookie = CookieSentinel.buildCookieHeader(targetHost = "linux.do")
            val csrfResp = sharedClient.get("https://linux.do/session/csrf.json") {
                header("Accept", "application/json")
                header("X-Requested-With", "XMLHttpRequest")
                UserApiKeyAuthManager.customUserAgent?.let { header("User-Agent", it) }
                if (initialCookie.isNotEmpty()) header("Cookie", initialCookie)
            }
            csrfResp.headers.getAll("Set-Cookie")?.forEach { setCookie ->
                val pair = setCookie.substringBefore(';')
                CookieSentinel.ingestRawCookieString(pair, domain = "linux.do", hostOnly = false)
                UserApiKeyAuthManager.onSyncCookieToPlatform?.invoke(setCookie)
            }
            val csrfBody = csrfResp.bodyAsText()
            val csrfToken = runCatching {
                json.parseToJsonElement(csrfBody).jsonObject["csrf"]?.jsonPrimitive?.contentOrNull
            }.getOrNull() ?: return@withContext false

            val updatedCookie = CookieSentinel.buildCookieHeader(targetHost = "linux.do")
            val otpResp = sharedClient.submitForm(
                url = "https://linux.do/session/otp/${otp.trim()}",
                formParameters = Parameters.Empty
            ) {
                header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
                header("X-CSRF-Token", csrfToken)
                header("X-Requested-With", "XMLHttpRequest")
                UserApiKeyAuthManager.customUserAgent?.let { header("User-Agent", it) }
                if (updatedCookie.isNotEmpty()) header("Cookie", updatedCookie)
            }
            otpResp.headers.getAll("Set-Cookie")?.forEach { setCookie ->
                val pair = setCookie.substringBefore(';')
                CookieSentinel.ingestRawCookieString(pair, domain = "linux.do", hostOnly = false)
                UserApiKeyAuthManager.onSyncCookieToPlatform?.invoke(setCookie)
            }

            if (CookieSentinel.resolveWinningCookies(targetHost = "linux.do").containsKey("_t")) {
                fetchCurrentUserProfile()
                UserApiKeyAuthManager.onAuthStateChanged?.invoke()
                return@withContext true
            }
        } catch (t: Throwable) {
            println("LinDoAuth: redeemOtpSilently failed: ${t.message}")
        }
        false
    }

    /**
     * 拉取线上话题列表：
     * - 支持 `latest.json`、`top.json`（含 `period=daily|weekly|monthly|yearly|all`）、`new.json`、`unread.json`、`read.json`
     * - 支持板块筛选 `/c/{slug}/{id}.json` 与标签筛选 `/tag/{tag}.json`
     * - 支持标准 Discourse 分页 `no_definitions=true&page={page}`
     */
    suspend fun fetchTopics(
        path: String = "latest.json",
        categoryId: Int? = null,
        page: Int = 0,
        tag: String? = null,
        topPeriod: String? = null
    ): List<TopicSummary> = withContext(Dispatchers.IO) {
        val basePath = when {
            !tag.isNullOrBlank() -> {
                val encodedTag = java.net.URLEncoder.encode(tag.trim(), "UTF-8")
                "tag/$encodedTag.json"
            }
            categoryId != null -> {
                val cat = categories.firstOrNull { it.id == categoryId }
                if (cat != null) "c/${cat.slug}/${cat.id}.json" else "c/$categoryId.json"
            }
            else -> path
        }

        val queryParams = mutableListOf("no_definitions=true")
        if (basePath.startsWith("top") && !topPeriod.isNullOrBlank()) {
            queryParams.add("period=$topPeriod")
        }
        if (page > 0) {
            queryParams.add("page=$page")
        }
        val sep = if (basePath.contains("?")) "&" else "?"
        val url = "https://linux.do/${basePath.trimStart('/')}$sep${queryParams.joinToString("&")}"
        val root = readApiObject(executeHttp(url = url, method = "GET", preserveFailure = true))
        (root["topic_list"] as? JsonObject ?: throw ApiReadException(ApiFailureKind.InvalidPayload)).requireArray("topics")
        parseTopicListRoot(root)
    }

    /**
     * 实时拉取指定话题详情 (`/t/{id}.json?track_visit=true`)，支持 `filterUsername` (只看楼主)
     *
     * 对齐 Discourse 官方前端：
     * - 携带 `track_visit=true`、`Discourse-Track-View: 1`、`Discourse-Track-View-Topic-Id: {id}`
     *   以确保后端递增 `UserStat.topics_entered`（即 Connect 中的「浏览话题」指标）！
     */
    suspend fun fetchTopicDetail(
        topicId: Int,
        summaryFallback: TopicSummary? = null,
        trackVisit: Boolean = true,
        filterUsername: String? = null
    ): TopicDetailModel = withContext(Dispatchers.IO) {
        val queryParts = mutableListOf<String>()
        if (trackVisit) queryParts.add("track_visit=true")
        if (!filterUsername.isNullOrBlank()) queryParts.add("username_filters=$filterUsername")
        val querySuffix = if (queryParts.isNotEmpty()) "?${queryParts.joinToString("&")}" else ""
        val url = "https://linux.do/t/$topicId.json$querySuffix"
        val extraHeaders = if (trackVisit) {
            mapOf(
                "Discourse-Track-View" to "1",
                "Discourse-Track-View-Topic-Id" to topicId.toString()
            )
        } else {
            emptyMap()
        }

        val res = executeHttp(url = url, method = "GET", extraHeaders = extraHeaders)
        val text = res?.second
        if (text != null && text.trimStart().startsWith("{")) {
            try {
                val root = json.parseToJsonElement(text).jsonObject
                val detail = parseLiveTopicDetail(root, summaryFallback)
                if (detail.posts.isNotEmpty()) {
                    return@withContext detail
                }
            } catch (_: Throwable) {
            }
        }

        // Preserve the real summary, but never fabricate a first post from an excerpt on failure.
        val s = summaryFallback
        TopicDetailModel(
            id = s?.id ?: topicId,
            title = s?.title ?: "话题 #$topicId",
            categoryName = s?.categoryName ?: "技术讨论",
            categoryColorHex = s?.categoryColorHex ?: "0E76BD",
            tags = s?.tags.orEmpty(),
            views = s?.views ?: 0,
            likeCount = s?.likeCount ?: 0,
            postsCount = s?.postsCount ?: 1,
            posts = emptyList(),
            loadError = "网络请求失败，或当前账号没有访问此话题的权限。"
        )
    }

    /**
     * 按 `post_stream.stream` 中的帖子 ID 列表分批拉取后续全部评论楼层 (`/t/{topicId}/posts.json?post_ids[]=...`)
     */
    suspend fun fetchMoreTopicPosts(
        topicId: Int,
        postIds: List<Int>
    ): List<PostItemModel> = withContext(Dispatchers.IO) {
        if (postIds.isEmpty()) return@withContext emptyList()
        val query = postIds.take(20).joinToString("&") { "post_ids[]=$it" }
        val url = "https://linux.do/t/$topicId/posts.json?$query"
        try {
            val root = readApiObject(executeHttp(url = url, method = "GET", preserveFailure = true))
            val postStream = root["post_stream"] as? JsonObject ?: throw ApiReadException(ApiFailureKind.InvalidPayload)
            val postsArr = postStream.requireArray("posts")
            val rawPosts = postsArr.mapIndexedNotNull { idx, elem ->
                parseSinglePostObject(elem as? JsonObject ?: return@mapIndexedNotNull null, idx)
            }
            BadgeInjector.injectTopLevelUserBadges(rawPosts, root)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ApiReadException) {
            throw error
        } catch (error: Exception) {
            throw ApiReadException(ApiFailureKind.InvalidPayload, error)
        }
    }

    /**
     * 实时同步当前登录用户的完整资料、真实信任等级 (`Lv.0` ~ `Lv.4`)、真实头像、简介与互动统计
     * 依次请求：
     * 1. `/session/current.json`（确认真实 username、trust_level、avatar_template、unread_notifications）
     * 2. `/u/{username}.json`（获取 bio_excerpt、title、custom_fields、badge_count）
     * 3. `/u/{username}/summary.json`（获取 days_visited、time_read、posts_read_count、likes_received、likes_given、topic_count、post_count、badges）
     */
    suspend fun fetchCurrentUserProfile(): UserFullProfileSummary? = withContext(Dispatchers.IO) {
        var currentUsername = UserApiKeyAuthManager.currentUser?.username?.takeIf {
            it.isNotBlank() && it != "linuxdo_user"
        }
        var userId = UserApiKeyAuthManager.currentUser?.userId ?: 0
        var displayName = UserApiKeyAuthManager.currentUser?.displayName ?: ""
        var avatarUrl = UserApiKeyAuthManager.currentUser?.avatarTemplate ?: ""
        var animatedAvatar = UserApiKeyAuthManager.currentUser?.animatedAvatar
        var trustLevel = UserApiKeyAuthManager.currentUser?.trustLevel ?: 1
        var title = UserApiKeyAuthManager.currentUser?.userTitle ?: ""
        var unreadNotifs = 0

        // 1. 探测 /session/current.json 获取最权威的当前登录态
        val sessionRes = executeHttp("https://linux.do/session/current.json", method = "GET")
        if (sessionRes != null && sessionRes.second.trimStart().startsWith("{")) {
            try {
                val root = json.parseToJsonElement(sessionRes.second).jsonObject
                val cu = root["current_user"] as? JsonObject
                if (cu != null) {
                    userId = cu["id"]?.jsonPrimitive?.intOrNull ?: userId
                    currentUsername = cu["username"]?.jsonPrimitive?.contentOrNull ?: currentUsername
                    displayName = cu["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                        ?: currentUsername ?: displayName
                    val rawTpl = cu["avatar_template"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (rawTpl.isNotBlank()) {
                        avatarUrl = LinuxDoSiteRules.resolveUrl(rawTpl)
                    }
                    animatedAvatar = LinuxDoSiteRules.resolveAnimatedAvatarUrl(
                        cu["animated_avatar"]?.jsonPrimitive?.contentOrNull) ?: animatedAvatar
                    trustLevel = cu["trust_level"]?.jsonPrimitive?.intOrNull ?: trustLevel
                    title = cu["title"]?.jsonPrimitive?.contentOrNull ?: title
                    unreadNotifs = cu["unread_notifications"]?.jsonPrimitive?.intOrNull
                        ?: cu["all_unread_notifications_count"]?.jsonPrimitive?.intOrNull ?: 0
                }
            } catch (_: Throwable) {
            }
        }

        val uname = currentUsername?.takeIf { it.isNotBlank() && it != "linuxdo_user" } ?: return@withContext null

        var bioExcerpt = ""
        var primaryGroupName: String? = null
        var admin = false
        var moderator = false
        var createdAtText = ""
        var badgeCount = 0

        var daysVisited = 0
        var timeReadSeconds = 0
        var recentTimeReadSeconds = 0
        var topicsEntered = 0
        var postsReadCount = 0
        var likesReceived = 0
        var likesGiven = 0
        var topicCount = 0
        var postCount = 0
        val topBadges = mutableListOf<GrantedBadge>()

        // 2 & 3. 并发拉取 /u/{username}.json 与 /u/{username}/summary.json，将个人中心同步耗时缩短 50%+
        val (userRes, summaryRes) = coroutineScope {
            val dUser = async { executeHttp("https://linux.do/u/$uname.json", method = "GET") }
            val dSummary = async { executeHttp("https://linux.do/u/$uname/summary.json", method = "GET") }
            dUser.await() to dSummary.await()
        }

        if (userRes != null && userRes.second.trimStart().startsWith("{")) {
            try {
                val root = json.parseToJsonElement(userRes.second).jsonObject
                val uObj = root["user"] as? JsonObject
                if (uObj != null) {
                    userId = uObj["id"]?.jsonPrimitive?.intOrNull ?: userId
                    displayName = uObj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: displayName.ifBlank { uname }
                    val rawTpl = uObj["avatar_template"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (rawTpl.isNotBlank()) {
                        avatarUrl = LinuxDoSiteRules.resolveUrl(rawTpl)
                    }
                    animatedAvatar = LinuxDoSiteRules.resolveAnimatedAvatarUrl(
                        uObj["animated_avatar"]?.jsonPrimitive?.contentOrNull) ?: animatedAvatar
                    trustLevel = uObj["trust_level"]?.jsonPrimitive?.intOrNull ?: trustLevel
                    title = uObj["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: title
                    bioExcerpt = uObj["bio_excerpt"]?.jsonPrimitive?.contentOrNull
                        ?.replace(Regex("<[^>]+>"), "")?.trim()
                        ?: uObj["bio_raw"]?.jsonPrimitive?.contentOrNull?.trim()
                        ?: ""
                    primaryGroupName = uObj["primary_group_name"]?.jsonPrimitive?.contentOrNull
                    admin = uObj["admin"]?.jsonPrimitive?.booleanOrNull ?: false
                    moderator = uObj["moderator"]?.jsonPrimitive?.booleanOrNull ?: false
                    badgeCount = uObj["badge_count"]?.jsonPrimitive?.intOrNull ?: 0
                    val createdIso = uObj["created_at"]?.jsonPrimitive?.contentOrNull
                    if (!createdIso.isNullOrBlank() && createdIso.length >= 10) {
                        createdAtText = createdIso.substring(0, 10)
                    }
                }
            } catch (_: Throwable) {
            }
        }

        if (summaryRes != null && summaryRes.second.trimStart().startsWith("{")) {
            try {
                val root = json.parseToJsonElement(summaryRes.second).jsonObject
                val us = root["user_summary"] as? JsonObject
                if (us != null) {
                    daysVisited = us["days_visited"]?.jsonPrimitive?.intOrNull ?: 0
                    timeReadSeconds = us["time_read"]?.jsonPrimitive?.intOrNull ?: 0
                    recentTimeReadSeconds = us["recent_time_read"]?.jsonPrimitive?.intOrNull ?: 0
                    topicsEntered = us["topics_entered"]?.jsonPrimitive?.intOrNull ?: 0
                    postsReadCount = us["posts_read_count"]?.jsonPrimitive?.intOrNull ?: 0
                    likesReceived = us["likes_received"]?.jsonPrimitive?.intOrNull ?: 0
                    likesGiven = us["likes_given"]?.jsonPrimitive?.intOrNull ?: 0
                    topicCount = us["topic_count"]?.jsonPrimitive?.intOrNull ?: 0
                    postCount = us["post_count"]?.jsonPrimitive?.intOrNull ?: 0
                }
                (root["badges"] as? JsonArray)?.forEach { bElem ->
                    val bObj = bElem as? JsonObject ?: return@forEach
                    val bid = bObj["id"]?.jsonPrimitive?.intOrNull ?: return@forEach
                    val bname = bObj["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    topBadges.add(
                        GrantedBadge(
                            id = bid,
                            name = bname,
                            description = bObj["description"]?.jsonPrimitive?.contentOrNull?.replace(Regex("<[^>]+>"), "") ?: "",
                            badgeTypeId = bObj["badge_type_id"]?.jsonPrimitive?.intOrNull ?: 3,
                            icon = bObj["icon"]?.jsonPrimitive?.contentOrNull,
                            imageUrl = bObj["image_url"]?.jsonPrimitive?.contentOrNull,
                            slug = bObj["slug"]?.jsonPrimitive?.contentOrNull ?: ""
                        )
                    )
                }
            } catch (_: Throwable) {
            }
        }

        // 同步更新全局登录用户状态（修正 Lv.3 等真实等级与真实头像）
        UserApiKeyAuthManager.recordWebSessionLogin(
            username = uname,
            displayName = displayName.ifBlank { uname },
            avatarUrl = avatarUrl,
            trustLevel = trustLevel,
            userId = userId,
            animatedAvatar = animatedAvatar,
            userTitle = title
        )

        val fullProfile = UserFullProfileSummary(
            id = userId,
            username = uname,
            displayName = displayName.ifBlank { uname },
            avatarTemplate = avatarUrl,
            animatedAvatar = animatedAvatar,
            trustLevel = trustLevel,
            title = title,
            bioExcerpt = bioExcerpt,
            primaryGroupName = primaryGroupName,
            admin = admin,
            moderator = moderator,
            createdAtText = createdAtText,
            daysVisited = daysVisited,
            timeReadSeconds = timeReadSeconds,
            recentTimeReadSeconds = recentTimeReadSeconds,
            topicsEntered = topicsEntered,
            postsReadCount = postsReadCount,
            likesReceived = likesReceived,
            likesGiven = likesGiven,
            topicCount = topicCount,
            postCount = postCount,
            badgeCount = badgeCount.coerceAtLeast(topBadges.size),
            unreadNotifications = unreadNotifs,
            badges = topBadges
        )
        UserApiKeyAuthManager.fullProfileCache = fullProfile
        fullProfile
    }

    /**
     * 发表话题回复或针对指定楼层发表评论 (`POST /posts.json`)
     */
    suspend fun createPost(
        topicId: Int,
        raw: String,
        replyToPostNumber: Int? = null
    ): PostItemModel? = withContext(Dispatchers.IO) {
        val params = buildMap {
            put("topic_id", topicId.toString())
            put("raw", raw.trim())
            if (replyToPostNumber != null && replyToPostNumber > 0) {
                put("reply_to_post_number", replyToPostNumber.toString())
            }
        }
        val res = executeHttp(
            url = "https://linux.do/posts.json",
            method = "POST",
            formParams = params
        ) ?: return@withContext null

        if (res.first in 200..299 && res.second.trimStart().startsWith("{")) {
            return@withContext try {
                val pObj = json.parseToJsonElement(res.second).jsonObject
                parseSinglePostObject(pObj, 0)
            } catch (_: Throwable) {
                null
            }
        }
        null
    }

    /**
     * 编辑自己发布的帖子/评论楼层 (`PUT /posts/{id}.json`)
     */
    suspend fun updatePost(
        postId: Int,
        newRaw: String,
        editReason: String = ""
    ): PostItemModel? = withContext(Dispatchers.IO) {
        val params = buildMap {
            put("post[raw]", newRaw.trim())
            if (editReason.isNotBlank()) {
                put("post[edit_reason]", editReason.trim())
            }
        }
        val res = executeHttp(
            url = "https://linux.do/posts/$postId.json",
            method = "PUT",
            formParams = params
        ) ?: return@withContext null

        if (res.first in 200..299 && res.second.trimStart().startsWith("{")) {
            return@withContext try {
                val root = json.parseToJsonElement(res.second).jsonObject
                val pObj = (root["post"] as? JsonObject) ?: root
                parseSinglePostObject(pObj, 0)
            } catch (_: Throwable) {
                null
            }
        }
        null
    }

    /**
     * 删除自己发布的评论楼层 (`DELETE /posts/{id}.json`)
     */
    suspend fun deletePost(postId: Int): Boolean = withContext(Dispatchers.IO) {
        val res = executeHttp(
            url = "https://linux.do/posts/$postId.json",
            method = "DELETE"
        )
        res != null && res.first in 200..299
    }

    /**
     * 拉取用户最近已读话题历史 (`/read.json`)
     */
    suspend fun fetchReadHistoryTopics(page: Int = 0): List<TopicSummary> = withContext(Dispatchers.IO) {
        fetchTopics(path = "read.json", page = page)
    }

    /**
     * 点赞 / 取消点赞指定楼层（优先调用 Discourse Reactions 插件接口，兼容标准 `post_actions`）
     */
    suspend fun togglePostLike(postId: Int): Boolean = withContext(Dispatchers.IO) {
        val reactionUrl = "https://linux.do/discourse-reactions/posts/$postId/custom-reactions/heart/toggle.json"
        val res = executeHttp(url = reactionUrl, method = "PUT", formParams = emptyMap())
        if (res != null && res.first in 200..299) {
            return@withContext true
        }
        val fallbackRes = executeHttp(
            url = "https://linux.do/post_actions.json",
            method = "POST",
            formParams = mapOf(
                "id" to postId.toString(),
                "post_action_type_id" to "2"
            )
        )
        fallbackRes != null && fallbackRes.first in 200..299
    }

    /**
     * 收藏指定帖子/话题到个人书签 (`POST /bookmarks.json`)
     */
    suspend fun toggleTopicBookmark(postId: Int, name: String = ""): Boolean = withContext(Dispatchers.IO) {
        val res = executeHttp(
            url = "https://linux.do/bookmarks.json",
            method = "POST",
            formParams = mapOf(
                "bookmarkable_id" to postId.toString(),
                "bookmarkable_type" to "Post",
                "name" to name
            )
        )
        res != null && res.first in 200..299
    }

    /**
     * 拉取当前登录用户的通知列表 (`/notifications.json?recent=true&limit=30`)
     */
    suspend fun fetchUserNotifications(): List<UserNotificationItem> = withContext(Dispatchers.IO) {
        val res = executeHttp("https://linux.do/notifications.json?recent=true&limit=30", method = "GET", preserveFailure = true)
            ?: throw ApiReadException(ApiFailureKind.Connection)
        try {
            val root = readApiObject(res)
            val arr = root["notifications"] as? JsonArray ?: throw ApiReadException(ApiFailureKind.InvalidPayload)
            arr.mapNotNull { elem ->
                val obj = elem as? JsonObject ?: return@mapNotNull null
                val id = obj["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                val nType = obj["notification_type"]?.jsonPrimitive?.intOrNull ?: 0
                val read = obj["read"]?.jsonPrimitive?.booleanOrNull ?: true
                val createdAt = formatRelativeTime(obj["created_at"]?.jsonPrimitive?.contentOrNull)
                val topicId = obj["topic_id"]?.jsonPrimitive?.intOrNull ?: 0
                val postNumber = obj["post_number"]?.jsonPrimitive?.intOrNull ?: 1
                val slug = obj["slug"]?.jsonPrimitive?.contentOrNull ?: ""
                val dataObj = obj["data"] as? JsonObject
                val title = dataObj?.get("topic_title")?.jsonPrimitive?.contentOrNull
                    ?: dataObj?.get("badge_name")?.jsonPrimitive?.contentOrNull
                    ?: obj["fancy_title"]?.jsonPrimitive?.contentOrNull
                    ?: "社区互动通知"
                val actor = dataObj?.get("display_username")?.jsonPrimitive?.contentOrNull
                    ?: dataObj?.get("original_username")?.jsonPrimitive?.contentOrNull
                    ?: obj["acting_user_name"]?.jsonPrimitive?.contentOrNull
                    ?: "系统"
                val actingAvatarTemplate = obj["acting_user_avatar_template"]?.jsonPrimitive?.contentOrNull
                    ?: dataObj?.get("acting_user_avatar_template")?.jsonPrimitive?.contentOrNull
                val excerpt = dataObj?.get("message")?.jsonPrimitive?.contentOrNull ?: ""
                UserNotificationItem(
                    id = id,
                    notificationType = nType,
                    read = read,
                    createdAtText = createdAt,
                    topicId = topicId,
                    postNumber = postNumber,
                    title = title,
                    actingUsername = actor,
                    actingAvatarTemplate = actingAvatarTemplate,
                    excerpt = excerpt
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ApiReadException) {
            throw error
        } catch (error: Exception) {
            throw ApiReadException(ApiFailureKind.InvalidPayload, error)
        }
    }

    /**
     * 拉取用户个人的历史发帖/回复/点赞动态 (`/user_actions.json?username={username}&Offset=0`)
     */
    suspend fun fetchUserActivities(username: String, filter: String? = null): List<UserActivityItem> = withContext(Dispatchers.IO) {
        if (username.isBlank() || username == "linuxdo_user") throw ApiReadException(ApiFailureKind.Authentication)
        val filterParam = if (!filter.isNullOrBlank()) "&filter=$filter" else ""
        val url = "https://linux.do/user_actions.json?offset=0&username=$username$filterParam"
        val res = executeHttp(url, method = "GET", preserveFailure = true) ?: throw ApiReadException(ApiFailureKind.Connection)
        try {
            val root = readApiObject(res)
            val arr = root["user_actions"] as? JsonArray ?: throw ApiReadException(ApiFailureKind.InvalidPayload)
            arr.mapNotNull { elem ->
                val obj = elem as? JsonObject ?: return@mapNotNull null
                val topicId = obj["topic_id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                val postId = obj["post_id"]?.jsonPrimitive?.intOrNull ?: (topicId * 10)
                val postNumber = obj["post_number"]?.jsonPrimitive?.intOrNull ?: 1
                val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: "话题 #$topicId"
                val excerpt = obj["excerpt"]?.jsonPrimitive?.contentOrNull
                    ?.replace(Regex("<[^>]+>"), "")?.trim() ?: ""
                val actionType = obj["action_type"]?.jsonPrimitive?.intOrNull ?: 5
                val createdAt = formatRelativeTime(obj["created_at"]?.jsonPrimitive?.contentOrNull)
                val catId = obj["category_id"]?.jsonPrimitive?.intOrNull ?: 4
                val (catName, _) = resolveCategoryMeta(catId)
                UserActivityItem(
                    id = postId,
                    topicId = topicId,
                    postNumber = postNumber,
                    title = title,
                    excerpt = excerpt,
                    createdAtText = createdAt,
                    categoryName = catName,
                    actionType = actionType
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ApiReadException) {
            throw error
        } catch (error: Exception) {
            throw ApiReadException(ApiFailureKind.InvalidPayload, error)
        }
    }

    /**
     * 拉取用户个人的书签列表 (`/u/{username}/bookmarks.json`)
     */
    suspend fun fetchUserBookmarks(username: String): List<UserActivityItem> = withContext(Dispatchers.IO) {
        if (username.isBlank() || username == "linuxdo_user") throw ApiReadException(ApiFailureKind.Authentication)
        val res = executeHttp("https://linux.do/u/$username/bookmarks.json", method = "GET", preserveFailure = true)
            ?: throw ApiReadException(ApiFailureKind.Connection)
        try {
            val root = readApiObject(res)
            val listObj = root["user_bookmark_list"] as? JsonObject ?: root
            val arr = listObj["bookmarks"] as? JsonArray ?: throw ApiReadException(ApiFailureKind.InvalidPayload)
            arr.mapNotNull { elem ->
                val obj = elem as? JsonObject ?: return@mapNotNull null
                val id = obj["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                val topicId = obj["topic_id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                val postNumber = obj["linked_post_number"]?.jsonPrimitive?.intOrNull
                    ?: obj["post_number"]?.jsonPrimitive?.intOrNull ?: 1
                val title = obj["title"]?.jsonPrimitive?.contentOrNull
                    ?: obj["fancy_title"]?.jsonPrimitive?.contentOrNull
                    ?: "收藏话题 #$topicId"
                val excerpt = obj["excerpt"]?.jsonPrimitive?.contentOrNull
                    ?.replace(Regex("<[^>]+>"), "")?.trim() ?: ""
                val createdAt = formatRelativeTime(obj["created_at"]?.jsonPrimitive?.contentOrNull)
                val catId = obj["category_id"]?.jsonPrimitive?.intOrNull ?: 4
                val (catName, _) = resolveCategoryMeta(catId)
                UserActivityItem(
                    id = id,
                    topicId = topicId,
                    postNumber = postNumber,
                    title = title,
                    excerpt = excerpt,
                    createdAtText = createdAt,
                    categoryName = catName,
                    actionType = 88
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ApiReadException) {
            throw error
        } catch (error: Exception) {
            throw ApiReadException(ApiFailureKind.InvalidPayload, error)
        }
    }

    @Volatile
    private var cachedCategoryTemplates: Map<Int, String>? = null

    /**
     * 从 Discourse `/site.json` 拉取各版块真实的 `topic_template`（服务端配置的发帖模板）
     */
    suspend fun fetchCategoryTopicTemplate(categoryId: Int): String? = withContext(Dispatchers.IO) {
        val existing = cachedCategoryTemplates
        if (existing != null) {
            return@withContext existing[categoryId]?.takeIf { it.isNotBlank() }
        }
        val res = executeHttp("https://linux.do/site.json", method = "GET")
        if (res != null && res.first in 200..299 && res.second.trimStart().startsWith("{")) {
            try {
                val root = json.parseToJsonElement(res.second).jsonObject
                val map = mutableMapOf<Int, String>()
                (root["categories"] as? JsonArray)?.forEach { cElem ->
                    val cObj = cElem as? JsonObject ?: return@forEach
                    val id = cObj["id"]?.jsonPrimitive?.intOrNull ?: return@forEach
                    val tpl = cObj["topic_template"]?.jsonPrimitive?.contentOrNull?.trim() ?: ""
                    if (tpl.isNotEmpty()) {
                        map[id] = tpl
                    }
                }
                cachedCategoryTemplates = map
                return@withContext map[categoryId]?.takeIf { it.isNotBlank() }
            } catch (_: Throwable) {
            }
        }
        null
    }

    /**
     * 调用 Discourse 官方发帖标签过滤接口 (`/tags/filter/search.json?filterForInput=true&categoryId=...`)
     * 获取当前版块允许使用的真实社区标签（Linux.do 普通用户不允许创建不存在的自定义标签）
     */
    suspend fun fetchCategoryAllowedTags(
        categoryId: Int,
        query: String = ""
    ): List<String> = withContext(Dispatchers.IO) {
        val encodedQ = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val url = "https://linux.do/tags/filter/search.json?q=$encodedQ&filterForInput=true&categoryId=$categoryId&limit=12"
        val res = executeHttp(url, method = "GET")
        if (res != null && res.first in 200..299 && res.second.trimStart().startsWith("{")) {
            try {
                val root = json.parseToJsonElement(res.second).jsonObject
                val list = (root["results"] as? JsonArray)?.mapNotNull { item ->
                    val obj = item as? JsonObject ?: return@mapNotNull null
                    obj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                        ?: obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                        ?: obj["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                }.orEmpty()
                if (list.isNotEmpty()) {
                    return@withContext list
                }
            } catch (_: Throwable) {
            }
        }
        listOf("人工智能", "快问快答", "纯水", "病友", "抽奖")
    }

    /**
     * 发布全新社区话题 (`POST /posts.json`)，返回新话题的 `topic_id`
     */
    suspend fun createTopic(
        title: String,
        raw: String,
        categoryId: Int,
        tags: List<String> = emptyList()
    ): Int? = withContext(Dispatchers.IO) {
        val params = buildMap {
            put("title", title.trim())
            put("raw", raw.trim())
            put("category", categoryId.toString())
            tags.filter { it.isNotBlank() }.forEachIndexed { idx, tag ->
                put("tags[$idx]", tag.trim())
            }
        }
        val res = executeHttp(
            url = "https://linux.do/posts.json",
            method = "POST",
            formParams = params
        ) ?: return@withContext null
        if (res.first in 200..299 && res.second.trimStart().startsWith("{")) {
            return@withContext try {
                val obj = json.parseToJsonElement(res.second).jsonObject
                obj["topic_id"]?.jsonPrimitive?.intOrNull
            } catch (_: Throwable) {
                null
            }
        }
        null
    }

    /**
     * 全局社区搜索 (`/search.json?q=...`)，支持排序筛选 (`latest`、`likes`、`views`)
     */
    suspend fun searchCommunity(
        query: String,
        order: String? = null
    ): List<CommunitySearchResult> = withContext(Dispatchers.IO) {
        val cleanQ = query.trim()
        if (cleanQ.isBlank()) return@withContext emptyList()
        val fullQuery = if (!order.isNullOrBlank()) "$cleanQ order:$order" else cleanQ
        val encoded = java.net.URLEncoder.encode(fullQuery, "UTF-8")
        val res = executeHttp("https://linux.do/search.json?q=$encoded", method = "GET", preserveFailure = true)
            ?: throw ApiReadException(ApiFailureKind.Connection)
        try {
            val root = readApiObject(res)
            val topicsMap = mutableMapOf<Int, JsonObject>()
            (root["topics"] as? JsonArray)?.forEach { tElem ->
                val tObj = tElem as? JsonObject ?: return@forEach
                val tid = tObj["id"]?.jsonPrimitive?.intOrNull ?: return@forEach
                topicsMap[tid] = tObj
            }
            val postsArr = root.requireArray("posts")
            postsArr.mapNotNull { pElem ->
                val pObj = pElem as? JsonObject ?: return@mapNotNull null
                val tid = pObj["topic_id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                val tObj = topicsMap[tid]
                val title = tObj?.get("title")?.jsonPrimitive?.contentOrNull
                    ?: pObj["topic_title_headline"]?.jsonPrimitive?.contentOrNull?.replace(Regex("<[^>]+>"), "")
                    ?: "话题 #$tid"
                val catId = tObj?.get("category_id")?.jsonPrimitive?.intOrNull ?: 4
                val (catName, catColor) = resolveCategoryMeta(catId)
                val blurb = pObj["blurb"]?.jsonPrimitive?.contentOrNull
                    ?.replace(Regex("<[^>]+>"), "")?.trim() ?: ""
                val rawTpl = pObj["avatar_template"]?.jsonPrimitive?.contentOrNull ?: ""
                val avatarUrl = if (rawTpl.isNotBlank()) {
                    if (rawTpl.startsWith("http")) rawTpl.replace("{size}", "96")
                    else "https://linux.do${rawTpl.replace("{size}", "96")}"
                } else ""
                CommunitySearchResult(
                    topicId = tid,
                    postNumber = pObj["post_number"]?.jsonPrimitive?.intOrNull ?: 1,
                    title = title,
                    blurb = blurb,
                    username = pObj["username"]?.jsonPrimitive?.contentOrNull ?: "linuxdo",
                    avatarTemplate = avatarUrl,
                    createdAtText = formatRelativeTime(pObj["created_at"]?.jsonPrimitive?.contentOrNull),
                    categoryName = catName,
                    categoryColorHex = catColor,
                    likeCount = pObj["like_count"]?.jsonPrimitive?.intOrNull ?: 0,
                    postsCount = tObj?.get("posts_count")?.jsonPrimitive?.intOrNull ?: 1
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ApiReadException) {
            throw error
        } catch (error: Exception) {
            throw ApiReadException(ApiFailureKind.InvalidPayload, error)
        }
    }

    /**
     * 一键将全部未读通知标记为已读 (`PUT /notifications/mark-read.json`)
     */
    suspend fun markNotificationsRead(): Boolean = withContext(Dispatchers.IO) {
        val res = executeHttp(
            url = "https://linux.do/notifications/mark-read.json",
            method = "PUT",
            formParams = emptyMap()
        )
        res != null && res.first in 200..299
    }

    /**
     * 切换话题订阅通知级别 (`POST /t/{id}/notifications.json`)
     * `level`: 3=关注(Watching), 2=跟踪(Tracking), 1=常规(Normal), 0=免扰(Muted)
     */
    suspend fun setTopicNotificationLevel(topicId: Int, level: Int): Boolean = withContext(Dispatchers.IO) {
        val res = executeHttp(
            url = "https://linux.do/t/$topicId/notifications.json",
            method = "POST",
            formParams = mapOf("notification_level" to level.toString())
        )
        res != null && res.first in 200..299
    }

    /**
     * 多表情互动回应 (`heart`, `+1`, `laughing`, `open_mouth`, `clap`, `confetti_ball`, `hugs`)
     */
    suspend fun togglePostReaction(postId: Int, reactionId: String = "heart"): Boolean = withContext(Dispatchers.IO) {
        val url = "https://linux.do/discourse-reactions/posts/$postId/custom-reactions/$reactionId/toggle.json"
        val res = executeHttp(url = url, method = "PUT", formParams = emptyMap())
        if (res != null && res.first in 200..299) return@withContext true
        if (reactionId == "heart") return@withContext togglePostLike(postId)
        false
    }

    /**
     * 拉取用户私信箱 (`/topics/private-messages/{username}.json` 或 `private-messages-sent`)
     */
    suspend fun fetchPrivateMessages(username: String, sent: Boolean = false): List<TopicSummary> = withContext(Dispatchers.IO) {
        if (username.isBlank() || username == "linuxdo_user") throw ApiReadException(ApiFailureKind.Authentication)
        val path = if (sent) {
            "topics/private-messages-sent/$username.json"
        } else {
            "topics/private-messages/$username.json"
        }
        val res = executeHttp("https://linux.do/$path", method = "GET", preserveFailure = true) ?: throw ApiReadException(ApiFailureKind.Connection)
        try {
            val root = readApiObject(res)
            (root["topic_list"] as? JsonObject ?: throw ApiReadException(ApiFailureKind.InvalidPayload)).requireArray("topics")
            parseTopicListRoot(root)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ApiReadException) {
            throw error
        } catch (error: Exception) {
            throw ApiReadException(ApiFailureKind.InvalidPayload, error)
        }
    }

    /**
     * 发送站内私信 (`POST /posts.json` with `archetype=private_message`)
     */
    suspend fun sendPrivateMessage(
        targetUsernames: String,
        title: String,
        raw: String
    ): Int? = withContext(Dispatchers.IO) {
        val params = mapOf(
            "title" to title.trim(),
            "raw" to raw.trim(),
            "archetype" to "private_message",
            "target_recipients" to targetUsernames.trim()
        )
        val res = executeHttp(
            url = "https://linux.do/posts.json",
            method = "POST",
            formParams = params
        ) ?: return@withContext null
        if (res.first in 200..299 && res.second.trimStart().startsWith("{")) {
            return@withContext try {
                val obj = json.parseToJsonElement(res.second).jsonObject
                obj["topic_id"]?.jsonPrimitive?.intOrNull
            } catch (_: Throwable) {
                null
            }
        }
        null
    }

    /**
     * 查看任意社区用户的公开名片与统计 (`/u/{username}.json` + `/u/{username}/summary.json`)
     */
    suspend fun fetchPublicUserCard(username: String): UserFullProfileSummary? = withContext(Dispatchers.IO) {
        val clean = username.trim()
        if (clean.isBlank()) return@withContext null
        var userId = 0
        var displayName = clean
        var avatarUrl = ""
        var animatedAvatar: String? = null
        var trustLevel = 1
        var title = ""
        var bioExcerpt = ""
        var primaryGroupName: String? = null
        var admin = false
        var moderator = false
        var createdAtText = ""
        var badgeCount = 0

        val userRes = executeHttp("https://linux.do/u/$clean.json", method = "GET") ?: return@withContext null
        if (userRes.second.trimStart().startsWith("{")) {
            try {
                val root = json.parseToJsonElement(userRes.second).jsonObject
                val uObj = root["user"] as? JsonObject
                if (uObj != null) {
                    userId = uObj["id"]?.jsonPrimitive?.intOrNull ?: 0
                    displayName = uObj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: clean
                    val rawTpl = uObj["avatar_template"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (rawTpl.isNotBlank()) {
                        avatarUrl = if (rawTpl.startsWith("http")) rawTpl.replace("{size}", "240")
                        else "https://linux.do${rawTpl.replace("{size}", "240")}"
                    }
                    trustLevel = uObj["trust_level"]?.jsonPrimitive?.intOrNull ?: 1
                    title = uObj["title"]?.jsonPrimitive?.contentOrNull ?: ""
                    bioExcerpt = uObj["bio_excerpt"]?.jsonPrimitive?.contentOrNull
                        ?.replace(Regex("<[^>]+>"), "")?.trim() ?: ""
                    primaryGroupName = uObj["primary_group_name"]?.jsonPrimitive?.contentOrNull
                    admin = uObj["admin"]?.jsonPrimitive?.booleanOrNull ?: false
                    moderator = uObj["moderator"]?.jsonPrimitive?.booleanOrNull ?: false
                    badgeCount = uObj["badge_count"]?.jsonPrimitive?.intOrNull ?: 0
                    val createdIso = uObj["created_at"]?.jsonPrimitive?.contentOrNull
                    if (!createdIso.isNullOrBlank() && createdIso.length >= 10) {
                        createdAtText = createdIso.substring(0, 10)
                    }
                }
            } catch (_: Throwable) {
            }
        }

        var daysVisited = 0
        var timeReadSeconds = 0
        var topicsEntered = 0
        var postsReadCount = 0
        var likesReceived = 0
        var likesGiven = 0
        var topicCount = 0
        var postCount = 0
        val topBadges = mutableListOf<GrantedBadge>()

        val summaryRes = executeHttp("https://linux.do/u/$clean/summary.json", method = "GET")
        if (summaryRes != null && summaryRes.second.trimStart().startsWith("{")) {
            try {
                val root = json.parseToJsonElement(summaryRes.second).jsonObject
                val us = root["user_summary"] as? JsonObject
                if (us != null) {
                    daysVisited = us["days_visited"]?.jsonPrimitive?.intOrNull ?: 0
                    timeReadSeconds = us["time_read"]?.jsonPrimitive?.intOrNull ?: 0
                    topicsEntered = us["topics_entered"]?.jsonPrimitive?.intOrNull ?: 0
                    postsReadCount = us["posts_read_count"]?.jsonPrimitive?.intOrNull ?: 0
                    likesReceived = us["likes_received"]?.jsonPrimitive?.intOrNull ?: 0
                    likesGiven = us["likes_given"]?.jsonPrimitive?.intOrNull ?: 0
                    topicCount = us["topic_count"]?.jsonPrimitive?.intOrNull ?: 0
                    postCount = us["post_count"]?.jsonPrimitive?.intOrNull ?: 0
                }
                (root["badges"] as? JsonArray)?.forEach { bElem ->
                    val bObj = bElem as? JsonObject ?: return@forEach
                    val bid = bObj["id"]?.jsonPrimitive?.intOrNull ?: return@forEach
                    val bname = bObj["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    topBadges.add(
                        GrantedBadge(
                            id = bid,
                            name = bname,
                            description = bObj["description"]?.jsonPrimitive?.contentOrNull?.replace(Regex("<[^>]+>"), "") ?: "",
                            badgeTypeId = bObj["badge_type_id"]?.jsonPrimitive?.intOrNull ?: 3,
                            icon = bObj["icon"]?.jsonPrimitive?.contentOrNull,
                            imageUrl = bObj["image_url"]?.jsonPrimitive?.contentOrNull,
                            slug = bObj["slug"]?.jsonPrimitive?.contentOrNull ?: ""
                        )
                    )
                }
            } catch (_: Throwable) {
            }
        }

        UserFullProfileSummary(
            id = userId,
            username = clean,
            displayName = displayName,
            avatarTemplate = avatarUrl,
            animatedAvatar = animatedAvatar,
            trustLevel = trustLevel,
            title = title,
            bioExcerpt = bioExcerpt,
            primaryGroupName = primaryGroupName,
            admin = admin,
            moderator = moderator,
            createdAtText = createdAtText,
            daysVisited = daysVisited,
            timeReadSeconds = timeReadSeconds,
            topicsEntered = topicsEntered,
            postsReadCount = postsReadCount,
            likesReceived = likesReceived,
            likesGiven = likesGiven,
            topicCount = topicCount,
            postCount = postCount,
            badgeCount = badgeCount.coerceAtLeast(topBadges.size),
            badges = topBadges
        )
    }

    /**
     * 修改当前用户的个人简介与昵称 (`PUT /u/{username}.json`)
     */
    suspend fun updateUserProfileInfo(
        username: String,
        displayName: String,
        bioRaw: String,
        title: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (username.isBlank() || username == "linuxdo_user") return@withContext false
        val params = buildMap {
            put("name", displayName.trim())
            put("bio_raw", bioRaw.trim())
            if (title != null) {
                put("title", title.trim())
            }
        }
        val res = executeHttp(
            url = "https://linux.do/u/$username.json",
            method = "PUT",
            formParams = params
        )
        if (res != null && res.first in 200..299) {
            fetchCurrentUserProfile()
            return@withContext true
        }
        false
    }

    private fun parseSinglePostObject(pObj: JsonObject, fallbackIdx: Int): PostItemModel? {
        val pid = pObj["id"]?.jsonPrimitive?.intOrNull ?: return null
        val postNumber = pObj["post_number"]?.jsonPrimitive?.intOrNull ?: (fallbackIdx + 1)
        val userId = pObj["user_id"]?.jsonPrimitive?.intOrNull ?: 0
        val username = pObj["username"]?.jsonPrimitive?.contentOrNull ?: "user_$userId"
        val displayName = pObj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val avatarTemplate = pObj["avatar_template"]?.jsonPrimitive?.contentOrNull ?: ""
        val animatedAvatar = pObj["animated_avatar"]?.jsonPrimitive?.contentOrNull
        val primaryGroupName = pObj["primary_group_name"]?.jsonPrimitive?.contentOrNull
        val userTitle = pObj["user_title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val trustLevel = pObj["trust_level"]?.jsonPrimitive?.intOrNull ?: 1
        val admin = pObj["admin"]?.jsonPrimitive?.booleanOrNull ?: false
        val moderator = pObj["moderator"]?.jsonPrimitive?.booleanOrNull ?: false
        val cooked = pObj["cooked"]?.jsonPrimitive?.contentOrNull ?: "<p></p>"
        val createdAtIso = pObj["created_at"]?.jsonPrimitive?.contentOrNull
        val replyCount = pObj["reply_count"]?.jsonPrimitive?.intOrNull ?: 0
        val replyToPostNumber = pObj["reply_to_post_number"]?.jsonPrimitive?.intOrNull ?: 0
        val replyToUserObj = pObj["reply_to_user"] as? JsonObject
        val replyToUsername = replyToUserObj?.get("username")?.jsonPrimitive?.contentOrNull
        val reads = pObj["reads"]?.jsonPrimitive?.intOrNull ?: 0

        val emojiMap = mapOf(
            "heart" to "❤️",
            "+1" to "👍",
            "thumbsup" to "👍",
            "confetti_ball" to "🎉",
            "tada" to "🎉",
            "fire" to "🔥",
            "laughing" to "😂",
            "joy" to "😂",
            "open_mouth" to "😮",
            "clap" to "👏",
            "hugs" to "🤗",
            "thinking" to "🤔",
            "cry" to "😢",
            "sob" to "😭",
            "rocket" to "🚀",
            "eyes" to "👀",
            "pray" to "🙏"
        )

        val parsedReactions = (pObj["reactions"] as? JsonArray)?.mapNotNull { rElem ->
            val rObj = rElem as? JsonObject ?: return@mapNotNull null
            val rId = rObj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val cnt = rObj["count"]?.jsonPrimitive?.intOrNull ?: 0
            if (cnt <= 0) return@mapNotNull null
            PostReactionItem(
                id = rId,
                emoji = emojiMap[rId] ?: "❤️",
                count = cnt
            )
        }.orEmpty()

        val reactionsCount = if (parsedReactions.isNotEmpty()) {
            parsedReactions.sumOf { it.count }
        } else {
            (pObj["actions_summary"] as? JsonArray)?.firstOrNull { aElem ->
                (aElem as? JsonObject)?.get("id")?.jsonPrimitive?.intOrNull == 2
            }?.let { (it as? JsonObject)?.get("count")?.jsonPrimitive?.intOrNull } ?: 0
        }

        val myReactionObj = pObj["current_user_reaction"] as? JsonObject
        val myReactionId = myReactionObj?.get("id")?.jsonPrimitive?.contentOrNull
            ?: if (
                (pObj["actions_summary"] as? JsonArray)?.any { aElem ->
                    val aObj = aElem as? JsonObject
                    aObj?.get("id")?.jsonPrimitive?.intOrNull == 2 && aObj["acted"]?.jsonPrimitive?.booleanOrNull == true
                } == true
            ) "heart" else null

        val likedByMe = !myReactionId.isNullOrBlank()

        val bookmarkedByMe = pObj["bookmarked"]?.jsonPrimitive?.booleanOrNull ?: false
        val inlineBadges = parseInlinePostBadges(pObj["user_badges"])

        return PostItemModel(
            id = pid,
            postNumber = postNumber,
            userId = userId,
            username = username,
            displayName = displayName,
            avatarTemplate = avatarTemplate,
            animatedAvatar = animatedAvatar,
            primaryGroupName = primaryGroupName,
            userTitle = userTitle,
            trustLevel = trustLevel,
            admin = admin,
            moderator = moderator,
            isTopicOwner = postNumber == 1 || pObj["topic_owner"]?.jsonPrimitive?.booleanOrNull == true,
            isTodayCakeday = pObj["user_cakedate"] != null && postNumber == 1,
            flair = FlairInfo(
                url = pObj["flair_url"]?.jsonPrimitive?.contentOrNull,
                name = pObj["flair_name"]?.jsonPrimitive?.contentOrNull,
                bgColorHex = pObj["flair_bg_color"]?.jsonPrimitive?.contentOrNull,
                fgColorHex = pObj["flair_color"]?.jsonPrimitive?.contentOrNull,
                groupId = pObj["flair_group_id"]?.jsonPrimitive?.intOrNull
            ),
            badgesGranted = inlineBadges,
            createdAtText = formatRelativeTime(createdAtIso),
            cookedHtml = cooked,
            replyCount = replyCount,
            replyToPostNumber = replyToPostNumber,
            replyToUsername = replyToUsername,
            likeCount = reactionsCount,
            readsCount = reads,
            likedByMe = likedByMe,
            myReactionId = myReactionId,
            reactions = parsedReactions,
            bookmarkedByMe = bookmarkedByMe
        )
    }

    private fun parseLiveTopicDetail(root: JsonObject, summaryFallback: TopicSummary?): TopicDetailModel {
        val id = root["id"]?.jsonPrimitive?.intOrNull ?: summaryFallback?.id ?: 0
        val title = root["title"]?.jsonPrimitive?.contentOrNull ?: summaryFallback?.title ?: ""
        val categoryId = root["category_id"]?.jsonPrimitive?.intOrNull ?: summaryFallback?.categoryId ?: 4
        val (catName, catColor) = resolveCategoryMeta(categoryId)
        val tags = parseTagsArray(root["tags"] as? JsonArray).ifEmpty { summaryFallback?.tags.orEmpty() }
        val views = root["views"]?.jsonPrimitive?.intOrNull ?: summaryFallback?.views ?: 0
        val likeCount = root["like_count"]?.jsonPrimitive?.intOrNull ?: summaryFallback?.likeCount ?: 0
        val postsCount = root["posts_count"]?.jsonPrimitive?.intOrNull ?: summaryFallback?.postsCount ?: 1
        val bookmarked = root["bookmarked"]?.jsonPrimitive?.booleanOrNull ?: false

        val postStream = root["post_stream"] as? JsonObject
        val postsArr = postStream?.get("posts") as? JsonArray ?: JsonArray(emptyList())
        val streamIds = (postStream?.get("stream") as? JsonArray)?.mapNotNull {
            it.jsonPrimitive.intOrNull
        }.orEmpty()

        val rawPosts = postsArr.mapIndexedNotNull { idx, elem ->
            parseSinglePostObject(elem as? JsonObject ?: return@mapIndexedNotNull null, idx)
        }

        val enrichedPosts = BadgeInjector.injectTopLevelUserBadges(rawPosts, root)
        return TopicDetailModel(
            id = id,
            title = title,
            categoryName = catName,
            categoryColorHex = catColor,
            tags = tags,
            views = views,
            likeCount = likeCount,
            postsCount = postsCount,
            posts = enrichedPosts,
            streamIds = streamIds,
            bookmarked = bookmarked
        )
    }

    private fun parseInlinePostBadges(element: JsonElement?): List<GrantedBadge> {
        val arr = element as? JsonArray ?: return emptyList()
        return arr.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            GrantedBadge(
                id = id,
                name = name,
                description = obj["description"]?.jsonPrimitive?.contentOrNull ?: "",
                badgeTypeId = obj["badge_type_id"]?.jsonPrimitive?.intOrNull ?: 3,
                icon = obj["icon"]?.jsonPrimitive?.contentOrNull,
                imageUrl = obj["image_url"]?.jsonPrimitive?.contentOrNull ?: obj["image"]?.jsonPrimitive?.contentOrNull,
                slug = obj["slug"]?.jsonPrimitive?.contentOrNull ?: ""
            )
        }
    }

    private fun parseTopicListRoot(root: JsonObject): List<TopicSummary> {
        val usersMap = mutableMapOf<Int, TopicUser>()
        (root["users"] as? JsonArray)?.forEach { elem ->
            val uObj = elem as? JsonObject ?: return@forEach
            val id = uObj["id"]?.jsonPrimitive?.intOrNull ?: return@forEach
            usersMap[id] = TopicUser(
                id = id,
                username = uObj["username"]?.jsonPrimitive?.contentOrNull ?: "user_$id",
                name = uObj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                avatarTemplate = uObj["avatar_template"]?.jsonPrimitive?.contentOrNull ?: "",
                animatedAvatar = uObj["animated_avatar"]?.jsonPrimitive?.contentOrNull,
                primaryGroupName = uObj["primary_group_name"]?.jsonPrimitive?.contentOrNull,
                flairName = uObj["flair_name"]?.jsonPrimitive?.contentOrNull,
                flairUrl = uObj["flair_url"]?.jsonPrimitive?.contentOrNull,
                flairBgColor = uObj["flair_bg_color"]?.jsonPrimitive?.contentOrNull,
                flairColor = uObj["flair_color"]?.jsonPrimitive?.contentOrNull,
                trustLevel = uObj["trust_level"]?.jsonPrimitive?.intOrNull ?: 1,
                admin = uObj["admin"]?.jsonPrimitive?.booleanOrNull ?: false,
                moderator = uObj["moderator"]?.jsonPrimitive?.booleanOrNull ?: false
            )
        }

        val topicsArray = (root["topic_list"] as? JsonObject)?.get("topics") as? JsonArray ?: return emptyList()
        return topicsArray.mapNotNull { elem ->
            val tObj = elem as? JsonObject ?: return@mapNotNull null
            val id = tObj["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val title = tObj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val posters = (tObj["posters"] as? JsonArray)?.mapNotNull { pElem ->
                val uid = (pElem as? JsonObject)?.get("user_id")?.jsonPrimitive?.intOrNull
                uid?.let { usersMap[it] }
            }.orEmpty()
            val op = posters.firstOrNull() ?: TopicUser(
                id = 0,
                username = tObj["last_poster_username"]?.jsonPrimitive?.contentOrNull ?: "linuxdo"
            )
            val categoryId = tObj["category_id"]?.jsonPrimitive?.intOrNull ?: 4
            val (catName, catColor) = resolveCategoryMeta(categoryId)
            val tags = parseTagsArray(tObj["tags"] as? JsonArray)
            val lastPostedIso = tObj["last_posted_at"]?.jsonPrimitive?.contentOrNull
                ?: tObj["bumped_at"]?.jsonPrimitive?.contentOrNull
                ?: tObj["created_at"]?.jsonPrimitive?.contentOrNull

            TopicSummary(
                id = id,
                title = title,
                postsCount = tObj["posts_count"]?.jsonPrimitive?.intOrNull ?: 1,
                replyCount = tObj["reply_count"]?.jsonPrimitive?.intOrNull ?: 0,
                views = tObj["views"]?.jsonPrimitive?.intOrNull ?: 0,
                likeCount = tObj["like_count"]?.jsonPrimitive?.intOrNull ?: 0,
                categoryId = categoryId,
                categoryName = catName,
                categoryColorHex = catColor,
                tags = tags,
                lastPostedAt = formatRelativeTime(lastPostedIso),
                excerpt = tObj["excerpt"]?.jsonPrimitive?.contentOrNull?.replace(Regex("<[^>]+>"), "")?.trim(),
                pinned = tObj["pinned"]?.jsonPrimitive?.booleanOrNull ?: false,
                unseen = tObj["unseen"]?.jsonPrimitive?.booleanOrNull ?: false,
                unreadPosts = tObj["unread_posts"]?.jsonPrimitive?.intOrNull ?: 0,
                hasAcceptedAnswer = tObj["has_accepted_answer"]?.jsonPrimitive?.booleanOrNull ?: false,
                originalPoster = op,
                participantAvatars = posters
            )
        }
    }

    private fun parseTagsArray(arr: JsonArray?): List<String> {
        if (arr == null) return emptyList()
        return arr.mapNotNull { elem ->
            when (elem) {
                is JsonPrimitive -> elem.contentOrNull
                is JsonObject -> elem["name"]?.jsonPrimitive?.contentOrNull
                else -> null
            }?.takeIf { it.isNotBlank() }
        }
    }

    private fun formatRelativeTime(isoString: String?): String {
        if (isoString.isNullOrBlank()) return "刚刚"
        return try {
            val instant = Instant.parse(isoString)
            val diffSec = Duration.between(instant, Instant.now()).seconds.coerceAtLeast(0L)
            when {
                diffSec < 60 -> "刚刚"
                diffSec < 3600 -> "${diffSec / 60}m"
                diffSec < 86400 -> "${diffSec / 3600}h"
                diffSec < 86400 * 30 -> "${diffSec / 86400}d"
                else -> "${diffSec / (86400 * 30)}mo"
            }
        } catch (_: Throwable) {
            "刚刚"
        }
    }

    /**
     * 上报帖子阅读时长与已读楼层 (`POST /topics/timings`)
     *
     * 对齐 Discourse 官方 `ScreenTrack` 协议，使服务端实时累加：
     * - `posts_read_count`（Connect 已读帖子数）
     * - `time_read` / `recent_time_read`（阅读时长）
     * - 话题阅读进度游标 (`last_read_post_number`)
     */
    suspend fun sendTopicTimings(batch: ConsolidatedTimingBatch): Int? = withContext(Dispatchers.IO) {
        val isLoggedIn = UserApiKeyAuthManager.isAuthorized() || CookieSentinel.resolveWinningCookies().containsKey("_t")
        if (!isLoggedIn) {
            // 未绑定账号时返回 200 交由本地会话统计维护
            return@withContext 200
        }

        val headers = UserPresenceService.buildPresenceHeaders(isBackgroundTimingRequest = true, isLoggedIn = true)
        val params = buildMap {
            put("topic_id", batch.topicId.toString())
            put("topic_time", batch.topicTimeMs.toString())
            batch.timingsMs.forEach { (number, milliseconds) -> put("timings[$number]", milliseconds.toString()) }
        }
        executeHttp("https://linux.do/topics/timings", method = "POST", formParams = params, extraHeaders = headers)?.first
    }

    /**
     * 获取 `connect.linux.do` 信任等级周期活跃度报告：
     * 1. 优先通过同源 CookieManager WebView 通道拉取 `connect.linux.do`（支持自动跟随 `/login` -> `linux.do/oauth2/authorize` 静默换取 Connect 会话）；
     * 2. 次选直连 `https://connect.linux.do/` 解析 `.tl3-ring` / `.tl3-bar-item` / `<table>` 100 天滚动周期数据；
     * 3. 当用户为 Lv.1/Lv.2（Connect 页仅展示基础欢迎信息）或尚未完成 Connect OAuth 时，基于 `/u/{username}.json` + `/u/{username}/summary.json` 真实账户数据生成精准进度报告，绝不展示假数据。
     */
    suspend fun fetchConnectTrustLevelReport(): ConnectTrustLevelReport = withContext(Dispatchers.IO) {
        if (!UserApiKeyAuthManager.isAuthorized()) {
            return@withContext ConnectStatsParser.buildSessionBaselineReport(
                sessionTopicsEntered = ScreenTrackEngine.sessionTopicsEntered,
                sessionPostsRead = ScreenTrackEngine.sessionPostsRead
            )
        }
        // 1. 优先尝试平台 WebView Connect 桥接（共享 CookieManager 与 OAuth2 自动登录态）
        try {
            val webHtml = UserApiKeyAuthManager.webViewConnectHtmlFetcher?.invoke()
            if (!webHtml.isNullOrBlank()) {
                ConnectStatsParser.parseConnectHtml(webHtml)?.let { return@withContext it }
            }
        } catch (_: Throwable) {
        }

        // 2. 尝试 Ktor 直连 connect.linux.do
        val client = sharedClient
        try {
            val cookieHeader = CookieSentinel.buildCookieHeader(targetHost = "connect.linux.do")
            val response = client.get("https://connect.linux.do/") {
                header("Accept", "text/html,application/xhtml+xml")
                UserApiKeyAuthManager.customUserAgent?.let { header("User-Agent", it) }
                if (cookieHeader.isNotEmpty()) header("Cookie", cookieHeader)
            }
            val html = response.bodyAsText()
            ConnectStatsParser.parseConnectHtml(html)?.let { return@withContext it }
        } catch (_: Throwable) {
        }

        // 3. 拉取当前登录用户的真实个人档案 (`/u/{username}.json` + `/u/{username}/summary.json`)
        val realProfile = try {
            fetchCurrentUserProfile() ?: UserApiKeyAuthManager.fullProfileCache
        } catch (_: Throwable) {
            UserApiKeyAuthManager.fullProfileCache
        }

        if (realProfile != null) {
            return@withContext ConnectStatsParser.buildRealProfileConnectReport(
                username = realProfile.username,
                trustLevel = realProfile.trustLevel,
                daysVisited = realProfile.daysVisited,
                topicsEntered = realProfile.topicsEntered,
                postsReadCount = realProfile.postsReadCount,
                topicCount = realProfile.topicCount,
                postCount = realProfile.postCount,
                likesGiven = realProfile.likesGiven,
                likesReceived = realProfile.likesReceived,
                timeReadSeconds = realProfile.timeReadSeconds
            )
        }

        ConnectStatsParser.buildSessionBaselineReport(
            sessionTopicsEntered = ScreenTrackEngine.sessionTopicsEntered,
            sessionPostsRead = ScreenTrackEngine.sessionPostsRead
        )
    }

    /**
     * 拉取云端草稿箱列表 (`GET /drafts.json`)
     */
    suspend fun fetchCloudDrafts(): List<CloudDraftItem> = withContext(Dispatchers.IO) {
        val res = executeHttp("https://linux.do/drafts.json", method = "GET", preserveFailure = true) ?: throw ApiReadException(ApiFailureKind.Connection)
        try {
            val root = readApiObject(res)
            val arr = root["drafts"] as? JsonArray ?: throw ApiReadException(ApiFailureKind.InvalidPayload)
            arr.mapNotNull { elem ->
                val obj = elem as? JsonObject ?: return@mapNotNull null
                val draftKey = obj["draft_key"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val seq = obj["sequence"]?.jsonPrimitive?.intOrNull ?: 0
                val dataStr = obj["data"]?.jsonPrimitive?.contentOrNull.orEmpty()
                var title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                var rawReply = ""
                if (dataStr.trimStart().startsWith("{")) {
                    runCatching {
                        val dObj = json.parseToJsonElement(dataStr).jsonObject
                        if (title.isBlank()) {
                            title = dObj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        }
                        rawReply = dObj["reply"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    }
                }
                val topicId = obj["topic_id"]?.jsonPrimitive?.intOrNull ?: 0
                val createdAt = formatRelativeTime(obj["created_at"]?.jsonPrimitive?.contentOrNull)
                CloudDraftItem(
                    draftKey = draftKey,
                    sequence = seq,
                    title = title.ifBlank { if (topicId > 0) "回复话题 #$topicId" else "未命名新话题草稿" },
                    excerpt = rawReply.replace(Regex("<[^>]+>"), "").trim(),
                    topicId = topicId,
                    createdAtText = createdAt
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ApiReadException) {
            throw error
        } catch (error: Exception) {
            throw ApiReadException(ApiFailureKind.InvalidPayload, error)
        }
    }

    /**
     * 删除指定云端草稿 (`DELETE /drafts/{draft_key}.json`)
     */
    suspend fun deleteCloudDraft(draftKey: String, sequence: Int): Boolean = withContext(Dispatchers.IO) {
        val encoded = java.net.URLEncoder.encode(draftKey, "UTF-8")
        val res = executeHttp(
            url = "https://linux.do/drafts/$encoded.json?sequence=$sequence",
            method = "DELETE"
        )
        res != null && res.first in 200..299
    }

    /**
     * 关注 / 取消关注指定社区用户 (`PUT /follow/{username}.json` / `DELETE /follow/{username}.json`)
     */
    suspend fun toggleFollowUser(username: String, follow: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        if (username.isBlank()) return@withContext false
        val res = executeHttp(
            url = "https://linux.do/follow/${username.trim()}.json",
            method = if (follow) "PUT" else "DELETE",
            formParams = emptyMap()
        )
        res != null && res.first in 200..299
    }

    /**
     * 帖内原生投票 (`PUT /polls/vote.json`)
     */
    suspend fun voteInPoll(postId: Int, pollName: String, options: List<String>): Boolean = withContext(Dispatchers.IO) {
        val params = buildMap {
            put("post_id", postId.toString())
            put("poll_name", pollName)
            options.forEachIndexed { idx, opt ->
                put("options[$idx]", opt)
            }
        }
        val res = executeHttp(
            url = "https://linux.do/polls/vote.json",
            method = "PUT",
            formParams = params
        )
        res != null && res.first in 200..299
    }

    /**
     * 采纳指定楼层为最佳解答 (`POST /solution/accept.json`)
     */
    suspend fun acceptPostSolution(postId: Int): Boolean = withContext(Dispatchers.IO) {
        val res = executeHttp(
            url = "https://linux.do/solution/accept.json",
            method = "POST",
            formParams = mapOf("id" to postId.toString())
        )
        res != null && res.first in 200..299
    }

    /**
     * 举报违规楼层 (`POST /post_actions.json`)
     * `flagTypeId`: 3=偏离主题(Off-Topic), 4=不当内容(Inappropriate), 8=垃圾广告(Spam)
     */
    suspend fun flagPost(postId: Int, flagTypeId: Int = 4, message: String = ""): Boolean = withContext(Dispatchers.IO) {
        val params = buildMap {
            put("id", postId.toString())
            put("post_action_type_id", flagTypeId.toString())
            if (message.isNotBlank()) {
                put("message", message.trim())
            }
        }
        val res = executeHttp(
            url = "https://linux.do/post_actions.json",
            method = "POST",
            formParams = params
        )
        res != null && res.first in 200..299
    }

    /**
     * 编辑器 `@用户 / @群组` 实时搜索自动补全 (`GET /u/search/users.json?term=...&include_groups=true`)
     * 对齐 FluxDo `mention_autocomplete.dart` 与 `COMPOSE-028/029`
     */
    suspend fun searchMentionCandidates(term: String): List<MentionCandidate> = withContext(Dispatchers.IO) {
        val cleanTerm = term.trim().removePrefix("@")
        val encoded = java.net.URLEncoder.encode(cleanTerm, "UTF-8")
        val url = "https://linux.do/u/search/users.json?term=$encoded&include_groups=true&limit=8"
        try {
            val root = readApiObject(executeHttp(url, method = "GET", preserveFailure = true))
            root.requireArray("users")
            val results = mutableListOf<MentionCandidate>()
            val usersArr = root["users"] as? JsonArray
            usersArr?.forEach { elem ->
                val obj = elem as? JsonObject ?: return@forEach
                val username = obj["username"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val rawTpl = obj["avatar_template"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val avatarUrl = if (rawTpl.startsWith("http")) {
                    rawTpl.replace("{size}", "96")
                } else if (rawTpl.isNotBlank()) {
                    "https://linux.do${rawTpl.replace("{size}", "96")}"
                } else ""
                results.add(
                    MentionCandidate(
                        handle = username,
                        displayName = name.ifBlank { username },
                        avatarUrl = avatarUrl,
                        isGroup = false
                    )
                )
            }
            val groupsArr = root["groups"] as? JsonArray
            groupsArr?.forEach { elem ->
                val obj = elem as? JsonObject ?: return@forEach
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val fullName = obj["full_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                results.add(
                    MentionCandidate(
                        handle = name,
                        displayName = fullName.ifBlank { "群组 @$name" },
                        avatarUrl = "",
                        isGroup = true
                    )
                )
            }
            results
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ApiReadException) {
            throw error
        } catch (error: Exception) {
            throw ApiReadException(ApiFailureKind.InvalidPayload, error)
        }
    }

    /**
     * 编辑器自动保存云端草稿 (`POST /drafts.json`)
     * 对齐 FluxDo 与 Discourse `DRAFT-001~009`：
     * - 新建话题：`draftKey = "new_topic"`
     * - 话题回复：`draftKey = "topic_$topicId"`
     * 返回更新后的 `draft_sequence`
     */
    suspend fun saveCloudDraft(
        draftKey: String,
        sequence: Int,
        replyRaw: String,
        title: String = "",
        categoryId: Int? = null,
        tags: List<String> = emptyList(),
        replyToPostNumber: Int? = null,
        action: String = if (draftKey == "new_topic") "createTopic" else "reply"
    ): Int? = withContext(Dispatchers.IO) {
        if (replyRaw.isBlank() && title.isBlank()) return@withContext null
        val escapedReply = replyRaw
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")
        val escapedTitle = title
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", " ")
        val tagsJson = tags.joinToString(",") { "\"${it.replace("\"", "")}\"" }
        val dataJson = buildString {
            append("{")
            append("\"reply\":\"$escapedReply\",")
            append("\"action\":\"$action\"")
            if (escapedTitle.isNotBlank()) append(",\"title\":\"$escapedTitle\"")
            if (categoryId != null && categoryId > 0) append(",\"categoryId\":$categoryId")
            if (tags.isNotEmpty()) append(",\"tags\":[$tagsJson]")
            if (replyToPostNumber != null && replyToPostNumber > 0) append(",\"postId\":$replyToPostNumber")
            append("}")
        }
        val res = executeHttp(
            url = "https://linux.do/drafts.json",
            method = "POST",
            formParams = mapOf(
                "draft_key" to draftKey,
                "sequence" to sequence.toString(),
                "data" to dataJson
            )
        ) ?: return@withContext null
        if (res.first !in 200..299 || !res.second.trimStart().startsWith("{")) return@withContext null
        try {
            val root = json.parseToJsonElement(res.second).jsonObject
            root["draft_sequence"]?.jsonPrimitive?.intOrNull ?: sequence
        } catch (_: Throwable) {
            sequence
        }
    }

    /**
     * 编辑器原生上传图片与附件 (`POST /uploads.json`)
     * 对齐 FluxDo 与 Discourse `MEDIA-001~014`：
     * - 自动生成标准 Discourse 图片 Markdown `![filename|WxH](short_url)` 或附件 `[filename|attachment](short_url) (size)`
     */
    suspend fun uploadMediaToLinuxDo(
        fileName: String,
        mimeType: String,
        bytes: ByteArray
    ): UploadedMediaResult? = withContext(Dispatchers.IO) {
        if (bytes.isEmpty()) return@withContext null
        val b64 = Base64.getEncoder().encodeToString(bytes)
        val rawBody: String? = PlatformMediaPicker.webViewUploadExecutor?.invoke(fileName, mimeType, b64)?.let { (code, text) ->
            if (code in 200..299 && text.trimStart().startsWith("{")) text else null
        } ?: run {
            try {
                val csrfBody = executeHttp("https://linux.do/session/csrf.json", method = "GET")?.second.orEmpty()
                val csrf = if (csrfBody.trimStart().startsWith("{")) {
                    runCatching { json.parseToJsonElement(csrfBody).jsonObject["csrf"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                } else null
                val cookieHeader = CookieSentinel.buildCookieHeader()
                val resp = sharedClient.submitFormWithBinaryData(
                    url = "https://linux.do/uploads.json",
                    formData = formData {
                        append("type", "composer")
                        append("synchronous", "true")
                        append(
                            "files[]",
                            bytes,
                            Headers.build {
                                append(HttpHeaders.ContentType, mimeType)
                                append(HttpHeaders.ContentDisposition, "filename=\"${fileName.replace("\"", "")}\"")
                            }
                        )
                    }
                ) {
                    header("Accept", "application/json")
                    header("X-Requested-With", "XMLHttpRequest")
                    UserApiKeyAuthManager.customUserAgent?.let { header("User-Agent", it) }
                    UserApiKeyAuthManager.getApiKey()?.let {
                        header("User-Api-Key", it)
                        header("User-Api-Client-Id", UserApiKeyAuthManager.getClientId())
                    }
                    if (!csrf.isNullOrBlank()) header("X-CSRF-Token", csrf)
                    if (cookieHeader.isNotEmpty()) header("Cookie", cookieHeader)
                }
                val text = resp.bodyAsText()
                if (resp.status.value in 200..299 && text.trimStart().startsWith("{")) text else null
            } catch (_: Throwable) {
                null
            }
        }
        if (rawBody.isNullOrBlank()) return@withContext null
        try {
            val obj = json.parseToJsonElement(rawBody).jsonObject
            val shortUrl = obj["short_url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val fullUrl = obj["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val targetUrl = shortUrl.ifBlank { fullUrl }
            if (targetUrl.isBlank()) return@withContext null
            val origName = obj["original_filename"]?.jsonPrimitive?.contentOrNull?.ifBlank { fileName } ?: fileName
            val width = obj["width"]?.jsonPrimitive?.intOrNull ?: 0
            val height = obj["height"]?.jsonPrimitive?.intOrNull ?: 0
            val humanSize = obj["human_filesize"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val isImage = mimeType.startsWith("image/", ignoreCase = true) || (width > 0 && height > 0)
            val markdown = if (isImage) {
                if (width > 0 && height > 0) {
                    "![${origName}|${width}x${height}]($targetUrl)"
                } else {
                    "![${origName}]($targetUrl)"
                }
            } else {
                val sizeSuffix = if (humanSize.isNotBlank()) " ($humanSize)" else ""
                "[${origName}|attachment]($targetUrl)$sizeSuffix"
            }
            UploadedMediaResult(
                originalFilename = origName,
                shortUrl = targetUrl,
                fullUrl = fullUrl.ifBlank { targetUrl },
                width = width,
                height = height,
                humanFilesize = humanSize,
                isImage = isImage,
                markdownSnippet = markdown
            )
        } catch (_: Throwable) {
            null
        }
    }
}

/**
 * 跨平台媒体文件选择器与 WebView Multipart 桥接器
 */
object PlatformMediaPicker {
    var launchPicker: ((mimeType: String, onPicked: (fileName: String, mimeType: String, bytes: ByteArray) -> Unit) -> Unit)? = null
    var webViewUploadExecutor: (suspend (fileName: String, mimeType: String, base64Bytes: String) -> Pair<Int, String>?)? = null
}

data class UploadedMediaResult(
    val originalFilename: String,
    val shortUrl: String,
    val fullUrl: String,
    val width: Int,
    val height: Int,
    val humanFilesize: String,
    val isImage: Boolean,
    val markdownSnippet: String
)

data class MentionCandidate(
    val handle: String,
    val displayName: String,
    val avatarUrl: String,
    val isGroup: Boolean
)

data class CloudDraftItem(
    val draftKey: String,
    val sequence: Int,
    val title: String,
    val excerpt: String,
    val topicId: Int,
    val createdAtText: String
)
