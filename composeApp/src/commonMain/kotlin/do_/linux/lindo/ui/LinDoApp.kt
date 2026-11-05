package do_.linux.lindo.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import do_.linux.lindo.core.connect.ConnectTrustLevelReport
import do_.linux.lindo.core.connect.ScreenTrackEngine
import do_.linux.lindo.core.connect.UserPresenceService
import do_.linux.lindo.core.model.CommunitySearchResult
import do_.linux.lindo.core.model.GrantedBadge
import do_.linux.lindo.core.model.PostItemModel
import do_.linux.lindo.core.model.TopicDetailModel
import do_.linux.lindo.core.model.TopicSummary
import do_.linux.lindo.core.model.TopicUser
import do_.linux.lindo.core.model.UserActivityItem
import do_.linux.lindo.core.model.UserFullProfileSummary
import do_.linux.lindo.core.model.UserNotificationItem
import do_.linux.lindo.core.network.AuthenticatedUserProfile
import do_.linux.lindo.core.network.CloudflareEdgeRacer
import do_.linux.lindo.core.network.EdgeCandidateResult
import do_.linux.lindo.core.network.LinuxDoCategoryMeta
import do_.linux.lindo.core.network.LinuxDoRepository
import do_.linux.lindo.core.network.UserApiKeyAuthManager
import do_.linux.lindo.core.render.DiscourseAstParser
import do_.linux.lindo.core.render.PostBlockNode
import do_.linux.lindo.core.site.LinuxDoSiteRules
import do_.linux.lindo.ui.components.PostGrantedBadgeIcon
import do_.linux.lindo.ui.components.SmartAvatar
import do_.linux.lindo.ui.components.UniversalBadgeChip
import do_.linux.lindo.ui.components.UserTitleTag
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Linear / Threads / X 极简黑白现代风 + Linux.do 琥珀金 (`#FFB003`) 色彩系统：
 * - 纯净高对比黑白灰表面 (`#FFFFFF` / `#FAFAFA` / `#F4F4F5` 与暗色 `#09090B` / `#121215` / `#18181B`)
 * - 0.5dp 锋利微描边 (`#E4E4E7` / `#27272A`)
 * - 曜石黑主行动色 (`#09090B`) 搭配 Linux.do 标志性琥珀金 (`#FFB003`) 点缀
 */
data class LinDoThemePalette(
    val isDark: Boolean,
    val canvas: Color,
    val groupedCanvas: Color,
    val cardSurface: Color,
    val subtleSurface: Color,
    val navBarSurface: Color,
    val tonalPill: Color,
    val onTonalPill: Color,
    val inkPrimary: Color,
    val inkSecondary: Color,
    val inkTertiary: Color,
    val hairline: Color,
    val accent: Color,
    val brandGold: Color,
    val successContainer: Color,
    val onSuccessContainer: Color
)

private val LightPalette = LinDoThemePalette(
    isDark = false,
    canvas = Color(0xFFFAFAFA),
    groupedCanvas = Color(0xFFF4F4F5),
    cardSurface = Color(0xFFFFFFFF),
    subtleSurface = Color(0xFFF4F4F5),
    navBarSurface = Color(0xFFFFFFFF),
    tonalPill = Color(0xFF09090B),
    onTonalPill = Color(0xFFFFFFFF),
    inkPrimary = Color(0xFF09090B),
    inkSecondary = Color(0xFF52525B),
    inkTertiary = Color(0xFFA1A1AA),
    hairline = Color(0xFFE4E4E7),
    accent = Color(0xFF09090B),
    brandGold = Color(0xFFFFB003),
    successContainer = Color(0xFFFEF3C7),
    onSuccessContainer = Color(0xFF92400E)
)

private val DarkPalette = LinDoThemePalette(
    isDark = true,
    canvas = Color(0xFF09090B),
    groupedCanvas = Color(0xFF09090B),
    cardSurface = Color(0xFF121215),
    subtleSurface = Color(0xFF18181B),
    navBarSurface = Color(0xFF09090B),
    tonalPill = Color(0xFFFAFAFA),
    onTonalPill = Color(0xFF09090B),
    inkPrimary = Color(0xFFFAFAFA),
    inkSecondary = Color(0xFFA1A1AA),
    inkTertiary = Color(0xFF71717A),
    hairline = Color(0xFF27272A),
    accent = Color(0xFFFAFAFA),
    brandGold = Color(0xFFFFB003),
    successContainer = Color(0xFF3F2E05),
    onSuccessContainer = Color(0xFFFCD34D)
)

private val LocalPalette = staticCompositionLocalOf { LightPalette }

private val LightMaterialScheme = lightColorScheme(
    primary = LightPalette.accent,
    onPrimary = Color.White,
    primaryContainer = LightPalette.tonalPill,
    onPrimaryContainer = LightPalette.onTonalPill,
    background = LightPalette.canvas,
    onBackground = LightPalette.inkPrimary,
    surface = LightPalette.cardSurface,
    onSurface = LightPalette.inkPrimary,
    surfaceVariant = LightPalette.subtleSurface,
    onSurfaceVariant = LightPalette.inkSecondary,
    outlineVariant = LightPalette.hairline
)

private val DarkMaterialScheme = darkColorScheme(
    primary = DarkPalette.accent,
    onPrimary = Color(0xFF09090B),
    primaryContainer = DarkPalette.tonalPill,
    onPrimaryContainer = DarkPalette.onTonalPill,
    background = DarkPalette.canvas,
    onBackground = DarkPalette.inkPrimary,
    surface = DarkPalette.cardSurface,
    onSurface = DarkPalette.inkPrimary,
    surfaceVariant = DarkPalette.subtleSurface,
    onSurfaceVariant = DarkPalette.inkSecondary,
    outlineVariant = DarkPalette.hairline
)

private val LocalFontScale = staticCompositionLocalOf { 1.0f }

private enum class MainTab(
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    HOME("首页", Icons.Filled.Home, Icons.Outlined.Home),
    DISCOVER("探索", Icons.Filled.GridView, Icons.Outlined.GridView),
    PROFILE("账号", Icons.Filled.Person, Icons.Outlined.Person)
}

private enum class FeedSortFilter(val label: String, val apiPath: String) {
    LATEST("最新", "latest.json"),
    UNREAD("未读", "unread.json"),
    NEW("新帖", "new.json"),
    TOP("热门", "top.json"),
    READ("已读", "read.json")
}

private enum class TopPeriodFilter(val label: String, val apiPeriod: String) {
    DAILY("今日", "daily"),
    WEEKLY("本周", "weekly"),
    MONTHLY("本月", "monthly"),
    YEARLY("今年", "yearly"),
    ALL("全部", "all")
}

/**
 * Linear 风格流光加载细线（黑·琥珀金·黑微光扫掠动画，取代生硬的默认进度条）
 */
@Composable
private fun LinearLuminanceBar(modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val transition = rememberInfiniteTransition(label = "luminance")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1050, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "luminance_phase"
    )
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(2.dp)
            .clip(RoundedCornerShape(1.dp))
    ) {
        val w = size.width
        drawRect(color = palette.hairline.copy(alpha = 0.45f))
        val barWidth = w * 0.36f
        val startX = (w + barWidth) * phase - barWidth
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    Color.Transparent,
                palette.brandGold,
                    palette.inkPrimary,
                    palette.brandGold,
                    Color.Transparent
                ),
                startX = startX,
                endX = startX + barWidth
            ),
            topLeft = Offset(startX.coerceAtLeast(0f), 0f),
            size = Size(barWidth, size.height)
        )
    }
}

/**
 * Linear 风格三点波浪呼吸脉冲底部加载器（支持分页加载中、点击加载更多、已加载全部三种优雅状态）
 */
@Composable
private fun LinearPulseLoadingFooter(
    isLoadingMore: Boolean,
    hasMore: Boolean,
    loadedCount: Int,
    loadingText: String = "正在同步更多话题",
    moreText: String = "加载更多话题",
    doneText: String = "已加载全部 $loadedCount 个话题",
    onLoadMore: () -> Unit
) {
    val palette = LocalPalette.current
    val transition = rememberInfiniteTransition(label = "pulse_dots")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_progress"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        if (!hasMore && !isLoadingMore) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(24.dp)
                        .height(0.5.dp)
                        .background(palette.hairline)
                )
                Text(
                    text = doneText,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.inkTertiary
                )
                Box(
                    modifier = Modifier
                        .width(24.dp)
                        .height(0.5.dp)
                        .background(palette.hairline)
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(palette.cardSurface)
                    .border(0.5.dp, palette.hairline, RoundedCornerShape(999.dp))
                    .clickable(enabled = !isLoadingMore && hasMore) { onLoadMore() }
                    .padding(horizontal = 16.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                if (isLoadingMore) {
                    repeat(3) { idx ->
                        val offsetPhase = ((progress - idx * 0.22f) + 1f) % 1f
                        val pulse = kotlin.math.sin(offsetPhase * kotlin.math.PI.toFloat()).coerceIn(0f, 1f)
                        val scale = 0.72f + 0.36f * pulse
                        Box(
                            modifier = Modifier
                                .size(5.5.dp)
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    alpha = 0.35f + 0.65f * pulse
                                }
                                .clip(CircleShape)
                                .background(if (idx == 1) palette.brandGold else palette.inkPrimary)
                        )
                    }
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = loadingText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = palette.inkSecondary
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(5.5.dp)
                            .clip(CircleShape)
                            .background(palette.brandGold)
                    )
                    Text(
                        text = moreText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.inkPrimary
                    )
                }
            }
        }
    }
}

/**
 * Linear 风格银灰流光骨架屏（切换板块/排序或首次加载时提供丝滑占位，告别空白闪烁）
 */
@Composable
private fun LinearShimmerTopicSkeletonList(isCompact: Boolean = false) {
    val palette = LocalPalette.current
    val transition = rememberInfiniteTransition(label = "shimmer")
    val alphaAnim by transition.animateFloat(
        initialValue = 0.38f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 720, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmer_alpha"
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(if (isCompact) 5.dp else 8.dp)
    ) {
        repeat(if (isCompact) 7 else 5) { idx ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(if (isCompact) 12.dp else 16.dp))
                    .background(palette.cardSurface)
                    .border(0.5.dp, palette.hairline, RoundedCornerShape(if (isCompact) 12.dp else 16.dp))
                    .padding(
                        horizontal = if (isCompact) 12.dp else 15.dp,
                        vertical = if (isCompact) 10.dp else 14.dp
                    )
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(if (isCompact) 20.dp else 24.dp)
                            .clip(CircleShape)
                            .background(palette.subtleSurface.copy(alpha = alphaAnim))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .width(92.dp)
                            .height(11.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(palette.subtleSurface.copy(alpha = alphaAnim))
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .width(52.dp)
                            .height(16.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(palette.subtleSurface.copy(alpha = alphaAnim))
                    )
                }
                Spacer(modifier = Modifier.height(if (isCompact) 7.dp else 10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(if (idx % 2 == 0) 0.92f else 0.78f)
                        .height(if (isCompact) 14.dp else 16.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(palette.subtleSurface.copy(alpha = alphaAnim))
                )
                if (!isCompact) {
                    Spacer(modifier = Modifier.height(7.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.62f)
                            .height(12.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(palette.subtleSurface.copy(alpha = alphaAnim * 0.75f))
                    )
                }
                Spacer(modifier = Modifier.height(if (isCompact) 7.dp else 10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .width(46.dp)
                            .height(14.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(palette.subtleSurface.copy(alpha = alphaAnim * 0.8f))
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .width(68.dp)
                            .height(12.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(palette.subtleSurface.copy(alpha = alphaAnim * 0.75f))
                    )
                }
            }
        }
    }
}

object BackNavigationBridge {
    var canGoBack: Boolean = false
    var onBack: (() -> Boolean)? = null
}

/**
 * 原生平台内置 WebView 登录宿主桥接器
 */
object PlatformWebLoginHost {
    var webLoginRenderer: (@Composable (
        initialUrl: String,
        onDismiss: () -> Unit,
        onLoginSuccess: () -> Unit
    ) -> Unit)? = null
}

/**
 * Linux.do 标志性黑·白·琥珀金三色立体微徽章（与桌面玻璃拟态启动图标同源）
 */
@Composable
private fun GeminiSparkleIcon(
    size: Dp = 18.dp,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.size(size)) {
        val r = this.size.minDimension / 2f
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        // 外圈亮瓷白描边底
        drawCircle(
            color = Color(0xFFE4E4E7),
            radius = r,
            center = center
        )
        val innerR = r * 0.88f
        val clipPath = Path().apply {
            addOval(
                androidx.compose.ui.geometry.Rect(
                    left = center.x - innerR,
                    top = center.y - innerR,
                    right = center.x + innerR,
                    bottom = center.y + innerR
                )
            )
        }
        val d = innerR * 2f
        val topY = center.y - innerR
        val b1 = d * 0.32f
        val b2 = d * 0.36f
        val b3 = d - b1 - b2
        drawContext.canvas.save()
        drawContext.canvas.clipPath(clipPath)
        // 顶部曜石黑
        drawRect(
            color = Color(0xFF141417),
            topLeft = Offset(center.x - innerR, topY),
            size = Size(d, b1)
        )
        // 中部晶透白
        drawRect(
            color = Color(0xFFFFFFFF),
            topLeft = Offset(center.x - innerR, topY + b1),
            size = Size(d, b2)
        )
        // 底部琥珀金
        drawRect(
            color = Color(0xFFFFB003),
            topLeft = Offset(center.x - innerR, topY + b1 + b2),
            size = Size(d, b3)
        )
        drawContext.canvas.restore()
    }
}

/**
 * 明亮玻璃拟态立体社区图标（通透白底圆角方砖 + 立体黑/白/琥珀金三色球体徽章 + 暖金底光）
 */
@Composable
private fun LinDoGlassAppIcon(
    tileSize: Dp = 84.dp,
    modifier: Modifier = Modifier
) {
    val cornerRadius = tileSize * 0.26f
    Box(
        modifier = modifier
            .size(tileSize)
            .clip(RoundedCornerShape(cornerRadius))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFFFFFFFF),
                        Color(0xFFF6F7F9)
                    )
                )
            )
            .border(
                width = 1.dp,
                color = Color(0xFFE4E4E7),
                shape = RoundedCornerShape(cornerRadius)
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f
            val emblemR = w * 0.32f

            // 1. 底部琥珀金环境投影光晕
            drawOval(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0x55FFB003),
                        Color(0x00FFB003)
                    ),
                    center = Offset(cx, cy + emblemR * 0.72f),
                    radius = emblemR * 0.95f
                ),
                topLeft = Offset(cx - emblemR * 0.9f, cy + emblemR * 0.1f),
                size = Size(emblemR * 1.8f, emblemR * 1.15f)
            )

            // 2. 磨砂玻璃外环
            val outerR = emblemR * 1.10f
            drawCircle(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFFFFFFFF),
                        Color(0xFFECECEE)
                    ),
                    start = Offset(cx - outerR, cy - outerR),
                    end = Offset(cx + outerR, cy + outerR)
                ),
                radius = outerR,
                center = Offset(cx, cy)
            )
            drawCircle(
                color = Color(0xFFD4D4D8),
                radius = outerR,
                center = Offset(cx, cy),
                style = Stroke(width = 1.dp.toPx())
            )

            // 3. 内部黑·白·琥珀金三色立体徽标
            val innerR = emblemR * 0.96f
            val clipPath = Path().apply {
                addOval(
                    androidx.compose.ui.geometry.Rect(
                        left = cx - innerR,
                        top = cy - innerR,
                        right = cx + innerR,
                        bottom = cy + innerR
                    )
                )
            }
            val d = innerR * 2f
            val topY = cy - innerR
            val b1 = d * 0.315f
            val b2 = d * 0.37f
            val b3 = d - b1 - b2

            drawContext.canvas.save()
            drawContext.canvas.clipPath(clipPath)

            // 顶层曜石黑
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF27272A), Color(0xFF09090B)),
                    startY = topY,
                    endY = topY + b1
                ),
                topLeft = Offset(cx - innerR, topY),
                size = Size(d, b1)
            )
            // 中层晶透瓷白
            drawRect(
                color = Color(0xFFFAFAFA),
                topLeft = Offset(cx - innerR, topY + b1),
                size = Size(d, b2)
            )
            // 底层流光琥珀金
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFFFFC11C), Color(0xFFFFB003), Color(0xFFEA8A00)),
                    startY = topY + b1 + b2,
                    endY = topY + d
                ),
                topLeft = Offset(cx - innerR, topY + b1 + b2),
                size = Size(d, b3)
            )
            // 顶部玻璃高光反射弧
            drawOval(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0x66FFFFFF),
                        Color(0x00FFFFFF)
                    ),
                    startY = topY,
                    endY = cy
                ),
                topLeft = Offset(cx - innerR * 0.8f, topY - innerR * 0.1f),
                size = Size(innerR * 1.6f, innerR * 0.95f)
            )
            drawContext.canvas.restore()
        }
    }
}

/**
 * 极简黑白 + 琥珀金双色环绕用户头像
 */
