package do_.linux.lindo.core.network

import do_.linux.lindo.core.badge.UniversalBadgeEngine
import do_.linux.lindo.core.connect.ConnectStatsParser
import do_.linux.lindo.core.connect.ConnectTrustLevelReport
import do_.linux.lindo.core.connect.ConsolidatedTimingBatch
import do_.linux.lindo.core.connect.ScreenTrackEngine
import do_.linux.lindo.core.connect.UserPresenceService
import do_.linux.lindo.core.model.BadgeInjector
import do_.linux.lindo.core.model.FlairInfo
import do_.linux.lindo.core.model.GrantedBadge
import do_.linux.lindo.core.model.PostItemModel
import do_.linux.lindo.core.model.TopicDetailModel
import do_.linux.lindo.core.model.TopicSummary
import do_.linux.lindo.core.model.TopicUser
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
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

    fun clear() {
        store.clear()
    }

    fun clearAll() {
        store.clear()
    }
}

/**
 * 已登录的 Linux.do 账号资料快照
 */
data class AuthenticatedUserProfile(
    val username: String,
    val displayName: String,
    val avatarTemplate: String = "",
    val trustLevel: Int = 2
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
    var pendingOtpToken: String? = null

    @Volatile
    var customUserAgent: String? = null

    var onAuthStateChanged: (() -> Unit)? = null
    var openExternalUrlLauncher: ((String) -> Unit)? = null
    var onLogoutPlatformClear: (() -> Unit)? = null
    var onPersistSession: ((AuthenticatedUserProfile?, String?) -> Unit)? = null
    var webViewJsonFetcher: (suspend (String) -> String?)? = null

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

    fun restorePersistedSession(
        profile: AuthenticatedUserProfile?,
        apiKey: String?,
        rawCookies: String?
    ) {
        if (!rawCookies.isNullOrBlank()) {
            CookieSentinel.ingestRawCookieString(rawCookies, domain = "linux.do", hostOnly = false)
        }
        activeUserApiKey = apiKey?.trim()?.takeIf { it.isNotEmpty() }
        currentUser = profile
        onAuthStateChanged?.invoke()
    }

    fun isAuthorized(): Boolean {
        return !activeUserApiKey.isNullOrBlank() ||
            currentUser != null ||
            CookieSentinel.resolveWinningCookies(targetHost = "linux.do").containsKey("_t")
    }

    fun logout() {
        activeUserApiKey = null
        currentUser = null
        pendingOtpToken = null
        CookieSentinel.clearAll()
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

    /**
     * 处理 `discourse://auth_redirect?payload=...` 深链回调，RSA/ECB/PKCS1Padding 解密
     */
    fun handleAuthRedirectUri(uriString: String): Boolean {
        val privKey = pendingPrivateKey ?: return false
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
            val payloadB64 = params["payload"]?.replace(" ", "+") ?: return false
            val cipher = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, privKey)
            val decryptedBytes = cipher.doFinal(Base64.getMimeDecoder().decode(payloadB64))
            val decryptedJson = String(decryptedBytes, Charsets.UTF_8)

            val jsonObj = Json { ignoreUnknownKeys = true }.parseToJsonElement(decryptedJson).jsonObject
            val key = jsonObj["key"]?.jsonPrimitive?.contentOrNull
            val otpInPayload = jsonObj["one_time_password"]?.jsonPrimitive?.contentOrNull

            // 若 query 独立携带加密 otp 参数则一并解密
            val otpParamB64 = params["otp"]?.replace(" ", "+")
            val decryptedOtp = if (!otpParamB64.isNullOrBlank()) {
                try {
                    String(cipher.doFinal(Base64.getMimeDecoder().decode(otpParamB64)), Charsets.UTF_8).trim()
                } catch (_: Throwable) {
                    otpInPayload
                }
            } else {
                otpInPayload
            }

            if (!key.isNullOrBlank()) {
                activeUserApiKey = key
            }
            if (!decryptedOtp.isNullOrBlank()) {
                pendingOtpToken = decryptedOtp
            }
            if (currentUser == null) {
                currentUser = AuthenticatedUserProfile(
                    username = "linuxdo_user",
                    displayName = "Linux.do 认证会员",
                    trustLevel = 2
                )
            }
            onPersistSession?.invoke(currentUser, activeUserApiKey)
            onAuthStateChanged?.invoke()
            true
        } catch (_: Throwable) {
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
object LinuxDoRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    val categories: List<LinuxDoCategoryMeta> = listOf(
        LinuxDoCategoryMeta(4, "开发调优", "develop", "0E76BD", "软件开发、系统调优与开源作品"),
        LinuxDoCategoryMeta(14, "资源荟萃", "resource", "12A89D", "软件、工具、模型与数字资源分享"),
        LinuxDoCategoryMeta(42, "文档共建", "wiki", "25AAE2", "技术沉淀、教程指南与知识库协作"),
        LinuxDoCategoryMeta(11, "搞七捻三", "gossip", "9EB83B", "日常交流、职场故事与生活记录"),
        LinuxDoCategoryMeta(36, "福利羊毛", "welfare", "F7941D", "社区福利、公益接口与优惠资讯"),
        LinuxDoCategoryMeta(34, "前沿快讯", "news", "BF1E2E", "人工智能、科技动态与行业资讯"),
        LinuxDoCategoryMeta(27, "非我莫属", "jobs", "652D90", "人才对接、远程协作与招聘求职"),
        LinuxDoCategoryMeta(10, "跳蚤市场", "market", "F1592A", "闲置数码、算力流转与二手交易"),
        LinuxDoCategoryMeta(32, "读书成诗", "reading", "0088CC", "书评笔记、阅读推荐与人文思考"),
        LinuxDoCategoryMeta(46, "扬帆起航", "startup", "3AB54A", "独立开发、出海实践与产品孵化"),
        LinuxDoCategoryMeta(2, "运营反馈", "feedback", "808281", "社区站务、功能建议与规则说明")
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

    private fun createClient(): HttpClient {
        return HttpClient(OkHttp) {
            engine {
                config {
                    connectTimeout(6, TimeUnit.SECONDS)
                    readTimeout(8, TimeUnit.SECONDS)
                    dns(object : Dns {
                        override fun lookup(hostname: String): List<InetAddress> {
                            val pinned = CloudflareEdgeRacer.pinnedEdgeIp
                            if ((hostname == "linux.do" || hostname.endsWith(".linux.do")) && !pinned.isNullOrBlank()) {
                                return try {
                                    listOf(InetAddress.getByName(pinned))
                                } catch (_: Throwable) {
                                    Dns.SYSTEM.lookup(hostname)
                                }
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
     * 拉取线上话题列表（支持 `latest.json`、`top.json`、`new.json` 以及 `/c/{slug}/{id}.json`）
     * 同时在用户前台活跃时注入 `Discourse-Present: true`，确保服务端更新 `last_seen_at` 与 `user_visits` 连登天数
     */
    suspend fun fetchTopics(
        path: String = "latest.json",
        categoryId: Int? = null
    ): List<TopicSummary> = withContext(Dispatchers.IO) {
        val targetPath = if (categoryId != null) {
            val cat = categories.firstOrNull { it.id == categoryId }
            if (cat != null) "c/${cat.slug}/${cat.id}.json" else "c/$categoryId.json"
        } else {
            path
        }

        val client = createClient()
        try {
            val isLoggedIn = UserApiKeyAuthManager.isAuthorized() || CookieSentinel.resolveWinningCookies().containsKey("_t")
            val presenceHeaders = UserPresenceService.buildPresenceHeaders(
                isBackgroundTimingRequest = false,
                isLoggedIn = isLoggedIn
            )
            val url = "https://linux.do/$targetPath"
            val response = client.get(url) {
                header("Accept", "application/json")
                UserApiKeyAuthManager.customUserAgent?.let { header("User-Agent", it) }
                presenceHeaders.forEach { (k, v) -> header(k, v) }
                UserApiKeyAuthManager.getApiKey()?.let { header("User-Api-Key", it) }
                val cookieHeader = CookieSentinel.buildCookieHeader(targetHost = "linux.do")
                if (cookieHeader.isNotEmpty()) header("Cookie", cookieHeader)
            }
            var text = response.bodyAsText()
            if (!text.trimStart().startsWith("{")) {
                UserApiKeyAuthManager.webViewJsonFetcher?.invoke(url)?.let { fallbackText ->
                    text = fallbackText
                }
            }
            if (text.trimStart().startsWith("{")) {
                UserPresenceService.recordSuccessfulForegroundRequest()
                val root = json.parseToJsonElement(text).jsonObject
                val parsed = parseTopicListRoot(root)
                if (parsed.isNotEmpty()) {
                    return@withContext parsed
                }
            }
        } catch (_: Throwable) {
        } finally {
            client.close()
        }
        emptyList()
    }

    /**
     * 实时拉取指定话题详情 (`/t/{id}.json?track_visit=true`)
     *
     * 对齐 Discourse 官方前端：
     * - 携带 `track_visit=true`、`Discourse-Track-View: 1`、`Discourse-Track-View-Topic-Id: {id}`
     *   以确保后端递增 `UserStat.topics_entered`（即 Connect 中的「浏览话题」指标）！
     */
    suspend fun fetchTopicDetail(
        topicId: Int,
        summaryFallback: TopicSummary? = null,
        trackVisit: Boolean = true
    ): TopicDetailModel = withContext(Dispatchers.IO) {
        val client = createClient()
        try {
            val isLoggedIn = UserApiKeyAuthManager.isAuthorized() || CookieSentinel.resolveWinningCookies().containsKey("_t")
            val presenceHeaders = UserPresenceService.buildPresenceHeaders(
                isBackgroundTimingRequest = false,
                isLoggedIn = isLoggedIn
            )
            val url = if (trackVisit) {
                "https://linux.do/t/$topicId.json?track_visit=true"
            } else {
                "https://linux.do/t/$topicId.json"
            }
            val response = client.get(url) {
                header("Accept", "application/json")
                UserApiKeyAuthManager.customUserAgent?.let { header("User-Agent", it) }
                presenceHeaders.forEach { (k, v) -> header(k, v) }
                if (trackVisit) {
                    header("Discourse-Track-View", "1")
                    header("Discourse-Track-View-Topic-Id", topicId.toString())
                }
                UserApiKeyAuthManager.getApiKey()?.let { header("User-Api-Key", it) }
                val cookieHeader = CookieSentinel.buildCookieHeader(targetHost = "linux.do")
                if (cookieHeader.isNotEmpty()) header("Cookie", cookieHeader)
            }
            var text = response.bodyAsText()
            if (!text.trimStart().startsWith("{")) {
                UserApiKeyAuthManager.webViewJsonFetcher?.invoke(url)?.let { fallbackText ->
                    text = fallbackText
                }
            }
            if (text.trimStart().startsWith("{")) {
                UserPresenceService.recordSuccessfulForegroundRequest()
                val root = json.parseToJsonElement(text).jsonObject
                val detail = parseLiveTopicDetail(root, summaryFallback)
                if (detail.posts.isNotEmpty()) {
                    return@withContext detail
                }
            }
        } catch (_: Throwable) {
        } finally {
            client.close()
        }

        // 若某私密板块话题需登录查看，返回基于话题摘要的干净首楼
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
            posts = if (s != null) {
                listOf(
                    PostItemModel(
                        id = s.id * 10 + 1,
                        postNumber = 1,
                        userId = s.originalPoster.id,
                        username = s.originalPoster.username,
                        displayName = s.originalPoster.name,
                        avatarTemplate = s.originalPoster.avatarTemplate,
                        animatedAvatar = s.originalPoster.animatedAvatar,
                        primaryGroupName = s.originalPoster.primaryGroupName,
                        trustLevel = s.originalPoster.trustLevel,
                        admin = s.originalPoster.admin,
                        moderator = s.originalPoster.moderator,
                        isTopicOwner = true,
                        flair = FlairInfo(
                            url = s.originalPoster.flairUrl,
                            name = s.originalPoster.flairName,
                            bgColorHex = s.originalPoster.flairBgColor,
                            fgColorHex = s.originalPoster.flairColor
                        ),
                        createdAtText = s.lastPostedAt,
                        likeCount = s.likeCount,
                        replyCount = s.replyCount,
                        cookedHtml = "<p>${s.excerpt ?: s.title}</p>"
                    )
                )
            } else {
                emptyList()
            }
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

        val postStream = root["post_stream"] as? JsonObject
        val postsArr = postStream?.get("posts") as? JsonArray ?: JsonArray(emptyList())

        val rawPosts = postsArr.mapIndexedNotNull { idx, elem ->
            val pObj = elem as? JsonObject ?: return@mapIndexedNotNull null
            val pid = pObj["id"]?.jsonPrimitive?.intOrNull ?: return@mapIndexedNotNull null
            val postNumber = pObj["post_number"]?.jsonPrimitive?.intOrNull ?: (idx + 1)
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

            val reactionsCount = (pObj["reactions"] as? JsonArray)?.sumOf { rElem ->
                (rElem as? JsonObject)?.get("count")?.jsonPrimitive?.intOrNull ?: 0
            } ?: (pObj["actions_summary"] as? JsonArray)?.firstOrNull { aElem ->
                (aElem as? JsonObject)?.get("id")?.jsonPrimitive?.intOrNull == 2
            }?.let { (it as? JsonObject)?.get("count")?.jsonPrimitive?.intOrNull } ?: 0

            val inlineBadges = parseInlinePostBadges(pObj["user_badges"])

            PostItemModel(
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
                readsCount = reads
            )
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
            posts = enrichedPosts
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

        val client = createClient()
        try {
            val headers = UserPresenceService.buildPresenceHeaders(
                isBackgroundTimingRequest = true,
                isLoggedIn = true
            )
            val params = Parameters.build {
                append("topic_id", batch.topicId.toString())
                append("topic_time", batch.topicTimeMs.toString())
                batch.timingsMs.forEach { (postNumber, durationMs) ->
                    append("timings[$postNumber]", durationMs.toString())
                }
            }
            val response = client.submitForm(
                url = "https://linux.do/topics/timings",
                formParameters = params
            ) {
                headers.forEach { (k, v) -> header(k, v) }
                UserApiKeyAuthManager.getApiKey()?.let { header("User-Api-Key", it) }
                val cookieHeader = CookieSentinel.buildCookieHeader(targetHost = "linux.do")
                if (cookieHeader.isNotEmpty()) header("Cookie", cookieHeader)
            }
            response.status.value
        } catch (_: Throwable) {
            null
        } finally {
            client.close()
        }
    }

    /**
     * 获取 `connect.linux.do` 信任等级周期活跃度报告
     */
    suspend fun fetchConnectTrustLevelReport(): ConnectTrustLevelReport = withContext(Dispatchers.IO) {
        val client = createClient()
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
        } finally {
            client.close()
        }

        ConnectStatsParser.buildSessionBaselineReport(
            sessionTopicsEntered = ScreenTrackEngine.sessionTopicsEntered,
            sessionPostsRead = ScreenTrackEngine.sessionPostsRead
        )
    }
}
