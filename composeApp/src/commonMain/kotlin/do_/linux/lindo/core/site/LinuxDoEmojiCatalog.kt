package do_.linux.lindo.core.site

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.ConcurrentHashMap

/**
 * 单个官方表情或社区自定义表情包条目
 */
data class LinuxDoEmojiItem(
    val shortcode: String,
    val unicode: String? = null,
    val customUrl: String? = null,
    val group: String = "default",
    val keywordsZh: String = ""
) {
    val markdownCode: String get() = ":${shortcode.lowercase()}:"

    val resolvedImageUrl: String
        get() = if (!customUrl.isNullOrBlank()) {
            LinuxDoSiteRules.resolveUrl(customUrl)
        } else {
            LinuxDoEmojiCatalog.resolveReactionImageUrl(shortcode)
        }
}

/**
 * 表情选择器分组
 *
 * @param id 分组唯一标识
 * @param labelZh 纯净中文分组名称（不含任何统一星星前缀）
 * @param items 分组内的表情条目列表
 * @param iconKey 官方分类图标或品牌 Logo 标识（如 `feishu`、`bilibili`、`tieba`、`smileys`、`people`、`animals`、`food` 等）
 * @param iconUnicode 标准分类对应的独立代表性 Emoji（如 😀、👋、🐻、🍔、✈️、⚽、💡、🔣、🏁）
 * @param iconUrl 自定义表情包的代表性图标 URL
 */
data class LinuxDoEmojiGroup(
    val id: String,
    val labelZh: String,
    val items: List<LinuxDoEmojiItem>,
    val iconKey: String = id,
    val iconUnicode: String? = null,
    val iconUrl: String? = null
)

/**
 * Linux.do 官方表情包与 Discourse Reactions 统一注册表 (`LinuxDoEmojiCatalog`)
 *
 * 1. 对齐 Linux.do 官方开启的楼层表情表态 (`discourse-reactions`)；
 * 2. 支持从 `https://linux.do/emojis.json` 动态同步站点全量自定义表情包（如飞书、贴吧、B站、小黄脸等分组）；
 * 3. 提供 150+ 标准 Discourse / Twemoji Shortcode 双向映射，彻底消除未知表情被误显示为红心 `❤️` 或裸露 `:shortcode:` 的问题。
 */
object LinuxDoEmojiCatalog {

    /**
     * Linux.do 官方楼层快捷表态集合（严格对齐站点 `discourse_reactions_enabled_reactions` 白名单）
     */
    val officialReactions: List<LinuxDoEmojiItem> = listOf(
        LinuxDoEmojiItem("heart", "❤️", keywordsZh = "爱心 点赞 喜欢 心心"),
        LinuxDoEmojiItem("+1", "👍", keywordsZh = "赞 赞同 大拇指 强"),
        LinuxDoEmojiItem("laughing", "😆", keywordsZh = "大笑 哈哈 笑死"),
        LinuxDoEmojiItem("open_mouth", "😮", keywordsZh = "惊讶 哇 卧槽 吃惊"),
        LinuxDoEmojiItem("clap", "👏", keywordsZh = "鼓掌 啪啪 好棒"),
        LinuxDoEmojiItem("confetti_ball", "🎊", keywordsZh = "庆祝 撒花 恭喜 礼花"),
        LinuxDoEmojiItem("hugs", "🤗", keywordsZh = "抱抱 拥抱 贴贴 安慰"),
        LinuxDoEmojiItem("distorted_face", "🫠", keywordsZh = "融化 裂开 麻了 扭曲"),
        LinuxDoEmojiItem("tieba_087", null, keywordsZh = "贴吧 滑稽 狗头"),
        LinuxDoEmojiItem("bili_057", null, keywordsZh = "B站 小电视 赞")
    )

    /**
     * 小火箭 Boost (`discourse-boosts`) 一键快捷助力短语（表情 + 16 字内短评）
     */
    val quickBoostPresets: List<Pair<String, String>> = listOf(
        "✋ 俺也一样" to "✋ 俺也一样",
        "🚀 前排支持" to "🚀 前排支持",
        "🎉 感谢分享" to "🎉 感谢分享",
        "🔥 太强了" to "🔥 太强了",
        "❤️ 学到了" to "❤️ 学到了",
        "🫡 瑞思拜" to "🫡 瑞思拜",
        "🤣 笑死" to "🤣 笑死",
        "🫠 破防了" to "🫠 破防了"
    )

    private val dynamicCustomUrls = ConcurrentHashMap<String, String>()

    @Volatile
    private var dynamicServerGroups: List<LinuxDoEmojiGroup> = emptyList()

    var catalogVersion by mutableIntStateOf(0)
        private set

    fun hasSyncedServerEmojis(): Boolean = dynamicServerGroups.isNotEmpty()

    fun registerSingleEmojiUrl(shortcode: String, rawUrl: String) {
        val cleanName = shortcode.trim().removeSurrounding(":").lowercase()
        val cleanUrl = rawUrl.trim()
        if (cleanName.isNotEmpty() && cleanUrl.isNotEmpty()) {
            val resolved = LinuxDoSiteRules.resolveUrl(cleanUrl)
            val prev = dynamicCustomUrls.put(cleanName, resolved)
            if (prev != resolved) {
                catalogVersion++
            }
        }
    }

