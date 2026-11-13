// ignore_for_file: constant_identifier_names
import 'lindo_auth_copy.dart';
import 'lindo_common_copy.dart';
import 'lindo_composer_copy.dart';
import 'lindo_copy_entry.dart';
import 'lindo_feed_copy.dart';
import 'lindo_messages_copy.dart';
import 'lindo_network_copy.dart';
import 'lindo_reader_copy.dart';

enum DiagnosticCategory {
  NETWORK('NETWORK'),
  AUTH('AUTH'),
  CRASH('CRASH'),
  SYSTEM('SYSTEM');

  final String wireName;
  const DiagnosticCategory(this.wireName);
}

enum DiagnosticLevel {
  INFO('INFO'),
  WARN('WARN'),
  ERROR('ERROR'),
  CRASH('CRASH');

  final String wireName;
  const DiagnosticLevel(this.wireName);
}

enum UiErrorKind {
  SITE_VERIFICATION,
  GUEST_LOGIN_REQUIRED,
  AUTH_OR_PERMISSION,
  TRANSIENT_RECOVERABLE,
  GENERAL;

  static UiErrorKind classify(String? message, {required bool isAuthorized}) {
    final clean = (message ?? '').trim();
    if (clean.isEmpty) return GENERAL;
    final lower = clean.toLowerCase();
    if (clean.contains(LinDoNetworkCopy.SITE_VERIFICATION_REQUIRED.text) ||
        clean.contains('站点验证') ||
        lower.contains('cloudflare')) {
      return SITE_VERIFICATION;
    }
    final authOrPerm = clean.contains('403') ||
        clean.contains('登录') ||
        clean.contains('权限') ||
        clean.contains('无权') ||
        clean.contains('受限') ||
        clean.contains('私密') ||
        clean.contains('等级');
    if (authOrPerm) {
      return (!isAuthorized && !clean.contains('等级'))
          ? GUEST_LOGIN_REQUIRED
          : AUTH_OR_PERMISSION;
    }
    final transient = clean.contains('网络') ||
        clean.contains('连接') ||
        clean.contains('暂时不可用') ||
        clean.contains('频繁') ||
        clean.contains('不完整') ||
        clean.contains('加载失败') ||
        clean.contains('楼层加载失败') ||
        clean.contains('超时');
    return transient ? TRANSIENT_RECOVERABLE : GENERAL;
  }
}

extension LinDoErrorStringExt on String? {
  bool get isSiteVerificationError {
    final s = this;
    if (s == null || s.trim().isEmpty) return false;
    return s.contains(LinDoNetworkCopy.SITE_VERIFICATION_REQUIRED.text) ||
        s.contains('站点验证') ||
        s.toLowerCase().contains('cloudflare');
  }

  bool get isAuthOrPermissionError {
    final s = this;
    if (s == null || s.trim().isEmpty) return false;
    return s.contains('权限') ||
        s.contains('无权') ||
        s.contains('登录') ||
        s.contains('等级') ||
        s.contains('受限') ||
        s.contains('私密');
  }
}

enum DiscourseTrustLevel {
  LV0(0, LinDoNetworkCopy.TRUST_LV0),
  LV1(1, LinDoNetworkCopy.TRUST_LV1),
  LV2(2, LinDoNetworkCopy.TRUST_LV2),
  LV3(3, LinDoNetworkCopy.TRUST_LV3),
  LV4(4, LinDoNetworkCopy.TRUST_LV4);

  final int level;
  final LinDoNetworkCopy copy;
  const DiscourseTrustLevel(this.level, this.copy);

  static DiscourseTrustLevel? fromLevel(int level) {
    for (final item in values) {
      if (item.level == level) return item;
    }
    return null;
  }

  static String displayLabel(int level, {bool vipFallback = false}) {
    final matched = fromLevel(level);
    if (matched != null) return matched.copy.text;
    return vipFallback
        ? LinDoNetworkCopy.TRUST_LV_FALLBACK_VIP.format(level)
        : LinDoNetworkCopy.TRUST_LV_FALLBACK_MEMBER.format(level);
  }
}

