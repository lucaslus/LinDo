// ignore_for_file: constant_identifier_names
import 'package:flutter/widgets.dart' show Characters;
import 'package:html/dom.dart' as dom;
import 'package:html/parser.dart' as html_parser;

import '../copy/lindo_copy.dart';

enum MainTab {
  HOME(LinDoFeedCopy.TAB_HOME),
  DISCOVER(LinDoFeedCopy.TAB_DISCOVER),
  MESSAGES(LinDoFeedCopy.TAB_MESSAGES),
  PROFILE(LinDoFeedCopy.TAB_PROFILE);

  final LinDoFeedCopy titleCopy;
  const MainTab(this.titleCopy);

  String get title => titleCopy.text;
}

enum FeedSortFilter {
  LATEST('latest', LinDoFeedCopy.SORT_LATEST),
  TOP('top', LinDoFeedCopy.SORT_TOP),
  NEW('new', LinDoFeedCopy.SORT_NEW),
  UNREAD('unread', LinDoFeedCopy.SORT_UNREAD),
  FOLLOWING('following', LinDoFeedCopy.SORT_FOLLOW);

  final String wireKey;
  final LinDoFeedCopy labelCopy;
  const FeedSortFilter(this.wireKey, this.labelCopy);

  String get label => labelCopy.text;
  bool get requiresAuth => this == NEW || this == UNREAD || this == FOLLOWING;
}

enum TopPeriodFilter {
  DAILY('daily', LinDoFeedCopy.PERIOD_DAILY),
  WEEKLY('weekly', LinDoFeedCopy.PERIOD_WEEKLY),
  MONTHLY('monthly', LinDoFeedCopy.PERIOD_MONTHLY),
  YEARLY('yearly', LinDoFeedCopy.PERIOD_YEARLY),
  ALL('all', LinDoFeedCopy.PERIOD_ALL);

  final String wireKey;
  final LinDoFeedCopy labelCopy;
  const TopPeriodFilter(this.wireKey, this.labelCopy);

  String get label => labelCopy.text;
}

enum BadgeType {
  GOLD(1, LinDoNetworkCopy.BADGE_GOLD),
  SILVER(2, LinDoNetworkCopy.BADGE_SILVER),
  BRONZE(3, LinDoNetworkCopy.BADGE_BRONZE);

  final int id;
  final LinDoNetworkCopy copy;
  const BadgeType(this.id, this.copy);

  String get labelZh => copy.text;

  static BadgeType fromId(int id) {
    for (final b in values) {
      if (b.id == id) return b;
    }
    return BRONZE;
  }
}

enum ComposerAction { NEW_TOPIC, REPLY, EDIT_POST, EDIT_TOPIC, PRIVATE_MESSAGE }

enum DesktopTrayCommand { OPEN_MESSAGES, OPEN_PUBLISH }

class GuestLoginPrompt {
  final String title;
  final String subtitle;
  const GuestLoginPrompt({required this.title, required this.subtitle});
}

class AuthenticatedUserProfile {
  final int userId;
  final String username;
  final String displayName;
  final String avatarTemplate;
  final String? animatedAvatar;
  final String userTitle;
  final int trustLevel;

  const AuthenticatedUserProfile({
    required this.userId,
    required this.username,
    required this.displayName,
    this.avatarTemplate = '',
    this.animatedAvatar,
    this.userTitle = '',
    this.trustLevel = 1,
  });

  Map<String, dynamic> toJson() => {
        'userId': userId,
        'username': username,
        'displayName': displayName,
        'avatarTemplate': avatarTemplate,
        'animatedAvatar': animatedAvatar,
        'userTitle': userTitle,
        'trustLevel': trustLevel,
      };

  factory AuthenticatedUserProfile.fromJson(Map<String, dynamic> json) =>
      AuthenticatedUserProfile(
        userId: (json['userId'] as num?)?.toInt() ?? 0,
        username: (json['username'] as String?) ?? '',
        displayName: (json['displayName'] as String?) ??
            (json['username'] as String?) ??
            '',
        avatarTemplate: (json['avatarTemplate'] as String?) ?? '',
        animatedAvatar: json['animatedAvatar'] as String?,
        userTitle: (json['userTitle'] as String?) ?? '',
        trustLevel: (json['trustLevel'] as num?)?.toInt() ?? 1,
      );
}

class FlairInfo {
  final String? url;
  final String? name;
  final String? bgColorHex;
  final String? fgColorHex;
  final int? groupId;