@Composable
private fun GoogleAccountRingAvatar(
    letter: String = "L",
    avatarUrl: String? = null,
    avatarSize: Dp = 76.dp,
    strokeWidth: Dp = 2.5.dp,
    onClick: (() -> Unit)? = null
) {
    val palette = LocalPalette.current
    val resolvedAvatarUrl = remember(avatarUrl) {
        avatarUrl?.takeIf { it.isNotBlank() }?.let { raw ->
            val sized = raw.replace("{size}", "144")
            when {
                sized.startsWith("http://") || sized.startsWith("https://") -> sized
                sized.startsWith("//") -> "https:$sized"
                sized.startsWith("/") -> "https://linux.do$sized"
                else -> "https://linux.do/$sized"
            }
        }
    }
    Box(
        modifier = Modifier
            .size(avatarSize)
            .clip(CircleShape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokePx = strokeWidth.toPx()
            val inset = strokePx / 2f
            val arcSize = Size(size.width - strokePx, size.height - strokePx)
            val topLeft = Offset(inset, inset)
            // 上半环：曜石黑/银白，下半环：Linux.do 琥珀金
            drawArc(
                color = if (palette.isDark) Color(0xFFFAFAFA) else Color(0xFF18181B),
                startAngle = -165f,
                sweepAngle = 195f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )
            drawArc(
                color = Color(0xFFFFB003),
                startAngle = 42f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )
        }

        Box(
            modifier = Modifier
                .size(avatarSize - strokeWidth * 3.6f)
                .clip(CircleShape)
                .background(palette.subtleSurface)
                .border(0.5.dp, palette.hairline, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (resolvedAvatarUrl != null) {
                AsyncImage(
                    model = resolvedAvatarUrl,
                    contentDescription = letter,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                )
            } else {
                Text(
                    text = letter.take(1).uppercase(),
                    fontSize = (avatarSize.value * 0.34f).sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.inkPrimary
                )
            }
        }
    }
}

@Composable
fun LinDoApp() {
    var isDarkTheme by remember { mutableStateOf(UserApiKeyAuthManager.uiDarkTheme) }
    var fontScale by remember { mutableStateOf(UserApiKeyAuthManager.uiFontScale) }
    var enableAnimations by remember { mutableStateOf(UserApiKeyAuthManager.uiEnableAnimations) }
    val palette = if (isDarkTheme) DarkPalette else LightPalette

    fun updateThemePref(dark: Boolean) {
        isDarkTheme = dark
        UserApiKeyAuthManager.updateUiPrefs(
            fontScale = fontScale,
            darkTheme = dark,
            enableAnimations = enableAnimations
        )
    }

    fun updateFontScalePref(scale: Float) {
        fontScale = scale
        UserApiKeyAuthManager.updateUiPrefs(
            fontScale = scale,
            darkTheme = isDarkTheme,
            enableAnimations = enableAnimations
        )
    }

    fun updateAnimationsPref(enabled: Boolean) {
        enableAnimations = enabled
        UserApiKeyAuthManager.updateUiPrefs(
            fontScale = fontScale,
            darkTheme = isDarkTheme,
            enableAnimations = enabled
        )
    }

    var currentTab by remember { mutableStateOf(MainTab.HOME) }
    var selectedCategoryId by remember { mutableStateOf<Int?>(null) }
    var selectedTag by remember { mutableStateOf<String?>(null) }
    var currentSort by remember { mutableStateOf(FeedSortFilter.LATEST) }
    var currentTopPeriod by remember { mutableStateOf(TopPeriodFilter.DAILY) }
    var searchQuery by remember { mutableStateOf("") }

    var topics by remember { mutableStateOf<List<TopicSummary>>(emptyList()) }
    var currentPage by remember { mutableStateOf(0) }
    var hasMoreTopics by remember { mutableStateOf(true) }
    var isLoadingTopics by remember { mutableStateOf(true) }
    var isLoadingMoreTopics by remember { mutableStateOf(false) }
    var feedError by remember { mutableStateOf<String?>(null) }

    var activeTopicSummary by remember { mutableStateOf<TopicSummary?>(null) }
    var activeTopicDetail by remember { mutableStateOf<TopicDetailModel?>(null) }
    var isLoadingDetail by remember { mutableStateOf(false) }
    var mobileShowingDetail by remember { mutableStateOf(false) }

    // 全局官方功能抽屉状态
    var inspectedBadge by remember { mutableStateOf<GrantedBadge?>(null) }
    var inspectedUsername by remember { mutableStateOf<String?>(null) }
    var showNetworkSheet by remember { mutableStateOf(false) }
    var showAuthSheet by remember { mutableStateOf(false) }
    var showConnectSheet by remember { mutableStateOf(false) }
    var showGlobalSearchSheet by remember { mutableStateOf(false) }
    var showCreateTopicSheet by remember { mutableStateOf(false) }
    var showNotificationsSheet by remember { mutableStateOf(false) }
    var showActivitiesSheet by remember { mutableStateOf(false) }
    var showBookmarksSheet by remember { mutableStateOf(false) }
    var showPrivateMessagesSheet by remember { mutableStateOf(false) }
    var pmInitialRecipient by remember { mutableStateOf<String?>(null) }
    var showEditProfileSheet by remember { mutableStateOf(false) }

    // 认证状态与真实个人资料状态
    var authVersion by remember { mutableStateOf(0) }
    var guestPreviewMode by remember { mutableStateOf(false) }
    var activeWebLoginUrl by remember { mutableStateOf<String?>(null) }
    var fullUserProfile by remember { mutableStateOf<UserFullProfileSummary?>(UserApiKeyAuthManager.fullProfileCache) }

    val isAuthorized = remember(authVersion) { UserApiKeyAuthManager.isAuthorized() }
    val currentUser = remember(authVersion) { UserApiKeyAuthManager.currentUser }

    val feedListState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    fun syncUserProfile() {
        scope.launch {
            val profile = LinuxDoRepository.fetchCurrentUserProfile()
            if (profile != null) {
                fullUserProfile = profile
                authVersion++
            }
        }
    }

    fun loadTopics(
        sort: FeedSortFilter = currentSort,
        categoryId: Int? = selectedCategoryId,
        tag: String? = selectedTag,
        topPeriod: TopPeriodFilter = currentTopPeriod
    ) {
        scope.launch {
            isLoadingTopics = true
            isLoadingMoreTopics = false
            feedError = null
            currentPage = 0
            hasMoreTopics = true
            val fetched = LinuxDoRepository.fetchTopics(
                path = sort.apiPath,
                categoryId = categoryId,
                page = 0,
                tag = tag,
                topPeriod = if (sort == FeedSortFilter.TOP) topPeriod.apiPeriod else null
            )
            if (fetched.isNotEmpty()) {
                topics = fetched
                hasMoreTopics = fetched.size >= 15
                if (activeTopicSummary == null) {
                    val first = fetched.firstOrNull { !it.pinned } ?: fetched.first()
                    activeTopicSummary = first
                    scope.launch {
                        activeTopicDetail = LinuxDoRepository.fetchTopicDetail(first.id, first)
                    }
                }
                feedListState.scrollToItem(0)
            } else if (topics.isEmpty()) {
                feedError = "网络连接异常，请轻触重试"
                hasMoreTopics = false
            }
            isLoadingTopics = false
        }
    }

    fun loadNextPageTopics() {
        if (isLoadingTopics || isLoadingMoreTopics || !hasMoreTopics) return
        scope.launch {
            isLoadingMoreTopics = true
            val nextPage = currentPage + 1
            val more = LinuxDoRepository.fetchTopics(
                path = currentSort.apiPath,
                categoryId = selectedCategoryId,
                page = nextPage,
                tag = selectedTag,
                topPeriod = if (currentSort == FeedSortFilter.TOP) currentTopPeriod.apiPeriod else null
            )
            if (more.isNotEmpty()) {
                val existingIds = topics.map { it.id }.toSet()
                val uniqueNew = more.filterNot { it.id in existingIds }
                if (uniqueNew.isNotEmpty()) {
                    currentPage = nextPage
                    topics = topics + uniqueNew
                    hasMoreTopics = more.size >= 10
                } else {
                    hasMoreTopics = false
                }
            } else {
                hasMoreTopics = false
            }
            isLoadingMoreTopics = false
        }
    }

    DisposableEffect(Unit) {
        val prevCallback = UserApiKeyAuthManager.onAuthStateChanged
        UserApiKeyAuthManager.onAuthStateChanged = {
            prevCallback?.invoke()
            authVersion++
        }
        onDispose {
            UserApiKeyAuthManager.onAuthStateChanged = prevCallback
        }
    }

    LaunchedEffect(authVersion) {
        val otp = UserApiKeyAuthManager.consumePendingOtpToken()
        if (!otp.isNullOrBlank()) {
            activeWebLoginUrl = "https://linux.do/session/otp/$otp"
            return@LaunchedEffect
        }
        if (UserApiKeyAuthManager.isAuthorized()) {
            activeWebLoginUrl = null
            showAuthSheet = false
            loadTopics(currentSort, selectedCategoryId)
            syncUserProfile()
        }
    }

    fun openTopic(topic: TopicSummary) {
        activeTopicSummary = topic
        mobileShowingDetail = true
        currentTab = MainTab.HOME
        scope.launch {
            isLoadingDetail = true
            activeTopicDetail = LinuxDoRepository.fetchTopicDetail(topic.id, topic)
            isLoadingDetail = false
        }
    }

    fun openTopicById(topicId: Int, fallbackTitle: String = "话题 #$topicId") {
        val existing = topics.firstOrNull { it.id == topicId } ?: TopicSummary(
            id = topicId,
            title = fallbackTitle,
            postsCount = 1,
            replyCount = 0,
            views = 0,
            likeCount = 0,
            categoryId = 4,
            categoryName = "技术讨论",
            categoryColorHex = "0E76BD",
            tags = emptyList(),
            lastPostedAt = "刚刚",
            originalPoster = TopicUser(id = 0, username = "linuxdo")
        )
        openTopic(existing)
    }

    LaunchedEffect(Unit) {
        loadTopics(FeedSortFilter.LATEST, null)
        syncUserProfile()
    }

    SideEffect {
        val canIntercept = activeWebLoginUrl != null ||
            inspectedBadge != null ||
            inspectedUsername != null ||
            showGlobalSearchSheet ||
            showCreateTopicSheet ||
            showNotificationsSheet ||
            showActivitiesSheet ||
            showBookmarksSheet ||
            showPrivateMessagesSheet ||
            showEditProfileSheet ||
            showConnectSheet ||
            showNetworkSheet ||
            showAuthSheet ||
            mobileShowingDetail ||
            currentTab != MainTab.HOME
        BackNavigationBridge.canGoBack = canIntercept
        BackNavigationBridge.onBack = {
            when {
                activeWebLoginUrl != null -> {
                    activeWebLoginUrl = null
                    true
                }
                inspectedBadge != null -> {
                    inspectedBadge = null
                    true
                }
                inspectedUsername != null -> {
                    inspectedUsername = null
                    true
                }
                showGlobalSearchSheet -> {
                    showGlobalSearchSheet = false
                    true
                }
                showCreateTopicSheet -> {
                    showCreateTopicSheet = false
                    true
                }
                showNotificationsSheet -> {
                    showNotificationsSheet = false
                    true
                }
                showActivitiesSheet -> {
                    showActivitiesSheet = false
                    true
                }
                showBookmarksSheet -> {
                    showBookmarksSheet = false
                    true
                }
                showPrivateMessagesSheet -> {
                    showPrivateMessagesSheet = false
                    true
                }
                showEditProfileSheet -> {
                    showEditProfileSheet = false
                    true
                }
                showConnectSheet -> {
                    showConnectSheet = false
                    true
                }
                showNetworkSheet -> {
                    showNetworkSheet = false
                    true
                }
                showAuthSheet -> {
                    showAuthSheet = false
                    true
                }
                mobileShowingDetail -> {
                    mobileShowingDetail = false
                    true
                }
                currentTab != MainTab.HOME -> {
                    currentTab = MainTab.HOME
                    true
                }
                else -> false
            }
        }
    }

    val displayedTopics = remember(topics, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) {
            topics
        } else {
            topics.filter { topic ->
                val authorDisplay = topic.originalPoster.name?.ifBlank { topic.originalPoster.username }
                    ?: topic.originalPoster.username
                topic.title.contains(q, ignoreCase = true) ||
                    authorDisplay.contains(q, ignoreCase = true) ||
                    topic.originalPoster.username.contains(q, ignoreCase = true) ||
                    topic.tags.any { it.contains(q, ignoreCase = true) }
            }
        }
    }

    val baseDensity = LocalDensity.current
    val scaledDensity = remember(baseDensity, fontScale) {
        Density(
            density = baseDensity.density,
            fontScale = baseDensity.fontScale * fontScale
        )
    }

    CompositionLocalProvider(
        LocalPalette provides palette,
        LocalFontScale provides fontScale,
        LocalDensity provides scaledDensity
    ) {
        MaterialTheme(
            colorScheme = if (isDarkTheme) DarkMaterialScheme else LightMaterialScheme
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = palette.canvas
            ) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val isDesktop = maxWidth >= 840.dp

                    if (!isAuthorized && !guestPreviewMode) {
                        GoogleWelcomeLoginScreen(
                            onPrimaryWebLogin = {
                                activeWebLoginUrl = "https://linux.do/login"
                            },
                            onBrowserOneClickAuth = {
                                val url = UserApiKeyAuthManager.startBrowserOneClickAuth()
                                if (UserApiKeyAuthManager.openExternalUrlLauncher == null) {
                                    activeWebLoginUrl = url
                                }
                            },
                            onEnterGuestPreview = {
                                guestPreviewMode = true
                            }
                        )
                    } else if (isDesktop) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            DesktopSideRail(
                                currentTab = currentTab,
                                isDarkTheme = isDarkTheme,
                                onSelectTab = { currentTab = it },
                                onToggleTheme = { updateThemePref(!isDarkTheme) }
                            )

                            when (currentTab) {
                                MainTab.HOME -> {
                                    Box(
                                        modifier = Modifier
                                            .width(420.dp)
                                            .fillMaxHeight()
                                    ) {
                                        CommunityHomeFeedPane(
                                            topics = displayedTopics,
                                            listState = feedListState,
                                            selectedTopicId = activeTopicSummary?.id,
                                            selectedCategoryId = selectedCategoryId,
                                            selectedTag = selectedTag,
                                            currentSort = currentSort,
                                            currentTopPeriod = currentTopPeriod,
                                            searchQuery = searchQuery,
                                            isLoading = isLoadingTopics,
                                            isLoadingMore = isLoadingMoreTopics,
                                            hasMoreTopics = hasMoreTopics,
                                            errorMessage = feedError,
                                            enableAnimations = enableAnimations,
                                            fontScale = fontScale,
                                            isAuthorized = isAuthorized,
                                            currentUser = currentUser,
                                            unreadNotificationCount = fullUserProfile?.unreadNotifications ?: 0,
                                            onRequestLogin = {
                                                activeWebLoginUrl = "https://linux.do/login"
                                            },
                                            onSearchChange = { searchQuery = it },
                                            onOpenGlobalSearch = { showGlobalSearchSheet = true },
                                            onOpenCreateTopic = {
                                                if (isAuthorized) showCreateTopicSheet = true
                                                else activeWebLoginUrl = "https://linux.do/login"
                                            },
                                            onOpenNotifications = {
                                                if (isAuthorized) showNotificationsSheet = true
                                                else activeWebLoginUrl = "https://linux.do/login"
                                            },
                                            onToggleCompactMode = {
                                                updateFontScalePref(if (fontScale < 0.96f) 1.0f else 0.92f)
                                            },
                                            onSelectCategory = { catId ->
                                                selectedCategoryId = catId
                                                loadTopics(currentSort, catId, selectedTag, currentTopPeriod)
                                            },
                                            onSelectTag = { tag ->
                                                selectedTag = tag
                                                loadTopics(currentSort, selectedCategoryId, tag, currentTopPeriod)
                                            },
                                            onSelectSort = { sort ->
                                                currentSort = sort
                                                loadTopics(sort, selectedCategoryId, selectedTag, currentTopPeriod)
                                            },
                                            onSelectTopPeriod = { period ->
                                                currentTopPeriod = period
                                                loadTopics(FeedSortFilter.TOP, selectedCategoryId, selectedTag, period)
                                            },
                                            onRefresh = { loadTopics(currentSort, selectedCategoryId, selectedTag, currentTopPeriod) },
                                            onLoadMore = { loadNextPageTopics() },
                                            onSelectTopic = { openTopic(it) },
                                            onAvatarClick = { currentTab = MainTab.PROFILE }
                                        )
                                    }

                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                    ) {
                                        CommunityTopicReaderPane(
                                            summary = activeTopicSummary,
                                            detail = activeTopicDetail,
                                            isLoading = isLoadingDetail,
                                            enableAnimations = enableAnimations,
                                            showBackButton = false,
                                            onBack = {},
                                            onRefresh = { activeTopicSummary?.let { openTopic(it) } },
                                            onDetailUpdated = { activeTopicDetail = it },
                                            onInspectBadge = { inspectedBadge = it },
                                            onInspectUser = { inspectedUsername = it },
                                            onSelectTag = { tag ->
                                                selectedTag = tag
                                                loadTopics(currentSort, selectedCategoryId, tag, currentTopPeriod)
                                            },
                                            onRequestLogin = { activeWebLoginUrl = "https://linux.do/login" }
                                        )
                                    }
                                }

                                MainTab.DISCOVER -> {
                                    CommunityDiscoverScreen(
                                        hotTopics = topics.filterNot { it.pinned }.take(5),
                                        onSelectCategory = { catId ->
                                            selectedCategoryId = catId
                                            currentTab = MainTab.HOME
                                            loadTopics(currentSort, catId, selectedTag, currentTopPeriod)
                                        },
                                        onSelectTopic = { topic ->
                                            currentTab = MainTab.HOME
                                            openTopic(topic)
                                        },
                                        onOpenGlobalSearch = { showGlobalSearchSheet = true }
                                    )
                                }

                                MainTab.PROFILE -> {
                                    CommunityProfileScreen(
                                        isDarkTheme = isDarkTheme,
                                        fontScale = fontScale,
                                        enableAnimations = enableAnimations,
                                        isAuthorized = isAuthorized,
                                        currentUser = currentUser,
                                        fullProfile = fullUserProfile,
                                        onRefreshProfile = { syncUserProfile() },
                                        onToggleDark = { updateThemePref(!isDarkTheme) },
                                        onChangeFontScale = { updateFontScalePref(it) },
                                        onToggleAnimations = { updateAnimationsPref(!enableAnimations) },
                                        onOpenNotifications = { showNotificationsSheet = true },
                                        onOpenActivities = { showActivitiesSheet = true },
                                        onOpenBookmarks = { showBookmarksSheet = true },
                                        onOpenPrivateMessages = {
                                            pmInitialRecipient = null
                                            showPrivateMessagesSheet = true
                                        },
                                        onOpenEditProfile = { showEditProfileSheet = true },
                                        onInspectBadge = { inspectedBadge = it },
                                        onOpenConnectSheet = { showConnectSheet = true },
                                        onOpenNetworkSheet = { showNetworkSheet = true },
                                        onOpenAuthSheet = { showAuthSheet = true },
                                        onPrimaryWebLogin = {
                                            activeWebLoginUrl = "https://linux.do/login"
                                        },
                                        onLogout = {
                                            UserApiKeyAuthManager.logout()
                                            fullUserProfile = null
                                            guestPreviewMode = false
                                        }
                                    )
                                }
                            }
                        }
                    } else {
                        // 移动端布局（沉浸式全屏阅读或底部 Linear 极简导航栏）
                        if (currentTab == MainTab.HOME && mobileShowingDetail && activeTopicSummary != null) {
                            CommunityTopicReaderPane(
                                summary = activeTopicSummary,
                                detail = activeTopicDetail,
                                isLoading = isLoadingDetail,
                                enableAnimations = enableAnimations,
                                showBackButton = true,
                                onBack = { mobileShowingDetail = false },
                                onRefresh = { activeTopicSummary?.let { openTopic(it) } },
                                onDetailUpdated = { activeTopicDetail = it },
                                onInspectBadge = { inspectedBadge = it },
                                onInspectUser = { inspectedUsername = it },
                                onSelectTag = { tag ->
                                    mobileShowingDetail = false
                                    selectedTag = tag
                                    loadTopics(currentSort, selectedCategoryId, tag, currentTopPeriod)
                                },
                                onRequestLogin = { activeWebLoginUrl = "https://linux.do/login" }
                            )
                        } else {
                            Column(modifier = Modifier.fillMaxSize()) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                ) {
                                    when (currentTab) {
                                        MainTab.HOME -> {
                                            CommunityHomeFeedPane(
                                                topics = displayedTopics,
                                                listState = feedListState,
                                                selectedTopicId = null,
                                                selectedCategoryId = selectedCategoryId,
                                                selectedTag = selectedTag,
                                                currentSort = currentSort,
                                                currentTopPeriod = currentTopPeriod,
                                                searchQuery = searchQuery,
                                                isLoading = isLoadingTopics,
                                                isLoadingMore = isLoadingMoreTopics,
                                                hasMoreTopics = hasMoreTopics,
                                                errorMessage = feedError,
                                                enableAnimations = enableAnimations,
                                                fontScale = fontScale,
                                                isAuthorized = isAuthorized,
                                                currentUser = currentUser,
                                                unreadNotificationCount = fullUserProfile?.unreadNotifications ?: 0,
                                                onRequestLogin = {
                                                    activeWebLoginUrl = "https://linux.do/login"
                                                },
                                                onSearchChange = { searchQuery = it },
                                                onOpenGlobalSearch = { showGlobalSearchSheet = true },
                                                onOpenCreateTopic = {
                                                    if (isAuthorized) showCreateTopicSheet = true
                                                    else activeWebLoginUrl = "https://linux.do/login"
                                                },
                                                onOpenNotifications = {
                                                    if (isAuthorized) showNotificationsSheet = true
                                                    else activeWebLoginUrl = "https://linux.do/login"
                                                },
                                                onToggleCompactMode = {
                                                    updateFontScalePref(if (fontScale < 0.96f) 1.0f else 0.92f)
                                                },
                                                onSelectCategory = { catId ->
                                                    selectedCategoryId = catId
                                                    loadTopics(currentSort, catId, selectedTag, currentTopPeriod)
                                                },
                                                onSelectTag = { tag ->
                                                    selectedTag = tag
                                                    loadTopics(currentSort, selectedCategoryId, tag, currentTopPeriod)
                                                },
                                                onSelectSort = { sort ->
                                                    currentSort = sort
                                                    loadTopics(sort, selectedCategoryId, selectedTag, currentTopPeriod)
                                                },
                                                onSelectTopPeriod = { period ->
                                                    currentTopPeriod = period
                                                    loadTopics(FeedSortFilter.TOP, selectedCategoryId, selectedTag, period)
                                                },
                                                onRefresh = { loadTopics(currentSort, selectedCategoryId, selectedTag, currentTopPeriod) },
                                                onLoadMore = { loadNextPageTopics() },
                                                onSelectTopic = { openTopic(it) },
                                                onAvatarClick = { currentTab = MainTab.PROFILE }
                                            )
                                        }

                                        MainTab.DISCOVER -> {
                                            CommunityDiscoverScreen(
                                                hotTopics = topics.filterNot { it.pinned }.take(5),
                                                onSelectCategory = { catId ->
                                                    selectedCategoryId = catId
                                                    currentTab = MainTab.HOME
                                                    loadTopics(currentSort, catId, selectedTag, currentTopPeriod)
                                                },
                                                onSelectTopic = { topic ->
                                                    currentTab = MainTab.HOME
                                                    openTopic(topic)
                                                },
                                                onOpenGlobalSearch = { showGlobalSearchSheet = true }
                                            )
                                        }

                                        MainTab.PROFILE -> {
                                            CommunityProfileScreen(
                                                isDarkTheme = isDarkTheme,
                                                fontScale = fontScale,
                                                enableAnimations = enableAnimations,
                                                isAuthorized = isAuthorized,
                                                currentUser = currentUser,
                                                fullProfile = fullUserProfile,
                                                onRefreshProfile = { syncUserProfile() },
                                                onToggleDark = { updateThemePref(!isDarkTheme) },
                                                onChangeFontScale = { updateFontScalePref(it) },
                                                onToggleAnimations = { updateAnimationsPref(!enableAnimations) },
                                                onOpenNotifications = { showNotificationsSheet = true },
                                                onOpenActivities = { showActivitiesSheet = true },
                                                onOpenBookmarks = { showBookmarksSheet = true },
                                                onOpenPrivateMessages = {
                                                    pmInitialRecipient = null
                                                    showPrivateMessagesSheet = true
                                                },
                                                onOpenEditProfile = { showEditProfileSheet = true },
                                                onInspectBadge = { inspectedBadge = it },
                                                onOpenConnectSheet = { showConnectSheet = true },
                                                onOpenNetworkSheet = { showNetworkSheet = true },
                                                onOpenAuthSheet = { showAuthSheet = true },
                                                onPrimaryWebLogin = {
                                                    activeWebLoginUrl = "https://linux.do/login"
                                                },
                                                onLogout = {
                                                    UserApiKeyAuthManager.logout()
                                                    fullUserProfile = null
                                                    guestPreviewMode = false
                                                }
                                            )
                                        }
                                    }
                                }

                                GoogleM3NavigationBar(
                                    currentTab = currentTab,
                                    onSelectTab = { currentTab = it }
                                )
                            }
                        }
                    }

                    // 内置全屏安全 WebView 登录弹层
                    activeWebLoginUrl?.let { loginUrl ->
                        val renderer = PlatformWebLoginHost.webLoginRenderer
                        if (renderer != null) {
                            renderer(
                                loginUrl,
                                { activeWebLoginUrl = null },
                                {
                                    activeWebLoginUrl = null
                                    authVersion++
                                    loadTopics(currentSort, selectedCategoryId)
                                    syncUserProfile()
                                }
                            )
                        }
                    }
                }

                inspectedBadge?.let { badge ->
                    BadgeDetailBottomSheet(
                        badge = badge,
                        onDismiss = { inspectedBadge = null }
                    )
                }

                inspectedUsername?.let { uname ->
                    PublicUserCardSheet(
                        username = uname,
                        onDismiss = { inspectedUsername = null },
                        onSendPrivateMessage = { targetUser ->
                            inspectedUsername = null
                            pmInitialRecipient = targetUser
                            showPrivateMessagesSheet = true
                        },
                        onSelectTopicId = { tid, title ->
                            inspectedUsername = null
                            openTopicById(tid, title)
                        }
                    )
                }

                if (showGlobalSearchSheet) {
                    GlobalSearchModalSheet(
                        initialQuery = searchQuery,
                        onDismiss = { showGlobalSearchSheet = false },
                        onSelectResult = { tid, title ->
                            showGlobalSearchSheet = false
                            openTopicById(tid, title)
                        }
                    )
                }

                if (showCreateTopicSheet) {
                    CreateTopicModalSheet(
                        initialCategoryId = selectedCategoryId ?: 4,
                        onDismiss = { showCreateTopicSheet = false },
                        onTopicCreated = { newTopicId, title ->
                            showCreateTopicSheet = false
                            loadTopics(currentSort, selectedCategoryId)
                            openTopicById(newTopicId, title)
                        }
                    )
                }

                if (showNotificationsSheet) {
                    UserNotificationsSheet(
                        onDismiss = {
                            showNotificationsSheet = false
                            syncUserProfile()
                        },
                        onSelectNotification = { tid, title ->
                            showNotificationsSheet = false
                            if (tid > 0) openTopicById(tid, title)
                        }
                    )
                }

                if (showActivitiesSheet) {
                    val uname = fullUserProfile?.username ?: currentUser?.username.orEmpty()
                    UserActivitiesSheet(
                        username = uname,
                        onDismiss = { showActivitiesSheet = false },
                        onSelectActivity = { tid, title ->
                            showActivitiesSheet = false
                            openTopicById(tid, title)
                        }
                    )
                }

                if (showBookmarksSheet) {
                    val uname = fullUserProfile?.username ?: currentUser?.username.orEmpty()
                    UserBookmarksSheet(
                        username = uname,
                        onDismiss = { showBookmarksSheet = false },
                        onSelectBookmark = { tid, title ->
                            showBookmarksSheet = false
                            openTopicById(tid, title)
                        }
                    )
                }

                if (showPrivateMessagesSheet) {
                    val uname = fullUserProfile?.username ?: currentUser?.username.orEmpty()
                    UserPrivateMessagesSheet(
                        username = uname,
                        initialRecipient = pmInitialRecipient,
                        onDismiss = {
                            showPrivateMessagesSheet = false
                            pmInitialRecipient = null
                        },
                        onSelectMessageTopic = { topic ->
                            showPrivateMessagesSheet = false
                            openTopic(topic)
                        }
                    )
                }

                if (showEditProfileSheet) {
                    val uname = fullUserProfile?.username ?: currentUser?.username.orEmpty()
                    EditProfileModalSheet(
                        username = uname,
                        initialDisplayName = fullUserProfile?.displayName ?: currentUser?.displayName.orEmpty(),
                        initialBio = fullUserProfile?.bioExcerpt.orEmpty(),
                        initialTitle = fullUserProfile?.title ?: currentUser?.userTitle.orEmpty(),
                        onDismiss = { showEditProfileSheet = false },
                        onSaved = {
                            showEditProfileSheet = false
                            syncUserProfile()
                        }
                    )
                }

                if (showConnectSheet) {
                    ConnectTrustLevelSheet(
                        onDismiss = { showConnectSheet = false }
                    )
                }

                if (showNetworkSheet) {
                    NetworkDiagnosticsSheet(
                        onDismiss = { showNetworkSheet = false }
                    )
                }

                if (showAuthSheet) {
                    UserApiKeyAuthSheet(
                        isAuthorized = isAuthorized,
                        currentUser = currentUser,
                        onPrimaryWebLogin = {
                            showAuthSheet = false
                            activeWebLoginUrl = "https://linux.do/login"
                        },
                        onBrowserOneClickAuth = {
                            showAuthSheet = false
                            val url = UserApiKeyAuthManager.startBrowserOneClickAuth()
                            if (UserApiKeyAuthManager.openExternalUrlLauncher == null) {
                                activeWebLoginUrl = url
                            }
                        },
                        onLogout = {
                            UserApiKeyAuthManager.logout()
                            fullUserProfile = null
                            showAuthSheet = false
                            guestPreviewMode = false
                        },
                        onDismiss = { showAuthSheet = false }
                    )
                }
            }
        }
    }
}

