// ignore_for_file: constant_identifier_names
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

import '../../core/copy/lindo_copy.dart';

enum BrandThemeOption {
  OFFICIAL_GOLD(
    key: 'OFFICIAL_GOLD',
    labelCopy: LinDoFeedCopy.THEME_OFFICIAL_GOLD,
    swatchColor: Color(0xFFF59E0B),
    logoDoColor: Color(0xFFF59E0B),
    tabIndicatorColor: Color(0xFFF59E0B),
    lightChipBg: Color(0xFFFEF3C7),
    lightChipText: Color(0xFFB45309),
    darkChipBg: Color(0xFF3F2E05),
    darkChipText: Color(0xFFFCD34D),
    publishBtnBg: Color(0xFF18181B),
    publishBtnContent: Color(0xFFFFB003),
  ),
  IDEAL_RED(
    key: 'IDEAL_RED',
    labelCopy: LinDoFeedCopy.THEME_IDEAL_RED,
    swatchColor: Color(0xFFE03E3E),
    logoDoColor: Color(0xFFE03E3E),
    tabIndicatorColor: Color(0xFFE03E3E),
    lightChipBg: Color(0xFFFDF2F2),
    lightChipText: Color(0xFFE03E3E),
    darkChipBg: Color(0xFF3F1515),
    darkChipText: Color(0xFFF87171),
    publishBtnBg: Color(0xFFE03E3E),
    publishBtnContent: Color(0xFFFFFFFF),
  ),
  GEEK_BLUE(
    key: 'GEEK_BLUE',
    labelCopy: LinDoFeedCopy.THEME_GEEK_BLUE,
    swatchColor: Color(0xFF2563EB),
    logoDoColor: Color(0xFF2563EB),
    tabIndicatorColor: Color(0xFF2563EB),
    lightChipBg: Color(0xFFEFF6FF),
    lightChipText: Color(0xFF1D4ED8),
    darkChipBg: Color(0xFF172554),
    darkChipText: Color(0xFF60A5FA),
    publishBtnBg: Color(0xFF2563EB),
    publishBtnContent: Color(0xFFFFFFFF),
  ),
  BAMBOO_GREEN(
    key: 'BAMBOO_GREEN',
    labelCopy: LinDoFeedCopy.THEME_BAMBOO_GREEN,
    swatchColor: Color(0xFF059669),
    logoDoColor: Color(0xFF059669),
    tabIndicatorColor: Color(0xFF059669),
    lightChipBg: Color(0xFFECFDF5),
    lightChipText: Color(0xFF047857),
    darkChipBg: Color(0xFF064E3B),
    darkChipText: Color(0xFF34D399),
    publishBtnBg: Color(0xFF059669),
    publishBtnContent: Color(0xFFFFFFFF),
  );

  final String key;
  final LinDoFeedCopy labelCopy;
  final Color swatchColor;
  final Color logoDoColor;
  final Color tabIndicatorColor;
  final Color lightChipBg;
  final Color lightChipText;
  final Color darkChipBg;
  final Color darkChipText;
  final Color publishBtnBg;
  final Color publishBtnContent;

  const BrandThemeOption({
    required this.key,
    required this.labelCopy,
    required this.swatchColor,
    required this.logoDoColor,
    required this.tabIndicatorColor,
    required this.lightChipBg,
    required this.lightChipText,
    required this.darkChipBg,
    required this.darkChipText,
    required this.publishBtnBg,
    required this.publishBtnContent,
  });

  String get label => labelCopy.text;

  static BrandThemeOption fromKey(String? key) {
    for (final item in values) {
      if (item.key == key) return item;
    }
    return OFFICIAL_GOLD;
  }
}

enum LinDoIconBackground {
  White('white', LinDoMessagesCopy.ICON_BG_LIGHT, Color(0xFFFFFFFF)),
  Black('black', LinDoMessagesCopy.ICON_BG_DARK, Color(0xFF121212)),
  Amber('amber', LinDoMessagesCopy.ICON_BG_CLASSIC, Color(0xFFF6B73C));

  final String key;
  final LinDoMessagesCopy labelCopy;
  final Color color;
  const LinDoIconBackground(this.key, this.labelCopy, this.color);

  String get label => labelCopy.text;
}

