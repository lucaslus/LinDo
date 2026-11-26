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
        for (key in listOf("WARM_ORANGE", "OFFICIAL_GOLD", "IDEAL_RED", "GEEK_BLUE", "BAMBOO_GREEN", "SOFT_PURPLE", "CLEAR_TEAL"))
            assertEquals(key, BrandThemeOption.fromKey(key).key)
        assertEquals(BrandThemeOption.WARM_ORANGE, BrandThemeOption.fromKey("unknown"))
        assertEquals(BrandThemeOption.WARM_ORANGE, BrandThemeOption.displayOrder.first())
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

    @Test fun lightThemeProposalASoftPorcelainPreventsGlareAndYellowCast() {
        val light = buildLinDoPalette(isDark = false)
        assertEquals(Color(0xFFF4F6F8), light.canvas)
        assertEquals(Color(0xFFECEEF2), light.groupedCanvas)
        assertEquals(Color(0xFFF4F6F8), light.cardSurface)
        assertEquals(Color(0xFFECEEF2), light.feedSurface)
        assertEquals(Color(0xFFFAFBFD), light.elevatedCardSurface)
        assertEquals(Color(0xFFE7E9ED), light.subtleSurface)
        assertEquals(Color(0xFFE5E7EB), light.navBarSurface)
        assertEquals(Color(0xFFDFE1E5), light.tonalPill)
        assertEquals(Color(0xFF161A22), light.onTonalPill)
        assertEquals(Color(0xFF161A22), light.inkPrimary)
        assertEquals(Color(0xFF242933), light.inkBody)
        assertEquals(Color(0xFF4F5665), light.inkSecondary)
        assertEquals(Color(0xFF5F6776), light.inkTertiary)
        assertEquals(Color(0xFFD9DCE2), light.hairline)

        // Zero-yellow chromaticity guard: all neutral light surfaces satisfy R <= G <= B and B - R <= 6.
        val lightSurfaces = listOf(
            "elevatedCardSurface" to light.elevatedCardSurface,
            "canvas" to light.canvas,
            "feedSurface" to light.feedSurface,
            "subtleSurface" to light.subtleSurface,
            "navBarSurface" to light.navBarSurface,
            "tonalPill" to light.tonalPill
        )
        lightSurfaces.forEach { (name, color) ->
            val r = (color.red * 255f + 0.5f).toInt()
            val g = (color.green * 255f + 0.5f).toInt()
            val b = (color.blue * 255f + 0.5f).toInt()
            assertTrue(r <= g && g <= b, "$name must never have a yellow/warm cast (r=$r, g=$g, b=$b)")
            assertTrue(b - r in 0..6, "$name cool-porcelain tint must stay subtle (r=$r, b=$b)")
        }
        run {
            val r = (light.hairline.red * 255f + 0.5f).toInt()
            val g = (light.hairline.green * 255f + 0.5f).toInt()
            val b = (light.hairline.blue * 255f + 0.5f).toInt()
            assertTrue(r <= g && g <= b, "hairline must never have a yellow/warm cast (r=$r, g=$g, b=$b)")
        }

        // Strict luminance separation ladder across wide-split & single-pane tiers
        assertTrue(light.elevatedCardSurface.luminance() > light.canvas.luminance())
        assertTrue(light.canvas.luminance() > light.feedSurface.luminance())
        assertTrue(light.feedSurface.luminance() > light.subtleSurface.luminance())
        assertTrue(light.subtleSurface.luminance() > light.navBarSurface.luminance())
        assertTrue(light.navBarSurface.luminance() > light.tonalPill.luminance())

        // Tertiary ink and every brand accent remain WCAG AA (>= 4.5:1) on both canvas and wide-split feedSurface
        assertTrue(contrast(light.inkTertiary, light.canvas) >= 4.5f)
        assertTrue(contrast(light.inkTertiary, light.feedSurface) >= 4.5f)
        assertTrue(contrast(light.inkTertiary, light.subtleSurface) >= 4.5f)
        for (theme in BrandThemeOption.entries) {
            val themed = buildLinDoPalette(isDark = false, option = theme)
            assertTrue(contrast(themed.brandGold, themed.feedSurface) >= 4.5f, "$theme brandGold on feedSurface")
        }

        // Dark mode stays strictly untouched
        val dark = buildLinDoPalette(isDark = true)
        assertEquals(Color(0xFF101217), dark.canvas)
        assertEquals(Color(0xFF101217), dark.feedSurface)
        assertEquals(Color(0xFF181B22), dark.subtleSurface)
        assertEquals(Color(0xFF181B22), dark.elevatedCardSurface)
        assertEquals(Color(0xFFDADBDF), dark.inkPrimary)
    }
}
