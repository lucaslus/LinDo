package do_.linux.lindo.core.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Discourse 徽章等级：1=金徽章, 2=银徽章, 3=铜徽章
 */
enum class BadgeType(val id: Int, val labelZh: String) {
    GOLD(1, "金徽章"),
    SILVER(2, "银徽章"),
    BRONZE(3, "铜徽章");

    companion object {
        fun fromId(id: Int): BadgeType = entries.firstOrNull { it.id == id } ?: BRONZE
    }
}

/**
 * 帖子头部或个人中心展示的徽章定义
 */
@Immutable
@Serializable
data class GrantedBadge(
    val id: Int,
    val name: String,
    val description: String = "",
    @SerialName("long_description") val longDescription: String = "",
    @SerialName("badge_type_id") val badgeTypeId: Int = 3,
    val icon: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    val slug: String = "",
    @SerialName("grant_count") val grantCount: Int = 0,
    val allowTitle: Boolean = false,
    @SerialName("badge_grouping_id") val badgeGroupingId: Int = 0,
    val badgeGroupingName: String = "",
    val isFirstTimeGrant: Boolean = false,
    val count: Int = 1,
    val grantedAtText: String = "",
    val isEarned: Boolean = false,
    val topicId: Int = 0,
    val topicTitle: String = "",
    val postNumber: Int = 0
) {
    val badgeType: BadgeType get() = BadgeType.fromId(badgeTypeId)

    val plainDescription: String
        get() = description
            .replace(Regex("<[^>]+>"), "")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .trim()
            .ifEmpty { name }

    val plainLongDescription: String
        get() = longDescription
            .replace(Regex("<[^>]+>"), "")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .trim()
}

/**
 * 用户身份角标 (Flair)
 */
@Immutable
@Serializable
data class FlairInfo(
    val url: String? = null,
    val name: String? = null,
    val bgColorHex: String? = null,
    val fgColorHex: String? = null,
    val groupId: Int? = null
) {
    val hasFlair: Boolean get() = !url.isNullOrBlank()
}

/**
 * 用户自定义状态
 */
@Immutable
@Serializable
data class UserStatus(
    val description: String? = null,
    val emoji: String? = null
)

/**
 * 话题列表中的用户信息（含静态头像模板 + 动画头像原件 + Flair）
 */
@Immutable
@Serializable
data class TopicUser(
    val id: Int,
    val username: String,
    val name: String? = null,
    @SerialName("avatar_template") val avatarTemplate: String = "",
    @SerialName("animated_avatar") val animatedAvatar: String? = null,
    @SerialName("primary_group_name") val primaryGroupName: String? = null,
    @SerialName("flair_name") val flairName: String? = null,
    @SerialName("flair_url") val flairUrl: String? = null,
    @SerialName("flair_bg_color") val flairBgColor: String? = null,
    @SerialName("flair_color") val flairColor: String? = null,
    @SerialName("flair_group_id") val flairGroupId: Int? = null,
    @SerialName("trust_level") val trustLevel: Int = 1,
    val admin: Boolean = false,
    val moderator: Boolean = false
) {
    val flair: FlairInfo
        get() = FlairInfo(
            url = flairUrl,
            name = flairName,
            bgColorHex = flairBgColor,
            fgColorHex = flairColor,
            groupId = flairGroupId
        )
}

/**
 * 话题摘要卡片数据
 */
@Immutable
@Serializable
data class TopicSummary(
    val id: Int,
    val title: String,
    @SerialName("fancy_title") val fancyTitle: String = "",
    val slug: String = "",
    @SerialName("posts_count") val postsCount: Int = 1,
    @SerialName("reply_count") val replyCount: Int = 0,
    val views: Int = 0,
    @SerialName("like_count") val likeCount: Int = 0,
    @SerialName("category_id") val categoryId: Int = 1,
    val categoryName: String = "常规话题",
    val categoryColorHex: String = "0088CC",
    val tags: List<String> = emptyList(),
    val excerpt: String? = null,
    val pinned: Boolean = false,
    val unseen: Boolean = false,
    val unreadPosts: Int = 0,
    val hasAcceptedAnswer: Boolean = false,
    val lastPostedAt: String = "1m",
    val createdAtIso: String = "",
    val lastPostedAtIso: String = "",
    val originalPoster: TopicUser,
    val participantAvatars: List<TopicUser> = emptyList()
)

