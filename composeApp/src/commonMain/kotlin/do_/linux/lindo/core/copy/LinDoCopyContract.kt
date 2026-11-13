package do_.linux.lindo.core.copy

import do_.linux.lindo.core.diagnostics.DiagnosticCategory

/**
 * 全局业务状态枚举与结构化分类器：
 * 取代散落在网络层、模型层与 UI 层中的魔法数字（`0/1/2/3`、`1..803`）、裸字符串前缀判断与中文子串 `if-else`。
 */

/**
 * UI 错误语义分类枚举：统一驱动「去验证」「去登录」「重试」等恢复交互，不再在各处散落 `.contains("站点验证")`
 */
enum class UiErrorKind {
    SITE_VERIFICATION,
    GUEST_LOGIN_REQUIRED,
    AUTH_OR_PERMISSION,
    TRANSIENT_RECOVERABLE,
    GENERAL;

    companion object {
        fun classify(message: String?, isAuthorized: Boolean): UiErrorKind {
            val clean = message?.trim().orEmpty()
            if (clean.isEmpty()) return GENERAL
            if (clean.contains(LinDoNetworkCopy.SITE_VERIFICATION_REQUIRED.text) ||
                clean.contains("站点验证") ||
                clean.contains("Cloudflare", ignoreCase = true)
            ) {
                return SITE_VERIFICATION
            }
            val authOrPerm = clean.contains("403") ||
                clean.contains("登录") ||
                clean.contains("权限") ||
                clean.contains("无权") ||
                clean.contains("受限") ||
                clean.contains("私密") ||
                clean.contains("等级")
            if (authOrPerm) {
                return if (!isAuthorized && !clean.contains("等级")) {
                    GUEST_LOGIN_REQUIRED
                } else {
                    AUTH_OR_PERMISSION
                }
            }
            val transient = clean.contains("网络") ||
                clean.contains("连接") ||
                clean.contains("暂时不可用") ||
                clean.contains("频繁") ||
                clean.contains("不完整") ||
                clean.contains("加载失败") ||
                clean.contains("楼层加载失败") ||
                clean.contains("超时")
            return if (transient) TRANSIENT_RECOVERABLE else GENERAL
        }
    }
}

fun String?.isSiteVerificationError(): Boolean {
    if (this.isNullOrBlank()) return false
    return this.contains(LinDoNetworkCopy.SITE_VERIFICATION_REQUIRED.text) ||
        this.contains("站点验证")
}

fun String?.isAuthOrPermissionError(): Boolean {
    if (this.isNullOrBlank()) return false
    return this.contains("权限") ||
        this.contains("无权") ||
        this.contains("登录") ||
        this.contains("等级") ||
        this.contains("受限") ||
        this.contains("私密")
}

/**
 * Discourse 信任等级枚举（取代 `when (trustLevel) { 0 -> ... 4 -> ... }`）
 */
enum class DiscourseTrustLevel(val level: Int, val copy: LinDoNetworkCopy) {
    LV0(0, LinDoNetworkCopy.TRUST_LV0),
    LV1(1, LinDoNetworkCopy.TRUST_LV1),
    LV2(2, LinDoNetworkCopy.TRUST_LV2),
    LV3(3, LinDoNetworkCopy.TRUST_LV3),
    LV4(4, LinDoNetworkCopy.TRUST_LV4);

    companion object {
        fun fromLevel(level: Int): DiscourseTrustLevel? = entries.firstOrNull { it.level == level }

        fun displayLabel(level: Int, vipFallback: Boolean = false): String {
            return fromLevel(level)?.copy?.text
                ?: if (vipFallback) {
                    LinDoNetworkCopy.TRUST_LV_FALLBACK_VIP.format(level)
                } else {
                    LinDoNetworkCopy.TRUST_LV_FALLBACK_MEMBER.format(level)
                }
        }
    }
}

/**
 * 消息页通知二级筛选枚举（取代 `categoryFilter == 0/1/2/3/4`）
 */
enum class NotificationCategoryFilter(val labelCopy: LinDoMessagesCopy) {
    ALL(LinDoMessagesCopy.FILTER_ALL),
    REPLY(LinDoMessagesCopy.FILTER_REPLY),
    REACTION(LinDoMessagesCopy.FILTER_REACTION),
    MENTION(LinDoMessagesCopy.FILTER_MENTION),
    SYSTEM(LinDoMessagesCopy.FILTER_SYSTEM)
}

/**
 * 独立通知抽屉筛选枚举（取代 `filterTab == 0/1/2/3`）
 */
enum class NotificationSheetFilterTab(val labelCopy: LinDoMessagesCopy) {
    ALL(LinDoMessagesCopy.FILTER_ALL),
    UNREAD(LinDoMessagesCopy.FILTER_UNREAD),
    REPLY_AND_MENTION(LinDoMessagesCopy.FILTER_REPLY_AND_MENTION),
    REACTION(LinDoMessagesCopy.FILTER_REACTION)
}

