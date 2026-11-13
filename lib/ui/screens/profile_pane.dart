import 'package:flutter/material.dart';

import '../../core/copy/lindo_copy.dart';
import '../../core/diagnostics/lindo_diagnostic_logger.dart';
import '../../core/models/lindo_models.dart';
import '../../core/network/linux_do_repository.dart';
import '../../core/site/linux_do_site_rules.dart';
import '../../core/storage/lindo_local_store.dart';
import '../components/lindo_brand_widgets.dart';
import '../components/lindo_icons.dart';
import '../components/lindo_mascot.dart';
import '../components/smart_avatar_and_badges.dart';
import '../sheets/app_icon_page.dart';
import '../sheets/badges_sheet.dart';
import '../sheets/connect_sheet.dart';
import '../sheets/debug_diagnostics_sheet.dart';
import '../sheets/user_collections_sheets.dart';
import '../theme/lindo_theme.dart';

class ProfilePane extends StatefulWidget {
  final LinuxDoRepository repository;
  final LinDoLocalStore store;
  final void Function(int topicId, {int? postNumber, String? title})
      onOpenTopic;
  final Future<void> Function() onStartWebLogin;
  final Future<void> Function() onStartSiteVerify;
  final ValueChanged<String>? onShowToast;

  const ProfilePane({
    super.key,
    required this.repository,
    required this.store,
    required this.onOpenTopic,
    required this.onStartWebLogin,
    required this.onStartSiteVerify,
    this.onShowToast,
  });

  @override
  State<ProfilePane> createState() => _ProfilePaneState();
}

class _ProfilePaneState extends State<ProfilePane> {
  UserFullProfileSummary? _summary;
  bool _syncing = false;
  String? _loadedUsername;

  @override
  void initState() {
    super.initState();
    widget.repository.addListener(_onRepositoryChanged);
    _maybeLoadProfile();
  }

