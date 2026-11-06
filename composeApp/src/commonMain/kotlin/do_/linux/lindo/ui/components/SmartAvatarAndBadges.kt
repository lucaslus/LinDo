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

    var animatedReady by remember(animatedUrl) { mutableStateOf(false) }
    var staticLoaded by remember(staticUrl) { mutableStateOf(false) }

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
                    onSuccess = { staticLoaded = true },
                    modifier = Modifier.fillMaxSize()
                )
            }

            if (!animatedUrl.isNullOrBlank()) {
                AsyncImage(
                    model = animatedUrl,
                    contentDescription = "$username animated avatar",
                    contentScale = ContentScale.Crop,
                    onSuccess = { animatedReady = true },
                    onError = { animatedReady = false },
                    modifier = Modifier.fillMaxSize()
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
 * Linux.do 帖子头部用户头衔（对齐 FluxDO 的 `HolographicText` 11sp 行内头衔样式）
 */
@Composable
fun UserTitleTag(
    title: String,
    enableAnimations: Boolean = true,
    modifier: Modifier = Modifier
) {
    val isHolo = LinuxDoSiteRules.isHolographicTitle(title)
    if (!isHolo) {
        Text(
            text = title,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier
        )
        return
    }

    val shift = if (enableAnimations && LocalLinDoAnimations.current && LocalWindowInfo.current.isWindowFocused) {
        val transition = rememberInfiniteTransition(label = "holoTitle")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = 200f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 15000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "holoShift"
        )
        value
    } else {
        40f
    }

    val holoBrush = Brush.linearGradient(
        colors = listOf(
            Color(0xFFFF00FF),
            Color(0xFF00FFFF),
            Color(0xFFFFFF00),
            Color(0xFFFF00FF),
            Color(0xFF00FFFF)
        ),
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
        modifier = modifier
    )
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
    val visual = remember(badge) {
        UniversalBadgeEngine.resolveBadgeVisual(badge, isDark = false)
    }
    val tierColor = remember(badge) {
        UniversalBadgeEngine.badgeTierColor(badge.badgeType, isDark = false)
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .padding(start = 2.dp)
            .size(15.dp)
            .let { mod ->
                if (onClick != null) mod.clickable { onClick(badge) } else mod
            }
    ) {
        when (visual) {
            is ResolvedBadgeVisual.BitmapImage -> {
                AsyncImage(
                    model = visual.url,
                    contentDescription = badge.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(14.dp)
                )
            }
            is ResolvedBadgeVisual.SvgImage -> {
                AsyncImage(
                    model = visual.url,
                    contentDescription = badge.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(14.dp)
                )
            }
            is ResolvedBadgeVisual.VectorGlyph -> {
                Icon(
                    imageVector = visual.icon,
                    contentDescription = badge.name,
                    tint = tierColor,
                    modifier = Modifier.size(13.5.dp)
                )
            }
            else -> {
                Icon(
                    imageVector = LinDoIcons.AwardBadge,
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
 * 对齐 Linux.do Web `.user-badge`：清爽细边框 + 金/银/铜勋章图标 + 徽章名称
 */
@Composable
fun UniversalBadgeChip(
    badge: GrantedBadge,
    compact: Boolean = true,
    modifier: Modifier = Modifier
) {
    val tierColor = remember(badge) {
        UniversalBadgeEngine.badgeTierColor(badge.badgeType, isDark = false)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .border(
                width = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                shape = RoundedCornerShape(if (compact) 5.dp else 7.dp)
            )
            .padding(
                horizontal = if (compact) 6.dp else 8.dp,
                vertical = if (compact) 2.5.dp else 4.dp
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
