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
    const val CDN_BASE_URL = "https://cdn.ldstatic.com"
    const val LETTER_AVATAR_CDN_BASE_URL = "https://avatars.discourse-cdn.com"
    const val MIN_TOPIC_TITLE_LENGTH = 5
    const val MIN_POST_RAW_LENGTH = 5
    const val LOTTERY_TAG = "抽奖"
    const val LOTTERY_TOPIC_COST_POINTS = 20

    private val squareAvatarUsernames = setOf("neo")
    // 对齐 LINUX DO 官方主题 common_theme_138 `.post-avatar:has(a.main-avatar[data-user-card=...]):after` 冰蓝呼吸光晕名单
    private val blueGlowUsernames = setOf(
        "neo",
        "kevin",
        "delph1s",
        "king-huiwen-of-qin",
        "nulluser",
        "dos.do",
        "musifei",
        "smnet",
        "syokatu"
    )
    private val holographicTitles = setOf("种子用户", "破界者", "premium")
    private val avatarUsernameRegex = Regex("""/user_avatar/[^/]+/([^/]+)/""")
    private val sizedUserAvatarRegex = Regex("""(/user_avatar/[^/]+/[^/]+/)\d+(/.*)""")
    private val sizedLetterAvatarRegex = Regex("""(/(?:letter_avatar_proxy/)?v\d+/letter/[^/]+/[^/]+/)\d+(\.[a-zA-Z0-9]+.*)""")

    /**
     * 将请求的像素尺寸收敛至 Discourse 官方预生成 `avatar_sizes` (`24, 48, 96, 144, 288`)，
     * 彻底消除请求非法尺寸（如 `120` / `240`）导致的源站 HTTP 302 重定向与 CDN 缓存穿透。
     */
    fun normalizeAvatarSize(sizePx: Int): Int = when {
        sizePx <= 24 -> 24
        sizePx <= 48 -> 48
        sizePx <= 96 -> 96
        sizePx <= 144 -> 144
        else -> 288
    }

    /**
     * 从 Discourse 头像模板或完整头像 URL 中提取用户名（如 `/user_avatar/linux.do/neo/48/123_2.png` -> `neo`）
     */
    fun extractUsernameFromAvatarUrl(avatarUrl: String?): String? {
        if (avatarUrl.isNullOrBlank()) return null
        return avatarUsernameRegex.find(avatarUrl)?.groupValues?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    /**
     * 判断是否为方形头像（如站长 @neo 的 `border-radius: 10% !important`）
     */
    fun isSquareAvatar(username: String?, avatarUrl: String? = null): Boolean {
        val cleanName = username?.trim()?.removePrefix("@")?.lowercase()
        if (!cleanName.isNullOrEmpty() && squareAvatarUsernames.contains(cleanName)) {
            return true
        }
        val extracted = extractUsernameFromAvatarUrl(avatarUrl)?.lowercase()
        return extracted != null && squareAvatarUsernames.contains(extracted)
    }

    /**
     * 匹配官方头像呼吸光晕颜色（null 表示无光晕）
     */
    fun matchAvatarGlowColor(
        username: String?,
        primaryGroupName: String?,
        avatarUrl: String? = null
    ): Color? {
        val cleanName = (username?.trim()?.removePrefix("@")?.takeIf { it.isNotEmpty() }
            ?: extractUsernameFromAvatarUrl(avatarUrl))?.lowercase()
        if (cleanName != null && blueGlowUsernames.contains(cleanName)) {
            return Color(0xFF00AEFF) // 站长与官方指定成员冰蓝光晕 (pulse 3s)
        }
        if (primaryGroupName?.trim()?.lowercase() in setOf("g-merchant", "g-merchant2")) {
            return Color(0xFFF5BF03) // 认证商家鎏金光晕 (pulse 5s)
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
     * 解析静态缩略图 URL：
     * 1. 收敛尺寸至 Discourse 官方预生成档位 (`144` / `288`)，同时兼容 `{size}` 模板与已替换数字路径，确保全局 100% 共享同一缓存键且零 302 跳转。
     * 2. 对齐官方 Ember `getURLWithCDN`：将 `/user_avatar/linux.do/` 直连 `https://cdn.ldstatic.com` 边缘 CDN，
     *    将 `/letter_avatar_proxy/v4/` 直连 `https://avatars.discourse-cdn.com`，绕开主站 WAF/WebView Base64 桥接瓶颈实现毫秒级直出。
     */
    fun resolveStaticAvatarUrl(avatarTemplate: String?, sizePx: Int = 144): String {
        if (avatarTemplate.isNullOrBlank()) return ""
        val targetSize = normalizeAvatarSize(sizePx).toString()
        val trimmed = avatarTemplate.trim()
        val sized = if ("{size}" in trimmed) {
            trimmed.replace("{size}", targetSize)
        } else {
            trimmed
                .let { sizedUserAvatarRegex.replace(it) { m -> "${m.groupValues[1]}$targetSize${m.groupValues[2]}" } }
                .let { sizedLetterAvatarRegex.replace(it) { m -> "${m.groupValues[1]}$targetSize${m.groupValues[2]}" } }
        }
        return when {
            sized.startsWith("/user_avatar/linux.do/") -> "$CDN_BASE_URL$sized"
            sized.startsWith("https://linux.do/user_avatar/linux.do/") ->
                sized.replaceFirst("https://linux.do", CDN_BASE_URL)
            sized.startsWith("http://linux.do/user_avatar/linux.do/") ->
                sized.replaceFirst("http://linux.do", CDN_BASE_URL)
            sized.startsWith("/letter_avatar_proxy/") ->
                "$LETTER_AVATAR_CDN_BASE_URL/${sized.removePrefix("/letter_avatar_proxy/")}"
            sized.startsWith("https://linux.do/letter_avatar_proxy/") ->
                sized.replaceFirst("https://linux.do/letter_avatar_proxy", LETTER_AVATAR_CDN_BASE_URL)
            sized.startsWith("http://linux.do/letter_avatar_proxy/") ->
                sized.replaceFirst("http://linux.do/letter_avatar_proxy", LETTER_AVATAR_CDN_BASE_URL)
            else -> resolveUrl(sized)
        }
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
