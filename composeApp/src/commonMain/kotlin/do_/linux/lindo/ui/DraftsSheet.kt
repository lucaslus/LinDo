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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import do_.linux.lindo.core.compose.LocalComposerDrafts
import do_.linux.lindo.core.compose.toCloudItem
import do_.linux.lindo.core.network.*
import do_.linux.lindo.ui.components.LinDoIcons
import do_.linux.lindo.ui.components.LocalLinDoNetworkRestoreVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun resolveDraftKindLabel(draft: CloudDraftItem): String = when {
    draft.action == "privateMessage" ||
        draft.draftKey.startsWith("private_message") ||
        draft.draftKey.startsWith("new_private_message") -> "私信"
    draft.action.startsWith("edit") ||
        draft.draftKey.startsWith("edit_post_") -> "编辑"
    draft.draftKey == "new_topic" ||
        draft.action == "createTopic" -> "新话题"
    else -> "回复"
}

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
        if (error != null && error?.contains("站点验证") != true) {
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
        if (deleteError?.contains("站点验证") == true) {
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
        catch (failure: Exception) { error = failure.message ?: "云端草稿同步失败" }
        finally { loading = false }
    }
    LinDoFullScreenPage(onBack = onDismiss, containerColor = palette.cardSurface) {
        LinDoPageTopBar(
            title = "草稿箱",
            onBack = onDismiss,
            trailingContent = {
                if (drafts.isNotEmpty() && !busy) {
                    TextButton(onClick = {
                        deleteError = null
                        showClearAllConfirm = true
                    }) {
                        Text(
                            text = "清空全部",
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
            if (loading && drafts.isEmpty()) Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { LinDoLoadingIndicator(label = "读取草稿") }
            else LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
                if (drafts.isEmpty() && error == null) item { LinDoEmptyState("暂无草稿") }
                items(drafts, key = { it.draftKey }) { draft ->
                    val kindLabel = resolveDraftKindLabel(draft)
                    Row(Modifier.fillMaxWidth().clickable { onOpen(draft) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
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
                                    text = "收件人：${draft.recipients}",
                                    color = palette.inkSecondary,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (draft.excerpt.isNotBlank()) Text(draft.excerpt, color = palette.inkSecondary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(draft.createdAtText, color = palette.inkTertiary, fontSize = 12.sp)
                        }
                        IconButton(onClick = { deleting = draft; deleteError = null }) { Icon(LinDoIcons.Trash, "删除草稿", tint = palette.inkSecondary, modifier = Modifier.size(20.dp)) }
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
                }
            }
        }
    }
    deleting?.let { draft ->
        LinDoConfirmDialog(
            title = "要删除这份草稿吗？",
            confirmLabel = "删除",
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
                                deleteError = "草稿已在其他设备更新，请重新核对"
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
                            } else deleteError = "草稿已在其他设备更新，请重新核对"
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        deleteError = failure.message ?: "删除失败，草稿已保留"
                    } finally {
                        busy = false
                    }
                }
            }
        )
    }
    if (showClearAllConfirm) {
        LinDoConfirmDialog(
            title = "要清空全部 ${drafts.size} 份草稿吗？",
            detail = "清空后云端与本地未发布草稿均不可恢复。",
            confirmLabel = "清空全部",
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
                            deleteError = "部分草稿已在其他设备更新，请刷新后重试"
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        deleteError = failure.message ?: "清空草稿失败，请稍后重试"
                    } finally {
                        busy = false
                    }
                }
            }
        )
    }
}
