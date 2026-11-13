import 'dart:io';
import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';

import '../../core/copy/lindo_copy.dart';
import '../theme/lindo_theme.dart';
import 'lindo_icons.dart';

bool shouldRunLinDoContinuousAnimation(BuildContext context, {bool enabled = true}) {
  if (!enabled) return false;
  if (!LinDoThemeScope.of(context).enableAnimations) return false;
  if (MediaQuery.maybeDisableAnimationsOf(context) == true) return false;
  if (!kIsWeb && Platform.environment['FLUTTER_TEST'] == 'true') return false;
  final bindingType = WidgetsBinding.instance.runtimeType.toString();
  if (bindingType.contains('TestWidgetsFlutterBinding') ||
      bindingType.contains('AutomatedTestWidgetsFlutterBinding')) {
    return false;
  }
  return true;
}

enum MascotMood {
  happy,
  thinking,
  sleepy,
  surprised,
  wink,
  connecting;

  String? get expressionSvgAsset {
    switch (this) {
      case MascotMood.happy:
        return 'assets/vectors/expressions/happy.svg';
      case MascotMood.sleepy:
        return 'assets/vectors/expressions/sleepy.svg';
      case MascotMood.surprised:
        return 'assets/vectors/expressions/surprised.svg';
      case MascotMood.wink:
        return 'assets/vectors/expressions/wink.svg';
      case MascotMood.thinking:
      case MascotMood.connecting:
        return null;
    }
  }
}

class LinDoMascot extends StatefulWidget {
  final MascotMood mood;
  final double size;
  final bool animate;

  const LinDoMascot({
    super.key,
    this.mood = MascotMood.happy,
    this.size = 88,
    this.animate = true,
  });

  @override
  State<LinDoMascot> createState() => _LinDoMascotState();
}