  const FlairInfo({
    this.url,
    this.name,
    this.bgColorHex,
    this.fgColorHex,
    this.groupId,
  });

  bool get hasFlair => (url ?? '').trim().isNotEmpty;
}

class UserStatus {
  final String? description;
  final String? emoji;
  const UserStatus({this.description, this.emoji});
}

class TopicUser {
  final int id;
  final String username;
  final String? name;
  final String avatarTemplate;
  final String? animatedAvatar;
  final String? primaryGroupName;
  final String? flairName;
  final String? flairUrl;
  final String? flairBgColor;
  final String? flairColor;
  final int? flairGroupId;
  final int trustLevel;
  final bool admin;
  final bool moderator;

  const TopicUser({
    required this.id,
    required this.username,
    this.name,
    this.avatarTemplate = '',
    this.animatedAvatar,
    this.primaryGroupName,
    this.flairName,
    this.flairUrl,
    this.flairBgColor,
    this.flairColor,
    this.flairGroupId,
    this.trustLevel = 1,
    this.admin = false,
    this.moderator = false,
  });

  String get displayName =>
      (name != null && name!.trim().isNotEmpty) ? name!.trim() : username;

  FlairInfo get flair => FlairInfo(
        url: flairUrl,
        name: flairName,
        bgColorHex: flairBgColor,
        fgColorHex: flairColor,
        groupId: flairGroupId,
      );
}

class CategoryInfo {
  final int id;
  final String name;
  final String slug;
  final String colorHex;
  final String textColorHex;
  final String descriptionText;
  final int topicCount;
  final int? parentCategoryId;
  final int minimumTrustLevel;
  final bool readRestricted;
  final String defaultTemplate;
  final List<String> allowedTags;
  final List<String> requiredTagGroupNames;

  const CategoryInfo({
    required this.id,
    required this.name,
    this.slug = '',
    this.colorHex = '0088CC',
    this.textColorHex = 'FFFFFF',
    this.descriptionText = '',
    this.topicCount = 0,
    this.parentCategoryId,
    this.minimumTrustLevel = 0,
    this.readRestricted = false,
    this.defaultTemplate = '',
    this.allowedTags = const [],
    this.requiredTagGroupNames = const [],
  });
}

class TopicSummary {
  final int id;
  final String title;
  final String fancyTitle;
  final String slug;
  final int postsCount;
  final int highestPostNumber;
  final int replyCount;
  final int views;
  final int likeCount;
  final int categoryId;
  final String categoryName;
  final String categoryColorHex;
  final List<String> tags;
  final String? excerpt;
  final bool pinned;
  final bool closed;
  final bool archived;
  final bool unseen;
  final int unreadPosts;
  final bool hasAcceptedAnswer;
  final String lastPostedAt;
  final String createdAtIso;
  final String lastPostedAtIso;
  final TopicUser originalPoster;
  final List<TopicUser> participantAvatars;

  const TopicSummary({
    required this.id,
    required this.title,
    this.fancyTitle = '',
    this.slug = '',
    this.postsCount = 1,
    int? highestPostNumber,
    this.replyCount = 0,
    this.views = 0,
    this.likeCount = 0,
    this.categoryId = 1,
    this.categoryName = '常规话题',
    this.categoryColorHex = '0088CC',
    this.tags = const [],
    this.excerpt,
    this.pinned = false,
    this.closed = false,
    this.archived = false,
    this.unseen = false,
    this.unreadPosts = 0,
    this.hasAcceptedAnswer = false,
    this.lastPostedAt = '1m',
    this.createdAtIso = '',
    this.lastPostedAtIso = '',
    required this.originalPoster,
    this.participantAvatars = const [],
  }) : highestPostNumber = highestPostNumber ?? postsCount;

  TopicSummary copyWith({
    bool? unseen,
    int? unreadPosts,
    bool? pinned,
    int? likeCount,
    int? postsCount,
    int? replyCount,
  }) =>
      TopicSummary(
        id: id,
        title: title,
        fancyTitle: fancyTitle,
        slug: slug,
        postsCount: postsCount ?? this.postsCount,
        highestPostNumber: highestPostNumber,
        replyCount: replyCount ?? this.replyCount,
        views: views,
        likeCount: likeCount ?? this.likeCount,
        categoryId: categoryId,
        categoryName: categoryName,
        categoryColorHex: categoryColorHex,
        tags: tags,
        excerpt: excerpt,
        pinned: pinned ?? this.pinned,
        closed: closed,
        archived: archived,
        unseen: unseen ?? this.unseen,
        unreadPosts: unreadPosts ?? this.unreadPosts,
        hasAcceptedAnswer: hasAcceptedAnswer,
        lastPostedAt: lastPostedAt,
        createdAtIso: createdAtIso,
        lastPostedAtIso: lastPostedAtIso,
        originalPoster: originalPoster,
        participantAvatars: participantAvatars,
      );
}

