package do_.linux.lindo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import do_.linux.lindo.core.copy.LinDoCommonCopy
import do_.linux.lindo.core.copy.LinDoComposerCopy
import do_.linux.lindo.core.copy.LinDoFeedCopy
import do_.linux.lindo.core.network.ComposerTagChoices
import do_.linux.lindo.core.network.ComposerTagOption
import do_.linux.lindo.core.network.LinuxDoCategoryMeta
import do_.linux.lindo.core.network.LinuxDoRepository
import do_.linux.lindo.core.site.LinuxDoSiteRules
import do_.linux.lindo.ui.components.LinDoIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComposerCategoryPicker(
    categories: List<LinuxDoCategoryMeta>,
    selectedId: Int?,
    enabled: Boolean,
    onSelect: (LinuxDoCategoryMeta) -> Unit,
    onFocusSelectParent: ((LinuxDoCategoryMeta) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val palette = LocalPalette.current
    if (do_.linux.lindo.ui.components.LocalIsWebModalActive.current) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = palette.cardSurface,
        contentColor = palette.inkPrimary
    ) {
        ComposerCategoryPickerContent(
            categories = categories,
            selectedId = selectedId,
            enabled = enabled,
            onSelect = onSelect,
            onFocusSelectParent = onFocusSelectParent,
            onDismiss = onDismiss
        )
    }
}

