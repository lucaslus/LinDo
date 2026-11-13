import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:html/parser.dart' as html_parser;
import 'package:http/http.dart' as http;

import '../copy/lindo_copy.dart';
import '../diagnostics/lindo_diagnostic_logger.dart';
import '../models/lindo_models.dart';
import '../platform/lindo_platform_channels.dart';
import '../site/linux_do_emoji_catalog.dart';
import '../site/linux_do_site_rules.dart';
import '../storage/lindo_local_store.dart';

class TopicFeedPage {
  final List<TopicSummary> topics;
  final List<TopicSummary> pinnedTopics;
  final String? moreTopicsUrl;
  final int nextPage;

  const TopicFeedPage({
    required this.topics,
    this.pinnedTopics = const [],
    this.moreTopicsUrl,
    this.nextPage = 1,
  });
}

class LinuxDoException implements Exception {
  final String message;
  final int statusCode;
  final bool isSiteVerification;

  const LinuxDoException(
    this.message, {
    this.statusCode = 0,
    this.isSiteVerification = false,
  });

  @override
  String toString() => message;
}

const List<CategoryInfo> _kFallbackCategories = <CategoryInfo>[
  CategoryInfo(
    id: 4,
    name: '开发调优',
    slug: 'develop',
    colorHex: '0E76BD',
    descriptionText: '编程开发、架构设计与技术调优',
  ),
  CategoryInfo(
    id: 98,
    name: '国产替代',
    slug: 'domestic',
    colorHex: 'ED207B',
    descriptionText: '国产软硬件生态与实践经验',
  ),
  CategoryInfo(
    id: 14,
    name: '资源荟萃',
    slug: 'resource',
    colorHex: '12A89D',
    descriptionText: '精选工具、开源项目与学习资料',
  ),
  CategoryInfo(
    id: 42,
    name: '文档共建',
    slug: 'wiki',
    colorHex: '0088CC',
    descriptionText: '社区知识库与协作文档',
  ),
  CategoryInfo(
    id: 27,
    name: '非我莫属',
    slug: 'jobs',
    colorHex: 'F7941D',
    descriptionText: '求职招聘与远程协作机会',
  ),
  CategoryInfo(
    id: 32,
    name: '读书成诗',
    slug: 'reading',
    colorHex: '92278F',
    descriptionText: '书籍推荐与阅读感悟',
  ),
  CategoryInfo(
    id: 34,
    name: '前沿快讯',
    slug: 'news',
    colorHex: 'BF1E2E',
    descriptionText: 'AI 与科技行业最新动态',
  ),
  CategoryInfo(
    id: 36,
    name: '福利羊毛',
    slug: 'welfare',
    colorHex: 'F1592A',
    descriptionText: '社区福利与优惠活动',
  ),
  CategoryInfo(
    id: 11,
    name: '搞七捻三',
    slug: 'gossip',
    colorHex: '3AB54A',
    descriptionText: '日常交流与生活闲聊',
  ),
  CategoryInfo(
    id: 2,
    name: '运营反馈',
    slug: 'feedback',
    colorHex: '808281',
    descriptionText: '站点公告与社区建议反馈',
  ),
];

class _RawHttpResponse {
  final int status;
  final Map<String, String> headers;
  final String body;
  final String transport;

  const _RawHttpResponse({
    required this.status,
    required this.headers,
    required this.body,
    required this.transport,
  });
}

class LinuxDoRepository extends ChangeNotifier {
  static final RegExp _consolidatedCountRegex = RegExp(
    r'^(\d+)\s*(?:个|条|位|次)?\s*(?:新)?(?:回复|消息|用户|人|发言|赞|replies|users|messages|notifications).*$',
    caseSensitive: false,
  );
  static final RegExp _connectCssVarValRegex = RegExp(r'--val\s*:\s*(-?\d+(?:\.\d+)?)');
  static final RegExp _connectCssVarMaxRegex = RegExp(r'--max\s*:\s*(-?\d+(?:\.\d+)?)');
  static final RegExp _connectPeriodDaysRegex = RegExp(r'过去\s*(\d+)\s*天');
  static final RegExp _numericExtractRegex = RegExp(r'-?\d+(?:\.\d+)?');

  final LinDoLocalStore store;
  final LinDoBrowserBridge browserBridge;
  http.Client? _injectedOrLazyClient;
  final bool _ownsFallbackClient;

  http.Client get _fallbackHttpClient =>
      _injectedOrLazyClient ??= http.Client();

  AuthenticatedUserProfile? _currentUser;
  String _csrfToken = '';
  String _rawCookies = '';
  final Map<String, String> _cookieJar = <String, String>{};
  List<CategoryInfo> _categories = List<CategoryInfo>.unmodifiable(_kFallbackCategories);
  final Map<String, String> _avatarTemplateByUsername = <String, String>{};
  final Map<String, String> _followCursors = <String, String>{};

  bool _isBootstrapped = false;
  bool _siteVerificationRequired = false;
  int _unreadNotificationCount = 0;
  int _unreadPmCount = 0;

  Timer? _liveSyncTimer;
  bool _pollInFlight = false;
  final String _messageBusClientId = _generateClientId();
  int _notifChannelSeq = -1;
  int _unreadChannelSeq = -1;

  LinuxDoRepository({
    required this.store,
    LinDoBrowserBridge? browserBridge,
    http.Client? fallbackHttpClient,
  })  : browserBridge = browserBridge ?? LinDoBrowserBridge.instance,
        _injectedOrLazyClient = fallbackHttpClient,
        _ownsFallbackClient = fallbackHttpClient == null {
    _hydrateInMemoryFromStore();
  }

  AuthenticatedUserProfile? get currentUser => _currentUser;

  bool get isAuthorized =>
      _currentUser != null && _currentUser!.username.trim().isNotEmpty;

  bool get siteVerificationRequired => _siteVerificationRequired;

  String get csrfToken => _csrfToken;

  List<CategoryInfo> get categories => _categories;

  int get unreadNotificationCount => _unreadNotificationCount;

  int get unreadPmCount => _unreadPmCount;

  int get totalUnreadBadgeCount => _unreadNotificationCount + _unreadPmCount;

  bool get isBootstrapped => _isBootstrapped;

  bool get _useBrowserTransport =>
      // ignore: invalid_use_of_visible_for_testing_member
      browserBridge.isAvailable || browserBridge.debugFetchOverride != null;

  @override
  void dispose() {
    stopLiveNotificationSync();
    if (_ownsFallbackClient) {
      _injectedOrLazyClient?.close();
    }
    super.dispose();
  }

  Future<void> bootstrap() async {
    _hydrateInMemoryFromStore();

    if (_useBrowserTransport && _rawCookies.isNotEmpty) {
      try {
        await browserBridge.setCookies(_rawCookies, url: kLinuxDoBaseUrl);
      } catch (err) {
        LinDoDiagnosticLogger.instance.warn(
          DiagnosticCategory.AUTH,
          'Bootstrap',
          'Failed to restore cookies into browser bridge: $err',
        );
      }
    }

    if (isAuthorized) {
      await refreshCurrentSessionAndUnread(silent: true);
    } else if (_useBrowserTransport) {
      final existingCookies = await browserBridge.getCookies(kLinuxDoBaseUrl);
      if (existingCookies.contains('_t=')) {
        _ingestCookieHeaderString(existingCookies);
        await refreshCurrentSessionAndUnread(silent: true);
      }
    }

    _isBootstrapped = true;
    notifyListeners();
  }

  Future<AuthenticatedUserProfile?> completeWebModalAuthentication({
    String? csrfToken,
    String? rawCookies,
    AuthenticatedUserProfile? user,
  }) async {
    _siteVerificationRequired = false;

    if (csrfToken != null && csrfToken.trim().isNotEmpty) {
      _csrfToken = csrfToken.trim();
    }

    if (rawCookies != null && rawCookies.trim().isNotEmpty) {
      _ingestCookieHeaderString(rawCookies);
      if (_useBrowserTransport) {
        await browserBridge.setCookies(_rawCookies, url: kLinuxDoBaseUrl);
      }
    } else if (_useBrowserTransport) {
      final fetchedCookies = await browserBridge.getCookies(kLinuxDoBaseUrl);
      if (fetchedCookies.trim().isNotEmpty) {
        _ingestCookieHeaderString(fetchedCookies);
      }
    }

    if (user != null) {
      _currentUser = user;
    }

    await refreshCurrentSessionAndUnread(silent: user != null);

    if (_csrfToken.isEmpty) {
      await _ensureCsrfToken(silent: true);
    }

    await store.saveSession(
      user: _currentUser,
      csrfToken: _csrfToken,
      rawCookies: _rawCookies,
    );

    LinDoDiagnosticLogger.instance.info(
      DiagnosticCategory.AUTH,
      'WebModalAuth',
      'Authentication completed (authorized=$isAuthorized, user=${_currentUser?.username ?? "none"})',
    );

    await LinDoPlatformBridge.instance.updateTrayState(
      unreadCount: totalUnreadBadgeCount,
      username: _currentUser?.username,
    );
    notifyListeners();
    return _currentUser;
  }

  Future<void> onSiteVerificationPassed({
    String? csrfToken,
    String? rawCookies,
  }) async {
    _siteVerificationRequired = false;
    if (csrfToken != null && csrfToken.trim().isNotEmpty) {
      _csrfToken = csrfToken.trim();
    }
    if (rawCookies != null && rawCookies.trim().isNotEmpty) {
      _ingestCookieHeaderString(rawCookies);
    } else if (_useBrowserTransport) {
      final cookies = await browserBridge.getCookies(kLinuxDoBaseUrl);
      if (cookies.trim().isNotEmpty) {
        _ingestCookieHeaderString(cookies);
      }
    }
    if (_currentUser != null) {
      await store.saveSession(
        user: _currentUser,
        csrfToken: _csrfToken,
        rawCookies: _rawCookies,
      );
    }
    LinDoDiagnosticLogger.instance.info(
      DiagnosticCategory.NETWORK,
      'Cloudflare',
      'Site verification cleared',
    );
    notifyListeners();
  }

  Future<void> logout() async {
    stopLiveNotificationSync();
    final prevUsername = _currentUser?.username;
    if (prevUsername != null && prevUsername.trim().isNotEmpty) {
      try {
        await _sendRequest(
          method: 'DELETE',
          pathOrUrl: '/session/${Uri.encodeComponent(prevUsername.trim())}.json',
          allowCsrfRetry: false,
        );
      } catch (_) {}
    }

    if (_useBrowserTransport) {
      try {
        await browserBridge.clearSession();
      } catch (_) {}
    }

    _currentUser = null;
    _csrfToken = '';
    _rawCookies = '';
    _cookieJar.clear();
    _followCursors.clear();
    _unreadNotificationCount = 0;
    _unreadPmCount = 0;
    _siteVerificationRequired = false;
    _notifChannelSeq = -1;
    _unreadChannelSeq = -1;

    await store.clearSession();
    await LinDoPlatformBridge.instance.updateTrayState(
      unreadCount: 0,
      username: null,
    );

    LinDoDiagnosticLogger.instance.info(
      DiagnosticCategory.AUTH,
      'Logout',
      'User session cleared',
    );
    notifyListeners();
  }

  // ---------------------------------------------------------------------------
  // Real-Time Unread Notification System
  // ---------------------------------------------------------------------------

  Future<int> refreshCurrentSessionAndUnread({bool silent = true}) async {
    try {
      final sessionRoot = await _requestJsonMap(
        method: 'GET',
        pathOrUrl: '/session/current.json',
      );
      final userMap = _asMap(sessionRoot['current_user']);
      if (userMap == null) {
        if (!silent) {
          throw LinuxDoException(
            LinDoNetworkCopy.AUTH_EXPIRED_RELOGIN.text,
            statusCode: 401,
          );
        }
        return totalUnreadBadgeCount;
      }

      final profile = AuthenticatedUserProfile(
        userId: _asInt(userMap['id']),
        username: _asString(userMap['username']),
        displayName: _nonBlankOr(
          _asString(userMap['name']),
          _asString(userMap['username']),
        ),
        avatarTemplate: _asString(userMap['avatar_template']),
        animatedAvatar: _nullIfBlank(_asString(userMap['animated_avatar'])),
        userTitle: _asString(userMap['title']),
        trustLevel: _asInt(userMap['trust_level'], fallback: 1),
      );

      if (profile.username.isNotEmpty) {
        _currentUser = profile;
        if (profile.avatarTemplate.isNotEmpty) {
          _avatarTemplateByUsername[profile.username.toLowerCase()] =
              profile.avatarTemplate;
        }
      }

      final unreadRegular = _asInt(userMap['unread_notifications']);
      final unreadHighPriority = _asInt(userMap['unread_high_priority_notifications']);
      final allUnreadCount = _asInt(userMap['all_unread_notifications_count']);
      var computedUnreadNotifs = math.max(
        allUnreadCount,
        unreadRegular + unreadHighPriority,
      );
      var computedUnreadPms = _asInt(userMap['unread_private_messages']);

      // Secondary check against `/notifications.json?recent=true&limit=30` so lagging
      // Discourse session counters never hide newly arrived replies/mentions/reactions.
      try {
        final recentRoot = await _requestJsonMap(
          method: 'GET',
          pathOrUrl: '/notifications.json?recent=true&limit=30',
          extraHeaders: const <String, String>{'Discourse-Background': 'true'},
        );
        final notifList = recentRoot['notifications'];
        if (notifList is List) {
          var unreadNonPmInRecent = 0;
          var unreadPmInRecent = 0;
          for (final item in notifList) {
            final map = _asMap(item);
            if (map == null) continue;
            final isRead = map['read'] == true;
            if (!isRead) {
              final nType = _asInt(map['notification_type']);
              if (nType == DiscourseNotificationKind.PRIVATE_MESSAGE.code ||
                  nType == DiscourseNotificationKind.INVITED_TO_PRIVATE_MESSAGE.code) {
                unreadPmInRecent++;
              } else {
                unreadNonPmInRecent++;
              }
            }
          }
          computedUnreadNotifs = math.max(
            computedUnreadNotifs,
            unreadNonPmInRecent,
          );
          computedUnreadPms = math.max(computedUnreadPms, unreadPmInRecent);
        }
      } catch (_) {
        // Non-fatal if secondary `/notifications.json` check fails or is unmocked.
      }

      _unreadNotificationCount = computedUnreadNotifs;
      _unreadPmCount = computedUnreadPms;

      await store.saveSession(
        user: _currentUser,
        csrfToken: _csrfToken,
        rawCookies: _rawCookies,
      );

      await LinDoPlatformBridge.instance.updateTrayState(
        unreadCount: totalUnreadBadgeCount,
        username: _currentUser?.username,
      );
      notifyListeners();
      return totalUnreadBadgeCount;
    } on LinuxDoException catch (err) {
      if (err.statusCode == 404 || err.statusCode == 401) {
        if (!silent) rethrow;
        return totalUnreadBadgeCount;
      }
      if (!silent) rethrow;
      return totalUnreadBadgeCount;
    } catch (err) {
      if (!silent) {
        throw LinuxDoException(err.toString());
      }
      return totalUnreadBadgeCount;
    }
  }

  void startLiveNotificationSync({
    Duration pollInterval = const Duration(seconds: 25),
  }) {
    stopLiveNotificationSync();
    _liveSyncTimer = Timer.periodic(pollInterval, (_) {
      unawaited(_pollLiveUnreadOnce());
    });
  }

  void stopLiveNotificationSync() {
    _liveSyncTimer?.cancel();
    _liveSyncTimer = null;
  }

  Future<void> onAppResumed() async {
    if (_siteVerificationRequired) return;
    if (!isAuthorized) return;
    await refreshCurrentSessionAndUnread(silent: true);
  }

  Future<void> _pollLiveUnreadOnce() async {
    if (_pollInFlight || !isAuthorized || _siteVerificationRequired) return;
    _pollInFlight = true;
    try {
      final userId = _currentUser?.userId ?? 0;
      var shouldRefreshSession = true;

      if (userId > 0) {
        try {
          final notifChannel = '/notification/$userId';
          final unreadChannel = '/unread/$userId';
          final res = await _sendRequest(
            method: 'POST',
            pathOrUrl: '/message-bus/$_messageBusClientId/poll?dlp=t',
            formFields: <String, String>{
              notifChannel: '$_notifChannelSeq',
              unreadChannel: '$_unreadChannelSeq',
            },
            extraHeaders: const <String, String>{
              'Discourse-Background': 'true',
            },
          );
          if (res.status >= 200 && res.status < 300) {
            final decoded = jsonDecode(res.body);
            if (decoded is List) {
              shouldRefreshSession = false;
              for (final msg in decoded) {
                final m = _asMap(msg);
                if (m == null) continue;
                final ch = _asString(m['channel']);
                final msgId = _asInt(m['message_id'], fallback: -1);
                if (ch == notifChannel) {
                  if (msgId > _notifChannelSeq) _notifChannelSeq = msgId;
                  shouldRefreshSession = true;
                } else if (ch == unreadChannel) {
                  if (msgId > _unreadChannelSeq) _unreadChannelSeq = msgId;
                  shouldRefreshSession = true;
                }
              }
            }
          }
        } catch (_) {
          shouldRefreshSession = true;
        }
      }

      if (shouldRefreshSession) {
        await refreshCurrentSessionAndUnread(silent: true);
      }
    } finally {
      _pollInFlight = false;
    }
  }

  // ---------------------------------------------------------------------------
  // Feed, Categories & Custom Emojis
  // ---------------------------------------------------------------------------

