import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../core/copy/lindo_copy.dart';
import '../../core/models/lindo_models.dart';
import '../../core/network/linux_do_repository.dart';
import '../../core/site/linux_do_emoji_catalog.dart';
import '../../core/site/linux_do_site_rules.dart';
import '../../core/storage/lindo_local_store.dart';
import '../components/composer_workbench.dart';
import '../components/lindo_icons.dart';
import '../components/lindo_mascot.dart';
import '../components/post_rich_content.dart';
import '../components/smart_avatar_and_badges.dart';
import '../components/topic_floor_navigator.dart';
import '../components/web_auth_modal.dart';
import '../sheets/badges_sheet.dart';
import '../sheets/user_collections_sheets.dart';
import '../theme/lindo_theme.dart';

sealed class _ReaderStreamEntry {
  const _ReaderStreamEntry();
}

class _PostStreamEntry extends _ReaderStreamEntry {
  final PostItemModel post;
  const _PostStreamEntry(this.post);
}

class _GapStreamEntry extends _ReaderStreamEntry {
  final String gapKey;
  final List<int> missingPostIds;
  final bool isLeading;
  final bool isTrailing;

  const _GapStreamEntry({
    required this.gapKey,
    required this.missingPostIds,
    this.isLeading = false,
    this.isTrailing = false,
  });
}

class TopicReaderPane extends StatefulWidget {
  final int? topicId;
  final int? initialPostNumber;
  final String? initialTitle;
  final LinuxDoRepository repository;
  final LinDoLocalStore store;
  final bool showBackButton;
  final VoidCallback? onClose;
  final void Function(int topicId, {int? postNumber, String? title})
      onOpenTopic;
  final ValueChanged<String>? onSelectTag;
  final Future<void> Function() onStartWebLogin;
  final Future<void> Function() onStartSiteVerify;
  final ValueChanged<String>? onShowToast;

  const TopicReaderPane({
    super.key,
    required this.topicId,
    this.initialPostNumber,
    this.initialTitle,
    required this.repository,
    required this.store,
    this.showBackButton = false,
    this.onClose,
    required this.onOpenTopic,
    this.onSelectTag,
    required this.onStartWebLogin,
    required this.onStartSiteVerify,
    this.onShowToast,
  });

  @override
  State<TopicReaderPane> createState() => _TopicReaderPaneState();
}

class _TopicReaderPaneState extends State<TopicReaderPane> {
  final ScrollController _scrollController = ScrollController();
  final Map<int, GlobalKey> _floorKeys = <int, GlobalKey>{};

  TopicDetailModel? _detail;
  bool _loading = false;
  String? _error;
  bool _opOnly = false;
  bool _summaryExpanded = false;
  bool _showStickyTitle = false;
  int _currentFloor = 1;
  int? _boostComposerPostId;

  final Set<String> _loadingGapKeys = <String>{};
  bool _autoLoadingBottom = false;
  int _loadGeneration = 0;
  int _highestRecordedFloor = 0;
  Timer? _timingDebounce;

  @override
  void initState() {
    super.initState();
    _scrollController.addListener(_onScroll);
    if (widget.topicId != null && widget.topicId! > 0) {
      _loadTopic(
        widget.topicId!,
        nearPostNumber: widget.initialPostNumber,
      );
    }
  }