    private data class GroupMetaSpec(
        val labelZh: String,
        val iconKey: String,
        val iconUnicode: String? = null,
        val isBrandPack: Boolean = false
    )

    private fun resolveGroupMeta(rawKey: String): GroupMetaSpec {
        val clean = rawKey.removePrefix("✨").trim()
        val key = clean.lowercase().replace('-', '_').replace(' ', '_')
        return when {
            key == "smileys_&_emotion" || key == "smileys_&_people" || key == "smileys" || clean == "笑脸" ->
                GroupMetaSpec("笑脸", "smileys", "😀")
            key == "people_&_body" || key == "gestures" || clean == "人物与手势" ->
                GroupMetaSpec("人物与手势", "people", "👋")
            key == "animals_&_nature" || key == "animals" || clean == "动物与自然" ->
                GroupMetaSpec("动物与自然", "animals", "🐻")
            key == "food_&_drink" || key == "food" || clean == "食物与饮品" ->
                GroupMetaSpec("食物与饮品", "food", "🍔")
            key == "travel_&_places" || key == "travel" || clean == "旅行与地点" ->
                GroupMetaSpec("旅行与地点", "travel", "✈️")
            key == "activities" || clean == "活动" ->
                GroupMetaSpec("活动", "activities", "⚽")
            key == "objects" || key == "geek_celebration" || clean == "物品" ->
                GroupMetaSpec("物品", "objects", "💡")
            key == "symbols" || clean == "符号" ->
                GroupMetaSpec("符号", "symbols", "🔣")
            key == "flags" || clean == "旗帜" ->
                GroupMetaSpec("旗帜", "flags", "🏁")
            key.contains("feishu") || key.contains("lark") || clean.contains("飞书") ->
                GroupMetaSpec("飞书", "feishu", null, isBrandPack = true)
            key.contains("bili") || clean.contains("B站", ignoreCase = true) || clean.contains("小电视") ->
                GroupMetaSpec("B站", "bilibili", null, isBrandPack = true)
            key.contains("tieba") || clean.contains("贴吧") || clean.contains("滑稽") ->
                GroupMetaSpec("贴吧", "tieba", null, isBrandPack = true)
            key == "qq" || clean.contains("小黄脸") || clean.contains("QQ", ignoreCase = true) ->
                GroupMetaSpec("小黄脸", "qq", null, isBrandPack = true)
            key.contains("coolapk") || clean.contains("酷安") ->
                GroupMetaSpec("酷安", "coolapk", null, isBrandPack = true)
            key.contains("weibo") || clean.contains("微博") ->
                GroupMetaSpec("微博", "weibo", null, isBrandPack = true)
            key.contains("xhs") || key.contains("xiaohongshu") || clean.contains("小红书") ->
                GroupMetaSpec("小红书", "xhs", null, isBrandPack = true)
            key.contains("acfun") || clean.contains("AC娘", ignoreCase = true) ->
                GroupMetaSpec("AC娘", "acfun", null, isBrandPack = true)
            key.contains("menhera") || clean.contains("胡桃") ->
                GroupMetaSpec("七濑胡桃", "menhera", null, isBrandPack = true)
            key.contains("doraemon") || clean.contains("哆啦") ->
                GroupMetaSpec("哆啦A梦", "doraemon", null, isBrandPack = true)
            key.contains("aru") || clean.contains("阿鲁") ->
                GroupMetaSpec("阿鲁", "aru", null, isBrandPack = true)
            key.contains("onion") || clean.contains("洋葱") ->
                GroupMetaSpec("洋葱头", "onion", null, isBrandPack = true)
            key.contains("blob") || clean.contains("果冻") ->
                GroupMetaSpec("果冻Blob", "blob", null, isBrandPack = true)
            key == "default" || key == "ungrouped" ->
                GroupMetaSpec("社区精选", "custom_pack", "🎭", isBrandPack = true)
            else ->
                GroupMetaSpec(clean, "custom_$key", null, isBrandPack = true)
        }
    }

