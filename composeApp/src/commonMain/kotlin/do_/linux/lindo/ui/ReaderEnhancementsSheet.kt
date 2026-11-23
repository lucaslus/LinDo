package do_.linux.lindo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import do_.linux.lindo.core.diagnostics.LinDoDiagnosticLogger
import do_.linux.lindo.core.model.PostItemModel
import do_.linux.lindo.core.model.PostRevisionDetail
import do_.linux.lindo.core.model.ReactionUserGroup
import do_.linux.lindo.core.network.LinuxDoRepository
import do_.linux.lindo.core.render.DiscourseAstParser
import do_.linux.lindo.core.render.PostBlockNode
import do_.linux.lindo.core.site.AuthorFeedbackSpec
import do_.linux.lindo.ui.components.LinDoIcons
import do_.linux.lindo.ui.components.SmartAvatar
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.launch

/**
 * 私信作者反馈弹窗：直接向 @jedilus 发送站内私信，支持问题分类、附带客户端版本信息与匿名化诊断摘要。
 */
@Composable
internal fun AuthorFeedbackDialog(
    onDismiss: () -> Unit,
    onSentSuccess: (topicId: Int?) -> Unit = {}
) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    var category by remember { mutableStateOf(AuthorFeedbackSpec.Category.BUG) }
    var subject by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var includeDeviceInfo by remember { mutableStateOf(true) }
    var includeDiagnostic by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = { if (!sending) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.93f)
                .widthIn(max = 520.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(palette.cardSurface)
                .border(0.5.dp, palette.hairline, RoundedCornerShape(16.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "私信作者反馈",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.inkPrimary
                    )
                    Text(
                        "通过 LINUX DO 站内私信直达 @${AuthorFeedbackSpec.AUTHOR_USERNAME}",
                        fontSize = 12.sp,
                        color = palette.inkTertiary
                    )
                }
                IconButton(onClick = { if (!sending) onDismiss() }) {
                    Icon(LinDoIcons.Close, "关闭", tint = palette.inkSecondary, modifier = Modifier.size(18.dp))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AuthorFeedbackSpec.Category.entries.forEach { item ->
                    val selected = item == category
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(if (selected) palette.activeChipBg else palette.subtleSurface)
                            .clickable(enabled = !sending) { category = item }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = item.label,
                            fontSize = 12.5.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (selected) palette.activeChipText else palette.inkSecondary
                        )
                    }
                }
            }

            OutlinedTextField(
                value = subject,
                onValueChange = { subject = it; errorMsg = null },
                enabled = !sending,
                singleLine = true,
                label = { Text("反馈主题", fontSize = 12.5.sp) },
                placeholder = { Text("简述问题或建议（至少 4 个字）", fontSize = 13.sp) },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = body,
                onValueChange = { body = it; errorMsg = null },
                enabled = !sending,
                minLines = 4,
                maxLines = 7,
                label = { Text("详细说明", fontSize = 12.5.sp) },
                placeholder = { Text("请描述复现步骤、现象或期望效果（支持 Markdown）…", fontSize = 13.sp) },
                modifier = Modifier.fillMaxWidth()
            )

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !sending) { includeDeviceInfo = !includeDeviceInfo },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = includeDeviceInfo, onCheckedChange = { includeDeviceInfo = it }, enabled = !sending)
                    Text("附带客户端版本与系统环境（便于定位）", fontSize = 12.5.sp, color = palette.inkSecondary)
                }
                Row(
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !sending) { includeDiagnostic = !includeDiagnostic },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = includeDiagnostic, onCheckedChange = { includeDiagnostic = it }, enabled = !sending)
                    Text("附带最近诊断摘要（已自动脱敏，不含 Cookie）", fontSize = 12.5.sp, color = palette.inkSecondary)
                }
            }

            if (errorMsg != null) {
                Text(errorMsg.orEmpty(), fontSize = 12.5.sp, color = Color(0xFFEF4444))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(enabled = !sending, onClick = onDismiss) {
                    Text("取消", color = palette.inkSecondary)
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = !sending && subject.trim().length >= 3 && body.trim().length >= 5,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.brandGold,
                        contentColor = palette.onAccent
                    ),
                    onClick = {
                        sending = true
                        errorMsg = null
                        scope.launch {
                            val platformName = System.getProperty("os.name").orEmpty().ifBlank { "Android" }
                            val diagSummary = if (includeDiagnostic) {
                                runCatching {
                                    LinDoDiagnosticLogger.redactSecrets(
                                        LinDoDiagnosticLogger.buildExportReport().takeLast(2000)
                                    )
                                }.getOrNull()
                            } else null
                            val (title, rawBody) = AuthorFeedbackSpec.buildPayload(
                                category = category,
                                subject = subject,
                                body = body,
                                appVersion = LinDoDiagnosticLogger.APP_VERSION_DISPLAY,
                                platformInfo = platformName,
                                includeDeviceInfo = includeDeviceInfo,
                                diagnosticSnippet = diagSummary
                            )
                            val result = runCatching {
                                LinuxDoRepository.sendPrivateMessageDetailed(
                                    targetUsernames = AuthorFeedbackSpec.AUTHOR_USERNAME,
                                    title = title,
                                    rawMarkdown = rawBody
                                )
                            }.getOrNull()
                            sending = false
                            if (result != null && result.isSuccess) {
                                onSentSuccess(result.topicId)
                                onDismiss()
                            } else {
                                errorMsg = result?.errorMessage ?: "发送未成功，请确认已登录并稍后重试"
                            }
                        }
                    }
                ) {
                    Text(if (sending) "发送中…" else "发送私信反馈", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * 楼层编辑历史弹窗 (`GET /posts/{id}/revisions/{rev|latest}.json`)
 */
@Composable
internal fun PostRevisionsDialog(
    postId: Int,
    onDismiss: () -> Unit
) {
    val palette = LocalPalette.current
    var revision by remember(postId) { mutableStateOf<Int?>(null) }
    var detail by remember(postId) { mutableStateOf<PostRevisionDetail?>(null) }
    var loading by remember(postId) { mutableStateOf(true) }
    var errorMsg by remember(postId) { mutableStateOf<String?>(null) }

    LaunchedEffect(postId, revision) {
        loading = true
        errorMsg = null
        val fetched = runCatching { LinuxDoRepository.fetchPostRevisions(postId, revision) }.getOrNull()
        if (fetched != null) {
            detail = fetched
        } else {
            errorMsg = "无法加载该楼层编辑记录（可能无查看权限或网络异常）"
        }
        loading = false
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.93f)
                .widthIn(max = 560.dp)
                .heightIn(max = 600.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(palette.cardSurface)
                .border(0.5.dp, palette.hairline, RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = detail?.let { "编辑历史 · v${it.currentRevision} / ${it.versionCount}" } ?: "编辑历史",
                    modifier = Modifier.weight(1f),
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.inkPrimary
                )
                IconButton(onClick = onDismiss) {
                    Icon(LinDoIcons.Close, "关闭", tint = palette.inkSecondary, modifier = Modifier.size(18.dp))
                }
            }

            when {
                loading -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(strokeWidth = 2.dp, color = palette.brandGold)
                }
                errorMsg != null -> Text(errorMsg.orEmpty(), color = palette.inkSecondary, fontSize = 13.sp)
                detail != null -> {
                    val rev = detail!!
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmartAvatar(username = rev.username, avatarTemplate = rev.avatarTemplate, animatedAvatar = null, size = 28.dp, enableAnimations = false)
                        Column(Modifier.weight(1f)) {
                            Text(rev.displayName?.ifBlank { rev.username } ?: rev.username, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = palette.inkPrimary)
                            Text(rev.createdAtText, fontSize = 11.5.sp, color = palette.inkTertiary)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(enabled = rev.previousRevision != null, onClick = { revision = rev.previousRevision }) {
                                Text("上一版", fontSize = 12.sp)
                            }
                            TextButton(enabled = rev.nextRevision != null, onClick = { revision = rev.nextRevision }) {
                                Text("下一版", fontSize = 12.sp)
                            }
                        }
                    }
                    if (!rev.editReason.isNullOrBlank()) {
                        Text(
                            text = "编辑原因：${rev.editReason}",
                            fontSize = 12.5.sp,
                            color = palette.activeChipText,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(palette.subtleSurface)
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                    val plainDiff = remember(rev.bodyChangesHtml, rev.titleChangesHtml) {
                        val parts = listOfNotNull(rev.titleChangesHtml, rev.bodyChangesHtml)
                            .map { DiscourseAstParser.stripHtmlTags(it).trim() }
                            .filter { it.isNotEmpty() }
                        parts.joinToString("\n\n").ifBlank { "无正文变更详情" }
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .clip(RoundedCornerShape(10.dp))
                            .background(palette.subtleSurface)
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp)
                    ) {
                        Text(plainDiff, fontSize = 13.sp, lineHeight = 20.sp, color = palette.inkPrimary)
                    }
                }
            }
        }
    }
}

