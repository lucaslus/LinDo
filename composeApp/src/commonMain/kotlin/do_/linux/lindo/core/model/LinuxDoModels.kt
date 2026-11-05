package do_.linux.lindo.core.model

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
@Serializable
data class GrantedBadge(
    val id: Int,
    val name: String,
    val description: String = "",
    @SerialName("badge_type_id") val badgeTypeId: Int = 3,
    val icon: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    val slug: String = "",
    @SerialName("grant_count") val grantCount: Int = 0,
    val allowTitle: Boolean = false
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
}

/**
 * 用户身份角标 (Flair)
 */
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
@Serializable
data class UserStatus(
    val description: String? = null,
    val emoji: String? = null
)

/**
 * 话题列表中的用户信息（含静态头像模板 + 动画头像原件 + Flair）
 */
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
    val lastPostedAt: String = "刚刚",
    val createdAtIso: String = "",
    val lastPostedAtIso: String = "",
    val originalPoster: TopicUser,
    val participantAvatars: List<TopicUser> = emptyList()
)

/**
 * Discourse Reactions 插件单个表情统计项（如 `heart` ❤️, `+1` 👍, `confetti_ball` 🎉, `fire` 🔥, `laughing` 😂, `clap` 👏, `hugs` 🤗）
 */
@Serializable
data class PostReactionItem(
    val id: String,
    val emoji: String,
    val count: Int
)

/**
 * 话题详情中的单层楼 (Post)
 */
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
    val trustLevel: Int = 1,
    val admin: Boolean = false,
    val moderator: Boolean = false,
    val groupModerator: Boolean = false,
    val isTopicOwner: Boolean = false,
    val flair: FlairInfo = FlairInfo(),
    val userStatus: UserStatus? = null,
    val isTodayBirthday: Boolean = false,
    val isTodayCakeday: Boolean = false,
    val badgesGranted: List<GrantedBadge> = emptyList(),
    val cookedHtml: String = "",
    val createdAtText: String = "",
    val replyToPostNumber: Int? = null,
    val replyToUsername: String? = null,
    val likeCount: Int = 0,
    val replyCount: Int = 0,
    val readsCount: Int = 0,
    val likedByMe: Boolean = false,
    val myReactionId: String? = null,
    val reactions: List<PostReactionItem> = emptyList(),
    val bookmarkedByMe: Boolean = false,
    val isSmallAction: Boolean = false,
    val actionCode: String? = null
)

/**
 * 完整话题详情模型
 */
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
    val loadError: String? = null
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
    val badges: List<GrantedBadge> = emptyList()
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
    val title: String,
    val actingUsername: String = "",
    val actingAvatarTemplate: String? = null,
    val excerpt: String = ""
) {
    val typeLabel: String
        get() = when (notificationType) {
            1 -> "提及了你"
            2 -> "回复了你"
            3 -> "引用了你"
            4 -> "编辑了帖子"
            5, 25 -> "赞了你的帖子"
            6 -> "发来私信"
            9 -> "回复了话题"
            12 -> "授予徽章"
            else -> "社区通知"
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

/**
 * Discourse 顶层 user_badges 解析与跨分页注入器
 *
 * 解决痛点：
 * 1. Discourse /t/{id}.json 的徽章数据不在 post 对象内，而在顶层 `user_badges.badges` 和 `user_badges.users` 中；
 * 2. `badges` 和 `users` 在不同版本/插件下可能是 Map(Object) 也可能是 List(Array)；
 * 3. 话题向下滚动翻页请求 `/t/{id}/posts.json` 时，保留并合并已知用户的徽章映射，防止翻页后楼层头部徽章消失。
 */
object BadgeInjector {
    private val userBadgesMemoryCache = mutableMapOf<Int, List<GrantedBadge>>()

    fun parseAndCacheUserBadges(rootJson: JsonObject): Map<Int, List<GrantedBadge>> {
        val container = rootJson["user_badges"] as? JsonObject ?: return userBadgesMemoryCache
        val badgesById = mutableMapOf<Int, GrantedBadge>()

        when (val badgesRaw = container["badges"]) {
            is JsonObject -> {
                for ((key, value) in badgesRaw) {
                    val badgeId = key.toIntOrNull() ?: continue
                    val obj = value as? JsonObject ?: continue
                    badgesById[badgeId] = parseGrantedBadge(obj, fallbackId = badgeId)
                }
            }
            is JsonArray -> {
                for (element in badgesRaw) {
                    val obj = element as? JsonObject ?: continue
                    val badgeId = obj["id"]?.jsonPrimitive?.intOrNull ?: continue
                    badgesById[badgeId] = parseGrantedBadge(obj, fallbackId = badgeId)
                }
            }
            else -> Unit
        }

        val userBadgeIds = mutableMapOf<Int, List<Int>>()
        when (val usersRaw = container["users"]) {
            is JsonObject -> {
                for ((key, value) in usersRaw) {
                    val userId = key.toIntOrNull() ?: continue
                    val obj = value as? JsonObject ?: continue
                    val ids = (obj["badge_ids"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
                        .orEmpty()
                    if (ids.isNotEmpty()) userBadgeIds[userId] = ids
                }
            }
            is JsonArray -> {
                for (element in usersRaw) {
                    val obj = element as? JsonObject ?: continue
                    val userId = obj["id"]?.jsonPrimitive?.intOrNull ?: continue
                    val ids = (obj["badge_ids"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
                        .orEmpty()
                    if (ids.isNotEmpty()) userBadgeIds[userId] = ids
                }
            }
            else -> Unit
        }

        for ((userId, ids) in userBadgeIds) {
            val resolved = ids.mapNotNull { badgesById[it] }
            if (resolved.isNotEmpty()) {
                userBadgesMemoryCache[userId] = resolved
            }
        }
        return userBadgesMemoryCache
    }

    fun getBadgesForUser(userId: Int): List<GrantedBadge> {
        return userBadgesMemoryCache[userId].orEmpty()
    }

    fun injectTopLevelUserBadges(posts: List<PostItemModel>, rootJson: JsonObject): List<PostItemModel> {
        parseAndCacheUserBadges(rootJson)
        return posts.map { post ->
            if (post.badgesGranted.isNotEmpty()) {
                post
            } else {
                val cached = getBadgesForUser(post.userId)
                if (cached.isNotEmpty()) post.copy(badgesGranted = cached) else post
            }
        }
    }

    fun parseGrantedBadge(obj: JsonObject, fallbackId: Int = 0): GrantedBadge {
        return GrantedBadge(
            id = obj["id"]?.jsonPrimitive?.intOrNull ?: fallbackId,
            name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "徽章 #$fallbackId",
            description = obj["description"]?.jsonPrimitive?.contentOrNull ?: "",
            badgeTypeId = obj["badge_type_id"]?.jsonPrimitive?.intOrNull ?: 3,
            icon = obj["icon"]?.jsonPrimitive?.contentOrNull,
            imageUrl = obj["image_url"]?.jsonPrimitive?.contentOrNull,
            slug = obj["slug"]?.jsonPrimitive?.contentOrNull ?: "",
            grantCount = obj["grant_count"]?.jsonPrimitive?.intOrNull ?: 0,
            allowTitle = obj["allow_title"]?.jsonPrimitive?.booleanOrNull ?: false
        )
    }
}
