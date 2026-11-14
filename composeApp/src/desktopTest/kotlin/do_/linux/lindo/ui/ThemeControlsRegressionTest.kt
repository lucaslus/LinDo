package do_.linux.lindo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import do_.linux.lindo.core.copy.ReaderFontScalePreset
import do_.linux.lindo.ui.components.LinDoIcons
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ThemeControlsRegressionTest {
    private fun <T> edt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }
    private fun ComposeUiTest.save(name: String) {
        val dir = System.getenv("LINDO_PREVIEW_DIR") ?: return
        waitForIdle()
        ImageIO.write(edt { onRoot().captureToImage().toAwtImage() }, "png", File(dir, name).apply { parentFile.mkdirs() })
    }
    @Test fun allThemesAndFiveSizesOnMobile() = runDesktopComposeUiTest(width = 390, height = 900) { verifyControls(true, 1f) }
    @Test fun narrowScreenWithLargeSystemTextKeepsChoicesReachable() = runDesktopComposeUiTest(width = 320, height = 1000) { verifyControls(false, 1.3f) }
    private fun ComposeUiTest.verifyControls(dark: Boolean, systemScale: Float) {
        var selected by mutableStateOf(BrandThemeOption.GEEK_BLUE)
        var preset by mutableStateOf(ReaderFontScalePreset.STANDARD)
        setContent {
            val palette = buildLinDoPalette(dark, selected)
            CompositionLocalProvider(LocalPalette provides palette, LocalDensity provides Density(1f, systemScale * preset.scale)) {
                MaterialTheme(colorScheme = buildMaterialSchemeFromPalette(palette)) {
                    Column(Modifier.fillMaxSize().background(palette.canvas).verticalScroll(rememberScrollState())) {
                        ExpandableSetting(LinDoIcons.Palette, "主题色", selected.label,
                            valueVisual = { SettingsThemeSwatch(selected) }) { SettingsThemeChoices(selected) { selected = it } }
                        ExpandableSetting(LinDoIcons.Sliders, "阅读字号", preset.labelCopy.text,
                            valueVisual = { SettingsFontPreview(preset.scale, palette.inkSecondary) }) {
                            SettingsChoices(ReaderFontScalePreset.entries, preset, { it.labelCopy.text }) { preset = it }
                            Text("预览：恰好的字号，让阅读更舒服。", color = palette.inkSecondary)
                        }
                    }
                }
            }
        }
        edt {
            onNodeWithText("蓝").assertExists()
            onNodeWithText("标准").assertExists()
            onNodeWithText("紫").assertDoesNotExist()
            onNodeWithText("最大").assertDoesNotExist()
        }
        save(if (dark) "settings-collapsed-dark.png" else "settings-collapsed-large-light.png")
        edt { onNodeWithText("主题色").performClick() }
        for (theme in BrandThemeOption.displayOrder) {
            edt { onNodeWithContentDescription(theme.label).performScrollTo().performClick() }
            waitForIdle()
            assertEquals(theme, selected)
            edt { onNodeWithContentDescription(theme.label).assertIsSelected() }
        }
        edt { onNodeWithText("阅读字号").performScrollTo().performClick() }
        for (size in ReaderFontScalePreset.entries) {
            edt { onNodeWithText(size.labelCopy.text).performScrollTo().performClick() }
            waitForIdle()
            assertEquals(size, preset)
            edt {
                onNodeWithText(size.labelCopy.text).assertIsSelected()
                val bounds = onNodeWithText(size.labelCopy.text).fetchSemanticsNode().boundsInRoot
                assertTrue(bounds.left >= 0 && bounds.right <= onRoot().fetchSemanticsNode().boundsInRoot.right)
            }
        }
        edt { onNodeWithContentDescription("金").performScrollTo().performClick() }
        save(if (dark) "settings-five-sizes-dark.png" else "settings-five-sizes-large-light.png")
        edt {
            onNodeWithText("主题色").performScrollTo().performClick()
            onNodeWithText("阅读字号").performScrollTo().performClick()
        }
        waitForIdle()
        edt {
            onNodeWithText("紫").assertDoesNotExist()
            onNodeWithText("标准").assertDoesNotExist()
            onNodeWithText("金").assertExists()
            onNodeWithText("最大").assertExists()
            onNodeWithText("阅读字号").performClick()
        }
        waitForIdle()
        edt { onNodeWithText("最大").assertIsSelected() }
    }
    @Test fun blueLoginUsesSameThemeAndBothLoginActions() = runDesktopComposeUiTest(width = 390, height = 840) {
        var primary = 0; var browser = 0; var guests = 0
        setContent {
            val p = buildLinDoPalette(true, BrandThemeOption.GEEK_BLUE)
            CompositionLocalProvider(LocalPalette provides p, LocalLinDoDarkTheme provides true, LocalLinDoAnimations provides false) {
                MaterialTheme(colorScheme = buildMaterialSchemeFromPalette(p)) {
                    LinDoWelcomeScreen(true, onPrimaryWebLogin = { primary++ }, onBrowserOneClickAuth = { browser++ },
                        onToggleDark = {}, onGuestBrowse = { guests++ })
                }
            }
        }
        save("login-blue-dark.png")
        edt {
            onNodeWithText("使用 LINUX DO 登录").performClick()
            onNodeWithText("浏览器授权").performClick()
            onNodeWithText("先逛逛").performClick()
        }
        assertEquals(1, primary); assertEquals(1, browser); assertEquals(1, guests)
    }
}