/**
 * Discourse Reactions 插件单个表情统计项（如 `heart` ❤️, `+1` 👍, `confetti_ball` 🎉, `fire` 🔥, `laughing` 😂, `clap` 👏, `hugs` 🤗）
 */
@Immutable
@Serializable
data class PostReactionItem(
    val id: String,
    val emoji: String,
    val count: Int
)

/**
 * Discourse Boosts (`discourse-boosts` / 小火箭助力弹幕) 单条助力气泡
 * 对应 `POST /discourse-boosts/posts/{postId}/boosts` 与 `post.boosts` 数组
 */
@Immutable
@Serializable
data class PostBoostItem(
    val id: Long,
    val cookedHtml: String,
    val plainText: String = "",
    val userId: Int = 0,
    val username: String = "",
    val displayName: String? = null,
    val avatarTemplate: String = "",
    val canDelete: Boolean = false,
    val canFlag: Boolean = false
) {
    internal fun isBy(accountId: Int?, accountUsername: String?): Boolean {
        if (accountId != null && accountId > 0 && userId > 0) return userId == accountId
        return !accountUsername.isNullOrBlank() && username.equals(accountUsername, ignoreCase = true)
    }
}

/**
 * 对齐官方 `DiscourseBoosts::Boost` (`MAX_VISIBLE_LENGTH = 16`, `MAX_EMOJI = 5`) 字数与表情统计：
 * - 每个 `:shortcode:` 自定义表情与每个 Unicode Emoji 簇均计为 1 个可见字符 + 1 个表情；
 * - 常规字符按 Unicode 字素簇（Grapheme Cluster）逐个计为 1 个可见字符。
 */
data class BoostInputStats(
    val visibleLength: Int,
    val emojiCount: Int,
    val maxVisibleLength: Int = MAX_BOOST_VISIBLE_LENGTH,
    val maxEmojiCount: Int = MAX_BOOST_EMOJI_COUNT
) {
    val canAddEmoji: Boolean
        get() {
            val spaceNeeded = if (visibleLength > 0) 2 else 1
            return visibleLength + spaceNeeded <= maxVisibleLength && emojiCount < maxEmojiCount
        }

    val isWithinLimit: Boolean
        get() = visibleLength <= maxVisibleLength && emojiCount <= maxEmojiCount

    val canSubmit: Boolean
        get() = visibleLength in 1..maxVisibleLength && emojiCount <= maxEmojiCount

    val isEmpty: Boolean
        get() = visibleLength == 0

    val exceedsLength: Boolean
        get() = visibleLength > maxVisibleLength

    val exceedsEmojiLimit: Boolean
        get() = emojiCount > maxEmojiCount

    val isValid: Boolean
        get() = canSubmit

    companion object {
        const val MAX_BOOST_VISIBLE_LENGTH = 16
        const val MAX_BOOST_EMOJI_COUNT = 5
        private val shortcodeRegex = Regex(":[a-zA-Z0-9_+-]+(?::t\\d)?:")

        fun compute(raw: String): BoostInputStats {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return BoostInputStats(0, 0)
            var shortcodeEmojiCount = 0
            val replaced = shortcodeRegex.replace(trimmed) {
                shortcodeEmojiCount++
                "\uFFFC"
            }
            val iterator = java.text.BreakIterator.getCharacterInstance()
            iterator.setText(replaced)
            var clusterCount = 0
            var unicodeEmojiCount = 0
            var start = iterator.first()
            var end = iterator.next()
            while (end != java.text.BreakIterator.DONE) {
                val cluster = replaced.substring(start, end)
                clusterCount++
                if (cluster != "\uFFFC" && isUnicodeEmojiCluster(cluster)) {
                    unicodeEmojiCount++
                }
                start = end
                end = iterator.next()
            }
            return BoostInputStats(
                visibleLength = clusterCount,
                emojiCount = shortcodeEmojiCount + unicodeEmojiCount
            )
        }

        private fun isUnicodeEmojiCluster(cluster: String): Boolean {
            var idx = 0
            while (idx < cluster.length) {
                val cp = cluster.codePointAt(idx)
                if (cp in 0x1F000..0x1FAFF ||
                    cp in 0x2600..0x27BF ||
                    cp in 0x2300..0x23FF ||
                    cp == 0x2B50 ||
                    cp == 0x2764 ||
                    cp == 0xFE0F
                ) {
                    return true
                }
                idx += Character.charCount(cp)
            }
            return false
        }
    }
}

