package do_.linux.lindo.core.badge

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MilitaryTech
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import do_.linux.lindo.core.model.BadgeType
import do_.linux.lindo.core.model.GrantedBadge
import do_.linux.lindo.core.site.LinuxDoSiteRules

/**
 * 徽章渲染决议结果（绝不静默丢失任何一枚新徽章）
 */
sealed interface ResolvedBadgeVisual {
    /** SVG 矢量徽章图片（支持 .svg 后缀或字节嗅探出的伪装 SVG） */
    data class SvgImage(val url: String, val tintColor: Color?) : ResolvedBadgeVisual

    /** 常规位图徽章图片（PNG / WebP / GIF） */
    data class BitmapImage(val url: String) : ResolvedBadgeVisual

    /** 从站点 `/svg-sprite/linux.do/svg-...` 动态同步出的自定义 `<symbol>` SVG 内容 */
    data class DynamicSpriteSvg(val symbolId: String, val svgXml: String, val tintColor: Color) : ResolvedBadgeVisual

    /** 归一化匹配到的高保真矢量图标 */
    data class VectorGlyph(val icon: ImageVector, val tintColor: Color) : ResolvedBadgeVisual

    /** 兜底勋章（当社区新增了尚未收录的纯文字 icon 名且无图片时，显示带徽章首字的等级勋章，绝不隐藏） */
    data class MedalFallback(val badgeInitial: String, val tintColor: Color) : ResolvedBadgeVisual
}

/**
 * 全格式徽章与站点动态 SVG Sprite 引擎
 *
 * 彻底解决 FluxDO / CFluxDo 的两大顽疾：
 * 1. `PostGrantedBadgeIcon` 把 `.svg` URL 直接喂给位图 `Image` 导致裂图消失；
 * 2. `FontAwesomeHelper` 静态表未收录的新徽章图标直接返回 `null` -> `SizedBox.shrink()` 吞掉徽章。
 */
object UniversalBadgeEngine {
    /**
     * 站点动态 SVG Sprite 注册表（支持运行期从 `/svg-sprite/linux.do/...` 增量注入）
     */
    private val customSpriteSymbols = mutableMapOf<String, String>()

