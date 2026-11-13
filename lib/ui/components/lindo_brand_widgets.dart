import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';

import '../../core/copy/lindo_copy.dart';
import '../theme/lindo_theme.dart';
import 'lindo_icons.dart';
import 'lindo_mascot.dart';

class LinDoGlassAppIcon extends StatelessWidget {
  final LinDoAppIcon icon;
  final double size;
  final double? borderRadius;

  const LinDoGlassAppIcon({
    super.key,
    this.icon = LinDoAppIcon.defaultIcon,
    this.size = 56,
    this.borderRadius,
  });

  @override
  Widget build(BuildContext context) {
    final radius = borderRadius ?? (size * 0.24);
    final shape = ContinuousRectangleBorder(
      borderRadius: BorderRadius.circular(radius * 2.15),
    );
    final isDarkBg = icon.background == LinDoIconBackground.Black;
    final borderColor = isDarkBg
        ? Colors.white.withValues(alpha: 0.14)
        : Colors.black.withValues(alpha: 0.08);
    final pad = size * 0.07;
    final innerSize = math.max(1.0, size - pad * 2);

    Widget artworkWidget;
    if (icon.artwork == LinDoIconArtwork.Official) {
      artworkWidget = SvgPicture.asset(
        'assets/vectors/brand/linuxdo_mark.svg',
        width: innerSize,
        height: innerSize,
        fit: BoxFit.contain,
      );
    } else if (icon.artwork.expressionSvgAsset != null) {
      artworkWidget = Stack(
        fit: StackFit.expand,
        children: [
          Image.asset(
            'assets/images/brand/mark.png',
            width: innerSize,
            height: innerSize,
            fit: BoxFit.contain,
            filterQuality: FilterQuality.medium,
            isAntiAlias: true,
          ),
          SvgPicture.asset(
            icon.artwork.expressionSvgAsset!,
            width: innerSize,
            height: innerSize,
            fit: BoxFit.contain,
          ),
        ],
      );
    } else {
      artworkWidget = Image.asset(
        'assets/images/brand/mark.png',
        width: innerSize,
        height: innerSize,
        fit: BoxFit.contain,
        filterQuality: FilterQuality.medium,
        isAntiAlias: true,
      );
    }

    return SizedBox(
      width: size,
      height: size,
      child: DecoratedBox(
        decoration: ShapeDecoration(
          color: icon.background.color,
          shape: shape.copyWith(
            side: BorderSide(color: borderColor, width: 0.75),
          ),
          shadows: [
            BoxShadow(
              color: Colors.black.withValues(alpha: isDarkBg ? 0.22 : 0.06),
              blurRadius: size * 0.14,
              offset: Offset(0, size * 0.04),
            ),
          ],
        ),
        child: ClipPath(
          clipper: ShapeBorderClipper(shape: shape),
          child: Stack(
            fit: StackFit.expand,
            children: [
              Padding(
                padding: EdgeInsets.all(pad),
                child: artworkWidget,
              ),
              IgnorePointer(
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    gradient: LinearGradient(
                      begin: Alignment.topLeft,
                      end: Alignment.bottomRight,
                      colors: [
                        Colors.white.withValues(alpha: isDarkBg ? 0.10 : 0.24),
                        Colors.white.withValues(alpha: 0.0),
                      ],
                      stops: const [0.0, 0.52],
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

class LinDoBrandWordmark extends StatelessWidget {
  final double fontSize;
  final bool showMascotBadge;

  const LinDoBrandWordmark({
    super.key,
    this.fontSize = 20,
    this.showMascotBadge = true,
  });

  @override
  Widget build(BuildContext context) {
    final scope = LinDoThemeScope.of(context);
    final palette = scope.palette;
    final resolvedFontSize = scope.sp(fontSize);

    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        if (showMascotBadge) ...[
          LinDoGlassAppIcon(
            icon: scope.appIcon,
            size: fontSize * 1.35,
          ),
          SizedBox(width: math.max(7.0, fontSize * 0.42)),
        ],
        Text.rich(
          TextSpan(
            children: [
              TextSpan(
                text: 'LINUX',
                style: TextStyle(
                  color: palette.inkPrimary,
                  fontSize: resolvedFontSize,
                  fontWeight: FontWeight.w800,
                  letterSpacing: -0.4,
                  height: 1.1,
                ),
              ),
              TextSpan(
                text: ' DO',
                style: TextStyle(
                  color: palette.logoDoColor,
                  fontSize: resolvedFontSize,
                  fontWeight: FontWeight.w900,
                  letterSpacing: -0.4,
                  height: 1.1,
                ),
              ),
            ],
          ),
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
        ),
      ],
    );
  }
}

class LinDoWelcomeScreen extends StatefulWidget {
  final VoidCallback onStartWebLogin;
  final VoidCallback onContinueAsGuest;

  const LinDoWelcomeScreen({
    super.key,
    required this.onStartWebLogin,
    required this.onContinueAsGuest,
  });

  @override
  State<LinDoWelcomeScreen> createState() => _LinDoWelcomeScreenState();
}

class _LinDoWelcomeScreenState extends State<LinDoWelcomeScreen>
    with SingleTickerProviderStateMixin {
  late final AnimationController _floatController;
  bool _isFloating = false;

  @override
  void initState() {
    super.initState();
    _floatController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 2800),
    );
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _syncFloat();
  }

  void _syncFloat() {
    final shouldFloat = shouldRunLinDoContinuousAnimation(context);
    if (shouldFloat && !_isFloating) {
      _isFloating = true;
      _floatController.repeat();
    } else if (!shouldFloat && _isFloating) {
      _isFloating = false;
      _floatController.stop();
      _floatController.value = 0.0;
    }
  }

  @override
  void dispose() {
    _floatController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final scope = LinDoThemeScope.of(context);
    final palette = scope.palette;
    final media = MediaQuery.of(context);
    final heroSize = media.size.height < 680 ? 156.0 : 196.0;

    final heroImage = Image.asset(
      'assets/images/brand/welcome.png',
      width: heroSize,
      height: heroSize,
      fit: BoxFit.contain,
      semanticLabel: LinDoAuthCopy.WELCOME_WAVE_DESC.text,
      filterQuality: FilterQuality.medium,
      isAntiAlias: true,
    );

    return Scaffold(
      backgroundColor: palette.canvas,
      body: SafeArea(
        child: Padding(
          padding: EdgeInsets.only(top: scope.macTitleBarInset),
          child: Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 420),
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 16),
                child: Column(
                  children: [
                    const Align(
                      alignment: Alignment.centerLeft,
                      child: Padding(
                        padding: EdgeInsets.symmetric(vertical: 6),
                        child: LinDoBrandWordmark(fontSize: 20),
                      ),
                    ),
                    Expanded(
                      child: Center(
                        child: SingleChildScrollView(
                          padding: const EdgeInsets.symmetric(vertical: 16),
                          child: Column(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              if (_isFloating)
                                AnimatedBuilder(
                                  animation: _floatController,
                                  child: heroImage,
                                  builder: (context, child) {
                                    final dy = math.sin(
                                          _floatController.value * 2.0 * math.pi,
                                        ) *
                                        4.0;
                                    return Transform.translate(
                                      offset: Offset(0, dy),
                                      child: child,
                                    );
                                  },
                                )
                              else
                                heroImage,
                              const SizedBox(height: 20),
                              Text(
                                LinDoAuthCopy.WELCOME_GREETING.text,
                                textAlign: TextAlign.center,
                                style: TextStyle(
                                  color: palette.inkPrimary,
                                  fontSize: scope.sp(27.0),
                                  fontWeight: FontWeight.w800,
                                  letterSpacing: -0.6,
                                  height: 1.28,
                                ),
                              ),
                              const SizedBox(height: 8),
                              Text(
                                LinDoAuthCopy.WELCOME_SUBTITLE.text,
                                textAlign: TextAlign.center,
                                style: TextStyle(
                                  color: palette.inkSecondary,
                                  fontSize: scope.sp(14.0),
                                  height: 1.55,
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                    ),
                    Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        SizedBox(
                          height: 52,
                          child: FilledButton(
                            onPressed: widget.onStartWebLogin,
                            style: FilledButton.styleFrom(
                              backgroundColor: const Color(0xFFF59E0B),
                              foregroundColor: const Color(0xFF18181B),
                              elevation: 0,
                              shape: RoundedRectangleBorder(
                                borderRadius: BorderRadius.circular(16),
                              ),
                              padding: const EdgeInsets.symmetric(
                                horizontal: 20,
                                vertical: 14,
                              ),
                            ),
                            child: Row(
                              mainAxisAlignment: MainAxisAlignment.center,
                              children: [
                                Text(
                                  LinDoAuthCopy.LOGIN_WITH_LINUX_DO.text,
                                  style: TextStyle(
                                    fontSize: scope.sp(15.0),
                                    fontWeight: FontWeight.w700,
                                  ),
                                ),
                                const SizedBox(width: 8),
                                const Icon(LinDoIcons.ArrowRight, size: 18),
                              ],
                            ),
                          ),
                        ),
                        const SizedBox(height: 10),
                        SizedBox(
                          height: 50,
                          child: OutlinedButton(
                            onPressed: widget.onContinueAsGuest,
                            style: OutlinedButton.styleFrom(
                              foregroundColor: palette.inkPrimary,
                              side: BorderSide(
                                color: palette.hairline,
                                width: 1.0,
                              ),
                              shape: RoundedRectangleBorder(
                                borderRadius: BorderRadius.circular(16),
                              ),
                              padding: const EdgeInsets.symmetric(
                                horizontal: 20,
                                vertical: 12,
                              ),
                            ),
                            child: Row(
                              mainAxisAlignment: MainAxisAlignment.center,
                              children: [
                                Icon(
                                  LinDoIcons.Eye,
                                  size: 17,
                                  color: palette.inkSecondary,
                                ),
                                const SizedBox(width: 8),
                                Text(
                                  LinDoAuthCopy.BROWSE_AS_GUEST.text,
                                  style: TextStyle(
                                    fontSize: scope.sp(14.5),
                                    fontWeight: FontWeight.w600,
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ),
                        const SizedBox(height: 14),
                        Text(
                          LinDoCommonCopy.THIRD_PARTY_CLIENT.text,
                          textAlign: TextAlign.center,
                          style: TextStyle(
                            color: palette.inkTertiary,
                            fontSize: scope.sp(10.5),
                            letterSpacing: 0.6,
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
      ),
    );
  }
}

class LinDoMascotLoginTransitionView extends StatelessWidget {
  final String username;

  const LinDoMascotLoginTransitionView({
    super.key,
    required this.username,
  });

  @override
  Widget build(BuildContext context) {
    final scope = LinDoThemeScope.of(context);
    final palette = scope.palette;
    final cleanUsername = username.trim().replaceFirst(RegExp(r'^@+'), '');

    return Material(
      color: palette.canvas.withValues(alpha: 0.96),
      child: SafeArea(
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 360),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 28, vertical: 24),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  const LinDoMascot(
                    mood: MascotMood.happy,
                    size: 92,
                    animate: true,
                  ),
                  const SizedBox(height: 18),
                  const LinDoBrandWordmark(
                    fontSize: 18,
                    showMascotBadge: false,
                  ),
                  const SizedBox(height: 10),
                  Text(
                    cleanUsername.isEmpty
                        ? LinDoAuthCopy.WELCOME_GREETING.text
                        : '${LinDoAuthCopy.WELCOME_GREETING.text} ${LinDoCommonCopy.AT_USERNAME.format(cleanUsername)}',
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      color: palette.inkPrimary,
                      fontSize: scope.sp(17.5),
                      fontWeight: FontWeight.w700,
                      height: 1.35,
                    ),
                  ),
                  const SizedBox(height: 8),
                  Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(
                        LinDoCommonCopy.SLOGAN_DOT.text,
                        style: TextStyle(
                          color: palette.inkSecondary,
                          fontSize: scope.sp(12.0),
                          fontWeight: FontWeight.w500,
                        ),
                      ),
                      const SizedBox(width: 6),
                      LinDoWaveDots(
                        color: palette.logoDoColor,
                        dotSize: 4.0,
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
  }
}
