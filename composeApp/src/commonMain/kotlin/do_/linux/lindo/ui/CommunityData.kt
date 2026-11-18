package do_.linux.lindo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import do_.linux.lindo.core.copy.isSiteVerificationError
import do_.linux.lindo.core.model.UserFullProfileSummary
import do_.linux.lindo.core.network.LinuxDoRepository
import do_.linux.lindo.core.network.UserApiKeyAuthManager
import do_.linux.lindo.ui.components.LinDoIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** Calendar days since joining, including the registration day; server dates use UTC. */
internal fun communityMembershipDays(createdDate: String, today: LocalDate): Long? =
    runCatching { ChronoUnit.DAYS.between(LocalDate.parse(createdDate), today) + 1 }
        .getOrNull()?.takeIf { it > 0 }

@Composable
internal fun CommunityActivitySummary(profile: UserFullProfileSummary) {
    val values = buildList {
        communityMembershipDays(profile.createdAtText, LocalDate.now(ZoneOffset.UTC))?.let { add("加入天数" to it.toString()) }
        profile.reportedDaysVisited?.let { add("累计访问" to it.toString()) }
        profile.communityScore?.let { add("社区分数" to it.toString()) }
    }
    if (values.isEmpty()) return
    val palette = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        values.forEach { (label, value) ->
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(value, color = palette.inkPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Text(label, color = palette.inkTertiary, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
    }
}

@Composable
internal fun UserArchiveExportRow(username: String, onOpenMessages: () -> Unit, onOpenWeb: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val palette = LocalPalette.current
    val generation = UserApiKeyAuthManager.sessionGeneration
    var showConfirmDialog by remember(username, generation) { mutableStateOf(false) }
    var pending by remember(username, generation) { mutableStateOf(false) }
    var requested by rememberSaveable(username, generation) { mutableStateOf(false) }
    var quotaReached by rememberSaveable(username, generation) { mutableStateOf(false) }
    var error by remember(username, generation) { mutableStateOf<String?>(null) }
    fun request() {
        if (pending || requested || quotaReached) return
        pending = true
        error = null
        scope.launch {
            try {
                LinuxDoRepository.requestUserArchive()
                if (generation != UserApiKeyAuthManager.sessionGeneration) return@launch
                requested = true
                showConfirmDialog = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation == UserApiKeyAuthManager.sessionGeneration) {
                    val msg = failure.message ?: "未能确认导出结果，请先查看私信"
                    val isQuota = msg.contains("每天只能导出一次") ||
                        msg.contains("一天只能") ||
                        msg.contains("once per day", ignoreCase = true) ||
                        msg.contains("24")
                    if (isQuota) {
                        quotaReached = true
                        showConfirmDialog = false
                    } else {
                        error = msg
                        showConfirmDialog = false
                    }
                }
            } finally {
                pending = false
            }
        }
    }
    LinDoSettingRow(
        icon = LinDoIcons.FileText,
        title = "导出我的数据",
        value = when {
            pending -> "正在申请…"
            requested -> "查看私信"
            quotaReached -> "今日已申请 · 查看私信"
            else -> ""
        },
        onClick = when {
            pending -> null
            requested || quotaReached -> onOpenMessages
            else -> ({ showConfirmDialog = true })
        }
    )
    if (requested) {
        Text("申请已提交。归档生成后将通过站内私信发送下载链接（包含发帖、互动与逐日访问记录）。",
            Modifier.padding(horizontal = LinDoTokens.PageHorizontalPadding, vertical = 8.dp),
            color = palette.inkTertiary, fontSize = 12.sp, lineHeight = 18.sp)
    } else if (quotaReached) {
        Row(Modifier.fillMaxWidth().padding(horizontal = LinDoTokens.PageHorizontalPadding, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("今日已申请过个人数据导出（社区限制每 24 小时 1 次），请前往站内私信查收下载链接。",
                Modifier.weight(1f), color = palette.inkTertiary, fontSize = 12.sp, lineHeight = 18.sp)
            TextButton(onClick = onOpenMessages) {
                Text("查看私信", color = palette.activeChipText, fontSize = 12.sp)
            }
        }
    }
    error?.let { message ->
        val needsVerification = message.isSiteVerificationError()
        Row(Modifier.fillMaxWidth().padding(horizontal = LinDoTokens.PageHorizontalPadding, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f), color = palette.inkSecondary, fontSize = 12.sp, lineHeight = 18.sp)
            TextButton(onClick = { if (needsVerification) onOpenWeb("https://linux.do/challenge#lindo-verify") else onOpenMessages() }) {
                Text(if (needsVerification) "去验证" else "查看私信", color = palette.activeChipText, fontSize = 12.sp)
            }
        }
    }
    if (showConfirmDialog) {
        LinDoConfirmDialog(
            title = "确认导出个人数据？",
            confirmLabel = if (pending) "正在申请…" else "确认导出",
            detail = "社区将在后台打包你的个人数据归档（包含发帖、互动、书签与逐日访问记录），生成完成后会通过「站内私信」发送下载链接给你。\n\n注：社区限制每 24 小时仅可申请导出 1 次。",
            busy = pending,
            onDismiss = { if (!pending) showConfirmDialog = false },
            onConfirm = ::request
        )
    }
}