  Future<TopicFeedPage> fetchFeed({
    FeedSortFilter sort = FeedSortFilter.LATEST,
    TopPeriodFilter topPeriod = TopPeriodFilter.WEEKLY,
    int? categoryId,
    String? categorySlug,
    String? tag,
    int page = 0,
    String? moreTopicsUrl,
  }) async {
    final cleanPage = math.max(0, page);

    if (sort == FeedSortFilter.FOLLOWING &&
        (moreTopicsUrl == null || moreTopicsUrl.trim().isEmpty)) {
      return _fetchFollowingFeed(
        page: cleanPage,
        categoryId: categoryId,
        tag: tag,
      );
    }

    String requestPath;
    if (moreTopicsUrl != null && moreTopicsUrl.trim().isNotEmpty) {
      requestPath = _normalizeMoreTopicsJsonPath(moreTopicsUrl.trim());
    } else if (tag != null && tag.trim().isNotEmpty) {
      final encodedTag = Uri.encodeComponent(tag.trim());
      final sortSuffix = sort == FeedSortFilter.LATEST
          ? '.json'
          : '/l/${sort.wireKey}.json';
      final queryParams = <String>[
        if (cleanPage > 0) 'page=$cleanPage',
        if (sort == FeedSortFilter.TOP) 'period=${topPeriod.wireKey}',
      ];
      requestPath =
          '/tag/$encodedTag$sortSuffix${queryParams.isEmpty ? "" : "?${queryParams.join("&")}"}';
    } else if (categoryId != null && categoryId > 0) {
      final resolvedSlug = _resolveCategorySlug(categoryId, categorySlug);
      final sortPart =
          sort == FeedSortFilter.LATEST ? '' : '/l/${sort.wireKey}';
      final slugSegment = resolvedSlug.isEmpty ? '' : '/$resolvedSlug';
      final queryParams = <String>[
        if (cleanPage > 0) 'page=$cleanPage',
        if (sort == FeedSortFilter.TOP) 'period=${topPeriod.wireKey}',
      ];
      requestPath =
          '/c$slugSegment/$categoryId$sortPart.json${queryParams.isEmpty ? "" : "?${queryParams.join("&")}"}';
    } else {
      final queryParams = <String>[
        if (cleanPage > 0) 'page=$cleanPage',
        if (sort == FeedSortFilter.TOP) 'period=${topPeriod.wireKey}',
      ];
      requestPath =
          '/${sort.wireKey}.json${queryParams.isEmpty ? "" : "?${queryParams.join("&")}"}';
    }

    final root = await _requestJsonMap(method: 'GET', pathOrUrl: requestPath);
    return _parseTopicListFeedPage(root, currentPage: cleanPage);
  }

  Future<TopicFeedPage> _fetchFollowingFeed({
    required int page,
    int? categoryId,
    String? tag,
  }) async {
    final username = _currentUser?.username.trim() ?? '';
    if (username.isEmpty) {
      throw LinuxDoException(
        LinDoNetworkCopy.GUEST_CONTENT_LOGIN_REQUIRED.text,
        statusCode: 401,
      );
    }

    final cursorKeyPrefix = '${username.toLowerCase()}:';
    var cursorParam = '';
    if (page == 0) {
      _followCursors.removeWhere((k, _) => k.startsWith(cursorKeyPrefix));
    } else {
      final prevCursor = _followCursors['$cursorKeyPrefix$page'];
      if (prevCursor == null || prevCursor.isEmpty) {
        return TopicFeedPage(topics: const <TopicSummary>[], nextPage: page + 1);
      }
      cursorParam = '&created_before=${Uri.encodeQueryComponent(prevCursor)}';
    }

    final encodedUser = Uri.encodeComponent(username);
    Map<String, dynamic>? root;
    try {
      root = await _requestJsonMap(
        method: 'GET',
        pathOrUrl: '/follow/posts/$encodedUser.json?limit=30$cursorParam',
      );
    } on LinuxDoException catch (err) {
      if (err.statusCode != 404) rethrow;
      try {
        final offsetQuery = page > 0 ? '?offset=${page * 20}' : '';
        root = await _requestJsonMap(
          method: 'GET',
          pathOrUrl: '/u/$encodedUser/follow/feed.json$offsetQuery',
        );
      } on LinuxDoException catch (innerErr) {
        if (innerErr.statusCode != 404) rethrow;
        final pageQuery = page > 0 ? '?page=$page' : '';
        root = await _requestJsonMap(
          method: 'GET',
          pathOrUrl: '/following.json$pageQuery',
        );
      }
    }

    if (root['topic_list'] is Map) {
      final parsed = _parseTopicListFeedPage(root, currentPage: page);
      final filtered = _filterTopicsByCategoryAndTag(
        parsed.topics,
        categoryId: categoryId,
        tag: tag,
      );
      return TopicFeedPage(
        topics: filtered,
        pinnedTopics: parsed.pinnedTopics,
        moreTopicsUrl: parsed.moreTopicsUrl,
        nextPage: parsed.nextPage,
      );
    }

    final postsArr = (root['posts'] as List?) ?? (root['feed'] as List?);
    if (postsArr == null) {
      return TopicFeedPage(topics: const <TopicSummary>[], nextPage: page + 1);
    }

    final extras = _asMap(root['extras']);
    final hasMore = extras?['has_more'] == true;
    String? lastCreatedAt;
    final items = <TopicSummary>[];
    final seenTopics = <int>{};

    for (final rawPost in postsArr) {
      final p = _asMap(rawPost);
      if (p == null) continue;
      final createdIso = _asString(p['created_at']);
      if (createdIso.isNotEmpty) {
        lastCreatedAt = createdIso;
      }
      final topicMap = _asMap(p['topic']);
      final topicId = _asInt(p['topic_id'], fallback: _asInt(topicMap?['id']));
      if (topicId <= 0 || !seenTopics.add(topicId)) continue;

      final catId = _asInt(
        p['category_id'],
        fallback: _asInt(topicMap?['category_id'], fallback: 4),
      );
      final catMeta = _findCategoryMeta(catId);
      final userMap = _asMap(p['user']) ?? p;
      final author = _parseTopicUser(userMap);
      final rawTags = (topicMap?['tags'] as List?) ?? (p['tags'] as List?);

      items.add(
        TopicSummary(
          id: topicId,
          title: decodeDiscourseHtmlSnippet(
            _nonBlankOr(
              _asString(topicMap?['title']),
              _nonBlankOr(
                _asString(p['topic_title']),
                LinDoCommonCopy.TOPIC_NUMBER_FALLBACK.format(topicId),
              ),
            ),
          ),
          fancyTitle: _asString(topicMap?['fancy_title']),
          slug: _nonBlankOr(_asString(topicMap?['slug']), _asString(p['topic_slug'])),
          postsCount: _asInt(topicMap?['posts_count'], fallback: _asInt(p['post_number'], fallback: 1)),
          highestPostNumber: _asInt(
            topicMap?['highest_post_number'],
            fallback: _asInt(p['post_number'], fallback: 1),
          ),
          replyCount: math.max(0, _asInt(topicMap?['posts_count'], fallback: 1) - 1),
          views: _asInt(topicMap?['views']),
          likeCount: _asInt(topicMap?['like_count'], fallback: _asInt(p['like_count'])),
          categoryId: catId,
          categoryName: catMeta.name,
          categoryColorHex: catMeta.colorHex,
          tags: _parseTagStrings(rawTags),
          excerpt: decodeDiscourseHtmlSnippet(
            _nonBlankOr(_asString(p['excerpt']), _asString(p['cooked'])),
          ),
          lastPostedAt: formatRelativeTime(createdIso),
          createdAtIso: createdIso,
          lastPostedAtIso: createdIso,
          originalPoster: author,
          participantAvatars: <TopicUser>[author],
        ),
      );
    }

    if (hasMore && lastCreatedAt != null) {
      _followCursors['$cursorKeyPrefix${page + 1}'] = lastCreatedAt;
    }

    final filtered = _filterTopicsByCategoryAndTag(
      items,
      categoryId: categoryId,
      tag: tag,
    );
    return TopicFeedPage(
      topics: filtered,
      moreTopicsUrl: (hasMore && lastCreatedAt != null) ? 'follow:page=${page + 1}' : null,
      nextPage: page + 1,
    );
  }

  Future<List<CategoryInfo>> fetchCategories({bool forceRefresh = false}) async {
    if (!forceRefresh && store.cachedCategories.isNotEmpty) {
      _categories = store.cachedCategories;
      return _categories;
    }

    Map<String, dynamic> root;
    try {
      root = await _requestJsonMap(method: 'GET', pathOrUrl: '/site.json');
    } catch (_) {
      root = await _requestJsonMap(method: 'GET', pathOrUrl: '/categories.json');
    }

    List? rawCategories = root['categories'] as List?;
    if (rawCategories == null) {
      final categoryListObj = _asMap(root['category_list']);
      rawCategories = categoryListObj?['categories'] as List?;
    }

    if (rawCategories == null || rawCategories.isEmpty) {
      return _categories;
    }

    final parsed = <CategoryInfo>[];
    final seenIds = <int>{};

    void parseCategoryItem(Map<String, dynamic> cObj, {int? inheritedParentId}) {
      final id = _asInt(cObj['id']);
      final name = _asString(cObj['name']).trim();
      if (id <= 0 || name.isEmpty || !seenIds.add(id)) return;

      final color = _asString(cObj['color']).replaceFirst('#', '').trim();
      final textColor = _asString(cObj['text_color']).replaceFirst('#', '').trim();
      final descText = _nonBlankOr(
        _asString(cObj['description_text']).trim(),
        decodeDiscourseHtmlSnippet(_asString(cObj['description_excerpt'])),
      );
      final parentId = _asNullableInt(cObj['parent_category_id']) ?? inheritedParentId;

      var minTrust = _asInt(cObj['minimum_trust_level']);
      if (minTrust == 0) {
        final lvMatch = RegExp(r'\bLv\s*([1-4])\b', caseSensitive: false).firstMatch(name);
        if (lvMatch != null) {
          minTrust = int.tryParse(lvMatch.group(1) ?? '') ?? 0;
        }
      }

      final requiredTagGroups = <String>[];
      final rtgList = cObj['required_tag_groups'];
      if (rtgList is List) {
        for (final g in rtgList) {
          final gMap = _asMap(g);
          final gName = _asString(gMap?['name']).trim();
          if (gName.isNotEmpty) requiredTagGroups.add(gName);
        }
      }

      parsed.add(
        CategoryInfo(
          id: id,
          name: name,
          slug: _asString(cObj['slug']).trim(),
          colorHex: color.isEmpty ? '0088CC' : color,
          textColorHex: textColor.isEmpty ? 'FFFFFF' : textColor,
          descriptionText: descText,
          topicCount: _asInt(cObj['topic_count']),
          parentCategoryId: parentId,
          minimumTrustLevel: minTrust,
          readRestricted: cObj['read_restricted'] == true,
          defaultTemplate: _asString(cObj['topic_template']),
          allowedTags: _parseTagStrings(cObj['allowed_tags'] as List?),
          requiredTagGroupNames: requiredTagGroups,
        ),
      );

      final subList = cObj['subcategory_list'];
      if (subList is List) {
        for (final sub in subList) {
          final subMap = _asMap(sub);
          if (subMap != null) {
            parseCategoryItem(subMap, inheritedParentId: id);
          }
        }
      }
    }

    for (final raw in rawCategories) {
      final map = _asMap(raw);
      if (map != null) parseCategoryItem(map);
    }

    if (parsed.isNotEmpty) {
      _categories = List<CategoryInfo>.unmodifiable(parsed);
      await store.saveCachedCategories(_categories);
      notifyListeners();
    }
    return _categories;
  }

  Future<void> syncCustomEmojis({bool forceRefresh = false}) async {
    if (!forceRefresh && store.cachedEmojisJson != null) {
      LinuxDoEmojiCatalog.instance.mergeRemoteEmojisJson(store.cachedEmojisJson!);
      return;
    }

    final root = await _requestJsonMap(
      method: 'GET',
      pathOrUrl: '/emojis.json',
      extraHeaders: const <String, String>{'Discourse-Background': 'true'},
    );
    if (root.isNotEmpty) {
      LinuxDoEmojiCatalog.instance.mergeRemoteEmojisJson(root);
      await store.saveCachedEmojisJson(root);
    }
  }

  // ---------------------------------------------------------------------------
  // Topic Detail, Stream Batching & Read Timings
  // ---------------------------------------------------------------------------

  Future<TopicDetailModel> fetchTopicDetail(
    int topicId, {
    int? nearPostNumber,
  }) async {
    final path = (nearPostNumber != null && nearPostNumber > 1)
        ? '/t/$topicId/$nearPostNumber.json?track_visit=true&forceLoad=true'
        : '/t/$topicId.json?track_visit=true&forceLoad=true';

    final root = await _requestJsonMap(method: 'GET', pathOrUrl: path);
    final resolvedId = _asInt(root['id'], fallback: topicId);
    final rawTitle = _nonBlankOr(
      decodeDiscourseHtmlSnippet(_asString(root['fancy_title'])),
      _asString(root['title']),
    );
    final catId = _asInt(root['category_id'], fallback: 4);
    final catMeta = _findCategoryMeta(catId);
    final tags = _parseTagStrings(root['tags'] as List?);

    final detailsMap = _asMap(root['details']);
    final createdByMap = _asMap(detailsMap?['created_by']);
    final opUsername = _nullIfBlank(_asString(createdByMap?['username']));

    final badgeCatalogById = _parseBadgeDefinitionsMap(root['badges']);
    final userBadgesByUserId = _parseTopicUserBadges(
      root['user_badges'],
      badgeCatalogById,
    );

    final postStream = _asMap(root['post_stream']);
    final streamIds = <int>[];
    final rawStream = postStream?['stream'];
    if (rawStream is List) {
      for (final item in rawStream) {
        final id = (item as num?)?.toInt();
        if (id != null && id > 0) streamIds.add(id);
      }
    }

    final posts = <PostItemModel>[];
    final rawPosts = postStream?['posts'];
    if (rawPosts is List) {
      for (final pRaw in rawPosts) {
        final pMap = _asMap(pRaw);
        if (pMap == null) continue;
        posts.add(
          _parsePostItem(
            pMap,
            fallbackTopicId: resolvedId,
            topicOwnerUsername: opUsername,
            badgeCatalogById: badgeCatalogById,
            userBadgesByUserId: userBadgesByUserId,
          ),
        );
      }
    }

    final postsCount = _asInt(
      root['posts_count'],
      fallback: math.max(streamIds.length, posts.length),
    );
    final highestPostNum = _asNullableInt(root['highest_post_number']) ?? postsCount;
    final serverLastRead = _asNullableInt(root['last_read_post_number']);
    final localFloor = store.getReadFloor(resolvedId);
    int? effectiveLastRead = serverLastRead ?? localFloor;
    if (serverLastRead != null && localFloor != null) {
      effectiveLastRead = math.max(serverLastRead, localFloor);
    }

    final aiSummaryObj = _asMap(root['ai_topic_summary']);
    final aiSummary = _nullIfBlank(
      _asString(aiSummaryObj?['summarized_text']),
    );

    final detail = TopicDetailModel(
      id: resolvedId,
      title: rawTitle.isEmpty
          ? LinDoCommonCopy.TOPIC_NUMBER_FALLBACK.format(resolvedId)
          : rawTitle,
      categoryName: catMeta.name,
      categoryColorHex: catMeta.colorHex,
      tags: tags,
      views: _asInt(root['views']),
      likeCount: _asInt(root['like_count']),
      postsCount: math.max(1, postsCount),
      bookmarked: root['bookmarked'] == true,
      posts: posts,
      streamIds: streamIds,
      originalPosterUsername: opUsername,
      highestPostNumber: highestPostNum,
      lastReadPostNumber: effectiveLastRead,
      notificationLevel: _asInt(detailsMap?['notification_level'], fallback: 1),
      closed: root['closed'] == true,
      archived: root['archived'] == true,
      canEditTopic: detailsMap?['can_edit'] == true,
      categoryId: catId,
      aiSummary: aiSummary,
    );

    final visitFloor = effectiveLastRead ??
        (posts.isNotEmpty ? posts.last.postNumber : 1);
    await store.recordTopicVisit(
      topicId: detail.id,
      title: detail.title,
      categoryName: detail.categoryName,
      categoryColorHex: detail.categoryColorHex,
      lastReadPostNumber: visitFloor,
      postsCount: detail.postsCount,
    );

    return detail;
  }

  Future<List<PostItemModel>> fetchTopicPostsByIds(
    int topicId,
    List<int> postIds,
  ) async {
    final cleanIds = postIds.where((id) => id > 0).toList(growable: false);
    if (cleanIds.isEmpty) return const <PostItemModel>[];

    final allPosts = <PostItemModel>[];
    const batchSize = 20;

    for (var offset = 0; offset < cleanIds.length; offset += batchSize) {
      final end = math.min(offset + batchSize, cleanIds.length);
      final chunk = cleanIds.sublist(offset, end);
      final query = chunk.map((id) => 'post_ids[]=$id').join('&');
      final root = await _requestJsonMap(
        method: 'GET',
        pathOrUrl: '/t/$topicId/posts.json?$query',
      );

      final badgeCatalogById = _parseBadgeDefinitionsMap(root['badges']);
      final userBadgesByUserId = _parseTopicUserBadges(
        root['user_badges'],
        badgeCatalogById,
      );
      final postStream = _asMap(root['post_stream']);
      final rawPosts = (postStream?['posts'] as List?) ?? (root['posts'] as List?);
      if (rawPosts == null) continue;

      for (final pRaw in rawPosts) {
        final pMap = _asMap(pRaw);
        if (pMap == null) continue;
        allPosts.add(
          _parsePostItem(
            pMap,
            fallbackTopicId: topicId,
            badgeCatalogById: badgeCatalogById,
            userBadgesByUserId: userBadgesByUserId,
          ),
        );
      }
    }
    return allPosts;
  }