/**
 * 话题详情中的单层楼 (Post)
 */
@Immutable
@Serializable
data class PostItemModel(
    val id: Int,
    @SerialName("post_number") val postNumber: Int,
    val userId: Int,
    val username: String,
    val displayName: String? = null,
    val avatarTemplate: String = "",
    val animatedAvatar: String? = null,
    val primaryGroupName: String? = null,
    val userTitle: String? = null,
    val titleIsGroup: Boolean = false,
    val trustLevel: Int = 1,
    val admin: Boolean = false,
    val moderator: Boolean = false,
    val groupModerator: Boolean = false,
    val isTopicOwner: Boolean = false,
    val flair: FlairInfo = FlairInfo(),
    val userStatus: UserStatus? = null,
    val iosDeviceName: String? = null,
    val isTodayBirthday: Boolean = false,
    val isTodayCakeday: Boolean = false,
    val badgesGranted: List<GrantedBadge> = emptyList(),
    val authorBadges: List<GrantedBadge> = emptyList(),
    val cookedHtml: String = "",
    val createdAtText: String = "",
    val replyToPostNumber: Int? = null,
    val replyToUsername: String? = null,
    val replyToDisplayName: String? = null,
    val replyToAvatarTemplate: String? = null,
    val likeCount: Int = 0,
    val replyCount: Int = 0,
    val readsCount: Int = 0,
    val likedByMe: Boolean = false,
    val isRead: Boolean = false,
    val myReactionId: String? = null,
    val reactions: List<PostReactionItem> = emptyList(),
    val boosts: List<PostBoostItem> = emptyList(),
    val canBoost: Boolean = true,
    val bookmarkedByMe: Boolean = false,
    val isSmallAction: Boolean = false,
    val actionCode: String? = null,
    val canEdit: Boolean = true,
    val canDelete: Boolean = false,
    val raw: String? = null,
    val topicId: Int = 0
)

/**
 * 完整话题详情模型
 */
@Immutable
@Serializable
data class TopicDetailModel(
    val id: Int,
    val title: String,
    val categoryName: String,
    val categoryColorHex: String = "0088CC",
    val tags: List<String> = emptyList(),
    val views: Int = 0,
    val likeCount: Int = 0,
    val postsCount: Int = 1,
    val bookmarked: Boolean = false,
    val replyCostHint: String? = null,
    val minWordCountRule: Int? = null,
    val posts: List<PostItemModel> = emptyList(),
    val streamIds: List<Int> = emptyList(),
    val loadError: String? = null,
    val originalPosterUsername: String? = null,
    val highestPostNumber: Int? = null,
    val unavailable: Boolean = false
)

/**
 * 线上个人资料与全量社区统计摘要（来自 `/session/current.json` + `/u/{username}.json` + `/u/{username}/summary.json`）
 */