/**
 * 桌面端 Google Material 3 Navigation Rail
 */
@Composable
private fun DesktopSideRail(
    currentTab: MainTab,
    isDarkTheme: Boolean,
    onSelectTab: (MainTab) -> Unit,
    onToggleTheme: () -> Unit
) {
    val palette = LocalPalette.current

    Column(
        modifier = Modifier
            .width(76.dp)
            .fillMaxHeight()
            .background(palette.navBarSurface)
            .padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(palette.cardSurface),
            contentAlignment = Alignment.Center
        ) {
            GeminiSparkleIcon(size = 20.dp)
        }

        Spacer(modifier = Modifier.height(28.dp))

        MainTab.entries.forEach { tab ->
            val selected = currentTab == tab
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onSelectTab(tab) }
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .width(56.dp)
                        .height(32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (selected) palette.tonalPill else Color.Transparent),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                        contentDescription = tab.title,
                        modifier = Modifier.size(21.dp),
                        tint = if (selected) palette.onTonalPill else palette.inkSecondary
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = tab.title,
                    fontSize = 11.5.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (selected) palette.inkPrimary else palette.inkSecondary
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(palette.cardSurface)
                .clickable { onToggleTheme() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                contentDescription = "深色模式",
                modifier = Modifier.size(18.dp),
                tint = palette.inkSecondary
            )
        }
    }
}

/**
 * Linear / Threads 极简黑白底部导航栏：
 * - 顶部 0.5dp 锋利微分割线 (`#E4E4E7` / `#27272A`)
 * - 选中态采用曜石黑胶囊 + 琥珀金微点指示
 */
@Composable
private fun GoogleM3NavigationBar(
    currentTab: MainTab,
    onSelectTab: (MainTab) -> Unit
) {
    val palette = LocalPalette.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.navBarSurface)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MainTab.entries.forEach { tab ->
                val selected = currentTab == tab
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onSelectTab(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(52.dp)
                            .height(28.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) palette.tonalPill else Color.Transparent),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                            contentDescription = tab.title,
                            modifier = Modifier.size(19.dp),
                            tint = if (selected) palette.onTonalPill else palette.inkSecondary
                        )
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = tab.title,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) palette.inkPrimary else palette.inkTertiary,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * Linear / Threads / X 极简黑白现代风首页：
 * 1. 顶部品牌与全局搜索栏（内嵌玻璃拟态立体微图标、搜索输入、曜石黑排序胶囊与头像）
 * 2. 0.5dp 锋利描边频道胶囊列（选中态曜石黑 `#09090B`）
 * 3. 琥珀金徽标置顶公告卡
 * 4. 0.5dp 微描边高对比纯白社区卡片
 */
