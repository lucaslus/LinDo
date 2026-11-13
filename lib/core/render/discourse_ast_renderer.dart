import 'package:html/dom.dart' as dom;
import 'package:html/parser.dart' as html_parser;

import '../copy/lindo_copy.dart';
import '../site/linux_do_emoji_catalog.dart';
import '../site/linux_do_site_rules.dart';

sealed class DiscourseAstNode {
  const DiscourseAstNode();
}

class AstParagraph extends DiscourseAstNode {
  final List<AstInline> inlines;
  const AstParagraph(this.inlines);
}

class AstHeading extends DiscourseAstNode {
  final int level;
  final List<AstInline> inlines;
  const AstHeading(this.level, this.inlines);
}

class AstCodeBlock extends DiscourseAstNode {
  final String language;
  final String code;
  const AstCodeBlock(this.language, this.code);
}

class AstQuoteBlock extends DiscourseAstNode {
  final String? username;
  final int? postNumber;
  final int? topicId;
  final String? avatarUrl;
  final List<DiscourseAstNode> children;

  const AstQuoteBlock({
    this.username,
    this.postNumber,
    this.topicId,
    this.avatarUrl,
    required this.children,
  });
}

class AstDetailsBlock extends DiscourseAstNode {
  final String summary;
  final List<DiscourseAstNode> children;

  const AstDetailsBlock({
    required this.summary,
    required this.children,
  });
}

class AstSpoilerBlock extends DiscourseAstNode {
  final List<DiscourseAstNode> children;
  const AstSpoilerBlock(this.children);
}

class AstCalloutBlock extends DiscourseAstNode {
  final CalloutBlockKind kind;
  final String title;
  final List<DiscourseAstNode> children;

  const AstCalloutBlock({
    required this.kind,
    required this.title,
    required this.children,
  });
}

class AstListItem {
  final bool? checked;
  final List<DiscourseAstNode> children;

  const AstListItem({
    this.checked,
    required this.children,
  });
}

class AstListBlock extends DiscourseAstNode {
  final bool ordered;
  final int start;
  final List<AstListItem> items;

  const AstListBlock({
    required this.ordered,
    this.start = 1,
    required this.items,
  });
}

class AstTableBlock extends DiscourseAstNode {
  final List<List<List<AstInline>>> headers;
  final List<List<List<AstInline>>> rows;

  const AstTableBlock({
    required this.headers,
    required this.rows,
  });
}

class AstImageBlock extends DiscourseAstNode {
  final String url;
  final String alt;
  final int? width;
  final int? height;

  const AstImageBlock({
    required this.url,
    this.alt = '',
    this.width,
    this.height,
  });
}

class AstOneboxCard extends DiscourseAstNode {
  final String url;
  final String title;
  final String description;
  final String domain;
  final String? faviconUrl;
  final String? thumbnailUrl;

  const AstOneboxCard({
    required this.url,
    required this.title,
    this.description = '',
    this.domain = '',
    this.faviconUrl,
    this.thumbnailUrl,
  });
}

class AstPollRef extends DiscourseAstNode {
  final String pollName;
  const AstPollRef(this.pollName);
}

class AstThematicBreak extends DiscourseAstNode {
  const AstThematicBreak();
}

sealed class AstInline {
  const AstInline();
}

class InlineText extends AstInline {
  final String text;
  final bool bold;
  final bool italic;
  final bool strikethrough;
  final bool code;
  final bool spoiler;
  final String? linkUrl;

  const InlineText(
    this.text, {
    this.bold = false,
    this.italic = false,
    this.strikethrough = false,
    this.code = false,
    this.spoiler = false,
    this.linkUrl,
  });
}

class InlineMention extends AstInline {
  final String username;
  final String? url;

  const InlineMention(this.username, {this.url});
}

class InlineEmoji extends AstInline {
  final String name;
  final String imageUrl;
  final String? unicodeFallback;

  const InlineEmoji({
    required this.name,
    required this.imageUrl,
    this.unicodeFallback,
  });
}

class InlineHashtag extends AstInline {
  final String label;
  final String? slug;
  final String? url;

  const InlineHashtag({
    required this.label,
    this.slug,
    this.url,
  });
}

class InlineImage extends AstInline {
  final String url;
  final String alt;

  const InlineImage({
    required this.url,
    this.alt = '',
  });
}

class InlineLineBreak extends AstInline {
  const InlineLineBreak();
}

class _InlineStyle {
  final bool bold;
  final bool italic;
  final bool strikethrough;
  final bool code;
  final bool spoiler;
  final String? linkUrl;

  const _InlineStyle({
    this.bold = false,
    this.italic = false,
    this.strikethrough = false,
    this.code = false,
    this.spoiler = false,
    this.linkUrl,
  });

