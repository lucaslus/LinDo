package do_.linux.lindo.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import do_.linux.lindo.core.copy.LinDoMessagesCopy
import do_.linux.lindo.core.diagnostics.DiagnosticCategory
import do_.linux.lindo.core.diagnostics.DiagnosticLevel
import do_.linux.lindo.core.diagnostics.DiagnosticLogEntry
import do_.linux.lindo.core.diagnostics.LinDoDiagnosticLogger
import do_.linux.lindo.core.network.CookieSentinel
import do_.linux.lindo.core.network.LinuxDoRepository
import do_.linux.lindo.core.network.UserApiKeyAuthManager
import do_.linux.lindo.ui.components.LinDoIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
internal fun ClearCacheOptionsDialog(onDismiss: () -> Unit, onClearCache: suspend () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LinDoConfirmDialog(
        title = "清理缓存？",
        confirmLabel = "清理",
        detail = "图片与临时数据将重新加载。\n账号和草稿会保留。",
        busy = busy,
        error = error,
        onDismiss = onDismiss,
        onConfirm = {
            if (!busy) {
                busy = true
                error = null
                scope.launch {
                    try {
                        onClearCache()
                        onDismiss()
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        error = "清理未完成，请重试"
                    } finally {
                        busy = false
                    }
                }
            }
        }
    )
}

private data class ParsedDiagnosticSummary(
    val displayTitle: String,
    val badgeLabel: String? = null,
    val latencyLabel: String? = null
)

private val httpLogRegex = Regex(
    """^(GET|POST|PUT|DELETE|PATCH|HEAD)\s+https?://linux\.do([^\s]*)\s+->\s+([^\[\(]+?)(?:\s+\[([^\]]+)\])?\s+\((\d+ms),\s*([^)]+)\)$"""
)

private fun parseCleanDiagnosticSummary(entry: DiagnosticLogEntry): ParsedDiagnosticSummary {
    val raw = entry.summary.trim()
    val match = httpLogRegex.matchEntire(raw)
    if (match != null) {
        val method = match.groupValues[1]
        val rawPath = match.groupValues[2].ifBlank { "/" }
        val cleanPath = if (rawPath.length > 56) {
            val base = rawPath.substringBefore('?')
            val query = rawPath.substringAfter('?', "")
            if (query.isNotEmpty() && base.length < 44) "$base?…" else rawPath.take(54) + "…"
        } else {
            rawPath
        }
        val rawStatus = match.groupValues[3].trim()
        val status = when {
            rawStatus == "IO_ERR(js_timeout)" -> "请求超时"
            rawStatus == "IO_ERR(csrf_timeout)" -> "CSRF 获取超时"
            rawStatus == "IO_ERR(csrf_net_err)" -> "CSRF 网络异常"
            rawStatus == "IO_ERR(csrf_failed)" -> "CSRF 获取失败"
            rawStatus == "IO_ERR(js_fetch_err)" -> "网络请求失败"
            rawStatus == "IO_ERR(kotlin_timeout)" -> "桥接无响应超时"
            rawStatus == "IO_ERR(rebuild_aborted)" -> "WebView 重建中断"
            rawStatus == "IO_ERR(webview_not_ready)" || rawStatus == "IO_ERR(webview_not_ready_pre)" -> "WebView 初始化超时"
            rawStatus == "IO_ERR(webview_null)" || rawStatus == "IO_ERR(webview_null_pre)" -> "WebView 未就绪"
            rawStatus == "IO_ERR(kotlin_cancelled)" -> "请求已取消"
            rawStatus == "IO_ERR(app_paused)" -> "切后台取消"
            rawStatus == "IO_ERR(stale_after_pause)" -> "切回前台已清理超时请求"
            rawStatus.startsWith("IO_ERR(") -> "连接中断 " + rawStatus.removePrefix("IO_ERR")
            else -> rawStatus.replace("IO_ERR", "连接中断")
        }
        val flags = match.groupValues[4]
        val rawMs = match.groupValues[5].removeSuffix("ms").toLongOrNull()
        val latency = when {
            rawMs == null -> match.groupValues[5]
            rawMs >= 1_000L -> String.format(java.util.Locale.US, "%.1fs", rawMs / 1000.0)
            else -> "${rawMs}ms"
        }
        val badge = when {
            status.contains("429") -> "限流"
            flags.contains("challenge", ignoreCase = true) -> "CF 验证"
            else -> null
        }
        return ParsedDiagnosticSummary(
            displayTitle = "$method $cleanPath  ·  $status",
            badgeLabel = badge,
            latencyLabel = latency
        )
    }
    val localized = when (raw) {
        "User unlocked DebugDiagnosticsSheet via consecutive version taps" -> "连续点击版本号进入诊断面板"
        "Session authenticated via in-app WebView" -> "应用内网页登录会话已确认"
        "Cloudflare verification completed" -> "站点安全验证已完成并恢复通行"
        else -> raw
            .replace("Callback accepted; OTP present=true", "系统浏览器一键授权回调成功")
            .replace("Callback accepted; OTP present=false", "系统浏览器授权回调已接收")
            .replace("https://linux.do/", "/")
    }
    return ParsedDiagnosticSummary(displayTitle = localized)
}

