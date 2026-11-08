package do_.linux.lindo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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

@Composable
internal fun RichPostText(rich: RichText, palette: LinDoThemePalette,
    onOpenLink: (String) -> Unit, heading: Boolean = false) {
    val plain = rich.runs.singleOrNull()
    if (plain != null && plain.href == null && plain.imageUrl == null && !plain.bold &&
        !plain.italic && !plain.code && !plain.strike && !plain.underline
    ) {
        SelectionContainer {
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
                    TextLinkStyles(SpanStyle(color = if (palette.isDark) androidx.compose.ui.graphics.Color(0xFFF6C762) else androidx.compose.ui.graphics.Color(0xFF8A5A00), textDecoration = TextDecoration.Underline)),
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
    val inlineImages = remember(rich, failures) {
        rich.runs.mapIndexedNotNull { index, run -> run.imageUrl?.let { url ->
            val failed = failures[url] == true
            val fallbackGlyph = shortcodeToUnicodeEmoji(run.text)
            val baseEm = if (run.largeEmoji) 1.65f else 1.25f
            val width = if (failed && fallbackGlyph == null) run.text.length.coerceIn(2, 10).toFloat() else baseEm
            "emoji-$index" to InlineTextContent(Placeholder(width.em, baseEm.em, PlaceholderVerticalAlign.TextCenter)) {
                if (failed) {
                    Text(
                        text = fallbackGlyph ?: run.text,
                        fontSize = if (run.largeEmoji) 20.sp else 14.5.sp,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
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
    SelectionContainer {
        Text(annotated, inlineContent = inlineImages, color = palette.inkPrimary, fontSize = if (heading) 17.sp else 15.sp, lineHeight = 24.sp)
    }
    unavailableMailLink?.let { mail ->
        TextButton(onClick = { clipboard.setText(AnnotatedString(mail.removePrefix("mailto:"))) }) {
            Text("无法打开邮件应用，点击复制地址")
        }
    }
}

@Composable
internal fun PostRichContent(nodes: List<PostBlockNode>, palette: LinDoThemePalette,
    onOpenLink: (String) -> Unit, originalUrl: String? = null, postId: Int? = null) {
    val clipboard = LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        for (node in nodes) when (node) {
            is PostBlockNode.Paragraph -> RichPostText(node.rich, palette, onOpenLink)
            is PostBlockNode.Heading -> RichPostText(node.rich, palette, onOpenLink, heading = true)
            is PostBlockNode.Spoiler -> {
                var revealed by remember(node) { mutableStateOf(false) }
                Column {
                    TextButton(onClick = { revealed = !revealed }) { Text(if (revealed) "收起隐藏内容" else "显示隐藏内容", color = palette.inkSecondary) }
                    if (revealed) PostRichContent(node.children, palette, onOpenLink, originalUrl, postId)
                }
            }
            is PostBlockNode.Poll -> NativePostPoll(node, palette, postId, originalUrl, onOpenLink)

            is PostBlockNode.QuoteBlock -> Column(Modifier.fillMaxWidth().background(palette.subtleSurface, RoundedCornerShape(8.dp)).padding(12.dp)) {
                node.username?.let { Text("@$it", color = palette.inkSecondary, fontSize = 12.sp) }
                PostRichContent(node.children.ifEmpty { listOf(PostBlockNode.Paragraph(node.quotedText)) }, palette, onOpenLink, originalUrl, postId)
            }
            is PostBlockNode.CodeBlock -> Column(Modifier.fillMaxWidth().background(palette.subtleSurface, RoundedCornerShape(8.dp))) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp)) {
                    Text(node.language.ifBlank { "text" }, Modifier.weight(1f).padding(top = 14.dp), color = palette.inkSecondary, fontSize = 12.sp)
                    TextButton(onClick = { clipboard.setText(AnnotatedString(node.code)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("复制代码") }
                }
                SelectionContainer {
                    Text(node.code, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp,
                        color = palette.inkPrimary, modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp))
                }
            }
            is PostBlockNode.BulletList -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                node.items.forEachIndexed { index, text ->
                    Row {
                        Text(if (node.ordered) "${node.start + index}. " else "• ", color = palette.inkPrimary, modifier = Modifier.widthIn(min = 24.dp))
                        Box(Modifier.weight(1f)) { PostRichContent(node.itemBlocks.getOrNull(index) ?: listOf(PostBlockNode.Paragraph(text)), palette, onOpenLink, originalUrl, postId) }
                    }
                }
            }
            is PostBlockNode.DetailsSpoilerBlock -> {
                var expanded by remember(node) { mutableStateOf(false) }
                Column(Modifier.fillMaxWidth().background(palette.subtleSurface, RoundedCornerShape(8.dp)).padding(8.dp)) {
                    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("${if (expanded) "▾" else "▸"} ${node.title}", color = palette.inkPrimary)
                    }
                    if (expanded) PostRichContent(node.children.ifEmpty { listOf(PostBlockNode.Paragraph(node.innerText)) }, palette, onOpenLink, originalUrl, postId)
                }
            }
            is PostBlockNode.ImageGallery -> node.urls.forEachIndexed { index, url ->
                var failed by remember(url) { mutableStateOf(false) }
                val alt = node.captions.getOrNull(index).orEmpty().ifBlank { "帖子配图" }
                if (failed) TextButton(onClick = { onOpenLink(url) }) { Text("$alt · 打开图片") }
                else AsyncImage(model = url, contentDescription = alt, contentScale = ContentScale.FillWidth,
                    onError = { failed = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 360.dp)
                        .clip(RoundedCornerShape(8.dp)).background(palette.subtleSurface).clickable { onOpenLink(url) })
            }
            is PostBlockNode.Table -> Column(Modifier.horizontalScroll(rememberScrollState())) {
                node.rows.forEachIndexed { index, cells -> Row {
                    cells.forEach { cell -> Column(Modifier.width(160.dp).border(.5.dp, palette.hairline)
                        .background(if (index in node.headerRows) palette.subtleSurface else palette.cardSurface).padding(10.dp)) {
                        PostRichContent(cell, palette, onOpenLink, originalUrl, postId)
                    } }
                } }
            }
            is PostBlockNode.Media -> Column(Modifier.fillMaxWidth().background(palette.subtleSurface, RoundedCornerShape(10.dp)).border(0.5.dp, palette.hairline, RoundedCornerShape(10.dp)).padding(12.dp)) {
                Text(node.label, color = palette.inkPrimary, fontWeight = FontWeight.Medium)
                TextButton(onClick = { (node.url ?: originalUrl)?.let(onOpenLink) }, enabled = node.url != null || originalUrl != null) {
                    Text("播放${if (node.kind == "audio") "音频" else "媒体内容"}")
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
                    PostRichContent(node.children, palette, onOpenLink, originalUrl, postId)
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
private fun NativePostPoll(poll: PostBlockNode.Poll, palette: LinDoThemePalette, postId: Int?, originalUrl: String?, onOpenLink: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val snapshot = remember(postId, poll.name) { postId?.let { LinuxDoRepository.getPollVoteSnapshot(it, poll.name) } }
    var selected by remember(postId, poll) { mutableStateOf(snapshot?.selectedIds.orEmpty()) }
    var submitting by remember(postId, poll) { mutableStateOf(false) }
    var submitted by remember(postId, poll) { mutableStateOf(snapshot != null) }
    var optionVotes by remember(postId, poll) { mutableStateOf(snapshot?.optionVotes.orEmpty()) }
    var voters by remember(postId, poll) { mutableStateOf(snapshot?.voters) }
    var error by remember(postId, poll) { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(poll.title, fontWeight = FontWeight.SemiBold, color = palette.inkPrimary)
        poll.options.forEach { option ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(enabled = postId != null && !poll.closed && !submitting && !submitted) {
                    selected = when {
                        option.id in selected -> selected - option.id
                        !poll.multiple -> setOf(option.id)
                        selected.size < poll.max -> selected + option.id
                        else -> selected
                    }
                }.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (option.id in selected) "●" else "○", color = if (option.id in selected) palette.brandGold else palette.inkSecondary)
                Text(option.text, modifier = Modifier.weight(1f), color = palette.inkPrimary, fontSize = 14.sp)
                optionVotes[option.id]?.let { count ->
                    Text("$count 票", color = palette.inkSecondary, fontSize = 12.sp)
                }
            }
        }
        when {
            poll.closed -> Text("投票已关闭", color = palette.inkSecondary, fontSize = 12.sp)
            postId == null -> Text("投票在发布后可用", color = palette.inkSecondary, fontSize = 12.sp)
            submitted -> Text(voters?.let { "投票已提交 · 共 $it 人参与" } ?: "投票已提交", color = palette.inkSecondary, fontSize = 13.sp)
            else -> TextButton(enabled = !submitting && selected.size in poll.min..poll.max, onClick = {
                if (!UserApiKeyAuthManager.isAuthorized()) { error = "请先登录后投票"; return@TextButton }
                if (submitting) return@TextButton
                submitting = true
                error = null
                scope.launch {
                    try {
                        submitted = LinuxDoRepository.voteInPoll(postId, poll.name, selected.toList())
                        if (submitted) {
                            LinuxDoRepository.getPollVoteSnapshot(postId, poll.name)?.let { latest ->
                                selected = latest.selectedIds
                                optionVotes = latest.optionVotes
                                voters = latest.voters
                            }
                        } else error = "投票未提交，请重试"
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message ?: "投票未提交，请重试" }
                    finally { submitting = false }
                }
            }) { LinDoBusyButtonLabel(submitting, "提交中", "提交投票", palette.brandGold) }
        }
        error?.let { LinDoFeedbackRow(it, LinDoMascotState.NetworkError) }
        if (originalUrl != null) TextButton(onClick = { onOpenLink(originalUrl + if (originalUrl.contains('?')) "&web=1" else "?web=1") }) {
            Text("在原帖查看投票结果", color = palette.inkSecondary, fontSize = 12.sp)
        }
    }
}
