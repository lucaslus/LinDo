package do_.linux.lindo.ui

import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.luminance
import do_.linux.lindo.ui.components.LinDoIcons
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Inline choices start collapsed; presentation and motion are shared by every platform. */
@Composable
internal fun ExpandableSetting(
    icon: ImageVector,
    title: String,
    value: String,
    content: @Composable ColumnScope.() -> Unit
) {
    val palette = LocalPalette.current
    val animations = LocalLinDoAnimations.current
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = LinDoTokens.SettingRowHeight)
                .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(role = Role.Button, onClickLabel = if (expanded) "收起" else "展开") { expanded = !expanded }
                .padding(horizontal = LinDoTokens.PageHorizontalPadding, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, Modifier.size(20.dp), tint = palette.inkSecondary)
            Spacer(Modifier.width(14.dp))
            Text(title, modifier = Modifier.weight(1f), fontSize = 15.sp,
                fontWeight = FontWeight.Medium, color = palette.inkPrimary)
            if (!expanded) {
                Spacer(Modifier.width(12.dp))
                Text(value, modifier = Modifier.widthIn(max = 120.dp), fontSize = 13.sp,
                    color = palette.inkTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Icon(if (expanded) LinDoIcons.ChevronUp else LinDoIcons.ChevronDown,
                null, Modifier.size(14.dp), tint = palette.inkTertiary)
        }
        AnimatedVisibility(
            visible = expanded,
            enter = if (animations) expandVertically(expandFrom = Alignment.Top) + fadeIn() else EnterTransition.None,
            exit = if (animations) shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut() else ExitTransition.None
        ) {
            Column(Modifier.padding(horizontal = LinDoTokens.PageHorizontalPadding)
                .padding(bottom = 14.dp).widthIn(max = 600.dp).fillMaxWidth(), content = content)
        }
        HorizontalDivider(Modifier.padding(start = 50.dp, end = LinDoTokens.PageHorizontalPadding),
            thickness = 0.5.dp, color = palette.hairline)
    }
}

@Composable
internal fun <T> SettingsChoices(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit
) {
    val palette = LocalPalette.current
    BoxWithConstraints(Modifier.fillMaxWidth().selectableGroup()) {
        // Keep five text sizes on one line when they fit; retain usable targets at larger system text sizes.
        val columns = if (options.size > 3 && maxWidth / options.size < (48 * LocalDensity.current.fontScale).dp) 3 else options.size
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            options.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                    .clip(RoundedCornerShape(10.dp)).background(palette.subtleSurface.copy(alpha = 0.65f))
                    .padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    row.forEach { option ->
                        val active = option == selected
                        Box(Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (active) palette.activeChipBg else Color.Transparent)
                            .selectable(selected = active, role = Role.RadioButton, onClick = { onSelect(option) })
                            .padding(horizontal = 4.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                            Text(label(option), fontSize = 14.sp, lineHeight = 20.sp,
                                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                                color = if (active) palette.activeChipText else palette.inkSecondary,
                                textAlign = TextAlign.Center)
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
internal fun SettingsThemeChoices(selected: BrandThemeOption, onSelect: (BrandThemeOption) -> Unit) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BrandThemeOption.displayOrder.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { option ->
                    val active = selected == option
                    Column(Modifier.weight(1f).heightIn(min = 68.dp).clip(RoundedCornerShape(8.dp))
                        .selectable(selected = active, role = Role.RadioButton, onClick = { onSelect(option) })
                        .padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Box(Modifier.size(30.dp).background(option.swatchColor, CircleShape), contentAlignment = Alignment.Center) {
                            if (active) Icon(LinDoIcons.Check, null, Modifier.size(18.dp),
                                tint = if (option.swatchColor.luminance() > 0.4f) Color(0xFF101217) else Color.White)
                        }
                        Text(option.label, fontSize = 12.sp, lineHeight = 16.sp, textAlign = TextAlign.Center,
                            color = if (active) palette.activeChipText else palette.inkSecondary)
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
