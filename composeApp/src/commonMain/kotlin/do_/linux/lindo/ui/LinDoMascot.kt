package do_.linux.lindo.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    Loading("loading", "零度娘正在整理内容", "新的讨论，马上就好"),
    Refreshing("refreshing", "正在同步最新内容", "看看社区又发生了什么"),
    NetworkError("error", "连接暂时中断了", "检查一下网络，再试一次吧"),
    Empty("empty", "这里还很安静", "有新内容时，再来看看吧"),
    Success("success", "已经准备好啦", "一起去看看新的讨论"),
    End("end", "已经看到这里啦", "休息一下，稍后再来逛逛")
}

/** Detailed SVG-derived vectors, animated as a single retained graphics layer. */
@Composable
fun LinDoMascot(
    state: LinDoMascotState = LinDoMascotState.Loading,
    size: Dp = 80.dp,
    compact: Boolean = false,
    animate: Boolean = true,
    modifier: Modifier = Modifier
) {
    val moving = LocalLinDoAnimations.current && animate && LocalWindowInfo.current.isWindowFocused &&
        state in setOf(LinDoMascotState.Loading, LinDoMascotState.Refreshing, LinDoMascotState.Empty)
    val rotation = if (moving) {
        val transition = rememberInfiniteTransition(label = "lingdu_sway")
        transition.animateFloat(
            initialValue = -1.8f, targetValue = 1.8f,
            animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "gentle_sway"
        )
    } else rememberUpdatedState(0f)
    val art = when {
        compact -> Res.drawable.lindo_brand_mark
        state == LinDoMascotState.Loading || state == LinDoMascotState.NetworkError -> Res.drawable.lindo_mascot_connecting
        else -> Res.drawable.lindo_mascot_welcome
    }
    Image(
        painter = painterResource(art),
        contentDescription = null,
        modifier = modifier.size(size).graphicsLayer {
            rotationZ = rotation.value
            transformOrigin = TransformOrigin(0.5f, 0.85f)
        },
        contentScale = ContentScale.Fit
    )
}

/** Compact loading for toolbars, searches, pagination and embedded web pages. */
@Composable
fun LinDoLoadingIndicator(
    modifier: Modifier = Modifier,
    color: Color = if (LocalLinDoDarkTheme.current) Color(0xFFC7BFB3) else Color(0xFF746C61),
    trackColor: Color = Color.Transparent,
    progress: (() -> Float)? = null,
    label: String = "零度娘正在加载",
    state: LinDoMascotState = LinDoMascotState.Loading
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 44.dp).background(trackColor)
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                progressBarRangeInfo = progress?.invoke()?.takeIf { it.isFinite() }?.let {
                    ProgressBarRangeInfo(it.coerceIn(0f, 1f), 0f..1f)
                } ?: ProgressBarRangeInfo.Indeterminate
            },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        LinDoMascot(state = state, compact = true, size = 24.dp)
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 12.sp, color = color, maxLines = 2, lineHeight = 17.sp)
        progress?.invoke()?.takeIf { it.isFinite() }?.let { value ->
            Spacer(Modifier.width(8.dp))
            Text("${(value.coerceIn(0f, 1f) * 100).toInt()}%", fontSize = 11.sp, color = color)
        }
    }
}

@Composable
fun LinDoStatePanel(
    state: LinDoMascotState,
    title: String = state.title,
    detail: String? = state.detail,
    onRetry: (() -> Unit)? = null,
    actionLabel: String = "刷新",
    modifier: Modifier = Modifier
) {
    val dark = LocalLinDoDarkTheme.current
    val secondaryText = if (dark) Color(0xFFB8AFA2) else Color(0xFF746C61)
    val displayText = detail?.takeIf { it.isNotBlank() } ?: title
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        LinDoMascot(state = state, size = 64.dp)
        Spacer(Modifier.height(8.dp))
        Text(
            text = displayText,
            color = secondaryText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp
        )
        if (onRetry != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onRetry,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Icon(
                    imageVector = LinDoIcons.Refresh,
                    contentDescription = "刷新",
                    modifier = Modifier.size(13.5.dp),
                    tint = secondaryText
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "刷新",
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
    Row(modifier.heightIn(min = 36.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically) {
        LinDoMascot(state = state, compact = true, size = 20.dp)
        Spacer(Modifier.width(6.dp))
        Text(message, modifier = Modifier.weight(1f), fontSize = 11.sp, lineHeight = 17.sp,
            color = if (LocalLinDoDarkTheme.current) Color(0xFFB8AFA2) else Color(0xFF746C61))
    }
}

@Composable
fun LinDoBusyButtonLabel(busy: Boolean, busyLabel: String, label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (busy) LinDoMascot(state = LinDoMascotState.Loading, compact = true, size = 20.dp)
        Text(if (busy) busyLabel else label, color = color, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
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
                contentDescription = "刷新",
                modifier = Modifier.size(13.dp),
                tint = secondaryText
            )
            Spacer(Modifier.width(4.dp))
            Text("刷新", fontSize = 11.8.sp, color = secondaryText)
        }
    }
}
