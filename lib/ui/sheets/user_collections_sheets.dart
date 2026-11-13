import 'package:flutter/material.dart';

import '../../core/copy/lindo_copy.dart';
import '../../core/diagnostics/lindo_diagnostic_logger.dart';
import '../../core/models/lindo_models.dart';
import '../../core/network/linux_do_repository.dart';
import '../../core/site/linux_do_site_rules.dart';
import '../../core/storage/lindo_local_store.dart';
import '../components/composer_workbench.dart';
import '../components/lindo_brand_widgets.dart';
import '../components/lindo_icons.dart';
import '../components/lindo_mascot.dart';
import '../components/smart_avatar_and_badges.dart';
import '../theme/lindo_theme.dart';
import 'app_icon_page.dart';
import 'badges_sheet.dart';
import 'debug_diagnostics_sheet.dart';

Future<T?> _presentResponsiveSheet<T>(
  BuildContext context, {
  required Widget child,
  double maxWidth = 680,
  double maxHeight = 740,
  double mobileHeightFactor = 0.90,
}) {
  final size = MediaQuery.sizeOf(context);
  final isWide = shouldUseWideSplitLayout(size.width, size.height);
  if (isWide) {
    return showDialog<T>(
      context: context,
      barrierColor: Colors.black.withValues(alpha: 0.45),
      builder: (ctx) => Dialog(
        backgroundColor: Colors.transparent,
        insetPadding: const EdgeInsets.symmetric(horizontal: 32, vertical: 28),
        child: ConstrainedBox(
          constraints: BoxConstraints(maxWidth: maxWidth, maxHeight: maxHeight),
          child: ClipRRect(
            borderRadius: BorderRadius.circular(24),
            child: child,
          ),
        ),
      ),
    );
  }
  return showModalBottomSheet<T>(
    context: context,
    isScrollControlled: true,
    useSafeArea: true,
    backgroundColor: Colors.transparent,
    builder: (ctx) => FractionallySizedBox(
      heightFactor: mobileHeightFactor,
      child: ClipRRect(
        borderRadius: const BorderRadius.vertical(top: Radius.circular(24)),
        child: child,
      ),
    ),
  );
}

String _formatReadDuration(int seconds) {
  if (seconds <= 0) return LinDoCommonCopy.ZERO_MINUTES_TEXT.text;
  final minutes = seconds ~/ 60;
  if (minutes < 60) {
    return LinDoCommonCopy.MINUTES_TEXT.format(minutes <= 0 ? 1 : minutes);
  }
  final hours = minutes ~/ 60;
  if (hours < 48) {
    return LinDoCommonCopy.HOURS_TEXT.format(hours);
  }
  final days = hours ~/ 24;
  final remHours = hours % 24;
  return LinDoCommonCopy.DURATION_DAYS_HOURS.format([days, remHours]);
}

// ============================================================================
// 1. GuestLoginGuideSheet (ZERO Cookie / cURL / Callback Paste UI!)
// ============================================================================

Future<void> showGuestLoginGuideSheet(
  BuildContext context, {
  GuestGateScenario scenario = GuestGateScenario.DEFAULT,
  String? customTitle,
  String? customSubtitle,
  required Future<void> Function() onStartWebLogin,
}) {
  final title = (customTitle != null && customTitle.trim().isNotEmpty)
      ? customTitle.trim()
      : scenario.titleCopy.text;
  final subtitle = (customSubtitle != null && customSubtitle.trim().isNotEmpty)
      ? customSubtitle.trim()
      : scenario.subtitleCopy.text;

  final size = MediaQuery.sizeOf(context);
  final isWide = shouldUseWideSplitLayout(size.width, size.height);
  if (isWide) {
    return showDialog<void>(
      context: context,
      barrierColor: Colors.black.withValues(alpha: 0.45),
      builder: (ctx) => Dialog(
        backgroundColor: Colors.transparent,
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 400),
          child: ClipRRect(
            borderRadius: BorderRadius.circular(24),
            child: GuestLoginGuideSheet(
              title: title,
              subtitle: subtitle,
              onStartWebLogin: onStartWebLogin,
            ),
          ),
        ),
      ),
    );
  }
  return showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    backgroundColor: Colors.transparent,
    builder: (ctx) => ClipRRect(
      borderRadius: const BorderRadius.vertical(top: Radius.circular(24)),
      child: GuestLoginGuideSheet(
        title: title,
        subtitle: subtitle,
        onStartWebLogin: onStartWebLogin,
      ),
    ),
  );
}

class GuestLoginGuideSheet extends StatelessWidget {
  final String title;
  final String subtitle;
  final Future<void> Function() onStartWebLogin;

  const GuestLoginGuideSheet({
    super.key,
    required this.title,
    required this.subtitle,
    required this.onStartWebLogin,
  });

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    final scope = LinDoThemeScope.of(context);