enum NotificationCategoryFilter {
  ALL(LinDoMessagesCopy.FILTER_ALL),
  REPLY(LinDoMessagesCopy.FILTER_REPLY),
  REACTION(LinDoMessagesCopy.FILTER_REACTION),
  MENTION(LinDoMessagesCopy.FILTER_MENTION),
  SYSTEM(LinDoMessagesCopy.FILTER_SYSTEM);

  final LinDoMessagesCopy labelCopy;
  const NotificationCategoryFilter(this.labelCopy);
}

enum NotificationSheetFilterTab {
  ALL(LinDoMessagesCopy.FILTER_ALL),
  UNREAD(LinDoMessagesCopy.FILTER_UNREAD),
  REPLY_AND_MENTION(LinDoMessagesCopy.FILTER_REPLY_AND_MENTION),
  REACTION(LinDoMessagesCopy.FILTER_REACTION);

  final LinDoMessagesCopy labelCopy;
  const NotificationSheetFilterTab(this.labelCopy);
}

enum DiscourseNotificationKind {
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
  CHAT_MENTION(29, NotificationCategoryFilter.MENTION, isChat: true),
  CHAT_MESSAGE(30, NotificationCategoryFilter.SYSTEM, isChat: true),
  CHAT_INVITATION(31, NotificationCategoryFilter.SYSTEM, isChat: true),
  CHAT_GROUP_MENTION(32, NotificationCategoryFilter.MENTION, isChat: true),
  CHAT_QUOTED(33, NotificationCategoryFilter.MENTION, isChat: true),
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

  final int code;
  final NotificationCategoryFilter category;
  final bool isChat;
  const DiscourseNotificationKind(this.code, this.category, {this.isChat = false});

  bool get supportsConsolidatedCount => this == REPLIED || this == POSTED;

  bool get matchesReplyOrMentionSheetTab =>
      category == NotificationCategoryFilter.REPLY ||
      category == NotificationCategoryFilter.MENTION ||
      this == PRIVATE_MESSAGE;

  bool routesToPrivateMessages(int topicId) =>
      this == GROUP_MESSAGE_SUMMARY || (this == PRIVATE_MESSAGE && topicId <= 0);

  static DiscourseNotificationKind fromCode(int code) {
    for (final k in values) {
      if (k.code == code) return k;
    }
    return UNKNOWN;
  }

