package do_.linux.lindo.ui

import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * macOS 原生质感桌面交互层 (`IndicationNodeFactory`)：
 * 1. 替换移动端 Material 默认的直角矩形 8%~12% 灰雾与水波纹扩散；
 * 2. 基于几何尺寸自动使用解析级抗锯齿 `drawRoundRect` 绘制细腻低对比度 Hover / Press 态，绝不产生四角溢出与 `.clip()` 模板锯齿；
 * 3. 严格执行桌面端 `:focus-visible`：鼠标点击绝不残留焦点灰底，仅键盘 Tab 导航展示柔和圆角焦点环。
 */
internal actual fun platformIndicationOverride(
    isDark: Boolean,
    tint: Color,
    focusRing: Color
): Indication? =
    DesktopCalmIndication(
        isDark = isDark,
        tint = tint,
        focusRing = focusRing
    )

private data class DesktopCalmIndication(
    val isDark: Boolean,
    val tint: Color,
    val focusRing: Color
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        DesktopCalmIndicationNode(
            interactionSource = interactionSource,
            isDark = isDark,
            tint = tint,
            focusRing = focusRing
        )
}

private class DesktopCalmIndicationNode(
    private val interactionSource: InteractionSource,
    private val isDark: Boolean,
    private val tint: Color,
    private val focusRing: Color
) : Modifier.Node(), DrawModifierNode {

    private var isHovered = false
    private var isPressed = false
    private var isKeyboardFocused = false

    private fun currentAlpha(): Float = when {
        isPressed -> if (isDark) 0.082f else 0.062f
        isHovered -> if (isDark) 0.046f else 0.034f
        else -> 0f
    }

    override fun onAttach() {
        coroutineScope.launch {
            val activeHovers = mutableSetOf<HoverInteraction.Enter>()
            val activePresses = mutableSetOf<PressInteraction.Press>()
            var pointerDown = false
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is HoverInteraction.Enter -> activeHovers.add(interaction)
                    is HoverInteraction.Exit -> activeHovers.remove(interaction.enter)
                    is PressInteraction.Press -> {
                        pointerDown = true
                        isKeyboardFocused = false
                        activePresses.add(interaction)
                    }
                    is PressInteraction.Release -> {
                        pointerDown = false
                        activePresses.remove(interaction.press)
                    }
                    is PressInteraction.Cancel -> {
                        pointerDown = false
                        activePresses.remove(interaction.press)
                    }
                    is FocusInteraction.Focus -> {
                        isKeyboardFocused = !pointerDown
                    }
                    is FocusInteraction.Unfocus -> {
                        isKeyboardFocused = false
                    }
                }
                val nextHover = activeHovers.isNotEmpty()
                val nextPress = activePresses.isNotEmpty()
                if (nextHover != isHovered || nextPress != isPressed || isKeyboardFocused) {
                    isHovered = nextHover
                    isPressed = nextPress
                    invalidateDraw()
                } else {
                    invalidateDraw()
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        val alpha = currentAlpha()
        val w = size.width
        val h = size.height
        if ((alpha > 0f || isKeyboardFocused) && w > 1f && h > 1f) {
            val isFullWidthRow = w >= 260.dp.toPx() && h >= 42.dp.toPx()
            val isNearSquareIcon = maxOf(w, h) <= 48.dp.toPx() && abs(w - h) <= 6.dp.toPx()
            val radiusPx = when {
                isFullWidthRow -> 0f
                isNearSquareIcon -> minOf(w, h) / 2f
                h <= 34.dp.toPx() -> minOf(7.dp.toPx(), h / 2f)
                h <= 46.dp.toPx() -> minOf(8.dp.toPx(), h / 2f)
                else -> 10.dp.toPx()
            }
            val corner = CornerRadius(radiusPx, radiusPx)
            if (alpha > 0f) {
                drawRoundRect(
                    color = tint.copy(alpha = alpha),
                    cornerRadius = corner
                )
            }
            if (isKeyboardFocused) {
                val strokePx = 1.dp.toPx()
                val half = strokePx / 2f
                drawRoundRect(
                    color = focusRing.copy(alpha = 0.68f),
                    topLeft = Offset(half, half),
                    size = Size((w - strokePx).coerceAtLeast(0f), (h - strokePx).coerceAtLeast(0f)),
                    cornerRadius = CornerRadius((radiusPx - half).coerceAtLeast(0f)),
                    style = Stroke(width = strokePx)
                )
            }
        }
        drawContent()
    }
}
