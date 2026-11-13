package do_.linux.lindo

import do_.linux.lindo.core.compose.ComposerAction
import do_.linux.lindo.core.compose.ComposerDraft
import do_.linux.lindo.core.compose.ComposerDraftSession
import do_.linux.lindo.core.compose.LocalComposerDrafts
import do_.linux.lindo.core.connect.ScreenTrackEngine
import do_.linux.lindo.core.connect.UserPresenceService
import do_.linux.lindo.core.model.UserNotificationItem
import do_.linux.lindo.core.network.ApiFailureKind
import do_.linux.lindo.core.network.ApiReadException
import do_.linux.lindo.core.network.AuthenticatedUserProfile
import do_.linux.lindo.core.network.CF_MITIGATED_CHALLENGE_PREFIX
import do_.linux.lindo.core.network.CookieSentinel
import do_.linux.lindo.core.network.LinuxDoCategoryMeta
import do_.linux.lindo.core.network.LinuxDoRepository
import do_.linux.lindo.core.network.PlatformMediaPicker
import do_.linux.lindo.core.network.PostSubmission
import do_.linux.lindo.core.network.UserApiKeyAuthManager
import do_.linux.lindo.core.network.onAuthenticationInvalidated
import do_.linux.lindo.core.network.readApiObject
import do_.linux.lindo.ui.isGuestLoginRequiredError
import do_.linux.lindo.ui.resolveGuestLoginPromptForUrl
import do_.linux.lindo.ui.shouldGateCategoryForGuest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Round4StabilityAndBadgesRegressionTest {

    private var savedHttpExecutor = UserApiKeyAuthManager.webViewHttpExecutor
    private var savedUploadExecutor = PlatformMediaPicker.webViewUploadExecutor
    private var savedBinaryUploadExecutor = PlatformMediaPicker.webViewBinaryUploadExecutor
    private var savedAuthCallback = UserApiKeyAuthManager.onAuthStateChanged
    private var savedPersistSession = UserApiKeyAuthManager.onPersistSession
    private var savedLogoutClear = UserApiKeyAuthManager.onLogoutPlatformClear
    private var savedAuthInvalidated = onAuthenticationInvalidated
    private var savedPrepareRecovery = UserApiKeyAuthManager.onPrepareManualRecovery
    private var savedRequireBrowserOnly = UserApiKeyAuthManager.requireBrowserTransportOnly

    @BeforeTest
    fun setUp() {
        savedHttpExecutor = UserApiKeyAuthManager.webViewHttpExecutor
        savedUploadExecutor = PlatformMediaPicker.webViewUploadExecutor
        savedBinaryUploadExecutor = PlatformMediaPicker.webViewBinaryUploadExecutor
        savedAuthCallback = UserApiKeyAuthManager.onAuthStateChanged
        savedPersistSession = UserApiKeyAuthManager.onPersistSession
        savedLogoutClear = UserApiKeyAuthManager.onLogoutPlatformClear
        savedAuthInvalidated = onAuthenticationInvalidated
        savedPrepareRecovery = UserApiKeyAuthManager.onPrepareManualRecovery
        savedRequireBrowserOnly = UserApiKeyAuthManager.requireBrowserTransportOnly

        UserApiKeyAuthManager.requireBrowserTransportOnly = false
        UserApiKeyAuthManager.webViewHttpExecutor = null
        UserApiKeyAuthManager.onAuthStateChanged = null
        UserApiKeyAuthManager.onPersistSession = null
        UserApiKeyAuthManager.onLogoutPlatformClear = null
        onAuthenticationInvalidated = null
        UserApiKeyAuthManager.onPrepareManualRecovery = null
        PlatformMediaPicker.webViewUploadExecutor = null
        PlatformMediaPicker.webViewBinaryUploadExecutor = null
        UserApiKeyAuthManager.logout()
        CookieSentinel.clearAll()
        LinuxDoRepository.clearUserSessionCaches()
        LinuxDoRepository.clearTransientRateLimit()
        LinuxDoRepository.clearChallengeCooldown()
        ScreenTrackEngine.resetForTest()
        UserPresenceService.setAppForeground(true)
    }

    @AfterTest
    fun tearDown() {
        UserApiKeyAuthManager.requireBrowserTransportOnly = savedRequireBrowserOnly
        UserApiKeyAuthManager.webViewHttpExecutor = savedHttpExecutor
        PlatformMediaPicker.webViewUploadExecutor = savedUploadExecutor
        PlatformMediaPicker.webViewBinaryUploadExecutor = savedBinaryUploadExecutor
        UserApiKeyAuthManager.onAuthStateChanged = null
        UserApiKeyAuthManager.onPersistSession = null
        UserApiKeyAuthManager.onLogoutPlatformClear = null
        onAuthenticationInvalidated = null
        UserApiKeyAuthManager.onPrepareManualRecovery = null
        UserApiKeyAuthManager.logout()
        CookieSentinel.clearAll()
        LinuxDoRepository.clearUserSessionCaches()
        LinuxDoRepository.clearTransientRateLimit()
        LinuxDoRepository.clearChallengeCooldown()
        ScreenTrackEngine.resetForTest()
        UserPresenceService.setAppForeground(true)
        UserApiKeyAuthManager.onAuthStateChanged = savedAuthCallback
        UserApiKeyAuthManager.onPersistSession = savedPersistSession
        UserApiKeyAuthManager.onLogoutPlatformClear = savedLogoutClear
        onAuthenticationInvalidated = savedAuthInvalidated
        UserApiKeyAuthManager.onPrepareManualRecovery = savedPrepareRecovery
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

    private fun buildGroupedBadgesPayload(distinctCount: Int = 28, extraRepeatsOnFirst: Int = 11): String {
        val badgesArray = buildJsonArray {
            for (i in 1..distinctCount) {
                add(
                    buildJsonObject {
                        put("id", 100 + i)
                        put("name", "徽章 #$i")
                        put("description", "测试徽章描述 $i")
                        put("badge_type_id", if (i == 1) 1 else 3)
                        put("icon", "fa-certificate")
                        put("allow_title", i <= 2)
                    }
                )
            }
        }
        val userBadgesArray = buildJsonArray {
            for (i in 1..distinctCount) {
                val grantCount = if (i == 1) 1 + extraRepeatsOnFirst else 1
                add(
                    buildJsonObject {
                        put("id", 5000 + i)
                        put("badge_id", 100 + i)
                        put("count", grantCount)
                        put("granted_at", "2026-11-10T10:00:00.000Z")
                    }
                )
            }
        }
        return buildJsonObject {
            put("badges", badgesArray)
            put("user_badges", userBadgesArray)
        }.toString()
    }

    @Test
    fun badgeCountUnifiesDistinctBadgeTypesWhilePreservingRepeatableGrantTotal() = runTest {
        CookieSentinel.ingestRawCookieString("_t=valid_badge_session", domain = "linux.do")
        UserApiKeyAuthManager.recordWebSessionLogin(
            username = "lucas",
            displayName = "Lucas",
            avatarUrl = "https://linux.do/user_avatar/linux.do/lucas/144/1_2.png",
            trustLevel = 3,
            userId = 42
        )

        val groupedJson = buildGroupedBadgesPayload(distinctCount = 28, extraRepeatsOnFirst = 11)
        val userJson = buildJsonObject {
            put(
                "user",
                buildJsonObject {
                    put("id", 42)
                    put("username", "lucas")
                    put("name", "Lucas")
                    put("avatar_template", "/user_avatar/linux.do/lucas/{size}/1_2.png")
                    put("trust_level", 3)
                    put("badge_count", 28)
                }
            )
            // 模拟 summary/user JSON 中附带高授予次数的可重复徽章（单项 count=12，累计远超种数）
            put(
                "badges",
                buildJsonArray {
                    for (i in 1..6) {
                        add(
                            buildJsonObject {
                                put("id", 100 + i)
                                put("name", "徽章 #$i")
                                put("badge_type_id", 3)
                                put("count", if (i == 1) 20 else 4)
                            }
                        )
                    }
                }
            )
            put(
                "user_badges",
                buildJsonArray {
                    for (i in 1..6) {
                        add(
                            buildJsonObject {
                                put("id", 900 + i)
                                put("badge_id", 100 + i)
                                put("count", if (i == 1) 20 else 4)
                            }
                        )
                    }
                }
            )
        }.toString()
        val summaryJson = buildJsonObject {
            put(
                "user_summary",
                buildJsonObject {
                    put("days_visited", 100)
                    put("topics_entered", 500)
                    put("posts_read_count", 6000)
                    put("likes_given", 120)
                    put("likes_received", 85)
                    put("topic_count", 10)
                    put("post_count", 90)
                    put("time_read", 72_000)
                }
            )
        }.toString()

        withMockHttp(
            handler = { url, _, _, _ ->
                when {
                    url.contains("/session/current.json") -> 200 to """{"current_user":{"id":42,"username":"lucas","name":"Lucas","trust_level":3,"avatar_template":"/user_avatar/linux.do/lucas/{size}/1_2.png"}}"""
                    url.contains("/u/lucas/summary.json") -> 200 to summaryJson
                    url.contains("/u/lucas.json") -> 200 to userJson
                    url.contains("/user-badges/lucas.json") -> 200 to groupedJson
                    else -> 404 to """{"errors":["not found"]}"""
                }
            }
        ) {
            val profile = LinuxDoRepository.fetchCurrentUserProfile()
            assertNotNull(profile)
            // 外层已获徽章数必须严格按去重后的徽章种数（28），不能被可重复徽章 count 总和（40/39）撑大
            assertEquals(28, profile.badgeCount)

            val card = LinuxDoRepository.fetchPublicUserCard("lucas")
            assertNotNull(card)
            assertEquals(28, card.badgeCount)

            // 假设旧缓存曾错误保留过 39，拉取完整徽章列表后也应自动校准为去重种数 28，同时保留单项 count=12、累计 39 次
            UserApiKeyAuthManager.fullProfileCache = profile.copy(badgeCount = 39)
            val earnedBadges = LinuxDoRepository.fetchUserBadges("lucas")
            assertEquals(28, earnedBadges.distinctBy { it.id }.size)
            assertEquals(39, earnedBadges.sumOf { it.count.coerceAtLeast(1) })
            assertEquals(12, earnedBadges.first { it.id == 101 }.count)
            assertEquals(28, UserApiKeyAuthManager.fullProfileCache?.badgeCount)
        }
    }

    @Test
    fun cloudflare503And200HtmlChallengesClassifyAsVerificationWithoutAuthInvalidationOrRetryStorm() = runTest {
        var authInvalidatedCount = 0
        onAuthenticationInvalidated = { authInvalidatedCount++ }

        val cf503Html = "<!DOCTYPE html><html><head><title>Just a moment...</title></head><body><div class=\"cf-browser-verification\">Checking your browser</div></body></html>"
        val ex503 = assertFailsWith<ApiReadException> {
            readApiObject(503 to cf503Html)
        }
        assertEquals(ApiFailureKind.Verification, ex503.kind)
        assertEquals(0, authInvalidatedCount)

        val cf200Html = "<html><head><title>Attention Required! | Cloudflare</title></head><body>__cf_chl_opt</body></html>"
        val ex200 = assertFailsWith<ApiReadException> {
            readApiObject(200 to cf200Html)
        }
        assertEquals(ApiFailureKind.Verification, ex200.kind)
        assertEquals(0, authInvalidatedCount)

        var fetchCalls = 0
        withMockHttp(
            handler = { url, _, _, _ ->
                if (url.contains("latest.json")) {
                    fetchCalls++
                    503 to cf503Html
                } else {
                    404 to "{}"
                }
            }
        ) {
            val feedEx = assertFailsWith<ApiReadException> {
                LinuxDoRepository.fetchTopics("latest.json")
            }
            assertEquals(ApiFailureKind.Verification, feedEx.kind)
            // 503 Cloudflare HTML 质询严禁被当作普通网关瞬断重试，防止放大风控封禁
            assertEquals(1, fetchCalls)
        }
    }

    @Test
    fun directAccountSwitchClearsPreviousUserCsrfAndSessionCachesAndNotifiesObservers() = runTest {
        CookieSentinel.ingestRawCookieString("_t=alice_cookie", domain = "linux.do")
        UserApiKeyAuthManager.recordWebSessionLogin(
            username = "alice",
            displayName = "Alice",
            avatarUrl = "https://linux.do/user_avatar/linux.do/alice/144/1.png",
            trustLevel = 2,
            userId = 101
        )
        LinuxDoRepository.updateCsrfToken("alice_csrf_secret")
        assertEquals("alice_csrf_secret", LinuxDoRepository.getCachedCsrfToken())

        var authChangedEvents = 0
        val epochBefore = UserApiKeyAuthManager.authStateNotifyEpoch
        UserApiKeyAuthManager.onAuthStateChanged = { authChangedEvents++ }

        CookieSentinel.ingestRawCookieString("_t=bob_cookie", domain = "linux.do")
        UserApiKeyAuthManager.recordWebSessionLogin(
            username = "bob",
            displayName = "Bob",
            avatarUrl = "https://linux.do/user_avatar/linux.do/bob/144/2.png",
            trustLevel = 3,
            userId = 202
        )
        assertEquals(1, authChangedEvents)
        assertTrue(UserApiKeyAuthManager.authStateNotifyEpoch > epochBefore)
        assertEquals("bob", UserApiKeyAuthManager.currentUser?.username)
        assertNull(UserApiKeyAuthManager.fullProfileCache)
        assertNull(LinuxDoRepository.getCachedCsrfToken(), "切换账号时必须立即清除旧账号遗留的 CSRF Token")

        // 验证通过 onSessionAuthenticated 直接切往第三个账号同样清空 CSRF 与旧账号资料缓存
        LinuxDoRepository.updateCsrfToken("bob_csrf_secret")
        assertEquals("bob_csrf_secret", LinuxDoRepository.getCachedCsrfToken())
        UserApiKeyAuthManager.onSessionAuthenticated(
            profile = AuthenticatedUserProfile(userId = 303, username = "charlie", displayName = "Charlie", trustLevel = 2),
            rawCookies = "_t=charlie_cookie"
        )
        assertEquals(2, authChangedEvents)
        assertEquals("charlie", UserApiKeyAuthManager.currentUser?.username)
        assertNull(LinuxDoRepository.getCachedCsrfToken())
    }

    @Test
    fun redeemOtpSilentlyPreservesApiKeyOnFailureOrUnverifiedStaleCookieAndClearsOnlyWhenVerified() = runTest {
        UserApiKeyAuthManager.restorePersistedSession(
            profile = AuthenticatedUserProfile(userId = 7, username = "otp_user", displayName = "OTP", trustLevel = 2),
            apiKey = "precious_user_api_key",
            rawCookies = null
        )

        // 场景 1：POST /session/otp 被 429 限流拦截时必须返回 false 且保留 User-Api-Key
        withMockHttp(
            handler = { _, _, _, _ -> 429 to """{"errors":["Too Many Requests"]}""" }
        ) {
            assertFalse(LinuxDoRepository.redeemOtpSilently("otp_token_429"))
            assertEquals("precious_user_api_key", UserApiKeyAuthManager.getApiKey())
        }
        LinuxDoRepository.clearTransientRateLimit()

        // 场景 2：残留失效旧 _t 未发生轮换且 /session/current.json 校验失败（403）时，严禁清空 User-Api-Key
        CookieSentinel.ingestRawCookieString("_t=stale_unrotated_t", domain = "linux.do")
        UserApiKeyAuthManager.restoreApiKeyQuietly("precious_user_api_key")
        var globalAuthInvalidated = false
        onAuthenticationInvalidated = { globalAuthInvalidated = true }

        withMockHttp(
            handler = { url, method, _, _ ->
                when {
                    url.contains("/session/otp/") && method == "POST" -> 200 to "<html>Redirected without rotating cookie</html>"
                    url.contains("/session/current.json") -> 403 to """{"errors":["Not logged in"],"error_type":"not_logged_in"}"""
                    url.contains("/u/otp_user.json") -> 403 to """{"errors":["Not logged in"],"error_type":"not_logged_in"}"""
                    else -> 404 to "{}"
                }
            }
        ) {
            assertFalse(LinuxDoRepository.redeemOtpSilently("otp_token_stale"))
            assertEquals("precious_user_api_key", UserApiKeyAuthManager.getApiKey())
            assertFalse(globalAuthInvalidated)
        }

        // 场景 3：POST /session/otp 成功下发新 _t 并通过档案校验后，返回 true 且安全切换为纯 Cookie 模式
        withMockHttp(
            handler = { url, method, _, _ ->
                when {
                    url.contains("/session/otp/") && method == "POST" -> {
                        CookieSentinel.ingestRawCookieString("_t=fresh_rotated_t", domain = "linux.do")
                        200 to """{"success":"OK"}"""
                    }
                    url.contains("/session/current.json") -> 200 to """{"current_user":{"id":7,"username":"otp_user","name":"OTP","trust_level":2,"avatar_template":"/user_avatar/linux.do/otp_user/{size}/1.png"}}"""
                    url.contains("/u/otp_user/summary.json") -> 200 to """{"user_summary":{"days_visited":12}}"""
                    url.contains("/u/otp_user.json") -> 200 to """{"user":{"id":7,"username":"otp_user","name":"OTP","trust_level":2,"badge_count":3,"avatar_template":"/user_avatar/linux.do/otp_user/{size}/1.png"}}"""
                    else -> 404 to "{}"
                }
            }
        ) {
            assertTrue(LinuxDoRepository.redeemOtpSilently("otp_token_ok"))
            assertNull(UserApiKeyAuthManager.getApiKey())
            assertEquals("fresh_rotated_t", CookieSentinel.resolveWinningCookies()["_t"]?.value)
        }
    }

    @Test
    fun backgroundScreenTrackFailuresDoNotExhaustTopicRetryBudget() {
        val t0 = 1_700_000_000_000L
        ScreenTrackEngine.onTopicEntered(5566, initialVisiblePosts = setOf(1, 2), nowMs = t0)
        ScreenTrackEngine.tickAndEnqueue(nowMs = t0 + 2_000L)
        ScreenTrackEngine.onTopicExited(5566, nowMs = t0 + 3_000L)
        assertEquals(1, ScreenTrackEngine.pendingBatchesCount)

        UserPresenceService.setAppForeground(false)
        assertFalse(UserPresenceService.isForegroundState)

        // 模拟 App 退到后台期间连续 5 次网络发送失败（超过前台 MAX_TOPIC_RETRIES = 3）
        var clock = t0 + 4_000L
        repeat(5) {
            val batch = ScreenTrackEngine.popNextSendableBatch(nowMs = clock)
            assertNotNull(batch, "后台重排不应丢弃待上报的阅读时长批次")
            ScreenTrackEngine.onBatchSendResult(batch, statusCode = null, nowMs = clock)
            clock += 20_000L
        }
        assertEquals(1, ScreenTrackEngine.pendingBatchesCount)

        // 回到前台后发送成功，楼层已读确认正常落盘递增 serverConfirmedReadVersion
        UserPresenceService.setAppForeground(true)
        assertTrue(UserPresenceService.isForegroundState)
        val versionBefore = ScreenTrackEngine.serverConfirmedReadVersion
        val foregroundBatch = ScreenTrackEngine.popNextSendableBatch(nowMs = clock)
        assertNotNull(foregroundBatch)
        ScreenTrackEngine.onBatchSendResult(foregroundBatch, statusCode = 200, nowMs = clock)
        assertEquals(0, ScreenTrackEngine.pendingBatchesCount)
        assertTrue(ScreenTrackEngine.serverConfirmedReadVersion > versionBefore)
    }

    @Test
    fun followFeedPaginationUsesServerHasMoreCursorEvenWhenDeduplicatedTopicsUnder15() = runTest {
        CookieSentinel.ingestRawCookieString("_t=follow_session", domain = "linux.do")
        UserApiKeyAuthManager.recordWebSessionLogin(
            username = "reader",
            displayName = "Reader",
            avatarUrl = "https://linux.do/user_avatar/linux.do/reader/144/1.png",
            trustLevel = 2,
            userId = 88
        )

        val page0Posts = buildJsonArray {
            // 10 条动态全部集中于 3 个话题（去重后仅 3 条 < 15），但 extras.has_more = true
            for (idx in 0 until 10) {
                val topicId = 900 + (idx % 3)
                add(
                    buildJsonObject {
                        put("id", 7000 + idx)
                        put("topic_id", topicId)
                        put("post_number", idx + 1)
                        put("created_at", "2026-11-11T10:0${9 - idx}:00.000Z")
                        put("excerpt", "关注动态摘要 $idx")
                        put(
                            "user",
                            buildJsonObject {
                                put("id", 300 + (idx % 3))
                                put("username", "author_${idx % 3}")
                                put("avatar_template", "/user_avatar/linux.do/author_${idx % 3}/{size}/1.png")
                            }
                        )
                        put(
                            "topic",
                            buildJsonObject {
                                put("id", topicId)
                                put("title", "热门关注话题 #$topicId")
                                put("posts_count", 25)
                                put("category_id", 4)
                            }
                        )
                    }
                )
            }
        }
        val page0Json = buildJsonObject {
            put("posts", page0Posts)
            put("extras", buildJsonObject { put("has_more", true) })
        }.toString()

        val page1Json = buildJsonObject {
            put(
                "posts",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("id", 6999)
                            put("topic_id", 999)
                            put("post_number", 1)
                            put("created_at", "2026-11-11T09:30:00.000Z")
                            put("excerpt", "第二页唯一话题")
                            put(
                                "user",
                                buildJsonObject {
                                    put("id", 399)
                                    put("username", "author_last")
                                    put("avatar_template", "/user_avatar/linux.do/author_last/{size}/1.png")
                                }
                            )
                            put(
                                "topic",
                                buildJsonObject {
                                    put("id", 999)
                                    put("title", "第二页话题")
                                    put("posts_count", 3)
                                    put("category_id", 4)
                                }
                            )
                        }
                    )
                }
            )
            put("extras", buildJsonObject { put("has_more", false) })
        }.toString()

        withMockHttp(
            handler = { url, _, _, _ ->
                when {
                    url.contains("/follow/posts/reader.json") && !url.contains("created_before=") -> 200 to page0Json
                    url.contains("/follow/posts/reader.json") && url.contains("created_before=") -> 200 to page1Json
                    else -> 404 to "{}"
                }
            }
        ) {
            val firstPage = LinuxDoRepository.fetchTopics(path = "follow", page = 0)
            assertEquals(3, firstPage.size)
            assertTrue(LinuxDoRepository.computeHasMoreTopics("follow", currentPage = 0, fetchedCount = firstPage.size))

            val secondPage = LinuxDoRepository.fetchTopics(path = "follow", page = 1)
            assertEquals(1, secondPage.size)
            assertFalse(LinuxDoRepository.computeHasMoreTopics("follow", currentPage = 1, fetchedCount = secondPage.size))
        }
    }

    @Test
    fun uploadMediaPrefersChunkedBinaryExecutorOverBase64StringExecutor() = runTest {
        val rawPayload = ByteArray(4096) { idx -> (idx and 0xFF).toByte() }
        var binaryCalledWith: ByteArray? = null
        var base64Called = false

        PlatformMediaPicker.webViewBinaryUploadExecutor = { fileName, mimeType, bytes ->
            assertEquals("demo.png", fileName)
            assertEquals("image/png", mimeType)
            binaryCalledWith = bytes
            200 to """{"original_filename":"demo.png","short_url":"upload://demo123.png","url":"/uploads/default/original/1X/demo123.png","width":800,"height":600,"human_filesize":"4 KB"}"""
        }
        PlatformMediaPicker.webViewUploadExecutor = { _, _, _ ->
            base64Called = true
            500 to """{"errors":["should not be called"]}"""
        }

        val uploaded = LinuxDoRepository.uploadMediaToLinuxDo("demo.png", "image/png", rawPayload)
        assertNotNull(uploaded)
        assertFalse(base64Called)
        val captured = binaryCalledWith
        assertNotNull(captured)
        assertContentEquals(rawPayload, captured)
        assertEquals("upload://demo123.png", uploaded.shortUrl)
    }

    @Test
    fun ingestSetCookieHeaderEvictsDeletedSessionCookieWhilePreservingCfClearance() {
        CookieSentinel.ingestRawCookieString("_t=active_tok; cf_clearance=cf_trust_token", domain = "linux.do")
        assertEquals("active_tok", CookieSentinel.resolveWinningCookies()["_t"]?.value)
        assertEquals("cf_trust_token", CookieSentinel.resolveWinningCookies()["cf_clearance"]?.value)

        CookieSentinel.ingestSetCookieHeader(
            "_t=; path=/; expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0; HttpOnly; Secure",
            domain = "linux.do"
        )
        assertNull(CookieSentinel.resolveWinningCookies()["_t"])
        assertEquals("cf_trust_token", CookieSentinel.resolveWinningCookies()["cf_clearance"]?.value)
    }

    @Test
    fun booleanMutationsPropagateAuthInvalidationOn401AndReject200CloudflareHtml() = runTest {
        CookieSentinel.ingestRawCookieString("_t=mut_tok", domain = "linux.do")
        LinuxDoRepository.updateCsrfToken("csrf_mut")
        var invalidatedCount = 0
        onAuthenticationInvalidated = { invalidatedCount++ }

        withMockHttp(
            handler = { url, _, _, _ ->
                when {
                    url.contains("/bookmarks/99.json") -> 401 to """{"errors":["You need to be logged in"],"error_type":"not_logged_in"}"""
                    url.contains("/follow/") -> 200 to "<!DOCTYPE html><html><head><title>Just a moment...</title></head><body>_cf_chl_opt</body></html>"
                    url.contains("/posts/88.json") -> 200 to "<html><title>Just a moment...</title><body>challenges.cloudflare.com</body></html>"
                    else -> 200 to """{"success":"OK"}"""
                }
            }
        ) {
            assertFalse(LinuxDoRepository.deleteBookmark(99))
            assertEquals(1, invalidatedCount)

            // 200 HTML Cloudflare 质询必须判为失败 false，且绝不能误触发认证失效退登
            assertFalse(LinuxDoRepository.toggleFollowUser("someone", follow = true))
            assertFalse(LinuxDoRepository.deletePost(88))
            assertEquals(1, invalidatedCount)
        }
    }

    @Test
    fun resolveNotificationActorParsesPostWithoutPostNumberFieldCleanly() = runTest {
        val item = UserNotificationItem(
            id = 3001,
            notificationType = 17,
            read = false,
            createdAtText = "5m",
            topicId = 7788,
            postNumber = 1,
            originalPostId = 99001,
            title = "集中帖更新",
            actingUsername = "2 个回复",
            excerpt = ""
        )

        withMockHttp(
            handler = { url, _, _, _ ->
                if (url.contains("/posts/99001.json")) {
                    // 故意省略 post_number，验证命名参数 fallbackIdx = 0, topicId = item.topicId 不会错位
                    200 to """{"id":99001,"topic_id":7788,"username":"neo","name":"Neo","avatar_template":"/user_avatar/linux.do/neo/{size}/88_2.png","trust_level":4,"cooked":"<p>hello</p>"}"""
                } else {
                    404 to "{}"
                }
            }
        ) {
            val actor = LinuxDoRepository.resolveNotificationActor(item)
            assertNotNull(actor)
            assertEquals("neo", actor.username)
            assertTrue(actor.avatarTemplate.contains("/user_avatar/linux.do/neo/"))
        }
    }

    @Test
    fun regularTopicListDoesNotBackfillVisitedTopicExcerptIntoFeedSummaries() = runTest {
        val detailJson = """
            {
              "id": 8801,
              "title": "白嫖的geminipro全掉了你们的掉了吗",
              "posts_count": 3,
              "category_id": 4,
              "post_stream": {
                "stream": [101],
                "posts": [
                  {
                    "id": 101,
                    "post_number": 1,
                    "username": "lol369",
                    "avatar_template": "/user_avatar/linux.do/lol369/{size}/1.png",
                    "cooked": "<p>一楼正文内容：刚发现账号权限掉回普通版了。</p>"
                  }
                ]
              }
            }
        """.trimIndent()
        val latestJson = """
            {
              "users": [
                {"id": 11, "username": "lol369", "avatar_template": "/user_avatar/linux.do/lol369/{size}/1.png"}
              ],
              "topic_list": {
                "topics": [
                  {
                    "id": 8801,
                    "title": "白嫖的geminipro全掉了你们的掉了吗",
                    "posts_count": 3,
                    "category_id": 4,
                    "posters": [{"user_id": 11, "description": "Original Poster"}]
                  }
                ]
              }
            }
        """.trimIndent()

        withMockHttp(
            handler = { url, _, _, _ ->
                when {
                    url.contains("/t/8801.json") -> 200 to detailJson
                    url.contains("latest.json") -> 200 to latestJson
                    else -> 404 to "{}"
                }
            }
        ) {
            // 先点进话题阅读（触发 topicExcerptCache 写入）
            val detail = LinuxDoRepository.fetchTopicDetail(8801)
            assertEquals(1, detail.posts.size)
            assertNotNull(LinuxDoRepository.getCachedTopicExcerpt(8801))

            // 再回到首页刷新常规列表：服务端未返回 excerpt 的普通话题绝不能因曾点进阅读而被回填摘要导致卡片高度参差不齐
            val feed = LinuxDoRepository.fetchTopics("latest.json")
            assertEquals(1, feed.size)
            assertNull(feed.single().excerpt)
        }
    }

    @Test
    fun composerDraftSyncAndPostPublishSurfaceSiteVerificationAndRecoverWithoutDataLoss() = runTest {
        CookieSentinel.ingestRawCookieString("_t=reply_tok", domain = "linux.do")
        LinuxDoRepository.updateCsrfToken("csrf_reply")
        LocalComposerDrafts.remove("lucas", "topic_9901")
        LocalComposerDrafts.forgetSequence("lucas", "topic_9901")

        var challengeActive = true
        withMockHttp(
            handler = { url, method, _, _ ->
                when {
                    url.contains("/drafts/topic_9901.json") && method == "GET" -> 200 to """{"draft":null,"draft_sequence":4}"""
                    url.contains("/drafts.json") && method == "POST" -> {
                        if (challengeActive) {
                            403 to (CF_MITIGATED_CHALLENGE_PREFIX + "<!DOCTYPE html><html><title>Just a moment...</title></html>")
                        } else {
                            200 to """{"draft_sequence":5}"""
                        }
                    }
                    url.contains("/posts.json") && method == "POST" -> {
                        if (challengeActive) {
                            403 to (CF_MITIGATED_CHALLENGE_PREFIX + "<!DOCTYPE html><html><title>Just a moment...</title></html>")
                        } else {
                            200 to """{"id":50123,"topic_id":9901,"post_number":10,"username":"lucas","cooked":"<p>浏览量是最简单的了佬友。</p>"}"""
                        }
                    }
                    url.contains("/drafts/topic_9901.json") && method == "DELETE" -> 200 to """{"success":"OK"}"""
                    else -> 404 to "{}"
                }
            }
        ) {
            val draft = ComposerDraft(
                owner = "lucas",
                key = "topic_9901",
                action = ComposerAction.REPLY,
                raw = "浏览量是最简单的了佬友。",
                topicId = 9901,
                replyToPostNumber = 9,
                replyToUsername = "Cory",
                sequence = 4
            )
            val session = ComposerDraftSession(draft, initialExistsOnServer = false)
            session.update(draft)

            // 1) 云端草稿保存遭遇 Cloudflare Managed Challenge 时必须原样抛出 Verification，且文案不再误导用户跳外部浏览器，本机草稿完整保留
            val draftVerifyEx = assertFailsWith<ApiReadException> {
                session.sync()
            }
            assertEquals(ApiFailureKind.Verification, draftVerifyEx.kind)
            assertEquals("需要完成站点验证", draftVerifyEx.message)
            assertFalse(draftVerifyEx.message.orEmpty().contains("官网"))
            assertEquals("浏览量是最简单的了佬友。", LocalComposerDrafts.get("lucas", "topic_9901")?.raw)

            // 2) 发送回复遭遇 Cloudflare Managed Challenge 时同样抛出 Verification，本机草稿绝不丢失
            val postVerifyEx = assertFailsWith<ApiReadException> {
                session.publish {
                    LinuxDoRepository.createPost(
                        topicId = 9901,
                        raw = draft.raw,
                        replyToPostNumber = 9,
                        draftKey = "topic_9901"
                    )
                }
            }
            assertEquals(ApiFailureKind.Verification, postVerifyEx.kind)
            assertEquals("需要完成站点验证", postVerifyEx.message)
            assertEquals("浏览量是最简单的了佬友。", LocalComposerDrafts.get("lucas", "topic_9901")?.raw)

            // 3) 用户在应用内置安全 WebView 完成站点验证后，恢复云端草稿同步与一键发送，成功后清理本机草稿
            challengeActive = false
            assertTrue(session.sync())
            val published = session.publish {
                LinuxDoRepository.createPost(
                    topicId = 9901,
                    raw = draft.raw,
                    replyToPostNumber = 9,
                    draftKey = "topic_9901"
                )
            }
            assertTrue(published is PostSubmission.Published)
            assertEquals(10, published.value.postNumber)
            assertNull(LocalComposerDrafts.get("lucas", "topic_9901"))
        }
    }

    @Test
    fun screenTrackAndTimingsPauseCleanlyOnSiteVerificationWithoutOneSecondRushStorm() = runTest {
        CookieSentinel.ingestRawCookieString("_t=valid_cookie", domain = "linux.do")
        LinuxDoRepository.updateCsrfToken("csrf_timings")
        var challengeActive = true
        var lastTimingsHeaders: Map<String, String> = emptyMap()
        withMockHttp(
            handler = { _, method, _, headers ->
                lastTimingsHeaders = headers
                if (method == "POST" && challengeActive) {
                    403 to "${CF_MITIGATED_CHALLENGE_PREFIX}<!DOCTYPE html><title>Just a moment...</title>"
                } else {
                    200 to "{}"
                }
            }
        ) {
            val t0 = 1_000_000L
            ScreenTrackEngine.onTopicEntered(topicId = 8801, initialVisiblePosts = setOf(1, 2), nowMs = t0)
            // 首次 1 秒阅读触发 hasUnreadRush 入队
            assertTrue(ScreenTrackEngine.tickAndEnqueue(nowMs = t0 + 1_200L))
            val firstBatch = ScreenTrackEngine.popNextSendableBatch(nowMs = t0 + 1_200L)
            assertNotNull(firstBatch)

            val status = LinuxDoRepository.sendTopicTimings(firstBatch)
            assertEquals(ScreenTrackEngine.STATUS_SITE_VERIFICATION_REQUIRED, status)
            assertTrue(LinuxDoRepository.isChallengeCooldownActive())
            assertEquals("https://linux.do/t/8801", lastTimingsHeaders["Referer"])
            assertEquals("true", lastTimingsHeaders["X-SILENCE-LOGGER"])

            ScreenTrackEngine.onBatchSendResult(firstBatch, status, nowMs = t0 + 1_200L)
            // 1) 在 90s 挂起窗口内，popNextSendableBatch 必须返回 null，绝不继续打 POST /topics/timings
            assertNull(ScreenTrackEngine.popNextSendableBatch(nowMs = t0 + 5_000L))
            // 2) 更关键：下一秒 tick 绝不能因为清空了 totalTimingsMs/readPostsSet 而陷入 1s hasUnreadRush 死循环！
            assertFalse(ScreenTrackEngine.tickAndEnqueue(nowMs = t0 + 2_300L))

            // 3) 用户完成内置网页安全验证并触发 prepareForManualRecovery 后，冷却解除并恢复正常上报
            challengeActive = false
            LinuxDoRepository.prepareForManualRecovery()
            assertFalse(LinuxDoRepository.isChallengeCooldownActive())
            val resumedBatch = ScreenTrackEngine.popNextSendableBatch(nowMs = t0 + 6_000L)
            assertNotNull(resumedBatch)
            val okStatus = LinuxDoRepository.sendTopicTimings(resumedBatch)
            assertEquals(200, okStatus)
            ScreenTrackEngine.onBatchSendResult(resumedBatch, okStatus, nowMs = t0 + 6_000L)
            assertFalse(ScreenTrackEngine.hasPendingWork())
        }
    }

    @Test
    fun postSubmission503ChallengeContextualRefererAndDiscardDraftProtection() = runTest {
        CookieSentinel.ingestRawCookieString("_t=valid_cookie", domain = "linux.do")
        LinuxDoRepository.updateCsrfToken("csrf_post")
        LocalComposerDrafts.remove("lucas", "topic_4321")
        LocalComposerDrafts.forgetSequence("lucas", "topic_4321")

        val observedHeadersByUrl = mutableMapOf<String, Map<String, String>>()
        withMockHttp(
            handler = { url, method, _, headers ->
                observedHeadersByUrl["$method $url"] = headers
                when {
                    method == "GET" && url.contains("latest.json") ->
                        200 to """{"topic_list":{"topics":[]}}"""
                    // Cloudflare Turnstile / JS Challenge 经常返回 HTTP 503 + HTML（甚至无 cf-mitigated 前缀）
                    method == "POST" && url.endsWith("/posts.json") ->
                        503 to "<!DOCTYPE html><html><head><title>Just a moment...</title></head><body><script src=\"/cdn-cgi/challenge-platform/scripts/jsd/main.js\"></script></body></html>"
                    method == "DELETE" && url.contains("/drafts/") ->
                        403 to "${CF_MITIGATED_CHALLENGE_PREFIX}<!DOCTYPE html><title>Just a moment...</title>"
                    else -> 200 to "{}"
                }
            }
        ) {
            // 后台普通 GET 不得携带非法的 X-SILENCE-LOGGER 或 Origin 请求头
            LinuxDoRepository.fetchTopics(path = "/latest.json", background = true)
            val getHeaders = observedHeadersByUrl.entries.first { it.key.startsWith("GET ") }.value
            assertNull(getHeaders["X-SILENCE-LOGGER"])
            assertNull(getHeaders["Origin"])
            assertEquals("application/json, text/javascript, */*; q=0.01", getHeaders["Accept"])

            // POST /posts.json 遭遇 503 Cloudflare Challenge 时必须抛出 ApiFailureKind.Verification，绝不能伪装成 PostSubmissionUnconfirmed
            val postEx = assertFailsWith<ApiReadException> {
                LinuxDoRepository.createPost(topicId = 4321, raw = "测试回复内容长度足够")
            }
            assertEquals(ApiFailureKind.Verification, postEx.kind)
            assertEquals("需要完成站点验证", postEx.message)
            val postHeaders = observedHeadersByUrl["POST https://linux.do/posts.json"]
            assertNotNull(postHeaders)
            assertEquals("https://linux.do/t/4321", postHeaders["Referer"])
            assertEquals("https://linux.do", postHeaders["Origin"])
            assertNull(postHeaders["X-SILENCE-LOGGER"])

            // 放弃草稿遭遇站点验证时必须抛出异常保留本机草稿，避免云端草稿未删却把本机草稿误删
            val draft = ComposerDraft(
                owner = "lucas",
                key = "topic_4321",
                action = ComposerAction.REPLY,
                topicId = 4321,
                raw = "未发送草稿",
                sequence = 2
            )
            val session = ComposerDraftSession(draft, initialExistsOnServer = true)
            session.update(draft)
            val discardEx = assertFailsWith<ApiReadException> {
                session.discard()
            }
            assertEquals(ApiFailureKind.Verification, discardEx.kind)
            assertNotNull(LocalComposerDrafts.get("lucas", "topic_4321"))
        }
    }

    @Test
    fun browserTransportOnlyGuardAndExpiredCfClearanceEvictionAndBooleanMutationReason() = runTest {
        // 1) CookieSentinel：当完整浏览器快照包含有效 _t 但不再包含过期 cf_clearance 时，必须剔除陈旧 cf_clearance
        CookieSentinel.ingestRawCookieString("_t=old_session; cf_clearance=stale_cf_token", domain = "linux.do")
        assertTrue(CookieSentinel.buildCookieHeader().contains("cf_clearance=stale_cf_token"))
        CookieSentinel.syncFromAuthoritativeBrowserCookieString("_t=fresh_session; _forum_session=sess_2")
        val cleaned = CookieSentinel.buildCookieHeader()
        assertTrue(cleaned.contains("_t=fresh_session"))
        assertFalse(cleaned.contains("cf_clearance"))

        // 2) Android 强制浏览器桥接锁开启后，严禁向非浏览器栈泄漏 cf_clearance，且桥接暂不可用时绝不回退 Java TLS OkHttp
        CookieSentinel.ingestRawCookieString("_t=fresh_session; cf_clearance=new_cf_token", domain = "linux.do")
        UserApiKeyAuthManager.requireBrowserTransportOnly = true
        assertFalse(CookieSentinel.buildCookieHeader().contains("cf_clearance"))
        UserApiKeyAuthManager.webViewHttpExecutor = null
        val fallbackBlockedEx = assertFailsWith<ApiReadException> {
            LinuxDoRepository.fetchTopics("latest.json")
        }
        assertEquals(ApiFailureKind.Connection, fallbackBlockedEx.kind)

        // 3) Boolean 写操作（如删帖/全部标为已读）遭遇 Cloudflare 验证时必须精确记录「需要完成站点验证」
        LinuxDoRepository.updateCsrfToken("csrf_bool")
        withMockHttp(
            handler = { _, _, _, _ ->
                403 to "${CF_MITIGATED_CHALLENGE_PREFIX}<!DOCTYPE html><title>Just a moment...</title>"
            }
        ) {
            assertFalse(LinuxDoRepository.deletePost(998877))
            assertEquals("需要完成站点验证", LinuxDoRepository.lastMutationErrorOr("删除失败"))
            assertFalse(LinuxDoRepository.markNotificationsRead())
            assertEquals("需要完成站点验证", LinuxDoRepository.lastMutationErrorOr("标记失败"))
        }
    }

    @Test
    fun guestModeUrlRoutingAndCategoryGuardPreventAbruptWebLoginJumps() {
        // 1) 所有未登录会 302 跳往 /login 的生态站与专属路径均返回结构化原生引导提示（含 ?web=1 参数场景）
        val connectPrompt = resolveGuestLoginPromptForUrl("https://connect.linux.do/?web=1")
        assertNotNull(connectPrompt)
        assertTrue(connectPrompt.title.contains("Connect"))

        assertNotNull(resolveGuestLoginPromptForUrl("https://credit.linux.do/"))
        assertNotNull(resolveGuestLoginPromptForUrl("https://cdk.linux.do/receive/abc"))
        assertNotNull(resolveGuestLoginPromptForUrl("https://hub.linux.do/"))
        assertNotNull(resolveGuestLoginPromptForUrl("https://lottery.linux.do/"))
        assertNotNull(resolveGuestLoginPromptForUrl("https://linux.do/login"))
        assertNotNull(resolveGuestLoginPromptForUrl("https://linux.do/new-topic?category=develop"))
        assertNotNull(resolveGuestLoginPromptForUrl("https://linux.do/chat/c/general/1"))
        assertNotNull(resolveGuestLoginPromptForUrl("https://linux.do/u/alice/follow"))
        assertNotNull(resolveGuestLoginPromptForUrl("https://linux.do/my/preferences/profile"))
        assertNotNull(resolveGuestLoginPromptForUrl("https://linux.do/my/messages"))
        assertNotNull(resolveGuestLoginPromptForUrl("https://linux.do/bookmarks"))

        // 2) 公开生态站、公开原生页面与公开话题链接在游客模式下保持畅通直达，绝不误拦截
        assertNull(resolveGuestLoginPromptForUrl("https://wiki.linux.do/"))
        assertNull(resolveGuestLoginPromptForUrl("https://idcflare.com/"))
        assertNull(resolveGuestLoginPromptForUrl("https://linux.do/t/topic/12345"))
        assertNull(resolveGuestLoginPromptForUrl("https://linux.do/badges"))
        assertNull(resolveGuestLoginPromptForUrl("https://linux.do/about"))
        assertNull(resolveGuestLoginPromptForUrl("https://linux.do/leaderboard"))
        assertNull(resolveGuestLoginPromptForUrl("https://github.com/linux-do"))

        // 3) 受限版块（如跳蚤市场、Lv1~Lv3 子版块）在游客模式下前置拦截引导登录，公开版块与已登录态正常放行
        val restrictedCat = LinuxDoCategoryMeta(
            id = 10,
            name = "跳蚤市场",
            slug = "trade",
            colorHex = "ED207B",
            description = "闲置流转",
            readRestricted = true
        )
        val publicCat = LinuxDoCategoryMeta(
            id = 4,
            name = "开发调优",
            slug = "develop",
            colorHex = "0088CC",
            description = "技术交流",
            readRestricted = false
        )
        assertTrue(shouldGateCategoryForGuest(isAuthorized = false, category = restrictedCat))
        assertFalse(shouldGateCategoryForGuest(isAuthorized = false, category = publicCat))
        assertFalse(shouldGateCategoryForGuest(isAuthorized = true, category = restrictedCat))

        // 4) 游客受限错误判定覆盖「登录 / 权限 / 无权 / 受限 / 私密」，但严禁把「站点验证」误判为登录引导
        assertTrue(isGuestLoginRequiredError(isAuthorized = false, errorMessage = "该内容需要登录后查看"))
        assertTrue(isGuestLoginRequiredError(isAuthorized = false, errorMessage = "您无权查看所请求的资源。"))
        assertTrue(isGuestLoginRequiredError(isAuthorized = false, errorMessage = "该版块需登录后访问"))
        assertFalse(isGuestLoginRequiredError(isAuthorized = false, errorMessage = "需要完成站点验证"))
        assertFalse(isGuestLoginRequiredError(isAuthorized = true, errorMessage = "该内容需要登录后查看"))
    }

    @Test
    fun guestMode401And403ResponsesSurfaceGuestLoginPromptInsteadOfFakeNetworkFailure() = runTest {
        // 确保处于纯游客模式（无 Cookie、无 API Key）
        UserApiKeyAuthManager.logout()
        CookieSentinel.clearAll()
        assertFalse(UserApiKeyAuthManager.isAuthorized())

        // 1) Discourse 中文站对未登录游客返回 403 invalid_access 时，readApiObject 归一化为友好的「该内容需要登录后查看」
        val guestForbiddenEx = assertFailsWith<ApiReadException> {
            readApiObject(
                403 to """{"errors":["您无权查看所请求的资源。"],"error_type":"invalid_access"}"""
            )
        }
        assertEquals(ApiFailureKind.PermissionDenied, guestForbiddenEx.kind)
        assertEquals("该内容需要登录后查看", guestForbiddenEx.message)

        // 2) 游客点开隐藏公开档案的用户名片（HTTP 403）或受限话题（HTTP 403）时，精确返回权限/登录提示而非伪造的「网络连接失败」
        withMockHttp(
            handler = { url, _, _, _ ->
                when {
                    url.contains("/u/hidden_member.json") ->
                        403 to """{"errors":["您无权查看所请求的资源。"],"error_type":"invalid_access"}"""
                    url.contains("/t/77777.json") ->
                        403 to """{"errors":["您无权查看所请求的资源。"],"error_type":"invalid_access"}"""
                    else -> 404 to "{}"
                }
            }
        ) {
            val cardEx = assertFailsWith<ApiReadException> {
                LinuxDoRepository.fetchPublicUserCard("hidden_member")
            }
            assertEquals(ApiFailureKind.PermissionDenied, cardEx.kind)
            assertEquals("该内容需要登录后查看", cardEx.message)

            val restrictedTopic = LinuxDoRepository.fetchTopicDetail(77777)
            assertEquals("该话题需要登录后查看", restrictedTopic.loadError)
            assertTrue(isGuestLoginRequiredError(isAuthorized = false, errorMessage = restrictedTopic.loadError))
        }

        // 3) 对照验证：已登录态遭遇 403 仍保持「当前账号暂无访问权限」语义，不混淆等级不足与未登录
        CookieSentinel.ingestRawCookieString("_t=logged_in_member", domain = "linux.do")
        assertTrue(UserApiKeyAuthManager.isAuthorized())
        val memberForbiddenEx = assertFailsWith<ApiReadException> {
            readApiObject(403 to "{}")
        }
        assertEquals(ApiFailureKind.PermissionDenied, memberForbiddenEx.kind)
        assertEquals("当前账号暂无访问权限", memberForbiddenEx.message)
    }
}