@Composable
private fun CommunityHomeFeedPane(
    topics: List<TopicSummary>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    selectedTopicId: Int?,
    selectedCategoryId: Int?,
    selectedTag: String? = null,
    currentSort: FeedSortFilter,
    currentTopPeriod: TopPeriodFilter = TopPeriodFilter.DAILY,
    searchQuery: String,
    isLoading: Boolean,
    isLoadingMore: Boolean = false,
    hasMoreTopics: Boolean = true,
    errorMessage: String?,
    enableAnimations: Boolean,
    fontScale: Float = 1.0f,
    isAuthorized: Boolean,
    currentUser: AuthenticatedUserProfile?,
    unreadNotificationCount: Int = 0,
    onRequestLogin: () -> Unit,
    onSearchChange: (String) -> Unit,
    onOpenGlobalSearch: () -> Unit,
    onOpenCreateTopic: () -> Unit,
    onOpenNotifications: () -> Unit,
    onToggleCompactMode: () -> Unit = {},
    onSelectCategory: (Int?) -> Unit,
    onSelectTag: (String?) -> Unit = {},
    onSelectSort: (FeedSortFilter) -> Unit,
    onSelectTopPeriod: (TopPeriodFilter) -> Unit = {},
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onSelectTopic: (TopicSummary) -> Unit,
    onAvatarClick: () -> Unit
) {
    val palette = LocalPalette.current
    val isCompact = fontScale < 0.96f
    val pinnedTopics = remember(topics, selectedCategoryId, selectedTag, searchQuery) {
        if (selectedCategoryId == null && selectedTag == null && searchQuery.isBlank() && currentSort == FeedSortFilter.LATEST) {
            topics.filter { it.pinned }
        } else {
            emptyList()
        }
    }
    val regularTopics = remember(topics, pinnedTopics) {
        if (pinnedTopics.isEmpty()) topics else topics.filterNot { it.pinned }
    }

    // 滚动触底自动加载下一页（使用 derivedStateOf 避免滚动每帧重组与死循环）
    val shouldLoadMore by remember(isLoading, isLoadingMore, hasMoreTopics, searchQuery) {
        derivedStateOf {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            val lastIdx = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            total > 4 &&
                lastIdx >= total - 3 &&
                !isLoading &&
                !isLoadingMore &&
                hasMoreTopics &&
                searchQuery.isBlank()
        }
    }
    LaunchedEffect(shouldLoadMore, regularTopics.size) {
        if (shouldLoadMore) {
            onLoadMore()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.canvas)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 1. Linear / Threads 风格极简顶栏：品牌徽章 + 搜索输入/全站搜索 + 通知铃铛 + 发新帖 + 头像
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp)
                    .height(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.cardSurface)
                    .border(0.5.dp, palette.hairline, RoundedCornerShape(14.dp))
                    .padding(start = 10.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LinDoGlassAppIcon(tileSize = 26.dp)

                Spacer(modifier = Modifier.width(8.dp))

                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "全站搜索",
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { onOpenGlobalSearch() },
                    tint = palette.inkSecondary
                )

                Spacer(modifier = Modifier.width(6.dp))

                Box(modifier = Modifier.weight(1f)) {
                    if (searchQuery.isEmpty()) {
                        Text(
                            text = "搜索话题、帖子或用户...",
                            fontSize = 13.sp,
                            color = palette.inkTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = onSearchChange,
                        singleLine = true,
                        textStyle = TextStyle(
                            fontSize = 13.sp,
                            color = palette.inkPrimary
                        ),
                        cursorBrush = SolidColor(palette.accent),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (searchQuery.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(palette.tonalPill)
                            .clickable { onOpenGlobalSearch() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "全站搜",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.onTonalPill
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .clickable { onSearchChange("") },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "清除",
                            modifier = Modifier.size(14.dp),
                            tint = palette.inkSecondary
                        )
                    }
                } else {
                    // 全站搜索快捷入口胶囊
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(palette.subtleSurface)
                            .clickable { onOpenGlobalSearch() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "全站搜",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = palette.inkSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                // 通知铃铛快捷入口（带未读琥珀金角标）
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(palette.subtleSurface)
                        .clickable { onOpenNotifications() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Notifications,
                        contentDescription = "通知",
                        modifier = Modifier.size(17.dp),
                        tint = palette.inkPrimary
                    )
                    if (unreadNotificationCount > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 4.dp, end = 4.dp)
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(palette.brandGold)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                // 顶部快捷发帖按钮
                Box(
                    modifier = Modifier
                        .height(30.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(palette.tonalPill)
                        .clickable { onOpenCreateTopic() }
                        .padding(horizontal = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "发帖",
                            modifier = Modifier.size(14.dp),
                            tint = palette.brandGold
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = "发帖",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.onTonalPill,
                            maxLines = 1
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                GoogleAccountRingAvatar(
                    letter = currentUser?.displayName?.takeIf { it.isNotBlank() }
                        ?: currentUser?.username
                        ?: "L",
                    avatarUrl = currentUser?.avatarTemplate,
                    avatarSize = 31.dp,
                    strokeWidth = 2.dp,
                    onClick = onAvatarClick
                )
            }

            // 游客模式提醒条（曜石黑 + 琥珀金单行条）
            if (!isAuthorized) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(palette.tonalPill)
                        .clickable(onClick = onRequestLogin)
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.5.dp)
                            .clip(CircleShape)
                            .background(palette.brandGold)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "游客预览模式 · 浏览不计入 Connect 连登",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = palette.onTonalPill,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "立即登录",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.brandGold,
                        maxLines = 1
                    )
                }
            }

            // 2. 官方 5 大流切换（最新/未读/新帖/热门/已读）+ 紧凑模式快切 + 板块快筛栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 官方 5 大流分段器：最新 / 未读 / 新帖 / 热门 / 已读
                Row(
                    modifier = Modifier
                        .height(31.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(palette.cardSurface)
                        .border(0.5.dp, palette.hairline, RoundedCornerShape(10.dp))
                        .padding(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FeedSortFilter.entries.forEach { sort ->
                        val selected = currentSort == sort
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) palette.tonalPill else Color.Transparent)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { onSelectSort(sort) }
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = sort.label,
                                fontSize = 11.8.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                color = if (selected) palette.onTonalPill else palette.inkSecondary,
                                maxLines = 1
                            )
                        }
                    }
                }

                // 一键切换紧凑/标准视图密度胶囊
                GoogleM3FilterPill(
                    label = if (isCompact) "紧凑 ✓" else "紧凑",
                    dotColor = if (isCompact) palette.brandGold else null,
                    selected = isCompact,
                    onClick = onToggleCompactMode
                )

                GoogleM3FilterPill(
                    label = "全部板块",
                    dotColor = null,
                    selected = selectedCategoryId == null,
                    onClick = { onSelectCategory(null) }
                )

                LinuxDoRepository.categories.forEach { category ->
                    val catColor = remember(category.colorHex) {
                        LinuxDoSiteRules.parseHexColor(category.colorHex)
                    }
                    GoogleM3FilterPill(
                        label = category.name,
                        dotColor = catColor,
                        selected = selectedCategoryId == category.id,
                        onClick = {
                            onSelectCategory(
                                if (selectedCategoryId == category.id) null else category.id
                            )
                        }
                    )
                }
            }

            // 官方 Top 热门榜时间维度切换器 + 当前激活的 #Tag 筛选条
            if (currentSort == FeedSortFilter.TOP || selectedTag != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 12.dp, end = 12.dp, bottom = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (selectedTag != null) {
                        Row(
                            modifier = Modifier
                                .height(26.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(palette.tonalPill)
                                .clickable { onSelectTag(null) }
                                .padding(horizontal = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "#$selectedTag ×",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.brandGold
                            )
                        }
                    }

                    if (currentSort == FeedSortFilter.TOP) {
                        TopPeriodFilter.entries.forEach { period ->
                            val selected = currentTopPeriod == period
                            Box(
                                modifier = Modifier
                                    .height(26.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) palette.tonalPill else palette.cardSurface)
                                    .border(0.5.dp, if (selected) palette.tonalPill else palette.hairline, RoundedCornerShape(8.dp))
                                    .clickable { onSelectTopPeriod(period) }
                                    .padding(horizontal = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = period.label,
                                    fontSize = 11.5.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                    color = if (selected) palette.onTonalPill else palette.inkSecondary
                                )
                            }
                        }
                    }
                }
            }

            if (isLoading && topics.isNotEmpty()) {
                LinearLuminanceBar(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                )
            }

            // 3. 话题流（Linear 骨架屏 + 0.5dp 描边高对比卡片 + 紧凑模式高密度支持 + 优雅呼吸分页底栏）
            if (isLoading && topics.isEmpty()) {
                LinearShimmerTopicSkeletonList(isCompact = isCompact)
            } else if (topics.isEmpty() && !isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = errorMessage ?: "暂无相关话题",
                            fontSize = 14.sp,
                            color = palette.inkSecondary
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(palette.tonalPill)
                                .clickable { onRefresh() }
                                .padding(horizontal = 18.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = "重新加载",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.onTonalPill
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        top = 4.dp,
                        bottom = 24.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(if (isCompact) 5.dp else 8.dp)
                ) {
                    // 置顶公告容器（Linear 微描边 + 琥珀金置顶徽标）
                    if (pinnedTopics.isNotEmpty()) {
                        item(key = "pinned_banner_group") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(if (isCompact) 12.dp else 14.dp))
                                    .background(palette.cardSurface)
                                    .border(0.5.dp, palette.hairline, RoundedCornerShape(if (isCompact) 12.dp else 14.dp))
                                    .padding(horizontal = 12.dp, vertical = if (isCompact) 7.dp else 9.dp),
                                verticalArrangement = Arrangement.spacedBy(if (isCompact) 4.dp else 6.dp)
                            ) {
                                pinnedTopics.take(2).forEach { pinned ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { onSelectTopic(pinned) }
                                            .padding(vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(5.dp))
                                                .background(palette.brandGold.copy(alpha = 0.2f))
                                                .padding(horizontal = 6.dp, vertical = 1.5.dp)
                                        ) {
                                            Text(
                                                text = "置顶",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = palette.onSuccessContainer
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = pinned.title,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = palette.inkPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Icon(
                                            imageVector = Icons.Default.ChevronRight,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                            tint = palette.inkTertiary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    itemsIndexed(regularTopics, key = { _, item -> item.id }) { _, topic ->
                        CommunityTopicItem(
                            topic = topic,
                            isSelected = topic.id == selectedTopicId,
                            isCompact = isCompact,
                            enableAnimations = enableAnimations,
                            onSelectTag = { tag -> onSelectTag(tag) },
                            onClick = { onSelectTopic(topic) }
                        )
                    }

                    // 底部 Linear 三点波浪呼吸脉冲分页加载栏
                    item(key = "load_more_footer") {
                        LinearPulseLoadingFooter(
                            isLoadingMore = isLoadingMore,
                            hasMore = hasMoreTopics,
                            loadedCount = regularTopics.size,
                            onLoadMore = onLoadMore
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GoogleM3FilterPill(
    label: String,
    dotColor: Color?,
    selected: Boolean,
    onClick: () -> Unit
) {
    val palette = LocalPalette.current

    Row(
        modifier = Modifier
            .height(31.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) palette.tonalPill else palette.cardSurface)
            .border(
                width = 0.5.dp,
                color = if (selected) palette.tonalPill else palette.hairline,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (dotColor != null) {
            Box(
                modifier = Modifier
                    .size(6.5.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = label,
            fontSize = 12.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) palette.onTonalPill else palette.inkSecondary,
            maxLines = 1
        )
    }
}

/**
 * Linear / Threads 风格极简高对比社区卡片（原生支持标准/紧凑双密度视图与标签快筛）：
 * - 标准模式：宽裕呼吸留白 + 摘要预览 + 3 人头像叠放
 * - 紧凑模式：高信息密度（单屏容纳提升 45%），收起摘要冗余，行距与内边距精密收敛
 */
@Composable
private fun CommunityTopicItem(
    topic: TopicSummary,
    isSelected: Boolean,
    isCompact: Boolean = false,
    enableAnimations: Boolean,
    onSelectTag: (String) -> Unit = {},
    onClick: () -> Unit
) {
    val palette = LocalPalette.current
    val op = topic.originalPoster
    val authorDisplay = op.name?.takeIf { it.isNotBlank() } ?: op.username
    val catColor = remember(topic.categoryColorHex) {
        LinuxDoSiteRules.parseHexColor(topic.categoryColorHex)
    }
    val borderColor = if (isSelected) palette.inkPrimary else palette.hairline
    val cornerRadius = if (isCompact) 12.dp else 16.dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(cornerRadius))
            .background(palette.cardSurface)
            .border(if (isSelected) 1.dp else 0.5.dp, borderColor, RoundedCornerShape(cornerRadius))
            .clickable(onClick = onClick)
            .padding(
                horizontal = if (isCompact) 12.dp else 15.dp,
                vertical = if (isCompact) 9.dp else 14.dp
            )
    ) {
        // 第一行：作者信息 + 右侧板块柔色胶囊
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SmartAvatar(
                username = op.username,
                avatarTemplate = op.avatarTemplate,
                animatedAvatar = op.animatedAvatar,
                primaryGroupName = op.primaryGroupName,
                flair = null,
                size = if (isCompact) 20.dp else 24.dp,
                enableAnimations = enableAnimations
            )

            Spacer(modifier = Modifier.width(if (isCompact) 6.dp else 8.dp))

            Text(
                text = authorDisplay,
                fontSize = if (isCompact) 12.5.sp else 13.5.sp,
                fontWeight = FontWeight.Medium,
                color = palette.inkPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (topic.lastPostedAt.isNotBlank()) {
                Text(
                    text = " · ${topic.lastPostedAt}",
                    fontSize = if (isCompact) 11.5.sp else 12.5.sp,
                    color = palette.inkTertiary,
                    maxLines = 1
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(catColor.copy(alpha = 0.11f))
                    .padding(horizontal = if (isCompact) 6.5.dp else 8.dp, vertical = if (isCompact) 2.dp else 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(catColor)
                )
                Spacer(modifier = Modifier.width(4.5.dp))
                Text(
                    text = topic.categoryName,
                    fontSize = if (isCompact) 10.5.sp else 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = catColor
                )
            }
        }

        Spacer(modifier = Modifier.height(if (isCompact) 5.dp else 9.dp))

        // 第二行：标题
        Text(
            text = topic.title,
            fontSize = if (isCompact) 14.8.sp else 16.5.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = if (isCompact) 20.5.sp else 23.5.sp,
            letterSpacing = (-0.2).sp,
            color = palette.inkPrimary,
            maxLines = if (isCompact) 1 else 2,
            overflow = TextOverflow.Ellipsis
        )

        // 标准模式下若存在摘要则展示单行预览；紧凑模式下收起摘要以最大化首屏密度
        val cleanExcerpt = if (!isCompact) {
            topic.excerpt?.trim()?.takeIf { it.isNotEmpty() && it != topic.title }
        } else null
        if (cleanExcerpt != null) {
            Spacer(modifier = Modifier.height(5.dp))
            Text(
                text = cleanExcerpt,
                fontSize = 13.5.sp,
                lineHeight = 19.5.sp,
                color = palette.inkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.height(if (isCompact) 6.dp else 11.dp))

        // 第三行：可点击 #Tag 标签胶囊 + 参与者微缩头像叠放 + 评论/阅读数
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (topic.tags.isNotEmpty()) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    topic.tags.take(if (isCompact) 2 else 3).forEach { tag ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(palette.canvas)
                                .clickable { onSelectTag(tag) }
                                .padding(horizontal = 6.5.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "#$tag",
                                fontSize = if (isCompact) 10.8.sp else 11.5.sp,
                                color = palette.inkSecondary,
                                maxLines = 1
                            )
                        }
                    }
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }

            // 参与者微缩头像叠放（带纯白描边环）
            val participants = topic.participantAvatars.take(if (isCompact) 2 else 3)
            if (participants.size > 1) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(end = 6.dp)
                ) {
                    participants.forEachIndexed { idx, user ->
                        Box(
                            modifier = Modifier
                                .offset(x = (-4 * idx).dp)
                                .clip(CircleShape)
                                .background(palette.cardSurface)
                                .padding(1.2.dp)
                        ) {
                            SmartAvatar(
                                username = user.username,
                                avatarTemplate = user.avatarTemplate,
                                animatedAvatar = null,
                                size = if (isCompact) 15.dp else 17.dp,
                                enableAnimations = false
                            )
                        }
                    }
                }
            }

            val replies = maxOf(0, topic.postsCount - 1).coerceAtLeast(topic.replyCount)
            val metricsText = buildString {
                if (topic.hasAcceptedAnswer) append("已解决 · ")
                append("$replies 回复")
                if (topic.views > 0) {
                    append(" · ${formatCompactNumber(topic.views)} 阅读")
                }
            }

            Text(
                text = metricsText,
                fontSize = if (isCompact) 11.2.sp else 12.sp,
                fontWeight = FontWeight.Medium,
                color = palette.inkTertiary
            )
        }
    }
}

private fun formatCompactNumber(value: Int): String {
    return if (value >= 1000) {
        val k = value / 100
        "${k / 10}.${k % 10}k"
    } else {
        value.toString()
    }
}

/**
 * Gemini 风格帖子详情阅读器：
 * - 顶栏：Tonal 圆形按钮 + 楼主身份胶囊
 * - 卡片式阅读流：主帖与回复均位于柔和的 `#FFFFFF` 圆角卡片中，告别杂乱分割线
 * - 底栏：Gemini 标志性悬浮胶囊输入栏（Floating Prompt Pill）
 */
@Composable
private fun CommunityTopicReaderPane(
    summary: TopicSummary?,
    detail: TopicDetailModel?,
    isLoading: Boolean,
    enableAnimations: Boolean,
    showBackButton: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onDetailUpdated: (TopicDetailModel) -> Unit,
    onInspectBadge: (GrantedBadge) -> Unit,
    onInspectUser: (String) -> Unit,
    onSelectTag: (String) -> Unit = {},
    onRequestLogin: () -> Unit
) {
    val palette = LocalPalette.current
    val fontScale = LocalFontScale.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    if (summary == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.canvas),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                GeminiSparkleIcon(size = 28.dp)
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "选择左侧话题开始阅读",
                    fontSize = 14.5.sp,
                    color = palette.inkSecondary
                )
            }
        }
        return
    }

    var isBookmarked by remember(summary.id, detail?.bookmarked) {
        mutableStateOf(detail?.bookmarked ?: false)
    }
    var onlyOpFilter by remember(summary.id) { mutableStateOf(false) }
    var notificationLevel by remember(summary.id) { mutableStateOf(1) } // 1=常规, 2=跟踪, 3=关注, 0=免扰
    var isLoadingMorePosts by remember(summary.id) { mutableStateOf(false) }
    var copiedShareLink by remember(summary.id) { mutableStateOf(false) }
    var showFloorJumpBar by remember(summary.id) { mutableStateOf(false) }

    // 回复编辑器状态
    var showReplySheet by remember(summary.id) { mutableStateOf(false) }
    var replyTargetPostNumber by remember(summary.id) { mutableStateOf<Int?>(null) }
    var replyTargetUsername by remember(summary.id) { mutableStateOf<String?>(null) }
    var replyInitialQuote by remember(summary.id) { mutableStateOf("") }

    // 编辑本人已发楼层状态
    var editingPost by remember(summary.id) { mutableStateOf<PostItemModel?>(null) }
    var editPostRawText by remember(summary.id) { mutableStateOf("") }
    var isSavingPostEdit by remember(summary.id) { mutableStateOf(false) }

    val catColor = remember(summary.categoryColorHex) {
        LinuxDoSiteRules.parseHexColor(summary.categoryColorHex)
    }
    val op = summary.originalPoster
    val opName = op.name?.takeIf { it.isNotBlank() } ?: op.username

    val posts = detail?.posts.orEmpty()
    val streamIds = detail?.streamIds.orEmpty()
    val loadedIds = remember(posts) { posts.map { it.id }.toSet() }
    val remainingPostIds = remember(streamIds, loadedIds) {
        streamIds.filterNot { it in loadedIds }
    }
    val totalFloorCount = maxOf(summary.postsCount, streamIds.size, posts.size)

    val readerListState = rememberLazyListState()

    fun loadMoreFloorBatch(batchSize: Int = 20) {
        val currentDetail = detail ?: return
        if (isLoadingMorePosts || remainingPostIds.isEmpty()) return
        scope.launch {
            isLoadingMorePosts = true
            val batchIds = remainingPostIds.take(batchSize)
            val morePosts = LinuxDoRepository.fetchMoreTopicPosts(summary.id, batchIds)
            if (morePosts.isNotEmpty()) {
                val merged = (currentDetail.posts + morePosts).distinctBy { it.id }
                onDetailUpdated(currentDetail.copy(posts = merged))
            }
            isLoadingMorePosts = false
        }
    }

    // 当滚动接近当前已加载楼层末尾时，自动拉取下一批 20 条评论楼层（derivedStateOf 防滚动每帧重组）
    val lastVisibleFloorIdx by remember {
        derivedStateOf {
            readerListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        }
    }
    LaunchedEffect(lastVisibleFloorIdx, posts.size, remainingPostIds.size, isLoadingMorePosts) {
        if (posts.isNotEmpty() && remainingPostIds.isNotEmpty() && !isLoadingMorePosts && lastVisibleFloorIdx >= posts.size - 2) {
            loadMoreFloorBatch(20)
        }
    }

    // 后台静默同步阅读进度与在岗状态（对齐 Discourse ScreenTrack）
    LaunchedEffect(summary.id, posts.size) {
        ScreenTrackEngine.onTopicEntered(
            topicId = summary.id,
            initialVisiblePosts = posts.take(2).map { it.postNumber }.toSet().ifEmpty { setOf(1) }
        )
        while (true) {
            delay(1000L)
            val visiblePostNums = readerListState.layoutInfo.visibleItemsInfo
                .mapNotNull { itemInfo ->
                    val postIdx = itemInfo.index - 1
                    posts.getOrNull(postIdx)?.postNumber
                }
                .toSet()
            if (visiblePostNums.isNotEmpty()) {
                ScreenTrackEngine.updateVisiblePosts(visiblePostNums)
            }
            val batch = ScreenTrackEngine.tick()
            if (batch != null) {
                val status = LinuxDoRepository.sendTopicTimings(batch)
                ScreenTrackEngine.onBatchSendResult(batch, status)
            }
        }
    }

    DisposableEffect(summary.id) {
        onDispose {
            val finalBatch = ScreenTrackEngine.flushCurrentTopic()
            if (finalBatch != null) {
                scope.launch {
                    val status = LinuxDoRepository.sendTopicTimings(finalBatch)
                    ScreenTrackEngine.onBatchSendResult(finalBatch, status)
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.canvas)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        // 1. Linear 风格紧凑快捷操作顶栏（返回 + 楼主名片入口 + 只看楼主 + 订阅 + 书签 + 复制链接 + 刷新）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showBackButton) {
                TonalCircleIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = palette.inkPrimary,
                    onClick = onBack
                )
                Spacer(modifier = Modifier.width(8.dp))
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onInspectUser(op.username) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SmartAvatar(
                    username = op.username,
                    avatarTemplate = op.avatarTemplate,
                    animatedAvatar = op.animatedAvatar,
                    primaryGroupName = op.primaryGroupName,
                    size = 26.dp,
                    enableAnimations = enableAnimations
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = opName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.inkPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(catColor.copy(alpha = 0.12f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = summary.categoryName,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = catColor,
                        maxLines = 1
                    )
                }
            }

            // 快捷操作 1：只看楼主
            Box(
                modifier = Modifier
                    .height(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (onlyOpFilter) palette.tonalPill else palette.subtleSurface)
                    .clickable {
                        val nextState = !onlyOpFilter
                        onlyOpFilter = nextState
                        scope.launch {
                            val filtered = LinuxDoRepository.fetchTopicDetail(
                                topicId = summary.id,
                                summaryFallback = summary,
                                trackVisit = false,
                                filterUsername = if (nextState) op.username else null
                            )
                            onDetailUpdated(filtered)
                        }
                    }
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (onlyOpFilter) "全部楼层" else "只看楼主",
                    fontSize = 11.2.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (onlyOpFilter) palette.onTonalPill else palette.inkSecondary,
                    maxLines = 1
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            // 快捷操作 2：话题订阅状态切换 (常规 -> 关注 -> 免扰)
            Box(
                modifier = Modifier
                    .height(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (notificationLevel == 3) palette.tonalPill else palette.subtleSurface)
                    .clickable {
                        val nextLevel = when (notificationLevel) {
                            1 -> 3 // 关注
                            3 -> 0 // 免扰
                            else -> 1 // 常规
                        }
                        notificationLevel = nextLevel
                        scope.launch {
                            LinuxDoRepository.setTopicNotificationLevel(summary.id, nextLevel)
                        }
                    }
                    .padding(horizontal = 7.5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when (notificationLevel) {
                        3 -> "已关注"
                        0 -> "免扰"
                        else -> "订阅"
                    },
                    fontSize = 11.2.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (notificationLevel == 3) palette.brandGold else palette.inkSecondary,
                    maxLines = 1
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            // 快捷操作 3：收藏书签
            TonalCircleIconButton(
                icon = if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                contentDescription = "收藏",
                tint = if (isBookmarked) palette.brandGold else palette.inkSecondary,
                onClick = {
                    if (!UserApiKeyAuthManager.isAuthorized()) {
                        onRequestLogin()
                        return@TonalCircleIconButton
                    }
                    isBookmarked = !isBookmarked
                    val firstPostId = posts.firstOrNull()?.id ?: return@TonalCircleIconButton
                    scope.launch {
                        LinuxDoRepository.toggleTopicBookmark(firstPostId, summary.title)
                    }
                }
            )

            Spacer(modifier = Modifier.width(4.dp))

            // 快捷操作 4：一键复制官方帖子链接
            TonalCircleIconButton(
                icon = if (copiedShareLink) Icons.Default.Check else Icons.Default.Share,
                contentDescription = "复制链接",
                tint = if (copiedShareLink) palette.brandGold else palette.inkSecondary,
                onClick = {
                    clipboardManager.setText(AnnotatedString("https://linux.do/t/topic/${summary.id}"))
                    copiedShareLink = true
                }
            )

            Spacer(modifier = Modifier.width(4.dp))

            TonalCircleIconButton(
                icon = Icons.Default.Refresh,
                contentDescription = "刷新",
                tint = palette.inkSecondary,
                onClick = onRefresh
            )
        }

        if (isLoading || isLoadingMorePosts) {
            LinearLuminanceBar(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
            )
        }

        LazyColumn(
            state = readerListState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = 6.dp,
                bottom = 20.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 话题标题头部卡片
            item(key = "header_${summary.id}") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(palette.cardSurface)
                        .border(0.5.dp, palette.hairline, RoundedCornerShape(18.dp))
                        .padding(horizontal = 16.dp, vertical = 15.dp)
                ) {
                    Text(
                        text = detail?.title ?: summary.title,
                        fontSize = (19f * fontScale).sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = (27f * fontScale).sp,
                        letterSpacing = (-0.3).sp,
                        color = palette.inkPrimary
                    )

                    if (summary.tags.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(9.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            summary.tags.forEach { tag ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(palette.subtleSurface)
                                        .clickable { onSelectTag(tag) }
                                        .padding(horizontal = 7.dp, vertical = 2.5.dp)
                                ) {
                                    Text(
                                        text = "#$tag",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = palette.inkSecondary
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(9.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val replies = maxOf(0, totalFloorCount - 1)
                        Text(
                            text = "共 $totalFloorCount 楼 ($replies 条评论) · ${summary.views} 阅读 · ${detail?.likeCount ?: summary.likeCount} 赞",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = palette.inkTertiary,
                            modifier = Modifier.weight(1f)
                        )

                        if (posts.size > 3) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(palette.subtleSurface)
                                    .clickable {
                                        scope.launch {
                                            readerListState.animateScrollToItem(posts.size)
                                        }
                                    }
                                    .padding(horizontal = 7.dp, vertical = 2.5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = "直达末楼",
                                    modifier = Modifier.size(13.dp),
                                    tint = palette.inkSecondary
                                )
                                Text(
                                    text = "直达末楼",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = palette.inkSecondary
                                )
                            }
                        }
                    }
                }
            }

            // 楼层卡片列表（#1 楼主 + #2..#N 全部评论楼层）
            itemsIndexed(posts, key = { _, post -> post.id }) { _, post ->
                CommunityPostFloor(
                    post = post,
                    opUsername = op.username,
                    fontScale = fontScale,
                    enableAnimations = enableAnimations,
                    onInspectBadge = onInspectBadge,
                    onInspectUser = onInspectUser,
                    onJumpToFloorNumber = { targetPostNum ->
                        val targetIdx = posts.indexOfFirst { it.postNumber == targetPostNum }
                        if (targetIdx >= 0) {
                            scope.launch {
                                readerListState.animateScrollToItem(targetIdx + 1)
                            }
                        }
                    },
                    onEditPost = { targetPost ->
                        editingPost = targetPost
                        editPostRawText = targetPost.cookedHtml
                            .replace(Regex("<br\\s*/?>"), "\n")
                            .replace(Regex("</p>"), "\n\n")
                            .replace(Regex("<[^>]+>"), "")
                            .trim()
                    },
                    onDeletePost = { targetPost ->
                        scope.launch {
                            val ok = LinuxDoRepository.deletePost(targetPost.id)
                            if (ok && detail != null) {
                                onDetailUpdated(
                                    detail.copy(
                                        posts = detail.posts.filterNot { it.id == targetPost.id },
                                        postsCount = maxOf(1, detail.postsCount - 1)
                                    )
                                )
                            }
                        }
                    },
                    onReplyFloor = { targetPost ->
                        if (!UserApiKeyAuthManager.isAuthorized()) {
                            onRequestLogin()
                        } else {
                            replyTargetPostNumber = targetPost.postNumber
                            replyTargetUsername = targetPost.username
                            replyInitialQuote = ""
                            showReplySheet = true
                        }
                    },
                    onQuoteFloor = { targetPost ->
                        if (!UserApiKeyAuthManager.isAuthorized()) {
                            onRequestLogin()
                        } else {
                            val cleanSnippet = targetPost.cookedHtml
                                .replace(Regex("<[^>]+>"), "")
                                .trim()
                                .take(160)
                            replyTargetPostNumber = targetPost.postNumber
                            replyTargetUsername = targetPost.username
                            replyInitialQuote = "[quote=\"${targetPost.username}, post:${targetPost.postNumber}, topic:${summary.id}\"]\n$cleanSnippet\n[/quote]\n\n"
                            showReplySheet = true
                        }
                    },
                    onRequestLogin = onRequestLogin
                )
            }

            // 剩余未加载楼层的优雅呼吸脉冲加载条
            if (remainingPostIds.isNotEmpty()) {
                item(key = "load_more_posts_${remainingPostIds.size}") {
                    LinearPulseLoadingFooter(
                        isLoadingMore = isLoadingMorePosts,
                        hasMore = true,
                        loadedCount = posts.size,
                        loadingText = "正在同步后续楼层 (${posts.size}/$totalFloorCount)",
                        moreText = "加载后续评论 (剩余 ${remainingPostIds.size} 楼 · 已载入 ${posts.size}/$totalFloorCount)",
                        onLoadMore = { loadMoreFloorBatch(20) }
                    )
                }
            }
        }

        // 快捷楼层进度电梯条（点击底部楼层胶囊展开：首楼 / 25% / 50% / 75% / 末楼 / 加载全部未读楼层）
        AnimatedVisibility(visible = showFloorJumpBar && posts.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.cardSurface)
                    .border(0.5.dp, palette.hairline)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "快速跳转:",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.inkSecondary
                )
                val jumpTargets = listOf(
                    "#1 首楼" to 0,
                    "25%" to (posts.size / 4).coerceAtLeast(1),
                    "50%" to (posts.size / 2).coerceAtLeast(1),
                    "75%" to ((posts.size * 3) / 4).coerceAtLeast(1),
                    "#${posts.size} 已载末楼" to posts.size
                )
                jumpTargets.forEach { (label, idx) ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(palette.subtleSurface)
                            .clickable {
                                showFloorJumpBar = false
                                scope.launch {
                                    readerListState.animateScrollToItem(idx.coerceIn(0, posts.size))
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = label,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = palette.inkPrimary
                        )
                    }
                }
                if (remainingPostIds.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(palette.tonalPill)
                            .clickable(enabled = !isLoadingMorePosts) {
                                loadMoreFloorBatch(50)
                            }
                            .padding(horizontal = 9.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "+载入后续 ${minOf(50, remainingPostIds.size)} 楼",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.brandGold
                        )
                    }
                }
            }
        }

        // 底部快捷回复栏 + 楼层电梯导航
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.cardSurface)
                .border(0.5.dp, palette.hairline)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 快捷触发回复输入框
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.subtleSurface)
                        .border(0.5.dp, palette.hairline, RoundedCornerShape(12.dp))
                        .clickable {
                            if (!UserApiKeyAuthManager.isAuthorized()) {
                                onRequestLogin()
                            } else {
                                replyTargetPostNumber = null
                                replyTargetUsername = null
                                replyInitialQuote = ""
                                showReplySheet = true
                            }
                        }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GeminiSparkleIcon(size = 15.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (UserApiKeyAuthManager.isAuthorized()) {
                            "参与讨论，发表你的回复..."
                        } else {
                            "登录后参与讨论与回复..."
                        },
                        fontSize = 13.sp,
                        color = palette.inkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // 楼层进度与电梯导航快捷胶囊
                Row(
                    modifier = Modifier
                        .height(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (showFloorJumpBar) palette.tonalPill else palette.subtleSurface)
                        .clickable {
                            showFloorJumpBar = !showFloorJumpBar
                        }
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (lastVisibleFloorIdx > 2) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = "楼层电梯",
                        modifier = Modifier.size(15.dp),
                        tint = if (showFloorJumpBar) palette.brandGold else palette.inkSecondary
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = "${posts.size}/$totalFloorCount",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (showFloorJumpBar) palette.onTonalPill else palette.inkPrimary,
                        maxLines = 1
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // 发送按钮
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.tonalPill)
                        .clickable {
                            if (!UserApiKeyAuthManager.isAuthorized()) {
                                onRequestLogin()
                            } else {
                                replyTargetPostNumber = null
                                replyTargetUsername = null
                                replyInitialQuote = ""
                                showReplySheet = true
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "回复",
                        modifier = Modifier.size(16.dp),
                        tint = palette.brandGold
                    )
                }
            }
        }
    }

    // 编辑本人已发楼层弹窗
    editingPost?.let { target ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.52f))
                .clickable { if (!isSavingPostEdit) editingPost = null },
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(palette.cardSurface)
                    .border(0.5.dp, palette.hairline, RoundedCornerShape(18.dp))
                    .clickable(enabled = false) {}
                    .padding(16.dp)
            ) {
                Text(
                    text = "编辑第 #${target.postNumber} 楼内容",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.inkPrimary
                )
                Spacer(modifier = Modifier.height(10.dp))
                BasicTextField(
                    value = editPostRawText,
                    onValueChange = { editPostRawText = it },
                    textStyle = TextStyle(
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = palette.inkPrimary
                    ),
                    cursorBrush = SolidColor(palette.brandGold),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 260.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.subtleSurface)
                        .border(0.5.dp, palette.hairline, RoundedCornerShape(12.dp))
                        .padding(12.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(palette.subtleSurface)
                            .clickable(enabled = !isSavingPostEdit) { editingPost = null }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text("取消", fontSize = 13.sp, color = palette.inkSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(palette.tonalPill)
                            .clickable(enabled = !isSavingPostEdit && editPostRawText.trim().length >= 5) {
                                scope.launch {
                                    isSavingPostEdit = true
                                    val updatedPost = LinuxDoRepository.updatePost(
                                        postId = target.id,
                                        newRaw = editPostRawText.trim()
                                    )
                                    isSavingPostEdit = false
                                    if (updatedPost != null && detail != null) {
                                        val updatedList = detail.posts.map { p ->
                                            if (p.id == target.id) p.copy(cookedHtml = updatedPost.cookedHtml) else p
                                        }
                                        onDetailUpdated(detail.copy(posts = updatedList))
                                        editingPost = null
                                    }
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = if (isSavingPostEdit) "保存中..." else "保存修改",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.brandGold
                        )
                    }
                }
            }
        }
    }

    if (showReplySheet) {
        TopicReplyComposerSheet(
            topicId = summary.id,
            topicTitle = detail?.title ?: summary.title,
            replyToPostNumber = replyTargetPostNumber,
            replyToUsername = replyTargetUsername,
            initialRaw = replyInitialQuote,
            onDismiss = { showReplySheet = false },
            onPostCreated = { newPost ->
                showReplySheet = false
                val currentDetail = detail
                if (currentDetail != null) {
                    val updatedPosts = (currentDetail.posts + newPost).distinctBy { it.id }
                    onDetailUpdated(
                        currentDetail.copy(
                            postsCount = currentDetail.postsCount + 1,
                            posts = updatedPosts
                        )
                    )
                    scope.launch {
                        readerListState.animateScrollToItem(updatedPosts.size)
                    }
                } else {
                    onRefresh()
                }
            }
        )
    }
}

