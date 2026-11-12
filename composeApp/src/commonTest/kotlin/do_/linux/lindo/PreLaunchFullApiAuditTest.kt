package do_.linux.lindo

import do_.linux.lindo.core.compose.ComposerAction
import do_.linux.lindo.core.compose.ComposerDraft
import do_.linux.lindo.core.compose.ComposerDraftSession
import do_.linux.lindo.core.compose.LocalComposerDrafts
import do_.linux.lindo.core.connect.ConnectStatsParser
import do_.linux.lindo.core.connect.ScreenTrackEngine
import do_.linux.lindo.core.connect.UserPresenceService
import do_.linux.lindo.core.model.PostItemModel
import do_.linux.lindo.core.model.PrivateMessageSendOutcome
import do_.linux.lindo.core.model.UserFullProfileSummary
import do_.linux.lindo.core.network.CookieSentinel
import do_.linux.lindo.core.network.LinuxDoRepository
import do_.linux.lindo.core.network.PlatformMediaPicker
import do_.linux.lindo.core.network.PostSubmission
import do_.linux.lindo.core.network.UserApiKeyAuthManager
import do_.linux.lindo.core.network.onAuthenticationInvalidated
import do_.linux.lindo.core.network.parsePastedAuthOrCurlInput
import do_.linux.lindo.core.render.DiscourseAstParser
import do_.linux.lindo.core.render.MarkdownPreview
import do_.linux.lindo.core.render.PostBlockNode
import do_.linux.lindo.core.render.postPreviewText
import do_.linux.lindo.core.render.postQuoteMarkup
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreLaunchFullApiAuditTest {

    private var savedHttpExecutor = UserApiKeyAuthManager.webViewHttpExecutor
    private var savedUploadExecutor = PlatformMediaPicker.webViewUploadExecutor
    private var savedBinaryUploadExecutor = PlatformMediaPicker.webViewBinaryUploadExecutor
    private var savedAuthCallback = UserApiKeyAuthManager.onAuthStateChanged
    private var savedPersistSession = UserApiKeyAuthManager.onPersistSession
    private var savedPersistPendingKey = UserApiKeyAuthManager.onPersistPendingKey
    private var savedLogoutClear = UserApiKeyAuthManager.onLogoutPlatformClear
    private var savedOpenUrlLauncher = UserApiKeyAuthManager.openExternalUrlLauncher
    private var savedSyncCookie = UserApiKeyAuthManager.onSyncCookieToPlatform
    private var savedCustomUa = UserApiKeyAuthManager.customUserAgent
    private var savedAuthInvalidated = onAuthenticationInvalidated
    private var savedPrepareRecovery = UserApiKeyAuthManager.onPrepareManualRecovery
    private var savedRequireBrowserOnly = UserApiKeyAuthManager.requireBrowserTransportOnly
    private var savedDraftPersist = LocalComposerDrafts.onPersist

    @BeforeTest
    fun setUp() {
        savedHttpExecutor = UserApiKeyAuthManager.webViewHttpExecutor
        savedUploadExecutor = PlatformMediaPicker.webViewUploadExecutor
        savedBinaryUploadExecutor = PlatformMediaPicker.webViewBinaryUploadExecutor
        savedAuthCallback = UserApiKeyAuthManager.onAuthStateChanged
        savedPersistSession = UserApiKeyAuthManager.onPersistSession
        savedPersistPendingKey = UserApiKeyAuthManager.onPersistPendingKey
        savedLogoutClear = UserApiKeyAuthManager.onLogoutPlatformClear
        savedOpenUrlLauncher = UserApiKeyAuthManager.openExternalUrlLauncher
        savedSyncCookie = UserApiKeyAuthManager.onSyncCookieToPlatform
        savedCustomUa = UserApiKeyAuthManager.customUserAgent
        savedAuthInvalidated = onAuthenticationInvalidated
        savedPrepareRecovery = UserApiKeyAuthManager.onPrepareManualRecovery
        savedRequireBrowserOnly = UserApiKeyAuthManager.requireBrowserTransportOnly
        savedDraftPersist = LocalComposerDrafts.onPersist

        UserApiKeyAuthManager.requireBrowserTransportOnly = false
        UserApiKeyAuthManager.webViewHttpExecutor = null
        UserApiKeyAuthManager.onAuthStateChanged = null
        UserApiKeyAuthManager.onPersistSession = null
        UserApiKeyAuthManager.onPersistPendingKey = null
        UserApiKeyAuthManager.onLogoutPlatformClear = null
        UserApiKeyAuthManager.openExternalUrlLauncher = null
        UserApiKeyAuthManager.onSyncCookieToPlatform = null
        UserApiKeyAuthManager.customUserAgent = null
        onAuthenticationInvalidated = null
        UserApiKeyAuthManager.onPrepareManualRecovery = null
        PlatformMediaPicker.webViewUploadExecutor = null
        PlatformMediaPicker.webViewBinaryUploadExecutor = null
        LocalComposerDrafts.onPersist = null

        UserApiKeyAuthManager.logout()
        CookieSentinel.clearAll()
        LocalComposerDrafts.clearAll()
        LinuxDoRepository.clearUserSessionCaches()
        LinuxDoRepository.clearAllLocalCaches()
        LinuxDoRepository.clearAvatarAndActorCachesForTest()
        ScreenTrackEngine.resetForTest()
        UserPresenceService.setAppForeground(true)
    }

    @AfterTest
    fun tearDown() {
        UserApiKeyAuthManager.requireBrowserTransportOnly = false
        UserApiKeyAuthManager.webViewHttpExecutor = null
        PlatformMediaPicker.webViewUploadExecutor = null
        PlatformMediaPicker.webViewBinaryUploadExecutor = null
        UserApiKeyAuthManager.onAuthStateChanged = null
        UserApiKeyAuthManager.onPersistSession = null
        UserApiKeyAuthManager.onPersistPendingKey = null
        UserApiKeyAuthManager.onLogoutPlatformClear = null
        UserApiKeyAuthManager.openExternalUrlLauncher = null
        UserApiKeyAuthManager.onSyncCookieToPlatform = null
        UserApiKeyAuthManager.customUserAgent = null
        onAuthenticationInvalidated = null
        UserApiKeyAuthManager.onPrepareManualRecovery = null
        LocalComposerDrafts.onPersist = null

        UserApiKeyAuthManager.logout()
        CookieSentinel.clearAll()
        LocalComposerDrafts.clearAll()
        LinuxDoRepository.clearUserSessionCaches()
        LinuxDoRepository.clearAllLocalCaches()
        LinuxDoRepository.clearAvatarAndActorCachesForTest()
        ScreenTrackEngine.resetForTest()
        UserPresenceService.setAppForeground(true)

        UserApiKeyAuthManager.requireBrowserTransportOnly = savedRequireBrowserOnly
        UserApiKeyAuthManager.webViewHttpExecutor = savedHttpExecutor
        PlatformMediaPicker.webViewUploadExecutor = savedUploadExecutor
        PlatformMediaPicker.webViewBinaryUploadExecutor = savedBinaryUploadExecutor
        UserApiKeyAuthManager.customUserAgent = savedCustomUa
        UserApiKeyAuthManager.openExternalUrlLauncher = savedOpenUrlLauncher
        UserApiKeyAuthManager.onPersistPendingKey = savedPersistPendingKey
        UserApiKeyAuthManager.onSyncCookieToPlatform = savedSyncCookie
        UserApiKeyAuthManager.onAuthStateChanged = savedAuthCallback
        UserApiKeyAuthManager.onPersistSession = savedPersistSession
        UserApiKeyAuthManager.onLogoutPlatformClear = savedLogoutClear
        onAuthenticationInvalidated = savedAuthInvalidated
        UserApiKeyAuthManager.onPrepareManualRecovery = savedPrepareRecovery
        LocalComposerDrafts.onPersist = savedDraftPersist
    }

    private suspend fun withMockHttp(
        handler: suspend (url: String, method: String, form: Map<String, String>?, headers: Map<String, String>) -> Pair<Int, String>?,
        block: suspend () -> Unit
    ) {
        val prev = UserApiKeyAuthManager.webViewHttpExecutor
        UserApiKeyAuthManager.webViewHttpExecutor = handler
        try {
            block()
        } finally {
            UserApiKeyAuthManager.webViewHttpExecutor = prev
        }
    }

    private fun authenticateTestUser(
        username: String = "lucas",
        displayName: String = "Lucas",
        userId: Int = 42,
        trustLevel: Int = 3,
        cookieToken: String = "valid_session_t"
    ) {
        CookieSentinel.ingestRawCookieString("_t=$cookieToken; cf_clearance=cf_ok_init", domain = "linux.do")
        UserApiKeyAuthManager.recordWebSessionLogin(
            username = username,
            displayName = displayName,
            avatarUrl = "https://linux.do/user_avatar/linux.do/$username/144/1.png",
            trustLevel = trustLevel,
            userId = userId
        )
        LinuxDoRepository.updateCsrfToken("csrf_audit_token")
    }

    // -------------------------------------------------------------------------
    // 1. Desktop Auth & one_time_password Non-Poisoning + Self-Heal
    // -------------------------------------------------------------------------
    @Test
    fun desktopOneClickAuthOtpNonPoisoningSilentRedeemAndLegacyKeySelfHeal() = runTest {
        var openedBrowserUrl: String? = null
        UserApiKeyAuthManager.openExternalUrlLauncher = { openedBrowserUrl = it }

        val authUrl = UserApiKeyAuthManager.startBrowserOneClickAuth()
        assertEquals(authUrl, openedBrowserUrl)
        assertTrue(authUrl.startsWith("https://linux.do/user-api-key/new?"))
        assertTrue(authUrl.contains("scopes=one_time_password"))
        assertTrue(authUrl.contains("otp=true"))

        val queryParams = authUrl.substringAfter('?').split('&').associate { pair ->
            pair.substringBefore('=') to URLDecoder.decode(pair.substringAfter('='), "UTF-8")
        }
        val nonce = queryParams.getValue("nonce")
        val publicPem = queryParams.getValue("public_key")
        val publicDerB64 = publicPem.lineSequence()
            .filterNot { it.startsWith("-----") }
            .joinToString("")
        val publicKey = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(Base64.getMimeDecoder().decode(publicDerB64)))

        fun encryptRsaBase64(plainText: String): String {
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.ENCRYPT_MODE, publicKey)
            return Base64.getEncoder().encodeToString(cipher.doFinal(plainText.toByteArray(Charsets.UTF_8)))
        }

        val payloadJson = buildJsonObject {
            put("key", "otp_only_key")
            put("nonce", nonce)
        }.toString()
        val expectedOtp = "desktop_otp_secret_987654"
        val redirectUri = "discourse://auth_redirect" +
            "?payload=${URLEncoder.encode(encryptRsaBase64(payloadJson), "UTF-8")}" +
            "&oneTimePassword=${URLEncoder.encode(encryptRsaBase64(expectedOtp), "UTF-8")}"

        assertTrue(UserApiKeyAuthManager.handleAuthRedirectUri(redirectUri))
        // one_time_password 授权回跳严禁把无 API 路由权限的 key 写入 activeUserApiKey 污染后续请求头
        assertNull(UserApiKeyAuthManager.getApiKey())
        assertEquals(expectedOtp, UserApiKeyAuthManager.consumePendingOtpToken())
        assertNull(UserApiKeyAuthManager.consumePendingOtpToken())
        // 防重放：同一深链不可二次消费
        assertFalse(UserApiKeyAuthManager.handleAuthRedirectUri(redirectUri))

        // 静默兑换 OTP -> 建立真实 _t 会话并水合 currentUser 与完整资料
        withMockHttp(
            handler = { url, method, _, _ ->
                when {
                    url == "https://linux.do/session/otp/$expectedOtp" && method == "POST" -> {
                        CookieSentinel.ingestSetCookieHeader(
                            "_t=redeemed_desktop_session_tok; path=/; HttpOnly; Secure",
                            domain = "linux.do"
                        )
                        200 to """{"success":"OK"}"""
                    }
                    url.contains("/session/current.json") ->
                        200 to """{"current_user":{"id":1024,"username":"desktop_dev","name":"Desktop Dev","trust_level":3,"avatar_template":"/user_avatar/linux.do/desktop_dev/{size}/1.png"}}"""
                    url.contains("/u/desktop_dev.json") ->
                        200 to """{"user":{"id":1024,"username":"desktop_dev","name":"Desktop Dev","trust_level":3,"badge_count":16,"title":"Linuxer","avatar_template":"/user_avatar/linux.do/desktop_dev/{size}/1.png"}}"""
                    url.contains("/u/desktop_dev/summary.json") ->
                        200 to """{"user_summary":{"days_visited":64,"posts_read_count":4200,"topics_entered":260,"likes_given":95,"likes_received":82,"topic_count":9,"post_count":120,"time_read":48000}}"""
                    else -> 404 to """{"errors":["not found"]}"""
                }
            }
        ) {
            assertTrue(LinuxDoRepository.redeemOtpSilently(expectedOtp))
            assertEquals("redeemed_desktop_session_tok", CookieSentinel.resolveWinningCookies()["_t"]?.value)
            assertNull(UserApiKeyAuthManager.getApiKey())
            assertTrue(UserApiKeyAuthManager.isAuthorized())
            assertEquals("desktop_dev", UserApiKeyAuthManager.currentUser?.username)
            assertEquals(1024, UserApiKeyAuthManager.currentUser?.userId)
            assertEquals(3, UserApiKeyAuthManager.currentUser?.trustLevel)
            assertEquals(64, UserApiKeyAuthManager.fullProfileCache?.daysVisited)
        }

        // 旧版遗留 session.properties 中毒自愈测试：仅有 legacy_otp_key 而无 _t Cookie 时遭遇 403 invalid_access 自动清空自愈
        UserApiKeyAuthManager.logout()
        CookieSentinel.clearAll()
        UserApiKeyAuthManager.setApiKey("legacy_otp_key")
        assertTrue(UserApiKeyAuthManager.isAuthorized())
        assertEquals("legacy_otp_key", UserApiKeyAuthManager.getApiKey())

        var observedPoisonedApiKeyHeader: String? = null
        withMockHttp(
            handler = { url, _, _, headers ->
                if (url.contains("/session/current.json")) {
                    observedPoisonedApiKeyHeader = headers["User-Api-Key"]
                    403 to """{"errors":["You are not permitted to view the requested resource."],"error_type":"invalid_access"}"""
                } else {
                    404 to "{}"
                }
            }
        ) {
            val profile = LinuxDoRepository.fetchCurrentUserProfile()
            assertNull(profile)
            assertEquals("legacy_otp_key", observedPoisonedApiKeyHeader)
            assertFalse(UserApiKeyAuthManager.isAuthorized())
            assertNull(UserApiKeyAuthManager.getApiKey())
        }
    }

    // -------------------------------------------------------------------------
    // 2. Manual Credential & Cloudflare Clearance Importers
    // -------------------------------------------------------------------------
    @Test
    fun manualCredentialAndCloudflareClearanceImportersWithRollbackSafety() = runTest {
        fun mockSessionHandlerFor(expectedT: String, username: String, userId: Int): suspend (String, String, Map<String, String>?, Map<String, String>) -> Pair<Int, String>? =
            { url, _, _, _ ->
                val currentT = CookieSentinel.resolveWinningCookies()["_t"]?.value
                when {
                    url.contains("/session/current.json") -> {
                        if (currentT == expectedT) {
                            200 to """{"current_user":{"id":$userId,"username":"$username","name":"$username","trust_level":2,"avatar_template":"/user_avatar/linux.do/$username/{size}/1.png"}}"""
                        } else {
                            401 to """{"errors":["Not logged in"],"error_type":"not_logged_in"}"""
                        }
                    }
                    url.contains("/u/$username.json") ->
                        200 to """{"user":{"id":$userId,"username":"$username","name":"$username","trust_level":2,"badge_count":5}}"""
                    url.contains("/u/$username/summary.json") ->
                        200 to """{"user_summary":{"days_visited":20,"posts_read_count":500}}"""
                    else -> 404 to "{}"
                }
            }

        // 2.1 Bash Copy as cURL
        val bashCurl = "curl 'https://linux.do/latest.json' -H 'user-agent: Mozilla/5.0 TestChrome/131' -H 'cookie: _t=curl_session_tok; cf_clearance=cf_ok_123'"
        val parsedBash = parsePastedAuthOrCurlInput(bashCurl)
        assertEquals("Mozilla/5.0 TestChrome/131", parsedBash.userAgent)
        assertEquals("_t=curl_session_tok; cf_clearance=cf_ok_123", parsedBash.cookieHeader)

        withMockHttp(mockSessionHandlerFor("curl_session_tok", "bash_user", 201)) {
            val res = LinuxDoRepository.importManualAuthInput(bashCurl)
            assertTrue(res.isSuccess)
            assertEquals("bash_user", res.getOrNull()?.username)
            assertEquals("Mozilla/5.0 TestChrome/131", UserApiKeyAuthManager.customUserAgent)
            assertEquals("curl_session_tok", CookieSentinel.resolveWinningCookies()["_t"]?.value)
            assertEquals("cf_ok_123", CookieSentinel.resolveWinningCookies()["cf_clearance"]?.value)
            assertEquals("bash_user", UserApiKeyAuthManager.currentUser?.username)
        }

        // 2.2 Windows CMD ^" escaped cURL
        val winCmdCurl = """curl "https://linux.do/" -H ^"cookie: _t=win_tok; cf_clearance=win_cf^""""
        val parsedWin = parsePastedAuthOrCurlInput(winCmdCurl)
        assertEquals("_t=win_tok; cf_clearance=win_cf", parsedWin.cookieHeader)

        withMockHttp(mockSessionHandlerFor("win_tok", "win_user", 202)) {
            val res = LinuxDoRepository.importManualAuthInput(winCmdCurl)
            assertTrue(res.isSuccess)
            assertEquals("win_user", UserApiKeyAuthManager.currentUser?.username)
            assertEquals("win_tok", CookieSentinel.resolveWinningCookies()["_t"]?.value)
            assertEquals("win_cf", CookieSentinel.resolveWinningCookies()["cf_clearance"]?.value)
        }

        // 2.3 Raw Cookie header + bare _t token
        val rawHeader = "Cookie: _t=raw_tok; _forum_session=sess_1"
        val parsedRaw = parsePastedAuthOrCurlInput(rawHeader)
        assertEquals("_t=raw_tok; _forum_session=sess_1", parsedRaw.cookieHeader)

        withMockHttp(mockSessionHandlerFor("raw_tok", "raw_user", 203)) {
            val res = LinuxDoRepository.importManualAuthInput(rawHeader)
            assertTrue(res.isSuccess)
            assertEquals("raw_user", UserApiKeyAuthManager.currentUser?.username)
            assertEquals("raw_tok", CookieSentinel.resolveWinningCookies()["_t"]?.value)
            assertEquals("sess_1", CookieSentinel.resolveWinningCookies()["_forum_session"]?.value)
        }

        val bareToken = "bare_valid_session_token_123456"
        val parsedBare = parsePastedAuthOrCurlInput(bareToken)
        assertEquals(bareToken, parsedBare.bareToken)
        assertEquals("_t=$bareToken", parsedBare.cookieHeader)

        UserApiKeyAuthManager.customUserAgent = "Mozilla/5.0 StableUA/131"
        CookieSentinel.ingestRawCookieString("cf_clearance=stable_cf_keep", domain = "linux.do")
        withMockHttp(mockSessionHandlerFor(bareToken, "bare_user", 204)) {
            val res = LinuxDoRepository.importManualAuthInput(bareToken)
            assertTrue(res.isSuccess)
            assertEquals("bare_user", UserApiKeyAuthManager.currentUser?.username)
            assertEquals(bareToken, CookieSentinel.resolveWinningCookies()["_t"]?.value)
            assertNotNull(UserApiKeyAuthManager.fullProfileCache)
        }

        // 2.4 Rollback safety on expired/invalid _t (401 AND 200 {"current_user":null})
        withMockHttp(
            handler = { url, _, _, _ ->
                if (url.contains("/session/current.json")) {
                    401 to """{"errors":["Not logged in"],"error_type":"not_logged_in"}"""
                } else {
                    404 to "{}"
                }
            }
        ) {
            val fail401 = LinuxDoRepository.importManualAuthInput(
                "curl 'https://linux.do/' -H 'user-agent: BrokenUA/0.1' -H 'cookie: _t=expired_401_tok; cf_clearance=bad_cf'"
            )
            assertTrue(fail401.isFailure)
        }
        assertEquals("Mozilla/5.0 StableUA/131", UserApiKeyAuthManager.customUserAgent)
        assertEquals(bareToken, CookieSentinel.resolveWinningCookies()["_t"]?.value)
        assertEquals("stable_cf_keep", CookieSentinel.resolveWinningCookies()["cf_clearance"]?.value)
        assertEquals("bare_user", UserApiKeyAuthManager.currentUser?.username)
        assertNotNull(UserApiKeyAuthManager.fullProfileCache)

        withMockHttp(
            handler = { url, _, _, _ ->
                if (url.contains("/session/current.json")) {
                    200 to """{"current_user":null}"""
                } else {
                    404 to "{}"
                }
            }
        ) {
            val failNullUser = LinuxDoRepository.importManualAuthInput("expired_null_current_user_tok_999")
            assertTrue(failNullUser.isFailure)
        }
        assertEquals("Mozilla/5.0 StableUA/131", UserApiKeyAuthManager.customUserAgent)
        assertEquals(bareToken, CookieSentinel.resolveWinningCookies()["_t"]?.value)
        assertEquals("stable_cf_keep", CookieSentinel.resolveWinningCookies()["cf_clearance"]?.value)
        assertEquals("bare_user", UserApiKeyAuthManager.currentUser?.username)

        // 2.5 importCloudflareClearanceInput clears challenge cooldown & transient rate limit
        withMockHttp(
            handler = { url, _, _, _ ->
                if (url.contains("rate_hit")) {
                    429 to """{"errors":["Too Many Requests"]}"""
                } else {
                    503 to "<!DOCTYPE html><html><head><title>Just a moment...</title></head><body>_cf_chl_opt</body></html>"
                }
            }
        ) {
            runCatching { LinuxDoRepository.fetchTopics(path = "rate_hit.json", background = true) }
            assertTrue(LinuxDoRepository.isRateLimitedNow())
            runCatching { LinuxDoRepository.fetchTopics(path = "cf_hit.json", background = true) }
            assertTrue(LinuxDoRepository.isChallengeCooldownActive())
        }

        val importedCf = LinuxDoRepository.importCloudflareClearanceInput(
            "curl 'https://linux.do/' -H 'user-agent: Mozilla/5.0 VerifiedChrome/132' -H 'cookie: cf_clearance=fresh_clearance_888'"
        )
        assertTrue(importedCf)
        assertFalse(LinuxDoRepository.isChallengeCooldownActive())
        assertFalse(LinuxDoRepository.isRateLimitedNow())
        assertEquals("fresh_clearance_888", CookieSentinel.resolveWinningCookies()["cf_clearance"]?.value)
        assertEquals("Mozilla/5.0 VerifiedChrome/132", UserApiKeyAuthManager.customUserAgent)
    }

    // -------------------------------------------------------------------------
    // 3. Feed, Categories, Search, Discover, Leaderboard & About
    // -------------------------------------------------------------------------
    @Test
    fun feedCategoriesSearchDiscoverLeaderboardAndAboutContracts() = runTest {
        authenticateTestUser(username = "lucas", userId = 42)
        val requestedUrls = CopyOnWriteArrayList<String>()

        fun buildTopicListJson(ids: List<Int>, pinnedFirst: Boolean = false): String = buildJsonObject {
            put(
                "users",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("id", 11)
                            put("username", "author_11")
                            put("name", "Author 11")
                            put("avatar_template", "/user_avatar/linux.do/author_11/{size}/1.png")
                        }
                    )
                }
            )
            put(
                "topic_list",
                buildJsonObject {
                    put(
                        "topics",
                        buildJsonArray {
                            ids.forEachIndexed { index, id ->
                                add(
                                    buildJsonObject {
                                        put("id", id)
                                        put("title", "话题标题 #$id")
                                        put("excerpt", "话题摘要 #$id")
                                        put("posts_count", 6)
                                        put("views", 120)
                                        put("like_count", 15)
                                        put("category_id", 4)
                                        put("pinned", pinnedFirst && index == 0)
                                        put("tags", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("kotlin")) })
                                        put(
                                            "posters",
                                            buildJsonArray {
                                                add(
                                                    buildJsonObject {
                                                        put("user_id", 11)
                                                        put("description", "Original Poster")
                                                    }
                                                )
                                            }
                                        )
                                    }
                                )
                            }
                        }
                    )
                }
            )
        }.toString()

        val followPage0 = buildJsonObject {
            put(
                "posts",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("id", 8101)
                            put("topic_id", 3101)
                            put("post_number", 1)
                            put("category_id", 4)
                            put("created_at", "2026-11-12T06:00:00.000Z")
                            put("excerpt", "Kotlin Multiplatform 实战分享")
                            put("user", buildJsonObject { put("id", 21); put("username", "followed_a") })
                            put(
                                "topic",
                                buildJsonObject {
                                    put("id", 3101)
                                    put("title", "KMP 关注动态")
                                    put("category_id", 4)
                                    put("tags", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("kotlin")) })
                                }
                            )
                        }
                    )
                    add(
                        buildJsonObject {
                            put("id", 8102)
                            put("topic_id", 3102)
                            put("post_number", 2)
                            put("category_id", 11)
                            put("created_at", "2026-11-12T05:30:00.000Z")
                            put("excerpt", "其他板块闲聊")
                            put("user", buildJsonObject { put("id", 22); put("username", "followed_b") })
                            put(
                                "topic",
                                buildJsonObject {
                                    put("id", 3102)
                                    put("title", "闲聊动态")
                                    put("category_id", 11)
                                    put("tags", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("gossip")) })
                                }
                            )
                        }
                    )
                }
            )
            put("extras", buildJsonObject { put("has_more", true) })
        }.toString()

        val followPage1 = buildJsonObject {
            put(
                "posts",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("id", 8103)
                            put("topic_id", 3103)
                            put("post_number", 1)
                            put("category_id", 4)
                            put("created_at", "2026-11-12T04:00:00.000Z")
                            put("excerpt", "第二页关注内容")
                            put("user", buildJsonObject { put("id", 23); put("username", "followed_c") })
                            put("topic", buildJsonObject { put("id", 3103); put("title", "关注第二页"); put("category_id", 4) })
                        }
                    )
                }
            )
            put("extras", buildJsonObject { put("has_more", false) })
        }.toString()

        withMockHttp(
            handler = { url, _, _, _ ->
                requestedUrls += url
                when {
                    url == "https://linux.do/site.json" ->
                        200 to """{"can_create_tag":true,"categories":[{"id":4,"name":"开发调优","slug":"develop","color":"32C3C3","topic_template":"请填写复现步骤"}]}"""
                    url == "https://linux.do/latest.json?no_definitions=true" ->
                        200 to buildTopicListJson(listOf(1001, 1002))
                    url == "https://linux.do/top.json?no_definitions=true&period=weekly" ->
                        200 to buildTopicListJson(listOf(2002, 2003, 2004, 2005))
                    url == "https://linux.do/top.json?no_definitions=true&period=daily" ->
                        200 to buildTopicListJson(listOf(2000, 2001, 2002), pinnedFirst = true)
                    url == "https://linux.do/new.json?no_definitions=true" ->
                        200 to buildTopicListJson(listOf(1101))
                    url == "https://linux.do/unread.json?no_definitions=true" ->
                        200 to buildTopicListJson(listOf(1201))
                    url == "https://linux.do/c/develop/4/l/top.json?no_definitions=true&period=weekly" ->
                        200 to buildTopicListJson(listOf(1301))
                    url == "https://linux.do/tag/kotlin.json?no_definitions=true" ->
                        200 to buildTopicListJson(listOf(1401))
                    url == "https://linux.do/follow/posts/lucas.json?limit=30" ->
                        200 to followPage0
                    url == "https://linux.do/follow/posts/lucas.json?limit=30&created_before=2026-11-12T05%3A30%3A00.000Z" ->
                        200 to followPage1
                    url == "https://linux.do/search.json?q=kotlin&page=1" ->
                        200 to """
                            {
                              "posts":[{"id":901,"topic_id":4001,"post_number":2,"blurb":"<b>Kotlin</b> 协程与 Compose 渲染优化","username":"jet","avatar_template":"/user_avatar/linux.do/jet/{size}/1.png","like_count":19,"created_at":"2026-11-12T06:00:00.000Z"}],
                              "topics":[
                                {"id":4001,"title":"Kotlin 跨平台实践","category_id":4,"posts_count":18},
                                {"id":4002,"title":"仅标题命中的 Kotlin 指南","category_id":42,"posts_count":5,"tags":["kotlin"]}
                              ],
                              "grouped_search_result":{"more_full_page_results":true}
                            }
                        """.trimIndent()
                    url == "https://linux.do/directory_items.json?period=weekly&order=likes_received&page=0" ->
                        200 to """
                            {
                              "directory_items":[
                                {
                                  "id":1,
                                  "likes_received":88,
                                  "likes_given":42,
                                  "post_count":65,
                                  "topic_count":6,
                                  "days_visited":7,
                                  "cheers":620,
                                  "user":{"id":9,"username":"top_coder","name":"Top Coder","title":"MVP","avatar_template":"/user_avatar/linux.do/top_coder/{size}/1.png"}
                                }
                              ]
                            }
                        """.trimIndent()
                    url == "https://linux.do/about.json" ->
                        200 to """
                            {
                              "users":[
                                {"id":1,"username":"neo","name":"Neo","title":"站长","trust_level":4,"avatar_template":"/user_avatar/linux.do/neo/{size}/1.png"},
                                {"id":2,"username":"mod_one","name":"Mod One","title":"版主","trust_level":4,"avatar_template":"/user_avatar/linux.do/mod_one/{size}/1.png"}
                              ],
                              "about":{
                                "title":"LINUX DO",
                                "description":"真诚、友善、团结、专业 &amp; 开源共建",
                                "admin_ids":[1],
                                "moderator_ids":[2],
                                "stats":{
                                  "topics_count":180000,
                                  "posts_count":4200000,
                                  "users_count":260000,
                                  "active_users_7_days":65000,
                                  "active_users_30_days":140000,
                                  "likes_count":9800000
                                }
                              }
                            }
                        """.trimIndent()
                    else -> 404 to """{"errors":["unhandled: $url"]}"""
                }
            }
        ) {
            val syncedCats = LinuxDoRepository.syncSiteCategories(force = true)
            assertTrue(syncedCats.any { it.id == 4 && it.slug == "develop" })
            assertTrue(LinuxDoRepository.canCreateTag)
            assertEquals("请填写复现步骤", LinuxDoRepository.fetchCategoryTopicTemplate(4))

            assertEquals(listOf(1001, 1002), LinuxDoRepository.fetchTopics("latest.json").map { it.id })
            assertEquals(listOf(2002, 2003, 2004, 2005), LinuxDoRepository.fetchTopics("top.json", topPeriod = "weekly").map { it.id })
            assertEquals(listOf(1101), LinuxDoRepository.fetchTopics("new.json").map { it.id })
            assertEquals(listOf(1201), LinuxDoRepository.fetchTopics("unread.json").map { it.id })
            assertEquals(listOf(1301), LinuxDoRepository.fetchTopics("top", categoryId = 4, topPeriod = "weekly").map { it.id })
            assertEquals(listOf(1401), LinuxDoRepository.fetchTopics("latest.json", tag = "kotlin").map { it.id })

            // Follow cursor pagination + category/tag filtering
            val filteredFollow = LinuxDoRepository.fetchTopics("follow", categoryId = 4, page = 0, tag = "kotlin")
            assertEquals(listOf(3101), filteredFollow.map { it.id })
            assertTrue(LinuxDoRepository.computeHasMoreTopics("follow", currentPage = 0, fetchedCount = filteredFollow.size))

            val nextFollow = LinuxDoRepository.fetchTopics("follow", page = 1)
            assertEquals(listOf(3103), nextFollow.map { it.id })
            assertFalse(LinuxDoRepository.computeHasMoreTopics("follow", currentPage = 1, fetchedCount = nextFollow.size))

            // Search
            val searchHits = LinuxDoRepository.searchCommunity("kotlin", page = 1)
            assertEquals(2, searchHits.size)
            assertEquals(4001, searchHits[0].topicId)
            assertEquals("Kotlin 协程与 Compose 渲染优化", searchHits[0].blurb)
            assertEquals(4002, searchHits[1].topicId)

            // Discover hot topics: daily excludes pinned 2000 -> [2001, 2002], merges weekly [2002..2005] deduplicating 2002 -> 5 items
            val discoverHot = LinuxDoRepository.fetchDiscoverHotTopics(limit = 5)
            assertEquals(listOf(2001, 2002, 2003, 2004, 2005), discoverHot.map { it.id })

            // Leaderboard
            val leaderboard = LinuxDoRepository.fetchCommunityLeaderboard("weekly", page = 0)
            assertEquals(1, leaderboard.size)
            assertEquals(1, leaderboard[0].rank)
            assertEquals("top_coder", leaderboard[0].username)
            assertEquals(620, leaderboard[0].score)

            // Community About
            val about = LinuxDoRepository.fetchCommunityAboutInfo()
            assertNotNull(about)
            assertEquals("LINUX DO", about.title)
            assertEquals("真诚、友善、团结、专业 & 开源共建", about.description)
            assertEquals(180000, about.topicCount)
            assertEquals("neo", about.admins.single().username)
            assertEquals("mod_one", about.moderators.single().username)
        }
    }

    // -------------------------------------------------------------------------
    // 4. Topic Detail, Paginated Chunk Read-State, small_action & AI Summary
    // -------------------------------------------------------------------------
    @Test
    fun topicDetailChunkReadStateSmallActionSynthesisAndAiSummary() = runTest {
        authenticateTestUser(username = "lucas", userId = 42)
        val topicId = 7700
        var trackHeadersObserved: Map<String, String> = emptyMap()

        withMockHttp(
            handler = { url, _, _, headers ->
                when {
                    url == "https://linux.do/t/$topicId.json?track_visit=true" -> {
                        trackHeadersObserved = headers
                        200 to """
                            {
                              "id": $topicId,
                              "title": "Lindo 分页阅读状态与管理事件回归",
                              "category_id": 4,
                              "posts_count": 20,
                              "highest_post_number": 19,
                              "last_read_post_number": 3,
                              "post_stream": {
                                "stream": [101, 201, 202, 203],
                                "posts": [
                                  {
                                    "id": 101,
                                    "post_number": 1,
                                    "user_id": 500,
                                    "username": "op_author",
                                    "read": true,
                                    "cooked": "<p>主帖一楼正文</p>"
                                  }
                                ]
                              }
                            }
                        """.trimIndent()
                    }
                    url == "https://linux.do/t/$topicId/posts.json?post_ids[]=201&post_ids[]=202&post_ids[]=203" ->
                        200 to """
                            {
                              "post_stream": {
                                "posts": [
                                  {
                                    "id": 201,
                                    "post_number": 5,
                                    "user_id": 501,
                                    "username": "reader_five",
                                    "read": true,
                                    "cooked": "<p>五楼历史已读内容</p>"
                                  },
                                  {
                                    "id": 202,
                                    "post_number": 18,
                                    "user_id": 502,
                                    "username": "reader_eighteen",
                                    "read": false,
                                    "cooked": "<p>十八楼新增未读内容</p>"
                                  },
                                  {
                                    "id": 203,
                                    "post_number": 19,
                                    "user_id": 503,
                                    "username": "mod_keeper",
                                    "post_type": 3,
                                    "action_code": "closed.enabled",
                                    "cooked": ""
                                  }
                                ]
                              }
                            }
                        """.trimIndent()
                    url == "https://linux.do/discourse-ai/summarization/t/$topicId.json" ->
                        200 to """{"ai_topic_summary":{"summarized_text":"  AI 摘要：修复了分批加载楼层已读游标与版主操作楼层渲染。  "}}"""
                    else -> 404 to """{"errors":["not found: $url"]}"""
                }
            }
        ) {
            val detail = LinuxDoRepository.fetchTopicDetail(topicId)
            assertEquals(topicId, detail.id)
            assertEquals("1", trackHeadersObserved["Discourse-Track-View"])
            assertEquals(topicId.toString(), trackHeadersObserved["Discourse-Track-View-Topic-Id"])
            assertEquals(3, LinuxDoRepository.getServerConfirmedReadFloor(topicId))

            // 推进已读进度至 15 楼后拉取分页 chunk [201(5楼), 202(18楼), 203(19楼 small_action)]
            LinuxDoRepository.markTopicRead(topicId, 15)
            assertEquals(15, LinuxDoRepository.getServerConfirmedReadFloor(topicId))

            val morePosts = LinuxDoRepository.fetchMoreTopicPosts(topicId, listOf(201, 202, 203))
            assertEquals(3, morePosts.size)

            val floor5 = morePosts.first { it.postNumber == 5 }
            val floor18 = morePosts.first { it.postNumber == 18 }
            val floor19 = morePosts.first { it.postNumber == 19 }

            assertTrue(floor5.isRead, "5 楼 (<= 15 且服务端 read=true) 必须保持 isRead=true，杜绝 ScreenTrack 1 秒 rush 死循环")
            assertFalse(floor18.isRead, "18 楼 (> 15) 必须为未读 isRead=false")
            assertTrue(floor19.cookedHtml.contains("已关闭此话题"), "small_action 空 cooked 楼层必须合成中文管理提示")
            assertTrue(DiscourseAstParser.stripHtmlTags(floor19.cookedHtml).isNotBlank())

            val aiSummary = LinuxDoRepository.fetchTopicAiSummary(topicId)
            assertEquals("AI 摘要：修复了分批加载楼层已读游标与版主操作楼层渲染。", aiSummary)
        }
    }

    // -------------------------------------------------------------------------
    // 5. Every Post & Topic Mutation Contract
    // -------------------------------------------------------------------------
    @Test
    fun postAndTopicMutationsPollsReactionsBoostsAndSanitizedUploads() = runTest {
        authenticateTestUser(username = "lucas", userId = 42)

        var deleteShouldFail422 = false
        var lastDeletePollUrl: String? = null
        var lastFlagParams: Map<String, String>? = null
        var lastUpdateTopicParams: Map<String, String>? = null

        withMockHttp(
            handler = { url, method, form, _ ->
                when {
                    url == "https://linux.do/posts.json" && method == "POST" -> {
                        if (form?.containsKey("category") == true) {
                            assertEquals("新话题标题", form["title"])
                            assertEquals("4", form["category"])
                            assertEquals("kotlin", form["tags[0]"])
                            200 to """{"id":601,"topic_id":8800,"post_number":1,"cooked":"<p>topic body</p>"}"""
                        } else {
                            assertEquals("8800", form?.get("topic_id"))
                            assertEquals("3", form?.get("reply_to_post_number"))
                            assertTrue(form?.get("raw").orEmpty().contains("[quote=\"alice, post:3, topic:8800\"]"))
                            200 to """{"post":{"id":602,"topic_id":8800,"post_number":4,"username":"lucas","cooked":"<p>reply body</p>"}}"""
                        }
                    }
                    url == "https://linux.do/posts/602.json" && method == "PUT" -> {
                        assertEquals("更新后的正文", form?.get("post[raw]"))
                        assertEquals("修正错别字", form?.get("post[edit_reason]"))
                        200 to """{"post":{"id":602,"topic_id":8800,"post_number":4,"username":"lucas","raw":"更新后的正文","cooked":"<p>更新后的正文</p>"}}"""
                    }
                    url == "https://linux.do/t/-/8800.json" && method == "PUT" -> {
                        lastUpdateTopicParams = form
                        200 to """{"basic_topic":{"id":8800,"title":"修订后话题标题"}}"""
                    }
                    url == "https://linux.do/posts/602.json" && method == "DELETE" -> {
                        if (deleteShouldFail422) {
                            422 to """{"errors":["帖子太旧无法删除"]}"""
                        } else {
                            200 to """{"success":"OK"}"""
                        }
                    }
                    url == "https://linux.do/discourse-reactions/posts/602/custom-reactions/heart/toggle.json" && method == "PUT" ->
                        200 to """{"id":602,"reactions":[{"id":"heart","type":"emoji","count":3}],"current_user_reaction":{"id":"heart","type":"emoji"}}"""
                    url == "https://linux.do/discourse-reactions/posts/602/custom-reactions/tieba_087/toggle.json" && method == "PUT" ->
                        200 to """{"id":602,"reactions":[{"id":"tieba_087","type":"emoji","count":2}],"current_user_reaction":{"id":"tieba_087","type":"emoji"}}"""
                    url == "https://linux.do/discourse-boosts/posts/602/boosts.json" && method == "POST" ->
                        200 to """{"boost":{"id":9901,"post_id":602,"cooked":"<p>🚀 前排支持</p>","can_delete":true,"user":{"id":42,"username":"lucas"}}}"""
                    url == "https://linux.do/discourse-boosts/boosts/9901.json" && method == "DELETE" ->
                        200 to """{"success":"OK"}"""
                    url == "https://linux.do/t/8800/bookmark.json" && method == "PUT" ->
                        200 to """{"success":"OK"}"""
                    url == "https://linux.do/t/8800/remove_bookmarks.json" && method == "PUT" ->
                        200 to """{"success":"OK"}"""
                    url == "https://linux.do/bookmarks/771.json" && method == "DELETE" ->
                        200 to """{"success":"OK"}"""
                    url == "https://linux.do/t/8800/notifications.json" && method == "POST" -> {
                        assertEquals("3", form?.get("notification_level"))
                        200 to """{"success":"OK"}"""
                    }
                    url == "https://linux.do/solution/accept.json" && method == "POST" -> {
                        assertEquals("602", form?.get("id"))
                        200 to """{"accepted_answer":{"post_number":4,"username":"lucas"}}"""
                    }
                    url == "https://linux.do/post_actions.json" && method == "POST" -> {
                        lastFlagParams = form
                        200 to """{"id":602}"""
                    }
                    url == "https://linux.do/polls/vote.json" && method == "PUT" -> {
                        assertEquals("602", form?.get("post_id"))
                        assertEquals("release_poll", form?.get("poll_name"))
                        assertEquals("opt_yes", form?.get("options[0]"))
                        200 to """{"poll":{"name":"release_poll","voters":10,"options":[{"id":"opt_yes","votes":9},{"id":"opt_no","votes":1}]},"vote":["opt_yes"]}"""
                    }
                    url.startsWith("https://linux.do/polls/vote.json") && method == "DELETE" -> {
                        lastDeletePollUrl = url
                        200 to """{"poll":{"name":"release_poll","voters":9,"options":[{"id":"opt_yes","votes":8},{"id":"opt_no","votes":1}]}}"""
                    }
                    else -> 404 to """{"errors":["unexpected: $method $url"]}"""
                }
            }
        ) {
            val createdTopic = assertIs<PostSubmission.Published<Int>>(
                LinuxDoRepository.createTopic(
                    title = "新话题标题",
                    raw = "新话题一楼正文内容",
                    categoryId = 4,
                    tags = listOf("kotlin"),
                    draftKey = "new_topic"
                )
            )
            assertEquals(8800, createdTopic.value)

            val quoteReplyRaw = postQuoteMarkup("alice", 3, 8800, "原楼层观点") + "同意这个方案！"
            val createdReply = assertIs<PostSubmission.Published<PostItemModel>>(
                LinuxDoRepository.createPost(
                    topicId = 8800,
                    raw = quoteReplyRaw,
                    replyToPostNumber = 3,
                    draftKey = "topic_8800"
                )
            )
            assertEquals(602, createdReply.value.id)
            assertEquals(4, createdReply.value.postNumber)

            val edited = LinuxDoRepository.updatePost(602, "更新后的正文", editReason = "修正错别字")
            assertNotNull(edited)
            assertEquals("更新后的正文", edited.raw)

            assertTrue(LinuxDoRepository.updateTopicMetadata(8800, "修订后话题标题", categoryId = 4, tags = listOf("kotlin", "kmp")))
            assertEquals("修订后话题标题", lastUpdateTopicParams?.get("title"))
            assertEquals("kmp", lastUpdateTopicParams?.get("tags[1]"))

            // deletePost: 200 OK & 422 exact Discourse error preservation
            assertTrue(LinuxDoRepository.deletePost(602))
            deleteShouldFail422 = true
            assertFalse(LinuxDoRepository.deletePost(602))
            assertEquals("帖子太旧无法删除", LinuxDoRepository.lastMutationErrorOr("删除失败"))

            // Reactions (heart + custom emoji)
            val heartReaction = LinuxDoRepository.togglePostReactionDetailed(602, "heart")
            assertTrue(heartReaction.ok)
            assertEquals("heart", heartReaction.myReactionId)
            val customReaction = LinuxDoRepository.togglePostReactionDetailed(602, ":tieba_087:")
            assertTrue(customReaction.ok)
            assertEquals("tieba_087", customReaction.myReactionId)
            assertEquals("tieba_087", LinuxDoRepository.getPostReactionSnapshot(602)?.myReactionId)

            // Boost create & delete
            val createdBoost = LinuxDoRepository.createPostBoost(602, "🚀 前排支持")
            assertTrue(createdBoost.ok)
            assertEquals(9901L, createdBoost.boost?.id)
            assertFalse(LinuxDoRepository.getPostBoostSnapshot(602)?.canBoost ?: true)
            val deletedBoost = LinuxDoRepository.deletePostBoost(602, 9901L)
            assertTrue(deletedBoost.ok)
            assertTrue(LinuxDoRepository.getPostBoostSnapshot(602)?.boosts.isNullOrEmpty())

            // Bookmarks, Notification Level, Solution, Flag
            assertTrue(LinuxDoRepository.setTopicBookmark(8800, bookmarked = true))
            assertTrue(LinuxDoRepository.setTopicBookmark(8800, bookmarked = false))
            assertTrue(LinuxDoRepository.deleteBookmark(771))
            assertTrue(LinuxDoRepository.setTopicNotificationLevel(8800, level = 3))
            assertTrue(LinuxDoRepository.acceptPostSolution(602))
            assertTrue(LinuxDoRepository.flagPost(602, flagTypeId = 8, message = "垃圾广告内容"))
            assertEquals("8", lastFlagParams?.get("post_action_type_id"))
            assertEquals("垃圾广告内容", lastFlagParams?.get("message"))

            // Polls: voteInPoll & removePollVote query params check
            assertTrue(LinuxDoRepository.voteInPoll(602, "release_poll", listOf("opt_yes")))
            assertEquals(setOf("opt_yes"), LinuxDoRepository.getPollVoteSnapshot(602, "release_poll")?.selectedIds)
            val removedSnap = LinuxDoRepository.removePollVote(602, "release_poll")
            assertNotNull(removedSnap)
            assertFalse(removedSnap.hasVoted)
            assertTrue(removedSnap.selectedIds.isEmpty())
            val capturedDeleteUrl = assertNotNull(lastDeletePollUrl)
            assertTrue(capturedDeleteUrl.contains("post_id=602"))
            assertTrue(capturedDeleteUrl.contains("poll_name=release_poll"))
        }

        // Media Upload: image ![name|WxH](upload://...) & attachment [doc.pdf|attachment](upload://...) with bracket/pipe sanitization
        PlatformMediaPicker.webViewBinaryUploadExecutor = { fileName, mimeType, _ ->
            if (mimeType.startsWith("image/")) {
                200 to """{"original_filename":"$fileName","short_url":"upload://imgSanitized01.png","url":"/uploads/default/original/1X/imgSanitized01.png","width":1440,"height":900,"human_filesize":"256 KB"}"""
            } else {
                200 to """{"original_filename":"$fileName","short_url":"upload://attSanitized02.pdf","url":"/uploads/default/original/1X/attSanitized02.pdf","human_filesize":"1.5 MB"}"""
            }
        }

        val imgUpload = LinuxDoRepository.uploadMediaToLinuxDo(
            fileName = "shot[v1]|retina.png",
            mimeType = "image/png",
            bytes = byteArrayOf(1, 2, 3, 4)
        )
        assertNotNull(imgUpload)
        assertTrue(imgUpload.isImage)
        assertEquals("![shot(v1)-retina.png|1440x900](upload://imgSanitized01.png)", imgUpload.markdownSnippet)

        val pdfUpload = LinuxDoRepository.uploadMediaToLinuxDo(
            fileName = "spec[draft]|v2.pdf",
            mimeType = "application/pdf",
            bytes = byteArrayOf(5, 6, 7, 8)
        )
        assertNotNull(pdfUpload)
        assertFalse(pdfUpload.isImage)
        assertEquals("[spec(draft)-v2.pdf|attachment](upload://attSanitized02.pdf) (1.5 MB)", pdfUpload.markdownSnippet)
    }

    // -------------------------------------------------------------------------
    // 6. Notifications, PMs, Profile, Badges, Bookmarks, History, Follow & Drafts
    // -------------------------------------------------------------------------
    @Test
    fun notificationsPrivateMessagesProfileBookmarksHistoryFollowAndCloudDrafts() = runTest {
        authenticateTestUser(username = "lucas", userId = 42)
        UserApiKeyAuthManager.fullProfileCache = UserFullProfileSummary(
            id = 42,
            username = "lucas",
            displayName = "Lucas",
            unreadNotifications = 5
        )

        var pmPostSimulateTimeout = false
        var pmPostCallCount = 0
        val recentIsoNow = Instant.now().toString()
        val observedDraftUrls = CopyOnWriteArrayList<String>()
        var draftDeletedOnServer = false
        var lastProfilePutForm: Map<String, String>? = null

        fun singlePmListJson(id: Int, title: String, createdAt: String = recentIsoNow): String = buildJsonObject {
            put("users", buildJsonArray { add(buildJsonObject { put("id", 42); put("username", "lucas") }) })
            put(
                "topic_list",
                buildJsonObject {
                    put(
                        "topics",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("id", id)
                                    put("title", title)
                                    put("excerpt", "摘要 #$id")
                                    put("posts_count", 1)
                                    put("created_at", createdAt)
                                    put("posters", buildJsonArray { add(buildJsonObject { put("user_id", 42); put("description", "Original Poster") }) })
                                }
                            )
                        }
                    )
                }
            )
        }.toString()

        withMockHttp(
            handler = { url, method, form, _ ->
                if (url.contains("/drafts")) {
                    observedDraftUrls += "$method $url"
                }
                when {
                    url == "https://linux.do/notifications.json?offset=0&limit=60&silent=true" ->
                        200 to """{"notifications":[{"id":701,"notification_type":1,"read":false,"created_at":"2026-11-12T06:00:00.000Z","topic_id":5001,"post_number":3,"data":{"display_username":"alice","topic_title":"提及测试话题","excerpt":"@lucas 来看看"}}]}"""
                    url == "https://linux.do/notifications/mark-read.json" && method == "PUT" ->
                        200 to """{"success":"OK"}"""
                    url == "https://linux.do/topics/private-messages/lucas.json" ->
                        200 to singlePmListJson(9101, "收件箱私信")
                    url == "https://linux.do/topics/private-messages-sent/lucas.json" ->
                        200 to singlePmListJson(9102, "超时后已落库私信", createdAt = recentIsoNow)
                    url == "https://linux.do/topics/private-messages-archive/lucas.json" ->
                        200 to singlePmListJson(9103, "已归档私信")
                    url == "https://linux.do/posts.json" && method == "POST" && form?.get("archetype") == "private_message" -> {
                        pmPostCallCount++
                        if (pmPostSimulateTimeout) {
                            null
                        } else {
                            assertEquals("alice,bob", form["target_recipients"])
                            200 to """{"topic_id":9100,"id":99001}"""
                        }
                    }
                    url == "https://linux.do/t/9101/archive-message.json" && method == "PUT" ->
                        200 to """{"success":"OK"}"""
                    url == "https://linux.do/t/9101/move-to-inbox.json" && method == "PUT" ->
                        200 to """{"success":"OK"}"""
                    url == "https://linux.do/u/lucas/bookmarks.json" ->
                        200 to """{"user_bookmark_list":{"bookmarks":[{"id":301,"topic_id":6001,"linked_post_number":2,"title":"架构收藏帖","excerpt":"精华内容","category_id":4,"created_at":"2026-11-12T06:00:00.000Z"}]}}"""
                    url == "https://linux.do/user_actions.json?offset=0&username=lucas" ->
                        200 to """{"user_actions":[{"topic_id":6002,"post_id":7002,"post_number":1,"action_type":4,"title":"我的发帖动态","excerpt":"动态摘要","category_id":4,"created_at":"2026-11-12T06:00:00.000Z"}]}"""
                    url == "https://linux.do/read.json?no_definitions=true" ->
                        200 to singlePmListJson(6003, "最近阅读历史话题")
                    url == "https://linux.do/follow/alice.json" && (method == "PUT" || method == "DELETE") ->
                        200 to """{"success":"OK"}"""
                    url == "https://linux.do/u/lucas/follow/following.json" ->
                        200 to """{"users":[{"id":88,"username":"alice","name":"Alice","trust_level":3,"avatar_template":"/user_avatar/linux.do/alice/{size}/1.png"}]}"""
                    url == "https://linux.do/u/lucas/follow/followers.json" ->
                        200 to """{"users":[{"id":88,"username":"alice","name":"Alice","trust_level":3},{"id":89,"username":"stranger","name":"Stranger","trust_level":2}]}"""
                    url == "https://linux.do/follow/posts/lucas.json?limit=30" ->
                        200 to """{"posts":[{"id":801,"topic_id":6004,"post_number":1,"username":"alice","title":"Alice 的新帖","excerpt":"关注流内容","category_id":4,"created_at":"2026-11-12T06:00:00.000Z"}],"extras":{"has_more":false}}"""
                    url == "https://linux.do/u/lucas.json" && method == "PUT" -> {
                        lastProfilePutForm = form
                        200 to """{"user":{"id":42,"username":"lucas","title":"开源先锋"}}"""
                    }
                    url.contains("/session/current.json") ->
                        200 to """{"current_user":{"id":42,"username":"lucas","name":"Lucas Updated","trust_level":3,"avatar_template":"/user_avatar/linux.do/lucas/{size}/1.png"}}"""
                    url == "https://linux.do/u/lucas.json" && method == "GET" ->
                        200 to """{"user":{"id":42,"username":"lucas","name":"Lucas Updated","trust_level":3,"title":"开源先锋","bio_raw":"KMP Developer","badge_count":10}}"""
                    url == "https://linux.do/u/lucas/summary.json" ->
                        200 to """{"user_summary":{"days_visited":88,"topics_entered":320,"posts_read_count":5000,"likes_given":120,"likes_received":95,"topic_count":12,"post_count":150,"time_read":54000}}"""
                    url == "https://linux.do/user-badges/lucas.json?grouped=true" ->
                        200 to """{"badges":[{"id":101,"name":"开源先锋","description":"贡献开源项目","badge_type_id":2,"allow_title":true}],"user_badges":[{"id":501,"badge_id":101,"count":1,"granted_at":"2026-10-01T00:00:00.000Z","grouping_position":1}]}"""
                    url == "https://linux.do/badges.json" ->
                        200 to """{"badge_groupings":[{"id":1,"name":"社区贡献","position":0}],"badges":[{"id":101,"name":"开源先锋","description":"贡献开源项目","badge_type_id":2,"badge_grouping_id":1,"enabled":true,"listable":true}]}"""
                    url == "https://linux.do/drafts.json" && method == "POST" -> {
                        val dataObj = Json.parseToJsonElement(form?.get("data").orEmpty()).jsonObject
                        assertEquals("草稿正文内容", dataObj["reply"]?.jsonPrimitive?.content)
                        200 to """{"draft_sequence":8}"""
                    }
                    url == "https://linux.do/drafts.json" && method == "GET" ->
                        200 to """{"drafts":[{"draft_key":"topic 9900 draft","sequence":8,"data":"{\"reply\":\"草稿正文内容\",\"action\":\"reply\",\"topicId\":9900}"}]}"""
                    url == "https://linux.do/drafts/topic%209900%20draft.json" && method == "GET" -> {
                        if (draftDeletedOnServer) {
                            200 to """{"draft":null,"draft_sequence":9}"""
                        } else {
                            200 to """{"draft":"{\"reply\":\"草稿正文内容\",\"action\":\"reply\",\"topicId\":9900}","draft_sequence":8}"""
                        }
                    }
                    url == "https://linux.do/drafts/topic%209900%20draft.json?sequence=8" && method == "DELETE" -> {
                        draftDeletedOnServer = true
                        200 to """{"success":"OK"}"""
                    }
                    else -> 404 to """{"errors":["unhandled: $method $url"]}"""
                }
            }
        ) {
            // Notifications
            val notifs = LinuxDoRepository.fetchUserNotifications(offset = 0)
            assertEquals(1, notifs.size)
            assertEquals("alice", notifs.single().actorUsername)
            assertTrue(LinuxDoRepository.markSingleNotificationRead(701))
            assertTrue(LinuxDoRepository.markNotificationsRead())
            assertEquals(0, UserApiKeyAuthManager.fullProfileCache?.unreadNotifications)

            // Private Messages: inbox / sent / archived + direct send + timeout post-confirmation
            assertEquals(9101, LinuxDoRepository.fetchPrivateMessages("lucas", sent = false, archived = false).single().id)
            assertEquals(9102, LinuxDoRepository.fetchPrivateMessages("lucas", sent = true, archived = false).single().id)
            assertEquals(9103, LinuxDoRepository.fetchPrivateMessages("lucas", sent = false, archived = true).single().id)

            val directPm = LinuxDoRepository.sendPrivateMessageDetailed("@alice, @bob", "正常私信标题", "私信内容")
            assertTrue(directPm.isSuccess)
            assertEquals(PrivateMessageSendOutcome.SENT, directPm.outcome)
            assertEquals(9100, directPm.topicId)
            assertEquals(1, pmPostCallCount)

            // 模拟 POST /posts.json 网络读超时返回 null，但后端实际已落库出现在发件箱，确保零重发找回 topicId
            pmPostSimulateTimeout = true
            val recoveredPm = LinuxDoRepository.sendPrivateMessageDetailed("alice", "超时后已落库私信", "防重私信正文")
            assertTrue(recoveredPm.isSuccess)
            assertEquals(9102, recoveredPm.topicId)
            assertNull(recoveredPm.errorMessage)
            assertEquals(2, pmPostCallCount, "超时后应通过发件箱回查确认，严禁二次重发 POST 制造重复私信")

            assertTrue(LinuxDoRepository.archivePrivateMessage(9101, archived = true))
            assertTrue(LinuxDoRepository.archivePrivateMessage(9101, archived = false))

            // Bookmarks, Activities, Read History, Follow
            val bookmarks = LinuxDoRepository.fetchUserBookmarks("lucas", page = 0)
            assertEquals(301, bookmarks.single().id)
            assertEquals(88, bookmarks.single().actionType)

            val activities = LinuxDoRepository.fetchUserActivities("lucas", offset = 0)
            assertEquals(6002, activities.single().topicId)

            val history = LinuxDoRepository.fetchReadHistoryTopics(page = 0)
            assertEquals(6003, history.single().id)

            assertTrue(LinuxDoRepository.toggleFollowUser("alice", follow = true))
            assertTrue(LinuxDoRepository.toggleFollowUser("alice", follow = false))

            val following = LinuxDoRepository.fetchUserFollowList("lucas", followers = false)
            assertEquals("alice", following.single().username)
            assertTrue(following.single().isFollowing)

            val followers = LinuxDoRepository.fetchUserFollowList("lucas", followers = true)
            assertEquals(2, followers.size)
            assertTrue(followers.first { it.username == "alice" }.isFollowing)
            assertFalse(followers.first { it.username == "stranger" }.isFollowing)

            val followFeed = LinuxDoRepository.fetchUserFollowFeed("lucas", page = 0)
            assertEquals(6004, followFeed.single().topicId)

            // Connect report fallback & HTML parser
            val parsedConnect = ConnectStatsParser.parseConnectHtml(
                """<h2 class="card-title">信任等级 3 要求</h2><span class="badge badge-success">已达标</span>
                   <div class="tl3-ring"><span class="tl3-ring-label">访问天数</span><div class="tl3-ring-circle met" style="--val: 65; --max: 50"></div></div>"""
            )
            assertNotNull(parsedConnect)
            assertTrue(parsedConnect.isQualified)
            assertEquals(65, parsedConnect.daysVisited)
            val fallbackConnect = LinuxDoRepository.fetchConnectTrustLevelReport()
            assertEquals(88, fallbackConnect.daysVisited)

            // Title & Profile Info updates
            assertTrue(LinuxDoRepository.updateUserTitleResult("lucas", "开源先锋").isSuccess)
            assertEquals("开源先锋", lastProfilePutForm?.get("title"))

            assertTrue(
                LinuxDoRepository.updateUserProfileInfo(
                    username = "lucas",
                    displayName = "Lucas Updated",
                    bioRaw = "KMP Developer",
                    location = "Shanghai",
                    website = "https://linux.do",
                    hidePresence = true,
                    allowPrivateMessages = true
                )
            )
            assertEquals("Lucas Updated", lastProfilePutForm?.get("name"))
            assertEquals("KMP Developer", lastProfilePutForm?.get("bio_raw"))
            assertEquals("true", lastProfilePutForm?.get("hide_profile_and_presence"))

            // Badges & Account Preferences
            val earnedBadges = LinuxDoRepository.fetchUserBadges("lucas")
            assertEquals(1, earnedBadges.size)
            assertEquals("开源先锋", earnedBadges.single().name)
            assertTrue(earnedBadges.single().isEarned)

            val siteCatalogBadges = LinuxDoRepository.fetchAllSiteBadges(force = true)
            assertEquals(1, siteCatalogBadges.size)
            assertEquals("社区贡献", siteCatalogBadges.single().badgeGroupingName)

            val accountPrefs = LinuxDoRepository.fetchUserAccountPreferences("lucas")
            assertNotNull(accountPrefs)
            assertEquals("KMP Developer", accountPrefs.bioRaw)
            assertTrue("开源先锋" in accountPrefs.availableTitles)

            // Cloud Drafts + ComposerDraftSession: save, list, fetchByKey, hydrate/conflict, delete (verifying %20 encoding instead of +)
            val nextSeq = LinuxDoRepository.saveCloudDraft(
                draftKey = "topic 9900 draft",
                sequence = 7,
                replyRaw = "草稿正文内容",
                topicId = 9900
            )
            assertEquals(8, nextSeq)

            val draftsList = LinuxDoRepository.fetchCloudDrafts()
            assertEquals("topic 9900 draft", draftsList.single().draftKey)
            assertEquals("草稿正文内容", draftsList.single().raw)

            val lookup = LinuxDoRepository.fetchCloudDraftByKey("topic 9900 draft")
            assertEquals(8, lookup.sequence)
            assertEquals("草稿正文内容", lookup.draft?.raw)

            val emptyLocalSession = ComposerDraftSession(
                ComposerDraft(owner = "lucas", key = "topic 9900 draft", action = ComposerAction.REPLY, topicId = 9900)
            )
            assertNull(emptyLocalSession.hydrateFromServer().conflict)
            assertEquals("草稿正文内容", emptyLocalSession.current.raw)
            assertEquals(8, emptyLocalSession.savedSequence)

            val conflictingSession = ComposerDraftSession(
                ComposerDraft(owner = "lucas", key = "topic 9900 draft", action = ComposerAction.REPLY, raw = "本地离线改动", topicId = 9900)
            )
            val conflictItem = conflictingSession.hydrateFromServer().conflict
            assertNotNull(conflictItem)
            assertEquals("草稿正文内容", conflictItem.raw)
            assertEquals("本地离线改动", conflictingSession.current.raw)

            assertTrue(LinuxDoRepository.deleteCloudDraft("topic 9900 draft", sequence = 8))
            val pathEncodedUrls = observedDraftUrls.filter { it.contains("/drafts/") }
            assertTrue(pathEncodedUrls.isNotEmpty())
            assertTrue(pathEncodedUrls.all { it.contains("topic%209900%20draft.json") && !it.contains("+") })
        }
    }

    // -------------------------------------------------------------------------
    // 7. Logout Privacy Wipe, clearAllLocalCaches(), and Spoiler AST Leak Prevention
    // -------------------------------------------------------------------------
    @Test
    fun logoutPrivacyWipeClearAllLocalCachesAndSpoilerAstLeakPrevention() {
        // 7.1 UserApiKeyAuthManager.logout() privacy wipe while preserving cf_clearance
        CookieSentinel.ingestRawCookieString(
            "_t=private_session_tok; _forum_session=private_forum_sess; cf_clearance=preserved_cf_token; __cf_bm=preserved_bm_token",
            domain = "linux.do"
        )
        UserApiKeyAuthManager.recordWebSessionLogin(
            username = "lucas",
            displayName = "Lucas",
            avatarUrl = "https://linux.do/user_avatar/linux.do/lucas/144/1.png",
            trustLevel = 3,
            userId = 42
        )
        LocalComposerDrafts.save(
            ComposerDraft(
                owner = "lucas",
                key = "topic_500",
                action = ComposerAction.REPLY,
                raw = "尚未发送的敏感草稿",
                topicId = 500
            )
        )
        LinuxDoRepository.cacheTopicExcerpt(500, "受限版块摘要缓存")
        LinuxDoRepository.mergeCategoriesFromSiteRoot(
            buildJsonObject {
                put("can_create_tag", true)
                put("categories", buildJsonArray {})
            }
        )
        LinuxDoRepository.markTopicRead(500, 12)
        LinuxDoRepository.updateCsrfToken("csrf_before_logout")

        assertNotNull(LocalComposerDrafts.get("lucas", "topic_500"))
        assertEquals("受限版块摘要缓存", LinuxDoRepository.getCachedTopicExcerpt(500))
        assertTrue(LinuxDoRepository.canCreateTag)
        assertEquals(12, LinuxDoRepository.getServerConfirmedReadFloor(500))

        UserApiKeyAuthManager.logout()

        assertFalse(UserApiKeyAuthManager.isAuthorized())
        assertNull(UserApiKeyAuthManager.currentUser)
        assertNull(LocalComposerDrafts.get("lucas", "topic_500"))
        assertTrue(LocalComposerDrafts.all("lucas").isEmpty())
        assertNull(LinuxDoRepository.getCachedTopicExcerpt(500))
        assertFalse(LinuxDoRepository.hasCachedTopicExcerpt(500))
        assertFalse(LinuxDoRepository.canCreateTag)
        assertNull(LinuxDoRepository.getServerConfirmedReadFloor(500))
        assertTrue(LinuxDoRepository.getReadTopicSnapshot().isEmpty())
        assertNull(LinuxDoRepository.getCachedCsrfToken())
        assertNull(CookieSentinel.resolveWinningCookies()["_t"])
        assertNull(CookieSentinel.resolveWinningCookies()["_forum_session"])
        assertEquals("preserved_cf_token", CookieSentinel.resolveWinningCookies()["cf_clearance"]?.value)
        assertEquals("preserved_bm_token", CookieSentinel.resolveWinningCookies()["__cf_bm"]?.value)

        // 7.2 LinuxDoRepository.clearAllLocalCaches() clears caches while keeping isAuthorized() == true
        authenticateTestUser(username = "lucas", userId = 42, cookieToken = "keep_logged_in_tok")
        LinuxDoRepository.cacheTopicExcerpt(600, "待清理的话题摘要")
        DiscourseAstParser.parseOrGetCached(600, "<p>缓存的 AST 节点</p>")
        assertNotNull(LinuxDoRepository.getCachedTopicExcerpt(600))
        assertNotNull(DiscourseAstParser.getCached(600, "<p>缓存的 AST 节点</p>"))

        LinuxDoRepository.clearAllLocalCaches()

        assertNull(LinuxDoRepository.getCachedTopicExcerpt(600))
        assertNull(DiscourseAstParser.getCached(600, "<p>缓存的 AST 节点</p>"))
        assertTrue(UserApiKeyAuthManager.isAuthorized())
        assertEquals("lucas", UserApiKeyAuthManager.currentUser?.username)
        assertEquals("keep_logged_in_tok", CookieSentinel.resolveWinningCookies()["_t"]?.value)

        // 7.3 Heading inline spoiler AST isolation: never leak secret inside unmasked PostBlockNode.Heading
        val headingHtml = """<h2>公开前缀 <span class="spoiler">机密口令</span></h2>"""
        val headingNodes = DiscourseAstParser.parseCookedHtml(headingHtml)
        val visibleHeadings = headingNodes.filterIsInstance<PostBlockNode.Heading>()
        assertEquals(1, visibleHeadings.size)
        assertEquals(2, visibleHeadings.single().level)
        assertEquals("公开前缀", visibleHeadings.single().text)
        assertFalse(visibleHeadings.any { "机密口令" in it.text || it.rich.runs.any { r -> "机密口令" in r.text } })
        val headingSpoilers = headingNodes.filterIsInstance<PostBlockNode.Spoiler>()
        assertEquals(1, headingSpoilers.size)
        assertEquals("机密口令", headingSpoilers.single().children.filterIsInstance<PostBlockNode.Paragraph>().single().text)
        assertFalse("机密口令" in postPreviewText(headingHtml))

        // 纯剧透标题同样绝不可回退 node.text() 泄漏明文 Heading
        val pureSpoilerHeadingNodes = DiscourseAstParser.parseCookedHtml("""<h2><span class="spoiler">纯标题机密</span></h2>""")
        assertTrue(pureSpoilerHeadingNodes.filterIsInstance<PostBlockNode.Heading>().isEmpty())
        assertEquals(
            "纯标题机密",
            pureSpoilerHeadingNodes.filterIsInstance<PostBlockNode.Spoiler>().single()
                .children.filterIsInstance<PostBlockNode.Paragraph>().single().text
        )

        // 7.4 Multi-paragraph [spoiler]...[/spoiler] in MarkdownPreview -> DiscourseAstParser: BOTH paragraphs masked
        val previewHtml = MarkdownPreview.render("[spoiler]第一段隐藏\n\n第二段依然隐藏[/spoiler]")
        val previewNodes = DiscourseAstParser.parseCookedHtml(previewHtml)
        assertTrue(
            previewNodes.none { node ->
                node !is PostBlockNode.Spoiler &&
                    when (node) {
                        is PostBlockNode.Paragraph -> "第一段隐藏" in node.text || "第二段依然隐藏" in node.text
                        is PostBlockNode.Heading -> "第一段隐藏" in node.text || "第二段依然隐藏" in node.text
                        else -> false
                    }
            },
            "多段 [spoiler] 渲染后绝不允许第二段逃逸为未遮罩的普通段落"
        )
        val maskedTexts = previewNodes.filterIsInstance<PostBlockNode.Spoiler>()
            .flatMap { it.children.filterIsInstance<PostBlockNode.Paragraph>() }
            .map { it.text }
        assertEquals(listOf("第一段隐藏", "第二段依然隐藏"), maskedTexts)
    }
}
