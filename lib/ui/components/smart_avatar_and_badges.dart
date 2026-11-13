import 'dart:collection';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';

import '../../core/copy/lindo_copy.dart';
import '../../core/models/lindo_models.dart';
import '../../core/platform/lindo_platform_channels.dart';
import '../../core/site/linux_do_emoji_catalog.dart';
import '../../core/site/linux_do_site_rules.dart';
import '../theme/lindo_theme.dart';
import 'lindo_icons.dart';

const Set<String> _kBundledBadgeSvgNames = <String>{
  'angles_up',
  'at',
  'baby',
  'book_open_reader',
  'bullhorn',
  'certificate',
  'cube',
  'dragon',
  'envelope',
  'fab_apple',
  'fab_git_alt',
  'face_smile',
  'far_eye',
  'far_pen_to_square',
  'file_lines',
  'file_signature',
  'flag',
  'hammer',
  'heart',
  'link',
  'medal',
  'pen',
  'pencil',
  'quote_right',
  'reply',
  'seedling',
  'share_nodes',
  'shield_halved',
  'square_check',
  'stamp',
  'user',
  'user_pen',
  'user_plus',
  'water',
};

const Map<String, String> _kBadgeIconAliases = <String, String>{
  'eye': 'far_eye',
  'far_fa_eye': 'far_eye',
  'pen_to_square': 'far_pen_to_square',
  'edit': 'far_pen_to_square',
  'far_edit': 'far_pen_to_square',
  'git': 'fab_git_alt',
  'git_alt': 'fab_git_alt',
  'github': 'fab_git_alt',
  'apple': 'fab_apple',
  'smile': 'face_smile',
  'far_smile': 'face_smile',
  'book_reader': 'book_open_reader',
  'book': 'book_open_reader',
  'book_open': 'book_open_reader',
  'shield': 'shield_halved',
  'shield_alt': 'shield_halved',
  'user_shield': 'shield_halved',
  'check_square': 'square_check',
  'check': 'square_check',
  'share': 'share_nodes',
  'share_alt': 'share_nodes',
  'external_link_alt': 'link',
  'quote_left': 'quote_right',
  'award': 'medal',
  'trophy': 'medal',
  'crown': 'medal',
  'star': 'certificate',
  'plus': 'user_plus',
  'user_edit': 'user_pen',
  'file_alt': 'file_lines',
  'angle_double_up': 'angles_up',
  'tint': 'water',
  'droplet': 'water',
};

String? resolveLinDoBadgeSvgAsset(GrantedBadge badge) {
  final rawIcon = (badge.icon ?? '').trim().toLowerCase();
  if (rawIcon.isNotEmpty) {
    final normalized = _normalizeBadgeIconToken(rawIcon);
    if (_kBundledBadgeSvgNames.contains(normalized)) {
      return 'assets/vectors/badges/$normalized.svg';
    }
    final alias = _kBadgeIconAliases[normalized];
    if (alias != null && _kBundledBadgeSvgNames.contains(alias)) {
      return 'assets/vectors/badges/$alias.svg';
    }
  }

  final combined = '${badge.slug} ${badge.name}'.toLowerCase();
  if (combined.contains('reader') || combined.contains('阅读')) {
    return 'assets/vectors/badges/book_open_reader.svg';
  }
  if (combined.contains('link') || combined.contains('链接')) {
    return 'assets/vectors/badges/link.svg';
  }
  if (combined.contains('quote') || combined.contains('引用')) {
    return 'assets/vectors/badges/quote_right.svg';
  }
  if (combined.contains('like') ||
      combined.contains('love') ||
      combined.contains('赞') ||
      combined.contains('心')) {
    return 'assets/vectors/badges/heart.svg';
  }
  if (combined.contains('welcome') || combined.contains('欢迎')) {
    return 'assets/vectors/badges/face_smile.svg';
  }
  if (combined.contains('wiki') || combined.contains('编辑')) {
    return 'assets/vectors/badges/far_pen_to_square.svg';
  }
  if (combined.contains('reply') || combined.contains('回复')) {
    return 'assets/vectors/badges/reply.svg';
  }
  if (combined.contains('flag') || combined.contains('举报')) {
    return 'assets/vectors/badges/flag.svg';
  }
  if (combined.contains('share') || combined.contains('分享')) {
    return 'assets/vectors/badges/share_nodes.svg';
  }
  if (combined.contains('seed') || combined.contains('新芽')) {
    return 'assets/vectors/badges/seedling.svg';
  }
  if (combined.contains('mention') || combined.contains('@')) {
    return 'assets/vectors/badges/at.svg';
  }
  if (combined.contains('invite') || combined.contains('邀请')) {
    return 'assets/vectors/badges/user_plus.svg';
  }
  if (combined.contains('profile') || combined.contains('资料') || combined.contains('自传')) {
    return 'assets/vectors/badges/user_pen.svg';
  }
  if (combined.contains('trust') ||
      combined.contains('leader') ||
      combined.contains('regular') ||
      combined.contains('活跃')) {
    return 'assets/vectors/badges/shield_halved.svg';
  }
  if (combined.contains('member') || combined.contains('basic') || combined.contains('成员')) {
    return 'assets/vectors/badges/user.svg';
  }
  if (combined.contains('anniversary') || combined.contains('周年')) {
    return 'assets/vectors/badges/medal.svg';
  }
  if (combined.contains('certified') || combined.contains('licensed') || combined.contains('认证')) {
    return 'assets/vectors/badges/certificate.svg';
  }
  return null;
}

