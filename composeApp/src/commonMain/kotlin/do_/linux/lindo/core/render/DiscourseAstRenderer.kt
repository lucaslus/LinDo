package do_.linux.lindo.core.render

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.net.URI

data class InlineRun(val text: String, val bold: Boolean = false, val italic: Boolean = false,
    val code: Boolean = false, val strike: Boolean = false, val underline: Boolean = false, val href: String? = null, val imageUrl: String? = null)
data class RichText(val runs: List<InlineRun>) {
    val text: String get() = runs.joinToString("") { it.text }
    companion object { fun plain(text: String) = RichText(listOf(InlineRun(text))) }
}

sealed interface PostBlockNode {
    data class Heading(val level: Int, val text: String, val rich: RichText = RichText.plain(text)) : PostBlockNode
    data class Paragraph(val text: String, val inlineCodeSpans: List<String> = emptyList(), val rich: RichText = RichText.plain(text)) : PostBlockNode
    data class CodeBlock(val language: String, val code: String) : PostBlockNode
    data class QuoteBlock(val username: String?, val postNumber: Int?, val topicId: Int?, val quotedText: String,
        val children: List<PostBlockNode> = emptyList()) : PostBlockNode
    data class DetailsSpoilerBlock(val title: String, val innerText: String, val children: List<PostBlockNode> = emptyList()) : PostBlockNode
    data class BulletList(val items: List<String>, val ordered: Boolean = false,
        val itemBlocks: List<List<PostBlockNode>> = emptyList(), val start: Int = 1) : PostBlockNode
    data class ImageGallery(val urls: List<String>, val captions: List<String> = emptyList()) : PostBlockNode
    data class Table(val rows: List<List<List<PostBlockNode>>>, val headerRows: Set<Int>) : PostBlockNode
    data class Media(val label: String, val url: String?, val kind: String) : PostBlockNode
    data class Unsupported(val label: String, val text: String) : PostBlockNode
    data object Divider : PostBlockNode
}

/** DOM parsing runs off the UI thread; cache identity includes full content, not just a hash. */
object DiscourseAstParser {
    private const val MAX_CACHE_ENTRIES = 256
    private const val MAX_CACHE_HTML_CHARS = 1_000_000
    private data class CacheKey(val postId: Int, val html: String)
    private val astCache = LinkedHashMap<CacheKey, List<PostBlockNode>>(256, .75f, true)
    private var cachedHtmlChars = 0
    fun signatureOf(postId: Int, cookedHtml: String) = (postId.toLong() shl 32) xor (cookedHtml.hashCode().toLong() and 0xFFFFFFFFL)
    fun getCached(postId: Int, cookedHtml: String) = synchronized(astCache) { astCache[CacheKey(postId, cookedHtml)] }
    fun parseOrGetCached(postId: Int, cookedHtml: String): List<PostBlockNode> {
        getCached(postId, cookedHtml)?.let { return it }
        val parsed = parseCookedHtml(cookedHtml)
        if (cookedHtml.length > MAX_CACHE_HTML_CHARS / 4) return parsed
        val key = CacheKey(postId, cookedHtml)
        synchronized(astCache) {
            astCache[key]?.let { return it }
            while (astCache.size >= MAX_CACHE_ENTRIES || cachedHtmlChars + cookedHtml.length > MAX_CACHE_HTML_CHARS) {
                val oldest = astCache.entries.firstOrNull()?.key ?: break
                cachedHtmlChars -= oldest.html.length
                astCache.remove(oldest)
            }
            astCache[key] = parsed
            cachedHtmlChars += cookedHtml.length
        }
        return parsed
    }

    fun safeUrl(raw: String, allowMailto: Boolean = true): String? = runCatching {
        val value = raw.trim()
        if (value.isEmpty()) return null
        val uri = URI("https://linux.do/").resolve(value)
        if (uri.scheme?.lowercase() !in if (allowMailto) setOf("https", "http", "mailto") else setOf("https", "http")) return null
        if (uri.scheme != "mailto" && uri.host.isNullOrBlank()) return null
        uri.toASCIIString()
    }.getOrNull()

    fun parseCookedHtml(cookedHtml: String): List<PostBlockNode> {
        if (cookedHtml.isBlank()) return emptyList()
        return blocks(Jsoup.parseBodyFragment(cookedHtml, "https://linux.do/").body().childNodes(), 0)
    }
    fun stripHtmlTags(html: String): String = Jsoup.parseBodyFragment(html).body().wholeText()