/**
 * Discourse 通知类型完整枚举（取代散落的 `notificationType == 2/9/16/17/29..33/36/800..803` 魔法数字）
 */
enum class DiscourseNotificationKind(
    val code: Int,
    val category: NotificationCategoryFilter,
    val isChat: Boolean = false
) {
    MENTIONED(1, NotificationCategoryFilter.MENTION),
    REPLIED(2, NotificationCategoryFilter.REPLY),
    QUOTED(3, NotificationCategoryFilter.REPLY),
    EDITED(4, NotificationCategoryFilter.SYSTEM),
    LIKED(5, NotificationCategoryFilter.REACTION),
    PRIVATE_MESSAGE(6, NotificationCategoryFilter.SYSTEM),
    INVITED_TO_PRIVATE_MESSAGE(7, NotificationCategoryFilter.SYSTEM),
    INVITEE_ACCEPTED(8, NotificationCategoryFilter.SYSTEM),
    POSTED(9, NotificationCategoryFilter.REPLY),
    MOVED_POST(10, NotificationCategoryFilter.SYSTEM),
    LINKED(11, NotificationCategoryFilter.REPLY),
    GRANTED_BADGE(12, NotificationCategoryFilter.SYSTEM),
    INVITED_TO_TOPIC(13, NotificationCategoryFilter.SYSTEM),
    CUSTOM(14, NotificationCategoryFilter.SYSTEM),
    GROUP_MENTIONED(15, NotificationCategoryFilter.MENTION),
    GROUP_MESSAGE_SUMMARY(16, NotificationCategoryFilter.SYSTEM),
    WATCHING_FIRST_POST(17, NotificationCategoryFilter.SYSTEM),
    TOPIC_REMINDER(18, NotificationCategoryFilter.SYSTEM),
    LIKED_CONSOLIDATED(19, NotificationCategoryFilter.REACTION),
    POST_APPROVED(20, NotificationCategoryFilter.SYSTEM),
    CODE_REVIEW_COMMIT_APPROVED(21, NotificationCategoryFilter.SYSTEM),
    MEMBERSHIP_REQUEST_ACCEPTED(22, NotificationCategoryFilter.SYSTEM),
    MEMBERSHIP_REQUEST_CONSOLIDATED(23, NotificationCategoryFilter.SYSTEM),
    BOOKMARK_REMINDER(24, NotificationCategoryFilter.SYSTEM),
    REACTION(25, NotificationCategoryFilter.REACTION),
    VOTES_RELEASED(26, NotificationCategoryFilter.SYSTEM),
    EVENT_REMINDER(27, NotificationCategoryFilter.SYSTEM),
    EVENT_INVITATION(28, NotificationCategoryFilter.SYSTEM),
    CHAT_MENTION(29, NotificationCategoryFilter.MENTION, isChat = true),
    CHAT_MESSAGE(30, NotificationCategoryFilter.SYSTEM, isChat = true),
    CHAT_INVITATION(31, NotificationCategoryFilter.SYSTEM, isChat = true),
    CHAT_GROUP_MENTION(32, NotificationCategoryFilter.MENTION, isChat = true),
    CHAT_QUOTED(33, NotificationCategoryFilter.MENTION, isChat = true),
    ASSIGNED(34, NotificationCategoryFilter.SYSTEM),
    WATCHING_CATEGORY_OR_TAG(36, NotificationCategoryFilter.SYSTEM),
    FOLLOWING_CREATED_TOPIC(37, NotificationCategoryFilter.SYSTEM),
    FOLLOWING_REPLIED(38, NotificationCategoryFilter.REPLY),
    CIRCLE_REACTION(39, NotificationCategoryFilter.REACTION),
    BOOST_LEGACY(40, NotificationCategoryFilter.REACTION),
    BOOST(43, NotificationCategoryFilter.REACTION),
    PLUGIN_FOLLOWED(800, NotificationCategoryFilter.SYSTEM),
    PLUGIN_FOLLOW_TOPIC(801, NotificationCategoryFilter.SYSTEM),
    PLUGIN_FOLLOW_POST(802, NotificationCategoryFilter.REPLY),
    PLUGIN_BOOST(803, NotificationCategoryFilter.REACTION),
    PLUGIN_REACTION_EXT(900, NotificationCategoryFilter.REACTION),
    UNKNOWN(-1, NotificationCategoryFilter.SYSTEM);

    val supportsConsolidatedCount: Boolean
        get() = this == REPLIED || this == POSTED

    val needsPostAuthorLookup: Boolean
        get() = this == WATCHING_FIRST_POST ||
            this == WATCHING_CATEGORY_OR_TAG ||
            this == PLUGIN_FOLLOW_TOPIC ||
            this == PLUGIN_FOLLOW_POST

    val matchesReplyOrMentionSheetTab: Boolean
        get() = category == NotificationCategoryFilter.REPLY ||
            category == NotificationCategoryFilter.MENTION ||
            this == PRIVATE_MESSAGE

    fun routesToPrivateMessages(topicId: Int): Boolean =
        this == GROUP_MESSAGE_SUMMARY || (this == PRIVATE_MESSAGE && topicId <= 0)

    fun formatTypeLabel(
        count: Int,
        hasActorUsername: Boolean,
        postNumber: Int,
        topicId: Int
    ): String = when (this) {
        MENTIONED -> LinDoNetworkCopy.NOTIF_MENTIONED.text
        REPLIED -> when {
            count > 1 && hasActorUsername -> LinDoNetworkCopy.NOTIF_REPLIED_GROUPED_ACTOR.format(count)
            count > 1 -> LinDoNetworkCopy.NOTIF_REPLIED_GROUPED.format(count)
            else -> LinDoNetworkCopy.NOTIF_REPLIED.text
        }
        QUOTED -> LinDoNetworkCopy.NOTIF_QUOTED.text
        EDITED -> LinDoNetworkCopy.NOTIF_EDITED.text
        LIKED -> if (postNumber > 1) LinDoNetworkCopy.NOTIF_LIKED_REPLY.text else LinDoNetworkCopy.NOTIF_LIKED_TOPIC.text
        PRIVATE_MESSAGE -> LinDoNetworkCopy.NOTIF_PRIVATE_MESSAGE.text
        INVITED_TO_PRIVATE_MESSAGE -> LinDoNetworkCopy.NOTIF_INVITED_PM.text
        INVITEE_ACCEPTED -> LinDoNetworkCopy.NOTIF_INVITEE_ACCEPTED.text
        POSTED -> when {
            count > 1 && hasActorUsername -> LinDoNetworkCopy.NOTIF_POSTED_GROUPED_ACTOR.format(count)
            count > 1 -> LinDoNetworkCopy.NOTIF_POSTED_GROUPED.format(count)
            else -> LinDoNetworkCopy.NOTIF_POSTED.text
        }
        MOVED_POST -> LinDoNetworkCopy.NOTIF_MOVED_POST.text
        LINKED -> LinDoNetworkCopy.NOTIF_LINKED.text
        GRANTED_BADGE -> LinDoNetworkCopy.NOTIF_GRANTED_BADGE.text
        INVITED_TO_TOPIC -> LinDoNetworkCopy.NOTIF_INVITED_TOPIC.text
        CUSTOM -> LinDoNetworkCopy.NOTIF_CUSTOM_SYSTEM.text
        GROUP_MENTIONED -> LinDoNetworkCopy.NOTIF_GROUP_MENTIONED.text
        GROUP_MESSAGE_SUMMARY -> LinDoNetworkCopy.NOTIF_GROUP_SUMMARY.text
        WATCHING_FIRST_POST -> LinDoNetworkCopy.NOTIF_WATCHING_FIRST_POST.text
        TOPIC_REMINDER -> LinDoNetworkCopy.NOTIF_TOPIC_REMINDER.text
        LIKED_CONSOLIDATED -> if (count > 1) {
            LinDoNetworkCopy.NOTIF_LIKED_CONSOLIDATED_COUNT.format(count)
        } else {
            LinDoNetworkCopy.NOTIF_LIKED_CONSOLIDATED.text
        }
        POST_APPROVED -> LinDoNetworkCopy.NOTIF_POST_APPROVED.text
        CODE_REVIEW_COMMIT_APPROVED -> LinDoNetworkCopy.NOTIF_CODE_REVIEW_APPROVED.text
        MEMBERSHIP_REQUEST_ACCEPTED -> LinDoNetworkCopy.NOTIF_MEMBERSHIP_ACCEPTED.text
        MEMBERSHIP_REQUEST_CONSOLIDATED -> LinDoNetworkCopy.NOTIF_MEMBERSHIP_CONSOLIDATED.text
        BOOKMARK_REMINDER -> LinDoNetworkCopy.NOTIF_BOOKMARK_REMINDER.text
        REACTION -> LinDoNetworkCopy.NOTIF_REACTION.text
        VOTES_RELEASED -> LinDoNetworkCopy.NOTIF_VOTES_RELEASED.text
        EVENT_REMINDER -> LinDoNetworkCopy.NOTIF_EVENT_REMINDER.text
        EVENT_INVITATION -> LinDoNetworkCopy.NOTIF_EVENT_INVITATION.text
        CHAT_MENTION -> LinDoNetworkCopy.NOTIF_CHAT_MENTION.text
        CHAT_MESSAGE -> LinDoNetworkCopy.NOTIF_CHAT_MESSAGE.text
        CHAT_INVITATION -> LinDoNetworkCopy.NOTIF_CHAT_INVITATION.text
        CHAT_GROUP_MENTION -> LinDoNetworkCopy.NOTIF_CHAT_GROUP_MENTION.text
        CHAT_QUOTED -> LinDoNetworkCopy.NOTIF_CHAT_QUOTED.text
        ASSIGNED -> LinDoNetworkCopy.NOTIF_ASSIGNED.text
        WATCHING_CATEGORY_OR_TAG -> if (topicId > 0) {
            LinDoNetworkCopy.NOTIF_WATCHING_CATEGORY_OR_TAG.text
        } else {
            LinDoNetworkCopy.NOTIF_FOLLOWED_YOU.text
        }
        FOLLOWING_CREATED_TOPIC, PLUGIN_FOLLOW_TOPIC -> LinDoNetworkCopy.NOTIF_FOLLOW_NEW_TOPIC.text
        FOLLOWING_REPLIED -> LinDoNetworkCopy.NOTIF_FOLLOW_NEW_REPLY.text
        BOOST_LEGACY, BOOST, PLUGIN_BOOST -> LinDoNetworkCopy.NOTIF_BOOSTED_POST.text
        PLUGIN_FOLLOWED -> LinDoNetworkCopy.NOTIF_FOLLOWED_YOU.text
        PLUGIN_FOLLOW_POST -> when {
            count > 1 && hasActorUsername -> LinDoNetworkCopy.NOTIF_FOLLOW_NEW_REPLY_GROUPED_ACTOR.format(count)
            count > 1 -> LinDoNetworkCopy.NOTIF_FOLLOW_NEW_REPLY_GROUPED.format(count)
            else -> LinDoNetworkCopy.NOTIF_FOLLOW_NEW_REPLY.text
        }
        CIRCLE_REACTION, PLUGIN_REACTION_EXT, UNKNOWN ->
            if (topicId > 0) LinDoNetworkCopy.NOTIF_TOPIC_INTERACTION.text else LinDoNetworkCopy.ACTION_COMMUNITY_ACTIVITY.text
    }

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun fromCode(code: Int): DiscourseNotificationKind = byCode[code] ?: UNKNOWN
    }
}

