package do_.linux.lindo.core.render

import do_.linux.lindo.core.copy.CalloutBlockKind
import do_.linux.lindo.core.copy.LinDoNetworkCopy
import do_.linux.lindo.core.copy.LinDoReaderCopy
import do_.linux.lindo.core.copy.MediaBlockKind
import do_.linux.lindo.core.copy.UnsupportedBlockKind
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.net.URI

data class InlineRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strike: Boolean = false,
    val underline: Boolean = false,
    val href: String? = null,
    val imageUrl: String? = null,
    val largeEmoji: Boolean = false,
    val colorHex: String? = null,
    val bgColorHex: String? = null
)
data class RichText(val runs: List<InlineRun>) {
    val text: String get() = runs.joinToString("") { it.text }
    companion object { fun plain(text: String) = RichText(listOf(InlineRun(text))) }
}

sealed interface PostBlockNode {
    data class Heading(val level: Int, val text: String, val rich: RichText = RichText.plain(text)) : PostBlockNode
    data class Paragraph(val text: String, val inlineCodeSpans: List<String> = emptyList(), val rich: RichText = RichText.plain(text)) : PostBlockNode
    data class CodeBlock(val language: String, val code: String) : PostBlockNode
    data class QuoteBlock(
        val username: String?,
        val postNumber: Int?,
        val topicId: Int?,
        val quotedText: String,
        val children: List<PostBlockNode> = emptyList(),
        val title: String? = null,
        val titleUrl: String? = null,
        val isFull: Boolean = true
    ) : PostBlockNode
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
        val linkUrls: List<String?> = emptyList(),
        val aspectRatios: List<Float?> = emptyList()
    ) : PostBlockNode
    data class Table(val rows: List<List<List<PostBlockNode>>>, val headerRows: Set<Int>) : PostBlockNode
    data class Media(val label: String, val url: String?, val kind: String) : PostBlockNode {
        val kindEnum: MediaBlockKind get() = MediaBlockKind.fromKey(kind)
    }
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
        val children: List<PostBlockNode> = emptyList(),
        val collapsible: Boolean = false,
        val defaultExpanded: Boolean = true
    ) : PostBlockNode {
        val kindEnum: CalloutBlockKind get() = CalloutBlockKind.fromKey(kind)
    }
    data class Unsupported(
        val label: String,
        val text: String,
        val options: List<String> = emptyList(),
        val kind: String = UnsupportedBlockKind.GENERIC.wireKey
    ) : PostBlockNode {
        val kindEnum: UnsupportedBlockKind get() = UnsupportedBlockKind.fromKey(kind)
    }
    data object Divider : PostBlockNode
}

/** DOM parsing runs off the UI thread; cache identity includes full content, not just a hash. */
object DiscourseAstParser {
    private const val MAX_CACHE_ENTRIES = 256
    private const val MAX_CACHE_HTML_CHARS = 1_000_000
    private val mediaTagNames = MediaBlockKind.entries.map { it.wireKey }.toSet()
    private data class CacheKey(val postId: Int, val html: String)
    private val astCache = LinkedHashMap<CacheKey, List<PostBlockNode>>(256, .75f, true)
    private var cachedHtmlChars = 0
    fun clearCache() = synchronized(astCache) {
        astCache.clear()
        cachedHtmlChars = 0
    }
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