  @override
  void didUpdateWidget(covariant TopicReaderPane oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.topicId != oldWidget.topicId) {
      _floorKeys.clear();
      _opOnly = false;
      _summaryExpanded = false;
      _boostComposerPostId = null;
      _highestRecordedFloor = 0;
      if (widget.topicId == null || widget.topicId! <= 0) {
        setState(() {
          _detail = null;
          _loading = false;
          _error = null;
        });
      } else {
        _loadTopic(
          widget.topicId!,
          nearPostNumber: widget.initialPostNumber,
        );
      }
    } else if (widget.topicId != null &&
        widget.initialPostNumber != null &&
        widget.initialPostNumber != oldWidget.initialPostNumber) {
      _jumpToFloor(widget.initialPostNumber!);
    }
  }

  @override
  void dispose() {
    _timingDebounce?.cancel();
    _scrollController.dispose();
    super.dispose();
  }

  void _toast(String msg) {
    if (widget.onShowToast != null) {
      widget.onShowToast!(msg);
      return;
    }
    if (!mounted) return;
    ScaffoldMessenger.maybeOf(context)
      ?..hideCurrentSnackBar()
      ..showSnackBar(
        SnackBar(
          content: Text(msg),
          behavior: SnackBarBehavior.floating,
          duration: const Duration(seconds: 2),
        ),
      );
  }

  void _onScroll() {
    if (!_scrollController.hasClients) return;
    final pos = _scrollController.position;
    final nextSticky = pos.pixels > 96;
    if (nextSticky != _showStickyTitle) {
      setState(() => _showStickyTitle = nextSticky);
    }

    // Estimate current visible floor from scroll progress or rendered keys
    final detail = _detail;
    if (detail != null && detail.posts.isNotEmpty) {
      int? detectedFloor;
      for (final entry in _floorKeys.entries) {
        final ctx = entry.value.currentContext;
        if (ctx == null) continue;
        final box = ctx.findRenderObject();
        if (box is RenderBox && box.hasSize) {
          final dy = box.localToGlobal(Offset.zero).dy;
          if (dy >= 60 && dy <= 420) {
            detectedFloor = entry.key;
            break;
          }
        }
      }
      if (detectedFloor != null && detectedFloor != _currentFloor) {
        setState(() => _currentFloor = detectedFloor!);
        _scheduleTimingRecord(detail, detectedFloor);
      }

      // Auto-expand trailing floors when scrolling near bottom
      if (!_opOnly &&
          !_autoLoadingBottom &&
          pos.pixels >= pos.maxScrollExtent - 380) {
        final trailingIds = _computeTrailingMissingIds(detail);
        if (trailingIds.isNotEmpty) {
          _autoLoadTrailing(detail, trailingIds);
        }
      }
    }
  }

  List<int> _computeTrailingMissingIds(TopicDetailModel detail) {
    if (detail.streamIds.isEmpty || detail.posts.isEmpty) {
      return const <int>[];
    }
    final lastLoadedId = detail.posts.last.id;
    final idx = detail.streamIds.indexOf(lastLoadedId);
    if (idx < 0 || idx >= detail.streamIds.length - 1) {
      return const <int>[];
    }
    return detail.streamIds.sublist(idx + 1);
  }

  Future<void> _autoLoadTrailing(
    TopicDetailModel detail,
    List<int> trailingIds,
  ) async {
    _autoLoadingBottom = true;
    try {
      await _expandGap('trailing', trailingIds.take(30).toList(growable: false));
    } finally {
      _autoLoadingBottom = false;
    }
  }

  void _scheduleTimingRecord(TopicDetailModel detail, int floor) {
    if (floor <= _highestRecordedFloor) return;
    _highestRecordedFloor = floor;
    _timingDebounce?.cancel();
    _timingDebounce = Timer(const Duration(milliseconds: 650), () {
      if (!mounted) return;
      final totalFloors = math.max(
        detail.postsCount,
        detail.highestPostNumber ?? detail.postsCount,
      );
      widget.store.recordTopicVisit(
        topicId: detail.id,
        title: detail.title,
        categoryName: detail.categoryName,
        categoryColorHex: detail.categoryColorHex,
        lastReadPostNumber: floor,
        postsCount: totalFloors,
      );
      widget.repository
          .recordTopicTiming(
            detail.id,
            postNumber: floor,
            title: detail.title,
            categoryName: detail.categoryName,
            categoryColorHex: detail.categoryColorHex,
            postsCount: totalFloors,
          )
          .catchError((Object _) {});
    });
  }

  Future<void> _loadTopic(int topicId, {int? nearPostNumber}) async {
    final gen = ++_loadGeneration;
    setState(() {
      _loading = true;
      _error = null;
    });

    try {
      final fetched = await widget.repository.fetchTopicDetail(
        topicId,
        nearPostNumber: nearPostNumber,
      );
      if (!mounted || gen != _loadGeneration) return;

      if (fetched.loadError != null && fetched.posts.isEmpty) {
        setState(() {
          _detail = fetched;
          _error = fetched.loadError;
          _loading = false;
        });
        return;
      }

      final initialFloor = nearPostNumber ??
          (fetched.posts.isNotEmpty ? fetched.posts.first.postNumber : 1);
      final totalFloors = math.max(
        fetched.postsCount,
        fetched.highestPostNumber ?? fetched.postsCount,
      );

      setState(() {
        _detail = fetched;
        _currentFloor = initialFloor.clamp(1, math.max(1, totalFloors));
        _loading = false;
      });

      widget.store.recordTopicVisit(
        topicId: fetched.id,
        title: fetched.title,
        categoryName: fetched.categoryName,
        categoryColorHex: fetched.categoryColorHex,
        lastReadPostNumber: _currentFloor,
        postsCount: totalFloors,
      );
      widget.repository
          .recordTopicTiming(
            fetched.id,
            postNumber: _currentFloor,
            title: fetched.title,
            categoryName: fetched.categoryName,
            categoryColorHex: fetched.categoryColorHex,
            postsCount: totalFloors,
          )
          .catchError((Object _) {});

      if (nearPostNumber != null && nearPostNumber > 1) {
        WidgetsBinding.instance.addPostFrameCallback((_) {
          _scrollToRenderedFloor(nearPostNumber);
        });
      } else if (_scrollController.hasClients) {
        _scrollController.jumpTo(0);
      }
    } catch (err) {
      if (!mounted || gen != _loadGeneration) return;
      setState(() {
        _error = err.toString();
        _loading = false;
      });
    }
  }

  void _scrollToRenderedFloor(int targetFloor) {
    final key = _floorKeys[targetFloor];
    final ctx = key?.currentContext;
    if (ctx != null) {
      Scrollable.ensureVisible(
        ctx,
        duration: const Duration(milliseconds: 260),
        curve: Curves.easeOutCubic,
        alignment: 0.08,
      );
    }
  }

  Future<void> _jumpToFloor(int targetFloor) async {
    final detail = _detail;
    if (detail == null) return;
    final cleanFloor = targetFloor
        .clamp(
          1,
          math.max(1, detail.highestPostNumber ?? detail.postsCount),
        )
        .toInt();
    setState(() => _currentFloor = cleanFloor);

    // If already loaded in memory, scroll directly
    if (detail.posts.any((p) => p.postNumber == cleanFloor)) {
      if (_opOnly) {
        final targetPost =
            detail.posts.firstWhere((p) => p.postNumber == cleanFloor);
        if (!_isOpPost(detail, targetPost)) {
          setState(() => _opOnly = false);
        }
      }
      WidgetsBinding.instance.addPostFrameCallback((_) {
        _scrollToRenderedFloor(cleanFloor);
      });
      return;
    }

    // Otherwise load topic window centered near target floor
    await _loadTopic(detail.id, nearPostNumber: cleanFloor);
  }

  Future<void> _expandGap(String gapKey, List<int> missingIds) async {
    final detail = _detail;
    if (detail == null || missingIds.isEmpty) return;
    if (_loadingGapKeys.contains(gapKey)) return;

    final batch = missingIds.take(35).toList(growable: false);
    setState(() => _loadingGapKeys.add(gapKey));
    try {
      final fetchedPosts =
          await widget.repository.fetchTopicPostsByIds(detail.id, batch);
      if (!mounted || _detail?.id != detail.id) return;
      final byId = <int, PostItemModel>{
        for (final p in _detail!.posts) p.id: p,
        for (final p in fetchedPosts) p.id: p,
      };
      final merged = byId.values.toList()
        ..sort((a, b) => a.postNumber.compareTo(b.postNumber));
      setState(() {
        _detail = _detail!.copyWith(posts: merged);
        _loadingGapKeys.remove(gapKey);
      });
    } catch (_) {
      if (mounted) {
        setState(() => _loadingGapKeys.remove(gapKey));
        _toast(LinDoReaderCopy.FLOOR_LOAD_FAILED.text);
      }
    }
  }

  bool _isOpPost(TopicDetailModel detail, PostItemModel post) {
    if (post.postNumber == 1 || post.isTopicOwner) return true;
    final opUser = (detail.originalPosterUsername ?? '').trim().toLowerCase();
    return opUser.isNotEmpty && post.username.toLowerCase() == opUser;
  }

  List<_ReaderStreamEntry> _buildStreamEntries(TopicDetailModel detail) {
    final posts = _opOnly
        ? detail.posts.where((p) => _isOpPost(detail, p)).toList(growable: false)
        : detail.posts;

    if (_opOnly || detail.streamIds.isEmpty || posts.isEmpty) {
      return posts.map(_PostStreamEntry.new).toList(growable: false);
    }

    final indexById = <int, int>{
      for (var i = 0; i < detail.streamIds.length; i++) detail.streamIds[i]: i,
    };
    final loadedIds = <int>{for (final p in posts) p.id};
    final out = <_ReaderStreamEntry>[];

    // Check leading gap before first loaded post (if OP is missing or gap after #1)
    final firstIdx = indexById[posts.first.id];
    if (firstIdx != null && firstIdx > 0) {
      final leadingMissing = detail.streamIds
          .sublist(0, firstIdx)
          .where((id) => !loadedIds.contains(id))
          .toList(growable: false);
      if (leadingMissing.isNotEmpty) {
        out.add(
          _GapStreamEntry(
            gapKey: 'leading_${posts.first.id}',
            missingPostIds: leadingMissing,
            isLeading: true,
          ),
        );
      }
    }

    for (var i = 0; i < posts.length; i++) {
      final current = posts[i];
      out.add(_PostStreamEntry(current));

      if (i < posts.length - 1) {
        final next = posts[i + 1];
        final curStreamIdx = indexById[current.id];
        final nextStreamIdx = indexById[next.id];
        if (curStreamIdx != null &&
            nextStreamIdx != null &&
            nextStreamIdx - curStreamIdx > 1) {
          final gapIds = detail.streamIds
              .sublist(curStreamIdx + 1, nextStreamIdx)
              .where((id) => !loadedIds.contains(id))
              .toList(growable: false);
          if (gapIds.isNotEmpty) {
            out.add(
              _GapStreamEntry(
                gapKey: 'gap_${current.id}_${next.id}',
                missingPostIds: gapIds,
              ),
            );
          }
        }
      }
    }

    // Trailing gap after last loaded post
    final lastIdx = indexById[posts.last.id];
    if (lastIdx != null && lastIdx < detail.streamIds.length - 1) {
      final trailingMissing = detail.streamIds
          .sublist(lastIdx + 1)
          .where((id) => !loadedIds.contains(id))
          .toList(growable: false);
      if (trailingMissing.isNotEmpty) {
        out.add(
          _GapStreamEntry(
            gapKey: 'trailing_${posts.last.id}',
            missingPostIds: trailingMissing,
            isTrailing: true,
          ),
        );
      }
    }

    return out;
  }

  void _replacePostInState(PostItemModel updated) {
    final detail = _detail;
    if (detail == null) return;
    final nextPosts = detail.posts
        .map((p) => p.id == updated.id ? updated : p)
        .toList(growable: false);
    setState(() {
      _detail = detail.copyWith(posts: nextPosts);
    });
  }

  // ---------------------------------------------------------------------------
  // Topic & Post Interactive Actions
  // ---------------------------------------------------------------------------

  Future<bool> _ensureAuthorized(GuestGateScenario scenario) async {
    if (widget.repository.isAuthorized) return true;
    await showGuestLoginGuideSheet(
      context,
      scenario: scenario,
      onStartWebLogin: widget.onStartWebLogin,
    );
    return false;
  }

  Future<void> _toggleTopicBookmark() async {
    final detail = _detail;
    if (detail == null) return;
    if (!await _ensureAuthorized(GuestGateScenario.BOOKMARK_TOPIC)) return;

    final nextState = !detail.bookmarked;
    setState(() => _detail = detail.copyWith(bookmarked: nextState));
    try {
      await widget.repository.toggleTopicBookmark(
        detail.id,
        bookmarked: nextState,
      );
    } catch (_) {
      if (mounted) {
        setState(() => _detail = detail.copyWith(bookmarked: !nextState));
        _toast(LinDoReaderCopy.BOOKMARK_FAILED.text);
      }
    }
  }

  Future<void> _setNotificationLevel(TopicNotificationLevel level) async {
    final detail = _detail;
    if (detail == null) return;
    if (!await _ensureAuthorized(GuestGateScenario.SUBSCRIBE_TOPIC)) return;

    final prev = detail.notificationLevel;
    setState(() => _detail = detail.copyWith(notificationLevel: level.code));
    try {
      await widget.repository.updateTopicNotificationLevel(detail.id, level);
      _toast(level.menuLabel.text);
    } catch (_) {
      if (mounted) {
        setState(() => _detail = detail.copyWith(notificationLevel: prev));
        _toast(LinDoReaderCopy.SUBSCRIPTION_UPDATE_FAILED.text);
      }
    }
  }

  Future<void> _handleHeartToggle(PostItemModel post) async {
    if (!await _ensureAuthorized(GuestGateScenario.REACTION)) return;
    final myUser = widget.repository.currentUser?.username.toLowerCase();
    if (myUser != null && post.username.toLowerCase() == myUser) {
      _toast(LinDoReaderCopy.CANNOT_REACT_OWN_POST.text);
      return;
    }

    try {
      final updated = await widget.repository.togglePostLike(
        post.id,
        currentlyLiked: post.likedByMe,
      );
      if (updated != null && mounted) {
        _replacePostInState(updated);
      }
    } catch (_) {
      _toast(LinDoReaderCopy.LIKE_OR_REACTION_FAILED.text);
    }
  }

  Future<void> _handleReactionToggle(
    PostItemModel post,
    String reactionId,
  ) async {
    if (!await _ensureAuthorized(GuestGateScenario.REACTION)) return;
    final myUser = widget.repository.currentUser?.username.toLowerCase();
    if (myUser != null && post.username.toLowerCase() == myUser) {
      _toast(LinDoReaderCopy.CANNOT_REACT_OWN_POST.text);
      return;
    }

    try {
      final updated =
          await widget.repository.togglePostReaction(post.id, reactionId);
      if (updated != null && mounted) {
        _replacePostInState(updated);
      }
    } catch (_) {
      _toast(LinDoReaderCopy.LIKE_OR_REACTION_FAILED.text);
    }
  }

  Future<void> _openReactionPicker(PostItemModel post) async {
    if (!await _ensureAuthorized(GuestGateScenario.REACTION)) return;
    final myUser = widget.repository.currentUser?.username.toLowerCase();
    if (myUser != null && post.username.toLowerCase() == myUser) {
      _toast(LinDoReaderCopy.CANNOT_REACT_OWN_POST.text);
      return;
    }
    if (!mounted) return;

    final chosen = await showModalBottomSheet<String>(
      context: context,
      backgroundColor: Colors.transparent,
      builder: (ctx) {
        final palette = LinDoThemeScope.paletteOf(ctx);
        final scope = LinDoThemeScope.of(ctx);
        final reactions = LinuxDoEmojiCatalog.instance.defaultReactions;
        return SafeArea(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Center(
              heightFactor: 1,
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 460),
                child: Material(
                  color: palette.cardSurface,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(22),
                    side: BorderSide(color: palette.hairline),
                  ),
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(18, 16, 18, 18),
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            Text(
                              LinDoReaderCopy.EMOJI_REACTION_MENU.text,
                              style: TextStyle(
                                fontSize: scope.sp(15),
                                fontWeight: FontWeight.w700,
                                color: palette.inkPrimary,
                              ),
                            ),
                            const Spacer(),
                            IconButton(
                              visualDensity: VisualDensity.compact,
                              onPressed: () => Navigator.of(ctx).pop(),
                              icon: Icon(
                                LinDoIcons.Close,
                                size: 18,
                                color: palette.inkSecondary,
                              ),
                            ),
                          ],
                        ),
                        const SizedBox(height: 10),
                        Wrap(
                          spacing: 10,
                          runSpacing: 10,
                          children: reactions.map((emoji) {
                            final active = post.myReactionId == emoji.name ||
                                (emoji.name == 'heart' && post.likedByMe);
                            return InkWell(
                              borderRadius: BorderRadius.circular(14),
                              onTap: () => Navigator.of(ctx).pop(emoji.name),
                              child: Container(
                                width: 46,
                                height: 46,
                                alignment: Alignment.center,
                                decoration: BoxDecoration(
                                  color: active
                                      ? palette.activeChipBg
                                      : palette.subtleSurface,
                                  borderRadius: BorderRadius.circular(14),
                                  border: Border.all(
                                    color: active
                                        ? palette.logoDoColor
                                        : palette.hairline,
                                  ),
                                ),
                                child: emoji.unicodeChar != null &&
                                        emoji.unicodeChar!.isNotEmpty
                                    ? Text(
                                        emoji.unicodeChar!,
                                        style: const TextStyle(fontSize: 22),
                                      )
                                    : LinDoNetworkImage(
                                        url: emoji.url,
                                        width: 26,
                                        height: 26,
                                        fit: BoxFit.contain,
                                      ),
                              ),
                            );
                          }).toList(),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),
        );
      },
    );

    if (chosen != null && chosen.isNotEmpty) {
      await _handleReactionToggle(post, chosen);
    }
  }

  Future<void> _togglePostBookmark(PostItemModel post) async {
    if (!await _ensureAuthorized(GuestGateScenario.BOOKMARK_TOPIC)) return;
    final nextVal = !post.bookmarkedByMe;
    _replacePostInState(post.copyWith(bookmarkedByMe: nextVal));
    try {
      await widget.repository.togglePostBookmark(post.id, bookmarked: nextVal);
    } catch (_) {
      _replacePostInState(post.copyWith(bookmarkedByMe: !nextVal));
      _toast(LinDoReaderCopy.BOOKMARK_FAILED.text);
    }
  }

  Future<void> _openReplyComposer({
    PostItemModel? replyToPost,
    bool withQuote = false,
  }) async {
    final detail = _detail;
    if (detail == null) return;
    if (!await _ensureAuthorized(GuestGateScenario.REPLY_TOPIC)) return;
    if (!mounted) return;

    String? quoteMarkdown;
    if (withQuote && replyToPost != null) {
      final snippet = decodeDiscourseHtmlSnippet(
        replyToPost.cookedHtml,
        preserveLineBreaks: true,
      );
      final trimmed = snippet.length > 260
          ? '${snippet.substring(0, 260)}…'
          : snippet;
      quoteMarkdown =
          '[quote="${replyToPost.username}, post:${replyToPost.postNumber}, topic:${detail.id}"]\n$trimmed\n[/quote]\n\n';
    }

    final outcome = await showLinDoComposerSheet(
      context,
      repository: widget.repository,
      store: widget.store,
      action: ComposerAction.REPLY,
      topicId: detail.id,
      topicTitle: detail.title,
      replyToPostNumber: replyToPost?.postNumber,
      replyToUsername: replyToPost?.username,
      replyToAvatarTemplate: replyToPost?.avatarTemplate,
      initialQuote: quoteMarkdown,
      minBodyLength: detail.minWordCountRule,
      onStartSiteVerify: widget.onStartSiteVerify,
    );

    if (outcome != null && outcome.success && mounted) {
      if (outcome.post != null && _detail != null) {
        final merged = <PostItemModel>[
          ..._detail!.posts.where((p) => p.id != outcome.post!.id),
          outcome.post!,
        ]..sort((a, b) => a.postNumber.compareTo(b.postNumber));
        setState(() {
          _detail = _detail!.copyWith(
            posts: merged,
            postsCount: math.max(_detail!.postsCount + 1, merged.length),
          );
        });
        WidgetsBinding.instance.addPostFrameCallback((_) {
          _scrollToRenderedFloor(outcome.post!.postNumber);
        });
      } else {
        await _loadTopic(detail.id, nearPostNumber: detail.postsCount + 1);
      }
    }
  }

  Future<void> _openEditComposer(PostItemModel post) async {
    final detail = _detail;
    if (detail == null) return;
    if (!await _ensureAuthorized(GuestGateScenario.PUBLISH_CONTENT)) return;

    String rawMarkdown = post.raw ?? '';
    if (rawMarkdown.trim().isEmpty) {
      try {
        rawMarkdown = await widget.repository.fetchPostRaw(
          post.id,
          topicId: detail.id,
          postNumber: post.postNumber,
        );
      } catch (_) {
        rawMarkdown = decodeDiscourseHtmlSnippet(
          post.cookedHtml,
          preserveLineBreaks: true,
        );
      }
    }
    if (!mounted) return;

    final isOpEdit = post.postNumber == 1 && detail.canEditTopic;
    final outcome = await showLinDoComposerSheet(
      context,
      repository: widget.repository,
      store: widget.store,
      action: isOpEdit ? ComposerAction.EDIT_TOPIC : ComposerAction.EDIT_POST,
      topicId: detail.id,
      topicTitle: detail.title,
      editPostId: post.id,
      initialRaw: rawMarkdown,
      initialTitle: isOpEdit ? detail.title : null,
      initialCategoryId: isOpEdit ? detail.categoryId : null,
      initialTags: isOpEdit ? detail.tags : null,
      onStartSiteVerify: widget.onStartSiteVerify,
    );

    if (outcome != null && outcome.success && mounted) {
      await _loadTopic(detail.id, nearPostNumber: post.postNumber);
    }
  }

  Future<void> _submitBoost(PostItemModel post, String rawText) async {
    if (!await _ensureAuthorized(GuestGateScenario.BOOST)) return;
    try {
      final created = await widget.repository.submitPostBoost(post.id, rawText);
      if (!mounted) return;
      _replacePostInState(
        post.copyWith(boosts: <PostBoostItem>[...post.boosts, created]),
      );
      setState(() => _boostComposerPostId = null);
    } catch (err) {
      _toast(err.toString());
    }
  }

  Future<void> _deleteBoost(PostItemModel post, PostBoostItem boost) async {
    try {
      final ok = await widget.repository.deletePostBoost(
        boost.id,
        postId: post.id,
      );
      if (ok && mounted) {
        _replacePostInState(
          post.copyWith(
            boosts: post.boosts.where((b) => b.id != boost.id).toList(),
          ),
        );
      }
    } catch (_) {
      _toast(LinDoReaderCopy.BOOST_DELETE_FAILED_SHORT.text);
    }
  }

  Future<PollModel?> _handleVotePoll(
    PostItemModel post,
    String pollName,
    List<String> optionIds,
  ) async {
    if (!await _ensureAuthorized(GuestGateScenario.POLL)) return null;
    try {
      final updatedPoll = await widget.repository.votePoll(
        postId: post.id,
        pollName: pollName,
        options: optionIds,
      );
      if (mounted) {
        final nextPolls = post.polls
            .map((p) => p.name == pollName ? updatedPoll : p)
            .toList(growable: false);
        _replacePostInState(post.copyWith(polls: nextPolls));
      }
      return updatedPoll;
    } catch (_) {
      _toast(LinDoReaderCopy.POLL_SUBMIT_FAILED_RETRY.text);
      return null;
    }
  }

  Future<void> _openFloorNavigator() async {
    final detail = _detail;
    if (detail == null) return;
    final total = math.max(
      detail.postsCount,
      detail.highestPostNumber ?? detail.postsCount,
    );
    final chosen = await showTopicFloorJumpSheet(
      context,
      currentFloor: _currentFloor,
      totalFloors: total,
    );
    if (chosen != null) {
      await _jumpToFloor(chosen);
    }
  }

  void _openUserCard(String username) {
    if (username.trim().isEmpty) return;
    showPublicUserCardSheet(
      context,
      username: username,
      repository: widget.repository,
      store: widget.store,
      onOpenTopic: widget.onOpenTopic,
      onStartSiteVerify: widget.onStartSiteVerify,
      onRequestGuestLogin: widget.onStartWebLogin,
      onShowToast: widget.onShowToast,
    );
  }

  void _openUrlInAppOrInternal(String rawUrl) {
    final internal = parseInternalTopicUrl(rawUrl);
    if (internal != null) {
      if (_detail != null && internal.topicId == _detail!.id) {
        if (internal.postNumber != null) {
          _jumpToFloor(internal.postNumber!);
        }
        return;
      }
      widget.onOpenTopic(
        internal.topicId,
        postNumber: internal.postNumber,
      );
      return;
    }
    final resolved = resolveDiscourseUrl(rawUrl);
    if (resolved.isEmpty) return;
    showLinDoWebAuthModal(
      context,
      initialUrl: resolved,
      mode: WebAuthModalMode.inAppBrowser,
    );
  }

  String _computeDigestText(TopicDetailModel detail) {
    final ai = (detail.aiSummary ?? '').trim();
    if (ai.isNotEmpty) return decodeDiscourseHtmlSnippet(ai, preserveLineBreaks: true);
    if (detail.posts.isEmpty) return LinDoReaderCopy.EMPTY_DIGEST.text;
    final opSnippet = decodeDiscourseHtmlSnippet(detail.posts.first.cookedHtml);
    if (opSnippet.isEmpty) return LinDoReaderCopy.EMPTY_DIGEST.text;
    return opSnippet.length > 220
        ? '${opSnippet.substring(0, 220)}…'
        : opSnippet;
  }

  // ---------------------------------------------------------------------------
  // Build
  // ---------------------------------------------------------------------------

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    final scope = LinDoThemeScope.of(context);

    // Desktop split-mode empty placeholder
    if (widget.topicId == null || widget.topicId! <= 0) {
      return ColoredBox(
        color: palette.canvas,
        child: Center(
          child: LinDoStatePanel(
            mood: MascotMood.sleepy,
            title: LinDoReaderCopy.EMPTY_READER_PLACEHOLDER.text,
          ),
        ),
      );
    }

    final isSiteVerify = widget.repository.siteVerificationRequired ||
        _error.isSiteVerificationError;

    return ColoredBox(
      color: palette.canvas,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _buildTopToolbar(context, palette, scope),
          Divider(height: 1, color: palette.hairline),
          Expanded(
            child: _loading && _detail == null
                ? Center(
                    child: LinDoStatePanel(
                      mood: MascotMood.connecting,
                      title: LinDoCommonCopy.LOADING.text,
                    ),
                  )
                : isSiteVerify && (_detail == null || _detail!.posts.isEmpty)
                    ? Center(
                        child: Padding(
                          padding: const EdgeInsets.all(20),
                          child: LinDoSiteVerificationCard(
                            onStartInAppVerify: () async {
                              await widget.onStartSiteVerify();
                              if (mounted && widget.topicId != null) {
                                _loadTopic(
                                  widget.topicId!,
                                  nearPostNumber: widget.initialPostNumber,
                                );
                              }
                            },
                            onRetry: () {
                              if (widget.topicId != null) {
                                _loadTopic(
                                  widget.topicId!,
                                  nearPostNumber: widget.initialPostNumber,
                                );
                              }
                            },
                          ),
                        ),
                      )
                    : (_error != null &&
                            (_detail == null || _detail!.posts.isEmpty))
                        ? Center(
                            child: _buildErrorState(context),
                          )
                        : _detail == null
                            ? const SizedBox.shrink()
                            : _buildReaderBody(context, palette, scope, _detail!),
          ),
        ],
      ),
    );
  }

  Widget _buildErrorState(BuildContext context) {
    final kind = UiErrorKind.classify(
      _error,
      isAuthorized: widget.repository.isAuthorized,
    );
    if (kind == UiErrorKind.GUEST_LOGIN_REQUIRED) {
      return LinDoStatePanel(
        mood: MascotMood.wink,
        title: GuestGateScenario.TOPIC_LOGIN_REQUIRED.titleCopy.text,
        subtitle: _error ??
            GuestGateScenario.TOPIC_LOGIN_REQUIRED.subtitleCopy.text,
        actionLabel: LinDoAuthCopy.LOGIN_WITH_LINUX_DO.text,
        onAction: widget.onStartWebLogin,
      );
    }
    return LinDoStatePanel(
      mood: MascotMood.surprised,
      title: LinDoCommonCopy.LOAD_FAILED.text,
      subtitle: _error,
      actionLabel: LinDoCommonCopy.RETRY.text,
      onAction: () {
        if (widget.topicId != null) {
          _loadTopic(widget.topicId!, nearPostNumber: widget.initialPostNumber);
        }
      },
    );
  }

  Widget _buildTopToolbar(
    BuildContext context,
    LinDoThemePalette palette,
    LinDoThemeScope scope,
  ) {
    final detail = _detail;
    final notifLevel = TopicNotificationLevel.fromCode(
      detail?.notificationLevel ?? 1,
    );
    final displayTitle = _showStickyTitle
        ? (detail?.title ??
            widget.initialTitle ??
            LinDoReaderCopy.TOPIC_HEADER_TITLE.text)
        : LinDoReaderCopy.TOPIC_HEADER_TITLE.text;

    return SizedBox(
      height: 48,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 8),
        child: Row(
          children: [
            if (widget.onClose != null || widget.showBackButton)
              IconButton(
                tooltip: LinDoCommonCopy.BACK.text,
                onPressed: () {
                  if (widget.onClose != null) {
                    widget.onClose!();
                  } else {
                    Navigator.of(context).maybePop();
                  }
                },
                icon: Icon(
                  widget.showBackButton
                      ? LinDoIcons.ArrowLeft
                      : LinDoIcons.Close,
                  size: 20,
                  color: palette.inkPrimary,
                ),
              )
            else
              const SizedBox(width: 8),
            Expanded(
              child: AnimatedSwitcher(
                duration: const Duration(milliseconds: 160),
                child: Align(
                  key: ValueKey<bool>(_showStickyTitle),
                  alignment: Alignment.centerLeft,
                  child: Text(
                    displayTitle,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: scope.sp(_showStickyTitle ? 14.5 : 15),
                      fontWeight: FontWeight.w700,
                      color: palette.inkPrimary,
                    ),
                  ),
                ),
              ),
            ),
            if (detail != null) ...[
              // OP-only pill
              InkWell(
                borderRadius: BorderRadius.circular(999),
                onTap: () => setState(() => _opOnly = !_opOnly),
                child: Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 10,
                    vertical: 4.5,
                  ),
                  decoration: BoxDecoration(
                    color: _opOnly
                        ? palette.activeChipBg
                        : palette.subtleSurface,
                    borderRadius: BorderRadius.circular(999),
                    border: Border.all(
                      color: _opOnly
                          ? palette.activeChipText.withValues(alpha: 0.35)
                          : palette.hairline,
                    ),
                  ),
                  child: Text(
                    LinDoReaderCopy.OP_ONLY_MODE.text,
                    style: TextStyle(
                      fontSize: scope.sp(11.5),
                      fontWeight: _opOnly ? FontWeight.w700 : FontWeight.w500,
                      color: _opOnly
                          ? palette.activeChipText
                          : palette.inkSecondary,
                    ),
                  ),
                ),
              ),
              const SizedBox(width: 4),

              // Notification subscription menu
              PopupMenuButton<TopicNotificationLevel>(
                tooltip: notifLevel.menuLabel.text,
                color: palette.cardSurface,
                onSelected: _setNotificationLevel,
                itemBuilder: (ctx) => [
                  PopupMenuItem(
                    value: TopicNotificationLevel.WATCHING,
                    child: Text(LinDoReaderCopy.SUBSCRIPTION_WATCHING.text),
                  ),
                  PopupMenuItem(
                    value: TopicNotificationLevel.REGULAR,
                    child: Text(LinDoReaderCopy.SUBSCRIPTION_DEFAULT.text),
                  ),
                  PopupMenuItem(
                    value: TopicNotificationLevel.MUTED,
                    child: Text(LinDoReaderCopy.SUBSCRIPTION_MUTED.text),
                  ),
                ],
                icon: Icon(
                  notifLevel == TopicNotificationLevel.WATCHING
                      ? LinDoIcons.BellSelected
                      : LinDoIcons.Bell,
                  size: 19,
                  color: notifLevel == TopicNotificationLevel.WATCHING
                      ? palette.logoDoColor
                      : palette.inkSecondary,
                ),
              ),

              // Topic Bookmark
              IconButton(
                visualDensity: VisualDensity.compact,
                tooltip: LinDoReaderCopy.BOOKMARK_ACTION.text,
                onPressed: _toggleTopicBookmark,
                icon: Icon(
                  detail.bookmarked
                      ? LinDoIcons.BookmarkFilled
                      : LinDoIcons.Bookmark,
                  size: 18.5,
                  color: detail.bookmarked
                      ? palette.logoDoColor
                      : palette.inkSecondary,
                ),
              ),

              // Quick Font Scale Cycle
              IconButton(
                visualDensity: VisualDensity.compact,
                tooltip: LinDoMessagesCopy.ROW_READING_FONT_SCALE.text,
                onPressed: () {
                  final prefs = widget.store.preferences;
                  final next = ReaderFontScalePreset.fromScale(prefs.fontScale)
                      .nextCycle();
                  widget.store.updatePreferences(
                    prefs.copyWith(fontScale: next.scale),
                  );
                  _toast(
                    '${LinDoMessagesCopy.ROW_READING_FONT_SCALE.text}：${next.labelCopy.text}',
                  );
                },
                icon: Icon(
                  LinDoIcons.Heading,
                  size: 18.5,
                  color: palette.inkSecondary,
                ),
              ),

              // More actions menu
              PopupMenuButton<String>(
                tooltip: LinDoReaderCopy.MORE_ACTIONS_DESC.text,
                color: palette.cardSurface,
                onSelected: (action) async {
                  final topicUrl = '$kLinuxDoBaseUrl/t/topic/${detail.id}';
                  switch (action) {
                    case 'web':
                      await showLinDoWebAuthModal(
                        context,
                        initialUrl: topicUrl,
                        mode: WebAuthModalMode.inAppBrowser,
                        customTitle: detail.title,
                      );
                      break;
                    case 'copy':
                      await Clipboard.setData(ClipboardData(text: topicUrl));
                      _toast(LinDoReaderCopy.LINK_COPIED.text);
                      break;
                    case 'refresh':
                      await _loadTopic(detail.id, nearPostNumber: _currentFloor);
                      break;
                    case 'edit_topic':
                      if (detail.posts.isNotEmpty) {
                        await _openEditComposer(detail.posts.first);
                      }
                      break;
                  }
                },
                itemBuilder: (ctx) => [
                  PopupMenuItem(
                    value: 'web',
                    child: Row(
                      children: [
                        const Icon(LinDoIcons.Globe, size: 16),
                        const SizedBox(width: 8),
                        Text(LinDoReaderCopy.ORIGINAL_WEB_POST.text),
                      ],
                    ),
                  ),
                  PopupMenuItem(
                    value: 'copy',
                    child: Row(
                      children: [
                        const Icon(LinDoIcons.Link, size: 16),
                        const SizedBox(width: 8),
                        Text(LinDoReaderCopy.COPY_LINK.text),
                      ],
                    ),
                  ),
                  PopupMenuItem(
                    value: 'refresh',
                    child: Row(
                      children: [
                        const Icon(LinDoIcons.Refresh, size: 16),
                        const SizedBox(width: 8),
                        Text(LinDoCommonCopy.REFRESH.text),
                      ],
                    ),
                  ),
                  if (detail.canEditTopic && detail.posts.isNotEmpty)
                    PopupMenuItem(
                      value: 'edit_topic',
                      child: Row(
                        children: [
                          const Icon(LinDoIcons.Edit, size: 16),
                          const SizedBox(width: 8),
                          Text(LinDoReaderCopy.EDIT_TOPIC_MENU.text),
                        ],
                      ),
                    ),
                ],
                icon: Icon(
                  LinDoIcons.MoreHoriz,
                  size: 20,
                  color: palette.inkSecondary,
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildReaderBody(
    BuildContext context,
    LinDoThemePalette palette,
    LinDoThemeScope scope,
    TopicDetailModel detail,
  ) {
    final entries = _buildStreamEntries(detail);
    final totalFloors = math.max(
      detail.postsCount,
      detail.highestPostNumber ?? detail.postsCount,
    );
    final repliesCount = math.max(0, totalFloors - 1);

    return Column(
      children: [
        Expanded(
          child: Stack(
            children: [
              RefreshIndicator(
                color: palette.logoDoColor,
                onRefresh: () =>
                    _loadTopic(detail.id, nearPostNumber: _currentFloor),
                child: CustomScrollView(
                  controller: _scrollController,
                  physics: const AlwaysScrollableScrollPhysics(),
                  slivers: [
                    // Topic Header Card
                    SliverToBoxAdapter(
                      child: Center(
                        child: ConstrainedBox(
                          constraints: const BoxConstraints(maxWidth: 880),
                          child: Padding(
                            padding: const EdgeInsets.fromLTRB(18, 16, 18, 12),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                SelectableText(
                                  detail.title,
                                  style: TextStyle(
                                    fontSize: scope.sp(21),
                                    fontWeight: FontWeight.w800,
                                    height: 1.35,
                                    color: palette.inkPrimary,
                                  ),
                                ),
                                const SizedBox(height: 12),
                                Wrap(
                                  spacing: 8,
                                  runSpacing: 6,
                                  crossAxisAlignment: WrapCrossAlignment.center,
                                  children: [
                                    CategoryMorandiChip(
                                      categoryName: detail.categoryName,
                                      colorHex: detail.categoryColorHex,
                                    ),
                                    if (detail.closed)
                                      _StatusBadgePill(
                                        icon: LinDoIcons.Lock,
                                        label: LinDoReaderCopy.TOPIC_CLOSED.text,
                                      ),
                                    if (detail.archived)
                                      _StatusBadgePill(
                                        icon: LinDoIcons.Layers,
                                        label:
                                            LinDoReaderCopy.TOPIC_ARCHIVED.text,
                                      ),
                                    ...detail.tags.map((tag) {
                                      return InkWell(
                                        borderRadius: BorderRadius.circular(6),
                                        onTap: widget.onSelectTag == null
                                            ? null
                                            : () => widget.onSelectTag!(tag),
                                        child: Container(
                                          padding: const EdgeInsets.symmetric(
                                            horizontal: 8,
                                            vertical: 3,
                                          ),
                                          decoration: BoxDecoration(
                                            color: palette.subtleSurface,
                                            borderRadius:
                                                BorderRadius.circular(6),
                                            border: Border.all(
                                              color: palette.hairline,
                                            ),
                                          ),
                                          child: Text(
                                            '#$tag',
                                            style: TextStyle(
                                              fontSize: scope.sp(11.5),
                                              fontWeight: FontWeight.w600,
                                              color: palette.inkSecondary,
                                            ),
                                          ),
                                        ),
                                      );
                                    }),
                                  ],
                                ),
                                const SizedBox(height: 10),
                                Row(
                                  children: [
                                    Text(
                                      LinDoReaderCopy
                                          .TOPIC_VIEWS_AND_REPLIES_STATS
                                          .format([
                                        formatCompactCount(detail.views),
                                        formatCompactCount(repliesCount),
                                      ]),
                                      style: TextStyle(
                                        fontSize: scope.sp(12),
                                        color: palette.inkTertiary,
                                      ),
                                    ),
                                    const Spacer(),
                                    InkWell(
                                      borderRadius: BorderRadius.circular(999),
                                      onTap: () => setState(
                                        () => _summaryExpanded =
                                            !_summaryExpanded,
                                      ),
                                      child: Container(
                                        padding: const EdgeInsets.symmetric(
                                          horizontal: 10,
                                          vertical: 4,
                                        ),
                                        decoration: BoxDecoration(
                                          color: palette.tonalPill,
                                          borderRadius:
                                              BorderRadius.circular(999),
                                        ),
                                        child: Row(
                                          mainAxisSize: MainAxisSize.min,
                                          children: [
                                            Icon(
                                              LinDoIcons.Sparkles,
                                              size: 13,
                                              color: palette.logoDoColor,
                                            ),
                                            const SizedBox(width: 4),
                                            Text(
                                              _summaryExpanded
                                                  ? LinDoReaderCopy
                                                      .COLLAPSE_SUMMARY.text
                                                  : LinDoReaderCopy
                                                      .SUMMARY_SHORT.text,
                                              style: TextStyle(
                                                fontSize: scope.sp(11.5),
                                                fontWeight: FontWeight.w700,
                                                color: palette.inkPrimary,
                                              ),
                                            ),
                                          ],
                                        ),
                                      ),
                                    ),
                                  ],
                                ),
                                if (_summaryExpanded) ...[
                                  const SizedBox(height: 10),
                                  Container(
                                    width: double.infinity,
                                    padding: const EdgeInsets.all(14),
                                    decoration: BoxDecoration(
                                      color: palette.groupedCanvas,
                                      borderRadius: BorderRadius.circular(14),
                                      border: Border.all(
                                        color: palette.hairline,
                                      ),
                                    ),
                                    child: Column(
                                      crossAxisAlignment:
                                          CrossAxisAlignment.start,
                                      children: [
                                        Row(
                                          children: [
                                            Icon(
                                              LinDoIcons.Sparkles,
                                              size: 14,
                                              color: palette.logoDoColor,
                                            ),
                                            const SizedBox(width: 6),
                                            Text(
                                              (detail.aiSummary ?? '')
                                                      .trim()
                                                      .isNotEmpty
                                                  ? LinDoReaderCopy
                                                      .AI_SUMMARY_TITLE.text
                                                  : LinDoReaderCopy
                                                      .LOCAL_DIGEST_TITLE.text,
                                              style: TextStyle(
                                                fontSize: scope.sp(12.5),
                                                fontWeight: FontWeight.w700,
                                                color: palette.inkPrimary,
                                              ),
                                            ),
                                          ],
                                        ),
                                        const SizedBox(height: 6),
                                        SelectableText(
                                          _computeDigestText(detail),
                                          style: TextStyle(
                                            fontSize: scope.sp(13),
                                            height: 1.5,
                                            color: palette.inkSecondary,
                                          ),
                                        ),
                                      ],
                                    ),
                                  ),
                                ],
                              ],
                            ),
                          ),
                        ),
                      ),
                    ),
                    SliverToBoxAdapter(
                      child: Divider(height: 1, color: palette.hairline),
                    ),

                    // Virtualized Floor Stream
                    SliverPadding(
                      padding: const EdgeInsets.only(bottom: 76),
                      sliver: SliverList.builder(
                        itemCount: entries.length,
                        itemBuilder: (context, index) {
                          final entry = entries[index];
                          if (entry is _GapStreamEntry) {
                            final isLoadingGap =
                                _loadingGapKeys.contains(entry.gapKey);
                            return RepaintBoundary(
                              child: Center(
                                child: ConstrainedBox(
                                  constraints:
                                      const BoxConstraints(maxWidth: 880),
                                  child: _GapLoaderBanner(
                                    missingCount: entry.missingPostIds.length,
                                    isLeading: entry.isLeading,
                                    isTrailing: entry.isTrailing,
                                    loading: isLoadingGap,
                                    onTap: () => _expandGap(
                                      entry.gapKey,
                                      entry.missingPostIds,
                                    ),
                                  ),
                                ),
                              ),
                            );
                          }

                          final post = (entry as _PostStreamEntry).post;
                          final floorKey = _floorKeys.putIfAbsent(
                            post.postNumber,
                            () => GlobalKey(),
                          );

                          if (post.isSmallAction) {
                            return RepaintBoundary(
                              key: floorKey,
                              child: Center(
                                child: ConstrainedBox(
                                  constraints:
                                      const BoxConstraints(maxWidth: 880),
                                  child: _SmallActionDividerRow(post: post),
                                ),
                              ),
                            );
                          }

                          return RepaintBoundary(
                            key: floorKey,
                            child: Center(
                              child: ConstrainedBox(
                                constraints:
                                    const BoxConstraints(maxWidth: 880),
                                child: _PostFloorCard(
                                  post: post,
                                  topicDetail: detail,
                                  currentUser: widget.repository.currentUser,
                                  boostComposerOpen:
                                      _boostComposerPostId == post.id,
                                  onToggleBoostComposer: () {
                                    setState(() {
                                      _boostComposerPostId =
                                          _boostComposerPostId == post.id
                                              ? null
                                              : post.id;
                                    });
                                  },
                                  onSubmitBoost: (raw) =>
                                      _submitBoost(post, raw),
                                  onDeleteBoost: (boost) =>
                                      _deleteBoost(post, boost),
                                  onTapUser: _openUserCard,
                                  onJumpToFloor: _jumpToFloor,
                                  onVotePoll: (pollName, optionIds) =>
                                      _handleVotePoll(
                                    post,
                                    pollName,
                                    optionIds,
                                  ),
                                  onTapInternalTopic: (tId, pNum) {
                                    if (tId == detail.id && pNum != null) {
                                      _jumpToFloor(pNum);
                                    } else {
                                      widget.onOpenTopic(tId, postNumber: pNum);
                                    }
                                  },
                                  onTapUrl: _openUrlInAppOrInternal,
                                  onTapHeart: () => _handleHeartToggle(post),
                                  onOpenReactionPicker: () =>
                                      _openReactionPicker(post),
                                  onToggleReaction: (reactionId) =>
                                      _handleReactionToggle(post, reactionId),
                                  onQuoteReply: () => _openReplyComposer(
                                    replyToPost: post,
                                    withQuote: true,
                                  ),
                                  onFloorReply: () => _openReplyComposer(
                                    replyToPost: post,
                                  ),
                                  onToggleBookmark: () =>
                                      _togglePostBookmark(post),
                                  onEditPost: () => _openEditComposer(post),
                                  onOpenBadge: (badge) => showBadgeDetailSheet(
                                    context,
                                    badge: badge,
                                    repository: widget.repository,
                                    onOpenTopic: (tId, {postNumber}) =>
                                        widget.onOpenTopic(
                                      tId,
                                      postNumber: postNumber,
                                    ),
                                    onShowToast: _toast,
                                  ),
                                ),
                              ),
                            ),
                          );
                        },
                      ),
                    ),
                  ],
                ),
              ),

              // Floating Floor Navigator Pill
              if (totalFloors > 1)
                Positioned(
                  right: 16,
                  bottom: 14,
                  child: TopicFloorNavigatorPill(
                    currentFloor: _currentFloor,
                    totalFloors: totalFloors,
                    onTap: _openFloorNavigator,
                  ),
                ),
            ],
          ),
        ),

        // Bottom Quick Reply Bar
        _buildBottomReplyBar(context, palette, scope, detail),
      ],
    );
  }

  Widget _buildBottomReplyBar(
    BuildContext context,
    LinDoThemePalette palette,
    LinDoThemeScope scope,
    TopicDetailModel detail,
  ) {
    final isLocked = detail.closed || detail.archived;
    return SafeArea(
      top: false,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
        decoration: BoxDecoration(
          color: palette.cardSurface,
          border: Border(top: BorderSide(color: palette.hairline)),
        ),
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 880),
            child: Row(
              children: [
                Expanded(
                  child: Material(
                    color: palette.tonalPill,
                    borderRadius: BorderRadius.circular(999),
                    child: InkWell(
                      borderRadius: BorderRadius.circular(999),
                      onTap: isLocked ? null : () => _openReplyComposer(),
                      child: Padding(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 16,
                          vertical: 10,
                        ),
                        child: Row(
                          children: [
                            Icon(
                              isLocked ? LinDoIcons.Lock : LinDoIcons.Edit,
                              size: 15.5,
                              color: palette.inkTertiary,
                            ),
                            const SizedBox(width: 8),
                            Expanded(
                              child: Text(
                                detail.closed
                                    ? LinDoReaderCopy.TOPIC_CLOSED.text
                                    : detail.archived
                                        ? LinDoReaderCopy.TOPIC_ARCHIVED.text
                                        : '说点什么，参与讨论…',
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: TextStyle(
                                  fontSize: scope.sp(13.5),
                                  color: palette.inkSecondary,
                                ),
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
                const SizedBox(width: 10),
                IconButton(
                  tooltip: LinDoReaderCopy.BOOKMARK_ACTION.text,
                  onPressed: _toggleTopicBookmark,
                  icon: Icon(
                    detail.bookmarked
                        ? LinDoIcons.BookmarkFilled
                        : LinDoIcons.Bookmark,
                    size: 20,
                    color: detail.bookmarked
                        ? palette.logoDoColor
                        : palette.inkSecondary,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _StatusBadgePill extends StatelessWidget {
  final IconData icon;
  final String label;

  const _StatusBadgePill({required this.icon, required this.label});

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    final scope = LinDoThemeScope.of(context);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: palette.tonalPill,
        borderRadius: BorderRadius.circular(999),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 12, color: palette.inkSecondary),
          const SizedBox(width: 4),
          Text(
            label,
            style: TextStyle(
              fontSize: scope.sp(11),
              fontWeight: FontWeight.w600,
              color: palette.inkSecondary,
            ),
          ),
        ],
      ),
    );
  }
}

class _GapLoaderBanner extends StatelessWidget {
  final int missingCount;
  final bool isLeading;
  final bool isTrailing;
  final bool loading;
  final VoidCallback onTap;

  const _GapLoaderBanner({
    required this.missingCount,
    required this.isLeading,
    required this.isTrailing,
    required this.loading,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    final scope = LinDoThemeScope.of(context);

    final String label;
    if (loading) {
      label = LinDoReaderCopy.EXPANDING_MIDDLE_FLOORS.text;
    } else if (isLeading) {
      label = '${LinDoReaderCopy.LOAD_EARLIER_FLOORS.text} ($missingCount)';
    } else if (isTrailing) {
      label = '${LinDoReaderCopy.LOAD_FOLLOWING_FLOORS.text} ($missingCount)';
    } else {
      label = LinDoReaderCopy.EXPAND_MIDDLE_GAP_COUNT.format(missingCount);
    }

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 8),
      child: Material(
        color: palette.groupedCanvas,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(12),
          side: BorderSide(color: palette.hairline),
        ),
        child: InkWell(
          borderRadius: BorderRadius.circular(12),
          onTap: loading ? null : onTap,
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: 11, horizontal: 16),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                if (loading)
                  const LinDoWaveDots()
                else ...[
                  Icon(
                    LinDoIcons.ChevronDown,
                    size: 16,
                    color: palette.logoDoColor,
                  ),
                  const SizedBox(width: 6),
                  Text(
                    label,
                    style: TextStyle(
                      fontSize: scope.sp(12.5),
                      fontWeight: FontWeight.w700,
                      color: palette.logoDoColor,
                    ),
                  ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _SmallActionDividerRow extends StatelessWidget {
  final PostItemModel post;

  const _SmallActionDividerRow({required this.post});

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    final scope = LinDoThemeScope.of(context);
    final actionText = SmallActionKind.localize(post.actionCode ?? '');

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 10),
      child: Row(
        children: [
          Expanded(child: Divider(height: 1, color: palette.hairline)),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12),
            child: Text(
              post.createdAtText.isNotEmpty
                  ? '$actionText · ${formatRelativeTime(post.createdAtText)}'
                  : actionText,
              style: TextStyle(
                fontSize: scope.sp(11.5),
                fontWeight: FontWeight.w600,
                color: palette.inkTertiary,
              ),
            ),
          ),
          Expanded(child: Divider(height: 1, color: palette.hairline)),
        ],
      ),
    );
  }
}

class _PostFloorCard extends StatelessWidget {
  final PostItemModel post;
  final TopicDetailModel topicDetail;
  final AuthenticatedUserProfile? currentUser;
  final bool boostComposerOpen;
  final VoidCallback onToggleBoostComposer;
  final Future<void> Function(String raw) onSubmitBoost;
  final Future<void> Function(PostBoostItem boost) onDeleteBoost;
  final ValueChanged<String> onTapUser;
  final ValueChanged<int> onJumpToFloor;
  final FutureOr<dynamic> Function(String pollName, List<String> optionIds)
      onVotePoll;
  final void Function(int topicId, int? postNumber) onTapInternalTopic;
  final ValueChanged<String> onTapUrl;
  final VoidCallback onTapHeart;
  final VoidCallback onOpenReactionPicker;
  final ValueChanged<String> onToggleReaction;
  final VoidCallback onQuoteReply;
  final VoidCallback onFloorReply;
  final VoidCallback onToggleBookmark;
  final VoidCallback onEditPost;
  final ValueChanged<GrantedBadge> onOpenBadge;

  const _PostFloorCard({
    required this.post,
    required this.topicDetail,
    required this.currentUser,
    required this.boostComposerOpen,
    required this.onToggleBoostComposer,
    required this.onSubmitBoost,
    required this.onDeleteBoost,
    required this.onTapUser,
    required this.onJumpToFloor,
    required this.onVotePoll,
    required this.onTapInternalTopic,
    required this.onTapUrl,
    required this.onTapHeart,
    required this.onOpenReactionPicker,
    required this.onToggleReaction,
    required this.onQuoteReply,
    required this.onFloorReply,
    required this.onToggleBookmark,
    required this.onEditPost,
    required this.onOpenBadge,
  });

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    final scope = LinDoThemeScope.of(context);
    final isOp = post.postNumber == 1 ||
        post.isTopicOwner ||
        (topicDetail.originalPosterUsername != null &&
            topicDetail.originalPosterUsername!.toLowerCase() ==
                post.username.toLowerCase());

    return Container(
      padding: const EdgeInsets.fromLTRB(18, 16, 18, 14),
      decoration: BoxDecoration(
        color: palette.canvas,
        border: Border(bottom: BorderSide(color: palette.hairline)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // 1. Floor Author Header
          Row(
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              SmartAvatar(
                avatarUrl: post.avatarTemplate,
                fallbackLabel: post.username,
                size: 38,
                flair: post.flair,
                trustLevel: post.trustLevel,
                isAdmin: post.admin,
                isModerator: post.moderator,
                animatedAvatar: post.animatedAvatar,
                primaryGroupName: post.primaryGroupName,
                onTap: () => onTapUser(post.username),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Wrap(
                      spacing: 6,
                      runSpacing: 3,
                      crossAxisAlignment: WrapCrossAlignment.center,
                      children: [
                        GestureDetector(
                          onTap: () => onTapUser(post.username),
                          child: Text(
                            post.effectiveName,
                            style: TextStyle(
                              fontSize: scope.sp(14),
                              fontWeight: FontWeight.w700,
                              color: palette.inkPrimary,
                            ),
                          ),
                        ),
                        if (post.effectiveName != post.username)
                          Text(
                            '@${post.username}',
                            style: TextStyle(
                              fontSize: scope.sp(12),
                              color: palette.inkTertiary,
                            ),
                          ),
                        UserTitleTag(
                          title: post.userTitle,
                          primaryGroupName: post.primaryGroupName,
                          trustLevel: post.trustLevel,
                          isTopicOwner: isOp,
                          isAdmin: post.admin,
                          isModerator: post.moderator,
                        ),
                      ],
                    ),
                    const SizedBox(height: 2),
                    Text(
                      formatRelativeTime(post.createdAtText),
                      style: TextStyle(
                        fontSize: scope.sp(11.5),
                        color: palette.inkTertiary,
                      ),
                    ),
                  ],
                ),
              ),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                decoration: BoxDecoration(
                  color: isOp ? palette.activeChipBg : palette.subtleSurface,
                  borderRadius: BorderRadius.circular(999),
                ),
                child: Text(
                  '#${post.postNumber}',
                  style: TextStyle(
                    fontSize: scope.sp(11.5),
                    fontWeight: FontWeight.w700,
                    color: isOp ? palette.activeChipText : palette.inkSecondary,
                  ),
                ),
              ),
            ],
          ),

          // 2. Reply-To Floor Jump Chip
          if (post.replyToPostNumber != null && post.replyToPostNumber! > 0) ...[
            const SizedBox(height: 10),
            InkWell(
              borderRadius: BorderRadius.circular(8),
              onTap: () => onJumpToFloor(post.replyToPostNumber!),
              child: Container(
                padding:
                    const EdgeInsets.symmetric(horizontal: 9, vertical: 4.5),
                decoration: BoxDecoration(
                  color: palette.subtleSurface,
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(color: palette.hairline),
                ),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(
                      LinDoIcons.Reply,
                      size: 13,
                      color: palette.inkSecondary,
                    ),
                    const SizedBox(width: 5),
                    Text(
                      LinDoReaderCopy.REPLY_TARGET_USER_FLOOR.format([
                        post.replyToUsername ?? '',
                        post.replyToPostNumber!,
                      ]),
                      style: TextStyle(
                        fontSize: scope.sp(11.5),
                        fontWeight: FontWeight.w600,
                        color: palette.inkSecondary,
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ],

          const SizedBox(height: 12),

          // 3. Rich AST / Poll Body
          PostRichContentView(
            cookedHtml: post.cookedHtml,
            polls: post.polls,
            postId: post.id,
            onVotePoll: onVotePoll,
            onTapInternalTopic: onTapInternalTopic,
            onTapMention: onTapUser,
            onTapUrl: onTapUrl,
          ),

          // 4. Granted Badges on Post
          if (post.badgesGranted.isNotEmpty) ...[
            const SizedBox(height: 10),
            Wrap(
              spacing: 6,
              runSpacing: 6,
              children: post.badgesGranted
                  .map(
                    (b) => UniversalBadgeChip(
                      badge: b,
                      compact: true,
                      onTap: () => onOpenBadge(b),
                    ),
                  )
                  .toList(),
            ),
          ],

          // 5. Boosts (`助力` / 小火箭) Horizontal Bubble Strip
          if (post.boosts.isNotEmpty) ...[
            const SizedBox(height: 12),
            SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              child: Row(
                children: post.boosts.map((boost) {
                  final canRemove = boost.canDelete ||
                      boost.isBy(currentUser?.userId, currentUser?.username);
                  final label = boost.plainText.trim().isNotEmpty
                      ? boost.plainText.trim()
                      : decodeDiscourseHtmlSnippet(boost.cookedHtml);
                  return Padding(
                    padding: const EdgeInsets.only(right: 8),
                    child: Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 9,
                        vertical: 5,
                      ),
                      decoration: BoxDecoration(
                        color: palette.groupedCanvas,
                        borderRadius: BorderRadius.circular(999),
                        border: Border.all(color: palette.hairline),
                      ),
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          SmartAvatar(
                            avatarUrl: boost.avatarTemplate,
                            fallbackLabel: boost.username,
                            size: 18,
                            onTap: () => onTapUser(boost.username),
                          ),
                          const SizedBox(width: 6),
                          Text(
                            label,
                            style: TextStyle(
                              fontSize: scope.sp(12),
                              fontWeight: FontWeight.w500,
                              color: palette.inkPrimary,
                            ),
                          ),
                          if (canRemove) ...[
                            const SizedBox(width: 5),
                            GestureDetector(
                              onTap: () => onDeleteBoost(boost),
                              child: Icon(
                                LinDoIcons.Close,
                                size: 13,
                                color: palette.inkTertiary,
                              ),
                            ),
                          ],
                        ],
                      ),
                    ),
                  );
                }).toList(),
              ),
            ),
          ],

          // Inline Boost Composer
          if (boostComposerOpen) ...[
            const SizedBox(height: 10),
            _InlineBoostComposer(
              targetUsername: post.username,
              onSubmit: onSubmitBoost,
              onClose: onToggleBoostComposer,
            ),
          ],

          const SizedBox(height: 12),

          // 6. Reactions Pills + Floor Action Bar
          if (post.reactions.isNotEmpty) ...[
            Wrap(
              spacing: 6,
              runSpacing: 6,
              children: post.reactions.map((r) {
                final active = post.myReactionId == r.id ||
                    (r.id == 'heart' && post.likedByMe);
                final unicode =
                    LinuxDoEmojiCatalog.instance.resolveUnicodeEmoji(r.id);
                final emojiUrl =
                    LinuxDoEmojiCatalog.instance.resolveEmojiUrl(r.id);
                return InkWell(
                  borderRadius: BorderRadius.circular(999),
                  onTap: () => onToggleReaction(r.id),
                  child: Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 9,
                      vertical: 4,
                    ),
                    decoration: BoxDecoration(
                      color:
                          active ? palette.activeChipBg : palette.subtleSurface,
                      borderRadius: BorderRadius.circular(999),
                      border: Border.all(
                        color: active
                            ? palette.logoDoColor.withValues(alpha: 0.5)
                            : palette.hairline,
                      ),
                    ),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        if (unicode != null && unicode.isNotEmpty)
                          Text(unicode, style: const TextStyle(fontSize: 13.5))
                        else if (emojiUrl != null && emojiUrl.isNotEmpty)
                          LinDoNetworkImage(
                            url: emojiUrl,
                            width: 15,
                            height: 15,
                            fit: BoxFit.contain,
                          )
                        else
                          Text(
                            ':${r.id}:',
                            style: TextStyle(
                              fontSize: scope.sp(11.0),
                              color: palette.inkSecondary,
                            ),
                          ),
                        const SizedBox(width: 5),
                        Text(
                          '${r.count}',
                          style: TextStyle(
                            fontSize: scope.sp(11.5),
                            fontWeight: FontWeight.w700,
                            color: active
                                ? palette.activeChipText
                                : palette.inkSecondary,
                          ),
                        ),
                      ],
                    ),
                  ),
                );
              }).toList(),
            ),
            const SizedBox(height: 8),
          ],

          Row(
            children: [
              // Heart Like (long press opens multi-emoji picker!)
              GestureDetector(
                onLongPress: onOpenReactionPicker,
                child: InkWell(
                  borderRadius: BorderRadius.circular(999),
                  onTap: onTapHeart,
                  child: Padding(
                    padding:
                        const EdgeInsets.symmetric(horizontal: 8, vertical: 5),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(
                          post.likedByMe
                              ? LinDoIcons.HeartFilled
                              : LinDoIcons.Heart,
                          size: 16.5,
                          color: post.likedByMe
                              ? const Color(0xFFEF4444)
                              : palette.inkSecondary,
                        ),
                        if (post.likeCount > 0) ...[
                          const SizedBox(width: 4),
                          Text(
                            formatCompactCount(post.likeCount),
                            style: TextStyle(
                              fontSize: scope.sp(12),
                              fontWeight: FontWeight.w600,
                              color: post.likedByMe
                                  ? const Color(0xFFEF4444)
                                  : palette.inkSecondary,
                            ),
                          ),
                        ],
                      ],
                    ),
                  ),
                ),
              ),
              const SizedBox(width: 4),

              // Multi-Emoji Reaction Picker
              _FloorActionIconBtn(
                icon: LinDoIcons.Smile,
                tooltip: LinDoReaderCopy.EMOJI_REACTION_MENU.text,
                onTap: onOpenReactionPicker,
              ),

              // Rocket Boost (`+ 助力`)
              if (post.canBoost)
                _FloorActionIconBtn(
                  icon: boostComposerOpen
                      ? LinDoIcons.BoostRocket
                      : LinDoIcons.Rocket,
                  active: boostComposerOpen,
                  tooltip: LinDoReaderCopy.ROCKET_BOOST_DESC.text,
                  onTap: onToggleBoostComposer,
                ),

              const Spacer(),

              // Quote Reply
              _FloorActionIconBtn(
                icon: LinDoIcons.Quote,
                tooltip: LinDoReaderCopy.QUOTE_THIS_FLOOR_MENU.text,
                onTap: onQuoteReply,
              ),

              // Floor Reply
              _FloorActionIconBtn(
                icon: LinDoIcons.Reply,
                tooltip: LinDoReaderCopy.REPLY_THIS_FLOOR_DESC.text,
                onTap: onFloorReply,
              ),

              // Post Bookmark
              _FloorActionIconBtn(
                icon: post.bookmarkedByMe
                    ? LinDoIcons.BookmarkFilled
                    : LinDoIcons.Bookmark,
                active: post.bookmarkedByMe,
                tooltip: LinDoReaderCopy.BOOKMARK_ACTION.text,
                onTap: onToggleBookmark,
              ),

              // Edit Post
              if (post.canEdit)
                _FloorActionIconBtn(
                  icon: LinDoIcons.Edit,
                  tooltip: post.postNumber == 1
                      ? LinDoReaderCopy.EDIT_TOPIC_MENU.text
                      : LinDoReaderCopy.EDIT_COMMENT_MENU.text,
                  onTap: onEditPost,
                ),
            ],
          ),
        ],
      ),
    );
  }
}