class GrantedBadge {
  final int id;
  final String name;
  final String description;
  final String longDescription;
  final int badgeTypeId;
  final String? icon;
  final String? imageUrl;
  final String slug;
  final int grantCount;
  final bool allowTitle;
  final int badgeGroupingId;
  final String badgeGroupingName;
  final bool isFirstTimeGrant;
  final int count;
  final String grantedAtText;
  final bool isEarned;
  final int topicId;
  final String topicTitle;
  final int postNumber;

  const GrantedBadge({
    required this.id,
    required this.name,
    this.description = '',
    this.longDescription = '',
    this.badgeTypeId = 3,
    this.icon,
    this.imageUrl,
    this.slug = '',
    this.grantCount = 0,
    this.allowTitle = false,
    this.badgeGroupingId = 0,
    this.badgeGroupingName = '',
    this.isFirstTimeGrant = false,
    this.count = 1,
    this.grantedAtText = '',
    this.isEarned = false,
    this.topicId = 0,
    this.topicTitle = '',
    this.postNumber = 0,
  });

  BadgeType get badgeType => BadgeType.fromId(badgeTypeId);

  String get plainDescription {
    final clean = decodeDiscourseHtmlSnippet(description);
    return clean.isEmpty ? name : clean;
  }

  String get plainLongDescription => decodeDiscourseHtmlSnippet(longDescription);

  GrantedBadge copyWith({
    int? count,
    String? grantedAtText,
    bool? isEarned,
  }) =>
      GrantedBadge(
        id: id,
        name: name,
        description: description,
        longDescription: longDescription,
        badgeTypeId: badgeTypeId,
        icon: icon,
        imageUrl: imageUrl,
        slug: slug,
        grantCount: grantCount,
        allowTitle: allowTitle,
        badgeGroupingId: badgeGroupingId,
        badgeGroupingName: badgeGroupingName,
        isFirstTimeGrant: isFirstTimeGrant,
        count: count ?? this.count,
        grantedAtText: grantedAtText ?? this.grantedAtText,
        isEarned: isEarned ?? this.isEarned,
        topicId: topicId,
        topicTitle: topicTitle,
        postNumber: postNumber,
      );
}

class PostReactionItem {
  final String id;
  final String emoji;
  final int count;
  const PostReactionItem({required this.id, required this.emoji, required this.count});
}

class PostBoostItem {
  final int id;
  final String cookedHtml;
  final String plainText;
  final int userId;
  final String username;
  final String? displayName;
  final String avatarTemplate;
  final bool canDelete;
  final bool canFlag;

  const PostBoostItem({
    required this.id,
    required this.cookedHtml,
    this.plainText = '',
    this.userId = 0,
    this.username = '',
    this.displayName,
    this.avatarTemplate = '',
    this.canDelete = false,
    this.canFlag = false,
  });

  bool isBy(int? accountId, String? accountUsername) {
    if (accountId != null && accountId > 0 && userId > 0) return userId == accountId;
    return accountUsername != null &&
        accountUsername.trim().isNotEmpty &&
        username.toLowerCase() == accountUsername.trim().toLowerCase();
  }
}

class BoostInputStats {
  final int visibleLength;
  final int emojiCount;
  final int maxVisibleLength;
  final int maxEmojiCount;

  static const int maxBoostVisibleLength = 16;
  static const int maxBoostEmojiCount = 5;
  static final RegExp _shortcodeRegex =
      RegExp(r'(?<![a-zA-Z0-9:]):[a-zA-Z0-9_+-]{2,36}(?::t\d)?:(?![a-zA-Z0-9:])');

  const BoostInputStats({
    required this.visibleLength,
    required this.emojiCount,
    this.maxVisibleLength = maxBoostVisibleLength,
    this.maxEmojiCount = maxBoostEmojiCount,
  });