String _normalizeBadgeIconToken(String raw) {
  var s = raw.trim().toLowerCase();
  if (s.startsWith('far ')) {
    final rest = s
        .substring(4)
        .trim()
        .replaceFirst(RegExp(r'^fa-'), '')
        .replaceAll('-', '_');
    return 'far_$rest';
  }
  if (s.startsWith('fab ')) {
    final rest = s
        .substring(4)
        .trim()
        .replaceFirst(RegExp(r'^fa-'), '')
        .replaceAll('-', '_');
    return 'fab_$rest';
  }
  s = s
      .replaceAll(RegExp(r'\b(fas|fa-solid|fa-regular|fa-brands)\b'), '')
      .trim()
      .replaceFirst(RegExp(r'^d-icon-'), '')
      .replaceFirst(RegExp(r'^fa-'), '')
      .replaceAll(RegExp(r'[\s-]+'), '_');
  return s;
}

/// 智能跨通道网络图片组件：
/// 1. 约 256 项内存 LRU `Uint8List` 缓存，列表往返滚动 0ms 瞬时命中；
/// 2. 当 `LinDoBrowserBridge.instance.isAvailable` 为真时优先经原生 WebView Cookie 通道拉取二进制字节，自动携带 Cloudflare 凭证；
/// 3. 离线 / 测试环境自动无感回退至高清抗锯齿 `Image.network` 或 `errorFallback`。
class LinDoNetworkImage extends StatefulWidget {
  static const int _maxCacheEntries = 256;
  static final LinkedHashMap<String, Uint8List> _lruBytesCache =
      LinkedHashMap<String, Uint8List>();
  static final Map<String, Future<Uint8List?>> _inFlightFetches =
      <String, Future<Uint8List?>>{};

  static void clearMemoryCache() {
    _lruBytesCache.clear();
    _inFlightFetches.clear();
  }

  static Uint8List? _readCache(String key) {
    final hit = _lruBytesCache.remove(key);
    if (hit != null) {
      _lruBytesCache[key] = hit;
    }
    return hit;
  }

  static void _writeCache(String key, Uint8List bytes) {
    if (bytes.isEmpty) return;
    _lruBytesCache.remove(key);
    _lruBytesCache[key] = bytes;
    while (_lruBytesCache.length > _maxCacheEntries) {
      _lruBytesCache.remove(_lruBytesCache.keys.first);
    }
  }

  final String url;
  final double? width;
  final double? height;
  final BoxFit fit;
  final BorderRadius? borderRadius;
  final Widget? placeholder;
  final Widget? errorFallback;

  const LinDoNetworkImage({
    super.key,
    required this.url,
    this.width,
    this.height,
    this.fit = BoxFit.cover,
    this.borderRadius,
    this.placeholder,
    this.errorFallback,
  });

  @override
  State<LinDoNetworkImage> createState() => _LinDoNetworkImageState();
}

