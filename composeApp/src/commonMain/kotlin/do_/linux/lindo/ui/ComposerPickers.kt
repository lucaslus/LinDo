package do_.linux.lindo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComposerCategoryPicker(
    categories: List<LinuxDoCategoryMeta>,
    selectedId: Int?,
    enabled: Boolean,
    onSelect: (LinuxDoCategoryMeta) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalPalette.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = palette.cardSurface,
        contentColor = palette.inkPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "选择发布版块",
                    color = palette.inkPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = LinDoIcons.Close,
                        contentDescription = "关闭版块抽屉",
                        modifier = Modifier.size(18.dp),
                        tint = palette.inkSecondary
                    )
                }
            }

            HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
            Spacer(modifier = Modifier.height(6.dp))

            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val minimumCellWidth = 148 * LocalDensity.current.fontScale.coerceAtLeast(1f)
                val columns = (maxWidth.value / minimumCellWidth).toInt().coerceIn(2, 3)
                val categoryRows = categories.chunked(columns)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState())
                        .selectableGroup()
                ) {
                    categoryRows.forEachIndexed { rowIdx, group ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            group.forEachIndexed { colIdx, category ->
                                val selected = selectedId == category.id
                                val dotColor = LinuxDoSiteRules.parseHexColor(category.colorHex)
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 46.dp)
                                        .background(
                                            if (selected) palette.brandGold.copy(alpha = 0.10f)
                                            else palette.cardSurface
                                        )
                                        .selectable(selected, enabled = enabled, role = Role.RadioButton) {
                                            onSelect(category)
                                        }
                                        .padding(horizontal = 10.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.5.dp)
                                            .background(dotColor, CircleShape)
                                    )
                                    Text(
                                        text = category.name,
                                        color = if (selected) palette.brandGold else palette.inkPrimary,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        fontSize = 13.5.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (selected) {
                                        Icon(
                                            imageVector = LinDoIcons.Check,
                                            contentDescription = "已选中",
                                            modifier = Modifier.size(15.dp),
                                            tint = palette.brandGold
                                        )
                                    }
                                }
                                if (colIdx < columns - 1) {
                                    Box(
                                        modifier = Modifier
                                            .width(0.5.dp)
                                            .height(22.dp)
                                            .background(palette.hairline)
                                    )
                                }
                            }
                            repeat(columns - group.size) { emptyIdx ->
                                Spacer(Modifier.weight(1f))
                                if (group.size + emptyIdx < columns - 1) {
                                    Spacer(Modifier.width(0.5.dp))
                                }
                            }
                        }
                        if (rowIdx < categoryRows.lastIndex) {
                            HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = palette.cardSurface,
        contentColor = palette.inkPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "选择话题标签",
                        color = palette.inkPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "已选 ${selected.size}/$limit",
                        color = palette.inkSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "完成",
                        color = palette.brandGold,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.5.sp
                    )
                }
            }

            HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
            Spacer(modifier = Modifier.height(10.dp))

            // 已选标签置顶一览
            if (selected.isNotEmpty()) {
                Text(
                    text = "已选标签（点击移除）",
                    color = palette.inkSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    selected.forEach { tagName ->
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(palette.brandGold.copy(alpha = 0.14f))
                                .border(1.dp, palette.brandGold, RoundedCornerShape(8.dp))
                                .clickable(enabled = enabled) {
                                    onToggle(ComposerTagOption(tagName))
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text(
                                text = "#$tagName",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.brandGold
                            )
                            Icon(
                                imageVector = LinDoIcons.Close,
                                contentDescription = "移除 $tagName",
                                modifier = Modifier.size(12.dp),
                                tint = palette.brandGold
                            )
                        }
                    }
                }
                HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
                Spacer(modifier = Modifier.height(10.dp))
            }

            when {
                loading -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        LinDoLoadingIndicator(label = "加载可用标签", compactInline = true)
                    }
                }
                error != null -> {
                    LinDoLoadFailure(error, onRetry)
                }
                else -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 180.dp, max = if (imeVisible) 260.dp else 400.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        val message = choices.forbiddenMessage
                            ?: choices.requiredGroup?.let { "请先选择「$it」中的标签" }
                        if (message != null) {
                            Text(
                                text = message,
                                color = palette.brandGold,
                                fontSize = 12.2.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }

                        val options = (selected.map { ComposerTagOption(it) } + choices.options).distinctBy { it.name }
                        if (options.isEmpty() && message == null) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (query.isBlank()) "当前版块暂无可用标签" else "没有匹配的标签",
                                    color = palette.inkSecondary,
                                    fontSize = 13.5.sp
                                )
                            }
                        } else {
                            Text(
                                text = "全部可选标签",
                                color = palette.inkSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                options.forEach { option ->
                                    val checked = option.name in selected
                                    val canClick = enabled && (checked || (!option.disabled && selected.size < limit))
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(9.dp))
                                            .background(
                                                when {
                                                    checked -> palette.brandGold.copy(alpha = 0.14f)
                                                    option.disabled -> palette.subtleSurface.copy(alpha = 0.45f)
                                                    else -> palette.subtleSurface
                                                }
                                            )
                                            .border(
                                                width = if (checked) 1.2.dp else 0.6.dp,
                                                color = if (checked) palette.brandGold else palette.hairline,
                                                shape = RoundedCornerShape(9.dp)
                                            )
                                            .selectable(
                                                selected = checked,
                                                enabled = canClick,
                                                role = Role.Checkbox
                                            ) { onToggle(option) }
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Text(
                                            text = "#${option.name}",
                                            fontSize = 13.2.sp,
                                            fontWeight = if (checked) FontWeight.Bold else FontWeight.Medium,
                                            color = when {
                                                checked -> palette.brandGold
                                                option.disabled -> palette.inkTertiary
                                                else -> palette.inkPrimary
                                            }
                                        )
                                        if (checked) {
                                            Icon(
                                                imageVector = LinDoIcons.Check,
                                                contentDescription = "已选",
                                                modifier = Modifier.size(14.dp),
                                                tint = palette.brandGold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
