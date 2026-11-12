package do_.linux.lindo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import do_.linux.lindo.core.network.LinuxDoRepository
import do_.linux.lindo.core.network.UserApiKeyAuthManager
import coil3.compose.AsyncImage
import do_.linux.lindo.core.render.*
import do_.linux.lindo.core.site.LinuxDoEmojiCatalog
import do_.linux.lindo.ui.components.LinDoIcons

@Composable
internal fun RichPostText(rich: RichText, palette: LinDoThemePalette,
    onOpenLink: (String) -> Unit, heading: Boolean = false, onSelectionChange: ((String?) -> Unit)? = null) {
    val plain = rich.runs.singleOrNull()
    if (plain != null && plain.href == null && plain.imageUrl == null && !plain.bold &&
        !plain.italic && !plain.code && !plain.strike && !plain.underline
    ) {
        SelectablePostText(plain.text, onSelectionChange) {
            Text(
                plain.text, color = palette.inkPrimary,
                fontSize = if (heading) 17.sp else 15.sp, lineHeight = 24.sp,
                fontWeight = if (heading) FontWeight.SemiBold else null
            )
        }
        return
    }
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    var unavailableMailLink by remember { mutableStateOf<String?>(null) }
    val annotated = remember(rich, palette, onOpenLink, uriHandler) {
        buildAnnotatedString {
            for ((index, run) in rich.runs.withIndex()) {
                pushStyle(SpanStyle(fontWeight = if (run.bold || heading) FontWeight.SemiBold else null,
                    fontStyle = if (run.italic) FontStyle.Italic else null,
                    fontFamily = if (run.code) FontFamily.Monospace else null,
                    background = if (run.code) palette.subtleSurface else androidx.compose.ui.graphics.Color.Unspecified,
                    textDecoration = when { run.strike -> TextDecoration.LineThrough; run.underline -> TextDecoration.Underline; else -> null }))
                if (run.href != null) pushLink(LinkAnnotation.Clickable(run.href,
                    TextLinkStyles(SpanStyle(color = palette.activeChipText, textDecoration = TextDecoration.Underline)),
                    linkInteractionListener = {
                        if (run.href.startsWith("mailto:", ignoreCase = true)) {
                            runCatching { uriHandler.openUri(run.href) }.onFailure { unavailableMailLink = run.href }
                        } else onOpenLink(run.href)
                    }))
                if (run.imageUrl != null) appendInlineContent("emoji-$index", run.text) else append(run.text)
                if (run.href != null) pop()
                pop()
            }
        }
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
                        contentDescription = "表情：${run.text}",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(0.5.dp),
                        onError = { failedEmoji[url] = true }
                    )
                }
            }
        } }.toMap()
    }
    SelectablePostText(annotated.text, onSelectionChange) {
        Text(annotated, inlineContent = inlineImages, color = palette.inkPrimary, fontSize = if (heading) 17.sp else 15.sp, lineHeight = 24.sp)
    }
    unavailableMailLink?.let { mail ->
        TextButton(onClick = { clipboard.setText(AnnotatedString(mail.removePrefix("mailto:"))) }) {
            Text("无法打开邮件应用，点击复制地址", color = palette.activeChipText)
        }
    }
}

private val LocalActiveSelectionOwner = compositionLocalOf<MutableState<Any?>?> { null }