@Composable
internal fun ComposerCategoryPickerContent(
    categories: List<LinuxDoCategoryMeta>,
    selectedId: Int?,
    enabled: Boolean,
    onSelect: (LinuxDoCategoryMeta) -> Unit,
    onFocusSelectParent: ((LinuxDoCategoryMeta) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val palette = LocalPalette.current
    val catalogVersion = LinuxDoRepository.categoryCatalogVersion
    val postableCategories = remember(categories, catalogVersion) {
        categories.filter { it.id > 0 && (it.isVerifiedFromServer || !it.readRestricted) }
            .ifEmpty { categories.filter { it.id > 0 } }
    }
    val initialParentId = remember(selectedId, postableCategories, catalogVersion) {
        LinuxDoRepository.resolveParentCategoryId(selectedId) ?: postableCategories.firstOrNull()?.id
    }
    var focusedParentId by remember(initialParentId) { mutableStateOf(initialParentId) }
    val focusedParent = remember(focusedParentId, postableCategories, catalogVersion) {
        postableCategories.firstOrNull { it.id == focusedParentId } ?: postableCategories.firstOrNull()
    }
    val focusedSubCategories = remember(focusedParent?.id, catalogVersion) {
        focusedParent?.id?.let { LinuxDoRepository.getPostableSubCategoriesForParent(it) }.orEmpty()
    }

    LinDoScaledPopupContent {
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
                    text = LinDoComposerCopy.PICKER_SELECT_CATEGORY_TITLE.text,
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
                        contentDescription = LinDoComposerCopy.CLOSE_CATEGORY_DRAWER_DESC.text,
                        modifier = Modifier.size(16.dp),
                        tint = palette.inkSecondary
                    )
                }
            }

            HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)

            // 二级子版块 / 信任等级单行内联选择条（与发现页版块设计统一，无灰底教学框）
            if (focusedParent != null && focusedSubCategories.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 2.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = LinDoComposerCopy.PARENT_CATEGORY_COLON.format(focusedParent.name),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = palette.inkSecondary,
                        maxLines = 1
                    )
                    val parentSelected = selectedId == focusedParent.id
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .pointerHoverIcon(androidx.compose.ui.input.pointer.PointerIcon.Hand)
                            .clickable(enabled = enabled) { onSelect(focusedParent) }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            if (focusedParent.readRestricted) {
                                Icon(
                                    imageVector = LinDoIcons.FaLock,
                                    contentDescription = null,
                                    modifier = Modifier.size(10.dp),
                                    tint = if (parentSelected) palette.activeChipText else palette.inkTertiary
                                )
                            }
                            Text(
                                text = LinDoComposerCopy.MAIN_CATEGORY_CHIP.text,
                                fontSize = 12.5.sp,
                                fontWeight = if (parentSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (parentSelected) palette.activeChipText else palette.inkPrimary
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Box(
                            modifier = Modifier
                                .width(14.dp)
                                .height(2.dp)
                                .clip(CircleShape)
                                .background(if (parentSelected) palette.tabIndicatorColor else androidx.compose.ui.graphics.Color.Transparent)
                        )
                    }

                    focusedSubCategories.forEach { subCat ->
                        val subSelected = selectedId == subCat.id
                        val subLabel = subCat.subLabel ?: subCat.name.substringAfter(",", subCat.name).trim()
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .pointerHoverIcon(androidx.compose.ui.input.pointer.PointerIcon.Hand)
                                .clickable(enabled = enabled) { onSelect(subCat) }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                if (subCat.readRestricted || subCat.trustLevel != null) {
                                    Icon(
                                        imageVector = LinDoIcons.FaLock,
                                        contentDescription = null,
                                        modifier = Modifier.size(10.dp),
                                        tint = if (subSelected) palette.activeChipText else palette.inkTertiary
                                    )
                                }
                                Text(
                                    text = subLabel,
                                    fontSize = 12.5.sp,
                                    fontWeight = if (subSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (subSelected) palette.activeChipText else palette.inkPrimary
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Box(
                                modifier = Modifier
                                    .width(14.dp)
                                    .height(2.dp)
                                    .clip(CircleShape)
                                    .background(if (subSelected) palette.tabIndicatorColor else androidx.compose.ui.graphics.Color.Transparent)
                            )
                        }
                    }
                }
                HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
            }

            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val minimumCellWidth = 148 * LocalDensity.current.fontScale.coerceAtLeast(1f)
                val columns = (maxWidth.value / minimumCellWidth).toInt().coerceIn(2, 3)
                val categoryRows = postableCategories.chunked(columns)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 340.dp, max = 520.dp)
                        .verticalScroll(rememberScrollState())
                        .selectableGroup()
                ) {
                    categoryRows.forEachIndexed { rowIdx, group ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            group.forEachIndexed { colIdx, category ->
                                val subCats = LinuxDoRepository.getPostableSubCategoriesForParent(category.id)
                                val activeSubOfThisParent = subCats.firstOrNull { it.id == selectedId }
                                val selected = selectedId == category.id || activeSubOfThisParent != null
                                val dotColor = LinuxDoSiteRules.parseHexColor(category.colorHex)
                                val (catIcon, officialColor, isRestricted) = resolveOfficialCategoryVisual(
                                    categoryName = category.name,
                                    fallbackColor = dotColor,
                                    iconNameOverride = category.icon,
                                    restrictedOverride = category.readRestricted
                                )
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 46.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(palette.cardSurface)
                                        .pointerHoverIcon(androidx.compose.ui.input.pointer.PointerIcon.Hand)
                                        .selectable(selected, enabled = enabled, role = Role.RadioButton) {
                                            if (subCats.isNotEmpty() && focusedParentId != category.id) {
                                                focusedParentId = category.id
                                                if (onFocusSelectParent != null) {
                                                    onFocusSelectParent(category)
                                                } else {
                                                    onSelect(category)
                                                }
                                            } else {
                                                onSelect(category)
                                            }
                                        }
                                        .padding(horizontal = 10.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = catIcon,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = officialColor
                                    )
                                    if (isRestricted) {
                                        Icon(
                                            imageVector = LinDoIcons.FaLock,
                                            contentDescription = null,
                                            modifier = Modifier.size(11.dp),
                                            tint = palette.inkSecondary
                                        )
                                    }
                                    Text(
                                        text = category.name,
                                        color = if (selected) palette.activeChipText else palette.inkPrimary,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        fontSize = 13.5.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (activeSubOfThisParent != null) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            Icon(
                                                imageVector = LinDoIcons.FaLock,
                                                contentDescription = null,
                                                modifier = Modifier.size(9.5.dp),
                                                tint = palette.activeChipText
                                            )
                                            Text(
                                                text = activeSubOfThisParent.subLabel ?: LinDoComposerCopy.SUBCATEGORY_LV_FALLBACK.text,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = palette.activeChipText
                                            )
                                        }
                                    } else if (subCats.isNotEmpty()) {
                                        val lvs = subCats.mapNotNull { it.trustLevel }.sorted()
                                        val hint = if (lvs.size >= 2) {
                                            LinDoFeedCopy.SUBCATEGORY_LV_RANGE.format(lvs.first(), lvs.last())
                                        } else {
                                            LinDoComposerCopy.SUBCATEGORY_LV_FALLBACK.text
                                        }
                                        Text(
                                            text = hint,
                                            fontSize = 10.5.sp,
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                            color = if (selected) palette.activeChipText else palette.inkTertiary
                                        )
                                    } else if (selectedId == category.id) {
                                        Icon(
                                            imageVector = LinDoIcons.Check,
                                            contentDescription = LinDoCommonCopy.SELECTED.text,
                                            modifier = Modifier.size(15.dp),
                                            tint = palette.activeChipText
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComposerTagPicker(
    query: String,
    onQueryChange: (String) -> Unit,
    selected: List<String>,
    choices: ComposerTagChoices,
    limit: Int,
    canCreateTag: Boolean = false,
    loading: Boolean,
    error: String?,
    enabled: Boolean,
    imeVisible: Boolean,
    onToggle: (ComposerTagOption) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalPalette.current
    if (do_.linux.lindo.ui.components.LocalIsWebModalActive.current) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = palette.cardSurface,
        contentColor = palette.inkPrimary
    ) {
        LinDoScaledPopupContent {
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
                        text = LinDoComposerCopy.PICKER_SELECT_TAGS_TITLE.text,
                        color = palette.inkPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = LinDoComposerCopy.PICKER_SELECTED_TAGS_COUNT.format(selected.size, limit),
                        color = palette.inkSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = LinDoIcons.Close,
                        contentDescription = LinDoComposerCopy.CLOSE_TAG_DRAWER_DESC.text,
                        modifier = Modifier.size(16.dp),
                        tint = palette.inkSecondary
                    )
                }
            }

            HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
            Spacer(modifier = Modifier.height(10.dp))

            val searchStyle = TextStyle(
                fontSize = 13.5.sp,
                lineHeight = 18.sp,
                color = palette.inkPrimary,
                fontWeight = FontWeight.Medium
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 38.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.subtleSurface.copy(alpha = 0.7f))
                    .border(0.5.dp, palette.hairline, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = LinDoIcons.Search,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = palette.inkTertiary
                )
                Spacer(modifier = Modifier.width(8.dp))
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    enabled = enabled,
                    singleLine = true,
                    textStyle = searchStyle,
                    cursorBrush = SolidColor(palette.brandGold),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        LinDoInputDecoration(
                            empty = query.isEmpty(),
                            placeholder = LinDoComposerCopy.SEARCH_TAGS_PLACEHOLDER.text,
                            textStyle = searchStyle,
                            placeholderColor = palette.inkSecondary,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
                            innerTextField = inner
                        )
                    }
                )
                if (query.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .clickable { onQueryChange("") },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = LinDoIcons.Close,
                            contentDescription = LinDoCommonCopy.CLEAR.text,
                            modifier = Modifier.size(12.dp),
                            tint = palette.inkTertiary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)

            when {
                loading -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        LinDoLoadingIndicator(label = LinDoComposerCopy.LOADING_AVAILABLE_TAGS.text, compactInline = true)
                    }
                }
                error != null -> {
                    LinDoLoadFailure(error, onRetry)
                }
                else -> {
                    val cleanQuery = query.trim().removePrefix("#").trim()
                    val baseOptions = (selected.map { name ->
                        choices.options.find { it.name.equals(name, ignoreCase = true) } ?: ComposerTagOption(name)
                    } + choices.options).distinctBy { it.name.lowercase() }

                    val filteredOptions = if (cleanQuery.isEmpty()) {
                        baseOptions
                    } else {
                        baseOptions
                            .filter { it.name.contains(cleanQuery, ignoreCase = true) }
                            .sortedWith(
                                compareByDescending<ComposerTagOption> { it.name.equals(cleanQuery, ignoreCase = true) }
                                    .thenByDescending { it.name.startsWith(cleanQuery, ignoreCase = true) }
                                    .thenByDescending { it.name in selected }
                            )
                    }

                    val message = choices.forbiddenMessage
                        ?: choices.requiredGroup?.let { LinDoComposerCopy.SELECT_REQUIRED_GROUP_FIRST.format(it) }
                    val hasExactMatch = baseOptions.any { it.name.equals(cleanQuery, ignoreCase = true) }
                    val canCreateNewTag = canCreateTag &&
                        cleanQuery.isNotEmpty() &&
                        !hasExactMatch &&
                        choices.forbiddenMessage == null &&
                        choices.requiredGroup == null &&
                        selected.size < limit

                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val minimumCellWidth = 148 * LocalDensity.current.fontScale.coerceAtLeast(1f)
                        val columns = (maxWidth.value / minimumCellWidth).toInt().coerceIn(2, 3)
                        val tagRows = filteredOptions.chunked(columns)

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 320.dp, max = if (imeVisible) 340.dp else 480.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            if (message != null) {
                                Text(
                                    text = message,
                                    color = palette.brandGold,
                                    fontSize = 12.2.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                                HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
                            }

                            if (canCreateNewTag) {
                                val normalizedNewTag = cleanQuery.take(20)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 46.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(palette.brandGold.copy(alpha = 0.08f))
                                        .pointerHoverIcon(androidx.compose.ui.input.pointer.PointerIcon.Hand)
                                        .clickable(enabled = enabled) {
                                            onToggle(ComposerTagOption(normalizedNewTag))
                                            onQueryChange("")
                                        }
                                        .padding(horizontal = 10.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = LinDoIcons.Plus,
                                        contentDescription = LinDoComposerCopy.CREATE_TAG_DESC.text,
                                        modifier = Modifier.size(15.dp),
                                        tint = palette.brandGold
                                    )
                                    Text(
                                        text = LinDoComposerCopy.CREATE_TAG_LABEL.format(normalizedNewTag),
                                        color = palette.brandGold,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.5.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
                            }

                            if (tagRows.isEmpty() && !canCreateNewTag) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 36.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = if (cleanQuery.isEmpty()) {
                                            LinDoComposerCopy.NO_AVAILABLE_TAGS_IN_CATEGORY.text
                                        } else {
                                            LinDoComposerCopy.NO_MATCHING_TAGS.format(cleanQuery)
                                        },
                                        color = palette.inkSecondary,
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    if (cleanQuery.isNotEmpty() && !canCreateTag) {
                                        Text(
                                            text = LinDoComposerCopy.ONLY_EXISTING_TAGS_ALLOWED.text,
                                            color = palette.inkTertiary,
                                            fontSize = 11.8.sp
                                        )
                                    }
                                }
                            } else {
                                tagRows.forEachIndexed { rowIdx, group ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        group.forEachIndexed { colIdx, option ->
                                            val checked = option.name in selected
                                            val canClick = enabled && (checked || (!option.disabled && selected.size < limit))
                                            val tagVisual = resolveOfficialTagVisual(option.name)
                                            Row(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .heightIn(min = 46.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(
                                                        if (checked) palette.brandGold.copy(alpha = 0.10f)
                                                        else palette.cardSurface
                                                    )
                                                    .let { mod ->
                                                        if (canClick) mod.pointerHoverIcon(androidx.compose.ui.input.pointer.PointerIcon.Hand) else mod
                                                    }
                                                    .selectable(
                                                        selected = checked,
                                                        enabled = canClick,
                                                        role = Role.Checkbox
                                                    ) { onToggle(option) }
                                                    .padding(horizontal = 10.dp, vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                if (tagVisual != null) {
                                                    Icon(
                                                        imageVector = tagVisual.first,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(13.dp),
                                                        tint = if (option.disabled) palette.inkTertiary.copy(alpha = 0.45f) else tagVisual.second
                                                    )
                                                } else {
                                                    Icon(
                                                        imageVector = LinDoIcons.FaTag,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(11.5.dp),
                                                        tint = when {
                                                            checked -> palette.brandGold
                                                            option.disabled -> palette.inkTertiary.copy(alpha = 0.45f)
                                                            else -> palette.inkTertiary
                                                        }
                                                    )
                                                }
                                                Text(
                                                    text = option.name,
                                                    color = when {
                                                        checked -> palette.brandGold
                                                        option.disabled -> palette.inkTertiary
                                                        else -> palette.inkPrimary
                                                    },
                                                    fontWeight = if (checked) FontWeight.Bold else FontWeight.Medium,
                                                    fontSize = 13.5.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                if (checked) {
                                                    Icon(
                                                        imageVector = LinDoIcons.Check,
                                                        contentDescription = LinDoCommonCopy.SELECTED.text,
                                                        modifier = Modifier.size(15.dp),
                                                        tint = palette.brandGold
                                                    )
                                                } else if (option.count != null && option.count > 0) {
                                                    Text(
                                                        text = formatTagTopicCount(option.count),
                                                        color = palette.inkTertiary,
                                                        fontSize = 11.sp
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
                                    if (rowIdx < tagRows.lastIndex) {
                                        HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
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

private fun formatTagTopicCount(count: Int): String = when {
    count >= 10_000 -> String.format("%.1fw", count / 10_000.0)
    count >= 1_000 -> String.format("%.1fk", count / 1_000.0)
    else -> count.toString()
}