  bool get canAddEmoji =>
      visibleLength + 1 <= maxVisibleLength && emojiCount < maxEmojiCount;
  bool get isWithinLimit =>
      visibleLength <= maxVisibleLength && emojiCount <= maxEmojiCount;
  bool get canSubmit =>
      visibleLength >= 1 &&
      visibleLength <= maxVisibleLength &&
      emojiCount <= maxEmojiCount;
  bool get isEmpty => visibleLength == 0;
  bool get exceedsLength => visibleLength > maxVisibleLength;
  bool get exceedsEmojiLimit => emojiCount > maxEmojiCount;

  static BoostInputStats compute(String raw) {
    final trimmed = raw.trim();
    if (trimmed.isEmpty) return const BoostInputStats(visibleLength: 0, emojiCount: 0);
    var shortcodeCount = 0;
    final replaced = trimmed.replaceAllMapped(_shortcodeRegex, (_) {
      shortcodeCount++;
      return '\uFFFC';
    });
    var clusterCount = 0;
    var unicodeEmojiCount = 0;
    for (final cluster in Characters(replaced)) {
      clusterCount++;
      if (cluster != '\uFFFC' && _isUnicodeEmojiCluster(cluster)) {
        unicodeEmojiCount++;
      }
    }
    return BoostInputStats(
      visibleLength: clusterCount,
      emojiCount: shortcodeCount + unicodeEmojiCount,
    );
  }

  static bool _isUnicodeEmojiCluster(String cluster) {
    for (final cp in cluster.runes) {
      if ((cp >= 0x1F000 && cp <= 0x1FAFF) ||
          (cp >= 0x2600 && cp <= 0x27BF) ||
          (cp >= 0x2300 && cp <= 0x23FF) ||
          cp == 0x2B50 ||
          cp == 0x2764 ||
          cp == 0xFE0F) {
        return true;
      }
    }
    return false;
  }
}

class PollOptionModel {
  final String id;
  final String html;
  final String plainText;
  final int votes;

  const PollOptionModel({
    required this.id,
    required this.html,
    required this.plainText,
    this.votes = 0,
  });
}

class PollModel {
  final String name;
  final String type;
  final String status;
  final String results;
  final int voters;
  final int min;
  final int max;
  final int step;
  final bool publicPoll;
  final String title;
  final List<PollOptionModel> options;
  final List<String> preloadedSelection;

  const PollModel({
    required this.name,
    this.type = 'regular',
    this.status = 'open',
    this.results = 'always',
    this.voters = 0,
    this.min = 1,
    this.max = 1,
    this.step = 1,
    this.publicPoll = false,
    this.title = '',
    this.options = const [],
    this.preloadedSelection = const [],
  });

  bool get isClosed => status.toLowerCase() == 'closed';
}

class PostItemModel {
  final int id;
  final int postNumber;
  final int userId;
  final String username;
  final String? displayName;
  final String avatarTemplate;
  final String? animatedAvatar;
  final String? primaryGroupName;
  final String? userTitle;
  final bool titleIsGroup;
  final int trustLevel;
  final bool admin;
  final bool moderator;
  final bool groupModerator;
  final bool isTopicOwner;
  final FlairInfo flair;
  final UserStatus? userStatus;
  final String? iosDeviceName;
  final bool isTodayBirthday;
  final bool isTodayCakeday;
  final List<GrantedBadge> badgesGranted;
  final List<GrantedBadge> authorBadges;
  final String cookedHtml;
  final String createdAtText;
  final int? replyToPostNumber;
  final String? replyToUsername;
  final String? replyToDisplayName;
  final String? replyToAvatarTemplate;
  final int likeCount;
  final int replyCount;
  final int readsCount;
  final bool likedByMe;
  final bool isRead;
  final String? myReactionId;
  final List<PostReactionItem> reactions;
  final List<PostBoostItem> boosts;
  final bool canBoost;
  final bool bookmarkedByMe;
  final bool isSmallAction;
  final String? actionCode;
  final bool canEdit;
  final bool canDelete;
  final String? raw;
  final int topicId;
  final List<PollModel> polls;

  const PostItemModel({
    required this.id,
    required this.postNumber,
    required this.userId,
    required this.username,
    this.displayName,
    this.avatarTemplate = '',
    this.animatedAvatar,
    this.primaryGroupName,
    this.userTitle,
    this.titleIsGroup = false,
    this.trustLevel = 1,
    this.admin = false,
    this.moderator = false,
    this.groupModerator = false,
    this.isTopicOwner = false,
    this.flair = const FlairInfo(),
    this.userStatus,
    this.iosDeviceName,
    this.isTodayBirthday = false,
    this.isTodayCakeday = false,
    this.badgesGranted = const [],
    this.authorBadges = const [],
    this.cookedHtml = '',
    this.createdAtText = '',
    this.replyToPostNumber,
    this.replyToUsername,
    this.replyToDisplayName,
    this.replyToAvatarTemplate,
    this.likeCount = 0,
    this.replyCount = 0,
    this.readsCount = 0,
    this.likedByMe = false,
    this.isRead = false,
    this.myReactionId,
    this.reactions = const [],
    this.boosts = const [],
    this.canBoost = true,
    this.bookmarkedByMe = false,
    this.isSmallAction = false,
    this.actionCode,
    this.canEdit = false,
    this.canDelete = false,
    this.raw,
    this.topicId = 0,
    this.polls = const [],
  });