    private fun blocks(nodes: List<Node>, depth: Int): List<PostBlockNode> {
        if (depth > 48) return listOf(PostBlockNode.Unsupported("深层嵌套内容", nodes.joinToString(" ") { if (it is Element) it.text() else it.toString() }))
        val result = mutableListOf<PostBlockNode>()
        val pending = mutableListOf<InlineRun>()
        fun flush() {
            val rich = trimmed(pending)
            if (rich.text.isNotBlank()) result += PostBlockNode.Paragraph(rich.text,
                rich.runs.filter { it.code }.map { it.text }, rich)
            pending.clear()
        }
        fun visit(node: Node, style: InlineRun = InlineRun(""), nesting: Int = depth) {
            if (nesting > 48) { flush(); result += PostBlockNode.Unsupported("深层嵌套内容", (node as? Element)?.text().orEmpty()); return }
            if (node is TextNode) {
                pending += style.copy(text = if (style.code) node.wholeText else node.wholeText.replace(Regex("[\\t\\n\\r ]+"), " "))
                return
            }
            if (node !is Element) return
            val tag = node.normalName()
            when {
                tag in setOf("script", "style", "noscript", "template") || node.hasAttr("hidden") || node.hasClass("meta") -> Unit
                node.hasClass("poll") -> { flush(); result += PostBlockNode.Unsupported("投票", node.wholeText().trim()) }
                node.hasClass("math") || node.hasClass("math-container") || node.hasClass("katex") -> {
                    flush(); result += PostBlockNode.Unsupported("数学公式", node.attr("data-math").ifBlank { node.text() })
                }
                tag == "pre" -> {
                    flush(); val code = node.selectFirst("code") ?: node
                    val language = code.classNames().firstOrNull { it.startsWith("lang-") || it.startsWith("language-") }?.substringAfter('-').orEmpty()
                    result += PostBlockNode.CodeBlock(language, code.wholeText().trimEnd('\n'))
                }
                tag == "blockquote" || (tag == "aside" && node.hasClass("quote")) -> {
                    flush(); val body = node.selectFirst("blockquote") ?: node
                    result += PostBlockNode.QuoteBlock(node.attr("data-username").ifBlank { null }, node.attr("data-post").toIntOrNull(),
                        node.attr("data-topic").toIntOrNull(), body.wholeText().trim(), blocks(body.childNodes(), nesting + 1))
                }
                tag == "details" -> {
                    flush(); val summary = node.children().firstOrNull { it.normalName() == "summary" }
                    val children = blocks(node.childNodes().filter { it !== summary }, nesting + 1)
                    result += PostBlockNode.DetailsSpoilerBlock(summary?.text().orEmpty().ifBlank { "展开内容" }, node.wholeText(), children)
                }
                tag == "ul" || tag == "ol" -> {
                    flush(); val items = node.children().filter { it.normalName() == "li" }
                    result += PostBlockNode.BulletList(items.map { it.text() }, tag == "ol",
                        items.map { blocks(it.childNodes(), nesting + 1) }, node.attr("start").toIntOrNull() ?: 1)
                }
                tag == "table" -> {
                    flush(); val rows = node.select("tr").filter { it.closest("table") === node }
                    result += PostBlockNode.Table(rows.map { row -> row.children().filter { it.normalName() in setOf("td", "th") }.map { blocks(it.childNodes(), nesting + 1) } },
                        rows.mapIndexedNotNull { i, row -> if (row.children().any { it.normalName() == "th" }) i else null }.toSet())
                    if (node.select("[rowspan], [colspan]").isNotEmpty()) result += PostBlockNode.Unsupported("合并单元格表格", "单元格内容已按顺序展示，可在原网页查看合并布局。")
                }
                tag in setOf("video", "audio", "iframe", "object", "embed") -> {
                    flush(); val raw = node.attr("src").ifBlank { node.attr("data") }.ifBlank { node.selectFirst("source")?.attr("src").orEmpty() }
                    result += PostBlockNode.Media(node.attr("title").ifBlank { if (tag == "audio") "音频" else "视频或嵌入内容" }, safeUrl(raw, allowMailto = false), tag)
                }
                tag == "svg" || tag == "canvas" || tag == "form" -> { flush(); result += PostBlockNode.Unsupported("互动内容", node.text()) }
                tag == "img" && (node.hasClass("emoji") || node.attr("src").contains("/emoji/") || node.attr("src").contains("twemoji")) ->
                    pending.add(style.copy(text = node.attr("alt").ifBlank { node.attr("title").ifBlank { "[表情]" } }, imageUrl = safeUrl(node.attr("src"), allowMailto = false)))
                tag == "img" -> {
                    flush(); val raw = node.attr("src").ifBlank { node.attr("data-src") }
                    safeUrl(raw, allowMailto = false)?.let { result += PostBlockNode.ImageGallery(listOf(it), listOf(node.attr("alt"))) }
                        ?: run { result += PostBlockNode.Unsupported("图片暂不可用", node.attr("alt")) }
                }
                tag == "hr" -> { flush(); result += PostBlockNode.Divider }
                tag.matches(Regex("h[1-6]")) -> {
                    flush(); val content = blocks(node.childNodes(), nesting + 1)
                    val rich = (content.firstOrNull() as? PostBlockNode.Paragraph)?.rich ?: RichText.plain(node.text())
                    result += PostBlockNode.Heading(tag[1].digitToInt(), rich.text, rich)
                    result += if (content.firstOrNull() is PostBlockNode.Paragraph) content.drop(1) else content
                }
                tag == "br" -> pending.add(style.copy(text = "\n"))
                tag in setOf("p", "div", "section", "article", "figure", "figcaption", "dl", "dt", "dd", "li") -> {
                    flush(); node.childNodes().forEach { visit(it, style, nesting + 1) }; flush()
                }
                else -> {
                    val next = when (tag) {
                        "b", "strong" -> style.copy(bold = true)
                        "i", "em" -> style.copy(italic = true)
                        "s", "del", "strike" -> style.copy(strike = true)
                        "u" -> style.copy(underline = true)
                        "code", "kbd" -> style.copy(code = true)
                        "a" -> style.copy(href = safeUrl(node.attr("href")))
                        else -> style
                    }
                    node.childNodes().forEach { visit(it, next, nesting + 1) }
                }
            }
        }
        nodes.forEach { visit(it) }; flush()
        return result
    }
    private fun trimmed(runs: List<InlineRun>): RichText {
        val copy = runs.filter { it.text.isNotEmpty() }.toMutableList()
        while (copy.isNotEmpty() && copy.first().text.isBlank()) copy.removeAt(0)
        while (copy.isNotEmpty() && copy.last().text.isBlank()) copy.removeAt(copy.lastIndex)
        if (copy.isNotEmpty()) {
            copy[0] = copy.first().copy(text = copy.first().text.trimStart())
            copy[copy.lastIndex] = copy.last().copy(text = copy.last().text.trimEnd())
        }
        return RichText(copy)
    }
}