class _LinDoNetworkImageState extends State<LinDoNetworkImage> {
  String _resolvedUrl = '';
  Uint8List? _memoryBytes;
  bool _bridgeLoading = false;

  @override
  void initState() {
    super.initState();
    _prepareImageSource();
  }

  @override
  void didUpdateWidget(covariant LinDoNetworkImage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.url != widget.url) {
      _prepareImageSource();
    }
  }

  void _prepareImageSource() {
    final resolved = resolveDiscourseUrl(widget.url);
    _resolvedUrl = resolved;
    if (resolved.isEmpty) {
      _memoryBytes = null;
      _bridgeLoading = false;
      return;
    }

    final cached = LinDoNetworkImage._readCache(resolved);
    if (cached != null) {
      _memoryBytes = cached;
      _bridgeLoading = false;
      return;
    }

    _memoryBytes = null;
    if (LinDoBrowserBridge.instance.isAvailable) {
      _bridgeLoading = true;
      _fetchViaBridge(resolved);
    } else {
      _bridgeLoading = false;
    }
  }

  Future<void> _fetchViaBridge(String targetUrl) async {
    Future<Uint8List?> pending = LinDoNetworkImage._inFlightFetches[targetUrl] ??=
        LinDoBrowserBridge.instance.fetchBinary(targetUrl);
    Uint8List? bytes;
    try {
      bytes = await pending;
    } catch (_) {
      bytes = null;
    } finally {
      if (identical(LinDoNetworkImage._inFlightFetches[targetUrl], pending)) {
        LinDoNetworkImage._inFlightFetches.remove(targetUrl);
      }
    }

    if (bytes != null && bytes.isNotEmpty) {
      LinDoNetworkImage._writeCache(targetUrl, bytes);
    }
    if (!mounted || _resolvedUrl != targetUrl) return;
    setState(() {
      _memoryBytes = (bytes != null && bytes.isNotEmpty) ? bytes : null;
      _bridgeLoading = false;
    });
  }

  Widget _buildDefaultPlaceholder(BuildContext context) {
    if (widget.placeholder != null) return widget.placeholder!;
    final palette = LinDoThemeScope.paletteOf(context);
    return SizedBox(
      width: widget.width,
      height: widget.height,
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: palette.subtleSurface,
          borderRadius: widget.borderRadius,
        ),
      ),
    );
  }

  Widget _buildDefaultError(BuildContext context) {
    if (widget.errorFallback != null) return widget.errorFallback!;
    if (widget.placeholder != null) return widget.placeholder!;
    final palette = LinDoThemeScope.paletteOf(context);
    return SizedBox(
      width: widget.width,
      height: widget.height,
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: palette.subtleSurface,
          borderRadius: widget.borderRadius,
        ),
        child: Center(
          child: Icon(
            LinDoIcons.Image,
            size: ((widget.width ?? widget.height ?? 28.0) * 0.45).clamp(12.0, 22.0),
            color: palette.inkTertiary,
          ),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    if (_resolvedUrl.isEmpty) {
      return _buildDefaultError(context);
    }

    Widget content;
    if (_memoryBytes != null) {
      content = Image.memory(
        _memoryBytes!,
        width: widget.width,
        height: widget.height,
        fit: widget.fit,
        gaplessPlayback: true,
        filterQuality: FilterQuality.medium,
        isAntiAlias: true,
        errorBuilder: (context, _, _) => _buildDefaultError(context),
      );
    } else if (_bridgeLoading) {
      content = _buildDefaultPlaceholder(context);
    } else {
      content = Image.network(
        _resolvedUrl,
        width: widget.width,
        height: widget.height,
        fit: widget.fit,
        gaplessPlayback: true,
        filterQuality: FilterQuality.medium,
        isAntiAlias: true,
        loadingBuilder: (context, child, loadingProgress) {
          if (loadingProgress == null) return child;
          return _buildDefaultPlaceholder(context);
        },
        errorBuilder: (context, _, _) => _buildDefaultError(context),
      );
    }

    if (widget.borderRadius != null) {
      return ClipRRect(
        borderRadius: widget.borderRadius!,
        child: content,
      );
    }
    return content;
  }
}

