import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

import '../../core/copy/lindo_copy.dart';
import '../../core/platform/lindo_platform_channels.dart';
import '../../core/storage/lindo_local_store.dart';
import '../components/lindo_brand_widgets.dart';
import '../components/lindo_icons.dart';
import '../theme/lindo_theme.dart';

Future<void> showAppIconSelectionPage(
  BuildContext context, {
  required LinDoLocalStore store,
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
        insetPadding: const EdgeInsets.symmetric(horizontal: 36, vertical: 32),
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 620, maxHeight: 700),
          child: ClipRRect(
            borderRadius: BorderRadius.circular(24),
            child: AppIconSelectionPage(
              store: store,
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
      heightFactor: 0.90,
      child: ClipRRect(
        borderRadius: const BorderRadius.vertical(top: Radius.circular(24)),
        child: AppIconSelectionPage(
          store: store,
          onShowToast: onShowToast,
        ),
      ),
    ),
  );
}

class AppIconSelectionPage extends StatefulWidget {
  final LinDoLocalStore store;
  final ValueChanged<String>? onShowToast;

  const AppIconSelectionPage({
    super.key,
    required this.store,
    this.onShowToast,
  });

  @override
  State<AppIconSelectionPage> createState() => _AppIconSelectionPageState();
}

class _AppIconSelectionPageState extends State<AppIconSelectionPage> {
  late LinDoAppIcon _previewIcon;
  bool _applying = false;