    private val obsidianCalloutRegex = Regex(
        """^\s*\[!(note|info|todo|tip|hint|important|success|check|done|warning|caution|attention|danger|error|failure|fail|bug|example|quote|cite|abstract|summary|tldr|question|help|faq)\]([+-])?(?:[ \t]+([^\r\n]*))?""",
        RegexOption.IGNORE_CASE
    )
    private val hexColorRegex = Regex("""^#([0-9a-fA-F]{3}|[0-9a-fA-F]{4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$""")
    private val rgbColorRegex = Regex("""^rgba?\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})(?:\s*,\s*([0-9.]+))?\s*\)$""", RegexOption.IGNORE_CASE)
    private val namedCssColors = mapOf(
        "red" to "#EF4444", "crimson" to "#DC2626", "darkred" to "#991B1B", "tomato" to "#F97316",
        "orange" to "#F97316", "darkorange" to "#EA580C", "amber" to "#F59E0B", "gold" to "#EAB308",
        "yellow" to "#EAB308", "green" to "#22C55E", "darkgreen" to "#15803D", "lime" to "#84CC16",
        "emerald" to "#10B981", "teal" to "#14B8A6", "cyan" to "#06B6D4", "aqua" to "#06B6D4",
        "skyblue" to "#38BDF8", "blue" to "#3B82F6", "royalblue" to "#2563EB", "navy" to "#1E40AF",
        "indigo" to "#6366F1", "purple" to "#A855F7", "violet" to "#8B5CF6", "magenta" to "#D946EF",
        "fuchsia" to "#D946EF", "pink" to "#EC4899", "hotpink" to "#EC4899", "brown" to "#A16207",
        "gray" to "#9CA3AF", "grey" to "#9CA3AF", "silver" to "#CBD5E1", "white" to "#F8FAFC",
        "black" to "#111827"
    )

    internal fun sanitizeSafeCssColor(raw: String?): String? {
        val clean = raw?.trim()?.removeSuffix("!important")?.trim() ?: return null
        if (clean.isEmpty() || clean.length > 48) return null
        val lower = clean.lowercase()
        if (lower.contains("url(") || lower.contains("expression(") || lower.contains("javascript:") || lower.contains(";")) {
            return null
        }
        namedCssColors[lower]?.let { return it }
        if (hexColorRegex.matches(clean)) return clean.uppercase()
        rgbColorRegex.matchEntire(clean)?.let { m ->
            val r = m.groupValues[1].toIntOrNull()?.coerceIn(0, 255) ?: return null
            val g = m.groupValues[2].toIntOrNull()?.coerceIn(0, 255) ?: return null
            val b = m.groupValues[3].toIntOrNull()?.coerceIn(0, 255) ?: return null
            val aStr = m.groupValues.getOrNull(4).orEmpty()
            return if (aStr.isNotBlank()) {
                val a = ((aStr.toFloatOrNull() ?: 1f).coerceIn(0f, 1f) * 255f).toInt()
                "#%02X%02X%02X%02X".format(r, g, b, a)
            } else {
                "#%02X%02X%02X".format(r, g, b)
            }
        }
        return null
    }

    private fun extractElementInlineColors(node: Element): Pair<String?, String?> {
        var fg = sanitizeSafeCssColor(node.attr("data-color").ifBlank { if (node.normalName() == "font") node.attr("color") else "" })
        var bg = sanitizeSafeCssColor(node.attr("data-bgcolor"))
        val styleAttr = node.attr("style")
        if (styleAttr.isNotBlank()) {
            styleAttr.split(';').forEach { decl ->
                val idx = decl.indexOf(':')
                if (idx > 0) {
                    val prop = decl.substring(0, idx).trim().lowercase()
                    val value = decl.substring(idx + 1).trim()
                    when (prop) {
                        "color" -> if (fg == null) fg = sanitizeSafeCssColor(value)
                        "background-color", "background" -> if (bg == null) bg = sanitizeSafeCssColor(value)
                    }
                }
            }
        }
        return fg to bg
    }

    private fun mapObsidianCalloutKind(rawType: String): CalloutBlockKind = when (rawType.lowercase()) {
        "tip", "hint", "success", "check", "done", "example" -> CalloutBlockKind.TIP
        "important", "abstract", "summary", "tldr", "question", "help", "faq" -> CalloutBlockKind.IMPORTANT
        "warning", "attention" -> CalloutBlockKind.WARNING
        "caution", "danger", "error", "failure", "fail", "bug" -> CalloutBlockKind.CAUTION
        "quote", "cite" -> CalloutBlockKind.QUOTE
        else -> CalloutBlockKind.NOTE
    }

