package do_.linux.lindo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import do_.linux.lindo.core.compose.LocalComposerDrafts
import do_.linux.lindo.core.compose.toCloudItem
import do_.linux.lindo.core.network.*
import do_.linux.lindo.ui.components.LinDoIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

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
    var busy by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(owner, reload) {
        loading = true
        error = null
        val local = LocalComposerDrafts.all(owner).filter { it.hasContent }.map { it.toCloudItem() }
        drafts = local
        try {
            val cloud = LinuxDoRepository.fetchCloudDrafts()
            val cloudByKey = cloud.associateBy { it.draftKey }
            val mergedLocal = local.map { item ->
                val remote = cloudByKey[item.draftKey]
                if (remote != null && !item.sequenceKnown) item.copy(sequence = remote.sequence, sequenceKnown = true) else item
            }
            drafts = (cloud + mergedLocal).associateBy { it.draftKey }.values.toList()
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "暂时无法同步云端，本机草稿仍可使用" }
        finally { loading = false }
    }
    LinDoFullScreenPage(onBack = onDismiss, containerColor = palette.cardSurface) {
        LinDoPageTopBar(title = "草稿箱", onBack = onDismiss)
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            error?.let { LinDoLoadFailure(it, { reload++ }) }
            if (loading && drafts.isEmpty()) Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { LinDoLoadingIndicator(label = "读取草稿") }
            else LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
                if (drafts.isEmpty() && error == null) item { LinDoEmptyState("暂无草稿，未发送的内容会保存在这里") }
                items(drafts, key = { it.draftKey }) { draft ->
                    Row(Modifier.fillMaxWidth().clickable { onOpen(draft) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(draft.title, color = palette.inkPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    deleting?.let { draft -> LinDoConfirmDialog("删除这份草稿？", "「${draft.title}」的本机与云端草稿将被移除，已发布内容不会受影响。", "确认删除",
        busy = busy, error = deleteError, onDismiss = { deleting = null }, onConfirm = {
            if (!busy) {
                busy = true
                scope.launch { try {
                    val sequence = if (draft.sequenceKnown) draft.sequence else LinuxDoRepository.fetchDraftSequence(draft.draftKey)
                    if (LinuxDoRepository.deleteCloudDraft(draft.draftKey, sequence)) {
                        LocalComposerDrafts.remove(owner, draft.draftKey)
                        LocalComposerDrafts.forgetSequence(owner, draft.draftKey)
                        drafts = drafts.filterNot { it.draftKey == draft.draftKey }
                        deleting = null
                    } else deleteError = "草稿已在其他设备更新，请重新读取后核对"
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { deleteError = failure.message ?: "删除失败，草稿已保留" }
                finally { busy = false } }
            }
        }) }
}