/**
 * 用户活动流类型枚举（取代 `actionType == 1/2/4/5/88`）
 */
enum class DiscourseUserActionKind(val code: Int, val copy: LinDoNetworkCopy, val filterParam: String? = null) {
    LIKE_GIVEN(1, LinDoNetworkCopy.ACTION_LIKE_GIVEN, "1"),
    LIKE_RECEIVED(2, LinDoNetworkCopy.ACTION_LIKE_RECEIVED, "2"),
    NEW_TOPIC(4, LinDoNetworkCopy.ACTION_NEW_TOPIC, "4"),
    REPLY(5, LinDoNetworkCopy.ACTION_REPLY, "5"),
    BOOKMARK(88, LinDoNetworkCopy.ACTION_BOOKMARK),
    OTHER(-1, LinDoNetworkCopy.ACTION_COMMUNITY_ACTIVITY);

    companion object {
        fun fromCode(code: Int): DiscourseUserActionKind =
            entries.firstOrNull { it.code == code } ?: OTHER
    }
}

/**
 * 话题订阅级别枚举（取代 `notificationLevel == 0/1/2/3` 魔法数字）
 */
enum class TopicNotificationLevel(val code: Int, val menuLabel: LinDoReaderCopy) {
    MUTED(0, LinDoReaderCopy.SUBSCRIPTION_MUTED),
    REGULAR(1, LinDoReaderCopy.SUBSCRIPTION_DEFAULT),
    TRACKING(2, LinDoReaderCopy.SUBSCRIPTION_DEFAULT),
    WATCHING(3, LinDoReaderCopy.SUBSCRIPTION_WATCHING);