// Compose exposes selection offsets through its controlled container on the pinned runtime.
@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
@Composable
private fun SelectablePostText(
    text: String,
    onSelectionChange: ((String?) -> Unit)?,
    content: @Composable () -> Unit
) {
    if (onSelectionChange == null) {
        SelectionContainer(content = content)
        return
    }
    val ownerToken = remember { Any() }
    val activeOwner = LocalActiveSelectionOwner.current
    var selection by remember(text) {
        mutableStateOf<androidx.compose.foundation.text.selection.Selection?>(null)
    }
    val latestCallback by rememberUpdatedState(onSelectionChange)
    LaunchedEffect(activeOwner?.value) {
        if (activeOwner != null && activeOwner.value != ownerToken && selection != null) {
            selection = null
        }
    }
    DisposableEffect(text, ownerToken) {
        onDispose {
            if (activeOwner == null) {
                if (selection != null) latestCallback(null)
            } else if (activeOwner.value == ownerToken) {
                activeOwner.value = null
                latestCallback(null)
            }
        }
    }
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
                    latestCallback(selectedText)
                } else if (activeOwner == null || activeOwner.value == ownerToken) {
                    if (activeOwner?.value == ownerToken) activeOwner.value = null
                    latestCallback(null)
                }
            } else if (hadSelection) {
                if (activeOwner == null || activeOwner.value == ownerToken) {
                    if (activeOwner?.value == ownerToken) activeOwner.value = null
                    latestCallback(null)
                }
            }
        },
        children = content
    )
}

@Composable
internal fun PostRichContent(
    nodes: List<PostBlockNode>,
    palette: LinDoThemePalette,
    onOpenLink: (String) -> Unit,
    originalUrl: String? = null,
    postId: Int? = null,
    onSelectionChange: ((String?) -> Unit)? = null,
    onRequestLogin: (() -> Unit)? = null
) {
    val existingSelectionOwner = LocalActiveSelectionOwner.current
    if (existingSelectionOwner == null) {
        val rootSelectionOwner = remember { mutableStateOf<Any?>(null) }
        CompositionLocalProvider(LocalActiveSelectionOwner provides rootSelectionOwner) {
            PostRichContentBody(
                nodes = nodes,
                palette = palette,
                onOpenLink = onOpenLink,
                originalUrl = originalUrl,
                postId = postId,
                onSelectionChange = onSelectionChange,
                onRequestLogin = onRequestLogin
            )
        }
    } else {
        PostRichContentBody(
            nodes = nodes,
            palette = palette,
            onOpenLink = onOpenLink,
            originalUrl = originalUrl,
            postId = postId,
            onSelectionChange = onSelectionChange,
            onRequestLogin = onRequestLogin
        )
    }
}