enum LinDoIconArtwork {
  Happy('happy', LinDoMessagesCopy.ICON_ART_HAPPY, 'assets/vectors/expressions/happy.svg'),
  Classic('classic', LinDoMessagesCopy.ICON_ART_CLASSIC, null),
  Wink('wink', LinDoMessagesCopy.ICON_ART_WINK, 'assets/vectors/expressions/wink.svg'),
  Surprised('surprised', LinDoMessagesCopy.ICON_ART_SURPRISED, 'assets/vectors/expressions/surprised.svg'),
  Sleepy('sleepy', LinDoMessagesCopy.ICON_ART_SLEEPY, 'assets/vectors/expressions/sleepy.svg'),
  Official('official', LinDoCommonCopy.LINUX_DO, null);

  final String key;
  final LinDoCopyEntry labelCopy;
  final String? expressionSvgAsset;
  const LinDoIconArtwork(this.key, this.labelCopy, this.expressionSvgAsset);

  String get label => labelCopy.text;

  String get description => this == Official
      ? LinDoMessagesCopy.ICON_DESC_LOGO.text
      : LinDoMessagesCopy.ICON_DESC_MASCOT.format(label);

  List<LinDoIconBackground> get backgrounds => this == Classic
      ? LinDoIconBackground.values
      : const [LinDoIconBackground.White, LinDoIconBackground.Black];
}

class LinDoAppIcon {
  final LinDoIconArtwork artwork;
  final LinDoIconBackground background;

  const LinDoAppIcon(this.artwork, this.background);

  String get key => '${artwork.key}_${background.key}';

  static const LinDoAppIcon defaultIcon =
      LinDoAppIcon(LinDoIconArtwork.Happy, LinDoIconBackground.White);
  static const LinDoAppIcon classicIcon =
      LinDoAppIcon(LinDoIconArtwork.Classic, LinDoIconBackground.Amber);

  static List<LinDoAppIcon> get choices => [
        for (final art in LinDoIconArtwork.values)
          for (final bg in art.backgrounds) LinDoAppIcon(art, bg),
      ];

  static LinDoAppIcon fromKey(String? key) {
    if (key == null || key.isEmpty) return defaultIcon;
    for (final c in choices) {
      if (c.key == key) return c;
    }
    return defaultIcon;
  }

  @override
  bool operator ==(Object other) =>
      other is LinDoAppIcon &&
      other.artwork == artwork &&
      other.background == background;

  @override
  int get hashCode => Object.hash(artwork, background);
}

class LinDoThemePalette {
  final bool isDark;
  final BrandThemeOption themeOption;
  final Color canvas;
  final Color groupedCanvas;
  final Color cardSurface;
  final Color subtleSurface;
  final Color navBarSurface;
  final Color tonalPill;
  final Color onTonalPill;
  final Color inkPrimary;
  final Color inkSecondary;
  final Color inkTertiary;
  final Color hairline;
  final Color accent;
  final Color brandGold;
  final Color logoDoColor;
  final Color tabIndicatorColor;
  final Color activeChipBg;
  final Color activeChipText;
  final Color publishBtnBg;
  final Color publishBtnContent;
  final Color successContainer;
  final Color onSuccessContainer;

  const LinDoThemePalette({
    required this.isDark,
    required this.themeOption,
    required this.canvas,
    required this.groupedCanvas,
    required this.cardSurface,
    required this.subtleSurface,
    required this.navBarSurface,
    required this.tonalPill,
    required this.onTonalPill,
    required this.inkPrimary,
    required this.inkSecondary,
    required this.inkTertiary,
    required this.hairline,
    required this.accent,
    required this.brandGold,
    required this.logoDoColor,
    required this.tabIndicatorColor,
    required this.activeChipBg,
    required this.activeChipText,
    required this.publishBtnBg,
    required this.publishBtnContent,
    required this.successContainer,
    required this.onSuccessContainer,
  });
}