  String formatTypeLabel({
    required int count,
    required bool hasActorUsername,
    required int postNumber,
    required int topicId,
  }) {
    switch (this) {
      case MENTIONED:
        return LinDoNetworkCopy.NOTIF_MENTIONED.text;
      case REPLIED:
        if (count > 1 && hasActorUsername) {
          return LinDoNetworkCopy.NOTIF_REPLIED_GROUPED_ACTOR.format(count);
        }
        if (count > 1) return LinDoNetworkCopy.NOTIF_REPLIED_GROUPED.format(count);
        return LinDoNetworkCopy.NOTIF_REPLIED.text;
      case QUOTED:
        return LinDoNetworkCopy.NOTIF_QUOTED.text;
      case EDITED:
        return LinDoNetworkCopy.NOTIF_EDITED.text;
      case LIKED:
        return postNumber > 1
            ? LinDoNetworkCopy.NOTIF_LIKED_REPLY.text
            : LinDoNetworkCopy.NOTIF_LIKED_TOPIC.text;
      case PRIVATE_MESSAGE:
        return LinDoNetworkCopy.NOTIF_PRIVATE_MESSAGE.text;
      case INVITED_TO_PRIVATE_MESSAGE:
        return LinDoNetworkCopy.NOTIF_INVITED_PM.text;
      case INVITEE_ACCEPTED:
        return LinDoNetworkCopy.NOTIF_INVITEE_ACCEPTED.text;
      case POSTED:
        if (count > 1 && hasActorUsername) {
          return LinDoNetworkCopy.NOTIF_POSTED_GROUPED_ACTOR.format(count);
        }
        if (count > 1) return LinDoNetworkCopy.NOTIF_POSTED_GROUPED.format(count);
        return LinDoNetworkCopy.NOTIF_POSTED.text;
      case MOVED_POST:
        return LinDoNetworkCopy.NOTIF_MOVED_POST.text;
      case LINKED:
        return LinDoNetworkCopy.NOTIF_LINKED.text;
      case GRANTED_BADGE:
        return LinDoNetworkCopy.NOTIF_GRANTED_BADGE.text;
      case INVITED_TO_TOPIC:
        return LinDoNetworkCopy.NOTIF_INVITED_TOPIC.text;
      case CUSTOM:
        return LinDoNetworkCopy.NOTIF_CUSTOM_SYSTEM.text;
      case GROUP_MENTIONED:
        return LinDoNetworkCopy.NOTIF_GROUP_MENTIONED.text;
      case GROUP_MESSAGE_SUMMARY:
        return LinDoNetworkCopy.NOTIF_GROUP_SUMMARY.text;
      case WATCHING_FIRST_POST:
        return LinDoNetworkCopy.NOTIF_WATCHING_FIRST_POST.text;
      case TOPIC_REMINDER:
        return LinDoNetworkCopy.NOTIF_TOPIC_REMINDER.text;
      case LIKED_CONSOLIDATED:
        return count > 1
            ? LinDoNetworkCopy.NOTIF_LIKED_CONSOLIDATED_COUNT.format(count)
            : LinDoNetworkCopy.NOTIF_LIKED_CONSOLIDATED.text;
      case POST_APPROVED:
        return LinDoNetworkCopy.NOTIF_POST_APPROVED.text;
      case CODE_REVIEW_COMMIT_APPROVED:
        return LinDoNetworkCopy.NOTIF_CODE_REVIEW_APPROVED.text;
      case MEMBERSHIP_REQUEST_ACCEPTED:
        return LinDoNetworkCopy.NOTIF_MEMBERSHIP_ACCEPTED.text;
      case MEMBERSHIP_REQUEST_CONSOLIDATED:
        return LinDoNetworkCopy.NOTIF_MEMBERSHIP_CONSOLIDATED.text;
      case BOOKMARK_REMINDER:
        return LinDoNetworkCopy.NOTIF_BOOKMARK_REMINDER.text;
      case REACTION:
        return LinDoNetworkCopy.NOTIF_REACTION.text;
      case VOTES_RELEASED:
        return LinDoNetworkCopy.NOTIF_VOTES_RELEASED.text;
      case EVENT_REMINDER:
        return LinDoNetworkCopy.NOTIF_EVENT_REMINDER.text;
      case EVENT_INVITATION:
        return LinDoNetworkCopy.NOTIF_EVENT_INVITATION.text;
      case CHAT_MENTION:
        return LinDoNetworkCopy.NOTIF_CHAT_MENTION.text;
      case CHAT_MESSAGE:
        return LinDoNetworkCopy.NOTIF_CHAT_MESSAGE.text;
      case CHAT_INVITATION:
        return LinDoNetworkCopy.NOTIF_CHAT_INVITATION.text;
      case CHAT_GROUP_MENTION:
        return LinDoNetworkCopy.NOTIF_CHAT_GROUP_MENTION.text;
      case CHAT_QUOTED:
        return LinDoNetworkCopy.NOTIF_CHAT_QUOTED.text;
      case ASSIGNED:
        return LinDoNetworkCopy.NOTIF_ASSIGNED.text;
      case WATCHING_CATEGORY_OR_TAG:
        return topicId > 0
            ? LinDoNetworkCopy.NOTIF_WATCHING_CATEGORY_OR_TAG.text
            : LinDoNetworkCopy.NOTIF_FOLLOWED_YOU.text;
      case FOLLOWING_CREATED_TOPIC:
      case PLUGIN_FOLLOW_TOPIC:
        return LinDoNetworkCopy.NOTIF_FOLLOW_NEW_TOPIC.text;
      case FOLLOWING_REPLIED:
        return LinDoNetworkCopy.NOTIF_FOLLOW_NEW_REPLY.text;
      case BOOST_LEGACY:
      case BOOST:
      case PLUGIN_BOOST:
        return LinDoNetworkCopy.NOTIF_BOOSTED_POST.text;
      case PLUGIN_FOLLOWED:
        return LinDoNetworkCopy.NOTIF_FOLLOWED_YOU.text;
      case PLUGIN_FOLLOW_POST:
        if (count > 1 && hasActorUsername) {
          return LinDoNetworkCopy.NOTIF_FOLLOW_NEW_REPLY_GROUPED_ACTOR.format(count);
        }
        if (count > 1) return LinDoNetworkCopy.NOTIF_FOLLOW_NEW_REPLY_GROUPED.format(count);
        return LinDoNetworkCopy.NOTIF_FOLLOW_NEW_REPLY.text;
      case CIRCLE_REACTION:
      case PLUGIN_REACTION_EXT:
      case UNKNOWN:
        return topicId > 0
            ? LinDoNetworkCopy.NOTIF_TOPIC_INTERACTION.text
            : LinDoNetworkCopy.ACTION_COMMUNITY_ACTIVITY.text;
    }
  }
}

