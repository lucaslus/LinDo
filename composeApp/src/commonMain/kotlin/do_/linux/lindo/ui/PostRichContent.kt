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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import do_.linux.lindo.core.render.*

@Composable
internal fun RichPostText(rich: RichText, palette: LinDoThemePalette,
    onOpenLink: (String) -> Unit, heading: Boolean = false) {
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
            val width = if (failed) run.text.length.coerceIn(2, 12).toFloat() else 1.2f
            "emoji-$index" to InlineTextContent(Placeholder(width.em, 1.2.em, PlaceholderVerticalAlign.TextCenter)) {
                if (failed) Text(run.text, fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                else AsyncImage(url, "表情：${run.text}", modifier = Modifier.fillMaxSize(), onError = { failedEmoji[url] = true })
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
    onOpenLink: (String) -> Unit, originalUrl: String? = null) {
    val clipboard = LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        for (node in nodes) when (node) {
            is PostBlockNode.Paragraph -> RichPostText(node.rich, palette, onOpenLink)
            is PostBlockNode.Heading -> RichPostText(node.rich, palette, onOpenLink, heading = true)
            is PostBlockNode.QuoteBlock -> Column(Modifier.fillMaxWidth().background(palette.subtleSurface, RoundedCornerShape(8.dp)).padding(12.dp)) {
                node.username?.let { Text("@$it", color = palette.inkSecondary, fontSize = 12.sp) }
                PostRichContent(node.children.ifEmpty { listOf(PostBlockNode.Paragraph(node.quotedText)) }, palette, onOpenLink, originalUrl)
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
                        Box(Modifier.weight(1f)) { PostRichContent(node.itemBlocks.getOrNull(index) ?: listOf(PostBlockNode.Paragraph(text)), palette, onOpenLink, originalUrl) }
                    }
                }
            }
            is PostBlockNode.DetailsSpoilerBlock -> {
                var expanded by remember(node) { mutableStateOf(false) }
                Column(Modifier.fillMaxWidth().background(palette.subtleSurface, RoundedCornerShape(8.dp)).padding(8.dp)) {
                    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("${if (expanded) "▾" else "▸"} ${node.title}", color = palette.inkPrimary)
                    }
                    if (expanded) PostRichContent(node.children.ifEmpty { listOf(PostBlockNode.Paragraph(node.innerText)) }, palette, onOpenLink, originalUrl)
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
                        PostRichContent(cell, palette, onOpenLink, originalUrl)
                    } }
                } }
            }
            is PostBlockNode.Media -> Column(Modifier.fillMaxWidth().background(palette.subtleSurface, RoundedCornerShape(8.dp)).padding(12.dp)) {
                Text(node.label, color = palette.inkPrimary)
                TextButton(onClick = { (node.url ?: originalUrl)?.let(onOpenLink) }, enabled = node.url != null || originalUrl != null) { Text("打开${if (node.kind == "audio") "音频" else "视频或网页"}") }
            }
            is PostBlockNode.Unsupported -> Column(Modifier.fillMaxWidth().background(palette.subtleSurface, RoundedCornerShape(8.dp)).padding(12.dp)) {
                Text(node.label, color = palette.inkPrimary, fontWeight = FontWeight.SemiBold)
                if (node.text.isNotBlank()) Text(node.text, color = palette.inkSecondary, fontSize = 14.sp)
                if (originalUrl != null) TextButton(onClick = { onOpenLink(originalUrl) }) { Text("在原网页查看完整内容") }
                else Text("此互动内容需在发布后的原网页查看", color = palette.inkSecondary, fontSize = 12.sp)
            }
            PostBlockNode.Divider -> HorizontalDivider(color = palette.hairline)
        }
    }
}