  Future<void> recordTopicTiming(
    int topicId, {
    List<int> postNumbers = const [],
    Map<int, int>? timingsMsByPostNumber,
    int? postNumber,
    int durationMs = 1200,
    String? title,
    String? categoryName,
    String categoryColorHex = '0088CC',
    int? postsCount,
  }) async {
    if (topicId <= 0) return;

    final resolvedTimings = <int, int>{};
    if (timingsMsByPostNumber != null) {
      for (final entry in timingsMsByPostNumber.entries) {
        if (entry.key > 0) {
          resolvedTimings[entry.key] = math.max(100, entry.value);
        }
      }
    }
    for (final pn in postNumbers) {
      if (pn > 0) {
        resolvedTimings.putIfAbsent(pn, () => math.max(100, durationMs));
      }
    }
    if (postNumber != null && postNumber > 0) {
      resolvedTimings.putIfAbsent(postNumber, () => math.max(100, durationMs));
    }
    if (resolvedTimings.isEmpty) return;

    final maxFloor = resolvedTimings.keys.reduce(math.max);
    await store.recordTopicVisit(
      topicId: topicId,
      title: title ?? '',
      categoryName: categoryName ?? '',
      categoryColorHex: categoryColorHex,
      lastReadPostNumber: maxFloor,
      postsCount: postsCount ?? maxFloor,
    );

    if (!isAuthorized) return;

    final form = <String, String>{
      'topic_id': '$topicId',
      'topic_time': '${math.max(100, durationMs)}',
    };
    for (final entry in resolvedTimings.entries) {
      form['timings[${entry.key}]'] = '${entry.value}';
    }

    try {
      await _sendRequest(
        method: 'POST',
        pathOrUrl: '/topics/timings',
        formFields: form,
        extraHeaders: const <String, String>{
          'Discourse-Background': 'true',
          'X-SILENCE-LOGGER': 'true',
        },
      );
    } catch (_) {
      // Read timing telemetry is best-effort and never blocks reading UX.
    }
  }

  // ---------------------------------------------------------------------------
  // Post & Topic Actions (Reactions, Boosts, Bookmarks, Polls, Raw)
  // ---------------------------------------------------------------------------

  Future<PostItemModel?> togglePostLike(
    int postId, {
    bool? currentlyLiked,
  }) async {
    try {
      return await togglePostReaction(postId, 'heart');
    } on LinuxDoException catch (err) {
      if (err.statusCode != 404) rethrow;
      if (currentlyLiked == true) {
        final root = await _requestJsonMap(
          method: 'DELETE',
          pathOrUrl: '/post_actions/$postId.json?post_action_type_id=2',
        );
        return root.containsKey('id') ? _parsePostItem(root) : null;
      }
      final root = await _requestJsonMap(
        method: 'POST',
        pathOrUrl: '/post_actions.json',
        formFields: <String, String>{
          'id': '$postId',
          'post_action_type_id': '2',
        },
      );
      return root.containsKey('id') ? _parsePostItem(root) : null;
    }
  }

  Future<PostItemModel?> togglePostReaction(
    int postId,
    String reactionId,
  ) async {
    final cleanReaction = reactionId.trim().replaceAll(':', '');
    if (cleanReaction.isEmpty) return null;

    final root = await _requestJsonMap(
      method: 'PUT',
      pathOrUrl:
          '/discourse-reactions/posts/$postId/custom-reactions/${Uri.encodeComponent(cleanReaction)}/toggle.json',
    );
    if (root.containsKey('id') && root.containsKey('post_number')) {
      return _parsePostItem(root);
    }
    final nestedPost = _asMap(root['post']);
    if (nestedPost != null) {
      return _parsePostItem(nestedPost);
    }
    return null;
  }

  Future<PostBoostItem> submitPostBoost(int postId, String raw) async {
    final stats = BoostInputStats.compute(raw);
    if (stats.isEmpty) {
      throw LinuxDoException(
        LinDoNetworkCopy.BOOST_EMPTY_INPUT.text,
        statusCode: 400,
      );
    }
    if (stats.exceedsLength) {
      throw LinuxDoException(
        LinDoNetworkCopy.BOOST_LENGTH_EXCEEDED.format(
          BoostInputStats.maxBoostVisibleLength,
          stats.visibleLength,
        ),
        statusCode: 400,
      );
    }
    if (stats.exceedsEmojiLimit) {
      throw LinuxDoException(
        LinDoNetworkCopy.BOOST_EMOJI_EXCEEDED.format(
          BoostInputStats.maxBoostEmojiCount,
          stats.emojiCount,
        ),
        statusCode: 400,
      );
    }

    final root = await _requestJsonMap(
      method: 'POST',
      pathOrUrl: '/discourse-boosts/posts/$postId/boosts.json',
      formFields: <String, String>{'raw': raw.trim()},
    );
    final boostMap = _asMap(root['boost']) ?? root;
    final parsed = _parseBoostItem(boostMap);
    if (parsed != null) return parsed;

    return PostBoostItem(
      id: DateTime.now().millisecondsSinceEpoch,
      cookedHtml: decodeDiscourseHtmlSnippet(raw.trim()),
      plainText: raw.trim(),
      userId: _currentUser?.userId ?? 0,
      username: _currentUser?.username ?? '',
      displayName: _currentUser?.displayName,
      avatarTemplate: _currentUser?.avatarTemplate ?? '',
      canDelete: true,
    );
  }

  Future<bool> deletePostBoost(int boostId, {int? postId}) async {
    if (boostId <= 0) return false;
    try {
      await _sendRequest(
        method: 'DELETE',
        pathOrUrl: '/discourse-boosts/boosts/$boostId.json',
      );
      return true;
    } on LinuxDoException catch (err) {
      if (err.statusCode == 404 && postId != null && postId > 0) {
        await _sendRequest(
          method: 'DELETE',
          pathOrUrl: '/discourse-boosts/posts/$postId/boosts/$boostId.json',
        );
        return true;
      }
      rethrow;
    }
  }

  Future<bool> toggleTopicBookmark(
    int topicId, {
    bool bookmarked = true,
  }) async {
    final route = bookmarked ? 'bookmark' : 'remove_bookmarks';
    await _sendRequest(
      method: 'PUT',
      pathOrUrl: '/t/$topicId/$route.json',
    );
    return bookmarked;
  }

  Future<int?> togglePostBookmark(
    int postId, {
    bool bookmarked = true,
    int? bookmarkId,
  }) async {
    if (bookmarked) {
      final root = await _requestJsonMap(
        method: 'POST',
        pathOrUrl: '/bookmarks.json',
        formFields: <String, String>{
          'bookmarkable_id': '$postId',
          'bookmarkable_type': 'Post',
        },
      );
      return _asNullableInt(root['id']) ?? 0;
    }

    if (bookmarkId != null && bookmarkId > 0) {
      await _sendRequest(
        method: 'DELETE',
        pathOrUrl: '/bookmarks/$bookmarkId.json',
      );
      return null;
    }

    await _sendRequest(
      method: 'PUT',
      pathOrUrl: '/posts/$postId/bookmark.json',
      formFields: const <String, String>{'bookmarked': 'false'},
    );
    return null;
  }

  Future<void> updateTopicNotificationLevel(
    int topicId,
    TopicNotificationLevel level,
  ) async {
    await _sendRequest(
      method: 'POST',
      pathOrUrl: '/t/$topicId/notifications.json',
      formFields: <String, String>{
        'notification_level': '${level.code}',
      },
    );
  }

  Future<PollModel> votePoll({
    required int postId,
    required String pollName,
    required List<String> options,
  }) async {
    Map<String, dynamic> root;
    if (options.isEmpty) {
      root = await _requestJsonMap(
        method: 'DELETE',
        pathOrUrl:
            '/polls/vote.json?post_id=$postId&poll_name=${Uri.encodeQueryComponent(pollName)}',
        formFields: <String, String>{
          'post_id': '$postId',
          'poll_name': pollName,
        },
      );
    } else {
      final pairs = <MapEntry<String, String>>[
        MapEntry<String, String>('post_id', '$postId'),
        MapEntry<String, String>('poll_name', pollName),
        for (final opt in options) MapEntry<String, String>('options[]', opt),
      ];
      root = await _requestJsonMap(
        method: 'PUT',
        pathOrUrl: '/polls/vote.json',
        formPairs: pairs,
      );
    }

    final pollObj = _asMap(root['poll']) ?? root;
    final voteList = (root['vote'] as List?)
            ?.map((e) => e?.toString().trim() ?? '')
            .where((s) => s.isNotEmpty)
            .toList(growable: false) ??
        options;
    return _parsePollModel(pollObj, preloadedVotes: voteList);
  }

  Future<String> fetchPostRaw(
    int postId, {
    int? topicId,
    int? postNumber,
  }) async {
    try {
      final root = await _requestJsonMap(
        method: 'GET',
        pathOrUrl: '/posts/$postId.json',
      );
      final raw = _asString(root['raw']);
      if (raw.isNotEmpty) return raw;
    } catch (_) {
      if (topicId == null || postNumber == null) rethrow;
    }

    if (topicId != null && postNumber != null) {
      final res = await _sendRequest(
        method: 'GET',
        pathOrUrl: '/raw/$topicId/$postNumber',
      );
      if (res.status >= 200 && res.status < 300) {
        return res.body;
      }
    }
    return '';
  }

  // ---------------------------------------------------------------------------
  // Composer: Publish Topic, Reply, Edit, Private Message, Upload, Mentions
  // ---------------------------------------------------------------------------

  Future<PublishOutcome> publishTopic({
    required String title,
    required String raw,
    int? categoryId,
    List<String> tags = const [],
  }) async {
    final pairs = <MapEntry<String, String>>[
      MapEntry<String, String>('title', title.trim()),
      MapEntry<String, String>('raw', raw),
      const MapEntry<String, String>('archetype', 'regular'),
      if (categoryId != null && categoryId > 0)
        MapEntry<String, String>('category', '$categoryId'),
      for (final t in tags)
        if (t.trim().isNotEmpty) MapEntry<String, String>('tags[]', t.trim()),
    ];

    final root = await _requestJsonMap(
      method: 'POST',
      pathOrUrl: '/posts.json',
      formPairs: pairs,
    );
    return _parsePublishOutcome(root, fallbackTitle: title.trim());
  }

  Future<PublishOutcome> publishReply({
    required int topicId,
    required String raw,
    int? replyToPostNumber,
  }) async {
    final fields = <String, String>{
      'topic_id': '$topicId',
      'raw': raw,
      if (replyToPostNumber != null && replyToPostNumber > 0)
        'reply_to_post_number': '$replyToPostNumber',
    };

    final root = await _requestJsonMap(
      method: 'POST',
      pathOrUrl: '/posts.json',
      formFields: fields,
    );
    return _parsePublishOutcome(root, fallbackTopicId: topicId);
  }

  Future<PublishOutcome> editPost({
    required int postId,
    required String raw,
    String? editReason,
    int? topicId,
    String? newTitle,
    int? categoryId,
    List<String>? tags,
  }) async {
    if (topicId != null &&
        topicId > 0 &&
        (newTitle != null || categoryId != null || tags != null)) {
      final topicPairs = <MapEntry<String, String>>[
        if (newTitle != null && newTitle.trim().isNotEmpty)
          MapEntry<String, String>('title', newTitle.trim()),
        if (categoryId != null && categoryId > 0)
          MapEntry<String, String>('category_id', '$categoryId'),
        if (tags != null)
          for (final t in tags)
            if (t.trim().isNotEmpty) MapEntry<String, String>('tags[]', t.trim()),
      ];
      if (topicPairs.isNotEmpty) {
        await _requestJsonMap(
          method: 'PUT',
          pathOrUrl: '/t/-/$topicId.json',
          formPairs: topicPairs,
        );
      }
    }

    final postFields = <String, String>{
      'post[raw]': raw,
      if (editReason != null && editReason.trim().isNotEmpty)
        'post[edit_reason]': editReason.trim(),
    };
    final root = await _requestJsonMap(
      method: 'PUT',
      pathOrUrl: '/posts/$postId.json',
      formFields: postFields,
    );
    final postMap = _asMap(root['post']) ?? root;
    final parsedPost = postMap.containsKey('id')
        ? _parsePostItem(postMap, fallbackTopicId: topicId ?? 0)
        : null;
    return PublishOutcome(
      success: true,
      topicId: parsedPost?.topicId ?? topicId,
      title: newTitle?.trim(),
      post: parsedPost,
    );
  }

  Future<PublishOutcome> sendPrivateMessage({
    required String title,
    required String raw,
    required List<String> targetUsernames,
  }) async {
    final recipients = targetUsernames
        .map((u) => u.trim().replaceFirst(RegExp(r'^@+'), ''))
        .where((u) => u.isNotEmpty)
        .join(',');
    final root = await _requestJsonMap(
      method: 'POST',
      pathOrUrl: '/posts.json',
      formFields: <String, String>{
        'title': title.trim(),
        'raw': raw,
        'archetype': 'private_message',
        'target_recipients': recipients,
      },
    );
    return _parsePublishOutcome(root, fallbackTitle: title.trim());
  }

  Future<String> uploadAttachment({
    String? filePath,
    Uint8List? bytes,
    String? fileName,
    String? mimeType,
    String type = 'composer',
  }) async {
    await _ensureCsrfToken(silent: true);

    final effectiveName = (fileName != null && fileName.trim().isNotEmpty)
        ? fileName.trim()
        : (filePath != null && filePath.trim().isNotEmpty
            ? filePath.trim().split(RegExp(r'[/\\]')).last
            : 'upload.bin');
    final payloadBytes = bytes ??
        ((filePath != null && filePath.trim().isNotEmpty)
            ? await File(filePath.trim()).readAsBytes()
            : Uint8List(0));
    final resolvedMime = (mimeType != null && mimeType.trim().isNotEmpty)
        ? mimeType.trim()
        : _guessMimeType(effectiveName);

    final url = '$kLinuxDoBaseUrl/uploads.json';
    final stopwatch = Stopwatch()..start();

    _RawHttpResponse rawRes;
    if (_useBrowserTransport) {
      final bridgeRes = await browserBridge.uploadMultipart(
        url: url,
        fileName: effectiveName,
        mimeType: resolvedMime,
        bytes: payloadBytes,
        fileField: 'file',
        fields: <String, String>{
          'type': type,
          'synchronous': 'true',
        },
        headers: <String, String>{
          'Accept': 'application/json',
          'X-Requested-With': 'XMLHttpRequest',
          if (_csrfToken.isNotEmpty) 'X-CSRF-Token': _csrfToken,
        },
      );
      rawRes = _RawHttpResponse(
        status: bridgeRes.status,
        headers: bridgeRes.headers,
        body: bridgeRes.body,
        transport: 'BrowserBridge',
      );
    } else {
      final req = http.MultipartRequest('POST', Uri.parse(url));
      req.headers['Accept'] = 'application/json';
      req.headers['X-Requested-With'] = 'XMLHttpRequest';
      if (_csrfToken.isNotEmpty) {
        req.headers['X-CSRF-Token'] = _csrfToken;
      }
      if (_rawCookies.isNotEmpty) {
        req.headers['Cookie'] = _rawCookies;
      }
      req.fields['type'] = type;
      req.fields['synchronous'] = 'true';
      req.files.add(
        http.MultipartFile.fromBytes(
          'file',
          payloadBytes,
          filename: effectiveName,
        ),
      );
      final streamed = await _fallbackHttpClient.send(req);
      final body = await streamed.stream.bytesToString();
      _ingestSetCookieFromHeaders(streamed.headers);
      rawRes = _RawHttpResponse(
        status: streamed.statusCode,
        headers: streamed.headers,
        body: body,
        transport: 'HttpClient',
      );
    }

    stopwatch.stop();
    _logHttpOutcome('POST', url, rawRes, stopwatch.elapsedMilliseconds);
    _checkCloudflareChallenge(rawRes);

    if (rawRes.status < 200 || rawRes.status >= 300) {
      throw LinuxDoException(
        _extractDiscourseErrorMessage(rawRes.body, rawRes.status),
        statusCode: rawRes.status,
      );
    }

    final decoded = jsonDecode(rawRes.body);
    final obj = _asMap(decoded);
    if (obj == null) {
      throw LinuxDoException(
        LinDoNetworkCopy.INVALID_PAYLOAD_RETRY.text,
        statusCode: rawRes.status,
      );
    }

    final targetUrl = _nonBlankOr(
      _asString(obj['short_url']),
      _asString(obj['url']),
    );
    final origName = _nonBlankOr(_asString(obj['original_filename']), effectiveName)
        .replaceAll('[', '(')
        .replaceAll(']', ')')
        .replaceAll('|', '-')
        .trim();
    final width = _asInt(obj['width']);
    final height = _asInt(obj['height']);
    final ext = _asString(obj['extension']).toLowerCase();
    final isImage = (width > 0 && height > 0) ||
        const <String>{'png', 'jpg', 'jpeg', 'gif', 'webp', 'avif', 'svg'}
            .contains(ext);

    if (isImage) {
      if (width > 0 && height > 0) {
        return '![$origName|${width}x$height]($targetUrl)';
      }
      return '![$origName]($targetUrl)';
    }
    final humanSize = _asString(obj['human_filesize']).trim();
    final sizeSuffix = humanSize.isEmpty ? '' : ' ($humanSize)';
    return '[$origName|attachment]($targetUrl)$sizeSuffix';
  }

  Future<List<TopicUser>> searchUsersForMention(String term) async {
    final cleanTerm = term.trim().replaceFirst(RegExp(r'^@+'), '');
    if (cleanTerm.isEmpty) return const <TopicUser>[];

    final root = await _requestJsonMap(
      method: 'GET',
      pathOrUrl:
          '/u/search/users.json?term=${Uri.encodeQueryComponent(cleanTerm)}&include_groups=true&limit=8',
    );

    final results = <TopicUser>[];
    final usersRaw = root['users'];
    if (usersRaw is List) {
      for (final u in usersRaw) {
        final uMap = _asMap(u);
        if (uMap == null) continue;
        final parsed = _parseTopicUser(uMap);
        if (parsed.username.isNotEmpty) {
          results.add(parsed);
        }
      }
    }

    final groupsRaw = root['groups'];
    if (groupsRaw is List) {
      for (final g in groupsRaw) {
        final gMap = _asMap(g);
        if (gMap == null) continue;
        final groupName = _asString(gMap['name']).trim();
        if (groupName.isEmpty) continue;
        final fullName = _asString(gMap['full_name']).trim();
        results.add(
          TopicUser(
            id: -results.length - 1,
            username: groupName,
            name: fullName.isEmpty
                ? LinDoNetworkCopy.GROUP_MENTION_DISPLAY.format(groupName)
                : fullName,
          ),
        );
      }
    }

    return results;
  }

  // ---------------------------------------------------------------------------
  // Search
  // ---------------------------------------------------------------------------

