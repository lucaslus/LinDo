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
    const val MIN_TOPIC_TITLE_LENGTH = 5
    const val MIN_POST_RAW_LENGTH = 5
    const val LOTTERY_TAG = "抽奖"
    const val LOTTERY_TOPIC_COST_POINTS = 20

    private val squareAvatarUsernames = setOf("neo")
    private val holographicTitles = setOf("种子用户", "破界者", "premium")
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
        if (primaryGroupName?.lowercase() in setOf("g-merchant", "g-merchant2")) {
            return Color(0xFFF5BF03) // 认证商家鎏金光晕
        }
        return null
    }

    /**
     * 判断用户头衔是否应使用全息流光渐变特效
     */
    fun isHolographicTitle(userTitle: String?): Boolean {
        return userTitle != null && holographicTitles.contains(userTitle.trim().lowercase())
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
     * 解析十六进制颜色字符串（支持 transparent / 3位 / 4位 / 6位 / CSS 8位 #RRGGBBAA）
     */
    fun parseHexColor(hexStr: String?, fallback: Color = Color(0xFF0088CC)): Color {
        if (hexStr.isNullOrBlank()) return fallback
        val trimmed = hexStr.trim()
        if (trimmed.equals("transparent", ignoreCase = true)) return Color.Transparent
        val clean = trimmed.removePrefix("#")
        val normalized = when (clean.length) {
            3 -> "FF${clean[0]}${clean[0]}${clean[1]}${clean[1]}${clean[2]}${clean[2]}"
            4 -> "${clean[3]}${clean[3]}${clean[0]}${clean[0]}${clean[1]}${clean[1]}${clean[2]}${clean[2]}"
            6 -> "FF$clean"
            8 -> clean.substring(6, 8) + clean.substring(0, 6)
            else -> return fallback
        }
        return normalized.toLongOrNull(16)?.let { Color(it) } ?: fallback
    }

    /**
     * 判断话题标签是否包含抽奖扣分提示（发布带 `抽奖` 标签的话题需消耗 20 积分）
     */
    fun requiresTopicCostConfirmation(tags: List<String>): Boolean {
        return tags.any { it.trim().equals(LOTTERY_TAG, ignoreCase = true) }
    }

    /**
     * 判断当前登录用户是否可删除指定楼层（主楼 #1 不可作为普通楼层删除，回复楼层遵循服务端 `can_delete` 或管理员权限）
     */
    fun canDeletePost(
        postNumber: Int,
        isOwnPost: Boolean,
        serverCanDelete: Boolean = false,
        isStaff: Boolean = false
    ): Boolean {
        if (isStaff) return true
        if (postNumber <= 1) return false
        return isOwnPost && serverCanDelete
    }

    fun canDeletePost(
        postNumber: Int,
        postUsername: String,
        currentUsername: String?,
        serverCanDelete: Boolean = false,
        isStaff: Boolean = false
    ): Boolean {
        val isOwn = !currentUsername.isNullOrBlank() && postUsername.equals(currentUsername, ignoreCase = true)
        return canDeletePost(
            postNumber = postNumber,
            isOwnPost = isOwn,
            serverCanDelete = serverCanDelete,
            isStaff = isStaff
        )
    }
}
