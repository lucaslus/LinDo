package do_.linux.lindo.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import do_.linux.lindo.core.connect.ScreenTrackEngine
import do_.linux.lindo.core.connect.UserPresenceService
import do_.linux.lindo.core.copy.LinDoReaderCopy
import do_.linux.lindo.core.model.PostItemModel
import do_.linux.lindo.core.model.TopicDetailModel
import do_.linux.lindo.core.model.TopicSummary
import do_.linux.lindo.core.model.TopicUser
import do_.linux.lindo.core.network.CookieSentinel
import do_.linux.lindo.core.network.LinuxDoRepository
import do_.linux.lindo.core.network.UserApiKeyAuthManager
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class Wave1ParityUiRegressionTest {

    @BeforeTest
    fun setUp() {
        resetState()
    }

    @AfterTest
    fun tearDown() {
        resetState()
    }

    private fun resetState() {
        UserApiKeyAuthManager.webViewHttpExecutor = null
        UserApiKeyAuthManager.requireBrowserTransportOnly = false
        UserApiKeyAuthManager.logout()
        CookieSentinel.clearAll()
        LinuxDoRepository.clearUserSessionCaches()
        LinuxDoRepository.clearAllLocalCaches()
        LinuxDoRepository.clearChallengeCooldown()
        LinuxDoRepository.clearTransientRateLimit()
        ScreenTrackEngine.resetSessionState()
        UserPresenceService.setAppForeground(true)
        var safety = 0
        while (BackNavigationBridge.hasActiveInterceptors && safety++ < 20) {
            BackNavigationBridge.dispatchBack()
        }
    }

    @Composable
    private fun TestTheme(content: @Composable () -> Unit) {
        val palette = buildLinDoPalette(false, BrandThemeOption.OFFICIAL_GOLD)
        CompositionLocalProvider(
            LocalPalette provides palette,
            LocalLinDoDarkTheme provides false,
            LocalLinDoAnimations provides false
        ) {
            MaterialTheme(colorScheme = buildMaterialSchemeFromPalette(palette), content = content)
        }
    }

    private fun <T> edt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun ComposeUiTest.waitForText(text: String, substring: Boolean = false, timeoutMillis: Long = 5_000L) {
        waitUntil(timeoutMillis = timeoutMillis) {
            edt { onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    private fun ComposeUiTest.waitForTag(tag: String, timeoutMillis: Long = 5_000L) {
        waitUntil(timeoutMillis = timeoutMillis) {
            edt { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @Test
    fun p0_7_topicReaderRendersSolvedPillBadgeAndAcceptUnacceptMenu() =
        runDesktopComposeUiTest(width = 430, height = 860) {
            val apiCalls = CopyOnWriteArrayList<Triple<String, String, Map<String, String>?>>()
            UserApiKeyAuthManager.requireBrowserTransportOnly = true
            UserApiKeyAuthManager.recordWebSessionLogin("alice", "Alice", "", trustLevel = 3, userId = 10)
            UserApiKeyAuthManager.webViewHttpExecutor = { url, method, formParams, _ ->
                apiCalls.add(Triple(method, url, formParams))
                when {
                    url.contains("/solution/accept.json") -> 200 to """{"success":"OK"}"""
                    url.contains("/solution/unaccept.json") -> 200 to """{"success":"OK"}"""
                    else -> 200 to "{}"
                }
            }

            val summary = TopicSummary(
                id = 501,
                title = "如何排查 Android ECH 连接？",
                slug = "ech-debug",
                postsCount = 3,
                views = 128,
                originalPoster = TopicUser(10, "alice", "Alice")
            )
            var detailState by mutableStateOf(
                TopicDetailModel(
                    id = 501,
                    title = summary.title,
                    categoryName = "开发调优",
                    postsCount = 3,
                    views = 128,
                    hasAcceptedAnswer = true,
                    acceptedAnswerPostNumber = 2,
                    posts = listOf(
                        PostItemModel(1001, 1, 10, "alice", "Alice", cookedHtml = "<p>主贴提问内容</p>", isTopicOwner = true),
                        PostItemModel(1002, 2, 20, "bob", "Bob", cookedHtml = "<p>使用智能路由即可</p>", acceptedAnswer = true, canAcceptAnswer = true),
                        PostItemModel(1003, 3, 30, "charlie", "Charlie", cookedHtml = "<p>检查 172.19 Fake-IP</p>", acceptedAnswer = false, canAcceptAnswer = true)
                    ),
                    streamIds = listOf(1001, 1002, 1003)
                )
            )

            setContent {
                TestTheme {
                    CommunityTopicReaderPane(
                        summary = summary,
                        detail = detailState,
                        isLoading = false,
                        enableAnimations = false,
                        isAuthorized = true,
                        showBackButton = true,
                        onBack = {},
                        onUnavailable = {},
                        onRefresh = {},
                        onDetailUpdated = { detailState = it },
                        onInspectBadge = {},
                        onInspectUser = {},
                        onSelectTag = {},
                        onRequestLogin = {}
                    )
                }
            }

            waitForTag("topic_accepted_answer_pill")
            edt {
                onNodeWithTag("topic_accepted_answer_pill").assertIsDisplayed()
                onNodeWithText("已解答 · #2").assertIsDisplayed()
                onNodeWithTag("post_accepted_answer_badge_2", useUnmergedTree = true).assertIsDisplayed()
                onNodeWithText("最佳解答", useUnmergedTree = true).assertIsDisplayed()
            }

            // Open floor #3 more menu -> 采纳最佳解答
            edt {
                onAllNodesWithContentDescription(LinDoReaderCopy.MORE_FLOOR_ACTIONS_DESC.text).onLast().performClick()
            }
            waitForText("采纳最佳解答")
            edt { onNodeWithText("采纳最佳解答").performClick() }

            waitForText("已解答 · #3")
            assertTrue(apiCalls.any { it.first == "POST" && it.second.endsWith("/solution/accept.json") && it.third?.get("id") == "1003" })

            // Open floor #3 more menu again -> 取消采纳解答
            edt {
                onAllNodesWithContentDescription(LinDoReaderCopy.MORE_FLOOR_ACTIONS_DESC.text).onLast().performClick()
            }
            waitForText("取消采纳解答")
            edt { onNodeWithText("取消采纳解答").performClick() }

            waitForText("已取消采纳最佳解答")
            assertTrue(apiCalls.any { it.first == "POST" && it.second.endsWith("/solution/unaccept.json") && it.third?.get("id") == "1003" })
            assertEquals(null, detailState.acceptedAnswerPostNumber)
        }

    @Test
    fun p1_12_connectTrustLevelSheetRendersNativeStructuredLayoutAndWebEscapeHatch() =
        runDesktopComposeUiTest(width = 430, height = 900) {
            UserApiKeyAuthManager.requireBrowserTransportOnly = true
            UserApiKeyAuthManager.recordWebSessionLogin("alice", "Alice", "", trustLevel = 3, userId = 10)
            val html = javaClass.getResource("/connect-current-layout.html")!!.readText()
            UserApiKeyAuthManager.webViewHttpExecutor = { url, _, _, _ ->
                if (url.contains("connect.linux.do")) 200 to html else 404 to "{}"
            }

            var webEscapeOpened = false
            setContent {
                TestTheme {
                    ConnectTrustLevelSheet(
                        onDismiss = {},
                        onOpenWebConnect = { webEscapeOpened = true }
                    )
                }
            }

            waitForText("活跃程度")
            edt {
                onNodeWithText("活跃程度").assertIsDisplayed()
                onNodeWithText("互动参与").assertIsDisplayed()
                onNodeWithText("合规记录").assertIsDisplayed()
                onNodeWithTag("connect_compliance_card_被禁言").assertIsDisplayed()
                onNodeWithTag("connect_compliance_card_被封禁").assertIsDisplayed()
                onNodeWithTag("connect_open_web_btn").assertIsDisplayed().performClick()
            }
            assertTrue(webEscapeOpened, "Clicking 网页版 Connect must trigger Web escape hatch")
        }

    @Test
    fun p1_14_pmParticipantsInviteRemoveAndArchiveActionsWork() =
        runDesktopComposeUiTest(width = 430, height = 860) {
            val apiCalls = CopyOnWriteArrayList<Triple<String, String, Map<String, String>?>>()
            UserApiKeyAuthManager.requireBrowserTransportOnly = true
            UserApiKeyAuthManager.recordWebSessionLogin("alice", "Alice", "", trustLevel = 3, userId = 10)
            UserApiKeyAuthManager.webViewHttpExecutor = { url, method, formParams, _ ->
                apiCalls.add(Triple(method, url, formParams))
                when {
                    url.contains("/u/search/users.json") -> 200 to """{"users":[{"username":"charlie","name":"Charlie","avatar_template":""}]}"""
                    url.contains("/t/808/invite") && method == "POST" -> 200 to """{"success":"OK"}"""
                    url.contains("/t/808/remove-allowed-user") && method == "PUT" -> 200 to """{"success":"OK"}"""
                    url.contains("/t/808/archive-message") && method == "PUT" -> 200 to """{"success":"OK"}"""
                    else -> 200 to "{}"
                }
            }

            val summary = TopicSummary(
                id = 808,
                title = "Wave 1 协同私信",
                slug = "pm-wave1",
                postsCount = 1,
                originalPoster = TopicUser(10, "alice", "Alice")
            )
            var detailState by mutableStateOf(
                TopicDetailModel(
                    id = 808,
                    title = summary.title,
                    categoryName = "私信",
                    archetype = "private_message",
                    archived = false,
                    canInviteTo = true,
                    canRemoveAllowedUsers = true,
                    allowedUsers = listOf(
                        TopicUser(10, "alice", "Alice"),
                        TopicUser(20, "bob", "Bob")
                    ),
                    posts = listOf(
                        PostItemModel(8081, 1, 10, "alice", "Alice", cookedHtml = "<p>测试私信内容</p>", isTopicOwner = true)
                    ),
                    streamIds = listOf(8081)
                )
            )

            setContent {
                TestTheme {
                    CommunityTopicReaderPane(
                        summary = summary,
                        detail = detailState,
                        isLoading = false,
                        enableAnimations = false,
                        isAuthorized = true,
                        showBackButton = true,
                        onBack = {},
                        onUnavailable = {},
                        onRefresh = {},
                        onDetailUpdated = { detailState = it },
                        onInspectBadge = {},
                        onInspectUser = {},
                        onSelectTag = {},
                        onRequestLogin = {}
                    )
                }
            }

            // 1. Invite participant
            waitForTag("pm_invite_button")
            edt { onNodeWithTag("pm_invite_button").performClick() }
            waitForTag("pm_invite_search_input")
            edt { onNodeWithTag("pm_invite_search_input").performTextInput("charlie") }
            waitForText("@charlie")
            edt { onNodeWithText("@charlie").performClick() }
            waitForText("已邀请 @charlie 加入私信")
            assertTrue(apiCalls.any { it.first == "POST" && it.second.contains("/t/808/invite") && it.third?.get("user") == "charlie" })

            // 2. Remove participant bob
            waitForTag("pm_remove_user_bob")
            edt { onNodeWithTag("pm_remove_user_bob").performClick() }
            waitForText("移出私信参与者")
            edt { onNodeWithText("移除").performClick() }
            waitForText("已将 @bob 移出私信")
            assertTrue(apiCalls.any { it.first == "PUT" && it.second.contains("/t/808/remove-allowed-user") && it.third?.get("username") == "bob" })

            // 3. Archive PM via top more menu
            edt {
                onAllNodesWithContentDescription(LinDoReaderCopy.MORE_ACTIONS_DESC.text).onLast().performClick()
            }
            waitForTag("pm_archive_toggle_action")
            edt { onNodeWithTag("pm_archive_toggle_action").performClick() }
            waitForText("私信已归档")
            assertTrue(apiCalls.any { it.first == "PUT" && it.second.contains("/t/808/archive-message") })
            assertTrue(detailState.archived)
        }

    @Test
    fun publicUserCardRendersTrustLevelBadgeInlineStatusEmojiAndClickableWebsite() =
        runDesktopComposeUiTest(width = 430, height = 860) {
            UserApiKeyAuthManager.requireBrowserTransportOnly = true
            UserApiKeyAuthManager.webViewHttpExecutor = { url, _, _, _ ->
                when {
                    url.contains("/u/alice.json") -> 200 to """
                        {
                          "user": {
                            "id": 42,
                            "username": "alice",
                            "name": "Alice Lin",
                            "trust_level": 3,
                            "website": "https://alice.dev",
                            "website_name": "alice.dev",
                            "status": {
                              "emoji": "rocket",
                              "description": "专注内核开发"
                            }
                          }
                        }
                    """.trimIndent()
                    url.contains("/u/alice/summary.json") -> 200 to """{"user_summary":{"days_visited":88,"solved_count":9}}"""
                    url.contains("/user_actions.json") -> 200 to """{"user_actions":[]}"""
                    else -> 200 to "{}"
                }
            }

            val openedUrls = CopyOnWriteArrayList<String>()
            setContent {
                TestTheme {
                    PublicUserCardSheet(
                        username = "alice",
                        currentUsername = "bob",
                        onDismiss = {},
                        onSendPrivateMessage = {},
                        onSelectTopicId = { _, _, _ -> },
                        onOpenWebUrl = { openedUrls.add(it) }
                    )
                }
            }

            waitForTag("public_user_trust_level_badge")
            edt {
                onNodeWithTag("public_user_trust_level_badge").assertIsDisplayed()
                onNodeWithText("Lv.3", substring = true).assertIsDisplayed()
                onNodeWithTag("public_user_status_row").assertIsDisplayed()
                onNodeWithText("专注内核开发").assertIsDisplayed()
                onNodeWithTag("public_user_website_link").assertIsDisplayed().performClick()
                onAllNodesWithText("修改个人资料").fetchSemanticsNodes().also { nodes ->
                    assertTrue(nodes.isEmpty(), "Edit Profile must remain hidden per 1dfb19f")
                }
            }
            assertEquals(listOf("https://alice.dev"), openedUrls)
        }
}
