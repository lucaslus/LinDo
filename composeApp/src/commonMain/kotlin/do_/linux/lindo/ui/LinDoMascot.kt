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
import androidx.compose.ui.graphics.vector.ImageVector
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
import do_.linux.lindo.core.copy.LinDoAuthCopy
import do_.linux.lindo.core.copy.LinDoCommonCopy
import do_.linux.lindo.core.copy.LinDoMessagesCopy
import do_.linux.lindo.core.copy.UiErrorKind
import do_.linux.lindo.core.copy.isAuthOrPermissionError
import do_.linux.lindo.core.copy.isSiteVerificationError
import do_.linux.lindo.core.network.UserApiKeyAuthManager
import do_.linux.lindo.ui.components.LinDoIcons
import do_.linux.lindo.ui.components.LocalOpenInAppWebUrl
import do_.linux.lindo.ui.components.LocalRequestGuestLogin
import lindo.composeapp.generated.resources.Res
import lindo.composeapp.generated.resources.lindo_brand_mark
import lindo.composeapp.generated.resources.lindo_mascot_welcome
import lindo.composeapp.generated.resources.lindo_mascot_connecting
import org.jetbrains.compose.resources.painterResource

val LocalLinDoAnimations = staticCompositionLocalOf { true }
val LocalLinDoDarkTheme = staticCompositionLocalOf { false }