    /**
     * 注册来自 `https://linux.do/emojis.json` 的站点自定义表情包分组
     */
    fun registerDynamicServerEmojis(rawGroups: Map<String, List<Pair<String, String>>>) {
        if (rawGroups.isEmpty()) return
        val brandGroups = mutableListOf<LinuxDoEmojiGroup>()
        val standardGroups = mutableListOf<LinuxDoEmojiGroup>()
        val seenIconKeys = HashSet<String>()

        for ((groupKey, entries) in rawGroups) {
            if (entries.isEmpty()) continue
            val cleanGroupKey = groupKey.trim()
            val meta = resolveGroupMeta(cleanGroupKey)
            val items = entries.mapNotNull { (rawName, rawUrl) ->
                val cleanName = rawName.trim().removeSurrounding(":")
                val cleanUrl = rawUrl.trim()
                if (cleanName.isEmpty() || cleanUrl.isEmpty()) return@mapNotNull null
                val fullUrl = LinuxDoSiteRules.resolveUrl(cleanUrl)
                dynamicCustomUrls[cleanName.lowercase()] = fullUrl
                LinuxDoEmojiItem(
                    shortcode = cleanName,
                    unicode = builtInShortcodeToUnicode[cleanName.lowercase()],
                    customUrl = fullUrl,
                    group = cleanGroupKey,
                    keywordsZh = "${meta.labelZh} $cleanName"
                )
            }
            if (items.isNotEmpty()) {
                // 如果 default 分组里混有按前缀命名的飞书/B站/贴吧等自定义包，自动按品牌前缀拆分为独立品牌分组
                val normalizedKey = cleanGroupKey.lowercase()
                if (normalizedKey == "default" || normalizedKey == "ungrouped") {
                    val prefixBuckets = mapOf(
                        "feishu" to items.filter { it.shortcode.startsWith("feishu_", true) || it.shortcode.startsWith("lark_", true) },
                        "bilibili" to items.filter { it.shortcode.startsWith("bili_", true) || it.shortcode.startsWith("bilibili_", true) },
                        "tieba" to items.filter { it.shortcode.startsWith("tieba_", true) },
                        "qq" to items.filter { it.shortcode.startsWith("qq_", true) }
                    )
                    val groupedShortcodes = HashSet<String>()
                    for ((brandKey, brandItems) in prefixBuckets) {
                        if (brandItems.isNotEmpty() && seenIconKeys.add(brandKey)) {
                            val bMeta = resolveGroupMeta(brandKey)
                            brandItems.forEach { groupedShortcodes.add(it.shortcode) }
                            brandGroups.add(
                                LinuxDoEmojiGroup(
                                    id = "server_$brandKey",
                                    labelZh = bMeta.labelZh,
                                    items = brandItems,
                                    iconKey = bMeta.iconKey,
                                    iconUnicode = bMeta.iconUnicode,
                                    iconUrl = brandItems.firstOrNull()?.resolvedImageUrl
                                )
                            )
                        }
                    }
                    val remaining = items.filterNot { it.shortcode in groupedShortcodes }
                    if (remaining.isNotEmpty()) {
                        brandGroups.add(
                            LinuxDoEmojiGroup(
                                id = "server_$cleanGroupKey",
                                labelZh = meta.labelZh,
                                items = remaining,
                                iconKey = meta.iconKey,
                                iconUnicode = meta.iconUnicode,
                                iconUrl = remaining.firstOrNull()?.resolvedImageUrl
                            )
                        )
                    }
                } else {
                    seenIconKeys.add(meta.iconKey)
                    val group = LinuxDoEmojiGroup(
                        id = "server_$cleanGroupKey",
                        labelZh = meta.labelZh,
                        items = items,
                        iconKey = meta.iconKey,
                        iconUnicode = meta.iconUnicode,
                        iconUrl = if (meta.isBrandPack) items.firstOrNull()?.resolvedImageUrl else null
                    )
                    if (meta.isBrandPack) {
                        brandGroups.add(group)
                    } else {
                        standardGroups.add(group)
                    }
                }
            }
        }
        val combined = brandGroups + standardGroups
        if (combined.isNotEmpty()) {
            dynamicServerGroups = combined
        }
        catalogVersion++
    }

    /**
     * 内置社区热梗与常用表情组
     */
    private val communityMemeEmojis: List<LinuxDoEmojiItem> = listOf(
        LinuxDoEmojiItem("distorted_face", "🫠", keywordsZh = "融化 裂开 麻了 扭曲"),
        LinuxDoEmojiItem("saluting_face", "🫡", keywordsZh = "致敬 敬礼 收到"),
        LinuxDoEmojiItem("holding_back_tears", "🥹", keywordsZh = "感动 强忍泪水 破防"),
        LinuxDoEmojiItem("peeking_eye", "🫣", keywordsZh = "偷看 捂脸看 害羞"),
        LinuxDoEmojiItem("face_with_diagonal_mouth", "🫤", keywordsZh = "撇嘴 无语 难评"),
        LinuxDoEmojiItem("dotted_line_face", "🫥", keywordsZh = "隐身 消失 透明人"),
        LinuxDoEmojiItem("shaking_face", "🫨", keywordsZh = "瞳孔地震 震惊 晃动"),
        LinuxDoEmojiItem("clown_face", "🤡", keywordsZh = "小丑 竟是我自己"),
        LinuxDoEmojiItem("nerd_face", "🤓", keywordsZh = "书呆子 懂王 眼镜"),
        LinuxDoEmojiItem("face_with_monocle", "🧐", keywordsZh = "端详 细品 审视"),
        LinuxDoEmojiItem("sunglasses", "😎", keywordsZh = "墨镜 酷 装逼 大佬"),
        LinuxDoEmojiItem("smirk", "😏", keywordsZh = "滑稽 坏笑 邪魅 狗头"),
        LinuxDoEmojiItem("upside_down_face", "🙃", keywordsZh = "倒脸 呵呵 微笑面对"),
        LinuxDoEmojiItem("roll_eyes", "🙄", keywordsZh = "翻白眼 无语 呵呵"),
        LinuxDoEmojiItem("sweat_smile", "😅", keywordsZh = "流汗黄豆 尴尬 汗"),
        LinuxDoEmojiItem("pleading_face", "🥺", keywordsZh = "可怜 求求 委屈 球球了"),
        LinuxDoEmojiItem("exploding_head", "🤯", keywordsZh = "头脑风暴 炸裂 脑洞"),
        LinuxDoEmojiItem("scream", "😱", keywordsZh = "尖叫 惊恐 天呐"),
        LinuxDoEmojiItem("rage", "😡", keywordsZh = "愤怒 气死 红温"),
        LinuxDoEmojiItem("skull", "💀", keywordsZh = "骷髅 笑死 寄 凉了"),
        LinuxDoEmojiItem("ghost", "👻", keywordsZh = "幽灵 鬼 溜了"),
        LinuxDoEmojiItem("lemon", "🍋", keywordsZh = "柠檬 酸了 慕了 我酸了"),
        LinuxDoEmojiItem("pill", "💊", keywordsZh = "药丸 吃药 要完"),
        LinuxDoEmojiItem("dog", "🐶", keywordsZh = "狗头 保命 汪"),
        LinuxDoEmojiItem("cat", "🐱", keywordsZh = "猫猫 喵 摸鱼"),
        LinuxDoEmojiItem("fox_face", "🦊", keywordsZh = "狐狸 狡猾"),
        LinuxDoEmojiItem("whale", "🐳", keywordsZh = "鲸鱼 Docker 大户"),
        LinuxDoEmojiItem("penguin", "🐧", keywordsZh = "企鹅 Linux 腾讯"),
        LinuxDoEmojiItem("bug", "🐛", keywordsZh = "虫子 漏洞 Bug"),
        LinuxDoEmojiItem("unicorn", "🦄", keywordsZh = "独角兽 稀有"),
        LinuxDoEmojiItem("trollface", "😏", keywordsZh = "滑稽 逗你玩"),
        LinuxDoEmojiItem("poop", "💩", keywordsZh = "便便 屎山代码")
    )

