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
            .replace(raw) { protect(it.value) }
        source = Regex("(`+)([^`]|(?!\\1)`)*?\\1").replace(source) { protect(it.value) }
        // Indented code must also remain literal when it contains Discourse-looking syntax.
        source = Regex("(?m)^(?:(?: {4}|\\t)[^\\n]*(?:\\n|$))+").replace(source) { protect(it.value) }
        fun attr(value: String) = Element("span").attr("data-value", value).outerHtml().substringAfter("data-value=\"").substringBefore('"')
        source = Regex("\\[details(?:=\"?([^\\]\"]*)\"?)?]([\\s\\S]*?)\\[/details]", RegexOption.IGNORE_CASE).replace(source) {
            "\n<details><summary>${org.jsoup.nodes.Entities.escape(it.groupValues[1].ifBlank { "展开内容" })}</summary>\n\n${it.groupValues[2]}\n\n</details>\n"
        }
        source = Regex("\\[spoiler]([\\s\\S]*?)\\[/spoiler]", RegexOption.IGNORE_CASE).replace(source) {
            "<span class=\"spoiler\">${it.groupValues[1]}</span>"
        }
        source = Regex("\\[quote(?:=\"([^\"]*)\")?]([\\s\\S]*?)\\[/quote]", RegexOption.IGNORE_CASE).replace(source) {
            val parts = it.groupValues[1].split(',').map(String::trim)
            val user = parts.firstOrNull().orEmpty()
            "\n<aside class=\"quote\" data-username=\"${attr(user)}\"><blockquote>\n\n${it.groupValues[2]}\n\n</blockquote></aside>\n"
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
        val alertMarker = Regex("^\\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)]\\s*", RegexOption.IGNORE_CASE)
        body.select("blockquote").forEach { quote ->
            val firstText = quote.selectFirst("p")?.childNodes()?.firstOrNull() as? TextNode ?: return@forEach
            val match = alertMarker.find(firstText.wholeText.trimStart()) ?: return@forEach
            val kind = match.groupValues[1].lowercase()
            quote.tagName("div").addClass("markdown-alert").addClass("markdown-alert-$kind")
            val remaining = firstText.wholeText.trimStart().removePrefix(match.value)
            if (remaining.isBlank()) firstText.remove() else firstText.text(remaining)
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
        val shortcode = Regex(":([a-zA-Z0-9_+-]{2,36}):")
        for (node in textNodes) {
            val text = node.wholeText
            val matches = shortcode.findAll(text).toList()
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
