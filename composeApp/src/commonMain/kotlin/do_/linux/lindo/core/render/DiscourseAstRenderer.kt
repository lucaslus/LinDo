package do_.linux.lindo.core.render

/**
 * 帖子正文 AST 节点定义（后台协程一次性解析，UI 线程直接走 LazyColumn / Column 零阻塞渲染）
 */
sealed interface PostBlockNode {
    data class Heading(val level: Int, val text: String) : PostBlockNode
    data class Paragraph(val text: String, val inlineCodeSpans: List<String> = emptyList()) : PostBlockNode
    data class CodeBlock(val language: String, val code: String) : PostBlockNode
    data class QuoteBlock(
        val username: String?,
        val postNumber: Int?,
        val topicId: Int?,
        val quotedText: String
    ) : PostBlockNode
    data class DetailsSpoilerBlock(val title: String, val innerText: String) : PostBlockNode
    data class BulletList(val items: List<String>, val ordered: Boolean = false) : PostBlockNode
    data class ImageGallery(val urls: List<String>, val captions: List<String> = emptyList()) : PostBlockNode
    data object Divider : PostBlockNode
}

/**
 * 高性能 Discourse Cooked HTML -> AST 解析器与签名 LRU 缓存
 *
 * 解决痛点：
 * - 摒弃 WebView 逐楼层挂载和主线程同步 DOM 遍历；
 * - 按 `postId + cookedHtml.hashCode()` 缓存 AST 结果，列表上下反复滚动时 0ms 直接命中。
 */
object DiscourseAstParser {
    private const val MAX_CACHE_ENTRIES = 256
    private val astCache = LinkedHashMap<Long, List<PostBlockNode>>(MAX_CACHE_ENTRIES, 0.75f, true)

    fun signatureOf(postId: Int, cookedHtml: String): Long {
        return (postId.toLong() shl 32) xor (cookedHtml.hashCode().toLong() and 0xFFFFFFFFL)
    }

    @Synchronized
    fun parseOrGetCached(postId: Int, cookedHtml: String): List<PostBlockNode> {
        val key = signatureOf(postId, cookedHtml)
        astCache[key]?.let { return it }

        val parsed = parseCookedHtml(cookedHtml)
        if (astCache.size >= MAX_CACHE_ENTRIES) {
            val eldestKey = astCache.entries.firstOrNull()?.key
            if (eldestKey != null) astCache.remove(eldestKey)
        }
        astCache[key] = parsed
        return parsed
    }

