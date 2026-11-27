package do_.linux.lindo.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import do_.linux.lindo.core.copy.LinDoCommonCopy
import do_.linux.lindo.core.copy.LinDoReaderCopy
import do_.linux.lindo.core.copy.TopicNotificationLevel
import do_.linux.lindo.core.render.CodeSyntaxHighlighter
import do_.linux.lindo.core.render.DiscourseAstParser
import do_.linux.lindo.core.render.PostBlockNode
import do_.linux.lindo.ui.components.LinDoIcons
import kotlinx.coroutines.delay
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Platform bridge for rich media capabilities (Android MediaStore image save, MediaPlayer inline audio,
 * multi-image batch picker, and optional inline WebView academic preview).
 * Wired in `MainActivity.kt` on Android; mockable in `desktopTest`.
 */
object PlatformRichMediaBridge {
    @Volatile
    var saveImageToGallery: (suspend (url: String, fileName: String) -> Result<String>)? = null

    @Volatile
    var audioControllerFactory: (() -> PlatformAudioController)? = null

    @Volatile
    var pickMultipleMedia: ((mimeFilter: String, onPicked: (List<PickedMediaFile>) -> Unit) -> Unit)? = null

    @Volatile
    var inlineVideoPlayerContent: (@Composable (url: String, onOpenExternal: () -> Unit) -> Unit)? = null

    @Volatile
    var academicWebPreviewContent: (@Composable (title: String, html: String, onDismiss: () -> Unit) -> Unit)? = null

    fun resetForTest() {
        saveImageToGallery = null
        audioControllerFactory = null
        pickMultipleMedia = null
        inlineVideoPlayerContent = null
        academicWebPreviewContent = null
    }
}

data class PickedMediaFile(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray
)

data class AudioPlaybackState(
    val isPlaying: Boolean = false,
    val isPreparing: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val error: String? = null
)

interface PlatformAudioController {
    val state: State<AudioPlaybackState>
    fun togglePlayPause(url: String)
    fun seekTo(positionMs: Long)
    fun release()
}

// ---------- In-Topic Find Highlight ----------

internal val LocalInTopicFindQuery = compositionLocalOf { "" }

internal fun applyInTopicFindHighlight(
    source: AnnotatedString,
    query: String,
    highlightBg: Color,
    highlightFg: Color
): AnnotatedString {
    val q = query.trim()
    if (q.isEmpty() || source.text.isEmpty()) return source
    val text = source.text
    var searchFrom = 0
    var foundAny = false
    val ranges = mutableListOf<IntRange>()
    while (searchFrom < text.length) {
        val idx = text.indexOf(q, startIndex = searchFrom, ignoreCase = true)
        if (idx < 0) break
        foundAny = true
        ranges += idx until (idx + q.length)
        searchFrom = idx + q.length.coerceAtLeast(1)
    }
    if (!foundAny) return source
    return buildAnnotatedString {
        append(source)
        for (range in ranges) {
            addStyle(
                SpanStyle(
                    background = highlightBg,
                    color = highlightFg,
                    fontWeight = FontWeight.SemiBold
                ),
                start = range.first,
                end = range.last + 1
            )
        }
    }
}

// ---------- TOC Exact Heading Scroll ----------

@OptIn(ExperimentalFoundationApi::class)
internal class TocHeadingScrollController {
    private val requesters = mutableMapOf<Int, BringIntoViewRequester>()

    fun register(index: Int, requester: BringIntoViewRequester) {
        requesters[index] = requester
    }

    fun unregister(index: Int, requester: BringIntoViewRequester) {
        if (requesters[index] === requester) {
            requesters.remove(index)
        }
    }

    suspend fun scrollToHeading(index: Int): Boolean {
        val req = requesters[index] ?: return false
        return runCatching {
            req.bringIntoView()
            true
        }.getOrDefault(false)
    }
}

internal val LocalTocHeadingScrollController = compositionLocalOf<TocHeadingScrollController?> { null }

internal fun extractTopicTocHeadings(firstPostHtml: String): List<Pair<Int, String>> {
    if (firstPostHtml.isBlank()) return emptyList()
    val nodes = DiscourseAstParser.parseCookedHtml(firstPostHtml)
    return buildList {
        fun walk(list: List<PostBlockNode>) {
            for (n in list) {
                when (n) {
                    is PostBlockNode.Heading -> if (n.text.isNotBlank()) add(n.level to n.text.trim())
                    is PostBlockNode.CalloutBlock -> walk(n.children)
                    is PostBlockNode.DetailsSpoilerBlock -> walk(n.children)
                    else -> Unit
                }
            }
        }
        walk(nodes)
    }
}

