package do_.linux.lindo.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import do_.linux.lindo.ui.LocalLinDoAnimations
import do_.linux.lindo.ui.LocalLinDoDarkTheme
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import do_.linux.lindo.core.badge.ResolvedBadgeVisual
import do_.linux.lindo.core.badge.UniversalBadgeEngine
import do_.linux.lindo.core.model.FlairInfo
import do_.linux.lindo.core.model.GrantedBadge
import do_.linux.lindo.core.site.LinuxDoSiteRules

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
    val requestedPixels = with(LocalDensity.current) { size.roundToPx() }
    val thumbnailSize = listOf(45, 60, 90, 120, 180, 240).firstOrNull { it >= requestedPixels } ?: 240
    val motionEnabled = enableAnimations && LocalLinDoAnimations.current && LocalWindowInfo.current.isWindowFocused
    val staticUrl = remember(avatarTemplate, thumbnailSize) {
        LinuxDoSiteRules.resolveStaticAvatarUrl(avatarTemplate, sizePx = thumbnailSize)
    }
    val animatedUrl = remember(animatedAvatar, motionEnabled) {
        if (motionEnabled) LinuxDoSiteRules.resolveAnimatedAvatarUrl(animatedAvatar) else null
    }
    val isSquare = remember(username, staticUrl) {
        LinuxDoSiteRules.isSquareAvatar(username, staticUrl)
    }
    val shape: Shape = if (isSquare) RoundedCornerShape(percent = 10) else CircleShape
    val glowColor = remember(username, primaryGroupName) { LinuxDoSiteRules.matchAvatarGlowColor(username, primaryGroupName) }
    val glowAlpha = if (glowColor != null && motionEnabled) {
        val pulse = rememberInfiniteTransition(label = "official_avatar_glow")
        pulse.animateFloat(0.18f, 0.42f,
            animationSpec = infiniteRepeatable(tween(if (isSquare) 3000 else 5000), RepeatMode.Reverse),
            label = "glow_alpha")
    } else rememberUpdatedState(0.25f)

    androidx.compose.runtime.key(username, staticUrl, animatedUrl) {
        var animatedReady by remember { mutableStateOf(false) }
        var staticLoaded by remember { mutableStateOf(false) }

        Box(
            modifier = modifier.size(size).drawBehind {
                glowColor?.let { glow ->
                    drawCircle(brush = Brush.radialGradient(
                        listOf(glow.copy(alpha = glowAlpha.value), glow.copy(alpha = 0f)),
                        radius = this.size.minDimension * 0.8f), radius = this.size.minDimension * 0.8f)
                }
            },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(avatarFallbackColor(username))
                    .border(
                        width = 0.5.dp,
                        color = Color(0x14000000),
                        shape = shape
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (!staticLoaded && !animatedReady) {
                    Text(
                        text = username.firstOrNull()?.uppercase() ?: "L",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = (size.value * 0.40f).sp
                    )
                }

                if (!animatedReady && staticUrl.isNotEmpty()) {
                    AsyncImage(
                        model = staticUrl,
                        contentDescription = username,
                        contentScale = ContentScale.Crop,
                        onLoading = { staticLoaded = false },
                        onSuccess = { staticLoaded = true },
                        onError = { staticLoaded = false },
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(if (staticLoaded) 1f else 0f)
                    )
                }

                if (!animatedUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = animatedUrl,
                        contentDescription = "$username animated avatar",
                        contentScale = ContentScale.Crop,
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
                FlairBadgeOverlay(
                    flair = flair,
                    badgeSize = (size.value * 0.36f).coerceIn(13f, 16f).dp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 1.dp, y = 1.dp)
                )
            }
        }
    }
}

/**
 * 右下角 Flair 群组身份徽章组件（1:1 对齐 Linux.do Web `.avatar-flair`）
 */