  _InlineStyle merge({
    bool? bold,
    bool? italic,
    bool? strikethrough,
    bool? code,
    bool? spoiler,
    String? linkUrl,
  }) =>
      _InlineStyle(
        bold: bold ?? this.bold,
        italic: italic ?? this.italic,
        strikethrough: strikethrough ?? this.strikethrough,
        code: code ?? this.code,
        spoiler: spoiler ?? this.spoiler,
        linkUrl: linkUrl ?? this.linkUrl,
      );
}

final RegExp _calloutMarkerRegex = RegExp(
  r'^\s*\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION|LOTTERY)\]\s*(.*)$',
  caseSensitive: false,
);

final RegExp _whitespaceCollapseRegex = RegExp(r'[ \t\r\n]+');

List<DiscourseAstNode> parseDiscourseCookedHtml(String cookedHtml) {
  if (cookedHtml.trim().isEmpty) return const <DiscourseAstNode>[];
  final fragment = html_parser.parseFragment(cookedHtml);
  return _parseBlockList(fragment.nodes);
}

List<DiscourseAstNode> _parseBlockList(List<dom.Node> domNodes) {
  final result = <DiscourseAstNode>[];
  final pendingInlines = <AstInline>[];

  void flushPendingInlines() {
    final normalized = _normalizeInlineList(pendingInlines);
    pendingInlines.clear();
    if (normalized.isNotEmpty) {
      result.add(AstParagraph(normalized));
    }
  }

  for (final node in domNodes) {
    if (node is dom.Comment) continue;

    if (node is dom.Text) {
      if (node.text.trim().isEmpty && pendingInlines.isEmpty) {
        continue;
      }
      _collectInlineNode(node, const _InlineStyle(), pendingInlines);
      continue;
    }

    if (node is! dom.Element) continue;
    final tag = node.localName?.toLowerCase() ?? '';

    if (_isBlockElement(node, tag)) {
      flushPendingInlines();
      final blocks = _parseBlockElement(node, tag);
      result.addAll(blocks);
    } else {
      _collectInlineNode(node, const _InlineStyle(), pendingInlines);
    }
  }

  flushPendingInlines();
  return result;
}

bool _isBlockElement(dom.Element el, String tag) {
  switch (tag) {
    case 'p':
    case 'h1':
    case 'h2':
    case 'h3':
    case 'h4':
    case 'h5':
    case 'h6':
    case 'pre':
    case 'blockquote':
    case 'aside':
    case 'details':
    case 'ul':
    case 'ol':
    case 'table':
    case 'hr':
    case 'div':
    case 'section':
    case 'article':
    case 'figure':
      return true;
    case 'a':
      return el.classes.contains('lightbox') || el.classes.contains('onebox');
    case 'img':
      return !el.classes.contains('emoji') && !el.classes.contains('avatar');
    default:
      return false;
  }
}

