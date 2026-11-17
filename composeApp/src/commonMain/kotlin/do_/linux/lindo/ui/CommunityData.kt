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
    var pending by remember(username, generation) { mutableStateOf(false) }
    var requested by rememberSaveable(username, generation) { mutableStateOf(false) }
    var error by remember(username, generation) { mutableStateOf<String?>(null) }
    fun request() {
        if (pending || requested) return
        pending = true
        error = null
        scope.launch {
            try {
                LinuxDoRepository.requestUserArchive()
                if (generation != UserApiKeyAuthManager.sessionGeneration) return@launch
                requested = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation == UserApiKeyAuthManager.sessionGeneration) error = failure.message ?: "未能确认导出结果，请先查看私信"
            } finally {
                pending = false
            }
        }
    }
    LinDoSettingRow(
        icon = LinDoIcons.FileText,
        title = "导出我的数据",
        value = when { pending -> "正在申请…"; requested -> "查看私信"; else -> "" },
        onClick = if (pending) null else if (requested) onOpenMessages else ::request
    )
    if (requested) {
        Text("生成后通过站内私信提供下载，包含逐日访问记录。",
            Modifier.padding(horizontal = LinDoTokens.PageHorizontalPadding, vertical = 8.dp),
            color = palette.inkTertiary, fontSize = 12.sp, lineHeight = 18.sp)
    }
    error?.let { message ->
        val needsVerification = message.isSiteVerificationError()
        Row(Modifier.fillMaxWidth().padding(horizontal = LinDoTokens.PageHorizontalPadding, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f), color = palette.inkSecondary, fontSize = 12.sp, lineHeight = 18.sp)
            TextButton(onClick = { if (needsVerification) onOpenWeb("https://linux.do/") else onOpenMessages() }) {
                Text(if (needsVerification) "去验证" else "查看私信", color = palette.activeChipText, fontSize = 12.sp)
            }
        }
    }
}
