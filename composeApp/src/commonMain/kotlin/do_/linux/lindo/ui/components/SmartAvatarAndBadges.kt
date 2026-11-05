package do_.linux.lindo.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
 * 解决 FluxDO / CFluxDo 的三大痛点：
 * 1. **全场景静态缩略图打底 + 动图无缝接管**：
 *    无论在话题列表还是帖子详情楼层，统一先展示极小体积的 `avatar_template` 静态打底层（或高对比度首字徽标）；
 *    当顶层 `animated_avatar` 首帧解码完成 (`animatedReady = true`) 后立即收起静态打底层，防止透明背景 GIF 透出底层静态图形成“双影”。
 * 2. **站点专属形态与呼吸光晕合一**：
 *    自动识别 `@neo` 10% 圆角方形裁切 + `#00AEFF` 冰蓝脉冲光晕，以及 `g-merchant` 商家 `#F5BF03` 鎏金脉冲光晕。
 * 3. **全局动态头像与光晕帧率开关 (`enableAnimations`)**：
 *    支持一键冻结动图与呼吸光晕 Ticker，在低功耗或省电模式下保持 0 额外重绘开销。
 */
@Composable
fun SmartAvatar(
    username: String,
    avatarTemplate: String?,
    animatedAvatar: String?,
    primaryGroupName: String? = null,
    flair: FlairInfo? = null,
    size: Dp = 42.dp,
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
    val shape: Shape = if (isSquare) RoundedCornerShape(percent = 14) else CircleShape

    val pulseAlpha = if (glowColor != null && enableAnimations) {
        val transition = rememberInfiniteTransition(label = "avatarGlow")
        val anim by transition.animateFloat(
            initialValue = 0.28f,
            targetValue = 0.78f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2400, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "glowPulse"
        )
        anim
    } else if (glowColor != null) {
        0.45f
    } else {
        0f
    }

    var animatedReady by remember(animatedUrl) { mutableStateOf(false) }
    var staticLoaded by remember(staticUrl) { mutableStateOf(false) }

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        // 外层脉冲光晕（仅针对 @neo 与 g-merchant 等特定身份开启）
        val outerMod = if (glowColor != null) {
            Modifier
                .fillMaxSize()
                .drawBehind {
                    drawCircle(
                        color = glowColor.copy(alpha = pulseAlpha * 0.45f),
                        radius = this.size.minDimension * 0.62f
                    )
                }
                .border(
                    width = 1.8.dp,
                    color = glowColor.copy(alpha = pulseAlpha),
                    shape = shape
                )
        } else {
            Modifier
                .fillMaxSize()
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    shape = shape
                )
        }

        Box(
            modifier = outerMod
                .padding(if (glowColor != null) 2.dp else 1.dp)
                .clip(shape)
                .background( avatarFallbackColor(username) ),
            contentAlignment = Alignment.Center
        ) {
            // 第一层：首字母高清矢量底（网络未返回或离线时秒显）
            if (!staticLoaded && !animatedReady) {
                Text(
                    text = username.firstOrNull()?.uppercase() ?: "L",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = (size.value * 0.42f).sp
                )
            }

            // 第二层：静态模板小图打底（当动图首帧就绪后立即隐藏，彻底消除透明 GIF 双影！）
            if (!animatedReady && staticUrl.isNotEmpty()) {
                AsyncImage(
                    model = staticUrl,
                    contentDescription = username,
                    contentScale = ContentScale.Crop,
                    onSuccess = { staticLoaded = true },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // 第三层：动态头像 overlay（首帧解码成功前透明，成功后原子切换并收起静态层）
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

            // 动态头像角标指示（方便直观识别 GIF/WebP 动图已激活）
            if (!animatedUrl.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(2.dp)
                        .background(
                            color = Color(0xCC000000),
                            shape = RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 3.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = "GIF",
                        color = Color(0xFF38BDF8),
                        fontSize = 7.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }

        // 右下角 Flair 群组身份角标
        if (flair != null && flair.hasFlair) {
            FlairBadgeOverlay(
                flair = flair,
                badgeSize = (size.value * 0.40f).coerceIn(14f, 22f).dp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 2.dp, y = 2.dp)
            )
        }
    }
}

/**
 * 右下角 Flair 群组身份徽章组件（支持 FA6 图标、SVG、位图与自定义背景色）
 */
@Composable
fun FlairBadgeOverlay(
    flair: FlairInfo,
    badgeSize: Dp = 16.dp,
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
        shadowElevation = 2.dp
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            when (visual) {
                is ResolvedBadgeVisual.VectorGlyph -> {
                    Icon(
                        imageVector = visual.icon,
                        contentDescription = flair.name,
                        tint = fgColor,
                        modifier = Modifier.size(badgeSize * 0.65f)
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
                        fontSize = (badgeSize.value * 0.55f).sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                is ResolvedBadgeVisual.MedalFallback -> {
                    Text(
                        text = visual.badgeInitial,
                        color = fgColor,
                        fontSize = (badgeSize.value * 0.52f).sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * Linux.do 全息流光渐变头衔（对齐 `种子用户` 与 `Premium` 的 CSS Holographic 特效）
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
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
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
            targetValue = 220f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 4500, easing = LinearEasing),
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
            Color(0xFF00E5FF),
            Color(0xFFFFEA00),
            Color(0xFFAA00FF),
            Color(0xFFFF2E93)
        ),
        start = Offset(shift, 0f),
        end = Offset(shift + 160f, 40f)
    )

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = Color(0x1FF43F5E)
    ) {
        Text(
            text = "✦ $title",
            style = TextStyle(
                brush = holoBrush,
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold
            ),
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/**
 * 帖子头部与徽章墙通用的全格式徽章图标组件 (`UniversalBadgeChip`)
 *
 * 无论徽章是 `.svg`、`.png`、FA6 图标、站点动态 `/svg-sprite/` 还是未来新出的未知图标，
 * 均能 100% 渲染，绝不出现空白或丢失。
 */
@Composable
fun UniversalBadgeChip(
    badge: GrantedBadge,
    compact: Boolean = true,
    modifier: Modifier = Modifier
) {
    val visual = remember(badge) {
        UniversalBadgeEngine.resolveBadgeVisual(badge, isDark = true)
    }
    val tierColor = remember(badge) {
        UniversalBadgeEngine.badgeTierColor(badge.badgeType, isDark = true)
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(if (compact) 6.dp else 10.dp),
        color = tierColor.copy(alpha = 0.14f),
        border = androidx.compose.foundation.BorderStroke(0.8.dp, tierColor.copy(alpha = 0.40f))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(
                horizontal = if (compact) 5.dp else 8.dp,
                vertical = if (compact) 2.dp else 4.dp
            )
        ) {
            when (visual) {
                is ResolvedBadgeVisual.VectorGlyph -> {
                    Icon(
                        imageVector = visual.icon,
                        contentDescription = badge.plainDescription,
                        tint = visual.tintColor,
                        modifier = Modifier.size(if (compact) 12.dp else 16.dp)
                    )
                }
                is ResolvedBadgeVisual.SvgImage -> {
                    // 使用 Coil SVG 解码器 + 矢量星章双保险，确保 .svg 徽章永不裂图
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(if (compact) 13.dp else 17.dp)) {
                        Text(
                            text = "✦",
                            color = tierColor,
                            fontSize = if (compact) 10.sp else 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        AsyncImage(
                            model = visual.url,
                            contentDescription = badge.plainDescription,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                is ResolvedBadgeVisual.BitmapImage -> {
                    AsyncImage(
                        model = visual.url,
                        contentDescription = badge.plainDescription,
                        modifier = Modifier.size(if (compact) 13.dp else 17.dp)
                    )
                }
                is ResolvedBadgeVisual.DynamicSpriteSvg -> {
                    Text(
                        text = "❖",
                        color = visual.tintColor,
                        fontSize = if (compact) 10.sp else 13.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                is ResolvedBadgeVisual.MedalFallback -> {
                    Box(
                        modifier = Modifier
                            .size(if (compact) 13.dp else 17.dp)
                            .background(visual.tintColor.copy(alpha = 0.25f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = visual.badgeInitial,
                            color = visual.tintColor,
                            fontSize = if (compact) 8.sp else 10.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = badge.name,
                color = tierColor,
                fontSize = if (compact) 10.sp else 12.sp,
                fontWeight = FontWeight.SemiBold,
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