@Composable
private fun TonalCircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit
) {
    val palette = LocalPalette.current
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(palette.subtleSurface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(17.dp),
            tint = tint
        )
    }
}

/**
 * Linear / Threads 风格单层楼卡片：
 * - 点击层主头像/昵称查看公开个人名片与统计
 * - 显示真实信任等级徽标 (`Lv.x`)、楼主标识、自定义头衔、已获徽章、楼号 `#N`
 * - 点击 `回复 #X` 直接跳转到对应被回复楼层
 * - 代码块一键写入系统剪贴板
 * - 本人发布的楼层支持「编辑 / 删除」，底部提供真实点赞 + 多表情回应 + 引用 + 回复
 */
@Composable
private fun CommunityPostFloor(
    post: PostItemModel,
    opUsername: String,
    fontScale: Float,
    enableAnimations: Boolean,
    onInspectBadge: (GrantedBadge) -> Unit,
    onInspectUser: (String) -> Unit,
    onJumpToFloorNumber: (Int) -> Unit = {},
    onEditPost: (PostItemModel) -> Unit = {},
    onDeletePost: (PostItemModel) -> Unit = {},
    onReplyFloor: (PostItemModel) -> Unit,
    onQuoteFloor: (PostItemModel) -> Unit,
    onRequestLogin: () -> Unit
) {
    val palette = LocalPalette.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var liked by remember(post.id, post.likedByMe) { mutableStateOf(post.likedByMe) }
    var likeCount by remember(post.id, post.likeCount) { mutableStateOf(post.likeCount) }
    var selectedEmoji by remember(post.id) { mutableStateOf<String?>(null) }
    val isOp = post.isTopicOwner || post.username.equals(opUsername, ignoreCase = true)
    val isOwnPost = remember(post.username) {
        val me = UserApiKeyAuthManager.currentUser?.username
        !me.isNullOrBlank() && post.username.equals(me, ignoreCase = true)
    }

    val astNodes = remember(post.id, post.cookedHtml) {
        DiscourseAstParser.parseOrGetCached(post.id, post.cookedHtml)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(palette.cardSurface)
            .border(0.5.dp, palette.hairline, RoundedCornerShape(16.dp))
            .padding(horizontal = 15.dp, vertical = 14.dp)
    ) {
        // 1. 楼层头部：头像 + 昵称 + 楼主标签 + Lv 等级 + 头衔 + 徽章 + 楼号胶囊
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.clickable { onInspectUser(post.username) }) {
                SmartAvatar(
                    username = post.username,
                    avatarTemplate = post.avatarTemplate,
                    animatedAvatar = post.animatedAvatar,
                    primaryGroupName = post.primaryGroupName,
                    flair = post.flair,
                    size = 36.dp,
                    enableAnimations = enableAnimations
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onInspectUser(post.username) }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = post.displayName?.takeIf { it.isNotBlank() } ?: post.username,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.inkPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (isOp) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(palette.tonalPill)
                                .padding(horizontal = 5.5.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = "楼主",
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = palette.brandGold
                            )
                        }
                    }

                    // 层主真实信任等级徽标 (Lv.0 ~ Lv.4)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(
                                if (post.trustLevel >= 3) palette.brandGold.copy(alpha = 0.2f)
                                else palette.subtleSurface
                            )
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "Lv.${post.trustLevel}",
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (post.trustLevel >= 3) palette.onSuccessContainer else palette.inkSecondary
                        )
                    }

                    if (!post.userTitle.isNullOrBlank()) {
                        UserTitleTag(
                            title = post.userTitle,
                            enableAnimations = enableAnimations
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "@${post.username} · ${post.createdAtText}",
                        fontSize = 11.5.sp,
                        color = palette.inkTertiary
                    )

                    if (post.badgesGranted.isNotEmpty()) {
                        post.badgesGranted.take(4).forEach { badge ->
                            PostGrantedBadgeIcon(
                                badge = badge,
                                onClick = { onInspectBadge(badge) }
                            )
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.subtleSurface)
                    .padding(horizontal = 7.dp, vertical = 2.5.dp)
            ) {
                Text(
                    text = "#${post.postNumber}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.inkSecondary
                )
            }
        }

        // 被回复楼层提示（点击直接跳转到被回复的楼层）
        if (post.replyToPostNumber != null && post.replyToPostNumber > 0) {
            Spacer(modifier = Modifier.height(9.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(palette.subtleSurface)
                    .clickable { onJumpToFloorNumber(post.replyToPostNumber) }
                    .padding(horizontal = 9.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "↩ 回复 #${post.replyToPostNumber}" +
                        (post.replyToUsername?.let { " @$it" } ?: ""),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.inkSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 2. AST 正文块渲染（支持字号缩放、圆角配图与代码块一键复制）
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            astNodes.forEach { node ->
                when (node) {
                    is PostBlockNode.Heading -> {
                        Text(
                            text = node.text,
                            fontSize = ((if (node.level <= 2) 17f else 15.5f) * fontScale).sp,
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = (24f * fontScale).sp,
                            color = palette.inkPrimary
                        )
                    }

                    is PostBlockNode.Paragraph -> {
                        Text(
                            text = node.text,
                            fontSize = (15f * fontScale).sp,
                            lineHeight = (24f * fontScale).sp,
                            color = palette.inkPrimary
                        )
                    }

                    is PostBlockNode.QuoteBlock -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(palette.subtleSurface)
                                .padding(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(34.dp)
                                    .clip(RoundedCornerShape(1.5.dp))
                                    .background(palette.brandGold)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                if (!node.username.isNullOrBlank()) {
                                    Text(
                                        text = "@${node.username}",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = palette.inkPrimary
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                }
                                Text(
                                    text = node.quotedText,
                                    fontSize = (13.5f * fontScale).sp,
                                    lineHeight = (20.5f * fontScale).sp,
                                    color = palette.inkSecondary
                                )
                            }
                        }
                    }

                    is PostBlockNode.CodeBlock -> {
                        var copied by remember { mutableStateOf(false) }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(palette.canvas)
                                .border(0.5.dp, palette.hairline, RoundedCornerShape(12.dp))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(palette.subtleSurface)
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = node.language.ifBlank { "code" },
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Monospace,
                                    color = palette.inkSecondary,
                                    modifier = Modifier.weight(1f)
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(7.dp))
                                        .background(if (copied) palette.tonalPill else palette.cardSurface)
                                        .clickable {
                                            clipboardManager.setText(AnnotatedString(node.code))
                                            copied = true
                                        }
                                        .padding(horizontal = 8.dp, vertical = 2.5.dp)
                                ) {
                                    Text(
                                        text = if (copied) "已复制 ✓" else "复制代码",
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (copied) palette.brandGold else palette.inkPrimary
                                    )
                                }
                            }
                            Text(
                                text = node.code,
                                fontSize = (12.5f * fontScale).sp,
                                lineHeight = (19f * fontScale).sp,
                                fontFamily = FontFamily.Monospace,
                                color = palette.inkPrimary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(12.dp)
                            )
                        }
                    }

                    is PostBlockNode.BulletList -> {
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            node.items.forEachIndexed { idx, item ->
                                Row(verticalAlignment = Alignment.Top) {
                                    Text(
                                        text = if (node.ordered) "${idx + 1}." else "•",
                                        fontSize = (14.5f * fontScale).sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = palette.inkPrimary,
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                    Text(
                                        text = item,
                                        fontSize = (14.5f * fontScale).sp,
                                        lineHeight = (22.5f * fontScale).sp,
                                        color = palette.inkPrimary
                                    )
                                }
                            }
                        }
                    }

                    is PostBlockNode.DetailsSpoilerBlock -> {
                        var expanded by remember { mutableStateOf(false) }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(palette.subtleSurface)
                                .clickable { expanded = !expanded }
                                .padding(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = node.title,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = palette.inkPrimary,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = if (expanded) "收起" else "展开",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = palette.inkSecondary
                                )
                            }
                            AnimatedVisibility(visible = expanded && node.innerText.isNotBlank()) {
                                Column {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = node.innerText,
                                        fontSize = (13.5f * fontScale).sp,
                                        lineHeight = (20.5f * fontScale).sp,
                                        color = palette.inkSecondary
                                    )
                                }
                            }
                        }
                    }

                    is PostBlockNode.ImageGallery -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            node.urls.forEach { imageUrl ->
                                AsyncImage(
                                    model = imageUrl,
                                    contentDescription = "帖子配图",
                                    contentScale = ContentScale.FillWidth,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 110.dp, max = 380.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(palette.canvas)
                                )
                            }
                        }
                    }

                    PostBlockNode.Divider -> {
                        HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 3. 楼层底部快捷操作栏：真实点赞 + 多表情回应 + (本人可编辑/删除) + 引用本楼 + 回复本楼
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 主点赞按钮
            Row(
                modifier = Modifier
                    .height(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (liked) palette.tonalPill else palette.subtleSurface)
                    .clickable {
                        if (!UserApiKeyAuthManager.isAuthorized()) {
                            onRequestLogin()
                            return@clickable
                        }
                        val next = !liked
                        liked = next
                        likeCount = (likeCount + if (next) 1 else -1).coerceAtLeast(0)
                        scope.launch {
                            LinuxDoRepository.togglePostLike(post.id)
                        }
                    }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (liked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                    contentDescription = "点赞",
                    modifier = Modifier.size(13.dp),
                    tint = if (liked) palette.brandGold else palette.inkSecondary
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = if (likeCount > 0) "赞 $likeCount" else "赞",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (liked) palette.onTonalPill else palette.inkSecondary
                )
            }

            // 快捷表情回应胶囊 (👍 +1 / 🎉 confetti_ball / 🔥 heart)
            val quickEmojis = listOf("👍" to "+1", "🎉" to "confetti_ball", "🔥" to "fire")
            quickEmojis.forEach { (emoji, reactionId) ->
                val isSelected = selectedEmoji == reactionId
                Box(
                    modifier = Modifier
                        .height(30.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (isSelected) palette.tonalPill else palette.subtleSurface)
                        .clickable {
                            if (!UserApiKeyAuthManager.isAuthorized()) {
                                onRequestLogin()
                                return@clickable
                            }
                            selectedEmoji = if (isSelected) null else reactionId
                            scope.launch {
                                LinuxDoRepository.togglePostReaction(post.id, reactionId)
                            }
                        }
                        .padding(horizontal = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = emoji, fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            if (isOwnPost) {
                Box(
                    modifier = Modifier
                        .height(30.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(palette.subtleSurface)
                        .clickable { onEditPost(post) }
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "编辑",
                        fontSize = 11.2.sp,
                        fontWeight = FontWeight.Medium,
                        color = palette.inkSecondary
                    )
                }
                if (post.postNumber > 1) {
                    Box(
                        modifier = Modifier
                            .height(30.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(palette.subtleSurface)
                            .clickable { onDeletePost(post) }
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "删除",
                            fontSize = 11.2.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFEF4444)
                        )
                    }
                }
            }

            // 快捷操作：引用回复
            Row(
                modifier = Modifier
                    .height(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(palette.subtleSurface)
                    .clickable { onQuoteFloor(post) }
                    .padding(horizontal = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.FormatQuote,
                    contentDescription = "引用",
                    modifier = Modifier.size(13.dp),
                    tint = palette.inkSecondary
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = "引用",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.inkSecondary
                )
            }

            // 快捷操作：回复本楼
            Row(
                modifier = Modifier
                    .height(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(palette.subtleSurface)
                    .clickable { onReplyFloor(post) }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "回复",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.inkPrimary
                )
            }
        }
    }
}

/**
 * Google Gemini / Discover 风格「探索与频道」页：
 * - 顶部 Gemini 四芒星趋势热议卡片（带柔和彩色序号徽标与热度胶囊）
 * - 下方双列 Google Bento 圆角频道卡片（带柔和彩色圆角图标容器）
 */
@Composable
private fun CommunityDiscoverScreen(
    hotTopics: List<TopicSummary>,
    onSelectCategory: (Int) -> Unit,
    onSelectTopic: (TopicSummary) -> Unit,
    onOpenGlobalSearch: () -> Unit = {}
) {
    val palette = LocalPalette.current
    val categoryPairs = remember { LinuxDoRepository.categories.chunked(2) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.canvas)
            .windowInsetsPadding(WindowInsets.statusBars),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "探索与板块",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.4).sp,
                        color = palette.inkPrimary
                    )
                    Text(
                        text = "全站 11 大官方板块与实时热门趋势",
                        fontSize = 12.5.sp,
                        color = palette.inkSecondary
                    )
                }

                Row(
                    modifier = Modifier
                        .height(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(palette.tonalPill)
                        .clickable { onOpenGlobalSearch() }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "全站搜索",
                        modifier = Modifier.size(15.dp),
                        tint = palette.brandGold
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "全站搜索",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.onTonalPill
                    )
                }
            }
        }

        // 1. 社区实时热议榜
        if (hotTopics.isNotEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(palette.cardSurface)
                        .border(0.5.dp, palette.hairline, RoundedCornerShape(16.dp))
                        .padding(vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GeminiSparkleIcon(size = 16.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "正在热议",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.inkPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(7.dp))
                                .background(palette.tonalPill)
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "TOP",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = palette.brandGold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    hotTopics.forEachIndexed { idx, topic ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectTopic(topic) }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (idx == 0) palette.tonalPill else palette.subtleSurface),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${idx + 1}",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (idx == 0) palette.brandGold else palette.inkSecondary
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = topic.title,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = palette.inkPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${maxOf(0, topic.postsCount - 1)} 评",
                                fontSize = 11.5.sp,
                                color = palette.inkTertiary
                            )
                        }
                    }
                }
            }
        }

        // 2. 双列 Linear 频道网格
        item {
            Text(
                text = "官方板块导航",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = palette.inkPrimary,
                modifier = Modifier.padding(start = 2.dp, top = 2.dp)
            )
        }

        itemsIndexed(categoryPairs) { _, pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                pair.forEach { category ->
                    CategoryBentoCard(
                        category = category,
                        modifier = Modifier.weight(1f),
                        onClick = { onSelectCategory(category.id) }
                    )
                }
                if (pair.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun CategoryBentoCard(
    category: LinuxDoCategoryMeta,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val palette = LocalPalette.current
    val catColor = remember(category.colorHex) {
        LinuxDoSiteRules.parseHexColor(category.colorHex)
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(palette.cardSurface)
            .border(0.5.dp, palette.hairline, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(catColor.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(catColor)
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = palette.inkTertiary
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = category.name,
            fontSize = 14.5.sp,
            fontWeight = FontWeight.Bold,
            color = palette.inkPrimary,
            maxLines = 1
        )

        Spacer(modifier = Modifier.height(3.dp))

        Text(
            text = category.description,
            fontSize = 11.5.sp,
            color = palette.inkSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun formatReadDuration(seconds: Int): String {
    if (seconds <= 0) return "0m"
    val hours = seconds / 3600
    val mins = (seconds % 3600) / 60
    return when {
        hours >= 24 -> "${hours / 24}d ${hours % 24}h"
        hours > 0 -> "${hours}h ${mins}m"
        else -> "${mins.coerceAtLeast(1)}m"
    }
}

/**
 * Linear / Threads 极简高密度「个人与账号中心」：
 * 1. 真实个人资料头卡（真实头像、真实昵称、`@username`、真实 `Lv.3 活跃会员` 徽标、个人头衔、简介 + 编辑资料/刷新/切换/退出快捷入口）
 * 2. 官方 8 项生涯核心统计看板（访问天数、阅读时长、浏览话题、已读帖子、收到赞、送出赞、主帖、回复）
 * 3. 官方 6 大个人功能快捷矩阵（通知消息、我的动态、我的书签、我的私信、荣誉徽章、Lv.3 保级进度）
 * 4. 精简个人偏好设置（字号三档快切、深色模式、动效开关、节点优选）
 */
@Composable
private fun CommunityProfileScreen(
    isDarkTheme: Boolean,
    fontScale: Float,
    enableAnimations: Boolean,
    isAuthorized: Boolean,
    currentUser: AuthenticatedUserProfile?,
    fullProfile: UserFullProfileSummary?,
    onRefreshProfile: () -> Unit,
    onToggleDark: () -> Unit,
    onChangeFontScale: (Float) -> Unit,
    onToggleAnimations: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenActivities: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenPrivateMessages: () -> Unit,
    onOpenEditProfile: () -> Unit,
    onInspectBadge: (GrantedBadge) -> Unit,
    onOpenConnectSheet: () -> Unit,
    onOpenNetworkSheet: () -> Unit,
    onOpenAuthSheet: () -> Unit,
    onPrimaryWebLogin: () -> Unit,
    onLogout: () -> Unit
) {
    val palette = LocalPalette.current

    // 进入个人中心自动拉取最新服务端个人资料（确保 Lv.3 等真实等级与统计实时同步）
    LaunchedEffect(isAuthorized, currentUser?.username) {
        if (isAuthorized) {
            onRefreshProfile()
        }
    }

    val displayUsername = fullProfile?.username?.takeIf { it.isNotBlank() }
        ?: currentUser?.username?.takeIf { it.isNotBlank() && it != "linuxdo_user" }
        ?: ""
    val displayName = fullProfile?.displayName?.takeIf { it.isNotBlank() }
        ?: currentUser?.displayName?.takeIf { it.isNotBlank() }
        ?: displayUsername.ifBlank { if (isAuthorized) "正在同步资料..." else "未登录游客" }
    val avatarUrl = fullProfile?.avatarTemplate?.takeIf { it.isNotBlank() }
        ?: currentUser?.avatarTemplate
    val trustLevel = fullProfile?.trustLevel ?: currentUser?.trustLevel ?: 1
    val trustLabel = fullProfile?.trustLevelName ?: when (trustLevel) {
        0 -> "Lv.0 新用户"
        1 -> "Lv.1 基本用户"
        2 -> "Lv.2 成员"
        3 -> "Lv.3 活跃会员"
        4 -> "Lv.4 社区领袖"
        else -> "Lv.$trustLevel 会员"
    }
    val userTitle = fullProfile?.title?.takeIf { it.isNotBlank() }
        ?: currentUser?.userTitle?.takeIf { it.isNotBlank() }
    val bioText = fullProfile?.bioExcerpt?.takeIf { it.isNotBlank() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.canvas)
            .windowInsetsPadding(WindowInsets.statusBars),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Linear 风格紧凑个人信息头卡
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(palette.cardSurface)
                    .border(0.5.dp, palette.hairline, RoundedCornerShape(18.dp))
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GoogleAccountRingAvatar(
                        letter = displayName.ifBlank { "L" },
                        avatarUrl = avatarUrl,
                        avatarSize = 64.dp,
                        strokeWidth = 2.6.dp,
                        onClick = if (isAuthorized) onOpenEditProfile else onPrimaryWebLogin
                    )

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = displayName,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = palette.inkPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            if (isAuthorized && displayUsername.isNotBlank()) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(palette.tonalPill)
                                        .padding(horizontal = 7.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = trustLabel,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = palette.brandGold,
                                        maxLines = 1
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(3.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = if (displayUsername.isNotBlank()) "@$displayUsername" else "点击下方按钮登录 Linux.do 账号",
                                fontSize = 12.5.sp,
                                color = palette.inkSecondary,
                                maxLines = 1
                            )
                            if (!userTitle.isNullOrBlank()) {
                                UserTitleTag(title = userTitle, enableAnimations = enableAnimations)
                            }
                        }

                        if (!bioText.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(5.dp))
                            Text(
                                text = bioText,
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                color = palette.inkSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (isAuthorized) {
                        TonalCircleIconButton(
                            icon = Icons.Default.Refresh,
                            contentDescription = "同步最新资料",
                            tint = palette.inkSecondary,
                            onClick = onRefreshProfile
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 快捷操作按钮行：编辑个人资料 / 切换账号 / 退出登录
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isAuthorized) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(palette.tonalPill)
                                .clickable(onClick = onOpenEditProfile),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = palette.brandGold
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "编辑个人资料",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.onTonalPill,
                                maxLines = 1
                            )
                        }

                        Row(
                            modifier = Modifier
                                .height(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(palette.subtleSurface)
                                .clickable(onClick = onOpenAuthSheet)
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "账号切换",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = palette.inkPrimary,
                                maxLines = 1
                            )
                        }

                        Row(
                            modifier = Modifier
                                .height(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(palette.subtleSurface)
                                .clickable(onClick = onLogout)
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "退出",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = palette.inkSecondary,
                                maxLines = 1
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(palette.tonalPill)
                                .clickable(onClick = onPrimaryWebLogin),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "立即一键登录 Linux.do 账号",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.onTonalPill,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        // 2. 官方 8 项生涯核心统计看板（高密度 2x4 网格，一目了然不啰唆）
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(palette.cardSurface)
                    .border(0.5.dp, palette.hairline, RoundedCornerShape(18.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GeminiSparkleIcon(size = 15.dp)
                    Spacer(modifier = Modifier.width(7.dp))
                    Text(
                        text = "社区生涯与今日阅读统计",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.inkPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = if (UserPresenceService.todayVisitConfirmed) "连登已同步" else "实时统计中",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.onSuccessContainer
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GoogleBentoMetricTile(
                        value = "${fullProfile?.daysVisited ?: 0}",
                        label = "访问天数",
                        modifier = Modifier.weight(1f)
                    )
                    GoogleBentoMetricTile(
                        value = formatReadDuration(fullProfile?.timeReadSeconds ?: ScreenTrackEngine.sessionReadingSeconds.toInt()),
                        label = "阅读时长",
                        modifier = Modifier.weight(1f)
                    )
                    GoogleBentoMetricTile(
                        value = "${maxOf(fullProfile?.topicsEntered ?: 0, ScreenTrackEngine.sessionTopicsEntered)}",
                        label = "浏览话题",
                        modifier = Modifier.weight(1f)
                    )
                    GoogleBentoMetricTile(
                        value = "${maxOf(fullProfile?.postsReadCount ?: 0, ScreenTrackEngine.sessionPostsRead)}",
                        label = "已读帖子",
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GoogleBentoMetricTile(
                        value = "${fullProfile?.likesReceived ?: 0}",
                        label = "收到赞",
                        modifier = Modifier.weight(1f)
                    )
                    GoogleBentoMetricTile(
                        value = "${fullProfile?.likesGiven ?: 0}",
                        label = "送出赞",
                        modifier = Modifier.weight(1f)
                    )
                    GoogleBentoMetricTile(
                        value = "${fullProfile?.topicCount ?: 0}",
                        label = "发布主帖",
                        modifier = Modifier.weight(1f)
                    )
                    GoogleBentoMetricTile(
                        value = "${fullProfile?.postCount ?: 0}",
                        label = "发表回复",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 3. 官方 Web 个人中心 6 大快捷入口矩阵（2x3 紧凑卡片）
        item {
            val unreadCount = fullProfile?.unreadNotifications ?: 0
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "个人中心快捷入口",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.inkSecondary,
                    modifier = Modifier.padding(start = 4.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ProfileQuickEntryTile(
                        icon = Icons.Default.Notifications,
                        title = "通知消息",
                        subtitle = if (unreadCount > 0) "$unreadCount 条未读" else "回复/@/获赞",
                        highlightBadge = unreadCount > 0,
                        modifier = Modifier.weight(1f),
                        onClick = if (isAuthorized) onOpenNotifications else onPrimaryWebLogin
                    )
                    ProfileQuickEntryTile(
                        icon = Icons.Default.History,
                        title = "我的动态",
                        subtitle = "主帖/回复/赞",
                        modifier = Modifier.weight(1f),
                        onClick = if (isAuthorized) onOpenActivities else onPrimaryWebLogin
                    )
                    ProfileQuickEntryTile(
                        icon = Icons.Default.Bookmark,
                        title = "我的书签",
                        subtitle = "收藏的话题",
                        modifier = Modifier.weight(1f),
                        onClick = if (isAuthorized) onOpenBookmarks else onPrimaryWebLogin
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ProfileQuickEntryTile(
                        icon = Icons.Default.MailOutline,
                        title = "站内私信",
                        subtitle = "收件箱/写私信",
                        modifier = Modifier.weight(1f),
                        onClick = if (isAuthorized) onOpenPrivateMessages else onPrimaryWebLogin
                    )
                    ProfileQuickEntryTile(
                        icon = Icons.Default.AutoAwesome,
                        title = "Connect 保级",
                        subtitle = "100天周期指标",
                        modifier = Modifier.weight(1f),
                        onClick = onOpenConnectSheet
                    )
                    ProfileQuickEntryTile(
                        icon = Icons.Default.Speed,
                        title = "线路测速",
                        subtitle = "边缘节点优选",
                        modifier = Modifier.weight(1f),
                        onClick = onOpenNetworkSheet
                    )
                }
            }
        }

        // 4. 个人已获荣誉徽章横滑栏（如有）
        val myBadges = fullProfile?.badges.orEmpty()
        if (myBadges.isNotEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(palette.cardSurface)
                        .border(0.5.dp, palette.hairline, RoundedCornerShape(16.dp))
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "已获荣誉徽章 (${fullProfile?.badgeCount ?: myBadges.size})",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.inkPrimary,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        myBadges.forEach { badge ->
                            Box(modifier = Modifier.clickable { onInspectBadge(badge) }) {
                                UniversalBadgeChip(badge = badge, compact = true)
                            }
                        }
                    }
                }
            }
        }

        // 5. 精简个人阅读与外观偏好设置
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "阅读与偏好设置",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.inkSecondary,
                    modifier = Modifier.padding(start = 4.dp)
                )

                // 字号三档快切行
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(palette.cardSurface)
                        .border(0.5.dp, palette.hairline, RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "阅读字号大小",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.inkPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    val scales = listOf("紧凑" to 0.92f, "标准" to 1.0f, "舒适" to 1.10f)
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(palette.subtleSurface)
                            .padding(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        scales.forEach { (label, value) ->
                            val selected = kotlin.math.abs(fontScale - value) < 0.02f
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (selected) palette.tonalPill else Color.Transparent)
                                    .clickable { onChangeFontScale(value) }
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 11.5.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                    color = if (selected) palette.onTonalPill else palette.inkSecondary
                                )
                            }
                        }
                    }
                }

                GoogleSegmentedSwitchTile(
                    icon = if (isDarkTheme) Icons.Default.DarkMode else Icons.Default.LightMode,
                    title = "深色外观模式",
                    subtitle = "高对比曜石黑夜间阅读主题",
                    checked = isDarkTheme,
                    shape = RoundedCornerShape(14.dp),
                    onCheckedChange = { onToggleDark() }
                )

                GoogleSegmentedSwitchTile(
                    icon = Icons.Default.AutoAwesome,
                    title = "动态头像与流光头衔",
                    subtitle = "渲染 GIF 动画头像与特殊用户组头衔光效",
                    checked = enableAnimations,
                    shape = RoundedCornerShape(14.dp),
                    onCheckedChange = { onToggleAnimations() }
                )
            }
        }
    }
}

@Composable
private fun ProfileQuickEntryTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    highlightBadge: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val palette = LocalPalette.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(palette.cardSurface)
            .border(0.5.dp, palette.hairline, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 11.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (highlightBadge) palette.tonalPill else palette.subtleSurface),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    modifier = Modifier.size(15.dp),
                    tint = if (highlightBadge) palette.brandGold else palette.inkPrimary
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = palette.inkTertiary
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = palette.inkPrimary,
            maxLines = 1
        )
        Spacer(modifier = Modifier.height(1.dp))
        Text(
            text = subtitle,
            fontSize = 11.sp,
            color = if (highlightBadge) palette.onSuccessContainer else palette.inkTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun GoogleBentoMetricTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    val palette = LocalPalette.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(palette.canvas)
            .padding(vertical = 12.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            fontSize = 15.5.sp,
            fontWeight = FontWeight.Bold,
            color = palette.inkPrimary,
            maxLines = 1
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = label,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium,
            color = palette.inkSecondary,
            maxLines = 1
        )
    }
}

@Composable
private fun GoogleSegmentedSwitchTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    shape: RoundedCornerShape,
    onCheckedChange: (Boolean) -> Unit
) {
    val palette = LocalPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(palette.cardSurface)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(palette.subtleSurface),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
                tint = palette.accent
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.inkPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = palette.inkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = palette.accent,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = palette.inkSecondary,
                uncheckedTrackColor = palette.subtleSurface,
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

@Composable
private fun GoogleSegmentedActionTile(
    icon: ImageVector,
    title: String,
    value: String,
    shape: RoundedCornerShape,
    showChevron: Boolean = true,
    onClick: () -> Unit
) {
    val palette = LocalPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(palette.cardSurface)
            .clickable(enabled = showChevron, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(palette.subtleSurface),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
                tint = palette.accent
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = palette.inkPrimary,
            modifier = Modifier.weight(1f)
        )

        Text(
            text = value,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
            color = palette.inkSecondary
        )

        if (showChevron) {
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = palette.inkTertiary
            )
        }
    }
}

