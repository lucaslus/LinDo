import 'dart:async';

import 'package:flutter/material.dart';

import '../../core/copy/lindo_copy.dart';
import '../../core/models/lindo_models.dart';
import '../../core/network/linux_do_repository.dart';
import '../../core/site/linux_do_site_rules.dart';
import '../../core/storage/lindo_local_store.dart';
import '../components/lindo_icons.dart';
import '../components/lindo_mascot.dart';
import '../components/smart_avatar_and_badges.dart';
import '../theme/lindo_theme.dart';

Future<void> showGlobalSearchSheet(
  BuildContext context, {
  required LinuxDoRepository repository,
  required LinDoLocalStore store,
  required void Function(int topicId, {int? postNumber, String? title})
      onOpenTopic,
  required Future<void> Function() onStartSiteVerify,
  VoidCallback? onRequestGuestLogin,
  String initialQuery = '',
}) {
  final size = MediaQuery.sizeOf(context);
  final isWide = shouldUseWideSplitLayout(size.width, size.height);
  if (isWide) {
    return showDialog<void>(
      context: context,
      barrierColor: Colors.black.withValues(alpha: 0.45),
      builder: (ctx) => Dialog(
        backgroundColor: Colors.transparent,
        insetPadding: const EdgeInsets.symmetric(horizontal: 36, vertical: 28),
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 740, maxHeight: 740),
          child: ClipRRect(
            borderRadius: BorderRadius.circular(24),
            child: GlobalSearchSheet(
              repository: repository,
              store: store,
              onOpenTopic: onOpenTopic,
              onStartSiteVerify: onStartSiteVerify,
              onRequestGuestLogin: onRequestGuestLogin,
              initialQuery: initialQuery,
            ),
          ),
        ),
      ),
    );
  }
  return showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    useSafeArea: true,
    backgroundColor: Colors.transparent,
    builder: (ctx) => FractionallySizedBox(
      heightFactor: 0.94,
      child: ClipRRect(
        borderRadius: const BorderRadius.vertical(top: Radius.circular(24)),
        child: GlobalSearchSheet(
          repository: repository,
          store: store,
          onOpenTopic: onOpenTopic,
          onStartSiteVerify: onStartSiteVerify,
          onRequestGuestLogin: onRequestGuestLogin,
          initialQuery: initialQuery,
        ),
      ),
    ),
  );
}

class GlobalSearchSheet extends StatefulWidget {
  final LinuxDoRepository repository;
  final LinDoLocalStore store;
  final void Function(int topicId, {int? postNumber, String? title})
      onOpenTopic;
  final Future<void> Function() onStartSiteVerify;
  final VoidCallback? onRequestGuestLogin;
  final String initialQuery;

  const GlobalSearchSheet({
    super.key,
    required this.repository,
    required this.store,
    required this.onOpenTopic,
    required this.onStartSiteVerify,
    this.onRequestGuestLogin,
    this.initialQuery = '',
  });

  @override
  State<GlobalSearchSheet> createState() => _GlobalSearchSheetState();
}

class _GlobalSearchSheetState extends State<GlobalSearchSheet> {
  static const List<String> _hotKeywords = <String>[
    'Claude',
    'DeepSeek',
    'MCP',
    '人工智能',
    'Cursor',
    '开源项目',
    'Docker',
    '快问快答',
  ];

  static final List<String> _sessionHistory = <String>[];

  late final TextEditingController _controller;
  final ScrollController _scrollController = ScrollController();
  Timer? _debounce;

  SearchSortOrder _sortOrder = SearchSortOrder.RELEVANCE;
  List<CommunitySearchResult> _results = const <CommunitySearchResult>[];
  bool _loading = false;
  bool _loadingMore = false;
  bool _hasMore = false;
  int _totalEstimate = 0;
  int _page = 1;
  String? _error;

  @override
  void initState() {
    super.initState();
    _controller = TextEditingController(text: widget.initialQuery);
    _scrollController.addListener(_onScroll);
    if (widget.initialQuery.trim().isNotEmpty) {
      _executeSearch(resetPage: true);
    }
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _controller.dispose();
    _scrollController.dispose();
    super.dispose();
  }