class SmartAvatar extends StatelessWidget {
  final String avatarUrl;
  final String fallbackLabel;
  final double size;
  final FlairInfo? flair;
  final int? trustLevel;
  final bool isAdmin;
  final bool isModerator;
  final VoidCallback? onTap;
  final String? animatedAvatar;
  final String? primaryGroupName;

  const SmartAvatar({
    super.key,
    String? avatarUrl,
    String? avatarTemplate,
    String? fallbackLabel,
    String? username,
    String? displayName,
    this.size = 38,
    this.flair,
    this.trustLevel,
    this.isAdmin = false,
    this.isModerator = false,
    this.onTap,
    this.animatedAvatar,
    this.primaryGroupName,
  })  : avatarUrl = avatarUrl ?? avatarTemplate ?? '',
        fallbackLabel = fallbackLabel ?? displayName ?? username ?? '';

  String _computeMonogram() {
    final cleaned = fallbackLabel.trim().replaceFirst(RegExp(r'^@+'), '');
    if (cleaned.isEmpty) return 'L';
    final chars = Characters(cleaned);
    return chars.isEmpty ? 'L' : chars.first.toUpperCase();
  }

  Widget _buildFallbackCircle(BuildContext context, BorderRadius radius) {
    final scope = LinDoThemeScope.of(context);
    final palette = scope.palette;
    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(
        color: palette.tonalPill,
        borderRadius: radius,
      ),
      alignment: Alignment.center,
      child: Text(
        _computeMonogram(),
        maxLines: 1,
        style: TextStyle(
          color: palette.inkSecondary,
          fontSize: scope.sp((size * 0.40).clamp(10.0, 22.0)),
          fontWeight: FontWeight.w700,
          height: 1.0,
        ),
      ),
    );
  }

  Widget? _buildCornerBadge(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    final badgeSize = (size * 0.38).clamp(13.0, 20.0);
    final hasFlair = flair != null && flair!.hasFlair;

    if (!hasFlair && !isAdmin && !isModerator) {
      return null;
    }

    if (hasFlair) {
      final rawFlairUrl = flair!.url!.trim();
      final bgColor = (flair!.bgColorHex ?? '').trim().isNotEmpty
          ? parseHexColor(flair!.bgColorHex, fallback: palette.cardSurface)
          : palette.cardSurface;
      final fgColor = (flair!.fgColorHex ?? '').trim().isNotEmpty
          ? parseHexColor(flair!.fgColorHex, fallback: palette.logoDoColor)
          : palette.logoDoColor;

      final isImageLike = rawFlairUrl.contains('/') ||
          rawFlairUrl.endsWith('.png') ||
          rawFlairUrl.endsWith('.webp') ||
          rawFlairUrl.endsWith('.svg') ||
          rawFlairUrl.startsWith('upload://');

      Widget inner;
      if (isImageLike) {
        final resolvedFlairUrl = resolveDiscourseUrl(rawFlairUrl);
        inner = LinDoNetworkImage(
          url: resolvedFlairUrl,
          width: badgeSize * 0.76,
          height: badgeSize * 0.76,
          fit: BoxFit.contain,
          errorFallback: Icon(
            LinDoIcons.VerifiedBadge,
            size: badgeSize * 0.68,
            color: fgColor,
          ),
        );
      } else if (rawFlairUrl.startsWith(':') && rawFlairUrl.endsWith(':')) {
        final emojiUrl = LinuxDoEmojiCatalog.instance.resolveEmojiUrl(rawFlairUrl);
        final unicode = LinuxDoEmojiCatalog.instance.resolveUnicodeEmoji(rawFlairUrl);
        inner = emojiUrl != null
            ? LinDoNetworkImage(
                url: emojiUrl,
                width: badgeSize * 0.76,
                height: badgeSize * 0.76,
                fit: BoxFit.contain,
                errorFallback: Text(
                  unicode ?? '✦',
                  style: TextStyle(fontSize: badgeSize * 0.62, height: 1.0),
                ),
              )
            : Text(
                unicode ?? '✦',
                style: TextStyle(fontSize: badgeSize * 0.62, height: 1.0),
              );
      } else {
        inner = Icon(
          isAdmin
              ? LinDoIcons.Crown
              : isModerator
                  ? LinDoIcons.ShieldCheck
                  : LinDoIcons.VerifiedBadge,
          size: badgeSize * 0.66,
          color: fgColor,
        );
      }

      return Container(
        width: badgeSize,
        height: badgeSize,
        decoration: BoxDecoration(
          color: bgColor,
          shape: BoxShape.circle,
          border: Border.all(color: palette.cardSurface, width: 1.4),
        ),
        alignment: Alignment.center,
        child: inner,
      );
    }

    final staffColor =
        isAdmin ? const Color(0xFFF59E0B) : const Color(0xFF10B981);
    return Container(
      width: badgeSize,
      height: badgeSize,
      decoration: BoxDecoration(
        color: staffColor,
        shape: BoxShape.circle,
        border: Border.all(color: palette.cardSurface, width: 1.4),
      ),
      alignment: Alignment.center,
      child: Icon(
        isAdmin ? LinDoIcons.Crown : LinDoIcons.ShieldCheck,
        size: badgeSize * 0.60,
        color: Colors.white,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final scope = LinDoThemeScope.of(context);
    final palette = scope.palette;
    final useSquare = isSquareAvatarGroup(primaryGroupName);
    final radius = useSquare
        ? BorderRadius.circular(size * 0.24)
        : BorderRadius.circular(size / 2.0);

    final targetPixelSize = (size * 2.5).round().clamp(48, 288);
    final resolvedUrl = resolveDiscourseAvatarUrl(
      avatarUrl,
      size: targetPixelSize,
      animatedAvatar: scope.enableAnimations ? animatedAvatar : null,
    );

    Color ringColor = palette.hairline;
    double ringWidth = 0.75;
    if (isAdmin) {
      ringColor = const Color(0xFFF59E0B);
      ringWidth = 1.4;
    } else if (isModerator) {
      ringColor = const Color(0xFF10B981);
      ringWidth = 1.3;
    } else if ((trustLevel ?? 0) >= 3) {
      ringColor = palette.logoDoColor.withValues(alpha: 0.42);
      ringWidth = 1.0;
    }

    final fallback = _buildFallbackCircle(context, radius);
    final cornerBadge = _buildCornerBadge(context);

    Widget avatarStack = SizedBox(
      width: size,
      height: size,
      child: Stack(
        clipBehavior: Clip.none,
        children: [
          Positioned.fill(
            child: DecoratedBox(
              decoration: BoxDecoration(
                borderRadius: radius,
                border: Border.all(color: ringColor, width: ringWidth),
              ),
              child: Padding(
                padding: EdgeInsets.all(ringWidth),
                child: resolvedUrl.isEmpty
                    ? fallback
                    : LinDoNetworkImage(
                        url: resolvedUrl,
                        width: size,
                        height: size,
                        fit: BoxFit.cover,
                        borderRadius: radius,
                        placeholder: fallback,
                        errorFallback: fallback,
                      ),
              ),
            ),
          ),
          if (cornerBadge != null)
            Positioned(
              right: -1.5,
              bottom: -1.5,
              child: cornerBadge,
            ),
        ],
      ),
    );

    if (onTap == null) return avatarStack;

    return MouseRegion(
      cursor: SystemMouseCursors.click,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: onTap,
        child: avatarStack,
      ),
    );
  }
}