// ---------- Colored <ins> / <del> Revision Diff ----------

internal fun buildRevisionDiffAnnotatedString(
    titleChangesHtml: String?,
    bodyChangesHtml: String?,
    isDark: Boolean,
    baseColor: Color
): AnnotatedString {
    val insFg = if (isDark) Color(0xFF34D399) else Color(0xFF047857)
    val insBg = insFg.copy(alpha = if (isDark) 0.22f else 0.14f)
    val delFg = if (isDark) Color(0xFFF87171) else Color(0xFFDC2626)
    val delBg = delFg.copy(alpha = if (isDark) 0.22f else 0.14f)

    fun AnnotatedString.Builder.appendDiffHtml(rawHtml: String) {
        val doc = Jsoup.parseBodyFragment(rawHtml)
        fun walk(node: Node, mode: Int) { // 0=normal, 1=ins, -1=del
            when (node) {
                is TextNode -> {
                    val t = node.text()
                    if (t.isNotEmpty()) {
                        when (mode) {
                            1 -> {
                                pushStyle(SpanStyle(color = insFg, background = insBg, fontWeight = FontWeight.Medium))
                                append(t)
                                pop()
                            }
                            -1 -> {
                                pushStyle(SpanStyle(color = delFg, background = delBg, textDecoration = TextDecoration.LineThrough))
                                append(t)
                                pop()
                            }
                            else -> {
                                pushStyle(SpanStyle(color = baseColor))
                                append(t)
                                pop()
                            }
                        }
                    }
                }
                is Element -> {
                    val tag = node.normalName()
                    val nextMode = when {
                        tag == "ins" || node.hasClass("diff-ins") -> 1
                        tag == "del" || node.hasClass("diff-del") -> -1
                        else -> mode
                    }
                    when (tag) {
                        "br" -> append("\n")
                        "p", "div", "li", "tr", "blockquote", "h1", "h2", "h3", "h4" -> {
                            if (length > 0 && !toAnnotatedString().text.endsWith("\n")) append("\n")
                            node.childNodes().forEach { walk(it, nextMode) }
                            if (!toAnnotatedString().text.endsWith("\n")) append("\n")
                        }
                        else -> node.childNodes().forEach { walk(it, nextMode) }
                    }
                }
            }
        }
        doc.body().childNodes().forEach { walk(it, 0) }
    }

    return buildAnnotatedString {
        var addedSection = false
        if (!titleChangesHtml.isNullOrBlank()) {
            pushStyle(SpanStyle(color = baseColor.copy(alpha = 0.7f), fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp))
            append("标题变更\n")
            pop()
            appendDiffHtml(titleChangesHtml)
            addedSection = true
        }
        if (!bodyChangesHtml.isNullOrBlank()) {
            if (addedSection) append("\n")
            pushStyle(SpanStyle(color = baseColor.copy(alpha = 0.7f), fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp))
            append("正文变更\n")
            pop()
            appendDiffHtml(bodyChangesHtml)
            addedSection = true
        }
        if (!addedSection) {
            pushStyle(SpanStyle(color = baseColor.copy(alpha = 0.7f)))
            append("无正文变更详情")
            pop()
        }
    }
}

// ---------- Footnote Parser & Bottom Sheet ----------

data class FootnoteDefinition(
    val id: String,
    val label: String,
    val text: String
)

internal fun extractFootnotesFromHtml(cookedHtml: String): Map<String, FootnoteDefinition> {
    if (!cookedHtml.contains("fn", ignoreCase = true) && !cookedHtml.contains("footnote", ignoreCase = true)) {
        return emptyMap()
    }
    val doc = runCatching { Jsoup.parseBodyFragment(cookedHtml) }.getOrNull() ?: return emptyMap()
    val map = LinkedHashMap<String, FootnoteDefinition>()
    val items = doc.select("li[id^=fn], ol.footnotes-list > li, section.footnotes li, div.footnotes li")
    items.forEachIndexed { idx, li ->
        val rawId = li.id().trim().ifBlank { "fn${idx + 1}" }
        val clone = li.clone()
        clone.select("a.footnote-backref, a[href^=#fnref]").remove()
        val body = clone.text().trim()
        if (body.isNotEmpty()) {
            val num = Regex("""(\d+)$""").find(rawId)?.groupValues?.getOrNull(1) ?: (idx + 1).toString()
            val def = FootnoteDefinition(id = rawId, label = "脚注 [$num]", text = body)
            map[rawId.lowercase()] = def
            map["fn$num"] = def
            map["fn-$num"] = def
            map["fn:$num"] = def
        }
    }
    return map
}

