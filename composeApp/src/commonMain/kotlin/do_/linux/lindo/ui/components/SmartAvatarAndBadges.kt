package do_.linux.lindo.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import do_.linux.lindo.ui.LocalLinDoAnimations
import do_.linux.lindo.ui.LocalLinDoDarkTheme
import do_.linux.lindo.ui.LocalPalette
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import do_.linux.lindo.core.badge.UniversalBadgeEngine
import do_.linux.lindo.core.model.FlairInfo
import do_.linux.lindo.core.model.GrantedBadge
import do_.linux.lindo.core.network.LinuxDoRepository
import do_.linux.lindo.core.site.LinuxDoSiteRules
import kotlinx.coroutines.delay

import lindo.composeapp.generated.resources.Res
import lindo.composeapp.generated.resources.allDrawableResources
import org.jetbrains.compose.resources.painterResource

val LocalLinDoNetworkRestoreVersion = compositionLocalOf { 0 }
val LocalSiteVerificationRestoreVersion = compositionLocalOf { 0 }
val LocalIsWebModalActive = compositionLocalOf { false }
val LocalOpenInAppWebUrl = compositionLocalOf<((String) -> Unit)?> { null }
val LocalRequestGuestLogin = compositionLocalOf<((String, String) -> Unit)?> { null }

/**
 * LinDo 双栈智能动态头像组件 (`SmartAvatar`)
 *
 * 1. 静态缩略图秒显打底 + 顶层 `animated_avatar` 首帧解码完成后自动隐藏底图（消除透明 GIF 双影）。
 * 2. 自动识别 `@neo` 10% 圆角方形裁切 + `#00AEFF` 冰蓝脉冲光晕，以及 `g-merchant` `#F5BF03` 鎏金脉冲光晕。
 * 3. 干净原生的视觉表现，无遮挡角标。
 */