class UniversalBadgeChip extends StatelessWidget {
  final GrantedBadge? badge;
  final String? label;
  final Color? backgroundColor;
  final Color? foregroundColor;
  final IconData? icon;
  final bool compact;
  final VoidCallback? onTap;

  const UniversalBadgeChip({
    super.key,
    this.badge,
    this.label,
    this.backgroundColor,
    this.foregroundColor,
    this.icon,
    this.compact = false,
    this.onTap,
  });

  ({Color bg, Color fg, Color iconTint, Color border}) _resolveTierStyle(
    LinDoThemePalette palette,
    BadgeType badgeType,
  ) {
    switch (badgeType) {
      case BadgeType.GOLD:
        return palette.isDark
            ? (
                bg: const Color(0xFF38290A),
                fg: const Color(0xFFFDE68A),
                iconTint: const Color(0xFFE7C300),
                border: const Color(0x66F59E0B),
              )
            : (
                bg: const Color(0xFFFEF7E0),
                fg: const Color(0xFF92400E),
                iconTint: const Color(0xFFD97706),
                border: const Color(0x59F59E0B),
              );
      case BadgeType.SILVER:
        return palette.isDark
            ? (
                bg: const Color(0xFF1F2530),
                fg: const Color(0xFFE2E8F0),
                iconTint: const Color(0xFFC0C0C0),
                border: const Color(0x5294A3B8),
              )
            : (
                bg: const Color(0xFFF3F5F8),
                fg: const Color(0xFF334155),
                iconTint: const Color(0xFF64748B),
                border: const Color(0x5294A3B8),
              );
      case BadgeType.BRONZE:
        return palette.isDark
            ? (
                bg: const Color(0xFF2E1E12),
                fg: const Color(0xFFFED7AA),
                iconTint: const Color(0xFFCD7F32),
                border: const Color(0x59CD7F32),
              )
            : (
                bg: const Color(0xFFFCF3EA),
                fg: const Color(0xFF7C2D12),
                iconTint: const Color(0xFFB45309),
                border: const Color(0x4DCD7F32),
              );
    }
  }

