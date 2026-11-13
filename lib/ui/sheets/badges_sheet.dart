import 'package:flutter/material.dart';

import '../../core/copy/lindo_copy.dart';
import '../../core/models/lindo_models.dart';
import '../../core/network/linux_do_repository.dart';
import '../../core/site/linux_do_site_rules.dart';
import '../components/lindo_icons.dart';
import '../components/lindo_mascot.dart';
import '../components/smart_avatar_and_badges.dart';
import '../theme/lindo_theme.dart';

Future<void> showBadgesSheet(
  BuildContext context, {
  required LinuxDoRepository repository,
  String? username,
  String? currentUserTitle,
  VoidCallback? onTitleUpdated,
  Future<void> Function()? onStartSiteVerify,
  void Function(int topicId, {int? postNumber})? onOpenTopic,
  ValueChanged<String>? onShowToast,
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
            child: BadgesSheet(
              repository: repository,
              username: username,
              currentUserTitle: currentUserTitle,
              onTitleUpdated: onTitleUpdated,
              onStartSiteVerify: onStartSiteVerify,
              onOpenTopic: onOpenTopic,
              onShowToast: onShowToast,
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
      heightFactor: 0.92,
      child: ClipRRect(
        borderRadius: const BorderRadius.vertical(top: Radius.circular(24)),
        child: BadgesSheet(
          repository: repository,
          username: username,
          currentUserTitle: currentUserTitle,
          onTitleUpdated: onTitleUpdated,
          onStartSiteVerify: onStartSiteVerify,
          onOpenTopic: onOpenTopic,
          onShowToast: onShowToast,
        ),
      ),
    ),
  );
}

