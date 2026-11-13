import 'dart:async';
import 'dart:collection';
import 'dart:math' as math;

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../core/copy/lindo_copy.dart';
import '../../core/models/lindo_models.dart';
import '../../core/platform/lindo_platform_channels.dart';
import '../../core/render/discourse_ast_renderer.dart';
import '../../core/site/linux_do_site_rules.dart';
import '../theme/lindo_theme.dart';
import 'lindo_icons.dart';
import 'smart_avatar_and_badges.dart';

typedef LinDoPollVoteCallback = FutureOr<dynamic> Function(
  String pollName,
  List<String> optionIds,
);

class PostRichContentView extends StatefulWidget {
  static const int _maxAstCacheSize = 180;
  static final LinkedHashMap<String, List<DiscourseAstNode>> _astLruCache =
      LinkedHashMap<String, List<DiscourseAstNode>>();

  static List<DiscourseAstNode> _parseMemoized(String cookedHtml) {
    if (cookedHtml.trim().isEmpty) return const <DiscourseAstNode>[];
    final cached = _astLruCache.remove(cookedHtml);
    if (cached != null) {
      _astLruCache[cookedHtml] = cached;
      return cached;
    }
    final parsed = List<DiscourseAstNode>.unmodifiable(
      parseDiscourseCookedHtml(cookedHtml),
    );
    if (cookedHtml.length <= 120000) {
      _astLruCache[cookedHtml] = parsed;
      while (_astLruCache.length > _maxAstCacheSize) {
        _astLruCache.remove(_astLruCache.keys.first);
      }
    }
    return parsed;
  }

  final String cookedHtml;
  final List<PollModel> polls;
  final int postId;
  final LinDoPollVoteCallback? onVotePoll;
  final void Function(int topicId, int? postNumber)? onTapInternalTopic;
  final ValueChanged<String>? onTapMention;
  final ValueChanged<String>? onTapUrl;

  const PostRichContentView({
    super.key,
    required this.cookedHtml,
    this.polls = const [],
    this.postId = 0,
    this.onVotePoll,
    this.onTapInternalTopic,
    this.onTapMention,
    this.onTapUrl,
  });

  @override
  State<PostRichContentView> createState() => _PostRichContentViewState();
}

class _PostRichContentViewState extends State<PostRichContentView> {
  String? _memoizedHtml;
  List<DiscourseAstNode> _nodes = const <DiscourseAstNode>[];
  List<String> _allPostImages = const <String>[];

  @override
  void initState() {
    super.initState();
    _syncParsedAst();
  }