LinDoThemePalette buildLinDoPalette({
  required bool isDark,
  BrandThemeOption option = BrandThemeOption.OFFICIAL_GOLD,
}) {
  if (!isDark) {
    return LinDoThemePalette(
      isDark: false,
      themeOption: option,
      canvas: const Color(0xFFFFFFFF),
      groupedCanvas: const Color(0xFFF8F9FB),
      cardSurface: const Color(0xFFFFFFFF),
      subtleSurface: const Color(0xFFF3F4F6),
      navBarSurface: const Color(0xFFFFFFFF),
      tonalPill: const Color(0xFFEEF1F5),
      onTonalPill: const Color(0xFF111827),
      inkPrimary: const Color(0xFF111827),
      inkSecondary: const Color(0xFF525866),
      inkTertiary: const Color(0xFF6B7280),
      hairline: const Color(0xFFEBEEF2),
      accent: option == BrandThemeOption.OFFICIAL_GOLD
          ? const Color(0xFF18181B)
          : option.logoDoColor,
      brandGold: option == BrandThemeOption.OFFICIAL_GOLD
          ? const Color(0xFF936000)
          : option.lightChipText,
      logoDoColor: option.logoDoColor,
      tabIndicatorColor: option.tabIndicatorColor,
      activeChipBg: option.lightChipBg,
      activeChipText: option.lightChipText,
      publishBtnBg: option.publishBtnBg,
      publishBtnContent: option.publishBtnContent,
      successContainer: const Color(0xFFFDF6E9),
      onSuccessContainer: const Color(0xFFB7791F),
    );
  } else {
    return LinDoThemePalette(
      isDark: true,
      themeOption: option,
      canvas: const Color(0xFF101217),
      groupedCanvas: const Color(0xFF0C0E12),
      cardSurface: const Color(0xFF101217),
      subtleSurface: const Color(0xFF181B22),
      navBarSurface: const Color(0xFF11141B),
      tonalPill: const Color(0xFF212631),
      onTonalPill: const Color(0xFFF3F4F6),
      inkPrimary: const Color(0xFFF3F4F6),
      inkSecondary: const Color(0xFFA1A8B5),
      inkTertiary: const Color(0xFF7D8594),
      hairline: const Color(0xFF1E222B),
      accent: option.logoDoColor,
      brandGold: option.darkChipText,
      logoDoColor: option.logoDoColor,
      tabIndicatorColor: option.tabIndicatorColor,
      activeChipBg: option.darkChipBg,
      activeChipText: option.darkChipText,
      publishBtnBg: option == BrandThemeOption.OFFICIAL_GOLD
          ? const Color(0xFFF59E0B)
          : option.publishBtnBg,
      publishBtnContent: option == BrandThemeOption.OFFICIAL_GOLD
          ? const Color(0xFF111827)
          : const Color(0xFFFFFFFF),
      successContainer: const Color(0xFF3F2E05),
      onSuccessContainer: const Color(0xFFFCD34D),
    );
  }
}

({Color bg, Color fg}) resolveMorandiCategoryColors(String categoryName, bool isDark) {
  if (categoryName.contains('开发') ||
      categoryName.contains('快讯') ||
      categoryName.contains('读书')) {
    return isDark
        ? (bg: const Color(0xFF1E293B), fg: const Color(0xFF93C5FD))
        : (bg: const Color(0xFFEEF3F8), fg: const Color(0xFF3B6E9C));
  }
  if (categoryName.contains('资源') ||
      categoryName.contains('文档') ||
      categoryName.contains('扬帆')) {
    return isDark
        ? (bg: const Color(0xFF143327), fg: const Color(0xFF6EE7B7))
        : (bg: const Color(0xFFEDF6F0), fg: const Color(0xFF2E7D5B));
  }
  if (categoryName.contains('搞七') ||
      categoryName.contains('福利') ||
      categoryName.contains('市场')) {
    return isDark
        ? (bg: const Color(0xFF3B2A10), fg: const Color(0xFFFCD34D))
        : (bg: const Color(0xFFFDF6E9), fg: const Color(0xFFB7791F));
  }
  return isDark
      ? (bg: const Color(0xFF3B1A1F), fg: const Color(0xFFFCA5A5))
      : (bg: const Color(0xFFFCEFF0), fg: const Color(0xFFC53030));
}

Color parseHexColor(String? hex, {Color fallback = const Color(0xFF0088CC)}) {
  if (hex == null || hex.trim().isEmpty) return fallback;
  final clean = hex.trim().replaceFirst('#', '');
  if (clean.length == 6) {
    final val = int.tryParse('FF$clean', radix: 16);
    if (val != null) return Color(val);
  } else if (clean.length == 8) {
    final val = int.tryParse(clean, radix: 16);
    if (val != null) return Color(val);
  }
  return fallback;
}