Future<void> showBadgeDetailSheet(
  BuildContext context, {
  required GrantedBadge badge,
  required LinuxDoRepository repository,
  String? currentUserTitle,
  VoidCallback? onTitleUpdated,
  void Function(int topicId, {int? postNumber})? onOpenTopic,
  ValueChanged<String>? onShowToast,
}) {
  final size = MediaQuery.sizeOf(context);
  final isWide = shouldUseWideSplitLayout(size.width, size.height);
  if (isWide) {
    return showDialog<void>(
      context: context,
      barrierColor: Colors.black.withValues(alpha: 0.45),
      builder: (ctx) => Dialog(
        backgroundColor: Colors.transparent,
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 460),
          child: ClipRRect(
            borderRadius: BorderRadius.circular(22),
            child: BadgeDetailSheet(
              badge: badge,
              repository: repository,
              currentUserTitle: currentUserTitle,
              onTitleUpdated: onTitleUpdated,
              onOpenTopic: onOpenTopic,
              onShowToast: onShowToast,
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
    builder: (ctx) => ClipRRect(
      borderRadius: const BorderRadius.vertical(top: Radius.circular(24)),
      child: BadgeDetailSheet(
        badge: badge,
        repository: repository,
        currentUserTitle: currentUserTitle,
        onTitleUpdated: onTitleUpdated,
        onOpenTopic: onOpenTopic,
        onShowToast: onShowToast,
      ),
    ),
  );
}

({Color bg, Color fg, Color medal}) _badgeTierPalette(
  BadgeType tier,
  bool isDark,
) {
  switch (tier) {
    case BadgeType.GOLD:
      return isDark
          ? (
              bg: const Color(0xFF3F2E05),
              fg: const Color(0xFFFCD34D),
              medal: const Color(0xFFF59E0B),
            )
          : (
              bg: const Color(0xFFFEF3C7),
              fg: const Color(0xFFB45309),
              medal: const Color(0xFFF59E0B),
            );
    case BadgeType.SILVER:
      return isDark
          ? (
              bg: const Color(0xFF1E293B),
              fg: const Color(0xFFCBD5E1),
              medal: const Color(0xFF94A3B8),
            )
          : (
              bg: const Color(0xFFF1F5F9),
              fg: const Color(0xFF475569),
              medal: const Color(0xFF64748B),
            );
    case BadgeType.BRONZE:
      return isDark
          ? (
              bg: const Color(0xFF3B2110),
              fg: const Color(0xFFFDBA74),
              medal: const Color(0xFFD97706),
            )
          : (
              bg: const Color(0xFFFFF7ED),
              fg: const Color(0xFFC2410C),
              medal: const Color(0xFFD97706),
            );
  }
}

class BadgesSheet extends StatefulWidget {
  final LinuxDoRepository repository;
  final String? username;
  final String? currentUserTitle;
  final VoidCallback? onTitleUpdated;
  final Future<void> Function()? onStartSiteVerify;
  final void Function(int topicId, {int? postNumber})? onOpenTopic;
  final ValueChanged<String>? onShowToast;

  const BadgesSheet({
    super.key,
    required this.repository,
    this.username,
    this.currentUserTitle,
    this.onTitleUpdated,
    this.onStartSiteVerify,
    this.onOpenTopic,
    this.onShowToast,
  });

  @override
  State<BadgesSheet> createState() => _BadgesSheetState();
}

class _BadgesSheetState extends State<BadgesSheet> {
  late BadgesSheetTab _activeTab;
  BadgeTierFilter _tierFilter = BadgeTierFilter.ALL;
  final TextEditingController _searchCtrl = TextEditingController();
  String _query = '';
  List<GrantedBadge> _earned = const <GrantedBadge>[];
  List<GrantedBadge> _allSite = const <GrantedBadge>[];
  String _activeTitle = '';
  bool _loading = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    final hasTargetUser = (widget.username?.trim().isNotEmpty ?? false) ||
        widget.repository.isAuthorized;
    _activeTab =
        hasTargetUser ? BadgesSheetTab.EARNED : BadgesSheetTab.ALL_SITE;
    _activeTitle = widget.currentUserTitle ??
        widget.repository.currentUser?.userTitle ??
        '';
    _load();
  }

  @override
  void dispose() {
    _searchCtrl.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final targetUsername =
          widget.username ?? widget.repository.currentUser?.username ?? '';
      final allBadges = await widget.repository.fetchBadgesOverview(
        targetUsername,
      );
      if (!mounted) return;
      setState(() {
        _earned = allBadges.where((b) => b.isEarned).toList(growable: false);
        _allSite = allBadges;
        if (_earned.isEmpty && _allSite.isNotEmpty && !widget.repository.isAuthorized) {
          _activeTab = BadgesSheetTab.ALL_SITE;
        }
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

  List<GrantedBadge> _filteredBadges() {
    final source =
        _activeTab == BadgesSheetTab.EARNED ? _earned : _allSite;
    final cleanQuery = _query.trim().toLowerCase();
    return source.where((badge) {
      if (_tierFilter == BadgeTierFilter.ALLOW_TITLE && !badge.allowTitle) {
        return false;
      }
      if (_tierFilter.badgeTypeId != null &&
          badge.badgeTypeId != _tierFilter.badgeTypeId) {
        return false;
      }
      if (cleanQuery.isNotEmpty) {
        final matchName = badge.name.toLowerCase().contains(cleanQuery);
        final matchDesc =
            badge.plainDescription.toLowerCase().contains(cleanQuery);
        if (!matchName && !matchDesc) return false;
      }
      return true;
    }).toList(growable: false);
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
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // Header
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 16, 12, 10),
            child: Row(
              children: [
                Icon(LinDoIcons.Trophy, size: 22, color: palette.logoDoColor),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    LinDoMessagesCopy.BADGES_SHEET_TITLE.text,
                    style: TextStyle(
                      fontSize: scope.sp(18),
                      fontWeight: FontWeight.w700,
                      color: palette.inkPrimary,
                    ),
                  ),
                ),
                IconButton(
                  tooltip: LinDoCommonCopy.REFRESH.text,
                  onPressed: _loading ? null : _load,
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
          ),

          // Primary Tabs: 已获徽章 / 全站徽章
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 20),
            child: Row(
              children: [
                _BadgeTopTab(
                  label: _earned.isEmpty
                      ? LinDoMessagesCopy.BADGES_TAB_EARNED.text
                      : LinDoMessagesCopy.BADGES_TAB_EARNED_COUNT
                          .format(_earned.length),
                  selected: _activeTab == BadgesSheetTab.EARNED,
                  onTap: () =>
                      setState(() => _activeTab = BadgesSheetTab.EARNED),
                ),
                const SizedBox(width: 20),
                _BadgeTopTab(
                  label: _allSite.isEmpty
                      ? LinDoMessagesCopy.BADGES_TAB_ALL.text
                      : LinDoMessagesCopy.BADGES_TAB_ALL_COUNT
                          .format(_allSite.length),
                  selected: _activeTab == BadgesSheetTab.ALL_SITE,
                  onTap: () =>
                      setState(() => _activeTab = BadgesSheetTab.ALL_SITE),
                ),
              ],
            ),
          ),
          Divider(height: 1, color: palette.hairline),

          // Search + Tier Filter Strip
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 8),
            child: Column(
              children: [
                Container(
                  height: 40,
                  decoration: BoxDecoration(
                    color: palette.tonalPill,
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: TextField(
                    controller: _searchCtrl,
                    onChanged: (val) => setState(() => _query = val),
                    style: TextStyle(
                      fontSize: scope.sp(13.5),
                      color: palette.inkPrimary,
                    ),
                    decoration: InputDecoration(
                      isDense: true,
                      border: InputBorder.none,
                      contentPadding: const EdgeInsets.symmetric(vertical: 10),
                      prefixIcon: Icon(
                        LinDoIcons.Search,
                        size: 17,
                        color: palette.inkTertiary,
                      ),
                      suffixIcon: _query.isEmpty
                          ? null
                          : IconButton(
                              onPressed: () {
                                _searchCtrl.clear();
                                setState(() => _query = '');
                              },
                              icon: Icon(
                                LinDoIcons.Close,
                                size: 16,
                                color: palette.inkTertiary,
                              ),
                            ),
                      hintText:
                          LinDoMessagesCopy.SEARCH_BADGES_PLACEHOLDER.text,
                      hintStyle: TextStyle(
                        fontSize: scope.sp(13.5),
                        color: palette.inkTertiary,
                      ),
                    ),
                  ),
                ),
                const SizedBox(height: 10),
                SizedBox(
                  height: 32,
                  child: ListView.separated(
                    scrollDirection: Axis.horizontal,
                    itemCount: BadgeTierFilter.values.length,
                    separatorBuilder: (_, _) => const SizedBox(width: 8),
                    itemBuilder: (context, index) {
                      final filter = BadgeTierFilter.values[index];
                      final selected = filter == _tierFilter;
                      return InkWell(
                        borderRadius: BorderRadius.circular(999),
                        onTap: () => setState(() => _tierFilter = filter),
                        child: Container(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 12,
                            vertical: 5,
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
                            filter.labelCopy.text,
                            style: TextStyle(
                              fontSize: scope.sp(12),
                              fontWeight: selected
                                  ? FontWeight.w700
                                  : FontWeight.w500,
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
              ],
            ),
          ),

          Expanded(
            child: _buildBadgeBody(context, palette, scope, isSiteVerify),
          ),
        ],
      ),
    );
  }

  Widget _buildBadgeBody(
    BuildContext context,
    LinDoThemePalette palette,
    LinDoThemeScope scope,
    bool isSiteVerify,
  ) {
    if (_loading) {
      return LinDoStatePanel(
        mood: MascotMood.connecting,
        title: LinDoMessagesCopy.SYNCING_SITE_BADGES.text,
      );
    }

    if (isSiteVerify && widget.onStartSiteVerify != null) {
      return LinDoSiteVerificationCard(
        onStartInAppVerify: () async {
          await widget.onStartSiteVerify!();
          if (mounted) _load();
        },
        onRetry: _load,
      );
    }

    if (_error != null) {
      return LinDoStatePanel(
        mood: MascotMood.surprised,
        title: _activeTab == BadgesSheetTab.EARNED
            ? LinDoMessagesCopy.EARNED_BADGES_LOAD_FAILED.text
            : LinDoMessagesCopy.SITE_BADGES_LOAD_FAILED.text,
        subtitle: _error,
        actionLabel: LinDoCommonCopy.RETRY.text,
        onAction: _load,
      );
    }

    final filtered = _filteredBadges();
    if (filtered.isEmpty) {
      final isEarnedTabEmpty =
          _activeTab == BadgesSheetTab.EARNED && _earned.isEmpty && _query.isEmpty;
      return LinDoStatePanel(
        mood: MascotMood.sleepy,
        title: isEarnedTabEmpty
            ? LinDoMessagesCopy.NO_EARNED_BADGES_YET.text
            : LinDoMessagesCopy.NO_MATCHING_BADGES.text,
        actionLabel: isEarnedTabEmpty
            ? LinDoMessagesCopy.BROWSE_ALL_SITE_BADGES.text
            : null,
        onAction: isEarnedTabEmpty
            ? () => setState(() => _activeTab = BadgesSheetTab.ALL_SITE)
            : null,
      );
    }

    final grouped = <String, List<GrantedBadge>>{};
    for (final badge in filtered) {
      final groupTitle = BadgeGroupingKind.localize(badge.badgeGroupingName);
      grouped.putIfAbsent(groupTitle, () => <GrantedBadge>[]).add(badge);
    }
    final groupEntries = grouped.entries.toList(growable: false);
    final totalEarnedGrants =
        _earned.fold<int>(0, (sum, item) => sum + (item.count > 0 ? item.count : 1));

    return ListView.builder(
      padding: const EdgeInsets.fromLTRB(16, 6, 16, 28),
      itemCount: groupEntries.length +
          (_activeTab == BadgesSheetTab.EARNED && _earned.isNotEmpty ? 1 : 0),
      itemBuilder: (context, index) {
        final hasHeader =
            _activeTab == BadgesSheetTab.EARNED && _earned.isNotEmpty;
        if (hasHeader && index == 0) {
          return Padding(
            padding: const EdgeInsets.only(bottom: 14),
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
              decoration: BoxDecoration(
                color: palette.groupedCanvas,
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: palette.hairline),
              ),
              child: Text(
                LinDoMessagesCopy.EARNED_BADGES_SUMMARY
                    .format([_earned.length, totalEarnedGrants]),
                style: TextStyle(
                  fontSize: scope.sp(12.5),
                  fontWeight: FontWeight.w600,
                  color: palette.inkSecondary,
                ),
              ),
            ),
          );
        }

        final group = groupEntries[hasHeader ? index - 1 : index];
        return RepaintBoundary(
          child: Padding(
            padding: const EdgeInsets.only(bottom: 18),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Padding(
                  padding: const EdgeInsets.only(left: 4, bottom: 8),
                  child: Text(
                    '${group.key} (${group.value.length})',
                    style: TextStyle(
                      fontSize: scope.sp(13),
                      fontWeight: FontWeight.w700,
                      color: palette.inkSecondary,
                    ),
                  ),
                ),
                ...group.value.map(
                  (badge) => Padding(
                    padding: const EdgeInsets.only(bottom: 8),
                    child: _BadgeRowCard(
                      badge: badge,
                      isCurrentTitle: _activeTitle.trim().isNotEmpty &&
                          _activeTitle.trim() == badge.name.trim(),
                      onTap: () async {
                        await showBadgeDetailSheet(
                          context,
                          badge: badge,
                          repository: widget.repository,
                          currentUserTitle: _activeTitle,
                          onTitleUpdated: () {
                            setState(() {
                              _activeTitle =
                                  widget.repository.currentUser?.userTitle ??
                                      badge.name;
                            });
                            widget.onTitleUpdated?.call();
                          },
                          onOpenTopic: widget.onOpenTopic,
                          onShowToast: widget.onShowToast,
                        );
                      },
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

class _BadgeTopTab extends StatelessWidget {
  final String label;
  final bool selected;
  final VoidCallback onTap;

  const _BadgeTopTab({
    required this.label,
    required this.selected,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    final scope = LinDoThemeScope.of(context);
    return InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.only(top: 4),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              label,
              style: TextStyle(
                fontSize: scope.sp(14),
                fontWeight: selected ? FontWeight.w700 : FontWeight.w500,
                color: selected ? palette.inkPrimary : palette.inkSecondary,
              ),
            ),
            const SizedBox(height: 8),
            Container(
              height: 2.5,
              width: 40,
              decoration: BoxDecoration(
                color:
                    selected ? palette.tabIndicatorColor : Colors.transparent,
                borderRadius: BorderRadius.circular(999),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _BadgeRowCard extends StatelessWidget {
  final GrantedBadge badge;
  final bool isCurrentTitle;
  final VoidCallback onTap;

  const _BadgeRowCard({
    required this.badge,
    required this.isCurrentTitle,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    final scope = LinDoThemeScope.of(context);
    final tierColors = _badgeTierPalette(badge.badgeType, palette.isDark);
    final imgUrl = (badge.imageUrl ?? '').trim();

    return Material(
      color: palette.subtleSurface,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(14),
        side: BorderSide(color: palette.hairline),
      ),
      child: InkWell(
        borderRadius: BorderRadius.circular(14),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Row(
            children: [
              Container(
                width: 42,
                height: 42,
                decoration: BoxDecoration(
                  color: tierColors.bg,
                  borderRadius: BorderRadius.circular(12),
                ),
                child: imgUrl.isNotEmpty
                    ? Padding(
                        padding: const EdgeInsets.all(6),
                        child: LinDoNetworkImage(
                          url: resolveDiscourseUrl(imgUrl),
                          width: 30,
                          height: 30,
                          fit: BoxFit.contain,
                        ),
                      )
                    : Icon(
                        LinDoIcons.Trophy,
                        size: 21,
                        color: tierColors.medal,
                      ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Flexible(
                          child: Text(
                            badge.name,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              fontSize: scope.sp(14),
                              fontWeight: FontWeight.w700,
                              color: palette.inkPrimary,
                            ),
                          ),
                        ),
                        if (badge.count > 1) ...[
                          const SizedBox(width: 6),
                          Text(
                            LinDoCommonCopy.COUNT_MULTIPLIER
                                .format(badge.count),
                            style: TextStyle(
                              fontSize: scope.sp(11.5),
                              fontWeight: FontWeight.w700,
                              color: tierColors.fg,
                            ),
                          ),
                        ],
                        if (isCurrentTitle) ...[
                          const SizedBox(width: 6),
                          Container(
                            padding: const EdgeInsets.symmetric(
                              horizontal: 6,
                              vertical: 2,
                            ),
                            decoration: BoxDecoration(
                              color: palette.activeChipBg,
                              borderRadius: BorderRadius.circular(6),
                            ),
                            child: Text(
                              LinDoMessagesCopy.BADGE_CURRENT_TITLE.text,
                              style: TextStyle(
                                fontSize: scope.sp(10.5),
                                fontWeight: FontWeight.w700,
                                color: palette.activeChipText,
                              ),
                            ),
                          ),
                        ] else if (badge.allowTitle) ...[
                          const SizedBox(width: 6),
                          Container(
                            padding: const EdgeInsets.symmetric(
                              horizontal: 6,
                              vertical: 2,
                            ),
                            decoration: BoxDecoration(
                              color: palette.tonalPill,
                              borderRadius: BorderRadius.circular(6),
                            ),
                            child: Text(
                              LinDoMessagesCopy.BADGE_CAN_BE_TITLE.text,
                              style: TextStyle(
                                fontSize: scope.sp(10.5),
                                fontWeight: FontWeight.w600,
                                color: palette.inkSecondary,
                              ),
                            ),
                          ),
                        ],
                      ],
                    ),
                    const SizedBox(height: 3),
                    Text(
                      badge.plainDescription,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        fontSize: scope.sp(12.5),
                        color: palette.inkSecondary,
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 8),
              if (badge.isEarned)
                Icon(
                  LinDoIcons.CheckCircle,
                  size: 17,
                  color: tierColors.medal,
                )
              else
                Icon(
                  LinDoIcons.ChevronRight,
                  size: 18,
                  color: palette.inkTertiary,
                ),
            ],
          ),
        ),
      ),
    );
  }
}

class BadgeDetailSheet extends StatefulWidget {
  final GrantedBadge badge;
  final LinuxDoRepository repository;
  final String? currentUserTitle;
  final VoidCallback? onTitleUpdated;
  final void Function(int topicId, {int? postNumber})? onOpenTopic;
  final ValueChanged<String>? onShowToast;

  const BadgeDetailSheet({
    super.key,
    required this.badge,
    required this.repository,
    this.currentUserTitle,
    this.onTitleUpdated,
    this.onOpenTopic,
    this.onShowToast,
  });

  @override
  State<BadgeDetailSheet> createState() => _BadgeDetailSheetState();
}

class _BadgeDetailSheetState extends State<BadgeDetailSheet> {
  bool _settingTitle = false;

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

  Future<void> _setAsTitle() async {
    if (_settingTitle) return;
    setState(() => _settingTitle = true);
    try {
      await widget.repository.updateUserBadgeTitle(title: widget.badge.name);
      _toast(LinDoMessagesCopy.BADGE_TITLE_SET_SUCCESS.text);
      widget.onTitleUpdated?.call();
      if (mounted) Navigator.of(context).maybePop();
    } catch (_) {
      _toast(LinDoCommonCopy.SET_FAILED_RETRY.text);
    } finally {
      if (mounted) setState(() => _settingTitle = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    final scope = LinDoThemeScope.of(context);
    final badge = widget.badge;
    final tierColors = _badgeTierPalette(badge.badgeType, palette.isDark);
    final longText = badge.plainLongDescription.trim();
    final descText = badge.plainDescription.trim();
    final canSetTitle =
        widget.repository.isAuthorized && badge.isEarned && badge.allowTitle;
    final isAlreadyTitle =
        (widget.currentUserTitle ?? '').trim() == badge.name.trim();

    return Material(
      color: palette.cardSurface,
      child: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(22, 18, 22, 24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text(
                      LinDoMessagesCopy.BADGE_DETAIL_TITLE.text,
                      style: TextStyle(
                        fontSize: scope.sp(16),
                        fontWeight: FontWeight.w700,
                        color: palette.inkPrimary,
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
              const SizedBox(height: 8),
              Center(
                child: Container(
                  width: 68,
                  height: 68,
                  decoration: BoxDecoration(
                    color: tierColors.bg,
                    borderRadius: BorderRadius.circular(20),
                  ),
                  child: (badge.imageUrl ?? '').trim().isNotEmpty
                      ? Padding(
                          padding: const EdgeInsets.all(12),
                          child: LinDoNetworkImage(
                            url: resolveDiscourseUrl(badge.imageUrl!),
                            width: 44,
                            height: 44,
                            fit: BoxFit.contain,
                          ),
                        )
                      : Icon(
                          LinDoIcons.Trophy,
                          size: 34,
                          color: tierColors.medal,
                        ),
                ),
              ),
              const SizedBox(height: 14),
              Text(
                badge.name,
                textAlign: TextAlign.center,
                style: TextStyle(
                  fontSize: scope.sp(19),
                  fontWeight: FontWeight.w800,
                  color: palette.inkPrimary,
                ),
              ),
              const SizedBox(height: 6),
              Wrap(
                alignment: WrapAlignment.center,
                spacing: 8,
                runSpacing: 6,
                children: [
                  UniversalBadgeChip(badge: badge, compact: true),
                  if (badge.grantCount > 0)
                    _BadgeMetaPill(
                      label: LinDoMessagesCopy.BADGE_GLOBAL_GRANTS_COUNT
                          .format(formatCompactCount(badge.grantCount)),
                      backgroundColor: palette.tonalPill,
                      foregroundColor: palette.inkSecondary,
                    ),
                  if (badge.isEarned)
                    _BadgeMetaPill(
                      label: badge.grantedAtText.trim().isNotEmpty
                          ? LinDoMessagesCopy.BADGE_GRANTED_AT
                              .format(badge.grantedAtText)
                          : LinDoMessagesCopy.BADGE_EARNED_SIMPLE.text,
                      backgroundColor: palette.successContainer,
                      foregroundColor: palette.onSuccessContainer,
                    ),
                ],
              ),
              const SizedBox(height: 16),
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
                      descText.isEmpty
                          ? LinDoMessagesCopy.EMPTY_BADGE_DESCRIPTION.text
                          : descText,
                      style: TextStyle(
                        fontSize: scope.sp(13.5),
                        height: 1.5,
                        color: palette.inkPrimary,
                      ),
                    ),
                    if (longText.isNotEmpty && longText != descText) ...[
                      const SizedBox(height: 8),
                      Text(
                        longText,
                        style: TextStyle(
                          fontSize: scope.sp(12.5),
                          height: 1.5,
                          color: palette.inkSecondary,
                        ),
                      ),
                    ],
                  ],
                ),
              ),
              if (badge.topicId > 0 && widget.onOpenTopic != null) ...[
                const SizedBox(height: 12),
                OutlinedButton.icon(
                  onPressed: () {
                    Navigator.of(context).pop();
                    widget.onOpenTopic!(
                      badge.topicId,
                      postNumber:
                          badge.postNumber > 0 ? badge.postNumber : null,
                    );
                  },
                  icon: const Icon(LinDoIcons.ExternalLink, size: 15),
                  label: Text(
                    badge.topicTitle.trim().isNotEmpty
                        ? '${LinDoMessagesCopy.BADGE_FROM_THIS_POST.text} · ${badge.topicTitle}'
                        : LinDoMessagesCopy.BADGE_FROM_THIS_POST.text,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
              ],
              if (canSetTitle) ...[
                const SizedBox(height: 12),
                SizedBox(
                  height: 44,
                  child: FilledButton.icon(
                    style: FilledButton.styleFrom(
                      backgroundColor: palette.publishBtnBg,
                      foregroundColor: palette.publishBtnContent,
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(12),
                      ),
                    ),
                    onPressed:
                        (_settingTitle || isAlreadyTitle) ? null : _setAsTitle,
                    icon: Icon(
                      isAlreadyTitle ? LinDoIcons.Check : LinDoIcons.Crown,
                      size: 16,
                    ),
                    label: Text(
                      isAlreadyTitle
                          ? LinDoMessagesCopy.BADGE_CURRENT_TITLE.text
                          : (_settingTitle
                              ? LinDoCommonCopy.SETTING_ELLIPSIS.text
                              : LinDoMessagesCopy.SET_AS_TITLE_ACTION.text),
                      style: TextStyle(
                        fontSize: scope.sp(14),
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

class _BadgeMetaPill extends StatelessWidget {
  final String label;
  final Color backgroundColor;
  final Color foregroundColor;

  const _BadgeMetaPill({
    required this.label,
    required this.backgroundColor,
    required this.foregroundColor,
  });

  @override
  Widget build(BuildContext context) {
    final scope = LinDoThemeScope.of(context);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3.5),
      decoration: BoxDecoration(
        color: backgroundColor,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Text(
        label,
        style: TextStyle(
          fontSize: scope.sp(11),
          fontWeight: FontWeight.w600,
          color: foregroundColor,
        ),
      ),
    );
  }
}