List<DiscourseAstNode> _parseBlockElement(dom.Element el, String tag) {
  if (tag == 'hr') {
    return const <DiscourseAstNode>[AstThematicBreak()];
  }

  if (tag == 'h1' ||
      tag == 'h2' ||
      tag == 'h3' ||
      tag == 'h4' ||
      tag == 'h5' ||
      tag == 'h6') {
    final level = int.tryParse(tag.substring(1)) ?? 2;
    final inlines = _parseElementInlines(el);
    if (inlines.isEmpty) return const <DiscourseAstNode>[];
    return <DiscourseAstNode>[AstHeading(level, inlines)];
  }

  if (tag == 'pre') {
    return <DiscourseAstNode>[_parsePreCodeBlock(el)];
  }

  if (tag == 'aside') {
    if (el.classes.contains('quote')) {
      return <DiscourseAstNode>[_parseAsideQuote(el)];
    }
    if (el.classes.contains('onebox')) {
      return <DiscourseAstNode>[_parseOneboxCard(el)];
    }
    return _parseBlockList(el.nodes);
  }

  if (tag == 'details') {
    return <DiscourseAstNode>[_parseDetailsBlock(el)];
  }

  if (tag == 'blockquote') {
    return <DiscourseAstNode>[_parseBlockquoteOrCallout(el)];
  }

  if (tag == 'ul' || tag == 'ol') {
    final listNode = _parseListBlock(el, ordered: tag == 'ol');
    return listNode.items.isEmpty
        ? const <DiscourseAstNode>[]
        : <DiscourseAstNode>[listNode];
  }

  if (tag == 'table') {
    return <DiscourseAstNode>[_parseTableBlock(el)];
  }

  if (tag == 'a' && el.classes.contains('lightbox')) {
    final imgBlock = _extractLightboxImage(el);
    if (imgBlock != null) return <DiscourseAstNode>[imgBlock];
  }

  if (tag == 'img' && !el.classes.contains('emoji')) {
    final imgBlock = _extractImgElementBlock(el);
    if (imgBlock != null) return <DiscourseAstNode>[imgBlock];
  }

  if (tag == 'div' || tag == 'section' || tag == 'article' || tag == 'figure') {
    if (el.classes.contains('poll') || el.attributes.containsKey('data-poll-name')) {
      final pollName = (el.attributes['data-poll-name'] ?? '').trim();
      return <DiscourseAstNode>[AstPollRef(pollName.isEmpty ? 'poll' : pollName)];
    }

    if (el.classes.contains('lightbox-wrapper')) {
      final anchor = el.querySelector('a.lightbox');
      if (anchor != null) {
        final imgBlock = _extractLightboxImage(anchor);
        if (imgBlock != null) return <DiscourseAstNode>[imgBlock];
      }
      final fallbackImg = el.querySelector('img');
      if (fallbackImg != null) {
        final imgBlock = _extractImgElementBlock(fallbackImg);
        if (imgBlock != null) return <DiscourseAstNode>[imgBlock];
      }
      return const <DiscourseAstNode>[];
    }

    if (el.classes.contains('spoiled') || el.classes.contains('spoiler')) {
      final innerBlocks = _parseBlockList(el.nodes);
      if (innerBlocks.isEmpty) return const <DiscourseAstNode>[];
      return <DiscourseAstNode>[AstSpoilerBlock(innerBlocks)];
    }

    if (el.classes.contains('markdown-alert') ||
        el.classes.any((c) => c.startsWith('markdown-alert-')) ||
        el.classes.contains('d-wrap') && (el.attributes['data-wrap'] ?? '') != '') {
      final callout = _tryParseAlertDiv(el);
      if (callout != null) return <DiscourseAstNode>[callout];
    }

    if (el.classes.contains('onebox')) {
      return <DiscourseAstNode>[_parseOneboxCard(el)];
    }

    if (el.classes.contains('md-table')) {
      final table = el.querySelector('table');
      if (table != null) {
        return <DiscourseAstNode>[_parseTableBlock(table)];
      }
    }

    // Ignore Discourse topic/footnote metadata chrome wrappers that have no body content
    if (el.classes.contains('meta')) {
      return const <DiscourseAstNode>[];
    }

    return _parseBlockList(el.nodes);
  }

  if (tag == 'p') {
    final standaloneBlock = _tryExtractStandaloneParagraphBlock(el);
    if (standaloneBlock != null) {
      return <DiscourseAstNode>[standaloneBlock];
    }
    final inlines = _parseElementInlines(el);
    if (inlines.isEmpty) return const <DiscourseAstNode>[];
    return <DiscourseAstNode>[AstParagraph(inlines)];
  }

  return _parseBlockList(el.nodes);
}

DiscourseAstNode? _tryExtractStandaloneParagraphBlock(dom.Element p) {
  final meaningfulNodes = p.nodes.where((n) {
    if (n is dom.Comment) return false;
    if (n is dom.Text && n.text.trim().isEmpty) return false;
    return true;
  }).toList(growable: false);

  if (meaningfulNodes.length != 1) return null;
  final single = meaningfulNodes.first;
  if (single is! dom.Element) return null;

  final singleTag = single.localName?.toLowerCase() ?? '';
  if (singleTag == 'div' && single.classes.contains('lightbox-wrapper')) {
    final anchor = single.querySelector('a.lightbox');
    if (anchor != null) return _extractLightboxImage(anchor);
  }
  if (singleTag == 'a' && single.classes.contains('lightbox')) {
    return _extractLightboxImage(single);
  }
  if (singleTag == 'img' &&
      !single.classes.contains('emoji') &&
      !single.classes.contains('avatar')) {
    return _extractImgElementBlock(single);
  }
  if (singleTag == 'a' && single.classes.contains('onebox')) {
    final href = resolveDiscourseUrl(single.attributes['href'] ?? '');
    if (href.isNotEmpty) {
      final text = single.text.trim();
      final host = Uri.tryParse(href)?.host ?? '';
      return AstOneboxCard(
        url: href,
        title: text.isEmpty ? href : text,
        domain: host,
      );
    }
  }
  return null;
}

AstCodeBlock _parsePreCodeBlock(dom.Element pre) {
  final codeEl = pre.querySelector('code');
  var language = '';
  if (codeEl != null) {
    for (final cls in codeEl.classes) {
      if (cls.startsWith('lang-')) {
        final candidate = cls.substring('lang-'.length).trim();
        if (candidate != 'auto' && candidate != 'plaintext') {
          language = candidate;
          break;
        }
      } else if (cls.startsWith('language-')) {
        final candidate = cls.substring('language-'.length).trim();
        if (candidate != 'auto' && candidate != 'plaintext') {
          language = candidate;
          break;
        }
      }
    }
  }
  var rawCode = (codeEl ?? pre).text;
  if (rawCode.endsWith('\r\n')) {
    rawCode = rawCode.substring(0, rawCode.length - 2);
  } else if (rawCode.endsWith('\n')) {
    rawCode = rawCode.substring(0, rawCode.length - 1);
  }
  return AstCodeBlock(language, rawCode);
}