internal fun resolveFootnoteForAnchor(
    footnotes: Map<String, FootnoteDefinition>,
    rawHref: String
): FootnoteDefinition? {
    val clean = rawHref.trim().removePrefix("#").lowercase()
    if (clean.isEmpty()) return null
    footnotes[clean]?.let { return it }
    val num = Regex("""(\d+)$""").find(clean)?.groupValues?.getOrNull(1)
    if (num != null) {
        footnotes["fn$num"]?.let { return it }
        footnotes["fn-$num"]?.let { return it }
        footnotes["fn:$num"]?.let { return it }
    }
    return footnotes.entries.firstOrNull { (k, _) -> k.endsWith(clean) || clean.endsWith(k) }?.value
}

internal val LocalOnOpenFootnote = compositionLocalOf<((String) -> Unit)?> { null }

@Composable
internal fun FootnotePopoverDialog(
    footnote: FootnoteDefinition,
    onDismiss: () -> Unit
) {
    val palette = LocalPalette.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth(0.9f)
                .testTag("footnote_popover_dialog")
                .clip(RoundedCornerShape(16.dp))
                .background(palette.cardSurface)
                .border(0.6.dp, palette.hairline, RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = footnote.label,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.inkPrimary,
                    modifier = Modifier.weight(1f)
                )
                IconButton(modifier = Modifier.size(30.dp), onClick = onDismiss) {
                    Icon(LinDoIcons.Close, "关闭脚注", Modifier.size(16.dp), tint = palette.inkSecondary)
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
            SelectionContainer {
                Text(
                    text = footnote.text,
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    color = palette.inkBody
                )
            }
        }
    }
}

// ---------- Post-Wide Multi-Image Gallery Collector ----------

internal val LocalPostWideImageGallery = compositionLocalOf<PostBlockNode.ImageGallery?> { null }

internal fun collectAllImagesFromPostNodes(nodes: List<PostBlockNode>): PostBlockNode.ImageGallery? {
    val urls = mutableListOf<String>()
    val fullUrls = mutableListOf<String>()
    val captions = mutableListOf<String>()
    val linkUrls = mutableListOf<String?>()
    val aspectRatios = mutableListOf<Float?>()
    val widths = mutableListOf<Int?>()
    val heights = mutableListOf<Int?>()
    val seen = HashSet<String>()

    fun walk(list: List<PostBlockNode>) {
        for (n in list) {
            when (n) {
                is PostBlockNode.ImageGallery -> {
                    n.urls.forEachIndexed { idx, u ->
                        val f = n.fullUrls.getOrElse(idx) { u }
                        val key = f.ifBlank { u }
                        if (key.isNotBlank() && seen.add(key)) {
                            urls += u
                            fullUrls += f
                            captions += n.captions.getOrElse(idx) { "" }
                            linkUrls += n.linkUrls.getOrNull(idx)
                            aspectRatios += n.aspectRatios.getOrNull(idx)
                            widths += n.widths.getOrNull(idx)
                            heights += n.heights.getOrNull(idx)
                        }
                    }
                }
                is PostBlockNode.QuoteBlock -> walk(n.children)
                is PostBlockNode.CalloutBlock -> walk(n.children)
                is PostBlockNode.DetailsSpoilerBlock -> walk(n.children)
                is PostBlockNode.Spoiler -> walk(n.children)
                is PostBlockNode.BulletList -> n.itemBlocks.forEach { walk(it) }
                is PostBlockNode.Table -> n.rows.forEach { row -> row.forEach { cell -> walk(cell) } }
                else -> Unit
            }
        }
    }
    walk(nodes)
    return if (urls.size > 1) {
        PostBlockNode.ImageGallery(
            urls = urls,
            fullUrls = fullUrls,
            captions = captions,
            linkUrls = linkUrls,
            aspectRatios = aspectRatios,
            widths = widths,
            heights = heights
        )
    } else null
}

// ---------- Academic Math Unicode Formatter & Tap-to-Render Preview Sheet ----------

