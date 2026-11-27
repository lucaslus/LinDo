package do_.linux.lindo.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
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