AstQuoteBlock _parseAsideQuote(dom.Element aside) {
  var username = aside.attributes['data-username']?.trim();
  if (username != null && username.isEmpty) username = null;

  final postNumber = int.tryParse(aside.attributes['data-post'] ?? '');
  final topicId = int.tryParse(aside.attributes['data-topic'] ?? '');

  String? avatarUrl;
  final titleEl = aside.querySelector('.title');
  if (titleEl != null) {
    final avatarImg = titleEl.querySelector('img');
    if (avatarImg != null) {
      final rawSrc = avatarImg.attributes['src'] ?? '';
      final resolved = resolveDiscourseUrl(rawSrc);
      if (resolved.isNotEmpty) avatarUrl = resolved;
    }
    if (username == null) {
      final rawTitle = titleEl.text.trim().replaceFirst(RegExp(r':\s*$'), '').trim();
      if (rawTitle.isNotEmpty) {
        username = rawTitle;
      }
    }
  }

  final blockquote = aside.querySelector('blockquote');
  final childNodes = blockquote != null
      ? _parseBlockList(blockquote.nodes)
      : _parseBlockList(
          aside.nodes.where((n) => !(n is dom.Element && n.classes.contains('title'))).toList(),
        );

  return AstQuoteBlock(
    username: username,
    postNumber: postNumber,
    topicId: topicId,
    avatarUrl: avatarUrl,
    children: childNodes,
  );
}

AstDetailsBlock _parseDetailsBlock(dom.Element details) {
  var summaryText = '';
  final remainingNodes = <dom.Node>[];
  var consumedSummary = false;

  for (final child in details.nodes) {
    if (!consumedSummary &&
        child is dom.Element &&
        child.localName?.toLowerCase() == 'summary') {
      consumedSummary = true;
      summaryText = child.text.replaceAll(_whitespaceCollapseRegex, ' ').trim();
      continue;
    }
    remainingNodes.add(child);
  }

  if (summaryText.isEmpty) {
    summaryText = LinDoReaderCopy.AST_EXPAND_DETAILS.text;
  }

  return AstDetailsBlock(
    summary: summaryText,
    children: _parseBlockList(remainingNodes),
  );
}

AstCalloutBlock? _tryParseAlertDiv(dom.Element el) {
  CalloutBlockKind? kind;
  for (final cls in el.classes) {
    final lower = cls.toLowerCase();
    if (lower == 'markdown-alert-warning' || lower == 'markdown-alert-caution') {
      kind = CalloutBlockKind.WARNING;
      break;
    } else if (lower == 'markdown-alert-tip') {
      kind = CalloutBlockKind.TIP;
      break;
    } else if (lower == 'markdown-alert-lottery') {
      kind = CalloutBlockKind.LOTTERY;
      break;
    } else if (lower == 'markdown-alert-note' || lower == 'markdown-alert-important') {
      kind = CalloutBlockKind.NOTE;
      break;
    }
  }

  final wrapAttr = (el.attributes['data-wrap'] ?? '').trim().toLowerCase();
  if (kind == null && wrapAttr.isNotEmpty) {
    if (wrapAttr.contains('lottery')) {
      kind = CalloutBlockKind.LOTTERY;
    } else if (wrapAttr.contains('warn') || wrapAttr.contains('danger')) {
      kind = CalloutBlockKind.WARNING;
    } else if (wrapAttr.contains('tip') || wrapAttr.contains('success')) {
      kind = CalloutBlockKind.TIP;
    } else if (wrapAttr.contains('note') || wrapAttr.contains('info')) {
      kind = CalloutBlockKind.NOTE;
    }
  }

  if (kind == null) return null;

  var title = kind.defaultTitle.text;
  final bodyDomNodes = <dom.Node>[];
  var consumedTitle = false;

  for (final node in el.nodes) {
    if (!consumedTitle &&
        node is dom.Element &&
        node.classes.contains('markdown-alert-title')) {
      consumedTitle = true;
      final extracted = node.text.replaceAll(_whitespaceCollapseRegex, ' ').trim();
      if (extracted.isNotEmpty) {
        title = extracted;
      }
      continue;
    }
    bodyDomNodes.add(node);
  }

  return AstCalloutBlock(
    kind: kind,
    title: title,
    children: _parseBlockList(bodyDomNodes),
  );
}

