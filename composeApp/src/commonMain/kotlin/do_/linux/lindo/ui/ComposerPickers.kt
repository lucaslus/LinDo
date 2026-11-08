package do_.linux.lindo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import do_.linux.lindo.core.network.ComposerTagChoices
import do_.linux.lindo.core.network.ComposerTagOption
import do_.linux.lindo.core.network.LinuxDoCategoryMeta
import do_.linux.lindo.core.site.LinuxDoSiteRules
import do_.linux.lindo.ui.components.LinDoIcons

@Composable
internal fun ComposerCategoryPicker(
    categories: List<LinuxDoCategoryMeta>,
    selectedId: Int?,
    enabled: Boolean,
    onSelect: (LinuxDoCategoryMeta) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("选择版块", Modifier.weight(1f), color = palette.inkPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            IconButton(onClick = onDismiss) {
                Icon(LinDoIcons.Close, "收起版块", Modifier.size(16.dp), tint = palette.inkSecondary)
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val minimumCellWidth = 126 * LocalDensity.current.fontScale.coerceAtLeast(1f)
            val columns = (maxWidth.value / minimumCellWidth).toInt().coerceIn(1, 3)
            Column(
                Modifier.heightIn(max = 218.dp).verticalScroll(rememberScrollState()).selectableGroup()
            ) {
                categories.chunked(columns).forEach { group ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        group.forEach { category ->
                            val selected = selectedId == category.id
                            Row(
                                Modifier.weight(1f).heightIn(min = 46.dp)
                                    .selectable(selected, enabled = enabled, role = Role.RadioButton) { onSelect(category) }
                                    .padding(horizontal = 4.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                Box(Modifier.size(7.dp).background(LinuxDoSiteRules.parseHexColor(category.colorHex), CircleShape))
                                Text(
                                    category.name,
                                    color = if (selected) palette.activeChipText else palette.inkPrimary,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        repeat(columns - group.size) { Spacer(Modifier.weight(1f)) }
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
                }
            }
        }
    }
}

@Composable
internal fun ComposerTagPicker(
    query: String,
    onQueryChange: (String) -> Unit,
    selected: List<String>,
    choices: ComposerTagChoices,
    limit: Int,
    loading: Boolean,
    error: String?,
    enabled: Boolean,
    imeVisible: Boolean,
    onToggle: (ComposerTagOption) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("选择标签", Modifier.weight(1f), color = palette.inkPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (selected.isNotEmpty()) Text("${selected.size}/$limit", fontSize = 12.sp, color = palette.inkSecondary)
            TextButton(onClick = onDismiss) { Text("完成", color = palette.activeChipText) }
        }
        ComposerTextInput(query, onQueryChange, "搜索标签", enabled = enabled)
        HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
        when {
            loading -> LinDoLoadingIndicator(label = "加载标签", compactInline = true)
            error != null -> LinDoLoadFailure(error, onRetry)
            else -> Column(Modifier.heightIn(max = if (imeVisible) 120.dp else 180.dp).verticalScroll(rememberScrollState())) {
                val message = choices.forbiddenMessage
                    ?: choices.requiredGroup?.let { "请先选择「$it」中的标签" }
                if (message != null) {
                    Text(message, color = palette.inkSecondary, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
                }
                val options = (selected.map { ComposerTagOption(it) } + choices.options).distinctBy { it.name }
                if (options.isEmpty() && message == null) {
                    Text(
                        if (query.isBlank()) "这个版块暂无可用标签" else "没有匹配的标签",
                        color = palette.inkSecondary, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
                options.forEach { option ->
                    val checked = option.name in selected
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 44.dp)
                            .selectable(
                                selected = checked,
                                enabled = enabled && (checked || !option.disabled && selected.size < limit),
                                role = Role.Checkbox
                            ) { onToggle(option) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                option.name, fontSize = 13.sp,
                                color = if (option.disabled) palette.inkTertiary else palette.inkPrimary
                            )
                            if (option.disabled && !option.reason.isNullOrBlank()) {
                                Text(option.reason, color = palette.inkSecondary, fontSize = 11.5.sp)
                            }
                        }
                        if (checked) Icon(LinDoIcons.Check, "已选", Modifier.size(17.dp), tint = palette.accent)
                    }
                }
            }
        }
    }
}
