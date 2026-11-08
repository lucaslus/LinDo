package do_.linux.lindo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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

private const val COMPOSER_TAG_LIMIT = 5

internal val LocalCurrentAccountUsername = staticCompositionLocalOf<String?> { null }

@Composable
internal fun LinDoDialogTitle(title: String) {
    Text(title, color = LocalPalette.current.inkPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
internal fun LinDoConfirmDialog(
    title: String,
    confirmLabel: String,
    detail: String? = null,
    busy: Boolean = false,
    error: String? = null,
    destructive: Boolean = false,
    dismissLabel: String = "取消",
    alternateAction: (@Composable () -> Unit)? = null,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val palette = LocalPalette.current
    val errorColor = MaterialTheme.colorScheme.error
    val visibleError = error?.takeIf { !busy && it.isNotBlank() }
    val body: (@Composable () -> Unit)? = if (detail == null && visibleError == null) null else {
        {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                detail?.let { Text(it, color = palette.inkSecondary, fontSize = 14.sp, lineHeight = 20.sp) }
                visibleError?.let {
                    Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = errorColor, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = palette.cardSurface,
        title = { LinDoDialogTitle(title) },
        text = body,
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (destructive) errorColor else palette.inkPrimary,
                    disabledContentColor = palette.inkSecondary
                )
            ) {
                if (busy) LinDoBusyButtonLabel(true, "处理中", confirmLabel, LocalContentColor.current)
                else Text(confirmLabel, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            if (!busy) alternateAction?.invoke()
            TextButton(
                enabled = !busy,
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = palette.inkSecondary)
            ) { Text(dismissLabel) }
        }
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
    var draftSyncFailed by remember(key) { mutableStateOf(false) }
    var submitError by remember(key) { mutableStateOf<String?>(null) }
    var showExit by remember(key) { mutableStateOf(false) }
    var showDiscard by remember(key) { mutableStateOf(false) }
    var discardError by remember(key) { mutableStateOf<String?>(null) }
    var showCost by remember(key) { mutableStateOf(false) }
    var syncRetry by remember(key) { mutableIntStateOf(0) }
    var tagChoices by remember(key, draft.categoryId) { mutableStateOf(ComposerTagChoices(emptyList())) }
    var tagIds by remember(key, draft.categoryId) { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var tagQuery by remember(key, draft.categoryId) { mutableStateOf("") }
    var tagsLoading by remember(key, draft.categoryId) { mutableStateOf(false) }
    var tagsError by remember { mutableStateOf<String?>(null) }
    var tagRetry by remember { mutableIntStateOf(0) }
    var appliedTemplate by remember(key) { mutableStateOf<String?>(null) }

    var replyContextExpanded by remember(key) { mutableStateOf(false) }
    var viewMode by remember(key) { mutableStateOf(WorkbenchViewMode.SOURCE) }
    var keyboardBeforePreview by remember(key) { mutableStateOf(false) }
    val imeVisible = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
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
        draftSyncFailed = false
        submitError = null
    }
    fun requestClose() {
        if (busy) return
        if (showCategoryPicker || showTagPicker) {
            showCategoryPicker = false
            showTagPicker = false
            return
        }
        if (viewMode == WorkbenchViewMode.PREVIEW) {
            viewMode = WorkbenchViewMode.SOURCE
            return
        }
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
        delay(1200)
        try {
            draftSyncFailed = session?.sync() != true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { draftSyncFailed = true }
    }

    LaunchedEffect(draft.categoryId, draft.tags, tagQuery, tagRetry, prepared, showTagPicker) {
        if (!prepared || !showTagPicker || action != ComposerAction.NEW_TOPIC) return@LaunchedEffect
        tagsLoading = true
        tagsError = null
        try {
            if (tagQuery.isNotBlank()) delay(300)
            val selected = draft.tags.associateWith { tagIds[it] }
            tagChoices = LinuxDoRepository.fetchCategoryAllowedTags(draft.categoryId ?: initialCategoryId, tagQuery, selected)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { tagsError = error.message ?: "标签加载失败" }
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

    val titleRemaining = LinuxDoSiteRules.MIN_TOPIC_TITLE_LENGTH - draft.title.trim().length
    val titleValidation = if (prepared && action == ComposerAction.NEW_TOPIC && titleRemaining > 0) {
        "标题还差 $titleRemaining 个字"
    } else null
    val bodyRemaining = LinuxDoSiteRules.MIN_POST_RAW_LENGTH - draft.raw.trim().length
    val bodyValidation = when {
        !prepared -> null
        bodyRemaining > 0 -> "还差 $bodyRemaining 个字"
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

    LinDoFullScreenPage(
        onBack = ::requestClose,
        containerColor = palette.cardSurface
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            val headerTitle = when (action) {
                ComposerAction.NEW_TOPIC -> "新话题"
                ComposerAction.EDIT -> draft.postNumber?.takeIf { it > 1 }
                    ?.let { "编辑回复 · $it 楼" } ?: "编辑帖子"
                ComposerAction.REPLY -> if (draft.replyToPostNumber == null) "回复话题" else "回复"
            }
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(enabled = !busy, onClick = ::requestClose) {
                    Icon(LinDoIcons.ArrowLeft, "返回", tint = palette.inkPrimary, modifier = Modifier.size(20.dp))
                }
                Text(
                    headerTitle, color = palette.inkPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                if (prepared && prepareError == null) {
                    val isPreview = viewMode == WorkbenchViewMode.PREVIEW
                    TextButton(
                        enabled = !busy,
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        onClick = {
                            if (isPreview) {
                                viewMode = WorkbenchViewMode.SOURCE
                            } else {
                                keyboardBeforePreview = imeVisible
                                keyboard?.hide()
                                viewMode = WorkbenchViewMode.PREVIEW
                            }
                        }
                    ) {
                        Text(if (isPreview) "继续编辑" else "预览", color = palette.inkPrimary, maxLines = 1)
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
                            color = if (canSubmit) palette.activeChipText else palette.inkTertiary
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
                            ?: draft.replyToUsername?.takeIf { it.isNotBlank() }
                        val snippet = if (floor == null) topicTitle else replyToContentSnippet.orEmpty()
                        if (floor != null || snippet.isNotBlank()) {
                            Row(
                                Modifier.fillMaxWidth()
                                    .then(if (snippet.isNotBlank()) Modifier.clickable { replyContextExpanded = !replyContextExpanded } else Modifier)
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(9.dp)
                            ) {
                                if (floor != null && name != null) {
                                    GoogleAccountRingAvatar(
                                        letter = name, avatarUrl = replyToAvatarUrl,
                                        animatedAvatar = replyToAnimatedAvatar, avatarSize = 25.dp, strokeWidth = 0.dp
                                    )
                                }
                                Column(Modifier.weight(1f)) {
                                    if (floor != null) {
                                        Text(
                                            when {
                                                name != null -> "$name · $floor 楼"
                                                floor == 1 -> "回复楼主"
                                                else -> "回复第 $floor 楼"
                                            },
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
                                        if (showCategoryPicker) {
                                            keyboard?.hide()
                                            showTagPicker = false
                                        }
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

                            if (draft.tags.size < COMPOSER_TAG_LIMIT) {
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

                        if (showCategoryPicker) {
                            ComposerCategoryPicker(
                                categories = LinuxDoRepository.categories,
                                selectedId = activeCat?.id,
                                enabled = !busy,
                                onSelect = { category ->
                                    if (draft.categoryId != category.id) {
                                        val next = draft.copy(categoryId = category.id, tags = emptyList())
                                        if (isUntouchedTemplate) draft = next.copy(updatedAt = System.currentTimeMillis())
                                        else update(next)
                                    }
                                    showCategoryPicker = false
                                },
                                onDismiss = { showCategoryPicker = false }
                            )
                            HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
                        } else if (showTagPicker) {
                            ComposerTagPicker(
                                query = tagQuery,
                                onQueryChange = { tagQuery = it },
                                selected = draft.tags,
                                choices = tagChoices,
                                limit = COMPOSER_TAG_LIMIT,
                                loading = tagsLoading,
                                error = tagsError,
                                enabled = !busy,
                                imeVisible = imeVisible,
                                onToggle = { option ->
                                    if (option.name in draft.tags) {
                                        update(draft.copy(tags = draft.tags - option.name))
                                    } else if (draft.tags.size < COMPOSER_TAG_LIMIT && !option.disabled) {
                                        option.id?.let { tagIds = tagIds + (option.name to it) }
                                        update(draft.copy(tags = draft.tags + option.name))
                                        tagQuery = ""
                                    }
                                },
                                onRetry = { tagRetry++ },
                                onDismiss = { keyboard?.hide(); showTagPicker = false }
                            )
                            HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
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

                    if (draftSyncFailed && submitError == null && !busy) {
                        LinDoLoadFailure("草稿已存本机 · 云端同步失败") { syncRetry++ }
                    }
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
                        restoreKeyboardFromPreview = keyboardBeforePreview,
                        validationHint = bodyValidation,
                        fillAvailableHeight = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }

    if (showExit) LinDoConfirmDialog(
        title = "要退出编辑吗？",
        confirmLabel = "保存并退出",
        dismissLabel = "继续编辑",
        alternateAction = {
            TextButton(onClick = { showExit = false; discardError = null; showDiscard = true }) {
                Text("丢弃草稿", color = MaterialTheme.colorScheme.error)
            }
        },
        onDismiss = { showExit = false },
        onConfirm = { session?.update(draft); onDismiss() }
    )
    if (showDiscard) LinDoConfirmDialog(
        title = "要丢弃这份草稿吗？",
        confirmLabel = "丢弃",
        busy = submitting,
        error = discardError,
        destructive = true,
        onDismiss = { showDiscard = false },
        onConfirm = {
            if (submitting) return@LinDoConfirmDialog
            submitting = true
            scope.launch {
                try {
                    if (session?.discard() == true) onDismiss() else discardError = "草稿已更新，请重新打开后核对"
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    discardError = error.message ?: "丢弃失败"
                } finally {
                    submitting = false
                }
            }
        }
    )
    if (showCost) LinDoConfirmDialog(
        title = "要发布这个抽奖话题吗？",
        confirmLabel = "发布",
        detail = "将扣除 ${LinuxDoSiteRules.LOTTERY_TOPIC_COST_POINTS} 社区积分",
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
            LinDoInputDecoration(
                empty = value.isEmpty(),
                placeholder = placeholder,
                textStyle = style,
                placeholderColor = palette.inkSecondary,
                modifier = Modifier.fillMaxWidth().padding(vertical = if (title) 8.dp else 6.dp),
                alignment = Alignment.TopStart,
                innerTextField = inner
            )
        }
    )
}