DiscourseAstNode _parseBlockquoteOrCallout(dom.Element blockquote) {
  for (final cls in blockquote.classes) {
    final lower = cls.toLowerCase();
    if (lower.contains('warning') || lower.contains('danger')) {
      return AstCalloutBlock(
        kind: CalloutBlockKind.WARNING,
        title: CalloutBlockKind.WARNING.defaultTitle.text,
        children: _parseBlockList(blockquote.nodes),
      );
    }
    if (lower.contains('tip') || lower.contains('success')) {
      return AstCalloutBlock(
        kind: CalloutBlockKind.TIP,
        title: CalloutBlockKind.TIP.defaultTitle.text,
        children: _parseBlockList(blockquote.nodes),
      );
    }
    if (lower.contains('info') || lower.contains('note')) {
      return AstCalloutBlock(
        kind: CalloutBlockKind.NOTE,
        title: CalloutBlockKind.NOTE.defaultTitle.text,
        children: _parseBlockList(blockquote.nodes),
      );
    }
  }

  final children = _parseBlockList(blockquote.nodes);
  if (children.isNotEmpty && children.first is AstParagraph) {
    final firstPara = children.first as AstParagraph;
    if (firstPara.inlines.isNotEmpty && firstPara.inlines.first is InlineText) {
      final firstInline = firstPara.inlines.first as InlineText;
      final firstLineSplit = firstInline.text.split('\n');
      final markerMatch = _calloutMarkerRegex.firstMatch(firstLineSplit.first);
      if (markerMatch != null) {
        final rawType = (markerMatch.group(1) ?? 'NOTE').toUpperCase();
        final CalloutBlockKind kind;
        switch (rawType) {
          case 'WARNING':
          case 'CAUTION':
            kind = CalloutBlockKind.WARNING;
            break;
          case 'TIP':
            kind = CalloutBlockKind.TIP;
            break;
          case 'LOTTERY':
            kind = CalloutBlockKind.LOTTERY;
            break;
          default:
            kind = CalloutBlockKind.NOTE;
            break;
        }
        final customTitle = (markerMatch.group(2) ?? '').trim();
        final effectiveTitle =
            customTitle.isEmpty ? kind.defaultTitle.text : customTitle;

        final remainingLines = firstLineSplit.length > 1
            ? firstLineSplit.sublist(1).join('\n').trimLeft()
            : '';
        final updatedInlines = <AstInline>[
          if (remainingLines.isNotEmpty)
            InlineText(
              remainingLines,
              bold: firstInline.bold,
              italic: firstInline.italic,
              strikethrough: firstInline.strikethrough,
              code: firstInline.code,
              spoiler: firstInline.spoiler,
              linkUrl: firstInline.linkUrl,
            ),
          ...firstPara.inlines.skip(1),
        ];
        while (updatedInlines.isNotEmpty && updatedInlines.first is InlineLineBreak) {
          updatedInlines.removeAt(0);
        }

        final updatedChildren = <DiscourseAstNode>[
          if (updatedInlines.isNotEmpty) AstParagraph(updatedInlines),
          ...children.skip(1),
        ];

        return AstCalloutBlock(
          kind: kind,
          title: effectiveTitle,
          children: updatedChildren,
        );
      }
    }
  }

  return AstQuoteBlock(children: children);
}

AstListBlock _parseListBlock(dom.Element listEl, {required bool ordered}) {
  final start = int.tryParse(listEl.attributes['start'] ?? '') ?? 1;
  final items = <AstListItem>[];

  for (final child in listEl.children) {
    if (child.localName?.toLowerCase() != 'li') continue;
    items.add(_parseListItem(child));
  }

  return AstListBlock(
    ordered: ordered,
    start: start,
    items: items,
  );
}

AstListItem _parseListItem(dom.Element li) {
  bool? checked;

  // Detect standard HTML checkbox `<input type="checkbox">` or Discourse cooked `.chcklst-box`
  final clonedNodes = li.nodes.map((n) => n.clone(true)).toList();

  bool stripLeadingCheckboxFrom(List<dom.Node> nodes) {
    for (var i = 0; i < nodes.length; i++) {
      final n = nodes[i];
      if (n is dom.Comment) continue;
      if (n is dom.Text && n.text.trim().isEmpty) continue;
      if (n is dom.Element) {
        final t = n.localName?.toLowerCase() ?? '';
        if (t == 'input' &&
            (n.attributes['type']?.toLowerCase() ?? '') == 'checkbox') {
          checked = n.attributes.containsKey('checked') ||
              n.attributes['checked'] == 'true';
          nodes.removeAt(i);
          return true;
        }
        if (t == 'span' && n.classes.contains('chcklst-box')) {
          checked = n.classes.contains('checked') ||
              n.classes.any((c) => c.contains('check'));
          nodes.removeAt(i);
          return true;
        }
        if (t == 'p') {
          if (stripLeadingCheckboxFrom(n.nodes)) return true;
        }
      }
      if (n is dom.Text) {
        final trimmedLeft = n.text.trimLeft();
        if (trimmedLeft.startsWith('[x] ') || trimmedLeft.startsWith('[X] ')) {
          checked = true;
          n.text = trimmedLeft.substring(4);
          return true;
        }
        if (trimmedLeft.startsWith('[ ] ')) {
          checked = false;
          n.text = trimmedLeft.substring(4);
          return true;
        }
      }
      break;
    }
    return false;
  }

  stripLeadingCheckboxFrom(clonedNodes);
  final children = _parseBlockList(clonedNodes);
  return AstListItem(checked: checked, children: children);
}

