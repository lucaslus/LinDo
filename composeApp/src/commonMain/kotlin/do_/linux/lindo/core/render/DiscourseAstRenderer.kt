package do_.linux.lindo.core.render

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.net.URI

data class InlineRun(val text: String, val bold: Boolean = false, val italic: Boolean = false,
    val code: Boolean = false, val strike: Boolean = false, val underline: Boolean = false, val href: String? = null, val imageUrl: String? = null, val largeEmoji: Boolean = false)
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
    data class Spoiler(val children: List<PostBlockNode>) : PostBlockNode
    data class PollOption(val id: String, val text: String)
    data class Poll(val name: String, val title: String, val options: List<PollOption>, val multiple: Boolean, val min: Int, val max: Int, val closed: Boolean) : PostBlockNode
    data class BulletList(val items: List<String>, val ordered: Boolean = false,
        val itemBlocks: List<List<PostBlockNode>> = emptyList(), val start: Int = 1) : PostBlockNode
    data class ImageGallery(
        val urls: List<String>,
        val captions: List<String> = emptyList(),
        val fullUrls: List<String> = urls,
        val linkUrls: List<String?> = emptyList()
    ) : PostBlockNode
    data class Table(val rows: List<List<List<PostBlockNode>>>, val headerRows: Set<Int>) : PostBlockNode
    data class Media(val label: String, val url: String?, val kind: String) : PostBlockNode
    data class OneboxCard(
        val title: String,
        val description: String,
        val url: String,
        val domain: String,
        val imageUrl: String? = null
    ) : PostBlockNode
    data class CalloutBlock(
        val kind: String,
        val title: String,
        val children: List<PostBlockNode> = emptyList()
    ) : PostBlockNode
    data class Unsupported(
        val label: String,
        val text: String,
        val options: List<String> = emptyList(),
        val kind: String = "generic"
    ) : PostBlockNode
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

    private fun encodeLooseUriChars(input: String): String {
        val ipv6Prefix = Regex("^(?:https?:)?//\\[[0-9a-fA-F:]+]").find(input)?.value.orEmpty()
        val rest = input.substring(ipv6Prefix.length)
        val sb = StringBuilder(input.length + 16)
        sb.append(ipv6Prefix)
        for (ch in rest) {
            when (ch) {
                ' ' -> sb.append("%20")
                '|' -> sb.append("%7C")
                '[' -> sb.append("%5B")
                ']' -> sb.append("%5D")
                '{' -> sb.append("%7B")
                '}' -> sb.append("%7D")
                '^' -> sb.append("%5E")
                '`' -> sb.append("%60")
                '"' -> sb.append("%22")
                '<' -> sb.append("%3C")
                '>' -> sb.append("%3E")
                '\\' -> sb.append("%5C")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun safeUrl(raw: String, allowMailto: Boolean = true): String? = runCatching {
        val value = raw.trim().let {
            if (it.startsWith("upload://") && it.removePrefix("upload://").matches(Regex("[A-Za-z0-9_.-]+")))
                "https://linux.do/uploads/short-url/${it.removePrefix("upload://")}" else it
        }
        if (value.isEmpty()) return null
        val encoded = encodeLooseUriChars(value)
        val uri = URI("https://linux.do/").resolve(encoded)
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
            if (rich.text.isNotBlank() || rich.runs.any { it.code && it.text.isNotEmpty() }) {
                result += PostBlockNode.Paragraph(
                    rich.text,
                    rich.runs.filter { it.code }.map { it.text },
                    rich
                )
            }
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
                node.hasClass("spoiler") || node.hasClass("spoilered") -> {
                    flush()
                    result += PostBlockNode.Spoiler(blocks(node.childNodes(), nesting + 1))
                }
                node.hasClass("poll") -> {
                    flush()
                    val title = node.attr("data-poll-title")
                        .ifBlank { node.selectFirst(".poll-title, p, h3, h4")?.text().orEmpty() }
                        .ifBlank { "社区投票" }
                    val liOptions = node.select("li").map { it.text().trim() }.filter { it.isNotBlank() }
                    val options = node.select("[data-poll-option-id]").map { PostBlockNode.PollOption(it.attr("data-poll-option-id"), it.text().trim()) }.filter { it.id.isNotBlank() }
                    if (options.isNotEmpty() && node.attr("data-poll-name").isNotBlank()) {
                        val multiple = node.attr("data-poll-type") == "multiple"
                        val minChoices = if (multiple) (node.attr("data-poll-min").toIntOrNull() ?: 1).coerceAtLeast(1) else 1
                        val maxChoices = if (multiple) (node.attr("data-poll-max").toIntOrNull() ?: options.size).coerceAtLeast(minChoices) else 1
                        result += PostBlockNode.Poll(
                            node.attr("data-poll-name"),
                            title,
                            options,
                            multiple,
                            minChoices,
                            maxChoices,
                            node.attr("data-poll-status") == "closed"
                        )
                    } else result += PostBlockNode.Unsupported(
                        label = title,
                        text = node.wholeText().trim(),
                        options = liOptions,
                        kind = "poll"
                    )
                }
                node.hasClass("math") || node.hasClass("math-container") || node.hasClass("katex") -> {
                    flush()
                    result += PostBlockNode.Unsupported(
                        label = "数学公式",
                        text = node.attr("data-math").ifBlank { node.text() },
                        kind = "math"
                    )
                }
                tag == "aside" && node.hasClass("onebox") -> {
                    flush()
                    val rawUrl = node.attr("data-onebox-src")
                        .ifBlank { node.selectFirst("header a[href], h3 a[href], h4 a[href], a[href]")?.attr("href").orEmpty() }
                    val safeLink = safeUrl(rawUrl, allowMailto = false).orEmpty()
                    val title = node.selectFirst("h3, h4, .onebox-body h3, .onebox-body h4, header a")?.text().orEmpty()
                        .ifBlank { safeLink.ifBlank { "引用链接" } }
                    val desc = node.selectFirst(".onebox-body p, p")?.text().orEmpty()
                        .ifBlank { node.text().removePrefix(title).trim().take(180) }
                    val domain = node.selectFirst("header .source, header a")?.text().orEmpty()
                        .ifBlank { runCatching { URI(safeLink).host.orEmpty() }.getOrDefault("") }
                    val thumb = node.selectFirst("img.thumbnail, .onebox-body img")?.let {
                        safeUrl(it.attr("src").ifBlank { it.attr("data-src") }, allowMailto = false)
                    }
                    if (safeLink.isNotBlank()) {
                        result += PostBlockNode.OneboxCard(
                            title = title,
                            description = desc,
                            url = safeLink,
                            domain = domain,
                            imageUrl = thumb
                        )
                    } else {
                        node.childNodes().forEach { visit(it, style, nesting + 1) }
                        flush()
                    }
                }
                node.hasClass("markdown-alert") || node.hasClass("admonition") ||
                    node.attr("data-wrap").equals("lottery", ignoreCase = true) ||
                    node.hasClass("linuxdo-lottery") -> {
                    flush()
                    val isLottery = node.attr("data-wrap").equals("lottery", ignoreCase = true) || node.hasClass("linuxdo-lottery")
                    val kind = when {
                        isLottery -> "lottery"
                        node.classNames().any { it.contains("warning") || it.contains("caution") } -> "warning"
                        node.classNames().any { it.contains("tip") || it.contains("important") } -> "tip"
                        else -> "note"
                    }
                    val titleEl = node.selectFirst(".markdown-alert-title, .admonition-title, .lottery-title")
                    val title = titleEl?.text()?.trim().orEmpty().ifBlank {
                        when (kind) {
                            "lottery" -> "社区抽奖活动"
                            "warning" -> "注意"
                            "tip" -> "提示"
                            else -> "说明"
                        }
                    }
                    val childrenNodes = blocks(node.childNodes().filter { it !== titleEl }, nesting + 1)
                    result += PostBlockNode.CalloutBlock(
                        kind = kind,
                        title = title,
                        children = childrenNodes.ifEmpty { listOf(PostBlockNode.Paragraph(node.text().trim())) }
                    )
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
                    val bodyNodes = node.childNodes().filter { it !== summary }
                    val children = blocks(bodyNodes, nesting + 1)
                    val bodyText = bodyNodes.joinToString("") { n ->
                        when (n) {
                            is TextNode -> n.wholeText
                            is Element -> n.wholeText()
                            else -> ""
                        }
                    }.trim()
                    result += PostBlockNode.DetailsSpoilerBlock(summary?.text().orEmpty().ifBlank { "展开内容" }, bodyText, children)
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
                    if (node.select("[rowspan], [colspan]").isNotEmpty()) {
                        result += PostBlockNode.Unsupported(
                            label = "合并单元格表格",
                            text = "单元格内容已按顺序展示，可在原网页查看合并布局。",
                            kind = "merged_table"
                        )
                    }
                }
                tag in setOf("video", "audio", "iframe", "object", "embed") -> {
                    flush(); val raw = node.attr("src").ifBlank { node.attr("data") }.ifBlank { node.selectFirst("source")?.attr("src").orEmpty() }
                    result += PostBlockNode.Media(node.attr("title").ifBlank { if (tag == "audio") "音频" else "视频或嵌入内容" }, safeUrl(raw, allowMailto = false), tag)
                }
                tag == "input" && node.attr("type").equals("checkbox", ignoreCase = true) -> {
                    val isChecked = node.hasAttr("checked") || node.attr("checked").equals("true", ignoreCase = true)
                    pending.add(style.copy(text = if (isChecked) "☑️ " else "⬜ "))
                }
                tag == "svg" -> {
                    // Discourse 行内 FontAwesome / 标签 / 勾选框 SVG 图标原生内联映射，绝不打断段落或报“互动内容需原网页查看”
                    val classes = node.classNames().joinToString(" ").lowercase()
                    val useHref = node.selectFirst("use")?.let {
                        it.attr("href").ifBlank { it.attr("xlink:href") }
                    }.orEmpty().lowercase()
                    val parentIsHashtag = node.parents().any { it.hasClass("hashtag-cooked") }
                    val inlineSymbol = when {
                        parentIsHashtag || classes.contains("d-icon-tag") || useHref.contains("tag") -> "#"
                        classes.contains("check-square") || classes.contains("square-check") ||
                            useHref.contains("check-square") || useHref.contains("square-check") -> "☑️ "
                        classes.contains("far-square") || useHref == "#far-square" || useHref == "#square" -> "⬜ "
                        classes.contains("d-icon-lock") || useHref.contains("lock") -> "🔒 "
                        classes.contains("d-icon-thumbtack") || useHref.contains("thumbtack") -> "📌 "
                        else -> null
                    }
                    if (inlineSymbol != null) {
                        val prevText = pending.lastOrNull()?.text.orEmpty()
                        if (inlineSymbol != "#" || !prevText.endsWith("#")) {
                            pending.add(style.copy(text = inlineSymbol))
                        }
                    } else {
                        val svgText = node.text().trim()
                        val isDecorativeIcon = node.hasClass("d-icon") ||
                            node.hasClass("svg-icon") ||
                            node.hasClass("fa") ||
                            node.hasClass("octicon") ||
                            node.parents().any { it.hasClass("anchor") || it.hasClass("chckbx-box") }
                        if (!isDecorativeIcon) {
                            flush()
                            result += PostBlockNode.Unsupported("图形内容", svgText.ifBlank { "此图形可在原帖中查看" }, kind = "svg")
                        }
                    }
                }
                tag == "canvas" || tag == "form" -> {
                    val inner = blocks(node.childNodes(), nesting + 1)
                    if (inner.isNotEmpty()) {
                        flush()
                        result += inner
                    } else if (node.text().isNotBlank()) {
                        flush()
                        result += PostBlockNode.Paragraph(node.text().trim())
                    }
                }
                tag == "img" && (node.hasClass("emoji") || node.attr("src").contains("/emoji/") || node.attr("src").contains("twemoji") || node.attr("data-src").contains("/emoji/")) -> {
                    val rawEmojiSrc = node.attr("src").ifBlank { node.attr("data-src") }
                    val emojiLabel = node.attr("title").ifBlank { node.attr("alt") }.ifBlank { "[表情]" }
                    pending.add(
                        style.copy(
                            text = emojiLabel,
                            imageUrl = safeUrl(rawEmojiSrc, allowMailto = false),
                            largeEmoji = node.hasClass("only-emoji")
                        )
                    )
                }
                tag == "img" -> {
                    flush()
                    resolveImageDisplayAndFullUrl(node)?.let { (displayUrl, fullUrl) ->
                        val linkUrl = resolveImageLinkUrl(node, style.href)
                        val prev = result.lastOrNull() as? PostBlockNode.ImageGallery
                        if (prev != null) {
                            val prevCaptions = List(prev.urls.size) { prev.captions.getOrNull(it).orEmpty() }
                            val prevFullUrls = List(prev.urls.size) { prev.fullUrls.getOrElse(it) { prev.urls[it] } }
                            val prevLinkUrls = List(prev.urls.size) { prev.linkUrls.getOrNull(it) }
                            result[result.lastIndex] = PostBlockNode.ImageGallery(
                                urls = prev.urls + displayUrl,
                                captions = prevCaptions + node.attr("alt"),
                                fullUrls = prevFullUrls + fullUrl,
                                linkUrls = prevLinkUrls + linkUrl
                            )
                        } else {
                            result += PostBlockNode.ImageGallery(
                                urls = listOf(displayUrl),
                                captions = listOf(node.attr("alt")),
                                fullUrls = listOf(fullUrl),
                                linkUrls = listOf(linkUrl)
                            )
                        }
                    } ?: run {
                        result += PostBlockNode.Unsupported("图片暂不可用", node.attr("alt"))
                    }
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

    private fun resolveImageDisplayAndFullUrl(node: Element): Pair<String, String>? {
        val lightboxHref = node.parents()
            .firstOrNull {
                it.normalName() == "a" && (it.hasClass("lightbox") || it.attr("href").contains("/uploads/"))
            }
            ?.attr("href")
            .orEmpty()
            .trim()
        val srcsetEntries = node.attr("srcset")
            .split(',')
            .map { it.trim().substringBefore(' ').trim() }
            .filter { it.isNotEmpty() }
        val midSrcset = srcsetEntries.getOrNull((srcsetEntries.size - 1).coerceAtMost(1)).orEmpty()
        val bestSrcset = srcsetEntries.lastOrNull().orEmpty()

        val displayCandidates = listOf(
            midSrcset,
            node.attr("src").trim(),
            node.attr("data-src").trim(),
            node.attr("data-large-src").trim(),
            bestSrcset,
            lightboxHref,
            node.attr("data-orig-src").trim()
        )
        val fullCandidates = listOf(
            lightboxHref,
            node.attr("data-orig-src").trim(),
            node.attr("data-large-src").trim(),
            bestSrcset,
            node.attr("src").trim(),
            node.attr("data-src").trim()
        )
        val displayUrl = displayCandidates.firstNotNullOfOrNull { c ->
            if (c.isNotEmpty()) safeUrl(c, allowMailto = false) else null
        } ?: return null
        val fullUrl = fullCandidates.firstNotNullOfOrNull { c ->
            if (c.isNotEmpty()) safeUrl(c, allowMailto = false) else null
        } ?: displayUrl
        return displayUrl to fullUrl
    }

    private val imageFileExtensions = listOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".avif", ".svg")

    private fun resolveImageLinkUrl(node: Element, inheritedHref: String?): String? {
        val anchor = node.parents().firstOrNull { it.normalName() == "a" }
        if (anchor != null && anchor.hasClass("lightbox")) return null
        val candidate = inheritedHref ?: anchor?.let { safeUrl(it.attr("href"), allowMailto = false) } ?: return null
        if (candidate.startsWith("mailto:", ignoreCase = true)) return null
        val pathOnly = candidate.substringBefore('#').substringBefore('?').lowercase()
        if (pathOnly.contains("/uploads/") || imageFileExtensions.any { pathOnly.endsWith(it) }) {
            return null
        }
        return candidate
    }

    private fun trimmed(runs: List<InlineRun>): RichText {
        val copy = runs.filter { it.text.isNotEmpty() || it.imageUrl != null }.toMutableList()
        while (copy.isNotEmpty() && !copy.first().code && copy.first().imageUrl == null && copy.first().text.isBlank()) {
            copy.removeAt(0)
        }
        while (copy.isNotEmpty() && !copy.last().code && copy.last().imageUrl == null && copy.last().text.isBlank()) {
            copy.removeAt(copy.lastIndex)
        }
        if (copy.isNotEmpty()) {
            if (!copy.first().code && copy.first().imageUrl == null) {
                copy[0] = copy.first().copy(text = copy.first().text.trimStart())
            }
            if (!copy.last().code && copy.last().imageUrl == null) {
                copy[copy.lastIndex] = copy.last().copy(text = copy.last().text.trimEnd())
            }
        }
        return RichText(copy)
    }
}
