package do_.linux.lindo.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.unit.em
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import do_.linux.lindo.core.copy.CalloutBlockKind
import do_.linux.lindo.core.copy.GuestGateScenario
import do_.linux.lindo.core.copy.LinDoCommonCopy
import do_.linux.lindo.core.copy.LinDoReaderCopy
import do_.linux.lindo.core.copy.MediaBlockKind
import do_.linux.lindo.core.copy.UnsupportedBlockKind
import do_.linux.lindo.core.copy.isSiteVerificationError
import do_.linux.lindo.core.network.LinuxDoRepository
import do_.linux.lindo.core.network.UserApiKeyAuthManager
import coil3.compose.AsyncImage
import do_.linux.lindo.core.render.*
import do_.linux.lindo.core.site.LinuxDoEmojiCatalog
import do_.linux.lindo.core.site.LinuxDoSiteRules
import do_.linux.lindo.ui.components.LinDoIcons

@Composable
internal fun RichPostText(rich: RichText, palette: LinDoThemePalette,
    onOpenLink: (String) -> Unit, heading: Boolean = false, onSelectionChange: ((String?) -> Unit)? = null) {
    val inQuote = LocalInQuoteBlock.current
    val findQuery = LocalInTopicFindQuery.current
    val onOpenFootnote = LocalOnOpenFootnote.current
    val highlightBg = if (palette.isDark) Color(0x66FBBF24) else Color(0xAAFEF08A)
    val highlightFg = if (palette.isDark) Color(0xFFFFFFFF) else Color(0xFF1C1917)
    val baseTextColor = when {
        heading && inQuote -> palette.inkBody
        heading -> palette.inkPrimary
        inQuote -> palette.inkSecondary
        else -> palette.inkBody
    }
    val bodyLetterSpacing = if (heading) {
        if (palette.isDark) 0.08.sp else 0.sp
    } else {
        if (palette.isDark) 0.22.sp else 0.12.sp
    }
    val plain = rich.runs.singleOrNull()
    if (plain != null && plain.href == null && plain.imageUrl == null && !plain.bold &&
        !plain.italic && !plain.code && !plain.strike && !plain.underline &&
        plain.colorHex == null && plain.bgColorHex == null && findQuery.isBlank()
    ) {
        var plainLayoutResult by remember(plain.text) { mutableStateOf<TextLayoutResult?>(null) }
        SelectablePostText(plain.text, onSelectionChange, plainLayoutResult) {
            Text(
                plain.text,
                color = baseTextColor,
                fontSize = if (heading) 17.sp else 16.sp,
                lineHeight = 25.sp,
                letterSpacing = bodyLetterSpacing,
                fontWeight = if (heading) FontWeight.SemiBold else null,
                onTextLayout = { plainLayoutResult = it }
            )
        }
        return
    }
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    var unavailableMailLink by remember { mutableStateOf<String?>(null) }
    val annotated = remember(rich, palette, inQuote, onOpenLink, uriHandler, findQuery, onOpenFootnote) {
        val baseAnnotated = buildAnnotatedString {
            for ((index, run) in rich.runs.withIndex()) {
                val customFgColor = run.colorHex?.let { hex ->
                    LinuxDoSiteRules.parseHexColor(hex, fallback = Color.Unspecified)
                } ?: Color.Unspecified
                val resolvedFgColor = when {
                    customFgColor != Color.Unspecified -> customFgColor
                    run.href != null -> Color.Unspecified
                    run.bold && !heading -> if (inQuote) palette.inkBody else palette.inkPrimary
                    else -> Color.Unspecified
                }
                val bgColor = run.bgColorHex?.let { hex ->
                    LinuxDoSiteRules.parseHexColor(hex, fallback = Color.Unspecified)
                } ?: if (run.code) palette.subtleSurface else Color.Unspecified
                pushStyle(SpanStyle(
                    color = resolvedFgColor,
                    fontWeight = if (run.bold || heading) FontWeight.SemiBold else null,
                    fontStyle = if (run.italic) FontStyle.Italic else null,
                    fontFamily = if (run.code) FontFamily.Monospace else null,
                    letterSpacing = if (run.code) 0.sp else TextUnit.Unspecified,
                    background = bgColor,
                    textDecoration = when { run.strike -> TextDecoration.LineThrough; run.underline -> TextDecoration.Underline; else -> null }
                ))
                if (run.href != null) pushLink(LinkAnnotation.Clickable(run.href,
                    TextLinkStyles(SpanStyle(color = if (customFgColor != Color.Unspecified) customFgColor else palette.activeChipText, textDecoration = TextDecoration.Underline)),
                    linkInteractionListener = {
                        when {
                            run.href.startsWith("#") -> {
                                onOpenFootnote?.invoke(run.href)
                            }
                            run.href.startsWith("mailto:", ignoreCase = true) -> {
                                runCatching { uriHandler.openUri(run.href) }.onFailure { unavailableMailLink = run.href }
                            }
                            else -> onOpenLink(run.href)
                        }
                    }))
                if (run.imageUrl != null) appendInlineContent("emoji-$index", run.text) else append(run.text)
                if (run.href != null) pop()
                pop()
            }
        }
        applyInTopicFindHighlight(baseAnnotated, findQuery, highlightBg, highlightFg)
    }
    val failedEmoji = remember(rich) { mutableStateMapOf<String, Boolean>() }
    val failures = failedEmoji.toMap()
    val emojiCatalogVer = LinuxDoEmojiCatalog.catalogVersion
    val inlineImages = remember(rich, failures, emojiCatalogVer) {
        rich.runs.mapIndexedNotNull { index, run -> run.imageUrl?.let { url ->
            val failed = failures[url] == true
            val fallbackGlyph = shortcodeToUnicodeEmoji(run.text)
            val baseEm = if (run.largeEmoji) 1.65f else 1.25f
            val width = if (failed && fallbackGlyph == null) run.text.length.coerceIn(2, 10).toFloat() else baseEm
            "emoji-$index" to InlineTextContent(Placeholder(width.em, baseEm.em, PlaceholderVerticalAlign.TextCenter)) {
                if (failed) {
                    DisableSelection { Text(
                        text = fallbackGlyph ?: run.text,
                        fontSize = if (run.largeEmoji) 20.sp else 14.5.sp,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    ) }
                } else {
                    AsyncImage(
                        model = url,
                        contentDescription = LinDoReaderCopy.EMOJI_ALT.format(run.text),
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.High,
                        modifier = Modifier.fillMaxSize().padding(0.5.dp),
                        onError = { failedEmoji[url] = true }
                    )
                }
            }
        } }.toMap()
    }
    var richLayoutResult by remember(annotated.text) { mutableStateOf<TextLayoutResult?>(null) }
    SelectablePostText(annotated.text, onSelectionChange, richLayoutResult) {
        Text(
            annotated,
            inlineContent = inlineImages,
            color = baseTextColor,
            fontSize = if (heading) 17.sp else 16.sp,
            lineHeight = 25.sp,
            letterSpacing = bodyLetterSpacing,
            onTextLayout = { richLayoutResult = it }
        )
    }
    unavailableMailLink?.let { mail ->
        TextButton(onClick = { clipboard.setText(AnnotatedString(mail.removePrefix("mailto:"))) }) {
            Text(LinDoReaderCopy.CANNOT_OPEN_MAIL_APP.text, color = palette.activeChipText)
        }
    }
}

private val LocalActiveSelectionOwner = compositionLocalOf<MutableState<Any?>?> { null }
private val LocalInQuoteBlock = compositionLocalOf { false }
internal val LocalOnQuoteSelectedText = compositionLocalOf<((String) -> Unit)?> { null }
internal val LocalOnJumpToFloorNumber = compositionLocalOf<((Int) -> Unit)?> { null }
private val isAndroidSelectionRuntime: Boolean =
    System.getProperty("java.vm.name")?.contains("Dalvik", ignoreCase = true) == true