enum class LinDoMascotState(
    val asset: String,
    val titleCopy: LinDoCommonCopy,
    val detailCopy: LinDoCommonCopy
) {
    Loading("loading", LinDoCommonCopy.LOADING, LinDoCommonCopy.LOADING),
    Refreshing("refreshing", LinDoCommonCopy.REFRESHING, LinDoCommonCopy.REFRESHING),
    NetworkError("error", LinDoCommonCopy.NETWORK_ERROR, LinDoCommonCopy.NETWORK_CONNECTION_ERROR),
    Empty("empty", LinDoCommonCopy.EMPTY_CONTENT, LinDoCommonCopy.EMPTY_CONTENT),
    Success("success", LinDoCommonCopy.COMPLETED, LinDoCommonCopy.COMPLETED),
    End("end", LinDoCommonCopy.NO_MORE, LinDoCommonCopy.NO_MORE);

    val title: String get() = titleCopy.text
    val detail: String get() = detailCopy.text
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
    color: Color = LocalPalette.current.inkSecondary,
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
            } else rememberUpdatedState(0.625f)
            val p = animProgress.value
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .graphicsLayer {
                        // 以水平中线（0dp）为基准上下对称微幅跳动（±0.82dp），消除原先单向负偏移导致的整体偏上
                        translationY = (0.625f - p) * 2.2f * density
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
    color: Color = LocalPalette.current.inkSecondary,
    trackColor: Color = Color.Transparent,
    progress: (() -> Float)? = null,
    label: String = LinDoCommonCopy.LOADING.text,
    state: LinDoMascotState = LinDoMascotState.Loading,
    compactInline: Boolean = false,
    useIconArtwork: Boolean = false
) {
    val cleanLabel = remember(label) {
        val stripped = label
            .replace(LinDoCommonCopy.MASCOT_PREFIX_FULL.text, "")
            .replace(LinDoCommonCopy.MASCOT_PREFIX_SHORT.text, "")
            .removeSuffix("...")
            .removeSuffix("…")
            .trim()
        when {
            stripped.isEmpty() -> LinDoCommonCopy.LOADING.text
            stripped.length > 16 -> stripped.take(16) + "…"
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
            Text(cleanLabel, fontSize = 12.sp, lineHeight = 16.sp, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                        fontSize = 12.5.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = color,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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

/**
 * 全局统一极简状态提示组件（无权限 / 需登录 / 空状态 / 异常）：
 * 仅保留「单个无背景矢量图标 + 一行灰色小字 + 可选无背景简洁文字动作」，全站视觉严丝合缝统一。
 */
@Composable
fun LinDoStatusNotice(
    message: String,
    icon: ImageVector = LinDoIcons.Inbox,
    iconSize: Dp = 21.dp,
    actionLabel: String? = null,
    actionIcon: ImageVector? = null,
    emphasizeAction: Boolean = false,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val palette = LocalPalette.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 20.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            tint = palette.inkTertiary
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            color = palette.inkSecondary,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center
        )
        if (onAction != null && !actionLabel.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            val actionTint = if (emphasizeAction) palette.activeChipText else palette.inkSecondary
            TextButton(
                onClick = onAction,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
            ) {
                if (actionIcon != null) {
                    Icon(
                        imageVector = actionIcon,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = actionTint
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = actionLabel,
                    color = actionTint,
                    fontSize = 12.5.sp,
                    fontWeight = if (emphasizeAction) FontWeight.SemiBold else FontWeight.Medium
                )
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
    actionLabel: String = if (state == LinDoMascotState.NetworkError) LinDoCommonCopy.RETRY.text else LinDoCommonCopy.REFRESH.text,
    modifier: Modifier = Modifier,
    useIconArtwork: Boolean = false,
    iconOverride: ImageVector? = null,
    showActionIcon: Boolean = true,
    emphasizeAction: Boolean = false
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
    val displayMessage = when {
        state == LinDoMascotState.NetworkError -> detail?.takeIf { it.isNotBlank() } ?: title
        !detail.isNullOrBlank() && detail != title && detail != state.detail -> detail
        else -> title
    }
    val openWeb = LocalOpenInAppWebUrl.current
    val requestGuestLogin = LocalRequestGuestLogin.current
    val isAuthorized = UserApiKeyAuthManager.isAuthorized()
    val errorKind = UiErrorKind.classify(displayMessage, isAuthorized)
    val isSiteVerify = state == LinDoMascotState.NetworkError &&
        (errorKind == UiErrorKind.SITE_VERIFICATION || displayMessage.isSiteVerificationError()) &&
        openWeb != null
    val isPermissionOrAuth = errorKind == UiErrorKind.GUEST_LOGIN_REQUIRED ||
        errorKind == UiErrorKind.AUTH_OR_PERMISSION ||
        displayMessage.isAuthOrPermissionError()
    val isGuestLoginNeeded = !isSiteVerify &&
        !isAuthorized &&
        isPermissionOrAuth &&
        requestGuestLogin != null
    val resolvedIcon = iconOverride ?: when {
        isPermissionOrAuth -> LinDoIcons.FaLock
        state == LinDoMascotState.NetworkError -> LinDoIcons.InfoCircle
        state == LinDoMascotState.Success -> LinDoIcons.Check
        else -> LinDoIcons.Inbox
    }
    val resolvedIconSize = if (resolvedIcon == LinDoIcons.FaLock) 19.dp else 21.dp
    val resolvedAction: (() -> Unit)? = when {
        isSiteVerify -> ({ openWeb?.invoke("https://linux.do/") })
        isGuestLoginNeeded -> ({ requestGuestLogin?.invoke(LinDoAuthCopy.GUEST_VIEW_CONTENT_TITLE.text, displayMessage) })
        else -> onRetry
    }
    val resolvedActionLabel = when {
        resolvedAction == null -> null
        isSiteVerify && actionLabel == LinDoCommonCopy.RETRY.text -> LinDoCommonCopy.OPEN_WEB_VERIFY.text
        isGuestLoginNeeded && actionLabel == LinDoCommonCopy.RETRY.text -> LinDoCommonCopy.GO_LOGIN.text
        else -> actionLabel
    }
    LinDoStatusNotice(
        message = displayMessage,
        icon = resolvedIcon,
        iconSize = resolvedIconSize,
        actionLabel = resolvedActionLabel,
        actionIcon = if (resolvedAction != null && showActionIcon && !isPermissionOrAuth && !isSiteVerify) LinDoIcons.Refresh else null,
        emphasizeAction = emphasizeAction || isPermissionOrAuth || isSiteVerify,
        onAction = resolvedAction,
        modifier = modifier
    )
}

@Composable
fun LinDoEmptyState(
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = LinDoIcons.Inbox,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    showMascot: Boolean = false
) {
    val isRestricted = message.isAuthOrPermissionError()
    if (showMascot && !isRestricted) {
        Column(modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            LinDoMascot(state = LinDoMascotState.Empty, size = 48.dp, animate = false)
            Spacer(Modifier.height(12.dp))
            Text(message, fontSize = 14.sp, color = LocalPalette.current.inkSecondary, textAlign = TextAlign.Center)
            if (actionLabel != null && onAction != null) TextButton(onClick = onAction) { Text(actionLabel) }
        }
        return
    }
    val resolvedIcon = if (isRestricted && icon == LinDoIcons.Inbox) LinDoIcons.FaLock else icon
    LinDoStatusNotice(
        message = message,
        icon = resolvedIcon,
        iconSize = if (resolvedIcon == LinDoIcons.FaLock) 19.dp else 21.dp,
        actionLabel = actionLabel,
        emphasizeAction = onAction != null,
        onAction = onAction,
        modifier = modifier
    )
}

@Composable
fun LinDoFeedbackRow(message: String, state: LinDoMascotState, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val secondaryText = palette.inkSecondary
    val openWeb = LocalOpenInAppWebUrl.current
    val showSiteVerifyAction = state == LinDoMascotState.NetworkError &&
        message.isSiteVerificationError() &&
        openWeb != null
    Row(
        modifier = modifier
            .then(if (showSiteVerifyAction) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = 22.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically
    ) {
        LinDoMascot(state = state, compact = true, size = 18.dp)
        Spacer(Modifier.width(5.dp))
        Text(
            text = message,
            modifier = Modifier.weight(1f, fill = showSiteVerifyAction),
            fontSize = 11.5.sp,
            lineHeight = 15.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            color = secondaryText
        )
        if (showSiteVerifyAction) {
            TextButton(
                onClick = { openWeb?.invoke("https://linux.do/") },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.heightIn(min = 28.dp)
            ) {
                Text(
                    text = LinDoCommonCopy.GO_VERIFY.text,
                    fontSize = 11.8.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.activeChipText
                )
            }
        }
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
                    Text(LinDoMessagesCopy.MASCOT_GALLERY_TITLE.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(LinDoMessagesCopy.MASCOT_PLAY_ANIMATION.text, Modifier.weight(1f))
                        Switch(checked = animate, onCheckedChange = { animate = it })
                        TextButton(onClick = { dark = !dark }) {
                            Text(if (dark) LinDoMessagesCopy.MASCOT_THEME_LIGHT.text else LinDoMessagesCopy.MASCOT_THEME_DARK.text)
                        }
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
                    LinDoLoadingIndicator(label = LinDoMessagesCopy.MASCOT_INLINE_PREVIEW_LABEL.text)
                    Button(onClick = { transition = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(LinDoMessagesCopy.MASCOT_VIEW_LOGIN_TRANSITION.text)
                    }
                    TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                        Text(LinDoMessagesCopy.MASCOT_BACK_TO_WELCOME.text)
                    }
                }
            }
        }
    }
}

/** Inline failure is never represented by the successful-empty illustration. */
@Composable
fun LinDoLoadFailure(message: String, onRetry: () -> Unit) {
    val palette = LocalPalette.current
    val secondaryText = palette.inkSecondary
    val openWeb = LocalOpenInAppWebUrl.current
    val requestGuestLogin = LocalRequestGuestLogin.current
    val isAuthorized = UserApiKeyAuthManager.isAuthorized()
    val errorKind = UiErrorKind.classify(message, isAuthorized)
    val isSiteVerify = (errorKind == UiErrorKind.SITE_VERIFICATION || message.isSiteVerificationError()) && openWeb != null
    val isGuestLogin = !isSiteVerify &&
        !isAuthorized &&
        requestGuestLogin != null &&
        (errorKind == UiErrorKind.GUEST_LOGIN_REQUIRED || message.isAuthOrPermissionError())
    val highlightAction = isSiteVerify || isGuestLogin
    val actionColor = if (highlightAction) palette.activeChipText else secondaryText
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isGuestLogin) {
            Icon(
                imageVector = LinDoIcons.FaLock,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = secondaryText
            )
        } else {
            LinDoMascot(LinDoMascotState.NetworkError, size = 20.dp, compact = true, animate = false)
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = message,
            modifier = Modifier.weight(1f),
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = secondaryText
        )
        TextButton(
            onClick = {
                when {
                    isSiteVerify -> openWeb?.invoke("https://linux.do/")
                    isGuestLogin -> requestGuestLogin?.invoke(LinDoAuthCopy.GUEST_VIEW_CONTENT_TITLE.text, message)
                    else -> onRetry()
                }
            },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
        ) {
            if (!highlightAction) {
                Icon(
                    imageVector = LinDoIcons.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = actionColor
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = when {
                    isSiteVerify -> LinDoCommonCopy.GO_VERIFY.text
                    isGuestLogin -> LinDoCommonCopy.GO_LOGIN.text
                    else -> LinDoCommonCopy.RETRY.text
                },
                fontSize = 11.8.sp,
                fontWeight = if (highlightAction) FontWeight.SemiBold else FontWeight.Normal,
                color = actionColor
            )
        }
    }
}