    /**
     * 笑脸与情绪标准组
     */
    private val smileyEmojis: List<LinuxDoEmojiItem> = listOf(
        LinuxDoEmojiItem("grinning", "😀", keywordsZh = "笑脸 开心"),
        LinuxDoEmojiItem("smile", "😄", keywordsZh = "微笑 高兴"),
        LinuxDoEmojiItem("grin", "😁", keywordsZh = "露齿笑 嘻嘻"),
        LinuxDoEmojiItem("laughing", "😆", keywordsZh = "大笑 哈哈"),
        LinuxDoEmojiItem("sweat_smile", "😅", keywordsZh = "流汗 尴尬笑"),
        LinuxDoEmojiItem("joy", "😂", keywordsZh = "笑哭 喜极而泣"),
        LinuxDoEmojiItem("rofl", "🤣", keywordsZh = "笑得打滚 笑死"),
        LinuxDoEmojiItem("blush", "😊", keywordsZh = "脸红 羞涩 微笑"),
        LinuxDoEmojiItem("innocent", "😇", keywordsZh = "天使 乖巧 无辜"),
        LinuxDoEmojiItem("slightly_smiling_face", "🙂", keywordsZh = "呵呵 微微笑"),
        LinuxDoEmojiItem("wink", "😉", keywordsZh = "眨眼 懂的都懂"),
        LinuxDoEmojiItem("relieved", "😌", keywordsZh = "舒心 松一口气"),
        LinuxDoEmojiItem("heart_eyes", "😍", keywordsZh = "花痴 喜欢 爱了"),
        LinuxDoEmojiItem("smiling_face_with_three_hearts", "🥰", keywordsZh = "喜爱 幸福 贴贴"),
        LinuxDoEmojiItem("kissing_heart", "😘", keywordsZh = "飞吻 么么哒"),
        LinuxDoEmojiItem("yum", "😋", keywordsZh = "好吃 美味 馋"),
        LinuxDoEmojiItem("stuck_out_tongue_winking_eye", "😜", keywordsZh = "吐舌 调皮 略略略"),
        LinuxDoEmojiItem("zany_face", "🤪", keywordsZh = "滑稽 搞怪 疯了"),
        LinuxDoEmojiItem("star_struck", "🤩", keywordsZh = "星星眼 崇拜 惊艳"),
        LinuxDoEmojiItem("partying_face", "🥳", keywordsZh = "派对 庆祝 好耶"),
        LinuxDoEmojiItem("smirk", "😏", keywordsZh = "得意 坏笑"),
        LinuxDoEmojiItem("unamused", "😒", keywordsZh = "不屑 嫌弃"),
        LinuxDoEmojiItem("disappointed", "😞", keywordsZh = "失望 难过"),
        LinuxDoEmojiItem("pensive", "😔", keywordsZh = "叹气 忧郁"),
        LinuxDoEmojiItem("worried", "😟", keywordsZh = "担心 忧虑"),
        LinuxDoEmojiItem("confused", "😕", keywordsZh = "困惑 迷茫"),
        LinuxDoEmojiItem("persevere", "😣", keywordsZh = "痛苦 煎熬"),
        LinuxDoEmojiItem("tired_face", "😫", keywordsZh = "心累 疲惫 抓狂"),
        LinuxDoEmojiItem("weary", "😩", keywordsZh = "太难了 叹气"),
        LinuxDoEmojiItem("cry", "😢", keywordsZh = "流泪 哭泣 伤心"),
        LinuxDoEmojiItem("sob", "😭", keywordsZh = "痛哭 泪崩 呜呜"),
        LinuxDoEmojiItem("triumph", "😤", keywordsZh = "傲娇 哼 努力"),
        LinuxDoEmojiItem("flushed", "😳", keywordsZh = "脸红 发呆 惊呆"),
        LinuxDoEmojiItem("fearful", "😨", keywordsZh = "害怕 恐惧"),
        LinuxDoEmojiItem("cold_sweat", "😰", keywordsZh = "冷汗 慌了"),
        LinuxDoEmojiItem("thinking", "🤔", keywordsZh = "思考 琢磨"),
        LinuxDoEmojiItem("shushing_face", "🤫", keywordsZh = "嘘 保密 低调"),
        LinuxDoEmojiItem("hand_over_mouth", "🤭", keywordsZh = "捂嘴笑 偷笑"),
        LinuxDoEmojiItem("yawning_face", "🥱", keywordsZh = "哈欠 困了 无聊"),
        LinuxDoEmojiItem("sleeping", "😴", keywordsZh = "睡觉 晚安"),
        LinuxDoEmojiItem("drooling_face", "🤤", keywordsZh = "流口水 馋了"),
        LinuxDoEmojiItem("mask", "😷", keywordsZh = "口罩 生病 闭嘴"),
        LinuxDoEmojiItem("thermometer_face", "🤒", keywordsZh = "发烧 感冒"),
        LinuxDoEmojiItem("dizzy_face", "😵", keywordsZh = "晕 眩晕 懵了"),
        LinuxDoEmojiItem("zipper_mouth_face", "🤐", keywordsZh = "闭嘴 封口"),
        LinuxDoEmojiItem("woozy_face", "🥴", keywordsZh = "微醺 晕乎乎"),
        LinuxDoEmojiItem("nauseated_face", "🤢", keywordsZh = "恶心 吐了"),
        LinuxDoEmojiItem("vomiting_face", "🤮", keywordsZh = "呕吐 难受")
    )

