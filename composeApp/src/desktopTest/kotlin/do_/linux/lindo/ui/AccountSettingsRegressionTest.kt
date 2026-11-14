package do_.linux.lindo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import do_.linux.lindo.core.network.AuthenticatedUserProfile
import do_.linux.lindo.core.network.UserAccountPreferencesSnapshot
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class AccountSettingsRegressionTest {
    private fun <T> edt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private val snapshot = UserAccountPreferencesSnapshot(
        username = "reader", displayName = "社区读者", email = "a.long.account.address@example.test",
        bioRaw = "", title = "活跃用户", location = "", website = "", trustLevel = 3, createdAtText = "",
        twoFactorEnabled = null, hideProfileAndPresence = false, allowPrivateMessages = true,
        activeDeviceCount = null,
        associatedAccounts = listOf("google_oauth2 (reader.long.name@example.test)", "google_one_tap (reader.long.name@example.test)")
    )

    @Composable private fun Host(dark: Boolean, fontScale: Float = 1f, content: @Composable () -> Unit) {
        val p = buildLinDoPalette(dark, BrandThemeOption.OFFICIAL_GOLD)
        CompositionLocalProvider(LocalPalette provides p, LocalLinDoDarkTheme provides dark,
            LocalLinDoAnimations provides false, LocalDensity provides Density(1f, fontScale)) {
            MaterialTheme(colorScheme = buildMaterialSchemeFromPalette(p)) {
                Box(Modifier.fillMaxSize().background(p.canvas)) { content() }
            }
        }
    }

    private fun ComposeUiTest.save(name: String) {
        val directory = System.getenv("LINDO_PREVIEW_DIR") ?: return
        waitForIdle()
        val file = File(directory, name).apply { parentFile.mkdirs() }
        ImageIO.write(edt { onRoot().captureToImage().toAwtImage() }, "png", file)
    }

    @Test fun accountPreferencesNarrowDark() = runDesktopComposeUiTest(width = 390, height = 900) {
        verifyPreferences(true, 1f, "account-preferences-dark")
    }
    @Test fun accountPreferencesSmallLightLargeText() = runDesktopComposeUiTest(width = 320, height = 900) {
        verifyPreferences(false, 1.3f, "account-preferences-light-large-text")
    }
    @Test fun accountPreferencesWideDark() = runDesktopComposeUiTest(width = 1100, height = 900) {
        verifyPreferences(true, 1f, "account-preferences-wide")
    }

    private fun ComposeUiTest.verifyPreferences(dark: Boolean, scale: Float, name: String) {
        val links = mutableListOf<String>()
        var hide by mutableStateOf(false)
        var pm by mutableStateOf(true)
        var edits = 0
        setContent {
            Host(dark, scale) {
                AccountPreferencesContent(snapshot, hide, pm, false, { h, p -> hide = h; pm = p }, { edits++ }, links::add)
            }
        }
        waitForIdle()
        save("$name.png")
        edt {
            onNodeWithText("昵称与个人资料").performClick()
            assertEquals(1, edits)
            onNodeWithText("隐藏公开资料与在线状态").performScrollTo().performClick()
            assertTrue(hide)
            onNodeWithText("允许站内私信").performScrollTo().performClick()
            assertFalse(pm)
            onNodeWithText("Google 快捷登录").performScrollTo()
            val root = onRoot().fetchSemanticsNode().boundsInRoot
            onAllNodesWithText("reader.long.name@example.test").fetchSemanticsNodes().forEach {
                assertTrue(it.boundsInRoot.left >= root.left && it.boundsInRoot.right <= root.right)
            }
            onAllNodesWithText("google_oauth2", substring = true).assertCountEquals(0)
            onAllNodesWithText("1 个登录会话").assertCountEquals(0)
        }
        save("$name-security.png")
        edt {
            onNodeWithText("通知").performScrollTo().performClick()
            onNodeWithText("邮件").performScrollTo().performClick()
            onNodeWithText("全部网站设置").performScrollTo().performClick()
        }
        assertEquals(listOf("https://linux.do/my/preferences/notifications", "https://linux.do/my/preferences/emails", "https://linux.do/my/preferences"), links)
    }

    @Test fun accountManagementUsesRealIdentityAndCompactActions() = runDesktopComposeUiTest(width = 390, height = 820) {
        var logins = 0; var browsers = 0; var logouts = 0
        setContent {
            Host(true) {
                AccountSessionContent(AuthenticatedUserProfile(username = "reader", displayName = "社区读者", trustLevel = 3), null,
                    { logins++ }, { browsers++ }, { logouts++ })
            }
        }
        waitForIdle()
        save("account-management-dark.png")
        edt {
            onNodeWithText("社区读者").assertIsDisplayed()
            onNodeWithText("重新登录").performClick()
            onNodeWithText("系统浏览器授权").performClick()
            onNodeWithText("退出当前账号").performScrollTo().performClick()
        }
        assertEquals(1, logins); assertEquals(1, browsers); assertEquals(1, logouts)
    }
}