@Serializable
data class UserFullProfileSummary(
    val id: Int = 0,
    val username: String,
    val displayName: String,
    val avatarTemplate: String = "",
    val animatedAvatar: String? = null,
    val trustLevel: Int = 1,
    val title: String = "",
    val bioExcerpt: String = "",
    val primaryGroupName: String? = null,
    val admin: Boolean = false,
    val moderator: Boolean = false,
    val createdAtText: String = "",
    val daysVisited: Int = 0,
    val timeReadSeconds: Int = 0,
    val recentTimeReadSeconds: Int = 0,
    val topicsEntered: Int = 0,
    val postsReadCount: Int = 0,
    val likesGiven: Int = 0,
    val likesReceived: Int = 0,
    val topicCount: Int = 0,
    val postCount: Int = 0,
    val badgeCount: Int = 0,
    val unreadNotifications: Int = 0,
    val lastSeenText: String = "",
    val location: String = "",
    val websiteName: String = "",
    val statusText: String = "",
    val solvedCount: Int = 0,
    val badges: List<GrantedBadge> = emptyList(),
    val flair: FlairInfo = FlairInfo()
) {
    val trustLevelName: String
        get() = when (trustLevel) {
            0 -> "Lv.0 新用户"
            1 -> "Lv.1 基本用户"
            2 -> "Lv.2 成员"
            3 -> "Lv.3 活跃会员"
            4 -> "Lv.4 社区领袖"
            else -> "Lv.$trustLevel 成员"
        }
}

/**
 * 用户通知条目（来自 `/notifications.json`）
 */
@Serializable
data class UserNotificationItem(
    val id: Int,
    val notificationType: Int,
    val read: Boolean,
    val createdAtText: String,
    val topicId: Int = 0,
    val postNumber: Int = 1,
    val slug: String = "",
    val title: String,
    val actingUsername: String = "",
    val actingAvatarTemplate: String? = null,
    val excerpt: String = "",
    val count: Int = 0,
    val badgeId: Int = 0,
    val badgeName: String = "",
    val chatChannelId: Int = 0
) {
    val isGroupedReply: Boolean get() = notificationType == 2 && count > 1

    val actorUsername: String?
        get() = actingUsername.substringBefore("、").trim()
            .takeIf { !isGroupedReply && it.isNotEmpty() && it != "系统" }

    val typeLabel: String
        get() = when (notificationType) {
            1 -> "提及了你"
            2 -> if (isGroupedReply) "收到 $count 条回复" else "回复了你"
            3 -> "引用了你的发言"
            4 -> "编辑了帖子"
            5 -> if (postNumber > 1) "赞了你的回复" else "赞了你的主帖"
            6 -> "发来私信"
            7 -> "邀请你参与私信"
            8 -> "接受了你的邀请"
            9 -> "回复了话题"
            10 -> "移动了帖子"
            11 -> "链接了你的帖子"
            12 -> "获得徽章"
            13 -> "邀请你参与话题"
            14 -> "系统通知"
            15 -> "提及了你所在的群组"
            16 -> "群组消息汇总"
            17 -> "发布了关注的新话题"
            18 -> "话题定时提醒"
            19 -> if (count > 1) "累计赞了你的 $count 条发言" else "赞了你的多条发言"
            20 -> "帖子已通过审核"
            21 -> "代码审查已通过"
            22 -> "群组申请已通过"
            23 -> "群组加入申请"
            24 -> "书签定时提醒"
            25 -> "给你的发言加了表情"
            26 -> "投票额度已归还"
            27 -> "活动即将开始"
            28 -> "邀请你参加活动"
            29 -> "在聊天中 @提及了你"
            30 -> "发来聊天消息"
            31 -> "邀请你加入聊天"
            32 -> "在聊天中提及群组"
            33 -> "在聊天中引用了你"
            34 -> "指派了话题给你"
            36, 800 -> "关注了你"
            37, 801 -> "发布了新话题"
            38, 802 -> "发表了新回复"
            else -> if (topicId > 0) "参与了话题互动" else "社区动态"
        }
}