  void _onScroll() {
    if (!_hasMore || _loading || _loadingMore) return;
    final pos = _scrollController.position;
    if (pos.pixels >= pos.maxScrollExtent - 240) {
      _executeSearch(resetPage: false);
    }
  }

  void _onQueryChanged(String value) {
    _debounce?.cancel();
    if (value.trim().isEmpty) {
      setState(() {
        _results = const <CommunitySearchResult>[];
        _loading = false;
        _loadingMore = false;
        _hasMore = false;
        _error = null;
      });
      return;
    }
    _debounce = Timer(const Duration(milliseconds: 340), () {
      _executeSearch(resetPage: true);
    });
  }

  void _recordSearchHistory(String query) {
    final clean = query.trim();
    if (clean.isEmpty) return;
    _sessionHistory.remove(clean);
    _sessionHistory.insert(0, clean);
    if (_sessionHistory.length > 12) {
      _sessionHistory.removeRange(12, _sessionHistory.length);
    }
  }

  Future<void> _executeSearch({required bool resetPage}) async {
    final query = _controller.text.trim();
    if (query.isEmpty) return;
    final targetPage = resetPage ? 1 : (_page + 1);
    _recordSearchHistory(query);

    setState(() {
      if (resetPage) {
        _loading = true;
        _error = null;
      } else {
        _loadingMore = true;
      }
    });

    try {
      final pageRes = await widget.repository.searchTopicsAndPosts(
        query,
        sort: _sortOrder,
        page: targetPage,
      );
      if (!mounted) return;
      setState(() {
        _page = targetPage;
        _results = resetPage
            ? pageRes.items
            : <CommunitySearchResult>[..._results, ...pageRes.items];
        _hasMore = pageRes.hasMore;
        _totalEstimate = pageRes.totalEstimate > 0
            ? pageRes.totalEstimate
            : _results.length;
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

  void _selectQuickKeyword(String kw) {
    _controller.text = kw;
    _controller.selection = TextSelection.collapsed(offset: kw.length);
    _executeSearch(resetPage: true);
  }

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    final scope = LinDoThemeScope.of(context);
    final activeQuery = _controller.text.trim();

    return Material(
      color: palette.canvas,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // Search Bar Header
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 14, 12, 10),
            child: Row(
              children: [
                Expanded(
                  child: Container(
                    height: 44,
                    decoration: BoxDecoration(
                      color: palette.tonalPill,
                      borderRadius: BorderRadius.circular(14),
                    ),
                    child: TextField(
                      controller: _controller,
                      autofocus: true,
                      textInputAction: TextInputAction.search,
                      onChanged: _onQueryChanged,
                      onSubmitted: (_) => _executeSearch(resetPage: true),
                      style: TextStyle(
                        fontSize: scope.sp(14.5),
                        color: palette.inkPrimary,
                      ),
                      decoration: InputDecoration(
                        border: InputBorder.none,
                        contentPadding:
                            const EdgeInsets.symmetric(vertical: 11),
                        prefixIcon: Icon(
                          LinDoIcons.Search,
                          size: 18,
                          color: palette.inkSecondary,
                        ),
                        suffixIcon: activeQuery.isEmpty
                            ? null
                            : IconButton(
                                tooltip: LinDoFeedCopy.CLEAR_SEARCH_DESC.text,
                                onPressed: () {
                                  _controller.clear();
                                  _onQueryChanged('');
                                },
                                icon: Icon(
                                  LinDoIcons.Close,
                                  size: 17,
                                  color: palette.inkTertiary,
                                ),
                              ),
                        hintText: LinDoFeedCopy.SEARCH_PLACEHOLDER.text,
                        hintStyle: TextStyle(
                          fontSize: scope.sp(14),
                          color: palette.inkTertiary,
                        ),
                      ),
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                TextButton(
                  onPressed: () => Navigator.of(context).maybePop(),
                  child: Text(
                    LinDoCommonCopy.CANCEL.text,
                    style: TextStyle(
                      fontSize: scope.sp(14),
                      fontWeight: FontWeight.w600,
                      color: palette.inkSecondary,
                    ),
                  ),
                ),
              ],
            ),
          ),

          // Sort tabs when active query
          if (activeQuery.isNotEmpty) ...[
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
              child: Row(
                children: [
                  for (final order in SearchSortOrder.values) ...[
                    Padding(
                      padding: const EdgeInsets.only(right: 8),
                      child: InkWell(
                        borderRadius: BorderRadius.circular(999),
                        onTap: () {
                          if (_sortOrder == order) return;
                          setState(() => _sortOrder = order);
                          _executeSearch(resetPage: true);
                        },
                        child: Container(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 12,
                            vertical: 5,
                          ),
                          decoration: BoxDecoration(
                            color: _sortOrder == order
                                ? palette.activeChipBg
                                : palette.subtleSurface,
                            borderRadius: BorderRadius.circular(999),
                            border: Border.all(
                              color: _sortOrder == order
                                  ? palette.activeChipText
                                      .withValues(alpha: 0.35)
                                  : palette.hairline,
                            ),
                          ),
                          child: Text(
                            order.labelCopy.text,
                            style: TextStyle(
                              fontSize: scope.sp(12),
                              fontWeight: _sortOrder == order
                                  ? FontWeight.w700
                                  : FontWeight.w500,
                              color: _sortOrder == order
                                  ? palette.activeChipText
                                  : palette.inkSecondary,
                            ),
                          ),
                        ),
                      ),
                    ),
                  ],
                ],
              ),
            ),
            Divider(height: 1, color: palette.hairline),
          ] else
            Divider(height: 1, color: palette.hairline),

          Expanded(
            child: activeQuery.isEmpty
                ? _buildDiscoverySuggestions(context, palette, scope)
                : _buildSearchResults(context, palette, scope, activeQuery),
          ),
        ],
      ),
    );
  }

