package do_.linux.lindo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import do_.linux.lindo.core.compose.LocalComposerDrafts
import do_.linux.lindo.core.compose.toCloudItem
import do_.linux.lindo.core.copy.CloudDraftKind
import do_.linux.lindo.core.copy.LinDoCommonCopy
import do_.linux.lindo.core.copy.LinDoComposerCopy
import do_.linux.lindo.core.copy.isSiteVerificationError
import do_.linux.lindo.core.network.*
import do_.linux.lindo.ui.components.LinDoIcons
import do_.linux.lindo.ui.components.LocalLinDoNetworkRestoreVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun resolveDraftKindLabel(draft: CloudDraftItem): String =
    CloudDraftKind.resolve(draft.action, draft.draftKey).badgeCopy.text

internal fun mergeCloudAndLocalDraftLists(
    owner: String,
    cloud: List<CloudDraftItem>,
    local: List<CloudDraftItem>
): List<CloudDraftItem> {
    val cloudByKey = cloud.associateBy { it.draftKey }
    val mergedLocal = local.mapNotNull { item ->
        val remote = cloudByKey[item.draftKey]
        when {
            remote == null -> item
            !item.sequenceKnown -> item.copy(sequenceKnown = false)
            remote.sequence >= item.sequence -> {
                if (remote.sequence > item.sequence || remote.raw.trim() != item.raw.trim()) {
                    LocalComposerDrafts.remove(owner, item.draftKey)
                    LocalComposerDrafts.forgetSequence(owner, item.draftKey)
                }
                null
            }
            else -> item
        }
    }
    return (cloud + mergedLocal).associateBy { it.draftKey }.values.toList()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UserDraftsSheet(onDismiss: () -> Unit, onOpen: (CloudDraftItem) -> Unit, accountUsername: String? = null) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    val owner = accountUsername ?: UserApiKeyAuthManager.currentUser?.username.orEmpty()
    var drafts by remember(owner) { mutableStateOf(LocalComposerDrafts.all(owner).filter { it.hasContent }.map { it.toCloudItem() }) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var deleting by remember { mutableStateOf<CloudDraftItem?>(null) }
    var showClearAllConfirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    val netRestoreVer = LocalLinDoNetworkRestoreVersion.current
    var baselineNetRestoreVer by remember { mutableIntStateOf(netRestoreVer) }
    LaunchedEffect(netRestoreVer) {
        if (netRestoreVer == baselineNetRestoreVer) return@LaunchedEffect
        baselineNetRestoreVer = netRestoreVer
        if (error != null && !error.isSiteVerificationError()) {
            reload++
        }
    }
    val siteVerifyVer = do_.linux.lindo.ui.components.LocalSiteVerificationRestoreVersion.current
    var baselineSiteVerifyVer by remember { mutableIntStateOf(siteVerifyVer) }
    LaunchedEffect(siteVerifyVer) {
        if (siteVerifyVer == baselineSiteVerifyVer) return@LaunchedEffect
        baselineSiteVerifyVer = siteVerifyVer
        if (error != null) {
            reload++
        }
        if (deleteError.isSiteVerificationError()) {
            deleteError = null
        }
    }
    LaunchedEffect(owner, reload) {
        loading = true
        error = null
        val local = LocalComposerDrafts.all(owner).filter { it.hasContent }.map { it.toCloudItem() }
        drafts = local
        try {
            val cloud = LinuxDoRepository.fetchCloudDrafts()
            drafts = mergeCloudAndLocalDraftLists(owner = owner, cloud = cloud, local = local)
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: LinDoComposerCopy.CLOUD_DRAFTS_SYNC_FAILED.text }
        finally { loading = false }
    }
    LinDoFullScreenPage(onBack = onDismiss, containerColor = palette.cardSurface) {
        LinDoPageTopBar(
            title = LinDoComposerCopy.DRAFTS_BOX_TITLE.text,
            onBack = onDismiss,
            trailingContent = {
                if (drafts.isNotEmpty() && !busy) {
                    TextButton(onClick = {
                        deleteError = null
                        showClearAllConfirm = true
                    }) {
                        Text(
                            text = LinDoComposerCopy.CLEAR_ALL_DRAFTS.text,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        )
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            error?.let { LinDoLoadFailure(it, { reload++ }) }
            if (loading && drafts.isEmpty()) Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { LinDoLoadingIndicator(label = LinDoComposerCopy.LOADING_DRAFTS.text) }
            else LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
                if (drafts.isEmpty() && error == null) item { LinDoEmptyState(LinDoComposerCopy.EMPTY_DRAFTS.text) }
                items(drafts, key = { it.draftKey }) { draft ->
                    val kindLabel = resolveDraftKindLabel(draft)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .pointerHoverIcon(androidx.compose.ui.input.pointer.PointerIcon.Hand)
                            .clickable { onOpen(draft) }
                            .padding(horizontal = 6.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(palette.subtleSurface)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = kindLabel,
                                        color = palette.activeChipText,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1
                                    )
                                }
                                Text(
                                    text = draft.title,
                                    color = palette.inkPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                            }
                            if (draft.recipients.isNotBlank()) {
                                Text(
                                    text = LinDoComposerCopy.RECIPIENT_PREFIX.format(draft.recipients),
                                    color = palette.inkSecondary,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (draft.excerpt.isNotBlank()) Text(draft.excerpt, color = palette.inkSecondary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(draft.createdAtText, color = palette.inkTertiary, fontSize = 12.sp)
                        }
                        IconButton(onClick = { deleting = draft; deleteError = null }) { Icon(LinDoIcons.Trash, LinDoComposerCopy.DELETE_DRAFT_DESC.text, tint = palette.inkSecondary, modifier = Modifier.size(20.dp)) }
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
                }
            }
        }
    }
    deleting?.let { draft ->
        LinDoConfirmDialog(
            title = LinDoComposerCopy.CONFIRM_DELETE_DRAFT.text,
            confirmLabel = LinDoCommonCopy.DELETE.text,
            busy = busy,
            error = deleteError,
            destructive = true,
            onDismiss = { deleting = null },
            onConfirm = {
                if (busy) return@LinDoConfirmDialog
                busy = true
                scope.launch {
                    try {
                        if (!draft.sequenceKnown) {
                            val cloudDeleted = try {
                                val sequence = LinuxDoRepository.fetchDraftSequence(draft.draftKey)
                                LinuxDoRepository.deleteCloudDraft(draft.draftKey, sequence)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (apiErr: ApiReadException) {
                                if (apiErr.kind == ApiFailureKind.Verification || apiErr.kind == ApiFailureKind.Authentication) {
                                    throw apiErr
                                }
                                true
                            } catch (_: Exception) {
                                true
                            }
                            if (!cloudDeleted) {
                                deleteError = LinDoComposerCopy.DRAFT_UPDATED_OTHER_DEVICE.text
                                return@launch
                            }
                            LocalComposerDrafts.remove(owner, draft.draftKey)
                            LocalComposerDrafts.forgetSequence(owner, draft.draftKey)
                            drafts = drafts.filterNot { it.draftKey == draft.draftKey }
                            deleting = null
                        } else {
                            if (LinuxDoRepository.deleteCloudDraft(draft.draftKey, draft.sequence)) {
                                LocalComposerDrafts.remove(owner, draft.draftKey)
                                LocalComposerDrafts.forgetSequence(owner, draft.draftKey)
                                drafts = drafts.filterNot { it.draftKey == draft.draftKey }
                                deleting = null
                            } else deleteError = LinDoComposerCopy.DRAFT_UPDATED_OTHER_DEVICE.text
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        deleteError = failure.message ?: LinDoComposerCopy.DELETE_DRAFT_FAILED_KEPT.text
                    } finally {
                        busy = false
                    }
                }
            }
        )
    }
    if (showClearAllConfirm) {
        LinDoConfirmDialog(
            title = LinDoComposerCopy.CONFIRM_CLEAR_ALL_DRAFTS.format(drafts.size),
            detail = LinDoComposerCopy.CLEAR_ALL_DRAFTS_DETAIL.text,
            confirmLabel = LinDoComposerCopy.CLEAR_ALL_DRAFTS.text,
            busy = busy,
            error = deleteError,
            destructive = true,
            onDismiss = { showClearAllConfirm = false },
            onConfirm = {
                if (busy) return@LinDoConfirmDialog
                busy = true
                deleteError = null
                val snapshot = drafts.toList()
                scope.launch {
                    try {
                        val remaining = snapshot.toMutableList()
                        for (item in snapshot) {
                            val deleted = if (!item.sequenceKnown) {
                                try {
                                    val seq = LinuxDoRepository.fetchDraftSequence(item.draftKey)
                                    LinuxDoRepository.deleteCloudDraft(item.draftKey, seq)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (apiErr: ApiReadException) {
                                    if (apiErr.kind == ApiFailureKind.Verification || apiErr.kind == ApiFailureKind.Authentication) {
                                        throw apiErr
                                    }
                                    true
                                } catch (_: Exception) {
                                    true
                                }
                            } else {
                                LinuxDoRepository.deleteCloudDraft(item.draftKey, item.sequence) || run {
                                    val seq = LinuxDoRepository.fetchDraftSequence(item.draftKey)
                                    LinuxDoRepository.deleteCloudDraft(item.draftKey, seq)
                                }
                            }
                            if (deleted) {
                                LocalComposerDrafts.remove(owner, item.draftKey)
                                LocalComposerDrafts.forgetSequence(owner, item.draftKey)
                                remaining.removeAll { it.draftKey == item.draftKey }
                            }
                        }
                        drafts = remaining.toList()
                        if (remaining.isEmpty()) {
                            showClearAllConfirm = false
                        } else {
                            deleteError = LinDoComposerCopy.PARTIAL_DRAFTS_UPDATED_REFRESH.text
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        deleteError = failure.message ?: LinDoComposerCopy.CLEAR_DRAFTS_FAILED_RETRY.text
                    } finally {
                        busy = false
                    }
                }
            }
        )
    }
}