  Widget _buildBadgeIcon(GrantedBadge targetBadge, double iconSize, Color tint) {
    final svgAsset = resolveLinDoBadgeSvgAsset(targetBadge);
    if (svgAsset != null) {
      return SvgPicture.asset(
        svgAsset,
        width: iconSize,
        height: iconSize,
        fit: BoxFit.contain,
        colorFilter: ColorFilter.mode(tint, BlendMode.srcIn),
      );
    }

    final rawImageUrl = (targetBadge.imageUrl ?? '').trim();
    if (rawImageUrl.isNotEmpty) {
      return LinDoNetworkImage(
        url: rawImageUrl,
        width: iconSize,
        height: iconSize,
        fit: BoxFit.contain,
        errorFallback: Icon(
          targetBadge.badgeType == BadgeType.GOLD
              ? LinDoIcons.Trophy
              : targetBadge.badgeType == BadgeType.SILVER
                  ? LinDoIcons.VerifiedBadge
                  : LinDoIcons.Seedling,
          size: iconSize,
          color: tint,
        ),
      );
    }

    return Icon(
      targetBadge.badgeType == BadgeType.GOLD
          ? LinDoIcons.Trophy
          : targetBadge.badgeType == BadgeType.SILVER
              ? LinDoIcons.VerifiedBadge
              : LinDoIcons.Seedling,
      size: iconSize,
      color: tint,
    );
  }

  @override
  Widget build(BuildContext context) {
    final scope = LinDoThemeScope.of(context);
    final palette = scope.palette;
    final iconSize = compact ? 12.0 : 14.0;
    final borderRadius = BorderRadius.circular(compact ? 8.0 : 10.0);

    final targetBadge = badge;
    if (targetBadge == null) {
      final resolvedBg = backgroundColor ?? palette.tonalPill;
      final resolvedFg = foregroundColor ?? palette.inkSecondary;
      final resolvedLabel = label ?? '';
      final simpleChip = Container(
        padding: EdgeInsets.symmetric(
          horizontal: compact ? 7.0 : 9.5,
          vertical: compact ? 3.0 : 4.5,
        ),
        decoration: BoxDecoration(
          color: resolvedBg,
          borderRadius: borderRadius,
          border: Border.all(
            color: resolvedFg.withValues(alpha: 0.18),
            width: 0.7,
          ),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (icon != null) ...[
              Icon(icon, size: iconSize, color: resolvedFg),
              SizedBox(width: compact ? 4.0 : 5.0),
            ],
            Flexible(
              child: Text(
                resolvedLabel,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  color: resolvedFg,
                  fontSize: scope.sp(compact ? 11.0 : 12.0),
                  fontWeight: FontWeight.w600,
                  height: 1.2,
                ),
              ),
            ),
          ],
        ),
      );
      if (onTap == null) return simpleChip;
      return Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: onTap,
          borderRadius: borderRadius,
          child: simpleChip,
        ),
      );
    }

    final style = _resolveTierStyle(palette, targetBadge.badgeType);
    final resolvedBg = backgroundColor ?? style.bg;
    final resolvedFg = foregroundColor ?? style.fg;

    final chip = Container(
      padding: EdgeInsets.symmetric(
        horizontal: compact ? 7.0 : 9.5,
        vertical: compact ? 3.0 : 4.5,
      ),
      decoration: BoxDecoration(
        color: resolvedBg,
        borderRadius: borderRadius,
        border: Border.all(color: style.border, width: 0.75),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          _buildBadgeIcon(targetBadge, iconSize, style.iconTint),
          SizedBox(width: compact ? 4.5 : 5.5),
          Flexible(
            child: Text(
              label ?? targetBadge.name,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                color: resolvedFg,
                fontSize: scope.sp(compact ? 11.0 : 12.0),
                fontWeight: FontWeight.w600,
                height: 1.2,
              ),
            ),
          ),
          if (targetBadge.count > 1 && label == null) ...[
            const SizedBox(width: 4),
            Text(
              LinDoCommonCopy.COUNT_MULTIPLIER.format(targetBadge.count),
              style: TextStyle(
                color: resolvedFg.withValues(alpha: 0.78),
                fontSize: scope.sp(compact ? 10.0 : 11.0),
                fontWeight: FontWeight.w700,
                height: 1.2,
              ),
            ),
          ],
        ],
      ),
    );

    if (onTap == null) return chip;

    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        borderRadius: borderRadius,
        child: chip,
      ),
    );
  }
}