  @override
  void didUpdateWidget(covariant ProfilePane oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.repository != widget.repository) {
      oldWidget.repository.removeListener(_onRepositoryChanged);
      widget.repository.addListener(_onRepositoryChanged);
    }
  }

  @override
  void dispose() {
    widget.repository.removeListener(_onRepositoryChanged);
    super.dispose();
  }

  void _onRepositoryChanged() {
    if (!mounted) return;
    final currentUsername = widget.repository.currentUser?.username;
    if (currentUsername != _loadedUsername) {
      _maybeLoadProfile();
      return;
    }
    setState(() {});
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

  Future<void> _maybeLoadProfile({bool force = false}) async {
    final user = widget.repository.currentUser;
    if (!widget.repository.isAuthorized || user == null) {
      setState(() {
        _summary = null;
        _loadedUsername = null;
        _syncing = false;
      });
      return;
    }
    if (!force && _loadedUsername == user.username && _summary != null) {
      return;
    }

    setState(() => _syncing = true);
    try {
      final res = await widget.repository.fetchUserSummary(user.username);
      if (!mounted) return;
      setState(() {
        _summary = res;
        _loadedUsername = user.username;
        _syncing = false;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _loadedUsername = user.username;
        _syncing = false;
      });
    }
  }

  String _formatDuration(int seconds) {
    if (seconds <= 0) return LinDoCommonCopy.ZERO_MINUTES_TEXT.text;
    final mins = seconds ~/ 60;
    if (mins < 60) {
      return LinDoCommonCopy.MINUTES_TEXT.format(mins <= 0 ? 1 : mins);
    }
    final hours = mins ~/ 60;
    if (hours < 48) {
      return LinDoCommonCopy.HOURS_TEXT.format(hours);
    }
    return LinDoCommonCopy.DURATION_DAYS_HOURS
        .format([hours ~/ 24, hours % 24]);
  }

  Future<void> _confirmLogout() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) {
        final palette = LinDoThemeScope.paletteOf(ctx);
        return AlertDialog(
          backgroundColor: palette.cardSurface,
          shape:
              RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
          title: Text(LinDoAuthCopy.CONFIRM_LOGOUT_TITLE.text),
          content: const Text('退出登录后将清理当前账号会话，本地阅读偏好与历史将保留。'),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(ctx).pop(false),
              child: Text(LinDoCommonCopy.CANCEL.text),
            ),
            FilledButton(
              style: FilledButton.styleFrom(
                backgroundColor: const Color(0xFFDC2626),
                foregroundColor: Colors.white,
              ),
              onPressed: () => Navigator.of(ctx).pop(true),
              child: Text(LinDoAuthCopy.CONFIRM_LOGOUT_ACTION.text),
            ),
          ],
        );
      },
    );
    if (confirmed == true) {
      await widget.repository.logout();
      _toast(LinDoCommonCopy.COMPLETED.text);
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    final scope = LinDoThemeScope.of(context);

    return ListenableBuilder(
      listenable: Listenable.merge([widget.store, widget.repository]),
      builder: (context, _) {
        final prefs = widget.store.preferences;
        final isAuthorized = widget.repository.isAuthorized;
        final user = widget.repository.currentUser;
        final versionText = 'v$kLinDoAppVersionName ($kLinDoAppBuildNumber)';

        return ColoredBox(
          color: palette.groupedCanvas,
          child: RefreshIndicator(
            color: palette.logoDoColor,
            onRefresh: () => _maybeLoadProfile(force: true),
            child: CustomScrollView(
              physics: const AlwaysScrollableScrollPhysics(),
              slivers: [
                SliverToBoxAdapter(
                  child: Center(
                    child: ConstrainedBox(
                      constraints: const BoxConstraints(maxWidth: 760),
                      child: Padding(
                        padding: const EdgeInsets.fromLTRB(18, 14, 18, 104),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.stretch,
                          children: [
                            // Top Bar
                            Row(
                              children: [
                                Icon(
                                  LinDoIcons.UserSelected,
                                  size: 21,
                                  color: palette.logoDoColor,
                                ),
                                const SizedBox(width: 9),
                                Expanded(
                                  child: Text(
                                    LinDoFeedCopy.TAB_PROFILE.text,
                                    style: TextStyle(
                                      fontSize: scope.sp(20.5),
                                      fontWeight: FontWeight.w800,
                                      color: palette.inkPrimary,
                                    ),
                                  ),
                                ),
                                if (isAuthorized)
                                  IconButton(
                                    tooltip:
                                        LinDoMessagesCopy.SYNC_PROFILE_DESC.text,
                                    onPressed: _syncing
                                        ? null
                                        : () => _maybeLoadProfile(force: true),
                                    icon: Icon(
                                      LinDoIcons.Refresh,
                                      size: 19,
                                      color: palette.inkSecondary,
                                    ),
                                  ),
                                IconButton(
                                  tooltip: palette.isDark
                                      ? LinDoMessagesCopy
                                          .SWITCH_LIGHT_SHORT_DESC.text
                                      : LinDoMessagesCopy
                                          .SWITCH_DARK_SHORT_DESC.text,
                                  onPressed: () {
                                    widget.store.updatePreferences(
                                      prefs.copyWith(
                                        isDarkOverride: !palette.isDark,
                                      ),
                                    );
                                  },
                                  icon: Icon(
                                    palette.isDark
                                        ? LinDoIcons.Sun
                                        : LinDoIcons.Moon,
                                    size: 19,
                                    color: palette.inkSecondary,
                                  ),
                                ),
                              ],
                            ),
                            const SizedBox(height: 12),

                            // Hero Card (Authenticated vs Warm Guest Card — ZERO Cookie UI!)
                            if (isAuthorized && user != null)
                              _buildAuthenticatedHeroCard(
                                context,
                                palette,
                                scope,
                                user,
                              )
                            else
                              _buildGuestHeroCard(context, palette, scope),

                            const SizedBox(height: 14),

                            // Quick 4-Action Strip: 收藏 / 历史 / 草稿 / 活动
                            Row(
                              children: [
                                Expanded(
                                  child: _QuickActionTile(
                                    icon: LinDoIcons.BookmarkFilled,
                                    label:
                                        LinDoMessagesCopy.QUICK_BOOKMARKS.text,
                                    onTap: () {
                                      if (!isAuthorized) {
                                        showGuestLoginGuideSheet(
                                          context,
                                          scenario: GuestGateScenario
                                              .BOOKMARKS_CENTER,
                                          onStartWebLogin:
                                              widget.onStartWebLogin,
                                        );
                                        return;
                                      }
                                      showBookmarksSheet(
                                        context,
                                        repository: widget.repository,
                                        onOpenTopic: widget.onOpenTopic,
                                        onStartSiteVerify:
                                            widget.onStartSiteVerify,
                                        onShowToast: _toast,
                                      );
                                    },
                                  ),
                                ),
                                const SizedBox(width: 10),
                                Expanded(
                                  child: _QuickActionTile(
                                    icon: LinDoIcons.History,
                                    label: LinDoMessagesCopy.QUICK_HISTORY.text,
                                    badgeCount: widget.store.readHistory.length,
                                    onTap: () => showReadHistorySheet(
                                      context,
                                      store: widget.store,
                                      onOpenTopic: widget.onOpenTopic,
                                      onShowToast: _toast,
                                    ),
                                  ),
                                ),
                                const SizedBox(width: 10),
                                Expanded(
                                  child: _QuickActionTile(
                                    icon: LinDoIcons.Drafts,
                                    label: LinDoMessagesCopy.QUICK_DRAFTS.text,
                                    badgeCount: widget.store.localDrafts.length,
                                    onTap: () => showDraftsSheet(
                                      context,
                                      repository: widget.repository,
                                      store: widget.store,
                                      onStartSiteVerify:
                                          widget.onStartSiteVerify,
                                      onOpenTopic: widget.onOpenTopic,
                                      onShowToast: _toast,
                                    ),
                                  ),
                                ),
                                const SizedBox(width: 10),
                                Expanded(
                                  child: _QuickActionTile(
                                    icon: LinDoIcons.Sparkles,
                                    label:
                                        LinDoMessagesCopy.QUICK_ACTIVITY.text,
                                    onTap: () {
                                      if (!isAuthorized) {
                                        showGuestLoginGuideSheet(
                                          context,
                                          scenario:
                                              GuestGateScenario.ACTIVITIES,
                                          onStartWebLogin:
                                              widget.onStartWebLogin,
                                        );
                                        return;
                                      }
                                      showUserActivitiesSheet(
                                        context,
                                        repository: widget.repository,
                                        onOpenTopic: widget.onOpenTopic,
                                        onStartSiteVerify:
                                            widget.onStartSiteVerify,
                                      );
                                    },
                                  ),
                                ),
                              ],
                            ),

                            const SizedBox(height: 20),

                            // Section 1: 社区与账号
                            _GroupLabel(
                              LinDoMessagesCopy
                                  .SECTION_COMMUNITY_AND_ACCOUNT.text,
                            ),
                            const SizedBox(height: 8),
                            _SettingsGroupCard(
                              children: [
                                _SettingsRow(
                                  icon: LinDoIcons.ShieldCheck,
                                  title: LinDoMessagesCopy
                                      .ROW_CONNECT_GROWTH.text,
                                  trailingText: isAuthorized ? 'Connect' : null,
                                  onTap: () {
                                    if (!isAuthorized) {
                                      showGuestLoginGuideSheet(
                                        context,
                                        scenario: GuestGateScenario.CONNECT,
                                        onStartWebLogin: widget.onStartWebLogin,
                                      );
                                      return;
                                    }
                                    showConnectSheet(
                                      context,
                                      repository: widget.repository,
                                      onStartSiteVerify:
                                          widget.onStartSiteVerify,
                                    );
                                  },
                                ),
                                _SettingsRow(
                                  icon: LinDoIcons.Trophy,
                                  title: LinDoMessagesCopy.ROW_BADGE_WALL.text,
                                  trailingText: (_summary?.badgeCount ?? 0) > 0
                                      ? LinDoMessagesCopy.ALL_BADGES_COUNT
                                          .format(_summary!.badgeCount)
                                      : null,
                                  onTap: () => showBadgesSheet(
                                    context,
                                    repository: widget.repository,
                                    username: user?.username,
                                    currentUserTitle:
                                        _summary?.title ?? user?.userTitle,
                                    onTitleUpdated: () =>
                                        _maybeLoadProfile(force: true),
                                    onStartSiteVerify:
                                        widget.onStartSiteVerify,
                                    onOpenTopic: (topicId, {postNumber}) =>
                                        widget.onOpenTopic(
                                      topicId,
                                      postNumber: postNumber,
                                    ),
                                    onShowToast: _toast,
                                  ),
                                ),
                                _SettingsRow(
                                  icon: LinDoIcons.UserSelected,
                                  title:
                                      LinDoMessagesCopy.ROW_FOLLOW_NETWORK.text,
                                  trailingText: _summary != null &&
                                          (_summary!.followingCount > 0 ||
                                              _summary!.followersCount > 0)
                                      ? '${_summary!.followingCount} / ${_summary!.followersCount}'
                                      : null,
                                  onTap: () {
                                    if (!isAuthorized) {
                                      showGuestLoginGuideSheet(
                                        context,
                                        scenario: GuestGateScenario.FOLLOW_LIST,
                                        onStartWebLogin: widget.onStartWebLogin,
                                      );
                                      return;
                                    }
                                    showFollowNetworkSheet(
                                      context,
                                      repository: widget.repository,
                                      store: widget.store,
                                      onOpenTopic: widget.onOpenTopic,
                                      onStartSiteVerify:
                                          widget.onStartSiteVerify,
                                      onShowToast: _toast,
                                    );
                                  },
                                ),
                                _SettingsRow(
                                  icon: LinDoIcons.Crown,
                                  title: LinDoMessagesCopy.ROW_LEADERBOARD.text,
                                  showDivider: false,
                                  onTap: () => showLeaderboardSheet(
                                    context,
                                    repository: widget.repository,
                                    store: widget.store,
                                    onOpenTopic: widget.onOpenTopic,
                                    onStartSiteVerify:
                                        widget.onStartSiteVerify,
                                    onShowToast: _toast,
                                  ),
                                ),
                              ],
                            ),

                            const SizedBox(height: 20),

                            // Section 2: 外观与设置
                            _GroupLabel(
                              LinDoMessagesCopy.SECTION_APPEARANCE.text,
                            ),
                            const SizedBox(height: 8),
                            _SettingsGroupCard(
                              children: [
                                _SettingsRow(
                                  icon: LinDoIcons.Sliders,
                                  title: LinDoMessagesCopy
                                      .ROW_ACCOUNT_PREFERENCES.text,
                                  trailingText: prefs.brandTheme.label,
                                  onTap: () => showPreferencesSheet(
                                    context,
                                    store: widget.store,
                                    repository: widget.repository,
                                    onShowToast: _toast,
                                  ),
                                ),
                                _SettingsRow(
                                  icon: LinDoIcons.Palette,
                                  title: LinDoMessagesCopy.ROW_APP_ICON.text,
                                  customTrailing: Row(
                                    mainAxisSize: MainAxisSize.min,
                                    children: [
                                      LinDoGlassAppIcon(
                                        icon: prefs.appIcon,
                                        size: 24,
                                      ),
                                      const SizedBox(width: 6),
                                      Icon(
                                        LinDoIcons.ChevronRight,
                                        size: 18,
                                        color: palette.inkTertiary,
                                      ),
                                    ],
                                  ),
                                  onTap: () => showAppIconSelectionPage(
                                    context,
                                    store: widget.store,
                                    onShowToast: _toast,
                                  ),
                                ),
                                _SettingsRow(
                                  icon: LinDoIcons.Trash,
                                  title: LinDoMessagesCopy
                                      .ROW_CLEAR_LOCAL_CACHE.text,
                                  onTap: () => showClearCacheOptionsDialog(
                                    context,
                                    store: widget.store,
                                    onShowToast: _toast,
                                  ),
                                ),
                                _SettingsRow(
                                  icon: LinDoIcons.InfoCircle,
                                  title:
                                      LinDoMessagesCopy.ROW_ABOUT_COMMUNITY.text,
                                  trailingText: versionText,
                                  showDivider:
                                      prefs.debugModeUnlocked || isAuthorized,
                                  onTap: () => showAboutSheet(
                                    context,
                                    repository: widget.repository,
                                    store: widget.store,
                                    onOpenTopic: widget.onOpenTopic,
                                    onStartSiteVerify:
                                        widget.onStartSiteVerify,
                                    onShowToast: _toast,
                                  ),
                                ),
                                if (prefs.debugModeUnlocked)
                                  _SettingsRow(
                                    icon: LinDoIcons.Bug,
                                    title: '调试与诊断日志',
                                    trailingText:
                                        '${LinDoDiagnosticLogger.instance.entries.length} 条',
                                    showDivider: isAuthorized,
                                    onTap: () => showDebugDiagnosticsSheet(
                                      context,
                                      store: widget.store,
                                      repository: widget.repository,
                                      onShowToast: _toast,
                                    ),
                                  ),
                                if (isAuthorized)
                                  _SettingsRow(
                                    icon: LinDoIcons.LogOut,
                                    iconColor: const Color(0xFFDC2626),
                                    title: LinDoAuthCopy.LOGOUT_CURRENT_ACCOUNT.text,
                                    titleColor: const Color(0xFFDC2626),
                                    showDivider: false,
                                    onTap: _confirmLogout,
                                  ),
                              ],
                            ),

                            const SizedBox(height: 22),

                            // Footer Version Pill (5-Tap Unlock Trigger)
                            Center(
                              child: DebugVersionUnlockTrigger(
                                store: widget.store,
                                onShowToast: _toast,
                                child: Padding(
                                  padding: const EdgeInsets.symmetric(
                                    horizontal: 12,
                                    vertical: 6,
                                  ),
                                  child: Text(
                                    '${LinDoCommonCopy.APP_NAME.text} $versionText · ${LinDoCommonCopy.SLOGAN_DOT.text}',
                                    style: TextStyle(
                                      fontSize: scope.sp(11.5),
                                      color: palette.inkTertiary,
                                    ),
                                  ),
                                ),
                              ),
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

  Widget _buildAuthenticatedHeroCard(
    BuildContext context,
    LinDoThemePalette palette,
    LinDoThemeScope scope,
    AuthenticatedUserProfile user,
  ) {
    final s = _summary;
    final displayName = (s?.displayName.trim().isNotEmpty ?? false)
        ? s!.displayName
        : (user.displayName.trim().isNotEmpty
            ? user.displayName
            : user.username);
    final activeTitle = (s?.title.trim().isNotEmpty ?? false)
        ? s!.title
        : user.userTitle;
    final trustLv = s?.trustLevel ?? user.trustLevel;
    final bio = s != null ? decodeDiscourseHtmlSnippet(s.bioExcerpt) : '';

    return Container(
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: palette.cardSurface,
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: palette.hairline),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              SmartAvatar(
                avatarUrl: s?.avatarTemplate.isNotEmpty == true
                    ? s!.avatarTemplate
                    : user.avatarTemplate,
                fallbackLabel: user.username,
                size: 62,
                flair: s?.flair ?? const FlairInfo(),
                trustLevel: trustLv,
                isAdmin: s?.admin ?? false,
                isModerator: s?.moderator ?? false,
                animatedAvatar: s?.animatedAvatar ?? user.animatedAvatar,
                primaryGroupName: s?.primaryGroupName,
              ),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      displayName,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        fontSize: scope.sp(18.5),
                        fontWeight: FontWeight.w800,
                        color: palette.inkPrimary,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      '@${user.username} · ${DiscourseTrustLevel.displayLabel(trustLv)}',
                      style: TextStyle(
                        fontSize: scope.sp(12.5),
                        color: palette.inkSecondary,
                      ),
                    ),
                    if (activeTitle.trim().isNotEmpty) ...[
                      const SizedBox(height: 6),
                      UserTitleTag(
                        title: activeTitle,
                        primaryGroupName: s?.primaryGroupName,
                        trustLevel: trustLv,
                        isAdmin: s?.admin ?? false,
                        isModerator: s?.moderator ?? false,
                      ),
                    ],
                  ],
                ),
              ),
              OutlinedButton.icon(
                style: OutlinedButton.styleFrom(
                  visualDensity: VisualDensity.compact,
                  side: BorderSide(color: palette.hairline),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(10),
                  ),
                ),
                onPressed: () async {
                  final updated = await showEditProfileSheet(
                    context,
                    repository: widget.repository,
                    onShowToast: _toast,
                  );
                  if (updated == true) {
                    _maybeLoadProfile(force: true);
                  }
                },
                icon: Icon(
                  LinDoIcons.Edit,
                  size: 14,
                  color: palette.inkPrimary,
                ),
                label: Text(
                  LinDoMessagesCopy.EDIT_PROFILE_SHORT.text,
                  style: TextStyle(
                    fontSize: scope.sp(12),
                    fontWeight: FontWeight.w600,
                    color: palette.inkPrimary,
                  ),
                ),
              ),
            ],
          ),
          if (bio.isNotEmpty) ...[
            const SizedBox(height: 12),
            Text(
              bio,
              maxLines: 3,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                fontSize: scope.sp(13),
                height: 1.45,
                color: palette.inkSecondary,
              ),
            ),
          ],
          const SizedBox(height: 16),
          Container(
            padding: const EdgeInsets.symmetric(vertical: 12, horizontal: 8),
            decoration: BoxDecoration(
              color: palette.groupedCanvas,
              borderRadius: BorderRadius.circular(14),
              border: Border.all(color: palette.hairline),
            ),
            child: Row(
              children: [
                Expanded(
                  child: _ProfileMetricCell(
                    label: LinDoMessagesCopy.STAT_DAYS_VISITED.text,
                    value: s == null
                        ? '—'
                        : formatCompactCount(s.daysVisited),
                  ),
                ),
                Expanded(
                  child: _ProfileMetricCell(
                    label: LinDoMessagesCopy.STAT_LIKES_RECEIVED.text,
                    value: s == null
                        ? '—'
                        : formatCompactCount(s.likesReceived),
                  ),
                ),
                Expanded(
                  child: _ProfileMetricCell(
                    label: LinDoMessagesCopy.STAT_TOPICS_CREATED.text,
                    value: s == null
                        ? '—'
                        : formatCompactCount(s.topicCount),
                  ),
                ),
                Expanded(
                  child: _ProfileMetricCell(
                    label: LinDoMessagesCopy.STAT_POSTS_CREATED.text,
                    value:
                        s == null ? '—' : formatCompactCount(s.postCount),
                  ),
                ),
                Expanded(
                  child: _ProfileMetricCell(
                    label: LinDoMessagesCopy.STAT_READ_TIME.text,
                    value: s == null
                        ? '—'
                        : _formatDuration(s.timeReadSeconds),
                  ),
                ),
              ],
            ),
          ),
          if (s != null && s.badges.isNotEmpty) ...[
            const SizedBox(height: 12),
            SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              child: Row(
                children: s.badges.take(6).map((badge) {
                  return Padding(
                    padding: const EdgeInsets.only(right: 6),
                    child: UniversalBadgeChip(
                      badge: badge,
                      compact: true,
                      onTap: () => showBadgeDetailSheet(
                        context,
                        badge: badge,
                        repository: widget.repository,
                        currentUserTitle: s.title,
                        onTitleUpdated: () => _maybeLoadProfile(force: true),
                        onOpenTopic: (topicId, {postNumber}) =>
                            widget.onOpenTopic(
                          topicId,
                          postNumber: postNumber,
                        ),
                        onShowToast: _toast,
                      ),
                    ),
                  );
                }).toList(),
              ),
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildGuestHeroCard(
    BuildContext context,
    LinDoThemePalette palette,
    LinDoThemeScope scope,
  ) {
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: palette.cardSurface,
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: palette.hairline),
      ),
      child: Column(
        children: [
          Row(
            children: [
              const LinDoMascot(
                mood: MascotMood.happy,
                size: 58,
                animate: true,
              ),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      LinDoMessagesCopy.GUEST_DISPLAY_NAME.text,
                      style: TextStyle(
                        fontSize: scope.sp(18),
                        fontWeight: FontWeight.w800,
                        color: palette.inkPrimary,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      LinDoMessagesCopy.GUEST_SYNC_HINT.text,
                      style: TextStyle(
                        fontSize: scope.sp(13),
                        color: palette.inkSecondary,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: 16),
          SizedBox(
            width: double.infinity,
            height: 46,
            child: FilledButton(
              style: FilledButton.styleFrom(
                backgroundColor: const Color(0xFFF59E0B),
                foregroundColor: const Color(0xFF18181B),
                elevation: 0,
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(14),
                ),
              ),
              onPressed: widget.onStartWebLogin,
              child: Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Text(
                    LinDoAuthCopy.LOGIN_WITH_LINUX_DO.text,
                    style: TextStyle(
                      fontSize: scope.sp(14.5),
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                  const SizedBox(width: 6),
                  const Icon(LinDoIcons.ArrowRight, size: 17),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _ProfileMetricCell extends StatelessWidget {
  final String label;
  final String value;

  const _ProfileMetricCell({
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
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: TextStyle(
            fontSize: scope.sp(14.5),
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
            fontSize: scope.sp(11),
            color: palette.inkSecondary,
          ),
        ),
      ],
    );
  }
}

class _QuickActionTile extends StatelessWidget {
  final IconData icon;
  final String label;
  final int badgeCount;
  final VoidCallback onTap;

  const _QuickActionTile({
    required this.icon,
    required this.label,
    this.badgeCount = 0,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    final scope = LinDoThemeScope.of(context);
    return Material(
      color: palette.cardSurface,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(16),
        side: BorderSide(color: palette.hairline),
      ),
      child: InkWell(
        borderRadius: BorderRadius.circular(16),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 13),
          child: Column(
            children: [
              Stack(
                clipBehavior: Clip.none,
                children: [
                  Icon(icon, size: 21, color: palette.logoDoColor),
                  if (badgeCount > 0)
                    Positioned(
                      right: -12,
                      top: -5,
                      child: Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 5,
                          vertical: 1,
                        ),
                        decoration: BoxDecoration(
                          color: palette.tonalPill,
                          borderRadius: BorderRadius.circular(999),
                        ),
                        child: Text(
                          badgeCount > 99 ? '99+' : '$badgeCount',
                          style: TextStyle(
                            fontSize: scope.sp(9.5),
                            fontWeight: FontWeight.w700,
                            color: palette.inkSecondary,
                          ),
                        ),
                      ),
                    ),
                ],
              ),
              const SizedBox(height: 6),
              Text(
                label,
                style: TextStyle(
                  fontSize: scope.sp(12.5),
                  fontWeight: FontWeight.w600,
                  color: palette.inkPrimary,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _GroupLabel extends StatelessWidget {
  final String title;
  const _GroupLabel(this.title);

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    final scope = LinDoThemeScope.of(context);
    return Padding(
      padding: const EdgeInsets.only(left: 4),
      child: Text(
        title,
        style: TextStyle(
          fontSize: scope.sp(12.5),
          fontWeight: FontWeight.w700,
          color: palette.inkSecondary,
        ),
      ),
    );
  }
}

class _SettingsGroupCard extends StatelessWidget {
  final List<Widget> children;

  const _SettingsGroupCard({required this.children});

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    return Container(
      decoration: BoxDecoration(
        color: palette.cardSurface,
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: palette.hairline),
      ),
      clipBehavior: Clip.antiAlias,
      child: Column(children: children),
    );
  }
}

class _SettingsRow extends StatelessWidget {
  final IconData icon;
  final Color? iconColor;
  final String title;
  final Color? titleColor;
  final String? trailingText;
  final Widget? customTrailing;
  final bool showDivider;
  final VoidCallback onTap;

  const _SettingsRow({
    required this.icon,
    this.iconColor,
    required this.title,
    this.titleColor,
    this.trailingText,
    this.customTrailing,
    this.showDivider = true,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    final scope = LinDoThemeScope.of(context);

    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
              child: Row(
                children: [
                  Icon(
                    icon,
                    size: 19,
                    color: iconColor ?? palette.logoDoColor,
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Text(
                      title,
                      style: TextStyle(
                        fontSize: scope.sp(14.5),
                        fontWeight: FontWeight.w600,
                        color: titleColor ?? palette.inkPrimary,
                      ),
                    ),
                  ),
                  if (customTrailing != null)
                    customTrailing!
                  else ...[
                    if (trailingText != null && trailingText!.isNotEmpty) ...[
                      Text(
                        trailingText!,
                        style: TextStyle(
                          fontSize: scope.sp(12.5),
                          color: palette.inkTertiary,
                        ),
                      ),
                      const SizedBox(width: 6),
                    ],
                    Icon(
                      LinDoIcons.ChevronRight,
                      size: 18,
                      color: palette.inkTertiary,
                    ),
                  ],
                ],
              ),
            ),
            if (showDivider)
              Padding(
                padding: const EdgeInsets.only(left: 47),
                child: Divider(height: 1, color: palette.hairline),
              ),
          ],
        ),
      ),
    );
  }
}
