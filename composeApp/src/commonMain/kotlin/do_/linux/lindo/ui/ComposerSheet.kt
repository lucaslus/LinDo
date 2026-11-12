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
import androidx.compose.ui.input.pointer.pointerHoverIcon
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
import do_.linux.lindo.core.copy.LinDoCommonCopy
import do_.linux.lindo.core.copy.LinDoComposerCopy
import do_.linux.lindo.core.copy.LinDoFeedCopy
import do_.linux.lindo.core.copy.LinDoNetworkCopy
import do_.linux.lindo.core.copy.LinDoReaderCopy
import do_.linux.lindo.core.copy.isSiteVerificationError
import do_.linux.lindo.core.model.PostItemModel
import do_.linux.lindo.core.network.*
import do_.linux.lindo.core.site.LinuxDoSiteRules
import do_.linux.lindo.ui.components.LinDoIcons
import do_.linux.lindo.ui.components.LocalLinDoNetworkRestoreVersion
import do_.linux.lindo.ui.components.LocalOpenInAppWebUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal typealias TopicPost = PostItemModel

internal data class ComposerPublished(
    val topicId: Int? = null,
    val title: String? = null,
    val post: TopicPost? = null,
    val updatedCategoryId: Int? = null,
    val updatedTags: List<String>? = null
)

private const val COMPOSER_TAG_LIMIT = 5

private enum class ActiveComposerPicker {
    NONE,
    CATEGORY,
    TAG
}

internal val LocalCurrentAccountUsername = staticCompositionLocalOf<String?> { null }