class UserTitleTag extends StatelessWidget {
  final String? title;
  final String? primaryGroupName;
  final int trustLevel;
  final bool isTopicOwner;
  final bool isAdmin;
  final bool isModerator;

  const UserTitleTag({
    super.key,
    this.title,
    this.primaryGroupName,
    this.trustLevel = 1,
    this.isTopicOwner = false,
    this.isAdmin = false,
    this.isModerator = false,
  });

  @override
  Widget build(BuildContext context) {
    final scope = LinDoThemeScope.of(context);
    final palette = scope.palette;
    final cleanTitle = (title ?? '').trim();
    final specialGroup = SpecialUserGroup.fromGroupName(primaryGroupName);
    final isHolo = isHolographicTitle(primaryGroupName: primaryGroupName);

    final pills = <Widget>[];

    if (isTopicOwner) {
      pills.add(
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 6.0, vertical: 1.8),
          decoration: BoxDecoration(
            color: palette.activeChipBg,
            borderRadius: BorderRadius.circular(6.0),
          ),
          child: Text(
            LinDoReaderCopy.OP_BADGE.text,
            style: TextStyle(
              color: palette.activeChipText,
              fontSize: scope.sp(10.5),
              fontWeight: FontWeight.w700,
              height: 1.15,
            ),
          ),
        ),
      );
    }

    if (isAdmin || isModerator) {
      final staffBg = isAdmin
          ? (palette.isDark ? const Color(0xFF3B2A0A) : const Color(0xFFFEF3C7))
          : (palette.isDark ? const Color(0xFF0E3226) : const Color(0xFFECFDF5));
      final staffFg = isAdmin
          ? (palette.isDark ? const Color(0xFFFCD34D) : const Color(0xFFB45309))
          : (palette.isDark ? const Color(0xFF6EE7B7) : const Color(0xFF047857));
      pills.add(
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 6.0, vertical: 1.8),
          decoration: BoxDecoration(
            color: staffBg,
            borderRadius: BorderRadius.circular(6.0),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(
                isAdmin ? LinDoIcons.Crown : LinDoIcons.ShieldCheck,
                size: 11,
                color: staffFg,
              ),
              const SizedBox(width: 2.5),
              Text(
                LinDoReaderCopy.MODERATOR_BADGE.text,
                style: TextStyle(
                  color: staffFg,
                  fontSize: scope.sp(10.5),
                  fontWeight: FontWeight.w700,
                  height: 1.15,
                ),
              ),
            ],
          ),
        ),
      );
    }

    final displayLabel = cleanTitle.isNotEmpty
        ? cleanTitle
        : (trustLevel >= 0 ? 'Lv.$trustLevel' : '');

    if (displayLabel.isNotEmpty) {
      if (specialGroup.usesMerchantBadgeStyle) {
        pills.add(
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 6.5, vertical: 1.8),
            decoration: BoxDecoration(
              gradient: LinearGradient(
                colors: palette.isDark
                    ? const [Color(0xFF422C08), Color(0xFF2D1D06)]
                    : const [Color(0xFFFEF3C7), Color(0xFFFDE68A)],
              ),
              borderRadius: BorderRadius.circular(6.0),
              border: Border.all(
                color: const Color(0xFFF59E0B).withValues(alpha: 0.42),
                width: 0.7,
              ),
            ),
            child: Text(
              displayLabel,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                color: palette.isDark
                    ? const Color(0xFFFCD34D)
                    : const Color(0xFF92400E),
                fontSize: scope.sp(10.5),
                fontWeight: FontWeight.w700,
                height: 1.15,
              ),
            ),
          ),
        );
      } else if (isHolo || trustLevel >= 3) {
        pills.add(
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 6.5, vertical: 1.8),
            decoration: BoxDecoration(
              color: palette.tonalPill,
              borderRadius: BorderRadius.circular(6.0),
              border: Border.all(
                color: palette.logoDoColor.withValues(alpha: 0.30),
                width: 0.7,
              ),
            ),
            child: Text(
              displayLabel,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                color: palette.brandGold,
                fontSize: scope.sp(10.5),
                fontWeight: FontWeight.w600,
                height: 1.15,
              ),
            ),
          ),
        );
      } else {
        pills.add(
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 6.0, vertical: 1.8),
            decoration: BoxDecoration(
              color: palette.tonalPill,
              borderRadius: BorderRadius.circular(6.0),
            ),
            child: Text(
              displayLabel,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                color: palette.inkSecondary,
                fontSize: scope.sp(10.5),
                fontWeight: FontWeight.w500,
                height: 1.15,
              ),
            ),
          ),
        );
      }
    }

    if (pills.isEmpty) return const SizedBox.shrink();

    return Wrap(
      spacing: 4.5,
      runSpacing: 3.0,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: pills,
    );
  }
}