    fun nextQuickToggle(): TopicNotificationLevel = when (this) {
        REGULAR, TRACKING -> WATCHING
        WATCHING -> MUTED
        MUTED -> REGULAR
    }

    companion object {
        fun fromCode(code: Int): TopicNotificationLevel =
            entries.firstOrNull { it.code == code } ?: REGULAR
    }
}

/**
 * 云端与本机草稿目标类型枚举（统一 `DraftsSheet`、`LinDoApp` 与 `LinDoNetworkStack` 的草稿分发逻辑）
 */
enum class CloudDraftKind(val badgeCopy: LinDoComposerCopy, val wireAction: String) {
    PRIVATE_MESSAGE(LinDoComposerCopy.DRAFT_KIND_PM, "privateMessage"),
    EDIT_POST(LinDoComposerCopy.DRAFT_KIND_EDIT, "edit"),
    NEW_TOPIC(LinDoComposerCopy.DRAFT_KIND_NEW_TOPIC, "createTopic"),
    TOPIC_REPLY(LinDoComposerCopy.DRAFT_KIND_REPLY, "reply");

    companion object {
        fun resolve(action: String, draftKey: String): CloudDraftKind = when {
            action == "privateMessage" ||
                draftKey.startsWith("private_message") ||
                draftKey.startsWith("new_private_message") -> PRIVATE_MESSAGE
            action.startsWith("edit") ||
                draftKey.startsWith("edit_post_") -> EDIT_POST
            draftKey == "new_topic" ||
                action == "createTopic" -> NEW_TOPIC
            else -> TOPIC_REPLY
        }
    }
}

