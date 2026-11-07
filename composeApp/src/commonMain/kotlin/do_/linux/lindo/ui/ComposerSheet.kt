package do_.linux.lindo.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import do_.linux.lindo.core.compose.*
import do_.linux.lindo.core.model.PostItemModel
import do_.linux.lindo.core.network.*
import do_.linux.lindo.core.site.LinuxDoSiteRules
import do_.linux.lindo.ui.components.LinDoIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class ComposerPublished(val post: PostItemModel? = null, val topicId: Int = 0, val title: String = "")

internal val LocalCurrentAccountUsername = staticCompositionLocalOf<String?> { null }

@Composable
internal fun LinDoConfirmDialog(
    title: String, message: String, confirmLabel: String, busy: Boolean = false,
    error: String? = null, destructive: Boolean = true, onDismiss: () -> Unit, onConfirm: () -> Unit
) {
    val palette = LocalPalette.current
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() }, containerColor = palette.cardSurface,
        title = { Text(title, color = palette.inkPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(message, color = palette.inkSecondary, fontSize = 14.sp, lineHeight = 21.sp)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
        } },
        confirmButton = { TextButton(enabled = !busy, onClick = onConfirm) {
            LinDoBusyButtonLabel(busy, "处理中", confirmLabel, if (destructive) MaterialTheme.colorScheme.error else palette.inkPrimary)
        } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消", color = palette.inkSecondary) } }
    )
}

