package do_.linux.lindo.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.SystemFont
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontSlant
import org.jetbrains.skia.FontStyle as SkiaFontStyle
import org.jetbrains.skia.FontWidth

@OptIn(ExperimentalTextApi::class)
private val cachedDesktopFontFamily: FontFamily? by lazy {
    val isMac = System.getProperty("os.name")?.contains("Mac", ignoreCase = true) == true
    if (!isMac) return@lazy null
    runCatching {
        val probe = FontMgr.default.matchFamilyStyle(
            "PingFang SC",
            SkiaFontStyle(400, FontWidth.NORMAL, FontSlant.UPRIGHT)
        ) ?: return@runCatching null
        if (!probe.familyName.contains("PingFang", ignoreCase = true)) {
            return@runCatching null
        }
        FontFamily(
            SystemFont("PingFang SC", FontWeight.Light, FontStyle.Normal),
            SystemFont("PingFang SC", FontWeight.Normal, FontStyle.Normal),
            SystemFont("PingFang SC", FontWeight.Medium, FontStyle.Normal),
            SystemFont("PingFang SC", FontWeight.SemiBold, FontStyle.Normal),
            SystemFont("PingFang SC", FontWeight.Bold, FontStyle.Normal),
            SystemFont("PingFang SC", FontWeight.ExtraBold, FontStyle.Normal)
        )
    }.getOrNull()
}

internal actual fun buildPlatformTypography(base: Typography): Typography {
    val family = cachedDesktopFontFamily ?: return base
    return Typography(
        displayLarge = base.displayLarge.copy(fontFamily = family),
        displayMedium = base.displayMedium.copy(fontFamily = family),
        displaySmall = base.displaySmall.copy(fontFamily = family),
        headlineLarge = base.headlineLarge.copy(fontFamily = family),
        headlineMedium = base.headlineMedium.copy(fontFamily = family),
        headlineSmall = base.headlineSmall.copy(fontFamily = family),
        titleLarge = base.titleLarge.copy(fontFamily = family),
        titleMedium = base.titleMedium.copy(fontFamily = family),
        titleSmall = base.titleSmall.copy(fontFamily = family),
        bodyLarge = base.bodyLarge.copy(fontFamily = family),
        bodyMedium = base.bodyMedium.copy(fontFamily = family),
        bodySmall = base.bodySmall.copy(fontFamily = family),
        labelLarge = base.labelLarge.copy(fontFamily = family),
        labelMedium = base.labelMedium.copy(fontFamily = family),
        labelSmall = base.labelSmall.copy(fontFamily = family)
    )
}
