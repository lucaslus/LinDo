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
 * - 按帖子 ID 与完整内容缓存，避免 32 位哈希碰撞造成旧正文串入。
 */
object DiscourseAstParser {
    private const val MAX_CACHE_ENTRIES = 256
    private const val MAX_CACHE_HTML_CHARS = 1_000_000
    private data class CacheKey(val postId: Int, val html: String)
    private val astCache = LinkedHashMap<CacheKey, List<PostBlockNode>>(MAX_CACHE_ENTRIES, 0.75f, true)
    private var cachedHtmlChars = 0

    fun signatureOf(postId: Int, cookedHtml: String): Long {
        return (postId.toLong() shl 32) xor (cookedHtml.hashCode().toLong() and 0xFFFFFFFFL)
    }

    fun getCached(postId: Int, cookedHtml: String): List<PostBlockNode>? = synchronized(astCache) {
        astCache[CacheKey(postId, cookedHtml)]
    }

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
                    // 检查段落内是否包含正文配图（过滤表情、头像、站点小图标）
                    val imgUrls = Regex("""<img[^>]+src=["']([^"']+)["'][^>]*>""")
                        .findAll(chunk)
                        .map { it.groupValues[1].trim() }
                        .filterNot {
                            it.contains("/images/emoji/") ||
                                it.contains("twemoji") ||
                                it.contains("user_avatar") ||
                                it.contains("favicon") ||
                                it.contains("/svg-sprite/")
                        }
                        .map { rawUrl ->
                            when {
                                rawUrl.startsWith("http://") || rawUrl.startsWith("https://") -> rawUrl
                                rawUrl.startsWith("//") -> "https:$rawUrl"
                                rawUrl.startsWith("/") -> "https://linux.do$rawUrl"
                                else -> "https://linux.do/$rawUrl"
                            }
                        }
                        .distinct()
                        .toList()
                    val inlineCodes = Regex("""(?is)<code[^>]*>(.*?)</code>""")
                        .findAll(chunk)
                        .map { stripHtmlTags(it.groupValues[1]) }
                        .toList()
                    // 剔除 Discourse Lightbox 图片尺寸元信息 (<div class="meta">...</div>)
                    val cleanedChunk = chunk.replace(Regex("""(?is)<div\s+class=["']meta["'][^>]*>.*?</div>"""), "")
                    val text = stripHtmlTags(cleanedChunk).trim()
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