  @override
  void didUpdateWidget(covariant PostRichContentView oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.cookedHtml != widget.cookedHtml) {
      _syncParsedAst();
    }
  }

  void _syncParsedAst() {
    if (_memoizedHtml == widget.cookedHtml) return;
    _memoizedHtml = widget.cookedHtml;
    _nodes = PostRichContentView._parseMemoized(widget.cookedHtml);
    final images = <String>[];
    _collectImagesFromBlocks(_nodes, images);
    _allPostImages = List<String>.unmodifiable(images);
  }

  void _collectImagesFromBlocks(
    List<DiscourseAstNode> nodes,
    List<String> out,
  ) {
    for (final node in nodes) {
      if (node is AstImageBlock) {
        final resolved = resolveDiscourseUrl(node.url);
        if (resolved.isNotEmpty && !out.contains(resolved)) {
          out.add(resolved);
        }
      } else if (node is AstParagraph) {
        _collectImagesFromInlines(node.inlines, out);
      } else if (node is AstQuoteBlock) {
        _collectImagesFromBlocks(node.children, out);
      } else if (node is AstDetailsBlock) {
        _collectImagesFromBlocks(node.children, out);
      } else if (node is AstSpoilerBlock) {
        _collectImagesFromBlocks(node.children, out);
      } else if (node is AstCalloutBlock) {
        _collectImagesFromBlocks(node.children, out);
      } else if (node is AstListBlock) {
        for (final item in node.items) {
          _collectImagesFromBlocks(item.children, out);
        }
      }
    }
  }

  void _collectImagesFromInlines(List<AstInline> inlines, List<String> out) {
    for (final inline in inlines) {
      if (inline is InlineImage) {
        final resolved = resolveDiscourseUrl(inline.url);
        if (resolved.isNotEmpty && !out.contains(resolved)) {
          out.add(resolved);
        }
      }
    }
  }

  void _handleOpenUrl(BuildContext context, String rawUrl) {
    final trimmed = rawUrl.trim();
    if (trimmed.isEmpty) return;

    if (trimmed.toLowerCase().startsWith('mailto:')) {
      LinDoPlatformBridge.instance.openExternalUrl(trimmed);
      return;
    }

    final resolved = resolveDiscourseUrl(trimmed);
    final internalTopic = parseInternalTopicUrl(resolved);
    if (internalTopic != null && widget.onTapInternalTopic != null) {
      widget.onTapInternalTopic!(
        internalTopic.topicId,
        internalTopic.postNumber,
      );
      return;
    }

    final uri = Uri.tryParse(resolved);
    if (uri != null &&
        (uri.host == 'linux.do' || uri.host.endsWith('.linux.do')) &&
        uri.pathSegments.length == 2 &&
        uri.pathSegments.first == 'u' &&
        widget.onTapMention != null) {
      final user = Uri.decodeComponent(uri.pathSegments[1]).trim();
      if (user.isNotEmpty) {
        widget.onTapMention!(user);
        return;
      }
    }

    if (widget.onTapUrl != null) {
      widget.onTapUrl!(resolved);
      return;
    }

    final scopeOpen = LinDoThemeScope.of(context).onOpenInAppWebUrl;
    if (scopeOpen != null) {
      scopeOpen(resolved);
      return;
    }

    LinDoPlatformBridge.instance.openExternalUrl(resolved);
  }

  void _handleOpenImageLightbox(BuildContext context, String imageUrl) {
    final resolved = resolveDiscourseUrl(imageUrl);
    if (resolved.isEmpty) return;
    final gallery = _allPostImages.contains(resolved)
        ? _allPostImages
        : <String>[resolved, ..._allPostImages];
    final initialIdx = math.max(0, gallery.indexOf(resolved));
    showDialog<void>(
      context: context,
      barrierColor: Colors.black.withValues(alpha: 0.90),
      builder: (dialogCtx) => _PostImageLightboxDialog(
        imageUrls: gallery,
        initialIndex: initialIdx,
      ),
    );
  }

  PollModel? _findPollByName(String pollName, Set<String> renderedPollNames) {
    if (widget.polls.isEmpty) return null;
    for (final poll in widget.polls) {
      if (poll.name == pollName) {
        renderedPollNames.add(poll.name);
        return poll;
      }
    }
    if (pollName == 'poll') {
      final first = widget.polls.first;
      renderedPollNames.add(first.name);
      return first;
    }
    return null;
  }

  @override
  Widget build(BuildContext context) {
    final renderedPollNames = <String>{};
    final blockWidgets = _buildBlockList(
      context,
      _nodes,
      renderedPollNames: renderedPollNames,
      depth: 0,
    );

    // Append any polls attached to PostItemModel that did not have an explicit DOM placeholder
    for (final extraPoll in widget.polls) {
      if (!renderedPollNames.contains(extraPoll.name)) {
        renderedPollNames.add(extraPoll.name);
        if (blockWidgets.isNotEmpty) {
          blockWidgets.add(const SizedBox(height: 12));
        }
        blockWidgets.add(
          _InteractivePollCard(
            poll: extraPoll,
            postId: widget.postId,
            onVotePoll: widget.onVotePoll,
          ),
        );
      }
    }

    if (blockWidgets.isEmpty) {
      return const SizedBox.shrink();
    }

    return SelectionArea(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        mainAxisSize: MainAxisSize.min,
        children: blockWidgets,
      ),
    );
  }

  List<Widget> _buildBlockList(
    BuildContext context,
    List<DiscourseAstNode> nodes, {
    required Set<String> renderedPollNames,
    required int depth,
  }) {
    if (depth > 8) {
      final scope = LinDoThemeScope.of(context);
      final palette = scope.palette;
      return <Widget>[
        Text(
          LinDoReaderCopy.AST_DEEP_NESTED.text,
          style: TextStyle(
            color: palette.inkTertiary,
            fontSize: scope.sp(13.0),
          ),
        ),
      ];
    }

    final children = <Widget>[];
    for (var i = 0; i < nodes.length; i++) {
      final widgetNode = _buildSingleBlock(
        context,
        nodes[i],
        renderedPollNames: renderedPollNames,
        depth: depth,
      );
      if (widgetNode == null) continue;
      if (children.isNotEmpty) {
        children.add(SizedBox(height: depth > 0 ? 8.0 : 10.5));
      }
      children.add(widgetNode);
    }
    return children;
  }

  Widget? _buildSingleBlock(
    BuildContext context,
    DiscourseAstNode node, {
    required Set<String> renderedPollNames,
    required int depth,
  }) {
    final scope = LinDoThemeScope.of(context);
    final palette = scope.palette;

    if (node is AstParagraph) {
      if (node.inlines.isEmpty) return null;
      return _RichInlineBlock(
        inlines: node.inlines,
        baseStyle: TextStyle(
          color: palette.inkPrimary,
          fontSize: scope.sp(15.0),
          height: 1.62,
        ),
        onTapUrl: (url) => _handleOpenUrl(context, url),
        onTapMention: widget.onTapMention,
        onTapImage: (url) => _handleOpenImageLightbox(context, url),
      );
    }

    if (node is AstHeading) {
      if (node.inlines.isEmpty) return null;
      final double baseSp;
      final FontWeight weight;
      switch (node.level) {
        case 1:
          baseSp = 20.5;
          weight = FontWeight.w800;
          break;
        case 2:
          baseSp = 18.0;
          weight = FontWeight.w800;
          break;
        case 3:
          baseSp = 16.5;
          weight = FontWeight.w700;
          break;
        default:
          baseSp = 15.5;
          weight = FontWeight.w700;
          break;
      }
      final headingText = _RichInlineBlock(
        inlines: node.inlines,
        baseStyle: TextStyle(
          color: palette.inkPrimary,
          fontSize: scope.sp(baseSp),
          fontWeight: weight,
          letterSpacing: -0.25,
          height: 1.38,
        ),
        onTapUrl: (url) => _handleOpenUrl(context, url),
        onTapMention: widget.onTapMention,
        onTapImage: (url) => _handleOpenImageLightbox(context, url),
      );
      if (node.level <= 2) {
        return Padding(
          padding: const EdgeInsets.only(top: 4, bottom: 2),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              headingText,
              const SizedBox(height: 5),
              Divider(height: 1, thickness: 0.7, color: palette.hairline),
            ],
          ),
        );
      }
      return Padding(
        padding: const EdgeInsets.only(top: 2),
        child: headingText,
      );
    }

    if (node is AstCodeBlock) {
      return _CodeBlockCard(
        language: node.language,
        code: node.code,
      );
    }

    if (node is AstQuoteBlock) {
      return _CollapsibleQuoteCard(
        quote: node,
        onTapJump: (node.topicId != null && widget.onTapInternalTopic != null)
            ? () => widget.onTapInternalTopic!(node.topicId!, node.postNumber)
            : null,
        onTapAuthor: (node.username != null && widget.onTapMention != null)
            ? () => widget.onTapMention!(node.username!)
            : null,
        childBuilder: (ctx) => Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          mainAxisSize: MainAxisSize.min,
          children: _buildBlockList(
            ctx,
            node.children,
            renderedPollNames: renderedPollNames,
            depth: depth + 1,
          ),
        ),
      );
    }

    if (node is AstDetailsBlock) {
      return _ExpandableDetailsCard(
        summary: node.summary,
        childBuilder: (ctx) => Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          mainAxisSize: MainAxisSize.min,
          children: _buildBlockList(
            ctx,
            node.children,
            renderedPollNames: renderedPollNames,
            depth: depth + 1,
          ),
        ),
      );
    }

    if (node is AstSpoilerBlock) {
      return _BlockSpoilerCard(
        childBuilder: (ctx) => Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          mainAxisSize: MainAxisSize.min,
          children: _buildBlockList(
            ctx,
            node.children,
            renderedPollNames: renderedPollNames,
            depth: depth + 1,
          ),
        ),
      );
    }

    if (node is AstCalloutBlock) {
      return _CalloutBlockCard(
        kind: node.kind,
        title: node.title,
        children: _buildBlockList(
          context,
          node.children,
          renderedPollNames: renderedPollNames,
          depth: depth + 1,
        ),
      );
    }

    if (node is AstListBlock) {
      if (node.items.isEmpty) return null;
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        mainAxisSize: MainAxisSize.min,
        children: List.generate(node.items.length, (idx) {
          final item = node.items[idx];
          Widget leading;
          if (item.checked != null) {
            leading = Padding(
              padding: const EdgeInsets.only(top: 3.5),
              child: Icon(
                item.checked!
                    ? LinDoIcons.CheckCircle
                    : Icons.radio_button_unchecked_rounded,
                size: 16,
                color: item.checked! ? palette.logoDoColor : palette.inkTertiary,
              ),
            );
          } else if (node.ordered) {
            leading = Padding(
              padding: const EdgeInsets.only(top: 1.5),
              child: Text(
                '${node.start + idx}.',
                style: TextStyle(
                  color: palette.inkSecondary,
                  fontSize: scope.sp(14.0),
                  fontWeight: FontWeight.w600,
                  height: 1.55,
                  fontFeatures: const [FontFeature.tabularFigures()],
                ),
              ),
            );
          } else {
            leading = Padding(
              padding: const EdgeInsets.only(top: 9.0, left: 3.0, right: 2.0),
              child: Container(
                width: 5.5,
                height: 5.5,
                decoration: BoxDecoration(
                  color: palette.inkSecondary,
                  shape: BoxShape.circle,
                ),
              ),
            );
          }

          final itemChildren = _buildBlockList(
            context,
            item.children,
            renderedPollNames: renderedPollNames,
            depth: depth + 1,
          );

          return Padding(
            padding: EdgeInsets.only(bottom: idx < node.items.length - 1 ? 6.0 : 0.0),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                SizedBox(
                  width: node.ordered ? 26 : 22,
                  child: Align(
                    alignment: Alignment.topLeft,
                    child: leading,
                  ),
                ),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    mainAxisSize: MainAxisSize.min,
                    children: itemChildren,
                  ),
                ),
              ],
            ),
          );
        }),
      );
    }

    if (node is AstTableBlock) {
      return _RichTableCard(
        table: node,
        onTapUrl: (url) => _handleOpenUrl(context, url),
        onTapMention: widget.onTapMention,
        onTapImage: (url) => _handleOpenImageLightbox(context, url),
      );
    }

    if (node is AstImageBlock) {
      final resolved = resolveDiscourseUrl(node.url);
      if (resolved.isEmpty) return null;
      return _PostImageBlockWidget(
        imageUrl: resolved,
        alt: node.alt,
        intrinsicWidth: node.width,
        intrinsicHeight: node.height,
        onTap: () => _handleOpenImageLightbox(context, resolved),
      );
    }

    if (node is AstOneboxCard) {
      return _OneboxPreviewCard(
        card: node,
        onTap: () => _handleOpenUrl(context, node.url),
      );
    }

    if (node is AstPollRef) {
      final matched = _findPollByName(node.pollName, renderedPollNames);
      if (matched != null) {
        return _InteractivePollCard(
          poll: matched,
          postId: widget.postId,
          onVotePoll: widget.onVotePoll,
        );
      }
      return Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 11),
        decoration: BoxDecoration(
          color: palette.subtleSurface,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: palette.hairline, width: 0.8),
        ),
        child: Row(
          children: [
            Icon(LinDoIcons.Poll, size: 17, color: palette.logoDoColor),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                widget.postId <= 0
                    ? LinDoReaderCopy.POLL_AVAILABLE_AFTER_PUBLISH.text
                    : LinDoReaderCopy.AST_COMMUNITY_POLL.text,
                style: TextStyle(
                  color: palette.inkSecondary,
                  fontSize: scope.sp(13.0),
                  fontWeight: FontWeight.w600,
                ),
              ),
            ),
          ],
        ),
      );
    }

    if (node is AstThematicBreak) {
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: Divider(height: 1, thickness: 0.8, color: palette.hairline),
      );
    }

    return null;
  }
}