/**
 * 表态成员查看弹窗 (`GET /discourse-reactions/posts/{id}/reactions-users.json`)
 */
@Composable
internal fun ReactionUsersDialog(
    postId: Int,
    onInspectUser: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalPalette.current
    var groups by remember(postId) { mutableStateOf<List<ReactionUserGroup>>(emptyList()) }
    var selectedGroupId by remember(postId) { mutableStateOf<String?>(null) }
    var loading by remember(postId) { mutableStateOf(true) }

    LaunchedEffect(postId) {
        loading = true
        val fetched = runCatching { LinuxDoRepository.fetchReactionUsers(postId) }.getOrDefault(emptyList())
        groups = fetched
        selectedGroupId = fetched.firstOrNull()?.id
        loading = false
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .widthIn(max = 460.dp)
                .heightIn(max = 480.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(palette.cardSurface)
                .border(0.5.dp, palette.hairline, RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("表态成员", modifier = Modifier.weight(1f), fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, color = palette.inkPrimary)
                IconButton(onClick = onDismiss) {
                    Icon(LinDoIcons.Close, "关闭", tint = palette.inkSecondary, modifier = Modifier.size(18.dp))
                }
            }
            when {
                loading -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(strokeWidth = 2.dp, color = palette.brandGold)
                }
                groups.isEmpty() -> Text("暂无可展示的表态成员", fontSize = 13.sp, color = palette.inkTertiary)
                else -> {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        groups.forEach { g ->
                            val active = g.id == selectedGroupId
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(if (active) palette.activeChipBg else palette.subtleSurface)
                                    .clickable { selectedGroupId = g.id }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = "${g.id} · ${g.count}",
                                    fontSize = 12.sp,
                                    color = if (active) palette.activeChipText else palette.inkSecondary,
                                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                        }
                    }
                    val activeUsers = groups.firstOrNull { it.id == selectedGroupId }?.users.orEmpty()
                    LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(activeUsers, key = { it.username }) { user ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        onDismiss()
                                        onInspectUser(user.username)
                                    }
                                    .padding(vertical = 4.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                SmartAvatar(username = user.username, avatarTemplate = user.avatarTemplate, animatedAvatar = null, size = 32.dp, enableAnimations = false)
                                Column(Modifier.weight(1f)) {
                                    Text(user.displayName?.ifBlank { user.username } ?: user.username, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = palette.inkPrimary)
                                    Text("@${user.username}", fontSize = 11.5.sp, color = palette.inkTertiary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 书签备注与自定义提醒设置弹窗 (`POST /bookmarks.json` / `PUT /bookmarks/{id}.json`)
 */
@Composable
internal fun BookmarkReminderDialog(
    post: PostItemModel,
    onDismiss: () -> Unit,
    onSaved: (bookmarkId: Int?, name: String?, reminderAt: String?) -> Unit
) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    var note by remember(post.id) { mutableStateOf(post.bookmarkName.orEmpty()) }
    var reminderChoice by remember(post.id) { mutableStateOf(0) } // 0=无, 1=1小时, 2=24小时, 3=3天
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .widthIn(max = 460.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(palette.cardSurface)
                .border(0.5.dp, palette.hairline, RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = if (post.bookmarkedByMe) "编辑书签提醒 (#${post.postNumber} 楼)" else "收藏并设置提醒 (#${post.postNumber} 楼)",
                fontSize = 15.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.inkPrimary
            )
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                enabled = !busy,
                singleLine = true,
                label = { Text("书签备注（选填）", fontSize = 12.5.sp) },
                placeholder = { Text("例如：周末回看部署配置", fontSize = 13.sp) },
                modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "不提醒", 1 to "1 小时后", 2 to "明天此时", 3 to "3 天后").forEach { (idx, label) ->
                    val selected = reminderChoice == idx
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (selected) palette.activeChipBg else palette.subtleSurface)
                            .clickable(enabled = !busy) { reminderChoice = idx }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(label, fontSize = 12.sp, color = if (selected) palette.activeChipText else palette.inkSecondary)
                    }
                }
            }
            if (errorMsg != null) {
                Text(errorMsg.orEmpty(), fontSize = 12.sp, color = Color(0xFFEF4444))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (post.bookmarkedByMe && post.bookmarkId != null) {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                val ok = runCatching { LinuxDoRepository.deleteBookmarkById(post.bookmarkId) }.getOrDefault(false)
                                busy = false
                                if (ok) {
                                    onSaved(null, null, null)
                                    onDismiss()
                                } else {
                                    errorMsg = "移除书签失败"
                                }
                            }
                        }
                    ) { Text("移除书签", color = Color(0xFFEF4444)) }
                }
                Spacer(Modifier.weight(1f))
                TextButton(enabled = !busy, onClick = onDismiss) { Text("取消", color = palette.inkSecondary) }
                Button(
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = palette.brandGold, contentColor = palette.onAccent),
                    onClick = {
                        busy = true
                        errorMsg = null
                        val reminderIso = when (reminderChoice) {
                            1 -> Instant.now().plus(1, ChronoUnit.HOURS).toString()
                            2 -> Instant.now().plus(24, ChronoUnit.HOURS).toString()
                            3 -> Instant.now().plus(3, ChronoUnit.DAYS).toString()
                            else -> null
                        }
                        scope.launch {
                            val savedId = runCatching {
                                LinuxDoRepository.saveBookmarkWithReminder(
                                    postId = post.id,
                                    name = note.trim().takeIf { it.isNotEmpty() },
                                    reminderAtIso = reminderIso,
                                    existingBookmarkId = post.bookmarkId
                                )
                            }.getOrNull()
                            busy = false
                            if (savedId != null) {
                                onSaved(savedId, note.trim().takeIf { it.isNotEmpty() }, reminderIso)
                                onDismiss()
                            } else {
                                errorMsg = "保存书签失败，请稍后重试"
                            }
                        }
                    }
                ) { Text(if (busy) "保存中…" else "保存") }
            }
        }
    }
}