AstTableBlock _parseTableBlock(dom.Element table) {
  final headers = <List<List<AstInline>>>[];
  final rows = <List<List<AstInline>>>[];

  final thead = table.querySelector('thead');
  if (thead != null) {
    for (final tr in thead.children.where((e) => e.localName?.toLowerCase() == 'tr')) {
      final rowCells = <List<AstInline>>[];
      for (final cell in tr.children) {
        final ct = cell.localName?.toLowerCase();
        if (ct == 'th' || ct == 'td') {
          rowCells.add(_parseElementInlines(cell));
        }
      }
      if (rowCells.isNotEmpty) {
        headers.add(rowCells);
      }
    }
  }

  final tbodies = table.children
      .where((e) => e.localName?.toLowerCase() == 'tbody')
      .toList(growable: false);

  final bodyRows = tbodies.isNotEmpty
      ? tbodies
          .expand((tb) => tb.children.where((e) => e.localName?.toLowerCase() == 'tr'))
          .toList(growable: false)
      : table.children
          .where((e) => e.localName?.toLowerCase() == 'tr')
          .toList(growable: false);

  for (var i = 0; i < bodyRows.length; i++) {
    final tr = bodyRows[i];
    final cells = tr.children
        .where((c) {
          final ct = c.localName?.toLowerCase();
          return ct == 'td' || ct == 'th';
        })
        .toList(growable: false);
    if (cells.isEmpty) continue;

    final parsedCells = cells.map(_parseElementInlines).toList(growable: false);
    final allHeaderTags =
        headers.isEmpty && i == 0 && cells.every((c) => c.localName?.toLowerCase() == 'th');
    if (allHeaderTags) {
      headers.add(parsedCells);
    } else {
      rows.add(parsedCells);
    }
  }

  return AstTableBlock(headers: headers, rows: rows);
}

AstOneboxCard _parseOneboxCard(dom.Element el) {
  var rawUrl = (el.attributes['data-onebox-src'] ?? '').trim();
  if (rawUrl.isEmpty) {
    final headerLink = el.querySelector('header a[href]') ??
        el.querySelector('h3 a[href]') ??
        el.querySelector('h4 a[href]') ??
        el.querySelector('a[href]');
    rawUrl = (headerLink?.attributes['href'] ?? '').trim();
  }
  final url = resolveDiscourseUrl(rawUrl);

  final titleEl = el.querySelector('.onebox-body h3') ??
      el.querySelector('.onebox-body h4') ??
      el.querySelector('h3') ??
      el.querySelector('h4') ??
      el.querySelector('header a');
  var title = titleEl?.text.replaceAll(_whitespaceCollapseRegex, ' ').trim() ?? '';
  if (title.isEmpty) {
    title = url.isEmpty ? LinDoCommonCopy.LINUX_DO.text : url;
  }

  var description = '';
  final bodyEl = el.querySelector('.onebox-body') ?? el;
  final paragraphs = bodyEl.querySelectorAll('p');
  if (paragraphs.isNotEmpty) {
    description = paragraphs
        .map((p) => p.text.replaceAll(_whitespaceCollapseRegex, ' ').trim())
        .where((t) => t.isNotEmpty && t != title)
        .join(' ');
  }

  var domain = '';
  final sourceEl = el.querySelector('header .source');
  if (sourceEl != null) {
    domain = sourceEl.text.replaceAll(_whitespaceCollapseRegex, ' ').trim();
  }
  if (domain.isEmpty && url.isNotEmpty) {
    domain = Uri.tryParse(url)?.host ?? '';
  }

  String? faviconUrl;
  final siteIcon = el.querySelector('img.site-icon');
  if (siteIcon != null) {
    final resolved = resolveDiscourseUrl(siteIcon.attributes['src'] ?? '');
    if (resolved.isNotEmpty) faviconUrl = resolved;
  }

  String? thumbnailUrl;
  for (final img in bodyEl.querySelectorAll('img')) {
    if (img.classes.contains('site-icon') ||
        img.classes.contains('emoji') ||
        img.classes.contains('avatar')) {
      continue;
    }
    final resolved = resolveDiscourseUrl(img.attributes['src'] ?? '');
    if (resolved.isNotEmpty) {
      thumbnailUrl = resolved;
      break;
    }
  }

  return AstOneboxCard(
    url: url,
    title: title,
    description: description,
    domain: domain,
    faviconUrl: faviconUrl,
    thumbnailUrl: thumbnailUrl,
  );
}