/**
 * 全新设计的旗舰级移动端创作工作台 (`ComposerSheet`)：
 * 1. 顶栏一体化：左侧关闭 + 标题/上下文副标题 + 右侧「编辑 | 预览」胶囊切换 + 醒目「发布」主按钮；
 * 2. 新建话题元数据精简为单行胶囊栏（`● 版块 ▾` + `#标签`），按需展开网格选择器，告别多行杂乱堆叠；
 * 3. 中部 `Modifier.weight(1f)` 无边框全画布沉浸式书写区，随软键盘弹性伸缩；
 * 4. 底部工具栏常驻吸附在软键盘上方，绝不因外层滚动而消失。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ComposerSheet(
    action: ComposerAction,
    topicId: Int? = null,
    topicTitle: String = "",
    replyToPostNumber: Int? = null,
    replyToUsername: String? = null,
    replyToDisplayName: String? = null,
    replyToAvatarUrl: String? = null,
    replyToAnimatedAvatar: String? = null,
    replyToContentSnippet: String? = null,
    post: PostItemModel? = null,
    accountUsername: String? = null,
    initialRaw: String = "",
    initialCategoryId: Int = 4,
    initialDraft: CloudDraftItem? = null,
    onDismiss: () -> Unit,
    onPublished: (ComposerPublished) -> Unit
) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val key = when (action) {
        ComposerAction.NEW_TOPIC -> "new_topic"
        ComposerAction.REPLY -> "topic_$topicId"
        ComposerAction.EDIT -> initialDraft?.draftKey ?: "edit_post_${post?.id}"
    }
    var draft by remember(key) { mutableStateOf(ComposerDraft("", key, action)) }
    var session by remember(key) { mutableStateOf<ComposerDraftSession?>(null) }
    var originalRaw by remember(key) { mutableStateOf("") }
    var prepared by remember(key) { mutableStateOf(false) }
    var prepareError by remember(key) { mutableStateOf<String?>(null) }
    var prepareRetry by remember(key) { mutableIntStateOf(0) }
    var submitting by remember(key) { mutableStateOf(false) }
    var uploading by remember(key) { mutableStateOf(false) }
    var syncing by remember(key) { mutableStateOf(false) }
    var synced by remember(key) { mutableStateOf(false) }
    var syncError by remember(key) { mutableStateOf<String?>(null) }
    var submitError by remember(key) { mutableStateOf<String?>(null) }
    var showExit by remember(key) { mutableStateOf(false) }
    var showDiscard by remember(key) { mutableStateOf(false) }
    var showCost by remember(key) { mutableStateOf(false) }
    var syncRetry by remember(key) { mutableIntStateOf(0) }
    var availableTags by remember(draft.categoryId) { mutableStateOf(emptyList<String>()) }
    var tagQuery by remember { mutableStateOf("") }
    var tagsLoading by remember { mutableStateOf(false) }
    var tagsError by remember { mutableStateOf<String?>(null) }
    var tagRetry by remember { mutableIntStateOf(0) }
    var appliedTemplate by remember(key) { mutableStateOf<String?>(null) }

    var replyContextExpanded by remember(key) { mutableStateOf(false) }
    var viewMode by remember(key) { mutableStateOf(WorkbenchViewMode.SOURCE) }
    var showCategoryPicker by remember(key) { mutableStateOf(false) }
    var showTagPicker by remember(key) { mutableStateOf(false) }

    val busy = submitting || uploading
    val isUntouchedTemplate = action == ComposerAction.NEW_TOPIC &&
        appliedTemplate != null &&
        draft.raw.trim() == appliedTemplate!!.trim() &&
        draft.title.isBlank() &&
        draft.tags.isEmpty()
    val hasChanges = prepared && !isUntouchedTemplate &&
        if (action == ComposerAction.EDIT) draft.raw != originalRaw else draft.hasContent

    fun update(next: ComposerDraft) {
        if (submitting || !prepared) return
        draft = next.copy(updatedAt = System.currentTimeMillis())
        session?.update(draft)
        synced = false
        syncError = null
        submitError = null
    }
    fun requestClose() {
        if (busy) return
        if (hasChanges) showExit = true else onDismiss()
    }

    LaunchedEffect(key, prepareRetry) {
        prepared = false
        prepareError = null
        try {
            val owner = accountUsername ?: UserApiKeyAuthManager.currentUser?.username
                ?: LinuxDoRepository.fetchCurrentUserProfile()?.username
                ?: throw ApiMutationException("暂时无法读取账号，请重试")
            val editingId = post?.id ?: initialDraft?.postId
            val sourcePost = if (action == ComposerAction.EDIT) {
                post?.takeIf { it.raw != null } ?: editingId?.let { LinuxDoRepository.fetchPostForEditing(it) }
                    ?: throw ApiMutationException("暂时无法读取原文，请重试后编辑")
            } else null
            originalRaw = sourcePost?.raw ?: ""
            val seed = ComposerDraft(owner, key, action,
                raw = initialDraft?.raw ?: if (action == ComposerAction.EDIT) originalRaw else initialRaw,
                title = if (action == ComposerAction.NEW_TOPIC) initialDraft?.title?.takeUnless { it.startsWith("未命名") }.orEmpty() else topicTitle,
                categoryId = initialDraft?.categoryId ?: initialCategoryId,
                tags = initialDraft?.tags.orEmpty(), topicId = topicId?.takeIf { it > 0 } ?: sourcePost?.topicId?.takeIf { it > 0 } ?: initialDraft?.topicId?.takeIf { it > 0 },
                postId = editingId, postNumber = post?.postNumber ?: sourcePost?.postNumber,
                replyToPostNumber = initialDraft?.replyToPostNumber ?: replyToPostNumber,
                replyToUsername = replyToUsername, sequence = initialDraft?.sequence?.takeIf { initialDraft.sequenceKnown })
            val local = LocalComposerDrafts.get(owner, key)
            draft = (local ?: seed).let { base ->
                if (action == ComposerAction.REPLY) {
                    val mergedRaw = if (initialRaw.isNotBlank() && !base.raw.contains(initialRaw)) {
                        (base.raw + "\n\n" + initialRaw).trim('\n')
                    } else base.raw
                    val targetNum = if (initialDraft != null) base.replyToPostNumber else replyToPostNumber
                    val targetUser = if (initialDraft != null) base.replyToUsername else replyToUsername
                    base.copy(raw = mergedRaw, replyToPostNumber = targetNum, replyToUsername = targetUser)
                } else if (action == ComposerAction.EDIT) {
                    base.copy(postNumber = seed.postNumber ?: base.postNumber)
                } else base
            }
            session = ComposerDraftSession(draft)
            prepared = true
            if (draft.hasContent && (action != ComposerAction.EDIT || draft.raw != originalRaw)) session?.update(draft)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { prepareError = error.message ?: "暂时无法打开编辑器，请重试" }
    }

    LaunchedEffect(draft, prepared, syncRetry) {
        if (!prepared || !hasChanges) return@LaunchedEffect
        synced = false
        delay(1200)
        syncing = true
        try { synced = session?.sync() == true; if (synced) syncError = null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { syncError = error.message ?: "云端同步失败，内容已保存在本机" }
        finally { syncing = false }
    }

    LaunchedEffect(draft.categoryId, tagQuery, tagRetry, prepared, showTagPicker) {
        if (!prepared || !showTagPicker || action != ComposerAction.NEW_TOPIC) return@LaunchedEffect
        tagsLoading = true
        tagsError = null
        try {
            if (tagQuery.isNotBlank()) delay(300)
            availableTags = LinuxDoRepository.fetchCategoryAllowedTags(draft.categoryId ?: initialCategoryId, tagQuery)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { availableTags = emptyList(); tagsError = error.message ?: "标签加载失败" }
        finally { tagsLoading = false }
    }

    LaunchedEffect(draft.categoryId, prepared) {
        if (!prepared || action != ComposerAction.NEW_TOPIC) return@LaunchedEffect
        val category = draft.categoryId ?: return@LaunchedEffect
        val canReplaceWithTemplate = draft.raw.isBlank() || (appliedTemplate != null && draft.raw.trim() == appliedTemplate!!.trim())
        if (!canReplaceWithTemplate) return@LaunchedEffect
        val template = LinuxDoRepository.fetchCategoryTopicTemplate(category).orEmpty()
        if (draft.categoryId == category && (draft.raw.isBlank() || (appliedTemplate != null && draft.raw.trim() == appliedTemplate!!.trim()))) {
            appliedTemplate = template.takeIf { it.isNotBlank() }
            draft = draft.copy(raw = template, updatedAt = System.currentTimeMillis())
        }
    }

    val titleValidation = if (prepared && action == ComposerAction.NEW_TOPIC &&
        draft.title.trim().length < LinuxDoSiteRules.MIN_TOPIC_TITLE_LENGTH
    ) "标题至少 ${LinuxDoSiteRules.MIN_TOPIC_TITLE_LENGTH} 个字" else null
    val bodyValidation = when {
        !prepared -> null
        draft.raw.trim().length < LinuxDoSiteRules.MIN_POST_RAW_LENGTH -> "正文至少 ${LinuxDoSiteRules.MIN_POST_RAW_LENGTH} 个字"
        action == ComposerAction.EDIT && !hasChanges -> "内容尚未修改"
        else -> null
    }
    val focusBodyInitially = remember(prepared) { action != ComposerAction.NEW_TOPIC || draft.title.isNotBlank() }
    val canSubmit = prepared && !busy && titleValidation == null && bodyValidation == null

    fun submit() {
        if (!canSubmit || submitting) return
        submitting = true
        submitError = null
        val snapshot = draft
        scope.launch {
            try {
                val result = session?.publish {
                    when (action) {
                        ComposerAction.NEW_TOPIC -> LinuxDoRepository.createTopic(snapshot.title, snapshot.raw, snapshot.categoryId ?: initialCategoryId, snapshot.tags)
                            ?.let { ComposerPublished(topicId = it, title = snapshot.title.trim()) }
                        ComposerAction.REPLY -> LinuxDoRepository.createPost(snapshot.topicId ?: throw ApiMutationException("话题信息不完整，请重新打开"), snapshot.raw, snapshot.replyToPostNumber)
                            ?.let { ComposerPublished(post = it.copy(raw = snapshot.raw, topicId = snapshot.topicId ?: 0)) }
                        ComposerAction.EDIT -> LinuxDoRepository.updatePost(snapshot.postId ?: throw ApiMutationException("帖子信息不完整，请重新打开"), snapshot.raw)
                            ?.let { ComposerPublished(post = it.copy(raw = snapshot.raw, topicId = snapshot.topicId ?: 0)) }
                    }
                }
                if (result != null) onPublished(result) else submitError = "未能确认提交结果，内容已保留，请稍后核对"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { submitError = error.message ?: "提交失败，内容已保留，请重试" }
            finally { submitting = false }
        }
    }

    val draftStatusLabel = when {
        uploading -> "上传附件中…"
        syncing -> "正在同步云端…"
        syncError != null -> "已存本机 · 点击重试同步"
        synced -> "云端草稿已同步"
        hasChanges -> "草稿已存本机"
        else -> null
    }

    LinDoFullScreenPage(
        onBack = ::requestClose,
        containerColor = palette.cardSurface
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            val headerTitle = when (action) {
                ComposerAction.NEW_TOPIC -> "新话题"
                ComposerAction.EDIT -> if ((draft.postNumber ?: 1) > 1) "编辑回复" else "编辑帖子"
                ComposerAction.REPLY -> if (draft.replyToPostNumber == null) "回复话题" else "回复"
            }
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(enabled = !busy, onClick = ::requestClose) {
                    Icon(LinDoIcons.ArrowLeft, "返回", tint = palette.inkPrimary, modifier = Modifier.size(20.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        headerTitle, color = palette.inkPrimary, fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    val floorLabel = draft.postNumber
                        ?.takeIf { action == ComposerAction.EDIT && it > 1 }
                        ?.let { "${it} 楼" }
                    val status = listOfNotNull(floorLabel, draftStatusLabel).joinToString(" · ")
                    if (status.isNotBlank()) {
                        Text(
                            status,
                            modifier = if (syncError != null && !busy) Modifier.clickable { syncRetry++ } else Modifier,
                            color = if (syncError != null) MaterialTheme.colorScheme.error else palette.inkSecondary,
                            fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (prepared && prepareError == null) {
                    val isPreview = viewMode == WorkbenchViewMode.PREVIEW
                    TextButton(
                        enabled = !busy,
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        onClick = {
                            viewMode = if (isPreview) WorkbenchViewMode.SOURCE else WorkbenchViewMode.PREVIEW
                            if (!isPreview) keyboard?.hide()
                        }
                    ) {
                        Text(if (isPreview) "继续编辑" else "预览", color = palette.inkSecondary, maxLines = 1)
                    }
                }
                val actionLabel = when (action) {
                    ComposerAction.NEW_TOPIC -> "发布"
                    ComposerAction.REPLY -> "发送"
                    ComposerAction.EDIT -> "保存"
                }
                TextButton(
                    enabled = canSubmit,
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    onClick = {
                        if (action == ComposerAction.NEW_TOPIC && LinuxDoSiteRules.requiresTopicCostConfirmation(draft.tags)) {
                            showCost = true
                        } else submit()
                    }
                ) {
                    if (submitting) {
                        LinDoMascot(state = LinDoMascotState.Loading, compact = true, size = 18.dp)
                    } else {
                        Text(
                            actionLabel, fontWeight = FontWeight.Bold, maxLines = 1,
                            color = if (canSubmit) palette.accent else palette.inkTertiary.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            HorizontalDivider(color = palette.hairline, thickness = 0.5.dp)

            when {
                prepareError != null -> {
                    Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        LinDoLoadFailure(prepareError!!, { prepareRetry++ })
                    }
                }
                !prepared -> {
                    Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        LinDoLoadingIndicator(label = "加载中")
                    }
                }
                else -> {
                    if (action == ComposerAction.REPLY) {
                        val floor = draft.replyToPostNumber
                        val name = replyToDisplayName?.takeIf { it.isNotBlank() }
                            ?: draft.replyToUsername?.takeIf { it.isNotBlank() } ?: "楼主"
                        val snippet = if (floor == null) topicTitle else replyToContentSnippet.orEmpty()
                        if (floor != null || snippet.isNotBlank()) {
                            Row(
                                Modifier.fillMaxWidth()
                                    .then(if (snippet.isNotBlank()) Modifier.clickable { replyContextExpanded = !replyContextExpanded } else Modifier)
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(9.dp)
                            ) {
                                if (floor != null) {
                                    GoogleAccountRingAvatar(
                                        letter = name, avatarUrl = replyToAvatarUrl,
                                        animatedAvatar = replyToAnimatedAvatar, avatarSize = 25.dp, strokeWidth = 0.dp
                                    )
                                }
                                Column(Modifier.weight(1f)) {
                                    if (floor != null) {
                                        Text(
                                            "回复给 $name · ${floor} 楼",
                                            color = palette.inkPrimary, fontWeight = FontWeight.Medium,
                                            fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (snippet.isNotBlank()) {
                                        Text(
                                            snippet, color = palette.inkSecondary, fontSize = 12.sp, lineHeight = 18.sp,
                                            maxLines = if (replyContextExpanded) 5 else 1, overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                if (snippet.isNotBlank()) {
                                    Icon(
                                        if (replyContextExpanded) LinDoIcons.ChevronUp else LinDoIcons.ChevronDown,
                                        if (replyContextExpanded) "收起原文" else "展开原文",
                                        tint = palette.inkSecondary, modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                            HorizontalDivider(color = palette.hairline, thickness = 0.5.dp)
                        }
                    }

                    if (action == ComposerAction.NEW_TOPIC) {
                        val activeCat = LinuxDoRepository.categories.firstOrNull { it.id == draft.categoryId }
                            ?: LinuxDoRepository.categories.firstOrNull()

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .clickable(enabled = !busy) {
                                        showCategoryPicker = !showCategoryPicker
                                        if (showCategoryPicker) showTagPicker = false
                                    }
                                    .padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .background(
                                            LinuxDoSiteRules.parseHexColor(activeCat?.colorHex ?: "0E76BD"),
                                            CircleShape
                                        )
                                )
                                Text(
                                    text = activeCat?.name ?: "版块",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = palette.inkPrimary
                                )
                                Icon(
                                    imageVector = if (showCategoryPicker) LinDoIcons.ChevronUp else LinDoIcons.ChevronDown,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.5.dp),
                                    tint = palette.inkSecondary
                                )
                            }

                            draft.tags.forEach { tag ->
                                Row(
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .clickable(enabled = !busy) {
                                            update(draft.copy(tags = draft.tags - tag))
                                        }
                                        .padding(horizontal = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "#$tag",
                                        fontSize = 11.8.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = palette.brandGold
                                    )
                                    Icon(
                                        imageVector = LinDoIcons.Close,
                                        contentDescription = "移除标签",
                                        modifier = Modifier.size(11.dp),
                                        tint = palette.inkSecondary
                                    )
                                }
                            }

                            if (draft.tags.size < 5) {
                                Row(
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .clickable(enabled = !busy) {
                                            showTagPicker = !showTagPicker
                                            if (showTagPicker) showCategoryPicker = false
                                        }
                                        .padding(horizontal = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Icon(
                                        imageVector = LinDoIcons.Plus,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp),
                                        tint = if (showTagPicker) palette.brandGold else palette.inkSecondary
                                    )
                                    Text(
                                        text = "添加标签",
                                        fontSize = 11.8.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (showTagPicker) palette.brandGold else palette.inkSecondary
                                    )
                                }
                            }
                        }

                        // 按需展开：版块选择面板
                        AnimatedVisibility(visible = showCategoryPicker) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 4.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(palette.subtleSurface)
                                    .border(0.6.dp, palette.hairline, RoundedCornerShape(12.dp))
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "版块",
                                        fontSize = 11.8.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = palette.inkSecondary
                                    )
                                    Box(
                                        modifier = Modifier
                                            .size(22.dp)
                                            .clip(CircleShape)
                                            .clickable { showCategoryPicker = false },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = LinDoIcons.Close,
                                            contentDescription = "关闭",
                                            modifier = Modifier.size(13.dp),
                                            tint = palette.inkTertiary
                                        )
                                    }
                                }
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                                    verticalArrangement = Arrangement.spacedBy(7.dp)
                                ) {
                                    LinuxDoRepository.categories.forEach { cat ->
                                        val isCurrent = draft.categoryId == cat.id
                                        Row(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(if (isCurrent) palette.activeChipBg else palette.cardSurface)
                                                .border(
                                                    width = 0.6.dp,
                                                    color = if (isCurrent) palette.brandGold else palette.hairline,
                                                    shape = RoundedCornerShape(10.dp)
                                                )
                                                .clickable(enabled = !busy) {
                                                    if (draft.categoryId != cat.id) {
                                                        tagQuery = ""
                                                        if (isUntouchedTemplate) {
                                                            draft = draft.copy(
                                                                categoryId = cat.id,
                                                                tags = emptyList(),
                                                                updatedAt = System.currentTimeMillis()
                                                            )
                                                        } else {
                                                            update(draft.copy(categoryId = cat.id, tags = emptyList()))
                                                        }
                                                    }
                                                    showCategoryPicker = false
                                                }
                                                .padding(horizontal = 10.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(7.dp)
                                                    .background(LinuxDoSiteRules.parseHexColor(cat.colorHex), CircleShape)
                                            )
                                            Text(
                                                text = cat.name,
                                                fontSize = 12.2.sp,
                                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                                color = if (isCurrent) palette.brandGold else palette.inkPrimary
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 无边框大字号话题标题输入栏
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        ) {
                            ComposerTextInput(
                                value = draft.title,
                                onChange = { update(draft.copy(title = it)) },
                                placeholder = "话题标题",
                                enabled = !busy,
                                title = true,
                                autofocus = !focusBodyInitially
                            )
                            if (titleValidation != null && (draft.title.isNotBlank() || draft.raw.isNotBlank())) {
                                Text(
                                    titleValidation, fontSize = 11.5.sp, color = palette.inkSecondary,
                                    modifier = Modifier.padding(bottom = 6.dp)
                                )
                            }
                        }
                        HorizontalDivider(
                            color = palette.hairline,
                            thickness = 0.5.dp
                        )
                    }

                    // ==================== 3. 错误与校验提示条（按需展示） ====================
                    if (submitError != null) {
                        Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                            LinDoFeedbackRow(submitError!!, LinDoMascotState.NetworkError)
                        }
                    }

                    // ==================== 4. 顶部 Action Tools Bar + 弹性全高创作画布 ====================
                    LinDoMarkdownWorkbench(
                        rawValue = draft.raw,
                        onRawValueChange = { update(draft.copy(raw = it)) },
                        placeholder = when (action) {
                            ComposerAction.NEW_TOPIC -> "写下正文"
                            ComposerAction.EDIT -> "修改正文"
                            ComposerAction.REPLY -> "写下回复"
                        },
                        enabled = !submitting,
                        autofocus = focusBodyInitially,
                        onUploadingChange = { uploading = it },
                        externalViewMode = viewMode,
                        validationHint = bodyValidation,
                        fillAvailableHeight = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }

    if (showTagPicker) AlertDialog(
        onDismissRequest = { showTagPicker = false },
        containerColor = palette.cardSurface,
        title = { Text("选择标签", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ComposerTextInput(tagQuery, { tagQuery = it }, "搜索标签", enabled = !busy, autofocus = true)
                Text("已选 ${draft.tags.size} / 5", fontSize = 12.sp, color = palette.inkSecondary)
                val tagError = tagsError
                when {
                    tagsLoading -> LinDoLoadingIndicator(label = "加载标签", compactInline = true)
                    tagError != null -> LinDoLoadFailure("标签暂时加载失败", { tagRetry++ })
                    availableTags.isEmpty() -> Text(
                        if (tagQuery.isBlank()) "这个版块暂无可用标签" else "没有匹配的标签",
                        fontSize = 13.sp, color = palette.inkSecondary
                    )
                    else -> Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                        (draft.tags + availableTags).distinct().forEach { tag ->
                            val selected = tag in draft.tags
                            val toggle = {
                                val next = if (selected) draft.tags - tag else draft.tags + tag
                                update(draft.copy(tags = next))
                            }
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .clickable(enabled = !busy && (selected || draft.tags.size < 5), onClick = toggle),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("#$tag", color = palette.inkPrimary, modifier = Modifier.weight(1f))
                                if (selected) Icon(
                                    LinDoIcons.Check, "已选", tint = palette.accent, modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showTagPicker = false }) { Text("完成") } }
    )

    if (showExit) AlertDialog(
        onDismissRequest = { showExit = false },
        containerColor = palette.cardSurface,
        title = { Text("保留草稿？", color = palette.inkPrimary, fontSize = 18.sp) },
        text = { Text("内容已保存在本机，你可以稍后继续编辑。", color = palette.inkSecondary) },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { session?.update(draft); onDismiss() }) {
                    Text("保留草稿并退出", color = palette.inkPrimary)
                }
                TextButton(onClick = { showExit = false; showDiscard = true }) {
                    Text("丢弃草稿", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { showExit = false }) {
                Text("继续编辑", color = palette.inkSecondary)
            }
        }
    )
    if (showDiscard) LinDoConfirmDialog(
        "丢弃草稿？",
        "当前草稿将被删除，已发布的内容不会受影响。",
        "确认丢弃",
        busy = submitting,
        error = submitError,
        onDismiss = { showDiscard = false },
        onConfirm = {
            if (submitting) return@LinDoConfirmDialog
            submitting = true
            scope.launch {
                try {
                    if (session?.discard() == true) onDismiss() else submitError = "草稿版本已更新，请重新核对后删除"
                } catch (error: Exception) {
                    submitError = error.message ?: "删除失败，请重试"
                } finally {
                    submitting = false
                }
            }
        }
    )
    if (showCost) LinDoConfirmDialog(
        "确认发布抽奖话题？",
        "发布将扣除 ${LinuxDoSiteRules.LOTTERY_TOPIC_COST_POINTS} 社区积分。",
        "确认发布",
        destructive = false,
        onDismiss = { showCost = false },
        onConfirm = { showCost = false; submit() }
    )
}

@Composable
internal fun ComposerTextInput(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean,
    title: Boolean = false,
    autofocus: Boolean = false
) {
    val palette = LocalPalette.current
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(autofocus) {
        if (autofocus) {
            delay(120)
            runCatching { focus.requestFocus() }
        }
    }
    val style = TextStyle(
        color = palette.inkPrimary,
        fontSize = if (title) 17.5.sp else 13.sp,
        lineHeight = if (title) 24.sp else 18.sp,
        fontWeight = if (title) FontWeight.Bold else FontWeight.Normal
    )
    BasicTextField(
        value = value,
        onValueChange = { next -> onChange(if (title) next.replace(Regex("[\\r\\n]+"), " ") else next) },
        enabled = enabled,
        singleLine = !title,
        maxLines = if (title) 3 else 1,
        textStyle = style,
        cursorBrush = SolidColor(palette.brandGold),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (title) 44.dp else 36.dp)
            .then(Modifier.focusRequester(focus)),
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = if (title) 8.dp else 6.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = style.copy(color = palette.inkTertiary, fontWeight = FontWeight.Normal),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                inner()
            }
        }
    )
}