    fun parseCookedHtml(cookedHtml: String): List<PostBlockNode> {
        if (cookedHtml.isBlank()) return emptyList()

        val nodes = mutableListOf<PostBlockNode>()
        var remaining = cookedHtml.trim()

        val blockPattern = Regex(
            pattern = """(?is)(<aside\s+class=["'][^"']*quote[^"']*["'][^>]*>.*?</aside>)|(<details[^>]*>.*?</details>)|(<pre[^>]*>\s*<code[^>]*>.*?</code>\s*</pre>)|(<h([1-6])[^>]*>.*?</h\5>)|(<ul[^>]*>.*?</ul>)|(<ol[^>]*>.*?</ol>)|(<p[^>]*>.*?</p>)|(<hr\s*/?>)""",
        )

        var lastIndex = 0
        for (match in blockPattern.findAll(remaining)) {
            if (match.range.first > lastIndex) {
                val gapText = stripHtmlTags(remaining.substring(lastIndex, match.range.first)).trim()
                if (gapText.isNotEmpty()) {
                    nodes += PostBlockNode.Paragraph(gapText)
                }
            }
            lastIndex = match.range.last + 1

            val chunk = match.value
            when {
                chunk.startsWith("<aside", ignoreCase = true) -> {
                    val user = Regex("""data-username=["']([^"']+)["']""").find(chunk)?.groupValues?.getOrNull(1)
                    val postNum = Regex("""data-post=["'](\d+)["']""").find(chunk)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    val topicId = Regex("""data-topic=["'](\d+)["']""").find(chunk)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    val bodyRaw = Regex("""(?is)<blockquote[^>]*>(.*?)</blockquote>""").find(chunk)?.groupValues?.getOrNull(1) ?: chunk
                    nodes += PostBlockNode.QuoteBlock(
                        username = user,
                        postNumber = postNum,
                        topicId = topicId,
                        quotedText = stripHtmlTags(bodyRaw).trim()
                    )
                }
                chunk.startsWith("<details", ignoreCase = true) -> {
                    val summary = Regex("""(?is)<summary[^>]*>(.*?)</summary>""").find(chunk)?.groupValues?.getOrNull(1)
                        ?.let { stripHtmlTags(it).trim() }
                        ?.ifEmpty { "点击展开折叠内容" }
                        ?: "点击展开折叠内容"
                    val body = chunk.replace(Regex("""(?is)<summary[^>]*>.*?</summary>"""), "")
                    nodes += PostBlockNode.DetailsSpoilerBlock(
                        title = summary,
                        innerText = stripHtmlTags(body).trim()
                    )
                }
                chunk.startsWith("<pre", ignoreCase = true) -> {
                    val lang = Regex("""class=["'][^"']*lang-([a-zA-Z0-9_+-]+)""").find(chunk)?.groupValues?.getOrNull(1) ?: "code"
                    val codeInner = Regex("""(?is)<code[^>]*>(.*?)</code>""").find(chunk)?.groupValues?.getOrNull(1) ?: chunk
                    nodes += PostBlockNode.CodeBlock(
                        language = lang,
                        code = decodeHtmlEntities(codeInner.replace(Regex("<[^>]+>"), "")).trimEnd()
                    )
                }
                chunk.startsWith("<h", ignoreCase = true) -> {
                    val level = chunk.getOrNull(2)?.digitToIntOrNull() ?: 2
                    nodes += PostBlockNode.Heading(level = level, text = stripHtmlTags(chunk).trim())
                }
                chunk.startsWith("<ul", ignoreCase = true) || chunk.startsWith("<ol", ignoreCase = true) -> {
                    val ordered = chunk.startsWith("<ol", ignoreCase = true)
                    val items = Regex("""(?is)<li[^>]*>(.*?)</li>""").findAll(chunk)
                        .map { stripHtmlTags(it.groupValues[1]).trim() }
                        .filter { it.isNotEmpty() }
                        .toList()
                    if (items.isNotEmpty()) {
                        nodes += PostBlockNode.BulletList(items = items, ordered = ordered)
                    }
                }
                chunk.startsWith("<hr", ignoreCase = true) -> {
                    nodes += PostBlockNode.Divider
                }
                else -> {
                    // 检查段落内是否包含图片
                    val imgUrls = Regex("""<img[^>]+src=["']([^"']+)["'][^>]*>""")
                        .findAll(chunk)
                        .map { it.groupValues[1] }
                        .filterNot { it.contains("/images/emoji/") || it.contains("twemoji") }
                        .toList()
                    val inlineCodes = Regex("""(?is)<code[^>]*>(.*?)</code>""")
                        .findAll(chunk)
                        .map { stripHtmlTags(it.groupValues[1]) }
                        .toList()
                    val text = stripHtmlTags(chunk).trim()
                    if (text.isNotEmpty()) {
                        nodes += PostBlockNode.Paragraph(text = text, inlineCodeSpans = inlineCodes)
                    }
                    if (imgUrls.isNotEmpty()) {
                        nodes += PostBlockNode.ImageGallery(urls = imgUrls)
                    }
                }
            }
        }

        if (lastIndex < remaining.length) {
            val tail = stripHtmlTags(remaining.substring(lastIndex)).trim()
            if (tail.isNotEmpty()) {
                nodes += PostBlockNode.Paragraph(tail)
            }
        }

        if (nodes.isEmpty()) {
            val plain = stripHtmlTags(cookedHtml).trim()
            if (plain.isNotEmpty()) {
                nodes += PostBlockNode.Paragraph(plain)
            }
        }
        return nodes
    }

    fun stripHtmlTags(html: String): String {
        val withLineBreaks = html
            .replace(Regex("""(?i)<br\s*/?>"""), "\n")
            .replace(Regex("""(?i)</p>"""), "\n")
        val noTags = withLineBreaks.replace(Regex("""<[^>]+>"""), "")
        return decodeHtmlEntities(noTags)
    }

    private fun decodeHtmlEntities(input: String): String {
        return input
            .replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
    }
}
