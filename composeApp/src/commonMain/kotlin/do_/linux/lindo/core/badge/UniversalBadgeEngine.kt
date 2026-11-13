package do_.linux.lindo.core.badge

import androidx.compose.ui.graphics.Color
import do_.linux.lindo.core.model.BadgeType
import do_.linux.lindo.core.model.GrantedBadge
import do_.linux.lindo.core.site.LinuxDoSiteRules

object UniversalBadgeEngine {
    const val OFFICIAL_ICON_BASE = "https://cdn.ldstatic.com/svg-sprite/linux.do/icon/"
    private val whitespace = Regex("\\s+")
    private val validIconId = Regex("[a-zA-Z0-9_-]+")

    // LinuxDo uses these same tier colors in both light and dark schemes.
    fun badgeTierColor(type: BadgeType): Color = when (type) {
        BadgeType.GOLD -> Color(0xFFE7C300)
        BadgeType.SILVER -> Color(0xFFC0C0C0)
        BadgeType.BRONZE -> Color(0xFFCD7F32)
    }

    fun artworkUrl(badge: GrantedBadge): String? =
        badge.imageUrl?.takeIf { it.isNotBlank() }?.let(LinuxDoSiteRules::resolveUrl)
            ?: iconUrl(badge.icon)

    fun defaultIconUrl(badge: GrantedBadge): String {
        val n = badge.name
        val iconName = when {
            badge.id in 45..47 || n.contains("全年不落") || n.contains("狂热") || n.contains("迷人") -> "far-eye"
            n.contains("受欢迎") || n.contains("喜爱") || n.contains("感激") || n.contains("赞") || n.contains("秋波") || n.contains("博爱") -> "heart"
            n.contains("善解人意") || n.contains("表情") -> "face-smile"
            badge.id == 102 || n.contains("种子") -> "seedling"
            n.contains("阅读") || n.contains("读者") -> "book-open-reader"
            n.contains("编辑") -> "pen"
            n.contains("链接") || n.contains("分享") -> "share-nodes"
            n.contains("解决方案") || n.contains("解答") -> "square-check"
            else -> "certificate"
        }
        return OFFICIAL_ICON_BASE + iconName + ".svg"
    }

    fun iconUrl(rawIcon: String?): String? {
        val tokens = rawIcon.orEmpty().trim().split(whitespace)
        val name = tokens.last().removePrefix("d-icon-").removePrefix("fa-")
        val prefix = when (tokens.first()) {
            "fab", "fa-brands" -> "fab-"
            "far", "fa-regular" -> "far-"
            else -> ""
        }
        if (!name.matches(validIconId)) return null
        return "$OFFICIAL_ICON_BASE$prefix$name.svg"
    }
}