internal fun formatMathForNativeDisplay(rawLatex: String): String {
    var s = rawLatex.trim()
        .removePrefix("$$").removeSuffix("$$")
        .removePrefix("$").removeSuffix("$")
        .trim()
    // Simplify \frac{a}{b} -> (a) / (b)
    val fracRegex = Regex("""\\frac\{([^{}]+)\}\{([^{}]+)\}""")
    repeat(3) {
        s = fracRegex.replace(s) { m ->
            val num = m.groupValues[1].trim()
            val den = m.groupValues[2].trim()
            val left = if (num.contains(' ') || num.contains('+') || num.contains('-')) "($num)" else num
            val right = if (den.contains(' ') || den.contains('+') || den.contains('-')) "($den)" else den
            "$left / $right"
        }
    }
    val sqrtRegex = Regex("""\\sqrt\{([^{}]+)\}""")
    s = sqrtRegex.replace(s) { "√(${it.groupValues[1].trim()})" }
    val textRegex = Regex("""\\(?:text|mathrm|mathbf|operatorname)\{([^{}]+)\}""")
    s = textRegex.replace(s) { it.groupValues[1] }

    val replacements = listOf(
        "\\alpha" to "α", "\\beta" to "β", "\\gamma" to "γ", "\\delta" to "δ",
        "\\epsilon" to "ε", "\\varepsilon" to "ε", "\\zeta" to "ζ", "\\eta" to "η",
        "\\theta" to "θ", "\\lambda" to "λ", "\\mu" to "μ", "\\nu" to "ν",
        "\\xi" to "ξ", "\\pi" to "π", "\\rho" to "ρ", "\\sigma" to "σ",
        "\\tau" to "τ", "\\phi" to "φ", "\\varphi" to "φ", "\\chi" to "χ",
        "\\psi" to "ψ", "\\omega" to "ω",
        "\\Gamma" to "Γ", "\\Delta" to "Δ", "\\Theta" to "Θ", "\\Lambda" to "Λ",
        "\\Pi" to "Π", "\\Sigma" to "Σ", "\\Phi" to "Φ", "\\Psi" to "Ψ", "\\Omega" to "Ω",
        "\\infty" to "∞", "\\sum" to "∑", "\\prod" to "∏", "\\int" to "∫",
        "\\partial" to "∂", "\\nabla" to "∇", "\\pm" to "±", "\\mp" to "∓",
        "\\times" to "×", "\\cdot" to "·", "\\div" to "÷",
        "\\le" to "≤", "\\leq" to "≤", "\\ge" to "≥", "\\geq" to "≥",
        "\\ne" to "≠", "\\neq" to "≠", "\\approx" to "≈", "\\equiv" to "≡",
        "\\in" to "∈", "\\notin" to "∉", "\\subset" to "⊂", "\\subseteq" to "⊆",
        "\\cup" to "∪", "\\cap" to "∩", "\\forall" to "∀", "\\exists" to "∃",
        "\\Rightarrow" to "⇒", "\\Leftarrow" to "⇐", "\\Leftrightarrow" to "⇔",
        "\\rightarrow" to "→", "\\to" to "→", "\\leftarrow" to "←", "\\mapsto" to "↦",
        "\\ldots" to "…", "\\cdots" to "⋯", "\\dots" to "…",
        "\\left" to "", "\\right" to "", "\\," to " ", "\\;" to " ", "\\quad" to "  "
    )
    for ((k, v) in replacements) {
        s = s.replace(k, v)
    }
    val supMap = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
        '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
        '+' to '⁺', '-' to '⁻', 'n' to 'ⁿ', 'i' to 'ⁱ'
    )
    val subMap = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
        '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
        '+' to '₊', '-' to '₋', 'i' to 'ᵢ', 'j' to 'ⱼ', 'n' to 'ₙ', 'k' to 'ₖ'
    )
    s = Regex("""\^\{?([0-9+-ni]+)\}?""").replace(s) { m ->
        val inner = m.groupValues[1]
        if (inner.all { it in supMap }) inner.map { supMap[it]!! }.joinToString("") else "^$inner"
    }
    s = Regex("""_\{?([0-9+-ijnk]+)\}?""").replace(s) { m ->
        val inner = m.groupValues[1]
        if (inner.all { it in subMap }) inner.map { subMap[it]!! }.joinToString("") else "_$inner"
    }
    return s.replace(Regex("""\s{2,}"""), " ").trim()
}

