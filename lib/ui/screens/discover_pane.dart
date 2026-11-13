import 'package:flutter/material.dart';

import '../../core/copy/lindo_copy.dart';
import '../../core/models/lindo_models.dart';
import '../../core/network/linux_do_repository.dart';
import '../../core/site/linux_do_site_rules.dart';
import '../../core/storage/lindo_local_store.dart';
import '../components/lindo_icons.dart';
import '../components/web_auth_modal.dart';
import '../sheets/badges_sheet.dart';
import '../sheets/connect_sheet.dart';
import '../sheets/global_search_sheet.dart';
import '../sheets/user_collections_sheets.dart';
import '../theme/lindo_theme.dart';

class DiscoverPane extends StatefulWidget {
  final LinuxDoRepository repository;
  final LinDoLocalStore store;
  final ValueChanged<CategoryInfo> onSelectCategory;
  final ValueChanged<String> onSelectTag;
  final void Function(int topicId, {int? postNumber, String? title})
      onOpenTopic;
  final Future<void> Function() onStartWebLogin;
  final Future<void> Function() onStartSiteVerify;
  final ValueChanged<String>? onShowToast;

  const DiscoverPane({
    super.key,
    required this.repository,
    required this.store,
    required this.onSelectCategory,
    required this.onSelectTag,
    required this.onOpenTopic,
    required this.onStartWebLogin,
    required this.onStartSiteVerify,
    this.onShowToast,
  });

  @override
  State<DiscoverPane> createState() => _DiscoverPaneState();
}

class _DiscoverPaneState extends State<DiscoverPane> {
  static const List<String> _curatedPopularTags = <String>[
    '人工智能',
    '开源项目',
    'Claude',
    'DeepSeek',
    'MCP',
    'Cursor',
    'Docker',
    '快问快答',
    '福利羊毛',
    '前端',
    'Python',
    'Rust',
    '自托管',
    '效率工具',
  ];

  final Set<int> _expandedCategoryIds = <int>{};
  bool _refreshingCategories = false;

  Future<void> _refreshCategories() async {
    setState(() => _refreshingCategories = true);
    try {
      await widget.repository.fetchCategories(forceRefresh: true);
    } catch (_) {}
    if (mounted) {
      setState(() => _refreshingCategories = false);
    }
  }

  IconData _portalIcon(String key) {
    switch (key) {
      case 'connect':
        return LinDoIcons.ShieldCheck;
      case 'leaderboard':
        return LinDoIcons.Crown;
      case 'badges':
        return LinDoIcons.Trophy;
      case 'about':
        return LinDoIcons.InfoCircle;
      case 'credit':
        return LinDoIcons.Coins;
      case 'cdk':
        return LinDoIcons.ShoppingBag;
      case 'hub':
        return LinDoIcons.CpuChip;
      case 'wiki':
        return LinDoIcons.BookOpen;
      case 'idc':
        return LinDoIcons.Layers;
      case 'lottery':
        return LinDoIcons.Sparkles;
      default:
        return LinDoIcons.Globe;
    }
  }

  Future<void> _handlePortalTap(EcosystemPortalItem portal) async {
    if (portal.guestScenario != null && !widget.repository.isAuthorized) {
      await showGuestLoginGuideSheet(
        context,
        scenario: portal.guestScenario!,
        onStartWebLogin: widget.onStartWebLogin,
      );
      return;
    }

    if (portal.opensConnectSheet) {
      await showConnectSheet(
        context,
        repository: widget.repository,
        onStartSiteVerify: widget.onStartSiteVerify,
      );
      return;
    }

    if (portal.opensLeaderboardSheet) {
      await showLeaderboardSheet(
        context,
        repository: widget.repository,
        store: widget.store,
        onOpenTopic: widget.onOpenTopic,
        onStartSiteVerify: widget.onStartSiteVerify,
        onShowToast: widget.onShowToast,
      );
      return;
    }

    if (portal.opensBadgesSheet) {
      await showBadgesSheet(
        context,
        repository: widget.repository,
        onStartSiteVerify: widget.onStartSiteVerify,
        onOpenTopic: (topicId, {postNumber}) =>
            widget.onOpenTopic(topicId, postNumber: postNumber),
        onShowToast: widget.onShowToast,
      );
      return;
    }

    if (portal.opensAboutSheet) {
      await showAboutSheet(
        context,
        repository: widget.repository,
        store: widget.store,
        onOpenTopic: widget.onOpenTopic,
        onStartSiteVerify: widget.onStartSiteVerify,
        onShowToast: widget.onShowToast,
      );
      return;
    }

    await showLinDoWebAuthModal(
      context,
      initialUrl: portal.url,
      mode: WebAuthModalMode.inAppBrowser,
      customTitle: portal.title,
    );
  }