/**
 * Discourse 帖子流系统动作码枚举（取代 19 分支字符串 `when`）
 */
enum class SmallActionKind(val wireCodes: Set<String>, val copy: LinDoNetworkCopy) {
    CLOSED(setOf("closed.enabled"), LinDoNetworkCopy.SMALL_ACTION_CLOSED),
    OPENED(setOf("closed.disabled"), LinDoNetworkCopy.SMALL_ACTION_OPENED),
    AUTOCLOSED(setOf("autoclosed.enabled"), LinDoNetworkCopy.SMALL_ACTION_AUTOCLOSED),
    AUTOOPENED(setOf("autoclosed.disabled"), LinDoNetworkCopy.SMALL_ACTION_AUTOOPENED),
    ARCHIVED(setOf("archived.enabled"), LinDoNetworkCopy.SMALL_ACTION_ARCHIVED),
    UNARCHIVED(setOf("archived.disabled"), LinDoNetworkCopy.SMALL_ACTION_UNARCHIVED),
    PINNED(setOf("pinned.enabled", "pinned_globally.enabled"), LinDoNetworkCopy.SMALL_ACTION_PINNED),
    UNPINNED(setOf("pinned.disabled", "pinned_globally.disabled", "unpinned"), LinDoNetworkCopy.SMALL_ACTION_UNPINNED),
    BANNER(setOf("banner.enabled"), LinDoNetworkCopy.SMALL_ACTION_BANNER),
    UNBANNER(setOf("banner.disabled"), LinDoNetworkCopy.SMALL_ACTION_UNBANNER),
    SPLIT_TOPIC(setOf("split_topic"), LinDoNetworkCopy.SMALL_ACTION_SPLIT_TOPIC),
    VISIBLE(setOf("visible.enabled", "public_topic"), LinDoNetworkCopy.SMALL_ACTION_VISIBLE),
    INVISIBLE(setOf("visible.disabled", "private_topic"), LinDoNetworkCopy.SMALL_ACTION_INVISIBLE),
    INVITED(setOf("invited_user", "invited_group"), LinDoNetworkCopy.SMALL_ACTION_INVITED),
    REMOVED(setOf("removed_user", "removed_group"), LinDoNetworkCopy.SMALL_ACTION_REMOVED);

    companion object {
        fun localize(actionCode: String): String {
            val matched = entries.firstOrNull { actionCode in it.wireCodes }
            return matched?.copy?.text ?: LinDoNetworkCopy.SMALL_ACTION_FALLBACK.format(actionCode)
        }
    }
}

/**
 * 特殊用户组视觉枚举（取代散落的 `primaryGroupName == "g-merchant" || primaryGroupName == "g-merchant2"`）
 */
enum class SpecialUserGroup {
    MERCHANT,
    STANDARD;

    val usesMerchantBadgeStyle: Boolean get() = this == MERCHANT

    companion object {
        private val merchantGroupNames = setOf("g-merchant", "g-merchant2")
        fun fromGroupName(primaryGroupName: String?): SpecialUserGroup {
            val clean = primaryGroupName?.trim()?.lowercase().orEmpty()
            return if (clean in merchantGroupNames) MERCHANT else STANDARD
        }
    }
}

/**
 * 游客登录拦截场景枚举（统一管理所有受限功能的登录引导标题与副标题）
 */
