package do_.linux.lindo.core.render

import do_.linux.lindo.core.site.LinuxDoEmojiCatalog
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/** CommonMark handles escaping and code literals; Discourse wrappers are added outside code. */
object MarkdownPreview {
    private val extensions = listOf(TablesExtension.create(), StrikethroughExtension.create())
    private val parser = Parser.builder().extensions(extensions).build()
    private val renderer = HtmlRenderer.builder().extensions(extensions).build()

    fun render(raw: String): String {
        if (raw.isBlank()) return ""
        val protected = mutableListOf<String>()
        val tokenPrefix = "LINDOCODE${java.util.UUID.randomUUID().toString().replace("-", "")}X"
        fun protect(value: String): String { protected += value; return "$tokenPrefix${protected.lastIndex}END" }
        var source = Regex("(?m)^ {0,3}(`{3,}|~{3,})[^\\n]*\\n[\\s\\S]*?^ {0,3}\\1[\\t ]*$")
            .replace(raw) { fence ->
                val info = fence.value.substringBefore('\n').trimStart().dropWhile { it == '`' || it == '~' }.trim()
                val label = if ("ai" in info.split(Regex("\\s+"))) "\n\n**AI 生成**\n\n" else ""
                label + protect(fence.value)
            }
        source = Regex("(`+)([^`]|(?!\\1)`)*?\\1").replace(source) { protect(it.value) }
        // Indented code must also remain literal when it contains Discourse-looking syntax.
        source = Regex("(?m)^(?:(?: {4}|\\t)[^\\n]*(?:\\n|$))+").replace(source) { protect(it.value) }
        fun attr(value: String) = Element("span").attr("data-value", value).outerHtml().substringAfter("data-value=\"").substringBefore('"')
        // Ensure GFM tables immediately following a paragraph or list item without a blank line parse cleanly
        source = Regex("(?m)(^[^\\n|]*[^\\s\\n|][^\\n]*\\n)(?=[ \\t]*\\|?[^\\n]*\\|[^\\n]*\\n[ \\t]*\\|?[ \\t]*:?-{3,}:?(?:[ \\t]*\\|[ \\t]*:?-{3,}:?)*[ \\t]*\\|?[ \\t]*(?:\\n|$))")
            .replace(source) { "${it.groupValues[1]}\n" }
        // BBCode [color=...] and [bgcolor=...] with strict CSS color sanitization
        val colorRegex = Regex("\\[color=\"?([^\\]\"]+)\"?]((?:(?!\\[color=)[\\s\\S])*?)\\[/color]", RegexOption.IGNORE_CASE)
        for (depth in 0 until 8) {
            val next = colorRegex.replace(source) { match ->
                val safe = DiscourseAstParser.sanitizeSafeCssColor(match.groupValues[1])
                if (safe != null) {
                    "<span class=\"bbcode-color\" data-color=\"${attr(safe)}\" style=\"color: ${attr(safe)}\">${match.groupValues[2]}</span>"
                } else {
                    match.groupValues[2]
                }
            }
            if (next == source) break
            source = next
        }
        val bgColorRegex = Regex("\\[bgcolor=\"?([^\\]\"]+)\"?]((?:(?!\\[bgcolor=)[\\s\\S])*?)\\[/bgcolor]", RegexOption.IGNORE_CASE)
        for (depth in 0 until 8) {
            val next = bgColorRegex.replace(source) { match ->
                val safe = DiscourseAstParser.sanitizeSafeCssColor(match.groupValues[1])
                if (safe != null) {
                    "<span class=\"bbcode-bgcolor\" data-bgcolor=\"${attr(safe)}\" style=\"background-color: ${attr(safe)}\">${match.groupValues[2]}</span>"
                } else {
                    match.groupValues[2]
                }
            }
            if (next == source) break
            source = next
        }
        // Block math $$...$$ and inline math $...$ (outside code)
        source = Regex("(?m)^\\$\\$\\s*\\n([\\s\\S]*?)\\n\\$\\$\\s*$").replace(source) { match ->
            val expr = match.groupValues[1].trim()
            "\n<div class=\"math\" data-math=\"${attr(expr)}\">${org.jsoup.nodes.Entities.escape(expr)}</div>\n"
        }
        source = Regex("(?<!\\\\)(?<!\\$)\\$(?!\\s)([^$\\n]+?)(?<!\\s)\\$(?!\\$)").replace(source) { match ->
            val expr = match.groupValues[1].trim()
            "<span class=\"math\" data-math=\"${attr(expr)}\">${org.jsoup.nodes.Entities.escape(expr)}</span>"
        }
        val detailsRegex = Regex("""\[details(?:=(?:"([^"]*)"|'([^']*)'|([^\]]*?)))?(\s+ai)?]((?:(?!\[details\b)[\s\S])*?)\[/details]""", RegexOption.IGNORE_CASE)
        for (depth in 0 until 8) {
            val next = detailsRegex.replace(source) {
                val title = it.groupValues.slice(1..3).firstOrNull(String::isNotBlank) ?: "展开内容"
                val label = if (it.groupValues[4].isNotEmpty()) " · AI 生成" else ""
                "\n<details><summary>${org.jsoup.nodes.Entities.escape(title)}$label</summary>\n\n${it.groupValues[5]}\n\n</details>\n"
            }
            if (next == source) break
            source = next
        }
        val spoilerRegex = Regex("\\[spoiler]((?:(?!\\[spoiler])[\\s\\S])*?)\\[/spoiler]", RegexOption.IGNORE_CASE)
        for (depth in 0 until 8) {
            val next = spoilerRegex.replace(source) { match ->
                val inner = match.groupValues[1]
                if (Regex("\\r?\\n[ \\t]*\\r?\\n").containsMatchIn(inner)) {
                    inner.split(Regex("(?:\\r?\\n[ \\t]*){2,}"))
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .joinToString("\n\n") { seg -> "<span class=\"spoiler\">$seg</span>" }
                } else {
                    "<span class=\"spoiler\">$inner</span>"
                }
            }
            if (next == source) break
            source = next
        }
        val quoteRegex = Regex("\\[quote(?:=\"?([^\\]\"]*)\"?)?]((?:(?!\\[quote(?:=|]))[\\s\\S])*?)\\[/quote]", RegexOption.IGNORE_CASE)
        for (depth in 0 until 8) {
            val next = quoteRegex.replace(source) {
                val parts = it.groupValues[1].split(',').map(String::trim)
                val user = parts.firstOrNull().orEmpty()
                val postAttr = parts.firstOrNull { p -> p.startsWith("post:", ignoreCase = true) }
                    ?.substringAfter(':')?.trim()?.toIntOrNull()?.let { n -> " data-post=\"$n\"" }.orEmpty()
                val topicAttr = parts.firstOrNull { p -> p.startsWith("topic:", ignoreCase = true) }
                    ?.substringAfter(':')?.trim()?.toIntOrNull()?.let { n -> " data-topic=\"$n\"" }.orEmpty()
                "\n<aside class=\"quote\" data-username=\"${attr(user)}\"$postAttr$topicAttr><blockquote>\n\n${it.groupValues[2]}\n\n</blockquote></aside>\n"
            }
            if (next == source) break
            source = next
        }
        source = Regex("\\[poll[^\\]]*]([\\s\\S]*?)\\[/poll]", RegexOption.IGNORE_CASE).replace(source) {
            val lines = it.groupValues[1].lines().map(String::trim)
            val title = lines.firstOrNull { line -> line.startsWith("#") }?.trimStart('#')?.trim().orEmpty().ifBlank { "投票预览" }
            val options = lines.filter { line -> line.startsWith("- ") || line.startsWith("* ") }
            "\n<div class=\"poll\" data-poll-title=\"${attr(title)}\"><ul>${options.joinToString("") { opt -> "<li>${org.jsoup.nodes.Entities.escape(opt.drop(2))}</li>" }}</ul></div>\n"
        }
        // Restore in reverse order in case an indented-code token contains an earlier token.
        protected.indices.reversed().forEach { source = source.replace("$tokenPrefix${it}END", protected[it]) }
        val body = Jsoup.parseBodyFragment(renderer.render(parser.parse(source))).body()
        val alertMarker = Regex(
            "^\\[!(AI|NOTE|INFO|TODO|TIP|HINT|IMPORTANT|SUCCESS|CHECK|DONE|WARNING|ATTENTION|CAUTION|DANGER|ERROR|FAILURE|FAIL|BUG|EXAMPLE|QUOTE|CITE|ABSTRACT|SUMMARY|TLDR|QUESTION|HELP|FAQ)\\]([+-])?(?:[ \\t]+([^\\r\\n]*))?",
            RegexOption.IGNORE_CASE
        )
        body.select("blockquote").forEach { quote ->
            val firstP = quote.children().firstOrNull { it.normalName() == "p" } ?: return@forEach
            val firstText = firstP.childNodes().firstOrNull() as? TextNode ?: return@forEach
            val rawFirst = firstText.wholeText.trimStart()
            val firstLine = rawFirst.substringBefore('\n')
            val match = alertMarker.find(firstLine) ?: return@forEach
            val rawKind = match.groupValues[1].lowercase()
            val normalizedKind = when (rawKind) {
                "tip", "hint", "success", "check", "done", "example" -> "tip"
                "important", "abstract", "summary", "tldr", "question", "help", "faq" -> "important"
                "warning", "attention" -> "warning"
                "caution", "danger", "error", "failure", "fail", "bug" -> "caution"
                "quote", "cite" -> "quote"
                else -> "note"
            }
            val foldFlag = match.groupValues[2]
            val customTitle = match.groupValues[3].trim().ifEmpty { if (rawKind == "ai") "AI 生成" else "" }
            quote.tagName("div").addClass("markdown-alert").addClass("markdown-alert-$normalizedKind")
            if (customTitle.isNotEmpty()) {
                quote.attr("data-callout-title", customTitle)
            }
            if (foldFlag == "+" || foldFlag == "-") {
                quote.attr("data-callout-collapsible", "true")
                quote.attr("data-callout-expanded", if (foldFlag == "-") "false" else "true")
            }
            val afterNewline = if (rawFirst.contains('\n')) rawFirst.substringAfter('\n') else ""
            if (afterNewline.isNotBlank()) {
                firstText.text(afterNewline)
            } else {
                firstText.remove()
                val nextChild = firstP.childNodes().firstOrNull()
                if (nextChild is Element && nextChild.normalName() == "br") {
                    nextChild.remove()
                }
                if (firstP.childNodes().isEmpty() || (firstP.text().isBlank() && firstP.selectFirst("img, code, pre") == null)) {
                    firstP.remove()
                }
            }
        }
        val textNodes = mutableListOf<TextNode>()
        fun collect(element: Element) {
            if (element.normalName() in setOf("code", "pre", "script", "style")) return
            element.childNodes().forEach { node -> when (node) {
                is TextNode -> textNodes += node
                is Element -> collect(node)
            } }
        }
        collect(body)
        val shortcode = Regex("(?<![a-zA-Z0-9:]):([a-zA-Z0-9_+-]{2,36}(?::t[2-6])?):(?![a-zA-Z0-9:])")
        for (node in textNodes) {
            val text = node.wholeText
            val matches = shortcode.findAll(text).filter { m ->
                val rawCode = m.groupValues[1]
                LinuxDoEmojiCatalog.shortcodeToUnicode(rawCode) != null || LinuxDoEmojiCatalog.isCustomImageEmoji(rawCode)
            }.toList()
            if (matches.isEmpty()) continue
            var offset = 0
            for (match in matches) {
                if (match.range.first > offset) node.before(TextNode(text.substring(offset, match.range.first)))
                val image = Element("img").addClass("emoji").attr("alt", match.value)
                    .attr("src", LinuxDoEmojiCatalog.resolveReactionImageUrl(match.groupValues[1]))
                node.before(image)
                offset = match.range.last + 1
            }
            if (offset < text.length) node.before(TextNode(text.substring(offset)))
            node.remove()
        }
        return body.html()
    }
}