    /**
     * 手势与互动标准组
     */
    private val gestureEmojis: List<LinuxDoEmojiItem> = listOf(
        LinuxDoEmojiItem("+1", "👍", keywordsZh = "点赞 大拇指 牛"),
        LinuxDoEmojiItem("-1", "👎", keywordsZh = "踩 差评 反对"),
        LinuxDoEmojiItem("clap", "👏", keywordsZh = "鼓掌 叫好"),
        LinuxDoEmojiItem("pray", "🙏", keywordsZh = "感谢 拜托 合十"),
        LinuxDoEmojiItem("hugs", "🤗", keywordsZh = "抱抱 拥抱"),
        LinuxDoEmojiItem("ok_hand", "👌", keywordsZh = "OK 没问题 搞定"),
        LinuxDoEmojiItem("v", "✌️", keywordsZh = "胜利 耶 比心"),
        LinuxDoEmojiItem("crossed_fingers", "🤞", keywordsZh = "祈祷 祝好运"),
        LinuxDoEmojiItem("love_you_gesture", "🤟", keywordsZh = "爱你 摇滚"),
        LinuxDoEmojiItem("call_me_hand", "🤙", keywordsZh = "666 打电话 六"),
        LinuxDoEmojiItem("muscle", "💪", keywordsZh = "加油 肌肉 力量 强"),
        LinuxDoEmojiItem("wave", "👋", keywordsZh = "挥手 你好 再见"),
        LinuxDoEmojiItem("raised_hands", "🙌", keywordsZh = "举双手 欢呼 万岁"),
        LinuxDoEmojiItem("handshake", "🤝", keywordsZh = "握手 合作 成交"),
        LinuxDoEmojiItem("fist", "✊", keywordsZh = "拳头 坚持 加油"),
        LinuxDoEmojiItem("punch", "👊", keywordsZh = "出拳 碰拳 铁拳"),
        LinuxDoEmojiItem("point_up", "☝️", keywordsZh = "楼上 第一 重点"),
        LinuxDoEmojiItem("point_down", "👇", keywordsZh = "楼下 往下看"),
        LinuxDoEmojiItem("point_left", "👈", keywordsZh = "左边 指"),
        LinuxDoEmojiItem("point_right", "👉", keywordsZh = "右边 看这里"),
        LinuxDoEmojiItem("eyes", "👀", keywordsZh = "眼睛 围观 看看"),
        LinuxDoEmojiItem("brain", "🧠", keywordsZh = "大脑 智商 脑子"),
        LinuxDoEmojiItem("writing_hand", "✍️", keywordsZh = "记录 记笔记 写"),
        LinuxDoEmojiItem("selfie", "🤳", keywordsZh = "自拍 手机"),
        LinuxDoEmojiItem("man_bowing", "🙇", keywordsZh = "鞠躬 道歉 感谢"),
        LinuxDoEmojiItem("person_shrugging", "🤷", keywordsZh = "摊手 无奈 不知道"),
        LinuxDoEmojiItem("person_facepalming", "🤦", keywordsZh = "捂脸 没眼看")
    )