enum DiscourseUserActionKind {
  LIKE_GIVEN(1, LinDoNetworkCopy.ACTION_LIKE_GIVEN, filterParam: '1'),
  LIKE_RECEIVED(2, LinDoNetworkCopy.ACTION_LIKE_RECEIVED, filterParam: '2'),
  NEW_TOPIC(4, LinDoNetworkCopy.ACTION_NEW_TOPIC, filterParam: '4'),
  REPLY(5, LinDoNetworkCopy.ACTION_REPLY, filterParam: '5'),
  BOOKMARK(88, LinDoNetworkCopy.ACTION_BOOKMARK),
  OTHER(-1, LinDoNetworkCopy.ACTION_COMMUNITY_ACTIVITY);

  final int code;
  final LinDoNetworkCopy copy;
  final String? filterParam;
  const DiscourseUserActionKind(this.code, this.copy, {this.filterParam});

  static DiscourseUserActionKind fromCode(int code) {
    for (final k in values) {
      if (k.code == code) return k;
    }
    return OTHER;
  }
}

enum TopicNotificationLevel {
  MUTED(0, LinDoReaderCopy.SUBSCRIPTION_MUTED),
  REGULAR(1, LinDoReaderCopy.SUBSCRIPTION_DEFAULT),
  TRACKING(2, LinDoReaderCopy.SUBSCRIPTION_DEFAULT),
  WATCHING(3, LinDoReaderCopy.SUBSCRIPTION_WATCHING);

  final int code;
  final LinDoReaderCopy menuLabel;
  const TopicNotificationLevel(this.code, this.menuLabel);

  TopicNotificationLevel nextQuickToggle() {
    switch (this) {
      case REGULAR:
      case TRACKING:
        return WATCHING;
      case WATCHING:
        return MUTED;
      case MUTED:
        return REGULAR;
    }
  }

  static TopicNotificationLevel fromCode(int code) {
    for (final item in values) {
      if (item.code == code) return item;
    }
    return REGULAR;
  }
}

enum CloudDraftKind {
  PRIVATE_MESSAGE(LinDoComposerCopy.DRAFT_KIND_PM, 'privateMessage'),
  EDIT_POST(LinDoComposerCopy.DRAFT_KIND_EDIT, 'edit'),
  NEW_TOPIC(LinDoComposerCopy.DRAFT_KIND_NEW_TOPIC, 'createTopic'),
  TOPIC_REPLY(LinDoComposerCopy.DRAFT_KIND_REPLY, 'reply');

  final LinDoComposerCopy badgeCopy;
  final String wireAction;
  const CloudDraftKind(this.badgeCopy, this.wireAction);

  static CloudDraftKind resolve(String action, String draftKey) {
    if (action == 'privateMessage' ||
        draftKey.startsWith('private_message') ||
        draftKey.startsWith('new_private_message')) {
      return PRIVATE_MESSAGE;
    }
    if (action.startsWith('edit') || draftKey.startsWith('edit_post_')) {
      return EDIT_POST;
    }
    if (draftKey == 'new_topic' || action == 'createTopic') {
      return NEW_TOPIC;
    }
    return TOPIC_REPLY;
  }
}