@Composable
private fun PostRichContentBody(
    nodes: List<PostBlockNode>,
    palette: LinDoThemePalette,
    onOpenLink: (String) -> Unit,
    originalUrl: String? = null,
    postId: Int? = null,
    onSelectionChange: ((String?) -> Unit)? = null,
    onRequestLogin: (() -> Unit)? = null
) {
    val clipboard = LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        for (node in nodes) when (node) {
            is PostBlockNode.Paragraph -> RichPostText(node.rich, palette, onOpenLink, onSelectionChange = onSelectionChange)
            is PostBlockNode.Heading -> RichPostText(node.rich, palette, onOpenLink, heading = true, onSelectionChange = onSelectionChange)
            is PostBlockNode.Spoiler -> {
                var revealed by remember(node) { mutableStateOf(false) }
                Column {
                    TextButton(onClick = { revealed = !revealed }) { Text(if (revealed) "收起隐藏内容" else "显示隐藏内容", color = palette.inkSecondary) }
                    if (revealed) PostRichContent(node.children, palette, onOpenLink, originalUrl, postId, onSelectionChange, onRequestLogin)
                }
            }
            is PostBlockNode.Poll -> NativePostPoll(node, palette, postId, originalUrl, onOpenLink, onRequestLogin)

            is PostBlockNode.QuoteBlock -> Column(Modifier.fillMaxWidth().background(palette.subtleSurface, RoundedCornerShape(8.dp)).padding(12.dp)) {
                node.username?.let { Text("@$it", color = palette.inkSecondary, fontSize = 12.sp) }
                PostRichContent(node.children.ifEmpty { listOf(PostBlockNode.Paragraph(node.quotedText)) }, palette, onOpenLink, originalUrl, postId, onSelectionChange, onRequestLogin)
            }
            is PostBlockNode.CodeBlock -> Column(Modifier.fillMaxWidth().background(palette.subtleSurface, RoundedCornerShape(8.dp))) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp)) {
                    Text(node.language.ifBlank { "text" }, Modifier.weight(1f).padding(top = 14.dp), color = palette.inkSecondary, fontSize = 12.sp)
                    TextButton(onClick = { clipboard.setText(AnnotatedString(node.code)) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("复制代码", color = palette.activeChipText)
                    }
                }
                SelectablePostText(node.code, onSelectionChange) {
                    Text(node.code, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp,
                        color = palette.inkPrimary, modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp))
                }
            }
            is PostBlockNode.BulletList -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                node.items.forEachIndexed { index, text ->
                    Row {
                        Text(if (node.ordered) "${node.start + index}. " else "• ", color = palette.inkPrimary, modifier = Modifier.widthIn(min = 24.dp))
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
            is PostBlockNode.ImageGallery -> node.urls.forEachIndexed { index, url ->
                var failed by remember(url) { mutableStateOf(false) }
                val fullUrl = node.fullUrls.getOrElse(index) { url }
                val linkTarget = node.linkUrls.getOrNull(index)?.takeIf { it.isNotBlank() } ?: fullUrl
                val alt = node.captions.getOrNull(index).orEmpty().ifBlank { "帖子配图" }
                if (failed) {
                    TextButton(onClick = { onOpenLink(linkTarget) }) { Text("$alt · 打开图片", color = palette.activeChipText) }
                } else {
                    val context = coil3.compose.LocalPlatformContext.current
                    val imageRequest = remember(context, url) {
                        coil3.request.ImageRequest.Builder(context)
                            .data(url)
                            .size(coil3.size.Size(coil3.size.Dimension(1440), coil3.size.Dimension.Undefined))
                            .precision(coil3.size.Precision.INEXACT)
                            .build()
                    }
                    AsyncImage(
                        model = imageRequest,
                        contentDescription = alt,
                        contentScale = ContentScale.FillWidth,
                        filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium,
                        onError = { failed = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 60.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(palette.subtleSurface)
                            .clickable { onOpenLink(linkTarget) }
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
            is PostBlockNode.Media -> Column(Modifier.fillMaxWidth().background(palette.subtleSurface, RoundedCornerShape(10.dp)).border(0.5.dp, palette.hairline, RoundedCornerShape(10.dp)).padding(12.dp)) {
                Text(node.label, color = palette.inkPrimary, fontWeight = FontWeight.Medium)
                TextButton(onClick = { (node.url ?: originalUrl)?.let(onOpenLink) }, enabled = node.url != null || originalUrl != null) {
                    Text(
                        "播放${if (node.kind == "audio") "音频" else "媒体内容"}",
                        color = if (node.url != null || originalUrl != null) palette.activeChipText else palette.inkTertiary
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
                val accentColor = when (node.kind) {
                    "lottery" -> palette.brandGold
                    "warning" -> androidx.compose.ui.graphics.Color(0xFFD97706)
                    "tip" -> androidx.compose.ui.graphics.Color(0xFF10B981)
                    else -> palette.inkSecondary
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(palette.subtleSurface)
                        .border(0.5.dp, accentColor.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = node.title,
                        color = accentColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    PostRichContent(node.children, palette, onOpenLink, originalUrl, postId, onSelectionChange, onRequestLogin)
                }
            }
            is PostBlockNode.Unsupported -> {
                when (node.kind) {
                    "merged_table" -> {
                        // 表格单元格内容已在上方 Table 块完整渲染，无需再显示冗余占位框
                    }
                    "math" -> {
                        if (node.text.isNotBlank()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(palette.subtleSurface)
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                SelectionContainer {
                                    Text(
                                        text = node.text,
                                        color = palette.inkPrimary,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.5.sp,
                                        lineHeight = 20.sp
                                    )
                                }
                            }
                        }
                    }
                    "poll" -> Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(node.label, color = palette.inkPrimary, fontWeight = FontWeight.SemiBold)
                        node.options.forEach { Text("• $it", color = palette.inkSecondary, fontSize = 14.sp) }
                        if (originalUrl != null) TextButton(onClick = { onOpenLink(originalUrl + if (originalUrl.contains('?')) "&web=1" else "?web=1") }) {
                            Text("前往原帖投票", color = palette.brandGold)
                        } else Text("投票在发布后可用", color = palette.inkSecondary, fontSize = 12.sp)
                    }
                    "svg" -> Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(node.text, color = palette.inkSecondary, fontSize = 13.sp)
                        if (originalUrl != null) TextButton(onClick = { onOpenLink(originalUrl + if (originalUrl.contains('?')) "&web=1" else "?web=1") }) {
                            Text("在原帖查看图形", color = palette.brandGold)
                        }
                    }

                    else -> {
                        if (node.text.isNotBlank()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(palette.subtleSurface, RoundedCornerShape(10.dp))
                                    .border(0.5.dp, palette.hairline, RoundedCornerShape(10.dp))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (node.label.isNotBlank() && node.label != "互动内容") {
                                    Text(node.label, color = palette.inkPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                                }
                                SelectionContainer {
                                    Text(node.text, color = palette.inkPrimary, fontSize = 14.sp, lineHeight = 20.sp)
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
        if (error?.contains("站点验证") == true) {
            error = null
        }
    }
    val busy = submitting || retracting
    val isAuthorized = UserApiKeyAuthManager.isAuthorized()
    val requestGuestLogin = do_.linux.lindo.ui.components.LocalRequestGuestLogin.current
    val triggerPollLogin: (() -> Unit)? = when {
        requestGuestLogin != null -> ({ requestGuestLogin("登录后参与投票", "登录 LINUX DO 账号后即可参与社区话题投票") })
        onRequestLogin != null -> onRequestLogin
        else -> null
    }

    fun submitOrUpdateVote() {
        if (postId == null || busy) return
        if (!UserApiKeyAuthManager.isAuthorized()) {
            if (triggerPollLogin != null) triggerPollLogin() else error = "请先登录后投票"
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
                    error = if (submitted) "投票未更新，请重试" else "投票未提交，请重试"
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: if (submitted) "投票未更新，请重试" else "投票未提交，请重试"
            } finally {
                submitting = false
            }
        }
    }

    fun retractVote() {
        if (postId == null || busy) return
        if (!UserApiKeyAuthManager.isAuthorized()) {
            if (triggerPollLogin != null) triggerPollLogin() else error = "请先登录后操作"
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
                    error = "撤回投票失败，请重试"
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "撤回投票失败，请重试"
            } finally {
                retracting = false
            }
        }
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(poll.title, fontWeight = FontWeight.SemiBold, color = palette.inkPrimary)
        poll.options.forEach { option ->
            val isOptionSelected = option.id in selected
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(enabled = postId != null && !isClosed && !busy) {
                    selected = when {
                        isOptionSelected -> selected - option.id
                        !poll.multiple -> setOf(option.id)
                        selected.size < poll.max -> selected + option.id
                        else -> selected
                    }
                    error = null
                }.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
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
                optionVotes[option.id]?.let { count ->
                    Text("$count 票", color = palette.inkSecondary, fontSize = 12.sp)
                }
            }
        }
        when {
            isClosed -> Text(voters?.let { "投票已关闭 · 共 $it 人参与" } ?: "投票已关闭", color = palette.inkSecondary, fontSize = 12.sp)
            postId == null -> Text("投票在发布后可用", color = palette.inkSecondary, fontSize = 12.sp)
            !isAuthorized && triggerPollLogin != null -> TextButton(enabled = !busy, onClick = triggerPollLogin) {
                Text("登录后投票", color = palette.brandGold, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
            }
            submitted -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = voters?.let { "投票已提交 · 共 $it 人参与" } ?: "投票已提交",
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
                                busyLabel = "更新中",
                                label = "更新投票",
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
                            busyLabel = "撤回中",
                            label = "撤回投票",
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
                        busyLabel = "提交中",
                        label = "提交投票",
                        color = if (canVote || submitting) palette.brandGold else palette.inkTertiary
                    )
                }
            }
        }
        error?.let { LinDoFeedbackRow(it, LinDoMascotState.NetworkError) }
    }
}