class _FloorActionIconBtn extends StatelessWidget {
  final IconData icon;
  final String tooltip;
  final bool active;
  final VoidCallback onTap;

  const _FloorActionIconBtn({
    required this.icon,
    required this.tooltip,
    this.active = false,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    return Tooltip(
      message: tooltip,
      child: InkWell(
        borderRadius: BorderRadius.circular(8),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(6),
          child: Icon(
            icon,
            size: 16.5,
            color: active ? palette.logoDoColor : palette.inkSecondary,
          ),
        ),
      ),
    );
  }
}

class _InlineBoostComposer extends StatefulWidget {
  final String targetUsername;
  final Future<void> Function(String raw) onSubmit;
  final VoidCallback onClose;

  const _InlineBoostComposer({
    required this.targetUsername,
    required this.onSubmit,
    required this.onClose,
  });

  @override
  State<_InlineBoostComposer> createState() => _InlineBoostComposerState();
}

class _InlineBoostComposerState extends State<_InlineBoostComposer> {
  final LinDoImeSafeEditorController _ctrl = LinDoImeSafeEditorController();
  bool _submitting = false;

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_submitting || _ctrl.isComposing) return;
    final stats = BoostInputStats.compute(_ctrl.text);
    if (!stats.canSubmit) return;

    setState(() => _submitting = true);
    try {
      await widget.onSubmit(_ctrl.text.trim());
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    final scope = LinDoThemeScope.of(context);
    final quickEmojis =
        LinuxDoEmojiCatalog.instance.defaultReactions.take(8).toList();

    return ListenableBuilder(
      listenable: _ctrl,
      builder: (context, _) {
        final stats = BoostInputStats.compute(_ctrl.text);
        final overLimit = !stats.isWithinLimit;

        return Container(
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(
            color: palette.groupedCanvas,
            borderRadius: BorderRadius.circular(14),
            border: Border.all(
              color: overLimit ? const Color(0xFFEF4444) : palette.hairline,
            ),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Icon(
                    LinDoIcons.BoostRocket,
                    size: 15,
                    color: palette.logoDoColor,
                  ),
                  const SizedBox(width: 6),
                  Expanded(
                    child: Text(
                      '${LinDoReaderCopy.BOOST_LIMIT_CHARS_TAG.format(BoostInputStats.maxBoostVisibleLength)} · ${LinDoReaderCopy.BOOST_LIMIT_EMOJIS_TAG.format(BoostInputStats.maxBoostEmojiCount)}',
                      style: TextStyle(
                        fontSize: scope.sp(11.5),
                        color: overLimit
                            ? const Color(0xFFEF4444)
                            : palette.inkSecondary,
                      ),
                    ),
                  ),
                  Text(
                    '${stats.visibleLength}/${stats.maxVisibleLength}',
                    style: TextStyle(
                      fontSize: scope.sp(11.5),
                      fontWeight: FontWeight.w700,
                      color: stats.exceedsLength
                          ? const Color(0xFFEF4444)
                          : palette.inkTertiary,
                    ),
                  ),
                  const SizedBox(width: 6),
                  GestureDetector(
                    onTap: widget.onClose,
                    child: Icon(
                      LinDoIcons.Close,
                      size: 16,
                      color: palette.inkTertiary,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              SingleChildScrollView(
                scrollDirection: Axis.horizontal,
                child: Row(
                  children: quickEmojis.map((em) {
                    return Padding(
                      padding: const EdgeInsets.only(right: 6),
                      child: InkWell(
                        borderRadius: BorderRadius.circular(8),
                        onTap: stats.canAddEmoji
                            ? () {
                                final token = em.unicodeChar != null &&
                                        em.unicodeChar!.isNotEmpty
                                    ? em.unicodeChar!
                                    : ':${em.name}:';
                                _ctrl.insertText(token);
                              }
                            : null,
                        child: Container(
                          padding: const EdgeInsets.all(5),
                          decoration: BoxDecoration(
                            color: palette.cardSurface,
                            borderRadius: BorderRadius.circular(8),
                            border: Border.all(color: palette.hairline),
                          ),
                          child: em.unicodeChar != null &&
                                  em.unicodeChar!.isNotEmpty
                              ? Text(
                                  em.unicodeChar!,
                                  style: const TextStyle(fontSize: 15),
                                )
                              : LinDoNetworkImage(
                                  url: em.url,
                                  width: 17,
                                  height: 17,
                                ),
                        ),
                      ),
                    );
                  }).toList(),
                ),
              ),
              const SizedBox(height: 8),
              Row(
                children: [
                  Expanded(
                    child: Container(
                      decoration: BoxDecoration(
                        color: palette.cardSurface,
                        borderRadius: BorderRadius.circular(10),
                        border: Border.all(color: palette.hairline),
                      ),
                      child: LinDoImeSafeTextField(
                        controller: _ctrl,
                        autofocus: true,
                        enableMarkdownShortcuts: false,
                        maxLines: 1,
                        hintText: LinDoReaderCopy.BOOST_INPUT_PLACEHOLDER
                            .format(widget.targetUsername),
                        contentPadding: const EdgeInsets.symmetric(
                          horizontal: 11,
                          vertical: 8,
                        ),
                        onSubmitted: (_) => _submit(),
                        onSubmitShortcut: _submit,
                      ),
                    ),
                  ),
                  const SizedBox(width: 8),
                  SizedBox(
                    height: 36,
                    child: FilledButton(
                      style: FilledButton.styleFrom(
                        backgroundColor: palette.publishBtnBg,
                        foregroundColor: palette.publishBtnContent,
                        padding: const EdgeInsets.symmetric(horizontal: 14),
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(10),
                        ),
                      ),
                      onPressed:
                          (!stats.canSubmit || _submitting) ? null : _submit,
                      child: Icon(
                        LinDoIcons.Send,
                        size: 15,
                        color: palette.publishBtnContent,
                      ),
                    ),
                  ),
                ],
              ),
            ],
          ),
        );
      },
    );
  }
}
