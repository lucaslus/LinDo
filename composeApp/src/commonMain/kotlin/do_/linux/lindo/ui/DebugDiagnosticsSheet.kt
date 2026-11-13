package do_.linux.lindo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import do_.linux.lindo.core.copy.DiagnosticFilterTab
import do_.linux.lindo.core.copy.LinDoCommonCopy
import do_.linux.lindo.core.copy.LinDoMessagesCopy
import do_.linux.lindo.core.diagnostics.DiagnosticCategory
import do_.linux.lindo.core.diagnostics.DiagnosticLevel
import do_.linux.lindo.core.diagnostics.LinDoDiagnosticLogger
import do_.linux.lindo.ui.components.LinDoIcons
import kotlinx.coroutines.delay

@Composable
internal fun rememberDebugUnlockTapState(
    requiredTaps: Int = 7,
    tapWindowMs: Long = 2_400L,
    onUnlocked: () -> Unit
): Pair<String?, () -> Unit> {
    var tapCount by remember { mutableIntStateOf(0) }
    var lastTapAtMs by remember { mutableLongStateOf(0L) }
    var countdownHint by remember { mutableStateOf<String?>(null) }
    val latestOnUnlocked by rememberUpdatedState(onUnlocked)

    LaunchedEffect(tapCount) {
        if (tapCount in 4 until requiredTaps) {
            delay(tapWindowMs)
            countdownHint = null
            tapCount = 0
        }
    }

    val onTap: () -> Unit = {
        val now = System.currentTimeMillis()
        val nextCount = if (now - lastTapAtMs <= tapWindowMs) tapCount + 1 else 1
        lastTapAtMs = now
        if (nextCount >= requiredTaps) {
            tapCount = 0
            countdownHint = null
            LinDoDiagnosticLogger.info(
                DiagnosticCategory.SYSTEM,
                "DebugMode",
                "User unlocked DebugDiagnosticsSheet via consecutive version taps"
            )
            latestOnUnlocked()
        } else {
            tapCount = nextCount
            if (nextCount >= 4) {
                countdownHint = LinDoMessagesCopy.TAP_UNLOCK_DIAGNOSTICS_HINT.format(requiredTaps - nextCount)
            }
        }
    }
    return countdownHint to onTap
}