  Widget _buildDiscoverySuggestions(
    BuildContext context,
    LinDoThemePalette palette,
    LinDoThemeScope scope,
  ) {
    return ListView(
      padding: const EdgeInsets.fromLTRB(18, 16, 18, 28),
      children: [
        if (_sessionHistory.isNotEmpty) ...[
          Row(
            children: [
              Icon(LinDoIcons.History, size: 16, color: palette.inkSecondary),
              const SizedBox(width: 6),
              Expanded(
                child: Text(
                  LinDoMessagesCopy.READ_HISTORY_TITLE.text,
                  style: TextStyle(
                    fontSize: scope.sp(13),
                    fontWeight: FontWeight.w700,
                    color: palette.inkSecondary,
                  ),
                ),
              ),
              TextButton(
                onPressed: () => setState(_sessionHistory.clear),
                child: Text(
                  LinDoCommonCopy.CLEAR.text,
                  style: TextStyle(
                    fontSize: scope.sp(12),
                    color: palette.inkTertiary,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 6),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: _sessionHistory
                .map(
                  (kw) => ActionChip(
                    backgroundColor: palette.subtleSurface,
                    side: BorderSide(color: palette.hairline),
                    label: Text(
                      kw,
                      style: TextStyle(
                        fontSize: scope.sp(12.5),
                        color: palette.inkPrimary,
                      ),
                    ),
                    onPressed: () => _selectQuickKeyword(kw),
                  ),
                )
                .toList(),
          ),
          const SizedBox(height: 22),
        ],
        Row(
          children: [
            Icon(LinDoIcons.Flame, size: 16, color: palette.logoDoColor),
            const SizedBox(width: 6),
            Text(
              LinDoFeedCopy.HOT_SEARCH_KEYWORDS_TITLE.text,
              style: TextStyle(
                fontSize: scope.sp(13),
                fontWeight: FontWeight.w700,
                color: palette.inkSecondary,
              ),
            ),
          ],
        ),
        const SizedBox(height: 10),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: _hotKeywords
              .map(
                (kw) => ActionChip(
                  backgroundColor: palette.tonalPill,
                  side: BorderSide.none,
                  label: Text(
                    kw,
                    style: TextStyle(
                      fontSize: scope.sp(13),
                      fontWeight: FontWeight.w500,
                      color: palette.inkPrimary,
                    ),
                  ),
                  onPressed: () => _selectQuickKeyword(kw),
                ),
              )
              .toList(),
        ),
      ],
    );
  }

  Widget _buildSearchResults(
    BuildContext context,
    LinDoThemePalette palette,
    LinDoThemeScope scope,
    String query,
  ) {
    if (_loading) {
      return LinDoStatePanel(
        mood: MascotMood.connecting,
        title: LinDoCommonCopy.SEARCHING.text,
      );
    }

    if (_error != null) {
      final errKind = UiErrorKind.classify(
        _error,
        isAuthorized: widget.repository.isAuthorized,
      );
      if (errKind == UiErrorKind.SITE_VERIFICATION) {
        return LinDoSiteVerificationCard(
          onStartInAppVerify: () async {
            await widget.onStartSiteVerify();
            if (mounted) _executeSearch(resetPage: true);
          },
          onRetry: () => _executeSearch(resetPage: true),
        );
      }
      if (errKind == UiErrorKind.GUEST_LOGIN_REQUIRED) {
        return LinDoStatePanel(
          mood: MascotMood.wink,
          title: GuestGateScenario.GLOBAL_SEARCH.titleCopy.text,
          subtitle: GuestGateScenario.GLOBAL_SEARCH.subtitleCopy.text,
          actionLabel: LinDoAuthCopy.LOGIN_WITH_LINUX_DO.text,
          onAction: () {
            Navigator.of(context).pop();
            widget.onRequestGuestLogin?.call();
          },
        );
      }
      return LinDoStatePanel(
        mood: MascotMood.surprised,
        title: LinDoFeedCopy.SEARCH_FAILED_RETRY.text,
        subtitle: _error,
        actionLabel: LinDoCommonCopy.RETRY.text,
        onAction: () => _executeSearch(resetPage: true),
      );
    }

    if (_results.isEmpty) {
      return LinDoStatePanel(
        mood: MascotMood.sleepy,
        title: LinDoFeedCopy.SEARCH_NO_RESULTS.text,
      );
    }

    return ListView.builder(
      controller: _scrollController,
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 28),
      itemCount: _results.length + 1,
      itemBuilder: (context, index) {
        if (index == _results.length) {
          if (_loadingMore) {
            return const Padding(
              padding: EdgeInsets.symmetric(vertical: 16),
              child: Center(
                child: SizedBox(
                  width: 20,
                  height: 20,
                  child: CircularProgressIndicator(strokeWidth: 2),
                ),
              ),
            );
          }
          if (!_hasMore) {
            return Padding(
              padding: const EdgeInsets.symmetric(vertical: 16),
              child: Center(
                child: Text(
                  LinDoFeedCopy.SEARCH_END_TOTAL_COUNT.format(_totalEstimate),
                  style: TextStyle(
                    fontSize: scope.sp(12),
                    color: palette.inkTertiary,
                  ),
                ),
              ),
            );
          }
          return const SizedBox(height: 16);
        }

        final item = _results[index];
        return RepaintBoundary(
          child: _SearchResultCard(
            item: item,
            query: query,
            onTap: () {
              Navigator.of(context).pop();
              widget.onOpenTopic(
                item.topicId,
                postNumber: item.postNumber > 1 ? item.postNumber : null,
                title: item.title,
              );
            },
          ),
        );
      },
    );
  }
}

class _SearchResultCard extends StatelessWidget {
  final CommunitySearchResult item;
  final String query;
  final VoidCallback onTap;

