import 'dart:convert';

import 'package:html/dom.dart' as dom;
import 'package:html/parser.dart' as html_parser;
import 'package:markdown/markdown.dart' as md;

import '../copy/lindo_copy.dart';
import '../site/linux_do_emoji_catalog.dart';
import '../site/linux_do_site_rules.dart';

final RegExp _fencedCodeRegex = RegExp(
  r'(^|\n)(`{3,}|~{3,})[^\n]*\n[\s\S]*?\n\2(?=\n|$)',
);
final RegExp _inlineCodeRegex = RegExp(r'(`+)([^\n`]+?)\1');

final RegExp _uploadMarkdownTargetRegex = RegExp(
  r'(\]\(\s*)(upload://[^\s)]+)(\s*(?:"[^"]*")?\s*\))',
);
final RegExp _bareUploadUrlRegex = RegExp(r'upload://[A-Za-z0-9._-]+');

final RegExp _bbcodePollRegex = RegExp(
  r'\[poll(?:\s+([^\]]*))?\][\s\S]*?\[/poll\]',
  caseSensitive: false,
);
final RegExp _pollNameAttrRegex = RegExp(
  r'''\bname\s*=\s*(?:"([^"]+)"|'([^']+)'|([^\s\]]+))''',
  caseSensitive: false,
);

final RegExp _bbcodeQuoteLeafRegex = RegExp(
  r'\[quote(?:=([^\]]*))?\]((?:(?!\[quote\b)[\s\S])*?)\[/quote\]',
  caseSensitive: false,
);

final RegExp _bbcodeDetailsLeafRegex = RegExp(
  r'\[details(?:=([^\]]*))?\]((?:(?!\[details\b)[\s\S])*?)\[/details\]',
  caseSensitive: false,
);

final RegExp _bbcodeSpoilerLeafRegex = RegExp(
  r'\[spoiler\]((?:(?!\[spoiler\])[\s\S])*?)\[/spoiler\]',
  caseSensitive: false,
);

final RegExp _emojiShortcodeRegex = RegExp(
  r'(?<![A-Za-z0-9:/]):([a-zA-Z0-9_+-]{1,40}(?::t[2-6])?):(?![A-Za-z0-9:])',
);

final RegExp _mentionRegex = RegExp(
  r'(^|[\s(\[{>])@([A-Za-z0-9_][A-Za-z0-9_.-]{0,38})(?![A-Za-z0-9_.-])',
  multiLine: true,
);

final RegExp _githubAlertLineRegex = RegExp(
  r'^\s*\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION|LOTTERY)\]\s*(.*)$',
  caseSensitive: false,
);

