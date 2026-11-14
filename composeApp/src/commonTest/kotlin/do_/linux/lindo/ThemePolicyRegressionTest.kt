package do_.linux.lindo

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import do_.linux.lindo.core.copy.ReaderFontScalePreset
import do_.linux.lindo.ui.*
import kotlin.test.*

class ThemePolicyRegressionTest {
    private fun contrast(a: Color, b: Color): Float =
        (maxOf(a.luminance(), b.luminance()) + .05f) / (minOf(a.luminance(), b.luminance()) + .05f)

    @Test fun everyThemeHasReadableControlsInBothAppearances() {
        for (theme in BrandThemeOption.entries) for (dark in listOf(false, true)) {
            val p = buildLinDoPalette(dark, theme)
            val pairs = listOf(p.brandGold to p.canvas, p.activeChipText to p.activeChipBg,
                p.onAccent to p.brandGold, p.publishBtnContent to p.publishBtnBg)
            pairs.forEach { (foreground, background) ->
                assertTrue(contrast(foreground, background) >= 4.5f, "$theme dark=$dark contrast=${contrast(foreground, background)}")
            }
            val scheme = buildMaterialSchemeFromPalette(p)
            assertEquals(p.brandGold, scheme.primary)
            assertEquals(p.brandGold, scheme.secondary)
            assertEquals(p.activeChipBg, scheme.primaryContainer)
            assertEquals(buildLinDoPalette(dark).logoDoColor, p.logoDoColor)
            assertEquals(buildMaterialSchemeFromPalette(buildLinDoPalette(dark)).error, scheme.error)
            assertEquals(p.canvas, buildLinDoPalette(dark).canvas)
        }
    }
    @Test fun presetsExtendExistingStepsWithoutChangingSavedSizes() {
        assertEquals(listOf(.90f, .95f, 1f, 1.12f, 1.24f), AppAppearance.fontScales)
        for (scale in listOf(.95f, 1f, 1.12f)) {
            assertEquals(scale, AppAppearance.migrateFontScale(scale, .92f))
            assertEquals(scale, ReaderFontScalePreset.fromScale(scale).scale)
        }
        assertEquals(ReaderFontScalePreset.MINIMUM, ReaderFontScalePreset.fromScale(.1f))
        assertEquals(ReaderFontScalePreset.MAXIMUM, ReaderFontScalePreset.fromScale(2f))
        assertEquals(ReaderFontScalePreset.STANDARD, ReaderFontScalePreset.fromScale(Float.NaN))
        assertEquals(ReaderFontScalePreset.MINIMUM, ReaderFontScalePreset.MAXIMUM.nextCycle())
    }
    @Test fun persistedThemeKeysRemainCompatible() {
        for (key in listOf("OFFICIAL_GOLD", "IDEAL_RED", "GEEK_BLUE", "BAMBOO_GREEN", "SOFT_PURPLE", "CLEAR_TEAL", "WARM_ORANGE"))
            assertEquals(key, BrandThemeOption.fromKey(key).key)
        assertEquals(BrandThemeOption.OFFICIAL_GOLD, BrandThemeOption.fromKey("unknown"))
    }
}