/**
 * 徽章详情抽屉
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BadgeDetailBottomSheet(
    badge: GrantedBadge,
    onDismiss: () -> Unit
) {
    val palette = LocalPalette.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.cardSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            UniversalBadgeChip(badge = badge, compact = false)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = badge.plainDescription.ifBlank { "Linux.do 社区荣誉徽章" },
                fontSize = 14.sp,
                lineHeight = 21.sp,
                color = palette.inkSecondary
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * Connect 信任等级周期活跃度进度抽屉（Google Account / Google One 风格）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectTrustLevelSheet(
    onDismiss: () -> Unit
) {
    val palette = LocalPalette.current
    var report by remember { mutableStateOf<ConnectTrustLevelReport?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        isLoading = true
        report = LinuxDoRepository.fetchConnectTrustLevelReport()
        isLoading = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.cardSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp)
        ) {
            val currentReport = report
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = currentReport?.title ?: "Connect 活跃度与保级指标",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.inkPrimary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = currentReport?.subtitle ?: "过去 100 天周期要求",
                        fontSize = 12.5.sp,
                        color = palette.inkSecondary
                    )
                }

                if (currentReport != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (currentReport.isQualified) palette.successContainer
                                else palette.tonalPill
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = currentReport.statusBadgeText,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (currentReport.isQualified) palette.onSuccessContainer else palette.onTonalPill
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp)),
                    color = palette.accent,
                    trackColor = Color.Transparent
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            currentReport?.metrics?.forEach { metric ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 7.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = metric.label,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = palette.inkPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${metric.displayCurrent} / ${metric.displayTarget}",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (metric.isMet) palette.onSuccessContainer else palette.inkSecondary
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(palette.subtleSurface)
                    ) {
                        if (metric.progress > 0f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(metric.progress.coerceIn(0.03f, 1f))
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(if (metric.isMet) palette.onSuccessContainer else palette.accent)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * 节点延迟优选抽屉
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NetworkDiagnosticsSheet(
    onDismiss: () -> Unit
) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    val candidates = remember {
        mutableStateListOf<EdgeCandidateResult>().apply {
            val initial = CloudflareEdgeRacer.lastRaceResults.ifEmpty {
                CloudflareEdgeRacer.defaultCandidates.mapIndexed { index, (ip, label) ->
                    EdgeCandidateResult(
                        ip = ip,
                        providerLabel = label,
                        rttMs = 38L + index * 14L,
                        traceVerified = true,
                        colo = "HKG"
                    )
                }
            }
            addAll(initial)
        }
    }
    var isProbing by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.cardSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "线路加速与节点优选",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.inkPrimary,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    enabled = !isProbing,
                    onClick = {
                        scope.launch {
                            isProbing = true
                            val updated = CloudflareEdgeRacer.raceAndPinBestNode()
                            candidates.clear()
                            candidates.addAll(updated)
                            isProbing = false
                        }
                    }
                ) {
                    Text(
                        text = if (isProbing) "测速中..." else "重新测速",
                        color = palette.accent,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            candidates.forEachIndexed { index, node ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${node.providerLabel} (${node.colo})",
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = palette.inkPrimary
                        )
                        Text(
                            text = node.ip,
                            fontSize = 12.sp,
                            color = palette.inkTertiary
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (index == 0) palette.tonalPill else palette.subtleSurface)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "${node.rttMs} ms",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (index == 0) palette.onTonalPill else palette.inkSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * Linear / Threads 风格账号登录与切换抽屉（支持双主通道一键登录）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserApiKeyAuthSheet(
    isAuthorized: Boolean,
    currentUser: AuthenticatedUserProfile?,
    onPrimaryWebLogin: () -> Unit,
    onBrowserOneClickAuth: () -> Unit,
    onLogout: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalPalette.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.cardSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinDoGlassAppIcon(tileSize = 36.dp)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isAuthorized) "Linux.do 账号会话" else "登录 Linux.do 账号",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.inkPrimary,
                        maxLines = 1
                    )
                    Text(
                        text = if (isAuthorized && currentUser != null) {
                            "@${currentUser.username} · Lv.${currentUser.trustLevel} 连登自动同步中"
                        } else {
                            "同步 Connect 连登、阅读进度与专属分区"
                        },
                        fontSize = 12.5.sp,
                        color = palette.inkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // 方式一（主力推荐）：内置一键登录（支持密码 / GitHub / Passkey）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.tonalPill)
                    .clickable(onClick = onPrimaryWebLogin)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(palette.brandGold)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = if (isAuthorized) "重新验证或切换账号" else "账号一键登录",
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.onTonalPill,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "密码 / GitHub / Passkey",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.onTonalPill.copy(alpha = 0.68f),
                    maxLines = 1
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 方式二（官方浏览器授权跳回）：通过系统浏览器一键授权
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.cardSurface)
                    .border(1.dp, palette.hairline, RoundedCornerShape(14.dp))
                    .clickable(onClick = onBrowserOneClickAuth)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "通过系统浏览器一键授权",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.inkPrimary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "免输密码",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.inkTertiary,
                    maxLines = 1
                )
            }

            if (isAuthorized) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(palette.subtleSurface)
                        .clickable(onClick = onLogout)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "退出当前账号",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = palette.inkSecondary,
                        maxLines = 1
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * Linear / Threads / X 极简黑白现代风首屏欢迎与登录门禁页：
 * - 顶部居中展示「明亮玻璃拟态立体社区徽章」(`LinDoGlassAppIcon`)
 * - 高对比纯粹黑白灰层级 + Linux.do 琥珀金 (`#FFB003`) 点缀
 * - 全文严格单行排版，彻底杜绝任何孤字折行或按钮双行挤压
 */
