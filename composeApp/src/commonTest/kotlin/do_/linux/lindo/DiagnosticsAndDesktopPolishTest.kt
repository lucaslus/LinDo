package do_.linux.lindo

import do_.linux.lindo.core.compose.LocalComposerDrafts
import do_.linux.lindo.core.copy.CloudDraftKind
import do_.linux.lindo.core.copy.DebugPreviewScene
import do_.linux.lindo.core.copy.DiscourseNotificationKind
import do_.linux.lindo.core.copy.DiscourseTrustLevel
import do_.linux.lindo.core.copy.DiscourseUserActionKind
import do_.linux.lindo.core.copy.LinDoAuthCopy
import do_.linux.lindo.core.copy.LinDoCommonCopy
import do_.linux.lindo.core.copy.LinDoComposerCopy
import do_.linux.lindo.core.copy.LinDoCopyEntry
import do_.linux.lindo.core.copy.LinDoFeedCopy
import do_.linux.lindo.core.copy.LinDoMessagesCopy
import do_.linux.lindo.core.copy.LinDoNetworkCopy
import do_.linux.lindo.core.copy.LinDoReaderCopy
import do_.linux.lindo.core.copy.NotificationCategoryFilter
import do_.linux.lindo.core.copy.ReaderFontScalePreset
import do_.linux.lindo.core.copy.SmallActionKind
import do_.linux.lindo.core.copy.SpecialUserGroup
import do_.linux.lindo.core.copy.TopicNotificationLevel
import do_.linux.lindo.core.copy.UiErrorKind
import do_.linux.lindo.core.diagnostics.DiagnosticCategory
import do_.linux.lindo.core.diagnostics.LinDoDiagnosticLogger
import do_.linux.lindo.core.network.ApiReadException
import do_.linux.lindo.core.network.AuthenticatedUserProfile
import do_.linux.lindo.core.network.CF_MITIGATED_CHALLENGE_PREFIX
import do_.linux.lindo.core.network.CookieSentinel
import do_.linux.lindo.core.network.LinuxDoRepository
import do_.linux.lindo.core.network.UserApiKeyAuthManager
import do_.linux.lindo.core.network.looksLikeCloudflareChallenge
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DiagnosticsAndDesktopPolishTest {

    private var savedHttpExecutor = UserApiKeyAuthManager.webViewHttpExecutor
    private var savedRequireBrowserOnly = UserApiKeyAuthManager.requireBrowserTransportOnly
    private var savedDraftPersist = LocalComposerDrafts.onPersist
    private var savedPersistSession = UserApiKeyAuthManager.onPersistSession
    private var savedUnreadCallback = UserApiKeyAuthManager.onUnreadCountChanged

    @BeforeTest
    fun setUp() {
        savedHttpExecutor = UserApiKeyAuthManager.webViewHttpExecutor
        savedRequireBrowserOnly = UserApiKeyAuthManager.requireBrowserTransportOnly
        savedDraftPersist = LocalComposerDrafts.onPersist
        savedPersistSession = UserApiKeyAuthManager.onPersistSession
        savedUnreadCallback = UserApiKeyAuthManager.onUnreadCountChanged
        UserApiKeyAuthManager.onPersistSession = null
        UserApiKeyAuthManager.onUnreadCountChanged = null
        UserApiKeyAuthManager.requireBrowserTransportOnly = true
        LocalComposerDrafts.onPersist = null
        LinDoDiagnosticLogger.resetForTest()
    }

    @AfterTest
    fun tearDown() {
        UserApiKeyAuthManager.webViewHttpExecutor = savedHttpExecutor
        UserApiKeyAuthManager.requireBrowserTransportOnly = savedRequireBrowserOnly
        UserApiKeyAuthManager.onUnreadCountChanged = savedUnreadCallback
        LocalComposerDrafts.onPersist = savedDraftPersist
        UserApiKeyAuthManager.onPersistSession = null
        UserApiKeyAuthManager.logout()
        CookieSentinel.clearAll()
        LinuxDoRepository.clearAllLocalCaches()
        LinDoDiagnosticLogger.resetForTest()
        UserApiKeyAuthManager.onPersistSession = savedPersistSession
    }

    @Test
    fun diagnosticLoggerRedactsAllSecretsPersistsAcrossRestartAndClearsCleanly() {
        val tempDir = Files.createTempDirectory("lindo_diag_test").toFile()
        try {
            LinDoDiagnosticLogger.configurePersistence(tempDir, platformInfo = "TestOS 1.0")
            LinDoDiagnosticLogger.recordHttpFailure(
                method = "GET",
                rawUrl = "https://linux.do/t/999.json?api_key=super_secret_query&payload=encrypted_otp_blob",
                statusCode = 503,
                transport = "OkHttp",
                durationMs = 412L,
                isChallenge = true,
                cfRay = "8f12ab34cd-SJC",
                bodyPreview = "Cookie: _t=my_live_discourse_token; cf_clearance=my_cf_secret; User-Api-Key: secret_header_key; X-CSRF-Token: secret_csrf_val; Bearer jwt_token_xyz"
            )
            LinDoDiagnosticLogger.recordUncaughtCrash(
                Thread("test-worker"),
                IllegalStateException("Simulated crash with _t=leak_attempt_123 and User-Api-Key: key_leak_456")
            )

            val report = LinDoDiagnosticLogger.buildExportReport()
            assertTrue(report.contains("Lindo Diagnostic & Crash Log Report"))
            assertTrue(report.contains("Version: Lindo v0.2.32"))
            assertTrue(report.contains("cfRay=8f12ab34cd-SJC"))
            assertTrue(report.contains("IllegalStateException"))

            // Verify zero secret leakage
            assertFalse(report.contains("super_secret_query"))
            assertFalse(report.contains("encrypted_otp_blob"))
            assertFalse(report.contains("my_live_discourse_token"))
            assertFalse(report.contains("my_cf_secret"))
            assertFalse(report.contains("secret_header_key"))
            assertFalse(report.contains("secret_csrf_val"))
            assertFalse(report.contains("jwt_token_xyz"))
            assertFalse(report.contains("leak_attempt_123"))
            assertFalse(report.contains("key_leak_456"))

            // Verify disk persistence survives fresh re-init
            assertTrue(LinDoDiagnosticLogger.persistedFileSizeBytes() > 0L)
            LinDoDiagnosticLogger.resetForTest()
            assertEquals(0, LinDoDiagnosticLogger.entryCount())

            LinDoDiagnosticLogger.configurePersistence(tempDir, platformInfo = "TestOS 1.0")
            assertTrue(LinDoDiagnosticLogger.entryCount() >= 2)
            assertTrue(
                LinDoDiagnosticLogger.snapshot(DiagnosticCategory.CRASH)
                    .any { it.detail?.contains("IllegalStateException") == true }
            )

            // Verify clearAllLogs wipes both memory and disk files
            LinDoDiagnosticLogger.clearAllLogs()
            assertEquals(0, LinDoDiagnosticLogger.entryCount())
            assertEquals(0L, LinDoDiagnosticLogger.persistedFileSizeBytes())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun cloudflare503AndLocalizedChallengeMapToSiteVerificationInsteadOfServiceUnavailable() = runTest {
        assertTrue(looksLikeCloudflareChallenge("<html><head><title>请稍候…</title></head><body>Enable JavaScript and cookies to continue</body></html>"))
        assertTrue(looksLikeCloudflareChallenge("${CF_MITIGATED_CHALLENGE_PREFIX}{\"errors\":[\"Challenge\"]}"))

        UserApiKeyAuthManager.webViewHttpExecutor = { url, _, _, _ ->
            if (url.contains("/t/778899.json")) {
                503 to "${CF_MITIGATED_CHALLENGE_PREFIX}<html><head><title>请稍候...</title></head></html>"
            } else {
                200 to "{}"
            }
        }

        val detail = LinuxDoRepository.fetchTopicDetail(778899)
        assertNotNull(detail)
        assertEquals(LinDoNetworkCopy.SITE_VERIFICATION_REQUIRED.text, detail.loadError)
        assertFalse(detail.unavailable)
    }

    @Test
    fun fetchTopicDetailRetriesWithoutTrackVisitAfterTransient502AndRecovers() = runTest {
        val seenUrls = CopyOnWriteArrayList<String>()
        UserApiKeyAuthManager.webViewHttpExecutor = { url, _, _, _ ->
            seenUrls.add(url)
            if (url.contains("/t/665544.json")) {
                if (url.contains("track_visit=true")) {
                    502 to "<html><body>502 Bad Gateway</body></html>"
                } else {
                    200 to """
                        {
                          "id": 665544,
                          "title": "Claude的重置才是真正的重置啊",
                          "posts_count": 1,
                          "views": 101,
                          "post_stream": {
                            "stream": [1001],
                            "posts": [
                              {
                                "id": 1001,
                                "post_number": 1,
                                "username": "JJJJ",
                                "cooked": "<p>正文成功恢复加载</p>",
                                "created_at": "2026-11-12T09:00:00Z"
                              }
                            ]
                          }
                        }
                    """.trimIndent()
                }
            } else {
                200 to "{}"
            }
        }

        val detail = LinuxDoRepository.fetchTopicDetail(665544)
        assertNotNull(detail)
        assertEquals(null, detail.loadError)
        assertEquals(1, detail.posts.size)
        assertTrue(seenUrls.first().contains("track_visit=true"))
        assertTrue(seenUrls.size >= 2 && !seenUrls.last().contains("track_visit=true"))
    }

    @Test
    fun searchTopicsForLinkPickerUsesSearchQueryEndpointAndSurfacesErrorsInsteadOfFalseEmpty() = runTest {
        val requestedUrls = CopyOnWriteArrayList<String>()
        UserApiKeyAuthManager.webViewHttpExecutor = { url, _, _, _ ->
            requestedUrls.add(url)
            when {
                url.contains("/search/query.json") -> 200 to """
                    {
                      "topics": [
                        {
                          "id": 321001,
                          "title": "Google Gemini 2.5 Pro 深度体验汇总",
                          "posts_count": 42,
                          "like_count": 88,
                          "category_id": 4
                        }
                      ],
                      "posts": [
                        {
                          "id": 9001,
                          "topic_id": 321001,
                          "post_number": 1,
                          "username": "google_fan",
                          "blurb": "Google 最新模型实测对比..."
                        }
                      ]
                    }
                """.trimIndent()
                else -> 200 to "{}"
            }
        }

        val hits = LinuxDoRepository.searchTopicsForLinkPicker("Google")
        assertTrue(requestedUrls.any { it.contains("/search/query.json?term=Google") })
        assertEquals(1, hits.size)
        assertEquals(321001, hits.first().topicId)
        assertTrue(hits.first().title.contains("Google"))

        // When Cloudflare challenges search, searchTopicsForLinkPicker must throw ApiReadException
        // so the UI shows error + retry/verify instead of falsely claiming "未找到匹配的帖子"
        CookieSentinel.clearAll()
        LinuxDoRepository.clearAllLocalCaches()
        UserApiKeyAuthManager.webViewHttpExecutor = { _, _, _, _ ->
            503 to "${CF_MITIGATED_CHALLENGE_PREFIX}<html><title>Just a moment...</title></html>"
        }
        val ex = assertFailsWith<ApiReadException> {
            LinuxDoRepository.searchTopicsForLinkPicker("GoogleUniqQuery")
        }
        assertTrue(ex.message.orEmpty().contains("站点验证"))
    }

    @Test
    fun globalCopyEnumsAndDomainStateEnumsMaintainStrictIntegrityWithoutEnumNameLeaks() {
        val allCopyBanks: List<List<LinDoCopyEntry>> = listOf(
            LinDoCommonCopy.entries,
            LinDoAuthCopy.entries,
            LinDoNetworkCopy.entries,
            LinDoFeedCopy.entries,
            LinDoReaderCopy.entries,
            LinDoComposerCopy.entries,
            LinDoMessagesCopy.entries
        )
        allCopyBanks.flatten().forEach { entry ->
            assertTrue(entry.text.isNotEmpty(), "Copy enum $entry must not be empty")
            assertEquals(entry.text, entry.toString(), "Copy enum toString() must return text rather than ENUM_NAME")
            val formatted = entry.format("参数0", "参数1", "参数2")
            assertFalse(formatted.contains("{0}") || formatted.contains("{1}") || formatted.contains("{2}"))
        }

        assertEquals(UiErrorKind.SITE_VERIFICATION, UiErrorKind.classify("需要完成站点验证", isAuthorized = true))
        assertEquals(UiErrorKind.GUEST_LOGIN_REQUIRED, UiErrorKind.classify("该内容需要登录后查看", isAuthorized = false))
        assertEquals(UiErrorKind.AUTH_OR_PERMISSION, UiErrorKind.classify("当前信任等级暂无权访问该版块", isAuthorized = true))
        assertEquals(UiErrorKind.TRANSIENT_RECOVERABLE, UiErrorKind.classify("服务暂时不可用，请稍后重试", isAuthorized = true))

        assertEquals(CloudDraftKind.PRIVATE_MESSAGE, CloudDraftKind.resolve("privateMessage", "new_private_message"))
        assertEquals(CloudDraftKind.EDIT_POST, CloudDraftKind.resolve("edit", "edit_post_42"))
        assertEquals(CloudDraftKind.NEW_TOPIC, CloudDraftKind.resolve("createTopic", "new_topic"))
        assertEquals(CloudDraftKind.TOPIC_REPLY, CloudDraftKind.resolve("reply", "topic_999"))

        assertEquals(TopicNotificationLevel.WATCHING, TopicNotificationLevel.REGULAR.nextQuickToggle())
        assertEquals(TopicNotificationLevel.MUTED, TopicNotificationLevel.WATCHING.nextQuickToggle())
        assertEquals(TopicNotificationLevel.REGULAR, TopicNotificationLevel.MUTED.nextQuickToggle())

        assertEquals(NotificationCategoryFilter.REPLY, DiscourseNotificationKind.fromCode(2).category)
        assertTrue(DiscourseNotificationKind.fromCode(29).isChat)
        assertTrue(DiscourseNotificationKind.fromCode(16).routesToPrivateMessages(topicId = 0))
        assertEquals("送出赞", DiscourseUserActionKind.fromCode(1).copy.text)
        assertEquals("Lv.3 活跃会员", DiscourseTrustLevel.displayLabel(3))
        assertEquals("已关闭此话题", SmallActionKind.localize("closed.enabled"))
        assertTrue(SpecialUserGroup.fromGroupName("g-merchant2").usesMerchantBadgeStyle)
        assertEquals(ReaderFontScalePreset.SMALL, ReaderFontScalePreset.fromScale(0.85f))
        assertEquals(DebugPreviewScene.NOTIFICATIONS, DebugPreviewScene.fromKey("notifications"))
    }

    @Test
    fun realTimeNotificationSyncDetectsUnreadRepliesEvenWhenSessionUnreadCounterIsZero() = runTest {
        UserApiKeyAuthManager.onSessionAuthenticated(
            profile = AuthenticatedUserProfile(
                userId = 888,
                username = "lucas_dev",
                displayName = "Lucas",
                trustLevel = 3
            ),
            rawCookies = "_t=live_session_cookie"
        )

        val observedCallbacks = CopyOnWriteArrayList<Int>()
        UserApiKeyAuthManager.onUnreadCountChanged = { count ->
            observedCallbacks.add(count)
        }

        // Simulate Discourse where low-priority `unread_notifications` is 0 (because seen_notification_id advanced
        // or notification is a high-priority reply), while `/notifications.json?silent=true` has 2 unread replies!
        UserApiKeyAuthManager.webViewHttpExecutor = { url, _, _, _ ->
            when {
                url.contains("/session/current.json") -> 200 to """
                    {
                      "current_user": {
                        "id": 888,
                        "username": "lucas_dev",
                        "name": "Lucas",
                        "trust_level": 3,
                        "unread_notifications": 0,
                        "unread_high_priority_notifications": 1
                      }
                    }
                """.trimIndent()
                url.contains("/u/lucas_dev.json") -> 200 to """{"user":{"id":888,"username":"lucas_dev","trust_level":3}}"""
                url.contains("/u/lucas_dev/summary.json") -> 200 to """{"user_summary":{"days_visited":30}}"""
                url.contains("/notifications.json") -> 200 to """
                    {
                      "notifications": [
                        {
                          "id": 9101,
                          "notification_type": 2,
                          "read": false,
                          "created_at": "2026-11-12T10:20:00Z",
                          "topic_id": 7001,
                          "post_number": 5,
                          "data": {"topic_title": "Lindo 桌面高刷优化讨论", "display_username": "neo", "excerpt": "回复了你的问题"}
                        },
                        {
                          "id": 9102,
                          "notification_type": 1,
                          "read": false,
                          "created_at": "2026-11-12T10:21:00Z",
                          "topic_id": 7002,
                          "post_number": 3,
                          "data": {"topic_title": "全局枚举重构心得", "display_username": "trinity", "excerpt": "@lucas_dev 来看看"}
                        },
                        {
                          "id": 9103,
                          "notification_type": 5,
                          "read": true,
                          "created_at": "2026-11-12T09:00:00Z",
                          "topic_id": 7000,
                          "post_number": 1,
                          "data": {"topic_title": "历史已读通知", "display_username": "morpheus"}
                        }
                      ]
                    }
                """.trimIndent()
                else -> 200 to "{}"
            }
        }

        val profile = LinuxDoRepository.fetchCurrentUserProfile()
        assertNotNull(profile)
        assertEquals(2, profile.unreadNotifications, "Must reflect all 2 unread reply/mention notifications immediately")
        assertEquals(2, UserApiKeyAuthManager.cachedUnreadNotificationCount)
        assertTrue(observedCallbacks.contains(2))
        assertEquals(3, LinuxDoRepository.getCachedRecentNotifications("lucas_dev").size)

        // Now simulate a 3rd new reply arriving during foreground heartbeat polling (`refreshUnreadNotifications`)
        UserApiKeyAuthManager.webViewHttpExecutor = { url, _, _, _ ->
            if (url.contains("/notifications.json")) {
                200 to """
                    {
                      "notifications": [
                        {"id": 9104, "notification_type": 2, "read": false, "created_at": "2026-11-12T10:25:00Z", "topic_id": 7003, "post_number": 8, "data": {"topic_title": "新回复到达", "display_username": "oracle"}},
                        {"id": 9101, "notification_type": 2, "read": false, "created_at": "2026-11-12T10:20:00Z", "topic_id": 7001, "post_number": 5, "data": {"topic_title": "Lindo 桌面高刷优化讨论", "display_username": "neo"}},
                        {"id": 9102, "notification_type": 1, "read": false, "created_at": "2026-11-12T10:21:00Z", "topic_id": 7002, "post_number": 3, "data": {"topic_title": "全局枚举重构心得", "display_username": "trinity"}}
                      ]
                    }
                """.trimIndent()
            } else {
                200 to "{}"
            }
        }

        val liveRefreshed = LinuxDoRepository.refreshUnreadNotifications(background = true)
        assertEquals(3, liveRefreshed)
        assertEquals(3, UserApiKeyAuthManager.cachedUnreadNotificationCount)
        assertEquals(3, observedCallbacks.last())
    }
}