    private fun tryParseObsidianCallout(blockquote: Element, nesting: Int): PostBlockNode.CalloutBlock? {
        val firstP = blockquote.children().firstOrNull { it.normalName() == "p" } ?: return null
        val firstNode = firstP.childNodes().firstOrNull() ?: return null
        val firstRawText = when (firstNode) {
            is TextNode -> firstNode.wholeText
            is Element -> if (firstNode.normalName() in setOf("strong", "b", "em", "span")) firstNode.wholeText() else return null
            else -> return null
        }
        val firstLine = firstRawText.substringBefore('\n')
        val match = obsidianCalloutRegex.find(firstLine) ?: return null
        val calloutKind = mapObsidianCalloutKind(match.groupValues[1])
        val foldFlag = match.groupValues[2]
        val customTitle = match.groupValues[3].trim()
        val title = customTitle.ifBlank { calloutKind.defaultTitle.text }
        val collapsible = foldFlag == "+" || foldFlag == "-"
        val defaultExpanded = foldFlag != "-"

        val clone = blockquote.clone()
        val clonedFirstP = clone.children().firstOrNull { it.normalName() == "p" }
        if (clonedFirstP != null) {
            val clonedFirstChild = clonedFirstP.childNodes().firstOrNull()
            val afterNewline = if (firstRawText.contains('\n')) firstRawText.substringAfter('\n') else ""
            if (afterNewline.isNotBlank() && clonedFirstChild is TextNode) {
                clonedFirstChild.text(afterNewline)
            } else {
                clonedFirstChild?.remove()
                // Strip leading <br> left immediately after `[!Note]+ title<br>body`
                val nextChild = clonedFirstP.childNodes().firstOrNull()
                if (nextChild is Element && nextChild.normalName() == "br") {
                    nextChild.remove()
                }
                if (clonedFirstP.childNodes().isEmpty() || clonedFirstP.text().isBlank() && clonedFirstP.selectFirst("img, code, pre") == null) {
                    clonedFirstP.remove()
                }
            }
        }
        val bodyBlocks = blocks(clone.childNodes(), nesting + 1)
        return PostBlockNode.CalloutBlock(
            kind = calloutKind.wireKey,
            title = title,
            children = bodyBlocks,
            collapsible = collapsible,
            defaultExpanded = defaultExpanded
        )
    }

    private fun friendlyMediaLabel(rawUrl: String?, mediaKind: MediaBlockKind, fallbackAttrTitle: String): String {
        if (fallbackAttrTitle.isNotBlank()) return fallbackAttrTitle
        if (mediaKind.isAudio) return LinDoReaderCopy.AST_AUDIO.text
        val host = rawUrl?.let { runCatching { URI(it).host.orEmpty().lowercase() }.getOrNull() }.orEmpty()
        return when {
            host.contains("bilibili.com") || host.contains("b23.tv") -> "Bilibili 视频"
            host.contains("youtube.com") || host.contains("youtu.be") -> "YouTube 视频"
            host.contains("vimeo.com") -> "Vimeo 视频"
            host.contains("codepen.io") -> "CodePen 演示"
            host.contains("codesandbox.io") -> "CodeSandbox 演示"
            host.contains("music.163.com") -> "网易云音乐"
            host.isNotBlank() && mediaKind == MediaBlockKind.IFRAME -> "嵌入内容 · ${host.removePrefix("www.")}"
            else -> LinDoReaderCopy.AST_VIDEO_OR_EMBED.text
        }
    }