  String get effectiveName =>
      (displayName != null && displayName!.trim().isNotEmpty)
          ? displayName!.trim()
          : username;

  PostItemModel copyWith({
    String? cookedHtml,
    int? likeCount,
    bool? likedByMe,
    String? myReactionId,
    bool clearMyReaction = false,
    List<PostReactionItem>? reactions,
    List<PostBoostItem>? boosts,
    bool? bookmarkedByMe,
    List<PollModel>? polls,
  }) =>
      PostItemModel(
        id: id,
        postNumber: postNumber,
        userId: userId,
        username: username,
        displayName: displayName,
        avatarTemplate: avatarTemplate,
        animatedAvatar: animatedAvatar,
        primaryGroupName: primaryGroupName,
        userTitle: userTitle,
        titleIsGroup: titleIsGroup,
        trustLevel: trustLevel,
        admin: admin,
        moderator: moderator,
        groupModerator: groupModerator,
        isTopicOwner: isTopicOwner,
        flair: flair,
        userStatus: userStatus,
        iosDeviceName: iosDeviceName,
        isTodayBirthday: isTodayBirthday,
        isTodayCakeday: isTodayCakeday,
        badgesGranted: badgesGranted,
        authorBadges: authorBadges,
        cookedHtml: cookedHtml ?? this.cookedHtml,
        createdAtText: createdAtText,
        replyToPostNumber: replyToPostNumber,
        replyToUsername: replyToUsername,
        replyToDisplayName: replyToDisplayName,
        replyToAvatarTemplate: replyToAvatarTemplate,
        likeCount: likeCount ?? this.likeCount,
        replyCount: replyCount,
        readsCount: readsCount,
        likedByMe: likedByMe ?? this.likedByMe,
        isRead: isRead,
        myReactionId: clearMyReaction ? null : (myReactionId ?? this.myReactionId),
        reactions: reactions ?? this.reactions,
        boosts: boosts ?? this.boosts,
        canBoost: canBoost,
        bookmarkedByMe: bookmarkedByMe ?? this.bookmarkedByMe,
        isSmallAction: isSmallAction,
        actionCode: actionCode,
        canEdit: canEdit,
        canDelete: canDelete,
        raw: raw,
        topicId: topicId,
        polls: polls ?? this.polls,
      );
}

class TopicDetailModel {
  final int id;
  final String title;
  final String categoryName;
  final String categoryColorHex;
  final List<String> tags;
  final int views;
  final int likeCount;
  final int postsCount;
  final bool bookmarked;
  final String? replyCostHint;
  final int? minWordCountRule;
  final List<PostItemModel> posts;
  final List<int> streamIds;
  final String? loadError;
  final String? originalPosterUsername;
  final int? highestPostNumber;
  final int? lastReadPostNumber;
  final int notificationLevel;
  final bool unavailable;
  final bool closed;
  final bool archived;
  final bool canEditTopic;
  final int categoryId;
  final String? aiSummary;

  const TopicDetailModel({
    required this.id,
    required this.title,
    required this.categoryName,
    this.categoryColorHex = '0088CC',
    this.tags = const [],
    this.views = 0,
    this.likeCount = 0,
    this.postsCount = 1,
    this.bookmarked = false,
    this.replyCostHint,
    this.minWordCountRule,
    this.posts = const [],
    this.streamIds = const [],
    this.loadError,
    this.originalPosterUsername,
    this.highestPostNumber,
    this.lastReadPostNumber,
    this.notificationLevel = 1,
    this.unavailable = false,
    this.closed = false,
    this.archived = false,
    this.canEditTopic = false,
    this.categoryId = 4,
    this.aiSummary,
  });

