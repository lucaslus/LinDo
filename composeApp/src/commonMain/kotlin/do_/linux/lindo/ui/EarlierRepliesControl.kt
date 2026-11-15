package do_.linux.lindo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import do_.linux.lindo.ui.components.LinDoIcons

/** A quiet pagination control between the topic summary and the first loaded reply. */
@Composable
internal fun EarlierRepliesControl(loading: Boolean, enabled: Boolean, error: Boolean, needsVerification: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
        TextButton(onClick = onClick, enabled = enabled,
            modifier = Modifier.heightIn(min = 44.dp).semantics { liveRegion = LiveRegionMode.Polite },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
            if (loading) CircularProgressIndicator(Modifier.size(14.dp), color = palette.inkSecondary, strokeWidth = 1.5.dp)
            else Icon(LinDoIcons.ChevronUp, null, Modifier.size(16.dp), tint = palette.inkSecondary)
            Spacer(Modifier.width(6.dp))
            Text(when { loading -> "正在加载…"; needsVerification -> "验证后继续"; error -> "加载未完成，重试"; else -> "查看更早回复" },
                color = palette.inkSecondary, fontSize = 13.sp)
        }
    }
}