  void _openGlobalSearch([String initialQuery = '']) {
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

  List<String> _collectPopularTags(List<CategoryInfo> categories) {
    final seen = <String>{};
    final out = <String>[];
    for (final cat in categories) {
      for (final t in cat.allowedTags) {
        final clean = t.trim();
        if (clean.isNotEmpty && seen.add(clean)) {
          out.add(clean);
        }
      }
    }
    for (final t in _curatedPopularTags) {
      if (seen.add(t)) {
        out.add(t);
      }
    }
    return out.take(20).toList(growable: false);
  }

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    final scope = LinDoThemeScope.of(context);
    final screenWidth = MediaQuery.sizeOf(context).width;
    final isWide = screenWidth >= 840;

    return ListenableBuilder(
      listenable: widget.repository,
      builder: (context, _) {
        final allCategories = widget.repository.categories;
        final parentCategories = allCategories
            .where((c) => c.parentCategoryId == null)
            .toList(growable: false);
        final subByParent = <int, List<CategoryInfo>>{};
        for (final c in allCategories) {
          final pId = c.parentCategoryId;
          if (pId != null) {
            subByParent.putIfAbsent(pId, () => <CategoryInfo>[]).add(c);
          }
        }
        final tags = _collectPopularTags(allCategories);

        return ColoredBox(
          color: palette.groupedCanvas,
          child: RefreshIndicator(
            color: palette.logoDoColor,
            onRefresh: _refreshCategories,
            child: CustomScrollView(
              physics: const AlwaysScrollableScrollPhysics(),
              slivers: [
                SliverToBoxAdapter(
                  child: Center(
                    child: ConstrainedBox(
                      constraints: const BoxConstraints(maxWidth: 980),
                      child: Padding(
                        padding: EdgeInsets.fromLTRB(
                          18,
                          isWide ? 20 : 14,
                          18,
                          96,
                        ),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            // Header + Prominent Search Bar
                            Row(
                              children: [
                                Icon(
                                  LinDoIcons.CompassSelected,
                                  size: 22,
                                  color: palette.logoDoColor,
                                ),
                                const SizedBox(width: 9),
                                Expanded(
                                  child: Text(
                                    LinDoFeedCopy.TAB_DISCOVER.text,
                                    style: TextStyle(
                                      fontSize: scope.sp(21),
                                      fontWeight: FontWeight.w800,
                                      color: palette.inkPrimary,
                                    ),
                                  ),
                                ),
                                IconButton(
                                  tooltip: LinDoCommonCopy.REFRESH.text,
                                  onPressed: _refreshingCategories
                                      ? null
                                      : _refreshCategories,
                                  icon: Icon(
                                    LinDoIcons.Refresh,
                                    size: 19,
                                    color: palette.inkSecondary,
                                  ),
                                ),
                              ],
                            ),
                            const SizedBox(height: 12),
                            Material(
                              color: palette.cardSurface,
                              shape: RoundedRectangleBorder(
                                borderRadius: BorderRadius.circular(16),
                                side: BorderSide(color: palette.hairline),
                              ),
                              child: InkWell(
                                borderRadius: BorderRadius.circular(16),
                                onTap: _openGlobalSearch,
                                child: Padding(
                                  padding: const EdgeInsets.symmetric(
                                    horizontal: 16,
                                    vertical: 13,
                                  ),
                                  child: Row(
                                    children: [
                                      Icon(
                                        LinDoIcons.Search,
                                        size: 18,
                                        color: palette.logoDoColor,
                                      ),
                                      const SizedBox(width: 10),
                                      Expanded(
                                        child: Text(
                                          LinDoFeedCopy.SEARCH_PLACEHOLDER.text,
                                          style: TextStyle(
                                            fontSize: scope.sp(14),
                                            color: palette.inkTertiary,
                                          ),
                                        ),
                                      ),
                                      Container(
                                        padding: const EdgeInsets.symmetric(
                                          horizontal: 8,
                                          vertical: 3,
                                        ),
                                        decoration: BoxDecoration(
                                          color: palette.tonalPill,
                                          borderRadius:
                                              BorderRadius.circular(8),
                                        ),
                                        child: Text(
                                          LinDoFeedCopy.GLOBAL_SEARCH_TITLE.text,
                                          style: TextStyle(
                                            fontSize: scope.sp(11),
                                            fontWeight: FontWeight.w600,
                                            color: palette.inkSecondary,
                                          ),
                                        ),
                                      ),
                                    ],
                                  ),
                                ),
                              ),
                            ),
                            const SizedBox(height: 24),

                            // 1. Official Ecosystem Portals
                            _SectionHeading(
                              icon: LinDoIcons.AppsGrid,
                              title:
                                  LinDoFeedCopy.SECTION_OFFICIAL_ECOSYSTEM.text,
                            ),
                            const SizedBox(height: 12),
                            LayoutBuilder(
                              builder: (context, constraints) {
                                final cols = constraints.maxWidth >= 760
                                    ? 4
                                    : constraints.maxWidth >= 500
                                        ? 3
                                        : 2;
                                return GridView.builder(
                                  shrinkWrap: true,
                                  physics:
                                      const NeverScrollableScrollPhysics(),
                                  itemCount: kOfficialEcosystemPortals.length,
                                  gridDelegate:
                                      SliverGridDelegateWithFixedCrossAxisCount(
                                    crossAxisCount: cols,
                                    mainAxisSpacing: 10,
                                    crossAxisSpacing: 10,
                                    mainAxisExtent: 82,
                                  ),
                                  itemBuilder: (context, index) {
                                    final portal =
                                        kOfficialEcosystemPortals[index];
                                    final requiresLogin =
                                        portal.guestScenario != null &&
                                            !widget.repository.isAuthorized;
                                    return RepaintBoundary(
                                      child: Material(
                                        color: palette.cardSurface,
                                        shape: RoundedRectangleBorder(
                                          borderRadius:
                                              BorderRadius.circular(16),
                                          side: BorderSide(
                                            color: palette.hairline,
                                          ),
                                        ),
                                        child: InkWell(
                                          borderRadius:
                                              BorderRadius.circular(16),
                                          onTap: () =>
                                              _handlePortalTap(portal),
                                          child: Padding(
                                            padding: const EdgeInsets.symmetric(
                                              horizontal: 13,
                                              vertical: 11,
                                            ),
                                            child: Row(
                                              children: [
                                                Container(
                                                  width: 38,
                                                  height: 38,
                                                  decoration: BoxDecoration(
                                                    color: palette.activeChipBg,
                                                    borderRadius:
                                                        BorderRadius.circular(
                                                      11,
                                                    ),
                                                  ),
                                                  child: Icon(
                                                    _portalIcon(portal.key),
                                                    size: 19,
                                                    color:
                                                        palette.activeChipText,
                                                  ),
                                                ),
                                                const SizedBox(width: 10),
                                                Expanded(
                                                  child: Column(
                                                    mainAxisAlignment:
                                                        MainAxisAlignment
                                                            .center,
                                                    crossAxisAlignment:
                                                        CrossAxisAlignment
                                                            .start,
                                                    children: [
                                                      Row(
                                                        children: [
                                                          Flexible(
                                                            child: Text(
                                                              portal.title,
                                                              maxLines: 1,
                                                              overflow:
                                                                  TextOverflow
                                                                      .ellipsis,
                                                              style: TextStyle(
                                                                fontSize: scope
                                                                    .sp(13.5),
                                                                fontWeight:
                                                                    FontWeight
                                                                        .w700,
                                                                color: palette
                                                                    .inkPrimary,
                                                              ),
                                                            ),
                                                          ),
                                                          if (requiresLogin) ...[
                                                            const SizedBox(
                                                              width: 4,
                                                            ),
                                                            Icon(
                                                              LinDoIcons.Lock,
                                                              size: 11.5,
                                                              color: palette
                                                                  .inkTertiary,
                                                            ),
                                                          ],
                                                        ],
                                                      ),
                                                      const SizedBox(height: 2),
                                                      Text(
                                                        portal.description,
                                                        maxLines: 1,
                                                        overflow: TextOverflow
                                                            .ellipsis,
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
                                              ],
                                            ),
                                          ),
                                        ),
                                      ),
                                    );
                                  },
                                );
                              },
                            ),

                            const SizedBox(height: 26),

                            // 2. Community Categories Directory
                            _SectionHeading(
                              icon: LinDoIcons.Layers,
                              title: LinDoFeedCopy
                                  .SECTION_COMMUNITY_CATEGORIES.text,
                            ),
                            const SizedBox(height: 12),
                            LayoutBuilder(
                              builder: (context, constraints) {
                                final wideGrid = constraints.maxWidth >= 680;
                                if (!wideGrid) {
                                  return Column(
                                    children: parentCategories
                                        .map(
                                          (cat) => Padding(
                                            padding: const EdgeInsets.only(
                                              bottom: 10,
                                            ),
                                            child: _CategoryDirectoryCard(
                                              category: cat,
                                              subcategories:
                                                  subByParent[cat.id] ??
                                                      const <CategoryInfo>[],
                                              expanded: _expandedCategoryIds
                                                  .contains(cat.id),
                                              onToggleSubcategories: () {
                                                setState(() {
                                                  if (!_expandedCategoryIds
                                                      .remove(cat.id)) {
                                                    _expandedCategoryIds
                                                        .add(cat.id);
                                                  }
                                                });
                                              },
                                              onSelectCategory:
                                                  widget.onSelectCategory,
                                            ),
                                          ),
                                        )
                                        .toList(),
                                  );
                                }
                                return Wrap(
                                  spacing: 12,
                                  runSpacing: 12,
                                  children: parentCategories.map((cat) {
                                    final itemWidth =
                                        (constraints.maxWidth - 12) / 2;
                                    return SizedBox(
                                      width: itemWidth,
                                      child: _CategoryDirectoryCard(
                                        category: cat,
                                        subcategories: subByParent[cat.id] ??
                                            const <CategoryInfo>[],
                                        expanded: _expandedCategoryIds
                                            .contains(cat.id),
                                        onToggleSubcategories: () {
                                          setState(() {
                                            if (!_expandedCategoryIds
                                                .remove(cat.id)) {
                                              _expandedCategoryIds.add(cat.id);
                                            }
                                          });
                                        },
                                        onSelectCategory:
                                            widget.onSelectCategory,
                                      ),
                                    );
                                  }).toList(),
                                );
                              },
                            ),

                            const SizedBox(height: 26),

                            // 3. Popular Tags Grid
                            _SectionHeading(
                              icon: LinDoIcons.Tag,
                              title: LinDoFeedCopy.SECTION_POPULAR_TAGS.text,
                            ),
                            const SizedBox(height: 12),
                            Wrap(
                              spacing: 8,
                              runSpacing: 8,
                              children: tags.map((tag) {
                                return Material(
                                  color: palette.cardSurface,
                                  shape: RoundedRectangleBorder(
                                    borderRadius: BorderRadius.circular(999),
                                    side: BorderSide(color: palette.hairline),
                                  ),
                                  child: InkWell(
                                    borderRadius: BorderRadius.circular(999),
                                    onTap: () => widget.onSelectTag(tag),
                                    child: Padding(
                                      padding: const EdgeInsets.symmetric(
                                        horizontal: 13,
                                        vertical: 7,
                                      ),
                                      child: Row(
                                        mainAxisSize: MainAxisSize.min,
                                        children: [
                                          Icon(
                                            LinDoIcons.Tag,
                                            size: 13,
                                            color: palette.logoDoColor,
                                          ),
                                          const SizedBox(width: 5),
                                          Text(
                                            tag,
                                            style: TextStyle(
                                              fontSize: scope.sp(13),
                                              fontWeight: FontWeight.w600,
                                              color: palette.inkPrimary,
                                            ),
                                          ),
                                        ],
                                      ),
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
              ],
            ),
          ),
        );
      },
    );
  }
}

class _SectionHeading extends StatelessWidget {
  final IconData icon;
  final String title;

  const _SectionHeading({
    required this.icon,
    required this.title,
  });

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    final scope = LinDoThemeScope.of(context);
    return Row(
      children: [
        Icon(icon, size: 17, color: palette.logoDoColor),
        const SizedBox(width: 7),
        Text(
          title,
          style: TextStyle(
            fontSize: scope.sp(15.5),
            fontWeight: FontWeight.w800,
            color: palette.inkPrimary,
          ),
        ),
      ],
    );
  }
}

class _CategoryDirectoryCard extends StatelessWidget {
  final CategoryInfo category;
  final List<CategoryInfo> subcategories;
  final bool expanded;
  final VoidCallback onToggleSubcategories;
  final ValueChanged<CategoryInfo> onSelectCategory;

  const _CategoryDirectoryCard({
    required this.category,
    required this.subcategories,
    required this.expanded,
    required this.onToggleSubcategories,
    required this.onSelectCategory,
  });

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    final scope = LinDoThemeScope.of(context);
    final accent = parseHexColor(category.colorHex, fallback: palette.logoDoColor);

    return RepaintBoundary(
      child: Material(
        color: palette.cardSurface,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(16),
          side: BorderSide(color: palette.hairline),
        ),
        child: InkWell(
          borderRadius: BorderRadius.circular(16),
          onTap: () => onSelectCategory(category),
          child: Padding(
            padding: const EdgeInsets.all(14),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Container(
                      width: 11,
                      height: 11,
                      decoration: BoxDecoration(
                        color: accent,
                        borderRadius: BorderRadius.circular(3.5),
                      ),
                    ),
                    const SizedBox(width: 9),
                    Expanded(
                      child: Text(
                        category.name,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          fontSize: scope.sp(14.5),
                          fontWeight: FontWeight.w700,
                          color: palette.inkPrimary,
                        ),
                      ),
                    ),
                    if (category.readRestricted) ...[
                      Icon(
                        LinDoIcons.Lock,
                        size: 13,
                        color: palette.inkTertiary,
                      ),
                      const SizedBox(width: 6),
                    ],
                    if (category.topicCount > 0)
                      Text(
                        formatCompactCount(category.topicCount),
                        style: TextStyle(
                          fontSize: scope.sp(12),
                          fontWeight: FontWeight.w600,
                          color: palette.inkTertiary,
                        ),
                      ),
                    if (subcategories.isNotEmpty) ...[
                      const SizedBox(width: 6),
                      InkWell(
                        borderRadius: BorderRadius.circular(8),
                        onTap: onToggleSubcategories,
                        child: Container(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 7,
                            vertical: 2.5,
                          ),
                          decoration: BoxDecoration(
                            color: palette.tonalPill,
                            borderRadius: BorderRadius.circular(8),
                          ),
                          child: Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Text(
                                LinDoFeedCopy.SUBCATEGORY_COUNT_BADGE
                                    .format(subcategories.length),
                                style: TextStyle(
                                  fontSize: scope.sp(10.5),
                                  fontWeight: FontWeight.w600,
                                  color: palette.inkSecondary,
                                ),
                              ),
                              Icon(
                                expanded
                                    ? LinDoIcons.ChevronUp
                                    : LinDoIcons.ChevronDown,
                                size: 13,
                                color: palette.inkSecondary,
                              ),
                            ],
                          ),
                        ),
                      ),
                    ],
                  ],
                ),
                if (category.descriptionText.trim().isNotEmpty) ...[
                  const SizedBox(height: 6),
                  Text(
                    category.descriptionText.trim(),
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: scope.sp(12.5),
                      height: 1.4,
                      color: palette.inkSecondary,
                    ),
                  ),
                ],
                if (expanded && subcategories.isNotEmpty) ...[
                  const SizedBox(height: 10),
                  Wrap(
                    spacing: 6,
                    runSpacing: 6,
                    children: subcategories.map((sub) {
                      return InkWell(
                        borderRadius: BorderRadius.circular(8),
                        onTap: () => onSelectCategory(sub),
                        child: Container(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 9,
                            vertical: 4,
                          ),
                          decoration: BoxDecoration(
                            color: palette.subtleSurface,
                            borderRadius: BorderRadius.circular(8),
                            border: Border.all(color: palette.hairline),
                          ),
                          child: Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Container(
                                width: 6,
                                height: 6,
                                decoration: BoxDecoration(
                                  color: parseHexColor(
                                    sub.colorHex,
                                    fallback: accent,
                                  ),
                                  shape: BoxShape.circle,
                                ),
                              ),
                              const SizedBox(width: 5),
                              Text(
                                sub.name,
                                style: TextStyle(
                                  fontSize: scope.sp(11.5),
                                  fontWeight: FontWeight.w600,
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
              ],
            ),
          ),
        ),
      ),
    );
  }
}