  TopicDetailModel copyWith({
    String? title,
    String? categoryName,
    int? categoryId,
    List<String>? tags,
    bool? bookmarked,
    List<PostItemModel>? posts,
    List<int>? streamIds,
    String? loadError,
    bool clearLoadError = false,
    int? notificationLevel,
    int? postsCount,
    String? aiSummary,
  }) =>
      TopicDetailModel(
        id: id,
        title: title ?? this.title,
        categoryName: categoryName ?? this.categoryName,
        categoryColorHex: categoryColorHex,
        tags: tags ?? this.tags,
        views: views,
        likeCount: likeCount,
        postsCount: postsCount ?? this.postsCount,
        bookmarked: bookmarked ?? this.bookmarked,
        replyCostHint: replyCostHint,
        minWordCountRule: minWordCountRule,
        posts: posts ?? this.posts,
        streamIds: streamIds ?? this.streamIds,
        loadError: clearLoadError ? null : (loadError ?? this.loadError),
        originalPosterUsername: originalPosterUsername,
        highestPostNumber: highestPostNumber,
        lastReadPostNumber: lastReadPostNumber,
        notificationLevel: notificationLevel ?? this.notificationLevel,
        unavailable: unavailable,
        closed: closed,
        archived: archived,
        canEditTopic: canEditTopic,
        categoryId: categoryId ?? this.categoryId,
        aiSummary: aiSummary ?? this.aiSummary,
      );
}

class UserFullProfileSummary {
  final int id;
  final String username;
  final String displayName;
  final String avatarTemplate;
  final String? animatedAvatar;
  final int trustLevel;
  final String title;
  final String bioExcerpt;
  final String bioRaw;
  final String? primaryGroupName;
  final bool admin;
  final bool moderator;
  final String createdAtText;
  final int daysVisited;
  final int timeReadSeconds;
  final int recentTimeReadSeconds;
  final int topicsEntered;
  final int postsReadCount;
  final int likesGiven;
  final int likesReceived;
  final int topicCount;
  final int postCount;
  final int badgeCount;
  final int unreadNotifications;
  final String lastSeenText;
  final String location;
  final String websiteName;
  final String websiteUrl;
  final String statusText;
  final int solvedCount;
  final List<GrantedBadge> badges;
  final FlairInfo flair;
  final bool canFollow;
  final bool isFollowing;
  final int followingCount;
  final int followersCount;

  const UserFullProfileSummary({
    this.id = 0,
    required this.username,
    required this.displayName,
    this.avatarTemplate = '',
    this.animatedAvatar,
    this.trustLevel = 1,
    this.title = '',
    this.bioExcerpt = '',
    this.bioRaw = '',
    this.primaryGroupName,
    this.admin = false,
    this.moderator = false,
    this.createdAtText = '',
    this.daysVisited = 0,
    this.timeReadSeconds = 0,
    this.recentTimeReadSeconds = 0,
    this.topicsEntered = 0,
    this.postsReadCount = 0,
    this.likesGiven = 0,
    this.likesReceived = 0,
    this.topicCount = 0,
    this.postCount = 0,
    this.badgeCount = 0,
    this.unreadNotifications = 0,
    this.lastSeenText = '',
    this.location = '',
    this.websiteName = '',
    this.websiteUrl = '',
    this.statusText = '',
    this.solvedCount = 0,
    this.badges = const [],
    this.flair = const FlairInfo(),
    this.canFollow = false,
    this.isFollowing = false,
    this.followingCount = 0,
    this.followersCount = 0,
  });

  String get trustLevelName => DiscourseTrustLevel.displayLabel(trustLevel);
}

class UserNotificationItem {
  final int id;
  final int notificationType;
  final bool read;
  final String createdAtText;
  final int topicId;
  final int postNumber;
  final int originalPostId;
  final String slug;
  final String title;
  final String actingUsername;
  final String? actingAvatarTemplate;
  final String? actingPrimaryGroupName;
  final String? actingAnimatedAvatar;
  final FlairInfo actingFlair;
  final String excerpt;
  final int count;
  final int badgeId;
  final String badgeName;
  final int chatChannelId;

  static final RegExp _consolidatedActorRegex = RegExp(
    r'^\d+\s*(?:个|条|位|次)?\s*(?:新)?(?:回复|消息|用户|人|发言|赞|replies|users|messages|notifications).*$',
    caseSensitive: false,
  );