  Future<SearchPageResult> searchTopicsAndPosts(
    String query, {
    SearchSortOrder sort = SearchSortOrder.RELEVANCE,
    int page = 1,
    int? categoryId,
    String? tag,
  }) async {
    final trimmed = query.trim();
    if (trimmed.isEmpty) {
      return const SearchPageResult(items: <CommunitySearchResult>[]);
    }

    final queryParts = <String>[trimmed];
    if (categoryId != null && categoryId > 0) {
      final slug = _resolveCategorySlug(categoryId, null);
      if (slug.isNotEmpty) {
        queryParts.add('#$slug');
      }
    }
    if (tag != null && tag.trim().isNotEmpty) {
      queryParts.add('tags:${tag.trim()}');
    }
    final orderPart = sort.wireOrder == null ? '' : ' order:${sort.wireOrder}';
    final fullQuery = '${queryParts.join(" ")}$orderPart'.trim();
    final cleanPage = math.max(1, page);

    final root = await _requestJsonMap(
      method: 'GET',
      pathOrUrl:
          '/search.json?q=${Uri.encodeQueryComponent(fullQuery)}&page=$cleanPage',
    );

    final topicsById = <int, Map<String, dynamic>>{};
    final rawTopics = root['topics'];
    if (rawTopics is List) {
      for (final t in rawTopics) {
        final tMap = _asMap(t);
        if (tMap == null) continue;
        final id = _asInt(tMap['id']);
        if (id > 0) topicsById[id] = tMap;
      }
    }

    final items = <CommunitySearchResult>[];
    final rawPosts = root['posts'];
    if (rawPosts is List) {
      for (final p in rawPosts) {
        final pMap = _asMap(p);
        if (pMap == null) continue;
        final topicId = _asInt(pMap['topic_id']);
        if (topicId <= 0) continue;
        final tMap = topicsById[topicId];
        final catId = _asInt(
          tMap?['category_id'],
          fallback: _asInt(pMap['category_id'], fallback: 4),
        );
        final catMeta = _findCategoryMeta(catId);
        final title = decodeDiscourseHtmlSnippet(
          _nonBlankOr(
            _asString(tMap?['fancy_title']),
            _nonBlankOr(
              _asString(tMap?['title']),
              LinDoCommonCopy.TOPIC_NUMBER_FALLBACK.format(topicId),
            ),
          ),
        );

        items.add(
          CommunitySearchResult(
            topicId: topicId,
            postNumber: _asInt(pMap['post_number'], fallback: 1),
            title: title,
            blurb: decodeDiscourseHtmlSnippet(_asString(pMap['blurb'])),
            username: _asString(pMap['username']),
            avatarTemplate: _asString(pMap['avatar_template']),
            createdAtText: formatRelativeTime(_asString(pMap['created_at'])),
            categoryName: catMeta.name,
            categoryColorHex: catMeta.colorHex,
            likeCount: _asInt(pMap['like_count']),
            postsCount: _asInt(tMap?['posts_count'], fallback: 1),
          ),
        );
      }
    } else if (topicsById.isNotEmpty) {
      for (final tMap in topicsById.values) {
        final topicId = _asInt(tMap['id']);
        final catMeta = _findCategoryMeta(_asInt(tMap['category_id'], fallback: 4));
        items.add(
          CommunitySearchResult(
            topicId: topicId,
            title: decodeDiscourseHtmlSnippet(
              _nonBlankOr(_asString(tMap['fancy_title']), _asString(tMap['title'])),
            ),
            createdAtText: formatRelativeTime(_asString(tMap['created_at'])),
            categoryName: catMeta.name,
            categoryColorHex: catMeta.colorHex,
            likeCount: _asInt(tMap['like_count']),
            postsCount: _asInt(tMap['posts_count'], fallback: 1),
          ),
        );
      }
    }

    final grouped = _asMap(root['grouped_search_result']);
    final hasMore = grouped?['more_full_page_results'] == true ||
        grouped?['more_posts'] == true;
    final postIds = grouped?['post_ids'];
    final totalEstimate = postIds is List
        ? math.max(items.length, postIds.length)
        : items.length;

    return SearchPageResult(
      items: items,
      hasMore: hasMore,
      totalEstimate: totalEstimate,
    );
  }

  // ---------------------------------------------------------------------------
  // Notifications, Private Messages & Discourse Chat
  // ---------------------------------------------------------------------------

  Future<List<UserNotificationItem>> fetchNotifications({
    int offset = 0,
    bool unreadOnly = false,
  }) async {
    final cleanOffset = math.max(0, offset);
    final unreadQuery = unreadOnly ? '&filter=unread' : '';
    final root = await _requestJsonMap(
      method: 'GET',
      pathOrUrl:
          '/notifications.json?offset=$cleanOffset&limit=60&silent=true$unreadQuery',
    );
    final items = _parseNotificationItems(root);

    if (cleanOffset == 0 && !unreadOnly && isAuthorized) {
      final unreadNonPm = items
          .where(
            (n) =>
                !n.read &&
                n.kind != DiscourseNotificationKind.PRIVATE_MESSAGE &&
                n.kind != DiscourseNotificationKind.INVITED_TO_PRIVATE_MESSAGE,
          )
          .length;
      _unreadNotificationCount = items.length < 60
          ? unreadNonPm
          : math.max(_unreadNotificationCount, unreadNonPm);
      await LinDoPlatformBridge.instance.updateTrayState(
        unreadCount: totalUnreadBadgeCount,
        username: _currentUser?.username,
      );
      notifyListeners();
    }

    return items;
  }

  Future<void> markAllNotificationsRead() async {
    await _sendRequest(
      method: 'PUT',
      pathOrUrl: '/notifications/mark-read.json',
    );
    _unreadNotificationCount = 0;
    await LinDoPlatformBridge.instance.updateTrayState(
      unreadCount: totalUnreadBadgeCount,
      username: _currentUser?.username,
    );
    notifyListeners();
  }

  Future<void> markNotificationRead(int notificationId) async {
    if (notificationId <= 0) return;
    await _sendRequest(
      method: 'PUT',
      pathOrUrl: '/notifications/mark-read.json',
      formFields: <String, String>{'id': '$notificationId'},
    );
    if (_unreadNotificationCount > 0) {
      _unreadNotificationCount--;
      await LinDoPlatformBridge.instance.updateTrayState(
        unreadCount: totalUnreadBadgeCount,
        username: _currentUser?.username,
      );
      notifyListeners();
    }
  }

  Future<List<TopicSummary>> fetchPrivateMessages({
    String? username,
    PrivateMessagesViewMode mode = PrivateMessagesViewMode.INBOX,
    int page = 0,
  }) async {
    final targetUser = (username ?? _currentUser?.username ?? '').trim();
    if (targetUser.isEmpty) {
      throw LinuxDoException(
        LinDoNetworkCopy.GUEST_CONTENT_LOGIN_REQUIRED.text,
        statusCode: 401,
      );
    }

    final encodedUser = Uri.encodeComponent(targetUser);
    final String routePrefix;
    switch (mode) {
      case PrivateMessagesViewMode.INBOX:
      case PrivateMessagesViewMode.COMPOSER:
        routePrefix = '/topics/private-messages/$encodedUser.json';
        break;
      case PrivateMessagesViewMode.SENT:
        routePrefix = '/topics/private-messages-sent/$encodedUser.json';
        break;
      case PrivateMessagesViewMode.ARCHIVE:
        routePrefix = '/topics/private-messages-archive/$encodedUser.json';
        break;
    }

    final cleanPage = math.max(0, page);
    final path = cleanPage > 0 ? '$routePrefix?page=$cleanPage' : routePrefix;
    final root = await _requestJsonMap(method: 'GET', pathOrUrl: path);
    final parsedPage = _parseTopicListFeedPage(root, currentPage: cleanPage);
    return <TopicSummary>[...parsedPage.pinnedTopics, ...parsedPage.topics];
  }

  Future<List<ChatChannelItem>> fetchChatChannels() async {
    Map<String, dynamic> root;
    try {
      root = await _requestJsonMap(
        method: 'GET',
        pathOrUrl: '/chat/api/me/channels',
      );
    } on LinuxDoException catch (err) {
      if (err.statusCode != 404) rethrow;
      root = await _requestJsonMap(
        method: 'GET',
        pathOrUrl: '/chat/api/channels',
      );
    }

    final trackingRoot = _asMap(root['tracking']);
    final channelTracking = _asMap(trackingRoot?['channel_tracking']);

    final channels = <ChatChannelItem>[];

    void parseChannelList(List? list, {required bool isDmFallback}) {
      if (list == null) return;
      for (final item in list) {
        final ch = _asMap(item);
        if (ch == null) continue;
        final id = _asInt(ch['id']);
        if (id <= 0) continue;

        final chatableType = _asString(ch['chatable_type']).toLowerCase();
        final isDm = isDmFallback || chatableType.contains('directmessage');
        var title = _asString(ch['title']).trim();
        String? dmAvatar;
        if (isDm) {
          final chatable = _asMap(ch['chatable']);
          final users = chatable?['users'] as List?;
          if (users != null && users.isNotEmpty) {
            final otherNames = <String>[];
            for (final u in users) {
              final uMap = _asMap(u);
              if (uMap == null) continue;
              final uname = _asString(uMap['username']);
              if (uname.toLowerCase() != (_currentUser?.username.toLowerCase() ?? '')) {
                otherNames.add(_nonBlankOr(_asString(uMap['name']), uname));
                dmAvatar ??= _nullIfBlank(_asString(uMap['avatar_template']));
              }
            }
            if (title.isEmpty && otherNames.isNotEmpty) {
              title = otherNames.join(', ');
            }
          }
        }
        if (title.isEmpty) {
          title = '#channel-$id';
        }

        final currentMembership = _asMap(ch['current_user_membership']);
        final trackingObj = _asMap(channelTracking?['$id']);
        final unreadCount = _asInt(
          trackingObj?['unread_count'],
          fallback: _asInt(currentMembership?['unread_count']),
        );

        final lastMessage = _asMap(ch['last_message']);
        final excerpt = _nullIfBlank(
          decodeDiscourseHtmlSnippet(
            _nonBlankOr(
              _asString(lastMessage?['excerpt']),
              _asString(lastMessage?['message']),
            ),
          ),
        );
        final lastTime = formatRelativeTime(_asString(lastMessage?['created_at']));

        channels.add(
          ChatChannelItem(
            id: id,
            title: title,
            description: decodeDiscourseHtmlSnippet(_asString(ch['description'])),
            slug: _asString(ch['slug']),
            unreadCount: unreadCount,
            isDirectMessage: isDm,
            lastMessageExcerpt: excerpt,
            lastMessageTimeText: lastTime,
            avatarTemplate: dmAvatar,
          ),
        );
      }
    }

    parseChannelList(root['public_channels'] as List?, isDmFallback: false);
    parseChannelList(root['direct_message_channels'] as List?, isDmFallback: true);
    parseChannelList(root['channels'] as List?, isDmFallback: false);
    return channels;
  }

  Future<List<ChatMessageItem>> fetchChatMessages(
    int channelId, {
    int pageSize = 50,
    int? targetMessageId,
  }) async {
    final targetQuery =
        targetMessageId != null ? '&target_message_id=$targetMessageId' : '';
    final root = await _requestJsonMap(
      method: 'GET',
      pathOrUrl:
          '/chat/api/channels/$channelId/messages?page_size=$pageSize$targetQuery',
    );
    final rawMessages = (root['messages'] as List?) ?? (root['chat_messages'] as List?);
    if (rawMessages == null) return const <ChatMessageItem>[];

    final parsed = <ChatMessageItem>[];
    for (final item in rawMessages) {
      final m = _asMap(item);
      if (m == null) continue;
      final msg = _parseChatMessage(m, fallbackChannelId: channelId);
      if (msg != null) parsed.add(msg);
    }
    return parsed;
  }

  Future<ChatMessageItem?> sendChatMessage(
    int channelId,
    String message, {
    int? inReplyToId,
  }) async {
    final trimmed = message.trim();
    if (trimmed.isEmpty) return null;

    final fields = <String, String>{
      'message': trimmed,
      if (inReplyToId != null && inReplyToId > 0)
        'in_reply_to_id': '$inReplyToId',
    };

    Map<String, dynamic> root;
    try {
      root = await _requestJsonMap(
        method: 'POST',
        pathOrUrl: '/chat/$channelId.json',
        formFields: fields,
      );
    } on LinuxDoException catch (err) {
      if (err.statusCode != 404) rethrow;
      root = await _requestJsonMap(
        method: 'POST',
        pathOrUrl: '/chat/api/channels/$channelId/messages',
        formFields: fields,
      );
    }

    final msgObj = _asMap(root['message']) ?? _asMap(root['chat_message']);
    if (msgObj != null) {
      return _parseChatMessage(msgObj, fallbackChannelId: channelId);
    }
    final msgId = _asInt(
      root['message_id'],
      fallback: DateTime.now().millisecondsSinceEpoch,
    );
    return ChatMessageItem(
      id: msgId,
      channelId: channelId,
      userId: _currentUser?.userId ?? 0,
      username: _currentUser?.username ?? '',
      displayName: _currentUser?.displayName ?? '',
      avatarTemplate: _currentUser?.avatarTemplate ?? '',
      message: trimmed,
      cookedHtml: decodeDiscourseHtmlSnippet(trimmed),
      createdAtText: LinDoCommonCopy.JUST_NOW.text,
    );
  }

  // ---------------------------------------------------------------------------
  // User Profile, Follow Graph, Connect Stats, Badges, Bookmarks, Actions
  // ---------------------------------------------------------------------------

  Future<UserFullProfileSummary> fetchUserSummary(String username) async {
    final cleanUser = username.trim().replaceFirst(RegExp(r'^@+'), '');
    if (cleanUser.isEmpty) {
      throw LinuxDoException(
        LinDoNetworkCopy.TOPIC_UNAVAILABLE.text,
        statusCode: 404,
      );
    }
    final encoded = Uri.encodeComponent(cleanUser);

    final results = await Future.wait<Map<String, dynamic>?>(<Future<Map<String, dynamic>?>>[
      _requestJsonMap(method: 'GET', pathOrUrl: '/u/$encoded.json'),
      _requestJsonMap(method: 'GET', pathOrUrl: '/u/$encoded/summary.json')
          .then<Map<String, dynamic>?>((v) => v)
          .catchError((Object _) => null),
    ]);

    final userRoot = results[0] ?? const <String, dynamic>{};
    final summaryRoot = results[1] ?? const <String, dynamic>{};

    final u = _asMap(userRoot['user']) ?? userRoot;
    final us = _asMap(summaryRoot['user_summary']);

    final badgeDefs = <int, GrantedBadge>{
      ..._parseBadgeDefinitionsMap(userRoot['badges']),
      ..._parseBadgeDefinitionsMap(summaryRoot['badges']),
    };
    final badges = _parseGrantedBadgesFromRoot(
      userRoot.containsKey('user_badges') ? userRoot : summaryRoot,
      badgeDefs,
    );

    final statusMap = _asMap(u['status']);
    final statusDesc = _asString(statusMap?['description']).trim();
    final statusEmoji = _asString(statusMap?['emoji']).trim();
    final statusText = statusDesc.isNotEmpty
        ? (statusEmoji.isNotEmpty ? ':$statusEmoji: $statusDesc' : statusDesc)
        : '';

    final resolvedUsername = _nonBlankOr(_asString(u['username']), cleanUser);
    return UserFullProfileSummary(
      id: _asInt(u['id']),
      username: resolvedUsername,
      displayName: _nonBlankOr(_asString(u['name']), resolvedUsername),
      avatarTemplate: _asString(u['avatar_template']),
      animatedAvatar: _nullIfBlank(_asString(u['animated_avatar'])),
      trustLevel: _asInt(u['trust_level'], fallback: 1),
      title: _asString(u['title']),
      bioExcerpt: decodeDiscourseHtmlSnippet(
        _nonBlankOr(_asString(u['bio_excerpt']), _asString(u['bio_cooked'])),
      ),
      bioRaw: _asString(u['bio_raw']),
      primaryGroupName: _nullIfBlank(_asString(u['primary_group_name'])),
      admin: u['admin'] == true,
      moderator: u['moderator'] == true,
      createdAtText: _formatDateShort(_asString(u['created_at'])),
      daysVisited: _asInt(us?['days_visited']),
      timeReadSeconds: _asInt(us?['time_read']),
      recentTimeReadSeconds: _asInt(us?['recent_time_read']),
      topicsEntered: _asInt(us?['topics_entered']),
      postsReadCount: _asInt(us?['posts_read_count']),
      likesGiven: _asInt(us?['likes_given']),
      likesReceived: _asInt(us?['likes_received']),
      topicCount: _asInt(us?['topic_count']),
      postCount: _asInt(us?['post_count']),
      badgeCount: _asInt(
        u['badge_count'],
        fallback: _asInt(us?['badge_count'], fallback: badges.length),
      ),
      unreadNotifications: _asInt(u['unread_notifications']),
      lastSeenText: formatRelativeTime(_asString(u['last_seen_at'])),
      location: _asString(u['location']),
      websiteName: _asString(u['website_name']),
      websiteUrl: _asString(u['website']),
      statusText: statusText,
      solvedCount: _asInt(
        us?['solved_count'],
        fallback: _asInt(u['accepted_answers']),
      ),
      badges: badges,
      flair: _parseFlairInfo(u),
      canFollow: u['can_follow_user'] == true || u['can_follow'] == true,
      isFollowing: u['is_followed'] == true || u['following'] == true,
      followingCount: _asInt(
        u['total_following'],
        fallback: _asInt(u['following_count']),
      ),
      followersCount: _asInt(
        u['total_followers'],
        fallback: _asInt(u['followers_count']),
      ),
    );
  }