    /**
     * 极客、技术与庆典标准组
     */
    private val geekAndCelebrationEmojis: List<LinuxDoEmojiItem> = listOf(
        LinuxDoEmojiItem("rocket", "🚀", keywordsZh = "火箭 起飞 发布 冲"),
        LinuxDoEmojiItem("fire", "🔥", keywordsZh = "火 热门 爆款"),
        LinuxDoEmojiItem("tada", "🎉", keywordsZh = "礼花 庆祝 恭喜 开奖"),
        LinuxDoEmojiItem("confetti_ball", "🎊", keywordsZh = "彩球 撒花 庆典"),
        LinuxDoEmojiItem("sparkles", "✨", keywordsZh = "闪耀 星星 AI 灵感"),
        LinuxDoEmojiItem("100", "💯", keywordsZh = "满分 一百分 完美"),
        LinuxDoEmojiItem("trophy", "🏆", keywordsZh = "奖杯 冠军 第一名"),
        LinuxDoEmojiItem("crown", "👑", keywordsZh = "皇冠 始皇 大佬"),
        LinuxDoEmojiItem("gem", "💎", keywordsZh = "钻石 宝石 珍贵"),
        LinuxDoEmojiItem("bulb", "💡", keywordsZh = "灯泡 灵感 想法 点子"),
        LinuxDoEmojiItem("gear", "⚙️", keywordsZh = "齿轮 设置 配置 调优"),
        LinuxDoEmojiItem("hammer_and_wrench", "🛠️", keywordsZh = "工具 维护 施工 开发"),
        LinuxDoEmojiItem("shield", "🛡️", keywordsZh = "盾牌 安全 防护"),
        LinuxDoEmojiItem("lock", "🔒", keywordsZh = "锁 加密 锁定"),
        LinuxDoEmojiItem("key", "🔑", keywordsZh = "钥匙 密钥 Token 激活码"),
        LinuxDoEmojiItem("computer", "💻", keywordsZh = "电脑 笔记本 写代码"),
        LinuxDoEmojiItem("keyboard", "⌨️", keywordsZh = "键盘 码字 程序员"),
        LinuxDoEmojiItem("mobile_phone", "📱", keywordsZh = "手机 移动端 App"),
        LinuxDoEmojiItem("robot", "🤖", keywordsZh = "机器人 AI 智能体 Bot"),
        LinuxDoEmojiItem("gift", "🎁", keywordsZh = "礼物 福利 抽奖 羊毛"),
        LinuxDoEmojiItem("moneybag", "💰", keywordsZh = "钱袋 积分 发财 氪金"),
        LinuxDoEmojiItem("chart_with_upwards_trend", "📈", keywordsZh = "上涨 暴涨 股票 遥遥领先"),
        LinuxDoEmojiItem("chart_with_downwards_trend", "📉", keywordsZh = "下跌 暴跌 亏麻了"),
        LinuxDoEmojiItem("coffee", "☕", keywordsZh = "咖啡 摸鱼 早安 Java"),
        LinuxDoEmojiItem("beer", "🍺", keywordsZh = "啤酒 干杯 庆祝"),
        LinuxDoEmojiItem("beers", "🍻", keywordsZh = "碰杯 干杯 兄弟"),
        LinuxDoEmojiItem("popcorn", "🍿", keywordsZh = "爆米花 吃瓜 看戏 前排"),
        LinuxDoEmojiItem("watermelon", "🍉", keywordsZh = "西瓜 吃瓜 围观"),
        LinuxDoEmojiItem("apple", "🍎", keywordsZh = "红苹果 Apple 苹果"),
        LinuxDoEmojiItem("green_apple", "🍏", keywordsZh = "青苹果"),
        LinuxDoEmojiItem("warning", "⚠️", keywordsZh = "警告 注意 风险"),
        LinuxDoEmojiItem("white_check_mark", "✅", keywordsZh = "通过 完成 对勾 已解决"),
        LinuxDoEmojiItem("x", "❌", keywordsZh = "错误 取消 失败 叉")
    )

    private val builtInShortcodeToUnicode: Map<String, String> by lazy {
        val map = LinkedHashMap<String, String>()
        (officialReactions + communityMemeEmojis + smileyEmojis + gestureEmojis + geekAndCelebrationEmojis).forEach { item ->
            if (!item.unicode.isNullOrBlank()) {
                map[item.shortcode.lowercase()] = item.unicode
            }
        }
        // 补充常见别名
        map["red_heart"] = "❤️"
        map["love"] = "❤️"
        map["thumbsup"] = "👍"
        map["thumbs_up"] = "👍"
        map["thumb_up"] = "👍"
        map["thumbsdown"] = "👎"
        map["thumbs_down"] = "👎"
        map["party_popper"] = "🎉"
        map["flame"] = "🔥"
        map["hugging"] = "🤗"
        map["hugging_face"] = "🤗"
        map["thinking_face"] = "🤔"
        map["folded_hands"] = "🙏"
        map["melting_face"] = "🫠"
        map["star"] = "⭐"
        map["boom"] = "💥"
        map["collision"] = "💥"
        map["zap"] = "⚡"
        map["question"] = "❓"
        map["exclamation"] = "❗"
        map["heavy_check_mark"] = "✔️"
        map["check_mark_button"] = "✅"
        map["cross_mark"] = "❌"
        map
    }

    /**
     * 将任意 `:shortcode:` 或 `reactionId` 转换为 Unicode Emoji（若为纯自定义图片表情则返回 null）
     */
    fun shortcodeToUnicode(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val key = raw.trim().removeSurrounding(":").lowercase()
        builtInShortcodeToUnicode[key]?.let { return it }
        val baseWithoutTone = key.replace(Regex(":t[2-6]$"), "")
        return builtInShortcodeToUnicode[baseWithoutTone]
    }

