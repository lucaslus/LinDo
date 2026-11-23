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
        assertEquals(listOf(.88f, .94f, 1f, 1.12f, 1.28f), AppAppearance.fontScales)
        for (scale in listOf(.94f, 1f, 1.12f)) {
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
    @Test fun readingInkHierarchyPreventsDarkHalationAndPreservesContrast() {
        for (dark in listOf(false, true)) {
            val p = buildLinDoPalette(dark)
            val cPrimary = contrast(p.inkPrimary, p.canvas)
            val cBody = contrast(p.inkBody, p.canvas)
            val cSecondary = contrast(p.inkSecondary, p.canvas)
            val cTertiary = contrast(p.inkTertiary, p.canvas)
            assertTrue(cPrimary > cBody, "dark=$dark inkPrimary ($cPrimary) must exceed inkBody ($cBody)")
            assertTrue(cBody > cSecondary, "dark=$dark inkBody ($cBody) must exceed inkSecondary ($cSecondary)")
            assertTrue(cSecondary > cTertiary, "dark=$dark inkSecondary ($cSecondary) must exceed inkTertiary ($cTertiary)")
            assertTrue(cTertiary >= 4.5f, "dark=$dark inkTertiary contrast=$cTertiary must pass WCAG AA")
            assertTrue(
                contrast(p.inkSecondary, p.subtleSurface) >= 4.5f,
                "dark=$dark quote body inkSecondary on subtleSurface contrast=${contrast(p.inkSecondary, p.subtleSurface)}"
            )
            if (dark) {
                assertTrue(cPrimary <= 14.5f, "dark inkPrimary contrast=$cPrimary should stay anti-halation")
                assertTrue(cBody in 10.0f..12.5f, "dark inkBody contrast=$cBody should stay in comfortable reading zone")
            } else {
                assertTrue(cBody in 12.0f..15.5f, "light inkBody contrast=$cBody should stay comfortably darker than secondary")
            }
        }
    }
}
