import 'package:flutter/material.dart';

import '../../core/copy/lindo_copy.dart';
import '../../core/models/lindo_models.dart';
import '../../core/network/linux_do_repository.dart';
import '../../core/site/linux_do_site_rules.dart';
import '../components/lindo_icons.dart';
import '../components/lindo_mascot.dart';
import '../components/web_auth_modal.dart';
import '../theme/lindo_theme.dart';

Future<void> showConnectSheet(
  BuildContext context, {
  required LinuxDoRepository repository,
  required Future<void> Function() onStartSiteVerify,
}) {
  final size = MediaQuery.sizeOf(context);
  final isWide = shouldUseWideSplitLayout(size.width, size.height);
  if (isWide) {
    return showDialog<void>(
      context: context,
      barrierColor: Colors.black.withValues(alpha: 0.45),
      builder: (ctx) => Dialog(
        backgroundColor: Colors.transparent,
        insetPadding: const EdgeInsets.symmetric(horizontal: 36, vertical: 32),
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 620, maxHeight: 720),
          child: ClipRRect(
            borderRadius: BorderRadius.circular(24),
            child: ConnectSheet(
              repository: repository,
              onStartSiteVerify: onStartSiteVerify,
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
      heightFactor: 0.90,
      child: ClipRRect(
        borderRadius: const BorderRadius.vertical(top: Radius.circular(24)),
        child: ConnectSheet(
          repository: repository,
          onStartSiteVerify: onStartSiteVerify,
        ),
      ),
    ),
  );
}

class ConnectSheet extends StatefulWidget {
  final LinuxDoRepository repository;
  final Future<void> Function() onStartSiteVerify;

  const ConnectSheet({
    super.key,
    required this.repository,
    required this.onStartSiteVerify,
  });

  @override
  State<ConnectSheet> createState() => _ConnectSheetState();
}

class _ConnectSheetState extends State<ConnectSheet> {
  ConnectOverviewModel? _overview;
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
      final result = await widget.repository.fetchConnectOverview();
      if (!mounted) return;
      setState(() {
        _overview = result;
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

  double? _computeRowProgress(ConnectRequirementRow row) {
    final numRegex = RegExp(r'(\d+(?:\.\d+)?)');
    final curMatch = numRegex.firstMatch(row.currentValue.replaceAll(',', ''));
    final reqMatch = numRegex.firstMatch(row.requiredValue.replaceAll(',', ''));
    if (curMatch == null || reqMatch == null) return null;
    final cur = double.tryParse(curMatch.group(1) ?? '');
    final req = double.tryParse(reqMatch.group(1) ?? '');
    if (cur == null || req == null || req <= 0) {
      return row.qualified ? 1.0 : 0.0;
    }
    if (row.label.contains('举报') || row.label.contains('封禁') || row.label.contains('禁言')) {
      return row.qualified ? 1.0 : 0.25;
    }
    return (cur / req).clamp(0.0, 1.0);
  }

  Future<void> _openConnectWeb() async {
    await showLinDoWebAuthModal(
      context,
      initialUrl: kLinuxDoConnectUrl,
      mode: WebAuthModalMode.inAppBrowser,
      customTitle: LinDoAuthCopy.WEB_MODAL_CONNECT_TITLE.text,
    );
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
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 16, 12, 12),
            child: Row(
              children: [
                Container(
                  width: 36,
                  height: 36,
                  decoration: BoxDecoration(
                    color: palette.activeChipBg,
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: Icon(
                    LinDoIcons.ShieldCheck,
                    size: 19,
                    color: palette.activeChipText,
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        LinDoAuthCopy.WEB_MODAL_CONNECT_TITLE.text,
                        style: TextStyle(
                          fontSize: scope.sp(17),
                          fontWeight: FontWeight.w700,
                          color: palette.inkPrimary,
                        ),
                      ),
                      Text(
                        LinDoFeedCopy.PORTAL_CONNECT_SUB.text,
                        style: TextStyle(
                          fontSize: scope.sp(12),
                          color: palette.inkSecondary,
                        ),
                      ),
                    ],
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
                  tooltip: LinDoAuthCopy.WEB_MODAL_CONNECT_TITLE.text,
                  onPressed: _openConnectWeb,
                  icon: Icon(
                    LinDoIcons.Globe,
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
          Divider(height: 1, color: palette.hairline),
          Expanded(
            child: _buildBody(context, palette, scope, isSiteVerify),
          ),
        ],
      ),
    );
  }

  Widget _buildBody(
    BuildContext context,
    LinDoThemePalette palette,
    LinDoThemeScope scope,
    bool isSiteVerify,
  ) {
    if (_loading) {
      return LinDoStatePanel(
        mood: MascotMood.connecting,
        title: LinDoMessagesCopy.MASCOT_SYNCING_CONNECT.text,
        subtitle: LinDoMessagesCopy.QUERYING_CONNECT_PAGE.text,
      );
    }

    if (isSiteVerify) {
      return LinDoSiteVerificationCard(
        onStartInAppVerify: () async {
          await widget.onStartSiteVerify();
          if (mounted) _load();
        },
        onRetry: _load,
      );
    }

    if (_error != null || _overview == null) {
      return LinDoStatePanel(
        mood: MascotMood.surprised,
        title: LinDoMessagesCopy.CONNECT_STATS_UNAVAILABLE.text,
        subtitle: _error,
        actionLabel: LinDoCommonCopy.RETRY.text,
        onAction: _load,
        secondaryActionLabel: LinDoAuthCopy.WEB_MODAL_CONNECT_TITLE.text,
        onSecondaryAction: _openConnectWeb,
      );
    }

    final overview = _overview!;
    final rows = overview.rows;
    final qualifiedCount = rows.where((r) => r.qualified).length;
    final ratio = rows.isEmpty ? 1.0 : (qualifiedCount / rows.length);

    final statusBg = overview.allQualified
        ? (palette.isDark ? const Color(0xFF064E3B) : const Color(0xFFECFDF5))
        : palette.activeChipBg;
    final statusFg = overview.allQualified
        ? (palette.isDark ? const Color(0xFF6EE7B7) : const Color(0xFF047857))
        : palette.activeChipText;

    return RefreshIndicator(
      onRefresh: _load,
      child: ListView.builder(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.fromLTRB(18, 18, 18, 28),
        itemCount: rows.length + 1,
        itemBuilder: (context, index) {
          if (index == 0) {
            return Padding(
              padding: const EdgeInsets.only(bottom: 16),
              child: Container(
                padding: const EdgeInsets.all(18),
                decoration: BoxDecoration(
                  color: palette.groupedCanvas,
                  borderRadius: BorderRadius.circular(18),
                  border: Border.all(color: palette.hairline),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Expanded(
                          child: Text(
                            overview.headerTitle,
                            style: TextStyle(
                              fontSize: scope.sp(17),
                              fontWeight: FontWeight.w800,
                              color: palette.inkPrimary,
                            ),
                          ),
                        ),
                        Container(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 10,
                            vertical: 4,
                          ),
                          decoration: BoxDecoration(
                            color: statusBg,
                            borderRadius: BorderRadius.circular(999),
                          ),
                          child: Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Icon(
                                overview.allQualified
                                    ? LinDoIcons.CheckCircle
                                    : LinDoIcons.Sparkles,
                                size: 13.5,
                                color: statusFg,
                              ),
                              const SizedBox(width: 4),
                              Text(
                                '$qualifiedCount / ${rows.length}',
                                style: TextStyle(
                                  fontSize: scope.sp(12),
                                  fontWeight: FontWeight.w700,
                                  color: statusFg,
                                ),
                              ),
                            ],
                          ),
                        ),
                      ],
                    ),
                    if (overview.subtitle.trim().isNotEmpty) ...[
                      const SizedBox(height: 6),
                      Text(
                        overview.subtitle,
                        style: TextStyle(
                          fontSize: scope.sp(13),
                          height: 1.45,
                          color: palette.inkSecondary,
                        ),
                      ),
                    ],
                    const SizedBox(height: 14),
                    ClipRRect(
                      borderRadius: BorderRadius.circular(999),
                      child: LinearProgressIndicator(
                        value: ratio.clamp(0.0, 1.0),
                        minHeight: 7,
                        backgroundColor: palette.tonalPill,
                        valueColor: AlwaysStoppedAnimation<Color>(
                          overview.allQualified
                              ? const Color(0xFF10B981)
                              : palette.logoDoColor,
                        ),
                      ),
                    ),
                    const SizedBox(height: 10),
                    Text(
                      overview.isLiveConnect
                          ? LinDoMessagesCopy.CONNECT_SOURCE_LIVE
                              .format(overview.periodDays)
                          : LinDoMessagesCopy
                              .CONNECT_SOURCE_PROFILE_FALLBACK.text,
                      style: TextStyle(
                        fontSize: scope.sp(11.5),
                        color: palette.inkTertiary,
                      ),
                    ),
                  ],
                ),
              ),
            );
          }

          final row = rows[index - 1];
          final progress = _computeRowProgress(row);
          final okColor = palette.isDark
              ? const Color(0xFF34D399)
              : const Color(0xFF059669);
          final pendingColor = palette.logoDoColor;

          return RepaintBoundary(
            child: Padding(
              padding: const EdgeInsets.only(bottom: 10),
              child: Container(
                padding: const EdgeInsets.symmetric(
                  horizontal: 14,
                  vertical: 13,
                ),
                decoration: BoxDecoration(
                  color: palette.subtleSurface,
                  borderRadius: BorderRadius.circular(14),
                  border: Border.all(color: palette.hairline),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Icon(
                          row.qualified
                              ? LinDoIcons.CheckCircle
                              : LinDoIcons.InfoCircle,
                          size: 16,
                          color: row.qualified ? okColor : pendingColor,
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          child: Text(
                            row.label,
                            style: TextStyle(
                              fontSize: scope.sp(13.5),
                              fontWeight: FontWeight.w600,
                              color: palette.inkPrimary,
                            ),
                          ),
                        ),
                        Text(
                          row.currentValue,
                          style: TextStyle(
                            fontSize: scope.sp(13.5),
                            fontWeight: FontWeight.w700,
                            color: row.qualified ? okColor : palette.inkPrimary,
                          ),
                        ),
                        if (row.requiredValue.trim().isNotEmpty) ...[
                          Text(
                            ' / ${row.requiredValue}',
                            style: TextStyle(
                              fontSize: scope.sp(12),
                              color: palette.inkTertiary,
                            ),
                          ),
                        ],
                      ],
                    ),
                    if (progress != null) ...[
                      const SizedBox(height: 8),
                      ClipRRect(
                        borderRadius: BorderRadius.circular(999),
                        child: LinearProgressIndicator(
                          value: progress,
                          minHeight: 5,
                          backgroundColor: palette.tonalPill,
                          valueColor: AlwaysStoppedAnimation<Color>(
                            row.qualified ? okColor : pendingColor,
                          ),
                        ),
                      ),
                    ],
                  ],
                ),
              ),
            ),
          );
        },
      ),
    );
  }
}
