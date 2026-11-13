import 'package:flutter/material.dart';

import '../../core/copy/lindo_copy.dart';
import '../../core/models/lindo_models.dart';
import '../../core/network/linux_do_repository.dart';
import '../../core/site/linux_do_site_rules.dart';
import '../../core/storage/lindo_local_store.dart';
import '../components/lindo_brand_widgets.dart';
import '../components/lindo_icons.dart';
import '../components/lindo_mascot.dart';
import '../components/smart_avatar_and_badges.dart';
import '../sheets/global_search_sheet.dart';
import '../sheets/user_collections_sheets.dart';
import '../theme/lindo_theme.dart';

class HomeFeedPane extends StatefulWidget {
  final LinuxDoRepository repository;
  final LinDoLocalStore store;
  final int? selectedTopicId;
  final int? filterCategoryId;
  final String? filterCategorySlug;
  final String? filterCategoryName;
  final String? filterTag;
  final ValueChanged<CategoryInfo?> onCategoryChanged;
  final ValueChanged<String?> onTagChanged;
  final void Function(int topicId, {int? postNumber, String? title})
      onOpenTopic;
  final Future<void> Function() onStartWebLogin;
  final Future<void> Function() onStartSiteVerify;
  final ValueChanged<String>? onShowToast;
  final bool isCompactDesktopColumn;

  const HomeFeedPane({
    super.key,
    required this.repository,
    required this.store,
    this.selectedTopicId,
    this.filterCategoryId,
    this.filterCategorySlug,
    this.filterCategoryName,
    this.filterTag,
    required this.onCategoryChanged,
    required this.onTagChanged,
    required this.onOpenTopic,
    required this.onStartWebLogin,
    required this.onStartSiteVerify,
    this.onShowToast,
    this.isCompactDesktopColumn = false,
  });

  @override
  State<HomeFeedPane> createState() => _HomeFeedPaneState();
}

class _HomeFeedPaneState extends State<HomeFeedPane> {
  final ScrollController _scrollController = ScrollController();

  FeedSortFilter _sort = FeedSortFilter.LATEST;
  TopPeriodFilter _topPeriod = TopPeriodFilter.WEEKLY;

  List<TopicSummary> _topics = const <TopicSummary>[];
  List<TopicSummary> _pinnedTopics = const <TopicSummary>[];
  bool _pinnedExpanded = false;

  bool _loading = true;
  bool _loadingMore = false;
  int? _nextPage;
  String? _moreTopicsUrl;
  String? _error;
  int _loadGeneration = 0;

  @override
  void initState() {
    super.initState();
    _scrollController.addListener(_onScroll);
    widget.repository.addListener(_onRepoChanged);
    _loadFeed(reset: true);
  }