  Future<void> updateUserProfile({
    String? username,
    String? displayName,
    String? bioRaw,
    String? title,
    String? location,
    String? website,
  }) async {
    final targetUser = (username ?? _currentUser?.username ?? '').trim();
    if (targetUser.isEmpty) {
      throw LinuxDoException(
        LinDoNetworkCopy.GUEST_CONTENT_LOGIN_REQUIRED.text,
        statusCode: 401,
      );
    }

    final fields = <String, String>{};
    if (displayName != null) fields['name'] = displayName.trim();
    if (bioRaw != null) fields['bio_raw'] = bioRaw;
    if (title != null) fields['title'] = title.trim();
    if (location != null) fields['location'] = location.trim();
    if (website != null) fields['website'] = website.trim();

    await _sendRequest(
      method: 'PUT',
      pathOrUrl: '/u/${Uri.encodeComponent(targetUser)}.json',
      formFields: fields,
    );

    if (_currentUser != null &&
        _currentUser!.username.toLowerCase() == targetUser.toLowerCase()) {
      await refreshCurrentSessionAndUnread(silent: true);
    }
  }

  Future<bool> toggleFollowUser(
    String username, {
    bool follow = true,
  }) async {
    final cleanUser = username.trim().replaceFirst(RegExp(r'^@+'), '');
    if (cleanUser.isEmpty) return false;
    await _sendRequest(
      method: follow ? 'PUT' : 'DELETE',
      pathOrUrl: '/follow/${Uri.encodeComponent(cleanUser)}.json',
    );
    return follow;
  }

  Future<List<TopicUser>> fetchFollowList(
    String username, {
    bool followers = false,
  }) async {
    final cleanUser = username.trim().replaceFirst(RegExp(r'^@+'), '');
    if (cleanUser.isEmpty) return const <TopicUser>[];
    final kind = followers ? 'followers' : 'following';
    final root = await _requestJsonMap(
      method: 'GET',
      pathOrUrl: '/u/${Uri.encodeComponent(cleanUser)}/follow/$kind.json',
    );

    final rawList = (root['users'] as List?) ??
        (root[kind] as List?) ??
        (root['follow'] as List?);
    if (rawList == null) return const <TopicUser>[];

    final users = <TopicUser>[];
    for (final item in rawList) {
      final map = _asMap(item);
      if (map == null) continue;
      final parsed = _parseTopicUser(map);
      if (parsed.username.isNotEmpty) {
        users.add(parsed);
      }
    }
    return users;
  }

  Future<ConnectOverviewModel> fetchConnectOverview() async {
    try {
      final res = await _sendRequest(
        method: 'GET',
        pathOrUrl: kLinuxDoConnectUrl,
        extraHeaders: const <String, String>{
          'Accept': 'text/html,application/xhtml+xml,*/*;q=0.8',
        },
      );
      if (res.status >= 200 && res.status < 300 && res.body.trim().isNotEmpty) {
        final parsed = _parseConnectHtml(res.body);
        if (parsed != null) return parsed;
      }
    } on LinuxDoException catch (err) {
      if (err.isSiteVerification) rethrow;
    } catch (_) {}

    final username = _currentUser?.username.trim() ?? '';
    if (username.isNotEmpty) {
      final summary = await fetchUserSummary(username);
      return _buildFallbackConnectOverview(summary);
    }

    throw LinuxDoException(
      LinDoNetworkCopy.GUEST_CONTENT_LOGIN_REQUIRED.text,
      statusCode: 401,
    );
  }

  Future<List<GrantedBadge>> fetchBadgesOverview(String username) async {
    final cleanUser = username.trim().replaceFirst(RegExp(r'^@+'), '');

    final futures = <Future<Map<String, dynamic>?>>[
      _requestJsonMap(method: 'GET', pathOrUrl: '/badges.json')
          .then<Map<String, dynamic>?>((v) => v)
          .catchError((Object _) => null),
      if (cleanUser.isNotEmpty)
        _requestJsonMap(
          method: 'GET',
          pathOrUrl: '/user-badges/${Uri.encodeComponent(cleanUser)}.json?grouped=true',
        ).then<Map<String, dynamic>?>((v) => v).catchError((Object _) => null),
    ];

    final results = await Future.wait(futures);
    final siteBadgesRoot = results[0] ?? const <String, dynamic>{};
    final userBadgesRoot =
        results.length > 1 ? (results[1] ?? const <String, dynamic>{}) : const <String, dynamic>{};

    final groupingNames = <int, String>{};
    final rawGroupings = siteBadgesRoot['badge_groupings'];
    if (rawGroupings is List) {
      for (final g in rawGroupings) {
        final gMap = _asMap(g);
        if (gMap == null) continue;
        final gId = _asInt(gMap['id']);
        final gName = _asString(gMap['name']).trim();
        if (gId > 0 && gName.isNotEmpty) {
          groupingNames[gId] = gName;
        }
      }
    }

    final catalogById = <int, GrantedBadge>{
      ..._parseBadgeDefinitionsMap(siteBadgesRoot['badges'], groupingNames: groupingNames),
      ..._parseBadgeDefinitionsMap(userBadgesRoot['badges'], groupingNames: groupingNames),
    };

    final earnedBadges = _parseGrantedBadgesFromRoot(userBadgesRoot, catalogById);
    final earnedById = <int, GrantedBadge>{
      for (final b in earnedBadges) b.id: b,
    };

    final merged = <GrantedBadge>[];
    for (final b in earnedBadges) {
      merged.add(b.copyWith(isEarned: true));
    }
    for (final entry in catalogById.entries) {
      if (!earnedById.containsKey(entry.key)) {
        merged.add(entry.value.copyWith(isEarned: false, count: 0));
      }
    }
    return merged;
  }

  Future<bool> updateUserBadgeTitle({
    String? username,
    int? userBadgeId,
    String title = '',
  }) async {
    final targetUser = (username ?? _currentUser?.username ?? '').trim();
    if (targetUser.isEmpty) {
      throw LinuxDoException(
        LinDoNetworkCopy.GUEST_CONTENT_LOGIN_REQUIRED.text,
        statusCode: 401,
      );
    }

    final encodedUser = Uri.encodeComponent(targetUser);
    if (userBadgeId != null) {
      await _sendRequest(
        method: 'PUT',
        pathOrUrl: '/u/$encodedUser/preferences/badge_title.json',
        formFields: <String, String>{
          'user_badge_id': userBadgeId > 0 ? '$userBadgeId' : '',
        },
      );
    } else {
      await _sendRequest(
        method: 'PUT',
        pathOrUrl: '/u/$encodedUser.json',
        formFields: <String, String>{'title': title.trim()},
      );
    }

    if (_currentUser != null &&
        _currentUser!.username.toLowerCase() == targetUser.toLowerCase()) {
      await refreshCurrentSessionAndUnread(silent: true);
    }
    return true;
  }

  Future<List<UserActivityItem>> fetchUserBookmarks({
    String? username,
    int page = 0,
  }) async {
    final targetUser = (username ?? _currentUser?.username ?? '').trim();
    if (targetUser.isEmpty) {
      throw LinuxDoException(
        LinDoNetworkCopy.GUEST_CONTENT_LOGIN_REQUIRED.text,
        statusCode: 401,
      );
    }

    final cleanPage = math.max(0, page);
    final pageQuery = cleanPage > 0 ? '?page=$cleanPage' : '';
    final root = await _requestJsonMap(
      method: 'GET',
      pathOrUrl: '/u/${Uri.encodeComponent(targetUser)}/bookmarks.json$pageQuery',
    );

    final listObj = _asMap(root['user_bookmark_list']) ?? root;
    final rawBookmarks = listObj['bookmarks'];
    if (rawBookmarks is! List) return const <UserActivityItem>[];

    final items = <UserActivityItem>[];
    for (final elem in rawBookmarks) {
      final b = _asMap(elem);
      if (b == null) continue;
      final id = _asInt(b['id']);
      final topicId = _asInt(b['topic_id']);
      if (id <= 0 || topicId <= 0) continue;

      final postNumber = _asInt(
        b['linked_post_number'],
        fallback: _asInt(b['post_number'], fallback: 1),
      );
      final title = _nonBlankOr(
        _asString(b['title']).trim(),
        _nonBlankOr(
          decodeDiscourseHtmlSnippet(_asString(b['fancy_title'])),
          LinDoNetworkCopy.BOOKMARK_TOPIC_FALLBACK.format(topicId),
        ),
      );
      final catMeta = _findCategoryMeta(_asInt(b['category_id'], fallback: 4));

      items.add(
        UserActivityItem(
          id: id,
          topicId: topicId,
          postNumber: postNumber,
          title: title,
          excerpt: decodeDiscourseHtmlSnippet(_asString(b['excerpt'])),
          createdAtText: formatRelativeTime(_asString(b['created_at'])),
          categoryName: catMeta.name,
          actionType: DiscourseUserActionKind.BOOKMARK.code,
          bookmarkId: id,
        ),
      );
    }
    return items;
  }

  Future<List<UserActivityItem>> fetchUserActions(
    String username, {
    DiscourseUserActionKind? kind,
    String? filter,
    int offset = 0,
  }) async {
    final cleanUser = username.trim().replaceFirst(RegExp(r'^@+'), '');
    if (cleanUser.isEmpty) return const <UserActivityItem>[];

    final cleanOffset = math.max(0, offset);
    final resolvedFilter = filter ?? (kind != null ? '${kind.code}' : null);
    final filterQuery = (resolvedFilter != null && resolvedFilter.isNotEmpty)
        ? '&filter=${Uri.encodeQueryComponent(resolvedFilter)}'
        : '';

    final root = await _requestJsonMap(
      method: 'GET',
      pathOrUrl:
          '/user_actions.json?offset=$cleanOffset&username=${Uri.encodeQueryComponent(cleanUser)}$filterQuery',
    );
    final rawActions = root['user_actions'];
    if (rawActions is! List) return const <UserActivityItem>[];

    final items = <UserActivityItem>[];
    for (final elem in rawActions) {
      final a = _asMap(elem);
      if (a == null) continue;
      final topicId = _asInt(a['topic_id']);
      if (topicId <= 0) continue;
      final postId = _asInt(a['post_id'], fallback: topicId * 10);
      final catMeta = _findCategoryMeta(_asInt(a['category_id'], fallback: 4));
      final title = _nonBlankOr(
        decodeDiscourseHtmlSnippet(_asString(a['title'])),
        LinDoCommonCopy.TOPIC_NUMBER_FALLBACK.format(topicId),
      );

      items.add(
        UserActivityItem(
          id: postId,
          topicId: topicId,
          postNumber: _asInt(a['post_number'], fallback: 1),
          title: title,
          excerpt: decodeDiscourseHtmlSnippet(_asString(a['excerpt'])),
          createdAtText: formatRelativeTime(_asString(a['created_at'])),
          categoryName: catMeta.name,
          actionType: _asInt(
            a['action_type'],
            fallback: DiscourseUserActionKind.REPLY.code,
          ),
        ),
      );
    }
    return items;
  }

  // ---------------------------------------------------------------------------
  // Cloud Drafts, Community Leaderboard & Site About
  // ---------------------------------------------------------------------------

  Future<List<CloudDraftItem>> fetchCloudDrafts() async {
    final mergedByKey = <String, CloudDraftItem>{
      for (final local in store.localDrafts) local.draftKey: local,
    };

    if (!isAuthorized) {
      return mergedByKey.values.toList(growable: false);
    }

    final root = await _requestJsonMap(method: 'GET', pathOrUrl: '/drafts.json');
    final rawDrafts = root['drafts'];
    if (rawDrafts is List) {
      for (final elem in rawDrafts) {
        final obj = _asMap(elem);
        if (obj == null) continue;
        final draftKey = _asString(obj['draft_key']).trim();
        if (draftKey.isEmpty) continue;
        final seq = _asInt(obj['sequence']);

        Map<String, dynamic>? dObj;
        final dataField = obj['data'];
        if (dataField is Map) {
          dObj = _asMap(dataField);
        } else if (dataField is String && dataField.trim().startsWith('{')) {
          try {
            dObj = _asMap(jsonDecode(dataField));
          } catch (_) {}
        }

        final recipientsRaw = dObj?['recipients'];
        final recipients = <String>[];
        if (recipientsRaw is List) {
          for (final r in recipientsRaw) {
            final s = r?.toString().trim() ?? '';
            if (s.isNotEmpty) recipients.add(s);
          }
        } else if (recipientsRaw is String && recipientsRaw.trim().isNotEmpty) {
          recipients.addAll(
            recipientsRaw
                .split(',')
                .map((s) => s.trim())
                .where((s) => s.isNotEmpty),
          );
        }

        final actionStr = _nonBlankOr(
          _asString(dObj?['action']),
          CloudDraftKind.resolve('', draftKey).wireAction,
        );

        mergedByKey[draftKey] = CloudDraftItem(
          draftKey: draftKey,
          sequence: seq,
          action: actionStr,
          topicId: _asNullableInt(dObj?['topicId']) ?? _asNullableInt(obj['topic_id']),
          categoryId: _asNullableInt(dObj?['categoryId']) ??
              _asNullableInt(obj['category_id']),
          replyToPostNumber: _asNullableInt(dObj?['replyToPostNumber']),
          editPostId: _asNullableInt(dObj?['postId']),
          title: _nonBlankOr(_asString(dObj?['title']), _asString(obj['title'])),
          raw: _asString(dObj?['reply']),
          tags: _parseTagStrings(dObj?['tags'] as List?),
          recipients: recipients,
          updatedAtText: formatRelativeTime(_asString(obj['created_at'])),
          isLocalOnly: false,
        );
      }
    }

    return mergedByKey.values.toList(growable: false);
  }

  Future<int> saveCloudDraft({
    required String draftKey,
    int sequence = 0,
    required String raw,
    String title = '',
    int? categoryId,
    List<String> tags = const [],
    int? topicId,
    int? replyToPostNumber,
    int? editPostId,
    List<String> recipients = const [],
    String? action,
  }) async {
    final resolvedAction = action ?? CloudDraftKind.resolve('', draftKey).wireAction;

    await store.saveLocalDraft(
      CloudDraftItem(
        draftKey: draftKey,
        sequence: sequence,
        action: resolvedAction,
        topicId: topicId,
        categoryId: categoryId,
        replyToPostNumber: replyToPostNumber,
        editPostId: editPostId,
        title: title,
        raw: raw,
        tags: tags,
        recipients: recipients,
        updatedAtText: formatRelativeTime(DateTime.now().toUtc().toIso8601String()),
        isLocalOnly: !isAuthorized,
      ),
    );

    if (!isAuthorized) return sequence;

    final dataPayload = <String, dynamic>{
      'reply': raw,
      'action': resolvedAction,
      'tags': tags,
    };
    if (title.trim().isNotEmpty) dataPayload['title'] = title.trim();
    if (categoryId != null) dataPayload['categoryId'] = categoryId;
    if (topicId != null) dataPayload['topicId'] = topicId;
    if (editPostId != null) dataPayload['postId'] = editPostId;
    if (replyToPostNumber != null) {
      dataPayload['replyToPostNumber'] = replyToPostNumber;
    }
    if (recipients.isNotEmpty) dataPayload['recipients'] = recipients.join(',');

    Future<Map<String, dynamic>> postWithSequence(int seq) => _requestJsonMap(
          method: 'POST',
          pathOrUrl: '/drafts.json',
          formFields: <String, String>{
            'draft_key': draftKey,
            'sequence': '$seq',
            'data': jsonEncode(dataPayload),
          },
        );

    Map<String, dynamic> root;
    try {
      root = await postWithSequence(sequence);
    } on LinuxDoException catch (err) {
      if (err.statusCode != 409) rethrow;
      final latestLookup = await _requestJsonMap(
        method: 'GET',
        pathOrUrl: '/drafts/${Uri.encodeComponent(draftKey)}.json',
      );
      final latestSeq = _asInt(
        latestLookup['draft_sequence'],
        fallback: _asInt(latestLookup['sequence'], fallback: sequence),
      );
      root = await postWithSequence(latestSeq);
    }

    final newSequence = _asInt(root['draft_sequence'], fallback: sequence);
    await store.deleteLocalDraft(draftKey);
    return newSequence;
  }

  Future<void> deleteCloudDraft(String draftKey, {int sequence = 0}) async {
    await store.deleteLocalDraft(draftKey);
    if (!isAuthorized) return;
    await _sendRequest(
      method: 'DELETE',
      pathOrUrl: '/drafts/${Uri.encodeComponent(draftKey)}.json?sequence=$sequence',
    );
  }

  Future<List<LeaderboardEntry>> fetchLeaderboard({
    LeaderboardPeriod period = LeaderboardPeriod.WEEKLY,
    int page = 0,
  }) async {
    final cleanPage = math.max(0, page);
    final root = await _requestJsonMap(
      method: 'GET',
      pathOrUrl:
          '/directory_items.json?period=${period.wirePeriod}&order=likes_received&page=$cleanPage',
    );

    final rawList = root['directory_items'];
    if (rawList is! List) return const <LeaderboardEntry>[];

    final entries = <LeaderboardEntry>[];
    for (var i = 0; i < rawList.length; i++) {
      final obj = _asMap(rawList[i]);
      if (obj == null) continue;
      final u = _asMap(obj['user']);
      if (u == null) continue;
      final username = _asString(u['username']).trim();
      if (username.isEmpty) continue;

      final likesReceived = _asInt(obj['likes_received']);
      final postCount = _asInt(obj['post_count']);
      final topicCount = _asInt(obj['topic_count']);
      final cheers = _asNullableInt(obj['cheers']) ??
          _asNullableInt(obj['gamification_score']) ??
          (likesReceived * 5 + postCount * 2 + topicCount * 5);

      entries.add(
        LeaderboardEntry(
          rank: cleanPage * 50 + i + 1,
          userId: _asInt(u['id'], fallback: _asInt(obj['id'], fallback: i + 1)),
          username: username,
          displayName: _nonBlankOr(_asString(u['name']).trim(), username),
          avatarTemplate: _asString(u['avatar_template']),
          cheersScore: cheers,
          likesReceived: likesReceived,
          postCount: postCount,
          topicCount: topicCount,
        ),
      );
    }
    return entries;
  }