@Composable
fun FlairBadgeOverlay(
    flair: FlairInfo,
    badgeSize: Dp = 14.dp,
    modifier: Modifier = Modifier
) {
    val rawUrl = flair.url?.trim().orEmpty()
    if (rawUrl.isEmpty()) return
    val isImageUrl = rawUrl.contains("/") ||
        rawUrl.endsWith(".png", ignoreCase = true) ||
        rawUrl.endsWith(".webp", ignoreCase = true) ||
        rawUrl.endsWith(".gif", ignoreCase = true) ||
        rawUrl.endsWith(".jpg", ignoreCase = true) ||
        rawUrl.endsWith(".svg", ignoreCase = true)

    val hasCustomBg = !flair.bgColorHex.isNullOrBlank()
    val bgColor = LinuxDoSiteRules.parseHexColor(flair.bgColorHex, fallback = Color.Transparent)
    val fgColor = LinuxDoSiteRules.parseHexColor(flair.fgColorHex, fallback = Color.White)

    if (isImageUrl) {
        val fullImgUrl = LinuxDoSiteRules.resolveUrl(rawUrl)
        Box(
            modifier = modifier
                .size(badgeSize)
                .let { m -> if (hasCustomBg) m.clip(CircleShape).background(bgColor) else m },
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = fullImgUrl,
                contentDescription = flair.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
        return
    }

    // FontAwesome 矢量图标 Flair
    val tempBadge = remember(flair) {
        GrantedBadge(
            id = flair.groupId ?: 0,
            name = flair.name ?: "Flair",
            badgeTypeId = 1,
            icon = rawUrl
        )
    }
    val visual = remember(tempBadge) {
        UniversalBadgeEngine.resolveBadgeVisual(tempBadge, isDark = true)
    }
    if (visual is ResolvedBadgeVisual.VectorGlyph) {
        Surface(
            modifier = modifier.size(badgeSize),
            shape = CircleShape,
            color = if (hasCustomBg) bgColor else Color(0xFF334155),
            shadowElevation = 0.5.dp
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = visual.icon,
                    contentDescription = flair.name,
                    tint = fgColor,
                    modifier = Modifier.size(badgeSize * 0.64f)
                )
            }
        }
    }
}

/**
 * Linux.do 帖子头部用户头衔（对齐 Web / CFluxDo / FluxDO 全息流光、黄金荣誉、Apple 专属与银质活跃头衔样式）
 */
