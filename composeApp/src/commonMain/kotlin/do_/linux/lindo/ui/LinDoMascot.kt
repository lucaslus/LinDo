package do_.linux.lindo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.core.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import do_.linux.lindo.ui.components.LinDoIcons
import lindo.composeapp.generated.resources.Res
import lindo.composeapp.generated.resources.lindo_brand_mark
import lindo.composeapp.generated.resources.lindo_mascot_welcome
import lindo.composeapp.generated.resources.lindo_mascot_connecting
import org.jetbrains.compose.resources.painterResource

val LocalLinDoAnimations = staticCompositionLocalOf { true }
val LocalLinDoDarkTheme = staticCompositionLocalOf { false }

enum class LinDoMascotState(val asset: String, val title: String, val detail: String) {
    Loading("loading", "加载中", "加载中"),
    Refreshing("refreshing", "刷新中", "刷新中"),
    NetworkError("error", "网络异常", "网络连接异常"),
    Empty("empty", "暂无内容", "暂无内容"),
    Success("success", "已完成", "已完成"),
    End("end", "没有更多了", "没有更多了")
}

/**
 * 零度娘统一形象组件：
 * - 加载 / 刷新状态下自带灵动的「左右摇摆」不倒翁动效（不绘制外圈转圈光弧，仅保留零度娘本体）
 */
@Composable
fun LinDoMascot(
    state: LinDoMascotState = LinDoMascotState.Loading,
    size: Dp = 80.dp,
    compact: Boolean = false,
    animate: Boolean = true,
    showSpinnerRing: Boolean = false,
    modifier: Modifier = Modifier,
    useIconArtwork: Boolean = false
) {
    val isActivelyLoading = state in setOf(LinDoMascotState.Loading, LinDoMascotState.Refreshing)
    val moving = LocalLinDoAnimations.current && animate &&
        (isActivelyLoading || state == LinDoMascotState.Empty)

    val transition = if (moving) rememberInfiniteTransition(label = "lingdu_motion") else null
    val rotation = if (transition != null) {
        transition.animateFloat(
            initialValue = if (isActivelyLoading) -11f else -3.5f,
            targetValue = if (isActivelyLoading) 11f else 3.5f,
            animationSpec = infiniteRepeatable(
                animation = tween(if (isActivelyLoading) 480 else 1400, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "sway_rotation"
        )
    } else rememberUpdatedState(0f)

    val swayOffsetX = if (transition != null && isActivelyLoading) {
        transition.animateFloat(
            initialValue = -3.2f,
            targetValue = 3.2f,
            animationSpec = infiniteRepeatable(
                animation = tween(480, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "sway_offset_x"
        )
    } else rememberUpdatedState(0f)

    val bobOffsetY = if (transition != null && isActivelyLoading) {
        transition.animateFloat(
            initialValue = -1.8f,
            targetValue = 1.2f,
            animationSpec = infiniteRepeatable(
                animation = tween(240, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "sway_bob_y"
        )
    } else rememberUpdatedState(0f)

    val art = when {
        compact -> Res.drawable.lindo_brand_mark
        state == LinDoMascotState.Loading || state == LinDoMascotState.NetworkError -> Res.drawable.lindo_mascot_connecting
        else -> Res.drawable.lindo_mascot_welcome
    }

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        val artworkModifier = Modifier.fillMaxSize().graphicsLayer {
            rotationZ = rotation.value
            translationX = swayOffsetX.value * density
            translationY = bobOffsetY.value * density
            transformOrigin = TransformOrigin(0.5f, 0.85f)
        }
        val selected = LocalLinDoAppIcon.current?.artwork
        if (useIconArtwork && selected != null) {
            LinDoIconArtworkImage(selected, artworkModifier)
        } else {
            Image(painterResource(art), null, artworkModifier, contentScale = ContentScale.Fit)
        }
    }
}

/**
 * 灵动三点跳动波浪动效（`• • •`）
 */
@Composable
fun LinDoWaveDots(
    color: Color = if (LocalLinDoDarkTheme.current) Color(0xFFC7BFB3) else Color(0xFF746C61),
    dotSize: Dp = 4.dp,
    modifier: Modifier = Modifier
) {
    val moving = LocalLinDoAnimations.current
    val transition = if (moving) rememberInfiniteTransition(label = "wave_dots") else null
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.5.dp)
    ) {
        repeat(3) { idx ->
            val phaseShift = idx * 150
            val animProgress = if (transition != null) {
                transition.animateFloat(
                    initialValue = 0.25f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 480, delayMillis = 0, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse,
                        initialStartOffset = StartOffset(phaseShift)
                    ),
                    label = "dot_$idx"
                )
            } else rememberUpdatedState(0.75f)
            val p = animProgress.value
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .graphicsLayer {
                        translationY = -(p - 0.25f) * 3.2f * density
                        alpha = p
                    }
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

/**
 * 全局统一加载组件：
 * - 默认屏幕/容器居中展示：左右摇摆的零度娘 + 外圈转圈圈光弧 + 极简文案（`加载中`）+ 跳动点点点（`•••`）；
 * - `compactInline = true` 时提供紧凑行内模式（用于顶栏胶囊或底部加载条）。
 */
@Composable
fun LinDoLoadingIndicator(
    modifier: Modifier = Modifier,
    color: Color = if (LocalLinDoDarkTheme.current) Color(0xFFC7BFB3) else Color(0xFF746C61),
    trackColor: Color = Color.Transparent,
    progress: (() -> Float)? = null,
    label: String = "加载中",
    state: LinDoMascotState = LinDoMascotState.Loading,
    compactInline: Boolean = false,
    useIconArtwork: Boolean = false
) {
    val cleanLabel = remember(label) {
        val stripped = label
            .replace("零度娘正在", "")
            .replace("正在", "")
            .removeSuffix("...")
            .removeSuffix("…")
            .trim()
        when {
            stripped.isEmpty() || stripped.length > 6 -> "加载中"
            else -> stripped
        }
    }

    if (compactInline) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .background(trackColor)
                .semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                    progressBarRangeInfo = progress?.invoke()?.takeIf { it.isFinite() }?.let {
                        ProgressBarRangeInfo(it.coerceIn(0f, 1f), 0f..1f)
                    } ?: ProgressBarRangeInfo.Indeterminate
                },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            LinDoMascot(state = state, compact = true, size = 24.dp, animate = true, useIconArtwork = useIconArtwork)
            Spacer(Modifier.width(7.dp))
            Text(cleanLabel, fontSize = 12.sp, color = color, maxLines = 1)
            Spacer(Modifier.width(4.dp))
            LinDoWaveDots(color = color, dotSize = 3.5.dp)
        }
    } else {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 180.dp)
                .background(trackColor)
                .padding(vertical = 24.dp)
                .semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                    progressBarRangeInfo = progress?.invoke()?.takeIf { it.isFinite() }?.let {
                        ProgressBarRangeInfo(it.coerceIn(0f, 1f), 0f..1f)
                    } ?: ProgressBarRangeInfo.Indeterminate
                },
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                LinDoMascot(
                    state = state,
                    compact = false,
                    size = 56.dp,
                    animate = true,
                    showSpinnerRing = false,
                    useIconArtwork = useIconArtwork
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = cleanLabel,
                        fontSize = 12.2.sp,
                        fontWeight = FontWeight.Medium,
                        color = color,
                        maxLines = 1
                    )
                    Spacer(Modifier.width(5.dp))
                    LinDoWaveDots(color = color, dotSize = 4.dp)
                    progress?.invoke()?.takeIf { it.isFinite() }?.let { value ->
                        Spacer(Modifier.width(6.dp))
                        Text("${(value.coerceIn(0f, 1f) * 100).toInt()}%", fontSize = 11.sp, color = color)
                    }
                }
            }
        }
    }
}

