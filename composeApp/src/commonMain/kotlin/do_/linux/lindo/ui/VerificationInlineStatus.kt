package do_.linux.lindo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import do_.linux.lindo.ui.components.LinDoIcons

/** Minimal pure-surface security-confirmation inline bar (zero muddy tint, shield icon + concise action). */
@Composable
internal fun VerificationInlineStatus(
    onVerify: () -> Unit,
    modifier: Modifier = Modifier,
    forSubmission: Boolean = true
) {
    val p = LocalPalette.current
    val busy = PlatformSessionRecovery.verifying
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 38.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (busy) {
            CircularProgressIndicator(
                Modifier.size(13.dp),
                color = p.brandGold,
                strokeWidth = 1.6.dp
            )
        } else {
            Icon(
                imageVector = LinDoIcons.ShieldCheck,
                contentDescription = null,
                modifier = Modifier.size(14.5.dp),
                tint = p.brandGold
            )
        }
        Spacer(Modifier.width(7.dp))
        Text(
            text = when {
                busy -> "安全确认中…"
                PlatformSessionRecovery.verificationMessage == "连接未完成，请重试" -> "安全确认未完成"
                forSubmission -> "需安全确认后发送"
                else -> "连接需安全确认"
            },
            modifier = Modifier
                .weight(1f)
                .semantics { liveRegion = LiveRegionMode.Polite },
            color = p.inkSecondary,
            fontSize = 12.5.sp
        )
        Row(
            modifier = Modifier
                .heightIn(min = 36.dp)
                .clickable {
                    if (busy) PlatformSessionRecovery.cancelVerification?.invoke() else onVerify()
                }
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = if (busy) "取消" else "去验证",
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                color = if (busy) p.inkSecondary else p.brandGold
            )
            if (!busy) {
                Text(
                    text = "›",
                    fontSize = 14.sp,
                    color = p.brandGold
                )
            }
        }
    }
}