enum class GuestGateScenario(
    val titleCopy: LinDoAuthCopy,
    val subtitleCopy: LinDoAuthCopy
) {
    DEFAULT(LinDoAuthCopy.GUEST_DEFAULT_TITLE, LinDoAuthCopy.GUEST_DEFAULT_SUBTITLE),
    CONNECT(LinDoAuthCopy.GUEST_CONNECT_TITLE, LinDoAuthCopy.GUEST_CONNECT_SUBTITLE),
    CREDIT(LinDoAuthCopy.GUEST_CREDIT_TITLE, LinDoAuthCopy.GUEST_CREDIT_SUBTITLE),
    CDK(LinDoAuthCopy.GUEST_CDK_TITLE, LinDoAuthCopy.GUEST_CDK_SUBTITLE),
    LD_HUB(LinDoAuthCopy.GUEST_HUB_TITLE, LinDoAuthCopy.GUEST_HUB_SUBTITLE),
    LOTTERY(LinDoAuthCopy.GUEST_LOTTERY_TITLE, LinDoAuthCopy.GUEST_LOTTERY_SUBTITLE),
    PUBLISH_CONTENT(LinDoAuthCopy.GUEST_PUBLISH_CONTENT_TITLE, LinDoAuthCopy.GUEST_PUBLISH_CONTENT_SUBTITLE),
    PUBLISH_TOPIC(LinDoAuthCopy.GUEST_PUBLISH_TOPIC_TITLE, LinDoAuthCopy.GUEST_PUBLISH_TOPIC_SUBTITLE),
    CHAT(LinDoAuthCopy.GUEST_CHAT_TITLE, LinDoAuthCopy.GUEST_CHAT_SUBTITLE),
    FOLLOW_LIST(LinDoAuthCopy.GUEST_FOLLOW_LIST_TITLE, LinDoAuthCopy.GUEST_FOLLOW_LIST_SUBTITLE),
    EDIT_PROFILE(LinDoAuthCopy.GUEST_EDIT_PROFILE_TITLE, LinDoAuthCopy.GUEST_EDIT_PROFILE_SUBTITLE),
    EDIT_PROFILE_WEB(LinDoAuthCopy.GUEST_EDIT_PROFILE_TITLE, LinDoAuthCopy.GUEST_EDIT_PROFILE_WEB_SUBTITLE),
    PREFERENCES(LinDoAuthCopy.GUEST_PREFERENCES_TITLE, LinDoAuthCopy.GUEST_PREFERENCES_SUBTITLE),
    PERSONAL_CENTER(LinDoAuthCopy.GUEST_PERSONAL_CENTER_TITLE, LinDoAuthCopy.GUEST_PERSONAL_CENTER_SUBTITLE),
    BOOKMARK_TOPIC(LinDoAuthCopy.GUEST_BOOKMARK_TOPIC_TITLE, LinDoAuthCopy.GUEST_BOOKMARK_TOPIC_SUBTITLE),
    SUBSCRIBE_TOPIC(LinDoAuthCopy.GUEST_SUBSCRIBE_TOPIC_TITLE, LinDoAuthCopy.GUEST_SUBSCRIBE_TOPIC_SUBTITLE),
    TOPIC_LOGIN_REQUIRED(LinDoAuthCopy.GUEST_TOPIC_LOGIN_REQUIRED_TITLE, LinDoAuthCopy.GUEST_TOPIC_LOGIN_REQUIRED_SUBTITLE),
    REPLY_TOPIC(LinDoAuthCopy.GUEST_REPLY_TOPIC_TITLE, LinDoAuthCopy.GUEST_REPLY_TOPIC_SUBTITLE),
    REACTION(LinDoAuthCopy.GUEST_REACTION_TITLE, LinDoAuthCopy.GUEST_REACTION_SUBTITLE),
    BOOST(LinDoAuthCopy.GUEST_BOOST_TITLE, LinDoAuthCopy.GUEST_BOOST_SUBTITLE),
    POLL(LinDoAuthCopy.GUEST_POLL_TITLE, LinDoAuthCopy.GUEST_POLL_SUBTITLE),
    GLOBAL_SEARCH(LinDoAuthCopy.GUEST_SEARCH_TITLE, LinDoAuthCopy.GUEST_SEARCH_SUBTITLE),
    BOOKMARKS_CENTER(LinDoAuthCopy.GUEST_BOOKMARKS_CENTER_TITLE, LinDoAuthCopy.GUEST_BOOKMARKS_CENTER_SUBTITLE),
    READ_HISTORY(LinDoAuthCopy.GUEST_HISTORY_TITLE, LinDoAuthCopy.GUEST_HISTORY_SUBTITLE),
    CLOUD_DRAFTS(LinDoAuthCopy.GUEST_CLOUD_DRAFTS_TITLE, LinDoAuthCopy.GUEST_CLOUD_DRAFTS_SUBTITLE),
    ACTIVITIES(LinDoAuthCopy.GUEST_ACTIVITIES_TITLE, LinDoAuthCopy.GUEST_ACTIVITIES_SUBTITLE),
    LEADERBOARD(LinDoAuthCopy.GUEST_LEADERBOARD_TITLE, LinDoAuthCopy.GUEST_LEADERBOARD_SUBTITLE)
}

/**
 * Android 与桌面调试预览场景枚举（取代 `debugPreview == "icon"` 等 13 路字符串分支）
 */
enum class DebugPreviewScene(val wireKey: String, val isRegressionSheetScene: Boolean = false) {
    ICON("icon"),
    WELCOME("welcome"),
    RICH("rich"),
    SEARCH("search", isRegressionSheetScene = true),
    BOOKMARKS("bookmarks", isRegressionSheetScene = true),
    NOTIFICATIONS("notifications", isRegressionSheetScene = true),
    DRAFTS("drafts", isRegressionSheetScene = true),
    HISTORY("history", isRegressionSheetScene = true),
    ACTIVITY("activity", isRegressionSheetScene = true),
    MESSAGES("messages", isRegressionSheetScene = true),
    WEB("web"),
    WEB_CRASH("web_crash"),
    CONNECT("connect");

    companion object {
        fun fromKey(raw: String?): DebugPreviewScene? {
            val clean = raw?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wireKey == clean }
        }
    }
}