class _LinDoMascotState extends State<LinDoMascot>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller;

  bool _isRepeating = false;

  @override
  void initState() {
    super.initState();
    _controller = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 2400),
    );
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _syncTicker();
  }

  @override
  void didUpdateWidget(covariant LinDoMascot oldWidget) {
    super.didUpdateWidget(oldWidget);
    _syncTicker();
  }

  void _syncTicker() {
    final shouldAnimate =
        shouldRunLinDoContinuousAnimation(context, enabled: widget.animate);
    if (shouldAnimate && !_isRepeating) {
      _isRepeating = true;
      _controller.repeat();
    } else if (!shouldAnimate && _isRepeating) {
      _isRepeating = false;
      _controller.stop();
      _controller.value = 0.0;
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Widget _buildArtwork() {
    if (widget.mood == MascotMood.connecting) {
      return Image.asset(
        'assets/images/brand/connecting.png',
        width: widget.size,
        height: widget.size,
        fit: BoxFit.contain,
        filterQuality: FilterQuality.medium,
        isAntiAlias: true,
      );
    }

    final baseMark = Image.asset(
      'assets/images/brand/mark.png',
      width: widget.size,
      height: widget.size,
      fit: BoxFit.contain,
      filterQuality: FilterQuality.medium,
      isAntiAlias: true,
    );

    final overlaySvg = widget.mood.expressionSvgAsset;
    if (overlaySvg == null) {
      return baseMark;
    }

    return SizedBox(
      width: widget.size,
      height: widget.size,
      child: Stack(
        fit: StackFit.expand,
        children: [
          baseMark,
          SvgPicture.asset(
            overlaySvg,
            width: widget.size,
            height: widget.size,
            fit: BoxFit.contain,
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final artwork = _buildArtwork();
    if (!_isRepeating) {
      return SizedBox(
        width: widget.size,
        height: widget.size,
        child: Center(child: artwork),
      );
    }

    return AnimatedBuilder(
      animation: _controller,
      child: artwork,
      builder: (context, child) {
        final dy = math.sin(_controller.value * 2.0 * math.pi) * 3.0;
        return Transform.translate(
          offset: Offset(0, dy),
          child: SizedBox(
            width: widget.size,
            height: widget.size,
            child: Center(child: child),
          ),
        );
      },
    );
  }
}

class LinDoWaveDots extends StatefulWidget {
  final Color? color;
  final double dotSize;

  const LinDoWaveDots({
    super.key,
    this.color,
    this.dotSize = 4.0,
  });

  @override
  State<LinDoWaveDots> createState() => _LinDoWaveDotsState();
}

class _LinDoWaveDotsState extends State<LinDoWaveDots>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller;
  bool _isRepeating = false;

  @override
  void initState() {
    super.initState();
    _controller = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1100),
    );
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _syncTicker();
  }

  @override
  void didUpdateWidget(covariant LinDoWaveDots oldWidget) {
    super.didUpdateWidget(oldWidget);
    _syncTicker();
  }

  void _syncTicker() {
    final shouldAnimate = shouldRunLinDoContinuousAnimation(context);
    if (shouldAnimate && !_isRepeating) {
      _isRepeating = true;
      _controller.repeat();
    } else if (!shouldAnimate && _isRepeating) {
      _isRepeating = false;
      _controller.stop();
      _controller.value = 0.25;
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    final resolvedColor = widget.color ?? palette.inkSecondary;

    return AnimatedBuilder(
      animation: _controller,
      builder: (context, _) {
        return Row(
          mainAxisSize: MainAxisSize.min,
          children: List.generate(3, (index) {
            final phase = (_controller.value - index * 0.18) % 1.0;
            final wave = _isRepeating
                ? (0.4 + 0.6 * (0.5 + 0.5 * math.sin(phase * 2.0 * math.pi)))
                : 0.7;
            final dy = _isRepeating
                ? math.sin(phase * 2.0 * math.pi) * -1.4
                : 0.0;
            return Padding(
              padding: EdgeInsets.only(right: index < 2 ? 3.5 : 0.0),
              child: Transform.translate(
                offset: Offset(0, dy),
                child: Container(
                  width: widget.dotSize,
                  height: widget.dotSize,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: resolvedColor.withValues(alpha: wave.clamp(0.25, 1.0)),
                  ),
                ),
              ),
            );
          }),
        );
      },
    );
  }
}

class LinDoStatePanel extends StatelessWidget {
  final MascotMood mood;
  final String title;
  final String? subtitle;
  final String? actionLabel;
  final VoidCallback? onAction;
  final String? secondaryActionLabel;
  final VoidCallback? onSecondaryAction;
  final bool compact;

  const LinDoStatePanel({
    super.key,
    this.mood = MascotMood.sleepy,
    required this.title,
    this.subtitle,
    this.actionLabel,
    this.onAction,
    this.secondaryActionLabel,
    this.onSecondaryAction,
    this.compact = false,
  });

  @override
  Widget build(BuildContext context) {
    final scope = LinDoThemeScope.of(context);
    final palette = scope.palette;
    final cleanSubtitle = subtitle?.trim();
    final hasPrimary =
        onAction != null && actionLabel != null && actionLabel!.trim().isNotEmpty;
    final hasSecondary = onSecondaryAction != null &&
        secondaryActionLabel != null &&
        secondaryActionLabel!.trim().isNotEmpty;

    return Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 380),
        child: Padding(
          padding: EdgeInsets.symmetric(
            horizontal: compact ? 16 : 24,
            vertical: compact ? 14 : 26,
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              LinDoMascot(
                mood: mood,
                size: compact ? 52 : 78,
                animate: true,
              ),
              SizedBox(height: compact ? 10 : 14),
              Row(
                mainAxisSize: MainAxisSize.min,
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Flexible(
                    child: Text(
                      title,
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        color: palette.inkPrimary,
                        fontSize: scope.sp(compact ? 14.0 : 15.5),
                        fontWeight: FontWeight.w700,
                        height: 1.38,
                      ),
                    ),
                  ),
                  if (mood == MascotMood.connecting) ...[
                    const SizedBox(width: 6),
                    LinDoWaveDots(color: palette.inkSecondary, dotSize: 3.8),
                  ],
                ],
              ),
              if (cleanSubtitle != null &&
                  cleanSubtitle.isNotEmpty &&
                  cleanSubtitle != title) ...[
                const SizedBox(height: 6),
                Text(
                  cleanSubtitle,
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    color: palette.inkSecondary,
                    fontSize: scope.sp(compact ? 12.0 : 13.0),
                    height: 1.5,
                  ),
                ),
              ],
              if (hasPrimary || hasSecondary) ...[
                SizedBox(height: compact ? 12 : 16),
                Wrap(
                  spacing: 10,
                  runSpacing: 8,
                  alignment: WrapAlignment.center,
                  children: [
                    if (hasPrimary)
                      FilledButton(
                        onPressed: onAction,
                        style: FilledButton.styleFrom(
                          backgroundColor: palette.publishBtnBg,
                          foregroundColor: palette.publishBtnContent,
                          elevation: 0,
                          minimumSize: Size(0, compact ? 36 : 40),
                          padding: EdgeInsets.symmetric(
                            horizontal: compact ? 14 : 18,
                            vertical: compact ? 8 : 10,
                          ),
                          shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(12),
                          ),
                        ),
                        child: Text(
                          actionLabel!,
                          style: TextStyle(
                            fontSize: scope.sp(13.0),
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                      ),
                    if (hasSecondary)
                      OutlinedButton(
                        onPressed: onSecondaryAction,
                        style: OutlinedButton.styleFrom(
                          foregroundColor: palette.inkPrimary,
                          side: BorderSide(color: palette.hairline, width: 1.0),
                          minimumSize: Size(0, compact ? 36 : 40),
                          padding: EdgeInsets.symmetric(
                            horizontal: compact ? 14 : 18,
                            vertical: compact ? 8 : 10,
                          ),
                          shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(12),
                          ),
                        ),
                        child: Text(
                          secondaryActionLabel!,
                          style: TextStyle(
                            fontSize: scope.sp(13.0),
                            fontWeight: FontWeight.w500,
                          ),
                        ),
                      ),
                  ],
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

class LinDoSiteVerificationCard extends StatelessWidget {
  final VoidCallback onStartInAppVerify;
  final VoidCallback? onRetry;

  const LinDoSiteVerificationCard({
    super.key,
    required this.onStartInAppVerify,
    this.onRetry,
  });

  @override
  Widget build(BuildContext context) {
    final scope = LinDoThemeScope.of(context);
    final palette = scope.palette;

    return Center(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 18),
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 372),
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 22, vertical: 22),
            decoration: BoxDecoration(
              color: palette.subtleSurface,
              borderRadius: BorderRadius.circular(16),
              border: Border.all(color: palette.hairline, width: 0.8),
            ),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                LinDoMascot(
                  mood: MascotMood.connecting,
                  size: 64,
                  animate: true,
                ),
                const SizedBox(height: 14),
                Text(
                  LinDoAuthCopy.SITE_VERIFY_PANEL_TITLE.text,
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    color: palette.inkPrimary,
                    fontSize: scope.sp(15.5),
                    fontWeight: FontWeight.w700,
                    height: 1.35,
                  ),
                ),
                const SizedBox(height: 6),
                Text(
                  LinDoAuthCopy.SITE_VERIFY_PANEL_SUBTITLE.text,
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    color: palette.inkSecondary,
                    fontSize: scope.sp(12.5),
                    height: 1.5,
                  ),
                ),
                const SizedBox(height: 18),
                SizedBox(
                  width: double.infinity,
                  height: 42,
                  child: FilledButton.icon(
                    onPressed: onStartInAppVerify,
                    style: FilledButton.styleFrom(
                      backgroundColor: palette.publishBtnBg,
                      foregroundColor: palette.publishBtnContent,
                      elevation: 0,
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(12),
                      ),
                    ),
                    icon: const Icon(LinDoIcons.ShieldCheck, size: 16),
                    label: Text(
                      LinDoAuthCopy.SITE_VERIFY_ACTION_BTN.text,
                      style: TextStyle(
                        fontSize: scope.sp(13.5),
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ),
                ),
                if (onRetry != null) ...[
                  const SizedBox(height: 8),
                  SizedBox(
                    width: double.infinity,
                    height: 38,
                    child: OutlinedButton.icon(
                      onPressed: onRetry,
                      style: OutlinedButton.styleFrom(
                        foregroundColor: palette.inkSecondary,
                        side: BorderSide(color: palette.hairline, width: 0.8),
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(12),
                        ),
                      ),
                      icon: const Icon(LinDoIcons.Refresh, size: 15),
                      label: Text(
                        LinDoCommonCopy.RETRY.text,
                        style: TextStyle(
                          fontSize: scope.sp(12.5),
                          fontWeight: FontWeight.w500,
                        ),
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