  @override
  void initState() {
    super.initState();
    _previewIcon = widget.store.preferences.appIcon;
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

  Future<void> _applyIcon(LinDoAppIcon target) async {
    if (_applying) return;
    setState(() {
      _previewIcon = target;
      _applying = true;
    });
    try {
      await widget.store.updatePreferences(
        widget.store.preferences.copyWith(appIcon: target),
      );
      final launcherOk =
          await LinDoPlatformBridge.instance.applyLauncherIcon(target.key);
      if (!launcherOk) {
        _toast(LinDoMessagesCopy.SWITCH_ICON_FAILED_RETRY.text);
      } else {
        _toast(
          '${LinDoMessagesCopy.ROW_APP_ICON.text} · ${target.artwork.description}',
        );
      }
    } catch (_) {
      _toast(LinDoMessagesCopy.SWITCH_ICON_FAILED_RETRY.text);
    } finally {
      if (mounted) setState(() => _applying = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    final scope = LinDoThemeScope.of(context);
    final choices = LinDoAppIcon.choices;
    final activeSaved = widget.store.preferences.appIcon;
    final isAndroid = !kIsWeb && Platform.isAndroid;

    return Material(
      color: palette.canvas,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // Header
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 16, 14, 12),
            child: Row(
              children: [
                Expanded(
                  child: Text(
                    LinDoMessagesCopy.ROW_APP_ICON.text,
                    style: TextStyle(
                      fontSize: scope.sp(18),
                      fontWeight: FontWeight.w700,
                      color: palette.inkPrimary,
                    ),
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
            child: ListView(
              padding: const EdgeInsets.fromLTRB(20, 20, 20, 28),
              children: [
                // Hero Preview Card
                Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 20,
                    vertical: 24,
                  ),
                  decoration: BoxDecoration(
                    color: palette.groupedCanvas,
                    borderRadius: BorderRadius.circular(20),
                    border: Border.all(color: palette.hairline),
                  ),
                  child: Column(
                    children: [
                      LinDoGlassAppIcon(
                        icon: _previewIcon,
                        size: 92,
                      ),
                      const SizedBox(height: 14),
                      Text(
                        _previewIcon.artwork.description,
                        style: TextStyle(
                          fontSize: scope.sp(16),
                          fontWeight: FontWeight.w700,
                          color: palette.inkPrimary,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        '${_previewIcon.artwork.label} · ${_previewIcon.background.label}',
                        style: TextStyle(
                          fontSize: scope.sp(13),
                          color: palette.inkSecondary,
                        ),
                      ),
                      if (_previewIcon != activeSaved) ...[
                        const SizedBox(height: 16),
                        FilledButton.icon(
                          style: FilledButton.styleFrom(
                            backgroundColor: palette.publishBtnBg,
                            foregroundColor: palette.publishBtnContent,
                            padding: const EdgeInsets.symmetric(
                              horizontal: 22,
                              vertical: 12,
                            ),
                            shape: RoundedRectangleBorder(
                              borderRadius: BorderRadius.circular(999),
                            ),
                          ),
                          onPressed: _applying
                              ? null
                              : () => _applyIcon(_previewIcon),
                          icon: Icon(LinDoIcons.Check, size: 17),
                          label: Text(
                            isAndroid
                                ? LinDoMessagesCopy.APPLY_ICON_AND_RESTART.text
                                : LinDoCommonCopy.CONFIRM.text,
                            style: TextStyle(
                              fontSize: scope.sp(13.5),
                              fontWeight: FontWeight.w700,
                            ),
                          ),
                        ),
                      ] else ...[
                        const SizedBox(height: 12),
                        Container(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 12,
                            vertical: 5,
                          ),
                          decoration: BoxDecoration(
                            color: palette.activeChipBg,
                            borderRadius: BorderRadius.circular(999),
                          ),
                          child: Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Icon(
                                LinDoIcons.CheckCircle,
                                size: 14,
                                color: palette.activeChipText,
                              ),
                              const SizedBox(width: 5),
                              Text(
                                LinDoCommonCopy.SELECTED.text,
                                style: TextStyle(
                                  fontSize: scope.sp(12),
                                  fontWeight: FontWeight.w600,
                                  color: palette.activeChipText,
                                ),
                              ),
                            ],
                          ),
                        ),
                      ],
                    ],
                  ),
                ),

                const SizedBox(height: 22),
                Text(
                  '${LinDoMessagesCopy.ICON_SECTION_ARTWORK.text} · ${choices.length}',
                  style: TextStyle(
                    fontSize: scope.sp(13),
                    fontWeight: FontWeight.w700,
                    color: palette.inkSecondary,
                  ),
                ),
                const SizedBox(height: 12),

                // All 13 choices grid
                GridView.builder(
                  shrinkWrap: true,
                  physics: const NeverScrollableScrollPhysics(),
                  gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
                    maxCrossAxisExtent: 136,
                    mainAxisSpacing: 12,
                    crossAxisSpacing: 12,
                    childAspectRatio: 0.92,
                  ),
                  itemCount: choices.length,
                  itemBuilder: (context, index) {
                    final choice = choices[index];
                    final isSelected = choice == _previewIcon;
                    final isCurrentSaved = choice == activeSaved;
                    return RepaintBoundary(
                      child: Material(
                        color: isSelected
                            ? palette.activeChipBg.withValues(alpha: 0.55)
                            : palette.subtleSurface,
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(18),
                          side: BorderSide(
                            color: isSelected
                                ? palette.logoDoColor
                                : palette.hairline,
                            width: isSelected ? 1.8 : 1.0,
                          ),
                        ),
                        child: InkWell(
                          borderRadius: BorderRadius.circular(18),
                          onTap: () {
                            if (isAndroid) {
                              setState(() => _previewIcon = choice);
                            } else {
                              _applyIcon(choice);
                            }
                          },
                          child: Padding(
                            padding: const EdgeInsets.symmetric(
                              horizontal: 8,
                              vertical: 12,
                            ),
                            child: Column(
                              mainAxisAlignment: MainAxisAlignment.center,
                              children: [
                                Stack(
                                  clipBehavior: Clip.none,
                                  children: [
                                    LinDoGlassAppIcon(
                                      icon: choice,
                                      size: 54,
                                    ),
                                    if (isCurrentSaved)
                                      Positioned(
                                        right: -4,
                                        top: -4,
                                        child: Container(
                                          width: 18,
                                          height: 18,
                                          decoration: BoxDecoration(
                                            color: palette.logoDoColor,
                                            shape: BoxShape.circle,
                                            border: Border.all(
                                              color: palette.canvas,
                                              width: 2,
                                            ),
                                          ),
                                          child: const Icon(
                                            LinDoIcons.Check,
                                            size: 11,
                                            color: Colors.white,
                                          ),
                                        ),
                                      ),
                                  ],
                                ),
                                const SizedBox(height: 8),
                                Text(
                                  choice.artwork.label,
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                  style: TextStyle(
                                    fontSize: scope.sp(12.5),
                                    fontWeight: FontWeight.w600,
                                    color: palette.inkPrimary,
                                  ),
                                ),
                                const SizedBox(height: 2),
                                Text(
                                  choice.background.label,
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                  style: TextStyle(
                                    fontSize: scope.sp(11),
                                    color: palette.inkTertiary,
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ),
                      ),
                    );
                  },
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
