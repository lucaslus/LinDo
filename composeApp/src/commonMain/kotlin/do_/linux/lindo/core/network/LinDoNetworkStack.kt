package do_.linux.lindo.core.network

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import do_.linux.lindo.core.runUiSafeStateWrite
import do_.linux.lindo.core.copy.CloudDraftKind
import do_.linux.lindo.core.copy.DiscourseNotificationKind
import do_.linux.lindo.core.copy.DiscourseUserActionKind
import do_.linux.lindo.core.copy.LinDoAuthCopy
import do_.linux.lindo.core.copy.LinDoCommonCopy
import do_.linux.lindo.core.copy.LinDoNetworkCopy
import do_.linux.lindo.core.copy.SmallActionKind
import do_.linux.lindo.core.copy.TopicNotificationLevel
import do_.linux.lindo.core.diagnostics.DiagnosticCategory
import do_.linux.lindo.core.diagnostics.LinDoDiagnosticLogger
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
import do_.linux.lindo.core.model.PrivateMessageSendResult
import do_.linux.lindo.core.model.SearchPageResult
import do_.linux.lindo.core.model.TopicDetailModel
import do_.linux.lindo.core.model.TopicSummary
import do_.linux.lindo.core.model.TopicUser
import do_.linux.lindo.core.model.UserActivityItem
import do_.linux.lindo.core.model.UserFullProfileSummary
import do_.linux.lindo.core.model.UserNotificationItem
import do_.linux.lindo.core.model.UserStatus
import do_.linux.lindo.core.model.decodeDiscourseHtmlSnippet
import do_.linux.lindo.core.site.LinuxDoEmojiCatalog
import do_.linux.lindo.core.site.LinuxDoSiteRules
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
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
import kotlinx.coroutines.launch
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
    val cfTrustCookieNames = setOf("cf_clearance", "__cf_bm", "_cfuvid", "cf_chl_rc_i", "cf_chl_rc_ni")
    private val store = LinkedHashMap<CookieQuadrupleKey, StoredCookieRecord>()
    private val sessionRefreshMutex = Mutex()

    @Synchronized
    fun putCookie(record: StoredCookieRecord) {
        if (record.value.isBlank() || record.isExpired()) {
            val normalizedDomain = record.key.domain.trim().lowercase().trimStart('.')
            val toRemove = store.keys.filter {
                it.name == record.key.name && matchesDomain(it, normalizedDomain)
            }
            toRemove.forEach { store.remove(it) }
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
        targetHost: String = "linux.do",
        includeCloudflareCookies: Boolean = UserApiKeyAuthManager.webViewHttpExecutor == null &&
            !UserApiKeyAuthManager.requireBrowserTransportOnly
    ): String {
        return resolveWinningCookies(nowMs, targetHost)
            .entries
            .filterNot { it.key == "_t" && it.value.value == "demo_session_token" }
            .filter { includeCloudflareCookies || it.key !in cfTrustCookieNames }
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
                if (name.isNotEmpty()) {
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

    /**
     * 增量同步系统 WebView `CookieManager.getCookie("https://linux.do")` 中的最新凭证（如轮换后的 `_t`、`_forum_session` 及最新 `cf_clearance`）：
     * 严禁在 `CookieManager` 瞬态返回空串（如渲染进程切换、SQLite 尚未加载完成）或瞬时漏读 HttpOnly Cookie 时误删内存 `_t` 导致自动退出登录；
     * 显式退出与经服务端双次确认的会话失效均由 `clearAuthCookiesOnly()` 专门清理。
     */
    fun syncFromAuthoritativeBrowserCookieString(
        rawCookieHeader: String?,
        domain: String = "linux.do"
    ) {
        if (rawCookieHeader.isNullOrBlank()) return
        ingestRawCookieString(rawCookieHeader, domain = domain, hostOnly = false)
        // 当完整浏览器快照携带有效 `_t` 会话时，同步淘汰浏览器侧已过期的 Cloudflare 信任 Cookie，防止旧 cf_clearance 被持久化并在冷启动时回灌
        val presentCookies = LinkedHashMap<String, String>()
        rawCookieHeader.split(";").forEach { segment ->
            val trimmed = segment.trim()
            val eqIdx = trimmed.indexOf('=')
            if (eqIdx > 0) {
                val name = trimmed.substring(0, eqIdx).trim()
                val value = trimmed.substring(eqIdx + 1).trim()
                if (name.isNotEmpty()) {
                    presentCookies[name] = value
                }
            }
        }
        val tVal = presentCookies["_t"]
        if (!tVal.isNullOrBlank() && tVal != "demo_session_token") {
            for (cfName in cfTrustCookieNames) {
                if (cfName !in presentCookies) {
                    removeCookieByName(cfName, domain = domain)
                }
            }
        }
    }

    @Synchronized
    fun removeCookieByName(name: String, domain: String = "linux.do") {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) return
        val normalizedDomain = domain.trim().lowercase().trimStart('.')
        val toRemove = store.keys.filter {
            it.name == cleanName && matchesDomain(it, normalizedDomain)
        }
        toRemove.forEach { store.remove(it) }
    }

    fun ingestSetCookieHeader(
        rawSetCookie: String?,
        domain: String = "linux.do"
    ) {
        if (rawSetCookie.isNullOrBlank()) return
        val segments = rawSetCookie.split(';')
        val firstPair = segments.firstOrNull()?.trim().orEmpty()
        val eqIdx = firstPair.indexOf('=')
        if (eqIdx <= 0) return
        val name = firstPair.substring(0, eqIdx).trim()
        if (name.isEmpty()) return
        val value = firstPair.substring(eqIdx + 1).trim()
        var effectiveDomain = domain.trim().lowercase().trimStart('.')
        var hasExplicitDomain = false
        var isExpiredDeletion = value.isEmpty() || value.equals("deleted", ignoreCase = true)
        for (i in 1 until segments.size) {
            val attr = segments[i].trim()
            val attrEq = attr.indexOf('=')
            if (attrEq > 0) {
                val attrName = attr.substring(0, attrEq).trim().lowercase()
                val attrVal = attr.substring(attrEq + 1).trim().trim('"')
                when (attrName) {
                    "domain" -> if (attrVal.isNotEmpty()) {
                        effectiveDomain = attrVal.lowercase().trimStart('.')
                        hasExplicitDomain = true
                    }
                    "max-age" -> {
                        val maxAge = attrVal.toLongOrNull()
                        if (maxAge != null && maxAge <= 0L) {
                            isExpiredDeletion = true
                        }
                    }
                    "expires" -> {
                        if (attrVal.contains("1970")) {
                            isExpiredDeletion = true
                        }
                    }
                }
            }
        }
        if (isExpiredDeletion) {
            removeCookieByName(name, effectiveDomain)
            val fallbackDomain = domain.trim().lowercase().trimStart('.')
            if (effectiveDomain != fallbackDomain) {
                removeCookieByName(name, fallbackDomain)
            }
            return
        }
        val discourseHostOnlyNames = setOf("_t", "_forum_session")
        val effectiveHostOnly = !hasExplicitDomain || name in discourseHostOnlyNames
        putCookie(
            StoredCookieRecord(
                key = CookieQuadrupleKey(
                    name = name,
                    domain = effectiveDomain,
                    path = "/",
                    hostOnly = effectiveHostOnly
                ),
                value = value,
                updatedAtEpochMs = System.currentTimeMillis()
            )
        )
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
        val keysToRemove = store.keys.filter { it.name !in cfTrustCookieNames }
        keysToRemove.forEach { store.remove(it) }
    }
}

internal fun ingestSetCookieHeader(rawSetCookie: String, domain: String = "linux.do") {
    CookieSentinel.ingestSetCookieHeader(rawSetCookie, domain)
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
    @Volatile
    private var pendingRequestedScopes: String = "one_time_password"
    fun pendingAuthorizationCreatedAt(): Long = pendingAuthCreatedAt

    @Volatile
    var currentUser: AuthenticatedUserProfile? = null
        private set

    @Volatile
    var fullProfileCache: UserFullProfileSummary? = null

    @Volatile
    var cachedUnreadNotificationCount: Int = 0

    @Volatile
    var pendingOtpToken: String? = null

    @Volatile
    var customUserAgent: String? = null

    @Volatile
    var uiFontScale: Float = 1f

    @Volatile
    var uiDarkTheme: Boolean = false

    @Volatile
    var uiEnableAnimations: Boolean = true

    @Volatile
    var uiThemeAccent: String = "OFFICIAL_GOLD"

    @Volatile
    var uiCompactFeed: Boolean = false

    @Volatile
    var uiHomeSearchExpanded: Boolean = true

    @Volatile
    var uiSplitFraction: Float = 0.42f

    var onAuthStateChanged: (() -> Unit)? = null
    var onUnreadCountChanged: ((Int) -> Unit)? = null
    var onNetworkRestored: (() -> Unit)? = null
    var onPrepareManualRecovery: (suspend () -> Unit)? = null
    var openExternalUrlLauncher: ((String) -> Unit)? = null
    var onLogoutPlatformClear: (() -> Unit)? = null
    var onPersistSession: ((AuthenticatedUserProfile?, String?) -> Unit)? = null
    var onPersistPendingKey: ((String?, String?) -> Unit)? = null
    var onPersistUiPrefs: ((Float, Boolean, Boolean) -> Unit)? = null
    var onPersistHomeSearchExpanded: ((Boolean) -> Unit)? = null
    var onPersistThemeAndDensity: ((String, Boolean) -> Unit)? = null
    var onPersistSplitFraction: ((Float) -> Unit)? = null
    var onPersistReadTopics: ((String) -> Unit)? = null
    var onSyncCookieToPlatform: ((String) -> Unit)? = null

    fun updateHomeSearchExpanded(expanded: Boolean) {
        uiHomeSearchExpanded = expanded
        onPersistHomeSearchExpanded?.invoke(expanded)
    }

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

    fun updateSplitFractionPref(fraction: Float) {
        val safe = if (fraction.isNaN() || fraction.isInfinite()) 0.42f else fraction.coerceIn(0.28f, 0.62f)
        uiSplitFraction = safe
        onPersistSplitFraction?.invoke(safe)
    }

    fun notifyNetworkRestored() {
        if (!NetworkRecoveryPolicy.enabled) LinuxDoRepository.clearTransientRateLimit()
        onNetworkRestored?.invoke()
    }
    data class BinaryHttpPayload(
        val status: Int,
        val contentType: String,
        val bytes: ByteArray
    )

    @Volatile
    var requireBrowserTransportOnly: Boolean = false

    var webViewJsonFetcher: (suspend (String) -> String?)? = null
    var webViewBinaryFetcher: (suspend (String) -> BinaryHttpPayload?)? = null
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

    private val invalidatingServerAuth = java.util.concurrent.atomic.AtomicBoolean(false)

    fun setApiKey(apiKey: String?) {
        activeUserApiKey = apiKey?.trim()?.takeIf { it.isNotEmpty() }
        onPersistSession?.invoke(currentUser, activeUserApiKey)
        onAuthStateChanged?.invoke()
    }

    @Volatile
    internal var authStateNotifyEpoch: Long = 0L

    internal fun clearApiKeyQuietly() {
        activeUserApiKey = null
    }

    internal fun restoreApiKeyQuietly(apiKey: String?) {
        activeUserApiKey = apiKey?.trim()?.takeIf { it.isNotEmpty() }
    }

    internal fun persistSessionQuietly() {
        onPersistSession?.invoke(currentUser, activeUserApiKey)
    }

    fun getApiKey(): String? = activeUserApiKey

    fun updateCachedUnreadNotifications(count: Int) {
        val safeCount = count.coerceAtLeast(0)
        cachedUnreadNotificationCount = safeCount
        fullProfileCache = fullProfileCache?.copy(unreadNotifications = safeCount)
        onUnreadCountChanged?.invoke(safeCount)
    }

    internal fun onBrowserAuthCookieEvicted() {
        if (!activeUserApiKey.isNullOrBlank()) return
        if (currentUser == null && fullProfileCache == null && cachedUnreadNotificationCount == 0) return
        sessionGeneration++
        currentUser = null
        fullProfileCache = null
        cachedUnreadNotificationCount = 0
        onUnreadCountChanged?.invoke(0)
        LinuxDoRepository.clearUserSessionCaches()
        LinuxDoRepository.updateCsrfToken(null)
        onPersistSession?.invoke(null, null)
        onAuthStateChanged?.invoke()
    }

    internal suspend fun <T> withServerAuthInvalidationSuppressed(block: suspend () -> T): T {
        val acquired = invalidatingServerAuth.compareAndSet(false, true)
        return try {
            block()
        } finally {
            if (acquired) {
                invalidatingServerAuth.set(false)
            }
        }
    }

    fun handleServerAuthInvalidated() {
        if (!isAuthorized()) return
        if (!invalidatingServerAuth.compareAndSet(false, true)) return
        try {
            logout()
        } finally {
            invalidatingServerAuth.set(false)
        }
    }

    internal fun hasValidWebSessionCookie(): Boolean {
        val rawT = CookieSentinel.resolveWinningCookies(targetHost = "linux.do")["_t"]?.value.orEmpty()
        return rawT.isNotBlank() && rawT != "demo_session_token"
    }

    fun onSessionAuthenticated(
        profile: AuthenticatedUserProfile,
        rawCookies: String? = null
    ) {
        val prevUsername = currentUser?.username
        if (prevUsername.isNullOrBlank() || !prevUsername.equals(profile.username, ignoreCase = true)) {
            sessionGeneration++
            fullProfileCache = null
            cachedUnreadNotificationCount = 0
            onUnreadCountChanged?.invoke(0)
            LinuxDoRepository.clearUserSessionCaches()
        }
        if (!rawCookies.isNullOrBlank()) {
            CookieSentinel.ingestRawCookieString(rawCookies, domain = "linux.do", hostOnly = false)
        }
        if (hasValidWebSessionCookie()) {
            activeUserApiKey = null
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
        val prevUsername = currentUser?.username
        val accountChanged = prevUsername.isNullOrBlank() || !prevUsername.equals(username, ignoreCase = true)
        if (accountChanged) {
            sessionGeneration++
            fullProfileCache = null
            cachedUnreadNotificationCount = 0
            onUnreadCountChanged?.invoke(0)
            LinuxDoRepository.clearUserSessionCaches()
        }
        if (hasValidWebSessionCookie()) {
            activeUserApiKey = null
        }
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
        if (accountChanged) {
            authStateNotifyEpoch++
            onAuthStateChanged?.invoke()
        }
    }

    fun restorePersistedSession(
        profile: AuthenticatedUserProfile?,
        apiKey: String?,
        rawCookies: String?
    ) {
        if (!rawCookies.isNullOrBlank()) {
            CookieSentinel.ingestRawCookieString(rawCookies, domain = "linux.do", hostOnly = false)
        }
        val hasWebCookie = hasValidWebSessionCookie()
        activeUserApiKey = if (hasWebCookie) null else apiKey?.trim()?.takeIf { it.isNotEmpty() }
        // 过滤掉旧版曾写入的假占位用户名，确保只保留真实账号
        currentUser = profile?.takeIf { it.username != "linuxdo_user" }
        onAuthStateChanged?.invoke()
    }

    fun isAuthorized(): Boolean {
        val realUser = currentUser?.takeIf { it.username != "linuxdo_user" }
        return realUser != null ||
            hasValidWebSessionCookie() ||
            !activeUserApiKey.isNullOrBlank()
    }

    fun logout() {
        sessionGeneration++
        activeUserApiKey = null
        currentUser = null
        fullProfileCache = null
        cachedUnreadNotificationCount = 0
        pendingOtpToken = null
        pendingPrivateKey = null
        pendingNonce = null
        pendingAuthCreatedAt = 0L
        pendingRequestedScopes = "one_time_password"
        do_.linux.lindo.core.compose.LocalComposerDrafts.clearAll()
        LinuxDoRepository.clearUserSessionCaches()
        LinuxDoRepository.updateCsrfToken(null)
        onPersistPendingKey?.invoke(null, null)
        CookieSentinel.clearAuthCookiesOnly()
        val platformClear = onLogoutPlatformClear
        if (platformClear != null) platformClear.invoke() else onPersistSession?.invoke(null, null)
        onUnreadCountChanged?.invoke(0)
        onAuthStateChanged?.invoke()
    }

    /**
     * 生成对齐 Linux.do 服务端白名单的浏览器一键授权链接：
     * - `scopes=one_time_password`（Linux.do 站点管理员禁用了第三方 `read,write` User-Api-Key 签发，仅开放 `one_time_password` 一次性兑换令牌）
     * - `auth_redirect=discourse://auth_redirect`（Discourse 官方白名单回跳协议）
     * - `otp=true`（随回调返回一次性登录兑换令牌，兑换后获得真实 `_t` 会话 Cookie）
     */
    fun generateAuthorizationUrl(
        applicationName: String = "Lindo",
        scopes: String = "one_time_password"
    ): Pair<String, String> {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()
        pendingPrivateKey = kp.private
        pendingRequestedScopes = scopes
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
        val (url, _) = generateAuthorizationUrl(scopes = "one_time_password")
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
            val encryptedOtp = (params["oneTimePassword"] ?: params["one_time_password"])?.takeIf { it.isNotBlank() }
            val otp = if (encryptedOtp != null) {
                decryptRsaBase64(encryptedOtp, privateKey)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: if (key == null) return false else null
            } else {
                obj["one_time_password"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            }
            if (key == null && otp.isNullOrBlank()) return false
            val keyHasApiRoutePermissions = pendingRequestedScopes
                .split(',')
                .map { it.trim().lowercase() }
                .any { it == "read" || it == "write" }
            // Consume before notifying observers so a duplicate deep link cannot replay the grant.
            pendingPrivateKey = null
            pendingNonce = null
            pendingAuthCreatedAt = 0L
            pendingRequestedScopes = "one_time_password"
            onPersistPendingKey?.invoke(null, null)
            if (key != null && (otp.isNullOrBlank() || keyHasApiRoutePermissions)) {
                activeUserApiKey = key
            }
            if (!otp.isNullOrBlank()) pendingOtpToken = otp
            onPersistSession?.invoke(currentUser, activeUserApiKey)
            LinDoDiagnosticLogger.info(DiagnosticCategory.AUTH, "BrowserAuth", "Callback accepted; OTP present=${!otp.isNullOrBlank()}")
            onAuthStateChanged?.invoke()
            true
        }.getOrDefault(false)
    }

}

/**
 * Linux.do 板块元数据定义（支持一级主版块与 Lv1/Lv2/Lv3 二级子版块）
 */
data class LinuxDoCategoryMeta(
    val id: Int,
    val name: String,
    val slug: String,
    val colorHex: String,
    val description: String,
    val icon: String? = null,
    val readRestricted: Boolean = false,
    val parentId: Int? = null,
    val parentSlug: String? = null,
    val trustLevel: Int? = null,
    val subLabel: String? = null,
    val isVerifiedFromServer: Boolean = true,
    val hasServerParent: Boolean = false
) {
    val displayName: String
        get() = name.replace(Regex(",\\s*"), " · ")
}

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
    if (status !in 200..299 || looksLikeCloudflareChallenge(body)) return false
    val trimmed = body.trimStart()
    return trimmed.isEmpty() ||
        trimmed.startsWith("{") ||
        trimmed.startsWith("[") ||
        trimmed.equals("ok", ignoreCase = true)
}

internal data class ParsedManualAuthInput(
    val callbackUrl: String? = null,
    val cookieHeader: String? = null,
    val userAgent: String? = null,
    val bareToken: String? = null
)

private fun tokenizeCurlCommand(rawCmd: String): List<String> {
    var normalized = rawCmd
        .replace(Regex("""\\[ \t]*\r?\n"""), " ")
        .replace(Regex("""`[ \t]*\r?\n"""), " ")
    val looksLikeCmdCaretCurl = normalized.contains("^\"") || Regex("""\^[ \t]*\r?\n""").containsMatchIn(normalized)
    if (looksLikeCmdCaretCurl) {
        normalized = normalized.replace(Regex("""\^[ \t]*\r?\n"""), " ")
        val sb = StringBuilder(normalized.length)
        var idx = 0
        while (idx < normalized.length) {
            val ch = normalized[idx]
            if (ch == '^' && idx + 1 < normalized.length) {
                sb.append(normalized[idx + 1])
                idx += 2
            } else {
                sb.append(ch)
                idx++
            }
        }
        normalized = sb.toString()
    }

    val tokens = ArrayList<String>()
    val current = StringBuilder()
    var hasTokenChars = false
    var i = 0
    while (i < normalized.length) {
        val c = normalized[i]
        when {
            c.isWhitespace() -> {
                if (hasTokenChars) {
                    tokens.add(current.toString())
                    current.setLength(0)
                    hasTokenChars = false
                }
                i++
            }
            c == '$' && i + 1 < normalized.length && normalized[i + 1] == '\'' -> {
                hasTokenChars = true
                i += 2
                while (i < normalized.length && normalized[i] != '\'') {
                    if (normalized[i] == '\\' && i + 1 < normalized.length) {
                        val esc = normalized[i + 1]
                        when {
                            esc == 'n' -> { current.append('\n'); i += 2 }
                            esc == 'r' -> { current.append('\r'); i += 2 }
                            esc == 't' -> { current.append('\t'); i += 2 }
                            esc == 'x' && i + 3 < normalized.length -> {
                                val hex = normalized.substring(i + 2, i + 4).toIntOrNull(16)
                                if (hex != null) {
                                    current.append(hex.toChar())
                                    i += 4
                                } else {
                                    current.append(esc)
                                    i += 2
                                }
                            }
                            else -> { current.append(esc); i += 2 }
                        }
                    } else {
                        current.append(normalized[i])
                        i++
                    }
                }
                if (i < normalized.length && normalized[i] == '\'') i++
            }
            c == '\'' -> {
                hasTokenChars = true
                i++
                while (i < normalized.length && normalized[i] != '\'') {
                    current.append(normalized[i])
                    i++
                }
                if (i < normalized.length && normalized[i] == '\'') i++
            }
            c == '"' -> {
                hasTokenChars = true
                i++
                while (i < normalized.length && normalized[i] != '"') {
                    if (normalized[i] == '\\' && i + 1 < normalized.length) {
                        val esc = normalized[i + 1]
                        if (esc == '"' || esc == '\\' || esc == '$' || esc == '`') {
                            current.append(esc)
                        } else {
                            current.append('\\').append(esc)
                        }
                        i += 2
                    } else {
                        current.append(normalized[i])
                        i++
                    }
                }
                if (i < normalized.length && normalized[i] == '"') i++
            }
            c == '\\' && i + 1 < normalized.length -> {
                hasTokenChars = true
                current.append(normalized[i + 1])
                i += 2
            }
            else -> {
                hasTokenChars = true
                current.append(c)
                i++
            }
        }
    }
    if (hasTokenChars) {
        tokens.add(current.toString())
    }
    return tokens
}

internal fun parsePastedAuthOrCurlInput(rawInput: String): ParsedManualAuthInput {
    val cleaned = rawInput
        .replace("\u200B", "")
        .replace("\uFEFF", "")
        .trim()
    if (cleaned.isEmpty()) return ParsedManualAuthInput()

    // 1. Callback URL: discourse://auth_redirect?... or lindo://auth_redirect?...
    val callbackMatch = Regex("""(?i)\b((?:discourse|lindo)://auth_redirect[^\s"'`<>]*)""").find(cleaned)
    if (callbackMatch != null) {
        return ParsedManualAuthInput(callbackUrl = callbackMatch.groupValues[1].trim())
    }

    // 2. Browser DevTools Copy as cURL (bash / cmd / PowerShell) or PowerShell Invoke-WebRequest
    val lowerStart = cleaned.lowercase()
    val looksLikeCurl = lowerStart.startsWith("curl ") ||
        lowerStart.startsWith("curl\t") ||
        lowerStart.startsWith("curl\n") ||
        lowerStart.startsWith("curl\r") ||
        lowerStart.startsWith("curl.exe ") ||
        lowerStart.startsWith("curl\"") ||
        lowerStart.startsWith("curl'") ||
        Regex("""(?:^|\s)(?:-H|--header|-b|--cookie)\s""").containsMatchIn(cleaned)
    if (looksLikeCurl) {
        val tokens = tokenizeCurlCommand(cleaned)
        val cookieParts = ArrayList<String>()
        var extractedUa: String? = null
        var i = 0
        while (i < tokens.size) {
            val tok = tokens[i]
            when {
                tok == "-H" || tok == "--header" -> {
                    val headerVal = tokens.getOrNull(i + 1).orEmpty()
                    i += 2
                    val colonIdx = headerVal.indexOf(':')
                    if (colonIdx > 0) {
                        val hName = headerVal.substring(0, colonIdx).trim().lowercase()
                        val hValue = headerVal.substring(colonIdx + 1).trim()
                        if (hName == "cookie" && hValue.isNotEmpty()) {
                            cookieParts.add(hValue)
                        } else if (hName == "user-agent" && hValue.isNotEmpty()) {
                            extractedUa = hValue
                        }
                    }
                }
                tok.startsWith("--header=") -> {
                    val headerVal = tok.removePrefix("--header=")
                    val colonIdx = headerVal.indexOf(':')
                    if (colonIdx > 0) {
                        val hName = headerVal.substring(0, colonIdx).trim().lowercase()
                        val hValue = headerVal.substring(colonIdx + 1).trim()
                        if (hName == "cookie" && hValue.isNotEmpty()) {
                            cookieParts.add(hValue)
                        } else if (hName == "user-agent" && hValue.isNotEmpty()) {
                            extractedUa = hValue
                        }
                    }
                    i++
                }
                tok == "-b" || tok == "--cookie" -> {
                    val cVal = tokens.getOrNull(i + 1).orEmpty().trim()
                        .replace(Regex("""^(?i)cookie\s*:\s*"""), "")
                        .trim()
                    i += 2
                    if (cVal.contains('=')) {
                        cookieParts.add(cVal)
                    }
                }
                tok.startsWith("--cookie=") -> {
                    val cVal = tok.removePrefix("--cookie=").trim()
                        .replace(Regex("""^(?i)cookie\s*:\s*"""), "")
                        .trim()
                    if (cVal.contains('=')) {
                        cookieParts.add(cVal)
                    }
                    i++
                }
                tok == "-A" || tok == "--user-agent" -> {
                    val ua = tokens.getOrNull(i + 1).orEmpty().trim()
                    i += 2
                    if (ua.isNotEmpty()) extractedUa = ua
                }
                tok.startsWith("--user-agent=") -> {
                    val ua = tok.removePrefix("--user-agent=").trim()
                    if (ua.isNotEmpty()) extractedUa = ua
                    i++
                }
                else -> i++
            }
        }
        val mergedCookies = cookieParts.joinToString("; ").takeIf { it.isNotBlank() }
        if (mergedCookies != null || extractedUa != null) {
            return ParsedManualAuthInput(cookieHeader = mergedCookies, userAgent = extractedUa)
        }
    }

    if (cleaned.contains("System.Net.Cookie", ignoreCase = true) || cleaned.contains("Invoke-WebRequest", ignoreCase = true)) {
        val psCookies = Regex("""System\.Net\.Cookie\(\s*"([^"]+)"\s*,\s*"([^"]*)"\s*""", RegexOption.IGNORE_CASE)
            .findAll(cleaned)
            .map { "${it.groupValues[1]}=${it.groupValues[2]}" }
            .toList()
        val psUa = Regex("""-UserAgent\s+"([^"]+)"""", RegexOption.IGNORE_CASE).find(cleaned)?.groupValues?.getOrNull(1)?.trim()
        if (psCookies.isNotEmpty() || !psUa.isNullOrBlank()) {
            return ParsedManualAuthInput(
                cookieHeader = psCookies.joinToString("; ").takeIf { it.isNotBlank() },
                userAgent = psUa
            )
        }
    }

    // 3. Raw HTTP headers (Cookie: ... / User-Agent: ...) or semicolon cookie pairs
    var headerUa: String? = null
    val headerCookies = ArrayList<String>()
    val rawLines = cleaned.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val hasExplicitHeaderLines = rawLines.any {
        it.startsWith("cookie:", ignoreCase = true) || it.startsWith("user-agent:", ignoreCase = true)
    }
    if (hasExplicitHeaderLines) {
        for (line in rawLines) {
            when {
                line.startsWith("cookie:", ignoreCase = true) -> {
                    val v = line.substringAfter(':').trim().trim('"', '\'', '`').trim()
                    if (v.isNotEmpty()) headerCookies.add(v)
                }
                line.startsWith("user-agent:", ignoreCase = true) -> {
                    val v = line.substringAfter(':').trim().trim('"', '\'', '`').trim()
                    if (v.isNotEmpty()) headerUa = v
                }
            }
        }
        val merged = headerCookies.joinToString("; ").takeIf { it.isNotBlank() }
        if (merged != null || headerUa != null) {
            return ParsedManualAuthInput(cookieHeader = merged, userAgent = headerUa)
        }
    }

    val unquoted = cleaned.let { s ->
        when {
            s.length >= 2 && s.first() == '\'' && s.last() == '\'' -> s.substring(1, s.length - 1).trim()
            s.length >= 2 && s.first() == '"' && s.last() == '"' -> s.substring(1, s.length - 1).trim()
            s.length >= 2 && s.first() == '`' && s.last() == '`' -> s.substring(1, s.length - 1).trim()
            else -> s
        }
    }.replace(Regex("""^(?i)cookie\s*:\s*"""), "").trim()

    if (unquoted.isNotEmpty() && !unquoted.contains("://")) {
        if (unquoted.contains('=')) {
            val normalizedPairs = unquoted
                .replace(Regex("""[\r\n]+"""), "; ")
                .split(';')
                .map { it.trim() }
                .filter { part ->
                    val eq = part.indexOf('=')
                    eq > 0 && part.substring(0, eq).trim().matches(Regex("""[A-Za-z0-9_.-]+"""))
                }
            if (normalizedPairs.isNotEmpty()) {
                return ParsedManualAuthInput(cookieHeader = normalizedPairs.joinToString("; "))
            }
        } else if (unquoted.length >= 16 && unquoted.none { it.isWhitespace() }) {
            // 4. Bare _t token
            return ParsedManualAuthInput(
                cookieHeader = "_t=$unquoted",
                bareToken = unquoted
            )
        }
    }

    return ParsedManualAuthInput()
}

object LinuxDoRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val serverConfirmedReadFloor = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    private val readTopicOrder = java.util.concurrent.ConcurrentLinkedQueue<Int>()
    private val readTopicsStateLock = Any()
    private val detachedDraftScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val followFeedCursors = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val userFollowFeedActivityCursors = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val recentNotificationsLock = Any()

    @Volatile
    private var recentNotificationsOwner: String = ""

    @Volatile
    private var recentNotificationsItems: List<UserNotificationItem> = emptyList()

    private fun storeRecentNotificationsCache(username: String?, items: List<UserNotificationItem>) {
        if (!UserApiKeyAuthManager.isAuthorized()) return
        val resolvedOwner = username?.trim()?.takeIf { it.isNotEmpty() && it != "linuxdo_user" }
            ?: UserApiKeyAuthManager.currentUser?.username?.trim()?.takeIf { it.isNotEmpty() && it != "linuxdo_user" }
            ?: return
        synchronized(recentNotificationsLock) {
            recentNotificationsOwner = resolvedOwner
            recentNotificationsItems = items
        }
    }

    fun getCachedRecentNotifications(
        username: String? = UserApiKeyAuthManager.currentUser?.username
    ): List<UserNotificationItem> {
        val clean = username?.trim()?.takeIf { it.isNotEmpty() && it != "linuxdo_user" } ?: return emptyList()
        return synchronized(recentNotificationsLock) {
            if (recentNotificationsOwner.isNotEmpty() && clean.equals(recentNotificationsOwner, ignoreCase = true)) {
                recentNotificationsItems
            } else {
                emptyList()
            }
        }
    }

    @Volatile
    private var lastFollowUsedTopicListFallback: Boolean = false

    var readTopicsVersion by mutableIntStateOf(0)
        private set

    private fun bumpReadTopicsVersion() {
        synchronized(readTopicsStateLock) {
            runUiSafeStateWrite {
                readTopicsVersion++
            }
        }
    }

    fun launchDetachedDraftOp(block: suspend () -> Unit) {
        detachedDraftScope.launch {
            runCatching { block() }
        }
    }

    /**
     * 当且仅当服务端真实确认某话题已读楼层推进（如 `POST /topics/timings` 返回 2xx，或 `/t/{id}.json` 返回 `last_read_post_number`）时回调 UI，
     * 绝不提前在客户端本地伪造消除未读小圆点。
     */
    var onTopicReadFloorConfirmed: ((topicId: Int, confirmedFloor: Int) -> Unit)? = null

    private fun <K, V> putBounded(map: java.util.concurrent.ConcurrentHashMap<K, V>, key: K, value: V, maxEntries: Int = 600) {
        if (map.size >= maxEntries && !map.containsKey(key)) {
            val trimCount = (maxEntries / 6).coerceAtLeast(1)
            val iter = map.keys.iterator()
            var removed = 0
            while (iter.hasNext() && removed < trimCount) {
                runCatching {
                    iter.next()
                    iter.remove()
                }
                removed++
            }
        }
        map[key] = value
    }

    fun recordServerConfirmedReadFloor(
        topicId: Int,
        floor: Int,
        notify: Boolean = true,
        persist: Boolean = true
    ) {
        if (topicId <= 0 || floor <= 0) return
        var prevFloor = 0
        var updatedFloor = 0
        var insertedNew = false
        serverConfirmedReadFloor.compute(topicId) { _, existing ->
            prevFloor = existing ?: 0
            if (existing == null) insertedNew = true
            updatedFloor = maxOf(prevFloor, floor)
            updatedFloor
        }
        if (insertedNew || updatedFloor > prevFloor) {
            synchronized(readTopicsStateLock) {
                readTopicOrder.remove(topicId)
                readTopicOrder.add(topicId)
                while (readTopicOrder.size > 1500) {
                    val oldest = readTopicOrder.poll() ?: break
                    if (oldest != topicId) {
                        serverConfirmedReadFloor.remove(oldest)
                    }
                }
            }
        }
        if (updatedFloor > prevFloor) {
            bumpReadTopicsVersion()
            if (persist) {
                UserApiKeyAuthManager.onPersistReadTopics?.invoke(exportReadTopics())
            }
            if (notify) {
                onTopicReadFloorConfirmed?.invoke(topicId, updatedFloor)
            }
        }
    }

    fun getServerConfirmedReadFloor(topicId: Int): Int? =
        if (topicId > 0) serverConfirmedReadFloor[topicId] else null

    fun markTopicRead(topicId: Int, postsCount: Int = 1) {
        recordServerConfirmedReadFloor(topicId, postsCount, notify = true, persist = true)
    }

    fun isTopicRead(topicId: Int, highestPostNumber: Int = 1): Boolean {
        @Suppress("UNUSED_VARIABLE")
        val observedVersion = readTopicsVersion
        if (topicId <= 0) return false
        val confirmed = serverConfirmedReadFloor[topicId] ?: return false
        return confirmed >= highestPostNumber.coerceAtLeast(1)
    }

    fun isTopicConfirmedReadReactive(topicId: Int, highestPostNumber: Int = 1): Boolean =
        isTopicRead(topicId, highestPostNumber)

    fun getReadTopicSnapshot(): Map<Int, Int> = HashMap(serverConfirmedReadFloor)

    fun restoreReadTopics(serialized: String?) {
        if (serialized.isNullOrBlank()) return
        var changed = false
        synchronized(readTopicsStateLock) {
            serialized.split(',').forEach { entry ->
                val parts = entry.split(':')
                val id = parts.getOrNull(0)?.toIntOrNull() ?: return@forEach
                val count = parts.getOrNull(1)?.toIntOrNull() ?: 1
                if (id > 0 && count > 0) {
                    val prev = serverConfirmedReadFloor.put(id, count)
                    readTopicOrder.remove(id)
                    readTopicOrder.add(id)
                    if (prev != count) {
                        changed = true
                    }
                }
            }
            while (readTopicOrder.size > 1500) {
                val oldest = readTopicOrder.poll() ?: break
                serverConfirmedReadFloor.remove(oldest)
            }
        }
        if (changed) {
            bumpReadTopicsVersion()
        }
    }

    fun clearReadTopics() {
        val hadAny = serverConfirmedReadFloor.isNotEmpty()
        synchronized(readTopicsStateLock) {
            serverConfirmedReadFloor.clear()
            readTopicOrder.clear()
        }
        if (hadAny) {
            bumpReadTopicsVersion()
        }
    }

    fun clearUserSessionCaches() {
        activeAuthVerifyJob?.cancel()
        activeAuthVerifyJob = null
        lastAuthVerifyEpochMs.set(0L)
        ScreenTrackEngine.resetSessionState()
        clearPollVoteState()
        topicExcerptCache.clear()
        topicOpCache.clear()
        postAuthorCache.clear()
        postReactionSnapshots.clear()
        postBoostSnapshots.clear()
        synchronized(recentNotificationsLock) {
            recentNotificationsOwner = ""
            recentNotificationsItems = emptyList()
        }
        updateCsrfToken(null)
        clearReadTopics()
        followFeedCursors.clear()
        lastFollowUsedTopicListFallback = false
        userFollowFeedActivityCursors.clear()
        synchronized(badgeCacheLock) {
            cachedUserBadgesByUsername = emptyMap()
        }
        cachedCategoryTemplates = null
        canCreateTag = false
        synchronized(categoryCatalogLock) {
            categories = defaultCategories
            subCategoriesByParentId = defaultSubCategories.groupBy { it.parentId ?: 0 }
            syncedCategoriesSessionGen = -1L
            categoriesLastSyncedAtMs = 0L
            runUiSafeStateWrite {
                categoryCatalogVersion++
            }
        }
        synchronized(imageByteCache) {
            imageByteCache.clear()
            imageByteCacheTotalBytes = 0L
        }
        bumpReadTopicsVersion()
    }

    fun clearAllLocalCaches() {
        synchronized(imageByteCache) {
            imageByteCache.clear()
            imageByteCacheTotalBytes = 0L
        }
        do_.linux.lindo.core.render.DiscourseAstParser.clearCache()
        topicExcerptCache.clear()
        topicOpCache.clear()
        postAuthorCache.clear()
        postReactionSnapshots.clear()
        postBoostSnapshots.clear()
        synchronized(recentNotificationsLock) {
            recentNotificationsOwner = ""
            recentNotificationsItems = emptyList()
        }
        synchronized(badgeCacheLock) {
            cachedUserBadgesByUsername = emptyMap()
        }
        cachedCategoryTemplates = null
        clearTransientRateLimit()
        clearChallengeCooldown()
    }

    private fun syncCookieHeaderPairsToPlatform(cookieHeader: String) {
        val syncFn = UserApiKeyAuthManager.onSyncCookieToPlatform ?: return
        val hostOnlyNames = setOf("_t", "_forum_session")
        cookieHeader.split(';').forEach { segment ->
            val trimmed = segment.trim()
            val eqIdx = trimmed.indexOf('=')
            if (eqIdx > 0) {
                val name = trimmed.substring(0, eqIdx).trim()
                val value = trimmed.substring(eqIdx + 1).trim()
                if (name.isNotEmpty() && value.isNotEmpty()) {
                    val formatted = if (name in hostOnlyNames) {
                        "$name=$value; Path=/; Secure; HttpOnly; SameSite=Lax"
                    } else {
                        "$name=$value; Domain=.linux.do; Path=/; Secure"
                    }
                    syncFn.invoke(formatted)
                }
            }
        }
    }

    suspend fun importManualAuthInput(rawInput: String): Result<AuthenticatedUserProfile> = withContext(Dispatchers.IO) {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException(LinDoAuthCopy.INPUT_CALLBACK_OR_COOKIE_PROMPT.text))
        }
        val parsed = parsePastedAuthOrCurlInput(trimmed)
        if (parsed.callbackUrl != null) {
            val accepted = UserApiKeyAuthManager.handleAuthRedirectUri(parsed.callbackUrl)
            if (!accepted) {
                return@withContext Result.failure(IllegalArgumentException(LinDoAuthCopy.INVALID_OR_EXPIRED_CALLBACK.text))
            }
            val otp = UserApiKeyAuthManager.consumePendingOtpToken()
            val ok = redeemOtpSilently(otp.orEmpty())
            val authed = UserApiKeyAuthManager.currentUser
            return@withContext if (ok && authed != null) {
                Result.success(authed)
            } else if (ok) {
                Result.failure(IllegalStateException(LinDoAuthCopy.SESSION_REDEEMED_PROFILE_FAILED.text))
            } else {
                Result.failure(IllegalStateException(LinDoAuthCopy.OTP_REDEEM_BLOCKED_USE_COOKIE.text))
            }
        }

        val prevCookies = CookieSentinel.buildCookieHeader(targetHost = "linux.do", includeCloudflareCookies = true)
        val prevUa = UserApiKeyAuthManager.customUserAgent
        val prevProfileCache = UserApiKeyAuthManager.fullProfileCache

        fun rollbackState() {
            CookieSentinel.clearAuthCookiesOnly()
            CookieSentinel.clearAll()
            if (prevCookies.isNotBlank()) {
                CookieSentinel.ingestRawCookieString(prevCookies, domain = "linux.do", hostOnly = false)
            }
            UserApiKeyAuthManager.customUserAgent = prevUa
            if (UserApiKeyAuthManager.fullProfileCache == null && prevProfileCache != null && UserApiKeyAuthManager.isAuthorized()) {
                UserApiKeyAuthManager.fullProfileCache = prevProfileCache
            }
        }

        if (!parsed.userAgent.isNullOrBlank()) {
            UserApiKeyAuthManager.customUserAgent = parsed.userAgent
        }
        CookieSentinel.clearAuthCookiesOnly()
        if (!parsed.cookieHeader.isNullOrBlank()) {
            CookieSentinel.ingestRawCookieString(parsed.cookieHeader, domain = "linux.do", hostOnly = false)
        }
        if (!UserApiKeyAuthManager.hasValidWebSessionCookie()) {
            rollbackState()
            return@withContext Result.failure(IllegalArgumentException(LinDoAuthCopy.MISSING_VALID_T_COOKIE.text))
        }

        if (!parsed.cookieHeader.isNullOrBlank()) {
            syncCookieHeaderPairsToPlatform(parsed.cookieHeader)
        }
        clearTransientRateLimit()
        clearChallengeCooldown()
        UserApiKeyAuthManager.fullProfileCache = null

        val summary = try {
            UserApiKeyAuthManager.withServerAuthInvalidationSuppressed {
                fetchCurrentUserProfile()
            }
        } catch (ce: CancellationException) {
            rollbackState()
            throw ce
        } catch (_: Throwable) {
            null
        }

        val currentAuthed = UserApiKeyAuthManager.currentUser
        if (summary != null && currentAuthed != null && currentAuthed.username.equals(summary.username, ignoreCase = true)) {
            UserApiKeyAuthManager.clearApiKeyQuietly()
            UserApiKeyAuthManager.persistSessionQuietly()
            UserApiKeyAuthManager.onAuthStateChanged?.invoke()
            Result.success(currentAuthed)
        } else {
            rollbackState()
            if (prevCookies.isNotBlank()) {
                syncCookieHeaderPairsToPlatform(prevCookies)
            }
            Result.failure(IllegalStateException(LinDoAuthCopy.COOKIE_VERIFY_REJECTED.text))
        }
    }

    fun importCloudflareClearanceInput(rawInput: String): Boolean {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return false
        val parsed = parsePastedAuthOrCurlInput(trimmed)
        var ingestedAny = false
        if (!parsed.userAgent.isNullOrBlank()) {
            UserApiKeyAuthManager.customUserAgent = parsed.userAgent
            ingestedAny = true
        }
        val effectiveCookieHeader = when {
            !parsed.bareToken.isNullOrBlank() -> "cf_clearance=${parsed.bareToken}"
            !parsed.cookieHeader.isNullOrBlank() -> parsed.cookieHeader
            else -> null
        }
        if (effectiveCookieHeader != null) {
            val hasValidPair = effectiveCookieHeader.split(';').any { seg ->
                val eq = seg.indexOf('=')
                eq > 0 && seg.substring(0, eq).trim().isNotEmpty() && seg.substring(eq + 1).trim().isNotEmpty()
            }
            if (hasValidPair) {
                CookieSentinel.ingestRawCookieString(effectiveCookieHeader, domain = "linux.do", hostOnly = false)
                syncCookieHeaderPairsToPlatform(effectiveCookieHeader)
                ingestedAny = true
            }
        }
        if (ingestedAny) {
            UserApiKeyAuthManager.persistSessionQuietly()
            clearTransientRateLimit()
            clearChallengeCooldown()
        }
        return ingestedAny
    }

    private fun exportReadTopics(): String = synchronized(readTopicsStateLock) {
        val orderedIds = LinkedHashSet<Int>()
        for (id in readTopicOrder) {
            if (serverConfirmedReadFloor.containsKey(id)) {
                orderedIds.add(id)
            }
        }
        for (id in serverConfirmedReadFloor.keys) {
            orderedIds.add(id)
        }
        val tailIds = if (orderedIds.size > 1500) {
            orderedIds.toList().takeLast(1500)
        } else {
            orderedIds.toList()
        }
        tailIds.mapNotNull { id ->
            serverConfirmedReadFloor[id]?.let { floor -> "$id:$floor" }
        }.joinToString(",")
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

    val defaultSubCategories: List<LinuxDoCategoryMeta> = listOf(
        // 开发调优 (4)
        LinuxDoCategoryMeta(20, "开发调优, Lv1", "develop-lv1", "32C3C3", "开发调优 Lv1 专区", icon = "code", readRestricted = true, parentId = 4, parentSlug = "develop", trustLevel = 1, subLabel = "Lv1", isVerifiedFromServer = false),
        LinuxDoCategoryMeta(64, "开发调优, Lv2", "develop-lv2", "32C3C3", "开发调优 Lv2 专区", icon = "code", readRestricted = true, parentId = 4, parentSlug = "develop", trustLevel = 2, subLabel = "Lv2", isVerifiedFromServer = false),
        LinuxDoCategoryMeta(65, "开发调优, Lv3", "develop-lv3", "32C3C3", "开发调优 Lv3 专区", icon = "code", readRestricted = true, parentId = 4, parentSlug = "develop", trustLevel = 3, subLabel = "Lv3", isVerifiedFromServer = false),
        // 资源荟萃 (14)
        LinuxDoCategoryMeta(21, "资源荟萃, Lv1", "resource-lv1", "12A89D", "资源荟萃 Lv1 专区", icon = "square-share-nodes", readRestricted = true, parentId = 14, parentSlug = "resource", trustLevel = 1, subLabel = "Lv1", isVerifiedFromServer = false),
        LinuxDoCategoryMeta(88, "资源荟萃, Lv2", "resource-lv2", "12A89D", "资源荟萃 Lv2 专区", icon = "square-share-nodes", readRestricted = true, parentId = 14, parentSlug = "resource", trustLevel = 2, subLabel = "Lv2", isVerifiedFromServer = false),
        LinuxDoCategoryMeta(89, "资源荟萃, Lv3", "resource-lv3", "12A89D", "资源荟萃 Lv3 专区", icon = "square-share-nodes", readRestricted = true, parentId = 14, parentSlug = "resource", trustLevel = 3, subLabel = "Lv3", isVerifiedFromServer = false),
        LinuxDoCategoryMeta(94, "网盘资源", "cloud-asset", "16B176", "网盘资源分享专区", icon = "hard-drive", readRestricted = false, parentId = 14, parentSlug = "resource", trustLevel = null, subLabel = "网盘", isVerifiedFromServer = true),
        // 文档共建 (42)
        LinuxDoCategoryMeta(75, "文档共建, Lv1", "wiki-lv1", "9CB6C4", "文档共建 Lv1 专区", icon = "book", readRestricted = true, parentId = 42, parentSlug = "wiki", trustLevel = 1, subLabel = "Lv1", isVerifiedFromServer = false),
        LinuxDoCategoryMeta(76, "文档共建, Lv2", "wiki-lv2", "9CB6C4", "文档共建 Lv2 专区", icon = "book", readRestricted = true, parentId = 42, parentSlug = "wiki", trustLevel = 2, subLabel = "Lv2", isVerifiedFromServer = false),
        LinuxDoCategoryMeta(77, "文档共建, Lv3", "wiki-lv3", "9CB6C4", "文档共建 Lv3 专区", icon = "book", readRestricted = true, parentId = 42, parentSlug = "wiki", trustLevel = 3, subLabel = "Lv3", isVerifiedFromServer = false),
        // 搞七捻三 (11)
        LinuxDoCategoryMeta(35, "搞七捻三, Lv1", "gossip-lv1", "3AB54A", "搞七捻三 Lv1 专区", icon = "droplet", readRestricted = true, parentId = 11, parentSlug = "gossip", trustLevel = 1, subLabel = "Lv1", isVerifiedFromServer = false),
        LinuxDoCategoryMeta(79, "搞七捻三, Lv2", "gossip-lv2", "3AB54A", "搞七捻三 Lv2 专区", icon = "droplet", readRestricted = true, parentId = 11, parentSlug = "gossip", trustLevel = 2, subLabel = "Lv2", isVerifiedFromServer = false),
        LinuxDoCategoryMeta(80, "搞七捻三, Lv3", "gossip-lv3", "3AB54A", "搞七捻三 Lv3 专区", icon = "droplet", readRestricted = true, parentId = 11, parentSlug = "gossip", trustLevel = 3, subLabel = "Lv3", isVerifiedFromServer = false),
        // 福利羊毛 (36)
        LinuxDoCategoryMeta(45, "福利羊毛, Lv1", "welfare-lv1", "E45735", "福利羊毛 Lv1 专区", icon = "piggy-bank", readRestricted = true, parentId = 36, parentSlug = "welfare", trustLevel = 1, subLabel = "Lv1", isVerifiedFromServer = false),
        LinuxDoCategoryMeta(68, "福利羊毛, Lv2", "welfare-lv2", "E45735", "福利羊毛 Lv2 专区", icon = "piggy-bank", readRestricted = true, parentId = 36, parentSlug = "welfare", trustLevel = 2, subLabel = "Lv2", isVerifiedFromServer = false),
        LinuxDoCategoryMeta(69, "福利羊毛, Lv3", "welfare-lv3", "E45735", "福利羊毛 Lv3 专区", icon = "piggy-bank", readRestricted = true, parentId = 36, parentSlug = "welfare", trustLevel = 3, subLabel = "Lv3", isVerifiedFromServer = false),
        // 前沿快讯 (34)
        LinuxDoCategoryMeta(52, "前沿快讯, Lv1", "news-lv1", "BB8FCE", "前沿快讯 Lv1 专区", icon = "newspaper", readRestricted = true, parentId = 34, parentSlug = "news", trustLevel = 1, subLabel = "Lv1", isVerifiedFromServer = false),
        LinuxDoCategoryMeta(72, "前沿快讯, Lv2", "news-lv2", "BB8FCE", "前沿快讯 Lv2 专区", icon = "newspaper", readRestricted = true, parentId = 34, parentSlug = "news", trustLevel = 2, subLabel = "Lv2", isVerifiedFromServer = false),
        LinuxDoCategoryMeta(73, "前沿快讯, Lv3", "news-lv3", "BB8FCE", "前沿快讯 Lv3 专区", icon = "newspaper", readRestricted = true, parentId = 34, parentSlug = "news", trustLevel = 3, subLabel = "Lv3", isVerifiedFromServer = false),
        // 非我莫属 (27)
        LinuxDoCategoryMeta(55, "非我莫属, Lv1", "job-lv1", "A8C6FE", "非我莫属 Lv1 专区", icon = "briefcase", readRestricted = true, parentId = 27, parentSlug = "job", trustLevel = 1, subLabel = "Lv1", isVerifiedFromServer = false),
        LinuxDoCategoryMeta(56, "非我莫属, Lv2", "job-lv2", "A8C6FE", "非我莫属 Lv2 专区", icon = "briefcase", readRestricted = true, parentId = 27, parentSlug = "job", trustLevel = 2, subLabel = "Lv2", isVerifiedFromServer = false),
        LinuxDoCategoryMeta(57, "非我莫属, Lv3", "job-lv3", "A8C6FE", "非我莫属 Lv3 专区", icon = "briefcase", readRestricted = true, parentId = 27, parentSlug = "job", trustLevel = 3, subLabel = "Lv3", isVerifiedFromServer = false),
        // 跳蚤市场 (10)
        LinuxDoCategoryMeta(58, "跳蚤市场, Lv1", "market-lv1", "ED207B", "跳蚤市场 Lv1 专区", icon = "store", readRestricted = true, parentId = 10, parentSlug = "market", trustLevel = 1, subLabel = "Lv1", isVerifiedFromServer = false),
        LinuxDoCategoryMeta(59, "跳蚤市场, Lv2", "market-lv2", "ED207B", "跳蚤市场 Lv2 专区", icon = "store", readRestricted = true, parentId = 10, parentSlug = "market", trustLevel = 2, subLabel = "Lv2", isVerifiedFromServer = false),
        LinuxDoCategoryMeta(60, "跳蚤市场, Lv3", "market-lv3", "ED207B", "跳蚤市场 Lv3 专区", icon = "store", readRestricted = true, parentId = 10, parentSlug = "market", trustLevel = 3, subLabel = "Lv3", isVerifiedFromServer = false),
        // 读书成诗 (32)
        LinuxDoCategoryMeta(61, "读书成诗, Lv1", "reading-lv1", "E0D900", "读书成诗 Lv1 专区", icon = "book-open-reader", readRestricted = true, parentId = 32, parentSlug = "reading", trustLevel = 1, subLabel = "Lv1", isVerifiedFromServer = false),
        LinuxDoCategoryMeta(62, "读书成诗, Lv2", "reading-lv2", "E0D900", "读书成诗 Lv2 专区", icon = "book-open-reader", readRestricted = true, parentId = 32, parentSlug = "reading", trustLevel = 2, subLabel = "Lv2", isVerifiedFromServer = false),
        LinuxDoCategoryMeta(63, "读书成诗, Lv3", "reading-lv3", "E0D900", "读书成诗 Lv3 专区", icon = "book-open-reader", readRestricted = true, parentId = 32, parentSlug = "reading", trustLevel = 3, subLabel = "Lv3", isVerifiedFromServer = false),
        // 积分乐园 (95)
        LinuxDoCategoryMeta(96, "积分乐园, Lv1", "playground-lv1", "E0D900", "积分乐园 Lv1 专区", icon = "coins", readRestricted = true, parentId = 95, parentSlug = "playground", trustLevel = 1, subLabel = "Lv1", isVerifiedFromServer = false),
        LinuxDoCategoryMeta(97, "积分乐园, Lv2", "playground-lv2", "E0D900", "积分乐园 Lv2 专区", icon = "coins", readRestricted = true, parentId = 95, parentSlug = "playground", trustLevel = 2, subLabel = "Lv2", isVerifiedFromServer = false),
        LinuxDoCategoryMeta(99, "积分乐园, Lv3", "playground-lv3", "E0D900", "积分乐园 Lv3 专区", icon = "coins", readRestricted = true, parentId = 95, parentSlug = "playground", trustLevel = 3, subLabel = "Lv3", isVerifiedFromServer = false)
    )

    @Volatile
    var categories: List<LinuxDoCategoryMeta> = defaultCategories
        private set

    @Volatile
    var subCategoriesByParentId: Map<Int, List<LinuxDoCategoryMeta>> =
        defaultSubCategories.groupBy { it.parentId ?: 0 }
        private set

    private val categoryCatalogLock = Any()

    var categoryCatalogVersion by mutableIntStateOf(0)
        private set

    @Volatile
    private var syncedCategoriesSessionGen: Long = -1L

    @Volatile
    private var categoriesLastSyncedAtMs: Long = 0L

    private val siteCategoriesSyncMutex = Mutex()
    private val siteBadgesSyncMutex = Mutex()
    private val siteEmojisSyncMutex = Mutex()

    @Volatile
    private var badgesLastSyncedAtMs: Long = 0L

    @Volatile
    private var emojisLastSyncedAtMs: Long = 0L

    private fun encodeUrlSegment(raw: String): String =
        java.net.URLEncoder.encode(raw.trim(), "UTF-8").replace("+", "%20")

    fun findCategoryById(id: Int?): LinuxDoCategoryMeta? {
        if (id == null) return null
        categories.firstOrNull { it.id == id }?.let { return it }
        subCategoriesByParentId.values.forEach { subs ->
            subs.firstOrNull { it.id == id }?.let { return it }
        }
        return null
    }

    fun resolveParentCategoryId(categoryId: Int?): Int? {
        if (categoryId == null) return null
        val cat = findCategoryById(categoryId) ?: return categoryId
        return cat.parentId ?: cat.id
    }

    fun getSubCategoriesForParent(parentId: Int): List<LinuxDoCategoryMeta> {
        return subCategoriesByParentId[parentId].orEmpty().filter { it.id > 0 }
    }

    fun getPostableSubCategoriesForParent(parentId: Int): List<LinuxDoCategoryMeta> {
        return subCategoriesByParentId[parentId].orEmpty().filter { it.isVerifiedFromServer && it.id > 0 }
    }

    @Volatile
    var canCreateTag: Boolean = false
        internal set

    private val categoryLookup = java.util.concurrent.ConcurrentHashMap<Int, Pair<String, String>>().apply {
        defaultCategories.forEach { put(it.id, it.name to it.colorHex) }
        defaultSubCategories.filter { it.isVerifiedFromServer }.forEach { put(it.id, it.name to it.colorHex) }
        put(49, "公告" to "F1592A")
        put(123, "悬赏" to "C4B512")
        put(30, "活动" to "38571A")
        put(124, "模板" to "04A407")
    }

    private val categoryIconLookup = java.util.concurrent.ConcurrentHashMap<String, String>().apply {
        (defaultCategories + defaultSubCategories).forEach { meta ->
            meta.icon?.let { put(meta.name, it) }
        }
        put("网盘资源", "hard-drive")
        put("公告", "bullhorn")
        put("悬赏", "award")
        put("活动", "users")
        put("模板", "clone")
    }

    private val categoryRestrictedLookup = java.util.concurrent.ConcurrentHashMap<String, Boolean>().apply {
        (defaultCategories + defaultSubCategories).forEach { meta ->
            put(meta.name, meta.readRestricted)
        }
    }

    fun resolveCategoryMeta(categoryId: Int): Pair<String, String> {
        return categoryLookup[categoryId] ?: (LinDoNetworkCopy.DEFAULT_DEV_CATEGORY.text to "32C3C3")
    }

    fun resolveCategoryIconName(categoryName: String): String? {
        val trimmed = categoryName.trim()
        categoryIconLookup[trimmed]?.let { return it }
        val normalizedComma = trimmed.replace(" · ", ", ")
        categoryIconLookup[normalizedComma]?.let { return it }
        val base = normalizedComma.substringBefore(",").trim()
        return categoryIconLookup[base]
    }

    fun isCategoryRestricted(categoryName: String): Boolean {
        val trimmed = categoryName.trim()
        if (trimmed.contains(", Lv", ignoreCase = true) ||
            trimmed.contains("· Lv", ignoreCase = true) ||
            Regex("\\bLv[1-4]\\b", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)
        ) {
            return true
        }
        val normalizedComma = trimmed.replace(" · ", ", ")
        categoryRestrictedLookup[trimmed]?.let { if (it) return true }
        categoryRestrictedLookup[normalizedComma]?.let { if (it) return true }
        val base = normalizedComma.substringBefore(",").trim()
        return categoryRestrictedLookup[base] == true ||
            base in setOf("跳蚤市场", "深海幽域", "积分乐园", "扬帆起航", "社区孵化")
    }

    internal fun mergeCategoriesFromSiteRoot(root: JsonObject) {
        (root["can_create_tag"] as? JsonPrimitive)?.booleanOrNull?.let {
            canCreateTag = it
        }
        val catArr = root["categories"] as? JsonArray ?: return
        val mergedById = LinkedHashMap<Int, LinuxDoCategoryMeta>()
        defaultCategories.forEach { mergedById[it.id] = it }

        val subById = LinkedHashMap<Int, LinuxDoCategoryMeta>()
        defaultSubCategories.forEach { subById[it.id] = it }

        val rawById = LinkedHashMap<Int, JsonObject>()
        catArr.forEach { elem ->
            val cObj = elem as? JsonObject ?: return@forEach
            val id = cObj["id"]?.jsonPrimitive?.intOrNull ?: return@forEach
            rawById[id] = cObj
        }

        fun relocateUnverifiedIfPresent(collidingId: Int) {
            val displaced = subById[collidingId]
            if (displaced != null && !displaced.isVerifiedFromServer) {
                subById.remove(collidingId)
            }
        }

        data class PendingSubEntry(
            val id: Int,
            val cObj: JsonObject,
            val preferredName: String,
            val slug: String,
            val rawParentId: Int?,
            val detectedLv: Int?,
            val basePrefix: String
        )
        val pendingSubs = mutableListOf<PendingSubEntry>()

        // Pass 1: 处理所有一级主版块，确保子版块推断时能引用最新真实主版块名称与 slug，且绝不发生 ID 碰撞
        for ((id, cObj) in rawById) {
            val name = cObj["name"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val slug = cObj["slug"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: "cat-$id"
            val rawParentId = cObj["parent_category_id"]?.jsonPrimitive?.intOrNull
            val existingTop = mergedById[id]
            val preferredName = if (existingTop != null && existingTop.name.any { it.code > 127 } && name.all { it.code < 128 }) {
                existingTop.name
            } else {
                name
            }
            val lvMatch = Regex("Lv\\s*([1-4])", RegexOption.IGNORE_CASE).find(preferredName)
            val detectedLv = lvMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
            val basePrefix = preferredName.substringBefore(",").trim()

            val isSubCandidate = rawParentId != null ||
                preferredName.startsWith("网盘资源") ||
                (detectedLv != null && basePrefix != preferredName)

            if (isSubCandidate) {
                pendingSubs += PendingSubEntry(id, cObj, preferredName, slug, rawParentId, detectedLv, basePrefix)
                continue
            }

            relocateUnverifiedIfPresent(id)
            val color = cObj["color"]?.jsonPrimitive?.contentOrNull?.trim()?.removePrefix("#")?.takeIf { it.isNotEmpty() }
                ?: existingTop?.colorHex ?: "32C3C3"
            val icon = cObj["icon"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                ?: existingTop?.icon
            val readRestricted = cObj["read_restricted"]?.jsonPrimitive?.booleanOrNull
                ?: existingTop?.readRestricted ?: false
            val desc = cObj["description_text"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                ?: cObj["description_excerpt"]?.jsonPrimitive?.contentOrNull?.let { decodeDiscourseHtmlSnippet(it) }?.takeIf { it.isNotEmpty() }
                ?: existingTop?.description ?: preferredName

            categoryLookup[id] = preferredName to color
            if (icon != null) categoryIconLookup[preferredName] = icon
            categoryRestrictedLookup[preferredName] = readRestricted

            mergedById[id] = LinuxDoCategoryMeta(
                id = id,
                name = preferredName,
                slug = slug,
                colorHex = color,
                description = desc.take(28),
                icon = icon,
                readRestricted = readRestricted,
                isVerifiedFromServer = true,
                hasServerParent = false
            )
        }

        // Pass 2: 处理二级与三级子版块（如 `资源荟萃 -> 网盘资源 -> 网盘资源, Lv1` 自动归拢至顶层主版块）
        for (sub in pendingSubs) {
            val id = sub.id
            val cObj = sub.cObj
            var rootParentId = when {
                sub.rawParentId != null -> sub.rawParentId
                sub.preferredName.startsWith("网盘资源") -> 14
                sub.detectedLv != null -> mergedById.values.firstOrNull { it.name == sub.basePrefix }?.id
                else -> null
            }
            // 若父分类本身也是子分类（如三级 `网盘资源, Lv1` 的 rawParentId=94），沿树向上追溯到一级主版块
            var guard = 0
            while (rootParentId != null && !mergedById.containsKey(rootParentId) && guard++ < 5) {
                val parentRaw = rawById[rootParentId]
                val upperId = parentRaw?.get("parent_category_id")?.jsonPrimitive?.intOrNull
                if (upperId != null && upperId != rootParentId) {
                    rootParentId = upperId
                } else if (sub.preferredName.startsWith("网盘资源")) {
                    rootParentId = 14
                    break
                } else {
                    break
                }
            }

            if (rootParentId == null || rootParentId == id) {
                relocateUnverifiedIfPresent(id)
                val existingTop = mergedById[id]
                val color = cObj["color"]?.jsonPrimitive?.contentOrNull?.trim()?.removePrefix("#")?.takeIf { it.isNotEmpty() }
                    ?: existingTop?.colorHex ?: "32C3C3"
                val icon = cObj["icon"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: existingTop?.icon
                val readRestricted = cObj["read_restricted"]?.jsonPrimitive?.booleanOrNull
                    ?: if (sub.detectedLv != null) true else (existingTop?.readRestricted ?: false)
                val desc = cObj["description_text"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                    ?: cObj["description_excerpt"]?.jsonPrimitive?.contentOrNull?.let { decodeDiscourseHtmlSnippet(it) }?.takeIf { it.isNotEmpty() }
                    ?: existingTop?.description ?: sub.preferredName
                categoryLookup[id] = sub.preferredName to color
                if (icon != null) categoryIconLookup[sub.preferredName] = icon
                categoryRestrictedLookup[sub.preferredName] = readRestricted
                mergedById[id] = LinuxDoCategoryMeta(
                    id = id,
                    name = sub.preferredName,
                    slug = sub.slug,
                    colorHex = color,
                    description = desc.take(28),
                    icon = icon,
                    readRestricted = readRestricted,
                    isVerifiedFromServer = true,
                    hasServerParent = false
                )
                continue
            }

            val parentMeta = mergedById[rootParentId]
            val directParentObj = sub.rawParentId?.let { rawById[it] }
            val existingSub = subById[id]?.takeIf { it.isVerifiedFromServer }
            val rawColor = cObj["color"]?.jsonPrimitive?.contentOrNull?.trim()?.removePrefix("#")?.takeIf { it.isNotEmpty() }
            val parentColor = directParentObj?.get("color")?.jsonPrimitive?.contentOrNull?.trim()?.removePrefix("#")?.takeIf { it.isNotEmpty() }
                ?: parentMeta?.colorHex
            val color = when {
                rawColor != null && !(parentColor != null && rawColor.equals("0088CC", ignoreCase = true)) -> rawColor
                parentColor != null -> parentColor
                else -> existingSub?.colorHex ?: "32C3C3"
            }
            val icon = cObj["icon"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                ?: (if (sub.preferredName.startsWith("网盘资源")) "hard-drive" else null)
                ?: directParentObj?.get("icon")?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                ?: parentMeta?.icon
                ?: existingSub?.icon
            val readRestricted = cObj["read_restricted"]?.jsonPrimitive?.booleanOrNull
                ?: if (sub.detectedLv != null) true else (existingSub?.readRestricted ?: false)
            val desc = cObj["description_text"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                ?: cObj["description_excerpt"]?.jsonPrimitive?.contentOrNull?.let { decodeDiscourseHtmlSnippet(it) }?.takeIf { it.isNotEmpty() }
                ?: existingSub?.description ?: sub.preferredName

            categoryLookup[id] = sub.preferredName to color
            if (icon != null) categoryIconLookup[sub.preferredName] = icon
            categoryRestrictedLookup[sub.preferredName] = readRestricted

            val computedSubLabel = when {
                sub.preferredName.startsWith("网盘资源") && sub.detectedLv != null -> "网盘 Lv${sub.detectedLv}"
                sub.preferredName.startsWith("网盘资源") -> "网盘"
                sub.detectedLv != null -> "Lv${sub.detectedLv}"
                else -> sub.preferredName.substringAfter(",", sub.preferredName).trim()
            }

            // 仅当服务端真实配置了 parent_category_id 且直接父级是一级主版块时，才允许拼接两段式 `c/{parentSlug}/{slug}/{id}.json`
            val directParentIsTopLevel = sub.rawParentId != null &&
                rawById[sub.rawParentId]?.get("parent_category_id")?.jsonPrimitive?.intOrNull == null
            val serverParentSlug = if (directParentIsTopLevel) {
                directParentObj?.get("slug")?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                    ?: parentMeta?.slug
            } else {
                null
            }

            // 移除同父级 + 同子标签的未验证默认占位项
            val duplicateDefaultKey = subById.entries.firstOrNull { (_, v) ->
                !v.isVerifiedFromServer && v.parentId == rootParentId && v.subLabel.equals(computedSubLabel, ignoreCase = true)
            }?.key
            if (duplicateDefaultKey != null) {
                subById.remove(duplicateDefaultKey)
            }
            // 若当前真实 ID 恰好与另一个子版块的未验证占位 ID 冲突，将该未验证占位迁移至安全负数 ID，避免误覆盖
            relocateUnverifiedIfPresent(id)
            mergedById.remove(id)

            subById[id] = LinuxDoCategoryMeta(
                id = id,
                name = sub.preferredName,
                slug = sub.slug,
                colorHex = color,
                description = desc.take(28),
                icon = icon,
                readRestricted = readRestricted,
                parentId = rootParentId,
                parentSlug = serverParentSlug,
                trustLevel = sub.detectedLv,
                subLabel = computedSubLabel,
                isVerifiedFromServer = true,
                hasServerParent = directParentIsTopLevel
            )
        }

        synchronized(categoryCatalogLock) {
            if (mergedById.isNotEmpty()) {
                categories = mergedById.values.toList()
            }
            if (subById.isNotEmpty()) {
                subCategoriesByParentId = subById.values
                    .groupBy { it.parentId ?: 0 }
                    .mapValues { (_, list) ->
                        list.sortedWith(compareBy({ it.trustLevel ?: 99 }, { it.name }))
                    }
            }
            syncedCategoriesSessionGen = UserApiKeyAuthManager.sessionGeneration
            runUiSafeStateWrite {
                categoryCatalogVersion++
            }
        }
    }

    private val sharedConnectionPool = okhttp3.ConnectionPool(8, 2, TimeUnit.MINUTES)
    private val longReadConnectionPool = okhttp3.ConnectionPool(4, 2, TimeUnit.MINUTES)
    private val imageConnectionPool = okhttp3.ConnectionPool(16, 2, TimeUnit.MINUTES)
    private val uploadConnectionPool = okhttp3.ConnectionPool(4, 2, TimeUnit.MINUTES)

    fun evictHttpConnectionPools() {
        runCatching { sharedConnectionPool.evictAll() }
        runCatching { longReadConnectionPool.evictAll() }
        runCatching { imageConnectionPool.evictAll() }
        runCatching { uploadConnectionPool.evictAll() }
    }

    private val otpExchangeClient: HttpClient by lazy { createOtpExchangeClient() }

    private val sharedClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            engine {
                config {
                    connectionPool(sharedConnectionPool)
                    connectTimeout(6, TimeUnit.SECONDS)
                    readTimeout(15, TimeUnit.SECONDS)
                    retryOnConnectionFailure(true)
                }
            }
        }
    }

    private val longReadClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            engine {
                config {
                    connectionPool(longReadConnectionPool)
                    connectTimeout(10, TimeUnit.SECONDS)
                    readTimeout(35, TimeUnit.SECONDS)
                    retryOnConnectionFailure(true)
                }
            }
        }
    }

    private val connectOAuthClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            followRedirects = false
            engine {
                config {
                    followRedirects(false)
                    followSslRedirects(false)
                    connectTimeout(8, TimeUnit.SECONDS)
                    readTimeout(15, TimeUnit.SECONDS)
                    retryOnConnectionFailure(false)
                }
            }
        }
    }

    private val uploadHttpClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            engine {
                config {
                    connectionPool(uploadConnectionPool)
                    connectTimeout(15, TimeUnit.SECONDS)
                    writeTimeout(90, TimeUnit.SECONDS)
                    readTimeout(90, TimeUnit.SECONDS)
                    callTimeout(120, TimeUnit.SECONDS)
                    retryOnConnectionFailure(false)
                }
            }
        }
    }

    private const val MAX_IMAGE_CACHE_ENTRIES = 256
    private const val MAX_IMAGE_CACHE_SINGLE_BYTES = 512 * 1024
    private const val MAX_IMAGE_CACHE_TOTAL_BYTES = 24L * 1024L * 1024L

    private val imageByteCache = LinkedHashMap<String, UserApiKeyAuthManager.BinaryHttpPayload>(128, 0.75f, true)
    private var imageByteCacheTotalBytes: Long = 0L
    private val inFlightBinaryFetches = java.util.concurrent.ConcurrentHashMap<String, Deferred<UserApiKeyAuthManager.BinaryHttpPayload?>>()
    private val binaryFetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val authVerifyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var activeAuthVerifyJob: kotlinx.coroutines.Job? = null
    private val lastAuthVerifyEpochMs = java.util.concurrent.atomic.AtomicLong(0L)
    private val inFlightForegroundApiCount = java.util.concurrent.atomic.AtomicInteger(0)

    internal fun scheduleAuthVerificationIfNeeded() {
        if (!UserApiKeyAuthManager.isAuthorized()) return
        val now = System.currentTimeMillis()
        val prev = lastAuthVerifyEpochMs.get()
        if (now - prev in 0 until 8_000L) return
        if (!lastAuthVerifyEpochMs.compareAndSet(prev, now)) return
        val expectedGen = UserApiKeyAuthManager.sessionGeneration
        activeAuthVerifyJob?.cancel()
        activeAuthVerifyJob = authVerifyScope.launch {
            if (UserApiKeyAuthManager.sessionGeneration != expectedGen) return@launch
            runCatching { fetchCurrentUserProfile(background = true) }
        }
    }

    private fun getCachedImagePayload(url: String): UserApiKeyAuthManager.BinaryHttpPayload? = synchronized(imageByteCache) {
        imageByteCache[url]
    }

    private fun putCachedImagePayload(url: String, payload: UserApiKeyAuthManager.BinaryHttpPayload) {
        val itemBytes = payload.bytes.size
        if (itemBytes <= 0 || itemBytes > MAX_IMAGE_CACHE_SINGLE_BYTES) return
        synchronized(imageByteCache) {
            val previous = imageByteCache.put(url, payload)
            if (previous != null) {
                imageByteCacheTotalBytes = (imageByteCacheTotalBytes - previous.bytes.size).coerceAtLeast(0L)
            }
            imageByteCacheTotalBytes += itemBytes
            val iter = imageByteCache.entries.iterator()
            while (iter.hasNext() && (imageByteCache.size > MAX_IMAGE_CACHE_ENTRIES || imageByteCacheTotalBytes > MAX_IMAGE_CACHE_TOTAL_BYTES)) {
                val eldest = iter.next()
                imageByteCacheTotalBytes = (imageByteCacheTotalBytes - eldest.value.bytes.size).coerceAtLeast(0L)
                iter.remove()
            }
        }
    }

    private suspend fun fetchLinuxDoBinarySingleFlight(
        cleanUrl: String,
        fetcher: suspend (String) -> UserApiKeyAuthManager.BinaryHttpPayload?
    ): UserApiKeyAuthManager.BinaryHttpPayload? {
        getCachedImagePayload(cleanUrl)?.let { return it }
        val deferred = inFlightBinaryFetches.computeIfAbsent(cleanUrl) {
            binaryFetchScope.async {
                try {
                    val result = fetcher(cleanUrl)
                    if (result != null && result.status in 200..299 && result.bytes.isNotEmpty()) {
                        putCachedImagePayload(cleanUrl, result)
                    }
                    result
                } finally {
                    inFlightBinaryFetches.remove(cleanUrl)
                }
            }
        }
        return deferred.await()
    }

    /**
     * Coil 图片客户端：
     * 1. 在已挂载同源 Chromium WebView 的平台（Android）上，针对 `linux.do` 域名的头像/表情/帖子图片请求，
     *    直接走同源 WebView 二进制桥接 (`webViewBinaryFetcher`)，复用 Chromium BoringSSL JA3/JA4 指纹与 `cf_clearance`、
     *    彻底杜绝 OkHttp (Java TLS) 被 Cloudflare 拦截返回 `429 cf-mitigated: challenge` 导致头像一直灰屏的问题！
     * 2. 同时扩容 OkHttp Dispatcher 并发上限，并配合内存 LRU 字节缓存与 Coil 磁盘缓存，消除滑动头像排队饥饿。
     */
    val imageHttpClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            engine {
                config {
                    connectionPool(imageConnectionPool)
                    retryOnConnectionFailure(true)
                    dispatcher(
                        okhttp3.Dispatcher().apply {
                            maxRequests = 64
                            maxRequestsPerHost = 32
                        }
                    )
                    connectTimeout(8, TimeUnit.SECONDS)
                    readTimeout(12, TimeUnit.SECONDS)
                    addInterceptor { chain ->
                        val orig = chain.request()
                        val rawUrl = orig.url.toString()
                        val strippedUrl = rawUrl
                            .replace(Regex("([?&])_r=\\d+(&?)")) { match ->
                                val prefix = match.groupValues[1]
                                val suffix = match.groupValues[2]
                                when {
                                    prefix == "?" && suffix == "&" -> "?"
                                    prefix == "&" && suffix == "&" -> "&"
                                    else -> ""
                                }
                            }
                            .trimEnd('?', '&')
                        // 将遗留落入主站域名的静态头像请求（/user_avatar/linux.do/ 与 /letter_avatar_proxy/）统一分流至官方静态边缘 CDN，
                        // 既免除 WebView Base64 桥接开销，又避免主站高并发图片请求触发 Cloudflare WAF 速率挑战；GIF 动图保持原主站路径走同源桥接
                        val isGifAvatar = strippedUrl.substringBefore('?').endsWith(".gif", ignoreCase = true)
                        val cleanUrl = if (
                            !isGifAvatar && (
                                strippedUrl.startsWith("https://linux.do/user_avatar/linux.do/") ||
                                    strippedUrl.startsWith("http://linux.do/user_avatar/linux.do/") ||
                                    strippedUrl.startsWith("https://linux.do/letter_avatar_proxy/") ||
                                    strippedUrl.startsWith("http://linux.do/letter_avatar_proxy/")
                                )
                        ) {
                            do_.linux.lindo.core.site.LinuxDoSiteRules.resolveStaticAvatarUrl(strippedUrl, 144)
                        } else {
                            strippedUrl
                        }
                        val host = runCatching { java.net.URI(cleanUrl).host?.lowercase() }
                            .getOrNull()
                            ?.takeIf { it.isNotBlank() }
                            ?: orig.url.host.lowercase()

                        if (host == "linux.do" || host.endsWith(".linux.do")) {
                            getCachedImagePayload(cleanUrl)?.let { cached ->
                                val mediaType = cached.contentType.ifBlank { "image/png" }.toMediaTypeOrNull()
                                return@addInterceptor okhttp3.Response.Builder()
                                    .request(orig)
                                    .protocol(okhttp3.Protocol.HTTP_2)
                                    .code(200)
                                    .message("OK")
                                    .header("Content-Type", cached.contentType.ifBlank { "image/png" })
                                    .header("Cache-Control", "public, max-age=604800, immutable")
                                    .body(cached.bytes.toResponseBody(mediaType))
                                    .build()
                            }
                            val binaryFetcher = UserApiKeyAuthManager.webViewBinaryFetcher
                            if (binaryFetcher != null) {
                                val payload = kotlinx.coroutines.runBlocking {
                                    fetchLinuxDoBinarySingleFlight(cleanUrl, binaryFetcher)
                                }
                                if (payload != null && payload.status in 200..299 && payload.bytes.isNotEmpty()) {
                                    val mediaType = payload.contentType.ifBlank { "image/png" }.toMediaTypeOrNull()
                                    return@addInterceptor okhttp3.Response.Builder()
                                        .request(orig)
                                        .protocol(okhttp3.Protocol.HTTP_2)
                                        .code(payload.status)
                                        .message("OK")
                                        .header("Content-Type", payload.contentType.ifBlank { "image/png" })
                                        .header("Cache-Control", "public, max-age=604800, immutable")
                                        .body(payload.bytes.toResponseBody(mediaType))
                                        .build()
                                }
                                // 100% 单栈指纹隔离：在已挂载 Chromium WebView 的平台上，任何 linux.do 主站及子域请求严禁回落至 Java TLS 指纹的 OkHttp，
                                // 彻底杜绝「同一出口 IP 同时出现 BoringSSL 与 Java TLS + Chrome UA」触发 Cloudflare 站点验证！
                                val failCode = payload?.status?.takeIf { it >= 400 } ?: 504
                                val emptyType = "text/plain".toMediaTypeOrNull()
                                return@addInterceptor okhttp3.Response.Builder()
                                    .request(orig)
                                    .protocol(okhttp3.Protocol.HTTP_2)
                                    .code(failCode)
                                    .message("Image bridge fetch failed")
                                    .body(ByteArray(0).toResponseBody(emptyType))
                                    .build()
                            }
                            if (UserApiKeyAuthManager.requireBrowserTransportOnly) {
                                val emptyType = "text/plain".toMediaTypeOrNull()
                                return@addInterceptor okhttp3.Response.Builder()
                                    .request(orig)
                                    .protocol(okhttp3.Protocol.HTTP_2)
                                    .code(503)
                                    .message("Browser image bridge unavailable")
                                    .body(ByteArray(0).toResponseBody(emptyType))
                                    .build()
                            }
                        }

                        val builder = orig.newBuilder().url(cleanUrl)
                        UserApiKeyAuthManager.customUserAgent?.takeIf { it.isNotBlank() }?.let { rawUa ->
                            val safeUa = if (host == "linux.do" || host.endsWith(".linux.do")) {
                                rawUa
                            } else {
                                rawUa.replace("; wv)", ")").replace(" wv)", ")")
                            }
                            builder.header("User-Agent", safeUa)
                        }
                        if (host == "linux.do" || host.endsWith(".linux.do") || host == "cdn.ldstatic.com") {
                            builder.header("Referer", "https://linux.do/")
                            builder.header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                        }
                        if (host == "linux.do" || host.endsWith(".linux.do")) {
                            val imgCookie = CookieSentinel.buildCookieHeader(targetHost = host)
                            if (imgCookie.isNotEmpty()) {
                                builder.header("Cookie", imgCookie)
                            }
                            if (!UserApiKeyAuthManager.hasValidWebSessionCookie()) {
                                UserApiKeyAuthManager.getApiKey()?.takeIf { it.isNotBlank() }?.let { key ->
                                    builder.header("User-Api-Key", key)
                                    builder.header("User-Api-Client-Id", UserApiKeyAuthManager.getClientId())
                                }
                            }
                        }
                        val resp = chain.proceed(builder.build())
                        if ((host == "linux.do" || host.endsWith(".linux.do")) && resp.isSuccessful) {
                            val ct = resp.header("Content-Type").orEmpty()
                            val bytes = resp.body?.bytes() ?: ByteArray(0)
                            if (bytes.isNotEmpty() && !ct.contains("text/html", ignoreCase = true)) {
                                putCachedImagePayload(cleanUrl, UserApiKeyAuthManager.BinaryHttpPayload(resp.code, ct, bytes))
                            }
                            val mediaType = ct.ifBlank { "image/png" }.toMediaTypeOrNull()
                            return@addInterceptor resp.newBuilder()
                                .header("Cache-Control", "public, max-age=604800, immutable")
                                .body(bytes.toResponseBody(mediaType))
                                .build()
                        }
                        resp
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
                ingestSetCookieHeader(setCookie, domain = "linux.do")
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

    private val rateLimitedUntilMs = java.util.concurrent.atomic.AtomicLong(0L)
    private val challengeBlockedUntilMs = java.util.concurrent.atomic.AtomicLong(0L)
    private val foregroundPriorityUntilMs = java.util.concurrent.atomic.AtomicLong(0L)
    private val readRateLimitRecovery = ReadRateLimitRecovery(
        remainingMillis = { (rateLimitedUntilMs.get() - System.currentTimeMillis()).coerceAtLeast(0L) },
        interruptedResponse = {
            if (DesktopNetworkPolicy.enabled && (isChallengeCooldownActive() || DesktopNetworkPolicy.verifying))
                403 to "${CF_MITIGATED_CHALLENGE_PREFIX}<html>需要完成站点验证</html>"
            else null
        }
    )

    private fun noteRateLimitHit(cooldownMs: Long = if (NetworkRecoveryPolicy.enabled) 2_000L else 8_000L) {
        val now = System.currentTimeMillis()
        val until = now + cooldownMs.coerceIn(0L, Long.MAX_VALUE - now)
        rateLimitedUntilMs.updateAndGet { prev -> maxOf(prev, until) }
    }

    fun noteRetryAfter(value: String?) {
        if (NetworkRecoveryPolicy.enabled) noteRateLimitHit(NetworkRecoveryPolicy.retryAfterMillis(value))
    }

    private fun noteChallengeHit(cooldownMs: Long = 60_000L) {
        val until = System.currentTimeMillis() + cooldownMs
        challengeBlockedUntilMs.updateAndGet { prev -> maxOf(prev, until) }
    }

    internal fun isRateLimitedNow(): Boolean =
        System.currentTimeMillis() < rateLimitedUntilMs.get()

    internal fun isChallengeCooldownActive(): Boolean =
        System.currentTimeMillis() < challengeBlockedUntilMs.get()

    internal fun clearTransientRateLimit() {
        rateLimitedUntilMs.set(0L)
        readRateLimitRecovery.reset()
    }

    internal fun clearChallengeCooldown() {
        challengeBlockedUntilMs.set(0L)
        ScreenTrackEngine.clearSendBlock()
    }

    /**
     * 用户主动下拉刷新或点击「重试」前的自愈预备：
     * 1. macOS / Android 保留服务器限流窗口；Android 继续检查原有 WebView 的健康状态；
     * 2. 触发底层传输通道活性自检与坏死连接池重建（如 Android 常驻 WebView 桥接挂起或 VPN 切网后 H2/QUIC 僵尸连接）。
     */
    suspend fun prepareForManualRecovery() {
        if (!NetworkRecoveryPolicy.enabled) clearTransientRateLimit()
        if (!DesktopNetworkPolicy.enabled) {
            clearChallengeCooldown()
            evictHttpConnectionPools()
        }
        try {
            UserApiKeyAuthManager.onPrepareManualRecovery?.invoke()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (err: Throwable) {
            LinDoDiagnosticLogger.warn(
                DiagnosticCategory.NETWORK,
                "ManualRecovery",
                "onPrepareManualRecovery failed",
                throwable = err
            )
        }
    }

    internal fun markForegroundPriority(durationMs: Long = 2_200L) {
        val until = System.currentTimeMillis() + durationMs
        foregroundPriorityUntilMs.updateAndGet { prev -> maxOf(prev, until) }
    }

    private fun isForegroundPriorityActive(): Boolean =
        System.currentTimeMillis() < foregroundPriorityUntilMs.get()

    /**
     * 统一 HTTP 执行器：
     * 1. 若平台已挂载 Chromium WebView 通道 (`webViewHttpExecutor`) 或 `webViewJsonFetcher`，优先走共享 TLS 指纹通道，天然携带 `cf_clearance`、`_t` 与 CSRF Token；
     * 2. 注意：当存在 `_t` 会话 Cookie 或走同源 WebView 时，绝不附加仅含 `one_time_password` 权限的 `User-Api-Key` 标头，避免 Discourse 因 Scope 限制返回 403 Forbidden！
     */
    private suspend fun executeHttp(
        url: String, method: String = "GET", formParams: Map<String, String>? = null,
        extraHeaders: Map<String, String> = emptyMap(), preserveFailure: Boolean = false,
        clientOverride: HttpClient = sharedClient
    ): Pair<Int, String>? {
        if (!NetworkRecoveryPolicy.enabled || method.uppercase() !in setOf("GET", "HEAD")) {
            return executeHttpUncoalesced(url, method, formParams, extraHeaders, preserveFailure, clientOverride)
        }
        val key = "${UserApiKeyAuthManager.sessionGeneration}|$method|$url|${extraHeaders.toSortedMap()}|${System.identityHashCode(clientOverride)}"
        val generation = UserApiKeyAuthManager.sessionGeneration
        return NetworkRecoveryPolicy.shareRead(key) {
            readRateLimitRecovery.read(foreground = extraHeaders["Discourse-Background"] != "true") {
                if (generation != UserApiKeyAuthManager.sessionGeneration) throw CancellationException("Session changed while waiting")
                executeHttpUncoalesced(url, method, formParams, extraHeaders, true, clientOverride)
            }?.takeIf { preserveFailure || isSuccessfulApiResponse(it.first, it.second) }
        }
    }

    private suspend fun executeHttpUncoalesced(
        url: String,
        method: String = "GET",
        formParams: Map<String, String>? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        preserveFailure: Boolean = false,
        clientOverride: HttpClient = sharedClient
    ): Pair<Int, String>? = withContext(Dispatchers.IO) {
        if (DesktopNetworkPolicy.enabled) {
            val verificationProbe = DesktopNetworkPolicy.verifying && java.net.URI(url).path == "/session/current.json"
            if (isChallengeCooldownActive() && !verificationProbe) {
                return@withContext if (preserveFailure) 403 to "${CF_MITIGATED_CHALLENGE_PREFIX}<html>cf-browser-verification</html>" else null
            }
        }
        if (NetworkRecoveryPolicy.enabled && isRateLimitedNow()) return@withContext if (preserveFailure) 429 to "{\"errors\":[\"请求较频繁，请稍后再试\"]}" else null
        val isReadMethod = method.equals("GET", true) || method.equals("HEAD", true)
        val winningCookies = CookieSentinel.resolveWinningCookies()
        val rawT = winningCookies["_t"]?.value.orEmpty()
        val hasWebCookie = rawT.isNotBlank() && rawT != "demo_session_token"
        val apiKey = UserApiKeyAuthManager.getApiKey()?.takeIf { it.isNotBlank() }
        val hasApiKey = apiKey != null
        val isLoggedIn = hasWebCookie || hasApiKey
        val presenceHeaders = UserPresenceService.buildPresenceHeaders(
            isBackgroundTimingRequest = extraHeaders["Discourse-Background"] == "true",
            isLoggedIn = isLoggedIn,
            silenceLogger = url.contains("/topics/timings"),
            includeOrigin = !isReadMethod
        )
        val mergedHeaders = buildMap {
            put("Accept", "application/json, text/javascript, */*; q=0.01")
            put("X-Requested-With", "XMLHttpRequest")
            presenceHeaders.forEach { (k, v) -> put(k, v) }
            // 当不存在 Web 会话 Cookie 且持有有效 User-Api-Key 时，附带 User-Api-Key 与 User-Api-Client-Id（含 WebView 通道）
            if (!hasWebCookie && apiKey != null) {
                put("User-Api-Key", apiKey)
                put("User-Api-Client-Id", UserApiKeyAuthManager.getClientId())
            }
            extraHeaders.forEach { (k, v) -> put(k, v) }
        }

        val isForegroundRequest = mergedHeaders["Discourse-Background"] != "true"
        if (isForegroundRequest) {
            inFlightForegroundApiCount.incrementAndGet()
        } else if (method.equals("GET", ignoreCase = true)) {
            var waited = 0L
            while (waited < 600L && inFlightForegroundApiCount.get() > 0) {
                kotlinx.coroutines.delay(150L)
                waited += 150L
            }
        }
        val isSilentTiming = url.contains("/topics/timings")
        try {
            if (UserApiKeyAuthManager.requireBrowserTransportOnly && UserApiKeyAuthManager.webViewHttpExecutor == null) {
                return@withContext null
            }
            suspend fun callWebView(): Pair<Int, String>? {
                val executor = UserApiKeyAuthManager.webViewHttpExecutor ?: return null
                val startMs = System.currentTimeMillis()
                return try {
                    val result = executor(url, method, formParams, mergedHeaders)?.takeIf { it.first != 0 }
                    val elapsedMs = System.currentTimeMillis() - startMs
                    if (!isSilentTiming && (result == null || !isSuccessfulApiResponse(result.first, result.second))) {
                        val cfFlag = if (result != null && looksLikeCloudflareChallenge(result.second)) "challenge" else null
                        LinDoDiagnosticLogger.recordHttpFailure(
                            method = method,
                            url = url,
                            statusCode = result?.first ?: 0,
                            transport = "WebView",
                            elapsedMs = elapsedMs,
                            cfMitigated = cfFlag,
                            responseSnippet = result?.second?.removePrefix(CF_MITIGATED_CHALLENGE_PREFIX)
                        )
                    }
                    result
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (err: Exception) {
                    if (!isSilentTiming) {
                        LinDoDiagnosticLogger.recordHttpFailure(
                            method = method,
                            url = url,
                            statusCode = 0,
                            transport = "WebView",
                            elapsedMs = System.currentTimeMillis() - startMs,
                            error = err
                        )
                    }
                    null
                }
            }
            // Mutations need the browser session's CSRF token. Never retry a write through a second transport.
            if (!isReadMethod && UserApiKeyAuthManager.webViewHttpExecutor != null) {
                val writeResult = callWebView()
                if (writeResult?.first == 429 && (!NetworkRecoveryPolicy.enabled || !isRateLimitedNow())) noteRateLimitHit()
                if (writeResult != null && looksLikeCloudflareChallenge(writeResult.second)) {
                    noteChallengeHit()
                } else if (writeResult != null && isSuccessfulApiResponse(writeResult.first, writeResult.second)) {
                    challengeBlockedUntilMs.set(0L)
                }
                return@withContext writeResult?.takeIf {
                    preserveFailure || isSuccessfulApiResponse(it.first, it.second)
                }
            }

            // 只要平台已挂载同源 Chromium WebView 通道（Android），所有请求一律走 WebView BoringSSL 通道，
            // 对前台只读请求在瞬时网络抖动或非 Cloudflare 429/5xx 时退避重试一次，
            // 同时严禁回落至缺少 cf_clearance 且为 Java TLS 指纹的 OkHttp sharedClient！
            val maxReadAttempts = if (isReadMethod && isForegroundRequest) 2 else 1
            var firstBrowserResult: Pair<Int, String>? = null
            if (UserApiKeyAuthManager.webViewHttpExecutor != null) {
                for (attempt in 0 until maxReadAttempts) {
                    val candidate = callWebView()
                    if (candidate?.first == 429 && (!NetworkRecoveryPolicy.enabled || !isRateLimitedNow())) noteRateLimitHit()
                    if (candidate != null && looksLikeCloudflareChallenge(candidate.second)) {
                        noteChallengeHit()
                    }
                    if (candidate != null && isSuccessfulApiResponse(candidate.first, candidate.second)) {
                        if (isForegroundRequest) {
                            if (!NetworkRecoveryPolicy.enabled) rateLimitedUntilMs.set(0L)
                            UserPresenceService.recordSuccessfulForegroundRequest()
                        }
                        return@withContext candidate
                    }
                    if (candidate != null) {
                        firstBrowserResult = candidate
                    }
                    val retryable = candidate == null || (
                        candidate.first in setOf(429, 500, 502, 503, 504) &&
                            !looksLikeCloudflareChallenge(candidate.second)
                    )
                    if (!retryable || attempt >= maxReadAttempts - 1 || (NetworkRecoveryPolicy.enabled && candidate?.first == 429)) break
                    if (candidate == null) {
                        try {
                            UserApiKeyAuthManager.onPrepareManualRecovery?.invoke()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                        }
                    }
                    val backoffMs = if (candidate?.first == 429) 1_200L else 320L
                    kotlinx.coroutines.delay(backoffMs)
                }
                return@withContext if (preserveFailure) firstBrowserResult else null
            }

            fun decorateOkHttpChallengeBody(
                headers: Headers,
                rawBody: String
            ): Triple<String, String?, String?> {
                val cfMitigated = headers["cf-mitigated"]
                val cfRay = headers["cf-ray"]
                val taggedBody = if (
                    cfMitigated?.contains("challenge", ignoreCase = true) == true &&
                    !rawBody.startsWith(CF_MITIGATED_CHALLENGE_PREFIX)
                ) {
                    "$CF_MITIGATED_CHALLENGE_PREFIX$rawBody"
                } else {
                    rawBody
                }
                return Triple(taggedBody, cfMitigated, cfRay)
            }

            var lastResponse: Pair<Int, String>? = null
            for (attempt in 0 until maxReadAttempts) {
                val attemptStartMs = System.currentTimeMillis()
                try {
                    val cfTlsBoundCookies = setOf("cf_clearance", "__cf_bm", "_cfuvid", "cf_chl_rc_i", "cf_chl_rc_ni")
                    fun currentSafeCookieHeader(): String = CookieSentinel.resolveWinningCookies(targetHost = "linux.do")
                        .entries
                        .filterNot { it.key == "_t" && it.value.value == "demo_session_token" }
                        // 若当前平台存在 WebView，禁止将绑定了 Chromium TLS 指纹的 cf_clearance 放到 OkHttp 请求上发送，防止被 Cloudflare 判定指纹劫持而吊销
                        .filterNot { (UserApiKeyAuthManager.webViewHttpExecutor != null || UserApiKeyAuthManager.requireBrowserTransportOnly) && it.key in cfTlsBoundCookies }
                        .joinToString("; ") { "${it.key}=${it.value.value}" }
                    val requestHeaders = mergedHeaders.toMutableMap()
                    if (!hasWebCookie && !hasApiKey) {
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
                    var response = executeApiRequest(clientOverride, url, method, formParams, requestHeaders)
                    response.headers.getAll("Set-Cookie")?.forEach { setCookie ->
                        ingestSetCookieHeader(setCookie, domain = "linux.do")
                    }
                    var status = response.status.value
                    var (text, cfMitigated, cfRay) = decorateOkHttpChallengeBody(response.headers, response.bodyAsText())
                    if (status == 429 && !NetworkRecoveryPolicy.enabled) noteRateLimitHit()
                    if (looksLikeCloudflareChallenge(text)) noteChallengeHit()
                    if (isMutation && status in setOf(403, 422) && !looksLikeCloudflareChallenge(text) && text.contains("CSRF", ignoreCase = true) && hasWebCookie) {
                        val freshCsrf = fetchCsrfTokenViaSharedClient(forceRefresh = true)
                        if (!freshCsrf.isNullOrBlank()) {
                            requestHeaders["X-CSRF-Token"] = freshCsrf
                            val updatedCookieHeader = currentSafeCookieHeader()
                            if (updatedCookieHeader.isNotEmpty()) requestHeaders["Cookie"] = updatedCookieHeader
                            response = executeApiRequest(clientOverride, url, method, formParams, requestHeaders)
                            response.headers.getAll("Set-Cookie")?.forEach { setCookie ->
                                ingestSetCookieHeader(setCookie, domain = "linux.do")
                            }
                            status = response.status.value
                            val decorated = decorateOkHttpChallengeBody(response.headers, response.bodyAsText())
                            text = decorated.first
                            cfMitigated = decorated.second
                            cfRay = decorated.third
                            if (looksLikeCloudflareChallenge(text)) noteChallengeHit()
                        }
                    }
                    // 若携带 Discourse-Logged-In 头遭遇非 Cloudflare 403（例如 Cookie 过期被 Discourse check_xhr 拦截），剥离该头自动重试一次
                    if (status == 403 && !looksLikeCloudflareChallenge(text) && requestHeaders.containsKey("Discourse-Logged-In") && method.equals("GET", true)) {
                        requestHeaders.remove("Discourse-Logged-In")
                        response = executeApiRequest(clientOverride, url, method, formParams, requestHeaders)
                        response.headers.getAll("Set-Cookie")?.forEach { setCookie ->
                            ingestSetCookieHeader(setCookie, domain = "linux.do")
                        }
                        status = response.status.value
                        val decorated = decorateOkHttpChallengeBody(response.headers, response.bodyAsText())
                        text = decorated.first
                        cfMitigated = decorated.second
                        cfRay = decorated.third
                        if (looksLikeCloudflareChallenge(text)) noteChallengeHit()
                    }
                    // Include a 429 returned by an authentication/CSRF recovery request as well.
                    if (status == 429 && NetworkRecoveryPolicy.enabled) noteRetryAfter(response.headers["Retry-After"])
                    // 避免在已挂载 WebView 的平台上，用 OkHttp 缺少 cf_clearance 触发的 Cloudflare HTML 403 覆盖真实响应
                    val trimmedBody = text.removePrefix(CF_MITIGATED_CHALLENGE_PREFIX).trimStart()
                    val isHtmlChallenge403 = status == 403 &&
                        UserApiKeyAuthManager.webViewHttpExecutor != null &&
                        !trimmedBody.startsWith("{") && !trimmedBody.startsWith("[")
                    if (!isHtmlChallenge403) {
                        lastResponse = status to text
                    }
                    if (isSuccessfulApiResponse(status, text)) {
                        if (isForegroundRequest) {
                            if (!NetworkRecoveryPolicy.enabled) rateLimitedUntilMs.set(0L)
                            if (!DesktopNetworkPolicy.enabled) {
                                challengeBlockedUntilMs.set(0L)
                            }
                            UserPresenceService.recordSuccessfulForegroundRequest()
                        }
                        return@withContext status to text
                    }
                    if (!isSilentTiming) {
                        LinDoDiagnosticLogger.recordHttpFailure(
                            method = method,
                            url = url,
                            statusCode = status,
                            transport = "OkHttp#${attempt + 1}",
                            elapsedMs = System.currentTimeMillis() - attemptStartMs,
                            cfMitigated = cfMitigated ?: if (looksLikeCloudflareChallenge(text)) "challenge-html" else null,
                            cfRay = cfRay,
                            responseSnippet = trimmedBody
                        )
                    }
                    if (NetworkRecoveryPolicy.enabled && status == 429) return@withContext if (preserveFailure) status to text else null
                    if (looksLikeCloudflareChallenge(text) || status in listOf(404, 410) || (status == 403 && !isHtmlChallenge403)) {
                        break
                    }
                    if (!DesktopNetworkPolicy.enabled && status in 500..599 && attempt < maxReadAttempts - 1) {
                        evictHttpConnectionPools()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (err: Exception) {
                    if (!isSilentTiming) {
                        LinDoDiagnosticLogger.recordHttpFailure(
                            method = method,
                            url = url,
                            statusCode = 0,
                            transport = "OkHttp#${attempt + 1}",
                            elapsedMs = System.currentTimeMillis() - attemptStartMs,
                            error = err
                        )
                    }
                    evictHttpConnectionPools()
                }
                if (attempt < maxReadAttempts - 1) {
                    val backoffMs = if (lastResponse?.first == 429) 1200L else 350L * (attempt + 1)
                    kotlinx.coroutines.delay(backoffMs)
                }
            }
            val browserResult = firstBrowserResult ?: callWebView()
            if (browserResult != null && isSuccessfulApiResponse(browserResult.first, browserResult.second)) {
                if (isForegroundRequest) UserPresenceService.recordSuccessfulForegroundRequest()
                return@withContext browserResult
            }

            if (preserveFailure) {
                val candidates = listOfNotNull(browserResult, lastResponse)
                candidates.firstOrNull { looksLikeCloudflareChallenge(it.second) }
                    ?: candidates.firstOrNull {
                        val stripped = it.second.removePrefix(CF_MITIGATED_CHALLENGE_PREFIX).trimStart()
                        stripped.startsWith("{") || stripped.startsWith("[")
                    }
                    ?: candidates.firstOrNull()
            } else {
                null
            }
        } finally {
            if (isForegroundRequest) {
                inFlightForegroundApiCount.decrementAndGet()
            }
        }
    }

    /**
     * 静默无感兑换系统浏览器回调的 OTP 令牌（免打开 WebView 页面，从而规避整页 HTML 导航的 Cloudflare Turnstile 验证）：
     * - 先通过不跟随重定向的专用客户端获取 `/session/csrf.json` 与 `_forum_session`；
     * - 再 POST `/session/otp/$otp` 直接捕获服务端下发的 `_t` 会话 Cookie 并同步至系统 `CookieManager`；
     * - 若直连兑换成功返回 `true`（直接完成登录），若被拦截则返回 `false` 交由 WebView 兜底。
     */
    suspend fun redeemOtpSilently(otp: String): Boolean = withContext(Dispatchers.IO) {
        if (otp.isBlank()) {
            if (!UserApiKeyAuthManager.getApiKey().isNullOrBlank()) {
                val epochBefore = UserApiKeyAuthManager.authStateNotifyEpoch
                val profile = fetchCurrentUserProfile()
                if (profile != null || UserApiKeyAuthManager.isAuthorized()) {
                    UserApiKeyAuthManager.persistSessionQuietly()
                    if (UserApiKeyAuthManager.authStateNotifyEpoch == epochBefore) {
                        UserApiKeyAuthManager.onAuthStateChanged?.invoke()
                    }
                    return@withContext true
                }
            }
            return@withContext false
        }

        suspend fun verifyAndFinalizeOtpSession(previousT: String): Boolean {
            val currentT = CookieSentinel.resolveWinningCookies(targetHost = "linux.do")["_t"]?.value.orEmpty()
            if (currentT.isBlank() || currentT == "demo_session_token") {
                return false
            }
            val epochBefore = UserApiKeyAuthManager.authStateNotifyEpoch
            if (currentT == previousT) {
                val preservedApiKey = UserApiKeyAuthManager.getApiKey()
                val preservedCachedProfile = UserApiKeyAuthManager.fullProfileCache
                UserApiKeyAuthManager.fullProfileCache = null
                val profile = UserApiKeyAuthManager.withServerAuthInvalidationSuppressed {
                    fetchCurrentUserProfile()
                }
                if (profile == null) {
                    if (!preservedApiKey.isNullOrBlank() && UserApiKeyAuthManager.getApiKey() != preservedApiKey) {
                        UserApiKeyAuthManager.restoreApiKeyQuietly(preservedApiKey)
                    }
                    if (UserApiKeyAuthManager.fullProfileCache == null && preservedCachedProfile != null && UserApiKeyAuthManager.isAuthorized()) {
                        UserApiKeyAuthManager.fullProfileCache = preservedCachedProfile
                    }
                    return false
                }
                UserApiKeyAuthManager.clearApiKeyQuietly()
                UserApiKeyAuthManager.persistSessionQuietly()
                if (UserApiKeyAuthManager.authStateNotifyEpoch == epochBefore) {
                    UserApiKeyAuthManager.onAuthStateChanged?.invoke()
                }
                return true
            }
            UserApiKeyAuthManager.clearApiKeyQuietly()
            val profile = fetchCurrentUserProfile()
            UserApiKeyAuthManager.persistSessionQuietly()
            if (UserApiKeyAuthManager.authStateNotifyEpoch == epochBefore) {
                UserApiKeyAuthManager.onAuthStateChanged?.invoke()
            }
            return profile != null || UserApiKeyAuthManager.isAuthorized()
        }

        try {
            val previousT = CookieSentinel.resolveWinningCookies(targetHost = "linux.do")["_t"]?.value
                ?.takeUnless { it == "demo_session_token" }
                .orEmpty()
            if (UserApiKeyAuthManager.webViewHttpExecutor != null) {
                val otpRes = executeHttp(
                    url = "https://linux.do/session/otp/${otp.trim()}",
                    method = "POST",
                    formParams = emptyMap(),
                    preserveFailure = true
                )
                if (otpRes != null && otpRes.first in 200..399 && !looksLikeCloudflareChallenge(otpRes.second)) {
                    return@withContext verifyAndFinalizeOtpSession(previousT)
                }
                return@withContext false
            }
            if (UserApiKeyAuthManager.requireBrowserTransportOnly) {
                return@withContext false
            }

            if (exchangeOtpSessionCookies(otp, otpExchangeClient)) {
                return@withContext verifyAndFinalizeOtpSession(previousT)
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            println("LinDoAuth: redeemOtpSilently failed: ${t.message}")
        }
        false
    }

    private fun parseFollowPostsIntoTopicSummaries(
        postsArr: JsonArray,
        filterCategoryId: Int? = null,
        filterTag: String? = null
    ): List<TopicSummary> {
        val seenTopicIds = HashSet<Int>()
        val summaries = mutableListOf<TopicSummary>()
        val cleanTagFilter = filterTag?.trim()?.takeIf { it.isNotEmpty() }
        postsArr.forEach { elem ->
            val pObj = elem as? JsonObject ?: return@forEach
            val topicObj = pObj["topic"] as? JsonObject
            val tid = pObj["topic_id"]?.jsonPrimitive?.intOrNull
                ?: topicObj?.get("id")?.jsonPrimitive?.intOrNull
                ?: return@forEach
            if (!seenTopicIds.add(tid)) return@forEach

            val catId = pObj["category_id"]?.jsonPrimitive?.intOrNull
                ?: topicObj?.get("category_id")?.jsonPrimitive?.intOrNull
                ?: 4
            if (filterCategoryId != null) {
                val catParent = findCategoryById(catId)?.parentId
                if (catId != filterCategoryId && catParent != filterCategoryId) return@forEach
            }
            val tags = parseTagsArray((topicObj?.get("tags") as? JsonArray) ?: (pObj["tags"] as? JsonArray))
            if (cleanTagFilter != null && tags.none { it.equals(cleanTagFilter, ignoreCase = true) }) {
                return@forEach
            }

            val (catName, catColor) = resolveCategoryMeta(catId)
            val userObj = pObj["user"] as? JsonObject
            val uid = userObj?.get("id")?.jsonPrimitive?.intOrNull
                ?: pObj["user_id"]?.jsonPrimitive?.intOrNull
                ?: 0
            val uname = userObj?.get("username")?.jsonPrimitive?.contentOrNull
                ?: pObj["username"]?.jsonPrimitive?.contentOrNull
                ?: "linuxdo"
            val avatarTpl = userObj?.get("avatar_template")?.jsonPrimitive?.contentOrNull
                ?: pObj["avatar_template"]?.jsonPrimitive?.contentOrNull
                ?: ""
            cacheUserAvatar(uname, avatarTpl)
            val poster = TopicUser(
                id = uid,
                username = uname,
                name = userObj?.get("name")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                avatarTemplate = avatarTpl,
                animatedAvatar = userObj?.get("animated_avatar")?.jsonPrimitive?.contentOrNull,
                primaryGroupName = userObj?.get("primary_group_name")?.jsonPrimitive?.contentOrNull,
                flairName = userObj?.get("flair_name")?.jsonPrimitive?.contentOrNull,
                flairUrl = userObj?.get("flair_url")?.jsonPrimitive?.contentOrNull,
                flairBgColor = userObj?.get("flair_bg_color")?.jsonPrimitive?.contentOrNull,
                flairColor = userObj?.get("flair_color")?.jsonPrimitive?.contentOrNull,
                flairGroupId = userObj?.get("flair_group_id")?.jsonPrimitive?.intOrNull,
                trustLevel = userObj?.get("trust_level")?.jsonPrimitive?.intOrNull ?: 1,
                admin = userObj?.get("admin")?.jsonPrimitive?.booleanOrNull ?: false,
                moderator = userObj?.get("moderator")?.jsonPrimitive?.booleanOrNull ?: false
            )
            cacheTopicUserVisuals(poster)

            val postNumber = pObj["post_number"]?.jsonPrimitive?.intOrNull ?: 1
            val rawPostId = pObj["id"]?.jsonPrimitive?.intOrNull ?: 0
            if (rawPostId > 0) {
                putBounded(postAuthorCache, rawPostId, poster)
            }
            if (postNumber <= 1 && tid > 0) {
                putBounded(topicOpCache, tid, poster)
            }
            val rawPostsCount = topicObj?.get("posts_count")?.jsonPrimitive?.intOrNull
                ?: postNumber.coerceAtLeast(1)
            val rawHighest = maxOf(
                topicObj?.get("highest_post_number")?.jsonPrimitive?.intOrNull ?: rawPostsCount,
                postNumber,
                rawPostsCount
            )
            val rawTitle = topicObj?.get("title")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: pObj["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: topicObj?.get("fancy_title")?.jsonPrimitive?.contentOrNull?.let { decodeDiscourseHtmlSnippet(it) }?.takeIf { it.isNotBlank() }
                ?: LinDoCommonCopy.TOPIC_NUMBER_FALLBACK.format(tid)
            val fancyTitle = topicObj?.get("fancy_title")?.jsonPrimitive?.contentOrNull.orEmpty()
            val slug = topicObj?.get("slug")?.jsonPrimitive?.contentOrNull
                ?: pObj["slug"]?.jsonPrimitive?.contentOrNull
                ?: ""
            val rawExcerpt = pObj["excerpt"]?.jsonPrimitive?.contentOrNull
                ?: pObj["cooked"]?.jsonPrimitive?.contentOrNull
            val cleanedExcerpt = extractPlainExcerptFromHtml(rawExcerpt).takeIf { it.isNotBlank() }
                ?.also { putBounded(topicExcerptCache, tid, it) }
                ?: topicExcerptCache[tid]?.takeIf { it.isNotBlank() }
            val createdIso = pObj["created_at"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val confirmedFloor = serverConfirmedReadFloor[tid]
            val rawUnseen = topicObj?.get("unseen")?.jsonPrimitive?.booleanOrNull ?: false
            val effectiveUnseen = if (confirmedFloor != null && confirmedFloor >= 1) false else rawUnseen

            summaries.add(
                TopicSummary(
                    id = tid,
                    title = rawTitle,
                    fancyTitle = fancyTitle,
                    slug = slug,
                    postsCount = rawPostsCount,
                    highestPostNumber = rawHighest,
                    replyCount = topicObj?.get("reply_count")?.jsonPrimitive?.intOrNull ?: (rawPostsCount - 1).coerceAtLeast(0),
                    views = topicObj?.get("views")?.jsonPrimitive?.intOrNull ?: 0,
                    likeCount = topicObj?.get("like_count")?.jsonPrimitive?.intOrNull ?: 0,
                    categoryId = catId,
                    categoryName = catName,
                    categoryColorHex = catColor,
                    tags = tags,
                    excerpt = cleanedExcerpt,
                    pinned = topicObj?.get("pinned")?.jsonPrimitive?.booleanOrNull ?: false,
                    unseen = effectiveUnseen,
                    unreadPosts = 0,
                    hasAcceptedAnswer = topicObj?.get("has_accepted_answer")?.jsonPrimitive?.booleanOrNull ?: false,
                    lastPostedAt = formatRelativeTime(createdIso).ifBlank { LinDoCommonCopy.ONE_MINUTE.text },
                    createdAtIso = createdIso,
                    lastPostedAtIso = createdIso,
                    originalPoster = poster,
                    participantAvatars = listOf(poster)
                )
            )
        }
        return summaries
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
        val sortMode = path.trimStart('/').substringBefore('?').removeSuffix(".json").lowercase()
        val headers = if (background) mapOf("Discourse-Background" to "true") else emptyMap()
        if (sortMode == "follow") {
            if (!UserApiKeyAuthManager.isAuthorized()) {
                throw ApiReadException(ApiFailureKind.Authentication)
            }
            val uname = UserApiKeyAuthManager.currentUser?.username?.takeIf {
                it.isNotBlank() && it != "linuxdo_user"
            } ?: fetchCurrentUserProfile(background = background)?.username?.takeIf {
                it.isNotBlank() && it != "linuxdo_user"
            } ?: throw ApiReadException(ApiFailureKind.Authentication)

            val cleanPage = page.coerceAtLeast(0)
            val cursorPrefix = "${uname.trim().lowercase()}:"
            val cursorQuery = if (cleanPage == 0) {
                followFeedCursors.keys.removeIf { it.startsWith(cursorPrefix) }
                ""
            } else {
                val cursor = followFeedCursors["$cursorPrefix$cleanPage"]?.takeIf { it.isNotBlank() }
                    ?: return@withContext emptyList()
                "&created_before=${encodeUrlSegment(cursor)}"
            }
            if (!background) {
                markForegroundPriority(2_200L)
            }
            val primaryUrl = "https://linux.do/follow/posts/${encodeUrlSegment(uname)}.json?limit=30$cursorQuery"
            val primaryRes = executeHttp(url = primaryUrl, extraHeaders = headers, preserveFailure = true)
            val effectiveRes = if (primaryRes?.first == 404) {
                val offsetParam = if (cleanPage > 0) "?offset=${cleanPage * 20}" else ""
                val feedFallback = executeHttp(
                    url = "https://linux.do/u/${encodeUrlSegment(uname)}/follow/feed.json$offsetParam",
                    extraHeaders = headers,
                    preserveFailure = true
                )
                if (feedFallback?.first == 404) {
                    val pageQuery = if (cleanPage > 0) "?no_definitions=true&page=$cleanPage" else "?no_definitions=true"
                    executeHttp(
                        url = "https://linux.do/follow.json$pageQuery",
                        extraHeaders = headers,
                        preserveFailure = true
                    ) ?: feedFallback
                } else {
                    feedFallback ?: primaryRes
                }
            } else {
                primaryRes
            }

            val root = readApiObject(effectiveRes)
            val postsArr = (root["posts"] as? JsonArray) ?: (root["feed"] as? JsonArray)
            if (postsArr != null) {
                lastFollowUsedTopicListFallback = false
                val hasMore = (root["extras"] as? JsonObject)?.get("has_more")?.jsonPrimitive?.booleanOrNull == true
                val lastCreatedAt = (postsArr.lastOrNull() as? JsonObject)
                    ?.get("created_at")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                val nextKey = "$cursorPrefix${cleanPage + 1}"
                if (hasMore && lastCreatedAt != null) {
                    followFeedCursors[nextKey] = lastCreatedAt
                } else {
                    followFeedCursors.remove(nextKey)
                }
                return@withContext parseFollowPostsIntoTopicSummaries(
                    postsArr = postsArr,
                    filterCategoryId = categoryId,
                    filterTag = tag
                )
            }
            if (root["topic_list"] is JsonObject) {
                lastFollowUsedTopicListFallback = true
                val parsed = parseTopicListRoot(root)
                return@withContext parsed.filter { item ->
                    val catOk = categoryId == null || item.categoryId == categoryId ||
                        findCategoryById(item.categoryId)?.parentId == categoryId
                    val tagOk = tag.isNullOrBlank() || item.tags.any { it.equals(tag.trim(), ignoreCase = true) }
                    catOk && tagOk
                }
            }
            throw ApiReadException(ApiFailureKind.InvalidPayload)
        }

        val sortSuffix = if (sortMode in setOf("top", "new", "unread", "hot")) "/l/$sortMode.json" else ".json"

        val basePath = when {
            !tag.isNullOrBlank() -> {
                val encodedTag = encodeUrlSegment(tag.trim())
                "tag/$encodedTag$sortSuffix"
            }
            categoryId != null -> {
                var cat = findCategoryById(categoryId)
                if (cat != null && !cat.isVerifiedFromServer && syncedCategoriesSessionGen != UserApiKeyAuthManager.sessionGeneration) {
                    val origParent = cat.parentId
                    val origSubLabel = cat.subLabel
                    runCatching { syncSiteCategories(background = false) }
                    cat = findCategoryById(categoryId)
                        ?: origParent?.let { pid ->
                            subCategoriesByParentId[pid]?.firstOrNull { it.subLabel == origSubLabel }
                        }
                }
                if (cat != null && !cat.isVerifiedFromServer && cat.readRestricted) {
                    val deniedReason = if (!UserApiKeyAuthManager.isAuthorized()) {
                        LinDoNetworkCopy.GUEST_CATEGORY_LOGIN_REQUIRED.text
                    } else {
                        LinDoNetworkCopy.CATEGORY_TRUST_LEVEL_DENIED.text
                    }
                    throw ApiReadException(ApiFailureKind.PermissionDenied, customMessage = deniedReason)
                }
                val catPrefix = when {
                    cat == null -> "c/$categoryId"
                    cat.hasServerParent && !cat.parentSlug.isNullOrBlank() && !cat.slug.contains("/") ->
                        "c/${cat.parentSlug}/${cat.slug}/${cat.id}"
                    cat.isVerifiedFromServer -> "c/${cat.slug}/${cat.id}"
                    else -> "c/${cat.id}"
                }
                "$catPrefix$sortSuffix"
            }
            else -> path
        }

        val queryParams = mutableListOf("no_definitions=true")
        if (sortMode == "top" && !topPeriod.isNullOrBlank()) {
            queryParams.add("period=$topPeriod")
        }
        if (page > 0) {
            queryParams.add("page=$page")
        }
        if (!background) {
            markForegroundPriority(2_200L)
        }
        val sep = if (basePath.contains("?")) "&" else "?"
        val url = "https://linux.do/${basePath.trimStart('/')}$sep${queryParams.joinToString("&")}"
        val root = readApiObject(executeHttp(url = url, extraHeaders = headers, preserveFailure = true))
        (root["topic_list"] as? JsonObject ?: throw ApiReadException(ApiFailureKind.InvalidPayload)).requireArray("topics")
        parseTopicListRoot(root)
    }

    fun computeHasMoreTopics(
        path: String,
        currentPage: Int,
        fetchedCount: Int
    ): Boolean {
        val cleanMode = path.removePrefix("/").substringBefore(".json").substringBefore("?").lowercase()
        if (cleanMode == "follow") {
            if (lastFollowUsedTopicListFallback) return fetchedCount >= 15
            val uname = UserApiKeyAuthManager.currentUser?.username?.trim()?.lowercase().orEmpty()
            if (uname.isBlank()) return false
            val nextKey = "$uname:${currentPage.coerceAtLeast(0) + 1}"
            return !followFeedCursors[nextKey].isNullOrBlank()
        }
        return fetchedCount >= 15
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
        markForegroundPriority(2_600L)
        val s = summaryFallback
        if (topicId <= 0) {
            return@withContext TopicDetailModel(
                id = 0,
                title = s?.title ?: LinDoNetworkCopy.INTERACTION_DETAIL_TITLE.text,
                categoryName = s?.categoryName ?: LinDoNetworkCopy.COMMUNITY_NOTIFICATION_CATEGORY.text,
                categoryColorHex = s?.categoryColorHex ?: "0E76BD",
                tags = s?.tags.orEmpty(),
                views = s?.views ?: 0,
                likeCount = s?.likeCount ?: 0,
                postsCount = 1,
                posts = emptyList(),
                loadError = LinDoNetworkCopy.NOTIFICATION_NO_TOPIC_BODY.text
            )
        }
        val queryParts = mutableListOf<String>()
        if (trackVisit) queryParts.add("track_visit=true")
        if (!filterUsername.isNullOrBlank()) queryParts.add("username_filters=${encodeUrlSegment(filterUsername)}")
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

        var res = executeHttp(url = url, method = "GET", extraHeaders = extraHeaders, preserveFailure = true)
        if (trackVisit && res != null && res.first in 500..599 && !looksLikeCloudflareChallenge(res.second)) {
            // Discourse track_visit=true 写计数锁偶发返回 502/503，清理连接池后降级为纯只读路径重试一次
            evictHttpConnectionPools()
            val fallbackQuery = if (!filterUsername.isNullOrBlank()) {
                "?username_filters=${encodeUrlSegment(filterUsername)}"
            } else ""
            val fallbackRes = executeHttp(
                url = "https://linux.do/t/$pathSegment$fallbackQuery",
                method = "GET",
                extraHeaders = emptyMap(),
                preserveFailure = true
            )
            if (fallbackRes != null) {
                res = fallbackRes
            }
        }
        val statusCode = res?.first ?: 0
        val text = res?.second?.removePrefix(CF_MITIGATED_CHALLENGE_PREFIX)
        if (statusCode in 200..299 && text != null && text.trimStart().startsWith("{") && !looksLikeCloudflareChallenge(res?.second)) {
            try {
                val root = json.parseToJsonElement(text).jsonObject
                val detail = parseLiveTopicDetail(root, summaryFallback)
                if (detail.posts.isNotEmpty()) {
                    val serverLastRead = detail.lastReadPostNumber
                    if (serverLastRead != null && serverLastRead > 0) {
                        recordServerConfirmedReadFloor(topicId, serverLastRead, notify = true)
                    }
                    return@withContext detail
                }
            } catch (parseErr: Throwable) {
                LinDoDiagnosticLogger.warn(
                    DiagnosticCategory.NETWORK,
                    "TopicDetailParse",
                    "Failed parsing /t/$pathSegment (len=${text.length})",
                    throwable = parseErr
                )
            }
        }

        val isCfChallenge = looksLikeCloudflareChallenge(res?.second)
        val isAuthorizedNow = UserApiKeyAuthManager.isAuthorized()
        val specificError = when {
            statusCode in listOf(404, 410) && !isCfChallenge -> LinDoNetworkCopy.TOPIC_UNAVAILABLE.text
            isCfChallenge -> LinDoNetworkCopy.SITE_VERIFICATION_REQUIRED.text
            statusCode == 401 -> if (isAuthorizedNow) {
                LinDoNetworkCopy.AUTH_EXPIRED_SHORT.text
            } else {
                LinDoNetworkCopy.GUEST_TOPIC_LOGIN_REQUIRED.text
            }
            statusCode == 403 -> if (!isAuthorizedNow) {
                LinDoNetworkCopy.GUEST_TOPIC_LOGIN_REQUIRED.text
            } else {
                extractActionErrorMessage(res, LinDoNetworkCopy.TOPIC_PERMISSION_DENIED.text)
            }
            statusCode == 429 -> LinDoNetworkCopy.RATE_LIMITED_SHORT.text
            statusCode in 500..599 -> LinDoNetworkCopy.SERVER_UNAVAILABLE_SHORT.text
            else -> LinDoNetworkCopy.CONNECTION_FAILED.text
        }

        // Preserve the real summary, but never fabricate a first post from an excerpt on failure.
        TopicDetailModel(
            id = s?.id ?: topicId,
            title = s?.title ?: LinDoCommonCopy.TOPIC_NUMBER_FALLBACK.format(topicId),
            categoryName = s?.categoryName ?: LinDoNetworkCopy.DEFAULT_TECH_CATEGORY.text,
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
        knownAuthorBadges: Map<Int, List<GrantedBadge>> = emptyMap(),
        lastReadPostNumber: Int? = getServerConfirmedReadFloor(topicId)
    ): List<PostItemModel> = withContext(Dispatchers.IO) {
        if (postIds.isEmpty()) return@withContext emptyList()
        markForegroundPriority(2_200L)
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
                    lastReadPostNumber = lastReadPostNumber,
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

    suspend fun fetchMoreTopicPosts(
        topicId: Int,
        postIds: List<Int>,
        knownAuthorBadges: List<GrantedBadge>,
        lastReadPostNumber: Int? = getServerConfirmedReadFloor(topicId)
    ): List<PostItemModel> = fetchMoreTopicPosts(
        topicId = topicId,
        postIds = postIds,
        knownAuthorBadges = emptyMap<Int, List<GrantedBadge>>(),
        lastReadPostNumber = lastReadPostNumber
    )

    private fun isConfirmedDiscourseUnauthenticatedSessionResponse(status: Int, rawBody: String): Boolean {
        if (looksLikeCloudflareChallenge(rawBody)) return false
        val trimmed = rawBody.removePrefix(CF_MITIGATED_CHALLENGE_PREFIX).trimStart()
        // Discourse SessionController.current returns an EMPTY 404 for guests.
        // The caller still confirms twice; HTML gateway errors remain excluded.
        if (status == 404 && trimmed.isBlank()) return true
        if (!trimmed.startsWith("{")) return false
        val root = runCatching { json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull() ?: return false
        val rawErrorType = root["error_type"]?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()
        val errorType = DiscourseErrorType.fromRaw(rawErrorType)
        return when {
            status == 401 -> root.containsKey("errors") ||
                errorType == DiscourseErrorType.NOT_LOGGED_IN ||
                errorType == DiscourseErrorType.INVALID_ACCESS
            status == 404 -> root.containsKey("errors") || rawErrorType.isNotEmpty()
            status == 403 -> errorType == DiscourseErrorType.NOT_LOGGED_IN
            status in 200..299 -> {
                if (!root.containsKey("current_user")) false
                else {
                    val cu = root["current_user"]
                    cu == null || cu is JsonNull ||
                        (cu as? JsonObject)?.get("username")?.jsonPrimitive?.contentOrNull
                            ?.let { it.isBlank() || it == "linuxdo_user" } == true
                }
            }
            else -> false
        }
    }

    internal fun parseSessionUnreadCount(cu: JsonObject): Int {
        val allUnread = cu["all_unread_notifications_count"]?.jsonPrimitive?.intOrNull ?: 0
        val regularUnread = cu["unread_notifications"]?.jsonPrimitive?.intOrNull ?: 0
        val highPriorityUnread = cu["unread_high_priority_notifications"]?.jsonPrimitive?.intOrNull ?: 0
        val unreadPrivateMessages = cu["unread_private_messages"]?.jsonPrimitive?.intOrNull ?: 0
        return maxOf(
            allUnread,
            regularUnread + highPriorityUnread,
            highPriorityUnread,
            unreadPrivateMessages
        ).coerceAtLeast(0)
    }

    /**
     * 实时同步当前登录用户的完整资料、真实信任等级 (`Lv.0` ~ `Lv.4`)、真实头像、简介与互动统计
     * 依次请求：
     * 1. `/session/current.json`（确认真实 username、trust_level、avatar_template、unread_notifications）
     * 2. `/u/{username}.json`（获取 bio_excerpt、title、custom_fields、badge_count）
     * 3. `/u/{username}/summary.json`（获取 days_visited、time_read、posts_read_count、likes_received、likes_given、topic_count、post_count、badges）
     */
    suspend fun fetchCurrentUserProfile(background: Boolean = false): UserFullProfileSummary? = withContext(Dispatchers.IO) {
        // The dedicated browser owns session recovery until it finishes. An old
        // transport's background 404 must not log out and cancel that recovery.
        if (DesktopNetworkPolicy.enabled && DesktopNetworkPolicy.verifying) {
            return@withContext UserApiKeyAuthManager.fullProfileCache
        }
        val generation = UserApiKeyAuthManager.sessionGeneration
        val headers = if (background) mapOf("Discourse-Background" to "true") else emptyMap()
        val cachedAccount = UserApiKeyAuthManager.currentUser

        // 1. 探测 /session/current.json 获取最权威的当前登录态：
        //    - 遇到网关 HTML 错误、Cloudflare 页面、429 限流或瞬时网络失败时保留现有登录态，绝不误踢用户；
        //    - 若返回 Discourse 结构化未登录 JSON，先将内存哨兵 `_t` 回填至平台 CookieJar 并二次确认一次，
        //      仅当连续两次均由服务端明确返回未登录 JSON 时才清除失效会话（且不回退匿名 /u/{username}.json）。
        var sessionRes = executeHttp(
            url = "https://linux.do/session/current.json",
            extraHeaders = headers,
            preserveFailure = true
        ) ?: return@withContext UserApiKeyAuthManager.fullProfileCache

        val isPoisonedApiKey403 = sessionRes.first == 403 &&
            !UserApiKeyAuthManager.hasValidWebSessionCookie() &&
            !UserApiKeyAuthManager.getApiKey().isNullOrBlank() &&
            !looksLikeCloudflareChallenge(sessionRes.second) &&
            sessionRes.second.removePrefix(CF_MITIGATED_CHALLENGE_PREFIX).trimStart().let { trimmed ->
                trimmed.startsWith("{") && runCatching {
                    val errObj = json.parseToJsonElement(trimmed) as? JsonObject
                    errObj != null && (errObj.containsKey("errors") || errObj.containsKey("error_type"))
                }.getOrDefault(false)
            }
        if (isPoisonedApiKey403) {
            if (generation == UserApiKeyAuthManager.sessionGeneration) {
                UserApiKeyAuthManager.handleServerAuthInvalidated()
            }
            return@withContext null
        }

        if (isConfirmedDiscourseUnauthenticatedSessionResponse(sessionRes.first, sessionRes.second)) {
            val savedT = CookieSentinel.resolveWinningCookies(targetHost = "linux.do")["_t"]?.value
                ?.takeIf { it.isNotBlank() && it != "demo_session_token" }
            if (savedT != null) {
                UserApiKeyAuthManager.onSyncCookieToPlatform?.invoke("_t=$savedT; Path=/; Secure; HttpOnly; SameSite=Lax")
            }
            kotlinx.coroutines.delay(150L)
            val confirmRes = executeHttp(
                url = "https://linux.do/session/current.json",
                extraHeaders = headers,
                preserveFailure = true
            ) ?: return@withContext UserApiKeyAuthManager.fullProfileCache
            if (isConfirmedDiscourseUnauthenticatedSessionResponse(confirmRes.first, confirmRes.second)) {
                if (generation == UserApiKeyAuthManager.sessionGeneration) {
                    UserApiKeyAuthManager.handleServerAuthInvalidated()
                }
                return@withContext null
            }
            sessionRes = confirmRes
        }

        val sessionStatus = sessionRes.first
        val sessionBody = sessionRes.second.removePrefix(CF_MITIGATED_CHALLENGE_PREFIX)
        val sessionTrimmed = sessionBody.trimStart()
        val isCfChallenge = looksLikeCloudflareChallenge(sessionRes.second)

        if (sessionStatus !in 200..299 || isCfChallenge || !sessionTrimmed.startsWith("{")) {
            return@withContext UserApiKeyAuthManager.fullProfileCache
        }

        val sessionRoot = runCatching { json.parseToJsonElement(sessionTrimmed).jsonObject }.getOrNull()
            ?: return@withContext UserApiKeyAuthManager.fullProfileCache
        val cu = sessionRoot["current_user"] as? JsonObject
            ?: return@withContext UserApiKeyAuthManager.fullProfileCache

        var userId = cu["id"]?.jsonPrimitive?.intOrNull ?: (cachedAccount?.userId ?: 0)
        val currentUsername = cu["username"]?.jsonPrimitive?.contentOrNull?.takeIf {
            it.isNotBlank() && it != "linuxdo_user"
        } ?: return@withContext UserApiKeyAuthManager.fullProfileCache
        val sameCachedUser = cachedAccount?.username?.equals(currentUsername, ignoreCase = true) == true
        var displayName = cu["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: (if (sameCachedUser) cachedAccount?.displayName else null)
            ?: currentUsername
        val rawSessionTpl = cu["avatar_template"]?.jsonPrimitive?.contentOrNull.orEmpty()
        var avatarUrl = if (rawSessionTpl.isNotBlank()) {
            LinuxDoSiteRules.resolveUrl(rawSessionTpl)
        } else if (sameCachedUser) {
            cachedAccount?.avatarTemplate.orEmpty()
        } else {
            ""
        }
        var animatedAvatar = LinuxDoSiteRules.resolveAnimatedAvatarUrl(
            cu["animated_avatar"]?.jsonPrimitive?.contentOrNull
        ) ?: if (sameCachedUser) cachedAccount?.animatedAvatar else null
        var trustLevel = cu["trust_level"]?.jsonPrimitive?.intOrNull
            ?: (if (sameCachedUser) cachedAccount?.trustLevel else null)
            ?: 1
        var title = cu["title"]?.jsonPrimitive?.contentOrNull
            ?: (if (sameCachedUser) cachedAccount?.userTitle else null).orEmpty()
        val sessionUnread = parseSessionUnreadCount(cu)

        val uname = currentUsername

        var bioExcerpt = ""
        var primaryGroupName: String? = null
        var admin = false
        var moderator = false
        var createdAtText = ""
        var location = ""
        var websiteName = ""
        var websiteUrl = ""
        var statusText = ""
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


        // 2 & 3. 并发拉取 /u/{username}.json、/u/{username}/summary.json 与近期通知快照
        val encodedUname = encodeUrlSegment(uname)
        val (userRes, summaryRes, probedNotifs) = coroutineScope {
            val dUser = async { executeHttp("https://linux.do/u/$encodedUname.json", extraHeaders = headers) }
            val dSummary = async { executeHttp("https://linux.do/u/$encodedUname/summary.json", extraHeaders = headers) }
            val dNotifs = async { runCatching { probeRecentNotificationsQuietly(uname, headers) }.getOrNull() }
            Triple(dUser.await(), dSummary.await(), dNotifs.await())
        }
        val unreadNotifs = probedNotifs?.let { items ->
            val pageUnread = items.count { !it.read }
            if (items.size < 60 && pageUnread == 0 && items.isNotEmpty()) 0 else maxOf(sessionUnread, pageUnread)
        } ?: sessionUnread

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
                        ?.let { decodeDiscourseHtmlSnippet(it) }
                        ?.takeIf { it.isNotEmpty() }
                        ?: uObj["bio_raw"]?.jsonPrimitive?.contentOrNull?.trim()
                        ?: ""
                    primaryGroupName = uObj["primary_group_name"]?.jsonPrimitive?.contentOrNull
                    admin = uObj["admin"]?.jsonPrimitive?.booleanOrNull ?: false
                    moderator = uObj["moderator"]?.jsonPrimitive?.booleanOrNull ?: false
                    badgeCount = uObj["badge_count"]?.jsonPrimitive?.intOrNull ?: 0
                    topBadges = BadgeInjector.readFeaturedBadges(root, uObj)
                    flair = parseFlair(uObj)
                    location = uObj["location"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    websiteUrl = uObj["website"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    websiteName = (uObj["website_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                        ?: websiteUrl.takeIf { it.isNotBlank() }).orEmpty().trim()
                    val statusObj = uObj["status"] as? JsonObject
                    if (statusObj != null) {
                        statusText = statusObj["description"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    }
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

        // 同步更新全局登录用户状态（注意：Guest -> 登录态转换时 recordWebSessionLogin 会合法递增 sessionGeneration）
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

        val cachedDistinctCount = getCachedUserBadges(uname)?.distinctBy { it.id }?.size ?: 0
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
            badgeCount = maxOf(badgeCount, topBadges.distinctBy { it.id }.size, cachedDistinctCount),
            unreadNotifications = unreadNotifs,
            location = location,
            websiteName = websiteName,
            websiteUrl = websiteUrl,
            statusText = statusText,
            badges = topBadges,
            flair = flair
        )
        if (!UserApiKeyAuthManager.isAuthorized() ||
            !UserApiKeyAuthManager.currentUser?.username.equals(uname, ignoreCase = true)
        ) {
            return@withContext null
        }
        if (probedNotifs != null) {
            storeRecentNotificationsCache(uname, probedNotifs)
        }
        UserApiKeyAuthManager.fullProfileCache = fullProfile
        UserApiKeyAuthManager.updateCachedUnreadNotifications(unreadNotifs)
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
        val extraHeaders = params["topic_id"]?.toIntOrNull()?.takeIf { it > 0 }?.let { tid ->
            mapOf("Referer" to "https://linux.do/t/$tid")
        }.orEmpty()
        val response = executeHttp(
            url = "https://linux.do/posts.json",
            method = "POST",
            formParams = form,
            extraHeaders = extraHeaders,
            preserveFailure = true
        ) ?: throw PostSubmissionUnconfirmed()
        val result = try {
            requireMutationObject(response)
        } catch (error: ApiReadException) {
            if (error.kind in setOf(
                    ApiFailureKind.Verification,
                    ApiFailureKind.Authentication,
                    ApiFailureKind.PermissionDenied,
                    ApiFailureKind.RateLimited
                )
            ) {
                throw error
            }
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
        if (parsed.id <= 0 || parsed.raw == null) throw ApiMutationException(LinDoNetworkCopy.READ_RAW_POST_FAILED.text)
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
            val rawRes = executeHttp(
                url = "https://linux.do/raw/$topicId/$postNumber",
                method = "GET",
                extraHeaders = mapOf("Accept" to "text/plain"),
                preserveFailure = true
            )
            if (rawRes != null && rawRes.first in 200..299 && !looksLikeCloudflareChallenge(rawRes.second)) {
                val trimmedStart = rawRes.second.trimStart()
                if (!trimmedStart.startsWith("<!DOCTYPE", ignoreCase = true) &&
                    !trimmedStart.startsWith("<html", ignoreCase = true)
                ) {
                    val text = rawRes.second.trim()
                    if (text.isNotEmpty()) return@withContext text
                }
            }
        }
        null
    }

    /**
     * 更新话题标题、所属分类与标签 (`PUT /t/-/{topicId}.json`)
     */
    suspend fun updateTopicMetadata(
        topicId: Int,
        title: String,
        categoryId: Int?,
        tags: List<String> = emptyList()
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanTags = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val params = buildMap {
            put("title", title.trim())
            if (categoryId != null && categoryId > 0) {
                put("category_id", categoryId.toString())
            }
            if (cleanTags.isEmpty()) {
                put("tags[0]", "")
            } else {
                cleanTags.forEachIndexed { idx, tag ->
                    put("tags[$idx]", tag)
                }
            }
        }
        val res = executeHttp(
            url = "https://linux.do/t/-/$topicId.json",
            method = "PUT",
            formParams = params,
            preserveFailure = true
        ) ?: throw ApiReadException(ApiFailureKind.Connection)
        requireMutationObject(res)
        true
    }

    private val lastBooleanMutationErrorThreadLocal = ThreadLocal<String?>()

    @Volatile
    private var lastBooleanMutationErrorMessage: String? = null

    private fun recordBooleanMutationError(message: String?) {
        lastBooleanMutationErrorThreadLocal.set(message)
        lastBooleanMutationErrorMessage = message
    }

    fun lastMutationErrorOr(defaultMsg: String): String =
        lastBooleanMutationErrorThreadLocal.get() ?: lastBooleanMutationErrorMessage ?: defaultMsg

    private fun checkBooleanMutationResponse(res: Pair<Int, String>?): Boolean {
        if (res == null) {
            recordBooleanMutationError(null)
            return false
        }
        val (status, rawBody) = res
        if (status in 200..299) {
            if (isSuccessfulApiResponse(status, rawBody)) {
                recordBooleanMutationError(null)
                return true
            }
            recordBooleanMutationError(
                if (looksLikeCloudflareChallenge(rawBody)) LinDoNetworkCopy.SITE_VERIFICATION_REQUIRED.text else null
            )
            return false
        }
        if (looksLikeCloudflareChallenge(rawBody)) {
            recordBooleanMutationError(LinDoNetworkCopy.SITE_VERIFICATION_REQUIRED.text)
            return false
        }
        val body = rawBody.removePrefix(CF_MITIGATED_CHALLENGE_PREFIX).trimStart()
        val root = if (body.startsWith("{")) {
            runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        } else {
            null
        }
        val rawErrorType = root?.get("error_type")?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()
        val errorType = DiscourseErrorType.fromRaw(rawErrorType)
        val jsonMessages = when (val errs = root?.get("errors")) {
            is JsonArray -> errs.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
            is JsonPrimitive -> listOfNotNull(errs.contentOrNull?.takeIf(String::isNotBlank))
            else -> emptyList()
        }.ifEmpty {
            listOfNotNull(
                (root?.get("error") as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank),
                (root?.get("message") as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
            )
        }
        val extractedJsonError = if (jsonMessages.isNotEmpty()) {
            org.jsoup.Jsoup.parse(jsonMessages.joinToString("；")).text().trim()
                .takeIf { it.isNotEmpty() }
                ?.take(200)
        } else {
            null
        }
        val isKeyOnlyJson403 = status == 403 &&
            !UserApiKeyAuthManager.hasValidWebSessionCookie() &&
            !UserApiKeyAuthManager.getApiKey().isNullOrBlank() &&
            (root?.containsKey("errors") == true || rawErrorType.isNotEmpty())
        if (status == 401 || (status == 403 && errorType == DiscourseErrorType.NOT_LOGGED_IN) || isKeyOnlyJson403) {
            onAuthenticationInvalidated?.invoke()
        }
        val resolvedError = when {
            status == 401 || (status == 403 && errorType == DiscourseErrorType.NOT_LOGGED_IN) ->
                extractedJsonError ?: LinDoNetworkCopy.AUTH_EXPIRED_RELOGIN.text
            status == 429 ->
                extractedJsonError ?: LinDoNetworkCopy.ACTION_RATE_LIMITED.text
            extractedJsonError != null ->
                extractedJsonError
            else -> null
        }
        recordBooleanMutationError(resolvedError)
        return false
    }

    /**
     * 删除自己发布的评论楼层 (`DELETE /posts/{id}.json`)
     */
    suspend fun deletePost(postId: Int): Boolean = withContext(Dispatchers.IO) {
        val res = executeHttp(
            url = "https://linux.do/posts/$postId.json",
            method = "DELETE",
            preserveFailure = true
        )
        checkBooleanMutationResponse(res)
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

    /**
     * 按书签 ID 删除单条个人书签 (`DELETE /bookmarks/{bookmarkId}.json`)
     */
    suspend fun deleteBookmark(bookmarkId: Int): Boolean = withContext(Dispatchers.IO) {
        if (bookmarkId <= 0) return@withContext false
        val res = executeHttp(
            url = "https://linux.do/bookmarks/$bookmarkId.json",
            method = "DELETE",
            preserveFailure = true
        )
        checkBooleanMutationResponse(res)
    }

    suspend fun archivePrivateMessage(topicId: Int, archived: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        val route = if (archived) "archive-message" else "move-to-inbox"
        requireMutationSuccess(executeHttp("https://linux.do/t/$topicId/$route.json", "PUT", emptyMap(), preserveFailure = true))
        true
    }

    private val consolidatedCountExtractRegex = Regex(
        """^(\d+)\s*(?:个|条|位|次)?\s*(?:新)?(?:回复|消息|用户|人|发言|赞|replies|users|messages|notifications).*$""",
        RegexOption.IGNORE_CASE
    )

    private fun parseNotificationItemsFromRoot(root: JsonObject): List<UserNotificationItem>? {
        val arr = root["notifications"] as? JsonArray ?: return null
        val systemActor = LinDoCommonCopy.SYSTEM_ACTOR.text
        // 第一遍扫描：将当前响应中已携带真实头像模板的用户写入缓存，以便同列表中的合并通知立即命中头像
        arr.forEach { elem ->
            val obj = elem as? JsonObject ?: return@forEach
            val dataObj = obj["data"] as? JsonObject
            val tpl = obj["acting_user_avatar_template"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: dataObj?.get("acting_user_avatar_template")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: dataObj?.get("original_user_avatar_template")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@forEach
            val candidateNames = listOfNotNull(
                dataObj?.get("original_username")?.jsonPrimitive?.contentOrNull,
                dataObj?.get("username")?.jsonPrimitive?.contentOrNull,
                dataObj?.get("display_username")?.jsonPrimitive?.contentOrNull,
                obj["acting_user_name"]?.jsonPrimitive?.contentOrNull,
                LinuxDoSiteRules.extractUsernameFromAvatarUrl(tpl)
            )
            candidateNames.firstOrNull { name ->
                val trimmed = name.trim()
                trimmed.isNotEmpty() && trimmed != systemActor && !consolidatedCountExtractRegex.matches(trimmed)
            }?.let { validUser ->
                cacheUserAvatar(validUser, tpl)
            }
        }
        return arr.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val nType = obj["notification_type"]?.jsonPrimitive?.intOrNull ?: 0
            val kind = DiscourseNotificationKind.fromCode(nType)
            val read = obj["read"]?.jsonPrimitive?.booleanOrNull ?: true
            val createdAt = formatRelativeTime(obj["created_at"]?.jsonPrimitive?.contentOrNull)
            val topicId = obj["topic_id"]?.jsonPrimitive?.intOrNull ?: 0
            val postNumber = obj["post_number"]?.jsonPrimitive?.intOrNull ?: 1
            val slug = obj["slug"]?.jsonPrimitive?.contentOrNull ?: ""
            val dataObj = obj["data"] as? JsonObject
            val originalPostId = dataObj?.get("original_post_id")?.jsonPrimitive?.intOrNull ?: 0
            val rawActingTpl = obj["acting_user_avatar_template"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: dataObj?.get("acting_user_avatar_template")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: dataObj?.get("original_user_avatar_template")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val rawDisplayUsername = dataObj?.get("display_username")?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            val consolidatedMatch = rawDisplayUsername?.let { consolidatedCountExtractRegex.find(it) }
            val parsedConsolidatedCount = consolidatedMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
            val consolidatedReplies = if (kind.supportsConsolidatedCount) dataObj?.get("consolidated_count")?.jsonPrimitive?.intOrNull else null
            val count = consolidatedReplies
                ?: dataObj?.get("count")?.jsonPrimitive?.intOrNull
                ?: dataObj?.get("inbox_count")?.jsonPrimitive?.intOrNull
                ?: parsedConsolidatedCount
                ?: 0
            val badgeId = dataObj?.get("badge_id")?.jsonPrimitive?.intOrNull ?: 0
            val badgeName = dataObj?.get("badge_name")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val chatChannelId = dataObj?.get("chat_channel_id")?.jsonPrimitive?.intOrNull ?: 0
            val nonConsolidatedDisplayUsername = rawDisplayUsername?.takeIf { consolidatedMatch == null }
            val cachedPostAuthor = when {
                kind.needsPostAuthorLookup && originalPostId > 0 -> postAuthorCache[originalPostId]
                else -> null
            } ?: when {
                (kind == DiscourseNotificationKind.WATCHING_FIRST_POST || kind == DiscourseNotificationKind.PLUGIN_FOLLOW_TOPIC) && topicId > 0 ->
                    topicOpCache[topicId]
                kind == DiscourseNotificationKind.WATCHING_CATEGORY_OR_TAG && topicId > 0 && postNumber <= 1 ->
                    topicOpCache[topicId]
                else -> null
            }
            val primaryActor = nonConsolidatedDisplayUsername
                ?: dataObj?.get("original_username")?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && !consolidatedCountExtractRegex.matches(it) }
                ?: dataObj?.get("username")?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() && !consolidatedCountExtractRegex.matches(it) }
                ?: dataObj?.get("mentioned_by_username")?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
                ?: LinuxDoSiteRules.extractUsernameFromAvatarUrl(rawActingTpl)?.takeIf { !consolidatedCountExtractRegex.matches(it) }
                ?: cachedPostAuthor?.username?.takeIf { it.isNotBlank() && it != "linuxdo" }
                ?: obj["acting_user_name"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() && !consolidatedCountExtractRegex.matches(it) }
                ?: systemActor
            val secondActor = dataObj?.get("username2")?.jsonPrimitive?.contentOrNull?.trim()?.takeIf {
                it.isNotEmpty() && !consolidatedCountExtractRegex.matches(it)
            }
            val actor = if (secondActor != null && secondActor != primaryActor && primaryActor != systemActor) {
                "$primaryActor、$secondActor"
            } else {
                primaryActor
            }
            val cleanFancyTitle = obj["fancy_title"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?.let { decodeDiscourseHtmlSnippet(it) }
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
                groupName != null -> if (count > 0) {
                    LinDoNetworkCopy.GROUP_MESSAGES_WITH_COUNT.format(groupName, count)
                } else {
                    LinDoNetworkCopy.GROUP_NAME_PREFIX.format(groupName)
                }
                customMessage != null -> customMessage
                slugTitle != null -> slugTitle
                topicId > 0 -> LinDoCommonCopy.TOPIC_NUMBER_FALLBACK.format(topicId)
                else -> ""
            }
            val primaryActorUsername = primaryActor.takeIf { it != systemActor }
            val cachedVisual = primaryActorUsername?.let { getCachedUserVisualMeta(it) }
            val actingAvatarTemplate = rawActingTpl
                ?: cachedPostAuthor?.avatarTemplate?.takeIf { it.isNotBlank() }
                ?: primaryActorUsername?.let { getCachedUserAvatar(it) }
            if (!primaryActorUsername.isNullOrBlank() && !actingAvatarTemplate.isNullOrBlank()) {
                cacheUserAvatar(primaryActorUsername, actingAvatarTemplate)
            }
            val excerpt = cleanSearchSnippet(rawExcerpt)
            UserNotificationItem(
                id = id,
                notificationType = nType,
                read = read,
                createdAtText = createdAt,
                topicId = topicId,
                postNumber = postNumber,
                originalPostId = originalPostId,
                slug = slug,
                title = title,
                actingUsername = actor,
                actingAvatarTemplate = actingAvatarTemplate,
                actingPrimaryGroupName = cachedPostAuthor?.primaryGroupName ?: cachedVisual?.primaryGroupName,
                actingAnimatedAvatar = cachedPostAuthor?.animatedAvatar ?: cachedVisual?.animatedAvatar,
                actingFlair = cachedPostAuthor?.flair?.takeIf { it.hasFlair } ?: cachedVisual?.flair ?: FlairInfo(),
                excerpt = excerpt,
                count = count,
                badgeId = badgeId,
                badgeName = badgeName,
                chatChannelId = chatChannelId
            )
        }
    }

    private suspend fun probeRecentNotificationsQuietly(
        expectedUsername: String?,
        extraHeaders: Map<String, String>,
        expectedGeneration: Long = UserApiKeyAuthManager.sessionGeneration
    ): List<UserNotificationItem>? {
        val res = executeHttp(
            url = "https://linux.do/notifications.json?offset=0&limit=60&silent=true",
            method = "GET",
            extraHeaders = extraHeaders,
            preserveFailure = false
        ) ?: return null
        val bodyTrimmed = res.second.trimStart()
        if (res.first !in 200..299 || !bodyTrimmed.startsWith("{") || looksLikeCloudflareChallenge(res.second)) {
            return null
        }
        val root = runCatching { json.parseToJsonElement(bodyTrimmed).jsonObject }.getOrNull() ?: return null
        val items = parseNotificationItemsFromRoot(root) ?: return null
        if (UserApiKeyAuthManager.isAuthorized() && UserApiKeyAuthManager.sessionGeneration == expectedGeneration) {
            storeRecentNotificationsCache(expectedUsername, items)
        }
        return items
    }

    suspend fun refreshUnreadNotifications(background: Boolean = true): Int? = withContext(Dispatchers.IO) {
        if (!UserApiKeyAuthManager.isAuthorized()) {
            UserApiKeyAuthManager.updateCachedUnreadNotifications(0)
            return@withContext 0
        }
        val startGen = UserApiKeyAuthManager.sessionGeneration
        val expectedUsername = UserApiKeyAuthManager.currentUser?.username
        val headers = if (background) mapOf("Discourse-Background" to "true") else emptyMap()
        val items = probeRecentNotificationsQuietly(expectedUsername, headers, startGen)
            ?: return@withContext null
        if (!UserApiKeyAuthManager.isAuthorized() || UserApiKeyAuthManager.sessionGeneration != startGen) {
            return@withContext null
        }
        val pageUnread = items.count { !it.read }
        val resolved = if (items.size < 60 || pageUnread == 0) {
            pageUnread
        } else {
            maxOf(UserApiKeyAuthManager.cachedUnreadNotificationCount, pageUnread)
        }
        UserApiKeyAuthManager.updateCachedUnreadNotifications(resolved)
        resolved
    }

    /**
     * 拉取当前登录用户的通知列表 (`/notifications.json?offset={offset}&limit=60&silent=true`)
     */
    suspend fun fetchUserNotifications(offset: Int = 0): List<UserNotificationItem> = withContext(Dispatchers.IO) {
        markForegroundPriority(1_800L)
        val startGen = UserApiKeyAuthManager.sessionGeneration
        val expectedUsername = UserApiKeyAuthManager.currentUser?.username
        val cleanOffset = offset.coerceAtLeast(0)
        val res = executeHttp("https://linux.do/notifications.json?offset=$cleanOffset&limit=60&silent=true", method = "GET", preserveFailure = true)
            ?: throw ApiReadException(ApiFailureKind.Connection)
        try {
            val root = readApiObject(res)
            val parsedItems = parseNotificationItemsFromRoot(root)
                ?: throw ApiReadException(ApiFailureKind.InvalidPayload)
            if (cleanOffset == 0 && UserApiKeyAuthManager.isAuthorized() && UserApiKeyAuthManager.sessionGeneration == startGen) {
                storeRecentNotificationsCache(expectedUsername, parsedItems)
                val pageUnread = parsedItems.count { !it.read }
                val resolved = if (parsedItems.size < 60 || pageUnread == 0) {
                    pageUnread
                } else {
                    maxOf(UserApiKeyAuthManager.cachedUnreadNotificationCount, pageUnread)
                }
                UserApiKeyAuthManager.updateCachedUnreadNotifications(resolved)
            }
            parsedItems
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
        val filterParam = if (!filter.isNullOrBlank()) "&filter=${encodeUrlSegment(filter)}" else ""
        val cleanOffset = offset.coerceAtLeast(0)
        val url = "https://linux.do/user_actions.json?offset=$cleanOffset&username=${encodeUrlSegment(username)}$filterParam"
        val res = executeHttp(url, method = "GET", preserveFailure = true) ?: throw ApiReadException(ApiFailureKind.Connection)
        try {
            val root = readApiObject(res)
            val arr = root["user_actions"] as? JsonArray ?: throw ApiReadException(ApiFailureKind.InvalidPayload)
            arr.mapNotNull { elem ->
                val obj = elem as? JsonObject ?: return@mapNotNull null
                val topicId = obj["topic_id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                val postId = obj["post_id"]?.jsonPrimitive?.intOrNull ?: (topicId * 10)
                val postNumber = obj["post_number"]?.jsonPrimitive?.intOrNull ?: 1
                val title = obj["title"]?.jsonPrimitive?.contentOrNull
                    ?.let { decodeDiscourseHtmlSnippet(it) }
                    ?.takeIf { it.isNotBlank() }
                    ?: LinDoCommonCopy.TOPIC_NUMBER_FALLBACK.format(topicId)
                val excerpt = obj["excerpt"]?.jsonPrimitive?.contentOrNull
                    ?.let { decodeDiscourseHtmlSnippet(it) } ?: ""
                val actionType = obj["action_type"]?.jsonPrimitive?.intOrNull ?: DiscourseUserActionKind.REPLY.code
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
        val res = executeHttp("https://linux.do/u/${encodeUrlSegment(username)}/bookmarks.json$pageParam", method = "GET", preserveFailure = true)
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
                val title = (obj["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: obj["fancy_title"]?.jsonPrimitive?.contentOrNull?.let { decodeDiscourseHtmlSnippet(it) }?.takeIf { it.isNotBlank() })
                    ?: LinDoNetworkCopy.BOOKMARK_TOPIC_FALLBACK.format(topicId)
                val excerpt = obj["excerpt"]?.jsonPrimitive?.contentOrNull
                    ?.let { decodeDiscourseHtmlSnippet(it) } ?: ""
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
                    actionType = DiscourseUserActionKind.BOOKMARK.code
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
    suspend fun syncSiteCategories(
        force: Boolean = false,
        background: Boolean = !force
    ): List<LinuxDoCategoryMeta> = withContext(Dispatchers.IO) {
        val currentGen = UserApiKeyAuthManager.sessionGeneration
        val now = System.currentTimeMillis()
        val ttlMs = 24L * 60L * 60L * 1000L
        if (!force && syncedCategoriesSessionGen == currentGen && now - categoriesLastSyncedAtMs in 0 until ttlMs) {
            return@withContext categories
        }
        val headers = if (background) mapOf("Discourse-Background" to "true") else emptyMap()
        siteCategoriesSyncMutex.withLock {
            val recheckGen = UserApiKeyAuthManager.sessionGeneration
            val recheckNow = System.currentTimeMillis()
            if (!force && syncedCategoriesSessionGen == recheckGen && recheckNow - categoriesLastSyncedAtMs in 0 until ttlMs) {
                return@withLock categories
            }
            val res = executeHttp("https://linux.do/site.json", method = "GET", extraHeaders = headers)
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
                    categoriesLastSyncedAtMs = System.currentTimeMillis()
                } catch (_: Throwable) {
                }
            }
            categories
        }
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
        // 优先不传 limit=5 以拉取站点允许的完整可选标签列表，避免选中必选组标签后候选数量骤降；
        // 若首次请求已命中 429 或 Cloudflare 站点验证挑战，绝不继续补发第二次请求加剧风控
        val fullResponse = executeHttp(url, preserveFailure = true)
        val root = readApiObject(
            if (
                fullResponse != null &&
                (isSuccessfulApiResponse(fullResponse.first, fullResponse.second) ||
                    fullResponse.first == 429 ||
                    looksLikeCloudflareChallenge(fullResponse.second))
            ) {
                fullResponse
            } else {
                executeHttp("$url&limit=5", preserveFailure = true)
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
        return decodeDiscourseHtmlSnippet(raw)
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
            val fallbackTopicTitle = LinDoCommonCopy.TOPIC_NUMBER_FALLBACK.format(tid)
            val rawTitle = tObj?.get("title")?.jsonPrimitive?.contentOrNull
                ?: tObj?.get("fancy_title")?.jsonPrimitive?.contentOrNull
                ?: pObj["topic_title_headline"]?.jsonPrimitive?.contentOrNull
                ?: fallbackTopicTitle
            val title = cleanSearchSnippet(rawTitle).ifBlank { fallbackTopicTitle }
            val catId = tObj?.get("category_id")?.jsonPrimitive?.intOrNull ?: 4
            val (catName, catColor) = resolveCategoryMeta(catId)
            val blurb = cleanSearchSnippet(pObj["blurb"]?.jsonPrimitive?.contentOrNull)
            val pUsername = pObj["username"]?.jsonPrimitive?.contentOrNull ?: "linuxdo"
            val rawTpl = pObj["avatar_template"]?.jsonPrimitive?.contentOrNull ?: ""
            if (rawTpl.isNotBlank()) {
                cacheUserAvatar(pUsername, rawTpl)
            }
            val avatarUrl = if (rawTpl.isNotBlank()) {
                LinuxDoSiteRules.resolveStaticAvatarUrl(rawTpl, 120)
            } else ""
            seenTopicPostKeys.add("${tid}_$postNum")
            seenTopicPostKeys.add("${tid}_1")
            results.add(
                CommunitySearchResult(
                    topicId = tid,
                    postNumber = postNum,
                    title = title,
                    blurb = blurb,
                    username = pUsername,
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
                val fallbackTopicTitle = LinDoCommonCopy.TOPIC_NUMBER_FALLBACK.format(tid)
                val rawTitle = tObj["title"]?.jsonPrimitive?.contentOrNull
                    ?: tObj["fancy_title"]?.jsonPrimitive?.contentOrNull
                    ?: fallbackTopicTitle
                val title = cleanSearchSnippet(rawTitle).ifBlank { fallbackTopicTitle }
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
     * 全局社区搜索（统一走官方全量分页接口 `/search.json?q=...&page=...` 每页最多 50 条，
     * 严禁在被限流或拦截时级联并发请求 4 个公开话题流接口加剧 429 风控）
     */
    suspend fun searchCommunityPage(
        query: String,
        order: String? = null,
        page: Int = 1
    ): SearchPageResult = withContext(Dispatchers.IO) {
        val cleanQ = query.trim()
        if (cleanQ.isBlank()) return@withContext SearchPageResult(items = emptyList(), hasMore = false)
        val safePage = page.coerceAtLeast(1)
        val fullQuery = if (!order.isNullOrBlank()) "$cleanQ order:$order" else cleanQ
        val encoded = java.net.URLEncoder.encode(fullQuery, "UTF-8")
        val endpoint = "https://linux.do/search.json?q=$encoded&page=$safePage"
        try {
            val root = readApiObject(executeHttp(endpoint, method = "GET", preserveFailure = true))
            val items = parseSearchResponseRoot(root)
            val grouped = root["grouped_search_result"] as? JsonObject
            val hasMore = grouped?.get("more_full_page_results")?.jsonPrimitive?.booleanOrNull == true
            SearchPageResult(items = items, hasMore = hasMore)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (apiErr: ApiReadException) {
            throw apiErr
        } catch (err: Exception) {
            throw ApiReadException(ApiFailureKind.InvalidPayload, err)
        }
    }

    suspend fun searchCommunityPage(
        query: String,
        pageNumber: Int
    ): SearchPageResult = searchCommunityPage(query = query, order = null, page = pageNumber)

    suspend fun searchCommunity(
        query: String,
        order: String? = null,
        page: Int = 1
    ): List<CommunitySearchResult> = searchCommunityPage(query = query, order = order, page = page).items

    /**
     * 编辑器「插入链接 -> 引用帖子」专用轻量即时搜索：
     * 1. 优先请求 Discourse 编辑器/顶栏联想专用轻量接口 `/search/query.json?term=...&include_blurbs=true`，
     *    开销小、响应快且不易触发全量 `/search.json` 的严格频率限制与 WAF 挑战；
     * 2. 若返回为空或旧端点不可用，再回退调用 `/search.json?q=...&page=1`；
     * 3. 若发生站点验证 / 限流 / 断网异常则原样抛出 `ApiReadException`，绝不静默伪装成「未找到匹配的帖子」。
     */
    suspend fun searchTopicsForLinkPicker(query: String): List<CommunitySearchResult> = withContext(Dispatchers.IO) {
        val cleanQ = query.trim()
        if (cleanQ.isBlank()) return@withContext emptyList()
        val encoded = java.net.URLEncoder.encode(cleanQ, "UTF-8")
        val quickEndpoint = "https://linux.do/search/query.json?term=$encoded&include_blurbs=true"
        var quickError: ApiReadException? = null
        val quickItems = try {
            val quickRes = executeHttp(quickEndpoint, method = "GET", preserveFailure = true)
            if (quickRes != null && quickRes.first == 404 && !looksLikeCloudflareChallenge(quickRes.second)) {
                emptyList()
            } else {
                parseSearchResponseRoot(readApiObject(quickRes))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (apiErr: ApiReadException) {
            if (apiErr.kind == ApiFailureKind.Verification || apiErr.kind == ApiFailureKind.Authentication) {
                throw apiErr
            }
            quickError = apiErr
            emptyList()
        } catch (err: Exception) {
            quickError = ApiReadException(ApiFailureKind.InvalidPayload, err)
            emptyList()
        }
        if (quickItems.isNotEmpty()) {
            return@withContext quickItems.distinctBy { it.topicId }
        }
        try {
            searchCommunity(cleanQ).distinctBy { it.topicId }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fallbackErr: ApiReadException) {
            throw quickError ?: fallbackErr
        }
    }

    /**
     * 一键将全部未读通知标记为已读 (`PUT /notifications/mark-read.json`)
     */
    suspend fun markNotificationsRead(): Boolean = withContext(Dispatchers.IO) {
        val res = executeHttp(
            url = "https://linux.do/notifications/mark-read.json",
            method = "PUT",
            formParams = emptyMap(),
            preserveFailure = true
        )
        val ok = checkBooleanMutationResponse(res)
        if (ok) {
            synchronized(recentNotificationsLock) {
                if (recentNotificationsItems.isNotEmpty()) {
                    recentNotificationsItems = recentNotificationsItems.map { item ->
                        if (item.read) item else item.copy(read = true)
                    }
                }
            }
            UserApiKeyAuthManager.updateCachedUnreadNotifications(0)
        }
        ok
    }

    /**
     * 将单条未读通知标记为已读 (`PUT /notifications/mark-read.json` + `id`)
     */
    suspend fun markSingleNotificationRead(notificationId: Int): Boolean = withContext(Dispatchers.IO) {
        if (notificationId <= 0) return@withContext false
        val res = executeHttp(
            url = "https://linux.do/notifications/mark-read.json",
            method = "PUT",
            formParams = mapOf("id" to notificationId.toString()),
            preserveFailure = true
        )
        val ok = checkBooleanMutationResponse(res)
        if (ok) {
            var shouldDecrement = true
            synchronized(recentNotificationsLock) {
                if (recentNotificationsItems.isNotEmpty()) {
                    var matched = false
                    var transitioned = false
                    recentNotificationsItems = recentNotificationsItems.map { item ->
                        if (item.id == notificationId) {
                            matched = true
                            if (!item.read) {
                                transitioned = true
                                item.copy(read = true)
                            } else {
                                item
                            }
                        } else {
                            item
                        }
                    }
                    if (matched) {
                        shouldDecrement = transitioned
                    }
                }
            }
            if (shouldDecrement && UserApiKeyAuthManager.cachedUnreadNotificationCount > 0) {
                UserApiKeyAuthManager.updateCachedUnreadNotifications(
                    (UserApiKeyAuthManager.cachedUnreadNotificationCount - 1).coerceAtLeast(0)
                )
            }
        }
        ok
    }

    /**
     * 切换话题订阅通知级别 (`POST /t/{id}/notifications.json`)
     * `level`: 3=关注(Watching), 2=跟踪(Tracking), 1=常规(Normal), 0=免扰(Muted)
     */
    suspend fun setTopicNotificationLevel(topicId: Int, level: Int): Boolean = withContext(Dispatchers.IO) {
        val res = executeHttp(
            url = "https://linux.do/t/$topicId/notifications.json",
            method = "POST",
            formParams = mapOf("notification_level" to level.toString()),
            preserveFailure = true
        )
        checkBooleanMutationResponse(res)
    }

    suspend fun setTopicNotificationLevel(topicId: Int, level: TopicNotificationLevel): Boolean =
        setTopicNotificationLevel(topicId, level.code)

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
            return LinDoNetworkCopy.CSRF_OR_CHANNEL_UNAVAILABLE.text
        }
        val (status, body) = res
        val isCfChallenge = looksLikeCloudflareChallenge(body)
        if (isCfChallenge) {
            return LinDoNetworkCopy.SITE_VERIFICATION_REQUIRED.text
        }
        val trimmed = body.removePrefix(CF_MITIGATED_CHALLENGE_PREFIX).trimStart()
        var errorType = DiscourseErrorType.OTHER
        if (trimmed.startsWith("{")) {
            runCatching {
                val root = json.parseToJsonElement(trimmed).jsonObject
                errorType = DiscourseErrorType.fromRaw(root["error_type"]?.jsonPrimitive?.contentOrNull)
                if (!isCfChallenge && (status == 401 || (status == 403 && errorType == DiscourseErrorType.NOT_LOGGED_IN))) {
                    onAuthenticationInvalidated?.invoke()
                }
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
                        return LinDoNetworkCopy.CSRF_TOKEN_EXPIRED.text
                    }
                    if (rawText.isNotEmpty()) {
                        return rawText.take(180)
                    }
                }
            }
        } else if (!isCfChallenge && status == 401) {
            onAuthenticationInvalidated?.invoke()
        }
        if (isCfChallenge) {
            return LinDoNetworkCopy.SITE_VERIFICATION_REQUIRED.text
        }
        return when (status) {
            401 -> LinDoNetworkCopy.AUTH_EXPIRED_FOR_ACTION.text
            403 -> if (errorType == DiscourseErrorType.NOT_LOGGED_IN) {
                LinDoNetworkCopy.AUTH_EXPIRED_FOR_ACTION.text
            } else {
                LinDoNetworkCopy.ACTION_FORBIDDEN_SELF_OR_LOCKED.text
            }
            404, 410 -> LinDoNetworkCopy.TARGET_POST_MISSING_OR_DELETED.text
            422 -> LinDoNetworkCopy.ACTION_UNPROCESSABLE.text
            429 -> LinDoNetworkCopy.ACTION_RATE_LIMITED.text
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
        val rawAvatar = uObj?.get("avatar_template")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: bObj["avatar_template"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: getCachedUserAvatar(uname)
            ?: ""
        if (rawAvatar.isNotBlank()) {
            cacheUserAvatar(uname, rawAvatar)
        }
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
        if (res != null && isSuccessfulApiResponse(res.first, res.second)) {
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
                        putBounded(
                            postReactionSnapshots,
                            postId,
                            PostReactionSnapshot(
                                myReactionId = serverMyReaction,
                                reactions = serverReactions
                            ),
                            maxEntries = 600
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
            if (fallbackRes != null && isSuccessfulApiResponse(fallbackRes.first, fallbackRes.second)) {
                val trimmed = fallbackRes.second.trimStart()
                if (trimmed.startsWith("{")) {
                    runCatching {
                        val root = json.parseToJsonElement(trimmed).jsonObject
                        val pObj = (root["post"] as? JsonObject) ?: root
                        if (pObj.containsKey("actions_summary") || pObj.containsKey("reactions")) {
                            val (serverReactions, serverMyReaction) = parsePostReactionsAndMine(pObj)
                            putBounded(
                                postReactionSnapshots,
                                postId,
                                PostReactionSnapshot(
                                    myReactionId = serverMyReaction,
                                    reactions = serverReactions
                                ),
                                maxEntries = 600
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
                errorMessage = extractActionErrorMessage(fallbackRes, LinDoNetworkCopy.LIKE_NOT_APPLIED.text)
            )
        }

        ReactionToggleResult(
            ok = false,
            errorMessage = extractActionErrorMessage(res, LinDoNetworkCopy.REACTION_NOT_APPLIED.text)
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
            return@withContext BoostMutationResult(ok = false, errorMessage = LinDoNetworkCopy.BOOST_EMPTY_INPUT.text)
        }
        if (stats.exceedsLength) {
            return@withContext BoostMutationResult(
                ok = false,
                errorMessage = LinDoNetworkCopy.BOOST_LENGTH_EXCEEDED.format(
                    BoostInputStats.MAX_BOOST_VISIBLE_LENGTH,
                    stats.visibleLength
                )
            )
        }
        if (stats.exceedsEmojiLimit) {
            return@withContext BoostMutationResult(
                ok = false,
                errorMessage = LinDoNetworkCopy.BOOST_EMOJI_EXCEEDED.format(
                    BoostInputStats.MAX_BOOST_EMOJI_COUNT,
                    stats.emojiCount
                )
            )
        }

        val res = executeHttp(
            url = "https://linux.do/discourse-boosts/posts/$postId/boosts.json",
            method = "POST",
            formParams = mapOf("raw" to trimmed),
            preserveFailure = true
        )
        if (res != null && isSuccessfulApiResponse(res.first, res.second)) {
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
            putBounded(
                postBoostSnapshots,
                postId,
                PostBoostSnapshot(boosts = updatedList, canBoost = false),
                maxEntries = 600
            )
            return@withContext BoostMutationResult(ok = true, boost = resolvedBoost)
        }
        BoostMutationResult(
            ok = false,
            errorMessage = extractActionErrorMessage(res, LinDoNetworkCopy.BOOST_SEND_FAILED.text)
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
        if (res != null && isSuccessfulApiResponse(res.first, res.second)) {
            postBoostSnapshots.computeIfPresent(postId) { _, snap ->
                val remaining = snap.boosts.filterNot { it.id == boostId }
                if (remaining.size == snap.boosts.size) snap else snap.copy(boosts = remaining, canBoost = true)
            }
            return@withContext BoostMutationResult(ok = true)
        }
        BoostMutationResult(
            ok = false,
            errorMessage = extractActionErrorMessage(res, LinDoNetworkCopy.BOOST_DELETE_FAILED.text)
        )
    }

    private enum class PrivateMessageBoxEndpoint(val routeSegment: String) {
        INBOX("private-messages"),
        SENT("private-messages-sent"),
        ARCHIVE("private-messages-archive");

        companion object {
            fun resolve(sent: Boolean, archived: Boolean): PrivateMessageBoxEndpoint = when {
                archived -> ARCHIVE
                sent -> SENT
                else -> INBOX
            }
        }
    }

    /**
     * 拉取用户私信箱 (`/topics/private-messages/{username}.json` 或 `private-messages-sent`)
     */
    suspend fun fetchPrivateMessages(username: String, sent: Boolean = false, archived: Boolean = false, page: Int = 0): List<TopicSummary> = withContext(Dispatchers.IO) {
        if (username.isBlank() || username == "linuxdo_user") throw ApiReadException(ApiFailureKind.Authentication)
        val mailbox = PrivateMessageBoxEndpoint.resolve(sent = sent, archived = archived).routeSegment
        val pageParam = if (page > 0) "?page=$page" else ""
        val res = executeHttp("https://linux.do/topics/$mailbox/${encodeUrlSegment(username)}.json$pageParam", method = "GET", preserveFailure = true) ?: throw ApiReadException(ApiFailureKind.Connection)
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

    internal fun normalizePrivateMessageRecipients(rawRecipients: String): String {
        return rawRecipients
            .split(Regex("[,，;；\\s]+"))
            .map { it.trim().trimStart('@').trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(",")
    }

    private fun isIsoWithinRecentSeconds(
        iso: String?,
        nowEpochSec: Long,
        maxAgeSeconds: Long = 60L
    ): Boolean {
        val raw = iso?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val instant = runCatching { Instant.parse(raw) }.getOrElse {
            runCatching { java.time.OffsetDateTime.parse(raw).toInstant() }.getOrNull()
        } ?: return false
        val ageSec = nowEpochSec - instant.epochSecond
        return ageSec in -15L..maxAgeSeconds
    }

    /**
     * 发送站内私信并返回完整结果（含 `enqueued` 待审队列识别与服务端 422 校验错误透传）
     */
    suspend fun sendPrivateMessageDetailed(
        targetUsernames: String,
        title: String,
        rawMarkdown: String,
        draftKey: String? = null
    ): PrivateMessageSendResult = withContext(Dispatchers.IO) {
        val normalizedRecipients = normalizePrivateMessageRecipients(targetUsernames)
        if (normalizedRecipients.isEmpty()) {
            return@withContext PrivateMessageSendResult(errorMessage = LinDoNetworkCopy.PM_MISSING_RECIPIENT.text)
        }
        val trimmedTitle = title.trim()
        val params = buildMap {
            put("title", trimmedTitle)
            put("raw", rawMarkdown)
            put("archetype", "private_message")
            put("target_recipients", normalizedRecipients)
            if (!draftKey.isNullOrBlank()) {
                put("draft_key", draftKey)
            }
        }
        try {
            val res = executeHttp(
                url = "https://linux.do/posts.json",
                method = "POST",
                formParams = params,
                preserveFailure = true
            )
            if (res == null) {
                val currentUsername = UserApiKeyAuthManager.currentUser?.username?.takeIf {
                    it.isNotBlank() && it != "linuxdo_user"
                }
                if (currentUsername != null && trimmedTitle.isNotEmpty()) {
                    val nowEpochSec = Instant.now().epochSecond
                    val recentSent = runCatching {
                        fetchPrivateMessages(username = currentUsername, sent = true, page = 0)
                    }.getOrNull()
                    val matched = recentSent?.firstOrNull { pm ->
                        pm.id > 0 &&
                            pm.title.trim() == trimmedTitle &&
                            isIsoWithinRecentSeconds(
                                iso = pm.createdAtIso.ifBlank { pm.lastPostedAtIso },
                                nowEpochSec = nowEpochSec,
                                maxAgeSeconds = 60L
                            )
                    }
                    if (matched != null) {
                        return@withContext PrivateMessageSendResult(topicId = matched.id)
                    }
                }
                return@withContext PrivateMessageSendResult(
                    errorMessage = ApiReadException(ApiFailureKind.Connection).message
                        ?: LinDoNetworkCopy.CONNECTION_FAILED.text
                )
            }
            val obj = requireMutationObject(res)
            val isEnqueued = obj["action"]?.jsonPrimitive?.contentOrNull == "enqueued" ||
                (obj["pending_post"] != null && obj["pending_post"] !is JsonNull)
            if (isEnqueued) {
                return@withContext PrivateMessageSendResult(enqueued = true)
            }
            val topicId = obj["topic_id"]?.jsonPrimitive?.intOrNull
                ?: (obj["post"] as? JsonObject)?.get("topic_id")?.jsonPrimitive?.intOrNull
            if (topicId != null && topicId > 0) {
                PrivateMessageSendResult(topicId = topicId)
            } else {
                PrivateMessageSendResult(errorMessage = LinDoNetworkCopy.PM_SENT_NO_TOPIC_ID.text)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (mutErr: ApiMutationException) {
            PrivateMessageSendResult(errorMessage = mutErr.message ?: LinDoNetworkCopy.PM_SEND_CHECK_CONTENT.text)
        } catch (readErr: ApiReadException) {
            PrivateMessageSendResult(errorMessage = readErr.message)
        } catch (t: Throwable) {
            PrivateMessageSendResult(
                errorMessage = t.message?.takeIf { it.isNotBlank() } ?: LinDoNetworkCopy.PM_SEND_FAILED_RETRY.text
            )
        }
    }

    /**
     * 发送站内私信 (`POST /posts.json` with `archetype=private_message`)
     */
    suspend fun sendPrivateMessage(
        targetUsernames: String,
        title: String,
        raw: String
    ): Int? {
        val result = sendPrivateMessageDetailed(targetUsernames = targetUsernames, title = title, rawMarkdown = raw)
        if (result.errorMessage != null) {
            throw ApiMutationException(result.errorMessage)
        }
        return if (result.enqueued) (result.topicId ?: 0) else result.topicId
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
        var websiteUrl = ""
        var statusText = ""
        var badgeCount = 0
        var topBadges = emptyList<GrantedBadge>()
        var flair = FlairInfo()

        val encodedClean = encodeUrlSegment(clean)
        val userRes = executeHttp("https://linux.do/u/$encodedClean.json", method = "GET", preserveFailure = true)
            ?: throw ApiReadException(ApiFailureKind.Connection)
        val root = readApiObject(userRes)
        val uObj = root["user"] as? JsonObject
        if (uObj != null) {
            userId = uObj["id"]?.jsonPrimitive?.intOrNull ?: 0
            displayName = uObj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: clean
            val rawTpl = uObj["avatar_template"]?.jsonPrimitive?.contentOrNull ?: ""
            if (rawTpl.isNotBlank()) {
                avatarUrl = LinuxDoSiteRules.resolveStaticAvatarUrl(rawTpl, 120)
            }
            animatedAvatar = uObj["animated_avatar"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            trustLevel = uObj["trust_level"]?.jsonPrimitive?.intOrNull ?: 1
            title = uObj["title"]?.jsonPrimitive?.contentOrNull ?: ""
            bioExcerpt = uObj["bio_excerpt"]?.jsonPrimitive?.contentOrNull
                ?.let { decodeDiscourseHtmlSnippet(it) } ?: ""
            primaryGroupName = uObj["primary_group_name"]?.jsonPrimitive?.contentOrNull
            admin = uObj["admin"]?.jsonPrimitive?.booleanOrNull ?: false
            moderator = uObj["moderator"]?.jsonPrimitive?.booleanOrNull ?: false
            badgeCount = uObj["badge_count"]?.jsonPrimitive?.intOrNull ?: 0
            topBadges = BadgeInjector.readFeaturedBadges(root, uObj)
            flair = parseFlair(uObj)
            cacheUserVisualMeta(
                username = clean,
                avatarTemplate = rawTpl,
                animatedAvatar = animatedAvatar,
                primaryGroupName = primaryGroupName,
                flair = flair
            )
            location = uObj["location"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            websiteUrl = uObj["website"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            websiteName = (uObj["website_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: websiteUrl.takeIf { it.isNotBlank() })?.trim().orEmpty()
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


        val summaryRes = executeHttp("https://linux.do/u/$encodedClean/summary.json", method = "GET")
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

        val cachedDistinctCount = getCachedUserBadges(clean)?.distinctBy { it.id }?.size ?: 0
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
            badgeCount = maxOf(badgeCount, topBadges.distinctBy { it.id }.size, cachedDistinctCount),
            lastSeenText = lastSeenText,
            location = location,
            websiteName = websiteName,
            websiteUrl = websiteUrl,
            statusText = statusText,
            solvedCount = solvedCount,
            badges = topBadges,
            flair = flair
        )
    }

    suspend fun updateUserTitleResult(
        username: String,
        title: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val targetUsername = username.ifBlank { UserApiKeyAuthManager.currentUser?.username.orEmpty() }
        if (targetUsername.isBlank() || targetUsername == "linuxdo_user") {
            return@withContext Result.failure(ApiReadException(ApiFailureKind.Authentication))
        }
        try {
            val res = executeHttp(
                url = "https://linux.do/u/${encodeUrlSegment(targetUsername)}.json",
                method = "PUT",
                formParams = mapOf("title" to title.trim()),
                preserveFailure = true
            )
            requireMutationSuccess(res)
            fetchCurrentUserProfile()
            Result.success(Unit)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun updateUserTitle(
        username: String,
        title: String
    ): Boolean = updateUserTitleResult(username = username, title = title).isSuccess

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
            val winningCookies = CookieSentinel.resolveWinningCookies(targetHost = "linux.do")
            val rawT = winningCookies["_t"]?.value.orEmpty()
            val hasWebCookie = rawT.isNotBlank() && rawT != "demo_session_token"
            val apiKey = UserApiKeyAuthManager.getApiKey()?.takeIf { it.isNotBlank() }
            val cookieHeader = winningCookies
                .entries
                .filterNot { it.key == "_t" && it.value.value == "demo_session_token" }
                .filterNot { it.key in cfTlsBoundCookies }
                .joinToString("; ") { "${it.key}=${it.value.value}" }
            val requestHeaders = LinkedHashMap<String, String>().apply {
                put("Accept", "application/json")
                put("X-Requested-With", "XMLHttpRequest")
                if (!hasWebCookie && apiKey != null) {
                    put("User-Api-Key", apiKey)
                    put("User-Api-Client-Id", UserApiKeyAuthManager.getClientId())
                }
                extraHeaders.forEach { (k, v) -> put(k, v) }
                UserApiKeyAuthManager.customUserAgent?.takeIf { it.isNotBlank() }?.let {
                    put("User-Agent", it)
                }
                if (cookieHeader.isNotEmpty()) put("Cookie", cookieHeader)
            }
            var response = executeApiRequest(sharedClient, url, "GET", null, requestHeaders)
            response.headers.getAll("Set-Cookie")?.forEach { setCookie ->
                ingestSetCookieHeader(setCookie, domain = "linux.do")
            }
            var status = response.status.value
            var text = response.bodyAsText()
            if (status == 403 && requestHeaders.containsKey("Discourse-Logged-In")) {
                requestHeaders.remove("Discourse-Logged-In")
                response = executeApiRequest(sharedClient, url, "GET", null, requestHeaders)
                response.headers.getAll("Set-Cookie")?.forEach { setCookie ->
                    ingestSetCookieHeader(setCookie, domain = "linux.do")
                }
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

    private val badgeCacheLock = Any()

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
    suspend fun fetchUserBadges(
        username: String,
        background: Boolean = false
    ): List<GrantedBadge> = withContext(Dispatchers.IO) {
        val clean = username.trim().removePrefix("@")
        if (clean.isBlank()) return@withContext emptyList()
        val encoded = java.net.URLEncoder.encode(clean, "UTF-8")
        val headers = if (background) mapOf("Discourse-Background" to "true") else emptyMap()
        val primaryRes = executeHttp(
            url = "https://linux.do/user-badges/$encoded.json?grouped=true",
            method = "GET",
            extraHeaders = headers,
            preserveFailure = true
        )
        val effectiveRes = if (
            primaryRes != null &&
            (isSuccessfulApiResponse(primaryRes.first, primaryRes.second) ||
                primaryRes.first == 429 ||
                looksLikeCloudflareChallenge(primaryRes.second))
        ) {
            primaryRes
        } else {
            executeHttp(
                url = "https://linux.do/user_badges.json?username=$encoded&grouped=true",
                method = "GET",
                extraHeaders = headers,
                preserveFailure = true
            ) ?: primaryRes
        }
        try {
            val root = readApiObject(effectiveRes)
            root.requireArray("user_badges")
            val parsed = parseUserBadgesFromRoot(root)
            if (parsed.isNotEmpty()) {
                val lowerKey = clean.lowercase()
                synchronized(badgeCacheLock) {
                    val updated = LinkedHashMap<String, List<GrantedBadge>>(cachedUserBadgesByUsername.size + 1)
                    updated.putAll(cachedUserBadgesByUsername)
                    updated.remove(lowerKey)
                    updated[lowerKey] = parsed
                    while (updated.size > 200) {
                        val eldestKey = updated.keys.firstOrNull() ?: break
                        updated.remove(eldestKey)
                    }
                    cachedUserBadgesByUsername = updated
                }
                if (UserApiKeyAuthManager.fullProfileCache?.username?.equals(clean, ignoreCase = true) == true) {
                    UserApiKeyAuthManager.fullProfileCache = UserApiKeyAuthManager.fullProfileCache?.copy(
                        badgeCount = parsed.distinctBy { it.id }.size
                    )
                }
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
            if (apiErr is ApiReadException &&
                (apiErr.kind == ApiFailureKind.Verification || apiErr.kind == ApiFailureKind.RateLimited)
            ) {
                throw apiErr
            }
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
    suspend fun fetchAllSiteBadges(
        force: Boolean = false,
        background: Boolean = false
    ): List<GrantedBadge> = withContext(Dispatchers.IO) {
        val ttlMs = 24L * 60L * 60L * 1000L
        if (!force && cachedSiteBadges.isNotEmpty() && System.currentTimeMillis() - badgesLastSyncedAtMs in 0 until ttlMs) {
            return@withContext cachedSiteBadges
        }
        val headers = if (background) mapOf("Discourse-Background" to "true") else emptyMap()
        siteBadgesSyncMutex.withLock {
            if (!force && cachedSiteBadges.isNotEmpty() && System.currentTimeMillis() - badgesLastSyncedAtMs in 0 until ttlMs) {
                return@withLock cachedSiteBadges
            }
            val root = readApiObject(
                executeHttp(
                    url = "https://linux.do/badges.json",
                    method = "GET",
                    extraHeaders = headers,
                    preserveFailure = true
                )
            )
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
                badgesLastSyncedAtMs = System.currentTimeMillis()
            }
            result
        }
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

    private fun localizeSmallActionCode(actionCode: String): String =
        SmallActionKind.localize(actionCode.trim().lowercase())

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
        cacheUserAvatar(username, avatarTemplate)
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
        val postType = pObj["post_type"]?.jsonPrimitive?.intOrNull ?: 1
        val actionCode = pObj["action_code"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val rawCooked = pObj["cooked"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val cookedHasVisibleContent = rawCooked.isNotEmpty() && runCatching {
            val doc = org.jsoup.Jsoup.parseBodyFragment(rawCooked)
            doc.text().isNotBlank() || doc.selectFirst("img, video, audio, iframe, svg") != null
        }.getOrDefault(true)
        val cooked = when {
            !cookedHasVisibleContent && (actionCode.isNotEmpty() || postType == 3) -> {
                val label = if (actionCode.isNotEmpty()) {
                    localizeSmallActionCode(actionCode)
                } else {
                    LinDoNetworkCopy.SMALL_ACTION_GENERIC.text
                }
                "<p><em>${org.jsoup.nodes.Entities.escape(label)}</em></p>"
            }
            rawCooked.isNotEmpty() -> rawCooked
            else -> "<p></p>"
        }
        val createdAtIso = pObj["created_at"]?.jsonPrimitive?.contentOrNull
        val replyCount = pObj["reply_count"]?.jsonPrimitive?.intOrNull ?: 0
        val replyToPostNumber = pObj["reply_to_post_number"]?.jsonPrimitive?.intOrNull ?: 0
        val replyToUserObj = pObj["reply_to_user"] as? JsonObject
        val replyToUsername = replyToUserObj?.get("username")?.jsonPrimitive?.contentOrNull
        val replyToDisplayName = replyToUserObj?.get("name")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val replyToAvatarTemplate = replyToUserObj?.get("avatar_template")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: replyToUsername?.let { getCachedUserAvatar(it) }
        if (!replyToUsername.isNullOrBlank() && !replyToAvatarTemplate.isNullOrBlank()) {
            cacheUserAvatar(replyToUsername, replyToAvatarTemplate)
        }
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
        val canEdit = pObj["can_edit"]?.jsonPrimitive?.booleanOrNull
            ?: pObj["yours"]?.jsonPrimitive?.booleanOrNull
            ?: false
        val canDelete = pObj["can_delete"]?.jsonPrimitive?.booleanOrNull ?: false
        val rawMarkdown = pObj["raw"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

        val pollsArr = pObj["polls"] as? JsonArray
        val pollsVotesObj = pObj["polls_votes"] as? JsonObject
        pollsArr?.forEach { pollElem ->
            val pollObj = pollElem as? JsonObject ?: return@forEach
            val pollName = pollObj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "poll"
            val pollStatus = pollObj["status"]?.jsonPrimitive?.contentOrNull
            val isClosed = pollStatus.equals("closed", ignoreCase = true)
            val votersCount = pollObj["voters"]?.jsonPrimitive?.intOrNull
            val votesByOption = (pollObj["options"] as? JsonArray)?.mapNotNull { optElem ->
                val optObj = optElem as? JsonObject ?: return@mapNotNull null
                val optId = optObj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val optVotes = optObj["votes"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                optId to optVotes
            }?.toMap().orEmpty()
            val votedIds = (pollsVotesObj?.get(pollName) as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }
                ?.toSet()
                .orEmpty()
            val snapKey = "$pid:$pollName"
            val existingSnap = pollVoteSnapshots[snapKey]
            val mergedIds = if (votedIds.isNotEmpty()) votedIds else existingSnap?.selectedIds.orEmpty()
            val mergedVotes = if (votesByOption.isNotEmpty()) votesByOption else existingSnap?.optionVotes.orEmpty()
            val mergedVoters = votersCount ?: existingSnap?.voters
            if (mergedIds.isNotEmpty() || mergedVotes.isNotEmpty() || isClosed || mergedVoters != null) {
                putBounded(
                    pollVoteSnapshots,
                    snapKey,
                    PollVoteSnapshot(
                        selectedIds = mergedIds,
                        optionVotes = mergedVotes,
                        voters = mergedVoters,
                        hasVoted = mergedIds.isNotEmpty(),
                        closed = isClosed
                    )
                )
            }
        }

        val grantedBadges = parsePostGrantedBadges(pObj["badges_granted"])

        val cakedateRaw = pObj["user_cakedate"]?.jsonPrimitive?.contentOrNull
        val birthdateRaw = pObj["user_birthdate"]?.jsonPrimitive?.contentOrNull
        val parsedFlair = parseFlair(pObj)
        val postAuthorTopicUser = TopicUser(
            id = userId,
            username = username,
            name = displayName,
            avatarTemplate = avatarTemplate,
            animatedAvatar = animatedAvatar,
            primaryGroupName = primaryGroupName,
            flairName = parsedFlair.name,
            flairUrl = parsedFlair.url,
            flairBgColor = parsedFlair.bgColorHex,
            flairColor = parsedFlair.fgColorHex,
            flairGroupId = parsedFlair.groupId,
            trustLevel = trustLevel,
            admin = admin,
            moderator = moderator
        )
        cacheTopicUserVisuals(postAuthorTopicUser)
        if (pid > 0) {
            putBounded(postAuthorCache, pid, postAuthorTopicUser)
        }
        if (postNumber == 1 && resolvedTopicId > 0) {
            putBounded(topicOpCache, resolvedTopicId, postAuthorTopicUser)
        }

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
            flair = parsedFlair,
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
            isSmallAction = postType == 3 || actionCode.isNotEmpty(),
            actionCode = actionCode.takeIf { it.isNotEmpty() },
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
                    putBounded(topicExcerptCache, id, ex)
                }
            }
        }

        val detailsObj = root["details"] as? JsonObject
        val notifLevel = detailsObj?.get("notification_level")?.jsonPrimitive?.intOrNull ?: 1
        val canEditTopic = detailsObj?.get("can_edit")?.jsonPrimitive?.booleanOrNull == true
        var smallActionClosed: Boolean? = null
        var smallActionArchived: Boolean? = null
        for (p in posts) {
            if (!p.isSmallAction) continue
            val code = p.actionCode?.lowercase()
            when {
                code in SmallActionKind.CLOSED.wireCodes || code in SmallActionKind.AUTOCLOSED.wireCodes ->
                    smallActionClosed = true
                code in SmallActionKind.OPENED.wireCodes || code in SmallActionKind.AUTOOPENED.wireCodes ->
                    smallActionClosed = false
                code in SmallActionKind.ARCHIVED.wireCodes ->
                    smallActionArchived = true
                code in SmallActionKind.UNARCHIVED.wireCodes ->
                    smallActionArchived = false
            }
        }
        val closed = root["closed"]?.jsonPrimitive?.booleanOrNull
            ?: smallActionClosed
            ?: summaryFallback?.closed
            ?: false
        val archived = root["archived"]?.jsonPrimitive?.booleanOrNull
            ?: smallActionArchived
            ?: summaryFallback?.archived
            ?: false
        return TopicDetailModel(
            id = id,
            title = title,
            originalPosterUsername = detailsObj?.get("created_by")?.let { (it as? JsonObject)?.get("username")?.jsonPrimitive?.contentOrNull },
            categoryId = categoryId,
            categoryName = catName,
            categoryColorHex = catColor,
            tags = tags,
            views = views,
            likeCount = likeCount,
            postsCount = postsCount,
            highestPostNumber = root["highest_post_number"]?.jsonPrimitive?.intOrNull,
            lastReadPostNumber = lastReadPostNumber,
            notificationLevel = notifLevel,
            closed = closed,
            archived = archived,
            canEditTopic = canEditTopic,
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
            val uname = uObj["username"]?.jsonPrimitive?.contentOrNull ?: "user_$id"
            val avatarTpl = uObj["avatar_template"]?.jsonPrimitive?.contentOrNull ?: ""
            val topicUser = TopicUser(
                id = id,
                username = uname,
                name = uObj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                avatarTemplate = avatarTpl,
                animatedAvatar = uObj["animated_avatar"]?.jsonPrimitive?.contentOrNull,
                primaryGroupName = uObj["primary_group_name"]?.jsonPrimitive?.contentOrNull,
                flairName = uObj["flair_name"]?.jsonPrimitive?.contentOrNull,
                flairUrl = uObj["flair_url"]?.jsonPrimitive?.contentOrNull,
                flairBgColor = uObj["flair_bg_color"]?.jsonPrimitive?.contentOrNull,
                flairColor = uObj["flair_color"]?.jsonPrimitive?.contentOrNull,
                flairGroupId = uObj["flair_group_id"]?.jsonPrimitive?.intOrNull,
                trustLevel = uObj["trust_level"]?.jsonPrimitive?.intOrNull ?: 1,
                admin = uObj["admin"]?.jsonPrimitive?.booleanOrNull ?: false,
                moderator = uObj["moderator"]?.jsonPrimitive?.booleanOrNull ?: false
            )
            cacheTopicUserVisuals(topicUser)
            usersMap[id] = topicUser
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
            if (id > 0 && op.username != "linuxdo") {
                putBounded(topicOpCache, id, op)
            }
            val categoryId = tObj["category_id"]?.jsonPrimitive?.intOrNull ?: 4
            val (catName, catColor) = resolveCategoryMeta(categoryId)
            val tags = parseTagsArray(tObj["tags"] as? JsonArray)
            val createdIso = tObj["created_at"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val lastPostedIso = tObj["bumped_at"]?.jsonPrimitive?.contentOrNull
                ?: tObj["last_posted_at"]?.jsonPrimitive?.contentOrNull
                ?: createdIso.takeIf { it.isNotEmpty() }

            val rawPostsCount = tObj["posts_count"]?.jsonPrimitive?.intOrNull ?: 1
            val rawHighest = maxOf(
                tObj["highest_post_number"]?.jsonPrimitive?.intOrNull ?: rawPostsCount,
                rawPostsCount
            )
            val rawUnseen = tObj["unseen"]?.jsonPrimitive?.booleanOrNull ?: false
            val rawUnreadPosts = (tObj["unread_posts"]?.jsonPrimitive?.intOrNull
                ?: tObj["unread"]?.jsonPrimitive?.intOrNull
                ?: tObj["new_posts"]?.jsonPrimitive?.intOrNull
                ?: 0).coerceAtLeast(0)
            val serverLastRead = tObj["last_read_post_number"]?.jsonPrimitive?.intOrNull

            if (rawUnseen) {
                val removed = serverConfirmedReadFloor.remove(id) != null
                synchronized(readTopicsStateLock) {
                    readTopicOrder.remove(id)
                }
                if (removed) {
                    bumpReadTopicsVersion()
                }
            } else if (serverLastRead != null && serverLastRead > 0) {
                val localConfirmed = serverConfirmedReadFloor[id] ?: 0
                if (serverLastRead >= localConfirmed) {
                    recordServerConfirmedReadFloor(id, serverLastRead, notify = false, persist = false)
                }
            }

            val confirmedFloor = serverConfirmedReadFloor[id]
            val effectiveUnseen = if (confirmedFloor != null && confirmedFloor >= 1) false else rawUnseen
            val effectiveUnreadPosts = when {
                confirmedFloor != null && (serverLastRead == null || confirmedFloor > serverLastRead) ->
                    minOf(rawUnreadPosts, (rawHighest - confirmedFloor).coerceAtLeast(0))
                else -> rawUnreadPosts
            }

            val rawExcerpt = tObj["excerpt"]?.jsonPrimitive?.contentOrNull
            val cleanedExcerpt = extractPlainExcerptFromHtml(rawExcerpt).takeIf { it.isNotBlank() }
                ?.also { putBounded(topicExcerptCache, id, it) }

            TopicSummary(
                id = id,
                title = title,
                postsCount = rawPostsCount,
                highestPostNumber = rawHighest,
                replyCount = tObj["reply_count"]?.jsonPrimitive?.intOrNull ?: 0,
                views = tObj["views"]?.jsonPrimitive?.intOrNull ?: 0,
                likeCount = tObj["like_count"]?.jsonPrimitive?.intOrNull ?: 0,
                categoryId = categoryId,
                categoryName = catName,
                categoryColorHex = catColor,
                tags = tags,
                lastPostedAt = formatRelativeTime(lastPostedIso),
                createdAtIso = createdIso,
                lastPostedAtIso = lastPostedIso.orEmpty(),
                excerpt = cleanedExcerpt,
                pinned = tObj["pinned"]?.jsonPrimitive?.booleanOrNull ?: false,
                closed = tObj["closed"]?.jsonPrimitive?.booleanOrNull ?: false,
                archived = tObj["archived"]?.jsonPrimitive?.booleanOrNull ?: false,
                unseen = effectiveUnseen,
                unreadPosts = effectiveUnreadPosts,
                hasAcceptedAnswer = tObj["has_accepted_answer"]?.jsonPrimitive?.booleanOrNull ?: false,
                originalPoster = op,
                participantAvatars = posters
            )
        }
    }

    data class UserVisualMeta(
        val avatarTemplate: String = "",
        val animatedAvatar: String? = null,
        val primaryGroupName: String? = null,
        val flair: FlairInfo = FlairInfo()
    )

    private val userAvatarCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val userVisualCache = java.util.concurrent.ConcurrentHashMap<String, UserVisualMeta>()
    private val topicOpCache = java.util.concurrent.ConcurrentHashMap<Int, TopicUser>()
    private val postAuthorCache = java.util.concurrent.ConcurrentHashMap<Int, TopicUser>()
    private val inFlightAvatarJobs = java.util.concurrent.ConcurrentHashMap<String, Deferred<String?>>()
    private val inFlightNotificationActorJobs = java.util.concurrent.ConcurrentHashMap<String, Deferred<TopicUser?>>()
    // 独立的轻量头像/作者元数据解析并发信号量（与后台帖子摘要爬取完全隔离，消除排队饥饿）
    private val avatarResolveSemaphore = Semaphore(6)

    internal fun clearAvatarAndActorCachesForTest() {
        userAvatarCache.clear()
        userVisualCache.clear()
        topicOpCache.clear()
        postAuthorCache.clear()
        rateLimitedUntilMs.set(0L)
    }

    fun cacheUserAvatar(username: String?, avatarTemplate: String?) {
        val systemActor = LinDoCommonCopy.SYSTEM_ACTOR.text
        val cleanName = username?.trim()?.removePrefix("@")?.lowercase()?.takeIf {
            it.isNotEmpty() && it != systemActor && it != "linuxdo" && !consolidatedCountExtractRegex.matches(it)
        } ?: return
        val cleanTpl = avatarTemplate?.trim()?.takeIf { it.isNotEmpty() } ?: return
        putBounded(userAvatarCache, cleanName, cleanTpl)
        val prev = userVisualCache[cleanName]
        if (prev == null || prev.avatarTemplate != cleanTpl) {
            putBounded(userVisualCache, cleanName, (prev ?: UserVisualMeta()).copy(avatarTemplate = cleanTpl))
        }
    }

    fun cacheUserVisualMeta(
        username: String?,
        avatarTemplate: String? = null,
        animatedAvatar: String? = null,
        primaryGroupName: String? = null,
        flair: FlairInfo = FlairInfo()
    ) {
        val systemActor = LinDoCommonCopy.SYSTEM_ACTOR.text
        val cleanName = username?.trim()?.removePrefix("@")?.lowercase()?.takeIf {
            it.isNotEmpty() && it != systemActor && it != "linuxdo" && !consolidatedCountExtractRegex.matches(it)
        } ?: return
        val cleanTpl = avatarTemplate?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanTpl != null) {
            putBounded(userAvatarCache, cleanName, cleanTpl)
        }
        val prev = userVisualCache[cleanName]
        val merged = UserVisualMeta(
            avatarTemplate = cleanTpl ?: prev?.avatarTemplate.orEmpty(),
            animatedAvatar = animatedAvatar?.trim()?.takeIf { it.isNotEmpty() } ?: prev?.animatedAvatar,
            primaryGroupName = primaryGroupName?.trim()?.takeIf { it.isNotEmpty() } ?: prev?.primaryGroupName,
            flair = if (flair.hasFlair) flair else (prev?.flair ?: FlairInfo())
        )
        if (merged.avatarTemplate.isNotEmpty() || merged.animatedAvatar != null || merged.primaryGroupName != null || merged.flair.hasFlair) {
            putBounded(userVisualCache, cleanName, merged)
        }
    }

    private fun cacheTopicUserVisuals(user: TopicUser) {
        cacheUserVisualMeta(
            username = user.username,
            avatarTemplate = user.avatarTemplate,
            animatedAvatar = user.animatedAvatar,
            primaryGroupName = user.primaryGroupName,
            flair = user.flair
        )
    }

    fun getCachedUserAvatar(username: String): String? {
        val key = username.trim().removePrefix("@").lowercase().takeIf { it.isNotEmpty() } ?: return null
        return userAvatarCache[key]?.takeIf { it.isNotBlank() }
    }

    fun getCachedUserVisualMeta(username: String): UserVisualMeta? {
        val key = username.trim().removePrefix("@").lowercase().takeIf { it.isNotEmpty() } ?: return null
        return userVisualCache[key]
    }

    suspend fun resolveUserAvatarTemplate(username: String): String? = withContext(Dispatchers.IO) {
        val systemActor = LinDoCommonCopy.SYSTEM_ACTOR.text
        val clean = username.trim().removePrefix("@").takeIf {
            it.isNotEmpty() && it != systemActor && it != "linuxdo" && !consolidatedCountExtractRegex.matches(it)
        } ?: return@withContext null
        getCachedUserAvatar(clean)?.let { return@withContext it }
        val key = clean.lowercase()
        inFlightAvatarJobs[key]?.let { existing ->
            if (existing.isActive || existing.isCompleted) return@withContext existing.await()
        }
        val deferred = excerptPrefetchScope.async {
            try {
                avatarResolveSemaphore.withPermit {
                    getCachedUserAvatar(clean)?.let { return@withPermit it }
                    if (isChallengeCooldownActive()) return@withPermit null
                    val cooldownRemain = (rateLimitedUntilMs.get() - System.currentTimeMillis()).coerceAtMost(3_000L)
                    if (cooldownRemain > 0L) {
                        kotlinx.coroutines.delay(cooldownRemain)
                    }
                    if (isChallengeCooldownActive()) return@withPermit null
                    val encoded = java.net.URLEncoder.encode(clean, "UTF-8")
                    val bgHeaders = mapOf("Discourse-Background" to "true")
                    val cardRes = executeHttp(
                        url = "https://linux.do/u/$encoded/card.json",
                        method = "GET",
                        extraHeaders = bgHeaders,
                        preserveFailure = true
                    )
                    if (cardRes != null && (cardRes.first == 429 || looksLikeCloudflareChallenge(cardRes.second))) {
                        return@withPermit null
                    }
                    val res = if (cardRes != null && isSuccessfulApiResponse(cardRes.first, cardRes.second)) {
                        cardRes
                    } else {
                        executeHttp(
                            url = "https://linux.do/u/$encoded.json",
                            method = "GET",
                            extraHeaders = bgHeaders,
                            preserveFailure = true
                        )?.takeIf { isSuccessfulApiResponse(it.first, it.second) }
                    }
                    val body = res?.second
                    if (res != null && res.first in 200..299 && body != null && body.trimStart().startsWith("{")) {
                        val parsed = runCatching {
                            val root = json.parseToJsonElement(body).jsonObject
                            val uObj = (root["user"] as? JsonObject) ?: root
                            val tpl = uObj["avatar_template"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                            val anim = uObj["animated_avatar"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                            val group = uObj["primary_group_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                            val fl = parseFlair(uObj)
                            if (!tpl.isNullOrBlank() || anim != null || group != null || fl.hasFlair) {
                                cacheUserVisualMeta(clean, tpl, anim, group, fl)
                            }
                            tpl
                        }.getOrNull()
                        if (!parsed.isNullOrBlank()) {
                            return@withPermit parsed
                        }
                    }
                    null
                }
            } finally {
                inFlightAvatarJobs.remove(key)
            }
        }
        val prev = inFlightAvatarJobs.putIfAbsent(key, deferred)
        if (prev != null && (prev.isActive || prev.isCompleted)) {
            deferred.cancel()
            return@withContext prev.await()
        }
        deferred.await()
    }

    /**
     * 补全被 Discourse `discourse-follow` 插件合并覆盖了 `display_username`（如 `"2 个回复"`）且缺失 `acting_user` 的话题/关注通知作者信息。
     */
    suspend fun resolveNotificationActor(item: UserNotificationItem): TopicUser? = withContext(Dispatchers.IO) {
        if (item.actorUsername != null) return@withContext null
        val kind = item.kind
        if (!kind.needsPostAuthorLookup) return@withContext null
        if (item.originalPostId <= 0 && item.topicId <= 0) return@withContext null
        val isFirstPostKind = kind == DiscourseNotificationKind.WATCHING_FIRST_POST ||
            kind == DiscourseNotificationKind.PLUGIN_FOLLOW_TOPIC

        if (item.originalPostId > 0) {
            postAuthorCache[item.originalPostId]?.let { return@withContext it }
        }
        if (item.topicId > 0 && (isFirstPostKind || item.postNumber <= 1)) {
            topicOpCache[item.topicId]?.let { return@withContext it }
        }

        val jobKey = if (item.originalPostId > 0) {
            "post:${item.originalPostId}"
        } else {
            "topic:${item.topicId}:${item.postNumber.coerceAtLeast(1)}"
        }
        inFlightNotificationActorJobs[jobKey]?.let { existing ->
            if (existing.isActive || existing.isCompleted) return@withContext existing.await()
        }
        val deferred = excerptPrefetchScope.async {
            try {
                avatarResolveSemaphore.withPermit {
                    if (item.originalPostId > 0) {
                        postAuthorCache[item.originalPostId]?.let { return@withPermit it }
                    }
                    if (item.topicId > 0 && (isFirstPostKind || item.postNumber <= 1)) {
                        topicOpCache[item.topicId]?.let { return@withPermit it }
                    }
                    if (isChallengeCooldownActive()) return@withPermit null
                    val cooldownRemain = (rateLimitedUntilMs.get() - System.currentTimeMillis()).coerceAtMost(3_000L)
                    if (cooldownRemain > 0L) {
                        kotlinx.coroutines.delay(cooldownRemain)
                    }
                    if (isChallengeCooldownActive()) return@withPermit null
                    val bgHeaders = mapOf("Discourse-Background" to "true")

                    if (item.originalPostId > 0) {
                        val pRes = executeHttp(
                            "https://linux.do/posts/${item.originalPostId}.json",
                            method = "GET",
                            extraHeaders = bgHeaders,
                            preserveFailure = true
                        )
                        if (pRes != null && (pRes.first == 429 || looksLikeCloudflareChallenge(pRes.second))) {
                            return@withPermit null
                        }
                        val pBody = pRes?.second
                        if (pRes != null && isSuccessfulApiResponse(pRes.first, pBody ?: "") && pBody != null && pBody.trimStart().startsWith("{")) {
                            val resolved = runCatching {
                                val root = json.parseToJsonElement(pBody).jsonObject
                                val pObj = (root["post"] as? JsonObject) ?: root
                                parseSinglePostObject(pObj = pObj, fallbackIdx = 0, topicId = item.topicId)
                                postAuthorCache[item.originalPostId]
                            }.getOrNull()
                            if (resolved != null && resolved.username.isNotBlank() && !resolved.username.startsWith("user_")) {
                                if (isFirstPostKind && item.topicId > 0) {
                                    putBounded(topicOpCache, item.topicId, resolved)
                                }
                                return@withPermit resolved
                            }
                        }
                    }

                    if (item.topicId > 0) {
                        val targetFloor = if (isFirstPostKind) 1 else item.postNumber.coerceAtLeast(1)
                        val tRes = executeHttp(
                            "https://linux.do/t/${item.topicId}/$targetFloor.json",
                            method = "GET",
                            extraHeaders = bgHeaders
                        )
                        val tBody = tRes?.second
                        if (tRes != null && tRes.first in 200..299 && tBody != null && tBody.trimStart().startsWith("{")) {
                            val resolved = runCatching {
                                val root = json.parseToJsonElement(tBody).jsonObject
                                val createdBy = (root["details"] as? JsonObject)?.get("created_by") as? JsonObject
                                val postsArr = (root["post_stream"] as? JsonObject)?.get("posts") as? JsonArray
                                val targetPostObj = postsArr?.mapNotNull { it as? JsonObject }?.firstOrNull {
                                    it["post_number"]?.jsonPrimitive?.intOrNull == targetFloor
                                } ?: (postsArr?.firstOrNull() as? JsonObject)
                                if (targetPostObj != null) {
                                    val parsed = parseSinglePostObject(pObj = targetPostObj, fallbackIdx = 0, topicId = item.topicId)
                                    parsed?.let { postAuthorCache[it.id] }
                                } else if (createdBy != null && targetFloor == 1) {
                                    val uname = createdBy["username"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                                    val tpl = createdBy["avatar_template"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                    if (uname.isNotEmpty()) {
                                        TopicUser(
                                            id = createdBy["id"]?.jsonPrimitive?.intOrNull ?: 0,
                                            username = uname,
                                            name = createdBy["name"]?.jsonPrimitive?.contentOrNull,
                                            avatarTemplate = tpl
                                        ).also {
                                            cacheTopicUserVisuals(it)
                                            putBounded(topicOpCache, item.topicId, it)
                                        }
                                    } else null
                                } else null
                            }.getOrNull()
                            if (resolved != null) return@withPermit resolved
                        }
                    }
                    null
                }
            } finally {
                inFlightNotificationActorJobs.remove(jobKey)
            }
        }
        val prev = inFlightNotificationActorJobs.putIfAbsent(jobKey, deferred)
        if (prev != null && (prev.isActive || prev.isCompleted)) {
            deferred.cancel()
            return@withContext prev.await()
        }
        deferred.await()
    }

    private val topicExcerptCache = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private val inFlightExcerptJobs = java.util.concurrent.ConcurrentHashMap<Int, Deferred<String?>>()
    private val excerptPrefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // 限制后台摘要补全最大并发为 2（原为 8），彻底杜绝首屏瞬间突发 18+ 请求打满 WebView 连接池并触发 Linux.do 429 限流
    private val excerptFetchSemaphore = Semaphore(2)

    fun hasCachedTopicExcerpt(topicId: Int): Boolean =
        topicId > 0 && topicExcerptCache.containsKey(topicId)

    fun getCachedTopicExcerpt(topicId: Int): String? =
        topicExcerptCache[topicId]?.takeIf { it.isNotBlank() }

    fun cacheTopicExcerpt(topicId: Int, excerpt: String) {
        if (topicId > 0) {
            putBounded(topicExcerptCache, topicId, excerpt.trim(), maxEntries = 600)
        }
    }

    /**
     * 首屏轻量预水合（仅对首屏前 4 条缺失摘要的非置顶话题做有界 240ms 预热，其余条目随列表滚动按需防抖加载），
     * 避免一次性发起 18 条后台请求导致点击帖子时触发 HTTP 429（「请求太频繁，请稍后再试」）。
     */
    private suspend fun hydrateFirstScreenExcerptsAndPrefetchRest(topics: List<TopicSummary>): List<TopicSummary> {
        if (isRateLimitedNow() || isChallengeCooldownActive() || UserApiKeyAuthManager.uiCompactFeed) return topics
        val missingTopics = topics.filter { !it.pinned && it.id > 0 && it.excerpt.isNullOrBlank() && !topicExcerptCache.containsKey(it.id) }
        if (missingTopics.isEmpty()) return topics

        val firstScreenIds = missingTopics.take(4).map { it.id }
        firstScreenIds.forEach { tid -> startExcerptFetchAsync(tid) }

        withTimeoutOrNull(240L) {
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
        val cleanedHtml = raw
            .replace(Regex("<aside[\\s\\S]*?</aside>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<div[^>]*class\\s*=\\s*[\"'][^\"']*meta[^\"']*[\"'][\\s\\S]*?</div>", RegexOption.IGNORE_CASE), " ")
        val stripped = decodeDiscourseHtmlSnippet(cleanedHtml)
        return when {
            stripped.isNotEmpty() -> if (stripped.length > 160) stripped.take(160) + "..." else stripped
            hasContentImage -> LinDoNetworkCopy.IMAGE_BRACKET_PLACEHOLDER.text
            else -> ""
        }
    }

    /**
     * 按需静默补全话题一楼纯文本摘要：
     * 仅请求轻量级单帖接口 `/posts/by_number/{topicId}/1.json`（仅约 2KB，天然不计入阅读），
     * 若遇前台点击导航或 429 冷却期自动退让，且绝不回退请求重型 `/t/{topicId}.json` 接口。
     */
    suspend fun fetchTopicExcerpt(topicId: Int): String? = withContext(Dispatchers.IO) {
        if (topicId <= 0) return@withContext null
        topicExcerptCache[topicId]?.let { cached ->
            return@withContext cached.takeIf { it.isNotBlank() }
        }
        if (isRateLimitedNow() || isChallengeCooldownActive()) return@withContext null
        startExcerptFetchAsync(topicId).await()
    }

    private suspend fun performTopicExcerptFetch(topicId: Int): String? {
        topicExcerptCache[topicId]?.let { cached ->
            return cached.takeIf { it.isNotBlank() }
        }
        if (isRateLimitedNow() || isChallengeCooldownActive()) return null
        return excerptFetchSemaphore.withPermit {
            topicExcerptCache[topicId]?.let { cached ->
                return@withPermit cached.takeIf { it.isNotBlank() }
            }
            if (isRateLimitedNow() || isChallengeCooldownActive()) return@withPermit null
            if (isForegroundPriorityActive()) {
                kotlinx.coroutines.delay(320L)
                if (isRateLimitedNow() || isChallengeCooldownActive()) return@withPermit null
            }
            val bgHeaders = mapOf("Discourse-Background" to "true")
            val fastUrl = "https://linux.do/posts/by_number/" + topicId.toString() + "/1.json"
            val fastRes = executeHttp(url = fastUrl, method = "GET", extraHeaders = bgHeaders, preserveFailure = true)
            val statusCode = fastRes?.first ?: 0
            if (statusCode == 429) {
                noteRateLimitHit()
                return@withPermit null
            }
            val fastBody = fastRes?.second
            if (fastBody != null && looksLikeCloudflareChallenge(fastBody)) {
                noteChallengeHit()
                return@withPermit null
            }
            if (statusCode in 200..299 && fastBody != null && fastBody.trimStart().startsWith("{")) {
                val fastExtracted = runCatching {
                    val root = json.parseToJsonElement(fastBody).jsonObject
                    val cooked = root["cooked"]?.jsonPrimitive?.contentOrNull
                    extractPlainExcerptFromHtml(cooked)
                }.getOrDefault("")
                putBounded(topicExcerptCache, topicId, fastExtracted, maxEntries = 600)
                kotlinx.coroutines.delay(80L)
                return@withPermit fastExtracted.takeIf { it.isNotBlank() }
            }
            if (statusCode in listOf(403, 404, 410)) {
                putBounded(topicExcerptCache, topicId, "", maxEntries = 600)
            }
            kotlinx.coroutines.delay(80L)
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
                diffSec < 60 -> LinDoCommonCopy.ONE_MINUTE.text
                diffSec < 3600 -> LinDoCommonCopy.UNIT_MINUTES_AGO.format((diffSec / 60).coerceAtLeast(1))
                diffSec < 86400 -> LinDoCommonCopy.UNIT_HOURS_AGO.format((diffSec / 3600).coerceAtLeast(1))
                diffSec < 86400 * 30 -> LinDoCommonCopy.UNIT_DAYS_AGO.format((diffSec / 86400).coerceAtLeast(1))
                else -> {
                    val zone = java.time.ZoneId.systemDefault()
                    val targetDate = instant.atZone(zone)
                    val currentYear = now.atZone(zone).year
                    if (targetDate.year == currentYear) {
                        LinDoCommonCopy.DATE_MONTH_DAY.format(targetDate.monthValue, targetDate.dayOfMonth)
                    } else {
                        LinDoCommonCopy.DATE_YEAR_MONTH.format(targetDate.year % 100, targetDate.monthValue)
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
        val winningCookies = CookieSentinel.resolveWinningCookies()
        val rawT = winningCookies["_t"]?.value.orEmpty()
        val hasWebCookie = rawT.isNotBlank() && rawT != "demo_session_token"
        val hasApiKey = !UserApiKeyAuthManager.getApiKey().isNullOrBlank()
        val isLoggedIn = UserApiKeyAuthManager.isAuthorized() || hasWebCookie || hasApiKey
        if (!isLoggedIn) {
            return@withContext null
        }
        if (isChallengeCooldownActive()) {
            return@withContext ScreenTrackEngine.STATUS_SITE_VERIFICATION_REQUIRED
        }

        val headers = UserPresenceService.buildPresenceHeaders(isBackgroundTimingRequest = true, isLoggedIn = true) +
            ("Referer" to "https://linux.do/t/${batch.topicId}")
        val params = buildMap {
            put("topic_id", batch.topicId.toString())
            put("topic_time", batch.topicTimeMs.toString())
            batch.timingsMs.forEach { (number, milliseconds) -> put("timings[$number]", milliseconds.toString()) }
        }
        val res = executeHttp(
            url = "https://linux.do/topics/timings",
            method = "POST",
            formParams = params,
            extraHeaders = headers,
            preserveFailure = true
        )
        if (res != null && looksLikeCloudflareChallenge(res.second)) {
            noteChallengeHit()
            return@withContext ScreenTrackEngine.STATUS_SITE_VERIFICATION_REQUIRED
        }
        val statusCode = res?.first
        if (statusCode != null && statusCode in 200..299) {
            val maxConfirmedFloor = batch.timingsMs.keys.maxOrNull() ?: 0
            if (maxConfirmedFloor > 0) {
                recordServerConfirmedReadFloor(batch.topicId, maxConfirmedFloor, notify = true)
            }
        }
        statusCode
    }

    /**
     * 获取 `connect.linux.do` 信任等级概览（可见完整滚动周期数据请直接打开同源 `https://connect.linux.do/` WebView）：
     * 注意：在已挂载 WebView 的平台严禁通过 OkHttp 直连 `connect.linux.do` 发送 `cf_clearance`，防止跨 TLS 栈污染会话。
     */
    suspend fun fetchConnectTrustLevelReport(): ConnectTrustLevelReport = withContext(Dispatchers.IO) {
        if (!UserApiKeyAuthManager.isAuthorized()) {
            return@withContext ConnectStatsParser.buildSessionBaselineReport(
                sessionTopicsEntered = ScreenTrackEngine.sessionTopicsEntered,
                sessionPostsRead = ScreenTrackEngine.sessionPostsRead
            )
        }

        if (UserApiKeyAuthManager.webViewHttpExecutor == null && !UserApiKeyAuthManager.requireBrowserTransportOnly) {
            try {
                val allowedHosts = setOf("connect.linux.do", "linux.do")
                val redirectStatuses = setOf(301, 302, 303, 307, 308)
                var currentUri = java.net.URI("https://connect.linux.do/")
                for (hop in 0..6) {
                    val scheme = currentUri.scheme?.lowercase().orEmpty()
                    val currentHost = currentUri.host?.lowercase().orEmpty()
                    if (scheme != "https" || currentHost !in allowedHosts) break
                    val cookieHeader = CookieSentinel.buildCookieHeader(targetHost = currentHost)
                    val response = connectOAuthClient.get(currentUri.toString()) {
                        header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        UserApiKeyAuthManager.customUserAgent?.takeIf { it.isNotBlank() }?.let {
                            header("User-Agent", it)
                        }
                        if (cookieHeader.isNotEmpty()) {
                            header("Cookie", cookieHeader)
                        }
                    }
                    response.headers.getAll("Set-Cookie")?.forEach { setCookie ->
                        ingestSetCookieHeader(setCookie, domain = currentHost)
                    }
                    val status = response.status.value
                    if (status in redirectStatuses) {
                        val location = response.headers["Location"]?.trim()?.takeIf { it.isNotEmpty() } ?: break
                        currentUri = runCatching { currentUri.resolve(location) }.getOrNull() ?: break
                        continue
                    }
                    if (status in 200..299) {
                        val html = response.bodyAsText()
                        if (!looksLikeCloudflareChallenge(html)) {
                            ConnectStatsParser.parseConnectHtml(html)?.let { return@withContext it }
                        }
                    }
                    break
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
            }
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

    private fun extractRecipientsFromDraftElement(element: JsonElement?): String {
        return when (element) {
            is JsonPrimitive -> normalizePrivateMessageRecipients(element.contentOrNull.orEmpty())
            is JsonArray -> {
                val names = element.mapNotNull { item ->
                    when (item) {
                        is JsonPrimitive -> item.contentOrNull
                        is JsonObject -> item["id"]?.jsonPrimitive?.contentOrNull
                            ?: item["username"]?.jsonPrimitive?.contentOrNull
                        else -> null
                    }
                }.joinToString(",")
                normalizePrivateMessageRecipients(names)
            }
            else -> ""
        }
    }

    private fun parseCloudDraftObject(
        draftKey: String,
        sequence: Int,
        dObj: JsonObject?,
        outerTitle: String = "",
        outerCategoryId: Int? = null,
        outerTopicId: Int? = null,
        createdAtIso: String? = null
    ): CloudDraftItem {
        var title = outerTitle
        var rawReply = ""
        var draftCategoryId: Int? = outerCategoryId
        var draftTags: List<String> = emptyList()
        var replyToPostNumber: Int? = null
        var action = CloudDraftKind.resolve(action = "", draftKey = draftKey).wireAction
        var targetTopicId: Int? = null
        var targetPostId: Int? = draftKey.removePrefix("edit_post_").takeIf { draftKey.startsWith("edit_post_") }?.toIntOrNull()
        var recipients = ""
        if (dObj != null) {
            if (title.isBlank()) {
                title = dObj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
            }
            rawReply = dObj["reply"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (draftCategoryId == null) {
                draftCategoryId = dObj["categoryId"]?.jsonPrimitive?.intOrNull
                    ?: dObj["category_id"]?.jsonPrimitive?.intOrNull
            }
            draftTags = parseTagsArray(dObj["tags"] as? JsonArray)
            val rawAction = dObj["action"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            val resolvedFromRaw = CloudDraftKind.resolve(action = rawAction.orEmpty(), draftKey = draftKey)
            action = when {
                resolvedFromRaw == CloudDraftKind.PRIVATE_MESSAGE -> CloudDraftKind.PRIVATE_MESSAGE.wireAction
                draftKey.startsWith("edit_post_") -> CloudDraftKind.EDIT_POST.wireAction
                rawAction != null -> rawAction
                else -> action
            }
            targetTopicId = dObj["topicId"]?.jsonPrimitive?.intOrNull
            if (action.startsWith("edit")) {
                targetPostId = targetPostId ?: dObj["postId"]?.jsonPrimitive?.intOrNull
            }
            replyToPostNumber = dObj["replyToPostNumber"]?.jsonPrimitive?.intOrNull
                ?: if (action == CloudDraftKind.TOPIC_REPLY.wireAction) dObj["postId"]?.jsonPrimitive?.intOrNull else null
            recipients = extractRecipientsFromDraftElement(dObj["recipients"])
                .ifBlank { extractRecipientsFromDraftElement(dObj["target_recipients"]) }
        }
        val topicId = outerTopicId
            ?: targetTopicId
            ?: draftKey.removePrefix("topic_").takeIf { draftKey.startsWith("topic_") }?.toIntOrNull()
            ?: 0
        val createdAt = formatRelativeTime(createdAtIso)
        val finalKind = CloudDraftKind.resolve(action = action, draftKey = draftKey)
        val fallbackTitle = when {
            finalKind == CloudDraftKind.PRIVATE_MESSAGE ->
                if (recipients.isNotBlank()) LinDoNetworkCopy.DRAFT_PM_RECIPIENT.format(recipients)
                else LinDoNetworkCopy.DRAFT_UNTITLED_PM.text
            topicId > 0 -> LinDoNetworkCopy.DRAFT_REPLY_TOPIC_ID.format(topicId)
            else -> LinDoNetworkCopy.DRAFT_UNTITLED_NEW_TOPIC.text
        }
        return CloudDraftItem(
            draftKey = draftKey,
            sequence = sequence,
            title = title.ifBlank { fallbackTitle },
            excerpt = rawReply.replace(Regex("<[^>]+>"), "").trim(),
            topicId = topicId,
            createdAtText = createdAt,
            raw = rawReply,
            categoryId = draftCategoryId,
            tags = draftTags,
            replyToPostNumber = replyToPostNumber,
            action = action,
            postId = targetPostId,
            sequenceKnown = true,
            recipients = recipients
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
                val dataElem = obj["data"]
                val dObj: JsonObject? = when (dataElem) {
                    is JsonObject -> dataElem
                    is JsonPrimitive -> {
                        val str = dataElem.contentOrNull.orEmpty().trimStart()
                        if (str.startsWith("{")) runCatching { json.parseToJsonElement(str).jsonObject }.getOrNull() else null
                    }
                    else -> null
                }
                parseCloudDraftObject(
                    draftKey = draftKey,
                    sequence = seq,
                    dObj = dObj,
                    outerTitle = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    outerCategoryId = obj["category_id"]?.jsonPrimitive?.intOrNull,
                    outerTopicId = obj["topic_id"]?.jsonPrimitive?.intOrNull,
                    createdAtIso = obj["created_at"]?.jsonPrimitive?.contentOrNull
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
        val encoded = encodeUrlSegment(draftKey)
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
            url = "https://linux.do/follow/${encodeUrlSegment(username)}.json",
            method = if (follow) "PUT" else "DELETE",
            formParams = emptyMap(),
            preserveFailure = true
        )
        checkBooleanMutationResponse(res)
    }

    data class PollVoteSnapshot(
        val selectedIds: Set<String>,
        val optionVotes: Map<String, Int> = emptyMap(),
        val voters: Int? = null,
        val hasVoted: Boolean = selectedIds.isNotEmpty(),
        val closed: Boolean = false
    ) {
        val myVotes: Set<String>
            get() = selectedIds
    }

    private val pollVoteSnapshots = java.util.concurrent.ConcurrentHashMap<String, PollVoteSnapshot>()
    fun getPollVoteSnapshot(postId: Int, pollName: String): PollVoteSnapshot? = pollVoteSnapshots["$postId:$pollName"]
    fun clearPollVoteState() {
        pollVoteSnapshots.clear()
        postReactionSnapshots.clear()
        postBoostSnapshots.clear()
        cachedCsrfToken = null
    }

    /**
     * 帖内原生投票 / 修改投票 (`PUT /polls/vote.json`)
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
            val isClosed = pollObj["status"]?.jsonPrimitive?.contentOrNull.equals("closed", ignoreCase = true)
            putBounded(
                pollVoteSnapshots,
                "$postId:$pollName",
                PollVoteSnapshot(
                    selectedIds = confirmedIds,
                    optionVotes = votesByOption,
                    voters = votersCount,
                    hasVoted = true,
                    closed = isClosed
                )
            )
        }
        ok
    }

    suspend fun votePoll(postId: Int, pollName: String, options: List<String>): PollVoteSnapshot? {
        val ok = voteInPoll(postId = postId, pollName = pollName, options = options)
        return if (ok) getPollVoteSnapshot(postId, pollName) else null
    }

    /**
     * 撤回帖内投票 (`DELETE /polls/vote.json`)
     */
    suspend fun removePollVote(postId: Int, pollName: String): PollVoteSnapshot? = withContext(Dispatchers.IO) {
        val params = mapOf(
            "post_id" to postId.toString(),
            "poll_name" to pollName
        )
        val res = executeHttp(
            url = "https://linux.do/polls/vote.json?post_id=${encodeUrlSegment(postId.toString())}&poll_name=${encodeUrlSegment(pollName)}",
            method = "DELETE",
            formParams = params,
            preserveFailure = true
        )
        val root = requireMutationObject(res)
        val pollObj = (root["poll"] as? JsonObject) ?: root.takeIf { it.containsKey("options") }
        val votesByOption = (pollObj?.get("options") as? JsonArray)?.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val count = obj["votes"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            id to count
        }?.toMap().orEmpty()
        val votersCount = pollObj?.get("voters")?.jsonPrimitive?.intOrNull
        val isClosed = pollObj?.get("status")?.jsonPrimitive?.contentOrNull.equals("closed", ignoreCase = true)
        val snapshot = PollVoteSnapshot(
            selectedIds = emptySet(),
            optionVotes = votesByOption,
            voters = votersCount,
            hasVoted = false,
            closed = isClosed
        )
        putBounded(pollVoteSnapshots, "$postId:$pollName", snapshot)
        snapshot
    }

    /**
     * 采纳指定楼层为最佳解答 (`POST /solution/accept.json`)
     */
    suspend fun acceptPostSolution(postId: Int): Boolean = withContext(Dispatchers.IO) {
        val res = executeHttp(
            url = "https://linux.do/solution/accept.json",
            method = "POST",
            formParams = mapOf("id" to postId.toString()),
            preserveFailure = true
        )
        checkBooleanMutationResponse(res)
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
            formParams = params,
            preserveFailure = true
        )
        checkBooleanMutationResponse(res)
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
                if (rawTpl.isNotBlank()) {
                    cacheUserAvatar(username, rawTpl)
                }
                val avatarUrl = if (rawTpl.isNotBlank()) {
                    LinuxDoSiteRules.resolveStaticAvatarUrl(rawTpl, 120)
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
                        displayName = fullName.ifBlank { LinDoNetworkCopy.GROUP_MENTION_DISPLAY.format(name) },
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
    /**
     * 按 `draft_key` 查询单条云端草稿与权威 `draft_sequence` (`GET /drafts/{draftKey}.json`)
     */
    suspend fun fetchCloudDraftByKey(draftKey: String): RemoteDraftLookup = withContext(Dispatchers.IO) {
        val encodedKey = encodeUrlSegment(draftKey)
        val root = readApiObject(executeHttp("https://linux.do/drafts/$encodedKey.json", preserveFailure = true))
        val sequence = root["draft_sequence"]?.jsonPrimitive?.intOrNull
            ?: root["sequence"]?.jsonPrimitive?.intOrNull
            ?: throw ApiReadException(ApiFailureKind.InvalidPayload)
        val draftElem = root["draft"]
        val parsedDraft: CloudDraftItem? = when (draftElem) {
            null, is JsonNull -> null
            is JsonObject -> {
                val item = parseCloudDraftObject(draftKey = draftKey, sequence = sequence, dObj = draftElem)
                item.takeIf { it.raw.isNotBlank() || it.recipients.isNotBlank() || draftElem["title"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true }
            }
            is JsonPrimitive -> {
                val rawStr = draftElem.contentOrNull?.trim().orEmpty()
                if (rawStr.isEmpty()) {
                    null
                } else if (rawStr.startsWith("{")) {
                    val dObj = runCatching { json.parseToJsonElement(rawStr).jsonObject }.getOrNull()
                    dObj?.let { obj ->
                        val item = parseCloudDraftObject(draftKey = draftKey, sequence = sequence, dObj = obj)
                        val explicitTitle = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        item.takeIf { it.raw.isNotBlank() || explicitTitle.isNotBlank() || it.recipients.isNotBlank() }
                    }
                } else {
                    null
                }
            }
            else -> null
        }
        RemoteDraftLookup(sequence = sequence, draft = parsedDraft)
    }

    suspend fun fetchDraftSequence(draftKey: String): Int =
        fetchCloudDraftByKey(draftKey).sequence

    suspend fun saveCloudDraft(
        draftKey: String,
        sequence: Int,
        replyRaw: String,
        title: String = "",
        categoryId: Int? = null,
        tags: List<String> = emptyList(),
        replyToPostNumber: Int? = null,
        action: String = CloudDraftKind.resolve(action = "", draftKey = draftKey).wireAction,
        topicId: Int? = null,
        postId: Int? = null,
        recipients: String = ""
    ): Int? = withContext(Dispatchers.IO) {
        val normalizedRecipients = normalizePrivateMessageRecipients(recipients)
        val data = buildJsonObject {
            put("reply", replyRaw)
            put("action", action)
            if (title.isNotBlank()) put("title", title)
            categoryId?.let { put("categoryId", it) }
            put("tags", buildJsonArray { tags.forEach { add(JsonPrimitive(it)) } })
            topicId?.let { put("topicId", it) }
            postId?.let { put("postId", it) }
            replyToPostNumber?.let { put("replyToPostNumber", it) }
            if (normalizedRecipients.isNotBlank()) {
                put("recipients", normalizedRecipients)
            }
        }
        val extraHeaders = if (topicId != null && topicId > 0) {
            mapOf("Referer" to "https://linux.do/t/$topicId")
        } else {
            emptyMap()
        }
        val root = requireMutationObject(executeHttp(
            url = "https://linux.do/drafts.json",
            method = "POST",
            formParams = mapOf(
                "draft_key" to draftKey,
                "sequence" to sequence.toString(),
                "data" to data.toString()
            ),
            extraHeaders = extraHeaders,
            preserveFailure = true
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
        val binaryUploadExecutor = PlatformMediaPicker.webViewBinaryUploadExecutor
        val webUploadExecutor = PlatformMediaPicker.webViewUploadExecutor
        val obj: JsonObject = when {
            binaryUploadExecutor != null -> {
                val uploadRes = binaryUploadExecutor(fileName, mimeType, bytes)
                requireMutationObject(uploadRes)
            }
            webUploadExecutor != null -> {
                val b64 = Base64.getEncoder().encodeToString(bytes)
                val uploadRes = webUploadExecutor(fileName, mimeType, b64)
                requireMutationObject(uploadRes)
            }
            else -> {
                if (UserApiKeyAuthManager.requireBrowserTransportOnly) {
                    throw ApiReadException(ApiFailureKind.Connection)
                }
                val fallbackRes: Pair<Int, String>? = try {
                    val csrfBody = executeHttp("https://linux.do/session/csrf.json", method = "GET")?.second.orEmpty()
                    val csrf = if (csrfBody.trimStart().startsWith("{")) {
                        runCatching { json.parseToJsonElement(csrfBody).jsonObject["csrf"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                    } else null
                    val cookieHeader = CookieSentinel.buildCookieHeader(targetHost = "linux.do")
                    val resp = uploadHttpClient.submitFormWithBinaryData(
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
                        UserApiKeyAuthManager.customUserAgent?.takeIf { it.isNotBlank() }?.let { header("User-Agent", it) }
                        if (!UserApiKeyAuthManager.hasValidWebSessionCookie()) {
                            UserApiKeyAuthManager.getApiKey()?.takeIf { it.isNotBlank() }?.let {
                                header("User-Api-Key", it)
                                header("User-Api-Client-Id", UserApiKeyAuthManager.getClientId())
                            }
                        }
                        if (!csrf.isNullOrBlank()) header("X-CSRF-Token", csrf)
                        if (cookieHeader.isNotEmpty()) header("Cookie", cookieHeader)
                    }
                    resp.headers.getAll("Set-Cookie")?.forEach { setCookie ->
                        ingestSetCookieHeader(setCookie, domain = "linux.do")
                    }
                    resp.status.value to resp.bodyAsText()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null
                }
                requireMutationObject(fallbackRes)
            }
        }
        try {
            val shortUrl = obj["short_url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val fullUrl = obj["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val targetUrl = shortUrl.ifBlank { fullUrl }
            if (targetUrl.isBlank()) return@withContext null
            val origName = obj["original_filename"]?.jsonPrimitive?.contentOrNull?.ifBlank { fileName } ?: fileName
            val safeName = origName
                .replace("\r", "")
                .replace("\n", " ")
                .replace("[", "(")
                .replace("]", ")")
                .replace("|", "-")
                .trim()
                .ifBlank { "upload" }
            val width = obj["width"]?.jsonPrimitive?.intOrNull ?: 0
            val height = obj["height"]?.jsonPrimitive?.intOrNull ?: 0
            val humanSize = obj["human_filesize"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val isImage = mimeType.startsWith("image/", ignoreCase = true) || (width > 0 && height > 0)
            val markdown = if (isImage) {
                if (width > 0 && height > 0) {
                    "![${safeName}|${width}x${height}]($targetUrl)"
                } else {
                    "![${safeName}]($targetUrl)"
                }
            } else {
                val sizeSuffix = if (humanSize.isNotBlank()) " ($humanSize)" else ""
                "[${safeName}|attachment]($targetUrl)$sizeSuffix"
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
            method = "GET",
            preserveFailure = true,
            clientOverride = longReadClient
        ) ?: return@withContext null
        try {
            val root = readApiObject(res)
            val summaryObj = root["ai_topic_summary"] as? JsonObject
            val text = summaryObj?.get("summarized_text")?.jsonPrimitive?.contentOrNull
                ?: root["summarized_text"]?.jsonPrimitive?.contentOrNull
            text?.trim()?.takeIf { it.isNotBlank() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 动态同步站点全量自定义表情包 (`GET /emojis.json`)，将飞书、B站、贴吧等官方分组注入 `LinuxDoEmojiCatalog`
     */
    suspend fun syncSiteEmojis(
        force: Boolean = false,
        background: Boolean = !force
    ): Boolean = withContext(Dispatchers.IO) {
        val ttlMs = 24L * 60L * 60L * 1000L
        if (!force && LinuxDoEmojiCatalog.hasSyncedServerEmojis() && System.currentTimeMillis() - emojisLastSyncedAtMs in 0 until ttlMs) {
            return@withContext true
        }
        val headers = if (background) mapOf("Discourse-Background" to "true") else emptyMap()
        siteEmojisSyncMutex.withLock {
            if (!force && LinuxDoEmojiCatalog.hasSyncedServerEmojis() && System.currentTimeMillis() - emojisLastSyncedAtMs in 0 until ttlMs) {
                return@withLock true
            }
            val res = executeHttp(
                url = "https://linux.do/emojis.json",
                method = "GET",
                extraHeaders = headers
            ) ?: return@withLock false
            if (res.first !in 200..299 || !res.second.trimStart().startsWith("{")) return@withLock false
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
                    emojisLastSyncedAtMs = System.currentTimeMillis()
                    true
                } else {
                    false
                }
            } catch (_: Throwable) {
                false
            }
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
     * 保存个人资料与偏好设置 (`PUT /u/{username}.json`)，透传服务端校验失败原因
     */
    suspend fun updateUserProfileInfoResult(
        username: String,
        displayName: String? = null,
        bioRaw: String? = null,
        title: String? = null,
        location: String? = null,
        website: String? = null,
        hidePresence: Boolean? = null,
        allowPrivateMessages: Boolean? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val targetUsername = username.ifBlank { UserApiKeyAuthManager.currentUser?.username.orEmpty() }
        if (targetUsername.isBlank() || targetUsername == "linuxdo_user") {
            return@withContext Result.failure(ApiReadException(ApiFailureKind.Authentication))
        }
        val params = LinkedHashMap<String, String>().apply {
            if (displayName != null) put("name", displayName.trim())
            if (bioRaw != null) put("bio_raw", bioRaw)
            if (title != null) put("title", title.trim())
            if (location != null) put("location", location.trim())
            if (website != null) put("website", website.trim())
            if (hidePresence != null) {
                put("hide_profile_and_presence", hidePresence.toString())
            }
            if (allowPrivateMessages != null) {
                put("allow_private_messages", allowPrivateMessages.toString())
            }
        }
        try {
            val res = executeHttp(
                url = "https://linux.do/u/${encodeUrlSegment(targetUsername)}.json",
                method = "PUT",
                formParams = params,
                preserveFailure = true
            )
            requireMutationSuccess(res)
            fetchCurrentUserProfile()
            Result.success(Unit)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun updateUserProfileInfo(
        username: String,
        displayName: String? = null,
        bioRaw: String? = null,
        title: String? = null,
        location: String? = null,
        website: String? = null,
        hidePresence: Boolean? = null,
        allowPrivateMessages: Boolean? = null
    ): Boolean = updateUserProfileInfoResult(
        username = username,
        displayName = displayName,
        bioRaw = bioRaw,
        title = title,
        location = location,
        website = website,
        hidePresence = hidePresence,
        allowPrivateMessages = allowPrivateMessages
    ).isSuccess

    /**
     * 读取个人账号与偏好设置 (`GET /u/{username}.json` + `/user-badges/{username}.json?grouped=true`)
     */
    suspend fun fetchUserAccountPreferences(username: String): UserAccountPreferencesSnapshot? = withContext(Dispatchers.IO) {
        val targetUsername = username.ifBlank { UserApiKeyAuthManager.currentUser?.username.orEmpty() }
        if (targetUsername.isBlank() || targetUsername == "linuxdo_user") throw ApiReadException(ApiFailureKind.Authentication)
        val encodedTarget = encodeUrlSegment(targetUsername)
        val (res, userBadgesRes) = coroutineScope {
            val dUser = async {
                executeHttp("https://linux.do/u/$encodedTarget.json", method = "GET", preserveFailure = true)
            }
            val dBadges = async {
                runCatching {
                    executeHttp("https://linux.do/user-badges/$encodedTarget.json?grouped=true", method = "GET")
                }.getOrNull()
            }
            dUser.await() to dBadges.await()
        }
        val nonNullRes = res ?: throw ApiReadException(ApiFailureKind.Connection)
        val root = readApiObject(nonNullRes)
        val u = root["user"] as? JsonObject ?: throw ApiReadException(ApiFailureKind.InvalidPayload)
        val uOpt = u["user_option"] as? JsonObject
        val badgeTitlesFromUser = parseUserBadgesFromRoot(root)
            .filter { it.allowTitle }
            .map { it.name.trim() }
            .filter { it.isNotEmpty() }
        val badgeTitlesFromEndpoint = if (userBadgesRes != null &&
            userBadgesRes.first in 200..299 &&
            userBadgesRes.second.trimStart().startsWith("{")
        ) {
            runCatching {
                val bRoot = json.parseToJsonElement(userBadgesRes.second).jsonObject
                val directDefs = BadgeInjector.readBadgeDefinitions(bRoot["badges"])
                    .filter { it.allowTitle }
                    .map { it.name.trim() }
                val grantedDefs = parseUserBadgesFromRoot(bRoot)
                    .filter { it.allowTitle }
                    .map { it.name.trim() }
                (directDefs + grantedDefs).filter { it.isNotEmpty() }
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val groupTitles = (u["groups"] as? JsonArray).orEmpty().mapNotNull { gElem ->
            val gObj = gElem as? JsonObject ?: return@mapNotNull null
            gObj["title"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        }
        val currentTitle = u["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val mergedTitles = buildList {
            addAll(badgeTitlesFromEndpoint)
            addAll(badgeTitlesFromUser)
            addAll(groupTitles)
            if (currentTitle.isNotEmpty()) add(currentTitle)
        }.distinct()
        val userAuthTokens = (u["user_auth_tokens"] as? JsonArray)?.size
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
            // 严禁将已截断去格式的 bio_excerpt 回退当作可编辑 Markdown bio_raw，防止覆盖丢失原始排版
            bioRaw = u["bio_raw"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            title = currentTitle,
            location = u["location"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            website = u["website"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            trustLevel = u["trust_level"]?.jsonPrimitive?.intOrNull ?: 1,
            createdAtText = u["created_at"]?.jsonPrimitive?.contentOrNull.orEmpty().take(10),
            twoFactorEnabled = u["second_factor_enabled"]?.jsonPrimitive?.booleanOrNull,
            hideProfileAndPresence = uOpt?.get("hide_profile_and_presence")?.jsonPrimitive?.booleanOrNull == true,
            allowPrivateMessages = uOpt?.get("allow_private_messages")?.jsonPrimitive?.booleanOrNull != false,
            activeDeviceCount = userAuthTokens,
            associatedAccounts = associatedAccounts,
            availableTitles = mergedTitles
        )
    }

    /**
     * 获取关注列表或粉丝列表 (`GET /u/{username}/follow/following.json` 或 `followers.json`)
     */
    suspend fun fetchUserFollowList(
        username: String,
        followers: Boolean = false
    ): List<FollowUserEntry> = withContext(Dispatchers.IO) {
        val targetUsername = username.ifBlank { UserApiKeyAuthManager.currentUser?.username.orEmpty() }
        if (targetUsername.isBlank() || targetUsername == "linuxdo_user") throw ApiReadException(ApiFailureKind.Authentication)
        val kind = if (followers) "followers" else "following"
        val myUsername = UserApiKeyAuthManager.currentUser?.username?.takeIf {
            it.isNotBlank() && it != "linuxdo_user"
        }
        val needMutualCheck = UserApiKeyAuthManager.isAuthorized() &&
            myUsername != null &&
            (followers || !targetUsername.equals(myUsername, ignoreCase = true))
        val (res, myFollowingSet) = coroutineScope {
            val dMain = async {
                executeHttp("https://linux.do/u/${encodeUrlSegment(targetUsername)}/follow/$kind.json", method = "GET", preserveFailure = true)
            }
            val dMine = async {
                if (!needMutualCheck) {
                    emptySet()
                } else {
                    runCatching {
                        val mineRes = executeHttp(
                            "https://linux.do/u/${encodeUrlSegment(myUsername)}/follow/following.json",
                            method = "GET"
                        )
                        val mineTrimmed = mineRes?.second?.trimStart().orEmpty()
                        if (mineRes != null && mineRes.first in 200..299 &&
                            (mineTrimmed.startsWith("{") || mineTrimmed.startsWith("["))
                        ) {
                            val mineElem = json.parseToJsonElement(mineTrimmed)
                            val mineArr: JsonArray = when (mineElem) {
                                is JsonArray -> mineElem
                                is JsonObject -> (mineElem["users"] as? JsonArray)
                                    ?: (mineElem["following"] as? JsonArray)
                                    ?: (mineElem["follow"] as? JsonArray)
                                    ?: JsonArray(emptyList())
                                else -> JsonArray(emptyList())
                            }
                            mineArr.mapNotNull { item ->
                                (item as? JsonObject)?.get("username")?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
                                    ?.takeIf { it.isNotEmpty() }
                            }.toSet()
                        } else {
                            emptySet()
                        }
                    }.getOrDefault(emptySet())
                }
            }
            dMain.await() to dMine.await()
        }
        val nonNullRes = res ?: throw ApiReadException(ApiFailureKind.Connection)
        if (nonNullRes.first !in 200..299) {
            readApiObject(nonNullRes)
        }
        val trimmed = nonNullRes.second.trimStart()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) throw ApiReadException(ApiFailureKind.InvalidPayload)
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
            val viewingOwnFollowing = !followers && (myUsername == null || targetUsername.equals(myUsername, ignoreCase = true))
            arr.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val uname = obj["username"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val uid = obj["id"]?.jsonPrimitive?.intOrNull ?: 0
                val dname = obj["name"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { uname } ?: uname
                val tpl = obj["avatar_template"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val avatarUrl = if (tpl.isNotBlank()) {
                    LinuxDoSiteRules.resolveStaticAvatarUrl(tpl, 120)
                } else ""
                val animRaw = obj["animated_avatar"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                val animatedAvatar = animRaw?.let { LinuxDoSiteRules.resolveAnimatedAvatarUrl(it) }
                cacheUserVisualMeta(
                    username = uname,
                    avatarTemplate = tpl,
                    animatedAvatar = animatedAvatar,
                    primaryGroupName = obj["primary_group_name"]?.jsonPrimitive?.contentOrNull,
                    flair = parseFlair(obj)
                )
                val isFollowing = viewingOwnFollowing ||
                    (uname.lowercase() in myFollowingSet) ||
                    (obj["is_following"]?.jsonPrimitive?.booleanOrNull == true)
                FollowUserEntry(
                    id = uid,
                    username = uname,
                    displayName = dname,
                    avatarUrl = avatarUrl,
                    animatedAvatar = animatedAvatar,
                    title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    trustLevel = obj["trust_level"]?.jsonPrimitive?.intOrNull ?: 1,
                    isFollowing = isFollowing
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

    private fun parseFollowFeedActivityItems(postsArr: JsonArray): List<UserActivityItem> {
        return postsArr.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val topicObj = obj["topic"] as? JsonObject
            val tid = obj["topic_id"]?.jsonPrimitive?.intOrNull
                ?: topicObj?.get("id")?.jsonPrimitive?.intOrNull
                ?: return@mapNotNull null
            val pnum = obj["post_number"]?.jsonPrimitive?.intOrNull ?: 1
            val rawTitle = obj["title"]?.jsonPrimitive?.contentOrNull
                ?: topicObj?.get("title")?.jsonPrimitive?.contentOrNull
                ?: topicObj?.get("fancy_title")?.jsonPrimitive?.contentOrNull
            val title = rawTitle?.let { decodeDiscourseHtmlSnippet(it) }?.takeIf { it.isNotBlank() }
                ?: LinDoCommonCopy.TOPIC_NUMBER_FALLBACK.format(tid)
            val excerptRaw = obj["excerpt"]?.jsonPrimitive?.contentOrNull
                ?: obj["cooked"]?.jsonPrimitive?.contentOrNull
                ?: ""
            val excerpt = decodeDiscourseHtmlSnippet(excerptRaw)
            val actorObj = obj["user"] as? JsonObject
            val actorName = obj["username"]?.jsonPrimitive?.contentOrNull
                ?: actorObj?.get("username")?.jsonPrimitive?.contentOrNull
                ?: ""
            val catId = obj["category_id"]?.jsonPrimitive?.intOrNull
                ?: topicObj?.get("category_id")?.jsonPrimitive?.intOrNull
                ?: 4
            val (catName, _) = resolveCategoryMeta(catId)
            val rawCreated = obj["created_at"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val createdAt = formatRelativeTime(rawCreated).ifBlank { rawCreated.take(10) }
            UserActivityItem(
                id = obj["id"]?.jsonPrimitive?.intOrNull ?: tid,
                topicId = tid,
                postNumber = pnum,
                title = if (actorName.isNotBlank()) "[$actorName] $title" else title,
                excerpt = excerpt,
                actionType = if (pnum <= 1) DiscourseUserActionKind.NEW_TOPIC.code else DiscourseUserActionKind.REPLY.code,
                createdAtText = createdAt,
                categoryName = catName
            )
        }
    }

    /**
     * 获取关注用户的最新动态（优先请求 `discourse-follow` 真实接口 `GET /follow/posts/{username}.json?limit=30`，兼容 `/u/{username}/follow/feed.json`）
     */
    suspend fun fetchUserFollowFeed(username: String, page: Int = 0): List<UserActivityItem> = withContext(Dispatchers.IO) {
        val targetUsername = username.ifBlank { UserApiKeyAuthManager.currentUser?.username.orEmpty() }
        if (targetUsername.isBlank() || targetUsername == "linuxdo_user") throw ApiReadException(ApiFailureKind.Authentication)
        val cleanPage = page.coerceAtLeast(0)
        val cursorPrefix = "${targetUsername.trim().lowercase()}:"
        val cursorQuery = if (cleanPage == 0) {
            userFollowFeedActivityCursors.keys.removeIf { it.startsWith(cursorPrefix) }
            ""
        } else {
            val cursor = userFollowFeedActivityCursors["$cursorPrefix$cleanPage"]
            if (cursor == "__EXHAUSTED__") return@withContext emptyList()
            if (!cursor.isNullOrBlank()) "&created_before=${encodeUrlSegment(cursor)}" else null
        }

        if (cleanPage == 0 || cursorQuery != null) {
            val postsUrl = "https://linux.do/follow/posts/${encodeUrlSegment(targetUsername)}.json?limit=30${cursorQuery.orEmpty()}"
            val postsRes = executeHttp(postsUrl, method = "GET", preserveFailure = true)
            if (postsRes != null && postsRes.first in 200..299 && postsRes.second.trimStart().startsWith("{")) {
                val parsed = runCatching {
                    val root = json.parseToJsonElement(postsRes.second).jsonObject
                    val postsArr = (root["posts"] as? JsonArray) ?: (root["feed"] as? JsonArray)
                    if (postsArr != null) {
                        val hasMore = (root["extras"] as? JsonObject)?.get("has_more")?.jsonPrimitive?.booleanOrNull == true
                        val lastCreatedAt = (postsArr.lastOrNull() as? JsonObject)
                            ?.get("created_at")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                        val nextKey = "$cursorPrefix${cleanPage + 1}"
                        if (hasMore && lastCreatedAt != null) {
                            userFollowFeedActivityCursors[nextKey] = lastCreatedAt
                        } else {
                            userFollowFeedActivityCursors[nextKey] = "__EXHAUSTED__"
                        }
                        parseFollowFeedActivityItems(postsArr)
                    } else null
                }.getOrNull()
                if (parsed != null) return@withContext parsed
            } else if (postsRes != null && postsRes.first != 404) {
                // Propagate real auth / permission / rate-limit errors if legacy endpoint isn't mocked
                if (postsRes.first in listOf(401, 403, 429) || postsRes.first in 500..599) {
                    readApiObject(postsRes)
                }
            }
        }

        val offsetParam = if (cleanPage > 0) "?offset=${cleanPage * 20}" else ""
        val res = executeHttp("https://linux.do/u/${encodeUrlSegment(targetUsername)}/follow/feed.json$offsetParam", method = "GET")
        if (res != null && res.first in 200..299 && res.second.trimStart().startsWith("{")) {
            try {
                val root = json.parseToJsonElement(res.second).jsonObject
                val postsArr = (root["posts"] as? JsonArray)
                    ?: (root["user_actions"] as? JsonArray)
                    ?: (root["feed"] as? JsonArray)
                if (postsArr != null) {
                    return@withContext parseFollowFeedActivityItems(postsArr)
                }
            } catch (_: Throwable) {
            }
        }
        emptyList()
    }

    /**
     * 获取关于社区及管理团队信息 (`GET /about.json`)
     */
    suspend fun fetchCommunityAboutInfo(): CommunityAboutData? = withContext(Dispatchers.IO) {
        val res = executeHttp("https://linux.do/about.json", method = "GET", preserveFailure = true)
            ?: throw ApiReadException(ApiFailureKind.Connection)
        val root = readApiObject(res)
        val about = root["about"] as? JsonObject ?: throw ApiReadException(ApiFailureKind.InvalidPayload)
        val stats = about["stats"] as? JsonObject
        val usersById = LinkedHashMap<Int, FollowUserEntry>()
        fun parseUserObj(u: JsonObject): FollowUserEntry? {
            val uid = u["id"]?.jsonPrimitive?.intOrNull ?: return null
            val uname = u["username"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val dname = u["name"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { uname } ?: uname
            val tpl = u["avatar_template"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val avatarUrl = if (tpl.isNotBlank()) {
                LinuxDoSiteRules.resolveStaticAvatarUrl(tpl, 120)
            } else ""
            cacheUserVisualMeta(
                username = uname,
                avatarTemplate = tpl,
                animatedAvatar = u["animated_avatar"]?.jsonPrimitive?.contentOrNull,
                primaryGroupName = u["primary_group_name"]?.jsonPrimitive?.contentOrNull,
                flair = parseFlair(u)
            )
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
            title = about["title"]?.jsonPrimitive?.contentOrNull ?: LinDoCommonCopy.LINUX_DO.text,
            description = decodeDiscourseHtmlSnippet(
                about["description"]?.jsonPrimitive?.contentOrNull
                    ?: LinDoCommonCopy.SLOGAN_FULL.text
            ),
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
    val twoFactorEnabled: Boolean?,
    val hideProfileAndPresence: Boolean,
    val allowPrivateMessages: Boolean,
    val activeDeviceCount: Int? = null,
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
    var webViewBinaryUploadExecutor: (suspend (fileName: String, mimeType: String, bytes: ByteArray) -> Pair<Int, String>?)? = null
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

typealias TopicPollSnapshot = LinuxDoRepository.PollVoteSnapshot

data class RemoteDraftLookup(
    val sequence: Int,
    val draft: CloudDraftItem?
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
    val action: String = CloudDraftKind.TOPIC_REPLY.wireAction,
    val postId: Int? = null,
    val sequenceKnown: Boolean = true,
    val recipients: String = ""
)