  Future<SiteAboutStats> fetchSiteAbout() async {
    final root = await _requestJsonMap(method: 'GET', pathOrUrl: '/about.json');
    final about = _asMap(root['about']) ?? root;
    final stats = _asMap(about['stats']);

    final usersById = <int, TopicUser>{};
    final rawUsers = root['users'];
    if (rawUsers is List) {
      for (final u in rawUsers) {
        final uMap = _asMap(u);
        if (uMap == null) continue;
        final parsed = _parseTopicUser(uMap);
        if (parsed.id > 0 && parsed.username.isNotEmpty) {
          usersById[parsed.id] = parsed;
        }
      }
    }

    List<TopicUser> resolveTeamList(String objListKey, String idListKey) {
      final out = <TopicUser>[];
      final seen = <String>{};
      final rawObjs = about[objListKey];
      if (rawObjs is List) {
        for (final item in rawObjs) {
          final map = _asMap(item);
          if (map == null) continue;
          final u = _parseTopicUser(map);
          if (u.username.isNotEmpty && seen.add(u.username.toLowerCase())) {
            out.add(u);
          }
        }
      }
      if (out.isEmpty) {
        final rawIds = about[idListKey];
        if (rawIds is List) {
          for (final idItem in rawIds) {
            final id = (idItem as num?)?.toInt();
            final u = id == null ? null : usersById[id];
            if (u != null && seen.add(u.username.toLowerCase())) {
              out.add(u);
            }
          }
        }
      }
      return out;
    }

    return SiteAboutStats(
      title: _nonBlankOr(_asString(about['title']), LinDoCommonCopy.LINUX_DO.text),
      description: decodeDiscourseHtmlSnippet(
        _nonBlankOr(
          _asString(about['description']),
          LinDoCommonCopy.SLOGAN_FULL.text,
        ),
      ),
      totalTopics: _asInt(
        stats?['topics_count'],
        fallback: _asInt(stats?['topic_count']),
      ),
      totalPosts: _asInt(
        stats?['posts_count'],
        fallback: _asInt(stats?['post_count']),
      ),
      totalUsers: _asInt(
        stats?['users_count'],
        fallback: _asInt(stats?['user_count']),
      ),
      active7d: _asInt(stats?['active_users_7_days']),
      active30d: _asInt(stats?['active_users_30_days']),
      totalLikes: _asInt(
        stats?['likes_count'],
        fallback: _asInt(stats?['like_count']),
      ),
      admins: resolveTeamList('admins', 'admin_ids'),
      moderators: resolveTeamList('moderators', 'moderator_ids'),
    );
  }

  // ---------------------------------------------------------------------------
  // Internal HTTP Transport, CSRF Management & Cloudflare Challenge Detection
  // ---------------------------------------------------------------------------

  Future<Map<String, dynamic>> _requestJsonMap({
    required String method,
    required String pathOrUrl,
    Map<String, String>? formFields,
    List<MapEntry<String, String>>? formPairs,
    Map<String, String>? extraHeaders,
    bool allowCsrfRetry = true,
  }) async {
    final res = await _sendRequest(
      method: method,
      pathOrUrl: pathOrUrl,
      formFields: formFields,
      formPairs: formPairs,
      extraHeaders: extraHeaders,
      allowCsrfRetry: allowCsrfRetry,
    );

    if (res.body.trim().isEmpty) {
      return const <String, dynamic>{};
    }

    try {
      final decoded = jsonDecode(res.body);
      final map = _asMap(decoded);
      if (map != null) return map;
      return <String, dynamic>{'data': decoded};
    } catch (_) {
      throw LinuxDoException(
        LinDoNetworkCopy.INVALID_PAYLOAD_RETRY.text,
        statusCode: res.status,
      );
    }
  }

  Future<_RawHttpResponse> _sendRequest({
    required String method,
    required String pathOrUrl,
    Map<String, String>? formFields,
    List<MapEntry<String, String>>? formPairs,
    Map<String, String>? extraHeaders,
    bool allowCsrfRetry = true,
  }) async {
    final upperMethod = method.toUpperCase();
    final isMutation =
        upperMethod == 'POST' || upperMethod == 'PUT' || upperMethod == 'DELETE';
    final fullUrl = _resolveEndpointUrl(pathOrUrl);

    if (isMutation && _csrfToken.isEmpty && !fullUrl.endsWith('/session/csrf.json')) {
      await _ensureCsrfToken(silent: true);
    }

    var res = await _executeSingleRequest(
      method: upperMethod,
      url: fullUrl,
      isMutation: isMutation,
      formFields: formFields,
      formPairs: formPairs,
      extraHeaders: extraHeaders,
    );

    _checkCloudflareChallenge(res);

    if (isMutation &&
        allowCsrfRetry &&
        (res.status == 403 || res.status == 422) &&
        res.body.toUpperCase().contains('CSRF')) {
      LinDoDiagnosticLogger.instance.warn(
        DiagnosticCategory.AUTH,
        'CSRF',
        'Detected BAD CSRF on $upperMethod $fullUrl; refreshing token and retrying once',
      );
      final refreshed = await _ensureCsrfToken(forceRefresh: true, silent: true);
      if (refreshed.isNotEmpty) {
        res = await _executeSingleRequest(
          method: upperMethod,
          url: fullUrl,
          isMutation: true,
          formFields: formFields,
          formPairs: formPairs,
          extraHeaders: extraHeaders,
        );
        _checkCloudflareChallenge(res);
      }
    }

    if (res.status < 200 || res.status >= 300) {
      throw LinuxDoException(
        _extractDiscourseErrorMessage(res.body, res.status),
        statusCode: res.status,
      );
    }

    return res;
  }

  Future<_RawHttpResponse> _executeSingleRequest({
    required String method,
    required String url,
    required bool isMutation,
    Map<String, String>? formFields,
    List<MapEntry<String, String>>? formPairs,
    Map<String, String>? extraHeaders,
  }) async {
    final encodedBody = _encodeFormBody(formFields, formPairs);
    final headers = <String, String>{
      'Accept': 'application/json, text/javascript, */*; q=0.01',
      'X-Requested-With': 'XMLHttpRequest',
      if (encodedBody != null)
        'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
      if (isMutation && _csrfToken.isNotEmpty) 'X-CSRF-Token': _csrfToken,
    };
    if (extraHeaders != null) {
      headers.addAll(extraHeaders);
    }

    final stopwatch = Stopwatch()..start();
    try {
      _RawHttpResponse rawRes;
      if (_useBrowserTransport) {
        final bridgeRes = await browserBridge.executeFetch(
          method: method,
          url: url,
          headers: headers,
          body: encodedBody,
        );
        rawRes = _RawHttpResponse(
          status: bridgeRes.status,
          headers: bridgeRes.headers,
          body: bridgeRes.body,
          transport: 'BrowserBridge',
        );
      } else {
        final req = http.Request(method, Uri.parse(url));
        req.headers.addAll(headers);
        if (_rawCookies.isNotEmpty && !req.headers.containsKey('Cookie')) {
          req.headers['Cookie'] = _rawCookies;
        }
        if (encodedBody != null) {
          req.body = encodedBody;
        }
        final streamed = await _fallbackHttpClient.send(req);
        final body = await streamed.stream.bytesToString();
        _ingestSetCookieFromHeaders(streamed.headers);
        rawRes = _RawHttpResponse(
          status: streamed.statusCode,
          headers: streamed.headers,
          body: body,
          transport: 'HttpClient',
        );
      }
      stopwatch.stop();
      _logHttpOutcome(method, url, rawRes, stopwatch.elapsedMilliseconds);
      return rawRes;
    } catch (err, st) {
      stopwatch.stop();
      LinDoDiagnosticLogger.instance.error(
        DiagnosticCategory.NETWORK,
        'HTTP',
        '$method $url transport failure (${stopwatch.elapsedMilliseconds}ms)',
        error: err,
        stackTrace: st,
      );
      if (err is LinuxDoException) rethrow;
      throw LinuxDoException(LinDoNetworkCopy.CONNECTION_FAILED.text);
    }
  }

  Future<String> _ensureCsrfToken({
    bool forceRefresh = false,
    bool silent = false,
  }) async {
    if (!forceRefresh && _csrfToken.isNotEmpty) {
      return _csrfToken;
    }
    try {
      final res = await _executeSingleRequest(
        method: 'GET',
        url: '$kLinuxDoBaseUrl/session/csrf.json',
        isMutation: false,
      );
      _checkCloudflareChallenge(res);
      if (res.status >= 200 && res.status < 300) {
        final decoded = _asMap(jsonDecode(res.body));
        final token = _asString(decoded?['csrf']).trim();
        if (token.isNotEmpty) {
          _csrfToken = token;
          return _csrfToken;
        }
      }
    } on LinuxDoException {
      if (!silent) rethrow;
    } catch (_) {
      if (!silent) {
        throw LinuxDoException(
          LinDoNetworkCopy.CSRF_TOKEN_EXPIRED.text,
          statusCode: 403,
        );
      }
    }
    return _csrfToken;
  }

  void _checkCloudflareChallenge(_RawHttpResponse res) {
    if (res.status != 403 && res.status != 503 && res.status != 429) {
      return;
    }

    var cfMitigated = '';
    for (final entry in res.headers.entries) {
      if (entry.key.toLowerCase() == 'cf-mitigated') {
        cfMitigated = entry.value.toLowerCase();
        break;
      }
    }

    final lowerBody = res.body.toLowerCase();
    final isChallenge = cfMitigated.contains('challenge') ||
        lowerBody.contains('<title>just a moment...</title>') ||
        lowerBody.contains('<title>请稍候') ||
        lowerBody.contains('cf-browser-verification') ||
        lowerBody.contains('__cf_chl_opt') ||
        lowerBody.contains('/cdn-cgi/challenge-platform/') ||
        lowerBody.contains('attention required! | cloudflare');

    if (isChallenge) {
      _siteVerificationRequired = true;
      LinDoDiagnosticLogger.instance.warn(
        DiagnosticCategory.NETWORK,
        'Cloudflare',
        'Cloudflare challenge detected (status=${res.status}, transport=${res.transport})',
      );
      notifyListeners();
      throw LinuxDoException(
        LinDoNetworkCopy.SITE_VERIFICATION_REQUIRED.text,
        statusCode: res.status,
        isSiteVerification: true,
      );
    }
  }

  void _logHttpOutcome(
    String method,
    String url,
    _RawHttpResponse res,
    int elapsedMs,
  ) {
    if (url.contains('/topics/timings')) return;
    final msg = '$method $url -> ${res.status} (${elapsedMs}ms, ${res.transport})';
    if (res.status >= 200 && res.status < 400) {
      LinDoDiagnosticLogger.instance.info(
        DiagnosticCategory.NETWORK,
        'HTTP',
        msg,
      );
    } else {
      final snippet = res.body.length > 240 ? res.body.substring(0, 240) : res.body;
      LinDoDiagnosticLogger.instance.warn(
        res.status == 401 || res.status == 403
            ? DiagnosticCategory.AUTH
            : DiagnosticCategory.NETWORK,
        'HTTP',
        msg,
        detail: snippet,
      );
    }
  }

  // ---------------------------------------------------------------------------
  // Connect HTML Parser (1:1 Port of KMP ConnectStatsParser)
  // ---------------------------------------------------------------------------

  ConnectOverviewModel? _parseConnectHtml(String html) {
    if (!html.contains('<')) return null;
    final doc = html_parser.parse(html);
    final rows = <ConnectRequirementRow>[];
    final seenLabels = <String>{};

    int periodDays = 100;
    final periodMatch = _connectPeriodDaysRegex.firstMatch(doc.body?.text ?? '');
    if (periodMatch != null) {
      periodDays = int.tryParse(periodMatch.group(1) ?? '') ?? 100;
    }

    double? readCssNum(RegExp re, String style) {
      final m = re.firstMatch(style);
      return m == null ? null : double.tryParse(m.group(1) ?? '');
    }

    String fmtNum(double v) =>
        v == v.roundToDouble() ? '${v.toInt()}' : v.toString();

    // 1. Modern `.tl3-ring` circular progress items
    for (final ring in doc.querySelectorAll('.tl3-ring')) {
      final labelEl = ring.parent?.querySelector('.tl3-ring-label') ??
          ring.querySelector('.tl3-ring-label');
      final label = labelEl?.text.trim() ?? '';
      if (label.isEmpty || !seenLabels.add(label)) continue;
      final style = ring.attributes['style'] ?? '';
      final valNum = readCssNum(_connectCssVarValRegex, style);
      final maxNum = readCssNum(_connectCssVarMaxRegex, style);
      if (valNum == null || maxNum == null) continue;
      final qualified = ring.classes.contains('met') || valNum >= maxNum;
      rows.add(
        ConnectRequirementRow(
          label: label,
          currentValue: fmtNum(valNum),
          requiredValue: fmtNum(maxNum),
          qualified: qualified,
        ),
      );
    }

    // 2. Modern `.tl3-bar-item` horizontal progress bars
    for (final bar in doc.querySelectorAll('.tl3-bar-item')) {
      final label = bar.querySelector('.tl3-bar-label')?.text.trim() ?? '';
      if (label.isEmpty || !seenLabels.add(label)) continue;
      final numsText = bar.querySelector('.tl3-bar-nums')?.text.trim() ?? '';
      final fillEl = bar.querySelector('.tl3-bar-fill');
      final style = fillEl?.attributes['style'] ?? '';
      final valNum = readCssNum(_connectCssVarValRegex, style);
      final maxNum = readCssNum(_connectCssVarMaxRegex, style);

      var curStr = valNum != null ? fmtNum(valNum) : '';
      var reqStr = maxNum != null ? fmtNum(maxNum) : '';
      if (numsText.contains('/')) {
        final parts = numsText.split('/');
        if (parts.length == 2) {
          if (curStr.isEmpty) curStr = parts[0].trim();
          if (reqStr.isEmpty) reqStr = parts[1].trim();
        }
      }
      if (curStr.isEmpty && reqStr.isEmpty) continue;
      final qualified = (fillEl?.classes.contains('met') ?? false) ||
          (bar.querySelector('.tl3-bar-nums')?.classes.contains('met') ?? false) ||
          (valNum != null && maxNum != null && valNum >= maxNum);
      rows.add(
        ConnectRequirementRow(
          label: label,
          currentValue: curStr,
          requiredValue: reqStr,
          qualified: qualified,
        ),
      );
    }

    // 3. Modern `.tl3-quota-card` upper-bound quota cards
    for (final quota in doc.querySelectorAll('.tl3-quota-card')) {
      final label = quota.querySelector('.tl3-quota-label')?.text.trim() ?? '';
      final nums = quota.querySelector('.tl3-quota-nums')?.text.trim() ?? '';
      if (label.isEmpty || !nums.contains('/') || !seenLabels.add(label)) continue;
      final parts = nums.split('/');
      if (parts.length != 2) continue;
      final curRaw = parts[0].trim();
      final reqRaw = parts[1].trim();
      final curVal = _firstNumber(curRaw);
      final reqVal = _firstNumber(reqRaw);
      final qualified = quota.classes.contains('met') ||
          (!quota.classes.contains('unmet') &&
              (curVal != null && reqVal != null ? curVal <= reqVal : true));
      rows.add(
        ConnectRequirementRow(
          label: label,
          currentValue: curRaw,
          requiredValue: '≤ $reqRaw',
          qualified: qualified,
        ),
      );
    }

    // 4. Classic `<table><tr><td>label</td><td>current</td><td>required</td></tr></table>`
    if (rows.isEmpty) {
      for (final tr in doc.querySelectorAll('table tr')) {
        final cells = tr.querySelectorAll('td');
        if (cells.length < 3) continue;
        final label = cells[0].text.replaceAll(RegExp(r'\s+'), ' ').trim();
        final curRaw = cells[1].text.replaceAll(RegExp(r'\s+'), ' ').trim();
        final reqRaw = cells[2].text.replaceAll(RegExp(r'\s+'), ' ').trim();
        if (label.isEmpty || !seenLabels.add(label)) continue;

        final curClasses = '${cells[1].className} ${tr.className}'.toLowerCase();
        bool qualified;
        if (curClasses.contains('red') ||
            curClasses.contains('fail') ||
            curClasses.contains('unmet') ||
            curClasses.contains('text-red')) {
          qualified = false;
        } else if (curClasses.contains('green') ||
            curClasses.contains('pass') ||
            curClasses.contains('met') ||
            curClasses.contains('text-green')) {
          qualified = true;
        } else {
          final curVal = _firstNumber(curRaw);
          final reqVal = _firstNumber(reqRaw);
          final isUpperBound = label.contains('举报') ||
              label.contains('封禁') ||
              label.contains('禁言') ||
              reqRaw.contains('最多') ||
              reqRaw.contains('不超过') ||
              reqRaw.contains('≤') ||
              reqRaw.contains('<=');
          if (curVal != null && reqVal != null) {
            qualified = isUpperBound ? curVal <= reqVal : curVal >= reqVal;
          } else {
            qualified = true;
          }
        }

        rows.add(
          ConnectRequirementRow(
            label: label,
            currentValue: curRaw,
            requiredValue: reqRaw,
            qualified: qualified,
          ),
        );
      }
    }

    if (rows.isEmpty) return null;

    var headerTitle = '';
    for (final h in doc.querySelectorAll('h1, h2, .card-title')) {
      final t = h.text.replaceAll(RegExp(r'\s+'), ' ').trim();
      if (t.contains('信任级别') || t.toLowerCase().contains('trust level') || t.contains('级用户')) {
        headerTitle = t;
        break;
      }
    }
    if (headerTitle.isEmpty) {
      headerTitle = 'Linux.do Connect 信任级别考核';
    }

    final allMet = rows.every((r) => r.qualified);
    final metCount = rows.where((r) => r.qualified).length;
    final subtitle = allMet
        ? '过去 $periodDays 天全部 ${rows.length} 项指标均已达标'
        : '过去 $periodDays 天已达标 $metCount / ${rows.length} 项指标';

    return ConnectOverviewModel(
      headerTitle: headerTitle,
      subtitle: subtitle,
      isLiveConnect: true,
      allQualified: allMet,
      periodDays: periodDays,
      rows: rows,
    );
  }

