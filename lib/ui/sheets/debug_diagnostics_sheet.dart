import 'package:flutter/material.dart' hide DiagnosticLevel;
import 'package:flutter/services.dart' hide DiagnosticLevel;

import '../../core/copy/lindo_copy.dart';
import '../../core/diagnostics/lindo_diagnostic_logger.dart';
import '../../core/network/linux_do_repository.dart';
import '../../core/platform/lindo_platform_channels.dart';
import '../../core/storage/lindo_local_store.dart';
import '../components/lindo_icons.dart';
import '../components/lindo_mascot.dart';
import '../theme/lindo_theme.dart';

Future<void> showDebugDiagnosticsSheet(
  BuildContext context, {
  required LinDoLocalStore store,
  LinuxDoRepository? repository,
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
        insetPadding: const EdgeInsets.symmetric(horizontal: 32, vertical: 28),
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 780, maxHeight: 720),
          child: ClipRRect(
            borderRadius: BorderRadius.circular(24),
            child: DebugDiagnosticsSheet(
              store: store,
              repository: repository,
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
        child: DebugDiagnosticsSheet(
          store: store,
          repository: repository,
          onShowToast: onShowToast,
        ),
      ),
    ),
  );
}

Future<void> showClearCacheOptionsDialog(
  BuildContext context, {
  required LinDoLocalStore store,
  ValueChanged<String>? onShowToast,
}) {
  final palette = LinDoThemeScope.paletteOf(context);
  final scope = LinDoThemeScope.of(context);
  final entries = LinDoDiagnosticLogger.instance.entries;
  final approxBytes = entries.fold<int>(
    0,
    (sum, item) => sum + item.message.length + (item.detail?.length ?? 0) + 48,
  );
  final approxKb = (approxBytes / 1024).ceil();

  return showDialog<void>(
    context: context,
    builder: (ctx) => AlertDialog(
      backgroundColor: palette.cardSurface,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(20),
        side: BorderSide(color: palette.hairline),
      ),
      title: Text(
        LinDoMessagesCopy.ROW_CLEAR_LOCAL_CACHE.text,
        style: TextStyle(
          fontSize: scope.sp(17),
          fontWeight: FontWeight.w700,
          color: palette.inkPrimary,
        ),
      ),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            LinDoMessagesCopy.CLEAR_CACHE_DIALOG_DESC.text,
            style: TextStyle(
              fontSize: scope.sp(13.5),
              height: 1.45,
              color: palette.inkSecondary,
            ),
          ),
          const SizedBox(height: 8),
          Text(
            entries.isEmpty
                ? LinDoMessagesCopy.NO_DIAGNOSTIC_LOGS.text
                : LinDoMessagesCopy.RECORDED_DIAGNOSTIC_LOGS_SUMMARY
                    .format([entries.length, approxKb]),
            style: TextStyle(
              fontSize: scope.sp(12),
              color: palette.inkTertiary,
            ),
          ),
          const SizedBox(height: 16),
          _CacheActionCard(
            title: LinDoMessagesCopy.CLEAR_ONLY_CACHE_OPTION.text,
            subtitle: LinDoMessagesCopy.CLEAR_ONLY_CACHE_SUB.text,
            icon: LinDoIcons.Layers,
            accentColor: palette.accent,
            onTap: () async {
              Navigator.of(ctx).pop();
              await store.clearContentCache();
              PaintingBinding.instance.imageCache.clear();
              PaintingBinding.instance.imageCache.clearLiveImages();
              onShowToast?.call(
                LinDoMessagesCopy.CLEARED_IMAGES_AND_PARSE_CACHE.text,
              );
            },
          ),
          const SizedBox(height: 10),
          _CacheActionCard(
            title: LinDoMessagesCopy.CLEAR_CACHE_AND_LOGS_OPTION.text,
            subtitle: LinDoMessagesCopy.CLEARED_ALL_DIAGNOSTIC_LOGS.text,
            icon: LinDoIcons.Trash,
            accentColor: const Color(0xFFDC2626),
            onTap: () async {
              Navigator.of(ctx).pop();
              await store.clearContentCache();
              PaintingBinding.instance.imageCache.clear();
              PaintingBinding.instance.imageCache.clearLiveImages();
              await LinDoDiagnosticLogger.instance.clearLogs();
              onShowToast?.call(
                LinDoMessagesCopy.CLEARED_CACHE_AND_LOGS.text,
              );
            },
          ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(ctx).pop(),
          child: Text(
            LinDoCommonCopy.CANCEL.text,
            style: TextStyle(color: palette.inkSecondary),
          ),
        ),
      ],
    ),
  );
}