@Composable
internal fun AcademicPreviewDialog(
    title: String,
    source: String,
    isMermaid: Boolean,
    onDismiss: () -> Unit
) {
    val palette = LocalPalette.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    var showRaw by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1600L)
            copied = false
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth(0.94f)
                .heightIn(max = 600.dp)
                .testTag("academic_preview_dialog")
                .clip(RoundedCornerShape(16.dp))
                .background(palette.cardSurface)
                .border(0.6.dp, palette.hairline, RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.inkPrimary,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { showRaw = !showRaw }) {
                    Text(if (showRaw) "渲染视图" else "源码", fontSize = 12.sp, color = palette.inkSecondary)
                }
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(source))
                    copied = true
                }) {
                    Text(if (copied) LinDoCommonCopy.COPIED.text else "复制", fontSize = 12.sp, color = palette.activeChipText)
                }
                IconButton(modifier = Modifier.size(30.dp), onClick = onDismiss) {
                    Icon(LinDoIcons.Close, "关闭", Modifier.size(16.dp), tint = palette.inkSecondary)
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (showRaw) {
                    SelectionContainer {
                        Text(
                            text = source,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            color = palette.inkPrimary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(palette.subtleSurface)
                                .padding(12.dp)
                        )
                    }
                } else if (isMermaid) {
                    val model = remember(source) { CodeSyntaxHighlighter.parseMermaidPreview(source) }
                    Text(
                        text = model.diagramType,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.activeChipText
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(palette.subtleSurface)
                            .border(0.6.dp, palette.hairline, RoundedCornerShape(12.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        model.lines.forEachIndexed { idx, step ->
                            val isEdge = step.contains("→") || step.contains("->") || step.contains("-->")
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isEdge) palette.cardSurface else palette.activeChipBg.copy(alpha = 0.55f))
                                    .border(0.5.dp, palette.hairline, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "${idx + 1}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = palette.activeChipText
                                )
                                Text(
                                    text = step,
                                    fontSize = 13.sp,
                                    lineHeight = 19.sp,
                                    color = palette.inkPrimary
                                )
                            }
                        }
                    }
                } else {
                    val formatted = remember(source) { formatMathForNativeDisplay(source) }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(palette.subtleSurface)
                            .border(0.6.dp, palette.hairline, RoundedCornerShape(12.dp))
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 18.dp, vertical = 20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        SelectionContainer {
                            Text(
                                text = formatted,
                                fontFamily = FontFamily.Serif,
                                fontStyle = FontStyle.Italic,
                                fontSize = 18.sp,
                                lineHeight = 28.sp,
                                color = palette.inkPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------- Inline Audio & Video Native Players ----------

internal fun formatMediaDurationMs(ms: Long): String {
    val totalSecs = (ms.coerceAtLeast(0L) / 1000L).toInt()
    val m = totalSecs / 60
    val s = totalSecs % 60
    return "%02d:%02d".format(m, s)
}

@Composable
internal fun InlineAudioPlayerBar(
    label: String,
    url: String?,
    fallbackUrl: String?,
    onOpenExternal: (String) -> Unit
) {
    val palette = LocalPalette.current
    val controller = remember(url) { PlatformRichMediaBridge.audioControllerFactory?.invoke() }
    DisposableEffect(controller) {
        onDispose { controller?.release() }
    }
    val playState = controller?.state?.value ?: AudioPlaybackState()
    val targetUrl = url ?: fallbackUrl

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("inline_audio_player_bar")
            .clip(RoundedCornerShape(12.dp))
            .background(palette.subtleSurface)
            .border(0.6.dp, palette.hairline, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(palette.brandGold)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(enabled = targetUrl != null) {
                        if (controller != null && url != null) {
                            controller.togglePlayPause(url)
                        } else if (targetUrl != null) {
                            onOpenExternal(targetUrl)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (playState.isPreparing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = palette.onAccent
                    )
                } else {
                    Text(
                        text = if (playState.isPlaying) "⏸" else "▶",
                        fontSize = 13.sp,
                        color = palette.onAccent,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = label.ifBlank { "音频附件" },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.inkPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (playState.durationMs > 0L) {
                        "${formatMediaDurationMs(playState.positionMs)} / ${formatMediaDurationMs(playState.durationMs)}"
                    } else if (playState.isPlaying) {
                        "正在播放…"
                    } else {
                        "点击播放音频"
                    },
                    fontSize = 11.2.sp,
                    color = palette.inkTertiary
                )
            }
            if (targetUrl != null) {
                IconButton(
                    modifier = Modifier.size(30.dp),
                    onClick = { onOpenExternal(targetUrl) }
                ) {
                    Icon(
                        imageVector = LinDoIcons.ExternalLink,
                        contentDescription = "外部打开音频",
                        modifier = Modifier.size(15.dp),
                        tint = palette.inkSecondary
                    )
                }
            }
        }
        if (controller != null && playState.durationMs > 0L) {
            Slider(
                value = (playState.positionMs.toFloat() / playState.durationMs.toFloat()).coerceIn(0f, 1f),
                onValueChange = { frac ->
                    controller.seekTo((frac * playState.durationMs).toLong())
                },
                modifier = Modifier.fillMaxWidth().height(26.dp),
                colors = SliderDefaults.colors(
                    thumbColor = palette.brandGold,
                    activeTrackColor = palette.brandGold,
                    inactiveTrackColor = palette.hairline
                )
            )
        }
        playState.error?.let { err ->
            Text(err, fontSize = 11.5.sp, color = Color(0xFFEF4444))
        }
    }
}

@Composable
internal fun InlineVideoPlayerCard(
    label: String,
    url: String?,
    fallbackUrl: String?,
    onOpenExternal: (String) -> Unit
) {
    val palette = LocalPalette.current
    var playingInline by remember(url) { mutableStateOf(false) }
    val targetUrl = url ?: fallbackUrl
    val inlineRenderer = PlatformRichMediaBridge.inlineVideoPlayerContent

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("inline_video_player_card")
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF111318))
            .border(0.6.dp, palette.hairline, RoundedCornerShape(12.dp))
    ) {
        if (playingInline && url != null && inlineRenderer != null) {
            Box(modifier = Modifier.fillMaxWidth().height(220.dp)) {
                inlineRenderer(url) { targetUrl?.let(onOpenExternal) }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(176.dp)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(enabled = targetUrl != null) {
                        if (url != null && inlineRenderer != null) {
                            playingInline = true
                        } else if (targetUrl != null) {
                            onOpenExternal(targetUrl)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.2f))
                            .border(1.dp, Color.White.copy(alpha = 0.45f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("▶", fontSize = 18.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        text = label.ifBlank { "视频内容" },
                        fontSize = 12.5.sp,
                        color = Color.White.copy(alpha = 0.9f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.subtleSurface)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (playingInline) "正在内嵌播放" else "视频预览",
                fontSize = 11.8.sp,
                color = palette.inkSecondary,
                modifier = Modifier.weight(1f)
            )
            if (targetUrl != null) {
                TextButton(
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    onClick = { onOpenExternal(targetUrl) }
                ) {
                    Text("全屏 / 外部播放", fontSize = 12.sp, color = palette.activeChipText)
                }
            }
        }
    }
}

// ---------- 4-Level Topic Notification Picker Sheet ----------

@Composable
internal fun TopicNotificationLevelDialog(
    current: TopicNotificationLevel,
    onSelect: (TopicNotificationLevel) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalPalette.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth(0.9f)
                .testTag("topic_notification_level_dialog")
                .clip(RoundedCornerShape(16.dp))
                .background(palette.cardSurface)
                .border(0.6.dp, palette.hairline, RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "话题通知级别",
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.inkPrimary,
                    modifier = Modifier.weight(1f)
                )
                IconButton(modifier = Modifier.size(30.dp), onClick = onDismiss) {
                    Icon(LinDoIcons.Close, "关闭", Modifier.size(16.dp), tint = palette.inkSecondary)
                }
            }
            TopicNotificationLevel.entries.forEach { level ->
                val selected = level == current
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("topic_notif_level_${level.name.lowercase()}")
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) palette.activeChipBg else palette.subtleSurface)
                        .border(
                            width = 0.6.dp,
                            color = if (selected) palette.activeChipText.copy(alpha = 0.4f) else palette.hairline,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable {
                            onSelect(level)
                            onDismiss()
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = if (level == TopicNotificationLevel.WATCHING) LinDoIcons.BellSelected else LinDoIcons.Bell,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (selected) palette.activeChipText else palette.inkSecondary
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = level.shortTitle,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (selected) palette.activeChipText else palette.inkPrimary
                        )
                        Text(
                            text = level.detailHint,
                            fontSize = 11.5.sp,
                            color = palette.inkSecondary
                        )
                    }
                    if (selected) {
                        Icon(
                            imageVector = LinDoIcons.Check,
                            contentDescription = "已选中",
                            modifier = Modifier.size(16.dp),
                            tint = palette.activeChipText
                        )
                    }
                }
            }
        }
    }
}