@Composable
fun LinDoStatePanel(
    state: LinDoMascotState,
    title: String = state.title,
    detail: String? = state.detail,
    onRetry: (() -> Unit)? = null,
    actionLabel: String = if (state == LinDoMascotState.NetworkError) "重试" else "刷新",
    modifier: Modifier = Modifier,
    useIconArtwork: Boolean = false
) {
    if (state in setOf(LinDoMascotState.Loading, LinDoMascotState.Refreshing) && onRetry == null) {
        LinDoLoadingIndicator(
            modifier = modifier,
            label = title,
            state = state,
            useIconArtwork = useIconArtwork
        )
        return
    }
    val dark = LocalLinDoDarkTheme.current
    val secondaryText = if (dark) Color(0xFFB8AFA2) else Color(0xFF746C61)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (state == LinDoMascotState.NetworkError) {
            LinDoMascot(state = state, size = 48.dp, useIconArtwork = useIconArtwork, animate = false)
            Spacer(Modifier.height(8.dp))
            Text(
                detail?.takeIf { it.isNotBlank() } ?: title,
                color = secondaryText, fontSize = 12.sp,
                textAlign = TextAlign.Center, lineHeight = 17.sp
            )
        } else {
            LinDoMascot(state = state, size = 60.dp, useIconArtwork = useIconArtwork)
            Spacer(Modifier.height(8.dp))
            Text(
                text = title,
                color = secondaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp
            )
            if (!detail.isNullOrBlank() && detail != title) {
                Spacer(Modifier.height(5.dp))
                Text(detail, color = secondaryText, fontSize = 12.sp, textAlign = TextAlign.Center, lineHeight = 18.sp)
            }
        }
        if (onRetry != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onRetry,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Icon(
                    imageVector = LinDoIcons.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(13.5.dp),
                    tint = secondaryText
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = actionLabel,
                    color = secondaryText,
                    fontSize = 11.8.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun LinDoEmptyState(message: String, modifier: Modifier = Modifier) {
    LinDoStatePanel(state = LinDoMascotState.Empty, title = message, detail = null, modifier = modifier)
}

@Composable
fun LinDoFeedbackRow(message: String, state: LinDoMascotState, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .heightIn(min = 22.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically
    ) {
        LinDoMascot(state = state, compact = true, size = 18.dp)
        Spacer(Modifier.width(5.dp))
        Text(
            text = message,
            modifier = Modifier.weight(1f, fill = false),
            fontSize = 11.sp,
            lineHeight = 15.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            color = if (LocalLinDoDarkTheme.current) Color(0xFFB8AFA2) else Color(0xFF746C61)
        )
    }
}

@Composable
fun LinDoBusyButtonLabel(busy: Boolean, busyLabel: String, label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        if (busy) LinDoMascot(state = LinDoMascotState.Loading, compact = true, size = 18.dp)
        Text(
            text = if (busy) busyLabel else label,
            color = color,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Debug-only entry is guarded by FLAG_DEBUGGABLE in MainActivity. */
@Composable
fun LinDoMascotGallery(onClose: () -> Unit, onThemeChanged: (Boolean) -> Unit = {}, systemAnimationsEnabled: Boolean = true) {
    var dark by remember { mutableStateOf(false) }
    var animate by remember { mutableStateOf(true) }
    var transition by remember { mutableStateOf(false) }
    LaunchedEffect(dark) { onThemeChanged(dark) }
    CompositionLocalProvider(LocalLinDoAnimations provides (animate && systemAnimationsEnabled), LocalLinDoDarkTheme provides dark) {
        MaterialTheme(colorScheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
            primary = Color(0xFFF6B73C), onPrimary = Color(0xFF342820),
            primaryContainer = if (dark) Color(0xFF54411E) else Color(0xFFFFE8B0),
            onPrimaryContainer = if (dark) Color(0xFFFFE8B0) else Color(0xFF342820)
        )) {
            if (transition) {
                LinDoMascotLoginTransitionView(isDark = dark, progress = Float.NaN, onCancel = { transition = false })
            } else {
                Column(Modifier.fillMaxSize().background(if (dark) Color(0xFF191815) else Color(0xFFFFFCF7))
                    .windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(20.dp)) {
                    LinDoBrandWordmark(isDark = dark)
                    Spacer(Modifier.height(12.dp))
                    Text("零度娘 · SVG 状态提示", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("播放动效", Modifier.weight(1f))
                        Switch(checked = animate, onCheckedChange = { animate = it })
                        TextButton(onClick = { dark = !dark }) { Text(if (dark) "浅色" else "深色") }
                    }
                    LinDoMascotState.entries.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            pair.forEach { state ->
                                Column(Modifier.weight(1f).padding(bottom = 12.dp)
                                    .border(1.dp, if (dark) Color(0xFF3E372E) else Color(0xFFE9E0D3), RoundedCornerShape(20.dp))
                                    .padding(vertical = 12.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    LinDoMascot(state = state, size = 72.dp)
                                    Text(state.title, fontSize = 11.sp, textAlign = TextAlign.Center)
                                }
                            }
                        }
                    }
                    LinDoLoadingIndicator(label = "行内加载与分页预览")
                    Button(onClick = { transition = true }, modifier = Modifier.fillMaxWidth()) { Text("查看登录过渡") }
                    TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("返回登录首屏") }
                }
            }
        }
    }
}

/** Inline failure is never represented by the successful-empty illustration. */
@Composable
fun LinDoLoadFailure(message: String, onRetry: () -> Unit) {
    val secondaryText = if (LocalLinDoDarkTheme.current) Color(0xFFB8AFA2) else Color(0xFF746C61)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LinDoMascot(LinDoMascotState.NetworkError, size = 20.dp, compact = true, animate = false)
        Spacer(Modifier.width(6.dp))
        Text(
            text = message,
            modifier = Modifier.weight(1f),
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = secondaryText
        )
        TextButton(
            onClick = onRetry,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Icon(
                imageVector = LinDoIcons.Refresh,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = secondaryText
            )
            Spacer(Modifier.width(4.dp))
            Text("重试", fontSize = 11.8.sp, color = secondaryText)
        }
    }
}