  @override
  void didUpdateWidget(covariant HomeFeedPane oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.repository != widget.repository) {
      oldWidget.repository.removeListener(_onRepoChanged);
      widget.repository.addListener(_onRepoChanged);
    }
    if (oldWidget.filterCategoryId != widget.filterCategoryId ||
        oldWidget.filterTag != widget.filterTag) {
      _loadFeed(reset: true);
    }
  }

  @override
  void dispose() {
    widget.repository.removeListener(_onRepoChanged);
    _scrollController.dispose();
    super.dispose();
  }

  void _onRepoChanged() {
    if (!mounted) return;
    // If user logged out while on an auth-only sort tab, fall back to LATEST.
    if (!widget.repository.isAuthorized && _sort.requiresAuth) {
      setState(() => _sort = FeedSortFilter.LATEST);
      _loadFeed(reset: true);
      return;
    }
    setState(() {});
  }

  void _onScroll() {
    if (_loading || _loadingMore) return;
    if (_nextPage == null && (_moreTopicsUrl == null || _moreTopicsUrl!.isEmpty)) {
      return;
    }
    if (!_scrollController.hasClients) return;
    final pos = _scrollController.position;
    if (pos.pixels >= pos.maxScrollExtent - 320) {
      _loadFeed(reset: false);
    }
  }

  Future<void> _loadFeed({required bool reset}) async {
    final gen = ++_loadGeneration;
    if (reset) {
      setState(() {
        _loading = true;
        _error = null;
      });
    } else {
      setState(() {
        _loadingMore = true;
      });
    }

    try {
      final page = await widget.repository.fetchFeed(
        sort: _sort,
        topPeriod: _topPeriod,
        categoryId: widget.filterCategoryId,
        categorySlug: widget.filterCategorySlug,
        tag: widget.filterTag,
        page: reset ? 0 : (_nextPage ?? 0),
        moreTopicsUrl: reset ? null : _moreTopicsUrl,
      );
      if (!mounted || gen != _loadGeneration) return;

      setState(() {
        if (reset) {
          _pinnedTopics = page.pinnedTopics;
          _topics = page.topics;
        } else {
          final existingIds = <int>{
            for (final p in _pinnedTopics) p.id,
            for (final t in _topics) t.id,
          };
          final appended = page.topics
              .where((t) => !existingIds.contains(t.id))
              .toList(growable: false);
          _topics = <TopicSummary>[..._topics, ...appended];
        }
        _nextPage = page.nextPage;
        _moreTopicsUrl = page.moreTopicsUrl;
        _loading = false;
        _loadingMore = false;
      });
    } catch (err) {
      if (!mounted || gen != _loadGeneration) return;
      setState(() {
        _error = err.toString();
        _loading = false;
        _loadingMore = false;
      });
    }
  }

  Future<void> _selectSort(FeedSortFilter nextSort) async {
    if (nextSort.requiresAuth && !widget.repository.isAuthorized) {
      await showGuestLoginGuideSheet(
        context,
        scenario: nextSort == FeedSortFilter.FOLLOWING
            ? GuestGateScenario.FOLLOW_LIST
            : GuestGateScenario.DEFAULT,
        onStartWebLogin: widget.onStartWebLogin,
      );
      return;
    }
    if (_sort == nextSort) {
      if (_scrollController.hasClients) {
        _scrollController.animateTo(
          0,
          duration: const Duration(milliseconds: 240),
          curve: Curves.easeOutCubic,
        );
      }
      _loadFeed(reset: true);
      return;
    }
    setState(() => _sort = nextSort);
    _loadFeed(reset: true);
  }

  void _openSearch([String initialQuery = '']) {
    showGlobalSearchSheet(
      context,
      repository: widget.repository,
      store: widget.store,
      initialQuery: initialQuery,
      onOpenTopic: widget.onOpenTopic,
      onStartSiteVerify: widget.onStartSiteVerify,
      onRequestGuestLogin: () {
        showGuestLoginGuideSheet(
          context,
          scenario: GuestGateScenario.GLOBAL_SEARCH,
          onStartWebLogin: widget.onStartWebLogin,
        );
      },
    );
  }

  void _openAuthorCard(String username) {
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

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    final scope = LinDoThemeScope.of(context);

    return ListenableBuilder(
      listenable: widget.store,
      builder: (context, _) {
        final prefs = widget.store.preferences;
        final isCompact = prefs.isCompactFeed;
        final isSiteVerify = widget.repository.siteVerificationRequired ||
            _error.isSiteVerificationError;

        final topCategories = widget.repository.categories
            .where((c) => c.parentCategoryId == null)
            .toList(growable: false);

        return ColoredBox(
          color: palette.canvas,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              _buildHeader(context, palette, scope, isCompact),
              _buildSortBar(context, palette, scope),
              if (_sort == FeedSortFilter.TOP)
                _buildTopPeriodBar(context, palette, scope),
              if (topCategories.isNotEmpty ||
                  widget.filterCategoryId != null ||
                  (widget.filterTag != null && widget.filterTag!.isNotEmpty))
                _buildCategoryAndTagStrip(
                  context,
                  palette,
                  scope,
                  topCategories,
                ),
              Divider(height: 1, color: palette.hairline),
              Expanded(
                child: _buildFeedBody(
                  context,
                  palette,
                  scope,
                  isCompact: isCompact,
                  isSiteVerify: isSiteVerify,
                ),
              ),
            ],
          ),
        );
      },
    );
  }

  Widget _buildHeader(
    BuildContext context,
    LinDoThemePalette palette,
    LinDoThemeScope scope,
    bool isCompact,
  ) {
    return Padding(
      padding: EdgeInsets.fromLTRB(
        16,
        widget.isCompactDesktopColumn ? 12 : 10,
        12,
        6,
      ),
      child: Row(
        children: [
          const LinDoBrandWordmark(
            fontSize: 18.5,
            showMascotBadge: false,
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Material(
              color: palette.tonalPill,
              borderRadius: BorderRadius.circular(999),
              child: InkWell(
                borderRadius: BorderRadius.circular(999),
                onTap: _openSearch,
                child: Container(
                  height: 36,
                  padding: const EdgeInsets.symmetric(horizontal: 12),
                  child: Row(
                    children: [
                      Icon(
                        LinDoIcons.Search,
                        size: 15.5,
                        color: palette.inkTertiary,
                      ),
                      const SizedBox(width: 7),
                      Expanded(
                        child: Text(
                          LinDoFeedCopy.SEARCH_PLACEHOLDER.text,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            fontSize: scope.sp(12.5),
                            color: palette.inkTertiary,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
          const SizedBox(width: 4),
          IconButton(
            visualDensity: VisualDensity.compact,
            tooltip: LinDoFeedCopy.TOGGLE_FEED_LAYOUT_DESC.text,
            onPressed: () {
              final cur = widget.store.preferences;
              widget.store.updatePreferences(
                cur.copyWith(isCompactFeed: !cur.isCompactFeed),
              );
            },
            icon: Icon(
              isCompact ? LinDoIcons.LayoutCards : LinDoIcons.LayoutCompact,
              size: 19,
              color: palette.inkSecondary,
            ),
          ),
          IconButton(
            visualDensity: VisualDensity.compact,
            tooltip: LinDoFeedCopy.REFRESH_LIST_DESC.text,
            onPressed: _loading ? null : () => _loadFeed(reset: true),
            icon: Icon(
              LinDoIcons.Refresh,
              size: 19,
              color: palette.inkSecondary,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildSortBar(
    BuildContext context,
    LinDoThemePalette palette,
    LinDoThemeScope scope,
  ) {
    return SizedBox(
      height: 38,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 16),
        itemCount: FeedSortFilter.values.length,
        separatorBuilder: (_, _) => const SizedBox(width: 18),
        itemBuilder: (context, index) {
          final filter = FeedSortFilter.values[index];
          final selected = filter == _sort;
          return InkWell(
            borderRadius: BorderRadius.circular(8),
            onTap: () => _selectSort(filter),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.end,
              children: [
                Text(
                  filter.label,
                  style: TextStyle(
                    fontSize: scope.sp(14),
                    fontWeight: selected ? FontWeight.w800 : FontWeight.w500,
                    color: selected ? palette.inkPrimary : palette.inkSecondary,
                  ),
                ),
                const SizedBox(height: 6),
                AnimatedContainer(
                  duration: const Duration(milliseconds: 180),
                  height: 2.5,
                  width: selected ? 20 : 0,
                  decoration: BoxDecoration(
                    color: palette.tabIndicatorColor,
                    borderRadius: BorderRadius.circular(999),
                  ),
                ),
              ],
            ),
          );
        },
      ),
    );
  }

  Widget _buildTopPeriodBar(
    BuildContext context,
    LinDoThemePalette palette,
    LinDoThemeScope scope,
  ) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 6, 16, 4),
      child: SizedBox(
        height: 28,
        child: ListView.separated(
          scrollDirection: Axis.horizontal,
          itemCount: TopPeriodFilter.values.length,
          separatorBuilder: (_, _) => const SizedBox(width: 6),
          itemBuilder: (context, index) {
            final p = TopPeriodFilter.values[index];
            final selected = p == _topPeriod;
            return InkWell(
              borderRadius: BorderRadius.circular(999),
              onTap: () {
                if (selected) return;
                setState(() => _topPeriod = p);
                _loadFeed(reset: true);
              },
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 4),
                decoration: BoxDecoration(
                  color: selected ? palette.activeChipBg : palette.subtleSurface,
                  borderRadius: BorderRadius.circular(999),
                ),
                child: Text(
                  p.label,
                  style: TextStyle(
                    fontSize: scope.sp(11.5),
                    fontWeight: selected ? FontWeight.w700 : FontWeight.w500,
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
    );
  }

  Widget _buildCategoryAndTagStrip(
    BuildContext context,
    LinDoThemePalette palette,
    LinDoThemeScope scope,
    List<CategoryInfo> topCategories,
  ) {
    final activeCatId = widget.filterCategoryId;
    final activeTag = (widget.filterTag ?? '').trim();

    // Ensure currently selected subcategory appears in strip even if child
    CategoryInfo? selectedSubCat;
    if (activeCatId != null &&
        !topCategories.any((c) => c.id == activeCatId)) {
      for (final c in widget.repository.categories) {
        if (c.id == activeCatId) {
          selectedSubCat = c;
          break;
        }
      }
      if (selectedSubCat == null &&
          widget.filterCategoryName != null &&
          widget.filterCategoryName!.isNotEmpty) {
        selectedSubCat = CategoryInfo(
          id: activeCatId,
          name: widget.filterCategoryName!,
          slug: widget.filterCategorySlug ?? '',
        );
      }
    }

    return Padding(
      padding: const EdgeInsets.fromLTRB(0, 6, 0, 8),
      child: SizedBox(
        height: 30,
        child: ListView(
          scrollDirection: Axis.horizontal,
          padding: const EdgeInsets.symmetric(horizontal: 14),
          children: [
            Padding(
              padding: const EdgeInsets.only(right: 7),
              child: InkWell(
                borderRadius: BorderRadius.circular(999),
                onTap: () {
                  widget.onCategoryChanged(null);
                  if (activeTag.isNotEmpty) {
                    widget.onTagChanged(null);
                  }
                },
                child: Container(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 12, vertical: 5),
                  decoration: BoxDecoration(
                    color: (activeCatId == null && activeTag.isEmpty)
                        ? palette.activeChipBg
                        : palette.subtleSurface,
                    borderRadius: BorderRadius.circular(999),
                    border: Border.all(
                      color: (activeCatId == null && activeTag.isEmpty)
                          ? palette.activeChipText.withValues(alpha: 0.35)
                          : palette.hairline,
                    ),
                  ),
                  child: Text(
                    LinDoFeedCopy.PERIOD_ALL.text,
                    style: TextStyle(
                      fontSize: scope.sp(12),
                      fontWeight: (activeCatId == null && activeTag.isEmpty)
                          ? FontWeight.w700
                          : FontWeight.w500,
                      color: (activeCatId == null && activeTag.isEmpty)
                          ? palette.activeChipText
                          : palette.inkSecondary,
                    ),
                  ),
                ),
              ),
            ),
            if (activeTag.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(right: 7),
                child: InkWell(
                  borderRadius: BorderRadius.circular(999),
                  onTap: () => widget.onTagChanged(null),
                  child: Container(
                    padding:
                        const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
                    decoration: BoxDecoration(
                      color: palette.activeChipBg,
                      borderRadius: BorderRadius.circular(999),
                      border: Border.all(
                        color: palette.activeChipText.withValues(alpha: 0.35),
                      ),
                    ),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(
                          LinDoIcons.Tag,
                          size: 12,
                          color: palette.activeChipText,
                        ),
                        const SizedBox(width: 4),
                        Text(
                          activeTag,
                          style: TextStyle(
                            fontSize: scope.sp(12),
                            fontWeight: FontWeight.w700,
                            color: palette.activeChipText,
                          ),
                        ),
                        const SizedBox(width: 4),
                        Icon(
                          LinDoIcons.Close,
                          size: 13,
                          color: palette.activeChipText,
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            if (selectedSubCat != null)
              Padding(
                padding: const EdgeInsets.only(right: 7),
                child: CategoryMorandiChip(
                  categoryName: selectedSubCat.name,
                  colorHex: selectedSubCat.colorHex,
                  selected: true,
                  onTap: () => widget.onCategoryChanged(null),
                ),
              ),
            ...topCategories.map((cat) {
              final isSelected = activeCatId == cat.id;
              return Padding(
                padding: const EdgeInsets.only(right: 7),
                child: CategoryMorandiChip(
                  categoryName: cat.name,
                  colorHex: cat.colorHex,
                  selected: isSelected,
                  onTap: () =>
                      widget.onCategoryChanged(isSelected ? null : cat),
                ),
              );
            }),
          ],
        ),
      ),
    );
  }

  Widget _buildFeedBody(
    BuildContext context,
    LinDoThemePalette palette,
    LinDoThemeScope scope, {
    required bool isCompact,
    required bool isSiteVerify,
  }) {
    if (_loading && _topics.isEmpty) {
      return Center(
        child: LinDoStatePanel(
          mood: MascotMood.connecting,
          title: LinDoCommonCopy.LOADING.text,
        ),
      );
    }

    if (isSiteVerify && _topics.isEmpty) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: LinDoSiteVerificationCard(
            onStartInAppVerify: () async {
              await widget.onStartSiteVerify();
              if (mounted) _loadFeed(reset: true);
            },
            onRetry: () => _loadFeed(reset: true),
          ),
        ),
      );
    }

    if (_error != null && _topics.isEmpty) {
      return Center(
        child: LinDoStatePanel(
          mood: MascotMood.surprised,
          title: LinDoCommonCopy.LOAD_FAILED.text,
          subtitle: _error,
          actionLabel: LinDoCommonCopy.RETRY.text,
          onAction: () => _loadFeed(reset: true),
        ),
      );
    }

    if (_topics.isEmpty && _pinnedTopics.isEmpty) {
      return Center(
        child: LinDoStatePanel(
          mood: MascotMood.sleepy,
          title: LinDoFeedCopy.EMPTY_TOPICS.text,
          actionLabel: LinDoCommonCopy.REFRESH.text,
          onAction: () => _loadFeed(reset: true),
        ),
      );
    }

    return ColoredBox(
      color: isCompact ? palette.canvas : palette.groupedCanvas,
      child: RefreshIndicator(
        color: palette.logoDoColor,
        onRefresh: () => _loadFeed(reset: true),
        child: CustomScrollView(
          controller: _scrollController,
          physics: const AlwaysScrollableScrollPhysics(),
          slivers: [
            if (isSiteVerify)
              SliverToBoxAdapter(
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(12, 10, 12, 4),
                  child: LinDoSiteVerificationCard(
                    onStartInAppVerify: () async {
                      await widget.onStartSiteVerify();
                      if (mounted) _loadFeed(reset: true);
                    },
                    onRetry: () => _loadFeed(reset: true),
                  ),
                ),
              ),
            if (_pinnedTopics.isNotEmpty)
              SliverToBoxAdapter(
                child: _PinnedTopicsSection(
                  pinnedTopics: _pinnedTopics,
                  expanded: _pinnedExpanded,
                  selectedTopicId: widget.selectedTopicId,
                  onToggleExpanded: () =>
                      setState(() => _pinnedExpanded = !_pinnedExpanded),
                  onOpenTopic: (topic) =>
                      widget.onOpenTopic(topic.id, title: topic.title),
                ),
              ),
            SliverPadding(
              padding: EdgeInsets.fromLTRB(
                isCompact ? 0 : 12,
                isCompact ? 0 : 10,
                isCompact ? 0 : 12,
                96,
              ),
              sliver: SliverList.builder(
                itemCount: _topics.length + (_loadingMore ? 1 : 0),
                itemBuilder: (context, index) {
                  if (index >= _topics.length) {
                    return const Padding(
                      padding: EdgeInsets.symmetric(vertical: 20),
                      child: Center(
                        child: LinDoWaveDots(),
                      ),
                    );
                  }
                  final topic = _topics[index];
                  final readFloor = widget.store.getReadFloor(topic.id) ?? 0;
                  final isSelected = widget.selectedTopicId == topic.id;

                  return RepaintBoundary(
                    child: isCompact
                        ? _CompactTopicRow(
                            topic: topic,
                            readFloor: readFloor,
                            selected: isSelected,
                            onTap: () => widget.onOpenTopic(
                              topic.id,
                              postNumber: readFloor > 1 ? readFloor : null,
                              title: topic.title,
                            ),
                            onTapTag: widget.onTagChanged,
                          )
                        : Padding(
                            padding: const EdgeInsets.only(bottom: 10),
                            child: _SpaciousTopicCard(
                              topic: topic,
                              readFloor: readFloor,
                              selected: isSelected,
                              onTap: () => widget.onOpenTopic(
                                topic.id,
                                postNumber: readFloor > 1 ? readFloor : null,
                                title: topic.title,
                              ),
                              onTapAuthor: () =>
                                  _openAuthorCard(topic.originalPoster.username),
                              onTapTag: widget.onTagChanged,
                            ),
                          ),
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _PinnedTopicsSection extends StatelessWidget {
  final List<TopicSummary> pinnedTopics;
  final bool expanded;
  final int? selectedTopicId;
  final VoidCallback onToggleExpanded;
  final ValueChanged<TopicSummary> onOpenTopic;

  const _PinnedTopicsSection({
    required this.pinnedTopics,
    required this.expanded,
    required this.selectedTopicId,
    required this.onToggleExpanded,
    required this.onOpenTopic,
  });

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    final scope = LinDoThemeScope.of(context);
    final visible = expanded ? pinnedTopics : pinnedTopics.take(2).toList();

    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 10, 12, 0),
      child: Container(
        decoration: BoxDecoration(
          color: palette.cardSurface,
          borderRadius: BorderRadius.circular(14),
          border: Border.all(color: palette.hairline),
        ),
        child: Column(
          children: [
            ...visible.map((t) {
              final selected = selectedTopicId == t.id;
              return InkWell(
                borderRadius: BorderRadius.circular(14),
                onTap: () => onOpenTopic(t),
                child: Container(
                  color: selected
                      ? palette.activeChipBg.withValues(alpha: 0.35)
                      : Colors.transparent,
                  padding: const EdgeInsets.symmetric(
                    horizontal: 12,
                    vertical: 9,
                  ),
                  child: Row(
                    children: [
                      Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 6,
                          vertical: 2,
                        ),
                        decoration: BoxDecoration(
                          color: palette.activeChipBg,
                          borderRadius: BorderRadius.circular(6),
                        ),
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Icon(
                              LinDoIcons.Pin,
                              size: 10.5,
                              color: palette.activeChipText,
                            ),
                            const SizedBox(width: 3),
                            Text(
                              LinDoFeedCopy.PINNED_BADGE.text,
                              style: TextStyle(
                                fontSize: scope.sp(10.5),
                                fontWeight: FontWeight.w700,
                                color: palette.activeChipText,
                              ),
                            ),
                          ],
                        ),
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        child: Text(
                          t.title,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            fontSize: scope.sp(13),
                            fontWeight: FontWeight.w600,
                            color: palette.inkPrimary,
                          ),
                        ),
                      ),
                      const SizedBox(width: 6),
                      Text(
                        formatCompactCount(t.postsCount > 0 ? t.postsCount - 1 : t.replyCount),
                        style: TextStyle(
                          fontSize: scope.sp(11.5),
                          color: palette.inkTertiary,
                        ),
                      ),
                    ],
                  ),
                ),
              );
            }),
            if (pinnedTopics.length > 2)
              InkWell(
                borderRadius: const BorderRadius.vertical(
                  bottom: Radius.circular(14),
                ),
                onTap: onToggleExpanded,
                child: Padding(
                  padding: const EdgeInsets.symmetric(vertical: 6),
                  child: Row(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      Text(
                        expanded
                            ? LinDoCommonCopy.CLOSE.text
                            : '${LinDoFeedCopy.PINNED_BADGE.text} (${pinnedTopics.length})',
                        style: TextStyle(
                          fontSize: scope.sp(11.5),
                          fontWeight: FontWeight.w600,
                          color: palette.inkTertiary,
                        ),
                      ),
                      Icon(
                        expanded
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
    );
  }
}

class _SpaciousTopicCard extends StatelessWidget {
  final TopicSummary topic;
  final int readFloor;
  final bool selected;
  final VoidCallback onTap;
  final VoidCallback onTapAuthor;
  final ValueChanged<String> onTapTag;

  const _SpaciousTopicCard({
    required this.topic,
    required this.readFloor,
    required this.selected,
    required this.onTap,
    required this.onTapAuthor,
    required this.onTapTag,
  });

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    final scope = LinDoThemeScope.of(context);
    final op = topic.originalPoster;
    final cleanExcerpt = (topic.excerpt ?? '').trim();
    final timeStr = topic.lastPostedAtIso.isNotEmpty
        ? formatRelativeTime(topic.lastPostedAtIso)
        : formatRelativeTime(topic.lastPostedAt);
    final replies = topic.postsCount > 1 ? topic.postsCount - 1 : topic.replyCount;

    return Material(
      color: selected
          ? Color.alphaBlend(
              palette.logoDoColor.withValues(alpha: palette.isDark ? 0.12 : 0.06),
              palette.cardSurface,
            )
          : palette.cardSurface,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(16),
        side: BorderSide(
          color: selected
              ? palette.logoDoColor.withValues(alpha: 0.55)
              : palette.hairline,
          width: selected ? 1.2 : 1.0,
        ),
      ),
      child: InkWell(
        borderRadius: BorderRadius.circular(16),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(15, 13, 15, 13),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // Author + Time + Category
              Row(
                children: [
                  SmartAvatar(
                    avatarUrl: op.avatarTemplate,
                    fallbackLabel: op.username,
                    size: 32,
                    flair: op.flair,
                    trustLevel: op.trustLevel,
                    isAdmin: op.admin,
                    isModerator: op.moderator,
                    animatedAvatar: op.animatedAvatar,
                    primaryGroupName: op.primaryGroupName,
                    onTap: onTapAuthor,
                  ),
                  const SizedBox(width: 9),
                  Expanded(
                    child: GestureDetector(
                      onTap: onTapAuthor,
                      behavior: HitTestBehavior.opaque,
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            op.displayName,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              fontSize: scope.sp(13),
                              fontWeight: FontWeight.w700,
                              color: palette.inkPrimary,
                            ),
                          ),
                          Text(
                            timeStr,
                            style: TextStyle(
                              fontSize: scope.sp(11),
                              color: palette.inkTertiary,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                  if (topic.unseen || topic.unreadPosts > 0) ...[
                    Container(
                      padding: EdgeInsets.symmetric(
                        horizontal: topic.unreadPosts > 0 ? 6.5 : 4,
                        vertical: topic.unreadPosts > 0 ? 2 : 4,
                      ),
                      decoration: BoxDecoration(
                        color: palette.logoDoColor,
                        borderRadius: BorderRadius.circular(999),
                      ),
                      child: topic.unreadPosts > 0
                          ? Text(
                              '+${topic.unreadPosts}',
                              style: TextStyle(
                                fontSize: scope.sp(10.5),
                                fontWeight: FontWeight.w800,
                                color: const Color(0xFF111827),
                              ),
                            )
                          : const SizedBox(width: 2, height: 2),
                    ),
                    const SizedBox(width: 6),
                  ],
                  CategoryMorandiChip(
                    categoryName: topic.categoryName,
                    colorHex: topic.categoryColorHex,
                    compact: true,
                  ),
                ],
              ),
              const SizedBox(height: 10),

              // Title
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  if (topic.closed) ...[
                    Padding(
                      padding: const EdgeInsets.only(top: 3, right: 5),
                      child: Icon(
                        LinDoIcons.Lock,
                        size: 14,
                        color: palette.inkTertiary,
                      ),
                    ),
                  ],
                  if (topic.hasAcceptedAnswer) ...[
                    const Padding(
                      padding: EdgeInsets.only(top: 3, right: 5),
                      child: Icon(
                        LinDoIcons.CheckCircle,
                        size: 14.5,
                        color: Color(0xFF10B981),
                      ),
                    ),
                  ],
                  Expanded(
                    child: Text(
                      topic.title,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        fontSize: scope.sp(15.5),
                        fontWeight: FontWeight.w700,
                        height: 1.38,
                        color: readFloor > 0 && topic.unreadPosts == 0 && !topic.unseen
                            ? palette.inkPrimary.withValues(alpha: 0.85)
                            : palette.inkPrimary,
                      ),
                    ),
                  ),
                ],
              ),

              if (cleanExcerpt.isNotEmpty) ...[
                const SizedBox(height: 6),
                Text(
                  decodeDiscourseHtmlSnippet(cleanExcerpt),
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: scope.sp(13),
                    height: 1.45,
                    color: palette.inkSecondary,
                  ),
                ),
              ],

              if (topic.tags.isNotEmpty) ...[
                const SizedBox(height: 8),
                Wrap(
                  spacing: 6,
                  runSpacing: 5,
                  children: topic.tags.take(4).map((tag) {
                    return InkWell(
                      borderRadius: BorderRadius.circular(6),
                      onTap: () => onTapTag(tag),
                      child: Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 7,
                          vertical: 2.5,
                        ),
                        decoration: BoxDecoration(
                          color: palette.subtleSurface,
                          borderRadius: BorderRadius.circular(6),
                        ),
                        child: Text(
                          '#$tag',
                          style: TextStyle(
                            fontSize: scope.sp(11),
                            fontWeight: FontWeight.w500,
                            color: palette.inkSecondary,
                          ),
                        ),
                      ),
                    );
                  }).toList(),
                ),
              ],

              const SizedBox(height: 10),

              // Footer: Participant Avatars + Stats
              Row(
                children: [
                  if (topic.participantAvatars.length > 1)
                    SizedBox(
                      height: 20,
                      width: (topic.participantAvatars.take(4).length * 14.0) + 6,
                      child: Stack(
                        children: [
                          for (var i = 0;
                              i < topic.participantAvatars.take(4).length;
                              i++)
                            Positioned(
                              left: i * 13.0,
                              child: Container(
                                decoration: BoxDecoration(
                                  shape: BoxShape.circle,
                                  border: Border.all(
                                    color: palette.cardSurface,
                                    width: 1.4,
                                  ),
                                ),
                                child: SmartAvatar(
                                  avatarUrl: topic
                                      .participantAvatars[i].avatarTemplate,
                                  fallbackLabel:
                                      topic.participantAvatars[i].username,
                                  size: 18,
                                ),
                              ),
                            ),
                        ],
                      ),
                    ),
                  if (readFloor > 1) ...[
                    Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 7,
                        vertical: 2,
                      ),
                      decoration: BoxDecoration(
                        color: palette.tonalPill,
                        borderRadius: BorderRadius.circular(999),
                      ),
                      child: Text(
                        '已读 #$readFloor',
                        style: TextStyle(
                          fontSize: scope.sp(10.5),
                          fontWeight: FontWeight.w600,
                          color: palette.inkSecondary,
                        ),
                      ),
                    ),
                  ],
                  const Spacer(),
                  _TopicMetricPill(
                    icon: LinDoIcons.MessageCircle,
                    text: formatCompactCount(replies),
                  ),
                  const SizedBox(width: 12),
                  _TopicMetricPill(
                    icon: LinDoIcons.Heart,
                    text: formatCompactCount(topic.likeCount),
                  ),
                  const SizedBox(width: 12),
                  _TopicMetricPill(
                    icon: LinDoIcons.Eye,
                    text: formatCompactCount(topic.views),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _CompactTopicRow extends StatelessWidget {
  final TopicSummary topic;
  final int readFloor;
  final bool selected;
  final VoidCallback onTap;
  final ValueChanged<String> onTapTag;

  const _CompactTopicRow({
    required this.topic,
    required this.readFloor,
    required this.selected,
    required this.onTap,
    required this.onTapTag,
  });

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    final scope = LinDoThemeScope.of(context);
    final replies = topic.postsCount > 1 ? topic.postsCount - 1 : topic.replyCount;
    final timeStr = topic.lastPostedAtIso.isNotEmpty
        ? formatRelativeTime(topic.lastPostedAtIso)
        : formatRelativeTime(topic.lastPostedAt);

    return Material(
      color: selected
          ? Color.alphaBlend(
              palette.logoDoColor.withValues(alpha: palette.isDark ? 0.12 : 0.06),
              palette.canvas,
            )
          : palette.canvas,
      child: InkWell(
        onTap: onTap,
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
          decoration: BoxDecoration(
            border: Border(
              bottom: BorderSide(color: palette.hairline, width: 0.8),
              left: selected
                  ? BorderSide(color: palette.logoDoColor, width: 3)
                  : BorderSide.none,
            ),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  if (topic.unseen || topic.unreadPosts > 0) ...[
                    Container(
                      width: 6.5,
                      height: 6.5,
                      decoration: BoxDecoration(
                        color: palette.logoDoColor,
                        shape: BoxShape.circle,
                      ),
                    ),
                    const SizedBox(width: 6),
                  ],
                  Expanded(
                    child: Text(
                      topic.title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        fontSize: scope.sp(14),
                        fontWeight: FontWeight.w600,
                        color: palette.inkPrimary,
                      ),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 6.5,
                      vertical: 1.5,
                    ),
                    decoration: BoxDecoration(
                      color: palette.subtleSurface,
                      borderRadius: BorderRadius.circular(999),
                    ),
                    child: Text(
                      formatCompactCount(replies),
                      style: TextStyle(
                        fontSize: scope.sp(11),
                        fontWeight: FontWeight.w700,
                        color: palette.inkSecondary,
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 5),
              Row(
                children: [
                  CategoryMorandiChip(
                    categoryName: topic.categoryName,
                    colorHex: topic.categoryColorHex,
                    compact: true,
                  ),
                  const SizedBox(width: 8),
                  Flexible(
                    child: Text(
                      '@${topic.originalPoster.username}',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        fontSize: scope.sp(11.5),
                        color: palette.inkTertiary,
                      ),
                    ),
                  ),
                  if (topic.tags.isNotEmpty) ...[
                    const SizedBox(width: 6),
                    GestureDetector(
                      onTap: () => onTapTag(topic.tags.first),
                      child: Text(
                        '#${topic.tags.first}',
                        style: TextStyle(
                          fontSize: scope.sp(11),
                          color: palette.inkSecondary,
                        ),
                      ),
                    ),
                  ],
                  const Spacer(),
                  if (topic.likeCount > 0) ...[
                    Icon(
                      LinDoIcons.Heart,
                      size: 11.5,
                      color: palette.inkTertiary,
                    ),
                    const SizedBox(width: 2.5),
                    Text(
                      formatCompactCount(topic.likeCount),
                      style: TextStyle(
                        fontSize: scope.sp(11),
                        color: palette.inkTertiary,
                      ),
                    ),
                    const SizedBox(width: 8),
                  ],
                  Text(
                    timeStr,
                    style: TextStyle(
                      fontSize: scope.sp(11),
                      color: palette.inkTertiary,
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _TopicMetricPill extends StatelessWidget {
  final IconData icon;
  final String text;

  const _TopicMetricPill({
    required this.icon,
    required this.text,
  });

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    final scope = LinDoThemeScope.of(context);
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, size: 13.5, color: palette.inkTertiary),
        const SizedBox(width: 3.5),
        Text(
          text,
          style: TextStyle(
            fontSize: scope.sp(11.5),
            color: palette.inkTertiary,
          ),
        ),
      ],
    );
  }
}
