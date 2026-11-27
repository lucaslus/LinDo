package do_.linux.lindo.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import do_.linux.lindo.core.connect.ScreenTrackEngine
import do_.linux.lindo.core.connect.UserPresenceService
import do_.linux.lindo.core.copy.LinDoReaderCopy
import do_.linux.lindo.core.copy.TopicNotificationLevel
import do_.linux.lindo.core.model.PostItemModel
import do_.linux.lindo.core.model.TopicDetailModel
import do_.linux.lindo.core.model.TopicSummary
import do_.linux.lindo.core.model.TopicUser
import do_.linux.lindo.core.model.UserActivityItem
import do_.linux.lindo.core.network.CookieSentinel
import do_.linux.lindo.core.network.LinuxDoRepository
import do_.linux.lindo.core.network.NetworkPolicyEngine
import do_.linux.lindo.core.network.NetworkRecoveryPolicy
import do_.linux.lindo.core.network.UserApiKeyAuthManager
import do_.linux.lindo.core.render.PostBlockNode
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class Wave2ParityUiRegressionTest {

    @BeforeTest
    fun setUp() {
        resetState()
    }

    @AfterTest
    fun tearDown() {
        resetState()
    }

    private fun resetState() {
        PlatformRichMediaBridge.resetForTest()
        PlatformWebLoginHost.webLoginRenderer = null
        PlatformWebLoginHost.resetForTest()
        PlatformSessionRecovery.verifying = false
        PlatformSessionRecovery.verifyPillVisible = false
        NetworkPolicyEngine.resetForTesting()
        NetworkRecoveryPolicy.androidEnabled = false
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
            edt { onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @Test
    fun p1_15_discoverHotLeaderboardAndGlobalSearchFilters() =
        runDesktopComposeUiTest(width = 440, height = 900) {
            UserApiKeyAuthManager.requireBrowserTransportOnly = true
            UserApiKeyAuthManager.webViewHttpExecutor = { url, _, _, _ ->
                when {
                    url.contains("period=weekly") -> 200 to """
                        {
                          "users": [{"id": 1, "username": "bob", "name": "Bob"}],
                          "topic_list": {
                            "topics": [
                              {"id": 201, "title": "本周最热架构实践", "slug": "weekly-hot-1", "posts_count": 42, "views": 3200, "like_count": 120, "posters": [{"user_id": 1}]}
                            ]
                          }
                        }
                    """.trimIndent()
                    url.contains("/search.json") -> 200 to """
                        {
                          "posts": [
                            {"id": 8801, "topic_id": 880, "post_number": 1, "username": "alice", "blurb": "搜索命中正文"}
                          ],
                          "topics": [
                            {"id": 880, "title": "Compose 搜索排序验证", "slug": "compose-search"}
                          ],
                          "users": [
                            {"id": 10, "username": "alice_dev", "name": "Alice Dev", "avatar_template": ""}
                          ],
                          "tags": [
                            {"id": 1, "name": "kotlin-multiplatform", "topic_count": 18}
                          ]
                        }
                    """.trimIndent()
                    else -> 200 to "{}"
                }
            }

            val clickedTopics = CopyOnWriteArrayList<Int>()
            val initialHot = (1..10).map { rank ->
                TopicSummary(
                    id = 100 + rank,
                    title = "今日热帖第 $rank 名",
                    slug = "hot-$rank",
                    postsCount = 10 + rank,
                    views = 500 * rank,
                    likeCount = 20 + rank,
                    originalPoster = TopicUser(1, "alice", "Alice")
                )
            }

            var showSearch by mutableStateOf(false)
            val inspectedUsers = CopyOnWriteArrayList<String>()
            val selectedTags = CopyOnWriteArrayList<String>()

            setContent {
                TestTheme {
                    if (!showSearch) {
                        CommunityDiscoverScreen(
                            hotTopics = initialHot,
                            isAuthorized = true,
                            onSelectCategory = {},
                            onSelectTopic = { clickedTopics.add(it.id) }
                        )
                    } else {
                        GlobalSearchModalSheet(
                            initialQuery = "compose",
                            onDismiss = {},
                            onSelectResult = { _, _, _ -> },
                            onInspectUser = { inspectedUsers.add(it) },
                            onSelectTag = { selectedTags.add(it) }
                        )
                    }
                }
            }

            waitForTag("discover_hot_topics_section")
            edt {
                onNodeWithTag("discover_hot_topic_1").assertIsDisplayed()
                onNodeWithTag("discover_hot_period_weekly").performClick()
            }
            waitForText("本周最热架构实践")
            edt { onNodeWithText("本周最热架构实践").performClick() }
            assertEquals(listOf(201), clickedTopics.toList())

            // Switch to GlobalSearchModalSheet and verify 4 sort orders, expandable filters, and direct user/tag nav
            showSearch = true
            waitForText("最多浏览")
            edt {
                onNodeWithText("最多浏览").assertIsDisplayed()
                onNodeWithTag("search_toggle_adv_filters").performClick()
            }
            waitForTag("search_filter_category")
            edt {
                onNodeWithTag("search_filter_category").assertIsDisplayed()
                onNodeWithTag("search_filter_tag").assertIsDisplayed()
                onNodeWithTag("search_filter_author").assertIsDisplayed()
            }
            waitForTag("search_user_hit_alice_dev")
            edt {
                onNodeWithTag("search_user_hit_alice_dev").performClick()
                onNodeWithTag("search_tag_hit_kotlin-multiplatform").performClick()
            }
            assertEquals(listOf("alice_dev"), inspectedUsers.toList())
            assertEquals(listOf("kotlin-multiplatform"), selectedTags.toList())
        }

    @Test
    fun p1_16_feedClosedSolvedIconsAndRateLimitCountdownBanner() =
        runDesktopComposeUiTest(width = 430, height = 860) {
            // Trigger a live 429 cooldown via noteRetryAfter so the countdown banner appears
            NetworkRecoveryPolicy.androidEnabled = true
            LinuxDoRepository.noteRetryAfter("12")

            val topics = listOf(
                TopicSummary(
                    id = 301,
                    title = "已关闭且已解决的高质量问答",
                    slug = "closed-solved",
                    postsCount = 8,
                    views = 450,
                    closed = true,
                    hasAcceptedAnswer = true,
                    originalPoster = TopicUser(1, "alice", "Alice")
                )
            )
            var refreshedCount = 0

            setContent {
                TestTheme {
                    CommunityHomeFeedPane(
                        topics = topics,
                        listState = rememberLazyListState(),
                        selectedTopicId = null,
                        selectedCategoryId = null,
                        currentSort = FeedSortFilter.LATEST,
                        searchQuery = "",
                        isLoading = false,
                        errorMessage = null,
                        enableAnimations = false,
                        isAuthorized = true,
                        currentUser = null,
                        onRequestLogin = {},
                        onSearchChange = {},
                        onOpenGlobalSearch = {},
                        onOpenCreateTopic = {},
                        onOpenNotifications = {},
                        onSelectCategory = {},
                        onSelectSort = {},
                        onRefresh = { refreshedCount++ },
                        onLoadMore = {},
                        onSelectTopic = {},
                        onAvatarClick = {}
                    )
                }
            }

            waitForTag("rate_limit_countdown_banner")
            edt {
                onNodeWithTag("rate_limit_countdown_banner").assertIsDisplayed()
                onNodeWithTag("topic_closed_icon_301", useUnmergedTree = true).assertIsDisplayed()
                onNodeWithTag("topic_solved_icon_301", useUnmergedTree = true).assertIsDisplayed()
                onNodeWithText("立即重试").performClick()
            }
            assertTrue(refreshedCount >= 1)
        }

    @Test
    fun p1_17_tocThresholdPlainTextFindSmallActionAndFourLevelNotifPicker() =
        runDesktopComposeUiTest(width = 440, height = 900) {
            val apiCalls = CopyOnWriteArrayList<Pair<String, Map<String, String>?>>()
            UserApiKeyAuthManager.requireBrowserTransportOnly = true
            UserApiKeyAuthManager.recordWebSessionLogin("alice", "Alice", "", trustLevel = 3, userId = 10)
            UserApiKeyAuthManager.webViewHttpExecutor = { url, method, formParams, _ ->
                apiCalls.add("$method $url" to formParams)
                200 to """{"success":"OK"}"""
            }

            val summary = TopicSummary(
                id = 601,
                title = "长文阅读工具回归测试",
                slug = "reader-tools",
                postsCount = 2,
                views = 99,
                originalPoster = TopicUser(10, "alice", "Alice")
            )
            var detail by mutableStateOf(
                TopicDetailModel(
                    id = 601,
                    title = summary.title,
                    categoryName = "开发调优",
                    postsCount = 2,
                    views = 99,
                    notificationLevel = TopicNotificationLevel.REGULAR.code,
                    posts = listOf(
                        PostItemModel(
                            id = 6011,
                            postNumber = 1,
                            userId = 10,
                            username = "alice",
                            displayName = "Alice",
                            cookedHtml = "<h2>第一节</h2><p><strong>你好世界</strong></p><h3>第二节</h3><p>内容</p>",
                            isTopicOwner = true
                        ),
                        PostItemModel(
                            id = 6012,
                            postNumber = 2,
                            userId = 10,
                            username = "alice",
                            displayName = "Alice",
                            cookedHtml = "",
                            isSmallAction = true,
                            actionCode = "closed.enabled",
                            createdAtText = "刚刚"
                        )
                    ),
                    streamIds = listOf(6011, 6012)
                )
            )

            setContent {
                TestTheme {
                    CommunityTopicReaderPane(
                        summary = summary,
                        detail = detail,
                        isLoading = false,
                        enableAnimations = false,
                        isAuthorized = true,
                        showBackButton = true,
                        onBack = {},
                        onUnavailable = {},
                        onRefresh = {},
                        onDetailUpdated = { detail = it },
                        onInspectBadge = {},
                        onInspectUser = {},
                        onSelectTag = {},
                        onRequestLogin = {}
                    )
                }
            }

            // 1. With only 2 headings, TOC button inside top ⋯ menu must stay hidden; small_action renders compact strip
            waitForTag("post_small_action_strip_2")
            edt {
                onNodeWithContentDescription(LinDoReaderCopy.MORE_ACTIONS_DESC.text).performClick()
            }
            waitForTag("in_topic_find_trigger")
            edt {
                assertTrue(onAllNodesWithTag("topic_toc_trigger").fetchSemanticsNodes().isEmpty())
                onNodeWithTag("post_small_action_strip_2").assertIsDisplayed()
            }

            // 2. Adding a 3rd heading reveals TOC button inside top ⋯ menu
            detail = detail.copy(
                posts = listOf(
                    detail.posts[0].copy(
                        cookedHtml = "<h2>第一节</h2><p><strong>你好世界</strong></p><h3>第二节</h3><h4>第三节</h4>"
                    ),
                    detail.posts[1]
                )
            )
            waitForTag("topic_toc_trigger")

            // 3. Plain-text in-topic find ignores raw HTML tag name ("strong") and finds visible text ("你好世界")
            edt { onNodeWithTag("in_topic_find_trigger").performScrollTo().performClick() }
            waitForTag("in_topic_find_input")
            edt { onNodeWithTag("in_topic_find_input").performTextInput("strong") }
            waitForText("0")
            edt {
                onNodeWithTag("in_topic_find_input").performTextClearance()
                onNodeWithTag("in_topic_find_input").performTextInput("你好世界")
            }
            waitForText("1/1")
            edt {
                onNodeWithTag("in_topic_find_prev").assertIsDisplayed()
                onNodeWithTag("in_topic_find_next").assertIsDisplayed()
            }

            // 4. 4-level notification picker opens dialog from top ⋯ menu and updates to TRACKING (level 2)
            edt { onNodeWithContentDescription(LinDoReaderCopy.MORE_ACTIONS_DESC.text).performClick() }
            waitForTag("topic_notification_level_trigger")
            edt { onNodeWithTag("topic_notification_level_trigger").performScrollTo().performClick() }
            waitForTag("topic_notification_level_dialog")
            edt {
                onNodeWithTag("topic_notif_level_watching").assertIsDisplayed()
                onNodeWithTag("topic_notif_level_tracking").assertIsDisplayed()
                onNodeWithTag("topic_notif_level_regular").assertIsDisplayed()
                onNodeWithTag("topic_notif_level_muted").assertIsDisplayed()
                onNodeWithTag("topic_notif_level_tracking").performClick()
            }
            waitUntil(timeoutMillis = 5_000L) {
                detail.notificationLevel == TopicNotificationLevel.TRACKING.code
            }
            assertTrue(apiCalls.any { it.first.contains("/t/601/notifications.json") && it.second?.get("notification_level") == "2" })
        }

    @Test
    fun p1_18_remoteRepliesParentAutoFetchRevisionBadgeAndPollProgressBars() =
        runDesktopComposeUiTest(width = 440, height = 920) {
            UserApiKeyAuthManager.requireBrowserTransportOnly = true
            UserApiKeyAuthManager.recordWebSessionLogin("alice", "Alice", "", trustLevel = 3, userId = 10)
            UserApiKeyAuthManager.webViewHttpExecutor = { url, _, _, _ ->
                when {
                    url.contains("/t/700.json") -> 200 to """
                        {
                          "id": 700,
                          "title": "Poll Seed",
                          "posts_count": 1,
                          "post_stream": {
                            "stream": [7002],
                            "posts": [
                              {
                                "id": 7002,
                                "post_number": 2,
                                "user_id": 20,
                                "username": "bob",
                                "cooked": "<p>投票</p>",
                                "polls": [
                                  {
                                    "name": "poll",
                                    "status": "open",
                                    "voters": 10,
                                    "options": [
                                      {"id": "opt1", "votes": 7},
                                      {"id": "opt2", "votes": 3}
                                    ]
                                  }
                                ]
                              }
                            ]
                          }
                        }
                    """.trimIndent()
                    url.contains("/posts/7002/replies.json") -> 200 to """
                        [
                          {
                            "id": 7003,
                            "post_number": 5,
                            "user_id": 30,
                            "username": "charlie",
                            "name": "Charlie",
                            "cooked": "<p>这是远程拉取的子回复文本</p>",
                            "reply_to_post_number": 2,
                            "actions_summary": [{"id": 2, "count": 3, "acted": true}]
                          }
                        ]
                    """.trimIndent()
                    url.contains("/discourse-reactions/posts/7003/custom-reactions/heart/toggle.json") -> 200 to """
                        {"id": 7003, "reactions": [{"id": "heart", "count": 2}]}
                    """.trimIndent()
                    url.contains("/polls/voters.json") -> 200 to """
                        {"voters": {"opt1": [{"id": 88, "username": "dave_voter", "name": "Dave Voter", "avatar_template": ""}]}}
                    """.trimIndent()
                    url.contains("/posts/by_number/700/1.json") -> 200 to """
                        {
                          "id": 7001,
                          "post_number": 1,
                          "user_id": 10,
                          "username": "alice",
                          "name": "Alice",
                          "cooked": "<p>这是自动补拉的一楼完整原帖正文内容</p>"
                        }
                    """.trimIndent()
                    url.contains("/posts/7002/revisions/2.json") -> 200 to """
                        {
                          "current_revision": 2,
                          "revision_count": 2,
                          "username": "bob",
                          "created_at": "2026-11-26T00:00:00Z",
                          "edit_reason": "补充细节",
                          "body_changes": {
                            "inline": "<p>旧句子 <del>被删文字</del><ins>新增文字</ins></p>"
                          }
                        }
                    """.trimIndent()
                    else -> 200 to "{}"
                }
            }

            var inspectedRevisionPostId by mutableStateOf<Int?>(null)
            val inspectedPollVoters = CopyOnWriteArrayList<String>()
            val floorPost = PostItemModel(
                id = 7002,
                topicId = 700,
                postNumber = 2,
                userId = 20,
                username = "bob",
                displayName = "Bob",
                cookedHtml = "<p>这是二楼回复内容</p>",
                version = 3,
                replyCount = 1,
                replyToPostNumber = 1,
                replyToUsername = "alice",
                replyToDisplayName = "Alice"
            )
            runBlocking { LinuxDoRepository.fetchTopicDetail(700) }
            val pollNode = PostBlockNode.Poll(
                name = "poll",
                title = "你最常用的客户端？",
                options = listOf(
                    PostBlockNode.PollOption("opt1", "Android"),
                    PostBlockNode.PollOption("opt2", "Desktop")
                ),
                multiple = false,
                min = 1,
                max = 1,
                closed = false
            )

            setContent {
                TestTheme {
                    Column {
                        CommunityPostFloor(
                            post = floorPost,
                            repliedPost = null,
                            childReplies = emptyList(),
                            opUsername = "alice",
                            fontScale = 1f,
                            enableAnimations = false,
                            onInspectBadge = {},
                            onInspectUser = {},
                            onInspectRevisions = { inspectedRevisionPostId = it },
                            onReplyFloor = {},
                            onQuoteFloor = { _, _ -> },
                            onRequestLogin = {}
                        )
                        CompositionLocalProvider(LocalOnInspectUser provides { u -> inspectedPollVoters.add(u) }) {
                            PostRichContent(
                                nodes = listOf(pollNode),
                                palette = LocalPalette.current,
                                onOpenLink = {},
                                postId = 7002
                            )
                        }
                    }
                    inspectedRevisionPostId?.let { pid ->
                        PostRevisionsDialog(postId = pid, onDismiss = { inspectedRevisionPostId = null })
                    }
                }
            }

            // 1. Auto-fetched parent preview + inline 展开原楼
            waitForText("这是自动补拉的一楼完整原帖正文内容", substring = true)
            edt { onNodeWithTag("reply_parent_expand_toggle").assertIsDisplayed() }

            // 2. Remote child replies toggle fetches /posts/7002/replies.json AND already-liked child reply can unlike (3 -> 2)
            edt { onNodeWithTag("post_child_replies_toggle_2").performClick() }
            waitForText("这是远程拉取的子回复文本", substring = true)
            waitForTag("child_reply_like_btn_7003")
            edt {
                onNodeWithText("3").assertIsDisplayed()
                onNodeWithTag("child_reply_like_btn_7003").performClick()
            }
            waitForText("2")

            // 3. Poll toggle reveals percentage progress bars (70% / 30%) AND voter avatar chip is clickable
            edt { onNodeWithTag("poll_toggle_results_btn").performClick() }
            waitForTag("poll_option_pct_opt1")
            edt {
                onNodeWithTag("poll_option_pct_opt1", useUnmergedTree = true).assertIsDisplayed()
                onNodeWithTag("poll_option_pct_opt2", useUnmergedTree = true).assertIsDisplayed()
                onNodeWithTag("poll_option_pct_opt1", useUnmergedTree = true).performClick()
            }
            waitForTag("poll_voter_chip_dave_voter")
            edt { onNodeWithTag("poll_voter_chip_dave_voter").performClick() }
            assertEquals(listOf("dave_voter"), inspectedPollVoters.toList())

            // 4. Clickable ✎ 2 edit badge opens PostRevisionsDialog with colored diff
            edt { onNodeWithTag("post_edit_badge_2", useUnmergedTree = true).performClick() }
            waitForTag("post_revision_diff_text")
            edt { onNodeWithTag("post_revision_diff_text").assertIsDisplayed() }
        }

    @Test
    fun p1_19_richMediaAudioVideoMathMermaidFootnoteAndLightboxSave() =
        runDesktopComposeUiTest(width = 440, height = 920) {
            val savedImages = CopyOnWriteArrayList<Pair<String, String>>()
            PlatformRichMediaBridge.saveImageToGallery = { url, name ->
                savedImages.add(url to name)
                Result.success("已保存到相册 Pictures/LinDo")
            }

            var showLightbox by mutableStateOf(false)
            val richHtml = """
                <p>正文含脚注<sup class="footnote-ref"><a href="#fn1">[1]</a></sup></p>
                <audio controls src="https://linux.do/uploads/demo.mp3"></audio>
                <video controls src="https://linux.do/uploads/demo.mp4"></video>
                <pre><code class="lang-mermaid">graph TD; A-->B;</code></pre>
                <div class="math">\frac{\alpha}{\beta} + \sqrt{2}</div>
                <section class="footnotes"><ol><li id="fn1"><p>脚注弹出层详细说明</p></li></ol></section>
            """.trimIndent()

            setContent {
                TestTheme {
                    Column {
                        CommunityPostContent(
                            postId = 8001,
                            topicId = 800,
                            postNumber = 1,
                            cookedHtml = richHtml,
                            originalUrl = "https://linux.do/t/800/1",
                            targetReplyFloorNum = null,
                            targetReplyDisplayName = null,
                            targetReplyUsername = null,
                            targetReplyAvatarTemplate = "",
                            targetReplyAnimatedAvatar = null,
                            enableAnimations = false,
                            repliedHtml = null,
                            onJumpToFloorNumber = {},
                            loadingReplyTarget = false,
                            onSelectionChange = {},
                            onOpenWebUrl = {}
                        )
                    }
                    if (showLightbox) {
                        PostImageLightboxDialog(
                            gallery = PostBlockNode.ImageGallery(
                                urls = listOf("https://linux.do/uploads/1.png", "https://linux.do/uploads/2.png"),
                                fullUrls = listOf("https://linux.do/uploads/1.png", "https://linux.do/uploads/2.png"),
                                captions = listOf("首图", "次图")
                            ),
                            initialIndex = 0,
                            onOpenLink = {},
                            onDismiss = { showLightbox = false }
                        )
                    }
                }
            }

            waitForTag("inline_audio_player_bar")
            edt {
                onNodeWithTag("inline_audio_player_bar").assertIsDisplayed()
                onNodeWithTag("inline_video_player_card").assertIsDisplayed()
                onNodeWithTag("mermaid_card_block").assertIsDisplayed()
                onNodeWithTag("math_card_block").assertIsDisplayed()
                onNodeWithTag("math_render_preview_btn").performClick()
            }
            waitForTag("academic_preview_dialog")
            edt { onNodeWithContentDescription("关闭").performClick() }

            // Open Lightbox and test 保存图片
            showLightbox = true
            waitForTag("lightbox_save_image_btn")
            edt { onNodeWithTag("lightbox_save_image_btn").performClick() }
            waitForTag("lightbox_save_toast")
            assertEquals(1, savedImages.size)
            assertEquals("https://linux.do/uploads/1.png", savedImages.first().first)
        }

    @Test
    fun p1_20_messagesUnreadFilterBookmarkBadgesAndComposerInlineAutocomplete() =
        runDesktopComposeUiTest(width = 440, height = 920) {
            UserApiKeyAuthManager.requireBrowserTransportOnly = true
            UserApiKeyAuthManager.recordWebSessionLogin("alice", "Alice", "", trustLevel = 3, userId = 10)
            UserApiKeyAuthManager.webViewHttpExecutor = { url, _, _, _ ->
                when {
                    url.contains("/notifications.json") -> 200 to """
                        {
                          "notifications": [
                            {"id": 1, "notification_type": 2, "read": false, "topic_id": 11, "post_number": 2, "fancy_title": "未读回复主题", "data": {"display_username": "bob", "topic_title": "未读回复主题"}},
                            {"id": 2, "notification_type": 5, "read": true, "topic_id": 12, "post_number": 1, "fancy_title": "已读点赞主题", "data": {"display_username": "charlie", "topic_title": "已读点赞主题"}}
                          ]
                        }
                    """.trimIndent()
                    url.contains("/u/search/users.json") -> 200 to """
                        {
                          "users": [
                            {"username": "bob_dev", "name": "Bob Dev", "avatar_template": "/user_avatar/linux.do/bob_dev/{size}/1.png"}
                          ]
                        }
                    """.trimIndent()
                    else -> 200 to "{}"
                }
            }

            var stage by mutableStateOf(0)
            var editorText by mutableStateOf("")

            setContent {
                TestTheme {
                    when (stage) {
                        0 -> CommunityMessagesScreen(
                            isAuthorized = true,
                            username = "alice",
                            onRequestLogin = {},
                            onSelectTopicId = { _, _, _ -> },
                            onSelectMessageTopic = {},
                            onManagePrivateMessages = {},
                            onOpenWebUrl = {},
                            onMarkedAllRead = {}
                        )
                        1 -> ProfileActivityRow(
                            activity = UserActivityItem(
                                id = 901,
                                topicId = 777,
                                postNumber = 3,
                                title = "带备注和提醒的书签",
                                excerpt = "关键段落摘要",
                                categoryName = "开发调优",
                                createdAtText = "刚刚",
                                bookmarkName = "周末深度研读",
                                bookmarkReminderAt = "2026-12-01T09:30:00Z"
                            ),
                            onClick = {}
                        )
                        else -> LinDoMarkdownWorkbench(
                            rawValue = editorText,
                            onRawValueChange = { editorText = it },
                            placeholder = "写点什么"
                        )
                    }
                }
            }

            // 1. Messages 未读 filter pill filters out already-read notification
            waitForText("未读回复主题")
            waitForTag("messages_filter_pill_unread")
            edt { onNodeWithTag("messages_filter_pill_unread").performClick() }
            waitUntil(timeoutMillis = 5_000L) {
                edt { onAllNodesWithText("已读点赞主题").fetchSemanticsNodes().isEmpty() }
            }
            edt { onNodeWithText("未读回复主题").assertIsDisplayed() }

            // 2. Bookmarks row renders bookmark name note & reminder badge
            stage = 1
            waitForTag("bookmark_name_badge")
            edt {
                onNodeWithTag("bookmark_name_badge", useUnmergedTree = true).assertIsDisplayed()
                onNodeWithTag("bookmark_reminder_badge", useUnmergedTree = true).assertIsDisplayed()
            }

            // 3. Composer inline :emoji: autocomplete & @mention autocomplete with avatar
            stage = 2
            waitForText("写点什么")
            edt { onNode(hasSetTextAction()).performTextInput("测试 :roc") }
            waitForTag("composer_inline_emoji_bar")
            edt { onNodeWithTag("composer_inline_emoji_bar").assertIsDisplayed() }

            edt {
                onNode(hasSetTextAction()).performTextClearance()
                onNode(hasSetTextAction()).performTextInput("你好 @bob")
            }
            waitForTag("composer_mention_chip_bob_dev")
            edt { onNodeWithTag("composer_mention_chip_bob_dev").performClick() }
            assertTrue(editorText.contains("@bob_dev"))
        }

    @Test
    fun composerLinkPickerDefaultsToQuoteCommunityTopicAndSupportsExternalTab() =
        runDesktopComposeUiTest(width = 440, height = 920) {
            UserApiKeyAuthManager.requireBrowserTransportOnly = true
            UserApiKeyAuthManager.recordWebSessionLogin("alice", "Alice", "", trustLevel = 3, userId = 10)
            UserApiKeyAuthManager.webViewHttpExecutor = { url, _, _, _ ->
                if (url.contains("/latest.json")) {
                    200 to """
                        {
                          "users": [{"id": 1, "username": "alice", "name": "Alice"}],
                          "topic_list": {
                            "topics": [
                              {"id": 9101, "title": "最近社区热门技术分享", "slug": "recent-tech", "posts_count": 12, "views": 300, "like_count": 20, "posters": [{"user_id": 1}]}
                            ]
                          }
                        }
                    """.trimIndent()
                } else 200 to "{}"
            }

            var editorText by mutableStateOf("")
            setContent {
                TestTheme {
                    LinDoMarkdownWorkbench(
                        rawValue = editorText,
                        onRawValueChange = { editorText = it },
                        placeholder = "写点什么"
                    )
                }
            }

            // 1. Opening Link Picker defaults to "引用社区帖子" and immediately pre-loads recent topics
            waitForText("写点什么")
            edt { onNodeWithContentDescription("链接").performClick() }
            waitForTag("composer_link_quote_search_input")
            edt {
                onNodeWithText("引用社区帖子").assertIsDisplayed()
                onNodeWithText("外部链接").assertIsDisplayed()
                onNodeWithTag("composer_link_quote_search_input").assertIsDisplayed()
                assertTrue(onAllNodesWithTag("composer_link_external_url_input").fetchSemanticsNodes().isEmpty())
            }
            waitForText("最近社区热门技术分享")

            // 2. Switching to "外部链接" works smoothly and inserts Markdown link
            edt { onNodeWithText("外部链接").performClick() }
            waitForTag("composer_link_external_url_input")
            edt {
                onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("composer_link_external_url_input")))
                    .performTextInput("https://example.com/guide")
                onAllNodesWithText("插入链接").onLast().performClick()
            }
            waitUntil(timeoutMillis = 5_000L) { editorText.contains("https://example.com/guide") }

            // 3. Re-opening Link Picker resets default tab back to "引用社区帖子" (never stuck on "外部链接")
            edt { onNodeWithContentDescription("链接").performClick() }
            waitForTag("composer_link_quote_search_input")
            edt {
                onNodeWithTag("composer_link_quote_search_input").assertIsDisplayed()
                assertTrue(onAllNodesWithTag("composer_link_external_url_input").fetchSemanticsNodes().isEmpty())
            }
        }

    @Test
    fun settingsManualSiteVerifyRowTriggersOfficialChallengeAndPostImageSkeletonRenders() =
        runDesktopComposeUiTest(width = 440, height = 960) {
            val openedUrls = CopyOnWriteArrayList<String>()
            val capturedForceRefreshFlags = CopyOnWriteArrayList<Boolean>()
            var onDismissCallback by mutableStateOf<(() -> Unit)?>(null)
            var onVerifiedCallback by mutableStateOf<(() -> Unit)?>(null)
            PlatformWebLoginHost.webLoginRenderer = { url, onDismiss, onVerified ->
                val isVerify = do_.linux.lindo.core.network.webPagePurpose(url) == do_.linux.lindo.core.network.WebPagePurpose.VERIFICATION
                val fr = remember(url) { PlatformWebLoginHost.consumeOneShotForceRefresh(isVerify) }
                LaunchedEffect(url) {
                    openedUrls.add(url)
                    capturedForceRefreshFlags.add(fr)
                }
                onDismissCallback = onDismiss
                onVerifiedCallback = onVerified
            }

            UserApiKeyAuthManager.requireBrowserTransportOnly = true
            UserApiKeyAuthManager.recordWebSessionLogin("alice", "Alice", "", trustLevel = 3, userId = 10)
            UserApiKeyAuthManager.webViewHttpExecutor = { url, _, _, _ ->
                when {
                    url.contains("/latest.json") -> 200 to """
                        {
                          "users": [{"id": 1, "username": "alice", "name": "Alice"}],
                          "topic_list": {"topics": [{"id": 100, "title": "首页帖子", "slug": "home", "posts_count": 1, "views": 10, "posters": [{"user_id": 1}]}]}
                        }
                    """.trimIndent()
                    url.contains("/u/alice.json") -> 200 to """
                        {"user": {"id": 10, "username": "alice", "name": "Alice", "trust_level": 3}}
                    """.trimIndent()
                    else -> 200 to "{}"
                }
            }

            // 1. Verify CommunityProfileScreen live status ("刷新验证票据" -> "待验证") + tap triggers official /challenge#lindo-verify
            val slowImageServer = java.net.ServerSocket(0)
            val slowImageThread = Thread {
                runCatching {
                    val sock = slowImageServer.accept()
                    runCatching { Thread.sleep(4_000L) }
                    runCatching { sock.close() }
                }
            }.apply { isDaemon = true; start() }
            val slowImageUrl = "http://127.0.0.1:${slowImageServer.localPort}/skeleton-demo.png"
            try {
            var stage by mutableStateOf(0)
            val profileOpenedUrls = CopyOnWriteArrayList<String>()
            setContent {
                TestTheme {
                    when (stage) {
                        0 -> CommunityProfileScreen(
                            isDarkTheme = false,
                            fontScale = 1f,
                            enableAnimations = false,
                            isAuthorized = true,
                            currentUser = UserApiKeyAuthManager.currentUser,
                            fullProfile = null,
                            onAutoRefreshProfile = {},
                            onToggleDark = {},
                            onChangeFontScale = {},
                            onToggleAnimations = {},
                            onOpenNotifications = {},
                            onOpenActivities = {},
                            onOpenBookmarks = {},
                            onOpenPrivateMessages = {},
                            onInspectBadge = {},
                            onOpenConnectSheet = {},
                            onOpenAuthSheet = {},
                            onOpenWebUrl = { profileOpenedUrls.add(it) },
                            onRequestLogin = {},
                            onLogout = {}
                        )
                        1 -> LinDoApp(systemAnimationsEnabled = false)
                        else -> PostRichContent(
                            nodes = listOf(
                                PostBlockNode.ImageGallery(
                                    urls = listOf(slowImageUrl),
                                    captions = listOf("示例图"),
                                    aspectRatios = listOf(690f / 388f)
                                )
                            ),
                            palette = LocalPalette.current,
                            onOpenLink = {}
                        )
                    }
                }
            }

            edt {
                onAllNodes(hasScrollToNodeAction()).onFirst()
                    .performScrollToNode(hasTestTag("settings_site_verify_row"))
            }
            waitForTag("settings_site_verify_row")
            edt {
                onNodeWithText("站点安全验证").assertIsDisplayed()
                onNodeWithText("刷新验证票据").assertIsDisplayed()
            }
            NetworkPolicyEngine.updateVerifyAttentionNeeded(true)
            waitForText("待验证")
            edt { onNodeWithTag("settings_site_verify_row").performClick() }
            assertEquals(listOf("https://linux.do/challenge#lindo-verify"), profileOpenedUrls.toList())

            // 2a. Cancelling (×) a Settings forceRefresh modal while CfClearanceAuthority.hasHealthyIncumbent() == true
            //     MUST NOT update lastSiteVerifiedAtMs, MUST NOT toast "安全验证已刷新", MUST arm dismiss snooze, and MUST preserve _t!
            NetworkPolicyEngine.updateVerifyAttentionNeeded(false)
            NetworkPolicyEngine.clearUserDismissedVerify()
            do_.linux.lindo.core.network.CfClearanceAuthority.reset()
            do_.linux.lindo.core.network.CfClearanceAuthority.selectWinner(listOf("incumbent_healthy_cf_001"), verified = true)
            CookieSentinel.ingestRawCookieString("_t=keep_login_session_token; _forum_session=keep_sess_1", domain = "linux.do")
            assertTrue(do_.linux.lindo.core.network.CfClearanceAuthority.hasHealthyIncumbent(), "Precondition: healthy incumbent exists")

            stage = 1
            waitForText("我的")
            edt { onNodeWithText("我的").performClick() }
            waitForText("外观")
            edt {
                onAllNodes(hasScrollToNodeAction()).onLast()
                    .performScrollToNode(hasText("清理本地缓存"))
            }
            waitForTag("settings_site_verify_row")
            edt { onNodeWithTag("settings_site_verify_row").performClick() }
            waitUntil(timeoutMillis = 5_000L) { openedUrls.contains("https://linux.do/challenge#lindo-verify") }
            assertEquals(listOf(true), capturedForceRefreshFlags.toList(), "Settings 站点安全验证 must arm one-shot forceRefresh = true")
            assertTrue(!PlatformWebLoginHost.peekOneShotForceRefreshForTest(), "One-shot forceRefresh must be consumed & cleared immediately")
            assertTrue(onDismissCallback != null, "Embedded verification modal must wire onDismiss")
            val oldSuccess = onVerifiedCallback
            edt { onDismissCallback?.invoke() }
            waitUntil(timeoutMillis = 5_000L) { NetworkPolicyEngine.isProactiveVerifySnoozed() }
            assertTrue(NetworkPolicyEngine.isProactiveVerifySnoozed(), "Cancelling forceRefresh must arm dismiss snooze")
            assertTrue(PlatformSessionRecovery.verifyPillVisible, "Cancelling forceRefresh must surface verify pill")
            edt {
                assertTrue(
                    onAllNodesWithText("安全验证已刷新").fetchSemanticsNodes().isEmpty(),
                    "Cancelled forceRefresh must NEVER show '安全验证已刷新' toast"
                )
                assertTrue(
                    onAllNodesWithText("刚刚已验证").fetchSemanticsNodes().isEmpty(),
                    "Cancelled forceRefresh must NEVER update lastSiteVerifiedAtMs"
                )
            }
            assertEquals(
                "keep_login_session_token",
                CookieSentinel.resolveWinningCookies(targetHost = "linux.do")["_t"]?.value,
                "Cancelling forceRefresh must NEVER drop _t login session cookie"
            )
            assertTrue(UserApiKeyAuthManager.isAuthorized(), "Cancelling forceRefresh must keep user logged in")

            // 2b. Re-opening Settings 站点安全验证 and completing via onVerified -> "安全验证已刷新" toast + "刚刚已验证"
            openedUrls.clear()
            capturedForceRefreshFlags.clear()
            edt { onNodeWithTag("settings_site_verify_row").performClick() }
            waitUntil(timeoutMillis = 5_000L) { openedUrls.contains("https://linux.do/challenge#lindo-verify") }
            assertEquals(listOf(true), capturedForceRefreshFlags.toList(), "Settings 站点安全验证 must arm one-shot forceRefresh = true again")
            assertTrue(!PlatformWebLoginHost.peekOneShotForceRefreshForTest(), "One-shot forceRefresh must be consumed & cleared immediately")
            assertTrue(onVerifiedCallback != null, "Embedded verification modal must open for human verification")
            val joinedResults = mutableListOf<VerificationDialogResult>()
            edt {
                PlatformSessionRecovery.openEmbeddedVerifyModal?.invoke { joinedResults.add(it) }
                oldSuccess?.invoke()
                assertTrue(joinedResults.isEmpty(), "A callback from the dismissed window must not finish the current one")
            }
            edt { onVerifiedCallback?.invoke() }
            waitForText("安全验证已刷新")
            assertEquals(listOf(VerificationDialogResult.Passed), joinedResults)
            waitForText("刚刚已验证")
            assertTrue(!NetworkPolicyEngine.verifyAttentionNeeded, "Verify attention flag must clear after verification")

            // Verify reactive PlatformSessionRecovery.openEmbeddedVerifyModal keeps forceRefresh = false (never sticky!)
            openedUrls.clear()
            capturedForceRefreshFlags.clear()
            edt { PlatformSessionRecovery.openEmbeddedVerifyModal?.invoke {} }
            waitUntil(timeoutMillis = 5_000L) { openedUrls.contains("https://linux.do/challenge#lindo-verify") }
            assertEquals(listOf(false), capturedForceRefreshFlags.toList(), "Reactive verify modal must keep forceRefresh = false")
            edt { onVerifiedCallback?.invoke() }

            // 3. Post image renders reserved aspect-ratio skeleton while Coil is genuinely loading
            stage = 2
            waitForTag("post_image_skeleton_0")
            edt { onNodeWithTag("post_image_skeleton_0").assertIsDisplayed() }
            } finally {
                do_.linux.lindo.core.network.CfClearanceAuthority.reset()
                runCatching { slowImageServer.close() }
                runCatching { slowImageThread.interrupt() }
            }
        }
}