/**
 * 用户个人动态 / 回复 / 书签条目（来自 `/user_actions.json` 或 `/u/{username}/bookmarks.json`）
 */
@Serializable
data class UserActivityItem(
    val id: Int,
    val topicId: Int,
    val postNumber: Int = 1,
    val title: String,
    val excerpt: String = "",
    val createdAtText: String = "",
    val categoryName: String = "技术讨论",
    val actionType: Int = 5
) {
    val actionLabel: String
        get() = when (actionType) {
            1 -> "送出赞"
            2 -> "收到赞"
            4 -> "发布话题"
            5 -> "发表回复"
            88 -> "收藏书签"
            else -> "社区动态"
        }
}

/**
 * 全局搜索结果条目（来自 `/search.json?q=...`）
 */
@Serializable
data class CommunitySearchResult(
    val topicId: Int,
    val postNumber: Int = 1,
    val title: String,
    val blurb: String = "",
    val username: String = "",
    val avatarTemplate: String = "",
    val createdAtText: String = "",
    val categoryName: String = "技术讨论",
    val categoryColorHex: String = "0E76BD",
    val likeCount: Int = 0,
    val postsCount: Int = 1
)

/** Resolves Discourse badge records without inferring awards from titles or device metadata. */
object BadgeInjector {
    private val knownBadgesById = java.util.concurrent.ConcurrentHashMap<Int, GrantedBadge>()

    fun lookupBadgeById(badgeId: Int): GrantedBadge? = knownBadgesById[badgeId]

    fun readBadgeDefinitions(element: JsonElement?): List<GrantedBadge> = when (element) {
        is JsonObject -> element.mapNotNull { (key, value) ->
            (value as? JsonObject)?.let { parseGrantedBadge(it, fallbackId = key.toIntOrNull() ?: 0) }
        }
        is JsonArray -> element.mapNotNull { (it as? JsonObject)?.let { obj -> parseGrantedBadge(obj) } }
        else -> emptyList()
    }

