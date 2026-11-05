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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
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
    val staticUrl = remember(avatarTemplate) {
        LinuxDoSiteRules.resolveStaticAvatarUrl(avatarTemplate, sizePx = 120)
    }
    val animatedUrl = remember(animatedAvatar, enableAnimations) {
        if (enableAnimations) LinuxDoSiteRules.resolveAnimatedAvatarUrl(animatedAvatar) else null
    }
    val isSquare = remember(username, staticUrl) {
        LinuxDoSiteRules.isSquareAvatar(username, staticUrl)
    }
    val glowColor = remember(username, primaryGroupName) {
        LinuxDoSiteRules.matchAvatarGlowColor(username, primaryGroupName)
    }
    val shape: Shape = if (isSquare) RoundedCornerShape(percent = 12) else CircleShape

    val pulseAlpha = if (glowColor != null && enableAnimations) {
        val transition = rememberInfiniteTransition(label = "avatarGlow")
        val anim by transition.animateFloat(
            initialValue = 0.30f,
            targetValue = 0.80f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2200, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "glowPulse"
        )
        anim
    } else if (glowColor != null) {
        0.50f
    } else {
        0f
    }

    var animatedReady by remember(animatedUrl) { mutableStateOf(false) }
    var staticLoaded by remember(staticUrl) { mutableStateOf(false) }

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        val outerMod = if (glowColor != null) {
            Modifier
                .fillMaxSize()
                .drawBehind {
                    drawCircle(
                        color = glowColor.copy(alpha = pulseAlpha * 0.35f),
                        radius = this.size.minDimension * 0.58f
                    )
                }
                .border(
                    width = 1.6.dp,
                    color = glowColor.copy(alpha = pulseAlpha),
                    shape = shape
                )
        } else {
            Modifier
                .fillMaxSize()
                .border(
                    width = 0.6.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                    shape = shape
                )
        }

        Box(
            modifier = outerMod
                .padding(if (glowColor != null) 1.6.dp else 0.6.dp)
                .clip(shape)
                .background(avatarFallbackColor(username)),
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

        if (flair != null && flair.hasFlair) {
            FlairBadgeOverlay(
                flair = flair,
                badgeSize = (size.value * 0.38f).coerceIn(13f, 18f).dp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 2.dp, y = 1.dp)
            )
        }
    }
}

/**
 * 右下角 Flair 群组身份徽章组件
 */
@Composable
fun FlairBadgeOverlay(
    flair: FlairInfo,
    badgeSize: Dp = 15.dp,
    modifier: Modifier = Modifier
) {
    val bgColor = LinuxDoSiteRules.parseHexColor(flair.bgColorHex, fallback = Color(0xFF1E293B))
    val fgColor = LinuxDoSiteRules.parseHexColor(flair.fgColorHex, fallback = Color.White)

    val tempBadge = remember(flair) {
        GrantedBadge(
            id = flair.groupId ?: 0,
            name = flair.name ?: "Flair",
            badgeTypeId = 1,
            icon = flair.url
        )
    }
    val visual = remember(tempBadge) {
        UniversalBadgeEngine.resolveBadgeVisual(tempBadge, isDark = true)
    }

    Surface(
        modifier = modifier.size(badgeSize),
        shape = CircleShape,
        color = bgColor,
        shadowElevation = 1.dp
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            when (visual) {
                is ResolvedBadgeVisual.VectorGlyph -> {
                    Icon(
                        imageVector = visual.icon,
                        contentDescription = flair.name,
                        tint = fgColor,
                        modifier = Modifier.size(badgeSize * 0.62f)
                    )
                }
                is ResolvedBadgeVisual.SvgImage -> {
                    AsyncImage(
                        model = visual.url,
                        contentDescription = flair.name,
                        modifier = Modifier.size(badgeSize * 0.72f)
                    )
                }
                is ResolvedBadgeVisual.BitmapImage -> {
                    AsyncImage(
                        model = visual.url,
                        contentDescription = flair.name,
                        modifier = Modifier.size(badgeSize * 0.72f)
                    )
                }
                is ResolvedBadgeVisual.DynamicSpriteSvg -> {
                    Text(
                        text = "✦",
                        color = fgColor,
                        fontSize = (badgeSize.value * 0.52f).sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                is ResolvedBadgeVisual.MedalFallback -> {
                    Text(
                        text = visual.badgeInitial,
                        color = fgColor,
                        fontSize = (badgeSize.value * 0.50f).sp,
                        fontWeight = FontWeight.Bold
                    )
                }
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

    val shift = if (enableAnimations) {
        val transition = rememberInfiniteTransition(label = "holoTitle")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = 200f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 4200, easing = LinearEasing),
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
            Color(0xFFFF2E93),
            Color(0xFF00B4D8),
            Color(0xFFF59E0B),
            Color(0xFF9333EA),
            Color(0xFFFF2E93)
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
 *
 * 对齐 Discourse 原生及 FluxDO `post_granted_badge.dart` 的 14dp 行内图标形态，
 * 同时具备 LinDo 五合一引擎（SVG / 动态 Sprite / FA6 / 位图 / 零丢失勋章兜底）。
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
            .padding(start = 3.dp)
            .size(15.dp)
            .let { mod ->
                if (onClick != null) mod.clickable { onClick(badge) } else mod
            }
    ) {
        when (visual) {
            is ResolvedBadgeVisual.VectorGlyph -> {
                Icon(
                    imageVector = visual.icon,
                    contentDescription = badge.name,
                    tint = tierColor,
                    modifier = Modifier.size(13.dp)
                )
            }
            is ResolvedBadgeVisual.SvgImage -> {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector = UniversalBadgeEngine.resolveBadgeVisual(
                            badge.copy(imageUrl = null, icon = "fa-certificate"),
                            isDark = false
                        ).let { (it as? ResolvedBadgeVisual.VectorGlyph)?.icon }
                            ?: Icons.Default.Star,
                        contentDescription = null,
                        tint = tierColor,
                        modifier = Modifier.size(12.dp)
                    )
                    AsyncImage(
                        model = visual.url,
                        contentDescription = badge.name,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            is ResolvedBadgeVisual.BitmapImage -> {
                AsyncImage(
                    model = visual.url,
                    contentDescription = badge.name,
                    modifier = Modifier.size(14.dp)
                )
            }
            is ResolvedBadgeVisual.DynamicSpriteSvg -> {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = badge.name,
                    tint = tierColor,
                    modifier = Modifier.size(13.dp)
                )
            }
            is ResolvedBadgeVisual.MedalFallback -> {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .background(tierColor.copy(alpha = 0.22f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = visual.badgeInitial,
                        color = tierColor,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * 徽章墙与用户资料卡使用的完整徽章胶囊组件 (`UniversalBadgeChip`)
 */
@Composable
fun UniversalBadgeChip(
    badge: GrantedBadge,
    compact: Boolean = true,
    modifier: Modifier = Modifier
) {
    val visual = remember(badge) {
        UniversalBadgeEngine.resolveBadgeVisual(badge, isDark = false)
    }
    val tierColor = remember(badge) {
        UniversalBadgeEngine.badgeTierColor(badge.badgeType, isDark = false)
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(if (compact) 6.dp else 8.dp),
        color = tierColor.copy(alpha = 0.10f),
        border = BorderStroke(0.6.dp, tierColor.copy(alpha = 0.35f))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(
                horizontal = if (compact) 6.dp else 8.dp,
                vertical = if (compact) 2.dp else 4.dp
            )
        ) {
            PostGrantedBadgeIcon(badge = badge)
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = badge.name,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = if (compact) 11.sp else 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
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
