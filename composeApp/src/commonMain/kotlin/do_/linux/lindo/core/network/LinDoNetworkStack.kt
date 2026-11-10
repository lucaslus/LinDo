package do_.linux.lindo.core.network

import do_.linux.lindo.core.connect.ConnectStatsParser
import do_.linux.lindo.core.connect.ConnectTrustLevelReport
import do_.linux.lindo.core.connect.ConsolidatedTimingBatch
import do_.linux.lindo.core.connect.ScreenTrackEngine
import do_.linux.lindo.core.connect.UserPresenceService
import do_.linux.lindo.core.model.BadgeInjector
import do_.linux.lindo.core.model.BoostInputStats
import do_.linux.lindo.core.model.CommunitySearchResult
import do_.linux.lindo.core.model.FlairInfo
import do_.linux.lindo.core.model.GrantedBadge
import do_.linux.lindo.core.model.PostBoostItem
import do_.linux.lindo.core.model.PostItemModel
import do_.linux.lindo.core.model.PostReactionItem
import do_.linux.lindo.core.model.TopicDetailModel
import do_.linux.lindo.core.model.TopicSummary
import do_.linux.lindo.core.model.TopicUser
import do_.linux.lindo.core.model.UserActivityItem
import do_.linux.lindo.core.model.UserFullProfileSummary
import do_.linux.lindo.core.model.UserNotificationItem
import do_.linux.lindo.core.model.UserStatus
import do_.linux.lindo.core.site.LinuxDoEmojiCatalog
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
import java.security.KeyPairGenerator
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
        val discourseHostOnlyNames = setOf("_t", "_forum_session")
        rawCookieHeader.split(";").forEach { segment ->
            val trimmed = segment.trim()
            val eqIdx = trimmed.indexOf('=')
            if (eqIdx > 0) {
                val name = trimmed.substring(0, eqIdx).trim()
                val value = trimmed.substring(eqIdx + 1).trim()
                if (name.isNotEmpty() && value.isNotEmpty()) {
                    val effectiveHostOnly = hostOnly || name in discourseHostOnlyNames
                    putCookie(
                        StoredCookieRecord(
                            key = CookieQuadrupleKey(
                                name = name,
                                domain = domain.trim().lowercase().trimStart('.'),
                                path = "/",
                                hostOnly = effectiveHostOnly
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
    @Volatile var sessionGeneration: Long = 0L
        private set
    private var activeUserApiKey: String? = null
    private var clientId: String = "lindo-kmp-${UUID.randomUUID()}"
    private var pendingPrivateKey: java.security.PrivateKey? = null
    private var pendingNonce: String? = null
    private var pendingAuthCreatedAt: Long = 0L
    fun pendingAuthorizationCreatedAt(): Long = pendingAuthCreatedAt

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
    var onPersistReadTopics: ((String) -> Unit)? = null
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

    fun restorePendingPrivateKeyBase64(pkcs8B64: String?, nonce: String?, createdAt: Long = System.currentTimeMillis()) {
        if (pkcs8B64.isNullOrBlank() || nonce.isNullOrBlank() || System.currentTimeMillis() - createdAt !in 0..900_000L) return
        pendingAuthCreatedAt = createdAt
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
        sessionGeneration++
        activeUserApiKey = null
        currentUser = null
        fullProfileCache = null
        pendingOtpToken = null
        pendingPrivateKey = null
        pendingNonce = null
        pendingAuthCreatedAt = 0L
        LinuxDoRepository.clearPollVoteState()
        LinuxDoRepository.clearReadTopics()
        LinuxDoRepository.updateCsrfToken(null)
        onPersistPendingKey?.invoke(null, null)
        CookieSentinel.clearAuthCookiesOnly()
        val platformClear = onLogoutPlatformClear
        if (platformClear != null) platformClear.invoke() else onPersistSession?.invoke(null, null)
        onAuthStateChanged?.invoke()
    }

    /**
     * 生成对齐 Linux.do 服务端白名单的浏览器一键授权链接：
     * - `scopes=one_time_password`（Linux.do 现行允许范围）
     * - `auth_redirect=discourse://auth_redirect`（Discourse 官方白名单回跳协议）
     * - `otp=true`（随回调返回一次性登录兑换令牌）
     */
    fun generateAuthorizationUrl(
        applicationName: String = "Lindo",
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
        pendingAuthCreatedAt = System.currentTimeMillis()
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
        val privateKey = pendingPrivateKey ?: return false
        val expectedNonce = pendingNonce ?: return false
        if (System.currentTimeMillis() - pendingAuthCreatedAt !in 0..900_000L) return false
        return runCatching {
            val uri = java.net.URI(uriString)
            if (uri.scheme !in setOf("discourse", "lindo") || uri.rawAuthority != "auth_redirect" ||
                uri.rawPath.orEmpty() !in setOf("", "/") || uri.userInfo != null) return false
            val entries = uri.rawQuery.orEmpty().split('&').filter { it.contains('=') }
                .map { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            if (entries.map { it.first }.distinct().size != entries.size) return false
            val params = entries.toMap()
            val payload = params["payload"]?.let { decryptRsaBase64(it, privateKey) } ?: return false
            val obj = Json.parseToJsonElement(payload).jsonObject
            val nonce = obj["nonce"]?.jsonPrimitive?.contentOrNull ?: return false
            if (!java.security.MessageDigest.isEqual(nonce.toByteArray(), expectedNonce.toByteArray())) return false
            val key = obj["key"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val encryptedOtp = params["oneTimePassword"] ?: params["one_time_password"]
            val otp = if (encryptedOtp != null) {
                decryptRsaBase64(encryptedOtp, privateKey)?.trim()?.takeIf { it.isNotEmpty() } ?: return false
            } else obj["one_time_password"]?.jsonPrimitive?.contentOrNull
            if (key == null && otp.isNullOrBlank()) return false
            // Consume before notifying observers so a duplicate deep link cannot replay the grant.
            pendingPrivateKey = null
            pendingNonce = null
            pendingAuthCreatedAt = 0L
            onPersistPendingKey?.invoke(null, null)
            if (key != null) activeUserApiKey = key
            if (!otp.isNullOrBlank()) pendingOtpToken = otp
            onPersistSession?.invoke(currentUser, activeUserApiKey)
            onAuthStateChanged?.invoke()
            true
        }.getOrDefault(false)
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
    val description: String,
    val icon: String? = null,
    val readRestricted: Boolean = false
)

internal data class ComposerTagOption(
    val name: String,
    val id: Int? = null,
    val count: Int? = null,
    val disabled: Boolean = false,
    val reason: String? = null
)

internal data class ComposerTagChoices(
    val options: List<ComposerTagOption>,
    val forbiddenMessage: String? = null,
    val requiredGroup: String? = null
)

/**
 * Linux.do 核心数据仓库（100% 纯净线上社区内容）
 */
internal fun isSuccessfulApiResponse(status: Int, body: String): Boolean {
    val trimmed = body.trimStart()
    return status in 200..299 && (
        trimmed.isEmpty() ||
            trimmed.startsWith("{") ||
            trimmed.startsWith("[") ||
            trimmed.equals("ok", ignoreCase = true)
    )
}

object LinuxDoRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val locallyReadTopicPosts = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    private val readTopicOrder = java.util.concurrent.ConcurrentLinkedQueue<Int>()

    fun markTopicRead(topicId: Int, postsCount: Int = 1) {
        if (topicId <= 0) return
        val effective = maxOf(locallyReadTopicPosts[topicId] ?: 0, postsCount, 1)
        val isNew = locallyReadTopicPosts.put(topicId, effective) == null
        if (isNew) {
            readTopicOrder.add(topicId)
            while (readTopicOrder.size > 1500) {
                val oldest = readTopicOrder.poll() ?: break
                locallyReadTopicPosts.remove(oldest)
            }
        }
        UserApiKeyAuthManager.onPersistReadTopics?.invoke(exportReadTopics())
    }

    fun isTopicRead(topicId: Int): Boolean = topicId > 0 && locallyReadTopicPosts.containsKey(topicId)

    fun getReadTopicSnapshot(): Map<Int, Int> = HashMap(locallyReadTopicPosts)

    fun restoreReadTopics(serialized: String?) {
        if (serialized.isNullOrBlank()) return
        serialized.split(',').forEach { entry ->
            val parts = entry.split(':')
            val id = parts.getOrNull(0)?.toIntOrNull() ?: return@forEach
            val count = parts.getOrNull(1)?.toIntOrNull() ?: 1
            if (id > 0) {
                if (locallyReadTopicPosts.put(id, count.coerceAtLeast(1)) == null) {
                    readTopicOrder.add(id)
                }
            }
        }
    }

    fun clearReadTopics() {
        locallyReadTopicPosts.clear()
        readTopicOrder.clear()
    }

    private fun exportReadTopics(): String {
        return locallyReadTopicPosts.entries.take(1500).joinToString(",") { "${it.key}:${it.value}" }
    }

    val defaultCategories: List<LinuxDoCategoryMeta> = listOf(
        LinuxDoCategoryMeta(4, "开发调优", "develop", "32C3C3", "软件开发与开源实践", icon = "code"),
        LinuxDoCategoryMeta(14, "资源荟萃", "resource", "12A89D", "软件工具与数字资源", icon = "square-share-nodes"),
        LinuxDoCategoryMeta(42, "文档共建", "wiki", "9CB6C4", "技术沉淀与教程指南", icon = "book"),
        LinuxDoCategoryMeta(11, "搞七捻三", "gossip", "3AB54A", "日常交流与职场故事", icon = "droplet"),
        LinuxDoCategoryMeta(36, "福利羊毛", "welfare", "E45735", "社区福利与公益接口", icon = "piggy-bank"),
        LinuxDoCategoryMeta(34, "前沿快讯", "news", "BB8FCE", "AI 动态与前沿科技", icon = "newspaper"),
        LinuxDoCategoryMeta(27, "非我莫属", "job", "A8C6FE", "人才对接与远程协作", icon = "briefcase"),
        LinuxDoCategoryMeta(10, "跳蚤市场", "market", "ED207B", "闲置数码与算力流转", icon = "store", readRestricted = true),
        LinuxDoCategoryMeta(32, "读书成诗", "reading", "E0D900", "书评笔记与阅读推荐", icon = "book-open-reader"),
        LinuxDoCategoryMeta(46, "扬帆起航", "startup", "F7941D", "独立开发与产品出海", icon = "rocket", readRestricted = true),
        LinuxDoCategoryMeta(2, "运营反馈", "feedback", "808281", "社区站务与功能建议", icon = "comments"),
        LinuxDoCategoryMeta(98, "国产替代", "domestic", "D12C25", "软硬件国产化与信创", icon = "seedling"),
        LinuxDoCategoryMeta(95, "积分乐园", "playground", "E0D900", "社区活动与积分互动", icon = "coins", readRestricted = true),
        LinuxDoCategoryMeta(92, "网络记忆", "feeds", "F7941D", "互联网记忆与归档", icon = "rss"),
        LinuxDoCategoryMeta(102, "社区孵化", "incubation", "F7941D", "社区共创与项目孵化", icon = "lightbulb", readRestricted = true),
        LinuxDoCategoryMeta(110, "虫洞广场", "square", "FF00F7", "跨界视野与奇思妙想", icon = "hurricane"),
        LinuxDoCategoryMeta(108, "深海幽域", "abyss", "12A89D", "深度讨论与高阶专区", icon = "water", readRestricted = true)
    )

    @Volatile
    var categories: List<LinuxDoCategoryMeta> = defaultCategories
        private set

    @Volatile
    var canCreateTag: Boolean = false
        internal set

    private val categoryLookup = java.util.concurrent.ConcurrentHashMap<Int, Pair<String, String>>().apply {
        defaultCategories.forEach { put(it.id, it.name to it.colorHex) }
        put(94, "网盘资源" to "16B176")
        put(49, "公告" to "F1592A")
        put(123, "悬赏" to "C4B512")
        put(30, "活动" to "38571A")
        put(124, "模板" to "04A407")
        put(20, "开发调优, Lv1" to "32C3C3")
        put(21, "资源荟萃, Lv1" to "12A89D")
        put(45, "福利羊毛, Lv1" to "E45735")
        put(52, "前沿快讯, Lv1" to "BB8FCE")
        put(64, "开发调优, Lv2" to "32C3C3")
        put(72, "前沿快讯, Lv2" to "BB8FCE")
        put(88, "资源荟萃, Lv2" to "12A89D")
    }

    private val categoryIconLookup = java.util.concurrent.ConcurrentHashMap<String, String>().apply {
        defaultCategories.forEach { meta ->
            meta.icon?.let { put(meta.name, it) }
        }
        put("网盘资源", "hard-drive")
        put("公告", "bullhorn")
        put("悬赏", "award")
        put("活动", "users")
        put("模板", "clone")
    }

    private val categoryRestrictedLookup = java.util.concurrent.ConcurrentHashMap<String, Boolean>().apply {
        defaultCategories.forEach { meta ->
            put(meta.name, meta.readRestricted)
        }
    }

    fun resolveCategoryMeta(categoryId: Int): Pair<String, String> {
        return categoryLookup[categoryId] ?: ("开发调优" to "32C3C3")
    }

    fun resolveCategoryIconName(categoryName: String): String? {
        val trimmed = categoryName.trim()
        categoryIconLookup[trimmed]?.let { return it }
        val base = trimmed.substringBefore(",").trim()
        return categoryIconLookup[base]
    }

    fun isCategoryRestricted(categoryName: String): Boolean {
        val trimmed = categoryName.trim()
        if (trimmed.contains(", Lv", ignoreCase = true) ||
            Regex("\\bLv[1-4]\\b", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)
        ) {
            return true
        }
        categoryRestrictedLookup[trimmed]?.let { if (it) return true }
        val base = trimmed.substringBefore(",").trim()
        return categoryRestrictedLookup[base] == true ||
            base in setOf("跳蚤市场", "深海幽域", "积分乐园", "扬帆起航", "社区孵化")
    }

    private fun mergeCategoriesFromSiteRoot(root: JsonObject) {
        (root["can_create_tag"] as? JsonPrimitive)?.booleanOrNull?.let {
            canCreateTag = it
        }
        val catArr = root["categories"] as? JsonArray ?: return
        val mergedById = LinkedHashMap<Int, LinuxDoCategoryMeta>()
        defaultCategories.forEach { mergedById[it.id] = it }

        val rawById = HashMap<Int, JsonObject>()
        catArr.forEach { elem ->
            val cObj = elem as? JsonObject ?: return@forEach
            val id = cObj["id"]?.jsonPrimitive?.intOrNull ?: return@forEach
            rawById[id] = cObj
        }

        catArr.forEach { elem ->
            val cObj = elem as? JsonObject ?: return@forEach
            val id = cObj["id"]?.jsonPrimitive?.intOrNull ?: return@forEach
            val name = cObj["name"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
            val slug = cObj["slug"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: "cat-$id"
            val parentId = cObj["parent_category_id"]?.jsonPrimitive?.intOrNull
            val parentObj = parentId?.let { rawById[it] }
            val parentMeta = parentId?.let { mergedById[it] }

            val rawColor = cObj["color"]?.jsonPrimitive?.contentOrNull?.trim()?.removePrefix("#")?.takeIf { it.isNotEmpty() }
            val parentColor = parentObj?.get("color")?.jsonPrimitive?.contentOrNull?.trim()?.removePrefix("#")?.takeIf { it.isNotEmpty() }
                ?: parentMeta?.colorHex
            val color = when {
                rawColor != null && !(parentColor != null && rawColor.equals("0088CC", ignoreCase = true)) -> rawColor
                parentColor != null -> parentColor
                else -> mergedById[id]?.colorHex ?: "32C3C3"
            }
            val icon = cObj["icon"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                ?: parentObj?.get("icon")?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                ?: parentMeta?.icon
                ?: mergedById[id]?.icon
            val readRestricted = cObj["read_restricted"]?.jsonPrimitive?.booleanOrNull
                ?: mergedById[id]?.readRestricted
                ?: false

            val desc = cObj["description_text"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: cObj["description_excerpt"]?.jsonPrimitive?.contentOrNull?.replace(Regex("<[^>]+>"), "")?.trim()
                    ?.takeIf { it.isNotEmpty() }
                ?: mergedById[id]?.description
                ?: name
            val existing = mergedById[id]
            val preferredName = if (existing != null && existing.name.any { it.code > 127 } && name.all { it.code < 128 }) {
                existing.name
            } else {
                name
            }
            val existingLookup = categoryLookup[id]
            val lookupName = if (existingLookup != null && existingLookup.first.any { it.code > 127 } && preferredName.all { it.code < 128 }) {
                existingLookup.first
            } else {
                preferredName
            }
            categoryLookup[id] = lookupName to color
            if (icon != null) {
                categoryIconLookup[lookupName] = icon
            }
            categoryRestrictedLookup[lookupName] = readRestricted
            if (parentId == null || mergedById.containsKey(id)) {
                mergedById[id] = LinuxDoCategoryMeta(
                    id = id,
                    name = preferredName,
                    slug = slug,
                    colorHex = color,
                    description = desc.take(28),
                    icon = icon,
                    readRestricted = readRestricted
                )
            }
        }
        if (mergedById.isNotEmpty()) {
            categories = mergedById.values.toList()
        }
    }

    private val sharedClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            engine {
                config {
                    connectTimeout(6, TimeUnit.SECONDS)
                    readTimeout(8, TimeUnit.SECONDS)
                    retryOnConnectionFailure(false)
                }
            }
        }
    }

    /**
     * Coil 图片客户端：
     * 仅加载公开静态图片资源（头像/表情/上传图片），严禁在已挂载 Chromium WebView 的平台上通过 OkHttp (Java TLS)
     * 携带 `cf_clearance` / `__cf_bm` / `_cfuvid` / `_t` / `_forum_session` 等指纹或会话绑定 Cookie，
     * 防止 Cloudflare Bot Management 检测到同一 `cf_clearance` 在 BoringSSL 与 Java TLS 双栈混用而吊销通行证！
     */
    val imageHttpClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            engine {
                config {
                    connectTimeout(8, TimeUnit.SECONDS)
                    readTimeout(12, TimeUnit.SECONDS)
                    addInterceptor { chain ->
                        val orig = chain.request()
                        val host = orig.url.host.lowercase()
                        val builder = orig.newBuilder()
                        if (UserApiKeyAuthManager.webViewHttpExecutor == null) {
                            UserApiKeyAuthManager.customUserAgent?.takeIf { it.isNotBlank() }?.let {
                                builder.header("User-Agent", it)
                            }
                        }
                        if (host == "linux.do" || host.endsWith(".linux.do")) {
                            builder.header("Referer", "https://linux.do/")
                            builder.header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                        }
                        chain.proceed(builder.build())
                    }
                }
            }
        }
    }

    @Volatile
    private var cachedCsrfToken: String? = null

    fun getCachedCsrfToken(): String? = cachedCsrfToken?.takeIf { it.isNotBlank() }

    fun updateCsrfToken(token: String?) {
        cachedCsrfToken = token?.trim()?.takeIf { it.isNotBlank() }
    }

    private suspend fun fetchCsrfTokenViaSharedClient(forceRefresh: Boolean = false): String? {
        if (!forceRefresh) {
            cachedCsrfToken?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return try {
            val cookieHeader = CookieSentinel.buildCookieHeader(targetHost = "linux.do")
            val csrfResp = sharedClient.get("https://linux.do/session/csrf.json") {
                header("Accept", "application/json")
                header("X-Requested-With", "XMLHttpRequest")
                UserApiKeyAuthManager.customUserAgent?.let { header("User-Agent", it) }
                if (cookieHeader.isNotEmpty()) header("Cookie", cookieHeader)
            }
            csrfResp.headers.getAll("Set-Cookie")?.forEach { setCookie ->
                val pair = setCookie.substringBefore(';')
                CookieSentinel.ingestRawCookieString(pair, domain = "linux.do")
                UserApiKeyAuthManager.onSyncCookieToPlatform?.invoke(setCookie)
            }
            val body = csrfResp.bodyAsText()
            val token = runCatching {
                json.parseToJsonElement(body).jsonObject["csrf"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()?.takeIf { it.isNotBlank() }
            if (token != null) cachedCsrfToken = token
            token
        } catch (_: Throwable) {
            null
        }
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
        val winningCookies = CookieSentinel.resolveWinningCookies()
        val rawT = winningCookies["_t"]?.value.orEmpty()
        val hasWebCookie = rawT.isNotBlank() && rawT != "demo_session_token"
        val isLoggedIn = hasWebCookie
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

        suspend fun callWebView(): Pair<Int, String>? {
            val executor = UserApiKeyAuthManager.webViewHttpExecutor ?: return null
            return try {
                executor(url, method, formParams, mergedHeaders)?.takeIf { it.first != 0 }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
        // Mutations need the browser session's CSRF token. Never retry a write through a second transport.
        if (!method.equals("GET", true) && !method.equals("HEAD", true) && UserApiKeyAuthManager.webViewHttpExecutor != null) {
            val writeResult = callWebView()
            return@withContext writeResult?.takeIf {
                preserveFailure || isSuccessfulApiResponse(it.first, it.second)
            }
        }

        // 只要平台已挂载同源 Chromium WebView 通道（Android），所有请求一律走 WebView BoringSSL 通道（内建无感重试），
        // 严禁回落至缺少 cf_clearance 且为 Java TLS 指纹的 OkHttp sharedClient，彻底杜绝触发 Cloudflare 403 风控！
        val isReadMethod = method.equals("GET", true) || method.equals("HEAD", true)
        val maxReadAttempts = if (isReadMethod) 2 else 1
        var firstBrowserResult: Pair<Int, String>? = null
        if (UserApiKeyAuthManager.webViewHttpExecutor != null) {
            firstBrowserResult = callWebView()
            if (firstBrowserResult != null && isSuccessfulApiResponse(firstBrowserResult.first, firstBrowserResult.second)) {
                if (mergedHeaders["Discourse-Background"] != "true") {
                    UserPresenceService.recordSuccessfulForegroundRequest()
                }
                return@withContext firstBrowserResult
            }
            return@withContext if (preserveFailure) firstBrowserResult else null
        }

        var lastResponse: Pair<Int, String>? = null
        for (attempt in 0 until maxReadAttempts) {
            try {
                val cfTlsBoundCookies = setOf("cf_clearance", "__cf_bm", "_cfuvid", "cf_chl_rc_i", "cf_chl_rc_ni")
                fun currentSafeCookieHeader(): String = CookieSentinel.resolveWinningCookies(targetHost = "linux.do")
                    .entries
                    .filterNot { it.key == "_t" && it.value.value == "demo_session_token" }
                    // 若当前平台存在 WebView，禁止将绑定了 Chromium TLS 指纹的 cf_clearance 放到 OkHttp 请求上发送，防止被 Cloudflare 判定指纹劫持而吊销
                    .filterNot { UserApiKeyAuthManager.webViewHttpExecutor != null && it.key in cfTlsBoundCookies }
                    .joinToString("; ") { "${it.key}=${it.value.value}" }
                val requestHeaders = mergedHeaders.toMutableMap()
                if (!hasWebCookie) {
                    requestHeaders.remove("Discourse-Logged-In")
                }
                UserApiKeyAuthManager.customUserAgent?.takeIf { it.isNotBlank() }?.let {
                    requestHeaders["User-Agent"] = it
                }
                val isMutation = !isReadMethod
                if (isMutation && hasWebCookie && !requestHeaders.containsKey("X-CSRF-Token")) {
                    fetchCsrfTokenViaSharedClient(forceRefresh = false)?.let { requestHeaders["X-CSRF-Token"] = it }
                }
                val cookieHeader = currentSafeCookieHeader()
                if (cookieHeader.isNotEmpty()) requestHeaders["Cookie"] = cookieHeader
                var response = executeApiRequest(sharedClient, url, method, formParams, requestHeaders)
                response.headers.getAll("Set-Cookie")?.forEach { setCookie ->
                    CookieSentinel.ingestRawCookieString(setCookie.substringBefore(';'), domain = "linux.do")
                }
                var status = response.status.value
                var text = response.bodyAsText()
                if (isMutation && status in setOf(403, 422) && text.contains("CSRF", ignoreCase = true) && hasWebCookie) {
                    val freshCsrf = fetchCsrfTokenViaSharedClient(forceRefresh = true)
                    if (!freshCsrf.isNullOrBlank()) {
                        requestHeaders["X-CSRF-Token"] = freshCsrf
                        val updatedCookieHeader = currentSafeCookieHeader()
                        if (updatedCookieHeader.isNotEmpty()) requestHeaders["Cookie"] = updatedCookieHeader
                        response = executeApiRequest(sharedClient, url, method, formParams, requestHeaders)
                        status = response.status.value
                        text = response.bodyAsText()
                    }
                }
                // 若携带 Discourse-Logged-In 头遭遇 403（例如 Cookie 过期被 Discourse check_xhr 拦截），剥离该头自动重试一次
                if (status == 403 && requestHeaders.containsKey("Discourse-Logged-In") && method.equals("GET", true)) {
                    requestHeaders.remove("Discourse-Logged-In")
                    response = executeApiRequest(sharedClient, url, method, formParams, requestHeaders)
                    status = response.status.value
                    text = response.bodyAsText()
                }
                // 避免在已挂载 WebView 的平台上，用 OkHttp 缺少 cf_clearance 触发的 Cloudflare HTML 403 覆盖真实响应
                val trimmedBody = text.trimStart()
                val isHtmlChallenge403 = status == 403 &&
                    UserApiKeyAuthManager.webViewHttpExecutor != null &&
                    !trimmedBody.startsWith("{") && !trimmedBody.startsWith("[")
                if (!isHtmlChallenge403) {
                    lastResponse = status to text
                }
                if (isSuccessfulApiResponse(status, text)) {
                    if (mergedHeaders["Discourse-Background"] != "true") UserPresenceService.recordSuccessfulForegroundRequest()
                    return@withContext status to text
                }
                if (status in listOf(404, 410) || (status == 403 && !isHtmlChallenge403)) {
                    break
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A read can safely fall back to the existing authenticated browser transport.
            }
            if (attempt < maxReadAttempts - 1) {
                kotlinx.coroutines.delay(300L * (attempt + 1))
            }
        }
        val browserResult = firstBrowserResult ?: callWebView()
        if (browserResult != null && isSuccessfulApiResponse(browserResult.first, browserResult.second)) {
            if (mergedHeaders["Discourse-Background"] != "true") UserPresenceService.recordSuccessfulForegroundRequest()
            return@withContext browserResult
        }

        if (preserveFailure) {
            val candidates = listOfNotNull(browserResult, lastResponse)
            candidates.firstOrNull { it.second.trimStart().startsWith("{") || it.second.trimStart().startsWith("[") }
                ?: candidates.firstOrNull()
        } else {
            null
        }
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
        topPeriod: String? = null,
        background: Boolean = false
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
        val headers = if (background) mapOf("Discourse-Background" to "true") else emptyMap()
        val root = readApiObject(executeHttp(url = url, extraHeaders = headers, preserveFailure = true))
        (root["topic_list"] as? JsonObject ?: throw ApiReadException(ApiFailureKind.InvalidPayload)).requireArray("topics")
        val initialList = parseTopicListRoot(root)
        hydrateFirstScreenExcerptsAndPrefetchRest(initialList)
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
        filterUsername: String? = null,
        targetPostNumber: Int? = null
    ): TopicDetailModel = withContext(Dispatchers.IO) {
        val s = summaryFallback
        if (topicId <= 0) {
            return@withContext TopicDetailModel(
                id = 0,
                title = s?.title ?: "互动详情",
                categoryName = s?.categoryName ?: "社区通知",
                categoryColorHex = s?.categoryColorHex ?: "0E76BD",
                tags = s?.tags.orEmpty(),
                views = s?.views ?: 0,
                likeCount = s?.likeCount ?: 0,
                postsCount = 1,
                posts = emptyList(),
                loadError = "该通知不关联具体话题。"
            )
        }
        val queryParts = mutableListOf<String>()
        if (trackVisit) queryParts.add("track_visit=true")
        if (!filterUsername.isNullOrBlank()) queryParts.add("username_filters=$filterUsername")
        val querySuffix = if (queryParts.isNotEmpty()) "?${queryParts.joinToString("&")}" else ""
        val pathSegment = if (targetPostNumber != null && targetPostNumber > 1) {
            "$topicId/$targetPostNumber.json"
        } else {
            "$topicId.json"
        }
        val url = "https://linux.do/t/$pathSegment$querySuffix"
        val extraHeaders = if (trackVisit) {
            mapOf(
                "Discourse-Track-View" to "1",
                "Discourse-Track-View-Topic-Id" to topicId.toString()
            )
        } else {
            emptyMap()
        }

        if (trackVisit) {
            markTopicRead(topicId, s?.postsCount ?: 1)
        }

        val res = executeHttp(url = url, method = "GET", extraHeaders = extraHeaders, preserveFailure = true)
        val statusCode = res?.first ?: 0
        val text = res?.second
        if (statusCode in 200..299 && text != null && text.trimStart().startsWith("{")) {
            try {
                val root = json.parseToJsonElement(text).jsonObject
                val detail = parseLiveTopicDetail(root, summaryFallback)
                if (detail.posts.isNotEmpty()) {
                    val highest = maxOf(detail.postsCount, detail.highestPostNumber ?: 0, s?.postsCount ?: 1)
                    if (trackVisit) {
                        markTopicRead(topicId, highest)
                        val immediateTimings = LinkedHashMap<Int, Int>().apply {
                            put(1, 1200)
                            detail.posts.firstOrNull()?.postNumber?.let { put(it, 1200) }
                            detail.posts.lastOrNull()?.postNumber?.let { put(it, 1200) }
                            put(highest, 1200)
                        }
                        ScreenTrackEngine.consolidateBatch(topicId, 1200, immediateTimings)
                    }
                    return@withContext detail
                }
            } catch (_: Throwable) {
            }
        }

        val isJsonError = text?.trimStart()?.let { it.startsWith("{") || it.startsWith("[") } == true
        val specificError = when {
            statusCode in listOf(404, 410) -> "这篇话题已不可用"
            statusCode == 403 && isJsonError -> "当前账号暂无访问该话题的权限（可能属于高信任等级或私密板块）。"
            statusCode == 401 -> "登录已失效"
            statusCode == 403 -> "需要完成站点验证"
            statusCode == 429 -> "请求太频繁，请稍后再试"
            statusCode in 500..599 -> "服务暂时不可用"
            else -> "网络连接失败"
        }

        // Preserve the real summary, but never fabricate a first post from an excerpt on failure.
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
            loadError = specificError,
            unavailable = statusCode == 404 || statusCode == 410
        )
    }

    /**
     * 按 `post_stream.stream` 中的帖子 ID 列表分批拉取后续全部评论楼层 (`/t/{topicId}/posts.json?post_ids[]=...`)
     */
    suspend fun fetchMoreTopicPosts(
        topicId: Int,
        postIds: List<Int>,
        knownAuthorBadges: Map<Int, List<GrantedBadge>>
    ): List<PostItemModel> = withContext(Dispatchers.IO) {
        if (postIds.isEmpty()) return@withContext emptyList()
        val query = postIds.take(20).joinToString("&") { "post_ids[]=$it" }
        val url = "https://linux.do/t/$topicId/posts.json?$query"
        try {
            val root = readApiObject(executeHttp(url = url, method = "GET", preserveFailure = true))
            val authorBadges = BadgeInjector.readTopicAuthorBadges(root) ?: knownAuthorBadges
            val postStream = root["post_stream"] as? JsonObject ?: throw ApiReadException(ApiFailureKind.InvalidPayload)
            val postsArr = postStream.requireArray("posts")
            postsArr.mapIndexedNotNull { idx, elem ->
                parseSinglePostObject(
                    pObj = elem as? JsonObject ?: return@mapIndexedNotNull null,
                    fallbackIdx = idx,
                    topicId = topicId,
                    authorBadges = authorBadges
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
     * 实时同步当前登录用户的完整资料、真实信任等级 (`Lv.0` ~ `Lv.4`)、真实头像、简介与互动统计
     * 依次请求：
     * 1. `/session/current.json`（确认真实 username、trust_level、avatar_template、unread_notifications）
     * 2. `/u/{username}.json`（获取 bio_excerpt、title、custom_fields、badge_count）
     * 3. `/u/{username}/summary.json`（获取 days_visited、time_read、posts_read_count、likes_received、likes_given、topic_count、post_count、badges）
     */
    suspend fun fetchCurrentUserProfile(background: Boolean = false): UserFullProfileSummary? = withContext(Dispatchers.IO) {
        val generation = UserApiKeyAuthManager.sessionGeneration
        val headers = if (background) mapOf("Discourse-Background" to "true") else emptyMap()
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
        val sessionRes = executeHttp("https://linux.do/session/current.json", extraHeaders = headers)
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
        var topBadges = emptyList<GrantedBadge>()
        var flair = FlairInfo()

        var daysVisited = 0
        var timeReadSeconds = 0
        var recentTimeReadSeconds = 0
        var topicsEntered = 0
        var postsReadCount = 0
        var likesReceived = 0
        var likesGiven = 0
        var topicCount = 0
        var postCount = 0


        // 2 & 3. 并发拉取 /u/{username}.json 与 /u/{username}/summary.json，将个人中心同步耗时缩短 50%+
        val (userRes, summaryRes) = coroutineScope {
            val dUser = async { executeHttp("https://linux.do/u/$uname.json", extraHeaders = headers) }
            val dSummary = async { executeHttp("https://linux.do/u/$uname/summary.json", extraHeaders = headers) }
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
                    topBadges = BadgeInjector.readFeaturedBadges(root, uObj)
                    flair = parseFlair(uObj)
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
                val summaryBadges = parseUserBadgesFromRoot(root)
                if (summaryBadges.isNotEmpty()) {
                    topBadges = (topBadges + summaryBadges).distinctBy { it.id }
                }
            } catch (_: Throwable) {
            }
        }

        // 同步更新全局登录用户状态（修正 Lv.3 等真实等级与真实头像）
        if (generation != UserApiKeyAuthManager.sessionGeneration) return@withContext null
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
            badgeCount = maxOf(badgeCount, topBadges.sumOf { it.count.coerceAtLeast(1) }),
            unreadNotifications = unreadNotifs,
            badges = topBadges,
            flair = flair
        )
        if (generation != UserApiKeyAuthManager.sessionGeneration) return@withContext null
        UserApiKeyAuthManager.fullProfileCache = fullProfile
        fullProfile
    }

    /**
     * 发表话题回复或针对指定楼层发表评论 (`POST /posts.json`)
     */
    internal suspend fun createPost(
        topicId: Int,
        raw: String,
        replyToPostNumber: Int? = null,
        draftKey: String? = null
    ): PostSubmission<PostItemModel> = withContext(Dispatchers.IO) {
        val params = buildMap {
            put("topic_id", topicId.toString())
            put("raw", raw)
            if (replyToPostNumber != null && replyToPostNumber > 0) {
                put("reply_to_post_number", replyToPostNumber.toString())
            }
        }
        submitForumPost(params, draftKey) { parseSinglePostObject(it, 0, topicId) }
    }

    private suspend fun <T> submitForumPost(
        params: Map<String, String>,
        draftKey: String?,
        parse: (JsonObject) -> T?
    ): PostSubmission<T> {
        val form = buildMap {
            putAll(params)
            put("nested_post", "true")
            draftKey?.let { put("draft_key", it) }
        }
        val response = executeHttp("https://linux.do/posts.json", "POST", form, preserveFailure = true)
            ?: throw PostSubmissionUnconfirmed()
        val result = try {
            requireMutationObject(response)
        } catch (error: ApiReadException) {
            if (response.first in 200..299 || response.first in 500..599 || response.first == 408) {
                throw PostSubmissionUnconfirmed()
            }
            throw error
        }
        if ((result["action"] as? JsonPrimitive)?.contentOrNull == "enqueued") return PostSubmission.PendingReview
        val post = parse((result["post"] as? JsonObject) ?: result) ?: throw PostSubmissionUnconfirmed()
        return PostSubmission.Published(post)
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
            put("post[raw]", newRaw)
            if (editReason.isNotBlank()) {
                put("post[edit_reason]", editReason.trim())
            }
        }
        val res = executeHttp(
            url = "https://linux.do/posts/$postId.json",
            method = "PUT",
            formParams = params,
            preserveFailure = true
        ) ?: throw ApiReadException(ApiFailureKind.Connection)
        requireMutationObject(res)

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
     * 拉取帖子原始 Markdown 源文（用于无损二次编辑，优先请求 `/posts/{id}.json`，兜底 `/raw/{topicId}/{postNumber}`）
     */
    suspend fun fetchPostForEditing(postId: Int): PostItemModel = withContext(Dispatchers.IO) {
        val root = readApiObject(executeHttp("https://linux.do/posts/$postId.json", preserveFailure = true))
        val post = (root["post"] as? JsonObject) ?: root
        val parsed = parseSinglePostObject(post, 0) ?: throw ApiReadException(ApiFailureKind.InvalidPayload)
        if (parsed.id <= 0 || parsed.raw == null) throw ApiMutationException("未能读取原始正文，请重试")
        parsed
    }

    suspend fun fetchPostRawMarkdown(
        postId: Int,
        topicId: Int? = null,
        postNumber: Int? = null
    ): String? = withContext(Dispatchers.IO) {
        val res = executeHttp("https://linux.do/posts/$postId.json", method = "GET")
        if (res != null && res.first in 200..299 && res.second.trimStart().startsWith("{")) {
            val raw = runCatching {
                val root = json.parseToJsonElement(res.second).jsonObject
                val pObj = (root["post"] as? JsonObject) ?: root
                pObj["raw"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            }.getOrNull()
            if (!raw.isNullOrBlank()) return@withContext raw
        }
        if (topicId != null && topicId > 0 && postNumber != null && postNumber > 0) {
            val rawRes = executeHttp("https://linux.do/raw/$topicId/$postNumber", method = "GET")
            if (rawRes != null && rawRes.first in 200..299 && !rawRes.second.trimStart().startsWith("<!DOCTYPE", ignoreCase = true)) {
                val text = rawRes.second.trim()
                if (text.isNotEmpty()) return@withContext text
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
    suspend fun togglePostLike(postId: Int): Boolean = togglePostReactionDetailed(postId, "heart").ok

    /**
     * 收藏指定帖子/话题到个人书签 (`POST /bookmarks.json`)
     */
    suspend fun setTopicBookmark(topicId: Int, bookmarked: Boolean): Boolean = withContext(Dispatchers.IO) {
        val route = if (bookmarked) "bookmark" else "remove_bookmarks"
        requireMutationSuccess(executeHttp("https://linux.do/t/$topicId/$route.json", "PUT", emptyMap(), preserveFailure = true))
        true
    }

    suspend fun archivePrivateMessage(topicId: Int, archived: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        val route = if (archived) "archive-message" else "move-to-inbox"
        requireMutationSuccess(executeHttp("https://linux.do/t/$topicId/$route.json", "PUT", emptyMap(), preserveFailure = true))
        true
    }

    /**
     * 拉取当前登录用户的通知列表 (`/notifications.json?offset={offset}&limit=60&silent=true`)
     */
    suspend fun fetchUserNotifications(offset: Int = 0): List<UserNotificationItem> = withContext(Dispatchers.IO) {
        val cleanOffset = offset.coerceAtLeast(0)
        val res = executeHttp("https://linux.do/notifications.json?offset=$cleanOffset&limit=60&silent=true", method = "GET", preserveFailure = true)
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
                val consolidatedReplies = if (nType == 2) dataObj?.get("consolidated_count")?.jsonPrimitive?.intOrNull else null
                val count = consolidatedReplies ?: dataObj?.get("count")?.jsonPrimitive?.intOrNull
                    ?: dataObj?.get("inbox_count")?.jsonPrimitive?.intOrNull
                    ?: 0
                val badgeId = dataObj?.get("badge_id")?.jsonPrimitive?.intOrNull ?: 0
                val badgeName = dataObj?.get("badge_name")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val chatChannelId = dataObj?.get("chat_channel_id")?.jsonPrimitive?.intOrNull ?: 0
                val primaryActor = dataObj?.get("display_username")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: dataObj?.get("username")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: dataObj?.get("original_username")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: dataObj?.get("mentioned_by_username")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: obj["acting_user_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: "系统"
                val secondActor = dataObj?.get("username2")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                val actor = if (secondActor != null && secondActor != primaryActor) "$primaryActor、$secondActor" else primaryActor
                val cleanFancyTitle = obj["fancy_title"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?.replace(Regex("<[^>]+>"), "")
                    ?.replace("&quot;", "\"")
                    ?.replace("&#39;", "'")
                    ?.replace("&lt;", "<")
                    ?.replace("&gt;", ">")
                    ?.replace("&amp;", "&")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                val rawTopicTitle = dataObj?.get("topic_title")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: cleanFancyTitle
                    ?: dataObj?.get("title")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: dataObj?.get("bookmark_name")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                val chatChannelTitle = dataObj?.get("chat_channel_title")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                val groupName = dataObj?.get("group_name")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                val customMessage = dataObj?.get("message")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                val rawExcerpt = dataObj?.get("excerpt")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: obj["excerpt"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: dataObj?.get("chat_message")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: dataObj?.get("original_post_excerpt")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: customMessage
                val slugTitle = slug.takeIf { it.isNotBlank() && it != "topic" }?.replace('-', ' ')
                val title = when {
                    rawTopicTitle != null -> rawTopicTitle
                    badgeName.isNotBlank() -> badgeName
                    chatChannelTitle != null -> chatChannelTitle
                    groupName != null -> if (count > 0) "群组「$groupName」有 $count 条消息" else "群组：$groupName"
                    customMessage != null -> customMessage
                    slugTitle != null -> slugTitle
                    topicId > 0 -> "话题 #$topicId"
                    else -> ""
                }
                val actingAvatarTemplate = obj["acting_user_avatar_template"]?.jsonPrimitive?.contentOrNull
                    ?: dataObj?.get("acting_user_avatar_template")?.jsonPrimitive?.contentOrNull
                val excerpt = cleanSearchSnippet(rawExcerpt)
                UserNotificationItem(
                    id = id,
                    notificationType = nType,
                    read = read,
                    createdAtText = createdAt,
                    topicId = topicId,
                    postNumber = postNumber,
                    slug = slug,
                    title = title,
                    actingUsername = actor,
                    actingAvatarTemplate = actingAvatarTemplate,
                    excerpt = excerpt,
                    count = count,
                    badgeId = badgeId,
                    badgeName = badgeName,
                    chatChannelId = chatChannelId
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
     * 拉取用户个人的历史发帖/回复/点赞动态 (`/user_actions.json?offset={offset}&username={username}`)
     */
    suspend fun fetchUserActivities(username: String, filter: String? = null, offset: Int = 0): List<UserActivityItem> = withContext(Dispatchers.IO) {
        if (username.isBlank() || username == "linuxdo_user") throw ApiReadException(ApiFailureKind.Authentication)
        val filterParam = if (!filter.isNullOrBlank()) "&filter=$filter" else ""
        val cleanOffset = offset.coerceAtLeast(0)
        val url = "https://linux.do/user_actions.json?offset=$cleanOffset&username=$username$filterParam"
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
     * 拉取用户个人的书签列表 (`/u/{username}/bookmarks.json?page={page}`)
     */
    suspend fun fetchUserBookmarks(username: String, page: Int = 0): List<UserActivityItem> = withContext(Dispatchers.IO) {
        if (username.isBlank() || username == "linuxdo_user") throw ApiReadException(ApiFailureKind.Authentication)
        val pageParam = if (page > 0) "?page=$page" else ""
        val res = executeHttp("https://linux.do/u/$username/bookmarks.json$pageParam", method = "GET", preserveFailure = true)
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
     * 从 Discourse `/site.json` 同步动态版块目录与各版块真实的 `topic_template`（服务端配置的发帖模板）
     */
    suspend fun syncSiteCategories(): List<LinuxDoCategoryMeta> = withContext(Dispatchers.IO) {
        val res = executeHttp("https://linux.do/site.json", method = "GET")
        if (res != null && res.first in 200..299 && res.second.trimStart().startsWith("{")) {
            try {
                val root = json.parseToJsonElement(res.second).jsonObject
                mergeCategoriesFromSiteRoot(root)
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
            } catch (_: Throwable) {
            }
        }
        categories
    }

    suspend fun fetchCategoryTopicTemplate(categoryId: Int): String? = withContext(Dispatchers.IO) {
        val existing = cachedCategoryTemplates
        if (existing != null) {
            return@withContext existing[categoryId]?.takeIf { it.isNotBlank() }
        }
        syncSiteCategories()
        cachedCategoryTemplates?.get(categoryId)?.takeIf { it.isNotBlank() }
    }

    internal suspend fun fetchCategoryAllowedTags(
        categoryId: Int,
        query: String = "",
        selected: Map<String, Int?> = emptyMap()
    ): ComposerTagChoices = withContext(Dispatchers.IO) {
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val url = buildString {
            append("https://linux.do/tags/filter/search.json?q=$q&filterForInput=true&categoryId=$categoryId")
            if (query.isBlank()) append("&prioritizeRecentTags=true")
            if (selected.values.all { it != null }) {
                selected.values.forEach { append("&selected_tag_ids%5B%5D=$it") }
            } else {
                selected.keys.forEach { name ->
                    append("&selected_tags%5B%5D=").append(java.net.URLEncoder.encode(name, "UTF-8"))
                }
            }
        }
        // 优先不传 limit=5 以拉取站点允许的完整可选标签列表，避免选中必选组标签后候选数量骤降
        val fullResponse = executeHttp(url, preserveFailure = true)
        val root = readApiObject(
            if (fullResponse == null || fullResponse.first !in 200..299) {
                executeHttp("$url&limit=5", preserveFailure = true)
            } else {
                fullResponse
            }
        )
        val options = root.requireArray("results").mapNotNull { element ->
            val tag = element as? JsonObject ?: return@mapNotNull null
            val rawId = tag["id"] as? JsonPrimitive
            val name = (tag["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: (tag["text"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: rawId?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ComposerTagOption(
                name = name,
                id = rawId?.intOrNull,
                count = (tag["count"] as? JsonPrimitive)?.intOrNull,
                disabled = (tag["disabled"] as? JsonPrimitive)?.booleanOrNull == true,
                reason = (tag["title"] as? JsonPrimitive)?.contentOrNull
            )
        }.distinctBy { it.name }.take(100)
        ComposerTagChoices(
            options,
            forbiddenMessage = (root["forbidden_message"] as? JsonPrimitive)?.contentOrNull,
            requiredGroup = ((root["required_tag_group"] as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull
        )
    }

    /**
     * 发布全新社区话题 (`POST /posts.json`)，返回新话题的 `topic_id`
     */
    internal suspend fun createTopic(
        title: String,
        raw: String,
        categoryId: Int,
        tags: List<String> = emptyList(),
        draftKey: String? = null
    ): PostSubmission<Int> = withContext(Dispatchers.IO) {
        val params = buildMap {
            put("title", title.trim())
            put("raw", raw)
            put("category", categoryId.toString())
            tags.filter { it.isNotBlank() }.forEachIndexed { idx, tag ->
                put("tags[$idx]", tag.trim())
            }
        }
        submitForumPost(params, draftKey) { (it["topic_id"] as? JsonPrimitive)?.intOrNull?.takeIf { id -> id > 0 } }
    }

    private fun cleanSearchSnippet(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&hellip;", "...")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun parseSearchResponseRoot(root: JsonObject): List<CommunitySearchResult> {
        val topicsMap = LinkedHashMap<Int, JsonObject>()
        (root["topics"] as? JsonArray)?.forEach { tElem ->
            val tObj = tElem as? JsonObject ?: return@forEach
            val tid = tObj["id"]?.jsonPrimitive?.intOrNull ?: return@forEach
            topicsMap[tid] = tObj
        }
        val postsArr = root["posts"] as? JsonArray
        val hasGrouped = root.containsKey("grouped_search_result") || root.containsKey("users") || root.containsKey("topics")
        if (postsArr == null && !hasGrouped) {
            throw ApiReadException(ApiFailureKind.InvalidPayload)
        }
        val seenTopicPostKeys = HashSet<String>()
        val results = mutableListOf<CommunitySearchResult>()

        postsArr?.forEach { pElem ->
            val pObj = pElem as? JsonObject ?: return@forEach
            val tid = pObj["topic_id"]?.jsonPrimitive?.intOrNull ?: return@forEach
            val postNum = pObj["post_number"]?.jsonPrimitive?.intOrNull ?: 1
            val tObj = topicsMap[tid]
            val rawTitle = tObj?.get("title")?.jsonPrimitive?.contentOrNull
                ?: tObj?.get("fancy_title")?.jsonPrimitive?.contentOrNull
                ?: pObj["topic_title_headline"]?.jsonPrimitive?.contentOrNull
                ?: "话题 #$tid"
            val title = cleanSearchSnippet(rawTitle).ifBlank { "话题 #$tid" }
            val catId = tObj?.get("category_id")?.jsonPrimitive?.intOrNull ?: 4
            val (catName, catColor) = resolveCategoryMeta(catId)
            val blurb = cleanSearchSnippet(pObj["blurb"]?.jsonPrimitive?.contentOrNull)
            val rawTpl = pObj["avatar_template"]?.jsonPrimitive?.contentOrNull ?: ""
            val avatarUrl = if (rawTpl.isNotBlank()) {
                if (rawTpl.startsWith("http")) rawTpl.replace("{size}", "96")
                else "https://linux.do${rawTpl.replace("{size}", "96")}"
            } else ""
            seenTopicPostKeys.add("${tid}_$postNum")
            seenTopicPostKeys.add("${tid}_1")
            results.add(
                CommunitySearchResult(
                    topicId = tid,
                    postNumber = postNum,
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
            )
        }

        // 补充仅出现在 topics 数组中（例如标题命中或 /search/query.json 返回）的话题条目
        topicsMap.forEach { (tid, tObj) ->
            if ("${tid}_1" !in seenTopicPostKeys) {
                val rawTitle = tObj["title"]?.jsonPrimitive?.contentOrNull
                    ?: tObj["fancy_title"]?.jsonPrimitive?.contentOrNull
                    ?: "话题 #$tid"
                val title = cleanSearchSnippet(rawTitle).ifBlank { "话题 #$tid" }
                val catId = tObj["category_id"]?.jsonPrimitive?.intOrNull ?: 4
                val (catName, catColor) = resolveCategoryMeta(catId)
                val tagsText = (tObj["tags"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    ?.takeIf { it.isNotEmpty() }
                    ?.joinToString(" ") { "#$it" }
                    .orEmpty()
                val excerpt = cleanSearchSnippet(tObj["excerpt"]?.jsonPrimitive?.contentOrNull).ifBlank { tagsText }
                results.add(
                    CommunitySearchResult(
                        topicId = tid,
                        postNumber = 1,
                        title = title,
                        blurb = excerpt,
                        username = tObj["last_poster_username"]?.jsonPrimitive?.contentOrNull ?: "linuxdo",
                        avatarTemplate = "",
                        createdAtText = formatRelativeTime(
                            tObj["created_at"]?.jsonPrimitive?.contentOrNull
                                ?: tObj["last_posted_at"]?.jsonPrimitive?.contentOrNull
                        ),
                        categoryName = catName,
                        categoryColorHex = catColor,
                        likeCount = tObj["like_count"]?.jsonPrimitive?.intOrNull ?: 0,
                        postsCount = tObj["posts_count"]?.jsonPrimitive?.intOrNull ?: 1
                    )
                )
            }
        }

        return results
    }

    /**
     * 全局社区搜索（优先调用全量分页接口 `/search.json?q=...&page=...` 每页返回最多 50 条结果，
     * 首页失败时自动回退 `/search/query.json?term=...` 或公开话题流兜底）
     */
    suspend fun searchCommunity(
        query: String,
        order: String? = null,
        page: Int = 1
    ): List<CommunitySearchResult> = withContext(Dispatchers.IO) {
        val cleanQ = query.trim()
        if (cleanQ.isBlank()) return@withContext emptyList()
        val safePage = page.coerceAtLeast(1)
        val fullQuery = if (!order.isNullOrBlank()) "$cleanQ order:$order" else cleanQ
        val encoded = java.net.URLEncoder.encode(fullQuery, "UTF-8")

        // 始终优先请求全站搜索分页接口 /search.json（单页返回 50 条），避免默认命中仅返回 5 条预览的 /search/query.json
        val endpoints = if (safePage == 1) {
            listOf(
                "https://linux.do/search.json?q=$encoded&page=1",
                "https://linux.do/search/query.json?term=$encoded"
            )
        } else {
            listOf(
                "https://linux.do/search.json?q=$encoded&page=$safePage"
            )
        }

        var lastApiError: ApiReadException? = null
        var receivedValidResults = false
        for (endpoint in endpoints) {
            try {
                val root = readApiObject(executeHttp(endpoint, method = "GET", preserveFailure = true))
                val parsed = parseSearchResponseRoot(root)
                if (parsed.isNotEmpty()) return@withContext parsed
                receivedValidResults = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (apiErr: ApiReadException) {
                if (lastApiError == null || apiErr.kind != ApiFailureKind.Connection) {
                    lastApiError = apiErr
                }
            } catch (err: Exception) {
                lastApiError = ApiReadException(ApiFailureKind.InvalidPayload, err)
            }
        }
        if (receivedValidResults) return@withContext emptyList()

        // 若因未登录 / Cloudflare 搜索接口 403 / 429 限流导致主搜索接口被拦截，自动通过公开话题流 (latest + top + tag) 进行兜底检索
        if (lastApiError?.kind in setOf(ApiFailureKind.Authentication, ApiFailureKind.Verification, ApiFailureKind.RateLimited)) {
            val fallbackPool = LinkedHashMap<Int, TopicSummary>()
            runCatching {
                fetchTopics("latest.json").forEach { fallbackPool[it.id] = it }
            }
            runCatching {
                fetchTopics("top.json", topPeriod = "weekly").forEach { fallbackPool.putIfAbsent(it.id, it) }
            }
            if (!cleanQ.contains(" ") && cleanQ.length <= 16) {
                runCatching {
                    fetchTopics("latest.json", tag = cleanQ.removePrefix("#")).forEach { fallbackPool.putIfAbsent(it.id, it) }
                }
            }
            if (fallbackPool.isNotEmpty()) {
                val keywords = cleanQ.lowercase()
                    .removePrefix("@")
                    .removePrefix("#")
                    .split(Regex("\\s+"))
                    .filter { it.isNotBlank() }
                val matched = fallbackPool.values.filter { t ->
                    val hay = buildString {
                        append(t.title).append(' ')
                        append(t.excerpt.orEmpty()).append(' ')
                        append(t.originalPoster.username).append(' ')
                        append(t.originalPoster.name.orEmpty()).append(' ')
                        append(t.categoryName).append(' ')
                        t.tags.forEach { append(it).append(' ') }
                    }.lowercase()
                    keywords.all { kw -> hay.contains(kw) }
                }
                val sorted = when (order) {
                    "latest" -> matched
                    "likes" -> matched.sortedByDescending { it.likeCount }
                    else -> matched.sortedByDescending {
                        val titleHit = if (it.title.contains(cleanQ, ignoreCase = true)) 10000 else 0
                        titleHit + it.likeCount * 3 + it.postsCount
                    }
                }
                return@withContext sorted.map { t ->
                    CommunitySearchResult(
                        topicId = t.id,
                        postNumber = 1,
                        title = t.title,
                        blurb = t.excerpt.orEmpty().ifBlank { t.tags.joinToString(" ") { "#$it" } },
                        username = t.originalPoster.username,
                        avatarTemplate = t.originalPoster.avatarTemplate,
                        createdAtText = t.lastPostedAt,
                        categoryName = t.categoryName,
                        categoryColorHex = t.categoryColorHex,
                        likeCount = t.likeCount,
                        postsCount = t.postsCount
                    )
                }
            }
        }

        throw lastApiError ?: ApiReadException(ApiFailureKind.Connection)
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

    data class ReactionToggleResult(
        val ok: Boolean,
        val myReactionId: String? = null,
        val reactions: List<PostReactionItem>? = null,
        val errorMessage: String? = null
    )

    data class BoostMutationResult(
        val ok: Boolean,
        val boost: PostBoostItem? = null,
        val errorMessage: String? = null
    )

    data class PostReactionSnapshot(
        val myReactionId: String?,
        val reactions: List<PostReactionItem>
    )

    data class PostBoostSnapshot(
        val boosts: List<PostBoostItem>,
        val canBoost: Boolean
    )

    private val postReactionSnapshots = java.util.concurrent.ConcurrentHashMap<Int, PostReactionSnapshot>()
    private val postBoostSnapshots = java.util.concurrent.ConcurrentHashMap<Int, PostBoostSnapshot>()

    fun getPostReactionSnapshot(postId: Int): PostReactionSnapshot? = postReactionSnapshots[postId]
    fun getPostBoostSnapshot(postId: Int): PostBoostSnapshot? = postBoostSnapshots[postId]

    private fun extractActionErrorMessage(res: Pair<Int, String>?, defaultMsg: String): String {
        if (res == null || res.first == 0) {
            return "网络或认证通道暂不可用，请检查网络或重新登录"
        }
        val (status, body) = res
        val trimmed = body.trimStart()
        if (trimmed.startsWith("{")) {
            runCatching {
                val root = json.parseToJsonElement(trimmed).jsonObject
                val messages = when (val errs = root["errors"]) {
                    is JsonArray -> errs.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
                    is JsonPrimitive -> listOfNotNull(errs.contentOrNull?.takeIf(String::isNotBlank))
                    else -> emptyList()
                }.ifEmpty {
                    listOfNotNull(
                        (root["error"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank),
                        (root["message"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                    )
                }
                if (messages.isNotEmpty()) {
                    val rawText = org.jsoup.Jsoup.parse(messages.joinToString("；")).text().trim()
                    if (rawText.equals("BAD CSRF", ignoreCase = true) || rawText.contains("CSRF", ignoreCase = true)) {
                        return "会话安全令牌已过期，请刷新页面或重新登录后重试"
                    }
                    if (rawText.isNotEmpty()) {
                        return rawText.take(180)
                    }
                }
            }
        }
        return when (status) {
            401 -> "登录状态已失效，请重新登录后操作"
            403 -> "当前无权限执行该操作（不能给自己的帖子、已删除或已锁定的楼层操作）"
            404, 410 -> "目标楼层不存在或已被作者删除"
            422 -> "操作未生效（不能给自己的帖子操作，或表情不在站点白名单内）"
            429 -> "操作过于频繁，请稍后再试"
            else -> defaultMsg
        }
    }

    private fun parsePostReactionsAndMine(pObj: JsonObject): Pair<List<PostReactionItem>, String?> {
        val parsedReactions = (pObj["reactions"] as? JsonArray)?.mapNotNull { rElem ->
            val rObj = rElem as? JsonObject ?: return@mapNotNull null
            val rId = rObj["id"]?.jsonPrimitive?.contentOrNull?.trim() ?: return@mapNotNull null
            val cnt = rObj["count"]?.jsonPrimitive?.intOrNull ?: 0
            if (cnt <= 0) return@mapNotNull null
            PostReactionItem(
                id = rId,
                emoji = LinuxDoEmojiCatalog.shortcodeToUnicode(rId) ?: ":$rId:",
                count = cnt
            )
        }.orEmpty()

        val myReactionObj = pObj["current_user_reaction"] as? JsonObject
        val myReactionId = myReactionObj?.get("id")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: if (
                (pObj["actions_summary"] as? JsonArray)?.any { aElem ->
                    val aObj = aElem as? JsonObject
                    aObj?.get("id")?.jsonPrimitive?.intOrNull == 2 && aObj["acted"]?.jsonPrimitive?.booleanOrNull == true
                } == true
            ) "heart" else null

        val finalReactions = if (parsedReactions.isNotEmpty()) {
            parsedReactions
        } else {
            val likeActCount = (pObj["actions_summary"] as? JsonArray)?.firstOrNull { aElem ->
                (aElem as? JsonObject)?.get("id")?.jsonPrimitive?.intOrNull == 2
            }?.let { (it as? JsonObject)?.get("count")?.jsonPrimitive?.intOrNull } ?: 0
            if (likeActCount > 0) {
                listOf(PostReactionItem(id = "heart", emoji = "❤️", count = likeActCount))
            } else {
                emptyList()
            }
        }
        return finalReactions to myReactionId
    }

    private fun cookedBoostToInlineText(cookedHtml: String): String {
        if (cookedHtml.isBlank()) return ""
        return try {
            val doc = org.jsoup.Jsoup.parseBodyFragment(cookedHtml)
            doc.select("img").forEach { img ->
                val src = img.attr("src").trim()
                val title = img.attr("title").trim()
                val alt = img.attr("alt").trim()
                val token = when {
                    title.startsWith(":") && title.endsWith(":") && title.length > 2 -> title
                    alt.startsWith(":") && alt.endsWith(":") && alt.length > 2 -> alt
                    title.isNotEmpty() -> ":${title.removeSurrounding(":")}:"
                    alt.isNotEmpty() -> alt
                    else -> ""
                }
                if (token.startsWith(":") && token.endsWith(":") && src.isNotEmpty()) {
                    LinuxDoEmojiCatalog.registerSingleEmojiUrl(token, src)
                }
                img.replaceWith(org.jsoup.nodes.TextNode(token))
            }
            doc.body().text().replace(Regex("\\s+"), " ").trim()
        } catch (_: Throwable) {
            cookedHtml.replace(Regex("<[^>]+>"), "").trim()
        }
    }

    private fun parseSingleBoostObject(bObj: JsonObject): PostBoostItem? {
        val bid = bObj["id"]?.jsonPrimitive?.longOrNull ?: return null
        val cooked = bObj["cooked"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val rawFallback = bObj["raw"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val plain = cookedBoostToInlineText(cooked).ifBlank { rawFallback }
        if (plain.isBlank() && cooked.isBlank()) return null
        val uObj = bObj["user"] as? JsonObject
        val uid = uObj?.get("id")?.jsonPrimitive?.intOrNull
            ?: bObj["user_id"]?.jsonPrimitive?.intOrNull ?: 0
        val uname = uObj?.get("username")?.jsonPrimitive?.contentOrNull
            ?: bObj["username"]?.jsonPrimitive?.contentOrNull ?: "linuxdo"
        val dName = uObj?.get("name")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val rawAvatar = uObj?.get("avatar_template")?.jsonPrimitive?.contentOrNull
            ?: bObj["avatar_template"]?.jsonPrimitive?.contentOrNull ?: ""
        val avatarUrl = if (rawAvatar.isNotBlank()) LinuxDoSiteRules.resolveUrl(rawAvatar) else ""
        val account = UserApiKeyAuthManager.currentUser
        val isMine = if (uid > 0 && account != null && account.userId > 0) uid == account.userId
            else account?.username?.let { uname.equals(it, ignoreCase = true) } == true
        val canDelete = bObj["can_delete"]?.jsonPrimitive?.booleanOrNull ?: isMine
        val canFlag = bObj["can_flag"]?.jsonPrimitive?.booleanOrNull ?: false
        return PostBoostItem(
            id = bid,
            cookedHtml = cooked.ifBlank { plain },
            plainText = plain,
            userId = uid,
            username = uname,
            displayName = dName,
            avatarTemplate = avatarUrl,
            canDelete = canDelete,
            canFlag = canFlag
        )
    }

    private fun parsePostBoostsArray(element: JsonElement?): List<PostBoostItem> {
        val arr = element as? JsonArray ?: return emptyList()
        return arr.mapNotNull { item ->
            parseSingleBoostObject(item as? JsonObject ?: return@mapNotNull null)
        }
    }

    /**
     * 多表情互动回应（严格对齐 Linux.do `discourse-reactions` 官方接口，解析服务端最新 `PostSerializer` 状态并持久化快照）
     */
    suspend fun togglePostReactionDetailed(
        postId: Int,
        reactionId: String = "heart"
    ): ReactionToggleResult = withContext(Dispatchers.IO) {
        val cleanReaction = reactionId.trim().removeSurrounding(":").ifBlank { "heart" }
        val encodedReaction = java.net.URLEncoder.encode(cleanReaction, "UTF-8")
        val url = "https://linux.do/discourse-reactions/posts/$postId/custom-reactions/$encodedReaction/toggle.json"
        val res = executeHttp(url = url, method = "PUT", formParams = emptyMap(), preserveFailure = true)
        if (res != null && res.first in 200..299) {
            val trimmed = res.second.trimStart()
            if (trimmed.startsWith("{")) {
                runCatching {
                    val root = json.parseToJsonElement(trimmed).jsonObject
                    val pObj = (root["post"] as? JsonObject) ?: root
                    if (
                        pObj.containsKey("reactions") ||
                        pObj.containsKey("current_user_reaction") ||
                        pObj.containsKey("actions_summary")
                    ) {
                        val (serverReactions, serverMyReaction) = parsePostReactionsAndMine(pObj)
                        postReactionSnapshots[postId] = PostReactionSnapshot(
                            myReactionId = serverMyReaction,
                            reactions = serverReactions
                        )
                        return@withContext ReactionToggleResult(
                            ok = true,
                            myReactionId = serverMyReaction,
                            reactions = serverReactions
                        )
                    }
                }
            }
            return@withContext ReactionToggleResult(ok = true)
        }

        // 仅当站点未挂载 discourse-reactions 路由 (404) 且点击的是红心时，回退至标准 post_actions 点赞接口
        if (cleanReaction == "heart" && res?.first == 404) {
            val fallbackRes = executeHttp(
                url = "https://linux.do/post_actions.json",
                method = "POST",
                formParams = mapOf(
                    "id" to postId.toString(),
                    "post_action_type_id" to "2"
                ),
                preserveFailure = true
            )
            if (fallbackRes != null && fallbackRes.first in 200..299) {
                val trimmed = fallbackRes.second.trimStart()
                if (trimmed.startsWith("{")) {
                    runCatching {
                        val root = json.parseToJsonElement(trimmed).jsonObject
                        val pObj = (root["post"] as? JsonObject) ?: root
                        if (pObj.containsKey("actions_summary") || pObj.containsKey("reactions")) {
                            val (serverReactions, serverMyReaction) = parsePostReactionsAndMine(pObj)
                            postReactionSnapshots[postId] = PostReactionSnapshot(
                                myReactionId = serverMyReaction,
                                reactions = serverReactions
                            )
                            return@withContext ReactionToggleResult(
                                ok = true,
                                myReactionId = serverMyReaction,
                                reactions = serverReactions
                            )
                        }
                    }
                }
                return@withContext ReactionToggleResult(ok = true)
            }
            return@withContext ReactionToggleResult(
                ok = false,
                errorMessage = extractActionErrorMessage(fallbackRes, "点赞未生效，请稍后重试")
            )
        }

        ReactionToggleResult(
            ok = false,
            errorMessage = extractActionErrorMessage(res, "表态未生效，请稍后重试")
        )
    }

    suspend fun togglePostReaction(postId: Int, reactionId: String = "heart"): Boolean =
        togglePostReactionDetailed(postId, reactionId).ok

    /**
     * 发送小火箭 Boost (`POST /discourse-boosts/posts/{postId}/boosts.json`)
     * 支持表情（含站点自定义 `:shortcode:`）+ 16 个可见字符以内的短评（最多 5 个表情）
     */
    suspend fun createPostBoost(postId: Int, raw: String): BoostMutationResult = withContext(Dispatchers.IO) {
        val trimmed = raw.trim()
        val stats = BoostInputStats.compute(trimmed)
        if (stats.isEmpty) {
            return@withContext BoostMutationResult(ok = false, errorMessage = "请输入 Boost 短评或选择表情")
        }
        if (stats.exceedsLength) {
            return@withContext BoostMutationResult(
                ok = false,
                errorMessage = "Boost 内容最多 ${BoostInputStats.MAX_BOOST_VISIBLE_LENGTH} 个字（当前 ${stats.visibleLength} 字）"
            )
        }
        if (stats.exceedsEmojiLimit) {
            return@withContext BoostMutationResult(
                ok = false,
                errorMessage = "单条 Boost 最多包含 ${BoostInputStats.MAX_BOOST_EMOJI_COUNT} 个表情（当前 ${stats.emojiCount} 个）"
            )
        }

        val res = executeHttp(
            url = "https://linux.do/discourse-boosts/posts/$postId/boosts.json",
            method = "POST",
            formParams = mapOf("raw" to trimmed),
            preserveFailure = true
        )
        if (res != null && res.first in 200..299) {
            val body = res.second.trimStart()
            var createdBoost: PostBoostItem? = null
            if (body.startsWith("{")) {
                runCatching {
                    val root = json.parseToJsonElement(body).jsonObject
                    val bObj = (root["boost"] as? JsonObject) ?: root
                    createdBoost = parseSingleBoostObject(bObj)
                }
            }
            val currentUser = UserApiKeyAuthManager.currentUser
            val resolvedBoost = createdBoost ?: PostBoostItem(
                id = System.currentTimeMillis(),
                cookedHtml = trimmed,
                plainText = trimmed,
                userId = currentUser?.userId ?: 0,
                username = currentUser?.username ?: "me",
                displayName = currentUser?.displayName,
                avatarTemplate = currentUser?.avatarTemplate.orEmpty(),
                canDelete = true,
                canFlag = false
            )
            val existing = postBoostSnapshots[postId]?.boosts.orEmpty()
            val updatedList = (existing.filterNot { it.id == resolvedBoost.id } + resolvedBoost)
            postBoostSnapshots[postId] = PostBoostSnapshot(boosts = updatedList, canBoost = false)
            return@withContext BoostMutationResult(ok = true, boost = resolvedBoost)
        }
        BoostMutationResult(
            ok = false,
            errorMessage = extractActionErrorMessage(res, "发送小火箭 Boost 失败，请稍后重试")
        )
    }

    /**
     * 删除自己发送的小火箭 Boost (`DELETE /discourse-boosts/boosts/{boostId}.json`)
     */
    suspend fun deletePostBoost(postId: Int, boostId: Long): BoostMutationResult = withContext(Dispatchers.IO) {
        val res = executeHttp(
            url = "https://linux.do/discourse-boosts/boosts/$boostId.json",
            method = "DELETE",
            preserveFailure = true
        )
        if (res != null && res.first in 200..299) {
            postBoostSnapshots.computeIfPresent(postId) { _, snap ->
                val remaining = snap.boosts.filterNot { it.id == boostId }
                if (remaining.size == snap.boosts.size) snap else snap.copy(boosts = remaining, canBoost = true)
            }
            return@withContext BoostMutationResult(ok = true)
        }
        BoostMutationResult(
            ok = false,
            errorMessage = extractActionErrorMessage(res, "删除 Boost 失败，请稍后重试")
        )
    }

    /**
     * 拉取用户私信箱 (`/topics/private-messages/{username}.json` 或 `private-messages-sent`)
     */
    suspend fun fetchPrivateMessages(username: String, sent: Boolean = false, archived: Boolean = false, page: Int = 0): List<TopicSummary> = withContext(Dispatchers.IO) {
        if (username.isBlank() || username == "linuxdo_user") throw ApiReadException(ApiFailureKind.Authentication)
        val mailbox = when {
            archived -> "private-messages-archive"
            sent -> "private-messages-sent"
            else -> "private-messages"
        }
        val pageParam = if (page > 0) "?page=$page" else ""
        val res = executeHttp("https://linux.do/topics/$mailbox/$username.json$pageParam", method = "GET", preserveFailure = true) ?: throw ApiReadException(ApiFailureKind.Connection)
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
            "raw" to raw,
            "archetype" to "private_message",
            "target_recipients" to targetUsernames.trim()
        )
        val res = executeHttp(
            url = "https://linux.do/posts.json",
            method = "POST",
            formParams = params, preserveFailure = true
        ) ?: throw ApiReadException(ApiFailureKind.Connection)
        requireMutationObject(res)
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
        var lastSeenText = ""
        var location = ""
        var websiteName = ""
        var statusText = ""
        var badgeCount = 0
        var topBadges = emptyList<GrantedBadge>()
        var flair = FlairInfo()

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
                    animatedAvatar = uObj["animated_avatar"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    trustLevel = uObj["trust_level"]?.jsonPrimitive?.intOrNull ?: 1
                    title = uObj["title"]?.jsonPrimitive?.contentOrNull ?: ""
                    bioExcerpt = uObj["bio_excerpt"]?.jsonPrimitive?.contentOrNull
                        ?.replace(Regex("<[^>]+>"), "")?.trim() ?: ""
                    primaryGroupName = uObj["primary_group_name"]?.jsonPrimitive?.contentOrNull
                    admin = uObj["admin"]?.jsonPrimitive?.booleanOrNull ?: false
                    moderator = uObj["moderator"]?.jsonPrimitive?.booleanOrNull ?: false
                    badgeCount = uObj["badge_count"]?.jsonPrimitive?.intOrNull ?: 0
                    topBadges = BadgeInjector.readFeaturedBadges(root, uObj)
                    flair = parseFlair(uObj)
                    location = uObj["location"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    websiteName = (uObj["website_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                        ?: uObj["website"]?.jsonPrimitive?.contentOrNull)?.trim().orEmpty()
                    val statusObj = uObj["status"] as? JsonObject
                    if (statusObj != null) {
                        val statusDesc = statusObj["description"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                        statusText = statusDesc
                    }
                    val createdIso = uObj["created_at"]?.jsonPrimitive?.contentOrNull
                    if (!createdIso.isNullOrBlank() && createdIso.length >= 10) {
                        createdAtText = createdIso.substring(0, 10)
                    }
                    val lastActiveIso = uObj["last_seen_at"]?.jsonPrimitive?.contentOrNull
                        ?: uObj["last_posted_at"]?.jsonPrimitive?.contentOrNull
                    if (!lastActiveIso.isNullOrBlank()) {
                        lastSeenText = formatRelativeTime(lastActiveIso)
                    }
                }
            } catch (_: Throwable) {
            }
        }

        var daysVisited = 0
        var timeReadSeconds = 0
        var recentTimeReadSeconds = 0
        var topicsEntered = 0
        var postsReadCount = 0
        var likesReceived = 0
        var likesGiven = 0
        var topicCount = 0
        var postCount = 0
        var solvedCount = 0


        val summaryRes = executeHttp("https://linux.do/u/$clean/summary.json", method = "GET")
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
                    solvedCount = us["solved_count"]?.jsonPrimitive?.intOrNull ?: 0
                }
                val summaryBadges = parseUserBadgesFromRoot(root)
                if (summaryBadges.isNotEmpty()) {
                    topBadges = (topBadges + summaryBadges).distinctBy { it.id }
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
            recentTimeReadSeconds = recentTimeReadSeconds,
            topicsEntered = topicsEntered,
            postsReadCount = postsReadCount,
            likesReceived = likesReceived,
            likesGiven = likesGiven,
            topicCount = topicCount,
            postCount = postCount,
            badgeCount = maxOf(badgeCount, topBadges.sumOf { it.count.coerceAtLeast(1) }),
            lastSeenText = lastSeenText,
            location = location,
            websiteName = websiteName,
            statusText = statusText,
            solvedCount = solvedCount,
            badges = topBadges,
            flair = flair
        )
    }

    suspend fun updateUserTitle(
        username: String,
        title: String
    ): Boolean = withContext(Dispatchers.IO) {
        val targetUsername = username.ifBlank { UserApiKeyAuthManager.currentUser?.username.orEmpty() }
        if (targetUsername.isBlank() || targetUsername == "linuxdo_user") return@withContext false
        val res = executeHttp(
            url = "https://linux.do/u/$targetUsername.json",
            method = "PUT",
            formParams = mapOf("title" to title.trim())
        )
        if (res != null && res.first in 200..299) {
            fetchCurrentUserProfile()
            return@withContext true
        }
        false
    }

    private fun parseUserBadgesFromRoot(root: JsonObject): List<GrantedBadge> {
        val badges = BadgeInjector.readBadgeDefinitions(root["badges"]).associateBy { it.id }
        val topicTitles = (root["topics"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .associate { it["id"]?.jsonPrimitive?.intOrNull to it["title"]?.jsonPrimitive?.contentOrNull.orEmpty() }
        val userBadgesArr = root["user_badges"] as? JsonArray ?: return emptyList()
        return userBadgesArr.mapNotNull { element ->
            val grant = element as? JsonObject ?: return@mapNotNull null
            val badgeId = grant["badge_id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val badge = badges[badgeId]
                ?: BadgeInjector.lookupBadgeById(badgeId)
                ?: BadgeInjector.parseGrantedBadge(grant, fallbackId = badgeId).takeIf { it.id > 0 }
                ?: return@mapNotNull null
            val topicId = grant["topic_id"]?.jsonPrimitive?.intOrNull ?: 0
            badge.copy(
                count = grant["count"]?.jsonPrimitive?.intOrNull ?: 1,
                grantedAtText = grant["granted_at"]?.jsonPrimitive?.contentOrNull.orEmpty().take(10),
                isEarned = true,
                topicId = topicId,
                topicTitle = grant["topic_title"]?.jsonPrimitive?.contentOrNull ?: topicTitles[topicId].orEmpty(),
                postNumber = grant["post_number"]?.jsonPrimitive?.intOrNull ?: 0
            ) to (grant["grouping_position"]?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE)
        }.distinctBy { it.first.id }
            .sortedWith(compareBy({ it.second }, { it.first.badgeTypeId }, { it.first.id }))
            .map { it.first }
    }

    internal suspend fun executeDirectReadFallback(
        url: String,
        extraHeaders: Map<String, String> = emptyMap()
    ): Pair<Int, String>? = withContext(Dispatchers.IO) {
        // 若当前平台已挂载同源 Chromium WebView 执行器（Android），严禁使用 OkHttp 发起直连回退，
        // 避免 Java TLS 指纹暴露及响应 Set-Cookie (_forum_session) 覆盖 WebView 会话状态
        if (UserApiKeyAuthManager.webViewHttpExecutor != null) {
            return@withContext null
        }
        try {
            val cfTlsBoundCookies = setOf("cf_clearance", "__cf_bm", "_cfuvid", "cf_chl_rc_i", "cf_chl_rc_ni")
            val cookieHeader = CookieSentinel.resolveWinningCookies(targetHost = "linux.do")
                .entries
                .filterNot { it.key == "_t" && it.value.value == "demo_session_token" }
                .filterNot { it.key in cfTlsBoundCookies }
                .joinToString("; ") { "${it.key}=${it.value.value}" }
            val requestHeaders = LinkedHashMap<String, String>().apply {
                put("Accept", "application/json")
                put("X-Requested-With", "XMLHttpRequest")
                extraHeaders.forEach { (k, v) -> put(k, v) }
                UserApiKeyAuthManager.customUserAgent?.takeIf { it.isNotBlank() }?.let {
                    put("User-Agent", it)
                }
                if (cookieHeader.isNotEmpty()) put("Cookie", cookieHeader)
            }
            var response = executeApiRequest(sharedClient, url, "GET", null, requestHeaders)
            response.headers.getAll("Set-Cookie")?.forEach { setCookie ->
                CookieSentinel.ingestRawCookieString(setCookie.substringBefore(';'), domain = "linux.do")
            }
            var status = response.status.value
            var text = response.bodyAsText()
            if (status == 403 && requestHeaders.containsKey("Discourse-Logged-In")) {
                requestHeaders.remove("Discourse-Logged-In")
                response = executeApiRequest(sharedClient, url, "GET", null, requestHeaders)
                status = response.status.value
                text = response.bodyAsText()
            }
            if (isSuccessfulApiResponse(status, text)) status to text else null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    @Volatile
    var cachedSiteBadges: List<GrantedBadge> = emptyList()
        private set

    @Volatile
    private var cachedUserBadgesByUsername: Map<String, List<GrantedBadge>> = emptyMap()

    fun getCachedUserBadges(username: String): List<GrantedBadge>? {
        val clean = username.trim().removePrefix("@").lowercase()
        if (clean.isBlank()) return null
        return cachedUserBadgesByUsername[clean]
    }

    /**
     * 获取指定用户的完整已获徽章列表 (`GET /user-badges/{username}.json?grouped=true`)
     */
    suspend fun fetchUserBadges(username: String): List<GrantedBadge> = withContext(Dispatchers.IO) {
        val clean = username.trim().removePrefix("@")
        if (clean.isBlank()) return@withContext emptyList()
        val encoded = java.net.URLEncoder.encode(clean, "UTF-8")
        val primaryRes = executeHttp("https://linux.do/user-badges/$encoded.json?grouped=true", method = "GET", preserveFailure = true)
        val effectiveRes = if (primaryRes != null && isSuccessfulApiResponse(primaryRes.first, primaryRes.second)) {
            primaryRes
        } else {
            executeHttp("https://linux.do/user_badges.json?username=$encoded&grouped=true", method = "GET", preserveFailure = true)
                ?: primaryRes
        }
        try {
            val root = readApiObject(effectiveRes)
            root.requireArray("user_badges")
            val parsed = parseUserBadgesFromRoot(root)
            if (parsed.isNotEmpty()) {
                cachedUserBadgesByUsername = cachedUserBadgesByUsername + (clean.lowercase() to parsed)
            }
            parsed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (apiErr: Exception) {
            getCachedUserBadges(clean)?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
            val cached = UserApiKeyAuthManager.fullProfileCache
                ?.takeIf { it.username.equals(clean, ignoreCase = true) && it.badges.isNotEmpty() }
                ?.badges
            if (!cached.isNullOrEmpty()) return@withContext cached
            val cardBadges = runCatching { fetchPublicUserCard(clean)?.badges }.getOrNull()
            if (!cardBadges.isNullOrEmpty()) return@withContext cardBadges
            throw apiErr
        }
    }

    suspend fun fetchBadgeDefinition(badgeId: Int): GrantedBadge? = withContext(Dispatchers.IO) {
        val response = executeHttp("https://linux.do/badges/$badgeId.json", method = "GET")
        try {
            val badge = readApiObject(response)["badge"] as? JsonObject ?: return@withContext null
            BadgeInjector.parseGrantedBadge(badge).copy(
                isEarned = badge["has_badge"]?.jsonPrimitive?.booleanOrNull == true
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    /** Loads the visible catalog in LinuxDo's badge-group order. */
    suspend fun fetchAllSiteBadges(): List<GrantedBadge> = withContext(Dispatchers.IO) {
        val root = readApiObject(executeHttp("https://linux.do/badges.json", method = "GET", preserveFailure = true))
        val groups = (root["badge_groupings"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .associateBy { it["id"]?.jsonPrimitive?.intOrNull }
        val result = root.requireArray("badges").mapNotNull { element ->
            val badge = element as? JsonObject ?: return@mapNotNull null
            if (badge["listable"]?.jsonPrimitive?.booleanOrNull == false ||
                badge["enabled"]?.jsonPrimitive?.booleanOrNull == false) return@mapNotNull null
            val parsed = BadgeInjector.parseGrantedBadge(badge)
            parsed.copy(
                isEarned = false,
                badgeGroupingName = groups[parsed.badgeGroupingId]?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()
            )
        }.distinctBy { it.id }.sortedBy { badge ->
            groups[badge.badgeGroupingId]?.get("position")?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE
        }
        if (result.isNotEmpty()) {
            cachedSiteBadges = result
        }
        result
    }

    private fun isTodayDateMonthDay(dateStr: String?, requirePreviousYear: Boolean): Boolean {
        if (dateStr.isNullOrBlank() || dateStr.length < 10) return false
        return try {
            val datePart = dateStr.substring(0, 10)
            val parsed = java.time.LocalDate.parse(datePart)
            val today = java.time.LocalDate.now()
            val sameMonthDay = parsed.monthValue == today.monthValue && parsed.dayOfMonth == today.dayOfMonth
            if (!sameMonthDay) false
            else if (requirePreviousYear) parsed.year < today.year
            else true
        } catch (_: Throwable) {
            false
        }
    }

    private fun parseFlair(user: JsonObject) = FlairInfo(
        url = user["flair_url"]?.jsonPrimitive?.contentOrNull,
        name = user["flair_name"]?.jsonPrimitive?.contentOrNull,
        bgColorHex = user["flair_bg_color"]?.jsonPrimitive?.contentOrNull,
        fgColorHex = user["flair_color"]?.jsonPrimitive?.contentOrNull,
        groupId = user["flair_group_id"]?.jsonPrimitive?.intOrNull
    )

    private fun parseSinglePostObject(
        pObj: JsonObject,
        fallbackIdx: Int,
        topicId: Int = 0,
        lastReadPostNumber: Int? = null,
        forceRead: Boolean = false,
        authorBadges: Map<Int, List<GrantedBadge>> = emptyMap()
    ): PostItemModel? {
        val pid = pObj["id"]?.jsonPrimitive?.intOrNull ?: return null
        val postNumber = pObj["post_number"]?.jsonPrimitive?.intOrNull ?: (fallbackIdx + 1)
        val resolvedTopicId = pObj["topic_id"]?.jsonPrimitive?.intOrNull ?: topicId
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
        val groupModerator = pObj["group_moderator"]?.jsonPrimitive?.booleanOrNull
            ?: pObj["category_group_moderator"]?.jsonPrimitive?.booleanOrNull
            ?: false
        val iosDeviceName = pObj["ios_device_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val userStatusObj = pObj["user_status"] as? JsonObject
        val userStatus = userStatusObj?.let { sObj ->
            val desc = sObj["description"]?.jsonPrimitive?.contentOrNull
            val emoji = sObj["emoji"]?.jsonPrimitive?.contentOrNull
            if (!desc.isNullOrBlank() || !emoji.isNullOrBlank()) {
                UserStatus(description = desc, emoji = emoji)
            } else {
                null
            }
        }
        val cooked = pObj["cooked"]?.jsonPrimitive?.contentOrNull ?: "<p></p>"
        val createdAtIso = pObj["created_at"]?.jsonPrimitive?.contentOrNull
        val replyCount = pObj["reply_count"]?.jsonPrimitive?.intOrNull ?: 0
        val replyToPostNumber = pObj["reply_to_post_number"]?.jsonPrimitive?.intOrNull ?: 0
        val replyToUserObj = pObj["reply_to_user"] as? JsonObject
        val replyToUsername = replyToUserObj?.get("username")?.jsonPrimitive?.contentOrNull
        val replyToDisplayName = replyToUserObj?.get("name")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val replyToAvatarTemplate = replyToUserObj?.get("avatar_template")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val reads = pObj["reads"]?.jsonPrimitive?.intOrNull ?: 0

        val (rawParsedReactions, rawMyReactionId) = parsePostReactionsAndMine(pObj)
        val reactionSnap = postReactionSnapshots[pid]
        val parsedReactions = reactionSnap?.reactions ?: rawParsedReactions
        val myReactionId = if (reactionSnap != null) reactionSnap.myReactionId else rawMyReactionId

        val reactionsCount = if (parsedReactions.isNotEmpty()) {
            parsedReactions.sumOf { it.count }
        } else {
            (pObj["actions_summary"] as? JsonArray)?.firstOrNull { aElem ->
                (aElem as? JsonObject)?.get("id")?.jsonPrimitive?.intOrNull == 2
            }?.let { (it as? JsonObject)?.get("count")?.jsonPrimitive?.intOrNull } ?: 0
        }

        val likedByMe = !myReactionId.isNullOrBlank()

        val rawRead = pObj["read"]?.jsonPrimitive?.booleanOrNull
        val alreadyReadInSession = resolvedTopicId > 0 && ScreenTrackEngine.isPostRead(resolvedTopicId, postNumber)
        val account = UserApiKeyAuthManager.currentUser
        val isOwnPost = if (userId > 0 && account != null && account.userId > 0) userId == account.userId
            else account?.username?.let { username.equals(it, ignoreCase = true) } == true
        val rawBoosts = parsePostBoostsArray(pObj["boosts"])
        val boostSnap = postBoostSnapshots[pid]
        val finalBoosts = boostSnap?.boosts ?: rawBoosts
        val alreadyBoostedByMe = finalBoosts.any { it.isBy(account?.userId, account?.username) }
        val canBoost = boostSnap?.canBoost
            ?: (pObj["can_boost"]?.jsonPrimitive?.booleanOrNull ?: (!isOwnPost && !alreadyBoostedByMe))
        val isRead = when {
            forceRead || alreadyReadInSession || isOwnPost -> true
            rawRead == false -> false
            lastReadPostNumber != null && postNumber > lastReadPostNumber -> false
            rawRead == true && lastReadPostNumber != null -> true
            rawRead == true && postNumber == 1 -> true
            else -> false
        }

        val bookmarkedByMe = pObj["bookmarked"]?.jsonPrimitive?.booleanOrNull ?: false
        val canEdit = pObj["can_edit"]?.jsonPrimitive?.booleanOrNull ?: true
        val canDelete = pObj["can_delete"]?.jsonPrimitive?.booleanOrNull ?: false
        val rawMarkdown = pObj["raw"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

        val grantedBadges = parsePostGrantedBadges(pObj["badges_granted"])

        val cakedateRaw = pObj["user_cakedate"]?.jsonPrimitive?.contentOrNull
        val birthdateRaw = pObj["user_birthdate"]?.jsonPrimitive?.contentOrNull

        return PostItemModel(
            id = pid,
            postNumber = postNumber,
            userId = userId,
            username = username,
            topicId = resolvedTopicId,
            displayName = displayName,
            avatarTemplate = avatarTemplate,
            animatedAvatar = animatedAvatar,
            primaryGroupName = primaryGroupName,
            userTitle = userTitle,
            titleIsGroup = pObj["title_is_group"]?.jsonPrimitive?.booleanOrNull == true,
            trustLevel = trustLevel,
            admin = admin,
            moderator = moderator,
            groupModerator = groupModerator,
            isTopicOwner = postNumber == 1 || pObj["topic_owner"]?.jsonPrimitive?.booleanOrNull == true,
            isTodayCakeday = isTodayDateMonthDay(cakedateRaw, requirePreviousYear = true),
            isTodayBirthday = isTodayDateMonthDay(birthdateRaw, requirePreviousYear = false),
            iosDeviceName = iosDeviceName,
            userStatus = userStatus,
            flair = parseFlair(pObj),
            badgesGranted = grantedBadges,
            authorBadges = authorBadges[userId].orEmpty(),
            createdAtText = formatRelativeTime(createdAtIso),
            cookedHtml = cooked,
            replyCount = replyCount,
            replyToPostNumber = replyToPostNumber,
            replyToUsername = replyToUsername,
            replyToDisplayName = replyToDisplayName,
            replyToAvatarTemplate = replyToAvatarTemplate,
            likeCount = reactionsCount,
            readsCount = reads,
            likedByMe = likedByMe,
            isRead = isRead,
            myReactionId = myReactionId,
            reactions = parsedReactions,
            boosts = finalBoosts,
            canBoost = canBoost,
            bookmarkedByMe = bookmarkedByMe,
            canEdit = canEdit,
            canDelete = canDelete,
            raw = rawMarkdown
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
        val lastReadPostNumber = root["last_read_post_number"]?.jsonPrimitive?.intOrNull

        val authorBadges = BadgeInjector.readTopicAuthorBadges(root).orEmpty()

        val postStream = root["post_stream"] as? JsonObject
        val postsArr = postStream?.get("posts") as? JsonArray ?: JsonArray(emptyList())
        val streamIds = (postStream?.get("stream") as? JsonArray)?.mapNotNull {
            it.jsonPrimitive.intOrNull
        }.orEmpty()

        val posts = postsArr.mapIndexedNotNull { idx, elem ->
            parseSinglePostObject(
                pObj = elem as? JsonObject ?: return@mapIndexedNotNull null,
                fallbackIdx = idx,
                topicId = id,
                lastReadPostNumber = lastReadPostNumber,
                authorBadges = authorBadges
            )
        }
        if (id > 0) {
            posts.firstOrNull()?.cookedHtml?.let { firstCooked ->
                val ex = extractPlainExcerptFromHtml(firstCooked)
                if (ex.isNotBlank()) {
                    topicExcerptCache[id] = ex
                }
            }
        }

        return TopicDetailModel(
            id = id,
            title = title,
            originalPosterUsername = (root["details"] as? JsonObject)?.get("created_by")?.let { (it as? JsonObject)?.get("username")?.jsonPrimitive?.contentOrNull },
            categoryName = catName,
            categoryColorHex = catColor,
            tags = tags,
            views = views,
            likeCount = likeCount,
            postsCount = postsCount,
            highestPostNumber = root["highest_post_number"]?.jsonPrimitive?.intOrNull,
            posts = posts,
            streamIds = streamIds,
            bookmarked = bookmarked
        )
    }

    private fun parsePostGrantedBadges(element: JsonElement?): List<GrantedBadge> =
        (element as? JsonArray).orEmpty().mapNotNull { item ->
            when (item) {
                is JsonObject -> BadgeInjector.parseGrantedBadge(item, isFirstGrant = true)
                is JsonPrimitive -> item.intOrNull?.let(BadgeInjector::lookupBadgeById)?.copy(isFirstTimeGrant = true)
                else -> null
            }
        }.filter { it.id > 0 }.distinctBy { it.id }

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
            val posterEntries = (tObj["posters"] as? JsonArray)?.mapNotNull { pElem ->
                val pObj = pElem as? JsonObject ?: return@mapNotNull null
                val uid = pObj["user_id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                val user = usersMap[uid] ?: return@mapNotNull null
                val desc = pObj["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
                desc to user
            }.orEmpty()
            val posters = posterEntries.map { it.second }
            val op = posterEntries.firstOrNull { (desc, _) ->
                desc.contains("Original Poster", ignoreCase = true) ||
                    desc.contains("原始") ||
                    desc.contains("Creator", ignoreCase = true)
            }?.second ?: posters.firstOrNull() ?: TopicUser(
                id = 0,
                username = tObj["last_poster_username"]?.jsonPrimitive?.contentOrNull ?: "linuxdo"
            )
            val categoryId = tObj["category_id"]?.jsonPrimitive?.intOrNull ?: 4
            val (catName, catColor) = resolveCategoryMeta(categoryId)
            val tags = parseTagsArray(tObj["tags"] as? JsonArray)
            val lastPostedIso = tObj["bumped_at"]?.jsonPrimitive?.contentOrNull
                ?: tObj["last_posted_at"]?.jsonPrimitive?.contentOrNull
                ?: tObj["created_at"]?.jsonPrimitive?.contentOrNull

            val rawPostsCount = tObj["posts_count"]?.jsonPrimitive?.intOrNull ?: 1
            val rawUnseen = tObj["unseen"]?.jsonPrimitive?.booleanOrNull ?: false
            val rawUnreadPosts = tObj["unread_posts"]?.jsonPrimitive?.intOrNull ?: 0
            val alreadyReadLocally = isTopicRead(id)

            val rawExcerpt = tObj["excerpt"]?.jsonPrimitive?.contentOrNull
            val cleanedExcerpt = extractPlainExcerptFromHtml(rawExcerpt).takeIf { it.isNotBlank() }
                ?.also { topicExcerptCache[id] = it }
                ?: topicExcerptCache[id]?.takeIf { it.isNotBlank() }

            TopicSummary(
                id = id,
                title = title,
                postsCount = rawPostsCount,
                replyCount = tObj["reply_count"]?.jsonPrimitive?.intOrNull ?: 0,
                views = tObj["views"]?.jsonPrimitive?.intOrNull ?: 0,
                likeCount = tObj["like_count"]?.jsonPrimitive?.intOrNull ?: 0,
                categoryId = categoryId,
                categoryName = catName,
                categoryColorHex = catColor,
                tags = tags,
                lastPostedAt = formatRelativeTime(lastPostedIso),
                excerpt = cleanedExcerpt,
                pinned = tObj["pinned"]?.jsonPrimitive?.booleanOrNull ?: false,
                unseen = if (alreadyReadLocally) false else rawUnseen,
                unreadPosts = if (alreadyReadLocally) 0 else rawUnreadPosts,
                hasAcceptedAnswer = tObj["has_accepted_answer"]?.jsonPrimitive?.booleanOrNull ?: false,
                originalPoster = op,
                participantAvatars = posters
            )
        }
    }

    private val topicExcerptCache = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private val inFlightExcerptJobs = java.util.concurrent.ConcurrentHashMap<Int, Deferred<String?>>()
    private val excerptPrefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val excerptFetchSemaphore = Semaphore(8)

    fun hasCachedTopicExcerpt(topicId: Int): Boolean =
        topicId > 0 && topicExcerptCache.containsKey(topicId)

    fun getCachedTopicExcerpt(topicId: Int): String? =
        topicExcerptCache[topicId]?.takeIf { it.isNotBlank() }

    fun cacheTopicExcerpt(topicId: Int, excerpt: String) {
        if (topicId > 0) {
            topicExcerptCache[topicId] = excerpt.trim()
        }
    }

    /**
     * 首屏并发行内预水合（有界等待 420ms 尽量随列表一次性对齐返回）+ 后续条目后台静默预热缓存。
     * 未在 420ms 内完成的条目会在 UI 层由等高 2 行骨架屏无缝承接并在后台继续完成。
     */
    private suspend fun hydrateFirstScreenExcerptsAndPrefetchRest(topics: List<TopicSummary>): List<TopicSummary> {
        val missingTopics = topics.filter { !it.pinned && it.id > 0 && it.excerpt.isNullOrBlank() && !topicExcerptCache.containsKey(it.id) }
        if (missingTopics.isEmpty()) return topics

        val firstScreenIds = missingTopics.take(6).map { it.id }
        val backgroundIds = missingTopics.drop(6).take(12).map { it.id }

        firstScreenIds.forEach { tid -> startExcerptFetchAsync(tid) }
        backgroundIds.forEach { tid -> startExcerptFetchAsync(tid) }

        withTimeoutOrNull(420L) {
            firstScreenIds.mapNotNull { inFlightExcerptJobs[it] }.awaitAll()
        }

        return topics.map { topic ->
            if (!topic.excerpt.isNullOrBlank()) {
                topic
            } else {
                val hydrated = getCachedTopicExcerpt(topic.id)
                if (!hydrated.isNullOrBlank()) topic.copy(excerpt = hydrated) else topic
            }
        }
    }

    private fun startExcerptFetchAsync(topicId: Int): Deferred<String?> {
        inFlightExcerptJobs[topicId]?.let { existing ->
            if (existing.isActive || existing.isCompleted) return existing
        }
        val created = excerptPrefetchScope.async {
            try {
                performTopicExcerptFetch(topicId)
            } finally {
                inFlightExcerptJobs.remove(topicId)
            }
        }
        val prev = inFlightExcerptJobs.putIfAbsent(topicId, created)
        if (prev != null && (prev.isActive || prev.isCompleted)) {
            created.cancel()
            return prev
        }
        return created
    }

    internal fun extractPlainExcerptFromHtml(cookedHtml: String?): String {
        val raw = cookedHtml?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        val hasContentImage = raw.contains("<img", ignoreCase = true) &&
            !Regex("<img[^>]*class\\s*=\\s*[\"'][^\"']*(?:emoji|avatar)[^\"']*[\"']", RegexOption.IGNORE_CASE).containsMatchIn(raw)
        val stripped = raw
            .replace(Regex("<aside[\\s\\S]*?</aside>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<div[^>]*class\\s*=\\s*[\"'][^\"']*meta[^\"']*[\"'][\\s\\S]*?</div>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&hellip;", "...")
            .replace(Regex("\\s+"), " ")
            .trim()
        return when {
            stripped.isNotEmpty() -> if (stripped.length > 160) stripped.take(160) + "..." else stripped
            hasContentImage -> "[图片]"
            else -> ""
        }
    }

    /**
     * 按需静默补全话题一楼纯文本摘要：
     * 优先请求轻量级单帖接口 `/posts/by_number/{topicId}/1.json`（仅约 2KB，比 `/t/{topicId}.json` 小 50 倍且天然不计入阅读），
     * 失败时回退至 `/t/{topicId}.json?track_visit=false`，全程携带 `Discourse-Background: true`。
     */
    suspend fun fetchTopicExcerpt(topicId: Int): String? = withContext(Dispatchers.IO) {
        if (topicId <= 0) return@withContext null
        topicExcerptCache[topicId]?.let { cached ->
            return@withContext cached.takeIf { it.isNotBlank() }
        }
        startExcerptFetchAsync(topicId).await()
    }

    private suspend fun performTopicExcerptFetch(topicId: Int): String? {
        topicExcerptCache[topicId]?.let { cached ->
            return cached.takeIf { it.isNotBlank() }
        }
        return excerptFetchSemaphore.withPermit {
            topicExcerptCache[topicId]?.let { cached ->
                return@withPermit cached.takeIf { it.isNotBlank() }
            }
            val bgHeaders = mapOf("Discourse-Background" to "true")
            val fastUrl = "https://linux.do/posts/by_number/" + topicId.toString() + "/1.json"
            val fastRes = executeHttp(url = fastUrl, method = "GET", extraHeaders = bgHeaders)
            val fastBody = fastRes?.second
            if (fastRes != null && fastRes.first in 200..299 && fastBody != null && fastBody.trimStart().startsWith("{")) {
                val fastExtracted = runCatching {
                    val root = json.parseToJsonElement(fastBody).jsonObject
                    val cooked = root["cooked"]?.jsonPrimitive?.contentOrNull
                    extractPlainExcerptFromHtml(cooked)
                }.getOrDefault("")
                if (fastExtracted.isNotBlank()) {
                    topicExcerptCache[topicId] = fastExtracted
                    return@withPermit fastExtracted
                }
            }

            val fallbackUrl = "https://linux.do/t/" + topicId.toString() + ".json?track_visit=false"
            val res = executeHttp(url = fallbackUrl, method = "GET", extraHeaders = bgHeaders)
            val statusCode = res?.first ?: 0
            val body = res?.second
            if (statusCode in 200..299 && body != null && body.trimStart().startsWith("{")) {
                val extracted = runCatching {
                    val root = json.parseToJsonElement(body).jsonObject
                    val postsArr = (root["post_stream"] as? JsonObject)?.get("posts") as? JsonArray
                    val firstPost = postsArr?.firstOrNull() as? JsonObject
                    val cooked = firstPost?.get("cooked")?.jsonPrimitive?.contentOrNull
                        ?: root["excerpt"]?.jsonPrimitive?.contentOrNull
                    extractPlainExcerptFromHtml(cooked)
                }.getOrDefault("")
                topicExcerptCache[topicId] = extracted
                return@withPermit extracted.takeIf { it.isNotBlank() }
            }
            null
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
        val raw = isoString?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        return try {
            val instant = runCatching { Instant.parse(raw) }.getOrElse {
                java.time.OffsetDateTime.parse(raw).toInstant()
            }
            val now = Instant.now()
            val diffSec = Duration.between(instant, now).seconds.coerceAtLeast(0L)
            when {
                diffSec < 60 -> "1m"
                diffSec < 3600 -> "${(diffSec / 60).coerceAtLeast(1)}m"
                diffSec < 86400 -> "${(diffSec / 3600).coerceAtLeast(1)}h"
                diffSec < 86400 * 30 -> "${(diffSec / 86400).coerceAtLeast(1)}d"
                else -> {
                    val zone = java.time.ZoneId.systemDefault()
                    val targetDate = instant.atZone(zone)
                    val currentYear = now.atZone(zone).year
                    if (targetDate.year == currentYear) {
                        "${targetDate.monthValue}月${targetDate.dayOfMonth}日"
                    } else {
                        "${targetDate.year % 100}年${targetDate.monthValue}月"
                    }
                }
            }
        } catch (_: Throwable) {
            ""
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
        executeHttp(
            url = "https://linux.do/topics/timings",
            method = "POST",
            formParams = params,
            extraHeaders = headers,
            preserveFailure = true
        )?.first
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
                var draftCategoryId: Int? = obj["category_id"]?.jsonPrimitive?.intOrNull
                var draftTags: List<String> = emptyList()
                var replyToPostNumber: Int? = null
                var action = if (draftKey == "new_topic") "createTopic" else if (draftKey.startsWith("edit_post_")) "edit" else "reply"
                var targetTopicId: Int? = null
                var targetPostId: Int? = draftKey.removePrefix("edit_post_").takeIf { draftKey.startsWith("edit_post_") }?.toIntOrNull()
                if (dataStr.trimStart().startsWith("{")) {
                    runCatching {
                        val dObj = json.parseToJsonElement(dataStr).jsonObject
                        if (title.isBlank()) {
                            title = dObj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        }
                        rawReply = dObj["reply"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        if (draftCategoryId == null) {
                            draftCategoryId = dObj["categoryId"]?.jsonPrimitive?.intOrNull
                                ?: dObj["category_id"]?.jsonPrimitive?.intOrNull
                        }
                        draftTags = parseTagsArray(dObj["tags"] as? JsonArray)
                        action = dObj["action"]?.jsonPrimitive?.contentOrNull?.takeUnless { draftKey.startsWith("edit_post_") } ?: action
                        targetTopicId = dObj["topicId"]?.jsonPrimitive?.intOrNull
                        if (action.startsWith("edit")) targetPostId = targetPostId ?: dObj["postId"]?.jsonPrimitive?.intOrNull
                        replyToPostNumber = dObj["replyToPostNumber"]?.jsonPrimitive?.intOrNull
                            ?: if (action == "reply") dObj["postId"]?.jsonPrimitive?.intOrNull else null
                    }
                }
                val topicId = obj["topic_id"]?.jsonPrimitive?.intOrNull
                    ?: targetTopicId
                    ?: draftKey.removePrefix("topic_").toIntOrNull()
                    ?: 0
                val createdAt = formatRelativeTime(obj["created_at"]?.jsonPrimitive?.contentOrNull)
                CloudDraftItem(
                    draftKey = draftKey,
                    sequence = seq,
                    title = title.ifBlank { if (topicId > 0) "回复话题 #$topicId" else "未命名新话题草稿" },
                    excerpt = rawReply.replace(Regex("<[^>]+>"), "").trim(),
                    topicId = topicId,
                    createdAtText = createdAt,
                    raw = rawReply,
                    categoryId = draftCategoryId,
                    tags = draftTags,
                    replyToPostNumber = replyToPostNumber,
                    action = action,
                    postId = targetPostId
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
        requireMutationObject(executeHttp("https://linux.do/drafts/$encoded.json?sequence=$sequence", "DELETE", preserveFailure = true))
        // Discourse deliberately returns success for sequence conflicts; verify the actual outcome.
        val root = readApiObject(executeHttp("https://linux.do/drafts/$encoded.json", preserveFailure = true))
        root["draft"] == null || root["draft"] is JsonNull
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

    data class PollVoteSnapshot(
        val selectedIds: Set<String>,
        val optionVotes: Map<String, Int> = emptyMap(),
        val voters: Int? = null
    )

    private val pollVoteSnapshots = java.util.concurrent.ConcurrentHashMap<String, PollVoteSnapshot>()
    fun getPollVoteSnapshot(postId: Int, pollName: String): PollVoteSnapshot? = pollVoteSnapshots["$postId:$pollName"]
    fun clearPollVoteState() {
        pollVoteSnapshots.clear()
        postReactionSnapshots.clear()
        postBoostSnapshots.clear()
        cachedCsrfToken = null
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
            formParams = params, preserveFailure = true
        )
        val root = requireMutationObject(res)
        val pollObj = root["poll"] as? JsonObject
        val voteArr = root["vote"] as? JsonArray
        val ok = pollObj != null && voteArr != null
        if (ok) {
            val confirmedIds = voteArr!!.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }.toSet().ifEmpty { options.toSet() }
            val votesByOption = (pollObj!!["options"] as? JsonArray)?.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val count = obj["votes"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                id to count
            }?.toMap().orEmpty()
            val votersCount = pollObj["voters"]?.jsonPrimitive?.intOrNull
            pollVoteSnapshots["$postId:$pollName"] = PollVoteSnapshot(confirmedIds, votesByOption, votersCount)
        }
        ok
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
    suspend fun fetchDraftSequence(draftKey: String): Int = withContext(Dispatchers.IO) {
        val key = java.net.URLEncoder.encode(draftKey, "UTF-8")
        val root = readApiObject(executeHttp("https://linux.do/drafts/$key.json", preserveFailure = true))
        root["draft_sequence"]?.jsonPrimitive?.intOrNull ?: throw ApiReadException(ApiFailureKind.InvalidPayload)
    }

    suspend fun saveCloudDraft(
        draftKey: String,
        sequence: Int,
        replyRaw: String,
        title: String = "",
        categoryId: Int? = null,
        tags: List<String> = emptyList(),
        replyToPostNumber: Int? = null,
        action: String = if (draftKey == "new_topic") "createTopic" else "reply",
        topicId: Int? = null,
        postId: Int? = null
    ): Int? = withContext(Dispatchers.IO) {
        val data = buildJsonObject {
            put("reply", replyRaw)
            put("action", action)
            if (title.isNotBlank()) put("title", title)
            categoryId?.let { put("categoryId", it) }
            put("tags", buildJsonArray { tags.forEach { add(JsonPrimitive(it)) } })
            topicId?.let { put("topicId", it) }
            postId?.let { put("postId", it) }
            replyToPostNumber?.let { put("replyToPostNumber", it) }
        }
        val root = requireMutationObject(executeHttp(
            "https://linux.do/drafts.json", "POST", mapOf("draft_key" to draftKey,
                "sequence" to sequence.toString(), "data" to data.toString()), preserveFailure = true
        ))
        root["draft_sequence"]?.jsonPrimitive?.intOrNull ?: throw ApiReadException(ApiFailureKind.InvalidPayload)
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

    /**
     * 获取 Discourse AI 官方话题智能总结 (`GET /discourse-ai/summarization/t/{id}.json`)
     */
    suspend fun fetchTopicAiSummary(topicId: Int): String? = withContext(Dispatchers.IO) {
        if (topicId <= 0) return@withContext null
        val res = executeHttp(
            url = "https://linux.do/discourse-ai/summarization/t/$topicId.json",
            method = "GET"
        ) ?: return@withContext null
        if (res.first !in 200..299 || !res.second.trimStart().startsWith("{")) return@withContext null
        try {
            val root = json.parseToJsonElement(res.second).jsonObject
            val summaryObj = root["ai_topic_summary"] as? JsonObject
            val text = summaryObj?.get("summarized_text")?.jsonPrimitive?.contentOrNull
                ?: root["summarized_text"]?.jsonPrimitive?.contentOrNull
            text?.trim()?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 动态同步站点全量自定义表情包 (`GET /emojis.json`)，将飞书、B站、贴吧等官方分组注入 `LinuxDoEmojiCatalog`
     */
    suspend fun syncSiteEmojis(): Boolean = withContext(Dispatchers.IO) {
        if (LinuxDoEmojiCatalog.hasSyncedServerEmojis()) return@withContext true
        val res = executeHttp(
            url = "https://linux.do/emojis.json",
            method = "GET"
        ) ?: return@withContext false
        if (res.first !in 200..299 || !res.second.trimStart().startsWith("{")) return@withContext false
        try {
            val root = json.parseToJsonElement(res.second).jsonObject
            val parsedGroups = LinkedHashMap<String, List<Pair<String, String>>>()
            for ((groupName, groupElement) in root) {
                val arr = groupElement as? JsonArray ?: continue
                val pairs = arr.mapNotNull { item ->
                    val obj = item as? JsonObject ?: return@mapNotNull null
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim() ?: return@mapNotNull null
                    val url = obj["url"]?.jsonPrimitive?.contentOrNull?.trim() ?: return@mapNotNull null
                    if (name.isNotEmpty() && url.isNotEmpty()) name to url else null
                }
                if (pairs.isNotEmpty()) {
                    parsedGroups[groupName] = pairs
                }
            }
            if (parsedGroups.isNotEmpty()) {
                LinuxDoEmojiCatalog.registerDynamicServerEmojis(parsedGroups)
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 获取发现页「正在热议」真实全站热门话题 (`GET /top.json?period=daily`，不足时回退 `/top.json?period=weekly`)
     */
    suspend fun fetchDiscoverHotTopics(limit: Int = 5): List<TopicSummary> = withContext(Dispatchers.IO) {
        val daily = runCatching {
            fetchTopics(
                path = "/top.json",
                categoryId = null,
                page = 0,
                topPeriod = "daily",
                background = true
            ).filterNot { it.pinned }
        }.getOrDefault(emptyList())
        if (daily.size >= limit) return@withContext daily.take(limit)
        val weekly = runCatching {
            fetchTopics(
                path = "/top.json",
                categoryId = null,
                page = 0,
                topPeriod = "weekly",
                background = true
            ).filterNot { it.pinned }
        }.getOrDefault(emptyList())
        (daily + weekly).distinctBy { it.id }.take(limit)
    }

    /**
     * 保存个人资料与偏好设置 (`PUT /u/{username}.json`)
     */
    suspend fun updateUserProfileInfo(
        username: String,
        displayName: String,
        bioRaw: String,
        title: String,
        location: String = "",
        website: String = "",
        hidePresence: Boolean? = null,
        allowPrivateMessages: Boolean? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val targetUsername = username.ifBlank { UserApiKeyAuthManager.currentUser?.username.orEmpty() }
        if (targetUsername.isBlank() || targetUsername == "linuxdo_user") return@withContext false
        val params = LinkedHashMap<String, String>().apply {
            put("name", displayName.trim())
            put("bio_raw", bioRaw)
            put("title", title.trim())
            put("location", location.trim())
            put("website", website.trim())
            if (hidePresence != null) {
                put("hide_profile_and_presence", hidePresence.toString())
            }
            if (allowPrivateMessages != null) {
                put("allow_private_messages", allowPrivateMessages.toString())
            }
        }
        val res = executeHttp(
            url = "https://linux.do/u/$targetUsername.json",
            method = "PUT",
            formParams = params
        )
        if (res != null && res.first in 200..299) {
            fetchCurrentUserProfile()
            return@withContext true
        }
        false
    }

    /**
     * 读取个人账号与偏好设置 (`GET /u/{username}.json`)
     */
    suspend fun fetchUserAccountPreferences(username: String): UserAccountPreferencesSnapshot? = withContext(Dispatchers.IO) {
        val targetUsername = username.ifBlank { UserApiKeyAuthManager.currentUser?.username.orEmpty() }
        if (targetUsername.isBlank() || targetUsername == "linuxdo_user") return@withContext null
        val res = executeHttp("https://linux.do/u/${targetUsername.trim()}.json", method = "GET") ?: return@withContext null
        if (!res.second.trimStart().startsWith("{")) return@withContext null
        try {
            val root = json.parseToJsonElement(res.second).jsonObject
            val u = root["user"] as? JsonObject ?: return@withContext null
            val uOpt = u["user_option"] as? JsonObject
            val badgeTitles = parseUserBadgesFromRoot(root)
                .filter { it.allowTitle }
                .map { it.name.trim() }
                .filter { it.isNotEmpty() }
            val userAuthTokens = (u["user_auth_tokens"] as? JsonArray)?.size ?: 0
            val associatedAccounts = (u["associated_accounts"] as? JsonArray).orEmpty().mapNotNull { elem ->
                val obj = elem as? JsonObject ?: return@mapNotNull null
                val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val desc = obj["description"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (name.isNotBlank()) "$name${if (desc.isNotBlank()) " ($desc)" else ""}" else null
            }
            UserAccountPreferencesSnapshot(
                username = u["username"]?.jsonPrimitive?.contentOrNull ?: targetUsername,
                displayName = u["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                email = u["email"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                bioRaw = u["bio_raw"]?.jsonPrimitive?.contentOrNull
                    ?: u["bio_excerpt"]?.jsonPrimitive?.contentOrNull?.replace(Regex("<[^>]+>"), "")?.trim().orEmpty(),
                title = u["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                location = u["location"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                website = u["website"]?.jsonPrimitive?.contentOrNull
                    ?: u["website_name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                trustLevel = u["trust_level"]?.jsonPrimitive?.intOrNull ?: 1,
                createdAtText = u["created_at"]?.jsonPrimitive?.contentOrNull.orEmpty().take(10),
                twoFactorEnabled = u["second_factor_enabled"]?.jsonPrimitive?.booleanOrNull == true,
                hideProfileAndPresence = uOpt?.get("hide_profile_and_presence")?.jsonPrimitive?.booleanOrNull == true,
                allowPrivateMessages = uOpt?.get("allow_private_messages")?.jsonPrimitive?.booleanOrNull != false,
                activeDeviceCount = userAuthTokens.coerceAtLeast(1),
                associatedAccounts = associatedAccounts,
                availableTitles = badgeTitles.distinct()
            )
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 获取关注列表或粉丝列表 (`GET /u/{username}/follow/following.json` 或 `followers.json`)
     */
    suspend fun fetchUserFollowList(
        username: String,
        followers: Boolean = false
    ): List<FollowUserEntry> = withContext(Dispatchers.IO) {
        val targetUsername = username.ifBlank { UserApiKeyAuthManager.currentUser?.username.orEmpty() }
        if (targetUsername.isBlank() || targetUsername == "linuxdo_user") return@withContext emptyList()
        val kind = if (followers) "followers" else "following"
        val res = executeHttp("https://linux.do/u/${targetUsername.trim()}/follow/$kind.json", method = "GET")
            ?: return@withContext emptyList()
        val trimmed = res.second.trimStart()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return@withContext emptyList()
        try {
            val elem = json.parseToJsonElement(trimmed)
            val arr: JsonArray = when (elem) {
                is JsonArray -> elem
                is JsonObject -> (elem["users"] as? JsonArray)
                    ?: (elem[kind] as? JsonArray)
                    ?: (elem["follow"] as? JsonArray)
                    ?: JsonArray(emptyList())
                else -> JsonArray(emptyList())
            }
            arr.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val uname = obj["username"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val uid = obj["id"]?.jsonPrimitive?.intOrNull ?: 0
                val dname = obj["name"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { uname } ?: uname
                val tpl = obj["avatar_template"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val avatarUrl = if (tpl.isNotBlank()) {
                    val sized = tpl.replace("{size}", "120")
                    if (sized.startsWith("http")) sized else "https://linux.do$sized"
                } else ""
                val animRaw = obj["animated_avatar"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                val animatedAvatar = animRaw?.let { if (it.startsWith("http")) it else "https://linux.do$it" }
                FollowUserEntry(
                    id = uid,
                    username = uname,
                    displayName = dname,
                    avatarUrl = avatarUrl,
                    animatedAvatar = animatedAvatar,
                    title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    trustLevel = obj["trust_level"]?.jsonPrimitive?.intOrNull ?: 1,
                    isFollowing = !followers || (obj["is_following"]?.jsonPrimitive?.booleanOrNull == true)
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * 获取关注用户的最新动态 (`GET /u/{username}/follow/feed.json`)
     */
    suspend fun fetchUserFollowFeed(username: String, page: Int = 0): List<UserActivityItem> = withContext(Dispatchers.IO) {
        val targetUsername = username.ifBlank { UserApiKeyAuthManager.currentUser?.username.orEmpty() }
        if (targetUsername.isBlank() || targetUsername == "linuxdo_user") return@withContext emptyList()
        val cleanPage = page.coerceAtLeast(0)
        val offsetParam = if (cleanPage > 0) "?offset=${cleanPage * 20}" else ""
        val res = executeHttp("https://linux.do/u/${targetUsername.trim()}/follow/feed.json$offsetParam", method = "GET")
        if (res != null && res.second.trimStart().startsWith("{")) {
            try {
                val root = json.parseToJsonElement(res.second).jsonObject
                val postsArr = (root["posts"] as? JsonArray)
                    ?: (root["user_actions"] as? JsonArray)
                    ?: (root["feed"] as? JsonArray)
                if (postsArr != null && postsArr.isNotEmpty()) {
                    return@withContext postsArr.mapNotNull { elem ->
                        val obj = elem as? JsonObject ?: return@mapNotNull null
                        val tid = obj["topic_id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                        val pnum = obj["post_number"]?.jsonPrimitive?.intOrNull ?: 1
                        val title = obj["title"]?.jsonPrimitive?.contentOrNull
                            ?: (obj["topic"] as? JsonObject)?.get("title")?.jsonPrimitive?.contentOrNull
                            ?: "话题 #$tid"
                        val excerptRaw = obj["excerpt"]?.jsonPrimitive?.contentOrNull
                            ?: obj["cooked"]?.jsonPrimitive?.contentOrNull
                            ?: ""
                        val excerpt = excerptRaw.replace(Regex("<[^>]+>"), "").trim()
                        val actorObj = obj["user"] as? JsonObject
                        val actorName = obj["username"]?.jsonPrimitive?.contentOrNull
                            ?: actorObj?.get("username")?.jsonPrimitive?.contentOrNull
                            ?: ""
                        val catId = obj["category_id"]?.jsonPrimitive?.intOrNull ?: 4
                        val (catName, _) = resolveCategoryMeta(catId)
                        val createdAt = obj["created_at"]?.jsonPrimitive?.contentOrNull.orEmpty().take(10)
                        UserActivityItem(
                            id = obj["id"]?.jsonPrimitive?.intOrNull ?: tid,
                            topicId = tid,
                            postNumber = pnum,
                            title = if (actorName.isNotBlank()) "[$actorName] $title" else title,
                            excerpt = excerpt,
                            actionType = if (pnum <= 1) 4 else 5,
                            createdAtText = createdAt,
                            categoryName = catName
                        )
                    }
                }
            } catch (_: Throwable) {
            }
        }
        val fallbackTopics = runCatching {
            fetchTopics(path = "/follow.json", categoryId = null, page = cleanPage)
        }.getOrDefault(emptyList())
        fallbackTopics.map { t ->
            UserActivityItem(
                id = t.id,
                topicId = t.id,
                postNumber = 1,
                title = t.title,
                excerpt = t.excerpt.orEmpty(),
                actionType = 4,
                createdAtText = t.lastPostedAt,
                categoryName = t.categoryName
            )
        }
    }

    /**
     * 获取社区积分与活跃排行榜 (`GET /directory_items.json?period={period}&order=likes_received&page={page}`)
     */
    suspend fun fetchCommunityLeaderboard(period: String = "weekly", page: Int = 0): List<LeaderboardUserEntry> = withContext(Dispatchers.IO) {
        val cleanPage = page.coerceAtLeast(0)
        val res = executeHttp(
            url = "https://linux.do/directory_items.json?period=$period&order=likes_received&page=$cleanPage",
            method = "GET"
        ) ?: return@withContext emptyList()
        if (!res.second.trimStart().startsWith("{")) return@withContext emptyList()
        try {
            val root = json.parseToJsonElement(res.second).jsonObject
            val arr = root["directory_items"] as? JsonArray ?: return@withContext emptyList()
            arr.mapIndexedNotNull { idx, elem ->
                val obj = elem as? JsonObject ?: return@mapIndexedNotNull null
                val u = obj["user"] as? JsonObject ?: return@mapIndexedNotNull null
                val uname = u["username"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
                val uid = u["id"]?.jsonPrimitive?.intOrNull ?: obj["id"]?.jsonPrimitive?.intOrNull ?: idx
                val dname = u["name"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { uname } ?: uname
                val tpl = u["avatar_template"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val avatarUrl = if (tpl.isNotBlank()) {
                    val sized = tpl.replace("{size}", "120")
                    if (sized.startsWith("http")) sized else "https://linux.do$sized"
                } else ""
                val likesReceived = obj["likes_received"]?.jsonPrimitive?.intOrNull ?: 0
                val likesGiven = obj["likes_given"]?.jsonPrimitive?.intOrNull ?: 0
                val postCount = obj["post_count"]?.jsonPrimitive?.intOrNull ?: 0
                val topicCount = obj["topic_count"]?.jsonPrimitive?.intOrNull ?: 0
                val daysVisited = obj["days_visited"]?.jsonPrimitive?.intOrNull ?: 0
                val cheers = obj["cheers"]?.jsonPrimitive?.intOrNull
                    ?: obj["gamification_score"]?.jsonPrimitive?.intOrNull
                    ?: (likesReceived * 5 + postCount * 2 + topicCount * 5)
                LeaderboardUserEntry(
                    rank = cleanPage * 50 + idx + 1,
                    id = uid,
                    username = uname,
                    displayName = dname,
                    avatarUrl = avatarUrl,
                    title = u["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    likesReceived = likesReceived,
                    likesGiven = likesGiven,
                    postCount = postCount,
                    topicCount = topicCount,
                    daysVisited = daysVisited,
                    score = cheers
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * 获取关于社区及管理团队信息 (`GET /about.json`)
     */
    suspend fun fetchCommunityAboutInfo(): CommunityAboutData? = withContext(Dispatchers.IO) {
        val res = executeHttp("https://linux.do/about.json", method = "GET") ?: return@withContext null
        if (!res.second.trimStart().startsWith("{")) return@withContext null
        try {
            val root = json.parseToJsonElement(res.second).jsonObject
            val about = root["about"] as? JsonObject ?: return@withContext null
            val stats = about["stats"] as? JsonObject
            val usersById = LinkedHashMap<Int, FollowUserEntry>()
            fun parseUserObj(u: JsonObject): FollowUserEntry? {
                val uid = u["id"]?.jsonPrimitive?.intOrNull ?: return null
                val uname = u["username"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                val dname = u["name"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { uname } ?: uname
                val tpl = u["avatar_template"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val avatarUrl = if (tpl.isNotBlank()) {
                    val sized = tpl.replace("{size}", "120")
                    if (sized.startsWith("http")) sized else "https://linux.do$sized"
                } else ""
                val animRaw = u["animated_avatar"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                return FollowUserEntry(
                    id = uid,
                    username = uname,
                    displayName = dname,
                    avatarUrl = avatarUrl,
                    animatedAvatar = animRaw?.let { if (it.startsWith("http")) it else "https://linux.do$it" },
                    title = u["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    trustLevel = u["trust_level"]?.jsonPrimitive?.intOrNull ?: 4
                )
            }
            (root["users"] as? JsonArray)?.forEach { elem ->
                (elem as? JsonObject)?.let(::parseUserObj)?.let { usersById[it.id] = it }
            }
            val admins = buildList {
                (about["admins"] as? JsonArray)?.forEach { elem ->
                    (elem as? JsonObject)?.let(::parseUserObj)?.let { add(it) }
                }
                if (isEmpty()) {
                    (about["admin_ids"] as? JsonArray)?.forEach { idElem ->
                        idElem.jsonPrimitive.intOrNull?.let { usersById[it] }?.let { add(it) }
                    }
                }
            }.distinctBy { it.username }
            val moderators = buildList {
                (about["moderators"] as? JsonArray)?.forEach { elem ->
                    (elem as? JsonObject)?.let(::parseUserObj)?.let { add(it) }
                }
                if (isEmpty()) {
                    (about["moderator_ids"] as? JsonArray)?.forEach { idElem ->
                        idElem.jsonPrimitive.intOrNull?.let { usersById[it] }?.let { add(it) }
                    }
                }
            }.distinctBy { it.username }
            CommunityAboutData(
                title = about["title"]?.jsonPrimitive?.contentOrNull ?: "LINUX DO",
                description = about["description"]?.jsonPrimitive?.contentOrNull
                    ?: "新的理想型社区 · 真诚、友善、团结、专业",
                topicCount = stats?.get("topics_count")?.jsonPrimitive?.intOrNull
                    ?: stats?.get("topic_count")?.jsonPrimitive?.intOrNull ?: 0,
                postCount = stats?.get("posts_count")?.jsonPrimitive?.intOrNull
                    ?: stats?.get("post_count")?.jsonPrimitive?.intOrNull ?: 0,
                userCount = stats?.get("users_count")?.jsonPrimitive?.intOrNull
                    ?: stats?.get("user_count")?.jsonPrimitive?.intOrNull ?: 0,
                activeUsers7Days = stats?.get("active_users_7_days")?.jsonPrimitive?.intOrNull ?: 0,
                activeUsers30Days = stats?.get("active_users_30_days")?.jsonPrimitive?.intOrNull ?: 0,
                likeCount = stats?.get("likes_count")?.jsonPrimitive?.intOrNull
                    ?: stats?.get("like_count")?.jsonPrimitive?.intOrNull ?: 0,
                admins = admins,
                moderators = moderators
            )
        } catch (_: Throwable) {
            null
        }
    }
}

data class UserAccountPreferencesSnapshot(
    val username: String,
    val displayName: String,
    val email: String,
    val bioRaw: String,
    val title: String,
    val location: String,
    val website: String,
    val trustLevel: Int,
    val createdAtText: String,
    val twoFactorEnabled: Boolean,
    val hideProfileAndPresence: Boolean,
    val allowPrivateMessages: Boolean,
    val activeDeviceCount: Int = 1,
    val associatedAccounts: List<String> = emptyList(),
    val availableTitles: List<String> = emptyList()
)

data class FollowUserEntry(
    val id: Int,
    val username: String,
    val displayName: String,
    val avatarUrl: String,
    val animatedAvatar: String? = null,
    val title: String = "",
    val trustLevel: Int = 1,
    val isFollowing: Boolean = false
)

data class LeaderboardUserEntry(
    val rank: Int,
    val id: Int,
    val username: String,
    val displayName: String,
    val avatarUrl: String,
    val title: String = "",
    val likesReceived: Int = 0,
    val likesGiven: Int = 0,
    val postCount: Int = 0,
    val topicCount: Int = 0,
    val daysVisited: Int = 0,
    val score: Int = 0
)

data class CommunityAboutData(
    val title: String,
    val description: String,
    val topicCount: Int,
    val postCount: Int,
    val userCount: Int,
    val activeUsers7Days: Int,
    val activeUsers30Days: Int,
    val likeCount: Int,
    val admins: List<FollowUserEntry>,
    val moderators: List<FollowUserEntry>
)

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
    val createdAtText: String,
    val raw: String = "",
    val categoryId: Int? = null,
    val tags: List<String> = emptyList(),
    val replyToPostNumber: Int? = null,
    val action: String = "reply",
    val postId: Int? = null,
    val sequenceKnown: Boolean = true
)