@Composable
fun SmartAvatar(
    username: String,
    avatarTemplate: String?,
    animatedAvatar: String?,
    primaryGroupName: String? = null,
    flair: FlairInfo? = null,
    size: Dp = 40.dp,
    enableAnimations: Boolean = true,
    modifier: Modifier = Modifier
) {
    // 统一收敛至 Discourse 官方预生成尺寸档位（常规 <=60dp 统一使用 144px，超大图使用 288px），
    // 确保首页、帖子正文、个人主页、用户名片、小火箭与通知中心 100% 共享同一 CDN URL 与内存/磁盘缓存，零 302 重定向！
    val thumbnailSize = if (size <= 60.dp) 144 else 288
    val motionEnabled = enableAnimations && LocalLinDoAnimations.current && LocalWindowInfo.current.isWindowFocused
    val networkRestoreVersion = LocalLinDoNetworkRestoreVersion.current
    val siteVerificationRestoreVersion = LocalSiteVerificationRestoreVersion.current

    var cachedVisual by remember(username) {
        mutableStateOf(LinuxDoRepository.getCachedUserVisualMeta(username))
    }
    var effectiveTemplate by remember(username, avatarTemplate) {
        mutableStateOf(
            avatarTemplate?.takeIf { it.isNotBlank() }
                ?: cachedVisual?.avatarTemplate?.takeIf { it.isNotBlank() }
                ?: LinuxDoRepository.getCachedUserAvatar(username).orEmpty()
        )
    }
    LaunchedEffect(username, effectiveTemplate, networkRestoreVersion, siteVerificationRestoreVersion) {
        if (effectiveTemplate.isBlank()) {
            val clean = username.trim().removePrefix("@")
            if (clean.isNotEmpty() && clean != "L" && !clean.startsWith("user_")) {
                val resolved = LinuxDoRepository.resolveUserAvatarTemplate(clean)
                cachedVisual = LinuxDoRepository.getCachedUserVisualMeta(clean)
                if (!resolved.isNullOrBlank()) {
                    effectiveTemplate = resolved
                }
            }
        } else if (cachedVisual == null) {
            cachedVisual = LinuxDoRepository.getCachedUserVisualMeta(username)
        }
    }

    val effectivePrimaryGroup = primaryGroupName?.takeIf { it.isNotBlank() } ?: cachedVisual?.primaryGroupName
    val effectiveAnimatedRaw = animatedAvatar?.takeIf { it.isNotBlank() } ?: cachedVisual?.animatedAvatar

    val staticUrl = remember(effectiveTemplate, thumbnailSize) {
        LinuxDoSiteRules.resolveStaticAvatarUrl(effectiveTemplate, sizePx = thumbnailSize)
    }
    val animatedUrl = remember(effectiveAnimatedRaw, motionEnabled) {
        if (motionEnabled) LinuxDoSiteRules.resolveAnimatedAvatarUrl(effectiveAnimatedRaw) else null
    }
    val isSquare = remember(username, staticUrl) {
        LinuxDoSiteRules.isSquareAvatar(username, staticUrl)
    }
    val shape: Shape = if (isSquare) RoundedCornerShape(percent = 10) else CircleShape
    val glowColor = remember(username, effectivePrimaryGroup, staticUrl) {
        LinuxDoSiteRules.matchAvatarGlowColor(username, effectivePrimaryGroup, staticUrl)
    }
    val isBlueGlow = glowColor == Color(0xFF00AEFF)
    // 对齐官方 common_theme_138 `@keyframes pulse`：冰蓝 3s 往返 (单程 1500ms)、鎏金 5s 往返 (单程 2500ms)，列表滚动禁用动效时保留静态光晕
    val pulseProgress = if (glowColor != null && motionEnabled) {
        val pulse = rememberInfiniteTransition(label = "official_avatar_glow")
        pulse.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = if (isBlueGlow) 1500 else 2500, easing = officialTitleEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "glow_pulse"
        )
    } else {
        rememberUpdatedState(0.72f)
    }

    val hasAnimated = !animatedUrl.isNullOrBlank()
    val palette = LocalPalette.current
    val platformContext = LocalPlatformContext.current
    androidx.compose.runtime.key(username, staticUrl, animatedUrl) {
        var animatedReady by remember { mutableStateOf(false) }
        var staticLoaded by remember {
            mutableStateOf(staticUrl.isNotEmpty() && isLoadedAvatarUrl(staticUrl))
        }
        var retryAttempt by remember { mutableIntStateOf(0) }
        var failedAttempt by remember { mutableIntStateOf(-1) }
        var lastHandledRestoreVersion by remember { mutableIntStateOf(networkRestoreVersion) }

        LaunchedEffect(networkRestoreVersion) {
            if (networkRestoreVersion > 0 && networkRestoreVersion != lastHandledRestoreVersion) {
                lastHandledRestoreVersion = networkRestoreVersion
                if (!staticLoaded && failedAttempt >= 0 && staticUrl.isNotEmpty()) {
                    failedAttempt = -1
                    retryAttempt = (retryAttempt + 1).coerceAtLeast(1)
                }
            }
        }

        LaunchedEffect(failedAttempt, staticLoaded) {
            if (!staticLoaded && failedAttempt in 0..2) {
                delay(900L * (failedAttempt + 1))
                if (!staticLoaded) {
                    retryAttempt = failedAttempt + 1
                }
            }
        }

        val fetchUrl = remember(staticUrl, retryAttempt) {
            if (retryAttempt == 0 || staticUrl.isEmpty()) {
                staticUrl
            } else {
                "$staticUrl${if ('?' in staticUrl) '&' else '?'}_r=$retryAttempt"
            }
        }
        val requestModel = remember(platformContext, staticUrl, fetchUrl) {
            if (staticUrl.isEmpty()) {
                null
            } else {
                // 指定 Size.ORIGINAL：1) 无需等待 Compose 布局测量即可在组合阶段立即并发发起请求；
                // 2) 防止小尺寸组件（如 16.5dp 助力胶囊）把缓存降采样成模糊小图后污染大尺寸头像渲染。
                ImageRequest.Builder(platformContext)
                    .data(fetchUrl)
                    .size(coil3.size.Size.ORIGINAL)
                    .memoryCacheKey(staticUrl)
                    .diskCacheKey(staticUrl)
                    .build()
            }
        }

        val glowOpacity = if (glowColor != null) 0.5f + 0.5f * pulseProgress.value else 0f
        val borderStrokeColor = if (glowColor != null) {
            glowColor.copy(alpha = (0.58f * glowOpacity).coerceIn(0.25f, 0.72f))
        } else {
            palette.hairline
        }
        val borderStrokeWidth = if (glowColor != null && size >= 24.dp) 1.dp else 0.5.dp

        Box(
            modifier = modifier.size(size).then(
                if (glowColor == null) {
                    Modifier
                } else {
                    Modifier.drawBehind {
                        val p = pulseProgress.value
                        val opacity = 0.5f + 0.5f * p
                        val scale = 0.95f + 0.05f * p
                        val minDim = this.size.minDimension
                        val maxSpread = (minDim * 0.20f).coerceIn(3.dp.toPx(), 8.dp.toPx())
                        val baseCorner = minDim * 0.10f
                        for (layer in 5 downTo 1) {
                            val t = layer / 5f
                            val spread = maxSpread * t * scale
                            val alpha = (0.048f + (1f - t) * 0.068f) * opacity
                            if (isSquare) {
                                drawRoundRect(
                                    color = glowColor.copy(alpha = alpha),
                                    topLeft = Offset(-spread, -spread),
                                    size = Size(this.size.width + spread * 2f, this.size.height + spread * 2f),
                                    cornerRadius = CornerRadius(baseCorner + spread, baseCorner + spread)
                                )
                            } else {
                                drawCircle(
                                    color = glowColor.copy(alpha = alpha),
                                    radius = minDim * 0.5f + spread,
                                    center = center
                                )
                            }
                        }
                    }
                }
            ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .border(
                        width = borderStrokeWidth,
                        color = borderStrokeColor,
                        shape = shape
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (staticUrl.isEmpty() || (!staticLoaded && !animatedReady)) {
                    AvatarSkeletonPlaceholder(
                        isDark = palette.isDark,
                        baseTint = palette.inkSecondary,
                        animate = motionEnabled && failedAttempt < 2,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                if (!animatedReady && requestModel != null) {
                    AsyncImage(
                        model = requestModel,
                        contentDescription = username,
                        contentScale = ContentScale.Crop,
                        filterQuality = FilterQuality.Medium,
                        onLoading = {
                            if (!isLoadedAvatarUrl(staticUrl)) {
                                staticLoaded = false
                            }
                        },
                        onSuccess = {
                            rememberLoadedAvatarUrl(staticUrl)
                            failedAttempt = -1
                            if (!staticLoaded) staticLoaded = true
                        },
                        onError = {
                            forgetLoadedAvatarUrl(staticUrl)
                            staticLoaded = false
                            failedAttempt = retryAttempt
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(if (staticLoaded) 1f else 0f)
                    )
                }

                if (hasAnimated && (staticLoaded || staticUrl.isEmpty())) {
                    AsyncImage(
                        model = animatedUrl,
                        contentDescription = "$username animated avatar",
                        contentScale = ContentScale.Crop,
                        filterQuality = FilterQuality.Medium,
                        onLoading = { animatedReady = false },
                        onSuccess = { animatedReady = true },
                        onError = { animatedReady = false },
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(if (animatedReady) 1f else 0f)
                    )
                }
            }

            if (flair != null && flair.hasFlair && size >= 28.dp) {
                // 对齐官方 d-avatar-flair.scss 比例（45px 头像配 20px 角标 ≈ 43%），避免过大角标遮挡头像主体
                val flairSize = when {
                    size >= 68.dp -> 28.dp
                    size >= 50.dp -> 21.dp
                    size >= 38.dp -> 18.dp
                    else -> 15.dp
                }
                FlairBadgeOverlay(
                    flair = flair,
                    badgeSize = flairSize,
                    modifier = Modifier.align(Alignment.BottomEnd).offset(
                        x = if (size >= 50.dp) 3.dp else 4.dp,
                        y = 2.dp
                    )
                )
            }
        }
    }
}

@Composable
fun FlairBadgeOverlay(
    flair: FlairInfo,
    badgeSize: Dp = 20.dp,
    modifier: Modifier = Modifier
) {
    val raw = flair.url?.trim()?.takeIf { it.isNotEmpty() } ?: return
    val isImage = '/' in raw
    val url = remember(raw) {
        if (isImage) LinuxDoSiteRules.resolveUrl(raw) else UniversalBadgeEngine.iconUrl(raw)
    } ?: return
    val background = remember(flair.bgColorHex) {
        LinuxDoSiteRules.parseHexColor(flair.bgColorHex, fallback = Color.Transparent)
    }
    val hasBackground = background.alpha > 0f
    val foreground = LinuxDoSiteRules.parseHexColor(
        flair.fgColorHex, fallback = LocalPalette.current.inkPrimary
    )
    val scale = when {
        isImage && hasBackground -> 0.75f
        isImage -> 1f
        hasBackground -> 0.6667f
        else -> 0.8f
    }
    Box(
        modifier = modifier.size(badgeSize).then(
            if (hasBackground) Modifier.background(background, CircleShape) else Modifier
        ),
        contentAlignment = Alignment.Center
    ) {
        OfficialBadgeArtwork(
            url = url,
            description = flair.name,
            colorFilter = if (isImage) null else ColorFilter.tint(foreground),
            modifier = Modifier.size(badgeSize * scale)
        )
    }
}

/** Only titles styled by the LinuxDo theme receive a special treatment. */
@Composable
fun UserTitleTag(
    title: String,
    animatePostTitle: Boolean? = null,
    merchantGroupTitle: Boolean = false,
    modifier: Modifier = Modifier
) {
    val palette = LocalPalette.current
    val isHolographic = remember(title) { LinuxDoSiteRules.isHolographicTitle(title) }
    val neutral = palette.inkSecondary
    if (isHolographic && animatePostTitle != null) {
        val animate = animatePostTitle && LocalLinDoAnimations.current && LocalWindowInfo.current.isWindowFocused
        val phase = if (animate) {
            val transition = rememberInfiniteTransition(label = "official_title")
            val progress by transition.animateFloat(
                0f, 4f, infiniteRepeatable(tween(15000, easing = LinearEasing)),
                label = "official_title_position"
            )
            progress
        } else 0f
        val stop = phase.toInt().coerceAtMost(3)
        val position = lerp(
            officialTitlePositions[stop], officialTitlePositions[stop + 1],
            officialTitleEasing.transform(phase - stop)
        )
        val gradient = remember(position) {
            object : ShaderBrush() {
                override fun createShader(size: Size): Shader {
                    // The web theme paints a 135° gradient on a 400% × 400% background.
                    val center = Offset(
                        size.width * (2 - 3 * position.x),
                        size.height * (2 - 3 * position.y)
                    )
                    val corner = size.width + size.height
                    val diagonal = Offset(corner, corner)
                    return LinearGradientShader(center - diagonal, center + diagonal, officialTitleColors)
                }
            }
        }
        Text(
            text = title,
            style = TextStyle(
                brush = gradient,
                fontSize = 12.sp,
                lineHeight = 14.sp
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier
        )
    } else {
        Text(
            text = title,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            color = if (merchantGroupTitle) Color(0xFFF5BF03) else neutral,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier
        )
    }
}

@Composable
fun PostGrantedBadgeIcon(
    badge: GrantedBadge,
    onClick: ((GrantedBadge) -> Unit)? = null,
    modifier: Modifier = Modifier,
    size: Dp = 14.dp,
    description: String = badge.name
) {
    val url = remember(badge.id, badge.name, badge.icon, badge.imageUrl) {
        UniversalBadgeEngine.artworkUrl(badge) ?: UniversalBadgeEngine.defaultIconUrl(badge)
    }
    val tint = if (badge.imageUrl.isNullOrBlank()) {
        ColorFilter.tint(UniversalBadgeEngine.badgeTierColor(badge.badgeType))
    } else null
    OfficialBadgeArtwork(
        url = url,
        description = description,
        colorFilter = tint,
        modifier = modifier.size(size).then(
            if (onClick == null) Modifier else Modifier.clickable { onClick(badge) }
        )
    )
}

@Composable
internal fun OfficialBadgeArtwork(
    url: String,
    description: String?,
    modifier: Modifier = Modifier,
    colorFilter: ColorFilter? = null
) {
    val resource = remember(url) {
        url.takeIf { it.startsWith(UniversalBadgeEngine.OFFICIAL_ICON_BASE) }?.let {
            val name = it.substringAfterLast('/').removeSuffix(".svg").replace('-', '_')
            Res.allDrawableResources["lindo_badge_" + name]
                ?: Res.allDrawableResources["lindo_badge_certificate"]
        }
    }
    if (resource != null) {
        Image(
            painterResource(resource), description, modifier,
            contentScale = ContentScale.Fit, colorFilter = colorFilter
        )
    } else {
        AsyncImage(url, description, modifier, contentScale = ContentScale.Fit, colorFilter = colorFilter)
    }
}

@Composable
fun UniversalBadgeChip(
    badge: GrantedBadge,
    compact: Boolean = true,
    onClick: ((GrantedBadge) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val palette = LocalPalette.current
    val tierColor = UniversalBadgeEngine.badgeTierColor(badge.badgeType)
    val shape = RoundedCornerShape(6.dp)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(shape)
            .let { mod ->
                if (onClick != null) mod.clickable { onClick(badge) } else mod
            }
            .background(
                color = palette.cardSurface,
                shape = shape
            )
            .border(
                width = 0.6.dp,
                color = palette.hairline,
                shape = shape
            )
            .padding(
                horizontal = if (compact) 7.dp else 9.dp,
                vertical = if (compact) 3.5.dp else 4.5.dp
            )
    ) {
        PostGrantedBadgeIcon(badge = badge)
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = badge.name,
            color = palette.inkPrimary,
            fontSize = if (compact) 11.sp else 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (badge.count > 1) {
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = "×${badge.count}",
                color = tierColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun AvatarSkeletonPlaceholder(
    isDark: Boolean,
    baseTint: Color,
    animate: Boolean = true,
    modifier: Modifier = Modifier
) {
    val baseAlpha = if (isDark) 0.08f else 0.06f
    if (!animate) {
        Box(modifier = modifier.background(baseTint.copy(alpha = baseAlpha)))
        return
    }
    val transition = rememberInfiniteTransition(label = "avatarSkeleton")
    val pulsePhase by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 750, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "avatarSkeletonPhase"
    )
    val peakAlpha = if (isDark) 0.17f else 0.13f
    val lowColor = baseTint.copy(alpha = baseAlpha)
    val highColor = baseTint.copy(alpha = baseAlpha + (peakAlpha - baseAlpha) * pulsePhase)

    Box(
        modifier = modifier.drawWithCache {
            val brush = Brush.linearGradient(
                colors = listOf(lowColor, highColor, lowColor),
                start = Offset.Zero,
                end = Offset(size.width, size.height)
            )
            onDrawBehind {
                drawRect(brush = brush)
            }
        }
    )
}

private val loadedStaticAvatarUrls = LinkedHashSet<String>()

private fun isLoadedAvatarUrl(url: String): Boolean {
    if (url.isEmpty()) return false
    return synchronized(loadedStaticAvatarUrls) { url in loadedStaticAvatarUrls }
}

private fun rememberLoadedAvatarUrl(url: String) {
    if (url.isEmpty()) return
    synchronized(loadedStaticAvatarUrls) {
        if (loadedStaticAvatarUrls.size >= 1024 && url !in loadedStaticAvatarUrls) {
            val oldest = loadedStaticAvatarUrls.firstOrNull()
            if (oldest != null) loadedStaticAvatarUrls.remove(oldest)
        }
        loadedStaticAvatarUrls.add(url)
    }
}

private fun forgetLoadedAvatarUrl(url: String) {
    if (url.isEmpty()) return
    synchronized(loadedStaticAvatarUrls) {
        loadedStaticAvatarUrls.remove(url)
    }
}

private val avatarThumbnailSizes = intArrayOf(24, 48, 96, 144, 288)

private val officialTitleColors = listOf(
    Color.Magenta, Color.Cyan, Color.Yellow, Color.Magenta, Color.Cyan
)

private val officialTitleEasing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
private val officialTitlePositions = listOf(
    Offset(0f, 0.5f), Offset(1f, 0f), Offset(1f, 1f), Offset(0f, 1f), Offset(0f, 0.5f)
)