class CategoryMorandiChip extends StatelessWidget {
  final String categoryName;
  final String? colorHex;
  final bool selected;
  final bool compact;
  final VoidCallback? onTap;

  const CategoryMorandiChip({
    super.key,
    required this.categoryName,
    this.colorHex,
    this.selected = false,
    this.compact = false,
    this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final scope = LinDoThemeScope.of(context);
    final palette = scope.palette;
    final morandi = resolveMorandiCategoryColors(categoryName, palette.isDark);
    final dotColor = (colorHex != null && colorHex!.trim().isNotEmpty)
        ? parseHexColor(colorHex, fallback: morandi.fg)
        : morandi.fg;

    final bgColor = selected ? palette.activeChipBg : morandi.bg;
    final fgColor = selected ? palette.activeChipText : morandi.fg;
    final borderRadius = BorderRadius.circular(compact ? 7.0 : 9.0);

    final chip = Container(
      padding: EdgeInsets.symmetric(
        horizontal: compact ? 7.0 : 10.0,
        vertical: compact ? 3.0 : 5.0,
      ),
      decoration: BoxDecoration(
        color: bgColor,
        borderRadius: borderRadius,
        border: selected
            ? Border.all(
                color: palette.logoDoColor.withValues(alpha: 0.55),
                width: 1.0,
              )
            : Border.all(
                color: dotColor.withValues(alpha: 0.14),
                width: 0.65,
              ),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            width: compact ? 5.5 : 6.5,
            height: compact ? 5.5 : 6.5,
            decoration: BoxDecoration(
              color: dotColor,
              borderRadius: BorderRadius.circular(2.0),
            ),
          ),
          SizedBox(width: compact ? 4.5 : 6.0),
          Flexible(
            child: Text(
              categoryName,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                color: fgColor,
                fontSize: scope.sp(compact ? 11.0 : 12.0),
                fontWeight: selected ? FontWeight.w700 : FontWeight.w600,
                height: 1.2,
              ),
            ),
          ),
        ],
      ),
    );

    if (onTap == null) return chip;

    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        borderRadius: borderRadius,
        child: chip,
      ),
    );
  }
}
