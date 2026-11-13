package do_.linux.lindo.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import do_.linux.lindo.DesktopSmoothWheelEngine
import do_.linux.lindo.core.compose.ComposerAction
import do_.linux.lindo.core.compose.LocalComposerDrafts
import do_.linux.lindo.core.connect.UserPresenceService
import do_.linux.lindo.core.diagnostics.LinDoDiagnosticLogger
import do_.linux.lindo.core.model.PostItemModel
import do_.linux.lindo.core.model.TopicDetailModel
import do_.linux.lindo.core.model.TopicSummary
import do_.linux.lindo.core.model.TopicUser
import do_.linux.lindo.core.network.AuthenticatedUserProfile
import do_.linux.lindo.core.network.CookieSentinel
import do_.linux.lindo.core.network.LinuxDoRepository
import do_.linux.lindo.core.network.UserApiKeyAuthManager
import kotlinx.coroutines.delay
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DesktopPolishV0232RegressionTest {

    private var savedPersistSession = UserApiKeyAuthManager.onPersistSession

    private fun <T> onEdt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    @Composable
    private fun TestThemeHost(content: @Composable () -> Unit) {
        val palette = buildLinDoPalette(isDark = false, option = BrandThemeOption.OFFICIAL_GOLD)
        CompositionLocalProvider(
            LocalPalette provides palette,
            LocalLinDoDarkTheme provides false,
            LocalLinDoAnimations provides false
        ) {
            MaterialTheme(colorScheme = buildMaterialSchemeFromPalette(palette)) {
                content()
            }
        }
    }

    @BeforeTest
    fun setUp() {
        savedPersistSession = UserApiKeyAuthManager.onPersistSession
        UserApiKeyAuthManager.onPersistSession = null
        LinDoDiagnosticLogger.resetForTest()
        LocalComposerDrafts.onPersist = null
        LocalComposerDrafts.clearAll()
        UserPresenceService.setAppForeground(true)
        UserApiKeyAuthManager.requireBrowserTransportOnly = true
        UserApiKeyAuthManager.onSessionAuthenticated(
            profile = AuthenticatedUserProfile(
                userId = 888,
                username = "lucas_dev",
                displayName = "Lucas",
                avatarTemplate = "/user_avatar/linux.do/lucas_dev/{size}/1_2.png",
                trustLevel = 3
            ),
            rawCookies = "_t=test_desktop_v0232_session"
        )
    }

    @AfterTest
    fun tearDown() {
        UserApiKeyAuthManager.webViewHttpExecutor = null
        UserApiKeyAuthManager.requireBrowserTransportOnly = false
        UserApiKeyAuthManager.onPersistSession = null
        UserApiKeyAuthManager.logout()
        CookieSentinel.clearAll()
        LinuxDoRepository.clearAllLocalCaches()
        LocalComposerDrafts.clearAll()
        LinDoDiagnosticLogger.resetForTest()
        UserApiKeyAuthManager.onPersistSession = savedPersistSession
    }

    @Test
    fun desktopWheelEngineNeverHijacksAppleTrackpadOrWindowsPrecisionTouchpad() {
        // Apple Trackpad / Magic Mouse continuous scroll frames (even fast swipes where rawInt >= 1)
        assertFalse(
            DesktopSmoothWheelEngine.isDiscreteMouseWheelNotch(
                rawInt = 0, rawPrecise = 0.04, dtMs = 8L, trackpadActive = false, isMacOs = true
            )
        )
        assertFalse(
            DesktopSmoothWheelEngine.isDiscreteMouseWheelNotch(
                rawInt = 1, rawPrecise = 0.68, dtMs = 50L, trackpadActive = false, isMacOs = true
            )
        )
        assertFalse(
            DesktopSmoothWheelEngine.isDiscreteMouseWheelNotch(
                rawInt = 2, rawPrecise = 1.47, dtMs = 8L, trackpadActive = true, isMacOs = true
            )
        )

        // Physical notched mouse wheel on macOS (CPlatformResponder SCROLL_PHASE_UNSUPPORTED: rawPrecise == rawInt * 0.1)
        assertTrue(
            DesktopSmoothWheelEngine.isDiscreteMouseWheelNotch(
                rawInt = 1, rawPrecise = 0.1, dtMs = 30L, trackpadActive = false, isMacOs = true
            )
        )
        assertTrue(
            DesktopSmoothWheelEngine.isDiscreteMouseWheelNotch(
                rawInt = -2, rawPrecise = -0.2, dtMs = 25L, trackpadActive = false, isMacOs = true
            )
        )

        // Windows Precision Touchpad fractional steps pass straight through
        assertFalse(
            DesktopSmoothWheelEngine.isDiscreteMouseWheelNotch(
                rawInt = 0, rawPrecise = 0.15, dtMs = 8L, trackpadActive = false, isMacOs = false
            )
        )
        assertFalse(
            DesktopSmoothWheelEngine.isDiscreteMouseWheelNotch(
                rawInt = 1, rawPrecise = 1.35, dtMs = 20L, trackpadActive = false, isMacOs = false
            )
        )

        // Physical notched mouse wheel on Windows
        assertTrue(
            DesktopSmoothWheelEngine.isDiscreteMouseWheelNotch(
                rawInt = 1, rawPrecise = 1.0, dtMs = 25L, trackpadActive = false, isMacOs = false
            )
        )
    }

    @Test
    fun replyComposerOpensInstantlyZeroMsWithoutBlockingOnSlowCloudDraftFetch() = runDesktopComposeUiTest(
        width = 1100,
        height = 760
    ) {
        val draftRequestStarted = AtomicBoolean(false)
        UserApiKeyAuthManager.webViewHttpExecutor = { url, _, _, _ ->
            if (url.contains("/drafts/topic_12345.json")) {
                draftRequestStarted.set(true)
                delay(1_800L)
                200 to """{"draft":null,"draft_sequence":4}"""
            } else {
                200 to "{}"
            }
        }

        setContent {
            TestThemeHost {
                ComposerSheet(
                    action = ComposerAction.REPLY,
                    topicId = 12345,
                    topicTitle = "chatgpt响应特别慢",
                    onDismiss = {},
                    onPublished = {}
                )
            }
        }

        // Must render editor immediately on Frame 0 WITHOUT showing blocking "加载中"!
        onEdt { onAllNodesWithText("加载中", substring = true).assertCountEquals(0) }
        onEdt { onNodeWithText("写下回复", substring = true).assertIsDisplayed() }

        // User can start typing immediately while remote draft request is still in-flight, and remote completion must NOT overwrite user input!
        onEdt { onAllNodes(hasSetTextAction()).onFirst().performTextInput("秒开回复框不卡顿！") }
        waitUntil(timeoutMillis = 3_000L) { draftRequestStarted.get() }
        onEdt { onNodeWithText("秒开回复框不卡顿！", substring = true).assertIsDisplayed() }
    }

    @Test
    fun composerInsertLinkTopicSearchFindsGoogleTopicsAndShowsExplicitRecoveryOnFailure() = runDesktopComposeUiTest(
        width = 1100,
        height = 780
    ) {
        var failSearch = false
        UserApiKeyAuthManager.webViewHttpExecutor = { url, _, _, _ ->
            when {
                url.contains("/drafts/") -> 200 to """{"draft":null,"draft_sequence":1}"""
                url.contains("/latest.json") -> 200 to """{"topic_list":{"topics":[]},"users":[]}"""
                url.contains("/search/query.json") -> {
                    if (failSearch) {
                        503 to "Service Unavailable"
                    } else {
                        200 to """
                            {
                              "topics": [
                                {"id": 908070, "title": "Google AI Studio 最新免费额度实测", "posts_count": 18, "like_count": 30, "category_id": 4}
                              ],
                              "posts": [
                                {"id": 11, "topic_id": 908070, "post_number": 1, "username": "alice", "blurb": "Google API 实测"}
                              ]
                            }
                        """.trimIndent()
                    }
                }
                else -> 200 to "{}"
            }
        }

        setContent {
            TestThemeHost {
                ComposerSheet(
                    action = ComposerAction.REPLY,
                    topicId = 12345,
                    topicTitle = "chatgpt响应特别慢",
                    onDismiss = {},
                    onPublished = {}
                )
            }
        }
        waitForIdle()

        // Open 插入链接 dialog via toolbar contentDescription "插入链接" (or "链接")
        onEdt { onNodeWithContentDescription("链接").performClick() }
        waitUntil(timeoutMillis = 3_000L) {
            onEdt { onAllNodesWithText("引用帖子").fetchSemanticsNodes().isNotEmpty() }
        }

        // Type "Google" into the search box inside 插入链接
        onEdt { onAllNodes(hasSetTextAction()).onLast().performTextInput("Google") }
        waitUntil(timeoutMillis = 3_000L) {
            onEdt {
                onAllNodesWithText("Google AI Studio 最新免费额度实测", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
        }

        // Click the search result to insert markdown link snippet into composer
        onEdt { onNodeWithText("Google AI Studio 最新免费额度实测").performClick() }
        waitUntil(timeoutMillis = 3_000L) {
            onEdt {
                onAllNodesWithText("[Google AI Studio 最新免费额度实测](https://linux.do/t/topic/908070)", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
        }

        // Now verify network failure surfaces explicit error + "重试" instead of misleading "未找到匹配的帖子"
        failSearch = true
        LinuxDoRepository.clearAllLocalCaches()
        onEdt { onNodeWithContentDescription("链接").performClick() }
        waitForIdle()
        onEdt { onAllNodes(hasSetTextAction()).onLast().performTextInput("GoogleFailCase") }
        waitUntil(timeoutMillis = 3_000L) {
            onEdt { onAllNodesWithText("重试").fetchSemanticsNodes().isNotEmpty() }
        }
        onEdt { onAllNodesWithText("未找到匹配的帖子").assertCountEquals(0) }
    }

    @Test
    fun selectingPostTextShowsFloatingQuoteAndReplyPillAndInsertsQuoteMarkup() = runDesktopComposeUiTest(
        width = 1180,
        height = 800
    ) {
        UserApiKeyAuthManager.webViewHttpExecutor = { url, _, _, _ ->
            when {
                url.contains("/drafts/") -> 200 to """{"draft":null,"draft_sequence":1}"""
                else -> 200 to "{}"
            }
        }

        val summary = TopicSummary(
            id = 556677,
            title = "屏幕维修经验交流",
            postsCount = 2,
            replyCount = 1,
            views = 320,
            likeCount = 12,
            lastPostedAt = "22m",
            categoryId = 4,
            categoryName = "搞七捻三",
            categoryColorHex = "0088CC",
            originalPoster = TopicUser(id = 1, username = "op_user", name = "OP")
        )
        val detail = TopicDetailModel(
            id = 556677,
            title = "屏幕维修经验交流",
            categoryId = 4,
            categoryName = "搞七捻三",
            views = 320,
            postsCount = 2,
            streamIds = listOf(101, 102),
            posts = listOf(
                PostItemModel(
                    id = 101,
                    postNumber = 1,
                    userId = 10,
                    username = "tametame",
                    displayName = "tametame",
                    avatarTemplate = "",
                    createdAtText = "23m",
                    cookedHtml = "<p>在淘宝找个店铺换只要八百而且和原装没什么区别</p>",
                    likeCount = 5,
                    isRead = true
                )
            )
        )

        setContent {
            TestThemeHost {
                CommunityTopicReaderPane(
                    summary = summary,
                    detail = detail,
                    isAuthorized = true,
                    isLoading = false,
                    enableAnimations = false,
                    showBackButton = false,
                    onBack = {},
                    onRefresh = {},
                    onUnavailable = {},
                    onDetailUpdated = {},
                    onInspectBadge = {},
                    onInspectUser = {},
                    onSelectTag = {},
                    onOpenWebUrl = {},
                    onRequestLogin = {}
                )
            }
        }
        waitUntil(timeoutMillis = 3_000L) {
            onEdt {
                onAllNodesWithText("在淘宝找个店铺换只要八百", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
        }

        // Mouse drag-select across the post text to trigger floating "引用并回复" pill
        onEdt {
            onNodeWithText("在淘宝找个店铺换只要八百", substring = true).performMouseInput {
                moveTo(Offset(12f, centerY))
                press()
                moveTo(Offset(right - 12f, centerY))
                release()
            }
        }
        waitUntil(timeoutMillis = 3_000L) {
            onEdt { onAllNodesWithTag("quote_and_reply_pill").fetchSemanticsNodes().isNotEmpty() }
        }
        onEdt { onNodeWithText("引用并回复").performClick() }
        waitForIdle()

        // Reply composer opens immediately with Discourse [quote="tametame, post:1, topic:556677"] markup!
        waitUntil(timeoutMillis = 3_000L) {
            onEdt {
                onAllNodesWithText("[quote=\"tametame, post:1, topic:556677\"]", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    @Test
    fun coldStartImmediatelyDisplaysUnreadNotificationBadgeBeforeOpeningMessagesTab() = runDesktopComposeUiTest(
        width = 1220,
        height = 820
    ) {
        val reportedUnreadBadge = AtomicInteger(-1)
        UserApiKeyAuthManager.webViewHttpExecutor = { url, _, _, _ ->
            when {
                url.contains("/session/current.json") -> 200 to """
                    {
                      "current_user": {
                        "id": 888,
                        "username": "lucas_dev",
                        "name": "Lucas",
                        "trust_level": 3,
                        "unread_notifications": 0
                      }
                    }
                """.trimIndent()
                url.contains("/u/lucas_dev.json") -> 200 to """{"user":{"id":888,"username":"lucas_dev","trust_level":3}}"""
                url.contains("/u/lucas_dev/summary.json") -> 200 to """{"user_summary":{"days_visited":12}}"""
                url.contains("/latest.json") -> 200 to """{"topic_list":{"topics":[]},"users":[]}"""
                url.contains("/site.json") -> 200 to """{"categories":[]}"""
                url.contains("/notifications.json") -> 200 to """
                    {
                      "notifications": [
                        {
                          "id": 8001,
                          "notification_type": 2,
                          "read": false,
                          "created_at": "2026-11-12T10:30:00Z",
                          "topic_id": 501,
                          "post_number": 4,
                          "data": {"topic_title": "谁谁回复了我测试帖", "display_username": "alice_reply", "excerpt": "我也遇到了这个问题"}
                        },
                        {
                          "id": 8002,
                          "notification_type": 2,
                          "read": false,
                          "created_at": "2026-11-12T10:31:00Z",
                          "topic_id": 502,
                          "post_number": 7,
                          "data": {"topic_title": "通知实时性优化验证", "display_username": "bob_reply", "excerpt": "角标现在秒出了"}
                        },
                        {
                          "id": 8003,
                          "notification_type": 1,
                          "read": false,
                          "created_at": "2026-11-12T10:32:00Z",
                          "topic_id": 503,
                          "post_number": 2,
                          "data": {"topic_title": "提及角标测试", "display_username": "carol_mention", "excerpt": "@lucas_dev"}
                        }
                      ]
                    }
                """.trimIndent()
                else -> 200 to "{}"
            }
        }

        setContent {
            LinDoApp(
                systemAnimationsEnabled = false,
                onUnreadStateChanged = { count, _ ->
                    reportedUnreadBadge.set(count)
                }
            )
        }

        // WITHOUT opening the "消息" tab, cold start on Home MUST detect all 3 unread replies/mentions and show badge "3"!
        waitUntil(timeoutMillis = 4_000L) {
            reportedUnreadBadge.get() == 3 &&
                onEdt { onAllNodesWithText("3").fetchSemanticsNodes().isNotEmpty() }
        }
        assertEquals(3, reportedUnreadBadge.get())
    }

    @Test
    fun sevenConsecutiveVersionTapsUnlockDebugDiagnosticsAndClearCacheAlsoCleansLogs() = runDesktopComposeUiTest(
        width = 1180,
        height = 820
    ) {
        val tempLogDir = Files.createTempDirectory("lindo_ui_diag_test").toFile()
        var exportedText: String? = null
        try {
            LinDoDiagnosticLogger.configurePersistence(tempLogDir, platformInfo = "macOS 15.1 arm64")
            LinDoDiagnosticLogger.exportSaveHandler = { _, content, onResult ->
                exportedText = content
                onResult(true, "已导出测试日志")
            }
            LinDoDiagnosticLogger.recordHttpFailure(
                method = "GET",
                rawUrl = "https://linux.do/t/889900.json",
                statusCode = 503,
                transport = "OkHttp",
                durationMs = 280L,
                isChallenge = true,
                cfRay = "test-ray-999",
                bodyPreview = "Just a moment..."
            )

            UserApiKeyAuthManager.webViewHttpExecutor = { url, _, _, _ ->
                when {
                    url.contains("/session/current.json") -> 200 to """{"current_user":{"id":888,"username":"lucas_dev","trust_level":3,"unread_notifications":0}}"""
                    url.contains("/u/lucas_dev.json") -> 200 to """{"user":{"id":888,"username":"lucas_dev","trust_level":3}}"""
                    url.contains("/u/lucas_dev/summary.json") -> 200 to """{"user_summary":{}}"""
                    url.contains("/notifications.json") -> 200 to """{"notifications":[]}"""
                    url.contains("/latest.json") -> 200 to """{"topic_list":{"topics":[]},"users":[]}"""
                    url.contains("/site.json") -> 200 to """{"categories":[]}"""
                    url.contains("/about.json") -> 503 to "Service Unavailable"
                    else -> 200 to "{}"
                }
            }

            setContent {
                LinDoApp(systemAnimationsEnabled = false)
            }
            waitForIdle()

            // Navigate to Profile ("我的")
            onEdt { onNodeWithText("我的").performClick() }
            waitForIdle()

            // Open "关于社区" — even though /about.json fails with 503, top bar version trigger works offline!
            onEdt {
                onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToIndex(2)
            }
            waitForIdle()
            onEdt { onNodeWithText("关于社区").performClick() }
            waitForIdle()

            repeat(7) {
                onEdt { onNodeWithTag("about_version_debug_trigger").performClick() }
            }
            waitUntil(timeoutMillis = 3_000L) {
                onEdt { onAllNodesWithText("诊断与崩溃日志").fetchSemanticsNodes().isNotEmpty() }
            }
            onEdt { onNodeWithText("889900.json", substring = true).assertIsDisplayed() }

            // Export .log file via platform save handler
            onEdt { onNodeWithText("导出 .log 文件").performClick() }
            waitUntil(timeoutMillis = 3_000L) { exportedText != null }
            assertNotNull(exportedText)
            assertTrue(exportedText!!.contains("test-ray-999"))

            // Close Debug sheet & About sheet via BackNavigationBridge
            runOnIdle { assertTrue(BackNavigationBridge.dispatchBack()) }
            waitForIdle()
            runOnIdle { assertTrue(BackNavigationBridge.dispatchBack()) }
            waitForIdle()

            // Click "清理本地缓存" -> choose "同时清理缓存与诊断日志" -> verify logs wiped to 0
            onEdt { onNodeWithText("清理本地缓存").performClick() }
            waitUntil(timeoutMillis = 3_000L) {
                onEdt { onAllNodesWithText("同时清理缓存与诊断日志").fetchSemanticsNodes().isNotEmpty() }
            }
            onEdt { onNodeWithText("同时清理缓存与诊断日志").performClick() }
            waitUntil(timeoutMillis = 3_000L) { LinDoDiagnosticLogger.entryCount() == 0 }
            assertEquals(0L, LinDoDiagnosticLogger.persistedFileSizeBytes())
        } finally {
            tempLogDir.deleteRecursively()
        }
    }
}
