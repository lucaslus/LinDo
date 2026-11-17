package do_.linux.lindo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Flat rows share state colours; content padding belongs AFTER the interaction modifier. */
@Composable
internal fun Modifier.rowStateLayer(
    source: MutableInteractionSource,
    enabled: Boolean = true,
    insetX: Dp = 0.dp,
    insetY: Dp = 0.dp,
    radius: Dp = 0.dp
): Modifier {
    val pressed = source.collectIsPressedAsState()
    val hovered = source.collectIsHoveredAsState()
    var keyboardFocused by remember(source) { mutableStateOf(false) }
    LaunchedEffect(source) {
        var pointerDown = false
        source.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    pointerDown = true
                    keyboardFocused = false
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    pointerDown = false
                }
                is FocusInteraction.Focus -> {
                    keyboardFocused = !pointerDown
                }
                is FocusInteraction.Unfocus -> {
                    keyboardFocused = false
                    pointerDown = false
                }
            }
        }
    }
    val palette = LocalPalette.current
    return drawBehind {
        if (!enabled) return@drawBehind
        val x = insetX.toPx()
        val y = insetY.toPx()
        val bounds = Size((size.width - 2 * x).coerceAtLeast(0f), (size.height - 2 * y).coerceAtLeast(0f))
        val alpha = when {
            pressed.value -> if (palette.isDark) 0.09f else 0.07f
            hovered.value -> if (palette.isDark) 0.045f else 0.035f
            else -> 0f
        }
        val corner = CornerRadius(radius.toPx())
        if (alpha > 0f) drawRoundRect(palette.inkPrimary.copy(alpha = alpha), Offset(x, y), bounds, corner)
        if (keyboardFocused) {
            val half = 0.5.dp.toPx()
            drawRoundRect(palette.brandGold.copy(alpha = 0.65f), Offset(x + half, y + half),
                Size((bounds.width - 2 * half).coerceAtLeast(0f), (bounds.height - 2 * half).coerceAtLeast(0f)),
                corner, style = Stroke(1.dp.toPx()))
        }
    }
}

internal fun Modifier.rowClickable(
    enabled: Boolean = true,
    role: Role? = null,
    onClickLabel: String? = null,
    onClick: () -> Unit
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    rowStateLayer(source, enabled)
        .clickable(source, indication = null, enabled = enabled, role = role, onClickLabel = onClickLabel, onClick = onClick)
}

internal fun Modifier.rowSelectable(selected: Boolean, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    rowStateLayer(source).selectable(selected, source, indication = null, role = Role.RadioButton, onClick = onClick)
}

internal fun Modifier.rowToggleable(value: Boolean, enabled: Boolean = true, onValueChange: (Boolean) -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    rowStateLayer(source, enabled).toggleable(value, source, indication = null, enabled = enabled, role = Role.Switch, onValueChange = onValueChange)
}