AstImageBlock? _extractLightboxImage(dom.Element anchor) {
  final img = anchor.querySelector('img');
  final rawHref = (anchor.attributes['href'] ?? '').trim();
  final rawSrc = (img?.attributes['src'] ?? '').trim();
  final resolvedUrl = resolveDiscourseUrl(rawHref.isNotEmpty ? rawHref : rawSrc);
  if (resolvedUrl.isEmpty) return null;

  final alt = (img?.attributes['alt'] ?? anchor.attributes['title'] ?? '').trim();
  final width = int.tryParse(img?.attributes['width'] ?? '');
  final height = int.tryParse(img?.attributes['height'] ?? '');

  return AstImageBlock(
    url: resolvedUrl,
    alt: alt,
    width: width,
    height: height,
  );
}

AstImageBlock? _extractImgElementBlock(dom.Element img) {
  final rawSrc = (img.attributes['src'] ?? img.attributes['data-orig-src'] ?? '').trim();
  final resolvedUrl = resolveDiscourseUrl(rawSrc);
  if (resolvedUrl.isEmpty) return null;

  final alt = (img.attributes['alt'] ?? img.attributes['title'] ?? '').trim();
  final width = int.tryParse(img.attributes['width'] ?? '');
  final height = int.tryParse(img.attributes['height'] ?? '');

  return AstImageBlock(
    url: resolvedUrl,
    alt: alt,
    width: width,
    height: height,
  );
}

List<AstInline> _parseElementInlines(dom.Element el) {
  final rawInlines = <AstInline>[];
  for (final node in el.nodes) {
    _collectInlineNode(node, const _InlineStyle(), rawInlines);
  }
  return _normalizeInlineList(rawInlines);
}

void _collectInlineNode(
  dom.Node node,
  _InlineStyle style,
  List<AstInline> out,
) {
  if (node is dom.Comment) return;

  if (node is dom.Text) {
    final text = style.code
        ? node.text
        : node.text.replaceAll(_whitespaceCollapseRegex, ' ');
    if (text.isEmpty) return;
    out.add(
      InlineText(
        text,
        bold: style.bold,
        italic: style.italic,
        strikethrough: style.strikethrough,
        code: style.code,
        spoiler: style.spoiler,
        linkUrl: style.linkUrl,
      ),
    );
    return;
  }

  if (node is! dom.Element) return;
  final tag = node.localName?.toLowerCase() ?? '';

  if (tag == 'br') {
    out.add(const InlineLineBreak());
    return;
  }

  if (node.classes.contains('meta')) {
    // Skip Discourse lightbox `.meta` filename/dimensions badge inside `<a>`
    return;
  }

  if (tag == 'img') {
    if (node.classes.contains('emoji')) {
      final rawName = (node.attributes['title'] ?? node.attributes['alt'] ?? '').trim();
      final cleanName = rawName.replaceAll(':', '').trim();
      final rawSrc = (node.attributes['src'] ?? '').trim();
      final resolvedSrc = resolveDiscourseUrl(rawSrc);
      final emojiUrl = resolvedSrc.isNotEmpty
          ? resolvedSrc
          : (LinuxDoEmojiCatalog.instance.resolveEmojiUrl(cleanName) ?? '');
      if (emojiUrl.isNotEmpty) {
        out.add(
          InlineEmoji(
            name: cleanName.isEmpty ? 'emoji' : cleanName,
            imageUrl: emojiUrl,
            unicodeFallback: LinuxDoEmojiCatalog.instance.resolveUnicodeEmoji(cleanName),
          ),
        );
      } else if (rawName.isNotEmpty) {
        out.add(InlineText(rawName));
      }
      return;
    }

    final resolvedSrc = resolveDiscourseUrl(
      node.attributes['src'] ?? node.attributes['data-orig-src'] ?? '',
    );
    if (resolvedSrc.isNotEmpty) {
      out.add(
        InlineImage(
          url: resolvedSrc,
          alt: (node.attributes['alt'] ?? node.attributes['title'] ?? '').trim(),
        ),
      );
    }
    return;
  }

  if (tag == 'a') {
    final rawHref = (node.attributes['href'] ?? '').trim();
    final resolvedHref = rawHref.isEmpty ? null : resolveDiscourseUrl(rawHref);

    if (node.classes.contains('mention') || node.classes.contains('mention-group')) {
      final rawMention = node.text.trim().replaceFirst(RegExp(r'^@+'), '').trim();
      if (rawMention.isNotEmpty) {
        out.add(InlineMention(rawMention, url: resolvedHref));
        return;
      }
    }

    if (node.classes.contains('hashtag-cooked') || node.classes.contains('hashtag')) {
      final label = node.text.replaceAll(_whitespaceCollapseRegex, ' ').trim();
      final slug = (node.attributes['data-slug'] ?? node.attributes['data-ref'] ?? '').trim();
      if (label.isNotEmpty || slug.isNotEmpty) {
        out.add(
          InlineHashtag(
            label: label.isEmpty ? '#$slug' : label,
            slug: slug.isEmpty ? null : slug,
            url: resolvedHref,
          ),
        );
        return;
      }
    }

    if (node.classes.contains('lightbox')) {
      final img = node.querySelector('img');
      final targetUrl = resolvedHref ??
          resolveDiscourseUrl(img?.attributes['src'] ?? '');
      if (targetUrl.isNotEmpty) {
        out.add(
          InlineImage(
            url: targetUrl,
            alt: (img?.attributes['alt'] ?? node.attributes['title'] ?? '').trim(),
          ),
        );
        return;
      }
    }

    final nextStyle = style.merge(linkUrl: resolvedHref ?? style.linkUrl);
    for (final child in node.nodes) {
      _collectInlineNode(child, nextStyle, out);
    }
    return;
  }

  var nextStyle = style;
  if (tag == 'strong' || tag == 'b') {
    nextStyle = nextStyle.merge(bold: true);
  } else if (tag == 'em' || tag == 'i') {
    nextStyle = nextStyle.merge(italic: true);
  } else if (tag == 'del' || tag == 's' || tag == 'strike') {
    nextStyle = nextStyle.merge(strikethrough: true);
  } else if (tag == 'code' || tag == 'kbd') {
    nextStyle = nextStyle.merge(code: true);
  }

  if (node.classes.contains('spoiler') || node.classes.contains('spoiled')) {
    nextStyle = nextStyle.merge(spoiler: true);
  }

  for (final child in node.nodes) {
    _collectInlineNode(child, nextStyle, out);
  }
}

