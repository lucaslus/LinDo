package do_.linux.lindo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

/** Shared presentation; the platform supplies the unmodified, interactive site page. */
@Composable
internal fun VerificationPageSurface(
    verification: Boolean,
    expanded: Boolean,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    val palette = LocalPalette.current
    BoxWithConstraints(Modifier.fillMaxSize()
        .background(if (verification) Color.Black.copy(alpha = .28f) else palette.canvas)
        .windowInsetsPadding(WindowInsets.statusBars)
        .windowInsetsPadding(WindowInsets.navigationBars)
        .imePadding()) {
        val fullHeight = !verification || expanded || maxHeight < 600.dp || LocalDensity.current.fontScale > 1.3f
        if (verification) Box(Modifier.matchParentSize().clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null,
            onClick = onDismiss
        ))
        val paneHeight = if (fullHeight) maxHeight else (maxHeight * .66f).coerceAtLeast(480.dp).coerceAtMost(maxHeight)
        Box(Modifier.align(Alignment.BottomCenter)
            .widthIn(max = if (verification) 600.dp else maxWidth)
            .fillMaxWidth().height(paneHeight)
            .clip(RoundedCornerShape(topStart = if (verification && !fullHeight) 8.dp else 0.dp,
                topEnd = if (verification && !fullHeight) 8.dp else 0.dp))
            .background(palette.canvas)) {
            // Behind the native view: block unused space without consuming WebView gestures.
            Box(Modifier.matchParentSize().clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null
            ) {}.clearAndSetSemantics {})
            content()
        }
    }
}