    fun safeUrl(raw: String, allowMailto: Boolean = true): String? = runCatching {
        val value = raw.trim().let {
            if (it.startsWith("upload://") && it.removePrefix("upload://").matches(Regex("[A-Za-z0-9_.-]+")))
                "https://linux.do/uploads/short-url/${it.removePrefix("upload://")}" else it
        }
        if (value.isEmpty()) return null
        if (value.startsWith("#") && value.length > 1) return value
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
        if (depth > 48) {
            return listOf(
                PostBlockNode.Unsupported(
                    LinDoReaderCopy.AST_DEEP_NESTED.text,
                    nodes.joinToString(" ") { if (it is Element) it.text() else it.toString() }
                )
            )
        }
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
            if (nesting > 48) {
                flush()
                result += PostBlockNode.Unsupported(LinDoReaderCopy.AST_DEEP_NESTED.text, (node as? Element)?.text().orEmpty())
                return
            }
            if (node is TextNode) {
                pending += style.copy(text = if (style.code) node.wholeText else node.wholeText.replace(Regex("[\\t\\n\\r ]+"), " "))
                return
            }
            if (node !is Element) return
            val tag = node.normalName()
            when {
                tag in setOf("script", "style", "noscript", "template") || node.hasAttr("hidden") || node.hasClass("meta") -> Unit
                node.hasClass("spoiler") || node.hasClass("spoilered") || node.hasClass("spoiled") -> {
                    flush()
                    result += PostBlockNode.Spoiler(blocks(node.childNodes(), nesting + 1))
                }
                node.hasClass("poll") -> {
                    flush()
                    val title = node.attr("data-poll-title")
                        .ifBlank { node.selectFirst(".poll-title, p, h3, h4")?.text().orEmpty() }
                        .ifBlank { LinDoReaderCopy.AST_COMMUNITY_POLL.text }
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
                        kind = UnsupportedBlockKind.POLL.wireKey
                    )
                }
                node.hasClass("math") || node.hasClass("math-container") || node.hasClass("katex") -> {
                    flush()
                    result += PostBlockNode.Unsupported(
                        label = LinDoReaderCopy.AST_MATH_FORMULA.text,
                        text = node.attr("data-math").ifBlank { node.text() },
                        kind = UnsupportedBlockKind.MATH.wireKey
                    )
                }
                tag == "aside" && node.hasClass("onebox") -> {
                    flush()
                    val rawUrl = node.attr("data-onebox-src")
                        .ifBlank { node.selectFirst("header a[href], h3 a[href], h4 a[href], a[href]")?.attr("href").orEmpty() }
                    val safeLink = safeUrl(rawUrl, allowMailto = false).orEmpty()
                    val title = node.selectFirst("h3, h4, .onebox-body h3, .onebox-body h4, header a")?.text().orEmpty()
                        .ifBlank { safeLink.ifBlank { LinDoReaderCopy.AST_QUOTE_LINK.text } }
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
                    val calloutKind = when {
                        isLottery -> CalloutBlockKind.LOTTERY
                        node.classNames().any { it.contains("caution") || it.contains("danger") || it.contains("error") } -> CalloutBlockKind.CAUTION
                        node.classNames().any { it.contains("warning") } -> CalloutBlockKind.WARNING
                        node.classNames().any { it.contains("important") } -> CalloutBlockKind.IMPORTANT
                        node.classNames().any { it.contains("tip") || it.contains("success") } -> CalloutBlockKind.TIP
                        node.classNames().any { it.contains("quote") } -> CalloutBlockKind.QUOTE
                        else -> CalloutBlockKind.NOTE
                    }
                    val titleEl = node.selectFirst(".markdown-alert-title, .admonition-title, .lottery-title")
                    val title = node.attr("data-callout-title").trim()
                        .ifBlank { titleEl?.text()?.trim().orEmpty() }
                        .ifBlank { calloutKind.defaultTitle.text }
                    val collapsible = node.attr("data-callout-collapsible").equals("true", ignoreCase = true)
                    val defaultExpanded = !node.attr("data-callout-expanded").equals("false", ignoreCase = true)
                    val childrenNodes = blocks(node.childNodes().filter { it !== titleEl }, nesting + 1)
                    result += PostBlockNode.CalloutBlock(
                        kind = calloutKind.wireKey,
                        title = title,
                        children = childrenNodes.ifEmpty { listOf(PostBlockNode.Paragraph(node.text().trim())) },
                        collapsible = collapsible,
                        defaultExpanded = defaultExpanded
                    )
                }
                tag == "pre" -> {
                    flush(); val code = node.selectFirst("code") ?: node
                    val language = code.classNames().firstOrNull { it.startsWith("lang-") || it.startsWith("language-") }?.substringAfter('-').orEmpty()
                    result += PostBlockNode.CodeBlock(language, code.wholeText().trimEnd('\n'))
                }
                tag == "blockquote" -> {
                    flush()
                    val obsidian = tryParseObsidianCallout(node, nesting)
                    if (obsidian != null) {
                        result += obsidian
                    } else {
                        result += PostBlockNode.QuoteBlock(
                            username = node.attr("data-username").ifBlank { null },
                            postNumber = node.attr("data-post").toIntOrNull(),
                            topicId = node.attr("data-topic").toIntOrNull(),
                            quotedText = node.wholeText().trim(),
                            children = blocks(node.childNodes(), nesting + 1)
                        )
                    }
                }
                tag == "aside" && node.hasClass("quote") -> {
                    flush()
                    val body = node.selectFirst("blockquote") ?: node
                    val titleDiv = node.selectFirst("> div.title, .title")
                    val titleAnchor = titleDiv?.selectFirst("a[href]")
                    val rawTitleHref = titleAnchor?.attr("href").orEmpty().trim()
                    val resolvedTitleUrl = safeUrl(rawTitleHref, allowMailto = false)
                    val titleText = titleAnchor?.text()?.trim()?.takeIf { it.isNotBlank() }
                        ?: titleDiv?.text()?.trim()?.removeSuffix(":")?.trim()?.takeIf { it.isNotBlank() }
                    val dataFull = node.attr("data-full").trim()
                    val topicId = node.attr("data-topic").toIntOrNull()
                    val postNumber = node.attr("data-post").toIntOrNull()
                    val isFull = when {
                        dataFull.equals("true", ignoreCase = true) -> true
                        dataFull.equals("false", ignoreCase = true) -> false
                        node.hasClass("quote-modified") -> false
                        topicId != null && postNumber != null -> false
                        else -> true
                    }
                    result += PostBlockNode.QuoteBlock(
                        username = node.attr("data-username").ifBlank { null },
                        postNumber = postNumber,
                        topicId = topicId,
                        quotedText = body.wholeText().trim(),
                        children = blocks(body.childNodes(), nesting + 1),
                        title = titleText,
                        titleUrl = resolvedTitleUrl,
                        isFull = isFull
                    )
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
                    result += PostBlockNode.DetailsSpoilerBlock(
                        summary?.text().orEmpty().ifBlank { LinDoReaderCopy.AST_EXPAND_DETAILS.text },
                        bodyText,
                        children
                    )
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
                            label = LinDoReaderCopy.AST_MERGED_TABLE_TITLE.text,
                            text = LinDoReaderCopy.AST_MERGED_TABLE_HINT.text,
                            kind = UnsupportedBlockKind.MERGED_TABLE.wireKey
                        )
                    }
                }
                tag in mediaTagNames -> {
                    flush()
                    val mediaKind = MediaBlockKind.fromKey(tag)
                    val raw = node.attr("src").ifBlank { node.attr("data") }.ifBlank { node.selectFirst("source")?.attr("src").orEmpty() }
                    val resolved = safeUrl(raw, allowMailto = false)
                    result += PostBlockNode.Media(
                        friendlyMediaLabel(resolved, mediaKind, node.attr("title").trim()),
                        resolved,
                        mediaKind.wireKey
                    )
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
                            result += PostBlockNode.Unsupported(
                                LinDoReaderCopy.AST_SVG_TITLE.text,
                                svgText.ifBlank { LinDoReaderCopy.AST_SVG_HINT.text },
                                kind = UnsupportedBlockKind.SVG.wireKey
                            )
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
                    val emojiLabel = node.attr("title").ifBlank { node.attr("alt") }
                        .ifBlank { LinDoNetworkCopy.EMOJI_BRACKET_PLACEHOLDER.text }
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
                        val rawW = node.attr("width").trim().removeSuffix("px").toFloatOrNull()
                        val rawH = node.attr("height").trim().removeSuffix("px").toFloatOrNull()
                        val aspectRatio = if (rawW != null && rawH != null && rawW >= 48f && rawH >= 48f) {
                            (rawW / rawH).coerceIn(0.45f, 3.0f)
                        } else null
                        val prev = result.lastOrNull() as? PostBlockNode.ImageGallery
                        if (prev != null) {
                            val prevCaptions = List(prev.urls.size) { prev.captions.getOrNull(it).orEmpty() }
                            val prevFullUrls = List(prev.urls.size) { prev.fullUrls.getOrElse(it) { prev.urls[it] } }
                            val prevLinkUrls = List(prev.urls.size) { prev.linkUrls.getOrNull(it) }
                            val prevRatios = List(prev.urls.size) { prev.aspectRatios.getOrNull(it) }
                            result[result.lastIndex] = PostBlockNode.ImageGallery(
                                urls = prev.urls + displayUrl,
                                captions = prevCaptions + node.attr("alt"),
                                fullUrls = prevFullUrls + fullUrl,
                                linkUrls = prevLinkUrls + linkUrl,
                                aspectRatios = prevRatios + aspectRatio
                            )
                        } else {
                            result += PostBlockNode.ImageGallery(
                                urls = listOf(displayUrl),
                                captions = listOf(node.attr("alt")),
                                fullUrls = listOf(fullUrl),
                                linkUrls = listOf(linkUrl),
                                aspectRatios = listOf(aspectRatio)
                            )
                        }
                    } ?: run {
                        result += PostBlockNode.Unsupported(LinDoReaderCopy.AST_IMAGE_UNAVAILABLE.text, node.attr("alt"))
                    }
                }
                tag == "hr" -> { flush(); result += PostBlockNode.Divider }
                tag.matches(Regex("h[1-6]")) -> {
                    flush()
                    val level = tag[1].digitToInt()
                    val content = blocks(node.childNodes(), nesting + 1)
                    if (content.isNotEmpty()) {
                        for (child in content) {
                            result += if (child is PostBlockNode.Paragraph) {
                                PostBlockNode.Heading(level, child.rich.text, child.rich)
                            } else {
                                child
                            }
                        }
                    } else if (node.selectFirst(".spoiler, .spoilered, .spoiled") == null) {
                        val plain = node.text().trim()
                        if (plain.isNotEmpty()) {
                            result += PostBlockNode.Heading(level, plain, RichText.plain(plain))
                        }
                    }
                }
                tag == "br" -> pending.add(style.copy(text = "\n"))
                tag in setOf("p", "div", "section", "article", "figure", "figcaption", "dl", "dt", "dd", "li") -> {
                    flush()
                    val (fgColor, bgColor) = extractElementInlineColors(node)
                    val blockStyle = if (fgColor != null || bgColor != null) {
                        style.copy(colorHex = fgColor ?: style.colorHex, bgColorHex = bgColor ?: style.bgColorHex)
                    } else style
                    node.childNodes().forEach { visit(it, blockStyle, nesting + 1) }
                    flush()
                }
                else -> {
                    val (fgColor, bgColor) = extractElementInlineColors(node)
                    val styledBase = if (fgColor != null || bgColor != null) {
                        style.copy(colorHex = fgColor ?: style.colorHex, bgColorHex = bgColor ?: style.bgColorHex)
                    } else style
                    val next = when (tag) {
                        "b", "strong" -> styledBase.copy(bold = true)
                        "i", "em" -> styledBase.copy(italic = true)
                        "s", "del", "strike" -> styledBase.copy(strike = true)
                        "u" -> styledBase.copy(underline = true)
                        "code", "kbd" -> styledBase.copy(code = true)
                        "a" -> styledBase.copy(href = safeUrl(node.attr("href")))
                        else -> styledBase
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
