package do_.linux.lindo.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth

@Composable
internal fun LinDoInputDecoration(
    empty: Boolean,
    placeholder: String,
    textStyle: TextStyle,
    placeholderColor: Color,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.CenterStart,
    placeholderLines: Int = 1,
    innerTextField: @Composable () -> Unit
) {
    Box(modifier, contentAlignment = alignment) {
        Layout(content = {
            Box { innerTextField() }
            if (empty) {
                BasicText(
                    text = placeholder,
                    style = textStyle.copy(color = placeholderColor),
                    maxLines = placeholderLines,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }) { measurables, constraints ->
            val field = measurables[0].measure(constraints)
            val hint = measurables.getOrNull(1)?.measure(constraints.copy(minWidth = 0, minHeight = 0))
            val fieldBaseline = field[FirstBaseline]
            val hintBaseline = hint?.get(FirstBaseline) ?: AlignmentLine.Unspecified
            // Align the first line even when CJK fallback glyphs and the empty editor have different metrics.
            val offset = if (fieldBaseline != AlignmentLine.Unspecified && hintBaseline != AlignmentLine.Unspecified) {
                fieldBaseline - hintBaseline
            } else {
                (field.height - (hint?.height ?: field.height)) / 2
            }
            val fieldTop = (-offset).coerceAtLeast(0)
            val hintTop = offset.coerceAtLeast(0)
            layout(
                constraints.constrainWidth(maxOf(field.width, hint?.width ?: 0)),
                constraints.constrainHeight(maxOf(fieldTop + field.height, hintTop + (hint?.height ?: 0)))
            ) {
                hint?.placeRelative(0, hintTop)
                field.placeRelative(0, fieldTop)
            }
        }
    }
}