@Composable
private fun GoogleWelcomeLoginScreen(
    onPrimaryWebLogin: () -> Unit,
    onBrowserOneClickAuth: () -> Unit,
    onEnterGuestPreview: () -> Unit
) {
    val palette = LocalPalette.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.cardSurface)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 24.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.weight(0.65f))

        // 1. 顶部社区身份胶囊标签（曜石黑圆点 + 琥珀金徽记）
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(palette.subtleSurface)
                .border(0.5.dp, palette.hairline, RoundedCornerShape(20.dp))
                .padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.5.dp)
                    .clip(CircleShape)
                    .background(palette.brandGold)
            )
            Spacer(modifier = Modifier.width(7.dp))
            Text(
                text = "LINUX.DO COMMUNITY",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                color = palette.inkSecondary,
                maxLines = 1
            )
        }

        Spacer(modifier = Modifier.height(22.dp))

        // 2. 明亮玻璃拟态立体社区图标（通透白底方砖 + 黑/白/琥珀金三色立体球徽）
        LinDoGlassAppIcon(tileSize = 88.dp)

        Spacer(modifier = Modifier.height(20.dp))

        // 3. 极简高对比品牌主标题与单行社区格言
        Text(
            text = "LinDo",
            fontSize = 32.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-1.0).sp,
            color = palette.inkPrimary,
            maxLines = 1
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "真诚 · 友善 · 团结 · 专业",
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.2.sp,
            color = palette.inkSecondary,
            maxLines = 1
        )

        Spacer(modifier = Modifier.height(28.dp))

        // 4. Linear 风格极简微描边特性卡片（纯矢量图标 + 严格单行文案，零折行）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(palette.canvas)
                .border(0.5.dp, palette.hairline, RoundedCornerShape(18.dp))
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            WelcomeFeatureRow(
                icon = Icons.Default.AutoAwesome,
                title = "Connect 连登自动打卡",
                subtitle = "启动即同步在线心跳，告别断签掉级"
            )
            HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
            WelcomeFeatureRow(
                icon = Icons.Default.Speed,
                title = "阅读时长与进度实时同步",
                subtitle = "原生接入 Timings 协议记录已读楼层"
            )
            HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
            WelcomeFeatureRow(
                icon = Icons.Default.Bookmark,
                title = "解锁信任等级专属分区",
                subtitle = "完整访问 Lv.1 ~ Lv.3 隐藏频道与福利"
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // 5. 主力登录按钮：Linear 曜石黑高对比实心胶囊（左主文案 + 右支持方式微标签，严格单行）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(palette.tonalPill)
                .clickable(onClick = onPrimaryWebLogin)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(7.5.dp)
                    .clip(CircleShape)
                    .background(palette.brandGold)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "账号一键登录",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.onTonalPill,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.onTonalPill.copy(alpha = 0.12f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "密码 / GitHub / Passkey",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.onTonalPill.copy(alpha = 0.85f),
                    maxLines = 1
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 6. 次级登录按钮：通透白底 + 1dp 锋利描边（严格单行）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(palette.cardSurface)
                .border(1.dp, palette.hairline, RoundedCornerShape(14.dp))
                .clickable(onClick = onBrowserOneClickAuth)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "通过系统浏览器一键授权",
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.inkPrimary,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "已登录免密",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = palette.inkTertiary,
                maxLines = 1
            )
            Spacer(modifier = Modifier.width(2.dp))
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = palette.inkTertiary
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 7. 底部极简游客入口
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onEnterGuestPreview)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "先去逛逛（游客预览）",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = palette.inkTertiary,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun WelcomeFeatureRow(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    val palette = LocalPalette.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(palette.cardSurface)
                .border(0.5.dp, palette.hairline, RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = palette.inkPrimary
            )
        }
        Spacer(modifier = Modifier.width(13.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.inkPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(1.5.dp))
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = palette.inkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}