  const UserNotificationItem({
    required this.id,
    required this.notificationType,
    required this.read,
    required this.createdAtText,
    this.topicId = 0,
    this.postNumber = 1,
    this.originalPostId = 0,
    this.slug = '',
    required this.title,
    this.actingUsername = '',
    this.actingAvatarTemplate,
    this.actingPrimaryGroupName,
    this.actingAnimatedAvatar,
    this.actingFlair = const FlairInfo(),
    this.excerpt = '',
    this.count = 0,
    this.badgeId = 0,
    this.badgeName = '',
    this.chatChannelId = 0,
  });

  DiscourseNotificationKind get kind =>
      DiscourseNotificationKind.fromCode(notificationType);

  String? get actorUsername {
    final first = actingUsername.split('、').first.trim();
    if (first.isEmpty ||
        first == LinDoCommonCopy.SYSTEM_ACTOR.text ||
        _consolidatedActorRegex.hasMatch(first)) {
      return null;
    }
    return first;
  }

  String get typeLabel => kind.formatTypeLabel(
        count: count,
        hasActorUsername: actorUsername != null,
        postNumber: postNumber,
        topicId: topicId,
      );

  UserNotificationItem copyWith({bool? read}) => UserNotificationItem(
        id: id,
        notificationType: notificationType,
        read: read ?? this.read,
        createdAtText: createdAtText,
        topicId: topicId,
        postNumber: postNumber,
        originalPostId: originalPostId,
        slug: slug,
        title: title,
        actingUsername: actingUsername,
        actingAvatarTemplate: actingAvatarTemplate,
        actingPrimaryGroupName: actingPrimaryGroupName,
        actingAnimatedAvatar: actingAnimatedAvatar,
        actingFlair: actingFlair,
        excerpt: excerpt,
        count: count,
        badgeId: badgeId,
        badgeName: badgeName,
        chatChannelId: chatChannelId,
      );
}

class UserActivityItem {
  final int id;
  final int topicId;
  final int postNumber;
  final String title;
  final String excerpt;
  final String createdAtText;
  final String categoryName;
  final int actionType;
  final int bookmarkId;

  const UserActivityItem({
    required this.id,
    required this.topicId,
    this.postNumber = 1,
    required this.title,
    this.excerpt = '',
    this.createdAtText = '',
    this.categoryName = '技术讨论',
    this.actionType = 5,
    this.bookmarkId = 0,
  });

  String get actionLabel => DiscourseUserActionKind.fromCode(actionType).copy.text;
}

class CommunitySearchResult {
  final int topicId;
  final int postNumber;
  final String title;
  final String blurb;
  final String username;
  final String avatarTemplate;
  final String createdAtText;
  final String categoryName;
  final String categoryColorHex;
  final int likeCount;
  final int postsCount;

  const CommunitySearchResult({
    required this.topicId,
    this.postNumber = 1,
    required this.title,
    this.blurb = '',
    this.username = '',
    this.avatarTemplate = '',
    this.createdAtText = '',
    this.categoryName = '技术讨论',
    this.categoryColorHex = '0E76BD',
    this.likeCount = 0,
    this.postsCount = 1,
  });
}

class SearchPageResult {
  final List<CommunitySearchResult> items;
  final bool hasMore;
  final int totalEstimate;

  const SearchPageResult({
    required this.items,
    this.hasMore = false,
    this.totalEstimate = 0,
  });
}

class CloudDraftItem {
  final String draftKey;
  final int sequence;
  final String action;
  final int? topicId;
  final int? categoryId;
  final int? replyToPostNumber;
  final int? editPostId;
  final String title;
  final String raw;
  final List<String> tags;
  final List<String> recipients;
  final String updatedAtText;
  final bool isLocalOnly;

  const CloudDraftItem({
    required this.draftKey,
    this.sequence = 0,
    this.action = 'reply',
    this.topicId,
    this.categoryId,
    this.replyToPostNumber,
    this.editPostId,
    this.title = '',
    this.raw = '',
    this.tags = const [],
    this.recipients = const [],
    this.updatedAtText = '',
    this.isLocalOnly = false,
  });

  CloudDraftKind get kind => CloudDraftKind.resolve(action, draftKey);
}

class ChatChannelItem {
  final int id;
  final String title;
  final String description;
  final String slug;
  final int unreadCount;
  final bool isDirectMessage;
  final String? lastMessageExcerpt;
  final String lastMessageTimeText;
  final String? avatarTemplate;

  const ChatChannelItem({
    required this.id,
    required this.title,
    this.description = '',
    this.slug = '',
    this.unreadCount = 0,
    this.isDirectMessage = false,
    this.lastMessageExcerpt,
    this.lastMessageTimeText = '',
    this.avatarTemplate,
  });
}