// Compose exposes selection offsets through its controlled container on the pinned runtime.
@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
@Composable
private fun SelectablePostText(
    text: String,
    onSelectionChange: ((String?) -> Unit)?,
    textLayoutResult: TextLayoutResult? = null,
    content: @Composable () -> Unit
) {
    val onQuoteAction = LocalOnQuoteSelectedText.current
    if (onSelectionChange == null && onQuoteAction == null) {
        SelectionContainer(content = content)
        return
    }
    val palette = LocalPalette.current
    val clipboard = LocalClipboardManager.current
    val density = LocalDensity.current
    val ownerToken = remember { Any() }
    val activeOwner = LocalActiveSelectionOwner.current
    var selection by remember(text) {
        mutableStateOf<androidx.compose.foundation.text.selection.Selection?>(null)
    }
    var isPointerPressed by remember(ownerToken) { mutableStateOf(false) }
    val latestCallback by rememberUpdatedState(onSelectionChange)
    val latestQuoteAction by rememberUpdatedState(onQuoteAction)
    LaunchedEffect(activeOwner?.value) {
        if (activeOwner != null && activeOwner.value != ownerToken && selection != null) {
            selection = null
        }
    }
    DisposableEffect(text, ownerToken) {
        onDispose {
            if (activeOwner == null) {
                if (selection != null) latestCallback?.invoke(null)
            } else if (activeOwner.value == ownerToken) {
                activeOwner.value = null
                latestCallback?.invoke(null)
            }
        }
    }

    val activeSelectedText = remember(selection, text) {
        val sel = selection ?: return@remember null
        val start = minOf(sel.start.offset, sel.end.offset).coerceIn(0, text.length)
        val end = maxOf(sel.start.offset, sel.end.offset).coerceIn(start, text.length)
        text.substring(start, end).takeIf { it.isNotBlank() }
    }

    val pillAnchorOffset = remember(selection, textLayoutResult, density, text) {
        val sel = selection
        val layout = textLayoutResult
        val pillHalfWidthPx = with(density) { 84.dp.toPx() }
        val pillHeightPx = with(density) { 40.dp.toPx() }
        val verticalGapPx = with(density) { 6.dp.toPx() }
        if (sel != null && layout != null && text.isNotEmpty()) {
            val maxIdx = (text.length - 1).coerceAtLeast(0)
            val minOff = minOf(sel.start.offset, sel.end.offset).coerceIn(0, maxIdx)
            val maxOff = (maxOf(sel.start.offset, sel.end.offset) - 1).coerceIn(minOff, maxIdx)
            val startBox = runCatching { layout.getBoundingBox(minOff) }.getOrNull()
            val endBox = runCatching { layout.getBoundingBox(maxOff) }.getOrNull()
            if (startBox != null && endBox != null) {
                val topPx = minOf(startBox.top, endBox.top)
                val bottomPx = maxOf(startBox.bottom, endBox.bottom)
                val centerX = ((startBox.left + endBox.right) * 0.5f).coerceAtLeast(0f)
                val yPx = if (isAndroidSelectionRuntime || topPx < pillHeightPx + 2f) {
                    (bottomPx + verticalGapPx).roundToInt()
                } else {
                    (topPx - pillHeightPx - verticalGapPx).roundToInt()
                }
                IntOffset((centerX - pillHalfWidthPx).coerceAtLeast(0f).roundToInt(), yPx)
            } else {
                IntOffset(0, (-pillHeightPx - verticalGapPx).roundToInt())
            }
        } else {
            IntOffset(0, (-pillHeightPx - verticalGapPx).roundToInt())
        }
    }

    Box(
        modifier = Modifier.pointerInput(ownerToken) {
            awaitPointerEventScope {
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    isPointerPressed = ev.changes.any { it.pressed }
                }
            }
        }
    ) {
        SelectionContainer(
            selection = selection,
            onSelectionChange = { next ->
                val hadSelection = selection != null
                selection = next
                if (next != null) {
                    val start = minOf(next.start.offset, next.end.offset).coerceIn(0, text.length)
                    val end = maxOf(next.start.offset, next.end.offset).coerceIn(start, text.length)
                    val selectedText = text.substring(start, end).takeIf { it.isNotBlank() }
                    if (selectedText != null) {
                        activeOwner?.value = ownerToken
                        latestCallback?.invoke(selectedText)
                    } else if (activeOwner == null || activeOwner.value == ownerToken) {
                        if (activeOwner?.value == ownerToken) activeOwner.value = null
                        latestCallback?.invoke(null)
                    }
                } else if (hadSelection) {
                    if (activeOwner == null || activeOwner.value == ownerToken) {
                        if (activeOwner?.value == ownerToken) activeOwner.value = null
                        latestCallback?.invoke(null)
                    }
                }
            },
            children = content
        )

        if (activeSelectedText != null && !isPointerPressed && latestQuoteAction != null) {
            Popup(
                alignment = Alignment.TopStart,
                offset = pillAnchorOffset,
                properties = PopupProperties(focusable = false)
            ) {
                val pillShape = RoundedCornerShape(20.dp)
                val pillBg = if (palette.isDark) Color(0xFF1E222B) else Color(0xFF1F2329)
                Row(
                    modifier = Modifier
                        .testTag("quote_and_reply_pill")
                        .shadow(elevation = 8.dp, shape = pillShape, clip = false)
                        .background(pillBg, pillShape)
                        .border(0.6.dp, Color.White.copy(alpha = 0.14f), pillShape)
                        .padding(horizontal = 4.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable {
                                val captured = activeSelectedText
                                selection = null
                                if (activeOwner?.value == ownerToken) activeOwner.value = null
                                latestCallback?.invoke(null)
                                latestQuoteAction?.invoke(captured)
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = LinDoIcons.Quote,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = palette.brandGold
                        )
                        Text(
                            text = LinDoReaderCopy.QUOTE_AND_REPLY_PILL.text,
                            fontSize = 12.5.sp,
                            lineHeight = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            maxLines = 1
                        )
                    }
                    Box(
                        modifier = Modifier
                            .width(0.6.dp)
                            .height(14.dp)
                            .background(Color.White.copy(alpha = 0.18f))
                    )
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable {
                                clipboard.setText(AnnotatedString(activeSelectedText))
                                selection = null
                                if (activeOwner?.value == ownerToken) activeOwner.value = null
                                latestCallback?.invoke(null)
                            }
                            .padding(horizontal = 9.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = LinDoIcons.FaClone,
                            contentDescription = null,
                            modifier = Modifier.size(11.5.dp),
                            tint = Color.White.copy(alpha = 0.85f)
                        )
                        Text(
                            text = LinDoCommonCopy.COPY.text,
                            fontSize = 12.2.sp,
                            lineHeight = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White.copy(alpha = 0.92f),
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

internal val LocalOnInspectUser = compositionLocalOf<((String) -> Unit)?> { null }

@Composable
internal fun PostRichContent(
    nodes: List<PostBlockNode>,
    palette: LinDoThemePalette,
    onOpenLink: (String) -> Unit,
    originalUrl: String? = null,
    postId: Int? = null,
    onSelectionChange: ((String?) -> Unit)? = null,
    onRequestLogin: (() -> Unit)? = null,
    onQuoteSelection: ((String) -> Unit)? = null,
    onJumpToFloorNumber: ((Int) -> Unit)? = null,
    registerTocHeadings: Boolean = false
) {
    val existingSelectionOwner = LocalActiveSelectionOwner.current
    val effectiveQuoteAction = onQuoteSelection ?: LocalOnQuoteSelectedText.current
    val effectiveJumpAction = onJumpToFloorNumber ?: LocalOnJumpToFloorNumber.current
    val existingWideGallery = LocalPostWideImageGallery.current
    val rootWideGallery = remember(nodes, existingWideGallery) {
        existingWideGallery ?: collectAllImagesFromPostNodes(nodes)
    }
    if (existingSelectionOwner == null) {
        val rootSelectionOwner = remember { mutableStateOf<Any?>(null) }
        val headingCounter = remember(nodes, registerTocHeadings) { intArrayOf(0) }
        headingCounter[0] = 0
        CompositionLocalProvider(
            LocalActiveSelectionOwner provides rootSelectionOwner,
            LocalOnQuoteSelectedText provides effectiveQuoteAction,
            LocalOnJumpToFloorNumber provides effectiveJumpAction,
            LocalPostWideImageGallery provides rootWideGallery
        ) {
            PostRichContentBody(
                nodes = nodes,
                palette = palette,
                onOpenLink = onOpenLink,
                originalUrl = originalUrl,
                postId = postId,
                onSelectionChange = onSelectionChange,
                onRequestLogin = onRequestLogin,
                tocHeadingCounter = if (registerTocHeadings) headingCounter else null
            )
        }
    } else {
        CompositionLocalProvider(
            LocalOnQuoteSelectedText provides effectiveQuoteAction,
            LocalOnJumpToFloorNumber provides effectiveJumpAction,
            LocalPostWideImageGallery provides rootWideGallery
        ) {
            PostRichContentBody(
                nodes = nodes,
                palette = palette,
                onOpenLink = onOpenLink,
                originalUrl = originalUrl,
                postId = postId,
                onSelectionChange = onSelectionChange,
                onRequestLogin = onRequestLogin,
                tocHeadingCounter = null
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PostRichContentBody(
    nodes: List<PostBlockNode>,
    palette: LinDoThemePalette,
    onOpenLink: (String) -> Unit,
    originalUrl: String? = null,
    postId: Int? = null,
    onSelectionChange: ((String?) -> Unit)? = null,
    onRequestLogin: (() -> Unit)? = null,
    tocHeadingCounter: IntArray? = null
) {
    val clipboard = LocalClipboardManager.current
    val inQuote = LocalInQuoteBlock.current
    val tocController = LocalTocHeadingScrollController.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        for (node in nodes) when (node) {
            is PostBlockNode.Paragraph -> RichPostText(node.rich, palette, onOpenLink, onSelectionChange = onSelectionChange)
            is PostBlockNode.Heading -> {
                if (tocHeadingCounter != null && tocController != null && node.text.isNotBlank()) {
                    val headingIdx = tocHeadingCounter[0]++
                    val bringIntoViewRequester = remember(postId, headingIdx) { BringIntoViewRequester() }
                    DisposableEffect(tocController, headingIdx, bringIntoViewRequester) {
                        tocController.register(headingIdx, bringIntoViewRequester)
                        onDispose { tocController.unregister(headingIdx, bringIntoViewRequester) }
                    }
                    Box(modifier = Modifier.fillMaxWidth().bringIntoViewRequester(bringIntoViewRequester)) {
                        RichPostText(node.rich, palette, onOpenLink, heading = true, onSelectionChange = onSelectionChange)
                    }
                } else {
                    RichPostText(node.rich, palette, onOpenLink, heading = true, onSelectionChange = onSelectionChange)
                }
            }
            is PostBlockNode.Spoiler -> {
                var revealed by remember(node) { mutableStateOf(false) }
                Column {
                    TextButton(onClick = { revealed = !revealed }) {
                        Text(
                            if (revealed) LinDoReaderCopy.COLLAPSE_SPOILER.text else LinDoReaderCopy.REVEAL_SPOILER.text,
                            color = palette.inkSecondary
                        )
                    }
                    if (revealed) PostRichContent(node.children, palette, onOpenLink, originalUrl, postId, onSelectionChange, onRequestLogin)
                }
            }
            is PostBlockNode.Poll -> NativePostPoll(node, palette, postId, originalUrl, onOpenLink, onRequestLogin)

            is PostBlockNode.QuoteBlock -> {
                val onJumpFloor = LocalOnJumpToFloorNumber.current
                val quoteScope = rememberCoroutineScope()
                val currentTopicId = remember(originalUrl) { originalUrl?.let(::parseSmartTopicUrlTarget)?.first }
                val accentColor = lerp(palette.hairline, palette.brandGold, 0.65f)
                val quoteFloorNum = node.postNumber?.takeIf { it > 0 }
                val isCrossTopic = node.topicId != null && (currentTopicId == null || node.topicId != currentTopicId)
                val targetLinkUrl = node.titleUrl
                    ?: if (node.topicId != null) "https://linux.do/t/-/${node.topicId}/${quoteFloorNum ?: 1}" else null
                val canJump = targetLinkUrl != null || (quoteFloorNum != null && onJumpFloor != null)
                val canInlineExpand = !node.isFull && node.topicId != null && quoteFloorNum != null
                var expandedFullNodes by remember(node) { mutableStateOf<List<PostBlockNode>?>(null) }
                var expandingFull by remember(node) { mutableStateOf(false) }
                val headerLabel = node.title?.takeIf { it.isNotBlank() }
                    ?: node.username?.takeIf { it.isNotBlank() }
                    ?: if (quoteFloorNum != null) "引用" else null
                val quoteShape = RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(quoteShape)
                        .background(palette.subtleSurface.copy(alpha = 0.64f))
                        .drawBehind {
                            val barWidth = 2.5.dp.toPx()
                            drawLine(
                                color = accentColor,
                                start = Offset(barWidth / 2f, 0f),
                                end = Offset(barWidth / 2f, size.height),
                                strokeWidth = barWidth
                            )
                        }
                        .padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (headerLabel != null || quoteFloorNum != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val titleIsClickableTopic = isCrossTopic && targetLinkUrl != null
                            Text(
                                text = headerLabel ?: "引用",
                                modifier = Modifier
                                    .weight(1f)
                                    .then(
                                        if (titleIsClickableTopic) {
                                            Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .pointerHoverIcon(PointerIcon.Hand)
                                                .clickable { onOpenLink(targetLinkUrl!!) }
                                        } else Modifier
                                    ),
                                color = if (titleIsClickableTopic) palette.activeChipText else palette.inkPrimary,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (canInlineExpand) {
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .pointerHoverIcon(PointerIcon.Hand)
                                        .clickable(enabled = !expandingFull) {
                                            if (expandedFullNodes != null) {
                                                expandedFullNodes = null
                                            } else {
                                                expandingFull = true
                                                quoteScope.launch {
                                                    val fullPost = runCatching {
                                                        LinuxDoRepository.fetchPostByNumber(node.topicId!!, quoteFloorNum!!)
                                                    }.getOrNull()
                                                    if (fullPost != null && fullPost.cookedHtml.isNotBlank()) {
                                                        expandedFullNodes = DiscourseAstParser.parseOrGetCached(fullPost.id, fullPost.cookedHtml)
                                                    }
                                                    expandingFull = false
                                                }
                                            }
                                        }
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (expandedFullNodes != null) LinDoIcons.ChevronUp else LinDoIcons.ChevronDown,
                                        contentDescription = if (expandedFullNodes != null) "收起引用" else "展开完整引用",
                                        tint = palette.inkTertiary,
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                            }
                            if (quoteFloorNum != null && canJump) {
                                Text(
                                    text = "#$quoteFloorNum 楼 ›",
                                    color = palette.activeChipText,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .pointerHoverIcon(PointerIcon.Hand)
                                        .clickable {
                                            if (isCrossTopic && targetLinkUrl != null) {
                                                onOpenLink(targetLinkUrl)
                                            } else if (onJumpFloor != null) {
                                                onJumpFloor(quoteFloorNum)
                                            } else if (targetLinkUrl != null) {
                                                onOpenLink(targetLinkUrl)
                                            }
                                        }
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                    CompositionLocalProvider(LocalInQuoteBlock provides true) {
                        PostRichContent(
                            expandedFullNodes ?: node.children.ifEmpty { listOf(PostBlockNode.Paragraph(node.quotedText)) },
                            palette,
                            onOpenLink,
                            originalUrl,
                            postId,
                            onSelectionChange,
                            onRequestLogin
                        )
                    }
                }
            }
            is PostBlockNode.CodeBlock -> {
                val isMermaid = remember(node.language) { CodeSyntaxHighlighter.isMermaidLanguage(node.language) }
                var showMermaidSource by remember(node.code) { mutableStateOf(false) }
                var copied by remember(node.code) { mutableStateOf(false) }
                LaunchedEffect(copied) {
                    if (copied) {
                        delay(1800)
                        copied = false
                    }
                }
                if (isMermaid && !showMermaidSource) {
                    val mermaidModel = remember(node.code) { CodeSyntaxHighlighter.parseMermaidPreview(node.code) }
                    var showMermaidPreviewSheet by remember(node.code) { mutableStateOf(false) }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("mermaid_card_block")
                            .clip(RoundedCornerShape(10.dp))
                            .background(palette.subtleSurface)
                            .border(0.5.dp, palette.hairline, RoundedCornerShape(10.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = mermaidModel.diagramType,
                                modifier = Modifier.weight(1f),
                                color = palette.activeChipText,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            TextButton(
                                onClick = { showMermaidPreviewSheet = true },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.testTag("mermaid_render_preview_btn")
                            ) {
                                Text("渲染预览", color = palette.brandGold, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            }
                            TextButton(
                                onClick = { showMermaidSource = true },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("源码", color = palette.inkSecondary, fontSize = 12.sp)
                            }
                            TextButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString(node.code))
                                    copied = true
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(if (copied) LinDoCommonCopy.COPIED.text else LinDoReaderCopy.COPY_CODE.text, color = palette.activeChipText, fontSize = 12.sp)
                            }
                        }
                        mermaidModel.lines.forEach { step ->
                            Text(
                                text = step,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.5.sp,
                                lineHeight = 18.sp,
                                color = palette.inkPrimary
                            )
                        }
                    }
                    if (showMermaidPreviewSheet) {
                        AcademicPreviewDialog(
                            title = "Mermaid 图表预览",
                            source = node.code,
                            isMermaid = true,
                            onDismiss = { showMermaidPreviewSheet = false }
                        )
                    }
                } else {
                    val highlightedCode = remember(node.code, node.language, palette.isDark) {
                        CodeSyntaxHighlighter.highlight(node.code, node.language, palette.isDark)
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(palette.subtleSurface)
                            .border(0.5.dp, palette.hairline, RoundedCornerShape(8.dp))
                    ) {
                        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(node.language.ifBlank { "text" }, Modifier.weight(1f), color = palette.inkSecondary, fontSize = 12.sp)
                            if (isMermaid) {
                                TextButton(onClick = { showMermaidSource = false }) {
                                    Text("图表视图", color = palette.inkSecondary, fontSize = 12.sp)
                                }
                            }
                            TextButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString(node.code))
                                    copied = true
                                },
                                modifier = Modifier.heightIn(min = 42.dp)
                            ) {
                                Text(if (copied) LinDoCommonCopy.COPIED.text else LinDoReaderCopy.COPY_CODE.text, color = palette.activeChipText)
                            }
                        }
                        var codeLayoutResult by remember(node.code) { mutableStateOf<TextLayoutResult?>(null) }
                        SelectablePostText(node.code, onSelectionChange, codeLayoutResult) {
                            Text(
                                text = highlightedCode,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                lineHeight = 20.sp,
                                color = palette.inkPrimary,
                                onTextLayout = { codeLayoutResult = it },
                                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                            )
                        }
                    }
                }
            }
            is PostBlockNode.BulletList -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                node.items.forEachIndexed { index, text ->
                    Row {
                        Text(
                            if (node.ordered) "${node.start + index}. " else "• ",
                            color = if (inQuote) palette.inkSecondary else palette.inkBody,
                            modifier = Modifier.widthIn(min = 24.dp)
                        )
                        Box(Modifier.weight(1f)) { PostRichContent(node.itemBlocks.getOrNull(index) ?: listOf(PostBlockNode.Paragraph(text)), palette, onOpenLink, originalUrl, postId, onSelectionChange, onRequestLogin) }
                    }
                }
            }
            is PostBlockNode.DetailsSpoilerBlock -> {
                var expanded by remember(node) { mutableStateOf(false) }
                Column(Modifier.fillMaxWidth().background(palette.subtleSurface, RoundedCornerShape(8.dp)).padding(8.dp)) {
                    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(
                            imageVector = if (expanded) LinDoIcons.ChevronDown else LinDoIcons.ChevronRight,
                            contentDescription = null,
                            tint = palette.inkSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(node.title, color = palette.inkPrimary)
                    }
                    if (expanded) {
                        val fallbackNodes = if (node.innerText.isNotBlank()) listOf(PostBlockNode.Paragraph(node.innerText)) else emptyList()
                        val bodyChildren = node.children.ifEmpty { fallbackNodes }
                        if (bodyChildren.isNotEmpty()) {
                            PostRichContent(bodyChildren, palette, onOpenLink, originalUrl, postId, onSelectionChange, onRequestLogin)
                        }
                    }
                }
            }
            is PostBlockNode.ImageGallery -> {
                val postWideGallery = LocalPostWideImageGallery.current
                var lightboxIndex by remember(node) { mutableStateOf<Int?>(null) }
                node.urls.forEachIndexed { index, url ->
                    var loadState by remember(url) { mutableIntStateOf(0) } // 0=loading, 1=loaded, -1=failed
                    val fullUrl = node.fullUrls.getOrElse(index) { url }
                    val rawLink = node.linkUrls.getOrNull(index)?.trim().orEmpty()
                    val linkTarget = rawLink.ifEmpty { fullUrl }
                    val isExternalArticleLink = rawLink.isNotEmpty() &&
                        !rawLink.equals(fullUrl, ignoreCase = true) &&
                        !rawLink.equals(url, ignoreCase = true) &&
                        !isPostImageAssetUrl(rawLink)
                    val alt = node.captions.getOrNull(index).orEmpty().ifBlank { LinDoReaderCopy.POST_IMAGE_ALT.text }
                    val knownAspectRatio = node.aspectRatios.getOrNull(index)?.coerceIn(0.45f, 3.0f)
                    if (loadState < 0) {
                        TextButton(onClick = {
                            if (isExternalArticleLink) onOpenLink(linkTarget) else lightboxIndex = index
                        }) { Text("查看图片", color = palette.activeChipText) }
                    } else {
                        val context = coil3.compose.LocalPlatformContext.current
                        val imageDensity = androidx.compose.ui.platform.LocalDensity.current.density
                        val imageRequest = remember(context, url, imageDensity) {
                            coil3.request.ImageRequest.Builder(context)
                                .data(url)
                                .size(coil3.size.Size((560 * imageDensity).toInt(), (360 * imageDensity).toInt()))
                                .precision(coil3.size.Precision.INEXACT)
                                .build()
                        }
                        val isLoading = loadState == 0
                        val animationsEnabled = LocalLinDoAnimations.current
                        val skeletonAlpha = if (isLoading && animationsEnabled) {
                            val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "img_skel_$index")
                            val a by pulse.animateFloat(
                                initialValue = 0.55f,
                                targetValue = 0.92f,
                                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                                    animation = androidx.compose.animation.core.tween(
                                        durationMillis = 950,
                                        easing = androidx.compose.animation.core.FastOutSlowInEasing
                                    ),
                                    repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
                                ),
                                label = "img_skel_alpha_$index"
                            )
                            a
                        } else 0.78f
                        val boxMod = if (isLoading) {
                            val base = Modifier
                                .widthIn(max = 560.dp)
                                .fillMaxWidth()
                                .let { m ->
                                    if (knownAspectRatio != null) {
                                        m.aspectRatio(knownAspectRatio).heightIn(min = 120.dp, max = 360.dp)
                                    } else {
                                        m.height(200.dp)
                                    }
                                }
                            base
                                .clip(RoundedCornerShape(10.dp))
                                .background(palette.subtleSurface.copy(alpha = skeletonAlpha))
                                .border(0.5.dp, palette.hairline, RoundedCornerShape(10.dp))
                                .testTag("post_image_skeleton_$index")
                        } else {
                            Modifier
                                .widthIn(max = 560.dp)
                                .heightIn(max = 360.dp)
                                .clip(RoundedCornerShape(8.dp))
                        }
                        Box(
                            modifier = boxMod.clickable {
                                if (isExternalArticleLink) {
                                    onOpenLink(linkTarget)
                                } else {
                                    lightboxIndex = index
                                }
                            },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isLoading) {
                                Icon(
                                    imageVector = do_.linux.lindo.ui.components.LinDoIcons.Image,
                                    contentDescription = null,
                                    modifier = Modifier.size(26.dp),
                                    tint = palette.inkTertiary.copy(alpha = 0.45f)
                                )
                            }
                            AsyncImage(
                                model = imageRequest,
                                contentDescription = alt,
                                contentScale = ContentScale.Inside,
                                filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium,
                                onSuccess = { loadState = 1 },
                                onError = { loadState = -1 },
                                modifier = if (isLoading) {
                                    Modifier.matchParentSize()
                                } else {
                                    Modifier.widthIn(max = 560.dp).heightIn(max = 360.dp)
                                }
                            )
                        }
                    }
                }
                lightboxIndex?.let { startIndex ->
                    val effectiveGallery = postWideGallery ?: node
                    val targetFullUrl = node.fullUrls.getOrElse(startIndex) { node.urls.getOrElse(startIndex) { "" } }
                    val resolvedStartIndex = if (postWideGallery != null) {
                        postWideGallery.fullUrls.indexOfFirst { it == targetFullUrl }
                            .takeIf { it >= 0 }
                            ?: postWideGallery.urls.indexOfFirst { it == node.urls.getOrNull(startIndex) }
                                .takeIf { it >= 0 }
                            ?: startIndex
                    } else {
                        startIndex
                    }
                    PostImageLightboxDialog(
                        gallery = effectiveGallery,
                        initialIndex = resolvedStartIndex,
                        onOpenLink = onOpenLink,
                        onDismiss = { lightboxIndex = null }
                    )
                }
            }
            is PostBlockNode.Table -> Column(Modifier.horizontalScroll(rememberScrollState())) {
                node.rows.forEachIndexed { index, cells -> Row(Modifier.height(IntrinsicSize.Min)) {
                    cells.forEach { cell -> Column(Modifier.width(160.dp).fillMaxHeight().border(.5.dp, palette.hairline)
                        .background(if (index in node.headerRows) palette.subtleSurface else palette.cardSurface).padding(10.dp)) {
                        PostRichContent(cell, palette, onOpenLink, originalUrl, postId, onSelectionChange, onRequestLogin)
                    } }
                } }
            }
            is PostBlockNode.Media -> {
                val mediaKind = MediaBlockKind.fromKey(node.kind)
                if (mediaKind.isAudio) {
                    InlineAudioPlayerBar(
                        label = node.label,
                        url = node.url,
                        fallbackUrl = originalUrl,
                        onOpenExternal = onOpenLink
                    )
                } else {
                    InlineVideoPlayerCard(
                        label = node.label,
                        url = node.url,
                        fallbackUrl = originalUrl,
                        onOpenExternal = onOpenLink
                    )
                }
            }
            is PostBlockNode.OneboxCard -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.subtleSurface)
                    .border(0.5.dp, palette.hairline, RoundedCornerShape(12.dp))
                    .clickable { onOpenLink(node.url) }
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (node.domain.isNotBlank()) {
                    Text(
                        text = node.domain,
                        color = palette.brandGold,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
                Text(
                    text = node.title,
                    color = palette.inkPrimary,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 20.sp
                )
                if (node.description.isNotBlank()) {
                    Text(
                        text = node.description,
                        color = palette.inkSecondary,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        maxLines = 3,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
            is PostBlockNode.CalloutBlock -> {
                val accentColor = when (CalloutBlockKind.fromKey(node.kind)) {
                    CalloutBlockKind.LOTTERY -> palette.brandGold
                    CalloutBlockKind.WARNING -> Color(0xFFD97706)
                    CalloutBlockKind.CAUTION -> Color(0xFFEF4444)
                    CalloutBlockKind.IMPORTANT -> Color(0xFF8B5CF6)
                    CalloutBlockKind.TIP -> Color(0xFF10B981)
                    CalloutBlockKind.QUOTE -> palette.inkSecondary
                    CalloutBlockKind.NOTE -> Color(0xFF3B82F6)
                }
                var expanded by remember(node) { mutableStateOf(!node.collapsible || node.defaultExpanded) }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(accentColor.copy(alpha = if (palette.isDark) 0.08f else 0.06f))
                        .border(0.5.dp, accentColor.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                        .drawBehind {
                            val barW = 3.dp.toPx()
                            drawLine(
                                color = accentColor,
                                start = Offset(barW / 2f, 0f),
                                end = Offset(barW / 2f, size.height),
                                strokeWidth = barW
                            )
                        }
                        .padding(start = 13.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (node.collapsible) {
                                    Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .pointerHoverIcon(PointerIcon.Hand)
                                        .clickable { expanded = !expanded }
                                } else Modifier
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = node.title,
                            modifier = Modifier.weight(1f),
                            color = accentColor,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (node.collapsible) {
                            Icon(
                                imageVector = if (expanded) LinDoIcons.ChevronDown else LinDoIcons.ChevronRight,
                                contentDescription = if (expanded) "收起提示框" else "展开提示框",
                                tint = accentColor.copy(alpha = 0.85f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    if (expanded && node.children.isNotEmpty()) {
                        PostRichContent(node.children, palette, onOpenLink, originalUrl, postId, onSelectionChange, onRequestLogin)
                    }
                }
            }
            is PostBlockNode.Unsupported -> {
                when (UnsupportedBlockKind.fromKey(node.kind)) {
                    UnsupportedBlockKind.MERGED_TABLE -> {
                        // 表格单元格内容已在上方 Table 块完整渲染，无需再显示冗余占位框
                    }
                    UnsupportedBlockKind.MATH -> {
                        if (node.text.isNotBlank()) {
                            var showMathSheet by remember(node.text) { mutableStateOf(false) }
                            val formattedMath = remember(node.text) { formatMathForNativeDisplay(node.text) }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("math_card_block")
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(palette.subtleSurface)
                                    .border(0.5.dp, palette.hairline, RoundedCornerShape(10.dp))
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "数学公式",
                                        fontSize = 11.2.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = palette.inkTertiary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = "渲染预览",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = palette.activeChipText,
                                        modifier = Modifier
                                            .testTag("math_render_preview_btn")
                                            .clip(RoundedCornerShape(6.dp))
                                            .pointerHoverIcon(PointerIcon.Hand)
                                            .clickable { showMathSheet = true }
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                                SelectionContainer {
                                    Text(
                                        text = formattedMath,
                                        color = palette.inkPrimary,
                                        fontFamily = FontFamily.Serif,
                                        fontStyle = FontStyle.Italic,
                                        fontSize = 15.5.sp,
                                        lineHeight = 23.sp,
                                        modifier = Modifier.horizontalScroll(rememberScrollState())
                                    )
                                }
                            }
                            if (showMathSheet) {
                                AcademicPreviewDialog(
                                    title = "数学公式预览",
                                    source = node.text,
                                    isMermaid = false,
                                    onDismiss = { showMathSheet = false }
                                )
                            }
                        }
                    }
                    UnsupportedBlockKind.POLL -> Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(node.label, color = palette.inkPrimary, fontWeight = FontWeight.SemiBold)
                        node.options.forEach { Text("• $it", color = palette.inkSecondary, fontSize = 14.sp) }
                        if (originalUrl != null) TextButton(onClick = { onOpenLink(originalUrl + if (originalUrl.contains('?')) "&web=1" else "?web=1") }) {
                            Text(LinDoReaderCopy.GO_ORIGINAL_POST_TO_VOTE.text, color = palette.brandGold)
                        } else Text(LinDoReaderCopy.POLL_AVAILABLE_AFTER_PUBLISH.text, color = palette.inkSecondary, fontSize = 12.sp)
                    }
                    UnsupportedBlockKind.SVG -> Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(node.text, color = palette.inkSecondary, fontSize = 13.sp)
                        if (originalUrl != null) TextButton(onClick = { onOpenLink(originalUrl + if (originalUrl.contains('?')) "&web=1" else "?web=1") }) {
                            Text(LinDoReaderCopy.VIEW_GRAPHIC_IN_ORIGINAL_POST.text, color = palette.brandGold)
                        }
                    }
                    UnsupportedBlockKind.GENERIC -> {
                        if (node.text.isNotBlank()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(palette.subtleSurface, RoundedCornerShape(10.dp))
                                    .border(0.5.dp, palette.hairline, RoundedCornerShape(10.dp))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (node.label.isNotBlank() && node.label != LinDoReaderCopy.INTERACTIVE_CONTENT_SENTINEL.text) {
                                    Text(node.label, color = palette.inkPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                                }
                                SelectionContainer {
                                    Text(
                                        node.text,
                                        color = if (inQuote) palette.inkSecondary else palette.inkBody,
                                        fontSize = 14.sp,
                                        lineHeight = 20.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
            PostBlockNode.Divider -> HorizontalDivider(color = palette.hairline)
        }
    }
}

private fun shortcodeToUnicodeEmoji(raw: String): String? {
    return LinuxDoEmojiCatalog.shortcodeToUnicode(raw)
}


@Composable
private fun NativePostPoll(
    poll: PostBlockNode.Poll,
    palette: LinDoThemePalette,
    postId: Int?,
    originalUrl: String?,
    onOpenLink: (String) -> Unit,
    onRequestLogin: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val snapshot = remember(postId, poll.name) { postId?.let { LinuxDoRepository.getPollVoteSnapshot(it, poll.name) } }
    var snapshotSelectedIds by remember(postId, poll, snapshot) { mutableStateOf(snapshot?.selectedIds.orEmpty()) }
    var selected by remember(postId, poll, snapshot) { mutableStateOf(snapshot?.selectedIds.orEmpty()) }
    var submitting by remember(postId, poll) { mutableStateOf(false) }
    var retracting by remember(postId, poll) { mutableStateOf(false) }
    var submitted by remember(postId, poll, snapshot) { mutableStateOf(snapshot?.hasVoted == true) }
    var optionVotes by remember(postId, poll, snapshot) { mutableStateOf(snapshot?.optionVotes.orEmpty()) }
    var voters by remember(postId, poll, snapshot) { mutableStateOf(snapshot?.voters) }
    var isClosed by remember(postId, poll, snapshot) { mutableStateOf(poll.closed || snapshot?.closed == true) }
    var error by remember(postId, poll) { mutableStateOf<String?>(null) }
    val siteVerifyVer = do_.linux.lindo.ui.components.LocalSiteVerificationRestoreVersion.current
    var baselineSiteVerifyVer by remember(postId, poll) { androidx.compose.runtime.mutableIntStateOf(siteVerifyVer) }
    androidx.compose.runtime.LaunchedEffect(siteVerifyVer) {
        if (siteVerifyVer == baselineSiteVerifyVer) return@LaunchedEffect
        baselineSiteVerifyVer = siteVerifyVer
        if (error.isSiteVerificationError()) {
            error = null
        }
    }
    val busy = submitting || retracting
    val isAuthorized = UserApiKeyAuthManager.isAuthorized()
    val requestGuestLogin = do_.linux.lindo.ui.components.LocalRequestGuestLogin.current
    val pollGuestGate = GuestGateScenario.POLL
    val triggerPollLogin: (() -> Unit)? = when {
        requestGuestLogin != null -> ({
            requestGuestLogin(pollGuestGate.titleCopy.text, pollGuestGate.subtitleCopy.text)
        })
        onRequestLogin != null -> onRequestLogin
        else -> null
    }

    fun submitOrUpdateVote() {
        if (postId == null || busy) return
        if (!UserApiKeyAuthManager.isAuthorized()) {
            if (triggerPollLogin != null) triggerPollLogin() else error = LinDoReaderCopy.PLEASE_LOGIN_BEFORE_VOTE.text
            return
        }
        if (selected.size !in poll.min..poll.max) return
        submitting = true
        error = null
        scope.launch {
            try {
                val latest = LinuxDoRepository.votePoll(postId, poll.name, selected.toList())
                if (latest != null) {
                    submitted = latest.hasVoted
                    snapshotSelectedIds = latest.selectedIds
                    selected = latest.selectedIds
                    optionVotes = latest.optionVotes
                    voters = latest.voters
                    isClosed = poll.closed || latest.closed
                } else {
                    error = if (submitted) LinDoReaderCopy.POLL_UPDATE_FAILED_RETRY.text else LinDoReaderCopy.POLL_SUBMIT_FAILED_RETRY.text
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: if (submitted) LinDoReaderCopy.POLL_UPDATE_FAILED_RETRY.text else LinDoReaderCopy.POLL_SUBMIT_FAILED_RETRY.text
            } finally {
                submitting = false
            }
        }
    }

    fun retractVote() {
        if (postId == null || busy) return
        if (!UserApiKeyAuthManager.isAuthorized()) {
            if (triggerPollLogin != null) triggerPollLogin() else error = LinDoReaderCopy.PLEASE_LOGIN_BEFORE_ACTION.text
            return
        }
        retracting = true
        error = null
        scope.launch {
            try {
                val removed = LinuxDoRepository.removePollVote(postId, poll.name)
                if (removed != null) {
                    submitted = false
                    snapshotSelectedIds = emptySet()
                    selected = emptySet()
                    optionVotes = removed.optionVotes
                    voters = removed.voters
                    isClosed = poll.closed || removed.closed
                } else {
                    error = LinDoReaderCopy.POLL_WITHDRAW_FAILED_RETRY.text
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: LinDoReaderCopy.POLL_WITHDRAW_FAILED_RETRY.text
            } finally {
                retracting = false
            }
        }
    }

    var expandedVotersOptionId by remember(postId, poll) { mutableStateOf<String?>(null) }
    var votersCache by remember(postId, poll) { mutableStateOf<Map<String, List<do_.linux.lindo.core.model.PollVoterEntry>>>(emptyMap()) }
    var loadingVotersFor by remember(postId, poll) { mutableStateOf<String?>(null) }
    var showResultsManually by remember(postId, poll) { mutableStateOf(false) }
    val totalVotesSum = remember(optionVotes, voters) {
        val sum = optionVotes.values.sum()
        if (sum > 0) sum else (voters ?: 0)
    }
    val hasAnyVoteCounts = optionVotes.isNotEmpty()
    val showResultBars = submitted || isClosed || showResultsManually

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(poll.title, fontWeight = FontWeight.SemiBold, color = palette.inkPrimary, modifier = Modifier.weight(1f))
            if (hasAnyVoteCounts && !submitted && !isClosed) {
                Text(
                    text = if (showResultsManually) "隐藏结果" else "查看结果",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.activeChipText,
                    modifier = Modifier
                        .testTag("poll_toggle_results_btn")
                        .clip(RoundedCornerShape(6.dp))
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable { showResultsManually = !showResultsManually }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        poll.options.forEach { option ->
            val isOptionSelected = option.id in selected
            val optVotes = optionVotes[option.id]
            val pctFraction = if (optVotes != null && totalVotesSum > 0) {
                (optVotes.toFloat() / totalVotesSum.toFloat()).coerceIn(0f, 1f)
            } else 0f
            val pctInt = (pctFraction * 100f).roundToInt()
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .let { mod ->
                            if (postId != null && !isClosed && !busy) {
                                mod.pointerHoverIcon(PointerIcon.Hand)
                            } else {
                                mod
                            }
                        }
                        .clickable(enabled = postId != null && !isClosed && !busy) {
                            selected = when {
                                isOptionSelected -> selected - option.id
                                !poll.multiple -> setOf(option.id)
                                selected.size < poll.max -> selected + option.id
                                else -> selected
                            }
                            error = null
                        }
                        .padding(horizontal = 6.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val indicatorShape = if (poll.multiple) RoundedCornerShape(4.dp) else androidx.compose.foundation.shape.CircleShape
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(indicatorShape)
                            .border(
                                width = if (isOptionSelected) 1.5.dp else 1.dp,
                                color = if (isOptionSelected) palette.brandGold else palette.inkTertiary,
                                shape = indicatorShape
                            )
                            .background(
                                if (isOptionSelected && poll.multiple) palette.brandGold else androidx.compose.ui.graphics.Color.Transparent
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isOptionSelected) {
                            if (poll.multiple) {
                                Icon(
                                    imageVector = LinDoIcons.Check,
                                    contentDescription = null,
                                    tint = androidx.compose.ui.graphics.Color.White,
                                    modifier = Modifier.size(12.dp)
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(8.5.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                        .background(palette.brandGold)
                                )
                            }
                        }
                    }
                    Text(option.text, modifier = Modifier.weight(1f), color = palette.inkPrimary, fontSize = 14.sp)
                    if (showResultBars && optVotes != null) {
                        Text(
                            text = "$pctInt% (${LinDoReaderCopy.POLL_VOTES_COUNT.format(optVotes)})",
                            color = if (optVotes > 0 && postId != null) palette.activeChipText else palette.inkSecondary,
                            fontSize = 12.sp,
                            fontWeight = if (isOptionSelected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier
                                .testTag("poll_option_pct_${option.id}")
                                .let { m ->
                                    if (optVotes > 0 && postId != null) {
                                        m.clip(RoundedCornerShape(6.dp))
                                            .pointerHoverIcon(PointerIcon.Hand)
                                            .clickable {
                                                if (expandedVotersOptionId == option.id) {
                                                    expandedVotersOptionId = null
                                                } else {
                                                    expandedVotersOptionId = option.id
                                                    if (!votersCache.containsKey(option.id)) {
                                                        loadingVotersFor = option.id
                                                        scope.launch {
                                                            val fetched = runCatching {
                                                                LinuxDoRepository.fetchPollVoters(postId, poll.name, option.id)
                                                            }.getOrDefault(emptyList())
                                                            votersCache = votersCache + (option.id to fetched)
                                                            if (loadingVotersFor == option.id) loadingVotersFor = null
                                                        }
                                                    }
                                                }
                                            }
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    } else m
                                }
                        )
                    }
                }
                if (showResultBars && optVotes != null) {
                    val animatedFrac by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = pctFraction,
                        label = "pollOptionBar_${option.id}"
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 34.dp, end = 6.dp, bottom = 4.dp)
                            .height(6.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(palette.subtleSurface)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(animatedFrac)
                                .height(6.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (isOptionSelected) palette.brandGold else palette.activeChipText.copy(alpha = 0.72f))
                        )
                    }
                }
                if (expandedVotersOptionId == option.id) {
                    val votersList = votersCache[option.id]
                    val loading = loadingVotersFor == option.id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 34.dp, end = 6.dp, bottom = 6.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        when {
                            loading -> Text("加载投票成员…", color = palette.inkTertiary, fontSize = 11.5.sp)
                            votersList.isNullOrEmpty() -> Text("该投票未公开投票成员", color = palette.inkTertiary, fontSize = 11.5.sp)
                            else -> {
                                val onInspectUser = LocalOnInspectUser.current
                                votersList.take(12).forEach { voter ->
                                    Row(
                                        modifier = Modifier
                                            .testTag("poll_voter_chip_${voter.username}")
                                            .clip(CircleShape)
                                            .background(palette.subtleSurface)
                                            .then(
                                                if (onInspectUser != null) {
                                                    Modifier
                                                        .pointerHoverIcon(PointerIcon.Hand)
                                                        .clickable { onInspectUser(voter.username) }
                                                } else Modifier
                                            )
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        do_.linux.lindo.ui.components.SmartAvatar(
                                            username = voter.username,
                                            avatarTemplate = voter.avatarTemplate,
                                            animatedAvatar = null,
                                            size = 15.dp,
                                            enableAnimations = false
                                        )
                                        Text("@${voter.username}", color = palette.inkSecondary, fontSize = 11.2.sp)
                                    }
                                }
                                if (votersList.size > 12) {
                                    Text("等 ${votersList.size} 人", color = palette.inkTertiary, fontSize = 11.2.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
        when {
            isClosed -> Text(
                voters?.let { LinDoReaderCopy.POLL_CLOSED_WITH_VOTERS.format(it) } ?: LinDoReaderCopy.POLL_CLOSED.text,
                color = palette.inkSecondary,
                fontSize = 12.sp
            )
            postId == null -> Text(LinDoReaderCopy.POLL_AVAILABLE_AFTER_PUBLISH.text, color = palette.inkSecondary, fontSize = 12.sp)
            !isAuthorized && triggerPollLogin != null -> TextButton(enabled = !busy, onClick = triggerPollLogin) {
                Text(LinDoReaderCopy.LOGIN_TO_VOTE.text, color = palette.brandGold, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
            }
            submitted -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = voters?.let { LinDoReaderCopy.POLL_SUBMITTED_WITH_VOTERS.format(it) } ?: LinDoReaderCopy.POLL_SUBMITTED.text,
                        modifier = Modifier.weight(1f),
                        color = palette.inkSecondary,
                        fontSize = 13.sp
                    )
                    if (selected != snapshotSelectedIds) {
                        val canUpdate = !busy && selected.size in poll.min..poll.max
                        TextButton(
                            enabled = canUpdate,
                            onClick = ::submitOrUpdateVote,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            LinDoBusyButtonLabel(
                                busy = submitting,
                                busyLabel = LinDoCommonCopy.UPDATING.text,
                                label = LinDoReaderCopy.UPDATE_VOTE.text,
                                color = if (canUpdate || submitting) palette.brandGold else palette.inkTertiary
                            )
                        }
                    }
                    TextButton(
                        enabled = !busy,
                        onClick = ::retractVote,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        LinDoBusyButtonLabel(
                            busy = retracting,
                            busyLabel = LinDoCommonCopy.WITHDRAWING.text,
                            label = LinDoReaderCopy.WITHDRAW_VOTE.text,
                            color = palette.inkSecondary
                        )
                    }
                }
            }
            else -> {
                val canVote = !busy && selected.size in poll.min..poll.max
                TextButton(enabled = canVote, onClick = ::submitOrUpdateVote) {
                    LinDoBusyButtonLabel(
                        busy = submitting,
                        busyLabel = LinDoCommonCopy.SUBMITTING.text,
                        label = LinDoReaderCopy.SUBMIT_VOTE.text,
                        color = if (canVote || submitting) palette.brandGold else palette.inkTertiary
                    )
                }
            }
        }
        error?.let { LinDoFeedbackRow(it, LinDoMascotState.NetworkError) }
    }
}

private fun isPostImageAssetUrl(rawUrl: String): Boolean {
    val cleanPath = rawUrl.substringBefore('#').substringBefore('?').lowercase()
    return cleanPath.endsWith(".png") ||
        cleanPath.endsWith(".jpg") ||
        cleanPath.endsWith(".jpeg") ||
        cleanPath.endsWith(".webp") ||
        cleanPath.endsWith(".gif") ||
        cleanPath.endsWith(".avif") ||
        cleanPath.endsWith(".svg") ||
        rawUrl.contains("/uploads/", ignoreCase = true) ||
        rawUrl.contains("cdn.ldstatic.com", ignoreCase = true)
}

@Composable
internal fun PostImageLightboxDialog(
    gallery: PostBlockNode.ImageGallery,
    initialIndex: Int,
    onOpenLink: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val totalCount = gallery.urls.size
    if (totalCount == 0) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    var currentIndex by remember(gallery, initialIndex) {
        mutableIntStateOf(initialIndex.coerceIn(0, totalCount - 1))
    }
    var scale by remember(currentIndex) { mutableFloatStateOf(1f) }
    var offset by remember(currentIndex) { mutableStateOf(Offset.Zero) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var copiedLink by remember(currentIndex) { mutableStateOf(false) }
    var loadOriginalFailed by remember(currentIndex) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(copiedLink) {
        if (copiedLink) {
            delay(1800)
            copiedLink = false
        }
    }
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    val previewUrl = gallery.urls.getOrElse(currentIndex) { "" }
    val fullUrl = gallery.fullUrls.getOrElse(currentIndex) { previewUrl }.ifBlank { previewUrl }
    val displayUrl = if (loadOriginalFailed) previewUrl else fullUrl
    val rawCaption = gallery.captions.getOrNull(currentIndex).orEmpty().trim()

    fun clampOffset(candidate: Offset, targetScale: Float): Offset {
        if (targetScale <= 1.01f) return Offset.Zero
        val maxX = (viewportSize.width * (targetScale - 1f) / 2f).coerceAtLeast(0f)
        val maxY = (viewportSize.height * (targetScale - 1f) / 2f).coerceAtLeast(0f)
        return Offset(
            x = candidate.x.coerceIn(-maxX, maxX),
            y = candidate.y.coerceIn(-maxY, maxY)
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { keyEvent ->
                    if (keyEvent.type == KeyEventType.KeyDown) {
                        when (keyEvent.key) {
                            Key.Escape -> {
                                onDismiss()
                                true
                            }
                            Key.DirectionLeft -> {
                                if (totalCount > 1 && currentIndex > 0) {
                                    currentIndex -= 1
                                    true
                                } else false
                            }
                            Key.DirectionRight -> {
                                if (totalCount > 1 && currentIndex < totalCount - 1) {
                                    currentIndex += 1
                                    true
                                } else false
                            }
                            else -> false
                        }
                    } else false
                }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { viewportSize = it }
                    .pointerInput(currentIndex) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > 1.05f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 2.5f
                                    offset = Offset.Zero
                                }
                            },
                            onTap = {
                                if (scale <= 1.05f) {
                                    onDismiss()
                                }
                            }
                        )
                    }
                    .pointerInput(currentIndex) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val nextScale = (scale * zoom).coerceIn(1f, 4f)
                            val nextOffset = if (nextScale > 1.01f) {
                                clampOffset(offset + pan, nextScale)
                            } else {
                                Offset.Zero
                            }
                            scale = nextScale
                            offset = nextOffset
                        }
                    }
                    .pointerInput(currentIndex) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type == PointerEventType.Scroll) {
                                    val scrollY = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                                    if (scrollY != 0f) {
                                        val factor = if (scrollY < 0f) 1.18f else (1f / 1.18f)
                                        val nextScale = (scale * factor).coerceIn(1f, 4f)
                                        scale = nextScale
                                        offset = clampOffset(offset, nextScale)
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = displayUrl,
                    contentDescription = rawCaption.ifBlank { LinDoReaderCopy.IMAGE_PREVIEW_DESC.text },
                    contentScale = ContentScale.Fit,
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                    onError = {
                        if (!loadOriginalFailed && previewUrl != fullUrl && previewUrl.isNotBlank()) {
                            loadOriginalFailed = true
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp, vertical = 64.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        }
                )
            }

            // Top bar: gallery index / caption, zoom reset chip, Close button
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (totalCount > 1) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.White.copy(alpha = 0.14f))
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = "${currentIndex + 1} / $totalCount",
                            color = Color.White.copy(alpha = 0.92f),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                if (rawCaption.isNotBlank()) {
                    Text(
                        text = rawCaption,
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (scale > 1.02f) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.White.copy(alpha = 0.18f))
                            .clickable {
                                scale = 1f
                                offset = Offset.Zero
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = LinDoReaderCopy.RESET_ZOOM_100.format((scale * 100f).roundToInt()),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.16f))
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = LinDoIcons.Close,
                        contentDescription = LinDoReaderCopy.CLOSE_IMAGE_PREVIEW_DESC.text,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Prev / Next arrows for multi-image galleries
            if (totalCount > 1) {
                if (currentIndex > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 14.dp)
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.16f))
                            .clickable { currentIndex -= 1 },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = LinDoIcons.ArrowLeft,
                            contentDescription = LinDoReaderCopy.PREV_IMAGE_DESC.text,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                if (currentIndex < totalCount - 1) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 14.dp)
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.16f))
                            .clickable { currentIndex += 1 },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = LinDoIcons.ArrowRight,
                            contentDescription = LinDoReaderCopy.NEXT_IMAGE_DESC.text,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Bottom subtle action bar (Copy Link + Save to Gallery + Open Original)
            val saveScope = rememberCoroutineScope()
            var savingImage by remember(currentIndex) { mutableStateOf(false) }
            var saveToast by remember(currentIndex) { mutableStateOf<String?>(null) }
            LaunchedEffect(saveToast) {
                if (saveToast != null) {
                    delay(2_200L)
                    saveToast = null
                }
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                saveToast?.let { toast ->
                    Text(
                        text = toast,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White,
                        modifier = Modifier
                            .testTag("lightbox_save_toast")
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.Black.copy(alpha = 0.72f))
                            .padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color.White.copy(alpha = 0.14f))
                        .border(0.5.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(22.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable(
                                onClickLabel = if (copiedLink) LinDoReaderCopy.IMAGE_LINK_COPIED.text else LinDoReaderCopy.COPY_IMAGE_LINK.text
                            ) {
                                clipboard.setText(AnnotatedString(fullUrl))
                                copiedLink = true
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (copiedLink) LinDoIcons.Check else LinDoIcons.Link,
                            contentDescription = if (copiedLink) LinDoReaderCopy.IMAGE_LINK_COPIED.text else LinDoReaderCopy.COPY_IMAGE_LINK.text,
                            tint = if (copiedLink) Color(0xFFF5C451) else Color.White,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .width(0.5.dp)
                            .height(16.dp)
                            .background(Color.White.copy(alpha = 0.25f))
                    )
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .testTag("lightbox_save_image_btn")
                            .clickable(enabled = !savingImage, onClickLabel = "保存图片") {
                                val saver = PlatformRichMediaBridge.saveImageToGallery
                                if (saver == null) {
                                    clipboard.setText(AnnotatedString(fullUrl))
                                    saveToast = "已复制图片链接"
                                    return@clickable
                                }
                                savingImage = true
                                val suggestedName = fullUrl.substringAfterLast('/').substringBefore('?').ifBlank { "lindo_image.jpg" }
                                saveScope.launch {
                                    val res = runCatching { saver(fullUrl, suggestedName) }.getOrElse { Result.failure(it) }
                                    savingImage = false
                                    saveToast = res.fold(
                                        onSuccess = { it.ifBlank { "已保存到相册 Pictures/LinDo" } },
                                        onFailure = { err -> err.message?.takeIf { it.isNotBlank() } ?: "保存图片失败" }
                                    )
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (savingImage) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(15.dp),
                                strokeWidth = 1.8.dp,
                                color = Color.White
                            )
                        } else {
                            Icon(
                                imageVector = LinDoIcons.Download,
                                contentDescription = "保存图片",
                                tint = Color.White,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .width(0.5.dp)
                            .height(16.dp)
                            .background(Color.White.copy(alpha = 0.25f))
                    )
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable(onClickLabel = LinDoReaderCopy.OPEN_ORIGINAL_IN_BROWSER.text) {
                                onOpenLink(fullUrl)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = LinDoIcons.ExternalLink,
                            contentDescription = LinDoReaderCopy.OPEN_ORIGINAL_IN_BROWSER.text,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}