    /**
     * 解析任意表情或 Reaction ID 的官方图片 URL（优先使用 `/emojis.json` 同步的自定义 URL，否则回退至官方 Twemoji 地址，兼容 `:t2`~`:t6` 肤色后缀）
     */
    fun resolveReactionImageUrl(reactionIdOrShortcode: String): String {
        val clean = reactionIdOrShortcode.trim().removeSurrounding(":")
        val lower = clean.lowercase()
        dynamicCustomUrls[lower]?.let { return it }
        val toneMatch = Regex("^([a-z0-9_+-]+):t([2-6])$").matchEntire(lower)
        if (toneMatch != null) {
            val rawBase = toneMatch.groupValues[1]
            val tone = toneMatch.groupValues[2]
            val baseFile = when (rawBase) {
                "+1", "thumbsup", "thumb_up" -> "+1"
                "-1", "thumbsdown" -> "-1"
                else -> rawBase
            }
            return "https://linux.do/images/emoji/twemoji/$baseFile/$tone.png?v=12"
        }
        val normalizedFile = when (lower) {
            "+1", "thumbsup", "thumb_up" -> "+1"
            "-1", "thumbsdown" -> "-1"
            else -> lower
        }
        return "https://linux.do/images/emoji/twemoji/$normalizedFile.png?v=12"
    }

    private val customEmojiPackPrefixes = listOf(
        "feishu_",
        "lark_",
        "bili_",
        "bilibili_",
        "tieba_",
        "qq_",
        "coolapk_",
        "weibo_",
        "xhs_",
        "acfun_",
        "ac_",
        "menhera_",
        "doraemon_",
        "aru_",
        "onion_",
        "blob_",
        "linuxdo_"
    )

    /**
     * 判断某个 reactionId 是否属于站点自定义图片表情（如 `bili_*`、`tieba_*`、`feishu_*` 等）
     */
    fun isCustomImageEmoji(reactionIdOrShortcode: String): Boolean {
        val clean = reactionIdOrShortcode.trim().removeSurrounding(":").lowercase()
        if (clean.isEmpty()) return false
        return dynamicCustomUrls.containsKey(clean) ||
            customEmojiPackPrefixes.any { clean.startsWith(it) }
    }

    private val animalAndNatureEmojis: List<LinuxDoEmojiItem> = listOf(
        LinuxDoEmojiItem("dog", "🐶", keywordsZh = "狗 汪 狗头"),
        LinuxDoEmojiItem("cat", "🐱", keywordsZh = "猫 喵 猫猫"),
        LinuxDoEmojiItem("fox_face", "🦊", keywordsZh = "狐狸"),
        LinuxDoEmojiItem("bear", "🐻", keywordsZh = "熊 小熊"),
        LinuxDoEmojiItem("panda_face", "🐼", keywordsZh = "熊猫 国宝"),
        LinuxDoEmojiItem("tiger", "🐯", keywordsZh = "老虎"),
        LinuxDoEmojiItem("lion", "🦁", keywordsZh = "狮子"),
        LinuxDoEmojiItem("rabbit", "🐰", keywordsZh = "兔子 兔兔"),
        LinuxDoEmojiItem("penguin", "🐧", keywordsZh = "企鹅 Linux"),
        LinuxDoEmojiItem("whale", "🐳", keywordsZh = "鲸鱼 Docker"),
        LinuxDoEmojiItem("unicorn", "🦄", keywordsZh = "独角兽"),
        LinuxDoEmojiItem("bug", "🐛", keywordsZh = "虫子 漏洞 Bug"),
        LinuxDoEmojiItem("seedling", "🌱", keywordsZh = "幼苗 新手 成长"),
        LinuxDoEmojiItem("four_leaf_clover", "🍀", keywordsZh = "四叶草 幸运")
    )

    private val foodAndDrinkEmojis: List<LinuxDoEmojiItem> = listOf(
        LinuxDoEmojiItem("coffee", "☕", keywordsZh = "咖啡 早安 Java"),
        LinuxDoEmojiItem("beer", "🍺", keywordsZh = "啤酒 干杯"),
        LinuxDoEmojiItem("beers", "🍻", keywordsZh = "碰杯 庆祝"),
        LinuxDoEmojiItem("popcorn", "🍿", keywordsZh = "爆米花 吃瓜 看戏"),
        LinuxDoEmojiItem("watermelon", "🍉", keywordsZh = "西瓜 吃瓜 围观"),
        LinuxDoEmojiItem("lemon", "🍋", keywordsZh = "柠檬 酸了"),
        LinuxDoEmojiItem("apple", "🍎", keywordsZh = "苹果 Apple"),
        LinuxDoEmojiItem("hamburger", "🍔", keywordsZh = "汉堡 美食"),
        LinuxDoEmojiItem("pizza", "🍕", keywordsZh = "披萨"),
        LinuxDoEmojiItem("cake", "🍰", keywordsZh = "蛋糕 生日 庆祝"),
        LinuxDoEmojiItem("tea", "🍵", keywordsZh = "茶 喝茶 养生"),
        LinuxDoEmojiItem("pill", "💊", keywordsZh = "药丸 吃药")
    )