class ChatMessageItem {
  final int id;
  final int channelId;
  final int userId;
  final String username;
  final String displayName;
  final String avatarTemplate;
  final String message;
  final String cookedHtml;
  final String createdAtText;

  const ChatMessageItem({
    required this.id,
    required this.channelId,
    required this.userId,
    required this.username,
    required this.displayName,
    this.avatarTemplate = '',
    required this.message,
    this.cookedHtml = '',
    this.createdAtText = '',
  });
}

class ConnectRequirementRow {
  final String label;
  final String currentValue;
  final String requiredValue;
  final bool qualified;

  const ConnectRequirementRow({
    required this.label,
    required this.currentValue,
    required this.requiredValue,
    required this.qualified,
  });
}

class ConnectOverviewModel {
  final String headerTitle;
  final String subtitle;
  final bool isLiveConnect;
  final bool allQualified;
  final int periodDays;
  final List<ConnectRequirementRow> rows;

  const ConnectOverviewModel({
    required this.headerTitle,
    required this.subtitle,
    required this.isLiveConnect,
    required this.allQualified,
    this.periodDays = 100,
    required this.rows,
  });
}

class LeaderboardEntry {
  final int rank;
  final int userId;
  final String username;
  final String displayName;
  final String avatarTemplate;
  final int cheersScore;
  final int likesReceived;
  final int postCount;
  final int topicCount;

  const LeaderboardEntry({
    required this.rank,
    required this.userId,
    required this.username,
    required this.displayName,
    this.avatarTemplate = '',
    this.cheersScore = 0,
    this.likesReceived = 0,
    this.postCount = 0,
    this.topicCount = 0,
  });
}

class SiteAboutStats {
  final String title;
  final String description;
  final int totalTopics;
  final int totalPosts;
  final int totalUsers;
  final int active7d;
  final int active30d;
  final int totalLikes;
  final List<TopicUser> admins;
  final List<TopicUser> moderators;

  const SiteAboutStats({
    this.title = 'LINUX DO',
    this.description = '',
    this.totalTopics = 0,
    this.totalPosts = 0,
    this.totalUsers = 0,
    this.active7d = 0,
    this.active30d = 0,
    this.totalLikes = 0,
    this.admins = const [],
    this.moderators = const [],
  });
}

class PublishOutcome {
  final bool success;
  final bool enqueuedForReview;
  final int? topicId;
  final String? title;
  final PostItemModel? post;
  final String? errorMessage;

  const PublishOutcome({
    required this.success,
    this.enqueuedForReview = false,
    this.topicId,
    this.title,
    this.post,
    this.errorMessage,
  });
}

String decodeDiscourseHtmlSnippet(String raw, {bool preserveLineBreaks = false}) {
  if (raw.trim().isEmpty) return '';
  if (!raw.contains('<') && !raw.contains('&')) {
    return preserveLineBreaks
        ? raw.trim()
        : raw.replaceAll(RegExp(r'\s+'), ' ').trim();
  }
  try {
    final fragment = html_parser.parseFragment(raw);
    for (final img in fragment.querySelectorAll('img')) {
      final alt = (img.attributes['alt'] ?? img.attributes['title'] ?? '').trim();
      final classes = img.classes;
      final isEmoji = classes.contains('emoji') ||
          (alt.startsWith(':') && alt.endsWith(':'));
      if (isEmoji && alt.isNotEmpty) {
        img.replaceWith(dom.Text(alt));
      } else {
        img.remove();
      }
    }
    final sep = preserveLineBreaks ? '\n' : ' ';
    for (final br in fragment.querySelectorAll('br')) {
      br.replaceWith(dom.Text(sep));
    }
    for (final el in fragment
        .querySelectorAll('p, div, li, blockquote, h1, h2, h3, h4, h5, h6, tr')) {
      el.append(dom.Text(sep));
    }
    final text = fragment.text ?? '';
    if (preserveLineBreaks) {
      return text
          .replaceAll('\r\n', '\n')
          .split('\n')
          .map((line) => line.replaceAll(RegExp(r'[ \t\x0B\f]+'), ' ').trim())
          .join('\n')
          .replaceAll(RegExp(r'\n{3,}'), '\n\n')
          .trim();
    }
    return text.replaceAll(RegExp(r'\s+'), ' ').trim();
  } catch (_) {
    return raw
        .replaceAll(RegExp(r'<[^>]+>'), ' ')
        .replaceAll(RegExp(r'\s+'), ' ')
        .trim();
  }
}
