package do_.linux.lindo.core.site

import androidx.compose.ui.graphics.Color

/**
 * Linux.do 社区专属规则与站点定制引擎
 *
 * 完整对齐 Linux.do 网页端 CSS / 主题组件与自定义插件：
 * 1. `@neo` 方形头像（10% 圆角）+ `#00AEFF` 冰蓝脉冲光晕
 * 2. `g-merchant`（认证商家群组）`#F5BF03` 鎏金脉冲光晕
 * 3. `种子用户` / `Premium` 全息流光渐变头衔（Holographic Title）
 * 4. 静态缩略图 (`avatar_template`) 与动态头像原件 (`animated_avatar`) 双轨 URL 解析
 * 5. 外链风险分级拦截策略（Internal / Trusted / Risky / Dangerous / Blocked）
 */
object LinuxDoSiteRules {
    const val BASE_URL = "https://linux.do"

    private val squareAvatarUsernames = setOf("neo")
    private val holographicTitles = setOf("种子用户", "Premium")
    private val avatarUsernameRegex = Regex("""/user_avatar/[^/]+/([^/]+)/""")

    /**
     * 判断是否为方形头像（如站长 @neo 的 `border-radius: 10% !important`）
     */
    fun isSquareAvatar(username: String?, avatarUrl: String? = null): Boolean {
        if (!username.isNullOrBlank() && squareAvatarUsernames.contains(username.lowercase())) {
            return true
        }
        if (!avatarUrl.isNullOrBlank()) {
            val extracted = avatarUsernameRegex.find(avatarUrl)?.groupValues?.getOrNull(1)?.lowercase()
            if (extracted != null && squareAvatarUsernames.contains(extracted)) {
                return true
            }
        }
        return false
    }

    /**
     * 匹配头像呼吸光晕颜色（null 表示无光晕）
     */
    fun matchAvatarGlowColor(username: String?, primaryGroupName: String?): Color? {
        if (username?.equals("neo", ignoreCase = true) == true) {
            return Color(0xFF00AEFF) // 站长专属冰蓝光晕
        }
        if (primaryGroupName?.equals("g-merchant", ignoreCase = true) == true) {
            return Color(0xFFF5BF03) // 认证商家鎏金光晕
        }
        return null
    }

    /**
     * 判断用户头衔是否应使用全息流光渐变特效
     */
    fun isHolographicTitle(userTitle: String?): Boolean {
        return userTitle != null && holographicTitles.contains(userTitle.trim())
    }

    /**
     * 将相对路径或协议相对路径解析为完整 https URL
     */
    fun resolveUrl(rawUrl: String?): String {
        if (rawUrl.isNullOrBlank()) return ""
        val trimmed = rawUrl.trim()
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> "$BASE_URL$trimmed"
            else -> "$BASE_URL/$trimmed"
        }
    }

    /**
     * 解析静态缩略图 URL（用于秒显打底，仅几 KB）
     */
    fun resolveStaticAvatarUrl(avatarTemplate: String?, sizePx: Int = 120): String {
        if (avatarTemplate.isNullOrBlank()) return ""
        val sized = avatarTemplate.replace("{size}", sizePx.toString())
        return resolveUrl(sized)
    }

    /**
     * 解析动画头像原件 URL（GIF / WebP，用于首帧就绪后无缝覆盖静态底图）
     */
    fun resolveAnimatedAvatarUrl(animatedAvatar: String?): String? {
        if (animatedAvatar.isNullOrBlank()) return null
        return resolveUrl(animatedAvatar)
    }

    /**
     * 解析十六进制颜色字符串（支持 3位 / 6位 / 8位 hex）
     */
    fun parseHexColor(hexStr: String?, fallback: Color = Color(0xFF0088CC)): Color {
        if (hexStr.isNullOrBlank()) return fallback
        val clean = hexStr.trim().removePrefix("#")
        val normalized = when (clean.length) {
            3 -> "FF${clean[0]}${clean[0]}${clean[1]}${clean[1]}${clean[2]}${clean[2]}"
            6 -> "FF$clean"
            8 -> clean
            else -> return fallback
        }
        return normalized.toLongOrNull(16)?.let { Color(it) } ?: fallback
    }
}