enum SmallActionKind {
  CLOSED({'closed.enabled'}, LinDoNetworkCopy.SMALL_ACTION_CLOSED),
  OPENED({'closed.disabled'}, LinDoNetworkCopy.SMALL_ACTION_OPENED),
  AUTOCLOSED({'autoclosed.enabled'}, LinDoNetworkCopy.SMALL_ACTION_AUTOCLOSED),
  AUTOOPENED({'autoclosed.disabled'}, LinDoNetworkCopy.SMALL_ACTION_AUTOOPENED),
  ARCHIVED({'archived.enabled'}, LinDoNetworkCopy.SMALL_ACTION_ARCHIVED),
  UNARCHIVED({'archived.disabled'}, LinDoNetworkCopy.SMALL_ACTION_UNARCHIVED),
  PINNED({'pinned.enabled', 'pinned_globally.enabled'}, LinDoNetworkCopy.SMALL_ACTION_PINNED),
  UNPINNED({'pinned.disabled', 'pinned_globally.disabled', 'unpinned'}, LinDoNetworkCopy.SMALL_ACTION_UNPINNED),
  BANNER({'banner.enabled'}, LinDoNetworkCopy.SMALL_ACTION_BANNER),
  UNBANNER({'banner.disabled'}, LinDoNetworkCopy.SMALL_ACTION_UNBANNER),
  SPLIT_TOPIC({'split_topic'}, LinDoNetworkCopy.SMALL_ACTION_SPLIT_TOPIC),
  VISIBLE({'visible.enabled', 'public_topic'}, LinDoNetworkCopy.SMALL_ACTION_VISIBLE),
  INVISIBLE({'visible.disabled', 'private_topic'}, LinDoNetworkCopy.SMALL_ACTION_INVISIBLE),
  INVITED({'invited_user', 'invited_group'}, LinDoNetworkCopy.SMALL_ACTION_INVITED),
  REMOVED({'removed_user', 'removed_group'}, LinDoNetworkCopy.SMALL_ACTION_REMOVED);

  final Set<String> wireCodes;
  final LinDoNetworkCopy copy;
  const SmallActionKind(this.wireCodes, this.copy);

  static String localize(String actionCode) {
    for (final k in values) {
      if (k.wireCodes.contains(actionCode)) return k.copy.text;
    }
    if (actionCode.trim().isEmpty) return LinDoNetworkCopy.SMALL_ACTION_GENERIC.text;
    return LinDoNetworkCopy.SMALL_ACTION_FALLBACK.format(actionCode);
  }
}

enum SpecialUserGroup {
  MERCHANT,
  STANDARD;

  bool get usesMerchantBadgeStyle => this == MERCHANT;

  static SpecialUserGroup fromGroupName(String? primaryGroupName) {
    final clean = (primaryGroupName ?? '').trim().toLowerCase();
    return (clean == 'g-merchant' || clean == 'g-merchant2') ? MERCHANT : STANDARD;
  }
}

enum GuestGateScenario {
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
  LEADERBOARD(LinDoAuthCopy.GUEST_LEADERBOARD_TITLE, LinDoAuthCopy.GUEST_LEADERBOARD_SUBTITLE);

  final LinDoAuthCopy titleCopy;
  final LinDoAuthCopy subtitleCopy;
  const GuestGateScenario(this.titleCopy, this.subtitleCopy);
}

enum CalloutBlockKind {
  LOTTERY('lottery', LinDoReaderCopy.CALLOUT_LOTTERY),
  WARNING('warning', LinDoReaderCopy.CALLOUT_WARNING),
  TIP('tip', LinDoReaderCopy.CALLOUT_TIP),
  NOTE('note', LinDoReaderCopy.CALLOUT_NOTE);

  final String wireKey;
  final LinDoReaderCopy defaultTitle;
  const CalloutBlockKind(this.wireKey, this.defaultTitle);

  static CalloutBlockKind fromKey(String raw) {
    final lower = raw.toLowerCase();
    for (final k in values) {
      if (k.wireKey == lower) return k;
    }
    return NOTE;
  }
}

enum MessagesSubTab {
  NOTIFICATIONS(LinDoMessagesCopy.TAB_NOTIFICATIONS),
  PRIVATE_MESSAGES(LinDoMessagesCopy.TAB_PRIVATE_MESSAGES),
  CHAT(LinDoMessagesCopy.TAB_CHAT);

  final LinDoMessagesCopy labelCopy;
  const MessagesSubTab(this.labelCopy);
}

enum PrivateMessagesViewMode {
  INBOX(LinDoMessagesCopy.PM_TAB_INBOX),
  SENT(LinDoMessagesCopy.PM_TAB_SENT),
  ARCHIVE(LinDoMessagesCopy.PM_TAB_ARCHIVE),
  COMPOSER(null);