@Composable
internal fun ClearCacheOptionsDialog(
    onDismiss: () -> Unit,
    onClearCacheOnly: () -> Unit,
    onClearCacheAndLogs: () -> Unit
) {
    val palette = LocalPalette.current
    val logVer = LinDoDiagnosticLogger.logVersion
    val logCount = remember(logVer) { LinDoDiagnosticLogger.entryCount() }
    val logBytes = remember(logVer) { LinDoDiagnosticLogger.persistedFileSizeBytes() }
    val sizeSummary = remember(logCount, logBytes) {
        if (logCount == 0 && logBytes == 0L) {
            LinDoMessagesCopy.NO_DIAGNOSTIC_LOGS.text
        } else {
            val kb = (logBytes / 1024.0).coerceAtLeast(0.1)
            LinDoMessagesCopy.RECORDED_DIAGNOSTIC_LOGS_SUMMARY.format(
                logCount,
                String.format(java.util.Locale.US, "%.1f", kb)
            )
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.cardSurface,
        titleContentColor = palette.inkPrimary,
        textContentColor = palette.inkSecondary,
        title = {
            Text(LinDoMessagesCopy.ROW_CLEAR_LOCAL_CACHE.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = LinDoMessagesCopy.CLEAR_CACHE_DIALOG_DESC.text,
                    fontSize = 13.5.sp,
                    lineHeight = 20.sp,
                    color = palette.inkSecondary
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(palette.subtleSurface)
                        .border(0.6.dp, palette.hairline, RoundedCornerShape(10.dp))
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable {
                            onClearCacheOnly()
                            onDismiss()
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = LinDoMessagesCopy.CLEAR_ONLY_CACHE_OPTION.text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.inkPrimary
                    )
                    Text(
                        text = LinDoMessagesCopy.CLEAR_ONLY_CACHE_SUB.text,
                        fontSize = 12.sp,
                        color = palette.inkTertiary
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("clear_cache_and_logs_option")
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFFEF4444).copy(alpha = if (palette.isDark) 0.14f else 0.08f))
                        .border(0.6.dp, Color(0xFFEF4444).copy(alpha = 0.30f), RoundedCornerShape(10.dp))
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable {
                            onClearCacheAndLogs()
                            onDismiss()
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = LinDoMessagesCopy.CLEAR_CACHE_AND_LOGS_OPTION.text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (palette.isDark) Color(0xFFFCA5A5) else Color(0xFFDC2626)
                    )
                    Text(
                        text = sizeSummary,
                        fontSize = 12.sp,
                        color = palette.inkTertiary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(LinDoCommonCopy.CANCEL.text, color = palette.inkSecondary)
            }
        }
    )
}

@Composable
internal fun DebugDiagnosticsSheet(
    onDismiss: () -> Unit
) {
    val palette = LocalPalette.current
    val clipboard = LocalClipboardManager.current
    val logVer = LinDoDiagnosticLogger.logVersion
    var selectedTab by remember { mutableStateOf(DiagnosticFilterTab.ALL) }
    var statusBanner by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(statusBanner) {
        if (statusBanner != null) {
            delay(2_800L)
            statusBanner = null
        }
    }

    val entries = remember(logVer, selectedTab) {
        LinDoDiagnosticLogger.snapshotEntries(selectedTab.category)
    }
    val diskSizeBytes = remember(logVer) { LinDoDiagnosticLogger.persistedFileSizeBytes() }
    val diskPath = remember(logVer) { LinDoDiagnosticLogger.persistedFilePath() }

    LinDoFullScreenPage(onBack = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .testTag("debug_diagnostics_sheet")
                .background(palette.cardSurface)
        ) {
            LinDoPageTopBar(title = LinDoMessagesCopy.DIAGNOSTICS_SHEET_TITLE.text, onBack = onDismiss) {
                TextButton(
                    onClick = {
                        LinDoDiagnosticLogger.clearAllLogs()
                        statusBanner = LinDoMessagesCopy.CLEARED_ALL_DIAGNOSTIC_LOGS.text
                    },
                    modifier = Modifier.testTag("debug_clear_logs_btn")
                ) {
                    Text(LinDoMessagesCopy.CLEAR_LOGS_ACTION.text, color = Color(0xFFEF4444), fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.subtleSurface.copy(alpha = 0.65f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val kbText = String.format(java.util.Locale.US, "%.1f KB", diskSizeBytes / 1024.0)
                Text(
                    text = LinDoMessagesCopy.DIAGNOSTICS_HEADER_SUMMARY.format(
                        LinDoDiagnosticLogger.APP_VERSION_DISPLAY,
                        entries.size,
                        kbText
                    ),
                    fontSize = 12.2.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.inkSecondary
                )
                if (!diskPath.isNullOrBlank()) {
                    Text(
                        text = diskPath,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = palette.inkTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val actionShape = RoundedCornerShape(8.dp)
                    Row(
                        modifier = Modifier
                            .testTag("debug_copy_logs_btn")
                            .clip(actionShape)
                            .background(palette.cardSurface)
                            .border(0.6.dp, palette.hairline, actionShape)
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable {
                                val report = LinDoDiagnosticLogger.buildExportReport(selectedTab.category)
                                clipboard.setText(AnnotatedString(report))
                                statusBanner = LinDoMessagesCopy.DIAGNOSTICS_COPIED_REDACTED.text
                            }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(LinDoIcons.FaClone, null, Modifier.size(13.dp), tint = palette.inkPrimary)
                        Text(LinDoMessagesCopy.COPY_REPORT_BTN.text, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = palette.inkPrimary)
                    }

                    Row(
                        modifier = Modifier
                            .testTag("debug_export_logs_btn")
                            .clip(actionShape)
                            .background(palette.brandGold)
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable {
                                val report = LinDoDiagnosticLogger.buildExportReport(selectedTab.category)
                                val fileName = LinDoDiagnosticLogger.defaultExportFileName()
                                val handled = LinDoDiagnosticLogger.requestExport(fileName, report)
                                if (handled) {
                                    statusBanner = LinDoMessagesCopy.EXPORT_DIALOG_OPENED.format(fileName)
                                } else {
                                    clipboard.setText(AnnotatedString(report))
                                    statusBanner = LinDoMessagesCopy.EXPORT_FALLBACK_CLIPBOARD.text
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(LinDoIcons.FileText, null, Modifier.size(13.5.dp), tint = Color(0xFF111827))
                        Text(LinDoMessagesCopy.EXPORT_LOG_FILE_BTN.text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF111827))
                    }
                }

                statusBanner?.let { msg ->
                    Text(
                        text = msg,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = palette.activeChipText
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DiagnosticFilterTab.entries.forEach { tab ->
                        val active = selectedTab == tab
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (active) palette.activeChipBg else palette.cardSurface)
                                .border(
                                    width = 0.6.dp,
                                    color = if (active) palette.brandGold.copy(alpha = 0.55f) else palette.hairline,
                                    shape = CircleShape
                                )
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable { selectedTab = tab }
                                .padding(horizontal = 11.dp, vertical = 4.5.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = tab.labelCopy.text,
                                fontSize = 12.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (active) palette.activeChipText else palette.inkSecondary
                            )
                        }
                    }
                }
            }

            HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)

            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    LinDoEmptyState(message = LinDoMessagesCopy.NO_MATCHING_DIAGNOSTIC_LOGS.text)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(entries, key = { it.id }) { entry ->
                        var expanded by remember(entry.id) { mutableStateOf(entry.category == DiagnosticCategory.CRASH) }
                        val levelColor = when (entry.level) {
                            DiagnosticLevel.FATAL -> Color(0xFFEF4444)
                            DiagnosticLevel.ERROR -> Color(0xFFF97316)
                            DiagnosticLevel.WARN -> Color(0xFFF59E0B)
                            DiagnosticLevel.INFO -> Color(0xFF3B82F6)
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(palette.subtleSurface.copy(alpha = 0.6f))
                                .border(0.6.dp, palette.hairline, RoundedCornerShape(10.dp))
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable { expanded = !expanded }
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(levelColor.copy(alpha = 0.15f))
                                        .padding(horizontal = 5.dp, vertical = 1.5.dp)
                                ) {
                                    Text(
                                        text = "${entry.category.name} · ${entry.level.name}",
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = levelColor
                                    )
                                }
                                Text(
                                    text = entry.tag,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = palette.inkPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = entry.timestampIso.substringAfter('T').substringBefore('+'),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = palette.inkTertiary
                                )
                            }
                            SelectionContainer {
                                Text(
                                    text = entry.summary,
                                    fontSize = 12.5.sp,
                                    lineHeight = 18.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = palette.inkPrimary
                                )
                            }
                            if (!entry.detail.isNullOrBlank()) {
                                if (expanded) {
                                    HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
                                    SelectionContainer {
                                        Text(
                                            text = entry.detail,
                                            fontSize = 11.5.sp,
                                            lineHeight = 16.5.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = palette.inkSecondary
                                        )
                                    }
                                } else {
                                    Text(
                                        text = LinDoMessagesCopy.TAP_TO_EXPAND_DETAIL.text,
                                        fontSize = 11.sp,
                                        color = palette.activeChipText
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
