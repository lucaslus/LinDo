package do_.linux.lindo.core.badge

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import do_.linux.lindo.core.model.BadgeType
import do_.linux.lindo.core.model.GrantedBadge
import do_.linux.lindo.core.site.LinuxDoSiteRules
import do_.linux.lindo.ui.components.LinDoIcons

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
     * 识别是否为官方新上线的 `Only Apple Can Do` 苹果生态专属徽章
     */
    fun isAppleBadge(badge: GrantedBadge): Boolean {
        val nameLower = badge.name.trim().lowercase()
        val slugLower = badge.slug.trim().lowercase()
        val iconKey = normalizeIconKey(badge.icon)
        val imgLower = badge.imageUrl?.trim()?.lowercase().orEmpty()
        return nameLower.contains("only apple can do") ||
            nameLower == "apple" ||
            slugLower.contains("only-apple-can-do") ||
            slugLower == "apple" ||
            iconKey == "apple" ||
            iconKey.endsWith("-apple") ||
            iconKey.contains("fab-apple") ||
            imgLower.contains("only-apple-can-do") ||
            imgLower.endsWith("/apple.svg") ||
            imgLower.endsWith("/apple.png")
    }

    /**
     * Linux.do 全站核心徽章与纪念徽章官方图鉴（完整覆盖金/银/铜等级、专属头衔徽章与解锁规则）
     */
    val officialBadgeCatalog: List<GrantedBadge> = listOf(
        // ==================== 金徽章 (Gold, badgeTypeId = 1) ====================
        GrantedBadge(
            id = 100,
            name = "种子用户",
            description = "Linux.do 创站早期种子用户专属荣誉金徽章",
            longDescription = "授予在 Linux.do 创站初期加入并共同见证社区从零成长的早期种子成员。持有者可佩戴全站瞩目的全息流光「种子用户」专属头衔。",
            badgeTypeId = 1,
            icon = "seedling",
            slug = "seed-user",
            grantCount = 1200,
            allowTitle = true
        ),
        GrantedBadge(
            id = 18,
            name = "全年不落",
            description = "连续访问社区满 365 天（一整年风雨无阻、全年不落）",
            longDescription = "连续 365 天每天访问 Linux.do 社区，无一日间断。这是社区最高活跃度与忠诚度的象征，解锁后可佩戴荣耀金级「全年不落」头衔。",
            badgeTypeId = 1,
            icon = "fire",
            slug = "devotee",
            grantCount = 3850,
            allowTitle = true
        ),
        GrantedBadge(
            id = 102,
            name = "不二之选",
            description = "Linux.do 两周年盛典限定纪念金徽章",
            longDescription = "真诚、友善、团结、专业 —— 授予参与并见证 Linux.do 两周年里程碑的社区佬友，可佩戴「不二之选」限定纪念头衔。",
            badgeTypeId = 1,
            icon = "crown",
            slug = "second-to-none",
            grantCount = 26800,
            allowTitle = true
        ),
        GrantedBadge(
            id = 101,
            name = "一元复始",
            description = "Linux.do 周年与万象更新纪念金徽章",
            longDescription = "一元复始，万象更新。授予陪伴 Linux.do 走过周年庆典与辞旧迎新时刻的社区成员，可佩戴「一元复始」专属纪念头衔。",
            badgeTypeId = 1,
            icon = "sparkles",
            slug = "new-beginning",
            grantCount = 19500,
            allowTitle = true
        ),
        GrantedBadge(
            id = 105,
            name = "骐骥驰骋",
            description = "Linux.do 丙午马年新春限定纪念金徽章",
            longDescription = "骐骥一跃，驰骋万里。授予马年新春盛典期间活跃参与社区共建的佬友，可佩戴「骐骥驰骋」限定纪念头衔。",
            badgeTypeId = 1,
            icon = "trophy",
            slug = "galloping-steed",
            grantCount = 31200,
            allowTitle = true
        ),
        GrantedBadge(
            id = 106,
            name = "岂曰无衣?",
            description = "岂曰无衣？与子同袍 · 社区守望相助荣誉金徽章",
            longDescription = "授予在社区技术共建、开源互助与生态维护中展现同袍情谊的核心贡献者，可佩戴「岂曰无衣?」专属头衔。",
            badgeTypeId = 1,
            icon = "shield-halved",
            slug = "comrades-in-arms",
            grantCount = 680,
            allowTitle = true
        ),
        GrantedBadge(
            id = 107,
            name = "破界者",
            description = "Linux.do 破界创新先锋荣誉金徽章",
            longDescription = "授予在开源创作、前沿 AI 探索或微应用生态中突破边界的先锋创作者，可佩戴全息流光「破界者」头衔。",
            badgeTypeId = 1,
            icon = "bolt",
            slug = "breaker",
            grantCount = 420,
            allowTitle = true
        ),
        GrantedBadge(
            id = 108,
            name = "非我莫属",
            description = "Linux.do 社区杰出贡献与风采纪念金徽章",
            longDescription = "授予在社区专项活动、技术挑战或共建板块中表现卓越的成员，可佩戴「非我莫属」专属头衔。",
            badgeTypeId = 1,
            icon = "award",
            slug = "none-but-me",
            grantCount = 1560,
            allowTitle = true
        ),
        GrantedBadge(
            id = 4,
            name = "社区领袖",
            description = "达到 Lv.4 社区领袖（Leader）信任等级",
            longDescription = "授予全站最受信任的 Lv.4 楷模成员，协助维护社区良好风气、话题分类与高质量技术讨论，可佩戴「社区领袖」头衔。",
            badgeTypeId = 1,
            icon = "crown",
            slug = "leader",
            grantCount = 185,
            allowTitle = true
        ),
        GrantedBadge(
            id = 37,
            name = "万人迷",
            description = "在 300 个不同帖子中收到至少 5 个赞",
            longDescription = "长期输出高质量技术与生活分享，深受全站佬友喜爱，可佩戴「万人迷」头衔。",
            badgeTypeId = 1,
            icon = "heart",
            slug = "admired",
            grantCount = 540,
            allowTitle = true
        ),
        GrantedBadge(
            id = 35,
            name = "点赞狂魔",
            description = "累计 20 天用完每日全部可用点赞额度",
            longDescription = "毫不吝啬对他人分享的赞美与鼓励，累计 20 天触发每日送赞上限，可佩戴「点赞狂魔」头衔。",
            badgeTypeId = 1,
            icon = "heart",
            slug = "crazy-in-love",
            grantCount = 2100,
            allowTitle = true
        ),
        GrantedBadge(
            id = 23,
            name = "精彩的话题",
            description = "发布的单个话题获得 50 个以上赞",
            longDescription = "你发起的话题引发了全站广泛共鸣，收获了 50 个以上的赞与互动回应。",
            badgeTypeId = 1,
            icon = "fire",
            slug = "great-topic",
            grantCount = 4900,
            allowTitle = false
        ),
        GrantedBadge(
            id = 26,
            name = "出色的回复",
            description = "发表的单条回复获得 50 个以上赞",
            longDescription = "你的高质量技术解答或神仙回复获得了 50 位以上佬友的赞同。",
            badgeTypeId = 1,
            icon = "heart",
            slug = "great-reply",
            grantCount = 3600,
            allowTitle = false
        ),

        // ==================== 银徽章 (Silver, badgeTypeId = 2) ====================
        GrantedBadge(
            id = 9991,
            name = "Only Apple Can Do",
            description = "Linux.do 官方「Only Apple Can Do」Apple 生态专属徽章",
            longDescription = "授予 Apple 生态深度玩家与参与官方活动解锁的佬友。首次获取或佩戴该徽章时，昵称旁将点亮专属 Apple 标志 ，并可佩戴「Only Apple Can Do」头衔。",
            badgeTypeId = 2,
            icon = "fab-apple",
            slug = "only-apple-can-do",
            grantCount = 8600,
            allowTitle = true
        ),
        GrantedBadge(
            id = 17,
            name = "狂热",
            description = "连续访问社区满 100 天",
            longDescription = "连续 100 天每天访问 Linux.do 社区，保持高度热忱与活跃，解锁「狂热」银徽章与专属头衔。",
            badgeTypeId = 2,
            icon = "fire",
            slug = "aficionado",
            grantCount = 18400,
            allowTitle = true
        ),
        GrantedBadge(
            id = 3,
            name = "活跃用户",
            description = "达到 Lv.3 活跃会员（Regular）信任等级",
            longDescription = "在过去 100 天内保持高频访问、阅读、互动并遵守社区准则，晋升为 Lv.3 活跃会员，可佩戴「活跃用户」头衔并访问专属分区。",
            badgeTypeId = 2,
            icon = "bolt",
            slug = "regular",
            grantCount = 29500,
            allowTitle = true
        ),
        GrantedBadge(
            id = 24,
            name = "一周年",
            description = "加入社区满一周年且至少发布过一篇帖子",
            longDescription = "感谢你陪伴 Linux.do 走过整整一年时光！每年注册纪念日当天，你的发帖头部还会点亮专属纪念日小蛋糕 🍰。",
            badgeTypeId = 2,
            icon = "cake-candles",
            slug = "anniversary",
            grantCount = 42000,
            allowTitle = true
        ),
        GrantedBadge(
            id = 109,
            name = "博学",
            description = "广泛阅读社区文档与技术指南并完成深度互动",
            longDescription = "授予热衷于阅读和钻研社区技术文章的博学佬友，可佩戴「博学」头衔。",
            badgeTypeId = 2,
            icon = "book",
            slug = "learned",
            grantCount = 6200,
            allowTitle = true
        ),
        GrantedBadge(
            id = 110,
            name = "领航员",
            description = "Linux.do 社区生态领航与引导贡献银徽章",
            longDescription = "为新佬友指路、积极参与文档共建与技术答疑的社区领航员，可佩戴「领航员」头衔。",
            badgeTypeId = 2,
            icon = "compass",
            slug = "navigator",
            grantCount = 1450,
            allowTitle = true
        ),
        GrantedBadge(
            id = 38,
            name = "深得人心",
            description = "在 100 个不同帖子中收到至少 2 个赞",
            longDescription = "持续输出有价值的分享，在社区中深得人心，可佩戴「深得人心」头衔。",
            badgeTypeId = 2,
            icon = "heart",
            slug = "respected",
            grantCount = 4100,
            allowTitle = true
        ),
        GrantedBadge(
            id = 36,
            name = "广受好评",
            description = "在 20 个不同帖子中收到至少 1 个赞",
            longDescription = "你的多篇发言得到了社区成员的肯定与赞赏。",
            badgeTypeId = 2,
            icon = "thumbs-up",
            slug = "appreciated",
            grantCount = 24000,
            allowTitle = false
        ),
        GrantedBadge(
            id = 34,
            name = "一见钟情",
            description = "累计 5 天用完每日全部可用点赞额度",
            longDescription = "累计 5 天送出全部每日可用赞数，用行动鼓励社区创作者。",
            badgeTypeId = 2,
            icon = "heart",
            slug = "higher-love",
            grantCount = 9800,
            allowTitle = false
        ),
        GrantedBadge(
            id = 22,
            name = "热门的话题",
            description = "发布的单个话题获得 25 个以上赞",
            longDescription = "你发布的话题获得了 25 个以上的赞，成为社区热门讨论。",
            badgeTypeId = 2,
            icon = "fire",
            slug = "good-topic",
            grantCount = 11200,
            allowTitle = false
        ),
        GrantedBadge(
            id = 25,
            name = "佳作",
            description = "发表的单条回复获得 25 个以上赞",
            longDescription = "你的精彩回复获得了 25 个以上的赞。",
            badgeTypeId = 2,
            icon = "heart",
            slug = "good-reply",
            grantCount = 8900,
            allowTitle = false
        ),

        // ==================== 铜徽章 (Bronze, badgeTypeId = 3) ====================
        GrantedBadge(
            id = 16,
            name = "迷恋",
            description = "连续访问社区满 10 天",
            longDescription = "连续 10 天每天访问 Linux.do 社区，养成每日逛站的习惯，可佩戴「迷恋」头衔。",
            badgeTypeId = 3,
            icon = "fire",
            slug = "enthusiast",
            grantCount = 86000,
            allowTitle = true
        ),
        GrantedBadge(
            id = 2,
            name = "成员",
            description = "达到 Lv.2 成员（Member）信任等级",
            longDescription = "持续参与社区阅读与互动，晋升为 Lv.2 正式成员，解锁私信、邀请等更多社区权益。",
            badgeTypeId = 3,
            icon = "user",
            slug = "member",
            grantCount = 128000,
            allowTitle = true
        ),
        GrantedBadge(
            id = 1,
            name = "基本用户",
            description = "达到 Lv.1 基本用户（Basic）信任等级",
            longDescription = "完成基础阅读要求，解锁全部基础社区发帖、回复、上传图片与标记功能。",
            badgeTypeId = 3,
            icon = "check",
            slug = "basic-user",
            grantCount = 210000,
            allowTitle = false
        ),
        GrantedBadge(
            id = 41,
            name = "维基编辑",
            description = "参与编辑社区共建 Wiki 帖子",
            longDescription = "为社区共建文档或 Wiki 帖贡献了宝贵的修订内容，可佩戴「维基编辑」头衔。",
            badgeTypeId = 3,
            icon = "book",
            slug = "wiki-editor",
            grantCount = 3400,
            allowTitle = true
        ),
        GrantedBadge(
            id = 5,
            name = "首赞",
            description = "首次为他人的帖子送出赞",
            longDescription = "送出你在 Linux.do 的第一个赞，传递友善与认可。",
            badgeTypeId = 3,
            icon = "heart",
            slug = "first-like",
            grantCount = 195000,
            allowTitle = false
        ),
        GrantedBadge(
            id = 6,
            name = "受到欢迎",
            description = "首次收到他人送出的赞",
            longDescription = "你的发言收到了社区成员送出的第一个赞，欢迎来到 Linux.do！",
            badgeTypeId = 3,
            icon = "heart",
            slug = "welcome",
            grantCount = 168000,
            allowTitle = false
        ),
        GrantedBadge(
            id = 40,
            name = "首次表情回应",
            description = "首次使用多样表情回应帖子",
            longDescription = "使用自定义表情贴纸回应了一篇社区帖子。",
            badgeTypeId = 3,
            icon = "sparkles",
            slug = "first-reaction",
            grantCount = 142000,
            allowTitle = false
        ),
        GrantedBadge(
            id = 14,
            name = "首次引用",
            description = "首次在回复中引用他人发言",
            longDescription = "在回复中使用了引用块与佬友展开有条理的讨论。",
            badgeTypeId = 3,
            icon = "comment",
            slug = "first-quote",
            grantCount = 92000,
            allowTitle = false
        ),
        GrantedBadge(
            id = 15,
            name = "首次提及",
            description = "首次在帖子中 @提及 其他成员",
            longDescription = "在讨论中使用 @用户名 邀请其他成员参与交流。",
            badgeTypeId = 3,
            icon = "user",
            slug = "first-mention",
            grantCount = 88000,
            allowTitle = false
        ),
        GrantedBadge(
            id = 11,
            name = "首次分享",
            description = "首次分享话题链接",
            longDescription = "将有价值的 Linux.do 话题链接分享给更多朋友。",
            badgeTypeId = 3,
            icon = "share",
            slug = "first-share",
            grantCount = 54000,
            allowTitle = false
        ),
        GrantedBadge(
            id = 33,
            name = "恢复精力",
            description = "单日送出全部可用点赞额度",
            longDescription = "一天之内把每日赞数全部送完，感谢你的热情鼓励！",
            badgeTypeId = 3,
            icon = "bolt",
            slug = "out-of-love",
            grantCount = 31000,
            allowTitle = false
        ),
        GrantedBadge(
            id = 9,
            name = "自传作者",
            description = "完善个人资料简介与头像",
            longDescription = "填写了个人简介并上传了自定义头像，让大家更好地认识你。",
            badgeTypeId = 3,
            icon = "id-card",
            slug = "autobiographer",
            grantCount = 115000,
            allowTitle = false
        ),
        GrantedBadge(
            id = 8,
            name = "编辑",
            description = "首次编辑并完善自己的帖子",
            longDescription = "对已发布的帖子进行了排版修订或内容补充。",
            badgeTypeId = 3,
            icon = "pen",
            slug = "editor",
            grantCount = 136000,
            allowTitle = false
        ),
        GrantedBadge(
            id = 21,
            name = "出色的话题",
            description = "发布的单个话题获得 10 个以上赞",
            longDescription = "你发起的话题获得了 10 个以上的赞。",
            badgeTypeId = 3,
            icon = "thumbs-up",
            slug = "nice-topic",
            grantCount = 32000,
            allowTitle = false
        ),
        GrantedBadge(
            id = 20,
            name = "不错的回复",
            description = "发表的单条回复获得 10 个以上赞",
            longDescription = "你的回复获得了 10 个以上的赞。",
            badgeTypeId = 3,
            icon = "thumbs-up",
            slug = "nice-reply",
            grantCount = 28000,
            allowTitle = false
        ),
        GrantedBadge(
            id = 19,
            name = "读者",
            description = "完整阅读一篇超过 100 楼的长篇讨论话题",
            longDescription = "耐心读完了整篇超过 100 条回复的话题。",
            badgeTypeId = 3,
            icon = "book",
            slug = "reader",
            grantCount = 148000,
            allowTitle = false
        )
    )

    private val badgeTitleAliases: Map<String, String> = mapOf(
        "种子用户" to "种子用户",
        "seed user" to "种子用户",
        "全年不落" to "全年不落",
        "devotee" to "全年不落",
        "狂热" to "狂热",
        "aficionado" to "狂热",
        "迷恋" to "迷恋",
        "enthusiast" to "迷恋",
        "only apple can do" to "Only Apple Can Do",
        "apple" to "Only Apple Can Do",
        "不二之选" to "不二之选",
        "一元复始" to "一元复始",
        "骐骥驰骋" to "骐骥驰骋",
        "岂曰无衣?" to "岂曰无衣?",
        "岂曰无衣？" to "岂曰无衣?",
        "岂曰无衣" to "岂曰无衣?",
        "破界者" to "破界者",
        "非我莫属" to "非我莫属",
        "社区领袖" to "社区领袖",
        "领袖" to "社区领袖",
        "leader" to "社区领袖",
        "活跃用户" to "活跃用户",
        "活跃会员" to "活跃用户",
        "regular" to "活跃用户",
        "一周年" to "一周年",
        "两周年" to "不二之选",
        "博学" to "博学",
        "领航员" to "领航员",
        "深得人心" to "深得人心",
        "万人迷" to "万人迷",
        "点赞狂魔" to "点赞狂魔",
        "维基编辑" to "维基编辑",
        "成员" to "成员"
    )

    /**
     * 按 ID、名称或 Slug 查找官方徽章定义
     */
    fun lookupOfficialBadge(id: Int = 0, name: String? = null, slug: String? = null): GrantedBadge? {
        if (!name.isNullOrBlank()) {
            val clean = name.trim()
            val mappedName = badgeTitleAliases[clean.lowercase()] ?: clean
            officialBadgeCatalog.firstOrNull {
                it.name.equals(mappedName, ignoreCase = true) ||
                    it.name.equals(clean, ignoreCase = true)
            }?.let { return it }
            if (clean.contains("only apple can do", ignoreCase = true)) {
                return createOnlyAppleCanDoBadge(isFirstGrant = false)
            }
        }
        if (!slug.isNullOrBlank()) {
            val cleanSlug = slug.trim().lowercase()
            officialBadgeCatalog.firstOrNull { it.slug.equals(cleanSlug, ignoreCase = true) }?.let { return it }
            if (cleanSlug.contains("only-apple-can-do")) {
                return createOnlyAppleCanDoBadge(isFirstGrant = false)
            }
        }
        if (id > 0) {
            officialBadgeCatalog.firstOrNull { it.id == id }?.let { return it }
        }
        return null
    }

    /**
     * 根据用户佩戴的头衔 (`user_title`) 反查对应的官方荣誉徽章（例如 `种子用户`、`全年不落`、`Only Apple Can Do`、`不二之选`、`一元复始`、`骐骥驰骋`、`活跃用户` 等）
     */
    fun resolveBadgeForUserTitle(userTitle: String?): GrantedBadge? {
        if (userTitle.isNullOrBlank()) return null
        val clean = userTitle.trim()
        lookupOfficialBadge(name = clean)?.let { return it }
        if (clean.equals("Premium", ignoreCase = true)) {
            return GrantedBadge(
                id = 9992,
                name = "Premium",
                description = "Linux.do Premium 尊享会员专属身份徽章",
                longDescription = "支持社区长期发展与基础设施建设的 Premium 尊享会员，享有全息流光「Premium」专属头衔。",
                badgeTypeId = 1,
                icon = "crown",
                slug = "premium",
                allowTitle = true
            )
        }
        return null
    }

    /**
     * 徽章去重归一化 Key（确保同名徽章从顶层 `user_badges`、楼层 `badges_granted` 与 `user_title` 多源合并时不重复）
     */
    fun badgeDedupeKey(badge: GrantedBadge): String {
        if (isAppleBadge(badge)) return "badge_only_apple_can_do"
        val canonical = lookupOfficialBadge(name = badge.name, slug = badge.slug)
        if (canonical != null) {
            return "badge_${canonical.slug.ifBlank { canonical.name.lowercase() }}"
        }
        val cleanName = badge.name.trim().lowercase()
        if (cleanName.isNotEmpty() && !cleanName.startsWith("徽章 #")) {
            return "badge_name_$cleanName"
        }
        return "badge_id_${badge.id}"
    }

    /**
     * 合并多来源徽章列表，保留最完整的图标、长描述、首次解锁标记与获得次数，并按优先级排序
     */
    fun mergeBadgesPreservingRichness(badges: List<GrantedBadge>): List<GrantedBadge> {
        if (badges.isEmpty()) return emptyList()
        val mergedMap = LinkedHashMap<String, GrantedBadge>()
        for (b in badges) {
            val key = badgeDedupeKey(b)
            val official = lookupOfficialBadge(id = b.id, name = b.name, slug = b.slug)
            val enriched = b.copy(
                description = b.description.ifBlank { official?.description.orEmpty() },
                longDescription = b.longDescription.ifBlank { official?.longDescription.orEmpty() },
                badgeTypeId = if (b.badgeTypeId in 1..3 && (b.description.isNotBlank() || b.icon != null || b.imageUrl != null)) {
                    b.badgeTypeId
                } else {
                    official?.badgeTypeId ?: b.badgeTypeId
                },
                icon = b.icon?.takeIf { it.isNotBlank() } ?: official?.icon,
                slug = b.slug.ifBlank { official?.slug.orEmpty() },
                grantCount = maxOf(b.grantCount, official?.grantCount ?: 0),
                allowTitle = b.allowTitle || (official?.allowTitle == true)
            )
            val prev = mergedMap[key]
            if (prev == null) {
                mergedMap[key] = enriched
            } else {
                mergedMap[key] = prev.copy(
                    id = if (prev.id in 1..9000) prev.id else enriched.id,
                    description = prev.description.ifBlank { enriched.description },
                    longDescription = prev.longDescription.ifBlank { enriched.longDescription },
                    icon = prev.icon ?: enriched.icon,
                    imageUrl = prev.imageUrl ?: enriched.imageUrl,
                    slug = prev.slug.ifBlank { enriched.slug },
                    grantCount = maxOf(prev.grantCount, enriched.grantCount),
                    allowTitle = prev.allowTitle || enriched.allowTitle,
                    isFirstTimeGrant = prev.isFirstTimeGrant || enriched.isFirstTimeGrant,
                    count = maxOf(prev.count, enriched.count),
                    isEarned = prev.isEarned || enriched.isEarned
                )
            }
        }
        return mergedMap.values.sortedWith(
            compareByDescending<GrantedBadge> { it.isFirstTimeGrant }
                .thenByDescending { isAppleBadge(it) }
                .thenBy { it.badgeTypeId } // 1=金, 2=银, 3=铜
        )
    }

    /**
     * 创建官方 `Only Apple Can Do` 徽章对象（用于首次获取或头衔匹配注入）
     */
    fun createOnlyAppleCanDoBadge(isFirstGrant: Boolean = false): GrantedBadge {
        return GrantedBadge(
            id = 9991,
            name = "Only Apple Can Do",
            description = if (isFirstGrant) {
                "首次获取「Only Apple Can Do」官方徽章 · Apple 生态专属纪念"
            } else {
                "Linux.do 官方「Only Apple Can Do」Apple 生态专属徽章"
            },
            longDescription = "授予 Apple 生态深度玩家与参与官方活动解锁的佬友。首次获取或佩戴该徽章时，昵称旁将点亮专属 Apple 标志 ，并可佩戴「Only Apple Can Do」头衔。",
            badgeTypeId = 2,
            icon = "fab-apple",
            slug = "only-apple-can-do",
            grantCount = 8600,
            allowTitle = true,
            isFirstTimeGrant = isFirstGrant
        )
    }

    /**
     * 当位图或 SVG 远程图片加载失败时的矢量图标兜底
     */
    fun resolveFallbackIcon(badge: GrantedBadge): ImageVector {
        if (isAppleBadge(badge)) return LinDoIcons.AppleLogo
        val key = normalizeIconKey(badge.icon)
        if (key.isNotEmpty()) {
            matchVectorIcon(key)?.let { return it }
        }
        val nameKey = badge.name.trim().lowercase()
        matchVectorIcon(nameKey)?.let { return it }
        return LinDoIcons.AwardBadge
    }

    /**
     * 决议任意徽章的渲染形态（保证 100% 可视，零丢失）
     */
    fun resolveBadgeVisual(badge: GrantedBadge, isDark: Boolean = true): ResolvedBadgeVisual {
        val tierColor = badgeTierColor(badge.badgeType, isDark)

        // 0. 优先识别官方 `Only Apple Can Do` 徽章，直接渲染高精度苹果 Logo 矢量图标（彻底避免 .svg 位图解码失败）
        if (isAppleBadge(badge)) {
            val appleTint = if (isDark) Color(0xFFF5F5F7) else Color(0xFF1D1D1F)
            return ResolvedBadgeVisual.VectorGlyph(icon = LinDoIcons.AppleLogo, tintColor = appleTint)
        }

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

            // 2b. 再查内置高频及新上线徽章图标表（同时结合徽章名称语义，避免通用 fa-certificate 冲掉专属图标）
            val nameHint = badge.name.trim().lowercase()
            val semanticIcon = matchBadgeNameIcon(nameHint) ?: matchVectorIcon(key)
            if (semanticIcon != null) {
                return ResolvedBadgeVisual.VectorGlyph(icon = semanticIcon, tintColor = tierColor)
            }
        }

        // 2c. 当 icon 为空时，按徽章名称语义匹配专属矢量图标
        matchBadgeNameIcon(badge.name.trim().lowercase())?.let { semanticVector ->
            return ResolvedBadgeVisual.VectorGlyph(icon = semanticVector, tintColor = tierColor)
        }

        // 3. 终极兜底：绝不隐藏，显示带徽章名首字 + 对应金/银/铜配色的勋章
        val initial = badge.name.trim().firstOrNull()?.uppercase() ?: "★"
        return ResolvedBadgeVisual.MedalFallback(badgeInitial = initial, tintColor = tierColor)
    }

    private fun matchBadgeNameIcon(nameLower: String): ImageVector? {
        return when {
            nameLower.contains("apple") -> LinDoIcons.AppleLogo
            nameLower.contains("种子") || nameLower.contains("seed") -> LinDoIcons.Seedling
            nameLower.contains("全年不落") || nameLower.contains("狂热") || nameLower.contains("迷恋") ||
                nameLower.contains("热门") || nameLower.contains("精彩的话题") -> LinDoIcons.Flame
            nameLower.contains("不二之选") || nameLower.contains("领袖") || nameLower.contains("premium") -> LinDoIcons.Crown
            nameLower.contains("一元复始") || nameLower.contains("表情") -> LinDoIcons.Sparkles
            nameLower.contains("骐骥驰骋") -> LinDoIcons.Trophy
            nameLower.contains("岂曰无衣") -> LinDoIcons.ShieldCheck
            nameLower.contains("破界") || nameLower.contains("活跃") || nameLower.contains("恢复精力") -> LinDoIcons.Bolt
            nameLower.contains("周年") -> LinDoIcons.Gift
            nameLower.contains("博学") || nameLower.contains("维基") || nameLower.contains("读者") -> LinDoIcons.BookOpen
            nameLower.contains("领航") -> LinDoIcons.Compass
            nameLower.contains("非我莫属") -> LinDoIcons.AwardBadge
            nameLower.contains("万人迷") || nameLower.contains("点赞") || nameLower.contains("深得人心") ||
                nameLower.contains("一见钟情") || nameLower.contains("首赞") || nameLower.contains("欢迎") ||
                nameLower.contains("出色的回复") || nameLower.contains("佳作") -> LinDoIcons.Heart
            nameLower.contains("好评") || nameLower.contains("出色的话题") || nameLower.contains("不错的回复") -> LinDoIcons.ThumbUp
            nameLower.contains("编辑") -> LinDoIcons.Edit
            nameLower.contains("自传") -> LinDoIcons.VerifiedBadge
            nameLower.contains("引用") || nameLower.contains("回复") -> LinDoIcons.MessageCircle
            nameLower.contains("分享") -> LinDoIcons.Share
            nameLower.contains("基本用户") -> LinDoIcons.Check
            nameLower.contains("成员") || nameLower.contains("提及") -> LinDoIcons.Users
            else -> null
        }
    }

    private fun matchVectorIcon(key: String): ImageVector? {
        return when {
            key.contains("apple") -> LinDoIcons.AppleLogo
            key.contains("crown") || key.contains("king") || key.contains("chess-king") -> LinDoIcons.Crown
            key.contains("trophy") || key.contains("champion") || key.contains("horse") -> LinDoIcons.Trophy
            key.contains("certificate") || key.contains("verified") || key.contains("id-card") || key.contains("address-card") -> LinDoIcons.VerifiedBadge
            key.contains("shield") || key.contains("halved") || key.contains("security") -> LinDoIcons.ShieldCheck
            key.contains("seedling") || key.contains("leaf") || key.contains("tree") || key.contains("spa") -> LinDoIcons.Seedling
            key.contains("rocket") || key.contains("shuttle") || key.contains("paper-plane") -> LinDoIcons.Rocket
            key.contains("fire") || key.contains("flame") || key.contains("hot") || key.contains("burn") -> LinDoIcons.Flame
            key.contains("bolt") || key.contains("lightning") || key.contains("flash") -> LinDoIcons.Bolt
            key.contains("heart") || key.contains("love") -> LinDoIcons.Heart
            key.contains("star") || key.contains("sparkles") || key.contains("wand") || key.contains("magic") || key.contains("smile") || key.contains("face") -> LinDoIcons.Sparkles
            key.contains("gem") || key.contains("diamond") -> LinDoIcons.Star
            key.contains("code") || key.contains("terminal") -> LinDoIcons.Code
            key.contains("bug") || key.contains("spider") -> LinDoIcons.Bug
            key.contains("book") || key.contains("reader") || key.contains("graduation") || key.contains("mortar-board") -> LinDoIcons.BookOpen
            key.contains("calendar") || key.contains("clock") || key.contains("hourglass") -> LinDoIcons.Calendar
            key.contains("cake") || key.contains("birthday") || key.contains("candles") -> LinDoIcons.Gift
            key.contains("gift") || key.contains("present") -> LinDoIcons.Gift
            key.contains("coin") || key.contains("wallet") || key.contains("credit") || key.contains("ldc") -> LinDoIcons.Coins
            key.contains("compass") || key.contains("map") || key.contains("location") -> LinDoIcons.Compass
            key.contains("eye") || key.contains("view") -> LinDoIcons.Eye
            key.contains("thumbs-up") || key.contains("like") -> LinDoIcons.ThumbUp
            key.contains("comment") || key.contains("reply") || key.contains("chat") || key.contains("quote") -> LinDoIcons.MessageCircle
            key.contains("share") || key.contains("retweet") || key.contains("nodes") -> LinDoIcons.Share
            key.contains("link") || key.contains("anchor") -> LinDoIcons.Link
            key.contains("pen") || key.contains("edit") || key.contains("pencil") || key.contains("feather") -> LinDoIcons.Edit
            key.contains("globe") || key.contains("earth") -> LinDoIcons.Globe
            key.contains("handshake") || key.contains("friend") || key.contains("user") || key.contains("at") -> LinDoIcons.Users
            key.contains("check") -> LinDoIcons.Check
            key.contains("lock") -> LinDoIcons.Lock
            key.contains("medal") || key.contains("award") || key.contains("ribbon") || key.contains(" rosette") -> LinDoIcons.AwardBadge
            else -> null
        }
    }
}