private fun formatCompactTimestamp(iso: String): String {
    // 2026-11-17T02:17:51.208+08:00 -> 11-17 02:17:51
    val datePart = iso.substringBefore('T')
    val monthDay = if (datePart.length >= 10) datePart.substring(5, 10) else ""
    val timePart = iso.substringAfter('T', "")
        .substringBefore('+')
        .substringBefore('Z')
        .substringBefore('.')
    return if (monthDay.isNotEmpty() && timePart.isNotEmpty()) "$monthDay $timePart" else timePart.ifEmpty { iso }
}

private fun levelLabelZh(level: DiagnosticLevel): String = when (level) {
    DiagnosticLevel.INFO -> "正常"
    DiagnosticLevel.WARN -> "警告"
    DiagnosticLevel.ERROR -> "异常"
    DiagnosticLevel.FATAL -> "崩溃"
}

private data class CollapsedDiagnosticItem(
    val entry: DiagnosticLogEntry,
    val repeatCount: Int
)

private fun collapseConsecutiveDiagnosticEntries(items: List<DiagnosticLogEntry>): List<CollapsedDiagnosticItem> {
    if (items.isEmpty()) return emptyList()
    val out = ArrayList<CollapsedDiagnosticItem>(items.size)
    for (item in items) {
        val prev = out.lastOrNull()
        val prevKey = prev?.let { parseCleanDiagnosticSummary(it.entry).displayTitle }
        val curKey = parseCleanDiagnosticSummary(item).displayTitle
        if (prev != null &&
            prev.entry.category == item.category &&
            prev.entry.level == item.level &&
            prev.entry.tag == item.tag &&
            prevKey == curKey
        ) {
            out[out.lastIndex] = prev.copy(repeatCount = prev.repeatCount + 1)
        } else {
            out.add(CollapsedDiagnosticItem(entry = item, repeatCount = 1))
        }
    }
    return out
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
    var showClearConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(statusBanner) {
        if (statusBanner != null) {
            delay(2_600L)
            statusBanner = null
        }
    }

    val allEntries = remember(logVer) { LinDoDiagnosticLogger.snapshotEntries(null) }
    val entries = remember(allEntries, selectedTab) {
        val cat = selectedTab.category
        if (cat == null) allEntries else allEntries.filter { it.category == cat }
    }
    val collapsedEntries = remember(entries) { collapseConsecutiveDiagnosticEntries(entries) }
    val categoryCounts = remember(allEntries) {
        allEntries.groupingBy { it.category }.eachCount()
    }
    val diskSizeBytes = remember(logVer) { LinDoDiagnosticLogger.persistedFileSizeBytes() }

    val statusSnapshot = remember(logVer) {
        val cookies = runCatching { CookieSentinel.resolveWinningCookies(targetHost = "linux.do") }.getOrDefault(emptyMap())
        val hasT = cookies["_t"]?.value?.let { it.isNotBlank() && it != "demo_session_token" } == true
        val hasCf = cookies["cf_clearance"]?.value?.isNotBlank() == true
        val isAuth = UserApiKeyAuthManager.isAuthorized()
        val cfCooldown = LinuxDoRepository.isChallengeCooldownActive()
        val rateLimited = LinuxDoRepository.isRateLimitedNow()
        Triple(isAuth && hasT, hasCf, cfCooldown || rateLimited)
    }

    LinDoFullScreenPage(onBack = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .testTag("debug_diagnostics_sheet")
                .background(palette.cardSurface)
        ) {
            // 1. 统一的 Lindo 冷静顶栏：无刺眼色块，紧凑胶囊操作
            LinDoPageTopBar(title = LinDoMessagesCopy.DIAGNOSTICS_SHEET_TITLE.text, onBack = onDismiss) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(end = 6.dp)
                ) {
                    val pillShape = CircleShape
                    Row(
                        modifier = Modifier
                            .testTag("debug_copy_logs_btn")
                            .clip(pillShape)
                            .background(palette.subtleSurface)
                            .border(0.5.dp, palette.hairline, pillShape)
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable {
                                val report = LinDoDiagnosticLogger.buildExportReport(selectedTab.category)
                                clipboard.setText(AnnotatedString(report))
                                statusBanner = LinDoMessagesCopy.DIAGNOSTICS_COPIED_REDACTED.text
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(LinDoIcons.FaClone, null, Modifier.size(11.5.dp), tint = palette.inkSecondary)
                        Text("复制", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = palette.inkPrimary)
                    }

                    Row(
                        modifier = Modifier
                            .testTag("debug_export_logs_btn")
                            .clip(pillShape)
                            .background(palette.activeChipBg)
                            .border(0.5.dp, palette.brandGold.copy(alpha = 0.42f), pillShape)
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
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(LinDoIcons.FileText, null, Modifier.size(12.dp), tint = palette.activeChipText)
                        Text("导出", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = palette.activeChipText)
                    }

                    if (allEntries.isNotEmpty()) {
                        IconButton(
                            onClick = { showClearConfirm = true },
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("debug_clear_logs_btn")
                        ) {
                            Icon(
                                imageVector = LinDoIcons.Trash,
                                contentDescription = LinDoMessagesCopy.CLEAR_LOGS_ACTION.text,
                                modifier = Modifier.size(15.dp),
                                tint = palette.inkSecondary
                            )
                        }
                    }
                }
            }

            // 2. 轻量状态摘要栏（隐藏丑陋的内部沙盒文件路径，聚焦关键运行指标）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val kbText = String.format(java.util.Locale.US, "%.1f KB", diskSizeBytes / 1024.0)
                val (sessionHealthy, hasCfClearance, guardCooling) = statusSnapshot
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${LinDoDiagnosticLogger.APP_VERSION_DISPLAY}  ·  ${allEntries.size} 条 ($kbText)",
                        fontSize = 12.sp,
                        color = palette.inkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DiagnosticDotPill(
                            dotColor = if (sessionHealthy) Color(0xFF10B981) else palette.inkTertiary,
                            label = if (sessionHealthy) "会话已保持" else "访客模式"
                        )
                        DiagnosticDotPill(
                            dotColor = when {
                                guardCooling -> Color(0xFFF59E0B)
                                hasCfClearance -> Color(0xFF10B981)
                                else -> palette.inkTertiary
                            },
                            label = when {
                                guardCooling -> "保护冷却中"
                                hasCfClearance -> "CF 凭证有效"
                                else -> "通道就绪"
                            }
                        )
                    }
                }

                AnimatedVisibility(visible = statusBanner != null) {
                    statusBanner?.let { msg ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(palette.subtleSurface)
                                .border(0.5.dp, palette.hairline, RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(LinDoIcons.Check, null, Modifier.size(13.dp), tint = palette.activeChipText)
                            Text(
                                text = msg,
                                fontSize = 12.sp,
                                color = palette.inkPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // 3. 分类筛选胶囊栏（与消息页筛选器风格完全对齐）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DiagnosticFilterTab.entries.forEach { tab ->
                        val active = selectedTab == tab
                        val count = if (tab.category == null) allEntries.size else (categoryCounts[tab.category] ?: 0)
                        Row(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (active) palette.activeChipBg else palette.subtleSurface.copy(alpha = 0.65f))
                                .border(
                                    width = 0.5.dp,
                                    color = if (active) palette.brandGold.copy(alpha = 0.45f) else Color.Transparent,
                                    shape = CircleShape
                                )
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable { selectedTab = tab }
                                .padding(horizontal = 11.dp, vertical = 4.5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = tab.labelCopy.text,
                                fontSize = 12.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (active) palette.activeChipText else palette.inkSecondary
                            )
                            if (count > 0) {
                                Text(
                                    text = count.toString(),
                                    fontSize = 11.sp,
                                    color = if (active) palette.activeChipText.copy(alpha = 0.85f) else palette.inkTertiary
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)

            // 4. 扁平化 0.5dp 内缩分割线列表（彻底告别厚重粗糙的方框堆叠）
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
                    contentPadding = PaddingValues(vertical = 2.dp)
                ) {
                    itemsIndexed(collapsedEntries, key = { _, it -> it.entry.id }) { index, collapsed ->
                        val entry = collapsed.entry
                        val repeatCount = collapsed.repeatCount
                        var expanded by remember(entry.id) { mutableStateOf(entry.category == DiagnosticCategory.CRASH) }
                        val parsed = remember(entry.id, entry.summary) { parseCleanDiagnosticSummary(entry) }
                        val compactTime = remember(entry.id, entry.timestampIso) { formatCompactTimestamp(entry.timestampIso) }
                        val dotColor = when (entry.level) {
                            DiagnosticLevel.FATAL, DiagnosticLevel.ERROR -> Color(0xFFEF4444)
                            DiagnosticLevel.WARN -> Color(0xFFF59E0B)
                            DiagnosticLevel.INFO -> if (entry.category == DiagnosticCategory.AUTH) Color(0xFF10B981) else Color(0xFF3B82F6)
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable { expanded = !expanded }
                                .padding(horizontal = 16.dp, vertical = 11.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .padding(top = 6.dp)
                                        .size(6.5.dp)
                                        .clip(CircleShape)
                                        .background(dotColor)
                                )

                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = "${entry.category.labelZh} · ${levelLabelZh(entry.level)}",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = dotColor
                                        )
                                        if (entry.tag.isNotBlank() && entry.tag != "App") {
                                            Text(
                                                text = entry.tag,
                                                fontSize = 11.5.sp,
                                                color = palette.inkTertiary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        parsed.badgeLabel?.let { badge ->
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(dotColor.copy(alpha = 0.12f))
                                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                                            ) {
                                                Text(
                                                    text = badge,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = dotColor
                                                )
                                            }
                                        }
                                        if (repeatCount > 1) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(CircleShape)
                                                    .background(palette.subtleSurface)
                                                    .border(0.5.dp, palette.hairline, CircleShape)
                                                    .padding(horizontal = 5.5.dp, vertical = 0.5.dp)
                                            ) {
                                                Text(
                                                    text = "×$repeatCount",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = palette.inkSecondary
                                                )
                                            }
                                        }
                                        Spacer(Modifier.weight(1f))
                                        parsed.latencyLabel?.let { lat ->
                                            Text(
                                                text = lat,
                                                fontSize = 11.sp,
                                                color = palette.inkTertiary
                                            )
                                        }
                                        Text(
                                            text = compactTime,
                                            fontSize = 11.sp,
                                            color = palette.inkTertiary
                                        )
                                    }

                                    Text(
                                        text = parsed.displayTitle,
                                        fontSize = 13.2.sp,
                                        lineHeight = 19.sp,
                                        color = palette.inkPrimary,
                                        maxLines = if (expanded) 6 else 2,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    AnimatedVisibility(visible = expanded) {
                                        Column(
                                            modifier = Modifier
                                                .padding(top = 6.dp)
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(palette.subtleSurface)
                                                .border(0.5.dp, palette.hairline, RoundedCornerShape(8.dp))
                                                .padding(10.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = entry.timestampIso,
                                                    fontSize = 10.5.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = palette.inkTertiary
                                                )
                                                Text(
                                                    text = "复制单条",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = palette.activeChipText,
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .clickable {
                                                            val single = buildString {
                                                                append("[${entry.timestampIso}] [${entry.level.name}] [${entry.category.name}] [${entry.tag}] ")
                                                                append(entry.summary)
                                                                if (!entry.detail.isNullOrBlank()) {
                                                                    append('\n')
                                                                    append(entry.detail)
                                                                }
                                                            }
                                                            clipboard.setText(AnnotatedString(single))
                                                            statusBanner = "已复制该条诊断记录（已自动脱敏）"
                                                        }
                                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                            }
                                            SelectionContainer {
                                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Text(
                                                        text = entry.summary,
                                                        fontSize = 11.5.sp,
                                                        lineHeight = 16.5.sp,
                                                        fontFamily = FontFamily.Monospace,
                                                        color = palette.inkPrimary
                                                    )
                                                    if (!entry.detail.isNullOrBlank()) {
                                                        HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
                                                        Text(
                                                            text = entry.detail,
                                                            fontSize = 11.sp,
                                                            lineHeight = 16.sp,
                                                            fontFamily = FontFamily.Monospace,
                                                            color = palette.inkSecondary
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                Icon(
                                    imageVector = if (expanded) LinDoIcons.ChevronUp else LinDoIcons.ChevronDown,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(top = 4.dp)
                                        .size(14.dp),
                                    tint = palette.inkTertiary.copy(alpha = 0.7f)
                                )
                            }
                        }

                        if (index < collapsedEntries.lastIndex) {
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = palette.hairline,
                                modifier = Modifier.padding(start = 32.dp, end = 16.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        LinDoConfirmDialog(
            title = "清空诊断日志？",
            confirmLabel = LinDoMessagesCopy.CLEAR_LOGS_ACTION.text,
            detail = "本地保存的网络与异常诊断记录将被清空，不影响登录状态与草稿。",
            destructive = true,
            onDismiss = { showClearConfirm = false },
            onConfirm = {
                showClearConfirm = false
                LinDoDiagnosticLogger.clearAllLogs()
                statusBanner = LinDoMessagesCopy.CLEARED_ALL_DIAGNOSTIC_LOGS.text
            }
        )
    }
}

@Composable
private fun DiagnosticDotPill(
    dotColor: Color,
    label: String
) {
    val palette = LocalPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.5.dp),
        modifier = Modifier
            .clip(CircleShape)
            .background(palette.subtleSurface)
            .padding(horizontal = 7.5.dp, vertical = 3.dp)
    ) {
        Box(
            modifier = Modifier
                .size(5.5.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = label,
            fontSize = 11.sp,
            color = palette.inkSecondary,
            maxLines = 1
        )
    }
}