  const _SearchResultCard({
    required this.item,
    required this.query,
    required this.onTap,
  });

  List<TextSpan> _highlightSpans(
    String text,
    String keyword,
    TextStyle normal,
    TextStyle highlight,
  ) {
    final cleanKeyword = keyword.trim();
    if (cleanKeyword.isEmpty || text.isEmpty) {
      return <TextSpan>[TextSpan(text: text, style: normal)];
    }
    final lowerText = text.toLowerCase();
    final lowerKey = cleanKeyword.toLowerCase();
    final spans = <TextSpan>[];
    var cursor = 0;
    while (cursor < text.length) {
      final hit = lowerText.indexOf(lowerKey, cursor);
      if (hit < 0) {
        spans.add(TextSpan(text: text.substring(cursor), style: normal));
        break;
      }
      if (hit > cursor) {
        spans.add(TextSpan(text: text.substring(cursor, hit), style: normal));
      }
      spans.add(
        TextSpan(
          text: text.substring(hit, hit + cleanKeyword.length),
          style: highlight,
        ),
      );
      cursor = hit + cleanKeyword.length;
    }
    return spans;
  }

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    final scope = LinDoThemeScope.of(context);
    final cleanBlurb = decodeDiscourseHtmlSnippet(item.blurb);

    final baseTitleStyle = TextStyle(
      fontSize: scope.sp(15),
      fontWeight: FontWeight.w700,
      height: 1.38,
      color: palette.inkPrimary,
    );
    final hiTitleStyle = baseTitleStyle.copyWith(
      color: palette.logoDoColor,
      backgroundColor: palette.activeChipBg.withValues(alpha: 0.7),
    );
    final baseBlurbStyle = TextStyle(
      fontSize: scope.sp(13),
      height: 1.48,
      color: palette.inkSecondary,
    );
    final hiBlurbStyle = baseBlurbStyle.copyWith(
      color: palette.activeChipText,
      fontWeight: FontWeight.w600,
    );

    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Material(
        color: palette.subtleSurface,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(16),
          side: BorderSide(color: palette.hairline),
        ),
        child: InkWell(
          borderRadius: BorderRadius.circular(16),
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.all(14),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    SmartAvatar(
                      avatarUrl: item.avatarTemplate,
                      fallbackLabel: item.username,
                      size: 22,
                    ),
                    const SizedBox(width: 7),
                    Flexible(
                      child: Text(
                        item.username.isEmpty ? 'linux.do' : '@${item.username}',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          fontSize: scope.sp(12),
                          fontWeight: FontWeight.w600,
                          color: palette.inkSecondary,
                        ),
                      ),
                    ),
                    if (item.createdAtText.isNotEmpty) ...[
                      const SizedBox(width: 6),
                      Text(
                        '· ${formatRelativeTime(item.createdAtText)}',
                        style: TextStyle(
                          fontSize: scope.sp(11.5),
                          color: palette.inkTertiary,
                        ),
                      ),
                    ],
                    const Spacer(),
                    if (item.categoryName.isNotEmpty)
                      CategoryMorandiChip(categoryName: item.categoryName),
                  ],
                ),
                const SizedBox(height: 8),
                Text.rich(
                  TextSpan(
                    children: _highlightSpans(
                      item.title,
                      query,
                      baseTitleStyle,
                      hiTitleStyle,
                    ),
                  ),
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                ),
                if (cleanBlurb.isNotEmpty) ...[
                  const SizedBox(height: 6),
                  Text.rich(
                    TextSpan(
                      children: _highlightSpans(
                        cleanBlurb,
                        query,
                        baseBlurbStyle,
                        hiBlurbStyle,
                      ),
                    ),
                    maxLines: 3,
                    overflow: TextOverflow.ellipsis,
                  ),
                ],
                const SizedBox(height: 8),
                Row(
                  children: [
                    if (item.postNumber > 1) ...[
                      Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 7,
                          vertical: 2,
                        ),
                        decoration: BoxDecoration(
                          color: palette.tonalPill,
                          borderRadius: BorderRadius.circular(6),
                        ),
                        child: Text(
                          '#${item.postNumber}',
                          style: TextStyle(
                            fontSize: scope.sp(11),
                            fontWeight: FontWeight.w600,
                            color: palette.inkSecondary,
                          ),
                        ),
                      ),
                      const SizedBox(width: 10),
                    ],
                    Icon(
                      LinDoIcons.Heart,
                      size: 13.5,
                      color: palette.inkTertiary,
                    ),
                    const SizedBox(width: 3),
                    Text(
                      formatCompactCount(item.likeCount),
                      style: TextStyle(
                        fontSize: scope.sp(11.5),
                        color: palette.inkTertiary,
                      ),
                    ),
                    const SizedBox(width: 12),
                    Icon(
                      LinDoIcons.MessageCircle,
                      size: 13.5,
                      color: palette.inkTertiary,
                    ),
                    const SizedBox(width: 3),
                    Text(
                      LinDoFeedCopy.SEARCH_DISCUSSIONS_COUNT
                          .format(formatCompactCount(item.postsCount)),
                      style: TextStyle(
                        fontSize: scope.sp(11.5),
                        color: palette.inkTertiary,
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