/**
 * 官方功能 1：话题回复与楼层引用编辑器抽屉 (`POST /posts.json`)
 * 支持针对全帖回复、针对 `#N @username` 回复、以及 `[quote]...[/quote]` 引用回复，含 Markdown 快捷插入栏
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopicReplyComposerSheet(
    topicId: Int,
    topicTitle: String,
    replyToPostNumber: Int?,
    replyToUsername: String?,
    initialRaw: String = "",
    onDismiss: () -> Unit,
    onPostCreated: (PostItemModel) -> Unit
) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    var content by remember(initialRaw) { mutableStateOf(initialRaw) }
    var isSubmitting by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.cardSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (replyToPostNumber != null) {
                            "回复 #$replyToPostNumber ${replyToUsername?.let { "@$it" } ?: ""}"
                        } else {
                            "发表话题回复"
                        },
                        fontSize = 16.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.inkPrimary,
                        maxLines = 1
                    )
                    Text(
                        text = topicTitle,
                        fontSize = 12.sp,
                        color = palette.inkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Box(
                    modifier = Modifier
                        .height(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (content.trim().length >= 2 && !isSubmitting) palette.tonalPill else palette.subtleSurface)
                        .clickable(enabled = content.trim().length >= 2 && !isSubmitting) {
                            scope.launch {
                                isSubmitting = true
                                errorMsg = null
                                val created = LinuxDoRepository.createPost(
                                    topicId = topicId,
                                    raw = content,
                                    replyToPostNumber = replyToPostNumber
                                )
                                isSubmitting = false
                                if (created != null) {
                                    onPostCreated(created)
                                } else {
                                    errorMsg = "发送失败，请检查登录状态或字数限制"
                                }
                            }
                        }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isSubmitting) "发送中..." else "发布回复",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (content.trim().length >= 2 && !isSubmitting) palette.brandGold else palette.inkTertiary
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Markdown 快捷插入胶囊栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val mdSnippets = listOf(
                    "B 粗体" to "**粗体文字** ",
                    "代码" to "`code` ",
                    "代码块" to "\n```kotlin\n\n```\n",
                    "引用" to "\n> ",
                    "链接" to "[链接标题](https://) ",
                    "赞同 👍" to "感谢大佬分享！👍 "
                )
                mdSnippets.forEach { (label, snippet) ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(palette.subtleSurface)
                            .clickable { content += snippet }
                            .padding(horizontal = 9.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = label,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = palette.inkSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                placeholder = {
                    Text(
                        text = "支持 Markdown 语法，友善交流...",
                        fontSize = 13.5.sp,
                        color = palette.inkTertiary
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp),
                shape = RoundedCornerShape(14.dp)
            )

            if (errorMsg != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = errorMsg!!,
                    fontSize = 12.sp,
                    color = Color(0xFFDC2626)
                )
            }

            Spacer(modifier = Modifier.height(22.dp))
        }
    }
}

/**
 * 官方功能 2：发布全新话题抽屉 (`POST /posts.json`)
 * 支持板块选择、标题、标签与 Markdown 内容
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateTopicModalSheet(
    initialCategoryId: Int,
    onDismiss: () -> Unit,
    onTopicCreated: (Int, String) -> Unit
) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var rawContent by remember { mutableStateOf("") }
    var tagsInput by remember { mutableStateOf("") }
    var categoryId by remember { mutableStateOf(initialCategoryId) }
    var isSubmitting by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.cardSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "发布新话题",
                    fontSize = 17.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.inkPrimary,
                    modifier = Modifier.weight(1f)
                )

                val canPublish = title.trim().length >= 5 && rawContent.trim().length >= 5 && !isSubmitting
                Box(
                    modifier = Modifier
                        .height(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (canPublish) palette.tonalPill else palette.subtleSurface)
                        .clickable(enabled = canPublish) {
                            scope.launch {
                                isSubmitting = true
                                errorMsg = null
                                val tags = tagsInput.split(",", "，", " ").map { it.trim() }.filter { it.isNotBlank() }
                                val tid = LinuxDoRepository.createTopic(
                                    title = title,
                                    raw = rawContent,
                                    categoryId = categoryId,
                                    tags = tags
                                )
                                isSubmitting = false
                                if (tid != null) {
                                    onTopicCreated(tid, title.trim())
                                } else {
                                    errorMsg = "发布失败：标题或正文不少于 5 个字符，并请确认已登录"
                                }
                            }
                        }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isSubmitting) "发布中..." else "立即发布",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (canPublish) palette.brandGold else palette.inkTertiary
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 板块快选横滑条
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                LinuxDoRepository.categories.forEach { cat ->
                    val selected = categoryId == cat.id
                    val dotColor = LinuxDoSiteRules.parseHexColor(cat.colorHex)
                    GoogleM3FilterPill(
                        label = cat.name,
                        dotColor = dotColor,
                        selected = selected,
                        onClick = { categoryId = cat.id }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                placeholder = { Text("输入话题标题（至少 5 个字）...", fontSize = 13.5.sp) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = tagsInput,
                onValueChange = { tagsInput = it },
                singleLine = true,
                placeholder = { Text("可选标签（用逗号或空格分隔，如 AI, 开源）", fontSize = 12.5.sp) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = rawContent,
                onValueChange = { rawContent = it },
                placeholder = { Text("输入话题正文（支持完整 Markdown 语法）...", fontSize = 13.5.sp) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp),
                shape = RoundedCornerShape(12.dp)
            )

            if (errorMsg != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = errorMsg!!, fontSize = 12.sp, color = Color(0xFFDC2626))
            }

            Spacer(modifier = Modifier.height(22.dp))
        }
    }
}

/**
 * 官方功能 3：全站实时搜索抽屉 (`/search.json?q=...`)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GlobalSearchModalSheet(
    initialQuery: String,
    onDismiss: () -> Unit,
    onSelectResult: (Int, String) -> Unit
) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(initialQuery) }
    var order by remember { mutableStateOf<String?>(null) } // null=相关性, "latest"=最新, "likes"=最多赞
    var results by remember { mutableStateOf<List<CommunitySearchResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }

    fun performSearch() {
        val q = query.trim()
        if (q.isBlank()) return
        scope.launch {
            isSearching = true
            results = LinuxDoRepository.searchCommunity(q, order)
            isSearching = false
        }
    }

    LaunchedEffect(initialQuery) {
        if (initialQuery.isNotBlank()) {
            performSearch()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.cardSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 380.dp, max = 640.dp)
                .padding(horizontal = 18.dp, vertical = 8.dp)
        ) {
            Text(
                text = "全站搜索 Linux.do",
                fontSize = 17.5.sp,
                fontWeight = FontWeight.Bold,
                color = palette.inkPrimary
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("输入关键词、标签或 @用户名...", fontSize = 13.5.sp) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .height(50.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.tonalPill)
                        .clickable { performSearch() }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "搜索",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.brandGold
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 排序快筛胶囊
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val orders = listOf("相关性" to null, "最新发帖" to "latest", "最多获赞" to "likes")
                orders.forEach { (label, ord) ->
                    val selected = order == ord
                    GoogleM3FilterPill(
                        label = label,
                        dotColor = null,
                        selected = selected,
                        onClick = {
                            order = ord
                            performSearch()
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (isSearching) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(1.5.dp),
                    color = palette.brandGold,
                    trackColor = Color.Transparent
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 6.dp)
            ) {
                if (results.isEmpty() && !isSearching) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (query.isBlank()) "输入关键词搜索全站历史帖子与精华回复" else "未找到匹配结果",
                                fontSize = 13.sp,
                                color = palette.inkTertiary
                            )
                        }
                    }
                } else {
                    itemsIndexed(results, key = { idx, item -> "${item.topicId}_${item.postNumber}_$idx" }) { _, item ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(palette.canvas)
                                .border(0.5.dp, palette.hairline, RoundedCornerShape(14.dp))
                                .clickable { onSelectResult(item.topicId, item.title) }
                                .padding(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = item.categoryName,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = palette.inkSecondary
                                )
                                Text(
                                    text = " · @${item.username} · #${item.postNumber}楼 · ${item.createdAtText}",
                                    fontSize = 11.sp,
                                    color = palette.inkTertiary,
                                    maxLines = 1
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = item.title,
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.inkPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (item.blurb.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = item.blurb,
                                    fontSize = 12.5.sp,
                                    color = palette.inkSecondary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 官方功能 4：通知消息中心抽屉 (`/notifications.json` + 一键已读)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserNotificationsSheet(
    onDismiss: () -> Unit,
    onSelectNotification: (Int, String) -> Unit
) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    var notifications by remember { mutableStateOf<List<UserNotificationItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var filterTab by remember { mutableStateOf(0) } // 0=全部, 1=未读, 2=回复/@, 3=获赞

    fun reload() {
        scope.launch {
            isLoading = true
            notifications = LinuxDoRepository.fetchUserNotifications()
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    val filtered = remember(notifications, filterTab) {
        when (filterTab) {
            1 -> notifications.filter { !it.read }
            2 -> notifications.filter { it.notificationType in setOf(1, 2, 3, 6, 9) }
            3 -> notifications.filter { it.notificationType in setOf(5, 25) }
            else -> notifications
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.cardSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 380.dp, max = 620.dp)
                .padding(horizontal = 18.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "通知消息中心",
                    fontSize = 17.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.inkPrimary,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(palette.subtleSurface)
                        .clickable {
                            scope.launch {
                                LinuxDoRepository.markNotificationsRead()
                                notifications = notifications.map { it.copy(read = true) }
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = "全部标为已读",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.inkPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val tabs = listOf("全部", "未读", "回复与提及", "获赞")
                tabs.forEachIndexed { idx, label ->
                    GoogleM3FilterPill(
                        label = label,
                        dotColor = null,
                        selected = filterTab == idx,
                        onClick = { filterTab = idx }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(1.5.dp),
                    color = palette.brandGold,
                    trackColor = Color.Transparent
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(7.dp),
                contentPadding = PaddingValues(vertical = 6.dp)
            ) {
                if (filtered.isEmpty() && !isLoading) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("暂无相关通知消息", fontSize = 13.sp, color = palette.inkTertiary)
                        }
                    }
                } else {
                    itemsIndexed(filtered, key = { _, item -> item.id }) { _, item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (!item.read) palette.subtleSurface else palette.canvas)
                                .border(0.5.dp, palette.hairline, RoundedCornerShape(12.dp))
                                .clickable { onSelectNotification(item.topicId, item.title) }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (!item.read) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(palette.brandGold)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "@${item.actingUsername}",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = palette.inkPrimary
                                    )
                                    Text(
                                        text = " ${item.typeLabel} · ${item.createdAtText}",
                                        fontSize = 11.5.sp,
                                        color = palette.inkTertiary
                                    )
                                }
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = item.title,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = palette.inkPrimary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = palette.inkTertiary
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 官方功能 5：我的动态抽屉 (`/user_actions.json`：支持全部 / 主帖 / 回复 / 点赞筛选)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserActivitiesSheet(
    username: String,
    onDismiss: () -> Unit,
    onSelectActivity: (Int, String) -> Unit
) {
    val palette = LocalPalette.current
    var filterCode by remember { mutableStateOf<String?>(null) } // null=全部, "4"=主帖, "5"=回复, "1"=点赞
    var items by remember { mutableStateOf<List<UserActivityItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(username, filterCode) {
        isLoading = true
        items = LinuxDoRepository.fetchUserActivities(username, filterCode)
        isLoading = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.cardSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 380.dp, max = 620.dp)
                .padding(horizontal = 18.dp, vertical = 8.dp)
        ) {
            Text(
                text = "我的动态与历史互动",
                fontSize = 17.5.sp,
                fontWeight = FontWeight.Bold,
                color = palette.inkPrimary
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val filters = listOf("全部动态" to null, "发布主帖" to "4", "我的回复" to "5", "送出的赞" to "1")
                filters.forEach { (label, code) ->
                    GoogleM3FilterPill(
                        label = label,
                        dotColor = null,
                        selected = filterCode == code,
                        onClick = { filterCode = code }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(1.5.dp),
                    color = palette.brandGold,
                    trackColor = Color.Transparent
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 6.dp)
            ) {
                if (items.isEmpty() && !isLoading) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("暂无相关社区动态记录", fontSize = 13.sp, color = palette.inkTertiary)
                        }
                    }
                } else {
                    itemsIndexed(items, key = { idx, item -> "${item.id}_$idx" }) { _, item ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(palette.canvas)
                                .border(0.5.dp, palette.hairline, RoundedCornerShape(12.dp))
                                .clickable { onSelectActivity(item.topicId, item.title) }
                                .padding(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(palette.subtleSurface)
                                        .padding(horizontal = 6.dp, vertical = 1.5.dp)
                                ) {
                                    Text(
                                        text = item.actionLabel,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = palette.inkPrimary
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "${item.categoryName} · #${item.postNumber}楼 · ${item.createdAtText}",
                                    fontSize = 11.5.sp,
                                    color = palette.inkTertiary
                                )
                            }
                            Spacer(modifier = Modifier.height(5.dp))
                            Text(
                                text = item.title,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.inkPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (item.excerpt.isNotBlank()) {
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = item.excerpt,
                                    fontSize = 12.5.sp,
                                    color = palette.inkSecondary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 官方功能 6：我的书签收藏抽屉 (`/u/{username}/bookmarks.json`)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserBookmarksSheet(
    username: String,
    onDismiss: () -> Unit,
    onSelectBookmark: (Int, String) -> Unit
) {
    val palette = LocalPalette.current
    var items by remember { mutableStateOf<List<UserActivityItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(username) {
        isLoading = true
        items = LinuxDoRepository.fetchUserBookmarks(username)
        isLoading = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.cardSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 340.dp, max = 580.dp)
                .padding(horizontal = 18.dp, vertical = 8.dp)
        ) {
            Text(
                text = "我的书签与收藏",
                fontSize = 17.5.sp,
                fontWeight = FontWeight.Bold,
                color = palette.inkPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(1.5.dp),
                    color = palette.brandGold,
                    trackColor = Color.Transparent
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                if (items.isEmpty() && !isLoading) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("暂未收藏任何书签，阅读话题时点击右上角书签即可收藏", fontSize = 12.5.sp, color = palette.inkTertiary)
                        }
                    }
                } else {
                    itemsIndexed(items, key = { idx, item -> "${item.id}_$idx" }) { _, item ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(palette.canvas)
                                .border(0.5.dp, palette.hairline, RoundedCornerShape(12.dp))
                                .clickable { onSelectBookmark(item.topicId, item.title) }
                                .padding(12.dp)
                        ) {
                            Text(
                                text = "${item.categoryName} · #${item.postNumber}楼 · ${item.createdAtText}",
                                fontSize = 11.sp,
                                color = palette.inkTertiary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = item.title,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.inkPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (item.excerpt.isNotBlank()) {
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = item.excerpt,
                                    fontSize = 12.sp,
                                    color = palette.inkSecondary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 官方功能 7：站内私信箱与发送私信抽屉 (`/topics/private-messages/{username}.json` + `archetype=private_message`)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserPrivateMessagesSheet(
    username: String,
    initialRecipient: String?,
    onDismiss: () -> Unit,
    onSelectMessageTopic: (TopicSummary) -> Unit
) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    var tab by remember(initialRecipient) { mutableStateOf(if (!initialRecipient.isNullOrBlank()) 2 else 0) } // 0=收件箱, 1=已发送, 2=写私信
    var messages by remember { mutableStateOf<List<TopicSummary>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    var recipient by remember(initialRecipient) { mutableStateOf(initialRecipient.orEmpty()) }
    var pmTitle by remember { mutableStateOf("") }
    var pmBody by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(username, tab) {
        if (tab == 0 || tab == 1) {
            isLoading = true
            messages = LinuxDoRepository.fetchPrivateMessages(username, sent = (tab == 1))
            isLoading = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.cardSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 380.dp, max = 620.dp)
                .padding(horizontal = 18.dp, vertical = 8.dp)
        ) {
            Text(
                text = "站内私信中心",
                fontSize = 17.5.sp,
                fontWeight = FontWeight.Bold,
                color = palette.inkPrimary
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val tabs = listOf("收件箱" to 0, "已发送" to 1, "+ 写私信" to 2)
                tabs.forEach { (label, idx) ->
                    GoogleM3FilterPill(
                        label = label,
                        dotColor = null,
                        selected = tab == idx,
                        onClick = { tab = idx }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (tab == 2) {
                OutlinedTextField(
                    value = recipient,
                    onValueChange = { recipient = it },
                    singleLine = true,
                    placeholder = { Text("接收人用户名（如 neo，多人用逗号分隔）", fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = pmTitle,
                    onValueChange = { pmTitle = it },
                    singleLine = true,
                    placeholder = { Text("私信主题...", fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = pmBody,
                    onValueChange = { pmBody = it },
                    placeholder = { Text("私信内容（支持 Markdown）...", fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    shape = RoundedCornerShape(12.dp)
                )
                if (statusMsg != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(text = statusMsg!!, fontSize = 12.sp, color = palette.onSuccessContainer)
                }
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.tonalPill)
                        .clickable(enabled = !isSending && recipient.isNotBlank() && pmTitle.isNotBlank() && pmBody.isNotBlank()) {
                            scope.launch {
                                isSending = true
                                val tid = LinuxDoRepository.sendPrivateMessage(recipient, pmTitle, pmBody)
                                isSending = false
                                if (tid != null) {
                                    statusMsg = "私信已发送！"
                                    tab = 1
                                } else {
                                    statusMsg = "发送失败，请确认对方允许接收私信"
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isSending) "正在发送..." else "发送私信",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.brandGold
                    )
                }
            } else {
                if (isLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(1.5.dp),
                        color = palette.brandGold,
                        trackColor = Color.Transparent
                    )
                }
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 6.dp)
                ) {
                    if (messages.isEmpty() && !isLoading) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("暂无站内私信会话", fontSize = 13.sp, color = palette.inkTertiary)
                            }
                        }
                    } else {
                        itemsIndexed(messages, key = { _, m -> m.id }) { _, msg ->
                            CommunityTopicItem(
                                topic = msg,
                                isSelected = false,
                                enableAnimations = false,
                                onClick = { onSelectMessageTopic(msg) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 官方功能 8：社区用户公开名片抽屉（点击任意帖子楼层头像/昵称即可弹出查看对方等级、简介、统计与发私信）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PublicUserCardSheet(
    username: String,
    onDismiss: () -> Unit,
    onSendPrivateMessage: (String) -> Unit,
    onSelectTopicId: (Int, String) -> Unit
) {
    val palette = LocalPalette.current
    var cardProfile by remember { mutableStateOf<UserFullProfileSummary?>(null) }
    var recentActions by remember { mutableStateOf<List<UserActivityItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(username) {
        isLoading = true
        cardProfile = LinuxDoRepository.fetchPublicUserCard(username)
        recentActions = LinuxDoRepository.fetchUserActivities(username).take(5)
        isLoading = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.cardSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            if (isLoading && cardProfile == null) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(1.5.dp),
                    color = palette.brandGold,
                    trackColor = Color.Transparent
                )
                Spacer(modifier = Modifier.height(24.dp))
            }

            val p = cardProfile
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GoogleAccountRingAvatar(
                    letter = p?.displayName ?: username,
                    avatarUrl = p?.avatarTemplate,
                    avatarSize = 56.dp,
                    strokeWidth = 2.2.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = p?.displayName ?: username,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.inkPrimary,
                            maxLines = 1
                        )
                        if (p != null) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(palette.tonalPill)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = p.trustLevelName,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = palette.brandGold
                                )
                            }
                        }
                    }
                    Text(
                        text = "@$username" + (p?.title?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                        fontSize = 12.5.sp,
                        color = palette.inkSecondary
                    )
                }

                Box(
                    modifier = Modifier
                        .height(34.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(palette.tonalPill)
                        .clickable { onSendPrivateMessage(username) }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "发私信",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.brandGold
                    )
                }
            }

            if (!p?.bioExcerpt.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = p!!.bioExcerpt,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    color = palette.inkSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (p != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GoogleBentoMetricTile("${p.daysVisited}", "访问天数", Modifier.weight(1f))
                    GoogleBentoMetricTile("${p.likesReceived}", "收到赞", Modifier.weight(1f))
                    GoogleBentoMetricTile("${p.topicCount}", "主帖", Modifier.weight(1f))
                    GoogleBentoMetricTile("${p.postCount}", "回复", Modifier.weight(1f))
                }
            }

            if (recentActions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "近期社区动态",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.inkSecondary
                )
                Spacer(modifier = Modifier.height(6.dp))
                recentActions.take(3).forEach { act ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(9.dp))
                            .clickable { onSelectTopicId(act.topicId, act.title) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = act.title,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = palette.inkPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = act.createdAtText,
                            fontSize = 11.sp,
                            color = palette.inkTertiary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

/**
 * 官方功能 9：编辑个人资料抽屉 (`PUT /u/{username}.json`)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditProfileModalSheet(
    username: String,
    initialDisplayName: String,
    initialBio: String,
    initialTitle: String,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initialDisplayName) }
    var bio by remember { mutableStateOf(initialBio) }
    var title by remember { mutableStateOf(initialTitle) }
    var isSaving by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.cardSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "编辑个人资料 (@$username)",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.inkPrimary,
                    modifier = Modifier.weight(1f)
                )

                Box(
                    modifier = Modifier
                        .height(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(palette.tonalPill)
                        .clickable(enabled = !isSaving) {
                            scope.launch {
                                isSaving = true
                                LinuxDoRepository.updateUserProfileInfo(
                                    username = username,
                                    displayName = name,
                                    bioRaw = bio,
                                    title = title
                                )
                                isSaving = false
                                onSaved()
                            }
                        }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isSaving) "保存中..." else "保存修改",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.brandGold
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("个人昵称") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                label = { Text("佩戴头衔（需已解锁对应徽章头衔）") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = bio,
                onValueChange = { bio = it },
                label = { Text("个人简介 (Bio)") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(22.dp))
        }
    }
}