String renderMarkdownToCookedHtml(String rawMarkdown) {
  if (rawMarkdown.trim().isEmpty) return '';

  var working = rawMarkdown.replaceAll('\r\n', '\n');
  final protectedSnippets = <String>[];

  String stashPlaceholder(String snippet) {
    final idx = protectedSnippets.length;
    protectedSnippets.add(snippet);
    return '\u0000LINDO_PROTECTED_${idx}_\u0000';
  }

  // 1. Protect fenced code blocks and inline code spans before Discourse syntax expansion.
  working = working.replaceAllMapped(_fencedCodeRegex, (match) {
    final leading = match.group(1) ?? '';
    final block = match.group(0)!.substring(leading.length);
    return '$leading${stashPlaceholder(block)}';
  });

  working = working.replaceAllMapped(_inlineCodeRegex, (match) {
    return stashPlaceholder(match.group(0)!);
  });

  // 2. Expand Discourse `upload://` URLs inside Markdown links/images and bare references.
  working = working.replaceAllMapped(_uploadMarkdownTargetRegex, (match) {
    final prefix = match.group(1) ?? '](';
    final resolved = resolveDiscourseUrl(match.group(2) ?? '');
    final suffix = match.group(3) ?? ')';
    return '$prefix$resolved$suffix';
  });
  working = working.replaceAllMapped(_bareUploadUrlRegex, (match) {
    return resolveDiscourseUrl(match.group(0) ?? '');
  });

  // 3. Expand `[poll name=...]` blocks into Discourse cooked `<div class="poll">`.
  working = working.replaceAllMapped(_bbcodePollRegex, (match) {
    final attrs = match.group(1) ?? '';
    final nameMatch = _pollNameAttrRegex.firstMatch(attrs);
    final rawName = (nameMatch?.group(1) ??
            nameMatch?.group(2) ??
            nameMatch?.group(3) ??
            'poll')
        .trim();
    final safeName = htmlEscape.convert(rawName.isEmpty ? 'poll' : rawName);
    return '\n\n<div class="poll" data-poll-name="$safeName"></div>\n\n';
  });

  // 4. Expand nested `[spoiler]`, `[details=...]`, and `[quote=...]` blocks innermost-first.
  var guard = 0;
  while (_bbcodeSpoilerLeafRegex.hasMatch(working) && guard++ < 16) {
    working = working.replaceAllMapped(_bbcodeSpoilerLeafRegex, (match) {
      final inner = match.group(1) ?? '';
      if (inner.contains('\n')) {
        final restoredInner = _restorePlaceholders(inner, protectedSnippets);
        final innerHtml = renderMarkdownToCookedHtml(restoredInner);
        return '\n\n<div class="spoiled">$innerHtml</div>\n\n';
      }
      final safeInner = htmlEscape.convert(
        _restorePlaceholders(inner, protectedSnippets),
      );
      return '<span class="spoiler">$safeInner</span>';
    });
  }

  guard = 0;
  while (_bbcodeDetailsLeafRegex.hasMatch(working) && guard++ < 16) {
    working = working.replaceAllMapped(_bbcodeDetailsLeafRegex, (match) {
      final rawAttr = _stripSurroundingQuotes((match.group(1) ?? '').trim());
      final summary =
          rawAttr.isEmpty ? LinDoReaderCopy.AST_EXPAND_DETAILS.text : rawAttr;
      final restoredInner = _restorePlaceholders(match.group(2) ?? '', protectedSnippets);
      final innerHtml = renderMarkdownToCookedHtml(restoredInner);
      final safeSummary = htmlEscape.convert(summary);
      return '\n\n<details><summary>$safeSummary</summary>\n$innerHtml\n</details>\n\n';
    });
  }

  guard = 0;
  while (_bbcodeQuoteLeafRegex.hasMatch(working) && guard++ < 16) {
    working = working.replaceAllMapped(_bbcodeQuoteLeafRegex, (match) {
      final rawAttr = _stripSurroundingQuotes((match.group(1) ?? '').trim());
      final quoteMeta = _parseQuoteAttributes(rawAttr);
      final restoredInner = _restorePlaceholders(match.group(2) ?? '', protectedSnippets);
      final innerHtml = renderMarkdownToCookedHtml(restoredInner);

      final attrs = <String>['class="quote"'];
      if (quoteMeta.username != null && quoteMeta.username!.isNotEmpty) {
        attrs.add('data-username="${htmlEscape.convert(quoteMeta.username!)}"');
      }
      if (quoteMeta.postNumber != null) {
        attrs.add('data-post="${quoteMeta.postNumber}"');
      }
      if (quoteMeta.topicId != null) {
        attrs.add('data-topic="${quoteMeta.topicId}"');
      }
      final titleHtml = (quoteMeta.username != null && quoteMeta.username!.isNotEmpty)
          ? '<div class="title">${htmlEscape.convert(quoteMeta.username!)}:</div>'
          : '';
      return '\n\n<aside ${attrs.join(' ')}>$titleHtml<blockquote>$innerHtml</blockquote></aside>\n\n';
    });
  }

  // 5. Restore code placeholders right before running package:markdown so code blocks parse natively.
  working = _restorePlaceholders(working, protectedSnippets);

  final rawHtml = md.markdownToHtml(
    working,
    extensionSet: md.ExtensionSet.gitHubWeb,
  );

  // 6. Post-process DOM text nodes (excluding `<code>`, `<pre>`, `<a>`) for `:shortcode:` emojis,
  //    `@username` mentions, and any remaining `> [!ALERT]` blockquotes.
  final fragment = html_parser.parseFragment(rawHtml);
  _upgradeRemainingAlertBlockquotes(fragment);
  _decorateInlineMentionsAndEmojis(fragment);
  return fragment.outerHtml;
}

String _restorePlaceholders(String input, List<String> protectedSnippets) {
  if (!input.contains('\u0000LINDO_PROTECTED_')) return input;
  var out = input;
  for (var i = protectedSnippets.length - 1; i >= 0; i--) {
    out = out.replaceAll('\u0000LINDO_PROTECTED_${i}_\u0000', protectedSnippets[i]);
  }
  return out;
}

String _stripSurroundingQuotes(String value) {
  if (value.length >= 2) {
    if ((value.startsWith('"') && value.endsWith('"')) ||
        (value.startsWith("'") && value.endsWith("'"))) {
      return value.substring(1, value.length - 1).trim();
    }
  }
  return value;
}

({String? username, int? postNumber, int? topicId}) _parseQuoteAttributes(String rawAttr) {
  if (rawAttr.isEmpty) {
    return (username: null, postNumber: null, topicId: null);
  }
  String? username;
  int? postNumber;
  int? topicId;

  final parts = rawAttr.split(',');
  for (var i = 0; i < parts.length; i++) {
    final seg = parts[i].trim();
    if (seg.isEmpty) continue;
    final lower = seg.toLowerCase();
    if (lower.startsWith('post:')) {
      postNumber = int.tryParse(seg.substring(5).trim());
    } else if (lower.startsWith('topic:')) {
      topicId = int.tryParse(seg.substring(6).trim());
    } else if (i == 0 && !seg.contains(':')) {
      username = seg;
    }
  }
  return (username: username, postNumber: postNumber, topicId: topicId);
}