    private val symbolRegex = Regex(
        pattern = """<symbol\s+[^>]*id=["']([^"']+)["'][^>]* viewBox=["']([^"']+)["'][^>]*>(.*?)</symbol>""",
        options = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    /**
     * 解析 Discourse `/svg-sprite/linux.do/svg-*.js` 响应中的 `<symbol id="..." viewBox="...">...</symbol>`
     * 并转换为可直接渲染的独立 `<svg>` XML。
     */
    fun registerDiscourseSpriteBundle(rawSpritePayload: String): Int {
        val unescaped = rawSpritePayload
            .replace("\\\"", "\"")
            .replace("\\/", "/")
            .replace("\\n", "\n")

        var count = 0
        for (match in symbolRegex.findAll(unescaped)) {
            val rawId = match.groupValues[1].trim().lowercase()
            val viewBox = match.groupValues[2].trim().ifEmpty { "0 0 512 512" }
            val innerPaths = match.groupValues[3].trim()
            if (rawId.isNotEmpty() && innerPaths.isNotEmpty()) {
                val standaloneSvg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="$viewBox">$innerPaths</svg>"""
                customSpriteSymbols[rawId] = standaloneSvg
                customSpriteSymbols[rawId.removePrefix("fa-").removePrefix("d-icon-")] = standaloneSvg
                count++
            }
        }
        return count
    }

    fun getRegisteredSpriteCount(): Int = customSpriteSymbols.size

    /**
     * 检测 URL 是否为 SVG
     */
    fun isSvgUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val cleanPath = url.substringBefore('?').substringBefore('#').lowercase()
        return cleanPath.endsWith(".svg")
    }

    /**
     * 字节级 SVG 魔数嗅探（防止服务端将 SVG 以 `.png` 或无扩展名返回导致位图解码器崩溃）
     */
    fun isSvgBytes(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val headLength = minOf(bytes.size, 512)
        val headText = bytes.decodeToString(0, headLength, throwOnInvalidSequence = false)
            .trimStart('\uFEFF', ' ', '\t', '\r', '\n')
            .lowercase()
        return headText.startsWith("<svg") ||
            (headText.startsWith("<?xml") && headText.contains("<svg")) ||
            (headText.startsWith("<!doctype svg") && headText.contains("<svg"))
    }

    /**
     * 根据徽章等级返回标准色值
     */
    fun badgeTierColor(type: BadgeType, isDark: Boolean = true): Color {
        return when (type) {
            BadgeType.GOLD -> if (isDark) Color(0xFFFFD54F) else Color(0xFFE5A100)
            BadgeType.SILVER -> if (isDark) Color(0xFFCFD8DC) else Color(0xFF78909C)
            BadgeType.BRONZE -> if (isDark) Color(0xFFFFAB91) else Color(0xFFCD7F32)
        }
    }

    /**
     * 归一化 FontAwesome / Discourse 图标名称（如 `fa-solid fa-crown` / `fas fa-medal` / `d-icon-certificate` -> `crown` / `medal` / `certificate`）
     */
    fun normalizeIconKey(rawIcon: String?): String {
        if (rawIcon.isNullOrBlank()) return ""
        return rawIcon.trim().lowercase()
            .replace("fa-solid", "")
            .replace("fa-regular", "")
            .replace("fa-brands", "")
            .replace("fa-light", "")
            .replace("fa-duotone", "")
            .replace(Regex("""\b(fas|far|fab|fal|fad|fa)\b"""), "")
            .trim()
            .removePrefix("d-icon-")
            .removePrefix("fa-")
            .trim()
    }

    /**
     * 决议任意徽章的渲染形态（保证 100% 可视，零丢失）
     */
    fun resolveBadgeVisual(badge: GrantedBadge, isDark: Boolean = true): ResolvedBadgeVisual {
        val tierColor = badgeTierColor(badge.badgeType, isDark)

        // 1. 优先处理 image_url（自动区分 SVG 与位图）
        if (!badge.imageUrl.isNullOrBlank()) {
            val fullUrl = LinuxDoSiteRules.resolveUrl(badge.imageUrl)
            return if (isSvgUrl(fullUrl)) {
                ResolvedBadgeVisual.SvgImage(url = fullUrl, tintColor = null)
            } else {
                ResolvedBadgeVisual.BitmapImage(url = fullUrl)
            }
        }

        // 2. 处理 icon 字段（支持 URL 型 icon、动态 SVG Sprite、内置 FA6 图标映射）
        val rawIcon = badge.icon?.trim().orEmpty()
        if (rawIcon.startsWith("http://") || rawIcon.startsWith("https://") || rawIcon.startsWith("/")) {
            val fullUrl = LinuxDoSiteRules.resolveUrl(rawIcon)
            return if (isSvgUrl(fullUrl)) {
                ResolvedBadgeVisual.SvgImage(url = fullUrl, tintColor = null)
            } else {
                ResolvedBadgeVisual.BitmapImage(url = fullUrl)
            }
        }

        val key = normalizeIconKey(rawIcon)
        if (key.isNotEmpty()) {
            // 2a. 先查站点动态同步的 SVG Sprite 表
            customSpriteSymbols[key]?.let { svgXml ->
                return ResolvedBadgeVisual.DynamicSpriteSvg(
                    symbolId = key,
                    svgXml = svgXml,
                    tintColor = tierColor
                )
            }

            // 2b. 再查内置高频及新上线徽章图标表
            matchVectorIcon(key)?.let { vector ->
                return ResolvedBadgeVisual.VectorGlyph(icon = vector, tintColor = tierColor)
            }
        }

        // 3. 终极兜底：绝不隐藏，显示带徽章名首字 + 对应金/银/铜配色的勋章
        val initial = badge.name.trim().firstOrNull()?.uppercase() ?: "★"
        return ResolvedBadgeVisual.MedalFallback(badgeInitial = initial, tintColor = tierColor)
    }

    private fun matchVectorIcon(key: String): ImageVector? {
        return when {
            key.contains("crown") || key.contains("king") -> Icons.Default.WorkspacePremium
            key.contains("trophy") || key.contains("champion") -> Icons.Default.EmojiEvents
            key.contains("certificate") || key.contains("verified") || key.contains("id-card") -> Icons.Default.Verified
            key.contains("shield") || key.contains("halved") || key.contains("security") -> Icons.Default.Shield
            key.contains("seedling") || key.contains("leaf") || key.contains("tree") -> Icons.Default.Park
            key.contains("rocket") || key.contains("shuttle") -> Icons.Default.RocketLaunch
            key.contains("fire") || key.contains("flame") || key.contains("hot") -> Icons.Default.LocalFireDepartment
            key.contains("bolt") || key.contains("lightning") || key.contains("flash") -> Icons.Default.Bolt
            key.contains("heart") || key.contains("love") -> Icons.Default.Favorite
            key.contains("star") || key.contains("sparkles") || key.contains("wand") || key.contains("magic") -> Icons.Default.AutoAwesome
            key.contains("gem") || key.contains("diamond") -> Icons.Default.Star
            key.contains("code") || key.contains("terminal") -> Icons.Default.Code
            key.contains("bug") || key.contains("spider") -> Icons.Default.BugReport
            key.contains("book") || key.contains("reader") || key.contains("graduation") -> Icons.Default.Book
            key.contains("calendar") || key.contains("clock") -> Icons.Default.CalendarMonth
            key.contains("cake") || key.contains("birthday") -> Icons.Default.Cake
            key.contains("gift") || key.contains("present") -> Icons.Default.CardGiftcard
            key.contains("coin") || key.contains("wallet") || key.contains("credit") || key.contains("ldc") -> Icons.Default.MonetizationOn
            key.contains("compass") || key.contains("map") -> Icons.Default.Explore
            key.contains("eye") || key.contains("view") -> Icons.Default.Visibility
            key.contains("thumbs-up") || key.contains("like") -> Icons.Default.ThumbUp
            key.contains("comment") || key.contains("reply") || key.contains("chat") -> Icons.AutoMirrored.Filled.Comment
            key.contains("share") || key.contains("retweet") -> Icons.Default.Share
            key.contains("link") || key.contains("anchor") -> Icons.Default.Link
            key.contains("pen") || key.contains("edit") || key.contains("pencil") -> Icons.Default.Edit
            key.contains("globe") || key.contains("earth") -> Icons.Default.Language
            key.contains("handshake") || key.contains("friend") || key.contains("user") -> Icons.Default.Handshake
            key.contains("check") -> Icons.Default.CheckCircle
            key.contains("lock") -> Icons.Default.Security
            key.contains("medal") || key.contains("award") || key.contains("ribbon") -> Icons.Default.MilitaryTech
            else -> null
        }
    }
}