/**
 * 帖子目录 (TOC) 弹窗：自动提取主贴 H1-H4 标题结构
 */
@Composable
internal fun TopicTocDialog(
    firstPostHtml: String,
    onJumpTop: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalPalette.current
    val headings = remember(firstPostHtml) {
        val nodes = DiscourseAstParser.parseCookedHtml(firstPostHtml)
        buildList {
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
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .widthIn(max = 420.dp)
                .heightIn(max = 440.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(palette.cardSurface)
                .border(0.5.dp, palette.hairline, RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("帖子目录", modifier = Modifier.weight(1f), fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, color = palette.inkPrimary)
                IconButton(onClick = onDismiss) {
                    Icon(LinDoIcons.Close, "关闭", tint = palette.inkSecondary, modifier = Modifier.size(18.dp))
                }
            }
            if (headings.isEmpty()) {
                Text("主贴未包含章节标题", fontSize = 13.sp, color = palette.inkTertiary)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(headings) { (level, title) ->
                        val indent = ((level - 1).coerceIn(0, 3) * 14).dp
                        Text(
                            text = title,
                            fontSize = if (level <= 2) 13.5.sp else 12.5.sp,
                            fontWeight = if (level <= 2) FontWeight.Medium else FontWeight.Normal,
                            color = palette.inkPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    onDismiss()
                                    onJumpTop()
                                }
                                .padding(start = indent + 6.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
                        )
                    }
                }
            }
        }
    }
}