class _CacheActionCard extends StatelessWidget {
  final String title;
  final String subtitle;
  final IconData icon;
  final Color accentColor;
  final VoidCallback onTap;

  const _CacheActionCard({
    required this.title,
    required this.subtitle,
    required this.icon,
    required this.accentColor,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    final scope = LinDoThemeScope.of(context);
    return Material(
      color: palette.subtleSurface,
      borderRadius: BorderRadius.circular(14),
      child: InkWell(
        borderRadius: BorderRadius.circular(14),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Row(
            children: [
              Container(
                width: 36,
                height: 36,
                decoration: BoxDecoration(
                  color: accentColor.withValues(alpha: 0.12),
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Icon(icon, size: 18, color: accentColor),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      title,
                      style: TextStyle(
                        fontSize: scope.sp(14),
                        fontWeight: FontWeight.w600,
                        color: palette.inkPrimary,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      subtitle,
                      style: TextStyle(
                        fontSize: scope.sp(12),
                        color: palette.inkSecondary,
                      ),
                    ),
                  ],
                ),
              ),
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

/// 5-tap version unlock detector (`v0.3.0 (35)` -> unlocks `debugModeUnlocked = true`).
class DebugVersionUnlockTrigger extends StatefulWidget {
  final LinDoLocalStore store;
  final ValueChanged<String>? onShowToast;
  final VoidCallback? onUnlocked;
  final Widget child;

  const DebugVersionUnlockTrigger({
    super.key,
    required this.store,
    required this.child,
    this.onShowToast,
    this.onUnlocked,
  });

  @override
  State<DebugVersionUnlockTrigger> createState() =>
      _DebugVersionUnlockTriggerState();
}

class _DebugVersionUnlockTriggerState extends State<DebugVersionUnlockTrigger> {
  int _tapCount = 0;
  DateTime? _lastTapTime;

  Future<void> _handleTap() async {
    final now = DateTime.now();
    if (_lastTapTime != null &&
        now.difference(_lastTapTime!) > const Duration(seconds: 3)) {
      _tapCount = 0;
    }
    _lastTapTime = now;

    if (widget.store.preferences.debugModeUnlocked) {
      widget.onUnlocked?.call();
      return;
    }

    _tapCount += 1;
    final remaining = 5 - _tapCount;
    if (remaining <= 0) {
      _tapCount = 0;
      await widget.store.updatePreferences(
        widget.store.preferences.copyWith(debugModeUnlocked: true),
      );
      LinDoDiagnosticLogger.instance.info(
        DiagnosticCategory.SYSTEM,
        'Diagnostics',
        'Debug diagnostics mode unlocked via 5-tap gesture',
      );
      widget.onShowToast?.call('已开启调试诊断模式');
      widget.onUnlocked?.call();
    } else if (_tapCount >= 2) {
      widget.onShowToast?.call(
        LinDoMessagesCopy.TAP_UNLOCK_DIAGNOSTICS_HINT.format(remaining),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: _handleTap,
      child: widget.child,
    );
  }
}

class DebugDiagnosticsSheet extends StatefulWidget {
  final LinDoLocalStore store;
  final LinuxDoRepository? repository;
  final ValueChanged<String>? onShowToast;

  const DebugDiagnosticsSheet({
    super.key,
    required this.store,
    this.repository,
    this.onShowToast,
  });

  @override
  State<DebugDiagnosticsSheet> createState() => _DebugDiagnosticsSheetState();
}

class _DebugDiagnosticsSheetState extends State<DebugDiagnosticsSheet> {
  DiagnosticFilterTab _activeTab = DiagnosticFilterTab.ALL;
  final Set<int> _expandedIds = <int>{};
  bool _exporting = false;

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

  String _buildReport() {
    return LinDoDiagnosticLogger.instance.buildDiagnosticReport(
      username: widget.repository?.currentUser?.username,
      isAuthorized: widget.repository?.isAuthorized ?? false,
    );
  }

  Future<void> _copyRedactedReport() async {
    final report = _buildReport();
    await Clipboard.setData(ClipboardData(text: report));
    _toast(LinDoMessagesCopy.DIAGNOSTICS_COPIED_REDACTED.text);
  }

  Future<void> _exportLogFile() async {
    if (_exporting) return;
    setState(() => _exporting = true);
    try {
      final report = _buildReport();
      final stamp = DateTime.now()
          .toIso8601String()
          .replaceAll(':', '-')
          .split('.')
          .first;
      final suggestedName = 'lindo-diagnostics-$stamp.log';
      final savedPath = await LinDoPlatformBridge.instance.saveDiagnosticFile(
        suggestedName: suggestedName,
        content: report,
      );
      if (savedPath != null && savedPath.trim().isNotEmpty) {
        _toast(LinDoMessagesCopy.EXPORT_SUCCESS_PATH.format(savedPath));
      } else {
        await Clipboard.setData(ClipboardData(text: report));
        _toast(LinDoMessagesCopy.EXPORT_FALLBACK_CLIPBOARD.text);
      }
    } catch (err) {
      _toast(LinDoMessagesCopy.EXPORT_FAILED_REASON.format('$err'));
    } finally {
      if (mounted) setState(() => _exporting = false);
    }
  }

  Future<void> _clearCache() async {
    await widget.store.clearContentCache();
    PaintingBinding.instance.imageCache.clear();
    PaintingBinding.instance.imageCache.clearLiveImages();
    _toast(LinDoMessagesCopy.CLEARED_IMAGES_AND_PARSE_CACHE.text);
  }

  Future<void> _clearLogs() async {
    await LinDoDiagnosticLogger.instance.clearLogs();
    if (mounted) {
      setState(() => _expandedIds.clear());
    }
    _toast(LinDoMessagesCopy.CLEARED_ALL_DIAGNOSTIC_LOGS.text);
  }

  String _formatStorageSize(List<DiagnosticLogEntry> entries) {
    final bytes = entries.fold<int>(
      0,
      (sum, item) => sum + item.message.length + (item.detail?.length ?? 0) + 52,
    );
    if (bytes < 1024) return '$bytes B';
    if (bytes < 1024 * 1024) {
      return '${(bytes / 1024).toStringAsFixed(1)} KB';
    }
    return '${(bytes / (1024 * 1024)).toStringAsFixed(2)} MB';
  }

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    final scope = LinDoThemeScope.of(context);

    return Material(
      color: palette.canvas,
      child: ListenableBuilder(
        listenable: LinDoDiagnosticLogger.instance,
        builder: (context, _) {
          final logger = LinDoDiagnosticLogger.instance;
          final allEntries = logger.entries;
          final filtered = logger
              .filteredEntries(_activeTab.category)
              .reversed
              .toList(growable: false);
          final sizeText = _formatStorageSize(allEntries);
          final versionLabel =
              'v$kLinDoAppVersionName ($kLinDoAppBuildNumber)';

          return Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // Header
              Padding(
                padding: const EdgeInsets.fromLTRB(20, 16, 14, 12),
                child: Row(
                  children: [
                    Container(
                      width: 38,
                      height: 38,
                      decoration: BoxDecoration(
                        color: palette.activeChipBg,
                        borderRadius: BorderRadius.circular(11),
                      ),
                      child: Icon(
                        LinDoIcons.Bug,
                        size: 20,
                        color: palette.activeChipText,
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            LinDoMessagesCopy.DIAGNOSTICS_SHEET_TITLE.text,
                            style: TextStyle(
                              fontSize: scope.sp(17),
                              fontWeight: FontWeight.w700,
                              color: palette.inkPrimary,
                            ),
                          ),
                          const SizedBox(height: 2),
                          Text(
                            LinDoMessagesCopy.DIAGNOSTICS_HEADER_SUMMARY.format([
                              versionLabel,
                              allEntries.length,
                              sizeText,
                            ]),
                            style: TextStyle(
                              fontSize: scope.sp(12),
                              color: palette.inkSecondary,
                            ),
                          ),
                        ],
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

              // Action buttons row
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
                child: Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: [
                    _DiagActionButton(
                      icon: LinDoIcons.Copy,
                      label: '复制脱敏诊断报告',
                      isPrimary: true,
                      onTap: _copyRedactedReport,
                    ),
                    _DiagActionButton(
                      icon: LinDoIcons.Share,
                      label: LinDoMessagesCopy.EXPORT_LOG_FILE_BTN.text,
                      onTap: _exporting ? null : _exportLogFile,
                    ),
                    _DiagActionButton(
                      icon: LinDoIcons.Layers,
                      label: '清理缓存',
                      onTap: _clearCache,
                    ),
                    _DiagActionButton(
                      icon: LinDoIcons.Trash,
                      label: '清空诊断日志',
                      isDestructive: true,
                      onTap: allEntries.isEmpty ? null : _clearLogs,
                    ),
                  ],
                ),
              ),

              // Filter Tabs
              Container(
                decoration: BoxDecoration(
                  border: Border(
                    top: BorderSide(color: palette.hairline),
                    bottom: BorderSide(color: palette.hairline),
                  ),
                  color: palette.groupedCanvas,
                ),
                height: 46,
                child: ListView.separated(
                  scrollDirection: Axis.horizontal,
                  padding: const EdgeInsets.symmetric(
                    horizontal: 16,
                    vertical: 7,
                  ),
                  itemCount: DiagnosticFilterTab.values.length,
                  separatorBuilder: (_, _) => const SizedBox(width: 8),
                  itemBuilder: (context, index) {
                    final tab = DiagnosticFilterTab.values[index];
                    final selected = tab == _activeTab;
                    final count = tab.category == null
                        ? allEntries.length
                        : logger.filteredEntries(tab.category).length;
                    return InkWell(
                      borderRadius: BorderRadius.circular(999),
                      onTap: () => setState(() => _activeTab = tab),
                      child: AnimatedContainer(
                        duration: const Duration(milliseconds: 160),
                        padding: const EdgeInsets.symmetric(
                          horizontal: 12,
                          vertical: 5,
                        ),
                        decoration: BoxDecoration(
                          color: selected
                              ? palette.activeChipBg
                              : palette.cardSurface,
                          borderRadius: BorderRadius.circular(999),
                          border: Border.all(
                            color: selected
                                ? palette.activeChipText.withValues(alpha: 0.35)
                                : palette.hairline,
                          ),
                        ),
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Text(
                              tab.labelCopy.text,
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
                            const SizedBox(width: 5),
                            Text(
                              '$count',
                              style: TextStyle(
                                fontSize: scope.sp(11.5),
                                fontWeight: FontWeight.w600,
                                color: selected
                                    ? palette.activeChipText
                                    : palette.inkTertiary,
                              ),
                            ),
                          ],
                        ),
                      ),
                    );
                  },
                ),
              ),

              // Monospace Log Entries List
              Expanded(
                child: filtered.isEmpty
                    ? Center(
                        child: LinDoStatePanel(
                          mood: MascotMood.sleepy,
                          title: LinDoMessagesCopy
                              .NO_MATCHING_DIAGNOSTIC_LOGS.text,
                          subtitle: allEntries.isEmpty
                              ? LinDoMessagesCopy.NO_DIAGNOSTIC_LOGS.text
                              : null,
                        ),
                      )
                    : ListView.builder(
                        padding: const EdgeInsets.fromLTRB(14, 12, 14, 24),
                        itemCount: filtered.length,
                        itemBuilder: (context, index) {
                          final entry = filtered[index];
                          final expanded = _expandedIds.contains(entry.id);
                          return RepaintBoundary(
                            child: _DiagnosticLogCard(
                              entry: entry,
                              expanded: expanded,
                              onToggleExpand: () {
                                setState(() {
                                  if (expanded) {
                                    _expandedIds.remove(entry.id);
                                  } else {
                                    _expandedIds.add(entry.id);
                                  }
                                });
                              },
                              onCopySingle: () async {
                                await Clipboard.setData(
                                  ClipboardData(
                                    text: logger.redactSensitive(
                                      entry.formatLine(),
                                    ),
                                  ),
                                );
                                _toast(LinDoCommonCopy.COPIED.text);
                              },
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

class _DiagActionButton extends StatelessWidget {
  final IconData icon;
  final String label;
  final bool isPrimary;
  final bool isDestructive;
  final VoidCallback? onTap;

  const _DiagActionButton({
    required this.icon,
    required this.label,
    this.isPrimary = false,
    this.isDestructive = false,
    this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    final scope = LinDoThemeScope.of(context);

    final Color bg;
    final Color fg;
    if (isPrimary) {
      bg = palette.publishBtnBg;
      fg = palette.publishBtnContent;
    } else if (isDestructive) {
      bg = palette.isDark
          ? const Color(0xFF3B181B)
          : const Color(0xFFFEF2F2);
      fg = palette.isDark
          ? const Color(0xFFFCA5A5)
          : const Color(0xFFDC2626);
    } else {
      bg = palette.tonalPill;
      fg = palette.onTonalPill;
    }

    return Opacity(
      opacity: onTap == null ? 0.45 : 1.0,
      child: Material(
        color: bg,
        borderRadius: BorderRadius.circular(10),
        child: InkWell(
          borderRadius: BorderRadius.circular(10),
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(icon, size: 15, color: fg),
                const SizedBox(width: 6),
                Text(
                  label,
                  style: TextStyle(
                    fontSize: scope.sp(12.5),
                    fontWeight: FontWeight.w600,
                    color: fg,
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

class _DiagnosticLogCard extends StatelessWidget {
  final DiagnosticLogEntry entry;
  final bool expanded;
  final VoidCallback onToggleExpand;
  final VoidCallback onCopySingle;

  const _DiagnosticLogCard({
    required this.entry,
    required this.expanded,
    required this.onToggleExpand,
    required this.onCopySingle,
  });

  static const List<String> _monoFallbacks = <String>[
    'SF Mono',
    'Menlo',
    'Consolas',
    'Roboto Mono',
    'monospace',
  ];

  ({Color bg, Color fg}) _levelColors(DiagnosticLevel level, bool isDark) {
    switch (level) {
      case DiagnosticLevel.CRASH:
        return isDark
            ? (bg: const Color(0xFF450A0A), fg: const Color(0xFFFCA5A5))
            : (bg: const Color(0xFFFEE2E2), fg: const Color(0xFFB91C1C));
      case DiagnosticLevel.ERROR:
        return isDark
            ? (bg: const Color(0xFF3F1515), fg: const Color(0xFFF87171))
            : (bg: const Color(0xFFFEF2F2), fg: const Color(0xFFDC2626));
      case DiagnosticLevel.WARN:
        return isDark
            ? (bg: const Color(0xFF3F2E05), fg: const Color(0xFFFCD34D))
            : (bg: const Color(0xFFFEF3C7), fg: const Color(0xFFB45309));
      case DiagnosticLevel.INFO:
        return isDark
            ? (bg: const Color(0xFF1E293B), fg: const Color(0xFF93C5FD))
            : (bg: const Color(0xFFEFF6FF), fg: const Color(0xFF1D4ED8));
    }
  }

  String _formatLocalTime(DateTime dt) {
    final local = dt.toLocal();
    final hh = local.hour.toString().padLeft(2, '0');
    final mm = local.minute.toString().padLeft(2, '0');
    final ss = local.second.toString().padLeft(2, '0');
    final ms = local.millisecond.toString().padLeft(3, '0');
    return '$hh:$mm:$ss.$ms';
  }

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    final scope = LinDoThemeScope.of(context);
    final levelStyle = _levelColors(entry.level, palette.isDark);
    final hasDetail = (entry.detail ?? '').trim().isNotEmpty;

    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Material(
        color: palette.subtleSurface,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(12),
          side: BorderSide(color: palette.hairline),
        ),
        child: InkWell(
          borderRadius: BorderRadius.circular(12),
          onTap: hasDetail ? onToggleExpand : null,
          child: Padding(
            padding: const EdgeInsets.all(11),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 6,
                        vertical: 2,
                      ),
                      decoration: BoxDecoration(
                        color: levelStyle.bg,
                        borderRadius: BorderRadius.circular(5),
                      ),
                      child: Text(
                        entry.level.wireName,
                        style: TextStyle(
                          fontSize: scope.sp(10.5),
                          fontWeight: FontWeight.w700,
                          color: levelStyle.fg,
                          fontFamilyFallback: _monoFallbacks,
                        ),
                      ),
                    ),
                    const SizedBox(width: 6),
                    Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 6,
                        vertical: 2,
                      ),
                      decoration: BoxDecoration(
                        color: palette.tonalPill,
                        borderRadius: BorderRadius.circular(5),
                      ),
                      child: Text(
                        entry.category.wireName,
                        style: TextStyle(
                          fontSize: scope.sp(10.5),
                          fontWeight: FontWeight.w600,
                          color: palette.inkSecondary,
                          fontFamilyFallback: _monoFallbacks,
                        ),
                      ),
                    ),
                    const SizedBox(width: 6),
                    Expanded(
                      child: Text(
                        '[${entry.tag}]',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          fontSize: scope.sp(11.5),
                          fontWeight: FontWeight.w600,
                          color: palette.inkSecondary,
                          fontFamilyFallback: _monoFallbacks,
                        ),
                      ),
                    ),
                    Text(
                      _formatLocalTime(entry.timestamp),
                      style: TextStyle(
                        fontSize: scope.sp(11),
                        color: palette.inkTertiary,
                        fontFamilyFallback: _monoFallbacks,
                      ),
                    ),
                    const SizedBox(width: 4),
                    InkWell(
                      borderRadius: BorderRadius.circular(6),
                      onTap: onCopySingle,
                      child: Padding(
                        padding: const EdgeInsets.all(4),
                        child: Icon(
                          LinDoIcons.Copy,
                          size: 13.5,
                          color: palette.inkTertiary,
                        ),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 6),
                Text(
                  entry.message,
                  style: TextStyle(
                    fontSize: scope.sp(12),
                    height: 1.42,
                    color: palette.inkPrimary,
                    fontFamilyFallback: _monoFallbacks,
                  ),
                ),
                if (hasDetail) ...[
                  const SizedBox(height: 6),
                  if (!expanded)
                    Row(
                      children: [
                        Icon(
                          LinDoIcons.ChevronDown,
                          size: 14,
                          color: palette.inkTertiary,
                        ),
                        const SizedBox(width: 3),
                        Text(
                          LinDoMessagesCopy.TAP_TO_EXPAND_DETAIL.text,
                          style: TextStyle(
                            fontSize: scope.sp(11),
                            color: palette.inkTertiary,
                          ),
                        ),
                      ],
                    )
                  else
                    Container(
                      width: double.infinity,
                      margin: const EdgeInsets.only(top: 2),
                      padding: const EdgeInsets.all(10),
                      decoration: BoxDecoration(
                        color: palette.canvas,
                        borderRadius: BorderRadius.circular(8),
                        border: Border.all(color: palette.hairline),
                      ),
                      child: SelectableText(
                        entry.detail!.trim(),
                        style: TextStyle(
                          fontSize: scope.sp(11),
                          height: 1.4,
                          color: palette.inkSecondary,
                          fontFamilyFallback: _monoFallbacks,
                        ),
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