    fun readFeaturedBadges(root: JsonObject, user: JsonObject): List<GrantedBadge> {
        val featuredIds = user["featured_user_badge_ids"] as? JsonArray ?: return emptyList()
        val definitions = readBadgeDefinitions(root["badges"]).associateBy { it.id }
        val grants = (root["user_badges"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .associateBy { it["id"]?.jsonPrimitive?.intOrNull }
        return featuredIds.mapNotNull { id ->
            val grant = grants[(id as? JsonPrimitive)?.intOrNull] ?: return@mapNotNull null
            val badgeId = grant["badge_id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val badge = definitions[badgeId] ?: return@mapNotNull null
            badge.copy(
                count = grant["count"]?.jsonPrimitive?.intOrNull ?: 1,
                grantedAtText = grant["granted_at"]?.jsonPrimitive?.contentOrNull.orEmpty().take(10)
            )
        }.distinctBy { it.id }
    }

    fun readTopicAuthorBadges(root: JsonObject): Map<Int, List<GrantedBadge>>? {
        val stream = root["post_stream"] as? JsonObject
        readBadgeDefinitions(root["badges"])
        readBadgeDefinitions(stream?.get("badges"))
        val payload = (root["user_badges"] ?: stream?.get("user_badges")) as? JsonObject ?: return null
        val definitions = readBadgeDefinitions(payload["badges"])
        if (definitions.isEmpty()) return emptyMap()
        val badgesById = definitions.associateBy { it.id }
        val users = when (val element = payload["users"]) {
            is JsonObject -> element.values
            is JsonArray -> element
            else -> emptyList()
        }
        return buildMap {
            for (user in users) {
                val record = user as? JsonObject ?: continue
                val userId = record["id"]?.jsonPrimitive?.intOrNull ?: continue
                val ids = record["badge_ids"] as? JsonArray ?: continue
                val badges = ids.mapNotNull { badgesById[(it as? JsonPrimitive)?.intOrNull] }
                    .distinctBy { it.id }
                val highestTrustBadge = badges.filter { it.badgeGroupingId == 4 && it.id in 1..4 }
                    .maxOfOrNull { it.id }
                put(userId, badges.filter { it.badgeGroupingId != 4 || it.id !in 1..4 || it.id == highestTrustBadge })
            }
        }
    }

    fun parseGrantedBadge(obj: JsonObject, fallbackId: Int = 0, isFirstGrant: Boolean = false): GrantedBadge {
        val nestedBadge = obj["badge"] as? JsonObject
        val target = nestedBadge ?: obj
        // 关键修复：当 obj 是 UserBadge 记录（同时含 id=记录主键 与 badge_id=徽章ID）时，必须优先取 badge_id！
        val resolvedId = nestedBadge?.get("id")?.jsonPrimitive?.intOrNull
            ?: obj["badge_id"]?.jsonPrimitive?.intOrNull
            ?: target["id"]?.jsonPrimitive?.intOrNull
            ?: fallbackId
        val rawNameCandidate = target["name"]?.jsonPrimitive?.contentOrNull
            ?: obj["badge_name"]?.jsonPrimitive?.contentOrNull
        val rawSlugCandidate = target["slug"]?.jsonPrimitive?.contentOrNull
            ?: obj["badge_slug"]?.jsonPrimitive?.contentOrNull
        val known = knownBadgesById[resolvedId]
        val rawName = rawNameCandidate
            ?: known?.name
            ?: "徽章 #$resolvedId"
        val rawSlug = rawSlugCandidate
            ?: known?.slug
            ?: ""
        val rawIcon = target["icon"]?.jsonPrimitive?.contentOrNull
            ?: known?.icon
        val badge = GrantedBadge(
            id = if (resolvedId != 0) resolvedId else (known?.id ?: 0),
            name = rawName,
            description = target["description"]?.jsonPrimitive?.contentOrNull ?: known?.description ?: "",
            longDescription = target["long_description"]?.jsonPrimitive?.contentOrNull ?: known?.longDescription ?: "",
            badgeTypeId = target["badge_type_id"]?.jsonPrimitive?.intOrNull ?: known?.badgeTypeId ?: 3,
            icon = if ("icon" in target) target["icon"]?.jsonPrimitive?.contentOrNull else rawIcon,
            imageUrl = when {
                "image_url" in target || "image" in target ->
                    target["image_url"]?.jsonPrimitive?.contentOrNull ?: target["image"]?.jsonPrimitive?.contentOrNull
                else -> known?.imageUrl
            },
            slug = rawSlug,
            grantCount = target["grant_count"]?.jsonPrimitive?.intOrNull ?: known?.grantCount ?: 0,
            allowTitle = target["allow_title"]?.jsonPrimitive?.booleanOrNull ?: known?.allowTitle ?: false,
            badgeGroupingId = target["badge_grouping_id"]?.jsonPrimitive?.intOrNull ?: known?.badgeGroupingId ?: 0,
            isFirstTimeGrant = isFirstGrant,
            count = obj["count"]?.jsonPrimitive?.intOrNull
                ?: target["count"]?.jsonPrimitive?.intOrNull
                ?: 1,
            grantedAtText = obj["granted_at"]?.jsonPrimitive?.contentOrNull
                ?: target["granted_at"]?.jsonPrimitive?.contentOrNull
                ?: "",
            isEarned = true,
            topicId = obj["topic_id"]?.jsonPrimitive?.intOrNull ?: 0,
            topicTitle = obj["topic_title"]?.jsonPrimitive?.contentOrNull ?: "",
            postNumber = obj["post_number"]?.jsonPrimitive?.intOrNull ?: 0
        )
        if (badge.id > 0 && rawNameCandidate != null && !isFirstGrant) {
            knownBadgesById[badge.id] = badge
        }
        return badge
    }
}