/// 平台级抗锯齿字体族栈：
/// - macOS: `.AppleSystemUIFont` + `PingFang SC` + `Apple Color Emoji`
/// - Windows: `Segoe UI` + `Microsoft YaHei UI` + `Segoe UI Emoji`
/// - Android / 其他: 系统默认高清晰无衬线字体
String? resolvePlatformPrimaryFontFamily() {
  if (kIsWeb) return null;
  if (Platform.isWindows) return 'Microsoft YaHei UI';
  return null;
}

List<String> resolvePlatformFontFallback() {
  if (kIsWeb) return const ['sans-serif'];
  if (Platform.isMacOS) {
    return const ['PingFang SC', 'Hiragino Sans GB', 'Apple Color Emoji'];
  }
  if (Platform.isWindows) {
    return const ['Microsoft YaHei UI', 'Segoe UI', 'Segoe UI Emoji'];
  }
  return const ['Noto Sans CJK SC', 'Roboto', 'Noto Color Emoji'];
}

/// 像素网格对齐字号换算：消除 `0.92f` 等任意小数乘积造成的半像素发虚与笔画毛刺
double snapFontSize(double baseSp, double scale) {
  final raw = baseSp * scale;
  return (raw * 2.0).roundToDouble() / 2.0;
}

ThemeData buildLinDoMaterialTheme(LinDoThemePalette palette) {
  final fontFamily = resolvePlatformPrimaryFontFamily();
  final fallbacks = resolvePlatformFontFallback();
  final baseScheme = palette.isDark
      ? ColorScheme.dark(
          primary: palette.logoDoColor,
          onPrimary: const Color(0xFF101217),
          primaryContainer: palette.tonalPill,
          onPrimaryContainer: palette.onTonalPill,
          surface: palette.cardSurface,
          onSurface: palette.inkPrimary,
          surfaceContainerHighest: palette.subtleSurface,
          onSurfaceVariant: palette.inkSecondary,
          outlineVariant: palette.hairline,
        )
      : ColorScheme.light(
          primary: palette.logoDoColor,
          onPrimary: Colors.white,
          primaryContainer: palette.tonalPill,
          onPrimaryContainer: palette.onTonalPill,
          surface: palette.cardSurface,
          onSurface: palette.inkPrimary,
          surfaceContainerHighest: palette.subtleSurface,
          onSurfaceVariant: palette.inkSecondary,
          outlineVariant: palette.hairline,
        );

  return ThemeData(
    useMaterial3: true,
    brightness: palette.isDark ? Brightness.dark : Brightness.light,
    colorScheme: baseScheme,
    scaffoldBackgroundColor: palette.canvas,
    canvasColor: palette.canvas,
    dividerColor: palette.hairline,
    fontFamily: fontFamily,
    fontFamilyFallback: fallbacks,
    splashFactory: InkSparkle.splashFactory,
    visualDensity: VisualDensity.standard,
  );
}

bool shouldUseWideSplitLayout(double width, double height) {
  return width >= 840.0 && width >= height * 0.85;
}

class LinDoThemeScope extends InheritedWidget {
  final LinDoThemePalette palette;
  final LinDoAppIcon appIcon;
  final double fontScale;
  final bool enableAnimations;
  final double macTitleBarInset;
  final ValueChanged<String>? onOpenInAppWebUrl;
  final void Function(String title, String subtitle)? onRequestGuestLogin;

  const LinDoThemeScope({
    super.key,
    required this.palette,
    this.appIcon = LinDoAppIcon.defaultIcon,
    this.fontScale = 1.0,
    this.enableAnimations = true,
    this.macTitleBarInset = 0.0,
    this.onOpenInAppWebUrl,
    this.onRequestGuestLogin,
    required super.child,
  });

  double sp(double size) => snapFontSize(size, fontScale);

  static LinDoThemeScope of(BuildContext context) {
    final scope = context.dependOnInheritedWidgetOfExactType<LinDoThemeScope>();
    if (scope != null) return scope;
    return LinDoThemeScope(
      palette: buildLinDoPalette(isDark: false),
      child: const SizedBox.shrink(),
    );
  }

  static LinDoThemePalette paletteOf(BuildContext context) => of(context).palette;

  @override
  bool updateShouldNotify(LinDoThemeScope oldWidget) =>
      palette != oldWidget.palette ||
      appIcon != oldWidget.appIcon ||
      fontScale != oldWidget.fontScale ||
      enableAnimations != oldWidget.enableAnimations ||
      macTitleBarInset != oldWidget.macTitleBarInset;
}
