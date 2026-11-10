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
    var targetFloor by remember { mutableIntStateOf(currentFloor.coerceIn(1, lastFloor)) }
    var enteringFloor by remember { mutableStateOf(false) }
    val target = targetFloor.coerceIn(1, lastFloor)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = palette.cardSurface,
        contentColor = palette.inkPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "楼层导航", Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.Bold
                )
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(palette.subtleSurface)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(LinDoIcons.Close, "关闭楼层导航", Modifier.size(16.dp), tint = palette.inkSecondary)
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)

            if (lastFloor > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { onJump(1, false) }) {
                        Icon(LinDoIcons.ChevronUp, "回到首楼", Modifier.size(20.dp), tint = palette.activeChipText)
                    }
                    TextButton(
                        modifier = Modifier.weight(1f),
                        onClick = { enteringFloor = !enteringFloor }
                    ) {
                        Text(
                            if (enteringFloor) "返回进度条" else "第 $target / $lastFloor 楼",
                            color = palette.inkPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                        )
                        if (!enteringFloor) {
                            Icon(
                                LinDoIcons.Edit, "输入楼号",
                                Modifier.padding(start = 6.dp).size(14.dp), tint = palette.inkSecondary
                            )
                        }
                    }
                    IconButton(onClick = { onJump(lastFloor, false) }) {
                        Icon(LinDoIcons.ChevronDown, "直达末楼", Modifier.size(20.dp), tint = palette.activeChipText)
                    }
                }

                if (enteringFloor) {
                    ExactFloorInput(target, lastFloor, onJump = { onJump(it, true) })
                } else {
                    val sliderColors = SliderDefaults.colors(
                        thumbColor = palette.brandGold,
                        activeTrackColor = palette.brandGold,
                        inactiveTrackColor = palette.inkTertiary.copy(alpha = 0.35f)
                    )
                    Slider(
                        value = target.toFloat(),
                        onValueChange = { targetFloor = it.roundToInt() },
                        valueRange = 1f..lastFloor.toFloat(),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "目标楼层" },
                        colors = sliderColors,
                        track = {
                            SliderDefaults.Track(
                                sliderState = it, modifier = Modifier.height(4.dp),
                                colors = sliderColors, drawStopIndicator = null
                            )
                        }
                    )
                    TextButton(
                        onClick = { onJump(target, true) },
                        modifier = Modifier.align(Alignment.End)
                    ) { Text("前往第 $target 楼", color = palette.activeChipText, fontWeight = FontWeight.SemiBold) }
                }
            } else {
                Text("只有首楼", Modifier.padding(bottom = 12.dp), color = palette.inkSecondary, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun ExactFloorInput(initialFloor: Int, lastFloor: Int, onJump: (Int) -> Unit) {
    val palette = LocalPalette.current
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var input by remember {
        val initial = initialFloor.toString()
        mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length)))
    }
    val selected = input.text.toIntOrNull()?.takeIf { it in 1..lastFloor }
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
                .semantics { contentDescription = "楼号" },
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(palette.brandGold),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { selected?.let(onJump) }),
            decorationBox = { inner ->
                LinDoInputDecoration(
                    empty = input.text.isEmpty(),
                    placeholder = "输入楼层 (1–$lastFloor)",
                    textStyle = style,
                    placeholderColor = palette.inkSecondary,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    innerTextField = inner
                )
            }
        )
        TextButton(enabled = selected != null, onClick = { selected?.let(onJump) }) {
            Text(
                "前往",
                color = if (selected != null) palette.activeChipText else palette.inkTertiary,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
    Spacer(Modifier.height(4.dp))
}
