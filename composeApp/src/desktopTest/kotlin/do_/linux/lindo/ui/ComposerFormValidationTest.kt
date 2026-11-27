package do_.linux.lindo.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import do_.linux.lindo.core.compose.ComposerAction
import do_.linux.lindo.core.compose.LocalComposerDrafts
import do_.linux.lindo.core.network.*
import javax.swing.SwingUtilities
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ComposerFormValidationTest {

    @BeforeTest
    fun setUp() {
        resetState()
        CookieSentinel.ingestRawCookieString("_t=valid_test_token", domain = "linux.do", hostOnly = true)
        UserApiKeyAuthManager.onSessionAuthenticated(
            AuthenticatedUserProfile(
                userId = 1001,
                username = "tester",
                displayName = "Tester",
                avatarTemplate = "/user_avatar/linux.do/tester/{size}/1_2.png",
                trustLevel = 2
            )
        )
        UserApiKeyAuthManager.requireBrowserTransportOnly = true
        UserApiKeyAuthManager.webViewHttpExecutor = { url, _, _, _ ->
            when {
                url.contains("/drafts/") -> 200 to """{"draft":null,"draft_sequence":1}"""
                url.contains("/tags/filter/search.json") -> {
                    if (url.contains("selected_tag_ids")) {
                        200 to """{"results":[{"id":42,"name":"快问快答","count":100}]}"""
                    } else {
                        200 to """{"results":[{"id":42,"name":"快问快答","count":100}],"required_tag_group":{"name":"类版块组"}}"""
                    }
                }
                else -> 200 to "{}"
            }
        }
    }

    @AfterTest
    fun tearDown() {
        resetState()
    }

    private fun resetState() {
        SavedAccounts.resetForTest()
        UserApiKeyAuthManager.webViewHttpExecutor = null
        UserApiKeyAuthManager.requireBrowserTransportOnly = false
        UserApiKeyAuthManager.logout()
        CookieSentinel.clearAll()
        LocalComposerDrafts.clearAll()
        LinuxDoRepository.clearAllLocalCaches()
        LinuxDoRepository.clearChallengeCooldown()
        LinuxDoRepository.clearTransientRateLimit()
    }

    private fun <T> onEdt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var res: Result<T>? = null
        SwingUtilities.invokeAndWait { res = runCatching(block) }
        return res!!.getOrThrow()
    }

    @Test
    fun erasingWhileTheParentStillHasItsOriginalValueEmitsTheDeletion() = runDesktopComposeUiTest(width = 500, height = 680) {
        val edits = mutableListOf<String>()
        setContent {
            MaterialTheme {
                LinDoMarkdownWorkbench(rawValue = "", onRawValueChange = { edits += it }, placeholder = "回复")
            }
        }
        onEdt { onNode(hasSetTextAction()).performTextInput("临时输入") }
        waitForIdle()
        onEdt { onNode(hasSetTextAction()).performTextClearance() }
        assertEquals(listOf("临时输入", ""), edits)
    }

    @Test
    fun deletingRestoredReplyClosesWithoutPromptAndReopensEmpty() = runDesktopComposeUiTest(width = 650, height = 810) {
        val visible = mutableStateOf(true)
        val navigation = ComposerExitInterceptor()
        val previous = UserApiKeyAuthManager.webViewHttpExecutor!!
        val serverBody = java.util.concurrent.atomic.AtomicReference<String?>("要删掉的旧草稿")
        val deleted = java.util.concurrent.atomic.AtomicInteger()
        UserApiKeyAuthManager.webViewHttpExecutor = { url, method, form, headers ->
            when {
                !url.contains("drafts/topic_42") -> previous(url, method, form, headers)
                method == "DELETE" -> {
                    assertTrue(url.endsWith("sequence=7"))
                    serverBody.set(null); deleted.incrementAndGet()
                    200 to """{"success":true}"""
                }
                else -> 200 to if (serverBody.get() != null) """{"draft_sequence":7,"draft":{"reply":"要删掉的旧草稿"}}"""
                    else """{"draft_sequence":7,"draft":null}"""
            }
        }
        setContent {
            if (visible.value) MaterialTheme {
                ComposerSheet(action = ComposerAction.REPLY, topicId = 42, topicTitle = "原帖标题",
                    exitInterceptor = navigation, onDismiss = { visible.value = false }, onPublished = { error("must not publish") })
            }
        }
        waitUntil(timeoutMillis = 5000L) { onEdt { onAllNodesWithText("要删掉的旧草稿").fetchSemanticsNodes().isNotEmpty() } }
        var navigated = false
        onEdt {
            onNode(hasSetTextAction()).performTextClearance()
            navigation.guardNavigation { navigated = true }
        }
        waitUntil(timeoutMillis = 5000L) { onEdt { navigated && !visible.value } }
        assertEquals(1, deleted.get())
        assertNull(LocalComposerDrafts.get("tester", "topic_42"))
        onEdt { visible.value = true }
        waitUntil(timeoutMillis = 5000L) { onEdt { onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() } }
        onEdt {
            onAllNodesWithText("要删掉的旧草稿").assertCountEquals(0)
            onAllNodesWithText("保存草稿？").assertCountEquals(0)
        }
    }

    @Test
    fun siteTemplateInsertsInCurrentDraftFromMoreToolsAndTracksOnlySelectedTemplate() = runDesktopComposeUiTest(width = 650, height = 870) {
        val previous = UserApiKeyAuthManager.webViewHttpExecutor!!
        val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
        UserApiKeyAuthManager.webViewHttpExecutor = { url, method, form, headers ->
            when {
                url.endsWith("/discourse_templates") -> {
                    calls.add(method)
                    200 to """{"templates":[{"id":7,"title":"周报","content":"你好 %{my_username}","tags":["weekly"],"usages":2}]}"""
                }
                url.endsWith("/discourse_templates/7/use") -> { calls.add(method); 200 to "{}" }
                else -> previous(url, method, form, headers)
            }
        }
        setContent {
            MaterialTheme { ComposerSheet(action = ComposerAction.NEW_TOPIC, onDismiss = {}, onPublished = { error("must not publish") }) }
        }
        waitUntil(timeoutMillis = 5000L) { onEdt { onAllNodesWithContentDescription("更多工具").fetchSemanticsNodes().isNotEmpty() } }
        onEdt {
            val fields = onAllNodes(hasSetTextAction())
            fields.onFirst().performTextInput("我的标题")
            fields.onLast().performTextInput("开头结尾")
            fields.onLast().performTextInputSelection(androidx.compose.ui.text.TextRange(2))
            onNodeWithContentDescription("更多工具").performClick()
        }
        onEdt { onNodeWithText("插入模板").performScrollTo().performClick() }
        waitUntil(timeoutMillis = 5000L) { onEdt { onAllNodesWithText("周报").fetchSemanticsNodes().isNotEmpty() } }
        onEdt {
            onNodeWithText("搜索模板").performTextInput("weekly")
            onNodeWithText("周报").performClick()
        }
        waitUntil(timeoutMillis = 5000L) { calls.toList() == listOf("GET", "POST") }
        onEdt {
            onAllNodes(hasSetTextAction()).onFirst().assertTextContains("我的标题")
            onAllNodes(hasSetTextAction()).onLast().assertTextContains("开头\n\n你好 tester\n\n结尾")
        }
    }

    @Test
    fun newTopicDoesNotShowEagerValidationBannersUntilSubmitAttempted() = runDesktopComposeUiTest(
        width = 1100,
        height = 760
    ) {
        val palette = buildLinDoPalette(isDark = true, option = BrandThemeOption.OFFICIAL_GOLD)
        setContent {
            CompositionLocalProvider(
                LocalPalette provides palette,
                LocalLinDoDarkTheme provides true,
                LocalLinDoAnimations provides false
            ) {
                MaterialTheme(colorScheme = buildMaterialSchemeFromPalette(palette)) {
                    ComposerSheet(
                        action = ComposerAction.NEW_TOPIC,
                        initialCategoryId = 4,
                        onDismiss = {},
                        onPublished = {}
                    )
                }
            }
        }

        waitUntil(timeoutMillis = 5_000L) {
            onEdt { onAllNodesWithText("选择标签", substring = true).fetchSemanticsNodes().isNotEmpty() }
        }
        waitForIdle()

        // 1. On initial entry: must NOT show eager "需选标签：类版块组" or "请填写标题" or "还差"
        onEdt {
            onAllNodesWithText("需选标签：类版块组", substring = true).assertCountEquals(0)
            onAllNodesWithText("请填写标题", substring = true).assertCountEquals(0)
            onAllNodesWithText("还差", substring = true).assertCountEquals(0)
        }

        // 2. Typing 2 characters in title must NOT pop an eager "标题还差" row before clicking 发布
        onEdt {
            onAllNodes(hasSetTextAction()).onFirst().performTextInput("测试")
        }
        waitForIdle()
        onEdt {
            onAllNodesWithText("标题还差", substring = true).assertCountEquals(0)
            onAllNodesWithText("需选标签：类版块组", substring = true).assertCountEquals(0)
        }

        onEdt { onNodeWithContentDescription("预览").performClick() }
        onEdt { onNodeWithContentDescription("继续编辑").performClick() }
        onEdt { onAllNodes(hasSetTextAction()).onFirst().assertTextContains("测试") }

        // 3. Clicking 发布 triggers form-style inline validation highlights on the incomplete fields/chips
        waitUntil(timeoutMillis = 3_000L) {
            runCatching {
                onEdt { onNodeWithContentDescription("发布").assertIsEnabled() }
                true
            }.getOrDefault(false)
        }
        onEdt {
            onNodeWithContentDescription("发布").performClick()
        }
        waitUntil(timeoutMillis = 3_000L) {
            onEdt {
                onAllNodesWithText("需选标签：类版块组", substring = true).fetchSemanticsNodes().isNotEmpty() &&
                    onAllNodesWithText("标题还差", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
        }

        // 4. Completing the title clears the title error immediately without affecting other fields
        onEdt {
            onAllNodes(hasSetTextAction()).onFirst().performTextReplacement("测试一个完整合规的话题标题")
        }
        waitForIdle()
        onEdt {
            onAllNodesWithText("标题还差", substring = true).assertCountEquals(0)
        }
    }
}