@Composable
internal fun LinDoDialogTitle(title: String) {
    Text(title, color = LocalPalette.current.inkPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
}

@Composable
internal fun LinDoConfirmDialog(
    title: String,
    confirmLabel: String,
    detail: String? = null,
    busy: Boolean = false,
    error: String? = null,
    destructive: Boolean = false,
    dismissLabel: String = LinDoCommonCopy.CANCEL.text,
    alternateAction: (@Composable () -> Unit)? = null,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val palette = LocalPalette.current
    if (do_.linux.lindo.ui.components.LocalIsWebModalActive.current) return
    val openWebUrl = LocalOpenInAppWebUrl.current
    val errorColor = MaterialTheme.colorScheme.error
    val visibleError = error?.takeIf { !busy && it.isNotBlank() }
    val needsVerify = visibleError.isSiteVerificationError() && openWebUrl != null
    val body: (@Composable () -> Unit)? = if (detail == null && visibleError == null) null else {
        {
            LinDoScaledPopupContent {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    detail?.let { Text(it, color = palette.inkSecondary, fontSize = 14.sp, lineHeight = 20.sp) }
                    visibleError?.let {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                it,
                                Modifier.weight(1f, fill = false).semantics { liveRegion = LiveRegionMode.Polite },
                                color = errorColor,
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            )
                            if (needsVerify) {
                                TextButton(
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    onClick = { openWebUrl?.invoke("https://linux.do/") }
                                ) {
                                    Text(LinDoCommonCopy.GO_VERIFY.text, color = palette.activeChipText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = palette.cardSurface,
        title = { LinDoScaledPopupContent { LinDoDialogTitle(title) } },
        text = body,
        confirmButton = {
            LinDoScaledPopupContent {
                TextButton(
                    enabled = !busy,
                    onClick = onConfirm,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (destructive) errorColor else palette.activeChipText,
                        disabledContentColor = palette.inkSecondary
                    )
                ) {
                    if (busy) LinDoBusyButtonLabel(true, LinDoCommonCopy.PROCESSING.text, confirmLabel, LocalContentColor.current)
                    else Text(confirmLabel, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        dismissButton = {
            LinDoScaledPopupContent {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!busy) alternateAction?.invoke()
                    TextButton(
                        enabled = !busy,
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(contentColor = palette.inkSecondary)
                    ) { Text(dismissLabel) }
                }
            }
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
    initialTags: List<String> = emptyList(),
    canEditTopicMeta: Boolean = false,
    onDismiss: () -> Unit,
    onPublished: (ComposerPublished) -> Unit
) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val ownerScopeKey = accountUsername ?: UserApiKeyAuthManager.currentUser?.username.orEmpty()
    val key = when (action) {
        ComposerAction.NEW_TOPIC -> "new_topic"
        ComposerAction.REPLY -> "topic_$topicId"
        ComposerAction.EDIT -> initialDraft?.draftKey ?: "edit_post_${post?.id}"
    }
    val stateKey = "$ownerScopeKey:$key"
    val immediateOwner = remember(accountUsername, ownerScopeKey) {
        accountUsername?.takeIf { it.isNotBlank() }
            ?: UserApiKeyAuthManager.currentUser?.username?.takeIf { it.isNotBlank() && it != "linuxdo_user" }
    }
    val canOpenInstantly = immediateOwner != null &&
        (action != ComposerAction.EDIT || post?.raw != null || initialDraft?.raw != null)

    fun buildHydratedLocalDraft(
        owner: String,
        sourceRaw: String,
        resolvedPostNumber: Int?
    ): ComposerDraft {
        val editingId = post?.id ?: initialDraft?.postId
        val seed = ComposerDraft(
            owner = owner,
            key = key,
            action = action,
            raw = initialDraft?.raw ?: if (action == ComposerAction.EDIT) sourceRaw else initialRaw,
            title = if (action == ComposerAction.NEW_TOPIC) {
                val untitledTopic = LinDoNetworkCopy.DRAFT_UNTITLED_TOPIC.text
                val untitledNewTopic = LinDoNetworkCopy.DRAFT_UNTITLED_NEW_TOPIC.text
                initialDraft?.title?.takeUnless {
                    it == untitledTopic || it == untitledNewTopic
                }.orEmpty()
            } else {
                val editDraftSuffix = LinDoNetworkCopy.DRAFT_FLOOR_EDIT.format("").trimStart()
                initialDraft?.title?.takeUnless {
                    it.isBlank() || it == LinDoNetworkCopy.DRAFT_POST_EDIT.text ||
                        (editDraftSuffix.isNotEmpty() && it.endsWith(editDraftSuffix))
                } ?: topicTitle
            },
            categoryId = initialDraft?.categoryId ?: initialCategoryId,
            tags = if (initialDraft != null) initialDraft.tags else initialTags,
            topicId = topicId?.takeIf { it > 0 } ?: post?.topicId?.takeIf { it > 0 } ?: initialDraft?.topicId?.takeIf { it > 0 },
            postId = editingId,
            postNumber = resolvedPostNumber,
            replyToPostNumber = initialDraft?.replyToPostNumber ?: replyToPostNumber,
            replyToUsername = replyToUsername,
            sequence = initialDraft?.sequence?.takeIf { initialDraft.sequenceKnown },
            recipients = initialDraft?.recipients.orEmpty()
        )
        val local = LocalComposerDrafts.get(owner, key)
        val chosenBase = if (
            local != null &&
            initialDraft != null &&
            initialDraft.sequenceKnown &&
            local.sequence != null &&
            (initialDraft.sequence > local.sequence ||
                (initialDraft.sequence == local.sequence && initialDraft.raw.trim() != local.raw.trim()))
        ) {
            LocalComposerDrafts.remove(owner, key)
            seed
        } else {
            local ?: seed
        }
        return chosenBase.let { base ->
            when (action) {
                ComposerAction.REPLY -> {
                    val quote = initialRaw.trimEnd()
                    val mergedRaw = when {
                        quote.isBlank() -> base.raw
                        base.raw.trimEnd().endsWith(quote) -> base.raw.trimEnd() + "\n\n"
                        quote in base.raw -> base.raw
                        base.raw.isBlank() -> initialRaw
                        else -> base.raw.trimEnd('\n') + "\n\n" + initialRaw
                    }
                    val targetNum = if (initialDraft != null) base.replyToPostNumber else replyToPostNumber
                    val targetUser = if (initialDraft != null) base.replyToUsername else replyToUsername
                    base.copy(raw = mergedRaw, replyToPostNumber = targetNum, replyToUsername = targetUser)
                }
                ComposerAction.EDIT -> base.copy(postNumber = seed.postNumber ?: base.postNumber)
                else -> base
            }
        }
    }

    val initialInstantPair = remember(stateKey) {
        val owner = immediateOwner
        if (owner != null && (action != ComposerAction.EDIT || post?.raw != null || initialDraft?.raw != null)) {
            val initSourceRaw = post?.raw ?: initialDraft?.raw.orEmpty()
            val initDraft = buildHydratedLocalDraft(
                owner = owner,
                sourceRaw = initSourceRaw,
                resolvedPostNumber = post?.postNumber
            )
            val initSession = ComposerDraftSession(
                initDraft,
                initialExistsOnServer = initialDraft?.sequenceKnown == true
            )
            if (initDraft.hasContent && (action != ComposerAction.EDIT || initDraft.raw != initSourceRaw)) {
                initSession.update(initDraft, fromUserEdit = false)
            }
            initDraft to initSession
        } else {
            null
        }
    }

    var draft by remember(stateKey) {
        mutableStateOf(initialInstantPair?.first ?: ComposerDraft("", key, action))
    }
    var session by remember(stateKey) {
        mutableStateOf<ComposerDraftSession?>(initialInstantPair?.second)
    }
    var originalRaw by remember(stateKey) {
        mutableStateOf(if (action == ComposerAction.EDIT) (post?.raw ?: initialDraft?.raw.orEmpty()) else "")
    }
    var originalTitle by remember(stateKey) { mutableStateOf(topicTitle) }
    var originalCategoryId by remember(stateKey) { mutableStateOf<Int?>(initialCategoryId) }
    var originalTags by remember(stateKey) { mutableStateOf(initialTags) }
    var conflictRemoteDraft by remember(stateKey) { mutableStateOf<CloudDraftItem?>(null) }
    var prepared by remember(stateKey) { mutableStateOf(initialInstantPair != null) }
    var prepareError by remember(stateKey) { mutableStateOf<String?>(null) }
    var prepareRetry by remember(stateKey) { mutableIntStateOf(0) }
    var submitting by remember(stateKey) { mutableStateOf(false) }
    var uploading by remember(stateKey) { mutableStateOf(false) }
    var draftSyncFailed by remember(stateKey) { mutableStateOf(false) }
    var draftSyncErrorMsg by remember(stateKey) { mutableStateOf<String?>(null) }
    var draftSyncBlockedByVerification by remember(stateKey) { mutableStateOf(false) }
    var submitError by remember(stateKey) { mutableStateOf<String?>(null) }
    var pendingReview by remember(stateKey) { mutableStateOf(false) }
    var unconfirmedDraft by remember(stateKey) { mutableStateOf<ComposerDraft?>(null) }
    var confirmResubmit by remember(stateKey) { mutableStateOf(false) }
    var showExit by remember(stateKey) { mutableStateOf(false) }
    var discardError by remember(stateKey) { mutableStateOf<String?>(null) }
    var showCost by remember(stateKey) { mutableStateOf(false) }
    var syncRetry by remember(stateKey) { mutableIntStateOf(0) }
    var tagChoices by remember(stateKey, draft.categoryId) { mutableStateOf(ComposerTagChoices(emptyList())) }
    var accumulatedTagOptions by remember(stateKey, draft.categoryId) { mutableStateOf<List<do_.linux.lindo.core.network.ComposerTagOption>>(emptyList()) }
    var tagIds by remember(stateKey, draft.categoryId) { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var tagQuery by remember(stateKey, draft.categoryId) { mutableStateOf("") }
    var tagsLoading by remember(stateKey, draft.categoryId) { mutableStateOf(action == ComposerAction.NEW_TOPIC || canEditTopicMeta) }
    var tagsError by remember(stateKey, draft.categoryId) { mutableStateOf<String?>(null) }
    var tagRetry by remember(stateKey, draft.categoryId) { mutableIntStateOf(0) }
    var appliedTemplate by remember(stateKey) { mutableStateOf<String?>(null) }

    var replyContextExpanded by remember(stateKey) { mutableStateOf(false) }
    var viewMode by remember(stateKey) { mutableStateOf(WorkbenchViewMode.SOURCE) }
    var keyboardBeforePreview by remember(stateKey) { mutableStateOf(false) }
    val imeVisible = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
    var activePicker by remember(stateKey) { mutableStateOf(ActiveComposerPicker.NONE) }

    val categoryCatalogVer = LinuxDoRepository.categoryCatalogVersion
    val isEditingTopicOp = action == ComposerAction.EDIT &&
        (draft.postNumber ?: post?.postNumber) == 1 &&
        canEditTopicMeta &&
        (draft.topicId ?: topicId ?: 0) > 0
    val hasTopicMetaControls = action == ComposerAction.NEW_TOPIC || isEditingTopicOp

    val busy = submitting || uploading || pendingReview
    val isUntouchedTemplate = action == ComposerAction.NEW_TOPIC &&
        appliedTemplate != null &&
        draft.raw.trim() == appliedTemplate!!.trim() &&
        draft.title.isBlank() &&
        draft.tags.isEmpty()
    val hasChanges = prepared && !isUntouchedTemplate &&
        if (action == ComposerAction.EDIT) {
            draft.raw != originalRaw || (
                isEditingTopicOp && (
                    draft.title.trim() != originalTitle.trim() ||
                        draft.categoryId != originalCategoryId ||
                        draft.tags != originalTags
                    )
                )
        } else {
            draft.hasContent
        }

    val openWebUrl = LocalOpenInAppWebUrl.current
    val netRestoreVer = LocalLinDoNetworkRestoreVersion.current
    var baselineNetRestoreVer by remember(stateKey) { mutableIntStateOf(netRestoreVer) }
    LaunchedEffect(netRestoreVer) {
        if (netRestoreVer == baselineNetRestoreVer) return@LaunchedEffect
        baselineNetRestoreVer = netRestoreVer
        if (prepareError != null && !prepareError.isSiteVerificationError()) {
            prepareRetry++
        }
        if (draftSyncFailed && !draftSyncBlockedByVerification) {
            draftSyncErrorMsg = null
            draftSyncFailed = false
            syncRetry++
        }
        if (tagsError != null && !tagsError.isSiteVerificationError()) {
            tagRetry++
        }
    }

    val siteVerifyVer = do_.linux.lindo.ui.components.LocalSiteVerificationRestoreVersion.current
    var baselineSiteVerifyVer by remember(stateKey) { mutableIntStateOf(siteVerifyVer) }
    LaunchedEffect(siteVerifyVer) {
        if (siteVerifyVer == baselineSiteVerifyVer) return@LaunchedEffect
        baselineSiteVerifyVer = siteVerifyVer
        if (prepareError != null) {
            prepareRetry++
        }
        if (submitError.isSiteVerificationError()) {
            submitError = null
        }
        if (discardError.isSiteVerificationError()) {
            discardError = null
        }
        if (draftSyncFailed || draftSyncBlockedByVerification) {
            draftSyncBlockedByVerification = false
            draftSyncErrorMsg = null
            draftSyncFailed = false
            syncRetry++
        }
        if (tagsError != null) {
            tagRetry++
        }
    }

    fun update(next: ComposerDraft) {
        if (submitting || !prepared) return
        draft = next.copy(updatedAt = System.currentTimeMillis())
        session?.update(draft)
        if (!draftSyncBlockedByVerification) {
            draftSyncFailed = false
            draftSyncErrorMsg = null
        }
        submitError = null
    }
    fun requestClose() {
        if (busy) return
        if (activePicker != ActiveComposerPicker.NONE) {
            activePicker = ActiveComposerPicker.NONE
            return
        }
        if (viewMode == WorkbenchViewMode.PREVIEW) {
            viewMode = WorkbenchViewMode.SOURCE
            return
        }
        if (hasChanges) {
            showExit = true
        } else {
            if (action == ComposerAction.EDIT && session?.existsOnServer == true) {
                val closingSession = session
                LinuxDoRepository.launchDetachedDraftOp {
                    runCatching { closingSession?.discard() }
                }
            }
            onDismiss()
        }
    }

    // 当回复面板已秒开后，若用户再次点击「引用并回复」或切换回复楼层，零阻塞追加引用内容，绝不闪回 Loading！
    var lastAppliedReplySeed by remember(stateKey) { mutableStateOf(replyToPostNumber to initialRaw) }
    LaunchedEffect(replyToPostNumber, initialRaw) {
        val nextSeed = replyToPostNumber to initialRaw
        if (nextSeed == lastAppliedReplySeed) return@LaunchedEffect
        lastAppliedReplySeed = nextSeed
        if (prepared && action == ComposerAction.REPLY) {
            val quote = initialRaw.trimEnd()
            val mergedRaw = when {
                quote.isBlank() -> draft.raw
                draft.raw.trimEnd().endsWith(quote) -> draft.raw.trimEnd() + "\n\n"
                quote in draft.raw -> draft.raw
                draft.raw.isBlank() -> initialRaw
                else -> draft.raw.trimEnd('\n') + "\n\n" + initialRaw
            }
            val updated = draft.copy(
                raw = mergedRaw,
                replyToPostNumber = replyToPostNumber,
                replyToUsername = replyToUsername,
                updatedAt = System.currentTimeMillis()
            )
            draft = updated
            session?.update(updated, fromUserEdit = false)
        }
    }

    LaunchedEffect(stateKey, prepareRetry) {
        val alreadyPreparedInstantly = prepared && session != null && prepareError == null
        if (!alreadyPreparedInstantly) {
            prepared = false
        }
        prepareError = null
        try {
            val activeSession: ComposerDraftSession = if (alreadyPreparedInstantly && session != null) {
                session!!
            } else {
                val owner = accountUsername ?: UserApiKeyAuthManager.currentUser?.username
                    ?: LinuxDoRepository.fetchCurrentUserProfile()?.username
                    ?: throw ApiMutationException(LinDoComposerCopy.CANNOT_READ_ACCOUNT_RETRY.text)
                val editingId = post?.id ?: initialDraft?.postId
                val sourcePost = if (action == ComposerAction.EDIT) {
                    post?.takeIf { it.raw != null } ?: editingId?.let { LinuxDoRepository.fetchPostForEditing(it) }
                        ?: throw ApiMutationException(LinDoComposerCopy.CANNOT_READ_RAW_POST_RETRY.text)
                } else null
                originalRaw = sourcePost?.raw ?: ""
                originalTitle = topicTitle
                originalCategoryId = initialCategoryId
                originalTags = initialTags
                val hydratedLocal = buildHydratedLocalDraft(
                    owner = owner,
                    sourceRaw = originalRaw,
                    resolvedPostNumber = post?.postNumber ?: sourcePost?.postNumber
                ).let { d ->
                    if (d.topicId == null && sourcePost?.topicId != null && sourcePost.topicId > 0) {
                        d.copy(topicId = sourcePost.topicId)
                    } else {
                        d
                    }
                }
                draft = hydratedLocal
                val created = ComposerDraftSession(
                    hydratedLocal,
                    initialExistsOnServer = initialDraft?.sequenceKnown == true
                )
                session = created
                prepared = true
                created
            }

            if (initialDraft == null || !initialDraft.sequenceKnown) {
                val remoteConflict = runCatching {
                    activeSession.prepareWithRemote(
                        untouchedBaselineRaw = if (action == ComposerAction.EDIT) originalRaw else appliedTemplate
                    )
                }.getOrNull()
                if (!activeSession.userModifiedInSession && remoteConflict == null && activeSession.existsOnServer) {
                    if (appliedTemplate != null && activeSession.current.raw.trim() != appliedTemplate!!.trim()) {
                        appliedTemplate = null
                    }
                    draft = activeSession.current
                } else if (activeSession.savedSequence != null && draft.sequence != activeSession.savedSequence) {
                    draft = draft.copy(sequence = activeSession.savedSequence)
                }
                conflictRemoteDraft = remoteConflict
            } else {
                conflictRemoteDraft = null
            }
            prepared = true
            val isUntouchedOnOpen = action == ComposerAction.NEW_TOPIC &&
                appliedTemplate != null &&
                draft.raw.trim() == appliedTemplate!!.trim() &&
                draft.title.isBlank() &&
                draft.tags.isEmpty()
            val editMetaChangedOnOpen = action == ComposerAction.EDIT &&
                (draft.postNumber ?: post?.postNumber) == 1 &&
                canEditTopicMeta &&
                (draft.topicId ?: topicId ?: 0) > 0 &&
                (draft.title.trim() != originalTitle.trim() ||
                    draft.categoryId != originalCategoryId ||
                    draft.tags != originalTags)
            if (
                conflictRemoteDraft == null &&
                !isUntouchedOnOpen &&
                draft.hasContent &&
                !activeSession.userModifiedInSession &&
                (action != ComposerAction.EDIT || draft.raw != originalRaw || editMetaChangedOnOpen)
            ) {
                activeSession.update(draft, fromUserEdit = false)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (!prepared) {
                prepareError = error.message ?: LinDoComposerCopy.CANNOT_OPEN_COMPOSER_RETRY.text
            }
        }
    }

    LaunchedEffect(prepared, draft.categoryId, categoryCatalogVer) {
        if (!prepared || action != ComposerAction.NEW_TOPIC) return@LaunchedEffect
        if (LinuxDoRepository.findCategoryById(draft.categoryId) == null) {
            val fallback = LinuxDoRepository.categories.firstOrNull {
                it.id > 0 && (it.isVerifiedFromServer || !it.readRestricted)
            } ?: LinuxDoRepository.categories.firstOrNull()
            if (fallback != null && draft.categoryId != fallback.id) {
                draft = draft.copy(categoryId = fallback.id)
                if (draft.hasContent && !isUntouchedTemplate && conflictRemoteDraft == null) {
                    session?.update(draft)
                }
            }
        }
    }

    LaunchedEffect(draft, prepared, syncRetry, conflictRemoteDraft, draftSyncBlockedByVerification) {
        if (!prepared || !hasChanges || isUntouchedTemplate || conflictRemoteDraft != null || draftSyncBlockedByVerification) return@LaunchedEffect
        delay(1200)
        if (!hasChanges || isUntouchedTemplate || conflictRemoteDraft != null || draftSyncBlockedByVerification) return@LaunchedEffect
        try {
            val ok = session?.sync() == true
            draftSyncFailed = !ok
            if (ok) {
                draftSyncBlockedByVerification = false
                draftSyncErrorMsg = null
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            draftSyncFailed = true
            val needsSiteVerify = (error is ApiReadException && error.kind == ApiFailureKind.Verification) ||
                error.message.isSiteVerificationError()
            draftSyncBlockedByVerification = needsSiteVerify
            draftSyncErrorMsg = if (needsSiteVerify) {
                LinDoComposerCopy.DRAFT_SAVED_LOCAL_NEED_VERIFY.text
            } else {
                null
            }
        }
    }

    LaunchedEffect(draft.categoryId, draft.tags, tagQuery, tagRetry, prepared, hasTopicMetaControls) {
        if (!prepared || !hasTopicMetaControls) return@LaunchedEffect
        val hadExistingOptions = accumulatedTagOptions.isNotEmpty()
        if (!hadExistingOptions) {
            tagsLoading = true
        }
        tagsError = null
        try {
            val cleanQuery = tagQuery.trim()
            if (cleanQuery.isNotEmpty()) delay(220)
            val selected = draft.tags.associateWith { tagIds[it] }
            val fetched = kotlinx.coroutines.withTimeoutOrNull(6_500L) {
                LinuxDoRepository.fetchCategoryAllowedTags(draft.categoryId ?: initialCategoryId, cleanQuery, selected)
            } ?: throw ApiMutationException(LinDoComposerCopy.TAGS_TEMP_UNAVAILABLE.text)
            val mergedMap = linkedMapOf<String, do_.linux.lindo.core.network.ComposerTagOption>()
            accumulatedTagOptions.forEach { mergedMap[it.name] = it }
            fetched.options.forEach { mergedMap[it.name] = it }
            val mergedOptions = mergedMap.values.toList()
            accumulatedTagOptions = mergedOptions
            val displayOptions = if (cleanQuery.isEmpty()) {
                mergedOptions
            } else {
                val queryMap = linkedMapOf<String, do_.linux.lindo.core.network.ComposerTagOption>()
                fetched.options.forEach { queryMap[it.name] = it }
                mergedOptions.forEach { opt ->
                    if (opt.name.contains(cleanQuery, ignoreCase = true) && !queryMap.containsKey(opt.name)) {
                        queryMap[opt.name] = opt
                    }
                }
                queryMap.values.toList()
            }
            tagChoices = fetched.copy(options = displayOptions)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (!hadExistingOptions) {
                tagsError = error.message ?: LinDoComposerCopy.TAGS_LOAD_FAILED.text
            }
        }
        finally { tagsLoading = false }
    }

    LaunchedEffect(draft.categoryId, prepared) {
        if (!prepared || action != ComposerAction.NEW_TOPIC) return@LaunchedEffect
        val category = draft.categoryId ?: return@LaunchedEffect
        val canReplaceWithTemplate = draft.raw.isBlank() || (appliedTemplate != null && draft.raw.trim() == appliedTemplate!!.trim())
        if (!canReplaceWithTemplate) return@LaunchedEffect
        val template = runCatching { LinuxDoRepository.fetchCategoryTopicTemplate(category) }.getOrNull().orEmpty()
        if (draft.categoryId == category && (draft.raw.isBlank() || (appliedTemplate != null && draft.raw.trim() == appliedTemplate!!.trim()))) {
            appliedTemplate = template.takeIf { it.isNotBlank() }
            draft = draft.copy(raw = template, updatedAt = System.currentTimeMillis())
        }
    }

    val titleRemaining = LinuxDoSiteRules.MIN_TOPIC_TITLE_LENGTH - draft.title.trim().length
    val titleValidation = if (prepared && hasTopicMetaControls && titleRemaining > 0) {
        LinDoComposerCopy.TITLE_CHARS_REMAINING.format(titleRemaining)
    } else null
    val bodyRemaining = LinuxDoSiteRules.MIN_POST_RAW_LENGTH - draft.raw.trim().length
    val bodyValidation = when {
        !prepared -> null
        bodyRemaining > 0 -> LinDoComposerCopy.BODY_CHARS_REMAINING.format(bodyRemaining)
        else -> null
    }
    val focusBodyInitially = remember(prepared) { !hasTopicMetaControls || draft.title.isNotBlank() }
    val tagValidationOk = !hasTopicMetaControls || (
        !tagsLoading &&
            tagChoices.requiredGroup == null &&
            tagChoices.forbiddenMessage == null
        )
    val canSubmit = prepared && !busy && titleValidation == null && bodyValidation == null &&
        tagValidationOk &&
        (action != ComposerAction.EDIT || hasChanges)

    fun submit(allowResubmit: Boolean = false) {
        if (!canSubmit || submitting) return
        val previous = unconfirmedDraft
        if (!allowResubmit && previous != null && previous.raw == draft.raw && previous.title == draft.title &&
            previous.categoryId == draft.categoryId && previous.tags == draft.tags
        ) {
            confirmResubmit = true
            return
        }
        submitting = true
        submitError = null
        val snapshot = draft
        scope.launch {
            try {
                val result = session?.publish {
                    when (action) {
                        ComposerAction.NEW_TOPIC -> when (val submission = LinuxDoRepository.createTopic(
                            snapshot.title, snapshot.raw, snapshot.categoryId ?: initialCategoryId, snapshot.tags, snapshot.key
                        )) {
                            is PostSubmission.Published -> PostSubmission.Published(
                                ComposerPublished(
                                    topicId = submission.value,
                                    title = snapshot.title.trim(),
                                    updatedCategoryId = snapshot.categoryId ?: initialCategoryId,
                                    updatedTags = snapshot.tags
                                )
                            )
                            PostSubmission.PendingReview -> PostSubmission.PendingReview
                        }
                        ComposerAction.REPLY -> when (val submission = LinuxDoRepository.createPost(
                            snapshot.topicId ?: throw ApiMutationException(LinDoComposerCopy.TOPIC_META_INCOMPLETE.text),
                            snapshot.raw, snapshot.replyToPostNumber, snapshot.key
                        )) {
                            is PostSubmission.Published -> PostSubmission.Published(
                                ComposerPublished(
                                    topicId = snapshot.topicId,
                                    post = submission.value.copy(raw = snapshot.raw)
                                )
                            )
                            PostSubmission.PendingReview -> PostSubmission.PendingReview
                        }
                        ComposerAction.EDIT -> {
                            val resolvedTopicId = snapshot.topicId ?: topicId
                            val editingOp = isEditingTopicOp && (resolvedTopicId ?: 0) > 0
                            val metaChanged = editingOp && (
                                snapshot.title.trim() != originalTitle.trim() ||
                                    snapshot.categoryId != originalCategoryId ||
                                    snapshot.tags != originalTags
                                )
                            if (metaChanged) {
                                val metaOk = LinuxDoRepository.updateTopicMetadata(
                                    resolvedTopicId!!,
                                    snapshot.title.trim(),
                                    snapshot.categoryId,
                                    snapshot.tags
                                )
                                if (!metaOk) {
                                    throw ApiMutationException(LinDoComposerCopy.TOPIC_META_UPDATE_FAILED.text)
                                }
                                originalTitle = snapshot.title.trim()
                                originalCategoryId = snapshot.categoryId
                                originalTags = snapshot.tags
                            }
                            if (!editingOp || snapshot.raw != originalRaw) {
                                val updatedPost = LinuxDoRepository.updatePost(
                                    snapshot.postId ?: throw ApiMutationException(LinDoComposerCopy.POST_META_INCOMPLETE.text),
                                    snapshot.raw
                                ) ?: throw PostSubmissionUnconfirmed()
                                PostSubmission.Published(
                                    ComposerPublished(
                                        topicId = resolvedTopicId,
                                        title = if (editingOp) snapshot.title.trim() else null,
                                        post = updatedPost.copy(raw = snapshot.raw, topicId = resolvedTopicId ?: 0),
                                        updatedCategoryId = if (editingOp) snapshot.categoryId else null,
                                        updatedTags = if (editingOp) snapshot.tags else null
                                    )
                                )
                            } else {
                                PostSubmission.Published(
                                    ComposerPublished(
                                        topicId = resolvedTopicId,
                                        title = snapshot.title.trim(),
                                        post = post,
                                        updatedCategoryId = snapshot.categoryId,
                                        updatedTags = snapshot.tags
                                    )
                                )
                            }
                        }
                    }
                }
                when (result) {
                    is PostSubmission.Published -> onPublished(result.value)
                    PostSubmission.PendingReview -> { keyboard?.hide(); pendingReview = true }
                    null -> throw PostSubmissionUnconfirmed()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (error is PostSubmissionUnconfirmed) unconfirmedDraft = snapshot
                submitError = error.message ?: LinDoComposerCopy.SUBMIT_FAILED_KEPT.text
            }
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
            val headerTitle = when {
                action == ComposerAction.NEW_TOPIC -> LinDoComposerCopy.HEADER_NEW_TOPIC.text
                isEditingTopicOp -> LinDoComposerCopy.HEADER_EDIT_TOPIC.text
                action == ComposerAction.EDIT -> draft.postNumber?.takeIf { it > 1 }
                    ?.let { LinDoComposerCopy.HEADER_EDIT_REPLY_FLOOR.format(it) } ?: LinDoComposerCopy.HEADER_EDIT_POST.text
                else -> if (draft.replyToPostNumber == null) LinDoComposerCopy.HEADER_REPLY_TOPIC.text else LinDoComposerCopy.HEADER_REPLY.text
            }
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(enabled = !busy, onClick = ::requestClose) {
                    Icon(LinDoIcons.ArrowLeft, LinDoCommonCopy.BACK.text, tint = palette.inkPrimary, modifier = Modifier.size(20.dp))
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
                        Text(if (isPreview) LinDoCommonCopy.CONTINUE_EDIT.text else LinDoCommonCopy.PREVIEW.text, color = palette.inkPrimary, maxLines = 1)
                    }
                }
                val actionLabel = when (action) {
                    ComposerAction.NEW_TOPIC -> LinDoCommonCopy.PUBLISH.text
                    ComposerAction.REPLY -> LinDoCommonCopy.SEND.text
                    ComposerAction.EDIT -> LinDoCommonCopy.SAVE.text
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
                    val prepNeedsVerify = prepareError.isSiteVerificationError() && openWebUrl != null
                    Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        LinDoLoadFailure(
                            message = prepareError!!,
                            onRetry = {
                                if (prepNeedsVerify) {
                                    openWebUrl?.invoke("https://linux.do/")
                                } else {
                                    prepareRetry++
                                }
                            }
                        )
                    }
                }
                !prepared -> {
                    Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        LinDoLoadingIndicator(label = LinDoCommonCopy.LOADING.text)
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
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .then(
                                        if (snippet.isNotBlank()) {
                                            Modifier
                                                .pointerHoverIcon(androidx.compose.ui.input.pointer.PointerIcon.Hand)
                                                .clickable { replyContextExpanded = !replyContextExpanded }
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .padding(horizontal = 6.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(9.dp)
                            ) {
                                if (floor != null && name != null) {
                                    GoogleAccountRingAvatar(
                                        letter = name,
                                        username = draft.replyToUsername ?: name,
                                        avatarUrl = replyToAvatarUrl,
                                        animatedAvatar = replyToAnimatedAvatar,
                                        avatarSize = 25.dp,
                                        strokeWidth = 0.dp
                                    )
                                }
                                Column(Modifier.weight(1f)) {
                                    if (floor != null) {
                                        Text(
                                            when {
                                                name != null -> LinDoReaderCopy.REPLY_TARGET_USER_FLOOR.format(name, floor)
                                                floor == 1 -> LinDoComposerCopy.REPLY_OP.text
                                                else -> LinDoComposerCopy.REPLY_FLOOR_NUMBER.format(floor)
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
                                        if (replyContextExpanded) LinDoComposerCopy.COLLAPSE_ORIGINAL_DESC.text else LinDoComposerCopy.EXPAND_ORIGINAL_DESC.text,
                                        tint = palette.inkSecondary, modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                            HorizontalDivider(color = palette.hairline, thickness = 0.5.dp)
                        }
                    }

                    if (hasTopicMetaControls) {
                        val activeCat = remember(draft.categoryId, categoryCatalogVer) {
                            LinuxDoRepository.findCategoryById(draft.categoryId)
                                ?: LinuxDoRepository.categories.firstOrNull {
                                    it.id > 0 && (it.isVerifiedFromServer || !it.readRestricted)
                                }
                                ?: LinuxDoRepository.categories.firstOrNull()
                        }
                        val catFallbackColor = remember(activeCat?.colorHex) {
                            LinuxDoSiteRules.parseHexColor(activeCat?.colorHex ?: "0E76BD")
                        }
                        val (catIcon, catColor, isCatRestricted) = remember(
                            activeCat?.id,
                            activeCat?.name,
                            activeCat?.icon,
                            activeCat?.readRestricted,
                            catFallbackColor
                        ) {
                            if (activeCat != null) {
                                resolveOfficialCategoryVisual(
                                    categoryName = activeCat.name,
                                    fallbackColor = catFallbackColor,
                                    iconNameOverride = activeCat.icon,
                                    restrictedOverride = activeCat.readRestricted
                                )
                            } else {
                                Triple(LinDoIcons.FaCode, catFallbackColor, false)
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // 1. 版块类别抽屉触发器（无底色无边框，与版块图标体系严格一致）
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(enabled = !busy) {
                                        keyboard?.hide()
                                        activePicker = ActiveComposerPicker.CATEGORY
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = catIcon,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = catColor
                                )
                                if (isCatRestricted) {
                                    Icon(
                                        imageVector = LinDoIcons.FaLock,
                                        contentDescription = LinDoFeedCopy.RESTRICTED_CATEGORY_DESC.text,
                                        modifier = Modifier.size(11.dp),
                                        tint = palette.inkSecondary
                                    )
                                }
                                Text(
                                    text = activeCat?.displayName ?: LinDoComposerCopy.SELECT_CATEGORY.text,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = palette.inkPrimary
                                )
                                Icon(
                                    imageVector = LinDoIcons.ChevronDown,
                                    contentDescription = LinDoComposerCopy.OPEN_CATEGORY_DRAWER_DESC.text,
                                    modifier = Modifier.size(13.dp),
                                    tint = palette.inkSecondary
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .width(0.5.dp)
                                    .height(15.dp)
                                    .background(palette.hairline)
                            )

                            // 2. 已选标签（无底色无边框，使用官方标签图标，点击可快速移除）
                            draft.tags.forEach { tag ->
                                val tagVisual = remember(tag) { resolveOfficialTagVisual(tag) }
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable(enabled = !busy) {
                                            update(draft.copy(tags = draft.tags - tag))
                                        }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    if (tagVisual != null) {
                                        Icon(
                                            imageVector = tagVisual.first,
                                            contentDescription = null,
                                            modifier = Modifier.size(12.dp),
                                            tint = tagVisual.second
                                        )
                                    } else {
                                        Icon(
                                            imageVector = LinDoIcons.FaTag,
                                            contentDescription = null,
                                            modifier = Modifier.size(10.5.dp),
                                            tint = palette.brandGold
                                        )
                                    }
                                    Text(
                                        text = tag,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = palette.inkPrimary
                                    )
                                    Icon(
                                        imageVector = LinDoIcons.Close,
                                        contentDescription = LinDoComposerCopy.REMOVE_TAG_DESC.text,
                                        modifier = Modifier.size(11.5.dp),
                                        tint = palette.inkTertiary
                                    )
                                }
                            }

                            // 3. 标签抽屉触发器（无底色无边框，清爽图标+文字）
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(enabled = !busy) {
                                        keyboard?.hide()
                                        if (tagsError != null) tagRetry++
                                        activePicker = ActiveComposerPicker.TAG
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = LinDoIcons.Plus,
                                    contentDescription = LinDoComposerCopy.OPEN_TAG_DRAWER_DESC.text,
                                    modifier = Modifier.size(13.dp),
                                    tint = palette.inkSecondary
                                )
                                Text(
                                    text = if (draft.tags.isEmpty()) LinDoComposerCopy.SELECT_TAGS.text else LinDoComposerCopy.MANAGE_TAGS_COUNT.format(draft.tags.size, COMPOSER_TAG_LIMIT),
                                    fontSize = 13.2.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = palette.inkSecondary
                                )
                            }
                        }

                        val tagHintMessage = tagChoices.forbiddenMessage
                            ?: tagChoices.requiredGroup?.let { LinDoComposerCopy.REQUIRED_TAG_GROUP_HINT.format(it) }
                        if (tagHintMessage != null) {
                            Text(
                                text = tagHintMessage,
                                fontSize = 11.8.sp,
                                fontWeight = FontWeight.Medium,
                                color = palette.brandGold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .pointerHoverIcon(androidx.compose.ui.input.pointer.PointerIcon.Hand)
                                    .clickable(enabled = !busy) {
                                        keyboard?.hide()
                                        activePicker = ActiveComposerPicker.TAG
                                    }
                                    .padding(horizontal = 4.dp, vertical = 4.dp)
                            )
                        }

                        HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)

                        when (activePicker) {
                            ActiveComposerPicker.NONE -> Unit
                            ActiveComposerPicker.CATEGORY -> ComposerCategoryPicker(
                                categories = LinuxDoRepository.categories,
                                selectedId = activeCat?.id,
                                enabled = !busy,
                                onSelect = { category ->
                                    if (draft.categoryId != category.id) {
                                        val next = draft.copy(categoryId = category.id, tags = emptyList())
                                        if (isUntouchedTemplate) draft = next.copy(updatedAt = System.currentTimeMillis())
                                        else update(next)
                                    }
                                    activePicker = ActiveComposerPicker.NONE
                                },
                                onFocusSelectParent = { category ->
                                    if (draft.categoryId != category.id) {
                                        val next = draft.copy(categoryId = category.id, tags = emptyList())
                                        if (isUntouchedTemplate) draft = next.copy(updatedAt = System.currentTimeMillis())
                                        else update(next)
                                    }
                                },
                                onDismiss = { activePicker = ActiveComposerPicker.NONE }
                            )
                            ActiveComposerPicker.TAG -> ComposerTagPicker(
                                query = tagQuery,
                                onQueryChange = { tagQuery = it },
                                selected = draft.tags,
                                choices = tagChoices,
                                limit = COMPOSER_TAG_LIMIT,
                                canCreateTag = LinuxDoRepository.canCreateTag,
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
                                    }
                                },
                                onRetry = { tagRetry++ },
                                onDismiss = { keyboard?.hide(); tagQuery = ""; activePicker = ActiveComposerPicker.NONE }
                            )
                        }

                        // 无边框大字号话题标题输入栏
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        ) {
                            ComposerTextInput(
                                value = draft.title,
                                onChange = { update(draft.copy(title = it)) },
                                placeholder = LinDoComposerCopy.TOPIC_TITLE_PLACEHOLDER.text,
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

                    conflictRemoteDraft?.let { remote ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(palette.subtleSurface)
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = LinDoComposerCopy.CLOUD_CONFLICT_DETECTED.text,
                                modifier = Modifier.weight(1f),
                                color = palette.inkPrimary,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            TextButton(
                                enabled = !busy,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                onClick = {
                                    session?.let { active ->
                                        draft = active.acceptRemoteDraft(remote)
                                    }
                                    conflictRemoteDraft = null
                                }
                            ) {
                                Text(LinDoComposerCopy.USE_CLOUD_DRAFT.text, color = palette.activeChipText, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                            }
                            TextButton(
                                enabled = !busy,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                onClick = {
                                    session?.let { active ->
                                        active.update(draft)
                                        draft = active.keepLocalOverRemote(remote.sequence)
                                    }
                                    conflictRemoteDraft = null
                                }
                            ) {
                                Text(LinDoComposerCopy.KEEP_LOCAL_DRAFT.text, color = palette.inkSecondary, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                        HorizontalDivider(color = palette.hairline, thickness = 0.5.dp)
                    }

                    if (draftSyncFailed && submitError == null && !busy) {
                        val syncMsg = draftSyncErrorMsg ?: LinDoComposerCopy.DRAFT_SAVED_LOCAL_CLOUD_FAILED.text
                        val syncNeedsVerify = syncMsg.isSiteVerificationError() && openWebUrl != null
                        LinDoLoadFailure(syncMsg) {
                            if (syncNeedsVerify) {
                                openWebUrl?.invoke("https://linux.do/")
                            } else {
                                draftSyncBlockedByVerification = false
                                syncRetry++
                            }
                        }
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
                            ComposerAction.NEW_TOPIC -> LinDoComposerCopy.PLACEHOLDER_WRITE_BODY.text
                            ComposerAction.EDIT -> LinDoComposerCopy.PLACEHOLDER_EDIT_BODY.text
                            ComposerAction.REPLY -> LinDoComposerCopy.PLACEHOLDER_WRITE_REPLY.text
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

    if (pendingReview && !do_.linux.lindo.ui.components.LocalIsWebModalActive.current) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { LinDoScaledPopupContent { LinDoDialogTitle(LinDoComposerCopy.SUBMITTED_FOR_REVIEW.text) } },
            confirmButton = {
                LinDoScaledPopupContent {
                    TextButton(onClick = onDismiss) { Text(LinDoCommonCopy.DONE.text, color = palette.activeChipText) }
                }
            },
            containerColor = palette.cardSurface
        )
    }
    if (confirmResubmit) LinDoConfirmDialog(
        title = LinDoComposerCopy.CONFIRM_RESUBMIT_TITLE.text,
        confirmLabel = LinDoComposerCopy.RESUBMIT_ACTION.text,
        onDismiss = { confirmResubmit = false },
        onConfirm = { confirmResubmit = false; submit(allowResubmit = true) }
    )
    if (showExit) LinDoConfirmDialog(
        title = LinDoComposerCopy.SAVE_DRAFT_PROMPT.text,
        confirmLabel = LinDoCommonCopy.SAVE.text,
        dismissLabel = LinDoCommonCopy.CANCEL.text,
        busy = submitting,
        error = discardError,
        alternateAction = {
            TextButton(
                onClick = {
                    if (submitting) return@TextButton
                    discardError = null
                    submitting = true
                    scope.launch {
                        try {
                            if (session?.discard() == true) {
                                showExit = false
                                onDismiss()
                            } else {
                                discardError = LinDoComposerCopy.DRAFT_UPDATED_REOPEN_CHECK.text
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            discardError = error.message ?: LinDoComposerCopy.DISCARD_DRAFT_FAILED.text
                        } finally {
                            submitting = false
                        }
                    }
                }
            ) {
                Text(LinDoCommonCopy.DONT_SAVE.text, color = MaterialTheme.colorScheme.error)
            }
        },
        onDismiss = { if (!submitting) { showExit = false; discardError = null } },
        onConfirm = { session?.update(draft); session?.syncInBackground(); showExit = false; onDismiss() }
    )
    if (showCost) LinDoConfirmDialog(
        title = LinDoComposerCopy.CONFIRM_LOTTERY_TOPIC_TITLE.text,
        confirmLabel = LinDoCommonCopy.PUBLISH.text,
        detail = LinDoComposerCopy.LOTTERY_COST_DETAIL.format(LinuxDoSiteRules.LOTTERY_TOPIC_COST_POINTS),
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