  final LinDoMessagesCopy? tabLabel;
  const PrivateMessagesViewMode(this.tabLabel);

  static const List<PrivateMessagesViewMode> mailboxTabs = [INBOX, SENT, ARCHIVE];
}

enum ReaderFontScalePreset {
  SMALL(0.92, LinDoMessagesCopy.FONT_SCALE_SMALL),
  STANDARD(1.0, LinDoMessagesCopy.FONT_SCALE_STANDARD),
  LARGE(1.08, LinDoMessagesCopy.FONT_SCALE_LARGE);

  final double scale;
  final LinDoMessagesCopy labelCopy;
  const ReaderFontScalePreset(this.scale, this.labelCopy);

  ReaderFontScalePreset nextCycle() {
    switch (this) {
      case SMALL:
        return STANDARD;
      case STANDARD:
        return LARGE;
      case LARGE:
        return SMALL;
    }
  }

  static ReaderFontScalePreset fromScale(double fontScale) {
    if (fontScale < 0.96) return SMALL;
    if (fontScale < 1.04) return STANDARD;
    return LARGE;
  }
}

enum FeedDensityMode {
  SPACIOUS(false, LinDoMessagesCopy.FEED_DENSITY_SPACIOUS),
  COMPACT(true, LinDoMessagesCopy.FEED_DENSITY_COMPACT);

  final bool isCompact;
  final LinDoMessagesCopy labelCopy;
  const FeedDensityMode(this.isCompact, this.labelCopy);

  static FeedDensityMode fromCompact(bool isCompact) => isCompact ? COMPACT : SPACIOUS;
}

enum BadgesSheetTab { EARNED, ALL_SITE }

enum BadgeTierFilter {
  ALL(null, LinDoMessagesCopy.FILTER_ALL),
  GOLD(1, LinDoMessagesCopy.BADGE_TIER_GOLD),
  SILVER(2, LinDoMessagesCopy.BADGE_TIER_SILVER),
  BRONZE(3, LinDoMessagesCopy.BADGE_TIER_BRONZE),
  ALLOW_TITLE(null, LinDoMessagesCopy.BADGE_TIER_ALLOW_TITLE);

  final int? badgeTypeId;
  final LinDoMessagesCopy labelCopy;
  const BadgeTierFilter(this.badgeTypeId, this.labelCopy);
}

enum BadgeGroupingKind {
  GETTING_STARTED('Getting Started', LinDoMessagesCopy.BADGE_GROUP_GETTING_STARTED),
  COMMUNITY('Community', LinDoMessagesCopy.BADGE_GROUP_COMMUNITY),
  POSTING('Posting', LinDoMessagesCopy.BADGE_GROUP_POSTING),
  TRUST_LEVEL('Trust Level', LinDoMessagesCopy.BADGE_GROUP_TRUST_LEVEL),
  OTHER('Other', LinDoMessagesCopy.BADGE_GROUP_OTHER);

  final String apiName;
  final LinDoMessagesCopy labelCopy;
  const BadgeGroupingKind(this.apiName, this.labelCopy);

  static String localize(String rawName) {
    if (rawName.trim().isEmpty) return OTHER.labelCopy.text;
    for (final item in values) {
      if (item.apiName == rawName) return item.labelCopy.text;
    }
    return rawName;
  }
}

enum WorkbenchLinkTab {
  QUOTE_POST(LinDoComposerCopy.LINK_TAB_QUOTE_POST),
  EXTERNAL_URL(LinDoComposerCopy.LINK_TAB_EXTERNAL_URL);

  final LinDoComposerCopy labelCopy;
  const WorkbenchLinkTab(this.labelCopy);
}

enum WorkbenchPollType {
  REGULAR('regular', LinDoComposerCopy.POLL_TYPE_SINGLE),
  MULTIPLE('multiple', LinDoComposerCopy.POLL_TYPE_MULTIPLE),
  NUMBER('number', LinDoComposerCopy.POLL_TYPE_NUMBER);

  final String wireType;
  final LinDoComposerCopy labelCopy;
  const WorkbenchPollType(this.wireType, this.labelCopy);

  static WorkbenchPollType fromWire(String raw) {
    for (final k in values) {
      if (k.wireType == raw) return k;
    }
    return REGULAR;
  }
}

