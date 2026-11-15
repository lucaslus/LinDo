package do_.linux.lindo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A status within the operation's existing surface, never another alert card. */
@Composable
internal fun VerificationInlineStatus(
    onVerify: () -> Unit,
    modifier: Modifier = Modifier,
    forSubmission: Boolean = true
) {
    val p = LocalPalette.current
    val busy = PlatformSessionRecovery.verifying
    Row(modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(12.dp), color = p.inkSecondary, strokeWidth = 1.5.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(if (busy) "验证中…" else if (PlatformSessionRecovery.verificationMessage == "连接未完成，请重试") "验证未完成"
            else if (forSubmission) "需验证后发送" else "需要站点验证",
            Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            color = p.inkSecondary, fontSize = 12.sp)
        TextButton(onClick = { if (busy) PlatformSessionRecovery.cancelVerification?.invoke() else onVerify() },
            contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.heightIn(min = 44.dp)) {
            Text(if (busy) "取消" else "验证", fontSize = 13.sp, color = if (busy) p.inkSecondary else p.activeChipText)
        }
    }
}