  ConnectOverviewModel _buildFallbackConnectOverview(UserFullProfileSummary summary) {
    final rows = <ConnectRequirementRow>[
      ConnectRequirementRow(
        label: '访问天数',
        currentValue: '${summary.daysVisited} 天',
        requiredValue: '50 天',
        qualified: summary.daysVisited >= 50,
      ),
      ConnectRequirementRow(
        label: '浏览话题',
        currentValue: '${summary.topicsEntered}',
        requiredValue: '500',
        qualified: summary.topicsEntered >= 500,
      ),
      ConnectRequirementRow(
        label: '浏览帖子',
        currentValue: '${summary.postsReadCount}',
        requiredValue: '20000',
        qualified: summary.postsReadCount >= 20000,
      ),
      ConnectRequirementRow(
        label: '获赞',
        currentValue: '${summary.likesReceived}',
        requiredValue: '20',
        qualified: summary.likesReceived >= 20,
      ),
      ConnectRequirementRow(
        label: '送出赞',
        currentValue: '${summary.likesGiven}',
        requiredValue: '30',
        qualified: summary.likesGiven >= 30,
      ),
      ConnectRequirementRow(
        label: '回复数',
        currentValue: '${summary.postCount}',
        requiredValue: '10',
        qualified: summary.postCount >= 10,
      ),
    ];

    final allMet = rows.every((r) => r.qualified);
    return ConnectOverviewModel(
      headerTitle: '${summary.trustLevelName} · 社区成长概览',
      subtitle: '当前展示账号累计统计数据（Connect 实时周期面板需 Lv2+ 解锁）',
      isLiveConnect: false,
      allQualified: allMet,
      periodDays: 100,
      rows: rows,
    );
  }

  // ---------------------------------------------------------------------------
  // Entity Parsers
  // ---------------------------------------------------------------------------

  TopicFeedPage _parseTopicListFeedPage(
    Map<String, dynamic> root, {
    required int currentPage,
  }) {
    final usersById = <int, TopicUser>{};
    final rawUsers = root['users'];
    if (rawUsers is List) {
      for (final u in rawUsers) {
        final uMap = _asMap(u);
        if (uMap == null) continue;
        final parsed = _parseTopicUser(uMap);
        if (parsed.id != 0) {
          usersById[parsed.id] = parsed;
        }
      }
    }

    final topicList = _asMap(root['topic_list']) ?? root;
    final moreTopicsUrl = _nullIfBlank(_asString(topicList['more_topics_url']));
    final rawTopics = topicList['topics'];
    if (rawTopics is! List) {
      return TopicFeedPage(
        topics: const <TopicSummary>[],
        moreTopicsUrl: moreTopicsUrl,
        nextPage: currentPage + 1,
      );
    }

    final pinnedTopics = <TopicSummary>[];
    final regularTopics = <TopicSummary>[];

    for (final item in rawTopics) {
      final t = _asMap(item);
      if (t == null) continue;
      final id = _asInt(t['id']);
      if (id <= 0) continue;

      final participants = <TopicUser>[];
      TopicUser? opUser;
      final posters = t['posters'];
      if (posters is List) {
        for (final p in posters) {
          final pMap = _asMap(p);
          if (pMap == null) continue;
          final uid = _asInt(pMap['user_id']);
          final user = usersById[uid];
          if (user == null) continue;
          final desc = _asString(pMap['description']);
          if (opUser == null &&
              (desc.contains('Original Poster') || desc.contains('原始作者'))) {
            opUser = user;
          }
          if (!participants.any((existing) => existing.id == user.id)) {
            participants.add(user);
          }
        }
      }
      opUser ??= participants.isNotEmpty
          ? participants.first
          : const TopicUser(id: 0, username: 'linuxdo');

      final catId = _asInt(t['category_id'], fallback: 4);
      final catMeta = _findCategoryMeta(catId);
      final postsCount = math.max(1, _asInt(t['posts_count'], fallback: 1));
      final highestPost = _asInt(t['highest_post_number'], fallback: postsCount);

      var unreadPosts = _asInt(t['unread_posts']) + _asInt(t['new_posts']);
      var unseen = t['unseen'] == true;
      final localFloor = store.getReadFloor(id);
      if (localFloor != null && localFloor >= highestPost) {
        unreadPosts = 0;
        unseen = false;
      }

      final createdIso = _asString(t['created_at']);
      final lastPostedIso = _nonBlankOr(_asString(t['last_posted_at']), createdIso);
      final isPinned = t['pinned'] == true || t['pinned_globally'] == true;

      final summary = TopicSummary(
        id: id,
        title: decodeDiscourseHtmlSnippet(
          _nonBlankOr(_asString(t['fancy_title']), _asString(t['title'])),
        ),
        fancyTitle: _asString(t['fancy_title']),
        slug: _asString(t['slug']),
        postsCount: postsCount,
        highestPostNumber: highestPost,
        replyCount: math.max(0, _asInt(t['reply_count'], fallback: postsCount - 1)),
        views: _asInt(t['views']),
        likeCount: _asInt(t['like_count']),
        categoryId: catId,
        categoryName: catMeta.name,
        categoryColorHex: catMeta.colorHex,
        tags: _parseTagStrings(t['tags'] as List?),
        excerpt: _nullIfBlank(decodeDiscourseHtmlSnippet(_asString(t['excerpt']))),
        pinned: isPinned,
        closed: t['closed'] == true,
        archived: t['archived'] == true,
        unseen: unseen,
        unreadPosts: unreadPosts,
        hasAcceptedAnswer: t['has_accepted_answer'] == true,
        lastPostedAt: formatRelativeTime(lastPostedIso),
        createdAtIso: createdIso,
        lastPostedAtIso: lastPostedIso,
        originalPoster: opUser,
        participantAvatars: participants,
      );

      if (isPinned && currentPage == 0) {
        pinnedTopics.add(summary);
      } else {
        regularTopics.add(summary);
      }
    }

    return TopicFeedPage(
      topics: regularTopics,
      pinnedTopics: pinnedTopics,
      moreTopicsUrl: moreTopicsUrl,
      nextPage: currentPage + 1,
    );
  }

  PostItemModel _parsePostItem(
    Map<String, dynamic> p, {
    int fallbackTopicId = 0,
    String? topicOwnerUsername,
    Map<int, GrantedBadge> badgeCatalogById = const <int, GrantedBadge>{},
    Map<int, List<GrantedBadge>> userBadgesByUserId = const <int, List<GrantedBadge>>{},
  }) {
    final userId = _asInt(p['user_id']);
    final username = _asString(p['username']);
    final avatarTpl = _asString(p['avatar_template']);
    if (username.isNotEmpty && avatarTpl.isNotEmpty) {
      _avatarTemplateByUsername[username.toLowerCase()] = avatarTpl;
    }

    final replyToUser = _asMap(p['reply_to_user']);
    final statusMap = _asMap(p['user_status']);
    final userStatus = statusMap == null
        ? null
        : UserStatus(
            description: _nullIfBlank(_asString(statusMap['description'])),
            emoji: _nullIfBlank(_asString(statusMap['emoji'])),
          );

    // Reactions & Likes
    final reactions = <PostReactionItem>[];
    var totalReactionCount = 0;
    final rawReactions = p['reactions'];
    if (rawReactions is List) {
      for (final r in rawReactions) {
        final rMap = _asMap(r);
        if (rMap == null) continue;
        final rId = _asString(rMap['id']).trim();
        final rCount = _asInt(rMap['count']);
        if (rId.isEmpty || rCount <= 0) continue;
        totalReactionCount += rCount;
        reactions.add(
          PostReactionItem(
            id: rId,
            emoji: _nonBlankOr(
              LinuxDoEmojiCatalog.instance.resolveUnicodeEmoji(rId) ?? '',
              rId,
            ),
            count: rCount,
          ),
        );
      }
    }

    String? myReactionId;
    final curReaction = p['current_user_reaction'];
    if (curReaction is Map) {
      myReactionId = _nullIfBlank(_asString(curReaction['id']));
    } else if (curReaction is String && curReaction.trim().isNotEmpty) {
      myReactionId = curReaction.trim();
    }

    var actionLikeCount = 0;
    var likedViaPostAction = false;
    final actionsSummary = p['actions_summary'];
    if (actionsSummary is List) {
      for (final act in actionsSummary) {
        final aMap = _asMap(act);
        if (aMap == null) continue;
        if (_asInt(aMap['id']) == 2) {
          actionLikeCount = _asInt(aMap['count']);
          if (aMap['acted'] == true) likedViaPostAction = true;
        }
      }
    }
    if (myReactionId == null &&
        (likedViaPostAction || p['current_user_used_main_reaction'] == true)) {
      myReactionId = 'heart';
    }
    final likeCount = math.max(totalReactionCount, actionLikeCount);
    final likedByMe = myReactionId != null || likedViaPostAction;

    // Boosts
    final boosts = <PostBoostItem>[];
    final rawBoosts = p['boosts'];
    if (rawBoosts is List) {
      for (final b in rawBoosts) {
        final bMap = _asMap(b);
        if (bMap == null) continue;
        final parsedBoost = _parseBoostItem(bMap);
        if (parsedBoost != null) boosts.add(parsedBoost);
      }
    }

    // Polls
    final polls = <PollModel>[];
    final pollsVotesMap = _asMap(p['polls_votes']);
    final rawPolls = p['polls'];
    if (rawPolls is List) {
      for (final pollElem in rawPolls) {
        final pollObj = _asMap(pollElem);
        if (pollObj == null) continue;
        final pollName = _nonBlankOr(_asString(pollObj['name']), 'poll');
        final preloaded = (pollsVotesMap?[pollName] as List?)
                ?.map((e) => e?.toString().trim() ?? '')
                .where((s) => s.isNotEmpty)
                .toList(growable: false) ??
            const <String>[];
        polls.add(_parsePollModel(pollObj, preloadedVotes: preloaded));
      }
    }

    // Granted badges on this specific post
    final badgesGranted = <GrantedBadge>[];
    final rawBadgesGranted = p['badges_granted'];
    if (rawBadgesGranted is List) {
      for (final b in rawBadgesGranted) {
        final bMap = _asMap(b);
        if (bMap != null) {
          final parsed = _parseBadgeDef(bMap);
          if (parsed != null) badgesGranted.add(parsed.copyWith(isEarned: true));
        }
      }
    }

    final postType = _asInt(p['post_type'], fallback: 1);
    final actionCode = _nullIfBlank(_asString(p['action_code']));
    final isOwner = p['topic_owner'] == true ||
        (topicOwnerUsername != null &&
            username.isNotEmpty &&
            username.toLowerCase() == topicOwnerUsername.toLowerCase());

    return PostItemModel(
      id: _asInt(p['id']),
      postNumber: _asInt(p['post_number'], fallback: 1),
      userId: userId,
      username: username,
      displayName: _nullIfBlank(_asString(p['name'])),
      avatarTemplate: avatarTpl,
      animatedAvatar: _nullIfBlank(_asString(p['animated_avatar'])),
      primaryGroupName: _nullIfBlank(_asString(p['primary_group_name'])),
      userTitle: _nullIfBlank(_asString(p['user_title'])),
      titleIsGroup: p['title_is_group'] == true,
      trustLevel: _asInt(p['trust_level'], fallback: 1),
      admin: p['admin'] == true,
      moderator: p['moderator'] == true,
      groupModerator: p['group_moderator'] == true,
      isTopicOwner: isOwner,
      flair: _parseFlairInfo(p),
      userStatus: userStatus,
      isTodayBirthday: _isTodayMonthDay(_asString(p['user_birthdate'])),
      isTodayCakeday: _isTodayMonthDay(_asString(p['user_cakedate'])),
      badgesGranted: badgesGranted,
      authorBadges: userBadgesByUserId[userId] ?? const <GrantedBadge>[],
      cookedHtml: _asString(p['cooked']),
      createdAtText: formatRelativeTime(_asString(p['created_at'])),
      replyToPostNumber: _asNullableInt(p['reply_to_post_number']),
      replyToUsername: _nullIfBlank(_asString(replyToUser?['username'])),
      replyToDisplayName: _nullIfBlank(_asString(replyToUser?['name'])),
      replyToAvatarTemplate: _nullIfBlank(_asString(replyToUser?['avatar_template'])),
      likeCount: likeCount,
      replyCount: _asInt(p['reply_count']),
      readsCount: _asInt(p['reads']),
      likedByMe: likedByMe,
      isRead: p['read'] == true,
      myReactionId: myReactionId,
      reactions: reactions,
      boosts: boosts,
      canBoost: p['can_boost'] != false,
      bookmarkedByMe: p['bookmarked'] == true,
      isSmallAction: postType == 3 || actionCode != null,
      actionCode: actionCode,
      canEdit: p['can_edit'] == true,
      canDelete: p['can_delete'] == true,
      raw: _nullIfBlank(_asString(p['raw'])),
      topicId: _asInt(p['topic_id'], fallback: fallbackTopicId),
      polls: polls,
    );
  }

  PostBoostItem? _parseBoostItem(Map<String, dynamic> b) {
    final id = _asInt(b['id']);
    if (id <= 0) return null;
    final userObj = _asMap(b['user']);
    final cooked = _nonBlankOr(_asString(b['cooked']), _asString(b['raw']));
    final userId = _asInt(userObj?['id'], fallback: _asInt(b['user_id']));
    final username = _nonBlankOr(
      _asString(userObj?['username']),
      _asString(b['username']),
    );
    return PostBoostItem(
      id: id,
      cookedHtml: cooked,
      plainText: decodeDiscourseHtmlSnippet(cooked),
      userId: userId,
      username: username,
      displayName: _nullIfBlank(
        _nonBlankOr(_asString(userObj?['name']), _asString(b['name'])),
      ),
      avatarTemplate: _nonBlankOr(
        _asString(userObj?['avatar_template']),
        _asString(b['avatar_template']),
      ),
      canDelete: b['can_delete'] == true ||
          (isAuthorized &&
              (_currentUser!.userId == userId ||
                  _currentUser!.username.toLowerCase() == username.toLowerCase())),
      canFlag: b['can_flag'] == true,
    );
  }

  PollModel _parsePollModel(
    Map<String, dynamic> p, {
    List<String> preloadedVotes = const <String>[],
  }) {
    final options = <PollOptionModel>[];
    final rawOpts = p['options'];
    if (rawOpts is List) {
      for (final o in rawOpts) {
        final oMap = _asMap(o);
        if (oMap == null) continue;
        final id = _asString(oMap['id']).trim();
        if (id.isEmpty) continue;
        final html = _asString(oMap['html']);
        options.add(
          PollOptionModel(
            id: id,
            html: html,
            plainText: decodeDiscourseHtmlSnippet(html),
            votes: _asInt(oMap['votes']),
          ),
        );
      }
    }

    final preloadedFromPoll = (p['preloaded_voters'] is Map || p['vote'] is List)
        ? ((p['vote'] as List?)
                ?.map((e) => e?.toString().trim() ?? '')
                .where((s) => s.isNotEmpty)
                .toList(growable: false) ??
            const <String>[])
        : const <String>[];

    return PollModel(
      name: _nonBlankOr(_asString(p['name']), 'poll'),
      type: _nonBlankOr(_asString(p['type']), 'regular'),
      status: _nonBlankOr(_asString(p['status']), 'open'),
      results: _nonBlankOr(_asString(p['results']), 'always'),
      voters: _asInt(p['voters']),
      min: _asInt(p['min'], fallback: 1),
      max: _asInt(p['max'], fallback: math.max(1, options.length)),
      step: _asInt(p['step'], fallback: 1),
      publicPoll: p['public'] == true,
      title: decodeDiscourseHtmlSnippet(_asString(p['title'])),
      options: options,
      preloadedSelection:
          preloadedVotes.isNotEmpty ? preloadedVotes : preloadedFromPoll,
    );
  }

  List<UserNotificationItem> _parseNotificationItems(Map<String, dynamic> root) {
    final rawArr = root['notifications'];
    if (rawArr is! List) return const <UserNotificationItem>[];

    final items = <UserNotificationItem>[];
    for (final elem in rawArr) {
      final obj = _asMap(elem);
      if (obj == null) continue;
      final id = _asInt(obj['id']);
      if (id <= 0) continue;

      final nType = _asInt(obj['notification_type']);
      final dataObj = _asMap(obj['data']);

      final actingUser = _nonBlankOr(
        _asString(dataObj?['display_username']),
        _nonBlankOr(
          _asString(dataObj?['original_username']),
          _nonBlankOr(
            _asString(obj['acting_user_name']),
            LinDoCommonCopy.SYSTEM_ACTOR.text,
          ),
        ),
      );

      var count = _asInt(dataObj?['count']);
      if (count <= 0) {
        final consolidatedMatch = _consolidatedCountRegex.firstMatch(actingUser);
        if (consolidatedMatch != null) {
          count = int.tryParse(consolidatedMatch.group(1) ?? '') ?? 0;
        }
      }

      var avatarTpl = _nullIfBlank(
        _nonBlankOr(
          _asString(obj['acting_user_avatar_template']),
          _nonBlankOr(
            _asString(dataObj?['acting_user_avatar_template']),
            _asString(dataObj?['original_user_avatar_template']),
          ),
        ),
      );
      if (avatarTpl != null && actingUser.isNotEmpty) {
        _avatarTemplateByUsername[actingUser.toLowerCase()] = avatarTpl;
      } else if (avatarTpl == null && actingUser.isNotEmpty) {
        avatarTpl = _avatarTemplateByUsername[actingUser.toLowerCase()];
      }

      final badgeId = _asInt(dataObj?['badge_id']);
      final badgeName = _asString(dataObj?['badge_name']);
      final chatChannelId = _asInt(dataObj?['chat_channel_id']);
      final topicTitle = decodeDiscourseHtmlSnippet(
        _nonBlankOr(
          _asString(obj['fancy_title']),
          _nonBlankOr(
            _asString(dataObj?['topic_title']),
            _nonBlankOr(
              badgeName,
              _nonBlankOr(
                _asString(dataObj?['chat_channel_title']),
                _asString(dataObj?['group_name']),
              ),
            ),
          ),
        ),
      );

      final excerpt = decodeDiscourseHtmlSnippet(
        _nonBlankOr(
          _asString(dataObj?['message']),
          _nonBlankOr(
            _asString(dataObj?['chat_message']),
            _asString(obj['excerpt']),
          ),
        ),
      );

      items.add(
        UserNotificationItem(
          id: id,
          notificationType: nType,
          read: obj['read'] == true,
          createdAtText: formatRelativeTime(_asString(obj['created_at'])),
          topicId: _asInt(obj['topic_id']),
          postNumber: _asInt(obj['post_number'], fallback: 1),
          originalPostId: _asInt(dataObj?['original_post_id']),
          slug: _asString(obj['slug']),
          title: topicTitle.isEmpty ? actingUser : topicTitle,
          actingUsername: actingUser,
          actingAvatarTemplate: avatarTpl,
          actingPrimaryGroupName:
              _nullIfBlank(_asString(obj['acting_user_primary_group_name'])),
          excerpt: excerpt,
          count: count,
          badgeId: badgeId,
          badgeName: badgeName,
          chatChannelId: chatChannelId,
        ),
      );
    }

    return items;
  }