List<AstInline> _normalizeInlineList(List<AstInline> raw) {
  if (raw.isEmpty) return const <AstInline>[];

  final merged = <AstInline>[];
  for (final item in raw) {
    if (item is InlineText && merged.isNotEmpty && merged.last is InlineText) {
      final prev = merged.last as InlineText;
      if (prev.bold == item.bold &&
          prev.italic == item.italic &&
          prev.strikethrough == item.strikethrough &&
          prev.code == item.code &&
          prev.spoiler == item.spoiler &&
          prev.linkUrl == item.linkUrl) {
        merged[merged.length - 1] = InlineText(
          '${prev.text}${item.text}',
          bold: prev.bold,
          italic: prev.italic,
          strikethrough: prev.strikethrough,
          code: prev.code,
          spoiler: prev.spoiler,
          linkUrl: prev.linkUrl,
        );
        continue;
      }
    }
    merged.add(item);
  }

  // Trim leading whitespace on first InlineText and trailing whitespace on last InlineText
  while (merged.isNotEmpty) {
    final first = merged.first;
    if (first is InlineLineBreak) {
      merged.removeAt(0);
      continue;
    }
    if (first is InlineText && !first.code) {
      final trimmedLeft = first.text.trimLeft();
      if (trimmedLeft.isEmpty) {
        merged.removeAt(0);
        continue;
      }
      if (trimmedLeft.length != first.text.length) {
        merged[0] = InlineText(
          trimmedLeft,
          bold: first.bold,
          italic: first.italic,
          strikethrough: first.strikethrough,
          code: first.code,
          spoiler: first.spoiler,
          linkUrl: first.linkUrl,
        );
      }
    }
    break;
  }

  while (merged.isNotEmpty) {
    final last = merged.last;
    if (last is InlineLineBreak) {
      merged.removeLast();
      continue;
    }
    if (last is InlineText && !last.code) {
      final trimmedRight = last.text.trimRight();
      if (trimmedRight.isEmpty) {
        merged.removeLast();
        continue;
      }
      if (trimmedRight.length != last.text.length) {
        merged[merged.length - 1] = InlineText(
          trimmedRight,
          bold: last.bold,
          italic: last.italic,
          strikethrough: last.strikethrough,
          code: last.code,
          spoiler: last.spoiler,
          linkUrl: last.linkUrl,
        );
      }
    }
    break;
  }

  return merged;
}