/**
 * 富文本块级 AST 种类枚举
 */
enum class CalloutBlockKind(val wireKey: String, val defaultTitle: LinDoReaderCopy) {
    LOTTERY("lottery", LinDoReaderCopy.CALLOUT_LOTTERY),
    WARNING("warning", LinDoReaderCopy.CALLOUT_WARNING),
    TIP("tip", LinDoReaderCopy.CALLOUT_TIP),
    NOTE("note", LinDoReaderCopy.CALLOUT_NOTE);

    companion object {
        fun fromKey(raw: String): CalloutBlockKind =
            entries.firstOrNull { it.wireKey.equals(raw, ignoreCase = true) } ?: NOTE
    }
}

enum class MediaBlockKind(val wireKey: String) {
    AUDIO("audio"),
    VIDEO("video"),
    IFRAME("iframe"),
    OBJECT("object"),
    EMBED("embed");

    val isAudio: Boolean get() = this == AUDIO

    companion object {
        fun fromKey(raw: String): MediaBlockKind =
            entries.firstOrNull { it.wireKey.equals(raw, ignoreCase = true) } ?: VIDEO
    }
}

enum class UnsupportedBlockKind(val wireKey: String) {
    GENERIC("generic"),
    POLL("poll"),
    MATH("math"),
    MERGED_TABLE("merged_table"),
    SVG("svg");

    companion object {
        fun fromKey(raw: String): UnsupportedBlockKind =
            entries.firstOrNull { it.wireKey.equals(raw, ignoreCase = true) } ?: GENERIC
    }
}

/**
 * 各功能页面子标签、过滤器与视图模式枚举（彻底消除 `0/1/2/3` 魔法数字分支）
 */
enum class MessagesSubTab(val labelCopy: LinDoMessagesCopy) {
    NOTIFICATIONS(LinDoMessagesCopy.TAB_NOTIFICATIONS),
    PRIVATE_MESSAGES(LinDoMessagesCopy.TAB_PRIVATE_MESSAGES),
    CHAT(LinDoMessagesCopy.TAB_CHAT)
}

enum class PrivateMessagesViewMode(val tabLabel: LinDoMessagesCopy? = null) {
    INBOX(LinDoMessagesCopy.PM_TAB_INBOX),
    SENT(LinDoMessagesCopy.PM_TAB_SENT),
    ARCHIVE(LinDoMessagesCopy.PM_TAB_ARCHIVE),
    COMPOSER(null);

    companion object {
        val mailboxTabs = listOf(INBOX, SENT, ARCHIVE)
    }
}

enum class ReaderFontScalePreset(val scale: Float, val labelCopy: LinDoMessagesCopy) {
    SMALL(0.95f, LinDoMessagesCopy.FONT_SCALE_SMALL),
    STANDARD(1.0f, LinDoMessagesCopy.FONT_SCALE_STANDARD),
    LARGE(1.12f, LinDoMessagesCopy.FONT_SCALE_LARGE);

    fun nextCycle(): ReaderFontScalePreset = when (this) {
        SMALL -> STANDARD
        STANDARD -> LARGE
        LARGE -> SMALL
    }

    companion object {
        fun fromScale(fontScale: Float): ReaderFontScalePreset = when {
            fontScale < 0.975f -> SMALL
            fontScale < 1.06f -> STANDARD
            else -> LARGE
        }
    }
}

enum class FeedDensityMode(val isCompact: Boolean, val labelCopy: LinDoMessagesCopy) {
    SPACIOUS(false, LinDoMessagesCopy.FEED_DENSITY_SPACIOUS),
    COMPACT(true, LinDoMessagesCopy.FEED_DENSITY_COMPACT);

    companion object {
        fun fromCompact(isCompact: Boolean): FeedDensityMode = if (isCompact) COMPACT else SPACIOUS
    }
}

enum class PullRefreshStage {
    IDLE,
    PULLING,
    READY
}

enum class BadgesSheetTab {
    EARNED,
    ALL_SITE
}

enum class BadgeTierFilter(val badgeTypeId: Int?, val labelCopy: LinDoMessagesCopy) {
    ALL(null, LinDoMessagesCopy.FILTER_ALL),
    GOLD(1, LinDoMessagesCopy.BADGE_TIER_GOLD),
    SILVER(2, LinDoMessagesCopy.BADGE_TIER_SILVER),
    BRONZE(3, LinDoMessagesCopy.BADGE_TIER_BRONZE),
    ALLOW_TITLE(null, LinDoMessagesCopy.BADGE_TIER_ALLOW_TITLE)
}

enum class BadgeGroupingKind(val apiName: String, val labelCopy: LinDoMessagesCopy) {
    GETTING_STARTED("Getting Started", LinDoMessagesCopy.BADGE_GROUP_GETTING_STARTED),
    COMMUNITY("Community", LinDoMessagesCopy.BADGE_GROUP_COMMUNITY),
    POSTING("Posting", LinDoMessagesCopy.BADGE_GROUP_POSTING),
    TRUST_LEVEL("Trust Level", LinDoMessagesCopy.BADGE_GROUP_TRUST_LEVEL),
    OTHER("Other", LinDoMessagesCopy.BADGE_GROUP_OTHER);