  PublishOutcome _parsePublishOutcome(
    Map<String, dynamic> root, {
    int? fallbackTopicId,
    String? fallbackTitle,
  }) {
    final action = _asString(root['action']).toLowerCase();
    if (action == 'enqueued' || root.containsKey('pending_post')) {
      return PublishOutcome(
        success: true,
        enqueuedForReview: true,
        topicId: fallbackTopicId,
        title: fallbackTitle,
      );
    }

    final postMap = _asMap(root['post']) ?? root;
    final topicId = _asNullableInt(postMap['topic_id']) ?? fallbackTopicId;
    final parsedPost = postMap.containsKey('id')
        ? _parsePostItem(postMap, fallbackTopicId: topicId ?? 0)
        : null;
    return PublishOutcome(
      success: true,
      topicId: topicId,
      title: _nullIfBlank(_asString(postMap['topic_slug'])) ?? fallbackTitle,
      post: parsedPost,
    );
  }

  ChatMessageItem? _parseChatMessage(
    Map<String, dynamic> m, {
    required int fallbackChannelId,
  }) {
    final id = _asInt(m['id']);
    if (id <= 0) return null;
    final u = _asMap(m['user']);
    final uname = _nonBlankOr(_asString(u?['username']), _asString(m['username']));
    final rawMessage = _asString(m['message']);
    final cooked = _nonBlankOr(_asString(m['cooked']), rawMessage);

    return ChatMessageItem(
      id: id,
      channelId: _asInt(m['chat_channel_id'], fallback: fallbackChannelId),
      userId: _asInt(u?['id'], fallback: _asInt(m['user_id'])),
      username: uname,
      displayName: _nonBlankOr(_asString(u?['name']), uname),
      avatarTemplate: _asString(u?['avatar_template']),
      message: rawMessage,
      cookedHtml: cooked,
      createdAtText: formatRelativeTime(_asString(m['created_at'])),
    );
  }

  TopicUser _parseTopicUser(Map<String, dynamic> u) {
    final uname = _asString(u['username']).trim();
    final tpl = _asString(u['avatar_template']);
    if (uname.isNotEmpty && tpl.isNotEmpty) {
      _avatarTemplateByUsername[uname.toLowerCase()] = tpl;
    }
    return TopicUser(
      id: _asInt(u['id']),
      username: uname,
      name: _nullIfBlank(_asString(u['name'])),
      avatarTemplate: tpl,
      animatedAvatar: _nullIfBlank(_asString(u['animated_avatar'])),
      primaryGroupName: _nullIfBlank(_asString(u['primary_group_name'])),
      flairName: _nullIfBlank(_asString(u['flair_name'])),
      flairUrl: _nullIfBlank(_asString(u['flair_url'])),
      flairBgColor: _nullIfBlank(_asString(u['flair_bg_color'])),
      flairColor: _nullIfBlank(_asString(u['flair_color'])),
      flairGroupId: _asNullableInt(u['flair_group_id']),
      trustLevel: _asInt(u['trust_level'], fallback: 1),
      admin: u['admin'] == true,
      moderator: u['moderator'] == true,
    );
  }

  FlairInfo _parseFlairInfo(Map<String, dynamic> m) => FlairInfo(
        url: _nullIfBlank(_asString(m['flair_url'])),
        name: _nullIfBlank(_asString(m['flair_name'])),
        bgColorHex: _nullIfBlank(_asString(m['flair_bg_color'])),
        fgColorHex: _nullIfBlank(_asString(m['flair_color'])),
        groupId: _asNullableInt(m['flair_group_id']),
      );

  Map<int, GrantedBadge> _parseBadgeDefinitionsMap(
    Object? rawBadges, {
    Map<int, String> groupingNames = const <int, String>{},
  }) {
    if (rawBadges is! List) return const <int, GrantedBadge>{};
    final out = <int, GrantedBadge>{};
    for (final item in rawBadges) {
      final map = _asMap(item);
      if (map == null) continue;
      final parsed = _parseBadgeDef(map, groupingNames: groupingNames);
      if (parsed != null) out[parsed.id] = parsed;
    }
    return out;
  }

  GrantedBadge? _parseBadgeDef(
    Map<String, dynamic> b, {
    Map<int, String> groupingNames = const <int, String>{},
  }) {
    final id = _asInt(b['id']);
    final name = _asString(b['name']).trim();
    if (id <= 0 || name.isEmpty) return null;
    final groupingId = _asInt(b['badge_grouping_id']);
    return GrantedBadge(
      id: id,
      name: name,
      description: _asString(b['description']),
      longDescription: _asString(b['long_description']),
      badgeTypeId: _asInt(b['badge_type_id'], fallback: 3),
      icon: _nullIfBlank(_asString(b['icon'])),
      imageUrl: _nullIfBlank(_asString(b['image_url'])),
      slug: _asString(b['slug']),
      grantCount: _asInt(b['grant_count']),
      allowTitle: b['allow_title'] == true,
      badgeGroupingId: groupingId,
      badgeGroupingName: groupingNames[groupingId] ?? '',
    );
  }

  Map<int, List<GrantedBadge>> _parseTopicUserBadges(
    Object? rawUserBadges,
    Map<int, GrantedBadge> catalog,
  ) {
    final map = _asMap(rawUserBadges);
    if (map == null) return const <int, List<GrantedBadge>>{};
    final out = <int, List<GrantedBadge>>{};
    for (final entry in map.entries) {
      final uid = int.tryParse(entry.key);
      final entryMap = _asMap(entry.value);
      final badgeIds = entryMap?['badge_ids'] ?? entry.value;
      if (uid == null || badgeIds is! List) continue;
      final list = <GrantedBadge>[];
      for (final bid in badgeIds) {
        final b = catalog[(bid as num?)?.toInt() ?? 0];
        if (b != null) list.add(b.copyWith(isEarned: true));
      }
      if (list.isNotEmpty) out[uid] = list;
    }
    return out;
  }

  List<GrantedBadge> _parseGrantedBadgesFromRoot(
    Map<String, dynamic> root,
    Map<int, GrantedBadge> catalog,
  ) {
    final rawUb = root['user_badges'];
    if (rawUb is! List) return catalog.values.toList(growable: false);

    final topicsById = <int, String>{};
    final rawTopics = root['topics'];
    if (rawTopics is List) {
      for (final t in rawTopics) {
        final tMap = _asMap(t);
        if (tMap == null) continue;
        final tid = _asInt(tMap['id']);
        if (tid > 0) {
          topicsById[tid] = decodeDiscourseHtmlSnippet(
            _nonBlankOr(_asString(tMap['fancy_title']), _asString(tMap['title'])),
          );
        }
      }
    }

    final granted = <GrantedBadge>[];
    for (final item in rawUb) {
      final ub = _asMap(item);
      if (ub == null) continue;
      final badgeId = _asInt(ub['badge_id'], fallback: _asInt(ub['id']));
      final base = catalog[badgeId] ?? _parseBadgeDef(ub);
      if (base == null) continue;
      final topicId = _asInt(ub['topic_id']);
      granted.add(
        GrantedBadge(
          id: base.id,
          name: base.name,
          description: base.description,
          longDescription: base.longDescription,
          badgeTypeId: base.badgeTypeId,
          icon: base.icon,
          imageUrl: base.imageUrl,
          slug: base.slug,
          grantCount: base.grantCount,
          allowTitle: base.allowTitle,
          badgeGroupingId: base.badgeGroupingId,
          badgeGroupingName: base.badgeGroupingName,
          count: math.max(1, _asInt(ub['count'], fallback: 1)),
          grantedAtText: _formatDateShort(_asString(ub['granted_at'])),
          isEarned: true,
          topicId: topicId,
          topicTitle: topicsById[topicId] ?? _asString(ub['topic_title']),
          postNumber: _asInt(ub['post_number']),
        ),
      );
    }
    return granted;
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  void _hydrateInMemoryFromStore() {
    _currentUser = store.savedUser;
    _csrfToken = store.savedCsrfToken;
    if (store.savedCookies.isNotEmpty) {
      _ingestCookieHeaderString(store.savedCookies);
    }
    if (store.cachedCategories.isNotEmpty) {
      _categories = store.cachedCategories;
    }
    if (store.cachedEmojisJson != null) {
      LinuxDoEmojiCatalog.instance.mergeRemoteEmojisJson(store.cachedEmojisJson!);
    }
  }

  void _ingestCookieHeaderString(String rawCookieString) {
    for (final part in rawCookieString.split(';')) {
      final trimmed = part.trim();
      final eqIdx = trimmed.indexOf('=');
      if (eqIdx <= 0) continue;
      final name = trimmed.substring(0, eqIdx).trim();
      final val = trimmed.substring(eqIdx + 1).trim();
      final lower = name.toLowerCase();
      if (lower == 'path' ||
          lower == 'domain' ||
          lower == 'expires' ||
          lower == 'max-age' ||
          lower == 'samesite' ||
          lower == 'httponly' ||
          lower == 'secure') {
        continue;
      }
      if (name.isNotEmpty && val.isNotEmpty) {
        _cookieJar[name] = val;
      }
    }
    _rawCookies = _cookieJar.entries.map((e) => '${e.key}=${e.value}').join('; ');
  }

  void _ingestSetCookieFromHeaders(Map<String, String> headers) {
    for (final entry in headers.entries) {
      if (entry.key.toLowerCase() == 'set-cookie') {
        // Split multiple Set-Cookie values carefully (ignoring commas inside Expires=Wed, 21 Oct...)
        final cookies = entry.value.split(RegExp(r',(?=\s*[A-Za-z0-9_-]+=)'));
        for (final c in cookies) {
          final firstPair = c.split(';').first.trim();
          final eq = firstPair.indexOf('=');
          if (eq > 0) {
            final k = firstPair.substring(0, eq).trim();
            final v = firstPair.substring(eq + 1).trim();
            if (k.isNotEmpty) _cookieJar[k] = v;
          }
        }
        _rawCookies = _cookieJar.entries.map((e) => '${e.key}=${e.value}').join('; ');
      }
    }
  }

  CategoryInfo _findCategoryMeta(int categoryId) {
    for (final c in _categories) {
      if (c.id == categoryId) return c;
    }
    for (final c in _kFallbackCategories) {
      if (c.id == categoryId) return c;
    }
    return CategoryInfo(
      id: categoryId,
      name: '常规话题',
      colorHex: '0088CC',
    );
  }

  String _resolveCategorySlug(int categoryId, String? explicitSlug) {
    if (explicitSlug != null && explicitSlug.trim().isNotEmpty) {
      return explicitSlug.trim();
    }
    final meta = _findCategoryMeta(categoryId);
    return meta.slug.trim();
  }

  List<TopicSummary> _filterTopicsByCategoryAndTag(
    List<TopicSummary> items, {
    int? categoryId,
    String? tag,
  }) {
    if ((categoryId == null || categoryId <= 0) &&
        (tag == null || tag.trim().isEmpty)) {
      return items;
    }
    final cleanTag = tag?.trim().toLowerCase();
    return items.where((t) {
      final catOk = categoryId == null ||
          categoryId <= 0 ||
          t.categoryId == categoryId ||
          _findCategoryMeta(t.categoryId).parentCategoryId == categoryId;
      final tagOk = cleanTag == null ||
          cleanTag.isEmpty ||
          t.tags.any((tg) => tg.toLowerCase() == cleanTag);
      return catOk && tagOk;
    }).toList(growable: false);
  }

  static String _resolveEndpointUrl(String pathOrUrl) {
    final trimmed = pathOrUrl.trim();
    if (trimmed.startsWith('http://') || trimmed.startsWith('https://')) {
      return trimmed;
    }
    if (trimmed.startsWith('/')) {
      return '$kLinuxDoBaseUrl$trimmed';
    }
    return '$kLinuxDoBaseUrl/$trimmed';
  }

  static String _normalizeMoreTopicsJsonPath(String rawUrl) {
    final qIdx = rawUrl.indexOf('?');
    final base = qIdx >= 0 ? rawUrl.substring(0, qIdx) : rawUrl;
    final query = qIdx >= 0 ? rawUrl.substring(qIdx) : '';
    final jsonBase = base.endsWith('.json') ? base : '$base.json';
    return '$jsonBase$query';
  }

  static String? _encodeFormBody(
    Map<String, String>? formFields,
    List<MapEntry<String, String>>? formPairs,
  ) {
    if (formPairs != null && formPairs.isNotEmpty) {
      return formPairs
          .map(
            (e) =>
                '${Uri.encodeQueryComponent(e.key)}=${Uri.encodeQueryComponent(e.value)}',
          )
          .join('&');
    }
    if (formFields != null && formFields.isNotEmpty) {
      return formFields.entries
          .map(
            (e) =>
                '${Uri.encodeQueryComponent(e.key)}=${Uri.encodeQueryComponent(e.value)}',
          )
          .join('&');
    }
    return null;
  }

  static String _extractDiscourseErrorMessage(String rawBody, int status) {
    if (rawBody.trim().startsWith('{')) {
      try {
        final decoded = _asMap(jsonDecode(rawBody));
        final errors = decoded?['errors'];
        if (errors is List && errors.isNotEmpty) {
          final joined = errors
              .map((e) => e?.toString().trim() ?? '')
              .where((s) => s.isNotEmpty)
              .join('；');
          if (joined.isNotEmpty) return joined;
        }
        final single = _asString(decoded?['error']).trim();
        if (single.isNotEmpty) return single;
      } catch (_) {}
    }

    if (status == 401) return LinDoNetworkCopy.AUTH_EXPIRED_RELOGIN.text;
    if (status == 403) return LinDoNetworkCopy.PERMISSION_DENIED.text;
    if (status == 404) return LinDoNetworkCopy.TOPIC_UNAVAILABLE.text;
    if (status == 429) return LinDoNetworkCopy.RATE_LIMITED_RETRY.text;
    if (status >= 500) return LinDoNetworkCopy.SERVER_UNAVAILABLE_RETRY.text;
    return '${LinDoCommonCopy.LOAD_FAILED.text} ($status)';
  }

  static String _guessMimeType(String fileName) {
    final dotIdx = fileName.lastIndexOf('.');
    final ext = dotIdx >= 0 ? fileName.substring(dotIdx + 1).toLowerCase() : '';
    switch (ext) {
      case 'png':
        return 'image/png';
      case 'jpg':
      case 'jpeg':
        return 'image/jpeg';
      case 'gif':
        return 'image/gif';
      case 'webp':
        return 'image/webp';
      case 'avif':
        return 'image/avif';
      case 'svg':
        return 'image/svg+xml';
      case 'pdf':
        return 'application/pdf';
      case 'zip':
        return 'application/zip';
      case 'json':
        return 'application/json';
      case 'txt':
      case 'md':
      case 'log':
        return 'text/plain';
      default:
        return 'application/octet-stream';
    }
  }

  static List<String> _parseTagStrings(List? rawTags) {
    if (rawTags == null || rawTags.isEmpty) return const <String>[];
    final out = <String>[];
    for (final item in rawTags) {
      if (item is String && item.trim().isNotEmpty) {
        out.add(item.trim());
      } else if (item is Map) {
        final name = (item['name'] ?? item['text'] ?? '').toString().trim();
        if (name.isNotEmpty) out.add(name);
      }
    }
    return out;
  }

  static Map<String, dynamic>? _asMap(Object? v) {
    if (v is Map<String, dynamic>) return v;
    if (v is Map) return v.map((k, val) => MapEntry(k.toString(), val));
    return null;
  }

  static String _asString(Object? v) => v == null ? '' : v.toString();

  static String? _nullIfBlank(String v) => v.trim().isEmpty ? null : v.trim();

  static String _nonBlankOr(String primary, String fallback) =>
      primary.trim().isNotEmpty ? primary : fallback;

  static int _asInt(Object? v, {int fallback = 0}) {
    if (v is int) return v;
    if (v is num) return v.toInt();
    if (v is String) return int.tryParse(v.trim()) ?? fallback;
    return fallback;
  }

  static int? _asNullableInt(Object? v) {
    if (v == null) return null;
    if (v is int) return v;
    if (v is num) return v.toInt();
    if (v is String) return int.tryParse(v.trim());
    return null;
  }

  static double? _firstNumber(String text) {
    final cleaned = text.replaceAll(',', '');
    final m = _numericExtractRegex.firstMatch(cleaned);
    return m == null ? null : double.tryParse(m.group(0) ?? '');
  }

  static String _formatDateShort(String iso) {
    final trimmed = iso.trim();
    if (trimmed.length >= 10) return trimmed.substring(0, 10);
    return trimmed;
  }

  static bool _isTodayMonthDay(String isoDate) {
    if (isoDate.trim().length < 10) return false;
    final parsed = DateTime.tryParse(isoDate.trim());
    if (parsed == null) return false;
    final now = DateTime.now();
    return parsed.month == now.month && parsed.day == now.day;
  }

  static String _generateClientId() {
    final rng = math.Random();
    final buf = StringBuffer();
    const hex = '0123456789abcdef';
    for (var i = 0; i < 32; i++) {
      buf.write(hex[rng.nextInt(16)]);
    }
    return buf.toString();
  }
}