class _RichInlineBlock extends StatefulWidget {
  final List<AstInline> inlines;
  final TextStyle baseStyle;
  final ValueChanged<String> onTapUrl;
  final ValueChanged<String>? onTapMention;
  final ValueChanged<String> onTapImage;

  const _RichInlineBlock({
    required this.inlines,
    required this.baseStyle,
    required this.onTapUrl,
    required this.onTapMention,
    required this.onTapImage,
  });

  @override
  State<_RichInlineBlock> createState() => _RichInlineBlockState();
}

class _RichInlineBlockState extends State<_RichInlineBlock> {
  final List<TapGestureRecognizer> _recognizers = <TapGestureRecognizer>[];

  void _disposeRecognizers() {
    for (final rec in _recognizers) {
      rec.dispose();
    }
    _recognizers.clear();
  }

  @override
  void dispose() {
    _disposeRecognizers();
    super.dispose();
  }

  TapGestureRecognizer _makeTapRecognizer(VoidCallback callback) {
    final recognizer = TapGestureRecognizer()..onTap = callback;
    _recognizers.add(recognizer);
    return recognizer;
  }

  @override
  Widget build(BuildContext context) {
    _disposeRecognizers();
    final scope = LinDoThemeScope.of(context);
    final palette = scope.palette;

    // Fast path for single plain unstyled text run
    if (widget.inlines.length == 1 && widget.inlines.first is InlineText) {
      final only = widget.inlines.first as InlineText;
      if (!only.bold &&
          !only.italic &&
          !only.strikethrough &&
          !only.code &&
          !only.spoiler &&
          only.linkUrl == null) {
        return Text(only.text, style: widget.baseStyle);
      }
    }

    final spans = <InlineSpan>[];
    for (final item in widget.inlines) {
      if (item is InlineLineBreak) {
        spans.add(const TextSpan(text: '\n'));
        continue;
      }

      if (item is InlineMention) {
        final cleanUser = item.username.trim().replaceFirst(RegExp(r'^@+'), '');
        spans.add(
          WidgetSpan(
            alignment: PlaceholderAlignment.middle,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 1.5),
              child: SelectionContainer.disabled(
                child: InkWell(
                  borderRadius: BorderRadius.circular(6),
                  onTap: () {
                    if (widget.onTapMention != null) {
                      widget.onTapMention!(cleanUser);
                    } else if (item.url != null && item.url!.isNotEmpty) {
                      widget.onTapUrl(item.url!);
                    }
                  },
                  child: Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 6.0,
                      vertical: 1.5,
                    ),
                    decoration: BoxDecoration(
                      color: palette.activeChipBg,
                      borderRadius: BorderRadius.circular(6),
                    ),
                    child: Text(
                      LinDoCommonCopy.AT_USERNAME.format(cleanUser),
                      style: TextStyle(
                        color: palette.activeChipText,
                        fontSize: scope.sp(13.0),
                        fontWeight: FontWeight.w600,
                        height: 1.25,
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        );
        continue;
      }

      if (item is InlineEmoji) {
        final fallbackText =
            (item.unicodeFallback != null && item.unicodeFallback!.isNotEmpty)
                ? item.unicodeFallback!
                : ':${item.name}:';
        spans.add(
          WidgetSpan(
            alignment: PlaceholderAlignment.middle,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 1.5),
              child: SelectionContainer.disabled(
                child: Tooltip(
                  message: ':${item.name}:',
                  child: SizedBox(
                    width: 19,
                    height: 19,
                    child: LinDoNetworkImage(
                      url: item.imageUrl,
                      width: 19,
                      height: 19,
                      fit: BoxFit.contain,
                      placeholder: Center(
                        child: Text(
                          item.unicodeFallback ?? '',
                          style: const TextStyle(fontSize: 14, height: 1.0),
                        ),
                      ),
                      errorFallback: Center(
                        child: Text(
                          fallbackText,
                          style: TextStyle(
                            fontSize: item.unicodeFallback != null ? 15 : 11,
                            height: 1.0,
                            color: palette.inkSecondary,
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        );
        continue;
      }

      if (item is InlineHashtag) {
        final label = item.label.startsWith('#') ? item.label : '#${item.label}';
        spans.add(
          WidgetSpan(
            alignment: PlaceholderAlignment.middle,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 1.5),
              child: SelectionContainer.disabled(
                child: InkWell(
                  borderRadius: BorderRadius.circular(6),
                  onTap: (item.url != null && item.url!.isNotEmpty)
                      ? () => widget.onTapUrl(item.url!)
                      : null,
                  child: Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 6.0,
                      vertical: 1.5,
                    ),
                    decoration: BoxDecoration(
                      color: palette.tonalPill,
                      borderRadius: BorderRadius.circular(6),
                    ),
                    child: Text(
                      label,
                      style: TextStyle(
                        color: palette.inkSecondary,
                        fontSize: scope.sp(12.5),
                        fontWeight: FontWeight.w600,
                        height: 1.25,
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        );
        continue;
      }

      if (item is InlineImage) {
        final resolved = resolveDiscourseUrl(item.url);
        if (resolved.isNotEmpty) {
          spans.add(
            WidgetSpan(
              alignment: PlaceholderAlignment.middle,
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 2.0),
                child: SelectionContainer.disabled(
                  child: GestureDetector(
                    onTap: () => widget.onTapImage(resolved),
                    child: LinDoNetworkImage(
                      url: resolved,
                      height: 22,
                      fit: BoxFit.contain,
                      borderRadius: BorderRadius.circular(4),
                    ),
                  ),
                ),
              ),
            ),
          );
        }
        continue;
      }

      if (item is InlineText) {
        if (item.spoiler) {
          spans.add(
            WidgetSpan(
              alignment: PlaceholderAlignment.middle,
              child: _InlineSpoilerChip(
                text: item.text,
                fontSize: scope.sp(14.0),
              ),
            ),
          );
          continue;
        }

        if (item.code) {
          spans.add(
            WidgetSpan(
              alignment: PlaceholderAlignment.middle,
              child: Container(
                margin: const EdgeInsets.symmetric(horizontal: 1.5),
                padding: const EdgeInsets.symmetric(horizontal: 5.5, vertical: 1.5),
                decoration: BoxDecoration(
                  color: palette.subtleSurface,
                  borderRadius: BorderRadius.circular(5),
                  border: Border.all(color: palette.hairline, width: 0.6),
                ),
                child: Text(
                  item.text,
                  style: TextStyle(
                    color: palette.isDark
                        ? const Color(0xFFFCD34D)
                        : const Color(0xFFB45309),
                    fontSize: scope.sp(13.0),
                    fontFamily: 'monospace',
                    fontFamilyFallback: const [
                      'SF Mono',
                      'Menlo',
                      'Consolas',
                      'monospace',
                    ],
                    height: 1.3,
                  ),
                ),
              ),
            ),
          );
          continue;
        }

        final isLink = item.linkUrl != null && item.linkUrl!.trim().isNotEmpty;
        final decorations = <TextDecoration>[
          if (item.strikethrough) TextDecoration.lineThrough,
          if (isLink) TextDecoration.underline,
        ];

        spans.add(
          TextSpan(
            text: item.text,
            style: widget.baseStyle.copyWith(
              fontWeight: item.bold ? FontWeight.w700 : widget.baseStyle.fontWeight,
              fontStyle: item.italic ? FontStyle.italic : FontStyle.normal,
              color: isLink ? palette.logoDoColor : widget.baseStyle.color,
              decoration: decorations.isEmpty
                  ? TextDecoration.none
                  : TextDecoration.combine(decorations),
              decorationColor: isLink
                  ? palette.logoDoColor.withValues(alpha: 0.55)
                  : palette.inkTertiary,
            ),
            recognizer: isLink
                ? _makeTapRecognizer(() => widget.onTapUrl(item.linkUrl!))
                : null,
          ),
        );
      }
    }

    return Text.rich(
      TextSpan(style: widget.baseStyle, children: spans),
    );
  }
}

class _InlineSpoilerChip extends StatefulWidget {
  final String text;
  final double fontSize;

  const _InlineSpoilerChip({
    required this.text,
    required this.fontSize,
  });

  @override
  State<_InlineSpoilerChip> createState() => _InlineSpoilerChipState();
}

class _InlineSpoilerChipState extends State<_InlineSpoilerChip> {
  bool _revealed = false;

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    return SelectionContainer.disabled(
      child: GestureDetector(
        onTap: () => setState(() => _revealed = !_revealed),
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 160),
          margin: const EdgeInsets.symmetric(horizontal: 2.0),
          padding: const EdgeInsets.symmetric(horizontal: 6.0, vertical: 1.5),
          decoration: BoxDecoration(
            color: _revealed
                ? palette.subtleSurface
                : (palette.isDark
                    ? const Color(0xFF2D3342)
                    : const Color(0xFFD5DAE2)),
            borderRadius: BorderRadius.circular(5),
          ),
          child: Text(
            widget.text,
            style: TextStyle(
              color: _revealed ? palette.inkPrimary : Colors.transparent,
              fontSize: widget.fontSize,
              height: 1.3,
            ),
          ),
        ),
      ),
    );
  }
}

class _CodeBlockCard extends StatefulWidget {
  final String language;
  final String code;

  const _CodeBlockCard({
    required this.language,
    required this.code,
  });

  @override
  State<_CodeBlockCard> createState() => _CodeBlockCardState();
}

class _CodeBlockCardState extends State<_CodeBlockCard> {
  bool _copied = false;
  Timer? _resetTimer;

  @override
  void dispose() {
    _resetTimer?.cancel();
    super.dispose();
  }

  Future<void> _copyCode() async {
    await Clipboard.setData(ClipboardData(text: widget.code));
    if (!mounted) return;
    setState(() => _copied = true);
    _resetTimer?.cancel();
    _resetTimer = Timer(const Duration(milliseconds: 1600), () {
      if (mounted) setState(() => _copied = false);
    });
  }

  @override
  Widget build(BuildContext context) {
    final scope = LinDoThemeScope.of(context);
    final palette = scope.palette;
    final langLabel =
        widget.language.trim().isEmpty ? 'code' : widget.language.trim();

    return Container(
      decoration: BoxDecoration(
        color: palette.isDark ? const Color(0xFF141820) : const Color(0xFFF6F8FA),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: palette.hairline, width: 0.85),
      ),
      clipBehavior: Clip.antiAlias,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Container(
            height: 34,
            padding: const EdgeInsets.symmetric(horizontal: 12),
            decoration: BoxDecoration(
              color: palette.subtleSurface.withValues(alpha: 0.85),
              border: Border(
                bottom: BorderSide(color: palette.hairline, width: 0.75),
              ),
            ),
            child: Row(
              children: [
                Icon(
                  LinDoIcons.Code,
                  size: 14,
                  color: palette.inkTertiary,
                ),
                const SizedBox(width: 6),
                Text(
                  langLabel,
                  style: TextStyle(
                    color: palette.inkSecondary,
                    fontSize: scope.sp(11.5),
                    fontWeight: FontWeight.w600,
                  ),
                ),
                const Spacer(),
                SelectionContainer.disabled(
                  child: InkWell(
                    onTap: _copyCode,
                    borderRadius: BorderRadius.circular(6),
                    child: Padding(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 6,
                        vertical: 3,
                      ),
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Icon(
                            _copied ? LinDoIcons.Check : LinDoIcons.Copy,
                            size: 13,
                            color: _copied
                                ? palette.logoDoColor
                                : palette.inkSecondary,
                          ),
                          const SizedBox(width: 4),
                          Text(
                            _copied
                                ? LinDoCommonCopy.COPIED.text
                                : LinDoCommonCopy.COPY.text,
                            style: TextStyle(
                              color: _copied
                                  ? palette.logoDoColor
                                  : palette.inkSecondary,
                              fontSize: scope.sp(11.5),
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ],
            ),
          ),
          SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
            child: Text(
              widget.code,
              style: TextStyle(
                color: palette.inkPrimary,
                fontSize: scope.sp(13.0),
                height: 1.54,
                fontFamily: 'monospace',
                fontFamilyFallback: const [
                  'SF Mono',
                  'Menlo',
                  'Consolas',
                  'monospace',
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _CollapsibleQuoteCard extends StatefulWidget {
  final AstQuoteBlock quote;
  final VoidCallback? onTapJump;
  final VoidCallback? onTapAuthor;
  final WidgetBuilder childBuilder;

  const _CollapsibleQuoteCard({
    required this.quote,
    required this.onTapJump,
    required this.onTapAuthor,
    required this.childBuilder,
  });

  @override
  State<_CollapsibleQuoteCard> createState() => _CollapsibleQuoteCardState();
}

class _CollapsibleQuoteCardState extends State<_CollapsibleQuoteCard> {
  bool _expanded = true;

  @override
  Widget build(BuildContext context) {
    final scope = LinDoThemeScope.of(context);
    final palette = scope.palette;
    final hasAuthorHeader =
        (widget.quote.username != null && widget.quote.username!.isNotEmpty) ||
            widget.quote.postNumber != null;

    return Container(
      decoration: BoxDecoration(
        color: palette.subtleSurface.withValues(alpha: 0.76),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: palette.hairline, width: 0.8),
      ),
      clipBehavior: Clip.antiAlias,
      child: IntrinsicHeight(
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Container(
              width: 3.5,
              color: palette.logoDoColor.withValues(alpha: 0.78),
            ),
            Expanded(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(12, 9, 12, 10),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    if (hasAuthorHeader)
                      SelectionContainer.disabled(
                        child: Row(
                          children: [
                            if (widget.quote.avatarUrl != null ||
                                widget.quote.username != null) ...[
                              SmartAvatar(
                                avatarUrl: widget.quote.avatarUrl ?? '',
                                fallbackLabel: widget.quote.username ?? 'L',
                                size: 20,
                                onTap: widget.onTapAuthor,
                              ),
                              const SizedBox(width: 7),
                            ],
                            Expanded(
                              child: GestureDetector(
                                onTap: () =>
                                    setState(() => _expanded = !_expanded),
                                behavior: HitTestBehavior.opaque,
                                child: Text(
                                  [
                                    if (widget.quote.username != null &&
                                        widget.quote.username!.isNotEmpty)
                                      LinDoCommonCopy.AT_USERNAME
                                          .format(widget.quote.username!),
                                    if (widget.quote.postNumber != null)
                                      LinDoReaderCopy.FLOOR_LABEL
                                          .format(widget.quote.postNumber!),
                                  ].join(' · '),
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                  style: TextStyle(
                                    color: palette.inkSecondary,
                                    fontSize: scope.sp(12.0),
                                    fontWeight: FontWeight.w600,
                                  ),
                                ),
                              ),
                            ),
                            if (widget.onTapJump != null)
                              IconButton(
                                onPressed: widget.onTapJump,
                                tooltip: widget.quote.postNumber != null
                                    ? LinDoReaderCopy.JUMP_TO_FLOOR_LABEL
                                        .format(widget.quote.postNumber!)
                                    : LinDoReaderCopy.AST_QUOTE_LINK.text,
                                visualDensity: VisualDensity.compact,
                                constraints: const BoxConstraints(
                                  minWidth: 26,
                                  minHeight: 26,
                                ),
                                padding: EdgeInsets.zero,
                                icon: Icon(
                                  LinDoIcons.ArrowRight,
                                  size: 14,
                                  color: palette.inkTertiary,
                                ),
                              ),
                            InkWell(
                              onTap: () =>
                                  setState(() => _expanded = !_expanded),
                              borderRadius: BorderRadius.circular(6),
                              child: Padding(
                                padding: const EdgeInsets.all(3),
                                child: Icon(
                                  _expanded
                                      ? LinDoIcons.ChevronUp
                                      : LinDoIcons.ChevronDown,
                                  size: 16,
                                  color: palette.inkTertiary,
                                ),
                              ),
                            ),
                          ],
                        ),
                      ),
                    if (_expanded) ...[
                      if (hasAuthorHeader) const SizedBox(height: 6),
                      widget.childBuilder(context),
                    ],
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _ExpandableDetailsCard extends StatefulWidget {
  final String summary;
  final WidgetBuilder childBuilder;

  const _ExpandableDetailsCard({
    required this.summary,
    required this.childBuilder,
  });

  @override
  State<_ExpandableDetailsCard> createState() => _ExpandableDetailsCardState();
}

class _ExpandableDetailsCardState extends State<_ExpandableDetailsCard> {
  bool _expanded = false;

  @override
  Widget build(BuildContext context) {
    final scope = LinDoThemeScope.of(context);
    final palette = scope.palette;
    final cleanSummary = widget.summary.trim().isEmpty
        ? LinDoReaderCopy.AST_EXPAND_DETAILS.text
        : widget.summary.trim();

    return Container(
      decoration: BoxDecoration(
        color: palette.subtleSurface.withValues(alpha: 0.65),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: palette.hairline, width: 0.8),
      ),
      clipBehavior: Clip.antiAlias,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          SelectionContainer.disabled(
            child: InkWell(
              onTap: () => setState(() => _expanded = !_expanded),
              child: Padding(
                padding:
                    const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                child: Row(
                  children: [
                    Icon(
                      _expanded
                          ? LinDoIcons.ChevronDown
                          : LinDoIcons.ChevronRight,
                      size: 17,
                      color: palette.logoDoColor,
                    ),
                    const SizedBox(width: 6),
                    Expanded(
                      child: Text(
                        cleanSummary,
                        style: TextStyle(
                          color: palette.inkPrimary,
                          fontSize: scope.sp(13.5),
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
          if (_expanded) ...[
            Divider(height: 1, thickness: 0.7, color: palette.hairline),
            Padding(
              padding: const EdgeInsets.all(12),
              child: widget.childBuilder(context),
            ),
          ],
        ],
      ),
    );
  }
}

class _BlockSpoilerCard extends StatefulWidget {
  final WidgetBuilder childBuilder;

  const _BlockSpoilerCard({required this.childBuilder});

  @override
  State<_BlockSpoilerCard> createState() => _BlockSpoilerCardState();
}

class _BlockSpoilerCardState extends State<_BlockSpoilerCard> {
  bool _revealed = false;

  @override
  Widget build(BuildContext context) {
    final scope = LinDoThemeScope.of(context);
    final palette = scope.palette;

    if (!_revealed) {
      return SelectionContainer.disabled(
        child: InkWell(
          onTap: () => setState(() => _revealed = true),
          borderRadius: BorderRadius.circular(12),
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
            decoration: BoxDecoration(
              color: palette.subtleSurface,
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: palette.hairline, width: 0.85),
            ),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Icon(
                  LinDoIcons.EyeOff,
                  size: 16,
                  color: palette.inkSecondary,
                ),
                const SizedBox(width: 7),
                Text(
                  LinDoReaderCopy.REVEAL_SPOILER.text,
                  style: TextStyle(
                    color: palette.inkSecondary,
                    fontSize: scope.sp(13.0),
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ],
            ),
          ),
        ),
      );
    }

    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: palette.subtleSurface.withValues(alpha: 0.55),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: palette.hairline, width: 0.8),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          SelectionContainer.disabled(
            child: Align(
              alignment: Alignment.centerRight,
              child: InkWell(
                onTap: () => setState(() => _revealed = false),
                borderRadius: BorderRadius.circular(6),
                child: Padding(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(
                        LinDoIcons.Eye,
                        size: 13,
                        color: palette.inkTertiary,
                      ),
                      const SizedBox(width: 4),
                      Text(
                        LinDoReaderCopy.COLLAPSE_SPOILER.text,
                        style: TextStyle(
                          color: palette.inkTertiary,
                          fontSize: scope.sp(11.5),
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
          const SizedBox(height: 4),
          widget.childBuilder(context),
        ],
      ),
    );
  }
}

class _CalloutBlockCard extends StatelessWidget {
  final CalloutBlockKind kind;
  final String title;
  final List<Widget> children;

  const _CalloutBlockCard({
    required this.kind,
    required this.title,
    required this.children,
  });

  @override
  Widget build(BuildContext context) {
    final scope = LinDoThemeScope.of(context);
    final palette = scope.palette;

    late final Color bg;
    late final Color accent;
    late final IconData icon;
    switch (kind) {
      case CalloutBlockKind.LOTTERY:
        bg = palette.isDark ? const Color(0xFF36270B) : const Color(0xFFFEF7E6);
        accent = const Color(0xFFF59E0B);
        icon = LinDoIcons.Sparkles;
        break;
      case CalloutBlockKind.WARNING:
        bg = palette.isDark ? const Color(0xFF371B1E) : const Color(0xFFFEF2F2);
        accent = const Color(0xFFEF4444);
        icon = LinDoIcons.Warning;
        break;
      case CalloutBlockKind.TIP:
        bg = palette.isDark ? const Color(0xFF103125) : const Color(0xFFECFDF5);
        accent = const Color(0xFF10B981);
        icon = LinDoIcons.Seedling;
        break;
      case CalloutBlockKind.NOTE:
        bg = palette.isDark ? const Color(0xFF16243B) : const Color(0xFFEFF6FF);
        accent = const Color(0xFF3B82F6);
        icon = LinDoIcons.InfoCircle;
        break;
    }

    final resolvedTitle =
        title.trim().isEmpty ? kind.defaultTitle.text : title.trim();

    return Container(
      padding: const EdgeInsets.all(13),
      decoration: BoxDecoration(
        color: bg,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: accent.withValues(alpha: 0.30), width: 0.85),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        mainAxisSize: MainAxisSize.min,
        children: [
          Row(
            children: [
              Icon(icon, size: 16, color: accent),
              const SizedBox(width: 7),
              Expanded(
                child: Text(
                  resolvedTitle,
                  style: TextStyle(
                    color: accent,
                    fontSize: scope.sp(13.5),
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ),
            ],
          ),
          if (children.isNotEmpty) ...[
            const SizedBox(height: 8),
            ...children,
          ],
        ],
      ),
    );
  }
}

class _RichTableCard extends StatelessWidget {
  final AstTableBlock table;
  final ValueChanged<String> onTapUrl;
  final ValueChanged<String>? onTapMention;
  final ValueChanged<String> onTapImage;

  const _RichTableCard({
    required this.table,
    required this.onTapUrl,
    required this.onTapMention,
    required this.onTapImage,
  });

  @override
  Widget build(BuildContext context) {
    final scope = LinDoThemeScope.of(context);
    final palette = scope.palette;

    var colCount = 0;
    for (final hr in table.headers) {
      colCount = math.max(colCount, hr.length);
    }
    for (final br in table.rows) {
      colCount = math.max(colCount, br.length);
    }
    if (colCount <= 0) return const SizedBox.shrink();

    List<Widget> buildCells(List<List<AstInline>> row, {required bool isHeader}) {
      return List.generate(colCount, (cIdx) {
        final cellInlines =
            cIdx < row.length ? row[cIdx] : const <AstInline>[];
        return ConstrainedBox(
          constraints: const BoxConstraints(minWidth: 72, maxWidth: 280),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 8),
            child: _RichInlineBlock(
              inlines: cellInlines,
              baseStyle: TextStyle(
                color: palette.inkPrimary,
                fontSize: scope.sp(13.0),
                fontWeight: isHeader ? FontWeight.w700 : FontWeight.w400,
                height: 1.45,
              ),
              onTapUrl: onTapUrl,
              onTapMention: onTapMention,
              onTapImage: onTapImage,
            ),
          ),
        );
      });
    }

    final tableRows = <TableRow>[];
    for (final headerRow in table.headers) {
      tableRows.add(
        TableRow(
          decoration: BoxDecoration(color: palette.subtleSurface),
          children: buildCells(headerRow, isHeader: true),
        ),
      );
    }
    for (final bodyRow in table.rows) {
      tableRows.add(
        TableRow(
          children: buildCells(bodyRow, isHeader: false),
        ),
      );
    }

    return Container(
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(11),
        border: Border.all(color: palette.hairline, width: 0.85),
      ),
      clipBehavior: Clip.antiAlias,
      child: SingleChildScrollView(
        scrollDirection: Axis.horizontal,
        child: Table(
          defaultColumnWidth: const IntrinsicColumnWidth(),
          border: TableBorder.symmetric(
            inside: BorderSide(color: palette.hairline, width: 0.7),
          ),
          children: tableRows,
        ),
      ),
    );
  }
}

class _PostImageBlockWidget extends StatelessWidget {
  final String imageUrl;
  final String alt;
  final int? intrinsicWidth;
  final int? intrinsicHeight;
  final VoidCallback onTap;

  const _PostImageBlockWidget({
    required this.imageUrl,
    required this.alt,
    required this.intrinsicWidth,
    required this.intrinsicHeight,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final scope = LinDoThemeScope.of(context);
    final palette = scope.palette;
    final hasValidDims = (intrinsicWidth ?? 0) > 0 && (intrinsicHeight ?? 0) > 0;
    final aspect = hasValidDims
        ? (intrinsicWidth! / intrinsicHeight!).clamp(0.35, 3.2)
        : null;

    Widget imageChild = LinDoNetworkImage(
      url: imageUrl,
      fit: BoxFit.contain,
      borderRadius: BorderRadius.circular(12),
      errorFallback: Container(
        height: 110,
        padding: const EdgeInsets.symmetric(horizontal: 16),
        decoration: BoxDecoration(
          color: palette.subtleSurface,
          borderRadius: BorderRadius.circular(12),
        ),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(LinDoIcons.Image, size: 18, color: palette.inkTertiary),
            const SizedBox(width: 8),
            Flexible(
              child: Text(
                alt.trim().isNotEmpty
                    ? alt.trim()
                    : LinDoReaderCopy.AST_IMAGE_UNAVAILABLE.text,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  color: palette.inkTertiary,
                  fontSize: scope.sp(12.5),
                ),
              ),
            ),
          ],
        ),
      ),
    );

    if (aspect != null) {
      imageChild = ConstrainedBox(
        constraints: BoxConstraints(
          maxWidth: math.min(640.0, intrinsicWidth!.toDouble()),
          maxHeight: 460.0,
        ),
        child: AspectRatio(
          aspectRatio: aspect,
          child: imageChild,
        ),
      );
    } else {
      imageChild = ConstrainedBox(
        constraints: const BoxConstraints(maxHeight: 440.0),
        child: imageChild,
      );
    }

    return SelectionContainer.disabled(
      child: Align(
        alignment: Alignment.centerLeft,
        child: MouseRegion(
          cursor: SystemMouseCursors.click,
          child: GestureDetector(
            onTap: onTap,
            child: Semantics(
              button: true,
              label: LinDoReaderCopy.OPEN_IMAGE_LABEL.format(
                alt.trim().isEmpty
                    ? LinDoReaderCopy.POST_IMAGE_ALT.text
                    : alt.trim(),
              ),
              child: DecoratedBox(
                decoration: BoxDecoration(
                  color: palette.subtleSurface.withValues(alpha: 0.45),
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(color: palette.hairline, width: 0.8),
                ),
                child: ClipRRect(
                  borderRadius: BorderRadius.circular(12),
                  child: imageChild,
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _OneboxPreviewCard extends StatelessWidget {
  final AstOneboxCard card;
  final VoidCallback onTap;

  const _OneboxPreviewCard({
    required this.card,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final scope = LinDoThemeScope.of(context);
    final palette = scope.palette;
    final host = card.domain.trim().isNotEmpty
        ? card.domain.trim()
        : (Uri.tryParse(card.url)?.host ?? card.url);

    return SelectionContainer.disabled(
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(13),
          child: Container(
            padding: const EdgeInsets.all(13),
            decoration: BoxDecoration(
              color: palette.subtleSurface.withValues(alpha: 0.72),
              borderRadius: BorderRadius.circular(13),
              border: Border.all(color: palette.hairline, width: 0.85),
            ),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Row(
                        children: [
                          if ((card.faviconUrl ?? '').trim().isNotEmpty) ...[
                            LinDoNetworkImage(
                              url: card.faviconUrl!,
                              width: 15,
                              height: 15,
                              fit: BoxFit.contain,
                              borderRadius: BorderRadius.circular(3),
                              errorFallback: Icon(
                                LinDoIcons.Globe,
                                size: 13,
                                color: palette.inkTertiary,
                              ),
                            ),
                            const SizedBox(width: 6),
                          ] else ...[
                            Icon(
                              LinDoIcons.Globe,
                              size: 13,
                              color: palette.inkTertiary,
                            ),
                            const SizedBox(width: 5),
                          ],
                          Expanded(
                            child: Text(
                              host,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: TextStyle(
                                color: palette.inkTertiary,
                                fontSize: scope.sp(11.5),
                                fontWeight: FontWeight.w600,
                              ),
                            ),
                          ),
                          Icon(
                            LinDoIcons.ExternalLink,
                            size: 13,
                            color: palette.inkTertiary,
                          ),
                        ],
                      ),
                      const SizedBox(height: 5),
                      Text(
                        card.title.trim().isEmpty ? card.url : card.title.trim(),
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          color: palette.inkPrimary,
                          fontSize: scope.sp(14.0),
                          fontWeight: FontWeight.w700,
                          height: 1.35,
                        ),
                      ),
                      if (card.description.trim().isNotEmpty) ...[
                        const SizedBox(height: 4),
                        Text(
                          card.description.trim(),
                          maxLines: 3,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            color: palette.inkSecondary,
                            fontSize: scope.sp(12.5),
                            height: 1.45,
                          ),
                        ),
                      ],
                    ],
                  ),
                ),
                if ((card.thumbnailUrl ?? '').trim().isNotEmpty) ...[
                  const SizedBox(width: 12),
                  LinDoNetworkImage(
                    url: card.thumbnailUrl!,
                    width: 68,
                    height: 68,
                    fit: BoxFit.cover,
                    borderRadius: BorderRadius.circular(10),
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

class _InteractivePollCard extends StatefulWidget {
  final PollModel poll;
  final int postId;
  final LinDoPollVoteCallback? onVotePoll;

  const _InteractivePollCard({
    required this.poll,
    required this.postId,
    required this.onVotePoll,
  });

  @override
  State<_InteractivePollCard> createState() => _InteractivePollCardState();
}

class _InteractivePollCardState extends State<_InteractivePollCard> {
  late PollModel _currentPoll = widget.poll;
  late Set<String> _selectedIds = Set<String>.from(widget.poll.preloadedSelection);
  bool _submitting = false;

  @override
  void didUpdateWidget(covariant _InteractivePollCard oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.poll != widget.poll) {
      _currentPoll = widget.poll;
      _selectedIds = Set<String>.from(widget.poll.preloadedSelection);
    }
  }

  bool get _isMultiple => _currentPoll.type.toLowerCase() == 'multiple';

  void _toggleOption(String optionId) {
    if (_currentPoll.isClosed || _submitting || widget.onVotePoll == null) {
      return;
    }
    setState(() {
      if (_isMultiple) {
        if (_selectedIds.contains(optionId)) {
          _selectedIds.remove(optionId);
        } else {
          final maxAllowed =
              _currentPoll.max > 0 ? _currentPoll.max : _currentPoll.options.length;
          if (_selectedIds.length < maxAllowed) {
            _selectedIds.add(optionId);
          }
        }
      } else {
        _selectedIds
          ..clear()
          ..add(optionId);
      }
    });
  }

  Future<void> _submitVote() async {
    final voteCb = widget.onVotePoll;
    if (voteCb == null || _submitting || _selectedIds.isEmpty) return;
    setState(() => _submitting = true);
    try {
      final res = await Future<dynamic>.sync(
        () => voteCb(_currentPoll.name, _selectedIds.toList(growable: false)),
      );
      if (!mounted) return;
      if (res is PollModel) {
        setState(() {
          _currentPoll = res;
          _selectedIds = Set<String>.from(res.preloadedSelection);
        });
      }
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final scope = LinDoThemeScope.of(context);
    final palette = scope.palette;
    final totalOptionVotes = _currentPoll.options.fold<int>(
      0,
      (sum, item) => sum + math.max(0, item.votes),
    );
    final pollTitle = _currentPoll.title.trim().isNotEmpty
        ? decodeDiscourseHtmlSnippet(_currentPoll.title)
        : LinDoReaderCopy.AST_COMMUNITY_POLL.text;
    final hasPreloadedVote = _currentPoll.preloadedSelection.isNotEmpty;

    return SelectionContainer.disabled(
      child: Container(
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: palette.subtleSurface.withValues(alpha: 0.78),
          borderRadius: BorderRadius.circular(14),
          border: Border.all(color: palette.hairline, width: 0.85),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          mainAxisSize: MainAxisSize.min,
          children: [
            Row(
              children: [
                Icon(LinDoIcons.Poll, size: 17, color: palette.logoDoColor),
                const SizedBox(width: 7),
                Expanded(
                  child: Text(
                    pollTitle,
                    style: TextStyle(
                      color: palette.inkPrimary,
                      fontSize: scope.sp(14.0),
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ),
                Text(
                  _currentPoll.isClosed
                      ? (_currentPoll.voters > 0
                          ? LinDoReaderCopy.POLL_CLOSED_WITH_VOTERS
                              .format(_currentPoll.voters)
                          : LinDoReaderCopy.POLL_CLOSED.text)
                      : (_currentPoll.voters > 0
                          ? '${_currentPoll.voters}'
                          : ''),
                  style: TextStyle(
                    color: palette.inkTertiary,
                    fontSize: scope.sp(11.5),
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 10),
            ..._currentPoll.options.map((option) {
              final selected = _selectedIds.contains(option.id);
              final ratio = totalOptionVotes > 0
                  ? (option.votes / totalOptionVotes).clamp(0.0, 1.0)
                  : 0.0;
              final pct = (ratio * 100).round();
              final label = option.plainText.trim().isNotEmpty
                  ? option.plainText.trim()
                  : decodeDiscourseHtmlSnippet(option.html);

              return Padding(
                padding: const EdgeInsets.only(bottom: 7),
                child: InkWell(
                  onTap: (_currentPoll.isClosed || widget.onVotePoll == null)
                      ? null
                      : () => _toggleOption(option.id),
                  borderRadius: BorderRadius.circular(10),
                  child: Container(
                    decoration: BoxDecoration(
                      color: palette.cardSurface,
                      borderRadius: BorderRadius.circular(10),
                      border: Border.all(
                        color: selected
                            ? palette.logoDoColor.withValues(alpha: 0.8)
                            : palette.hairline,
                        width: selected ? 1.2 : 0.8,
                      ),
                    ),
                    clipBehavior: Clip.antiAlias,
                    child: Stack(
                      children: [
                        Positioned.fill(
                          child: FractionallySizedBox(
                            alignment: Alignment.centerLeft,
                            widthFactor: ratio,
                            child: DecoratedBox(
                              decoration: BoxDecoration(
                                color: palette.logoDoColor.withValues(
                                  alpha: selected ? 0.22 : 0.12,
                                ),
                              ),
                            ),
                          ),
                        ),
                        Padding(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 11,
                            vertical: 9,
                          ),
                          child: Row(
                            children: [
                              if (!_currentPoll.isClosed &&
                                  widget.onVotePoll != null) ...[
                                Icon(
                                  selected
                                      ? LinDoIcons.CheckCircle
                                      : (_isMultiple
                                          ? Icons.check_box_outline_blank_rounded
                                          : Icons.radio_button_unchecked_rounded),
                                  size: 16,
                                  color: selected
                                      ? palette.logoDoColor
                                      : palette.inkTertiary,
                                ),
                                const SizedBox(width: 8),
                              ],
                              Expanded(
                                child: Text(
                                  label,
                                  style: TextStyle(
                                    color: palette.inkPrimary,
                                    fontSize: scope.sp(13.5),
                                    fontWeight: selected
                                        ? FontWeight.w700
                                        : FontWeight.w500,
                                  ),
                                ),
                              ),
                              const SizedBox(width: 8),
                              Text(
                                '$pct% (${LinDoReaderCopy.POLL_VOTES_COUNT.format(option.votes)})',
                                style: TextStyle(
                                  color: palette.inkSecondary,
                                  fontSize: scope.sp(11.5),
                                  fontWeight: FontWeight.w600,
                                  fontFeatures: const [
                                    FontFeature.tabularFigures(),
                                  ],
                                ),
                              ),
                            ],
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              );
            }),
            if (!_currentPoll.isClosed && widget.onVotePoll != null) ...[
              const SizedBox(height: 4),
              Align(
                alignment: Alignment.centerRight,
                child: SizedBox(
                  height: 36,
                  child: FilledButton(
                    onPressed:
                        (_submitting || _selectedIds.isEmpty) ? null : _submitVote,
                    style: FilledButton.styleFrom(
                      backgroundColor: palette.publishBtnBg,
                      foregroundColor: palette.publishBtnContent,
                      elevation: 0,
                      padding: const EdgeInsets.symmetric(horizontal: 16),
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(10),
                      ),
                    ),
                    child: Text(
                      _submitting
                          ? LinDoCommonCopy.SUBMITTING.text
                          : (hasPreloadedVote
                              ? LinDoReaderCopy.UPDATE_VOTE.text
                              : LinDoReaderCopy.SUBMIT_VOTE.text),
                      style: TextStyle(
                        fontSize: scope.sp(12.5),
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                  ),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _PostImageLightboxDialog extends StatefulWidget {
  final List<String> imageUrls;
  final int initialIndex;

  const _PostImageLightboxDialog({
    required this.imageUrls,
    required this.initialIndex,
  });

  @override
  State<_PostImageLightboxDialog> createState() =>
      _PostImageLightboxDialogState();
}

class _PostImageLightboxDialogState extends State<_PostImageLightboxDialog> {
  late int _currentIndex =
      widget.initialIndex.clamp(0, math.max(0, widget.imageUrls.length - 1));
  late final PageController _pageController =
      PageController(initialPage: _currentIndex);
  final TransformationController _transformController =
      TransformationController();
  bool _copiedToast = false;

  @override
  void dispose() {
    _pageController.dispose();
    _transformController.dispose();
    super.dispose();
  }

  void _resetZoom() {
    _transformController.value = Matrix4.identity();
  }

  Future<void> _copyCurrentUrl() async {
    final url = widget.imageUrls[_currentIndex];
    await Clipboard.setData(ClipboardData(text: url));
    if (!mounted) return;
    setState(() => _copiedToast = true);
    Future<void>.delayed(const Duration(milliseconds: 1400), () {
      if (mounted) setState(() => _copiedToast = false);
    });
  }

  @override
  Widget build(BuildContext context) {
    final scope = LinDoThemeScope.of(context);
    final currentUrl = widget.imageUrls[_currentIndex];
    final hasMultiple = widget.imageUrls.length > 1;

    return Material(
      color: Colors.transparent,
      child: SafeArea(
        child: Stack(
          fit: StackFit.expand,
          children: [
            PageView.builder(
              controller: _pageController,
              itemCount: widget.imageUrls.length,
              onPageChanged: (idx) {
                _resetZoom();
                setState(() => _currentIndex = idx);
              },
              itemBuilder: (context, index) {
                return GestureDetector(
                  onDoubleTap: () {
                    if (_transformController.value != Matrix4.identity()) {
                      _resetZoom();
                    } else {
                      _transformController.value =
                          Matrix4.diagonal3Values(2.0, 2.0, 1.0);
                    }
                  },
                  child: InteractiveViewer(
                    transformationController: _transformController,
                    minScale: 0.75,
                    maxScale: 5.0,
                    child: Center(
                      child: Padding(
                        padding: const EdgeInsets.all(20),
                        child: LinDoNetworkImage(
                          url: widget.imageUrls[index],
                          fit: BoxFit.contain,
                        ),
                      ),
                    ),
                  ),
                );
              },
            ),
            Positioned(
              top: 12,
              left: 16,
              right: 16,
              child: Row(
                children: [
                  if (hasMultiple)
                    Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 10,
                        vertical: 5,
                      ),
                      decoration: BoxDecoration(
                        color: Colors.black.withValues(alpha: 0.55),
                        borderRadius: BorderRadius.circular(999),
                      ),
                      child: Text(
                        '${_currentIndex + 1} / ${widget.imageUrls.length}',
                        style: TextStyle(
                          color: Colors.white,
                          fontSize: scope.sp(12.5),
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ),
                  const Spacer(),
                  IconButton(
                    onPressed: _copyCurrentUrl,
                    tooltip: _copiedToast
                        ? LinDoReaderCopy.IMAGE_LINK_COPIED.text
                        : LinDoReaderCopy.COPY_IMAGE_LINK.text,
                    style: IconButton.styleFrom(
                      backgroundColor: Colors.black.withValues(alpha: 0.55),
                      foregroundColor: Colors.white,
                    ),
                    icon: Icon(
                      _copiedToast ? LinDoIcons.Check : LinDoIcons.Copy,
                      size: 18,
                    ),
                  ),
                  const SizedBox(width: 8),
                  IconButton(
                    onPressed: () =>
                        LinDoPlatformBridge.instance.openExternalUrl(currentUrl),
                    tooltip: LinDoReaderCopy.OPEN_ORIGINAL_IN_BROWSER.text,
                    style: IconButton.styleFrom(
                      backgroundColor: Colors.black.withValues(alpha: 0.55),
                      foregroundColor: Colors.white,
                    ),
                    icon: const Icon(LinDoIcons.ExternalLink, size: 18),
                  ),
                  const SizedBox(width: 8),
                  IconButton(
                    onPressed: () => Navigator.of(context).pop(),
                    tooltip: LinDoReaderCopy.CLOSE_IMAGE_PREVIEW_DESC.text,
                    style: IconButton.styleFrom(
                      backgroundColor: Colors.black.withValues(alpha: 0.55),
                      foregroundColor: Colors.white,
                    ),
                    icon: const Icon(LinDoIcons.Close, size: 20),
                  ),
                ],
              ),
            ),
            if (hasMultiple && _currentIndex > 0)
              Positioned(
                left: 14,
                top: 0,
                bottom: 0,
                child: Center(
                  child: IconButton(
                    onPressed: () => _pageController.previousPage(
                      duration: const Duration(milliseconds: 220),
                      curve: Curves.easeOutCubic,
                    ),
                    tooltip: LinDoReaderCopy.PREV_IMAGE_DESC.text,
                    style: IconButton.styleFrom(
                      backgroundColor: Colors.black.withValues(alpha: 0.52),
                      foregroundColor: Colors.white,
                    ),
                    icon: const Icon(LinDoIcons.ArrowLeft, size: 20),
                  ),
                ),
              ),
            if (hasMultiple && _currentIndex < widget.imageUrls.length - 1)
              Positioned(
                right: 14,
                top: 0,
                bottom: 0,
                child: Center(
                  child: IconButton(
                    onPressed: () => _pageController.nextPage(
                      duration: const Duration(milliseconds: 220),
                      curve: Curves.easeOutCubic,
                    ),
                    tooltip: LinDoReaderCopy.NEXT_IMAGE_DESC.text,
                    style: IconButton.styleFrom(
                      backgroundColor: Colors.black.withValues(alpha: 0.52),
                      foregroundColor: Colors.white,
                    ),
                    icon: const Icon(LinDoIcons.ArrowRight, size: 20),
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}