    return Material(
      color: palette.cardSurface,
      child: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(24, 24, 24, 24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Center(
                child: LinDoMascot(
                  mood: MascotMood.wink,
                  size: 76,
                  animate: true,
                ),
              ),
              const SizedBox(height: 16),
              Text(
                title,
                textAlign: TextAlign.center,
                style: TextStyle(
                  fontSize: scope.sp(18),
                  fontWeight: FontWeight.w800,
                  color: palette.inkPrimary,
                ),
              ),
              const SizedBox(height: 8),
              Text(
                subtitle,
                textAlign: TextAlign.center,
                style: TextStyle(
                  fontSize: scope.sp(13.5),
                  height: 1.5,
                  color: palette.inkSecondary,
                ),
              ),
              const SizedBox(height: 22),
              SizedBox(
                height: 48,
                child: FilledButton(
                  style: FilledButton.styleFrom(
                    backgroundColor: const Color(0xFFF59E0B),
                    foregroundColor: const Color(0xFF18181B),
                    elevation: 0,
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(14),
                    ),
                  ),
                  onPressed: () async {
                    Navigator.of(context).pop();
                    await onStartWebLogin();
                  },
                  child: Row(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      Text(
                        LinDoAuthCopy.LOGIN_WITH_LINUX_DO.text,
                        style: TextStyle(
                          fontSize: scope.sp(14.5),
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                      const SizedBox(width: 6),
                      const Icon(LinDoIcons.ArrowRight, size: 17),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 10),
              SizedBox(
                height: 44,
                child: OutlinedButton(
                  style: OutlinedButton.styleFrom(
                    foregroundColor: palette.inkSecondary,
                    side: BorderSide(color: palette.hairline),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(14),
                    ),
                  ),
                  onPressed: () => Navigator.of(context).maybePop(),
                  child: Text(
                    '暂不登录',
                    style: TextStyle(
                      fontSize: scope.sp(14),
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

// ============================================================================
// 2. BookmarksSheet
// ============================================================================

Future<void> showBookmarksSheet(
  BuildContext context, {
  required LinuxDoRepository repository,
  required void Function(int topicId, {int? postNumber, String? title})
      onOpenTopic,
  required Future<void> Function() onStartSiteVerify,
  ValueChanged<String>? onShowToast,
}) {
  return _presentResponsiveSheet<void>(
    context,
    child: BookmarksSheet(
      repository: repository,
      onOpenTopic: onOpenTopic,
      onStartSiteVerify: onStartSiteVerify,
      onShowToast: onShowToast,
    ),
  );
}

class BookmarksSheet extends StatefulWidget {
  final LinuxDoRepository repository;
  final void Function(int topicId, {int? postNumber, String? title})
      onOpenTopic;
  final Future<void> Function() onStartSiteVerify;
  final ValueChanged<String>? onShowToast;

  const BookmarksSheet({
    super.key,
    required this.repository,
    required this.onOpenTopic,
    required this.onStartSiteVerify,
    this.onShowToast,
  });

  @override
  State<BookmarksSheet> createState() => _BookmarksSheetState();
}

class _BookmarksSheetState extends State<BookmarksSheet> {
  List<UserActivityItem> _items = const <UserActivityItem>[];
  bool _loading = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final list = await widget.repository.fetchUserBookmarks();
      if (!mounted) return;
      setState(() {
        _items = list;
        _loading = false;
      });
    } catch (err) {
      if (!mounted) return;
      setState(() {
        _error = err.toString();
        _loading = false;
      });
    }
  }

  Future<void> _removeBookmark(UserActivityItem item) async {
    try {
      if (item.postNumber > 1 && item.id > 0) {
        await widget.repository.togglePostBookmark(
          item.id,
          bookmarked: false,
          bookmarkId: item.bookmarkId > 0 ? item.bookmarkId : null,
        );
      } else {
        await widget.repository.toggleTopicBookmark(
          item.topicId,
          bookmarked: false,
        );
      }
      if (!mounted) return;
      setState(() {
        _items = _items.where((e) => e.id != item.id || e.topicId != item.topicId).toList();
      });
    } catch (_) {
      widget.onShowToast?.call(LinDoMessagesCopy.REMOVE_BOOKMARK_FAILED.text);
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    final scope = LinDoThemeScope.of(context);
    final isSiteVerify =
        widget.repository.siteVerificationRequired || _error.isSiteVerificationError;

    return Material(
      color: palette.canvas,
      child: Column(
        children: [
          _SheetHeaderBar(
            icon: LinDoIcons.BookmarkFilled,
            title: LinDoMessagesCopy.BOOKMARKS_SHEET_TITLE.text,
            onRefresh: _loading ? null : _load,
          ),
          Divider(height: 1, color: palette.hairline),
          Expanded(
            child: _loading
                ? LinDoStatePanel(
                    mood: MascotMood.connecting,
                    title: LinDoCommonCopy.LOADING.text,
                  )
                : isSiteVerify
                    ? LinDoSiteVerificationCard(
                        onStartInAppVerify: () async {
                          await widget.onStartSiteVerify();
                          if (mounted) _load();
                        },
                        onRetry: _load,
                      )
                    : _error != null
                        ? LinDoStatePanel(
                            mood: MascotMood.surprised,
                            title: LinDoCommonCopy.LOAD_FAILED.text,
                            subtitle: _error,
                            actionLabel: LinDoCommonCopy.RETRY.text,
                            onAction: _load,
                          )
                        : _items.isEmpty
                            ? LinDoStatePanel(
                                mood: MascotMood.sleepy,
                                title: LinDoMessagesCopy.EMPTY_BOOKMARKS.text,
                              )
                            : ListView.builder(
                                padding:
                                    const EdgeInsets.fromLTRB(16, 12, 16, 28),
                                itemCount: _items.length,
                                itemBuilder: (context, index) {
                                  final item = _items[index];
                                  final snippet = decodeDiscourseHtmlSnippet(
                                    item.excerpt,
                                  );
                                  return RepaintBoundary(
                                    child: Padding(
                                      padding:
                                          const EdgeInsets.only(bottom: 10),
                                      child: Material(
                                        color: palette.subtleSurface,
                                        shape: RoundedRectangleBorder(
                                          borderRadius:
                                              BorderRadius.circular(14),
                                          side: BorderSide(
                                            color: palette.hairline,
                                          ),
                                        ),
                                        child: InkWell(
                                          borderRadius:
                                              BorderRadius.circular(14),
                                          onTap: () {
                                            Navigator.of(context).pop();
                                            widget.onOpenTopic(
                                              item.topicId,
                                              postNumber: item.postNumber > 1
                                                  ? item.postNumber
                                                  : null,
                                              title: item.title,
                                            );
                                          },
                                          child: Padding(
                                            padding: const EdgeInsets.all(14),
                                            child: Column(
                                              crossAxisAlignment:
                                                  CrossAxisAlignment.start,
                                              children: [
                                                Row(
                                                  children: [
                                                    CategoryMorandiChip(
                                                      categoryName:
                                                          item.categoryName,
                                                      compact: true,
                                                    ),
                                                    if (item.postNumber > 1) ...[
                                                      const SizedBox(width: 6),
                                                      Text(
                                                        '#${item.postNumber}',
                                                        style: TextStyle(
                                                          fontSize:
                                                              scope.sp(11.5),
                                                          fontWeight:
                                                              FontWeight.w600,
                                                          color: palette
                                                              .inkSecondary,
                                                        ),
                                                      ),
                                                    ],
                                                    const Spacer(),
                                                    Text(
                                                      formatRelativeTime(
                                                        item.createdAtText,
                                                      ),
                                                      style: TextStyle(
                                                        fontSize:
                                                            scope.sp(11.5),
                                                        color: palette
                                                            .inkTertiary,
                                                      ),
                                                    ),
                                                    const SizedBox(width: 4),
                                                    IconButton(
                                                      visualDensity:
                                                          VisualDensity.compact,
                                                      tooltip: LinDoMessagesCopy
                                                          .UNBOOKMARK_DESC.text,
                                                      onPressed: () =>
                                                          _removeBookmark(item),
                                                      icon: Icon(
                                                        LinDoIcons.Trash,
                                                        size: 16,
                                                        color: palette
                                                            .inkTertiary,
                                                      ),
                                                    ),
                                                  ],
                                                ),
                                                const SizedBox(height: 6),
                                                Text(
                                                  item.title,
                                                  maxLines: 2,
                                                  overflow:
                                                      TextOverflow.ellipsis,
                                                  style: TextStyle(
                                                    fontSize: scope.sp(14.5),
                                                    fontWeight: FontWeight.w700,
                                                    color: palette.inkPrimary,
                                                  ),
                                                ),
                                                if (snippet.isNotEmpty) ...[
                                                  const SizedBox(height: 5),
                                                  Text(
                                                    snippet,
                                                    maxLines: 2,
                                                    overflow:
                                                        TextOverflow.ellipsis,
                                                    style: TextStyle(
                                                      fontSize: scope.sp(12.5),
                                                      color:
                                                          palette.inkSecondary,
                                                    ),
                                                  ),
                                                ],
                                              ],
                                            ),
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
    );
  }
}

// ============================================================================
// 3. ReadHistorySheet
// ============================================================================

Future<void> showReadHistorySheet(
  BuildContext context, {
  required LinDoLocalStore store,
  required void Function(int topicId, {int? postNumber, String? title})
      onOpenTopic,
  ValueChanged<String>? onShowToast,
}) {
  return _presentResponsiveSheet<void>(
    context,
    child: ReadHistorySheet(
      store: store,
      onOpenTopic: onOpenTopic,
      onShowToast: onShowToast,
    ),
  );
}

class ReadHistorySheet extends StatelessWidget {
  final LinDoLocalStore store;
  final void Function(int topicId, {int? postNumber, String? title})
      onOpenTopic;
  final ValueChanged<String>? onShowToast;

  const ReadHistorySheet({
    super.key,
    required this.store,
    required this.onOpenTopic,
    this.onShowToast,
  });

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    final scope = LinDoThemeScope.of(context);

    return Material(
      color: palette.canvas,
      child: ListenableBuilder(
        listenable: store,
        builder: (context, _) {
          final history = store.readHistory;
          return Column(
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(20, 14, 12, 12),
                child: Row(
                  children: [
                    Icon(
                      LinDoIcons.History,
                      size: 21,
                      color: palette.logoDoColor,
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: Text(
                        LinDoMessagesCopy.READ_HISTORY_TITLE.text,
                        style: TextStyle(
                          fontSize: scope.sp(17.5),
                          fontWeight: FontWeight.w700,
                          color: palette.inkPrimary,
                        ),
                      ),
                    ),
                    if (history.isNotEmpty)
                      TextButton.icon(
                        onPressed: () async {
                          await store.clearReadHistory();
                          onShowToast?.call(LinDoCommonCopy.COMPLETED.text);
                        },
                        icon: Icon(
                          LinDoIcons.Trash,
                          size: 15,
                          color: palette.inkSecondary,
                        ),
                        label: Text(
                          LinDoCommonCopy.CLEAR.text,
                          style: TextStyle(
                            fontSize: scope.sp(13),
                            color: palette.inkSecondary,
                          ),
                        ),
                      ),
                    IconButton(
                      onPressed: () => Navigator.of(context).maybePop(),
                      icon: Icon(
                        LinDoIcons.Close,
                        size: 20,
                        color: palette.inkSecondary,
                      ),
                    ),
                  ],
                ),
              ),
              Divider(height: 1, color: palette.hairline),
              Expanded(
                child: history.isEmpty
                    ? LinDoStatePanel(
                        mood: MascotMood.sleepy,
                        title: LinDoMessagesCopy.EMPTY_READ_HISTORY.text,
                      )
                    : ListView.builder(
                        padding: const EdgeInsets.fromLTRB(16, 12, 16, 28),
                        itemCount: history.length,
                        itemBuilder: (context, index) {
                          final entry = history[index];
                          final visitedIso = DateTime.fromMillisecondsSinceEpoch(
                            entry.visitedAtMs,
                            isUtc: true,
                          ).toIso8601String();
                          return RepaintBoundary(
                            child: Padding(
                              padding: const EdgeInsets.only(bottom: 9),
                              child: Material(
                                color: palette.subtleSurface,
                                shape: RoundedRectangleBorder(
                                  borderRadius: BorderRadius.circular(14),
                                  side: BorderSide(color: palette.hairline),
                                ),
                                child: InkWell(
                                  borderRadius: BorderRadius.circular(14),
                                  onTap: () {
                                    Navigator.of(context).pop();
                                    onOpenTopic(
                                      entry.topicId,
                                      postNumber: entry.lastReadPostNumber > 1
                                          ? entry.lastReadPostNumber
                                          : null,
                                      title: entry.title,
                                    );
                                  },
                                  child: Padding(
                                    padding: const EdgeInsets.all(13),
                                    child: Column(
                                      crossAxisAlignment:
                                          CrossAxisAlignment.start,
                                      children: [
                                        Row(
                                          children: [
                                            if (entry.categoryName.isNotEmpty)
                                              CategoryMorandiChip(
                                                categoryName:
                                                    entry.categoryName,
                                                colorHex:
                                                    entry.categoryColorHex,
                                                compact: true,
                                              ),
                                            const Spacer(),
                                            Container(
                                              padding:
                                                  const EdgeInsets.symmetric(
                                                horizontal: 8,
                                                vertical: 2.5,
                                              ),
                                              decoration: BoxDecoration(
                                                color: palette.tonalPill,
                                                borderRadius:
                                                    BorderRadius.circular(999),
                                              ),
                                              child: Text(
                                                LinDoReaderCopy.FLOOR_COUNTER
                                                    .format([
                                                  entry.lastReadPostNumber,
                                                  entry.postsCount,
                                                ]),
                                                style: TextStyle(
                                                  fontSize: scope.sp(11),
                                                  fontWeight: FontWeight.w600,
                                                  color: palette.inkSecondary,
                                                ),
                                              ),
                                            ),
                                            const SizedBox(width: 8),
                                            Text(
                                              formatRelativeTime(visitedIso),
                                              style: TextStyle(
                                                fontSize: scope.sp(11.5),
                                                color: palette.inkTertiary,
                                              ),
                                            ),
                                          ],
                                        ),
                                        const SizedBox(height: 7),
                                        Text(
                                          entry.title.isEmpty
                                              ? LinDoCommonCopy
                                                  .TOPIC_NUMBER_FALLBACK
                                                  .format(entry.topicId)
                                              : entry.title,
                                          maxLines: 2,
                                          overflow: TextOverflow.ellipsis,
                                          style: TextStyle(
                                            fontSize: scope.sp(14.5),
                                            fontWeight: FontWeight.w700,
                                            color: palette.inkPrimary,
                                          ),
                                        ),
                                      ],
                                    ),
                                  ),
                                ),
                              ),
                            ),
                          );
                        },
                      ),
              ),
            ],
          );
        },
      ),
    );
  }
}

// ============================================================================
// 4. UserActivitiesSheet
// ============================================================================

Future<void> showUserActivitiesSheet(
  BuildContext context, {
  required LinuxDoRepository repository,
  String? username,
  required void Function(int topicId, {int? postNumber, String? title})
      onOpenTopic,
  required Future<void> Function() onStartSiteVerify,
}) {
  return _presentResponsiveSheet<void>(
    context,
    child: UserActivitiesSheet(
      repository: repository,
      username: username,
      onOpenTopic: onOpenTopic,
      onStartSiteVerify: onStartSiteVerify,
    ),
  );
}

class UserActivitiesSheet extends StatefulWidget {
  final LinuxDoRepository repository;
  final String? username;
  final void Function(int topicId, {int? postNumber, String? title})
      onOpenTopic;
  final Future<void> Function() onStartSiteVerify;

  const UserActivitiesSheet({
    super.key,
    required this.repository,
    this.username,
    required this.onOpenTopic,
    required this.onStartSiteVerify,
  });

  @override
  State<UserActivitiesSheet> createState() => _UserActivitiesSheetState();
}

class _UserActivitiesSheetState extends State<UserActivitiesSheet> {
  UserActionFilterTab _activeTab = UserActionFilterTab.ALL;
  List<UserActivityItem> _items = const <UserActivityItem>[];
  bool _loading = true;
  bool _loadingMore = false;
  bool _hasMore = true;
  String? _error;
  final ScrollController _scrollCtrl = ScrollController();

  @override
  void initState() {
    super.initState();
    _scrollCtrl.addListener(_onScroll);
    _load(reset: true);
  }

  @override
  void dispose() {
    _scrollCtrl.dispose();
    super.dispose();
  }

  void _onScroll() {
    if (!_hasMore || _loading || _loadingMore) return;
    final pos = _scrollCtrl.position;
    if (pos.pixels >= pos.maxScrollExtent - 220) {
      _load(reset: false);
    }
  }

  Future<void> _load({required bool reset}) async {
    final targetUser =
        widget.username ?? widget.repository.currentUser?.username ?? '';
    if (targetUser.trim().isEmpty) {
      setState(() {
        _loading = false;
        _items = const <UserActivityItem>[];
      });
      return;
    }

    setState(() {
      if (reset) {
        _loading = true;
        _error = null;
      } else {
        _loadingMore = true;
      }
    });

    try {
      final offset = reset ? 0 : _items.length;
      final chunk = await widget.repository.fetchUserActions(
        targetUser,
        filter: _activeTab.apiFilter,
        offset: offset,
      );
      if (!mounted) return;
      setState(() {
        _items = reset ? chunk : <UserActivityItem>[..._items, ...chunk];
        _hasMore = chunk.length >= 20;
        _loading = false;
        _loadingMore = false;
      });
    } catch (err) {
      if (!mounted) return;
      setState(() {
        _error = err.toString();
        _loading = false;
        _loadingMore = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    final scope = LinDoThemeScope.of(context);
    final isSiteVerify =
        widget.repository.siteVerificationRequired || _error.isSiteVerificationError;

    return Material(
      color: palette.canvas,
      child: Column(
        children: [
          _SheetHeaderBar(
            icon: LinDoIcons.Sparkles,
            title: LinDoMessagesCopy.ACTIVITIES_SHEET_TITLE.text,
            onRefresh: _loading ? null : () => _load(reset: true),
          ),
          SizedBox(
            height: 40,
            child: ListView.separated(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
              itemCount: UserActionFilterTab.values.length,
              separatorBuilder: (_, _) => const SizedBox(width: 8),
              itemBuilder: (context, index) {
                final tab = UserActionFilterTab.values[index];
                final selected = tab == _activeTab;
                return InkWell(
                  borderRadius: BorderRadius.circular(999),
                  onTap: () {
                    if (selected) return;
                    setState(() => _activeTab = tab);
                    _load(reset: true);
                  },
                  child: Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 13,
                      vertical: 5,
                    ),
                    decoration: BoxDecoration(
                      color:
                          selected ? palette.activeChipBg : palette.subtleSurface,
                      borderRadius: BorderRadius.circular(999),
                      border: Border.all(
                        color: selected
                            ? palette.activeChipText.withValues(alpha: 0.35)
                            : palette.hairline,
                      ),
                    ),
                    child: Text(
                      tab.labelCopy.text,
                      style: TextStyle(
                        fontSize: scope.sp(12.5),
                        fontWeight:
                            selected ? FontWeight.w700 : FontWeight.w500,
                        color: selected
                            ? palette.activeChipText
                            : palette.inkSecondary,
                      ),
                    ),
                  ),
                );
              },
            ),
          ),
          const SizedBox(height: 6),
          Divider(height: 1, color: palette.hairline),
          Expanded(
            child: _loading
                ? LinDoStatePanel(
                    mood: MascotMood.connecting,
                    title: LinDoMessagesCopy.LOADING_ACTIVITY_ELLIPSIS.text,
                  )
                : isSiteVerify
                    ? LinDoSiteVerificationCard(
                        onStartInAppVerify: () async {
                          await widget.onStartSiteVerify();
                          if (mounted) _load(reset: true);
                        },
                        onRetry: () => _load(reset: true),
                      )
                    : _error != null
                        ? LinDoStatePanel(
                            mood: MascotMood.surprised,
                            title: LinDoCommonCopy.LOAD_FAILED.text,
                            subtitle: _error,
                            actionLabel: LinDoCommonCopy.RETRY.text,
                            onAction: () => _load(reset: true),
                          )
                        : _items.isEmpty
                            ? LinDoStatePanel(
                                mood: MascotMood.sleepy,
                                title: LinDoMessagesCopy.EMPTY_ACTIVITIES.text,
                              )
                            : ListView.builder(
                                controller: _scrollCtrl,
                                padding:
                                    const EdgeInsets.fromLTRB(16, 12, 16, 28),
                                itemCount:
                                    _items.length + (_loadingMore ? 1 : 0),
                                itemBuilder: (context, index) {
                                  if (index >= _items.length) {
                                    return const Padding(
                                      padding:
                                          EdgeInsets.symmetric(vertical: 16),
                                      child: Center(
                                        child: SizedBox(
                                          width: 20,
                                          height: 20,
                                          child: CircularProgressIndicator(
                                            strokeWidth: 2,
                                          ),
                                        ),
                                      ),
                                    );
                                  }
                                  final item = _items[index];
                                  return RepaintBoundary(
                                    child: _UserActivityRowCard(
                                      item: item,
                                      onTap: () {
                                        Navigator.of(context).pop();
                                        widget.onOpenTopic(
                                          item.topicId,
                                          postNumber: item.postNumber > 1
                                              ? item.postNumber
                                              : null,
                                          title: item.title,
                                        );
                                      },
                                    ),
                                  );
                                },
                              ),
          ),
        ],
      ),
    );
  }
}

class _UserActivityRowCard extends StatelessWidget {
  final UserActivityItem item;
  final VoidCallback onTap;

  const _UserActivityRowCard({
    required this.item,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    final scope = LinDoThemeScope.of(context);
    final snippet = decodeDiscourseHtmlSnippet(item.excerpt);

    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Material(
        color: palette.subtleSurface,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(14),
          side: BorderSide(color: palette.hairline),
        ),
        child: InkWell(
          borderRadius: BorderRadius.circular(14),
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.all(13),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 7,
                        vertical: 2.5,
                      ),
                      decoration: BoxDecoration(
                        color: palette.activeChipBg,
                        borderRadius: BorderRadius.circular(6),
                      ),
                      child: Text(
                        item.actionLabel,
                        style: TextStyle(
                          fontSize: scope.sp(11),
                          fontWeight: FontWeight.w700,
                          color: palette.activeChipText,
                        ),
                      ),
                    ),
                    const SizedBox(width: 8),
                    if (item.categoryName.isNotEmpty)
                      CategoryMorandiChip(
                        categoryName: item.categoryName,
                        compact: true,
                      ),
                    const Spacer(),
                    Text(
                      formatRelativeTime(item.createdAtText),
                      style: TextStyle(
                        fontSize: scope.sp(11.5),
                        color: palette.inkTertiary,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                Text(
                  item.title,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: scope.sp(14.5),
                    fontWeight: FontWeight.w700,
                    color: palette.inkPrimary,
                  ),
                ),
                if (snippet.isNotEmpty) ...[
                  const SizedBox(height: 5),
                  Text(
                    snippet,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: scope.sp(12.5),
                      height: 1.45,
                      color: palette.inkSecondary,
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

// ============================================================================
// 5. DraftsSheet (Cloud + Local Merged)
// ============================================================================

Future<void> showDraftsSheet(
  BuildContext context, {
  required LinuxDoRepository repository,
  required LinDoLocalStore store,
  required Future<void> Function() onStartSiteVerify,
  void Function(int topicId, {int? postNumber, String? title})? onOpenTopic,
  ValueChanged<String>? onShowToast,
}) {
  return _presentResponsiveSheet<void>(
    context,
    child: DraftsSheet(
      repository: repository,
      store: store,
      onStartSiteVerify: onStartSiteVerify,
      onOpenTopic: onOpenTopic,
      onShowToast: onShowToast,
    ),
  );
}

class DraftsSheet extends StatefulWidget {
  final LinuxDoRepository repository;
  final LinDoLocalStore store;
  final Future<void> Function() onStartSiteVerify;
  final void Function(int topicId, {int? postNumber, String? title})?
      onOpenTopic;
  final ValueChanged<String>? onShowToast;

  const DraftsSheet({
    super.key,
    required this.repository,
    required this.store,
    required this.onStartSiteVerify,
    this.onOpenTopic,
    this.onShowToast,
  });

  @override
  State<DraftsSheet> createState() => _DraftsSheetState();
}

class _DraftsSheetState extends State<DraftsSheet> {
  List<CloudDraftItem> _cloudDrafts = const <CloudDraftItem>[];
  bool _loading = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    if (!widget.repository.isAuthorized) {
      setState(() {
        _cloudDrafts = const <CloudDraftItem>[];
        _loading = false;
      });
      return;
    }
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final list = await widget.repository.fetchCloudDrafts();
      if (!mounted) return;
      setState(() {
        _cloudDrafts = list;
        _loading = false;
      });
    } catch (err) {
      if (!mounted) return;
      setState(() {
        _error = err.toString();
        _loading = false;
      });
    }
  }

  List<CloudDraftItem> _mergedDrafts() {
    final map = <String, CloudDraftItem>{};
    for (final c in _cloudDrafts) {
      map[c.draftKey] = c;
    }
    for (final local in widget.store.localDrafts) {
      if (!map.containsKey(local.draftKey) || local.isLocalOnly) {
        map[local.draftKey] = local;
      }
    }
    return map.values.toList(growable: false);
  }

  Future<void> _deleteDraft(CloudDraftItem draft) async {
    await widget.store.deleteLocalDraft(draft.draftKey);
    if (!draft.isLocalOnly && widget.repository.isAuthorized) {
      try {
        await widget.repository.deleteCloudDraft(
          draft.draftKey,
          sequence: draft.sequence,
        );
      } catch (_) {}
    }
    if (!mounted) return;
    setState(() {
      _cloudDrafts =
          _cloudDrafts.where((e) => e.draftKey != draft.draftKey).toList();
    });
  }

  ComposerAction _resolveAction(CloudDraftItem draft) {
    switch (draft.kind) {
      case CloudDraftKind.PRIVATE_MESSAGE:
        return ComposerAction.PRIVATE_MESSAGE;
      case CloudDraftKind.EDIT_POST:
        return ComposerAction.EDIT_POST;
      case CloudDraftKind.NEW_TOPIC:
        return ComposerAction.NEW_TOPIC;
      case CloudDraftKind.TOPIC_REPLY:
        return ComposerAction.REPLY;
    }
  }

  Future<void> _resumeDraft(CloudDraftItem draft) async {
    final nav = Navigator.of(context);
    nav.pop();
    final outcome = await showLinDoComposerSheet(
      nav.context,
      repository: widget.repository,
      store: widget.store,
      action: _resolveAction(draft),
      topicId: draft.topicId,
      topicTitle: draft.title.isNotEmpty ? draft.title : null,
      editPostId: draft.editPostId,
      replyToPostNumber: draft.replyToPostNumber,
      initialCategoryId: draft.categoryId,
      initialTitle: draft.title,
      initialTags: draft.tags,
      initialRecipients: draft.recipients,
      initialDraft: draft,
    );
    if (outcome != null &&
        outcome.success &&
        outcome.topicId != null &&
        widget.onOpenTopic != null) {
      widget.onOpenTopic!(
        outcome.topicId!,
        postNumber: outcome.post?.postNumber,
        title: outcome.title,
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    final scope = LinDoThemeScope.of(context);

    return Material(
      color: palette.canvas,
      child: ListenableBuilder(
        listenable: widget.store,
        builder: (context, _) {
          final merged = _mergedDrafts();
          return Column(
            children: [
              _SheetHeaderBar(
                icon: LinDoIcons.Drafts,
                title: '草稿箱',
                onRefresh: _loading ? null : _load,
              ),
              Divider(height: 1, color: palette.hairline),
              Expanded(
                child: _loading && merged.isEmpty
                    ? LinDoStatePanel(
                        mood: MascotMood.connecting,
                        title: LinDoCommonCopy.LOADING.text,
                      )
                    : (_error != null &&
                            merged.isEmpty &&
                            _error.isSiteVerificationError)
                        ? LinDoSiteVerificationCard(
                            onStartInAppVerify: () async {
                              await widget.onStartSiteVerify();
                              if (mounted) _load();
                            },
                            onRetry: _load,
                          )
                        : merged.isEmpty
                            ? LinDoStatePanel(
                                mood: MascotMood.sleepy,
                                title: '暂无草稿',
                                subtitle: '未发送的话题与回复会自动保存在这里',
                              )
                            : ListView.builder(
                                padding:
                                    const EdgeInsets.fromLTRB(16, 12, 16, 28),
                                itemCount: merged.length,
                                itemBuilder: (context, index) {
                                  final draft = merged[index];
                                  final preview = draft.raw.trim();
                                  return RepaintBoundary(
                                    child: Padding(
                                      padding:
                                          const EdgeInsets.only(bottom: 10),
                                      child: Material(
                                        color: palette.subtleSurface,
                                        shape: RoundedRectangleBorder(
                                          borderRadius:
                                              BorderRadius.circular(14),
                                          side: BorderSide(
                                            color: palette.hairline,
                                          ),
                                        ),
                                        child: InkWell(
                                          borderRadius:
                                              BorderRadius.circular(14),
                                          onTap: () => _resumeDraft(draft),
                                          child: Padding(
                                            padding: const EdgeInsets.all(14),
                                            child: Column(
                                              crossAxisAlignment:
                                                  CrossAxisAlignment.start,
                                              children: [
                                                Row(
                                                  children: [
                                                    Container(
                                                      padding: const EdgeInsets
                                                          .symmetric(
                                                        horizontal: 7,
                                                        vertical: 2.5,
                                                      ),
                                                      decoration: BoxDecoration(
                                                        color: palette
                                                            .activeChipBg,
                                                        borderRadius:
                                                            BorderRadius
                                                                .circular(6),
                                                      ),
                                                      child: Text(
                                                        draft.kind.badgeCopy
                                                            .text,
                                                        style: TextStyle(
                                                          fontSize:
                                                              scope.sp(11),
                                                          fontWeight:
                                                              FontWeight.w700,
                                                          color: palette
                                                              .activeChipText,
                                                        ),
                                                      ),
                                                    ),
                                                    const SizedBox(width: 6),
                                                    Container(
                                                      padding: const EdgeInsets
                                                          .symmetric(
                                                        horizontal: 7,
                                                        vertical: 2.5,
                                                      ),
                                                      decoration: BoxDecoration(
                                                        color:
                                                            palette.tonalPill,
                                                        borderRadius:
                                                            BorderRadius
                                                                .circular(6),
                                                      ),
                                                      child: Text(
                                                        draft.isLocalOnly
                                                            ? '本地草稿'
                                                            : '云端同步',
                                                        style: TextStyle(
                                                          fontSize:
                                                              scope.sp(10.5),
                                                          fontWeight:
                                                              FontWeight.w600,
                                                          color: palette
                                                              .inkSecondary,
                                                        ),
                                                      ),
                                                    ),
                                                    const Spacer(),
                                                    if (draft.updatedAtText
                                                        .isNotEmpty)
                                                      Text(
                                                        formatRelativeTime(
                                                          draft.updatedAtText,
                                                        ),
                                                        style: TextStyle(
                                                          fontSize:
                                                              scope.sp(11.5),
                                                          color: palette
                                                              .inkTertiary,
                                                        ),
                                                      ),
                                                    IconButton(
                                                      visualDensity:
                                                          VisualDensity.compact,
                                                      tooltip: LinDoCommonCopy
                                                          .DELETE.text,
                                                      onPressed: () =>
                                                          _deleteDraft(draft),
                                                      icon: Icon(
                                                        LinDoIcons.Trash,
                                                        size: 16,
                                                        color: palette
                                                            .inkTertiary,
                                                      ),
                                                    ),
                                                  ],
                                                ),
                                                if (draft.title
                                                    .trim()
                                                    .isNotEmpty) ...[
                                                  const SizedBox(height: 6),
                                                  Text(
                                                    draft.title.trim(),
                                                    maxLines: 1,
                                                    overflow:
                                                        TextOverflow.ellipsis,
                                                    style: TextStyle(
                                                      fontSize: scope.sp(14.5),
                                                      fontWeight:
                                                          FontWeight.w700,
                                                      color: palette.inkPrimary,
                                                    ),
                                                  ),
                                                ],
                                                if (preview.isNotEmpty) ...[
                                                  const SizedBox(height: 5),
                                                  Text(
                                                    preview,
                                                    maxLines: 3,
                                                    overflow:
                                                        TextOverflow.ellipsis,
                                                    style: TextStyle(
                                                      fontSize: scope.sp(13),
                                                      color:
                                                          palette.inkSecondary,
                                                    ),
                                                  ),
                                                ],
                                              ],
                                            ),
                                          ),
                                        ),
                                      ),
                                    ),
                                  );
                                },
                              ),
              ),
            ],
          );
        },
      ),
    );
  }
}

// ============================================================================
// 6. FollowNetworkSheet
// ============================================================================

Future<void> showFollowNetworkSheet(
  BuildContext context, {
  required LinuxDoRepository repository,
  required LinDoLocalStore store,
  String? username,
  FollowNetworkTab initialTab = FollowNetworkTab.FOLLOWING,
  required void Function(int topicId, {int? postNumber, String? title})
      onOpenTopic,
  required Future<void> Function() onStartSiteVerify,
  ValueChanged<String>? onShowToast,
}) {
  return _presentResponsiveSheet<void>(
    context,
    child: FollowNetworkSheet(
      repository: repository,
      store: store,
      username: username,
      initialTab: initialTab,
      onOpenTopic: onOpenTopic,
      onStartSiteVerify: onStartSiteVerify,
      onShowToast: onShowToast,
    ),
  );
}

class FollowNetworkSheet extends StatefulWidget {
  final LinuxDoRepository repository;
  final LinDoLocalStore store;
  final String? username;
  final FollowNetworkTab initialTab;
  final void Function(int topicId, {int? postNumber, String? title})
      onOpenTopic;
  final Future<void> Function() onStartSiteVerify;
  final ValueChanged<String>? onShowToast;

  const FollowNetworkSheet({
    super.key,
    required this.repository,
    required this.store,
    this.username,
    this.initialTab = FollowNetworkTab.FOLLOWING,
    required this.onOpenTopic,
    required this.onStartSiteVerify,
    this.onShowToast,
  });

  @override
  State<FollowNetworkSheet> createState() => _FollowNetworkSheetState();
}

class _FollowNetworkSheetState extends State<FollowNetworkSheet> {
  late FollowNetworkTab _activeTab;
  List<TopicUser> _users = const <TopicUser>[];
  List<TopicSummary> _feedTopics = const <TopicSummary>[];
  final Set<String> _unfollowedInSession = <String>{};
  bool _loading = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    _activeTab = widget.initialTab;
    _load();
  }

  Future<void> _load() async {
    final targetUser =
        widget.username ?? widget.repository.currentUser?.username ?? '';
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      if (_activeTab == FollowNetworkTab.FEED) {
        final feedPage = await widget.repository.fetchFeed(
          sort: FeedSortFilter.FOLLOWING,
        );
        if (!mounted) return;
        setState(() {
          _feedTopics = feedPage.topics;
          _loading = false;
        });
      } else {
        final list = await widget.repository.fetchFollowList(
          targetUser,
          followers: _activeTab == FollowNetworkTab.FOLLOWERS,
        );
        if (!mounted) return;
        setState(() {
          _users = list;
          _loading = false;
        });
      }
    } catch (err) {
      if (!mounted) return;
      setState(() {
        _error = err.toString();
        _loading = false;
      });
    }
  }

  Future<void> _toggleFollow(TopicUser user) async {
    final currentlyFollowing = !_unfollowedInSession.contains(user.username);
    try {
      await widget.repository.toggleFollowUser(
        user.username,
        follow: !currentlyFollowing,
      );
      if (!mounted) return;
      setState(() {
        if (currentlyFollowing) {
          _unfollowedInSession.add(user.username);
        } else {
          _unfollowedInSession.remove(user.username);
        }
      });
    } catch (_) {
      widget.onShowToast?.call(
        currentlyFollowing
            ? LinDoMessagesCopy.UNFOLLOW_FAILED_RETRY.text
            : LinDoMessagesCopy.FOLLOW_FAILED_RETRY.text,
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    final scope = LinDoThemeScope.of(context);
    final isSiteVerify =
        widget.repository.siteVerificationRequired || _error.isSiteVerificationError;

    return Material(
      color: palette.canvas,
      child: Column(
        children: [
          _SheetHeaderBar(
            icon: LinDoIcons.UserSelected,
            title: LinDoMessagesCopy.ROW_FOLLOW_NETWORK.text,
            onRefresh: _loading ? null : _load,
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 10),
            child: Row(
              children: FollowNetworkTab.values.map((tab) {
                final selected = tab == _activeTab;
                return Padding(
                  padding: const EdgeInsets.only(right: 8),
                  child: InkWell(
                    borderRadius: BorderRadius.circular(999),
                    onTap: () {
                      if (selected) return;
                      setState(() => _activeTab = tab);
                      _load();
                    },
                    child: Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 14,
                        vertical: 6,
                      ),
                      decoration: BoxDecoration(
                        color: selected
                            ? palette.activeChipBg
                            : palette.subtleSurface,
                        borderRadius: BorderRadius.circular(999),
                        border: Border.all(
                          color: selected
                              ? palette.activeChipText.withValues(alpha: 0.35)
                              : palette.hairline,
                        ),
                      ),
                      child: Text(
                        tab.labelCopy.text,
                        style: TextStyle(
                          fontSize: scope.sp(12.5),
                          fontWeight:
                              selected ? FontWeight.w700 : FontWeight.w500,
                          color: selected
                              ? palette.activeChipText
                              : palette.inkSecondary,
                        ),
                      ),
                    ),
                  ),
                );
              }).toList(),
            ),
          ),
          Divider(height: 1, color: palette.hairline),
          Expanded(
            child: _loading
                ? LinDoStatePanel(
                    mood: MascotMood.connecting,
                    title: LinDoCommonCopy.LOADING.text,
                  )
                : isSiteVerify
                    ? LinDoSiteVerificationCard(
                        onStartInAppVerify: () async {
                          await widget.onStartSiteVerify();
                          if (mounted) _load();
                        },
                        onRetry: _load,
                      )
                    : _error != null
                        ? LinDoStatePanel(
                            mood: MascotMood.surprised,
                            title: LinDoCommonCopy.LOAD_FAILED.text,
                            subtitle: _error,
                            actionLabel: LinDoCommonCopy.RETRY.text,
                            onAction: _load,
                          )
                        : _activeTab == FollowNetworkTab.FEED
                            ? (_feedTopics.isEmpty
                                ? LinDoStatePanel(
                                    mood: MascotMood.sleepy,
                                    title:
                                        LinDoMessagesCopy.EMPTY_FOLLOW_FEED.text,
                                  )
                                : ListView.builder(
                                    padding: const EdgeInsets.fromLTRB(
                                      16,
                                      12,
                                      16,
                                      28,
                                    ),
                                    itemCount: _feedTopics.length,
                                    itemBuilder: (context, index) {
                                      final t = _feedTopics[index];
                                      return RepaintBoundary(
                                        child: Padding(
                                          padding:
                                              const EdgeInsets.only(bottom: 9),
                                          child: ListTile(
                                            tileColor: palette.subtleSurface,
                                            shape: RoundedRectangleBorder(
                                              borderRadius:
                                                  BorderRadius.circular(14),
                                              side: BorderSide(
                                                color: palette.hairline,
                                              ),
                                            ),
                                            leading: SmartAvatar(
                                              avatarUrl: t.originalPoster
                                                  .avatarTemplate,
                                              fallbackLabel:
                                                  t.originalPoster.username,
                                              size: 38,
                                            ),
                                            title: Text(
                                              t.title,
                                              maxLines: 2,
                                              overflow: TextOverflow.ellipsis,
                                              style: TextStyle(
                                                fontSize: scope.sp(14),
                                                fontWeight: FontWeight.w700,
                                                color: palette.inkPrimary,
                                              ),
                                            ),
                                            subtitle: Text(
                                              '@${t.originalPoster.username} · ${t.categoryName}',
                                              style: TextStyle(
                                                fontSize: scope.sp(12),
                                                color: palette.inkSecondary,
                                              ),
                                            ),
                                            onTap: () {
                                              Navigator.of(context).pop();
                                              widget.onOpenTopic(
                                                t.id,
                                                title: t.title,
                                              );
                                            },
                                          ),
                                        ),
                                      );
                                    },
                                  ))
                            : (_users.isEmpty
                                ? LinDoStatePanel(
                                    mood: MascotMood.sleepy,
                                    title:
                                        _activeTab == FollowNetworkTab.FOLLOWING
                                            ? LinDoMessagesCopy
                                                .EMPTY_FOLLOWING.text
                                            : LinDoMessagesCopy
                                                .EMPTY_FOLLOWERS.text,
                                  )
                                : ListView.builder(
                                    padding: const EdgeInsets.fromLTRB(
                                      16,
                                      12,
                                      16,
                                      28,
                                    ),
                                    itemCount: _users.length,
                                    itemBuilder: (context, index) {
                                      final user = _users[index];
                                      final isFollowing = !_unfollowedInSession
                                          .contains(user.username);
                                      return RepaintBoundary(
                                        child: Padding(
                                          padding:
                                              const EdgeInsets.only(bottom: 9),
                                          child: Material(
                                            color: palette.subtleSurface,
                                            shape: RoundedRectangleBorder(
                                              borderRadius:
                                                  BorderRadius.circular(14),
                                              side: BorderSide(
                                                color: palette.hairline,
                                              ),
                                            ),
                                            child: InkWell(
                                              borderRadius:
                                                  BorderRadius.circular(14),
                                              onTap: () {
                                                showPublicUserCardSheet(
                                                  context,
                                                  username: user.username,
                                                  repository: widget.repository,
                                                  store: widget.store,
                                                  onOpenTopic:
                                                      widget.onOpenTopic,
                                                  onStartSiteVerify:
                                                      widget.onStartSiteVerify,
                                                  onShowToast:
                                                      widget.onShowToast,
                                                );
                                              },
                                              child: Padding(
                                                padding:
                                                    const EdgeInsets.symmetric(
                                                  horizontal: 14,
                                                  vertical: 11,
                                                ),
                                                child: Row(
                                                  children: [
                                                    SmartAvatar(
                                                      avatarUrl:
                                                          user.avatarTemplate,
                                                      fallbackLabel:
                                                          user.username,
                                                      size: 42,
                                                      flair: user.flair,
                                                      trustLevel:
                                                          user.trustLevel,
                                                      isAdmin: user.admin,
                                                      isModerator:
                                                          user.moderator,
                                                      animatedAvatar:
                                                          user.animatedAvatar,
                                                      primaryGroupName: user
                                                          .primaryGroupName,
                                                    ),
                                                    const SizedBox(width: 12),
                                                    Expanded(
                                                      child: Column(
                                                        crossAxisAlignment:
                                                            CrossAxisAlignment
                                                                .start,
                                                        children: [
                                                          Text(
                                                            user.displayName,
                                                            maxLines: 1,
                                                            overflow:
                                                                TextOverflow
                                                                    .ellipsis,
                                                            style: TextStyle(
                                                              fontSize:
                                                                  scope.sp(14),
                                                              fontWeight:
                                                                  FontWeight
                                                                      .w700,
                                                              color: palette
                                                                  .inkPrimary,
                                                            ),
                                                          ),
                                                          Text(
                                                            '@${user.username}',
                                                            style: TextStyle(
                                                              fontSize:
                                                                  scope.sp(12),
                                                              color: palette
                                                                  .inkSecondary,
                                                            ),
                                                          ),
                                                        ],
                                                      ),
                                                    ),
                                                    if (_activeTab ==
                                                            FollowNetworkTab
                                                                .FOLLOWING &&
                                                        widget.repository
                                                            .isAuthorized)
                                                      OutlinedButton(
                                                        style: OutlinedButton
                                                            .styleFrom(
                                                          visualDensity:
                                                              VisualDensity
                                                                  .compact,
                                                          side: BorderSide(
                                                            color: palette
                                                                .hairline,
                                                          ),
                                                        ),
                                                        onPressed: () =>
                                                            _toggleFollow(user),
                                                        child: Text(
                                                          isFollowing
                                                              ? LinDoMessagesCopy
                                                                  .FOLLOWING_BTN
                                                                  .text
                                                              : LinDoMessagesCopy
                                                                  .FOLLOW_PLUS_BTN
                                                                  .text,
                                                          style: TextStyle(
                                                            fontSize:
                                                                scope.sp(12),
                                                          ),
                                                        ),
                                                      ),
                                                  ],
                                                ),
                                              ),
                                            ),
                                          ),
                                        ),
                                      );
                                    },
                                  )),
          ),
        ],
      ),
    );
  }
}

// ============================================================================
// 7. LeaderboardSheet
// ============================================================================

Future<void> showLeaderboardSheet(
  BuildContext context, {
  required LinuxDoRepository repository,
  required LinDoLocalStore store,
  required void Function(int topicId, {int? postNumber, String? title})
      onOpenTopic,
  required Future<void> Function() onStartSiteVerify,
  ValueChanged<String>? onShowToast,
}) {
  return _presentResponsiveSheet<void>(
    context,
    child: LeaderboardSheet(
      repository: repository,
      store: store,
      onOpenTopic: onOpenTopic,
      onStartSiteVerify: onStartSiteVerify,
      onShowToast: onShowToast,
    ),
  );
}

class LeaderboardSheet extends StatefulWidget {
  final LinuxDoRepository repository;
  final LinDoLocalStore store;
  final void Function(int topicId, {int? postNumber, String? title})
      onOpenTopic;
  final Future<void> Function() onStartSiteVerify;
  final ValueChanged<String>? onShowToast;

  const LeaderboardSheet({
    super.key,
    required this.repository,
    required this.store,
    required this.onOpenTopic,
    required this.onStartSiteVerify,
    this.onShowToast,
  });

  @override
  State<LeaderboardSheet> createState() => _LeaderboardSheetState();
}

class _LeaderboardSheetState extends State<LeaderboardSheet> {
  LeaderboardPeriod _period = LeaderboardPeriod.WEEKLY;
  List<LeaderboardEntry> _entries = const <LeaderboardEntry>[];
  bool _loading = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final list = await widget.repository.fetchLeaderboard(
        period: _period,
      );
      if (!mounted) return;
      setState(() {
        _entries = list;
        _loading = false;
      });
    } catch (err) {
      if (!mounted) return;
      setState(() {
        _error = err.toString();
        _loading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    final scope = LinDoThemeScope.of(context);
    final isSiteVerify =
        widget.repository.siteVerificationRequired || _error.isSiteVerificationError;

    return Material(
      color: palette.canvas,
      child: Column(
        children: [
          _SheetHeaderBar(
            icon: LinDoIcons.Crown,
            title: LinDoMessagesCopy.LEADERBOARD_SHEET_TITLE.text,
            onRefresh: _loading ? null : _load,
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 10),
            child: Row(
              children: LeaderboardPeriod.values.map((p) {
                final selected = p == _period;
                return Padding(
                  padding: const EdgeInsets.only(right: 8),
                  child: InkWell(
                    borderRadius: BorderRadius.circular(999),
                    onTap: () {
                      if (selected) return;
                      setState(() => _period = p);
                      _load();
                    },
                    child: Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 14,
                        vertical: 6,
                      ),
                      decoration: BoxDecoration(
                        color: selected
                            ? palette.activeChipBg
                            : palette.subtleSurface,
                        borderRadius: BorderRadius.circular(999),
                        border: Border.all(
                          color: selected
                              ? palette.activeChipText.withValues(alpha: 0.35)
                              : palette.hairline,
                        ),
                      ),
                      child: Text(
                        p.labelCopy.text,
                        style: TextStyle(
                          fontSize: scope.sp(12.5),
                          fontWeight:
                              selected ? FontWeight.w700 : FontWeight.w500,
                          color: selected
                              ? palette.activeChipText
                              : palette.inkSecondary,
                        ),
                      ),
                    ),
                  ),
                );
              }).toList(),
            ),
          ),
          Divider(height: 1, color: palette.hairline),
          Expanded(
            child: _loading
                ? LinDoStatePanel(
                    mood: MascotMood.connecting,
                    title: LinDoMessagesCopy.LOADING_LEADERBOARD.text,
                  )
                : isSiteVerify
                    ? LinDoSiteVerificationCard(
                        onStartInAppVerify: () async {
                          await widget.onStartSiteVerify();
                          if (mounted) _load();
                        },
                        onRetry: _load,
                      )
                    : _error != null
                        ? LinDoStatePanel(
                            mood: MascotMood.surprised,
                            title:
                                LinDoMessagesCopy.LEADERBOARD_LOAD_FAILED.text,
                            subtitle: _error,
                            actionLabel: LinDoCommonCopy.RETRY.text,
                            onAction: _load,
                          )
                        : _entries.isEmpty
                            ? LinDoStatePanel(
                                mood: MascotMood.sleepy,
                                title: LinDoMessagesCopy.EMPTY_LEADERBOARD.text,
                              )
                            : ListView.builder(
                                padding:
                                    const EdgeInsets.fromLTRB(16, 12, 16, 28),
                                itemCount: _entries.length,
                                itemBuilder: (context, index) {
                                  final item = _entries[index];
                                  final rank =
                                      item.rank > 0 ? item.rank : index + 1;
                                  final Color? medalColor = rank == 1
                                      ? const Color(0xFFF59E0B)
                                      : rank == 2
                                          ? const Color(0xFF94A3B8)
                                          : rank == 3
                                              ? const Color(0xFFD97706)
                                              : null;
                                  return RepaintBoundary(
                                    child: Padding(
                                      padding: const EdgeInsets.only(bottom: 9),
                                      child: Material(
                                        color: palette.subtleSurface,
                                        shape: RoundedRectangleBorder(
                                          borderRadius:
                                              BorderRadius.circular(14),
                                          side: BorderSide(
                                            color: medalColor != null
                                                ? medalColor.withValues(
                                                    alpha: 0.35,
                                                  )
                                                : palette.hairline,
                                          ),
                                        ),
                                        child: InkWell(
                                          borderRadius:
                                              BorderRadius.circular(14),
                                          onTap: () {
                                            showPublicUserCardSheet(
                                              context,
                                              username: item.username,
                                              repository: widget.repository,
                                              store: widget.store,
                                              onOpenTopic: widget.onOpenTopic,
                                              onStartSiteVerify:
                                                  widget.onStartSiteVerify,
                                              onShowToast: widget.onShowToast,
                                            );
                                          },
                                          child: Padding(
                                            padding: const EdgeInsets.symmetric(
                                              horizontal: 14,
                                              vertical: 12,
                                            ),
                                            child: Row(
                                              children: [
                                                SizedBox(
                                                  width: 32,
                                                  child: Text(
                                                    '#$rank',
                                                    style: TextStyle(
                                                      fontSize: scope.sp(
                                                        rank <= 3 ? 15 : 13,
                                                      ),
                                                      fontWeight:
                                                          FontWeight.w800,
                                                      color: medalColor ??
                                                          palette.inkTertiary,
                                                    ),
                                                  ),
                                                ),
                                                SmartAvatar(
                                                  avatarUrl:
                                                      item.avatarTemplate,
                                                  fallbackLabel: item.username,
                                                  size: 40,
                                                ),
                                                const SizedBox(width: 12),
                                                Expanded(
                                                  child: Column(
                                                    crossAxisAlignment:
                                                        CrossAxisAlignment
                                                            .start,
                                                    children: [
                                                      Text(
                                                        item.displayName
                                                                .trim()
                                                                .isEmpty
                                                            ? item.username
                                                            : item.displayName,
                                                        maxLines: 1,
                                                        overflow: TextOverflow
                                                            .ellipsis,
                                                        style: TextStyle(
                                                          fontSize:
                                                              scope.sp(14),
                                                          fontWeight:
                                                              FontWeight.w700,
                                                          color: palette
                                                              .inkPrimary,
                                                        ),
                                                      ),
                                                      const SizedBox(height: 2),
                                                      Text(
                                                        LinDoMessagesCopy
                                                            .LEADERBOARD_ENTRY_META
                                                            .format([
                                                          formatCompactCount(
                                                            item.likesReceived,
                                                          ),
                                                          formatCompactCount(
                                                            item.postCount,
                                                          ),
                                                          formatCompactCount(
                                                            item.topicCount,
                                                          ),
                                                        ]),
                                                        style: TextStyle(
                                                          fontSize:
                                                              scope.sp(11.5),
                                                          color: palette
                                                              .inkSecondary,
                                                        ),
                                                      ),
                                                    ],
                                                  ),
                                                ),
                                                if (item.cheersScore > 0 ||
                                                    item.likesReceived > 0)
                                                  Column(
                                                    crossAxisAlignment:
                                                        CrossAxisAlignment.end,
                                                    children: [
                                                      Text(
                                                        formatCompactCount(
                                                          item.cheersScore > 0
                                                              ? item.cheersScore
                                                              : item
                                                                  .likesReceived,
                                                        ),
                                                        style: TextStyle(
                                                          fontSize:
                                                              scope.sp(15),
                                                          fontWeight:
                                                              FontWeight.w800,
                                                          color: palette
                                                              .logoDoColor,
                                                        ),
                                                      ),
                                                      Text(
                                                        LinDoMessagesCopy
                                                            .CHEERS_SCORE_LABEL
                                                            .text,
                                                        style: TextStyle(
                                                          fontSize:
                                                              scope.sp(10.5),
                                                          color: palette
                                                              .inkTertiary,
                                                        ),
                                                      ),
                                                    ],
                                                  ),
                                              ],
                                            ),
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
    );
  }
}

// ============================================================================
// 8. PublicUserCardSheet
// ============================================================================

Future<void> showPublicUserCardSheet(
  BuildContext context, {
  required String username,
  required LinuxDoRepository repository,
  required LinDoLocalStore store,
  required void Function(int topicId, {int? postNumber, String? title})
      onOpenTopic,
  required Future<void> Function() onStartSiteVerify,
  Future<void> Function()? onRequestGuestLogin,
  ValueChanged<String>? onShowToast,
}) {
  return _presentResponsiveSheet<void>(
    context,
    maxWidth: 660,
    child: PublicUserCardSheet(
      username: username,
      repository: repository,
      store: store,
      onOpenTopic: onOpenTopic,
      onStartSiteVerify: onStartSiteVerify,
      onRequestGuestLogin: onRequestGuestLogin,
      onShowToast: onShowToast,
    ),
  );
}

class PublicUserCardSheet extends StatefulWidget {
  final String username;
  final LinuxDoRepository repository;
  final LinDoLocalStore store;
  final void Function(int topicId, {int? postNumber, String? title})
      onOpenTopic;
  final Future<void> Function() onStartSiteVerify;
  final Future<void> Function()? onRequestGuestLogin;
  final ValueChanged<String>? onShowToast;

  const PublicUserCardSheet({
    super.key,
    required this.username,
    required this.repository,
    required this.store,
    required this.onOpenTopic,
    required this.onStartSiteVerify,
    this.onRequestGuestLogin,
    this.onShowToast,
  });

  @override
  State<PublicUserCardSheet> createState() => _PublicUserCardSheetState();
}

class _PublicUserCardSheetState extends State<PublicUserCardSheet> {
  UserFullProfileSummary? _summary;
  PublicUserCardTab _activeTab = PublicUserCardTab.ALL_ACTIVITY;
  List<UserActivityItem> _activities = const <UserActivityItem>[];
  bool _loadingProfile = true;
  bool _loadingActivities = false;
  bool _expandStats = false;
  bool _isFollowing = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _loadAll();
  }

  Future<void> _loadAll() async {
    final cleanUser = widget.username.trim().replaceFirst(RegExp(r'^@+'), '');
    setState(() {
      _loadingProfile = true;
      _error = null;
    });
    try {
      final profile = await widget.repository.fetchUserSummary(cleanUser);
      if (!mounted) return;
      setState(() {
        _summary = profile;
        _isFollowing = profile.isFollowing;
        _loadingProfile = false;
      });
      _loadTabActivities();
    } catch (err) {
      if (!mounted) return;
      setState(() {
        _error = err.toString();
        _loadingProfile = false;
      });
    }
  }

  Future<void> _loadTabActivities() async {
    if (_activeTab == PublicUserCardTab.BADGES) return;
    final cleanUser = widget.username.trim().replaceFirst(RegExp(r'^@+'), '');
    setState(() => _loadingActivities = true);
    try {
      final filter = _activeTab == PublicUserCardTab.TOPICS
          ? '4'
          : _activeTab == PublicUserCardTab.REPLIES
              ? '5'
              : null;
      final list = await widget.repository.fetchUserActions(
        cleanUser,
        filter: filter,
      );
      if (!mounted) return;
      setState(() {
        _activities = list;
        _loadingActivities = false;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() => _loadingActivities = false);
    }
  }

  Future<void> _handleFollowToggle() async {
    if (!widget.repository.isAuthorized) {
      if (widget.onRequestGuestLogin != null) {
        await showGuestLoginGuideSheet(
          context,
          customTitle: LinDoAuthCopy.GUEST_FOLLOW_USER_TITLE.text,
          customSubtitle:
              LinDoAuthCopy.GUEST_FOLLOW_USER_SUBTITLE.format(widget.username),
          onStartWebLogin: widget.onRequestGuestLogin!,
        );
      }
      return;
    }
    final summary = _summary;
    if (summary == null) return;
    try {
      await widget.repository.toggleFollowUser(
        summary.username,
        follow: !_isFollowing,
      );
      if (!mounted) return;
      setState(() => _isFollowing = !_isFollowing);
    } catch (_) {
      widget.onShowToast?.call(
        _isFollowing
            ? LinDoMessagesCopy.UNFOLLOW_FAILED_RETRY.text
            : LinDoMessagesCopy.FOLLOW_FAILED_RETRY.text,
      );
    }
  }

  Future<void> _handleSendPm() async {
    if (!widget.repository.isAuthorized) {
      if (widget.onRequestGuestLogin != null) {
        await showGuestLoginGuideSheet(
          context,
          customTitle: LinDoAuthCopy.GUEST_PRIVATE_MESSAGE_TITLE.text,
          customSubtitle: LinDoAuthCopy.GUEST_PRIVATE_MESSAGE_SUBTITLE
              .format(widget.username),
          onStartWebLogin: widget.onRequestGuestLogin!,
        );
      }
      return;
    }
    final summary = _summary;
    if (summary == null) return;
    await showLinDoComposerSheet(
      context,
      repository: widget.repository,
      store: widget.store,
      action: ComposerAction.PRIVATE_MESSAGE,
      initialRecipients: <String>[summary.username],
    );
  }

  String _tabLabel(PublicUserCardTab tab, UserFullProfileSummary s) {
    switch (tab) {
      case PublicUserCardTab.ALL_ACTIVITY:
        return LinDoMessagesCopy.USER_TAB_ACTIVITY.text;
      case PublicUserCardTab.TOPICS:
        return s.topicCount > 0
            ? LinDoMessagesCopy.USER_TAB_TOPICS_COUNT
                .format(formatCompactCount(s.topicCount))
            : LinDoMessagesCopy.USER_TAB_TOPICS.text;
      case PublicUserCardTab.REPLIES:
        return s.postCount > 0
            ? LinDoMessagesCopy.USER_TAB_REPLIES_COUNT
                .format(formatCompactCount(s.postCount))
            : LinDoMessagesCopy.USER_TAB_REPLIES.text;
      case PublicUserCardTab.BADGES:
        return s.badgeCount > 0
            ? LinDoMessagesCopy.USER_TAB_BADGES_COUNT
                .format(formatCompactCount(s.badgeCount))
            : LinDoMessagesCopy.USER_TAB_BADGES.text;
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    final scope = LinDoThemeScope.of(context);
    final isSiteVerify =
        widget.repository.siteVerificationRequired || _error.isSiteVerificationError;

    return Material(
      color: palette.canvas,
      child: Column(
        children: [
          _SheetHeaderBar(
            icon: LinDoIcons.UserSelected,
            title: LinDoMessagesCopy.PUBLIC_USER_PROFILE_TITLE.text,
            onRefresh: _loadingProfile ? null : _loadAll,
          ),
          Divider(height: 1, color: palette.hairline),
          Expanded(
            child: _loadingProfile
                ? LinDoStatePanel(
                    mood: MascotMood.connecting,
                    title: LinDoCommonCopy.LOADING.text,
                  )
                : isSiteVerify
                    ? LinDoSiteVerificationCard(
                        onStartInAppVerify: () async {
                          await widget.onStartSiteVerify();
                          if (mounted) _loadAll();
                        },
                        onRetry: _loadAll,
                      )
                    : _error != null || _summary == null
                        ? LinDoStatePanel(
                            mood: MascotMood.surprised,
                            title: LinDoCommonCopy.LOAD_FAILED.text,
                            subtitle: _error,
                            actionLabel: LinDoCommonCopy.RETRY.text,
                            onAction: _loadAll,
                          )
                        : _buildProfileContent(context, palette, scope, _summary!),
          ),
        ],
      ),
    );
  }

  Widget _buildProfileContent(
    BuildContext context,
    LinDoThemePalette palette,
    LinDoThemeScope scope,
    UserFullProfileSummary s,
  ) {
    final isSelf = widget.repository.currentUser?.username.toLowerCase() ==
        s.username.toLowerCase();
    final cleanBio = decodeDiscourseHtmlSnippet(s.bioExcerpt);

    return CustomScrollView(
      slivers: [
        SliverToBoxAdapter(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(18, 16, 18, 12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    SmartAvatar(
                      avatarUrl: s.avatarTemplate,
                      fallbackLabel: s.username,
                      size: 64,
                      flair: s.flair,
                      trustLevel: s.trustLevel,
                      isAdmin: s.admin,
                      isModerator: s.moderator,
                      animatedAvatar: s.animatedAvatar,
                      primaryGroupName: s.primaryGroupName,
                    ),
                    const SizedBox(width: 14),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            s.displayName.trim().isEmpty
                                ? s.username
                                : s.displayName,
                            style: TextStyle(
                              fontSize: scope.sp(18.5),
                              fontWeight: FontWeight.w800,
                              color: palette.inkPrimary,
                            ),
                          ),
                          const SizedBox(height: 2),
                          Text(
                            '@${s.username}',
                            style: TextStyle(
                              fontSize: scope.sp(13),
                              color: palette.inkSecondary,
                            ),
                          ),
                          const SizedBox(height: 6),
                          UserTitleTag(
                            title: s.title.isNotEmpty
                                ? s.title
                                : s.trustLevelName,
                            primaryGroupName: s.primaryGroupName,
                            trustLevel: s.trustLevel,
                            isAdmin: s.admin,
                            isModerator: s.moderator,
                          ),
                        ],
                      ),
                    ),
                    if (!isSelf)
                      Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          FilledButton.tonal(
                            style: FilledButton.styleFrom(
                              backgroundColor: _isFollowing
                                  ? palette.tonalPill
                                  : palette.publishBtnBg,
                              foregroundColor: _isFollowing
                                  ? palette.inkPrimary
                                  : palette.publishBtnContent,
                              visualDensity: VisualDensity.compact,
                              shape: RoundedRectangleBorder(
                                borderRadius: BorderRadius.circular(10),
                              ),
                            ),
                            onPressed: _handleFollowToggle,
                            child: Text(
                              _isFollowing
                                  ? LinDoMessagesCopy.FOLLOWING_BTN.text
                                  : LinDoMessagesCopy.FOLLOW_PLUS_BTN.text,
                              style: TextStyle(
                                fontSize: scope.sp(12.5),
                                fontWeight: FontWeight.w700,
                              ),
                            ),
                          ),
                          const SizedBox(width: 6),
                          IconButton(
                            tooltip: LinDoMessagesCopy.SEND_PM_SHORT.text,
                            onPressed: _handleSendPm,
                            icon: Icon(
                              LinDoIcons.Send,
                              size: 18,
                              color: palette.inkSecondary,
                            ),
                          ),
                        ],
                      ),
                  ],
                ),
                if (cleanBio.isNotEmpty) ...[
                  const SizedBox(height: 12),
                  Text(
                    cleanBio,
                    style: TextStyle(
                      fontSize: scope.sp(13.5),
                      height: 1.48,
                      color: palette.inkPrimary,
                    ),
                  ),
                ],
                const SizedBox(height: 12),
                Wrap(
                  spacing: 10,
                  runSpacing: 6,
                  children: [
                    if (s.createdAtText.isNotEmpty)
                      _MetaCaption(
                        LinDoMessagesCopy.JOINED_AT_META
                            .format(formatRelativeTime(s.createdAtText)),
                      ),
                    if (s.lastSeenText.isNotEmpty)
                      _MetaCaption(
                        LinDoMessagesCopy.LAST_SEEN_META
                            .format(formatRelativeTime(s.lastSeenText)),
                      ),
                    if (s.location.isNotEmpty)
                      _MetaCaption(
                        LinDoMessagesCopy.LOCATION_META.format(s.location),
                      ),
                    if (s.solvedCount > 0)
                      _MetaCaption(
                        LinDoMessagesCopy.SOLVED_COUNT_META
                            .format(s.solvedCount),
                      ),
                  ],
                ),
                const SizedBox(height: 14),
                Container(
                  padding: const EdgeInsets.all(14),
                  decoration: BoxDecoration(
                    color: palette.groupedCanvas,
                    borderRadius: BorderRadius.circular(16),
                    border: Border.all(color: palette.hairline),
                  ),
                  child: Column(
                    children: [
                      Row(
                        children: [
                          Expanded(
                            child: _StatCell(
                              label: LinDoMessagesCopy.STAT_LIKES_RECEIVED.text,
                              value: formatCompactCount(s.likesReceived),
                            ),
                          ),
                          Expanded(
                            child: _StatCell(
                              label: LinDoMessagesCopy.STAT_DAYS_VISITED.text,
                              value: formatCompactCount(s.daysVisited),
                            ),
                          ),
                          Expanded(
                            child: _StatCell(
                              label: LinDoMessagesCopy.STAT_READ_TIME.text,
                              value: _formatReadDuration(s.timeReadSeconds),
                            ),
                          ),
                        ],
                      ),
                      if (_expandStats) ...[
                        Padding(
                          padding: const EdgeInsets.symmetric(vertical: 10),
                          child: Divider(height: 1, color: palette.hairline),
                        ),
                        Row(
                          children: [
                            Expanded(
                              child: _StatCell(
                                label:
                                    LinDoMessagesCopy.STAT_TOPICS_ENTERED.text,
                                value: formatCompactCount(s.topicsEntered),
                              ),
                            ),
                            Expanded(
                              child: _StatCell(
                                label: LinDoMessagesCopy.STAT_POSTS_READ.text,
                                value: formatCompactCount(s.postsReadCount),
                              ),
                            ),
                            Expanded(
                              child: _StatCell(
                                label: LinDoMessagesCopy.STAT_LIKES_GIVEN.text,
                                value: formatCompactCount(s.likesGiven),
                              ),
                            ),
                          ],
                        ),
                      ],
                      const SizedBox(height: 6),
                      InkWell(
                        borderRadius: BorderRadius.circular(8),
                        onTap: () =>
                            setState(() => _expandStats = !_expandStats),
                        child: Padding(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 8,
                            vertical: 4,
                          ),
                          child: Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Text(
                                _expandStats
                                    ? LinDoMessagesCopy
                                        .COLLAPSE_COMMUNITY_DATA.text
                                    : LinDoMessagesCopy.COMMUNITY_DATA_BTN.text,
                                style: TextStyle(
                                  fontSize: scope.sp(11.5),
                                  fontWeight: FontWeight.w600,
                                  color: palette.inkTertiary,
                                ),
                              ),
                              Icon(
                                _expandStats
                                    ? LinDoIcons.ChevronUp
                                    : LinDoIcons.ChevronDown,
                                size: 15,
                                color: palette.inkTertiary,
                              ),
                            ],
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 14),
                SingleChildScrollView(
                  scrollDirection: Axis.horizontal,
                  child: Row(
                    children: PublicUserCardTab.values.map((tab) {
                      final selected = tab == _activeTab;
                      return Padding(
                        padding: const EdgeInsets.only(right: 8),
                        child: InkWell(
                          borderRadius: BorderRadius.circular(999),
                          onTap: () {
                            if (selected) return;
                            setState(() => _activeTab = tab);
                            _loadTabActivities();
                          },
                          child: Container(
                            padding: const EdgeInsets.symmetric(
                              horizontal: 13,
                              vertical: 6,
                            ),
                            decoration: BoxDecoration(
                              color: selected
                                  ? palette.activeChipBg
                                  : palette.subtleSurface,
                              borderRadius: BorderRadius.circular(999),
                              border: Border.all(
                                color: selected
                                    ? palette.activeChipText
                                        .withValues(alpha: 0.35)
                                    : palette.hairline,
                              ),
                            ),
                            child: Text(
                              _tabLabel(tab, s),
                              style: TextStyle(
                                fontSize: scope.sp(12.5),
                                fontWeight: selected
                                    ? FontWeight.w700
                                    : FontWeight.w500,
                                color: selected
                                    ? palette.activeChipText
                                    : palette.inkSecondary,
                              ),
                            ),
                          ),
                        ),
                      );
                    }).toList(),
                  ),
                ),
              ],
            ),
          ),
        ),
        if (_activeTab == PublicUserCardTab.BADGES)
          SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(18, 4, 18, 28),
              child: s.badges.isEmpty
                  ? LinDoStatePanel(
                      mood: MascotMood.sleepy,
                      title: LinDoMessagesCopy.EMPTY_PUBLIC_BADGES.text,
                      compact: true,
                    )
                  : Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Wrap(
                          spacing: 8,
                          runSpacing: 8,
                          children: s.badges
                              .map(
                                (b) => UniversalBadgeChip(
                                  badge: b,
                                  onTap: () => showBadgeDetailSheet(
                                    context,
                                    badge: b,
                                    repository: widget.repository,
                                    onOpenTopic: (topicId, {postNumber}) {
                                      Navigator.of(context).pop();
                                      widget.onOpenTopic(
                                        topicId,
                                        postNumber: postNumber,
                                      );
                                    },
                                  ),
                                ),
                              )
                              .toList(),
                        ),
                        const SizedBox(height: 14),
                        OutlinedButton.icon(
                          onPressed: () => showBadgesSheet(
                            context,
                            repository: widget.repository,
                            username: s.username,
                            onStartSiteVerify: widget.onStartSiteVerify,
                            onOpenTopic: (topicId, {postNumber}) {
                              Navigator.of(context).pop();
                              widget.onOpenTopic(
                                topicId,
                                postNumber: postNumber,
                              );
                            },
                          ),
                          icon: const Icon(LinDoIcons.Trophy, size: 15),
                          label: Text(LinDoMessagesCopy.VIEW_BADGE_WALL.text),
                        ),
                      ],
                    ),
            ),
          )
        else if (_loadingActivities)
          SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 28),
              child: LinDoStatePanel(
                mood: MascotMood.connecting,
                title: LinDoMessagesCopy.LOADING_ACTIVITY_ELLIPSIS.text,
                compact: true,
              ),
            ),
          )
        else if (_activities.isEmpty)
          SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 28),
              child: LinDoStatePanel(
                mood: MascotMood.sleepy,
                title: LinDoMessagesCopy.EMPTY_PUBLIC_ACTIVITY.text,
                compact: true,
              ),
            ),
          )
        else
          SliverPadding(
            padding: const EdgeInsets.fromLTRB(18, 4, 18, 28),
            sliver: SliverList.builder(
              itemCount: _activities.length,
              itemBuilder: (context, index) {
                final item = _activities[index];
                return RepaintBoundary(
                  child: _UserActivityRowCard(
                    item: item,
                    onTap: () {
                      Navigator.of(context).pop();
                      widget.onOpenTopic(
                        item.topicId,
                        postNumber:
                            item.postNumber > 1 ? item.postNumber : null,
                        title: item.title,
                      );
                    },
                  ),
                );
              },
            ),
          ),
      ],
    );
  }
}

// ============================================================================
// 9. EditProfileSheet
// ============================================================================

Future<bool?> showEditProfileSheet(
  BuildContext context, {
  required LinuxDoRepository repository,
  ValueChanged<String>? onShowToast,
}) {
  return _presentResponsiveSheet<bool>(
    context,
    maxWidth: 560,
    child: EditProfileSheet(
      repository: repository,
      onShowToast: onShowToast,
    ),
  );
}

class EditProfileSheet extends StatefulWidget {
  final LinuxDoRepository repository;
  final ValueChanged<String>? onShowToast;

  const EditProfileSheet({
    super.key,
    required this.repository,
    this.onShowToast,
  });

  @override
  State<EditProfileSheet> createState() => _EditProfileSheetState();
}

class _EditProfileSheetState extends State<EditProfileSheet> {
  final TextEditingController _nameCtrl = TextEditingController();
  final TextEditingController _locationCtrl = TextEditingController();
  final TextEditingController _websiteCtrl = TextEditingController();
  final TextEditingController _bioCtrl = TextEditingController();
  String _selectedTitle = '';
  List<String> _availableTitles = const <String>[];
  bool _loading = true;
  bool _saving = false;
  String? _syncError;

  @override
  void initState() {
    super.initState();
    _loadCurrentProfile();
  }

  @override
  void dispose() {
    _nameCtrl.dispose();
    _locationCtrl.dispose();
    _websiteCtrl.dispose();
    _bioCtrl.dispose();
    super.dispose();
  }

  Future<void> _loadCurrentProfile() async {
    final username = widget.repository.currentUser?.username ?? '';
    if (username.isEmpty) {
      setState(() {
        _syncError = LinDoMessagesCopy.PROFILE_SYNC_PAUSED_SAFE.text;
        _loading = false;
      });
      return;
    }
    try {
      final profile = await widget.repository.fetchUserSummary(username);
      final titles = <String>{
        if (profile.title.trim().isNotEmpty) profile.title.trim(),
        for (final b in profile.badges)
          if (b.allowTitle && b.name.trim().isNotEmpty) b.name.trim(),
      }.toList();
      if (!mounted) return;
      setState(() {
        _nameCtrl.text = profile.displayName;
        _locationCtrl.text = profile.location;
        _websiteCtrl.text = profile.websiteUrl;
        _bioCtrl.text = profile.bioRaw.isNotEmpty
            ? profile.bioRaw
            : decodeDiscourseHtmlSnippet(profile.bioExcerpt);
        _selectedTitle = profile.title.trim();
        _availableTitles = titles;
        _loading = false;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _syncError = LinDoMessagesCopy.PROFILE_SYNC_PAUSED_SAFE.text;
        _loading = false;
      });
    }
  }

  Future<void> _save() async {
    if (_saving || _syncError != null) return;
    final username = widget.repository.currentUser?.username ?? '';
    if (username.isEmpty) return;

    setState(() => _saving = true);
    try {
      await widget.repository.updateUserProfile(
        username: username,
        displayName: _nameCtrl.text.trim(),
        title: _selectedTitle,
        location: _locationCtrl.text.trim(),
        website: _websiteCtrl.text.trim(),
        bioRaw: _bioCtrl.text.trim(),
      );
      widget.onShowToast?.call(LinDoCommonCopy.COMPLETED.text);
      if (mounted) Navigator.of(context).pop(true);
    } catch (_) {
      widget.onShowToast?.call(LinDoCommonCopy.SAVE_FAILED_RETRY.text);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    final scope = LinDoThemeScope.of(context);

    return Material(
      color: palette.canvas,
      child: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 14, 14, 12),
            child: Row(
              children: [
                Expanded(
                  child: Text(
                    LinDoMessagesCopy.EDIT_PROFILE_TITLE.text,
                    style: TextStyle(
                      fontSize: scope.sp(17.5),
                      fontWeight: FontWeight.w700,
                      color: palette.inkPrimary,
                    ),
                  ),
                ),
                TextButton(
                  onPressed: () => Navigator.of(context).maybePop(false),
                  child: Text(
                    LinDoCommonCopy.CANCEL.text,
                    style: TextStyle(color: palette.inkSecondary),
                  ),
                ),
                const SizedBox(width: 4),
                FilledButton(
                  style: FilledButton.styleFrom(
                    backgroundColor: palette.publishBtnBg,
                    foregroundColor: palette.publishBtnContent,
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(10),
                    ),
                  ),
                  onPressed:
                      (_loading || _saving || _syncError != null) ? null : _save,
                  child: Text(
                    _saving
                        ? LinDoCommonCopy.SAVING_ELLIPSIS.text
                        : LinDoCommonCopy.SAVE.text,
                    style: TextStyle(
                      fontSize: scope.sp(13.5),
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ),
              ],
            ),
          ),
          Divider(height: 1, color: palette.hairline),
          Expanded(
            child: _loading
                ? LinDoStatePanel(
                    mood: MascotMood.connecting,
                    title: LinDoMessagesCopy.SYNCING_PROFILE_SHORT.text,
                  )
                : _syncError != null
                    ? LinDoStatePanel(
                        mood: MascotMood.surprised,
                        title: _syncError!,
                        actionLabel: LinDoCommonCopy.RETRY.text,
                        onAction: () {
                          setState(() {
                            _loading = true;
                            _syncError = null;
                          });
                          _loadCurrentProfile();
                        },
                      )
                    : ListView(
                        padding: const EdgeInsets.fromLTRB(20, 18, 20, 28),
                        children: [
                          _ProfileInputField(
                            label: LinDoMessagesCopy.FIELD_DISPLAY_NAME.text,
                            placeholder:
                                LinDoMessagesCopy.PLACEHOLDER_DISPLAY_NAME.text,
                            controller: _nameCtrl,
                          ),
                          const SizedBox(height: 14),
                          Text(
                            LinDoMessagesCopy.FIELD_USER_TITLE.text,
                            style: TextStyle(
                              fontSize: scope.sp(13),
                              fontWeight: FontWeight.w600,
                              color: palette.inkSecondary,
                            ),
                          ),
                          const SizedBox(height: 6),
                          DropdownButtonFormField<String>(
                            initialValue: _availableTitles.contains(_selectedTitle)
                                ? _selectedTitle
                                : '',
                            dropdownColor: palette.cardSurface,
                            decoration: InputDecoration(
                              filled: true,
                              fillColor: palette.subtleSurface,
                              border: OutlineInputBorder(
                                borderRadius: BorderRadius.circular(12),
                                borderSide: BorderSide(color: palette.hairline),
                              ),
                              enabledBorder: OutlineInputBorder(
                                borderRadius: BorderRadius.circular(12),
                                borderSide: BorderSide(color: palette.hairline),
                              ),
                              contentPadding: const EdgeInsets.symmetric(
                                horizontal: 14,
                                vertical: 12,
                              ),
                            ),
                            items: <DropdownMenuItem<String>>[
                              DropdownMenuItem<String>(
                                value: '',
                                child: Text(
                                  LinDoMessagesCopy.NO_USER_TITLE_OPTION.text,
                                  style: TextStyle(
                                    fontSize: scope.sp(14),
                                    color: palette.inkPrimary,
                                  ),
                                ),
                              ),
                              ..._availableTitles.map(
                                (t) => DropdownMenuItem<String>(
                                  value: t,
                                  child: Text(
                                    t,
                                    style: TextStyle(
                                      fontSize: scope.sp(14),
                                      color: palette.inkPrimary,
                                    ),
                                  ),
                                ),
                              ),
                            ],
                            onChanged: (val) =>
                                setState(() => _selectedTitle = val ?? ''),
                          ),
                          const SizedBox(height: 14),
                          _ProfileInputField(
                            label: LinDoMessagesCopy.FIELD_LOCATION.text,
                            placeholder:
                                LinDoMessagesCopy.PLACEHOLDER_LOCATION.text,
                            controller: _locationCtrl,
                          ),
                          const SizedBox(height: 14),
                          _ProfileInputField(
                            label: LinDoMessagesCopy.FIELD_WEBSITE.text,
                            placeholder:
                                LinDoMessagesCopy.PLACEHOLDER_WEBSITE.text,
                            controller: _websiteCtrl,
                          ),
                          const SizedBox(height: 14),
                          _ProfileInputField(
                            label: LinDoMessagesCopy.FIELD_BIO.text,
                            placeholder: LinDoMessagesCopy.FIELD_BIO.text,
                            controller: _bioCtrl,
                            maxLines: 5,
                          ),
                        ],
                      ),
          ),
        ],
      ),
    );
  }
}

class _ProfileInputField extends StatelessWidget {
  final String label;
  final String placeholder;
  final TextEditingController controller;
  final int maxLines;

  const _ProfileInputField({
    required this.label,
    required this.placeholder,
    required this.controller,
    this.maxLines = 1,
  });

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    final scope = LinDoThemeScope.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          label,
          style: TextStyle(
            fontSize: scope.sp(13),
            fontWeight: FontWeight.w600,
            color: palette.inkSecondary,
          ),
        ),
        const SizedBox(height: 6),
        TextField(
          controller: controller,
          maxLines: maxLines,
          style: TextStyle(
            fontSize: scope.sp(14),
            color: palette.inkPrimary,
          ),
          decoration: InputDecoration(
            hintText: placeholder,
            hintStyle: TextStyle(
              fontSize: scope.sp(13.5),
              color: palette.inkTertiary,
            ),
            filled: true,
            fillColor: palette.subtleSurface,
            contentPadding: const EdgeInsets.symmetric(
              horizontal: 14,
              vertical: 12,
            ),
            border: OutlineInputBorder(
              borderRadius: BorderRadius.circular(12),
              borderSide: BorderSide(color: palette.hairline),
            ),
            enabledBorder: OutlineInputBorder(
              borderRadius: BorderRadius.circular(12),
              borderSide: BorderSide(color: palette.hairline),
            ),
            focusedBorder: OutlineInputBorder(
              borderRadius: BorderRadius.circular(12),
              borderSide: BorderSide(color: palette.logoDoColor, width: 1.2),
            ),
          ),
        ),
      ],
    );
  }
}

// ============================================================================
// 10. PreferencesSheet
// ============================================================================

Future<void> showPreferencesSheet(
  BuildContext context, {
  required LinDoLocalStore store,
  LinuxDoRepository? repository,
  ValueChanged<String>? onShowToast,
}) {
  return _presentResponsiveSheet<void>(
    context,
    maxWidth: 600,
    child: PreferencesSheet(
      store: store,
      repository: repository,
      onShowToast: onShowToast,
    ),
  );
}

class PreferencesSheet extends StatelessWidget {
  final LinDoLocalStore store;
  final LinuxDoRepository? repository;
  final ValueChanged<String>? onShowToast;

  const PreferencesSheet({
    super.key,
    required this.store,
    this.repository,
    this.onShowToast,
  });

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    final scope = LinDoThemeScope.of(context);

    return Material(
      color: palette.canvas,
      child: ListenableBuilder(
        listenable: store,
        builder: (context, _) {
          final prefs = store.preferences;
          final currentPreset =
              ReaderFontScalePreset.fromScale(prefs.fontScale);
          final currentDensity =
              FeedDensityMode.fromCompact(prefs.isCompactFeed);
          final user = repository?.currentUser;

          return Column(
            children: [
              _SheetHeaderBar(
                icon: LinDoIcons.Sliders,
                title: LinDoMessagesCopy.ROW_ACCOUNT_PREFERENCES.text,
              ),
              Divider(height: 1, color: palette.hairline),
              Expanded(
                child: ListView(
                  padding: const EdgeInsets.fromLTRB(20, 16, 20, 28),
                  children: [
                    if (user != null) ...[
                      Text(
                        LinDoMessagesCopy.SECTION_ACCOUNT_INFO.text,
                        style: TextStyle(
                          fontSize: scope.sp(12.5),
                          fontWeight: FontWeight.w700,
                          color: palette.inkSecondary,
                        ),
                      ),
                      const SizedBox(height: 8),
                      Container(
                        padding: const EdgeInsets.all(14),
                        decoration: BoxDecoration(
                          color: palette.subtleSurface,
                          borderRadius: BorderRadius.circular(14),
                          border: Border.all(color: palette.hairline),
                        ),
                        child: Row(
                          children: [
                            SmartAvatar(
                              avatarUrl: user.avatarTemplate,
                              fallbackLabel: user.username,
                              size: 42,
                              trustLevel: user.trustLevel,
                              animatedAvatar: user.animatedAvatar,
                            ),
                            const SizedBox(width: 12),
                            Expanded(
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Text(
                                    user.displayName.isEmpty
                                        ? user.username
                                        : user.displayName,
                                    style: TextStyle(
                                      fontSize: scope.sp(14.5),
                                      fontWeight: FontWeight.w700,
                                      color: palette.inkPrimary,
                                    ),
                                  ),
                                  Text(
                                    '@${user.username} · ${DiscourseTrustLevel.displayLabel(user.trustLevel)}',
                                    style: TextStyle(
                                      fontSize: scope.sp(12),
                                      color: palette.inkSecondary,
                                    ),
                                  ),
                                ],
                              ),
                            ),
                          ],
                        ),
                      ),
                      const SizedBox(height: 20),
                    ],

                    Text(
                      LinDoMessagesCopy.SECTION_APPEARANCE.text,
                      style: TextStyle(
                        fontSize: scope.sp(12.5),
                        fontWeight: FontWeight.w700,
                        color: palette.inkSecondary,
                      ),
                    ),
                    const SizedBox(height: 10),

                    // Dark/Light/System Mode
                    Container(
                      padding: const EdgeInsets.all(14),
                      decoration: BoxDecoration(
                        color: palette.subtleSurface,
                        borderRadius: BorderRadius.circular(14),
                        border: Border.all(color: palette.hairline),
                      ),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            LinDoFeedCopy.DARK_MODE_DESC.text,
                            style: TextStyle(
                              fontSize: scope.sp(14),
                              fontWeight: FontWeight.w600,
                              color: palette.inkPrimary,
                            ),
                          ),
                          const SizedBox(height: 10),
                          Row(
                            children: [
                              Expanded(
                                child: _SegmentChoiceButton(
                                  label: '跟随系统',
                                  selected: prefs.isDarkOverride == null,
                                  onTap: () => store.updatePreferences(
                                    prefs.copyWith(clearDarkOverride: true),
                                  ),
                                ),
                              ),
                              const SizedBox(width: 8),
                              Expanded(
                                child: _SegmentChoiceButton(
                                  label:
                                      LinDoMessagesCopy.MASCOT_THEME_LIGHT.text,
                                  selected: prefs.isDarkOverride == false,
                                  onTap: () => store.updatePreferences(
                                    prefs.copyWith(isDarkOverride: false),
                                  ),
                                ),
                              ),
                              const SizedBox(width: 8),
                              Expanded(
                                child: _SegmentChoiceButton(
                                  label:
                                      LinDoMessagesCopy.MASCOT_THEME_DARK.text,
                                  selected: prefs.isDarkOverride == true,
                                  onTap: () => store.updatePreferences(
                                    prefs.copyWith(isDarkOverride: true),
                                  ),
                                ),
                              ),
                            ],
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(height: 12),

                    // Brand Theme Swatches (4 options)
                    Container(
                      padding: const EdgeInsets.all(14),
                      decoration: BoxDecoration(
                        color: palette.subtleSurface,
                        borderRadius: BorderRadius.circular(14),
                        border: Border.all(color: palette.hairline),
                      ),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            LinDoMessagesCopy.ROW_THEME_COLOR.text,
                            style: TextStyle(
                              fontSize: scope.sp(14),
                              fontWeight: FontWeight.w600,
                              color: palette.inkPrimary,
                            ),
                          ),
                          const SizedBox(height: 10),
                          Wrap(
                            spacing: 10,
                            runSpacing: 10,
                            children: BrandThemeOption.values.map((opt) {
                              final selected = prefs.brandTheme == opt;
                              return InkWell(
                                borderRadius: BorderRadius.circular(12),
                                onTap: () => store.updatePreferences(
                                  prefs.copyWith(brandTheme: opt),
                                ),
                                child: Container(
                                  padding: const EdgeInsets.symmetric(
                                    horizontal: 12,
                                    vertical: 8,
                                  ),
                                  decoration: BoxDecoration(
                                    color: selected
                                        ? palette.activeChipBg
                                        : palette.canvas,
                                    borderRadius: BorderRadius.circular(12),
                                    border: Border.all(
                                      color: selected
                                          ? opt.swatchColor
                                          : palette.hairline,
                                      width: selected ? 1.5 : 1.0,
                                    ),
                                  ),
                                  child: Row(
                                    mainAxisSize: MainAxisSize.min,
                                    children: [
                                      Container(
                                        width: 14,
                                        height: 14,
                                        decoration: BoxDecoration(
                                          color: opt.swatchColor,
                                          shape: BoxShape.circle,
                                        ),
                                      ),
                                      const SizedBox(width: 7),
                                      Text(
                                        opt.label,
                                        style: TextStyle(
                                          fontSize: scope.sp(12.5),
                                          fontWeight: selected
                                              ? FontWeight.w700
                                              : FontWeight.w500,
                                          color: palette.inkPrimary,
                                        ),
                                      ),
                                    ],
                                  ),
                                ),
                              );
                            }).toList(),
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(height: 12),

                    // Feed Density (Spacious vs Compact)
                    Container(
                      padding: const EdgeInsets.all(14),
                      decoration: BoxDecoration(
                        color: palette.subtleSurface,
                        borderRadius: BorderRadius.circular(14),
                        border: Border.all(color: palette.hairline),
                      ),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            LinDoMessagesCopy.ROW_HOME_FEED_VIEW.text,
                            style: TextStyle(
                              fontSize: scope.sp(14),
                              fontWeight: FontWeight.w600,
                              color: palette.inkPrimary,
                            ),
                          ),
                          const SizedBox(height: 10),
                          Row(
                            children: FeedDensityMode.values.map((mode) {
                              final selected = mode == currentDensity;
                              return Expanded(
                                child: Padding(
                                  padding: EdgeInsets.only(
                                    right: mode == FeedDensityMode.SPACIOUS
                                        ? 8
                                        : 0,
                                  ),
                                  child: _SegmentChoiceButton(
                                    label: mode.labelCopy.text,
                                    selected: selected,
                                    onTap: () => store.updatePreferences(
                                      prefs.copyWith(
                                        isCompactFeed: mode.isCompact,
                                      ),
                                    ),
                                  ),
                                ),
                              );
                            }).toList(),
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(height: 12),

                    // Font Scale Preset
                    Container(
                      padding: const EdgeInsets.all(14),
                      decoration: BoxDecoration(
                        color: palette.subtleSurface,
                        borderRadius: BorderRadius.circular(14),
                        border: Border.all(color: palette.hairline),
                      ),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            LinDoMessagesCopy.ROW_READING_FONT_SCALE.text,
                            style: TextStyle(
                              fontSize: scope.sp(14),
                              fontWeight: FontWeight.w600,
                              color: palette.inkPrimary,
                            ),
                          ),
                          const SizedBox(height: 10),
                          Row(
                            children: ReaderFontScalePreset.values.map((preset) {
                              final selected = preset == currentPreset;
                              return Expanded(
                                child: Padding(
                                  padding: EdgeInsets.only(
                                    right:
                                        preset != ReaderFontScalePreset.LARGE
                                            ? 8
                                            : 0,
                                  ),
                                  child: _SegmentChoiceButton(
                                    label: preset.labelCopy.text,
                                    selected: selected,
                                    onTap: () => store.updatePreferences(
                                      prefs.copyWith(fontScale: preset.scale),
                                    ),
                                  ),
                                ),
                              );
                            }).toList(),
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(height: 12),

                    // Animations Switch
                    SwitchListTile(
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(14),
                        side: BorderSide(color: palette.hairline),
                      ),
                      tileColor: palette.subtleSurface,
                      title: Text(
                        LinDoMessagesCopy.ROW_ANIMATIONS.text,
                        style: TextStyle(
                          fontSize: scope.sp(14),
                          fontWeight: FontWeight.w600,
                          color: palette.inkPrimary,
                        ),
                      ),
                      value: prefs.enableAnimations,
                      activeThumbColor: palette.logoDoColor,
                      onChanged: (val) => store.updatePreferences(
                        prefs.copyWith(enableAnimations: val),
                      ),
                    ),
                    const SizedBox(height: 12),

                    // App Icon shortcut
                    ListTile(
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(14),
                        side: BorderSide(color: palette.hairline),
                      ),
                      tileColor: palette.subtleSurface,
                      leading: LinDoGlassAppIcon(
                        icon: prefs.appIcon,
                        size: 32,
                      ),
                      title: Text(
                        LinDoMessagesCopy.ROW_APP_ICON.text,
                        style: TextStyle(
                          fontSize: scope.sp(14),
                          fontWeight: FontWeight.w600,
                          color: palette.inkPrimary,
                        ),
                      ),
                      trailing: Icon(
                        LinDoIcons.ChevronRight,
                        size: 18,
                        color: palette.inkTertiary,
                      ),
                      onTap: () => showAppIconSelectionPage(
                        context,
                        store: store,
                        onShowToast: onShowToast,
                      ),
                    ),
                    const SizedBox(height: 12),

                    // Clear Local Cache
                    ListTile(
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(14),
                        side: BorderSide(color: palette.hairline),
                      ),
                      tileColor: palette.subtleSurface,
                      leading: Icon(
                        LinDoIcons.Trash,
                        size: 19,
                        color: palette.inkSecondary,
                      ),
                      title: Text(
                        LinDoMessagesCopy.ROW_CLEAR_LOCAL_CACHE.text,
                        style: TextStyle(
                          fontSize: scope.sp(14),
                          fontWeight: FontWeight.w600,
                          color: palette.inkPrimary,
                        ),
                      ),
                      trailing: Icon(
                        LinDoIcons.ChevronRight,
                        size: 18,
                        color: palette.inkTertiary,
                      ),
                      onTap: () => showClearCacheOptionsDialog(
                        context,
                        store: store,
                        onShowToast: onShowToast,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          );
        },
      ),
    );
  }
}

class _SegmentChoiceButton extends StatelessWidget {
  final String label;
  final bool selected;
  final VoidCallback onTap;

  const _SegmentChoiceButton({
    required this.label,
    required this.selected,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    final scope = LinDoThemeScope.of(context);
    return InkWell(
      borderRadius: BorderRadius.circular(10),
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: 8),
        alignment: Alignment.center,
        decoration: BoxDecoration(
          color: selected ? palette.activeChipBg : palette.canvas,
          borderRadius: BorderRadius.circular(10),
          border: Border.all(
            color: selected ? palette.logoDoColor : palette.hairline,
          ),
        ),
        child: Text(
          label,
          style: TextStyle(
            fontSize: scope.sp(12.5),
            fontWeight: selected ? FontWeight.w700 : FontWeight.w500,
            color: selected ? palette.activeChipText : palette.inkSecondary,
          ),
        ),
      ),
    );
  }
}

// ============================================================================
// 11. AboutSheet (with 5-Tap Version Debug Mode Unlock!)
// ============================================================================

Future<void> showAboutSheet(
  BuildContext context, {
  required LinuxDoRepository repository,
  required LinDoLocalStore store,
  required void Function(int topicId, {int? postNumber, String? title})
      onOpenTopic,
  required Future<void> Function() onStartSiteVerify,
  ValueChanged<String>? onShowToast,
}) {
  return _presentResponsiveSheet<void>(
    context,
    maxWidth: 660,
    child: AboutSheet(
      repository: repository,
      store: store,
      onOpenTopic: onOpenTopic,
      onStartSiteVerify: onStartSiteVerify,
      onShowToast: onShowToast,
    ),
  );
}

class AboutSheet extends StatefulWidget {
  final LinuxDoRepository repository;
  final LinDoLocalStore store;
  final void Function(int topicId, {int? postNumber, String? title})
      onOpenTopic;
  final Future<void> Function() onStartSiteVerify;
  final ValueChanged<String>? onShowToast;

  const AboutSheet({
    super.key,
    required this.repository,
    required this.store,
    required this.onOpenTopic,
    required this.onStartSiteVerify,
    this.onShowToast,
  });

  @override
  State<AboutSheet> createState() => _AboutSheetState();
}

class _AboutSheetState extends State<AboutSheet> {
  SiteAboutStats? _stats;
  AboutTeamTab _teamTab = AboutTeamTab.ADMINS;
  bool _loading = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  void _toast(String message) {
    if (widget.onShowToast != null) {
      widget.onShowToast!(message);
      return;
    }
    if (!mounted) return;
    ScaffoldMessenger.maybeOf(context)
      ?..hideCurrentSnackBar()
      ..showSnackBar(
        SnackBar(
          content: Text(message),
          behavior: SnackBarBehavior.floating,
          duration: const Duration(seconds: 2),
        ),
      );
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final res = await widget.repository.fetchSiteAbout();
      if (!mounted) return;
      setState(() {
        _stats = res;
        _loading = false;
      });
    } catch (err) {
      if (!mounted) return;
      setState(() {
        _error = err.toString();
        _loading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    final scope = LinDoThemeScope.of(context);
    final versionText = 'v$kLinDoAppVersionName ($kLinDoAppBuildNumber)';

    return Material(
      color: palette.canvas,
      child: ListenableBuilder(
        listenable: widget.store,
        builder: (context, _) {
          final debugUnlocked = widget.store.preferences.debugModeUnlocked;
          return Column(
            children: [
              _SheetHeaderBar(
                icon: LinDoIcons.InfoCircle,
                title: LinDoMessagesCopy.ABOUT_SHEET_TITLE.text,
                onRefresh: _loading ? null : _load,
              ),
              Divider(height: 1, color: palette.hairline),
              Expanded(
                child: ListView(
                  padding: const EdgeInsets.fromLTRB(18, 18, 18, 28),
                  children: [
                    // App Hero & 5-Tap Version Row
                    Container(
                      padding: const EdgeInsets.all(20),
                      decoration: BoxDecoration(
                        color: palette.groupedCanvas,
                        borderRadius: BorderRadius.circular(20),
                        border: Border.all(color: palette.hairline),
                      ),
                      child: Column(
                        children: [
                          LinDoGlassAppIcon(
                            icon: widget.store.preferences.appIcon,
                            size: 64,
                          ),
                          const SizedBox(height: 12),
                          const LinDoBrandWordmark(
                            fontSize: 21,
                            showMascotBadge: false,
                          ),
                          const SizedBox(height: 6),
                          Text(
                            LinDoMessagesCopy.ABOUT_HEADLINE.text,
                            textAlign: TextAlign.center,
                            style: TextStyle(
                              fontSize: scope.sp(14),
                              fontWeight: FontWeight.w700,
                              color: palette.inkPrimary,
                            ),
                          ),
                          const SizedBox(height: 4),
                          Text(
                            LinDoCommonCopy.ABOUT_MOTTO_FULL.text,
                            textAlign: TextAlign.center,
                            style: TextStyle(
                              fontSize: scope.sp(12.5),
                              color: palette.inkSecondary,
                            ),
                          ),
                          const SizedBox(height: 14),
                          DebugVersionUnlockTrigger(
                            store: widget.store,
                            onShowToast: _toast,
                            child: Container(
                              padding: const EdgeInsets.symmetric(
                                horizontal: 14,
                                vertical: 6,
                              ),
                              decoration: BoxDecoration(
                                color: palette.tonalPill,
                                borderRadius: BorderRadius.circular(999),
                              ),
                              child: Text(
                                '${LinDoCommonCopy.APP_NAME.text} $versionText',
                                style: TextStyle(
                                  fontSize: scope.sp(12.5),
                                  fontWeight: FontWeight.w600,
                                  color: palette.inkSecondary,
                                ),
                              ),
                            ),
                          ),
                          if (debugUnlocked) ...[
                            const SizedBox(height: 12),
                            OutlinedButton.icon(
                              onPressed: () => showDebugDiagnosticsSheet(
                                context,
                                store: widget.store,
                                repository: widget.repository,
                                onShowToast: _toast,
                              ),
                              icon: Icon(
                                LinDoIcons.Bug,
                                size: 16,
                                color: palette.logoDoColor,
                              ),
                              label: const Text('调试与诊断日志'),
                            ),
                          ],
                        ],
                      ),
                    ),
                    const SizedBox(height: 18),

                    if (_loading)
                      Padding(
                        padding: const EdgeInsets.symmetric(vertical: 24),
                        child: LinDoStatePanel(
                          mood: MascotMood.connecting,
                          title: LinDoCommonCopy.LOADING.text,
                          compact: true,
                        ),
                      )
                    else if (_error != null)
                      (_error.isSiteVerificationError
                          ? LinDoSiteVerificationCard(
                              onStartInAppVerify: () async {
                                await widget.onStartSiteVerify();
                                if (mounted) _load();
                              },
                              onRetry: _load,
                            )
                          : LinDoStatePanel(
                              mood: MascotMood.surprised,
                              title: LinDoMessagesCopy.ABOUT_LOAD_FAILED.text,
                              subtitle: _error,
                              actionLabel: LinDoCommonCopy.RETRY.text,
                              onAction: _load,
                              compact: true,
                            ))
                    else if (_stats != null) ...[
                      // 6-item Site Stats Grid
                      GridView.count(
                        shrinkWrap: true,
                        physics: const NeverScrollableScrollPhysics(),
                        crossAxisCount: 3,
                        mainAxisSpacing: 10,
                        crossAxisSpacing: 10,
                        childAspectRatio: 1.55,
                        children: [
                          _AboutStatTile(
                            label: LinDoMessagesCopy.STAT_TOTAL_TOPICS.text,
                            value: formatCompactCount(_stats!.totalTopics),
                          ),
                          _AboutStatTile(
                            label: LinDoMessagesCopy.STAT_TOTAL_POSTS.text,
                            value: formatCompactCount(_stats!.totalPosts),
                          ),
                          _AboutStatTile(
                            label: LinDoMessagesCopy.STAT_TOTAL_USERS.text,
                            value: formatCompactCount(_stats!.totalUsers),
                          ),
                          _AboutStatTile(
                            label: LinDoMessagesCopy.STAT_ACTIVE_7D.text,
                            value: formatCompactCount(_stats!.active7d),
                          ),
                          _AboutStatTile(
                            label: LinDoMessagesCopy.STAT_ACTIVE_30D.text,
                            value: formatCompactCount(_stats!.active30d),
                          ),
                          _AboutStatTile(
                            label: LinDoMessagesCopy.STAT_TOTAL_LIKES.text,
                            value: formatCompactCount(_stats!.totalLikes),
                          ),
                        ],
                      ),
                      const SizedBox(height: 20),
                      Row(
                        children: [
                          _SegmentTabChip(
                            label: LinDoMessagesCopy.ABOUT_TAB_ADMINS
                                .format(_stats!.admins.length),
                            selected: _teamTab == AboutTeamTab.ADMINS,
                            onTap: () =>
                                setState(() => _teamTab = AboutTeamTab.ADMINS),
                          ),
                          const SizedBox(width: 8),
                          _SegmentTabChip(
                            label: LinDoMessagesCopy.ABOUT_TAB_MODERATORS
                                .format(_stats!.moderators.length),
                            selected: _teamTab == AboutTeamTab.MODERATORS,
                            onTap: () => setState(
                              () => _teamTab = AboutTeamTab.MODERATORS,
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 12),
                      ...(_teamTab == AboutTeamTab.ADMINS
                              ? _stats!.admins
                              : _stats!.moderators)
                          .map(
                        (member) => Padding(
                          padding: const EdgeInsets.only(bottom: 8),
                          child: ListTile(
                            tileColor: palette.subtleSurface,
                            shape: RoundedRectangleBorder(
                              borderRadius: BorderRadius.circular(14),
                              side: BorderSide(color: palette.hairline),
                            ),
                            leading: SmartAvatar(
                              avatarUrl: member.avatarTemplate,
                              fallbackLabel: member.username,
                              size: 38,
                              isAdmin: member.admin,
                              isModerator: member.moderator,
                              flair: member.flair,
                            ),
                            title: Text(
                              member.displayName,
                              style: TextStyle(
                                fontSize: scope.sp(14),
                                fontWeight: FontWeight.w700,
                                color: palette.inkPrimary,
                              ),
                            ),
                            subtitle: Text(
                              '@${member.username}',
                              style: TextStyle(
                                fontSize: scope.sp(12),
                                color: palette.inkSecondary,
                              ),
                            ),
                            trailing: Icon(
                              LinDoIcons.ChevronRight,
                              size: 18,
                              color: palette.inkTertiary,
                            ),
                            onTap: () => showPublicUserCardSheet(
                              context,
                              username: member.username,
                              repository: widget.repository,
                              store: widget.store,
                              onOpenTopic: widget.onOpenTopic,
                              onStartSiteVerify: widget.onStartSiteVerify,
                              onShowToast: widget.onShowToast,
                            ),
                          ),
                        ),
                      ),
                    ],
                  ],
                ),
              ),
            ],
          );
        },
      ),
    );
  }
}

// ============================================================================
// Shared Sheet Building Blocks
// ============================================================================

class _SheetHeaderBar extends StatelessWidget {
  final IconData icon;
  final String title;
  final VoidCallback? onRefresh;

  const _SheetHeaderBar({
    required this.icon,
    required this.title,
    this.onRefresh,
  });

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    final scope = LinDoThemeScope.of(context);
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 14, 12, 12),
      child: Row(
        children: [
          Icon(icon, size: 21, color: palette.logoDoColor),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              title,
              style: TextStyle(
                fontSize: scope.sp(17.5),
                fontWeight: FontWeight.w700,
                color: palette.inkPrimary,
              ),
            ),
          ),
          if (onRefresh != null)
            IconButton(
              tooltip: LinDoCommonCopy.REFRESH.text,
              onPressed: onRefresh,
              icon: Icon(
                LinDoIcons.Refresh,
                size: 19,
                color: palette.inkSecondary,
              ),
            ),
          IconButton(
            tooltip: LinDoCommonCopy.CLOSE.text,
            onPressed: () => Navigator.of(context).maybePop(),
            icon: Icon(
              LinDoIcons.Close,
              size: 20,
              color: palette.inkSecondary,
            ),
          ),
        ],
      ),
    );
  }
}

class _MetaCaption extends StatelessWidget {
  final String text;
  const _MetaCaption(this.text);

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    final scope = LinDoThemeScope.of(context);
    return Text(
      text,
      style: TextStyle(
        fontSize: scope.sp(12),
        color: palette.inkSecondary,
      ),
    );
  }
}

class _StatCell extends StatelessWidget {
  final String label;
  final String value;

  const _StatCell({
    required this.label,
    required this.value,
  });

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    final scope = LinDoThemeScope.of(context);
    return Column(
      children: [
        Text(
          value,
          style: TextStyle(
            fontSize: scope.sp(16),
            fontWeight: FontWeight.w800,
            color: palette.inkPrimary,
          ),
        ),
        const SizedBox(height: 2),
        Text(
          label,
          style: TextStyle(
            fontSize: scope.sp(11.5),
            color: palette.inkTertiary,
          ),
        ),
      ],
    );
  }
}

class _AboutStatTile extends StatelessWidget {
  final String label;
  final String value;

  const _AboutStatTile({
    required this.label,
    required this.value,
  });

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    final scope = LinDoThemeScope.of(context);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 10),
      decoration: BoxDecoration(
        color: palette.subtleSurface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: palette.hairline),
      ),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Text(
            value,
            style: TextStyle(
              fontSize: scope.sp(16),
              fontWeight: FontWeight.w800,
              color: palette.inkPrimary,
            ),
          ),
          const SizedBox(height: 3),
          Text(
            label,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(
              fontSize: scope.sp(11.5),
              color: palette.inkSecondary,
            ),
          ),
        ],
      ),
    );
  }
}

class _SegmentTabChip extends StatelessWidget {
  final String label;
  final bool selected;
  final VoidCallback onTap;

  const _SegmentTabChip({
    required this.label,
    required this.selected,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    final scope = LinDoThemeScope.of(context);
    return InkWell(
      borderRadius: BorderRadius.circular(999),
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 6),
        decoration: BoxDecoration(
          color: selected ? palette.activeChipBg : palette.subtleSurface,
          borderRadius: BorderRadius.circular(999),
          border: Border.all(
            color: selected
                ? palette.activeChipText.withValues(alpha: 0.35)
                : palette.hairline,
          ),
        ),
        child: Text(
          label,
          style: TextStyle(
            fontSize: scope.sp(12.5),
            fontWeight: selected ? FontWeight.w700 : FontWeight.w500,
            color: selected ? palette.activeChipText : palette.inkSecondary,
          ),
        ),
      ),
    );
  }
}