void _upgradeRemainingAlertBlockquotes(dom.DocumentFragment fragment) {
  for (final bq in fragment.querySelectorAll('blockquote').toList()) {
    final firstP = bq.children.isNotEmpty && bq.children.first.localName == 'p'
        ? bq.children.first
        : null;
    if (firstP == null || firstP.nodes.isEmpty) continue;
    final firstNode = firstP.nodes.first;
    if (firstNode is! dom.Text) continue;

    final lines = firstNode.text.split('\n');
    final match = _githubAlertLineRegex.firstMatch(lines.first);
    if (match == null) continue;

    final alertKindRaw = (match.group(1) ?? 'NOTE').toLowerCase();
    final customTitle = (match.group(2) ?? '').trim();
    final CalloutBlockKind kind;
    final String alertClassSuffix;
    switch (alertKindRaw) {
      case 'warning':
        kind = CalloutBlockKind.WARNING;
        alertClassSuffix = 'warning';
        break;
      case 'caution':
        kind = CalloutBlockKind.WARNING;
        alertClassSuffix = 'caution';
        break;
      case 'tip':
        kind = CalloutBlockKind.TIP;
        alertClassSuffix = 'tip';
        break;
      case 'lottery':
        kind = CalloutBlockKind.LOTTERY;
        alertClassSuffix = 'lottery';
        break;
      case 'important':
        kind = CalloutBlockKind.NOTE;
        alertClassSuffix = 'important';
        break;
      default:
        kind = CalloutBlockKind.NOTE;
        alertClassSuffix = 'note';
        break;
    }

    final restText = lines.length > 1 ? lines.sublist(1).join('\n').trimLeft() : '';
    if (restText.isEmpty) {
      firstNode.remove();
      if (firstP.nodes.isNotEmpty &&
          firstP.nodes.first is dom.Element &&
          (firstP.nodes.first as dom.Element).localName == 'br') {
        firstP.nodes.first.remove();
      }
      if (firstP.text.trim().isEmpty && firstP.children.isEmpty) {
        firstP.remove();
      }
    } else {
      firstNode.text = restText;
    }

    final wrapper = dom.Element.tag('div')
      ..classes.addAll(<String>['markdown-alert', 'markdown-alert-$alertClassSuffix']);
    final titleP = dom.Element.tag('p')
      ..classes.add('markdown-alert-title')
      ..text = customTitle.isEmpty ? kind.defaultTitle.text : customTitle;
    wrapper.append(titleP);
    while (bq.nodes.isNotEmpty) {
      wrapper.append(bq.nodes.first);
    }
    bq.replaceWith(wrapper);
  }
}

void _decorateInlineMentionsAndEmojis(dom.Node root) {
  final childSnapshot = List<dom.Node>.from(root.nodes);
  for (final child in childSnapshot) {
    if (child is dom.Element) {
      final tag = child.localName?.toLowerCase() ?? '';
      if (tag == 'code' || tag == 'pre' || tag == 'a' || tag == 'script' || tag == 'style') {
        continue;
      }
      if (tag == 'img') {
        final rawSrc = child.attributes['src'] ?? '';
        if (rawSrc.isNotEmpty) {
          child.attributes['src'] = resolveDiscourseUrl(rawSrc);
        }
        continue;
      }
      _decorateInlineMentionsAndEmojis(child);
    } else if (child is dom.Text) {
      final raw = child.text;
      if (!raw.contains(':') && !raw.contains('@')) continue;
      final transformedHtml = _expandEmojisAndMentionsInPlainText(raw);
      if (transformedHtml != null) {
        final subFrag = html_parser.parseFragment(transformedHtml);
        final parent = child.parentNode;
        if (parent != null) {
          final index = parent.nodes.indexOf(child);
          if (index >= 0) {
            parent.nodes.removeAt(index);
            parent.nodes.insertAll(index, subFrag.nodes);
          }
        }
      }
    }
  }
}

String? _expandEmojisAndMentionsInPlainText(String text) {
  var mutated = false;
  var escaped = htmlEscape.convert(text);

  escaped = escaped.replaceAllMapped(_emojiShortcodeRegex, (match) {
    final code = match.group(1) ?? '';
    final url = LinuxDoEmojiCatalog.instance.resolveEmojiUrl(code);
    if (url == null || url.isEmpty) {
      return match.group(0)!;
    }
    mutated = true;
    final safeCode = htmlEscape.convert(code);
    final safeUrl = htmlEscape.convert(url);
    return '<img src="$safeUrl" title=":$safeCode:" class="emoji" alt=":$safeCode:">';
  });

  escaped = escaped.replaceAllMapped(_mentionRegex, (match) {
    final prefix = match.group(1) ?? '';
    final username = match.group(2) ?? '';
    if (username.isEmpty) return match.group(0)!;
    mutated = true;
    final safeUser = htmlEscape.convert(username);
    final safeHref = htmlEscape.convert('$kLinuxDoBaseUrl/u/${username.toLowerCase()}');
    return '$prefix<a class="mention" href="$safeHref">@$safeUser</a>';
  });

  return mutated ? escaped : null;
}