    companion object {
        fun localize(rawName: String): String {
            if (rawName.isBlank()) return OTHER.labelCopy.text
            return entries.firstOrNull { it.apiName == rawName }?.labelCopy?.text ?: rawName
        }
    }
}

enum class WorkbenchLinkTab(val labelCopy: LinDoComposerCopy) {
    QUOTE_POST(LinDoComposerCopy.LINK_TAB_QUOTE_POST),
    EXTERNAL_URL(LinDoComposerCopy.LINK_TAB_EXTERNAL_URL)
}

enum class WorkbenchPollType(val wireType: String, val labelCopy: LinDoComposerCopy) {
    REGULAR("regular", LinDoComposerCopy.POLL_TYPE_SINGLE),
    MULTIPLE("multiple", LinDoComposerCopy.POLL_TYPE_MULTIPLE),
    NUMBER("number", LinDoComposerCopy.POLL_TYPE_NUMBER);

    companion object {
        fun fromWire(raw: String): WorkbenchPollType =
            entries.firstOrNull { it.wireType == raw } ?: REGULAR
    }
}

enum class WorkbenchPollResultsVisibility(val wireValue: String, val labelCopy: LinDoComposerCopy) {
    ALWAYS("always", LinDoComposerCopy.POLL_RESULTS_ALWAYS),
    ON_VOTE("on_vote", LinDoComposerCopy.POLL_RESULTS_ON_VOTE),
    ON_CLOSE("on_close", LinDoComposerCopy.POLL_RESULTS_ON_CLOSE);

    companion object {
        fun fromWire(raw: String): WorkbenchPollResultsVisibility =
            entries.firstOrNull { it.wireValue == raw } ?: ALWAYS
    }
}

enum class ClearanceSyncState {
    IDLE,
    SYNCED,
    UNRECOGNIZED
}

enum class SearchSortOrder(val wireOrder: String?, val labelCopy: LinDoFeedCopy) {
    RELEVANCE(null, LinDoFeedCopy.SEARCH_SORT_RELEVANCE),
    LATEST("latest", LinDoFeedCopy.SEARCH_SORT_LATEST),
    LIKES("likes", LinDoFeedCopy.SEARCH_SORT_LIKES)
}

enum class UserActionFilterTab(val apiFilter: String?, val labelCopy: LinDoMessagesCopy) {
    ALL(null, LinDoMessagesCopy.ACTIVITY_TAB_ALL),
    TOPICS("4", LinDoMessagesCopy.ACTIVITY_TAB_TOPICS),
    REPLIES("5", LinDoMessagesCopy.ACTIVITY_TAB_REPLIES),
    LIKES("1", LinDoMessagesCopy.ACTIVITY_TAB_LIKES)
}

enum class PublicUserCardTab {
    ALL_ACTIVITY,
    TOPICS,
    REPLIES,
    BADGES
}

enum class FollowNetworkTab(val labelCopy: LinDoMessagesCopy) {
    FOLLOWING(LinDoMessagesCopy.FOLLOW_TAB_FOLLOWING),
    FOLLOWERS(LinDoMessagesCopy.FOLLOW_TAB_FOLLOWERS),
    FEED(LinDoMessagesCopy.FOLLOW_TAB_FEED)
}

enum class LeaderboardPeriod(val wirePeriod: String, val labelCopy: LinDoMessagesCopy) {
    DAILY("daily", LinDoMessagesCopy.LEADERBOARD_DAILY),
    WEEKLY("weekly", LinDoMessagesCopy.LEADERBOARD_WEEKLY),
    MONTHLY("monthly", LinDoMessagesCopy.LEADERBOARD_MONTHLY),
    ALL("all", LinDoMessagesCopy.LEADERBOARD_ALL)
}

enum class AboutTeamTab {
    ADMINS,
    MODERATORS
}

enum class DiagnosticFilterTab(val category: DiagnosticCategory?, val labelCopy: LinDoMessagesCopy) {
    ALL(null, LinDoMessagesCopy.FILTER_ALL),
    NETWORK(DiagnosticCategory.NETWORK, LinDoMessagesCopy.DIAG_TAB_NETWORK),
    AUTH(DiagnosticCategory.AUTH, LinDoMessagesCopy.DIAG_TAB_AUTH),
    CRASH(DiagnosticCategory.CRASH, LinDoMessagesCopy.DIAG_TAB_CRASH),
    SYSTEM(DiagnosticCategory.SYSTEM, LinDoMessagesCopy.DIAG_TAB_SYSTEM)
}

enum class FloorNavigatorMode {
    SLIDER,
    EXACT_INPUT
}