@Composable
fun UserTitleTag(
    title: String,
    enableAnimations: Boolean = true,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isDark = LocalLinDoDarkTheme.current
    val visualStyle = remember(title) { LinuxDoSiteRules.resolveTitleVisualStyle(title) }
    val clickableModifier = if (onClick != null) {
        modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
    } else {
        modifier
    }

    when (visualStyle) {
        LinuxDoSiteRules.UserTitleVisualStyle.HOLOGRAPHIC -> {
            val shift = if (enableAnimations && LocalLinDoAnimations.current && LocalWindowInfo.current.isWindowFocused) {
                val transition = rememberInfiniteTransition(label = "holoTitle")
                val value by transition.animateFloat(
                    initialValue = 0f,
                    targetValue = 200f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 12000, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "holoShift"
                )
                value
            } else {
                40f
            }

            val holoColors = if (isDark) {
                listOf(
                    Color(0xFFFF00FF),
                    Color(0xFF00FFFF),
                    Color(0xFFFFFF00),
                    Color(0xFFFF00FF),
                    Color(0xFF00FFFF)
                )
            } else {
                listOf(
                    Color(0xFFCC00CC),
                    Color(0xFF0099AA),
                    Color(0xFFCC8800),
                    Color(0xFFCC00CC),
                    Color(0xFF0099AA)
                )
            }

            val holoBrush = Brush.linearGradient(
                colors = holoColors,
                start = Offset(shift, 0f),
                end = Offset(shift + 140f, 30f)
            )

            Text(
                text = title,
                style = TextStyle(
                    brush = holoBrush,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = clickableModifier
            )
        }

        LinuxDoSiteRules.UserTitleVisualStyle.GOLD_HONOR -> {
            val goldColors = if (isDark) {
                listOf(
                    Color(0xFFFBBF24),
                    Color(0xFFF59E0B),
                    Color(0xFFFDE68A),
                    Color(0xFFF59E0B)
                )
            } else {
                listOf(
                    Color(0xFFB45309),
                    Color(0xFFD97706),
                    Color(0xFF92400E)
                )
            }
            val goldBrush = Brush.linearGradient(
                colors = goldColors,
                start = Offset(0f, 0f),
                end = Offset(120f, 20f)
            )
            Text(
                text = title,
                style = TextStyle(
                    brush = goldBrush,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = clickableModifier
            )
        }

        LinuxDoSiteRules.UserTitleVisualStyle.APPLE_SPECIAL -> {
            val appleTint = if (isDark) Color(0xFFE2E8F0) else Color(0xFF1E293B)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.5.dp),
                modifier = clickableModifier
            ) {
                Icon(
                    imageVector = LinDoIcons.AppleLogo,
                    contentDescription = "Apple",
                    tint = appleTint,
                    modifier = Modifier.size(10.5.dp)
                )
                Text(
                    text = title,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = appleTint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        LinuxDoSiteRules.UserTitleVisualStyle.SILVER_ACTIVE -> {
            val silverTint = if (isDark) Color(0xFFCBD5E1) else Color(0xFF475569)
            Text(
                text = title,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = silverTint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = clickableModifier
            )
        }

        LinuxDoSiteRules.UserTitleVisualStyle.STANDARD -> {
            Text(
                text = title,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = clickableModifier
            )
        }
    }
}

/**
 * 帖子楼层头部行内精巧徽章图标 (`PostGrantedBadgeIcon`)
 * 1:1 还原 Linux.do Web `.user-badge-buttons .user-badge` 的 14dp 行内图标形态（位图或金/银/铜矢量勋章图标，绝不显示粗糙汉字圆圈）
 */
@Composable
fun PostGrantedBadgeIcon(
    badge: GrantedBadge,
    onClick: ((GrantedBadge) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isDark = LocalLinDoDarkTheme.current
    val visual = remember(badge, isDark) {
        UniversalBadgeEngine.resolveBadgeVisual(badge, isDark = isDark)
    }
    val tierColor = remember(badge, isDark) {
        UniversalBadgeEngine.badgeTierColor(badge.badgeType, isDark = isDark)
    }
    val fallbackVector = remember(badge) {
        UniversalBadgeEngine.resolveFallbackIcon(badge)
    }
    val isApple = remember(badge) {
        UniversalBadgeEngine.isAppleBadge(badge)
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .padding(start = 2.dp)
            .size(if (isApple) 15.5.dp else 15.dp)
            .let { mod ->
                if (onClick != null) mod.clickable { onClick(badge) } else mod
            }
    ) {
        when (visual) {
            is ResolvedBadgeVisual.BitmapImage -> {
                var imgError by remember(visual.url) { mutableStateOf(false) }
                if (imgError) {
                    Icon(
                        imageVector = fallbackVector,
                        contentDescription = badge.name,
                        tint = tierColor,
                        modifier = Modifier.size(13.5.dp)
                    )
                } else {
                    AsyncImage(
                        model = visual.url,
                        contentDescription = badge.name,
                        contentScale = ContentScale.Fit,
                        onError = { imgError = true },
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            is ResolvedBadgeVisual.SvgImage -> {
                var svgLoaded by remember(visual.url) { mutableStateOf(false) }
                if (!svgLoaded) {
                    Icon(
                        imageVector = fallbackVector,
                        contentDescription = badge.name,
                        tint = tierColor,
                        modifier = Modifier.size(13.5.dp)
                    )
                }
                AsyncImage(
                    model = visual.url,
                    contentDescription = badge.name,
                    contentScale = ContentScale.Fit,
                    onSuccess = { svgLoaded = true },
                    onError = { svgLoaded = false },
                    modifier = Modifier
                        .size(14.dp)
                        .alpha(if (svgLoaded) 1f else 0f)
                )
            }
            is ResolvedBadgeVisual.VectorGlyph -> {
                Icon(
                    imageVector = visual.icon,
                    contentDescription = badge.name,
                    tint = visual.tintColor,
                    modifier = Modifier.size(if (isApple) 14.2.dp else 13.5.dp)
                )
            }
            else -> {
                Icon(
                    imageVector = fallbackVector,
                    contentDescription = badge.name,
                    tint = tierColor,
                    modifier = Modifier.size(13.5.dp)
                )
            }
        }
    }
}

/**
 * 徽章墙与用户资料卡使用的 Web 风格徽章组件 (`UniversalBadgeChip`)
 * 对齐 Linux.do Web `.user-badge`：清爽细边框 + 金/银/铜勋章图标 + 徽章名称 + 重复获授次数 `×N`
 */
@Composable
fun UniversalBadgeChip(
    badge: GrantedBadge,
    compact: Boolean = true,
    onClick: ((GrantedBadge) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isDark = LocalLinDoDarkTheme.current
    val tierColor = remember(badge, isDark) {
        UniversalBadgeEngine.badgeTierColor(badge.badgeType, isDark = isDark)
    }
    val shape = RoundedCornerShape(if (compact) 6.dp else 8.dp)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(shape)
            .let { mod ->
                if (onClick != null) mod.clickable { onClick(badge) } else mod
            }
            .background(
                color = tierColor.copy(alpha = if (badge.isEarned) 0.08f else 0.03f),
                shape = shape
            )
            .border(
                width = 0.6.dp,
                color = if (badge.isEarned) {
                    tierColor.copy(alpha = 0.34f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                },
                shape = shape
            )
            .padding(
                horizontal = if (compact) 6.5.dp else 8.5.dp,
                vertical = if (compact) 3.dp else 4.5.dp
            )
    ) {
        PostGrantedBadgeIcon(badge = badge)
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = badge.name,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = if (compact) 11.2.sp else 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (badge.count > 1) {
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = "×${badge.count}",
                color = tierColor,
                fontSize = if (compact) 10.2.sp else 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

private fun avatarFallbackColor(username: String): Color {
    val palette = listOf(
        Color(0xFF0284C7),
        Color(0xFF7C3AED),
        Color(0xFF059669),
        Color(0xFFD97706),
        Color(0xFFE11D48),
        Color(0xFF4F46E5)
    )
    val idx = (username.hashCode() and 0x7FFFFFFF) % palette.size
    return palette[idx]
}
