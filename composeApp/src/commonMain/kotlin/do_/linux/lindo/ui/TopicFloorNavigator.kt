package do_.linux.lindo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import do_.linux.lindo.core.copy.FloorNavigatorMode
import do_.linux.lindo.core.copy.LinDoReaderCopy
import do_.linux.lindo.ui.components.LinDoIcons
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TopicFloorNavigator(
    currentFloor: Int,
    lastFloor: Int,
    onDismiss: () -> Unit,
    onJump: (floor: Int, exact: Boolean) -> Unit
) {
    val palette = LocalPalette.current
    val safeLastFloor = lastFloor.coerceAtLeast(1)
    var targetFloor by remember { mutableIntStateOf(currentFloor.coerceIn(1, safeLastFloor)) }
    var mode by remember { mutableStateOf(FloorNavigatorMode.SLIDER) }
    val target = targetFloor.coerceIn(1, safeLastFloor)

    if (do_.linux.lindo.ui.components.LocalIsWebModalActive.current) return

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = palette.cardSurface,
        contentColor = palette.inkPrimary
    ) {
        LinDoScaledPopupContent {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (safeLastFloor > 1) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    mode = when (mode) {
                                        FloorNavigatorMode.SLIDER -> FloorNavigatorMode.EXACT_INPUT
                                        FloorNavigatorMode.EXACT_INPUT -> FloorNavigatorMode.SLIDER
                                    }
                                }
                                .padding(vertical = 4.dp, horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text(
                                text = LinDoReaderCopy.FLOOR_COUNTER.format(target, safeLastFloor),
                                color = palette.inkPrimary,
                                fontSize = 15.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Icon(
                                imageVector = when (mode) {
                                    FloorNavigatorMode.SLIDER -> LinDoIcons.Edit
                                    FloorNavigatorMode.EXACT_INPUT -> LinDoIcons.Sliders
                                },
                                contentDescription = when (mode) {
                                    FloorNavigatorMode.SLIDER -> LinDoReaderCopy.FLOOR_ENTER_NUMBER_DESC.text
                                    FloorNavigatorMode.EXACT_INPUT -> LinDoReaderCopy.FLOOR_BACK_TO_SLIDER_DESC.text
                                },
                                modifier = Modifier.size(13.5.dp),
                                tint = palette.inkSecondary
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = { onJump(1, false) },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(LinDoReaderCopy.FIRST_FLOOR.text, color = palette.inkSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                        TextButton(
                            onClick = { onJump(safeLastFloor, false) },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(LinDoReaderCopy.LAST_FLOOR.text, color = palette.inkSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                        if (mode == FloorNavigatorMode.SLIDER) {
                            TextButton(
                                onClick = { onJump(target, false) },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(LinDoReaderCopy.JUMP_ACTION.text, color = palette.activeChipText, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .clickable(onClick = onDismiss),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(LinDoIcons.Close, LinDoReaderCopy.CLOSE_FLOOR_NAV_DESC.text, Modifier.size(15.dp), tint = palette.inkSecondary)
                        }
                    }

                    when (mode) {
                        FloorNavigatorMode.EXACT_INPUT -> {
                            ExactFloorInput(target, safeLastFloor, onJump = { onJump(it, true) })
                        }
                        FloorNavigatorMode.SLIDER -> {
                            val sliderColors = SliderDefaults.colors(
                                thumbColor = palette.brandGold,
                                activeTrackColor = palette.brandGold,
                                inactiveTrackColor = palette.inkTertiary.copy(alpha = 0.35f)
                            )
                            Slider(
                                value = target.toFloat(),
                                onValueChange = { targetFloor = it.roundToInt() },
                                valueRange = 1f..safeLastFloor.toFloat(),
                                modifier = Modifier.fillMaxWidth().semantics { contentDescription = LinDoReaderCopy.TARGET_FLOOR_SEMANTICS.text },
                                colors = sliderColors,
                                track = {
                                    SliderDefaults.Track(
                                        sliderState = it, modifier = Modifier.height(4.dp),
                                        colors = sliderColors, drawStopIndicator = null
                                    )
                                }
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(LinDoReaderCopy.SINGLE_FLOOR_TEXT.text, Modifier.weight(1f), color = palette.inkSecondary, fontSize = 13.5.sp)
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .clickable(onClick = onDismiss),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(LinDoIcons.Close, LinDoReaderCopy.CLOSE_FLOOR_NAV_DESC.text, Modifier.size(15.dp), tint = palette.inkSecondary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExactFloorInput(initialFloor: Int, lastFloor: Int, onJump: (Int) -> Unit) {
    val palette = LocalPalette.current
    val safeLastFloor = lastFloor.coerceAtLeast(1)
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var input by remember {
        val initial = initialFloor.coerceIn(1, safeLastFloor).toString()
        mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length)))
    }
    val selected = input.text.toIntOrNull()?.takeIf { it in 1..safeLastFloor }
    val style = TextStyle(color = palette.inkPrimary, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium)
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        BasicTextField(
            value = input,
            onValueChange = { next ->
                if (next.text.length <= 10 && next.text.all(Char::isDigit)) input = next
            },
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 42.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(palette.subtleSurface.copy(alpha = 0.7f))
                .border(0.5.dp, palette.hairline, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp)
                .focusRequester(focusRequester)
                .semantics { contentDescription = LinDoReaderCopy.FLOOR_NUMBER_SEMANTICS.text },
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(palette.brandGold),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { selected?.let(onJump) }),
            decorationBox = { inner ->
                LinDoInputDecoration(
                    empty = input.text.isEmpty(),
                    placeholder = LinDoReaderCopy.FLOOR_INPUT_PLACEHOLDER.format(safeLastFloor),
                    textStyle = style,
                    placeholderColor = palette.inkSecondary,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    innerTextField = inner
                )
            }
        )
        TextButton(enabled = selected != null, onClick = { selected?.let(onJump) }) {
            Text(
                LinDoReaderCopy.GO_TO_FLOOR_ACTION.text,
                color = if (selected != null) palette.activeChipText else palette.inkTertiary,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
    Spacer(Modifier.height(4.dp))
}