enum WorkbenchPollResultsVisibility {
  ALWAYS('always', LinDoComposerCopy.POLL_RESULTS_ALWAYS),
  ON_VOTE('on_vote', LinDoComposerCopy.POLL_RESULTS_ON_VOTE),
  ON_CLOSE('on_close', LinDoComposerCopy.POLL_RESULTS_ON_CLOSE);

  final String wireValue;
  final LinDoComposerCopy labelCopy;
  const WorkbenchPollResultsVisibility(this.wireValue, this.labelCopy);

  static WorkbenchPollResultsVisibility fromWire(String raw) {
    for (final k in values) {
      if (k.wireValue == raw) return k;
    }
    return ALWAYS;
  }
}

enum SearchSortOrder {
  RELEVANCE(null, LinDoFeedCopy.SEARCH_SORT_RELEVANCE),
  LATEST('latest', LinDoFeedCopy.SEARCH_SORT_LATEST),
  LIKES('likes', LinDoFeedCopy.SEARCH_SORT_LIKES);

  final String? wireOrder;
  final LinDoFeedCopy labelCopy;
  const SearchSortOrder(this.wireOrder, this.labelCopy);
}

enum UserActionFilterTab {
  ALL(null, LinDoMessagesCopy.ACTIVITY_TAB_ALL),
  TOPICS('4', LinDoMessagesCopy.ACTIVITY_TAB_TOPICS),
  REPLIES('5', LinDoMessagesCopy.ACTIVITY_TAB_REPLIES),
  LIKES('1', LinDoMessagesCopy.ACTIVITY_TAB_LIKES);

  final String? apiFilter;
  final LinDoMessagesCopy labelCopy;
  const UserActionFilterTab(this.apiFilter, this.labelCopy);
}

enum PublicUserCardTab { ALL_ACTIVITY, TOPICS, REPLIES, BADGES }

enum FollowNetworkTab {
  FOLLOWING(LinDoMessagesCopy.FOLLOW_TAB_FOLLOWING),
  FOLLOWERS(LinDoMessagesCopy.FOLLOW_TAB_FOLLOWERS),
  FEED(LinDoMessagesCopy.FOLLOW_TAB_FEED);

  final LinDoMessagesCopy labelCopy;
  const FollowNetworkTab(this.labelCopy);
}

enum LeaderboardPeriod {
  DAILY('daily', LinDoMessagesCopy.LEADERBOARD_DAILY),
  WEEKLY('weekly', LinDoMessagesCopy.LEADERBOARD_WEEKLY),
  MONTHLY('monthly', LinDoMessagesCopy.LEADERBOARD_MONTHLY),
  ALL('all', LinDoMessagesCopy.LEADERBOARD_ALL);

  final String wirePeriod;
  final LinDoMessagesCopy labelCopy;
  const LeaderboardPeriod(this.wirePeriod, this.labelCopy);
}

enum AboutTeamTab { ADMINS, MODERATORS }

enum DiagnosticFilterTab {
  ALL(null, LinDoMessagesCopy.FILTER_ALL),
  NETWORK(DiagnosticCategory.NETWORK, LinDoMessagesCopy.DIAG_TAB_NETWORK),
  AUTH(DiagnosticCategory.AUTH, LinDoMessagesCopy.DIAG_TAB_AUTH),
  CRASH(DiagnosticCategory.CRASH, LinDoMessagesCopy.DIAG_TAB_CRASH),
  SYSTEM(DiagnosticCategory.SYSTEM, LinDoMessagesCopy.DIAG_TAB_SYSTEM);

  final DiagnosticCategory? category;
  final LinDoMessagesCopy labelCopy;
  const DiagnosticFilterTab(this.category, this.labelCopy);
}

enum FloorNavigatorMode { SLIDER, EXACT_INPUT }

/// 用于静态测试审计全站文案枚举完整性
List<LinDoCopyEntry> allLinDoCopyEntries() => [
      ...LinDoCommonCopy.values,
      ...LinDoAuthCopy.values,
      ...LinDoNetworkCopy.values,
      ...LinDoFeedCopy.values,
      ...LinDoReaderCopy.values,
      ...LinDoComposerCopy.values,
      ...LinDoMessagesCopy.values,
    ];