    /**
     * 获取编辑器表情面板的全部分组（包含动态同步的站点自定义表情包）
     */
    fun getPickerGroups(): List<LinuxDoEmojiGroup> {
        val baseFirst = LinuxDoEmojiGroup(
            id = "official_reactions",
            labelZh = "官方表态",
            items = officialReactions,
            iconKey = "official_reactions",
            iconUnicode = "❤️"
        )
        val builtInBrandPacks = listOf(
            LinuxDoEmojiGroup(
                id = "feishu",
                labelZh = "飞书",
                items = listOf(
                    LinuxDoEmojiItem("+1", "👍", keywordsZh = "飞书 赞 强"),
                    LinuxDoEmojiItem("tada", "🎉", keywordsZh = "飞书 撒花 庆祝"),
                    LinuxDoEmojiItem("pray", "🙏", keywordsZh = "飞书 感谢 拜托"),
                    LinuxDoEmojiItem("fire", "🔥", keywordsZh = "飞书 给力 加油"),
                    LinuxDoEmojiItem("heart", "❤️", keywordsZh = "飞书 比心 喜欢"),
                    LinuxDoEmojiItem("saluting_face", "🫡", keywordsZh = "飞书 收到 敬礼")
                ),
                iconKey = "feishu"
            ),
            LinuxDoEmojiGroup(
                id = "bilibili",
                labelZh = "B站",
                items = buildList {
                    if (dynamicCustomUrls.containsKey("bili_057")) {
                        add(LinuxDoEmojiItem("bili_057", null, customUrl = dynamicCustomUrls["bili_057"], keywordsZh = "B站 小电视 赞"))
                    }
                    add(LinuxDoEmojiItem("clap", "👏", keywordsZh = "B站 鼓掌 支持"))
                    add(LinuxDoEmojiItem("open_mouth", "😮", keywordsZh = "B站 吃惊"))
                    add(LinuxDoEmojiItem("sob", "😭", keywordsZh = "B站 泪目 哭"))
                    add(LinuxDoEmojiItem("laughing", "😆", keywordsZh = "B站 大笑"))
                },
                iconKey = "bilibili"
            ),
            LinuxDoEmojiGroup(
                id = "tieba",
                labelZh = "贴吧",
                items = buildList {
                    if (dynamicCustomUrls.containsKey("tieba_087")) {
                        add(LinuxDoEmojiItem("tieba_087", null, customUrl = dynamicCustomUrls["tieba_087"], keywordsZh = "贴吧 滑稽 狗头"))
                    }
                    add(LinuxDoEmojiItem("smirk", "😏", keywordsZh = "贴吧 滑稽 坏笑"))
                    add(LinuxDoEmojiItem("sweat_smile", "😅", keywordsZh = "贴吧 流汗黄豆"))
                    add(LinuxDoEmojiItem("joy", "😂", keywordsZh = "贴吧 笑哭"))
                    add(LinuxDoEmojiItem("rofl", "🤣", keywordsZh = "贴吧 笑死"))
                },
                iconKey = "tieba"
            )
        )
        val builtInFallbacks = listOf(
            LinuxDoEmojiGroup(
                id = "smileys",
                labelZh = "笑脸",
                items = smileyEmojis,
                iconKey = "smileys",
                iconUnicode = "😀"
            ),
            LinuxDoEmojiGroup(
                id = "gestures",
                labelZh = "人物与手势",
                items = gestureEmojis,
                iconKey = "people",
                iconUnicode = "👋"
            ),
            LinuxDoEmojiGroup(
                id = "animals",
                labelZh = "动物与自然",
                items = animalAndNatureEmojis,
                iconKey = "animals",
                iconUnicode = "🐻"
            ),
            LinuxDoEmojiGroup(
                id = "food",
                labelZh = "食物与饮品",
                items = foodAndDrinkEmojis,
                iconKey = "food",
                iconUnicode = "🍔"
            ),
            LinuxDoEmojiGroup(
                id = "geek_celebration",
                labelZh = "物品与庆典",
                items = geekAndCelebrationEmojis,
                iconKey = "objects",
                iconUnicode = "💡"
            ),
            LinuxDoEmojiGroup(
                id = "community_memes",
                labelZh = "社区热梗",
                items = communityMemeEmojis,
                iconKey = "memes",
                iconUnicode = "🫠"
            )
        )
        val serverIconKeys = dynamicServerGroups.map { it.iconKey }.toSet()
        val nonOverlappingBrands = builtInBrandPacks.filterNot { it.iconKey in serverIconKeys }
        val nonOverlappingFallbacks = builtInFallbacks.filterNot { it.iconKey in serverIconKeys }
        return listOf(baseFirst) + dynamicServerGroups + nonOverlappingFallbacks + nonOverlappingBrands
    }

    /**
     * 按关键词（中文或英文 Shortcode）跨分组搜索表情
     */
    fun searchEmojis(query: String): List<LinuxDoEmojiItem> {
        val q = query.trim().lowercase().removeSurrounding(":")
        if (q.isEmpty()) return officialReactions + communityMemeEmojis
        return getPickerGroups()
            .flatMap { it.items }
            .distinctBy { it.shortcode.lowercase() }
            .filter { item ->
                item.shortcode.lowercase().contains(q) ||
                    item.keywordsZh.lowercase().contains(q) ||
                    item.unicode == q
            }
    }
}
