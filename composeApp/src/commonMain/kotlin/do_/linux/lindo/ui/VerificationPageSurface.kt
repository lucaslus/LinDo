package do_.linux.lindo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

internal fun computeVerificationCardHeightDp(maxHeightDp: Float, expanded: Boolean): Float {
    val safeCeiling = (maxHeightDp - 32f).coerceAtLeast(240f)
    // 原生 Cloudflare /challenge 页包含站点 Logo、Turnstile 复选框及隐私条款尾栏，
    // 380dp（扣除 44dp 顶栏后正文 336dp，对齐桌面端 480×380 卡片）可零裁剪完整呈现且无需篡改页面 DOM。
    val compactHeight = 380f.coerceAtMost(safeCeiling)
    return if (expanded) {
        (maxHeightDp * 0.78f).coerceAtLeast(compactHeight).coerceAtMost((maxHeightDp - 24f).coerceAtLeast(compactHeight))
    } else {
        compactHeight
    }
}

/** Shared presentation; verification uses a compact centered card matching desktop, while general web pages stay full-screen. */
@Composable
internal fun VerificationPageSurface(
    verification: Boolean,
    expanded: Boolean,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    val palette = LocalPalette.current
    if (!verification) {
        Box(
            Modifier
                .fillMaxSize()
                .background(palette.canvas)
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .imePadding()
        ) {
            content()
        }
        return
    }

    val cardBg = if (palette.isDark) Color(0xFF1A1E28) else palette.cardSurface
    val cardBorder = if (palette.isDark) Color.White.copy(alpha = 0.08f) else palette.hairline
    val cardShape = RoundedCornerShape(16.dp)

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.50f))
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .imePadding()
    ) {
        Box(
            Modifier
                .matchParentSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
        )
        val cardHeight = computeVerificationCardHeightDp(maxHeight.value, expanded).dp
        Box(
            Modifier
                .align(Alignment.Center)
                .padding(horizontal = 16.dp)
                .widthIn(max = 388.dp)
                .fillMaxWidth()
                .height(cardHeight)
                .shadow(
                    elevation = if (palette.isDark) 20.dp else 12.dp,
                    shape = cardShape,
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.45f),
                    spotColor = Color.Black.copy(alpha = 0.55f)
                )
                .clip(cardShape)
                .background(cardBg)
                .border(0.5.dp, cardBorder, cardShape)
        ) {
            // Behind the native view: block outside-dismiss taps inside the card without consuming WebView gestures.
            Box(
                Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {}
                    .clearAndSetSemantics {}
            )
            content()
        }
    }
}
