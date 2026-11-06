package do_.linux.lindo.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
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
import do_.linux.lindo.core.model.PostReactionItem
import do_.linux.lindo.core.model.TopicDetailModel
import do_.linux.lindo.core.model.TopicSummary
import do_.linux.lindo.core.model.TopicUser
import do_.linux.lindo.core.model.UserActivityItem
import do_.linux.lindo.core.model.UserFullProfileSummary
import do_.linux.lindo.core.model.UserNotificationItem
import do_.linux.lindo.core.network.AuthenticatedUserProfile
import do_.linux.lindo.core.network.CloudDraftItem
import do_.linux.lindo.core.network.CloudflareEdgeRacer
import do_.linux.lindo.core.network.EdgeCandidateResult
import do_.linux.lindo.core.network.LinuxDoCategoryMeta
import do_.linux.lindo.core.network.LinuxDoRepository
import do_.linux.lindo.core.network.MentionCandidate
import do_.linux.lindo.core.network.PlatformMediaPicker
import do_.linux.lindo.core.network.UploadedMediaResult
import do_.linux.lindo.core.network.UserApiKeyAuthManager
import do_.linux.lindo.core.render.DiscourseAstParser
import do_.linux.lindo.core.render.PostBlockNode
import do_.linux.lindo.core.site.LinuxDoSiteRules
import do_.linux.lindo.ui.components.LinDoIcons
import do_.linux.lindo.ui.components.PostGrantedBadgeIcon
import lindo.composeapp.generated.resources.*
import org.jetbrains.compose.resources.painterResource
import do_.linux.lindo.ui.components.SmartAvatar
import do_.linux.lindo.ui.components.UniversalBadgeChip
import do_.linux.lindo.ui.components.UserTitleTag
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 可选品牌主题色配置：
 * - 默认：`OFFICIAL_GOLD`（Linux.do 官方经典黑白金黄配色）
 * - 可选：`IDEAL_RED`（理想绯红）、`GEEK_BLUE`（极客海蓝）、`BAMBOO_GREEN`（竹林青绿）
 */
enum class BrandThemeOption(
    val key: String,
    val label: String,
    val swatchColor: Color,
    val logoDoColor: Color,
    val tabIndicatorColor: Color,
    val lightChipBg: Color,
    val lightChipText: Color,
    val darkChipBg: Color,
    val darkChipText: Color,
    val publishBtnBg: Color,
    val publishBtnContent: Color
) {
    OFFICIAL_GOLD(
        key = "OFFICIAL_GOLD",
        label = "官方黑白黄",
        swatchColor = Color(0xFFF59E0B),
        logoDoColor = Color(0xFFF59E0B),
        tabIndicatorColor = Color(0xFFF59E0B),
        lightChipBg = Color(0xFFFEF3C7),
        lightChipText = Color(0xFFB45309),
        darkChipBg = Color(0xFF3F2E05),
        darkChipText = Color(0xFFFCD34D),
        publishBtnBg = Color(0xFF18181B),
        publishBtnContent = Color(0xFFFFB003)
    ),
    IDEAL_RED(
        key = "IDEAL_RED",
        label = "理想绯红",
        swatchColor = Color(0xFFE03E3E),
        logoDoColor = Color(0xFFE03E3E),
        tabIndicatorColor = Color(0xFFE03E3E),
        lightChipBg = Color(0xFFFDF2F2),
        lightChipText = Color(0xFFE03E3E),
        darkChipBg = Color(0xFF3F1515),
        darkChipText = Color(0xFFF87171),
        publishBtnBg = Color(0xFFE03E3E),
        publishBtnContent = Color(0xFFFFFFFF)
    ),
    GEEK_BLUE(
        key = "GEEK_BLUE",
        label = "极客海蓝",
        swatchColor = Color(0xFF2563EB),
        logoDoColor = Color(0xFF2563EB),
        tabIndicatorColor = Color(0xFF2563EB),
        lightChipBg = Color(0xFFEFF6FF),
        lightChipText = Color(0xFF1D4ED8),
        darkChipBg = Color(0xFF172554),
        darkChipText = Color(0xFF60A5FA),
        publishBtnBg = Color(0xFF2563EB),
        publishBtnContent = Color(0xFFFFFFFF)
    ),
    BAMBOO_GREEN(
        key = "BAMBOO_GREEN",
        label = "竹林青绿",
        swatchColor = Color(0xFF059669),
        logoDoColor = Color(0xFF059669),
        tabIndicatorColor = Color(0xFF059669),
        lightChipBg = Color(0xFFECFDF5),
        lightChipText = Color(0xFF047857),
        darkChipBg = Color(0xFF064E3B),
        darkChipText = Color(0xFF34D399),
        publishBtnBg = Color(0xFF059669),
        publishBtnContent = Color(0xFFFFFFFF)
    );

    companion object {
        fun fromKey(key: String?): BrandThemeOption {
            return entries.firstOrNull { it.key == key } ?: OFFICIAL_GOLD
        }
    }
}

/**
 * 理想型社区设计规范调色盘（温润白底 · 莫兰迪柔色 · 可选主题色，默认 Linux.do 官方黑白黄）
 */
data class LinDoThemePalette(
    val isDark: Boolean,
    val themeOption: BrandThemeOption,
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
    val logoDoColor: Color,
    val tabIndicatorColor: Color,
    val activeChipBg: Color,
    val activeChipText: Color,
    val publishBtnBg: Color,
    val publishBtnContent: Color,
    val successContainer: Color,
    val onSuccessContainer: Color
)

private fun buildLinDoPalette(
    isDark: Boolean,
    option: BrandThemeOption = BrandThemeOption.OFFICIAL_GOLD
): LinDoThemePalette {
    return if (!isDark) {
        LinDoThemePalette(
            isDark = false,
            themeOption = option,
            canvas = Color(0xFFF6F7F9),
            groupedCanvas = Color(0xFFF6F7F9),
            cardSurface = Color(0xFFFFFFFF),
            subtleSurface = Color(0xFFF1F3F6),
            navBarSurface = Color(0xFFFFFFFF),
            tonalPill = Color(0xFFEEF1F5),
            onTonalPill = Color(0xFF111827),
            inkPrimary = Color(0xFF111827),
            inkSecondary = Color(0xFF525866),
            inkTertiary = Color(0xFF9499A3),
            hairline = Color(0xFFEDEFF3),
            accent = if (option == BrandThemeOption.OFFICIAL_GOLD) Color(0xFF18181B) else option.logoDoColor,
            brandGold = option.logoDoColor,
            logoDoColor = option.logoDoColor,
            tabIndicatorColor = option.tabIndicatorColor,
            activeChipBg = option.lightChipBg,
            activeChipText = option.lightChipText,
            publishBtnBg = option.publishBtnBg,
            publishBtnContent = option.publishBtnContent,
            successContainer = Color(0xFFFDF6E9),
            onSuccessContainer = Color(0xFFB7791F)
        )
    } else {
        LinDoThemePalette(
            isDark = true,
            themeOption = option,
            canvas = Color(0xFF0D0F13),
            groupedCanvas = Color(0xFF0D0F13),
            cardSurface = Color(0xFF161920),
            subtleSurface = Color(0xFF1F232D),
            navBarSurface = Color(0xFF14171D),
            tonalPill = Color(0xFF252A36),
            onTonalPill = Color(0xFFF3F4F6),
            inkPrimary = Color(0xFFF3F4F6),
            inkSecondary = Color(0xFF9CA3AF),
            inkTertiary = Color(0xFF6B7280),
            hairline = Color(0xFF222630),
            accent = option.logoDoColor,
            brandGold = option.logoDoColor,
            logoDoColor = option.logoDoColor,
            tabIndicatorColor = option.tabIndicatorColor,
            activeChipBg = option.darkChipBg,
            activeChipText = option.darkChipText,
            publishBtnBg = if (option == BrandThemeOption.OFFICIAL_GOLD) Color(0xFFF59E0B) else option.publishBtnBg,
            publishBtnContent = if (option == BrandThemeOption.OFFICIAL_GOLD) Color(0xFF111827) else Color(0xFFFFFFFF),
            successContainer = Color(0xFF3F2E05),
            onSuccessContainer = Color(0xFFFCD34D)
        )
    }
}

/**
 * 莫兰迪低饱和版块色彩映射（雾霾蓝 / 鼠尾草绿 / 暖琥珀金 / 浅绯粉）
 */
private fun resolveMorandiCategoryColors(categoryName: String, isDark: Boolean): Pair<Color, Color> {
    return when {
        categoryName.contains("开发") || categoryName.contains("快讯") || categoryName.contains("读书") -> {
            if (isDark) Color(0xFF1E293B) to Color(0xFF93C5FD)
            else Color(0xFFEEF3F8) to Color(0xFF3B6E9C)
        }
        categoryName.contains("资源") || categoryName.contains("文档") || categoryName.contains("扬帆") -> {
            if (isDark) Color(0xFF143327) to Color(0xFF6EE7B7)
            else Color(0xFFEDF6F0) to Color(0xFF2E7D5B)
        }
        categoryName.contains("搞七") || categoryName.contains("福利") || categoryName.contains("市场") -> {
            if (isDark) Color(0xFF3B2A10) to Color(0xFFFCD34D)
            else Color(0xFFFDF6E9) to Color(0xFFB7791F)
        }
        else -> {
            if (isDark) Color(0xFF3B1A1F) to Color(0xFFFCA5A5)
            else Color(0xFFFCEFF0) to Color(0xFFC53030)
        }
    }
}

private val LightPalette = buildLinDoPalette(isDark = false, option = BrandThemeOption.OFFICIAL_GOLD)
private val DarkPalette = buildLinDoPalette(isDark = true, option = BrandThemeOption.OFFICIAL_GOLD)

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
    HOME("首页", LinDoIcons.HomeSelected, LinDoIcons.Home),
    DISCOVER("发现", LinDoIcons.CompassSelected, LinDoIcons.Compass),
    MESSAGES("消息", LinDoIcons.BellSelected, LinDoIcons.Bell),
    PROFILE("我的", LinDoIcons.UserSelected, LinDoIcons.User)
}

private enum class FeedSortFilter(val label: String, val apiPath: String) {
    LATEST("最新", "latest.json"),
    TOP("热门", "top.json"),
    NEW("新帖", "new.json"),
    UNREAD("未读", "unread.json"),
    READ("关注", "read.json")
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
    LinDoLoadingIndicator(modifier = modifier, label = "加载中")
}

/**
 * 底部加载与收尾条（支持分页加载中、网络异常手动重试、点击加载更多、已加载全部四种状态）
 */
@Composable
private fun LinearPulseLoadingFooter(
    isLoadingMore: Boolean,
    hasMore: Boolean,
    loadedCount: Int,
    isError: Boolean = false,
    loadingText: String = "加载中",
    moreText: String = "加载更多",
    errorText: String = "网络异常",
    doneText: String = "没有更多了",
    onLoadMore: () -> Unit
) {
    val palette = LocalPalette.current
    val state = when {
        isError -> LinDoMascotState.NetworkError
        isLoadingMore -> LinDoMascotState.Loading
        !hasMore -> LinDoMascotState.End
        else -> LinDoMascotState.Refreshing
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(enabled = !isLoadingMore && (hasMore || isError), onClick = onLoadMore)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!hasMore && !isLoadingMore && !isError) {
            Box(
                modifier = Modifier
                    .width(24.dp)
                    .height(0.5.dp)
                    .background(palette.hairline)
            )
            Spacer(Modifier.width(10.dp))
        }
        LinDoMascot(state = state, compact = true, size = 22.dp, animate = isLoadingMore)
        Spacer(Modifier.width(6.dp))
        Text(
            text = when {
                isError -> errorText
                isLoadingMore -> "加载中"
                !hasMore -> "没有更多了"
                else -> moreText
            },
            color = palette.inkSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal,
            lineHeight = 18.sp
        )
        if (isLoadingMore) {
            Spacer(Modifier.width(4.dp))
            LinDoWaveDots(color = palette.inkSecondary, dotSize = 3.5.dp)
        }
        if (isError) {
            Spacer(Modifier.width(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Icon(
                    imageVector = LinDoIcons.Refresh,
                    contentDescription = "刷新",
                    modifier = Modifier.size(13.dp),
                    tint = palette.inkSecondary
                )
                Text(
                    text = "刷新",
                    color = palette.inkSecondary,
                    fontSize = 11.8.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        if (!hasMore && !isLoadingMore && !isError) {
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .width(24.dp)
                    .height(0.5.dp)
                    .background(palette.hairline)
            )
        }
    }
}

/**
 * 全局统一的屏幕居中零度娘摇摆 Loading 视图
 */
@Composable
private fun LinearShimmerTopicSkeletonList(isCompact: Boolean = false) {
    val palette = LocalPalette.current
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        LinDoLoadingIndicator(
            color = palette.inkSecondary,
            label = "加载中"
        )
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
 * 纯净圆角用户头像（移除外围 Linux.do 双色圆环，保持清爽现代全尺寸圆形裁切与 0.5dp 微描边）
 */
@Composable
private fun GoogleAccountRingAvatar(
    letter: String = "L",
    avatarUrl: String? = null,
    avatarSize: Dp = 76.dp,
    strokeWidth: Dp = 0.5.dp,
    onClick: (() -> Unit)? = null,
    animatedAvatar: String? = null
) {
    Box(Modifier.size(avatarSize).let {
        if (onClick != null) it.clickable(onClick = onClick) else it
    }, contentAlignment = Alignment.Center) {
        SmartAvatar(username = letter, avatarTemplate = avatarUrl, animatedAvatar = animatedAvatar,
            size = avatarSize, enableAnimations = LocalLinDoAnimations.current)
    }
}

@Composable
fun LinDoApp(onThemeChanged: (Boolean) -> Unit = {}, systemAnimationsEnabled: Boolean = true) {
    var isDarkTheme by remember { mutableStateOf(UserApiKeyAuthManager.uiDarkTheme) }
    LaunchedEffect(isDarkTheme) { onThemeChanged(isDarkTheme) }
    var fontScale by remember { mutableStateOf(UserApiKeyAuthManager.uiFontScale) }
    var enableAnimations by remember { mutableStateOf(UserApiKeyAuthManager.uiEnableAnimations) }
    var themeOption by remember { mutableStateOf(BrandThemeOption.fromKey(UserApiKeyAuthManager.uiThemeAccent)) }
    var isCompactFeed by remember { mutableStateOf(UserApiKeyAuthManager.uiCompactFeed) }
    val palette = remember(isDarkTheme, themeOption) {
        buildLinDoPalette(isDark = isDarkTheme, option = themeOption)
    }

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

    fun updateBrandThemeOption(newOption: BrandThemeOption) {
        themeOption = newOption
        UserApiKeyAuthManager.updateThemeAndDensityPrefs(
            themeAccent = newOption.key,
            compactFeed = isCompactFeed
        )
    }

    fun updateCompactFeedMode(compact: Boolean) {
        isCompactFeed = compact
        UserApiKeyAuthManager.updateThemeAndDensityPrefs(
            themeAccent = themeOption.key,
            compactFeed = compact
        )
    }

    var currentTab by remember { mutableStateOf(MainTab.HOME) }
    var selectedCategoryId by remember { mutableStateOf<Int?>(null) }
    var selectedTag by remember { mutableStateOf<String?>(null) }
    var currentSort by remember { mutableStateOf(FeedSortFilter.LATEST) }
    var currentTopPeriod by remember { mutableStateOf(TopPeriodFilter.DAILY) }
    var searchQuery by remember { mutableStateOf("") }

    var topics by remember { mutableStateOf<List<TopicSummary>>(emptyList()) }
    var pendingNewTopics by remember { mutableStateOf<List<TopicSummary>>(emptyList()) }
    var pendingNewCount by remember { mutableStateOf(0) }
    var currentPage by remember { mutableStateOf(0) }
    var hasMoreTopics by remember { mutableStateOf(true) }
    var isLoadingTopics by remember { mutableStateOf(true) }
    var isLoadingMoreTopics by remember { mutableStateOf(false) }
    var loadMoreError by remember { mutableStateOf(false) }
    var feedError by remember { mutableStateOf<String?>(null) }
    var refreshUpToDateNotice by remember { mutableStateOf(false) }
    var loadTopicsJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var loadMoreJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    var activeTopicSummary by remember { mutableStateOf<TopicSummary?>(null) }
    var activeTopicDetail by remember { mutableStateOf<TopicDetailModel?>(null) }
    var activeTargetPostNumber by remember { mutableStateOf<Int?>(null) }
    var pendingReplyDraft by remember { mutableStateOf<CloudDraftItem?>(null) }
    var pendingNewTopicDraft by remember { mutableStateOf<CloudDraftItem?>(null) }
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
    var showReadHistorySheet by remember { mutableStateOf(false) }
    var showCloudDraftsSheet by remember { mutableStateOf(false) }
    var showPrivateMessagesSheet by remember { mutableStateOf(false) }
    var pmInitialRecipient by remember { mutableStateOf<String?>(null) }
    var showEditProfileSheet by remember { mutableStateOf(false) }

    // 认证状态与真实个人资料状态
    var authVersion by remember { mutableStateOf(0) }
    var wasAuthorized by remember { mutableStateOf(UserApiKeyAuthManager.isAuthorized()) }
    var activeWebLoginUrl by remember { mutableStateOf<String?>(null) }
    var fullUserProfile by remember { mutableStateOf<UserFullProfileSummary?>(UserApiKeyAuthManager.fullProfileCache) }

    val isAuthorized = remember(authVersion) { UserApiKeyAuthManager.isAuthorized() }
    val currentUser = remember(authVersion, fullUserProfile) { UserApiKeyAuthManager.currentUser }

    val feedListState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    fun syncUserProfile() {
        scope.launch {
            val profile = LinuxDoRepository.fetchCurrentUserProfile()
            if (profile != null) {
                fullUserProfile = profile
            }
        }
    }

    fun applyPendingNewTopics() {
        if (pendingNewTopics.isNotEmpty()) {
            topics = pendingNewTopics
            pendingNewTopics = emptyList()
            pendingNewCount = 0
            currentPage = 0
            hasMoreTopics = true
            scope.launch {
                feedListState.animateScrollToItem(0)
            }
        }
    }

    fun loadTopics(
        sort: FeedSortFilter = currentSort,
        categoryId: Int? = selectedCategoryId,
        tag: String? = selectedTag,
        topPeriod: TopPeriodFilter = currentTopPeriod,
        isManualRefresh: Boolean = false
    ) {
        loadMoreJob?.cancel()
        loadTopicsJob?.cancel()
        loadTopicsJob = scope.launch {
            val previousTopId = topics.firstOrNull()?.id
            val previousTopPostsCount = topics.firstOrNull()?.postsCount
            isLoadingTopics = true
            isLoadingMoreTopics = false
            loadMoreError = false
            feedError = null
            if (!isManualRefresh) {
                refreshUpToDateNotice = false
            }
            pendingNewTopics = emptyList()
            pendingNewCount = 0
            currentPage = 0
            hasMoreTopics = true
            try {
                val fetched = LinuxDoRepository.fetchTopics(
                    path = sort.apiPath,
                    categoryId = categoryId,
                    page = 0,
                    tag = tag,
                    topPeriod = if (sort == FeedSortFilter.TOP) topPeriod.apiPeriod else null
                )
                if (fetched.isNotEmpty()) {
                    val isUnchanged = isManualRefresh &&
                        previousTopId != null &&
                        fetched.first().id == previousTopId &&
                        fetched.first().postsCount == previousTopPostsCount
                    topics = fetched
                    hasMoreTopics = fetched.size >= 15
                    if (isUnchanged) {
                        refreshUpToDateNotice = true
                        scope.launch {
                            delay(2200L)
                            refreshUpToDateNotice = false
                        }
                    }
                    if (activeTopicSummary == null) {
                        val first = fetched.firstOrNull { !it.pinned } ?: fetched.first()
                        activeTopicSummary = first
                        scope.launch {
                            activeTopicDetail = LinuxDoRepository.fetchTopicDetail(first.id, first)
                        }
                    }
                    feedListState.scrollToItem(0)
                } else {
                    topics = emptyList()
                    hasMoreTopics = false
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Throwable) {
                if (topics.isEmpty()) {
                    feedError = "网络连接异常，请点击下方按钮手动重试"
                    hasMoreTopics = false
                }
            } finally {
                isLoadingTopics = false
            }
        }
    }

    fun loadNextPageTopics() {
        if (isLoadingTopics || isLoadingMoreTopics || !hasMoreTopics) return
        loadMoreJob = scope.launch {
            isLoadingMoreTopics = true
            loadMoreError = false
            try {
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
                    // 正常分页已到底：切换为“没有更多了”零度娘收尾提示
                    hasMoreTopics = false
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Throwable) {
                loadMoreError = true
            } finally {
                isLoadingMoreTopics = false
            }
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
            // 优先在后台静默兑换 OTP 令牌，免弹 WebView 页面以避免二次触发 Cloudflare Turnstile 验证
            val redeemed = LinuxDoRepository.redeemOtpSilently(otp)
            if (!redeemed) {
                activeWebLoginUrl = "https://linux.do/session/otp/$otp"
                return@LaunchedEffect
            }
        }
        val nowAuth = UserApiKeyAuthManager.isAuthorized()
        if (nowAuth && !wasAuthorized) {
            wasAuthorized = true
            activeWebLoginUrl = null
            showAuthSheet = false
            loadTopics(currentSort, selectedCategoryId, selectedTag, currentTopPeriod)
            syncUserProfile()
        } else {
            wasAuthorized = nowAuth
        }
    }

    var detailLoadJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun openTopic(topic: TopicSummary, targetPostNumber: Int? = null) {
        if (topic.id <= 0) return
        detailLoadJob?.cancel()
        activeTopicDetail = null
        activeTopicSummary = topic
        activeTargetPostNumber = targetPostNumber
        mobileShowingDetail = true
        detailLoadJob = scope.launch {
            isLoadingDetail = true
            try {
                val loaded = LinuxDoRepository.fetchTopicDetail(
                    topicId = topic.id,
                    summaryFallback = topic,
                    targetPostNumber = targetPostNumber
                )
                if (activeTopicSummary?.id == topic.id) activeTopicDetail = loaded
            } finally {
                if (activeTopicSummary?.id == topic.id) isLoadingDetail = false
            }
        }
    }

    fun openTopicById(
        topicId: Int,
        fallbackTitle: String = "话题 #$topicId",
        targetPostNumber: Int? = null
    ) {
        if (topicId <= 0) return
        val existing = topics.firstOrNull { it.id == topicId } ?: TopicSummary(
            id = topicId,
            title = fallbackTitle,
            postsCount = maxOf(1, targetPostNumber ?: 1),
            replyCount = 0,
            views = 0,
            likeCount = 0,
            categoryId = 4,
            categoryName = "技术讨论",
            categoryColorHex = "0E76BD",
            tags = emptyList(),
            lastPostedAt = "",
            originalPoster = TopicUser(id = 0, username = "linuxdo")
        )
        openTopic(existing, targetPostNumber)
    }

    // 站内链接原生智能路由：点击帖子内的话题、标签、分类、用户名片或 Connect 链接时直接在 App 原生界面打开，避免跳转 WebView 触发 Cloudflare 验证
    fun openSmartUrl(rawUrl: String) {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return
        if (trimmed.contains("web=1")) {
            activeWebLoginUrl = trimmed.replace("?web=1", "").replace("&web=1", "")
            return
        }
        val topicMatch = Regex(
            """^https?://(?:www\.)?linux\.do/t/(?:[^/?#]+/)?(\d+)(?:/(\d+))?(?:[?#].*)?$""",
            RegexOption.IGNORE_CASE
        ).find(trimmed)
        if (topicMatch != null) {
            val tid = topicMatch.groupValues[1].toIntOrNull()
            val pnum = topicMatch.groupValues.getOrNull(2)?.toIntOrNull()
            if (tid != null && tid > 0) {
                openTopicById(tid, "话题 #$tid", pnum)
                return
            }
        }
        val tagMatch = Regex(
            """^https?://(?:www\.)?linux\.do/tag/([^/?#]+)(?:[/?#].*)?$""",
            RegexOption.IGNORE_CASE
        ).find(trimmed)
        if (tagMatch != null) {
            val decodedTag = runCatching {
                java.net.URLDecoder.decode(tagMatch.groupValues[1], "UTF-8")
            }.getOrDefault(tagMatch.groupValues[1])
            if (decodedTag.isNotBlank()) {
                selectedTag = decodedTag
                mobileShowingDetail = false
                currentTab = MainTab.HOME
                loadTopics(currentSort, selectedCategoryId, decodedTag, currentTopPeriod)
                return
            }
        }
        val catMatch = Regex(
            """^https?://(?:www\.)?linux\.do/c/(?:[^/?#]+/)*(\d+)(?:[/?#].*)?$""",
            RegexOption.IGNORE_CASE
        ).find(trimmed)
        if (catMatch != null) {
            val cid = catMatch.groupValues[1].toIntOrNull()
            if (cid != null && cid > 0) {
                selectedCategoryId = cid
                mobileShowingDetail = false
                currentTab = MainTab.HOME
                loadTopics(currentSort, cid, selectedTag, currentTopPeriod)
                return
            }
        }
        val userMatch = Regex(
            """^https?://(?:www\.)?linux\.do/u/([^/?#]+)/?$""",
            RegexOption.IGNORE_CASE
        ).find(trimmed)
        if (userMatch != null) {
            val uname = userMatch.groupValues[1]
            if (uname.isNotBlank() && !uname.equals("account-created", ignoreCase = true)) {
                inspectedUsername = uname
                return
            }
        }
        if (trimmed.equals("https://connect.linux.do", ignoreCase = true) ||
            trimmed.equals("https://connect.linux.do/", ignoreCase = true)
        ) {
            showConnectSheet = true
            return
        }
        activeWebLoginUrl = trimmed
    }

    LaunchedEffect(Unit) {
        launch { LinuxDoRepository.syncSiteCategories() }
        loadTopics(FeedSortFilter.LATEST, null)
        syncUserProfile()
    }

    LaunchedEffect(isAuthorized) {
        while (isAuthorized) {
            delay(2000L)
            if (!do_.linux.lindo.core.connect.UserPresenceService.isForeground) continue
            val batch = ScreenTrackEngine.popNextSendableBatch() ?: continue
            var status: Int? = null
            try { status = LinuxDoRepository.sendTopicTimings(batch) }
            finally { ScreenTrackEngine.onBatchSendResult(batch, status) }
        }
    }

    // 实时新帖子推送检测（LIST-024：在首页静默检测是否有新发布或新回复的话题，有则在顶部弹出「查看 N 条新帖子动态」提示条）
    LaunchedEffect(currentTab, mobileShowingDetail, currentSort, selectedCategoryId, selectedTag, currentTopPeriod) {
        while (currentTab == MainTab.HOME && !mobileShowingDetail) {
            delay(18_000L)
            if (!do_.linux.lindo.core.connect.UserPresenceService.isForeground) continue
            if (!isLoadingTopics && !isLoadingMoreTopics && topics.isNotEmpty() && searchQuery.isBlank()) {
                val fresh = try {
                    LinuxDoRepository.fetchTopics(
                        path = currentSort.apiPath,
                        categoryId = selectedCategoryId,
                        page = 0,
                        tag = selectedTag,
                        topPeriod = if (currentSort == FeedSortFilter.TOP) currentTopPeriod.apiPeriod else null
                    )
                } catch (_: Throwable) {
                    emptyList()
                }
                if (fresh.isNotEmpty()) {
                    val currentMap = topics.associateBy { it.id }
                    val changedCount = fresh.filterNot { it.pinned }.count { item ->
                        val prev = currentMap[item.id]
                        prev == null || item.postsCount > prev.postsCount
                    }
                    if (changedCount > 0) {
                        val freshIds = fresh.map { it.id }.toSet()
                        val merged = fresh + topics.filterNot { it.id in freshIds }
                        pendingNewTopics = merged
                        pendingNewCount = changedCount
                    }
                }
            }
        }
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
            showReadHistorySheet ||
            showCloudDraftsSheet ||
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
                showReadHistorySheet -> {
                    showReadHistorySheet = false
                    true
                }
                showCloudDraftsSheet -> {
                    showCloudDraftsSheet = false
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
        LocalLinDoAnimations provides (enableAnimations && systemAnimationsEnabled),
        LocalLinDoDarkTheme provides isDarkTheme,
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

                    if (!isAuthorized) {
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
                            onToggleDark = { updateThemePref(!isDarkTheme) }
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
                                            loadMoreError = loadMoreError,
                                            errorMessage = feedError,
                                            refreshUpToDateNotice = refreshUpToDateNotice,
                                            pendingNewCount = pendingNewCount,
                                            enableAnimations = enableAnimations,
                                            fontScale = fontScale,
                                            isCompactFeed = isCompactFeed,
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
                                                currentTab = MainTab.MESSAGES
                                            },
                                            onToggleCompactMode = {
                                                updateCompactFeedMode(!isCompactFeed)
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
                                            onApplyPendingNewTopics = { applyPendingNewTopics() },
                                            onRefresh = { loadTopics(currentSort, selectedCategoryId, selectedTag, currentTopPeriod, isManualRefresh = true) },
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
                                            targetPostNumber = activeTargetPostNumber,
                                            initialReplyDraft = pendingReplyDraft,
                                            onConsumeReplyDraft = { pendingReplyDraft = null },
                                            isLoading = isLoadingDetail,
                                            enableAnimations = enableAnimations,
                                            showBackButton = false,
                                            onBack = {},
                                            onRefresh = { activeTopicSummary?.let { openTopic(it, activeTargetPostNumber) } },
                                            onDetailUpdated = { activeTopicDetail = it },
                                            onInspectBadge = { inspectedBadge = it },
                                            onInspectUser = { inspectedUsername = it },
                                            onSelectTag = { tag ->
                                                selectedTag = tag
                                                loadTopics(currentSort, selectedCategoryId, tag, currentTopPeriod)
                                            },
                                            onOpenWebUrl = { openSmartUrl(it) },
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
                                        onSelectTag = { tag ->
                                            selectedTag = tag
                                            currentTab = MainTab.HOME
                                            loadTopics(currentSort, selectedCategoryId, tag, currentTopPeriod)
                                        },
                                        onSelectTopic = { topic ->
                                            currentTab = MainTab.HOME
                                            openTopic(topic)
                                        },
                                        onOpenGlobalSearch = { showGlobalSearchSheet = true },
                                        onOpenWebUrl = { openSmartUrl(it) }
                                    )
                                }

                                MainTab.MESSAGES -> {
                                    CommunityMessagesScreen(
                                        isAuthorized = isAuthorized,
                                        username = fullUserProfile?.username ?: currentUser?.username.orEmpty(),
                                        onRequestLogin = { activeWebLoginUrl = "https://linux.do/login" },
                                        onSelectTopicId = { tid, title, postNum ->
                                            currentTab = MainTab.HOME
                                            openTopicById(tid, title, postNum)
                                        },
                                        onSelectMessageTopic = { topic ->
                                            currentTab = MainTab.HOME
                                            openTopic(topic)
                                        },
                                        onInspectUser = { uname -> inspectedUsername = uname },
                                        onInspectBadge = { badge -> inspectedBadge = badge },
                                        onUnreadCountSynced = { unread ->
                                            fullUserProfile = fullUserProfile?.copy(unreadNotifications = unread)
                                        },
                                        onOpenNewPrivateMessage = {
                                            pmInitialRecipient = null
                                            showPrivateMessagesSheet = true
                                        },
                                        onOpenWebUrl = { openSmartUrl(it) },
                                        onMarkedAllRead = {
                                            fullUserProfile = fullUserProfile?.copy(unreadNotifications = 0)
                                            syncUserProfile()
                                        }
                                    )
                                }

                                MainTab.PROFILE -> {
                                    CommunityProfileScreen(
                                        isDarkTheme = isDarkTheme,
                                        fontScale = fontScale,
                                        enableAnimations = enableAnimations,
                                        themeOption = themeOption,
                                        isCompactFeed = isCompactFeed,
                                        isAuthorized = isAuthorized,
                                        currentUser = currentUser,
                                        fullProfile = fullUserProfile,
                                        onRefreshProfile = { syncUserProfile() },
                                        onToggleDark = { updateThemePref(!isDarkTheme) },
                                        onChangeFontScale = { updateFontScalePref(it) },
                                        onToggleAnimations = { updateAnimationsPref(!enableAnimations) },
                                        onSelectThemeOption = { updateBrandThemeOption(it) },
                                        onToggleCompactFeed = { updateCompactFeedMode(!isCompactFeed) },
                                        onOpenNotifications = { currentTab = MainTab.MESSAGES },
                                        onOpenActivities = { showActivitiesSheet = true },
                                        onOpenBookmarks = { showBookmarksSheet = true },
                                        onOpenReadHistory = { showReadHistorySheet = true },
                                        onOpenCloudDrafts = { showCloudDraftsSheet = true },
                                        onOpenPrivateMessages = {
                                            pmInitialRecipient = null
                                            showPrivateMessagesSheet = true
                                        },
                                        onOpenEditProfile = { showEditProfileSheet = true },
                                        onInspectBadge = { inspectedBadge = it },
                                        onOpenConnectSheet = { showConnectSheet = true },
                                        onOpenNetworkSheet = { showNetworkSheet = true },
                                        onOpenAuthSheet = { showAuthSheet = true },
                                        onOpenWebUrl = { openSmartUrl(it) },
                                        onPrimaryWebLogin = {
                                            activeWebLoginUrl = "https://linux.do/login"
                                        },
                                        onLogout = {
                                            UserApiKeyAuthManager.logout()
                                            fullUserProfile = null
                                        }
                                    )
                                }
                            }
                        }
                    } else {
                        // 移动端布局（沉浸式全屏阅读或底部 5-Tab 理想型社区导航栏）
                        if (mobileShowingDetail && activeTopicSummary != null) {
                            CommunityTopicReaderPane(
                                summary = activeTopicSummary,
                                detail = activeTopicDetail,
                                targetPostNumber = activeTargetPostNumber,
                                initialReplyDraft = pendingReplyDraft,
                                onConsumeReplyDraft = { pendingReplyDraft = null },
                                isLoading = isLoadingDetail,
                                enableAnimations = enableAnimations,
                                showBackButton = true,
                                onBack = { mobileShowingDetail = false },
                                onRefresh = { activeTopicSummary?.let { openTopic(it, activeTargetPostNumber) } },
                                onDetailUpdated = { activeTopicDetail = it },
                                onInspectBadge = { inspectedBadge = it },
                                onInspectUser = { inspectedUsername = it },
                                onSelectTag = { tag ->
                                    mobileShowingDetail = false
                                    selectedTag = tag
                                    loadTopics(currentSort, selectedCategoryId, tag, currentTopPeriod)
                                },
                                onOpenWebUrl = { openSmartUrl(it) },
                                onRequestLogin = { activeWebLoginUrl = "https://linux.do/login" }
                            )
                        } else {
                            Box(modifier = Modifier.fillMaxSize()) {
                                Box(
                                    modifier = Modifier.fillMaxSize()
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
                                                loadMoreError = loadMoreError,
                                                errorMessage = feedError,
                                                refreshUpToDateNotice = refreshUpToDateNotice,
                                                pendingNewCount = pendingNewCount,
                                                enableAnimations = enableAnimations,
                                                fontScale = fontScale,
                                                isCompactFeed = isCompactFeed,
                                                isAuthorized = isAuthorized,
                                                currentUser = currentUser,
                                                unreadNotificationCount = fullUserProfile?.unreadNotifications ?: 0,
                                                onRequestLogin = {
                                                    activeWebLoginUrl = "https://linux.do/login"
                                                },
                                                onSearchChange = { searchQuery = it },
                                                onOpenGlobalSearch = { showGlobalSearchSheet = true },
                                                onOpenCreateTopic = {
                                                    if (isAuthorized) {
                                                        pendingNewTopicDraft = null
                                                        showCreateTopicSheet = true
                                                    } else activeWebLoginUrl = "https://linux.do/login"
                                                },
                                                onOpenNotifications = {
                                                    currentTab = MainTab.MESSAGES
                                                },
                                                onToggleCompactMode = {
                                                    updateCompactFeedMode(!isCompactFeed)
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
                                                onApplyPendingNewTopics = { applyPendingNewTopics() },
                                                onRefresh = { loadTopics(currentSort, selectedCategoryId, selectedTag, currentTopPeriod, isManualRefresh = true) },
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
                                                onSelectTag = { tag ->
                                                    selectedTag = tag
                                                    currentTab = MainTab.HOME
                                                    loadTopics(currentSort, selectedCategoryId, tag, currentTopPeriod)
                                                },
                                                onSelectTopic = { topic ->
                                                    openTopic(topic)
                                                },
                                                onOpenGlobalSearch = { showGlobalSearchSheet = true },
                                                onOpenWebUrl = { openSmartUrl(it) }
                                            )
                                        }

                                        MainTab.MESSAGES -> {
                                            CommunityMessagesScreen(
                                                isAuthorized = isAuthorized,
                                                username = fullUserProfile?.username ?: currentUser?.username.orEmpty(),
                                                onRequestLogin = { activeWebLoginUrl = "https://linux.do/login" },
                                                onSelectTopicId = { tid, title, postNum -> openTopicById(tid, title, postNum) },
                                                onSelectMessageTopic = { topic -> openTopic(topic) },
                                                onInspectUser = { uname -> inspectedUsername = uname },
                                                onInspectBadge = { badge -> inspectedBadge = badge },
                                                onUnreadCountSynced = { unread ->
                                                    fullUserProfile = fullUserProfile?.copy(unreadNotifications = unread)
                                                },
                                                onOpenNewPrivateMessage = {
                                                    pmInitialRecipient = null
                                                    showPrivateMessagesSheet = true
                                                },
                                                onOpenWebUrl = { openSmartUrl(it) },
                                                onMarkedAllRead = {
                                                    fullUserProfile = fullUserProfile?.copy(unreadNotifications = 0)
                                                    syncUserProfile()
                                                }
                                            )
                                        }

                                        MainTab.PROFILE -> {
                                            CommunityProfileScreen(
                                                isDarkTheme = isDarkTheme,
                                                fontScale = fontScale,
                                                enableAnimations = enableAnimations,
                                                themeOption = themeOption,
                                                isCompactFeed = isCompactFeed,
                                                isAuthorized = isAuthorized,
                                                currentUser = currentUser,
                                                fullProfile = fullUserProfile,
                                                onRefreshProfile = { syncUserProfile() },
                                                onToggleDark = { updateThemePref(!isDarkTheme) },
                                                onChangeFontScale = { updateFontScalePref(it) },
                                                onToggleAnimations = { updateAnimationsPref(!enableAnimations) },
                                                onSelectThemeOption = { updateBrandThemeOption(it) },
                                                onToggleCompactFeed = { updateCompactFeedMode(!isCompactFeed) },
                                                onOpenNotifications = { currentTab = MainTab.MESSAGES },
                                                onOpenActivities = { showActivitiesSheet = true },
                                                onOpenBookmarks = { showBookmarksSheet = true },
                                                onOpenReadHistory = { showReadHistorySheet = true },
                                                onOpenCloudDrafts = { showCloudDraftsSheet = true },
                                                onOpenPrivateMessages = {
                                                    pmInitialRecipient = null
                                                    showPrivateMessagesSheet = true
                                                },
                                                onOpenEditProfile = { showEditProfileSheet = true },
                                                onInspectBadge = { inspectedBadge = it },
                                                onOpenConnectSheet = { showConnectSheet = true },
                                                onOpenNetworkSheet = { showNetworkSheet = true },
                                                onOpenAuthSheet = { showAuthSheet = true },
                                                onOpenWebUrl = { openSmartUrl(it) },
                                                onPrimaryWebLogin = {
                                                    activeWebLoginUrl = "https://linux.do/login"
                                                },
                                                onLogout = {
                                                    UserApiKeyAuthManager.logout()
                                                    fullUserProfile = null
                                                }
                                            )
                                        }
                                    }
                                }

                                GoogleM3NavigationBar(
                                    currentTab = currentTab,
                                    unreadCount = fullUserProfile?.unreadNotifications ?: 0,
                                    onSelectTab = {
                                        mobileShowingDetail = false
                                        currentTab = it
                                    },
                                    onPublishClick = {
                                        pendingNewTopicDraft = null
                                        showCreateTopicSheet = true
                                    },
                                    modifier = Modifier.align(Alignment.BottomCenter)
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
                        onSelectResult = { tid, title, postNum ->
                            showGlobalSearchSheet = false
                            openTopicById(tid, title, postNum)
                        }
                    )
                }

                if (showCreateTopicSheet) {
                    CreateTopicModalSheet(
                        initialCategoryId = pendingNewTopicDraft?.categoryId ?: selectedCategoryId ?: 4,
                        initialDraft = pendingNewTopicDraft,
                        onDismiss = {
                            showCreateTopicSheet = false
                            pendingNewTopicDraft = null
                        },
                        onTopicCreated = { newTopicId, title ->
                            showCreateTopicSheet = false
                            pendingNewTopicDraft = null
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
                        onSelectNotification = { tid, title, postNum ->
                            showNotificationsSheet = false
                            if (tid > 0) openTopicById(tid, title, postNum)
                        },
                        onInspectUser = { uname ->
                            showNotificationsSheet = false
                            inspectedUsername = uname
                        }
                    )
                }

                if (showActivitiesSheet) {
                    val uname = fullUserProfile?.username ?: currentUser?.username.orEmpty()
                    UserActivitiesSheet(
                        username = uname,
                        onDismiss = { showActivitiesSheet = false },
                        onSelectActivity = { tid, title, postNum ->
                            showActivitiesSheet = false
                            openTopicById(tid, title, postNum)
                        }
                    )
                }

                if (showBookmarksSheet) {
                    val uname = fullUserProfile?.username ?: currentUser?.username.orEmpty()
                    UserBookmarksSheet(
                        username = uname,
                        onDismiss = { showBookmarksSheet = false },
                        onSelectBookmark = { tid, title, postNum ->
                            showBookmarksSheet = false
                            openTopicById(tid, title, postNum)
                        }
                    )
                }

                if (showReadHistorySheet) {
                    UserReadHistorySheet(
                        onDismiss = { showReadHistorySheet = false },
                        onSelectTopic = { topic ->
                            showReadHistorySheet = false
                            openTopic(topic)
                        }
                    )
                }

                if (showCloudDraftsSheet) {
                    UserCloudDraftsSheet(
                        onDismiss = { showCloudDraftsSheet = false },
                        onOpenDraftTopic = { draft ->
                            showCloudDraftsSheet = false
                            if (draft.topicId > 0) {
                                pendingReplyDraft = draft
                                openTopicById(draft.topicId, draft.title, draft.replyToPostNumber)
                            } else {
                                pendingNewTopicDraft = draft
                                showCreateTopicSheet = true
                            }
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
                        .background(if (selected) palette.activeChipBg else Color.Transparent),
                    contentAlignment = Alignment.Center
                ) {
                    if (tab == MainTab.HOME) {
                        Image(
                            painter = painterResource(Res.drawable.lindo_brand_mark),
                            contentDescription = tab.title,
                            modifier = Modifier.size(23.dp),
                            alpha = if (selected) 1f else 0.75f,
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Icon(
                            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                            contentDescription = tab.title,
                            modifier = Modifier.size(21.dp),
                            tint = if (selected) palette.activeChipText else palette.inkSecondary
                        )
                    }
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
                .clickable { onToggleTheme() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isDarkTheme) LinDoIcons.Sun else LinDoIcons.Moon,
                contentDescription = "深色模式",
                modifier = Modifier.size(19.dp),
                tint = palette.inkSecondary
            )
        }
    }
}

/**
 * 理想型社区 5-Tab 底部导航栏：
 * 遵循「去底色块、充足顶部留白、纯线条/主题色区分」设计语言：
 * `首页` · `发现` · `+ 发布`（纯主题色加号，无底色块） · `消息`（带未读角标） · `我的`
 */
@Composable
private fun GoogleM3NavigationBar(
    currentTab: MainTab,
    unreadCount: Int = 0,
    onSelectTab: (MainTab) -> Unit,
    onPublishClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val palette = LocalPalette.current
    val leftTabs = listOf(MainTab.HOME, MainTab.DISCOVER)
    val rightTabs = listOf(MainTab.MESSAGES, MainTab.PROFILE)

    val outerCapsuleShape = RoundedCornerShape(99.dp)
    val innerPillShape = RoundedCornerShape(99.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        palette.canvas.copy(alpha = 0.82f),
                        palette.canvas.copy(alpha = 0.96f)
                    )
                )
            )
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = if (palette.isDark) 20.dp else 14.dp,
                    shape = outerCapsuleShape,
                    ambientColor = Color.Black.copy(alpha = if (palette.isDark) 0.55f else 0.16f),
                    spotColor = Color.Black.copy(alpha = if (palette.isDark) 0.65f else 0.20f)
                )
                .clip(outerCapsuleShape)
                .background(
                    brush = Brush.verticalGradient(
                        colors = if (palette.isDark) {
                            listOf(
                                Color(0xFF1F2430).copy(alpha = 0.95f),
                                Color(0xFF151922).copy(alpha = 0.95f)
                            )
                        } else {
                            listOf(
                                Color(0xFFF1F4F9).copy(alpha = 0.96f),
                                Color(0xFFE8ECF3).copy(alpha = 0.94f)
                            )
                        }
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        colors = if (palette.isDark) {
                            listOf(
                                Color.White.copy(alpha = 0.20f),
                                Color.White.copy(alpha = 0.06f)
                            )
                        } else {
                            listOf(
                                Color.White,
                                Color(0xFFD5DCE6).copy(alpha = 0.80f)
                            )
                        }
                    ),
                    shape = outerCapsuleShape
                )
                .padding(horizontal = 6.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            leftTabs.forEach { tab ->
                val selected = currentTab == tab
                val tabModifier = if (selected) {
                    Modifier
                        .weight(1f)
                        .shadow(
                            elevation = if (palette.isDark) 6.dp else 4.dp,
                            shape = innerPillShape,
                            ambientColor = Color.Black.copy(alpha = if (palette.isDark) 0.35f else 0.10f),
                            spotColor = Color.Black.copy(alpha = if (palette.isDark) 0.40f else 0.12f)
                        )
                        .clip(innerPillShape)
                        .background(
                            brush = Brush.verticalGradient(
                                colors = if (palette.isDark) {
                                    listOf(
                                        Color(0xFF2C3344),
                                        Color(0xFF232938)
                                    )
                                } else {
                                    listOf(
                                        Color.White,
                                        Color(0xFFF9FAFC)
                                    )
                                }
                            )
                        )
                        .border(
                            width = 0.7.dp,
                            color = if (palette.isDark) Color.White.copy(alpha = 0.18f) else Color.White,
                            shape = innerPillShape
                        )
                } else {
                    Modifier
                        .weight(1f)
                        .clip(innerPillShape)
                }

                Column(
                    modifier = tabModifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onSelectTab(tab) }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier.size(width = 34.dp, height = 22.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (tab == MainTab.HOME) {
                            Image(
                                painter = painterResource(Res.drawable.lindo_brand_mark),
                                contentDescription = tab.title,
                                modifier = Modifier.size(22.dp),
                                alpha = if (selected) 1f else 0.72f,
                                contentScale = ContentScale.Fit
                            )
                        } else {
                            Icon(
                                imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                contentDescription = tab.title,
                                modifier = Modifier.size(20.dp),
                                tint = if (selected) palette.inkPrimary else palette.inkSecondary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = tab.title,
                        fontSize = 10.5.sp,
                        lineHeight = 12.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) palette.inkPrimary else palette.inkSecondary,
                        maxLines = 1
                    )
                }
            }

            // 中央「+ 发布」：不带任何背景色块，直接使用主题色（官方金/所选主题色）纯加号图标 + 文字
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(innerPillShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onPublishClick() }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier.size(width = 34.dp, height = 22.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = LinDoIcons.Plus,
                        contentDescription = "发布",
                        modifier = Modifier.size(22.dp),
                        tint = palette.tabIndicatorColor
                    )
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "发布",
                    fontSize = 10.5.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.tabIndicatorColor,
                    maxLines = 1
                )
            }

            rightTabs.forEach { tab ->
                val selected = currentTab == tab
                val tabModifier = if (selected) {
                    Modifier
                        .weight(1f)
                        .shadow(
                            elevation = if (palette.isDark) 6.dp else 4.dp,
                            shape = innerPillShape,
                            ambientColor = Color.Black.copy(alpha = if (palette.isDark) 0.35f else 0.10f),
                            spotColor = Color.Black.copy(alpha = if (palette.isDark) 0.40f else 0.12f)
                        )
                        .clip(innerPillShape)
                        .background(
                            brush = Brush.verticalGradient(
                                colors = if (palette.isDark) {
                                    listOf(
                                        Color(0xFF2C3344),
                                        Color(0xFF232938)
                                    )
                                } else {
                                    listOf(
                                        Color.White,
                                        Color(0xFFF9FAFC)
                                    )
                                }
                            )
                        )
                        .border(
                            width = 0.7.dp,
                            color = if (palette.isDark) Color.White.copy(alpha = 0.18f) else Color.White,
                            shape = innerPillShape
                        )
                } else {
                    Modifier
                        .weight(1f)
                        .clip(innerPillShape)
                }

                Column(
                    modifier = tabModifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onSelectTab(tab) }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier.size(width = 34.dp, height = 22.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                            contentDescription = tab.title,
                            modifier = Modifier.size(20.dp),
                            tint = if (selected) palette.inkPrimary else palette.inkSecondary
                        )
                        if (tab == MainTab.MESSAGES && unreadCount > 0) {
                            val badgeText = if (unreadCount > 99) "99+" else unreadCount.toString()
                            val isSingleDigit = badgeText.length == 1
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = if (isSingleDigit) (-1).dp else 2.dp, y = (-2).dp)
                                    .then(
                                        if (isSingleDigit) {
                                            Modifier.size(15.dp)
                                        } else {
                                            Modifier
                                                .height(15.dp)
                                                .widthIn(min = 19.dp)
                                        }
                                    )
                                    .clip(CircleShape)
                                    .background(palette.logoDoColor)
                                    .padding(horizontal = if (isSingleDigit) 0.dp else 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = badgeText,
                                    fontSize = 9.sp,
                                    lineHeight = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (palette.themeOption == BrandThemeOption.OFFICIAL_GOLD) Color(0xFF111827) else Color.White,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = tab.title,
                        fontSize = 10.5.sp,
                        lineHeight = 12.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) palette.inkPrimary else palette.inkSecondary,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * 理想型社区（Warm Minimalist Editorial）首页：
 * 1. 顶部 `LINUX DO · 新的理想型社区` 品牌栏（DO 随主题色联动：默认官方琥珀金，可选理想绯红/极客海蓝/竹林青绿）
 * 2. 柔和浅灰圆角搜索框（`#F4F5F7`）
 * 3. 2.5dp 短横线指示器频道切换栏 + 右侧一键 `卡片 / 紧凑` 双模式密度切换按钮
 * 4. 莫兰迪低饱和胶囊分类条 + 精巧卡片 / 紧凑列表双模式话题流
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
    loadMoreError: Boolean = false,
    errorMessage: String?,
    refreshUpToDateNotice: Boolean = false,
    pendingNewCount: Int = 0,
    enableAnimations: Boolean,
    fontScale: Float = 1.0f,
    isCompactFeed: Boolean = false,
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
    onApplyPendingNewTopics: () -> Unit = {},
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onSelectTopic: (TopicSummary) -> Unit,
    onAvatarClick: () -> Unit
) {
    val palette = LocalPalette.current
    val density = LocalDensity.current
    val isCompact = isCompactFeed || fontScale < 0.96f
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

    // 顶部下拉刷新手势状态（按屏幕密度换算为 dp，并设置 28.dp 防误触死区 + 悬浮指示器避免挤推列表首帖）
    var pullDownPx by remember { mutableStateOf(0f) }
    val pullDeadZonePx = with(density) { 28.dp.toPx() }
    val pullThresholdPx = with(density) { 92.dp.toPx() }
    val maxPullPx = with(density) { 148.dp.toPx() }

    // 兜底：若用户手指抬起未产生 Fling（例如轻点首条帖子微滑后松手），自动归零或触发刷新
    LaunchedEffect(listState.isScrollInProgress, isLoading) {
        if (!listState.isScrollInProgress && !isLoading && pullDownPx > 0f) {
            if (pullDownPx >= pullThresholdPx) {
                pullDownPx = 0f
                onRefresh()
            } else {
                pullDownPx = 0f
            }
        }
    }

    val pullNestedScroll = remember(isLoading, pullThresholdPx, maxPullPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (pullDownPx > 0f && available.y < 0f) {
                    val consumed = maxOf(available.y, -pullDownPx)
                    pullDownPx = (pullDownPx + consumed).coerceAtLeast(0f)
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (source == NestedScrollSource.UserInput &&
                    available.y > 0f &&
                    consumed.y == 0f &&
                    !isLoading &&
                    listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0
                ) {
                    pullDownPx = (pullDownPx + available.y * 0.45f).coerceAtMost(maxPullPx)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pullDownPx >= pullThresholdPx && !isLoading) {
                    pullDownPx = 0f
                    onRefresh()
                } else {
                    pullDownPx = 0f
                }
                return Velocity.Zero
            }
        }
    }

    // 滚动触底自动加载下一页（一旦发生网络异常 loadMoreError=true 立即停止自动重试，仅允许手动点击重试）
    val shouldLoadMore by remember(isLoading, isLoadingMore, hasMoreTopics, loadMoreError, searchQuery) {
        derivedStateOf {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            val lastIdx = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            total > 4 &&
                lastIdx >= total - 3 &&
                !isLoading &&
                !isLoadingMore &&
                hasMoreTopics &&
                !loadMoreError &&
                searchQuery.isBlank()
        }
    }
    LaunchedEffect(shouldLoadMore, regularTopics.size) {
        if (shouldLoadMore && !loadMoreError) {
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
            // 1. 理想型社区品牌顶栏：LINUX DO + 新的理想型社区 + 右侧搜索/通知/头像
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.cardSurface)
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onRefresh() },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LinDoBrandWordmark(
                            isDark = palette.isDark,
                            fontSizeSp = 22f,
                            iconSize = 34.dp,
                            showMotto = true
                        )
                    }

                    // 顶部快捷刷新
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable { onRefresh() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = LinDoIcons.Refresh,
                            contentDescription = "刷新最新话题",
                            modifier = Modifier.size(17.dp),
                            tint = palette.inkPrimary
                        )
                    }

                    Spacer(modifier = Modifier.width(2.dp))

                    // 顶部通知铃铛（带主题色未读小红点/琥珀点）
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable { onOpenNotifications() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = LinDoIcons.Bell,
                            contentDescription = "消息通知",
                            modifier = Modifier.size(19.dp),
                            tint = palette.inkPrimary
                        )
                        if (unreadNotificationCount > 0) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = 5.dp, end = 5.dp)
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(palette.tabIndicatorColor)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // 纯净圆形个人头像（不套 Linux.do 外圈，略微放大提升触控与视觉亲和力）
                    GoogleAccountRingAvatar(
                        letter = currentUser?.displayName?.takeIf { it.isNotBlank() }
                            ?: currentUser?.username
                            ?: "L",
                        avatarUrl = currentUser?.avatarTemplate,
                        animatedAvatar = currentUser?.animatedAvatar,
                        avatarSize = 34.dp,
                        onClick = onAvatarClick
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 2. 全圆角胶囊搜索入口（图标、占位符与胶囊框严格居中对齐，点击直接唤起全站实时搜索抽屉）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(palette.subtleSurface)
                        .clickable { onOpenGlobalSearch() }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = LinDoIcons.Search,
                        contentDescription = "全站搜索",
                        modifier = Modifier.size(15.dp),
                        tint = palette.inkTertiary
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            text = if (searchQuery.isEmpty()) "搜索话题、用户和你感兴趣的事" else searchQuery,
                            style = TextStyle(
                                fontSize = 13.sp,
                                lineHeight = 16.sp,
                                color = if (searchQuery.isEmpty()) palette.inkTertiary else palette.inkPrimary
                            ),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (searchQuery.isNotEmpty()) {
                        Icon(
                            imageVector = LinDoIcons.Close,
                            contentDescription = "清除",
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .clickable { onSearchChange("") }
                                .padding(1.dp),
                            tint = palette.inkSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 3. QQ音乐级大小字阶频道 Tab（选中 18sp ExtraBold，未选中 14.5sp Medium，底部 11x3dp 胶囊横条）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        FeedSortFilter.entries.forEach { sort ->
                            val selected = currentSort == sort
                            Column(
                                modifier = Modifier
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) { onSelectSort(sort) }
                                    .padding(vertical = 1.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = sort.label,
                                    fontSize = if (selected) 18.sp else 14.5.sp,
                                    fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium,
                                    letterSpacing = if (selected) (-0.3).sp else 0.sp,
                                    color = if (selected) palette.inkPrimary else palette.inkSecondary,
                                    maxLines = 1,
                                    softWrap = false
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Box(
                                    modifier = Modifier
                                        .width(12.dp)
                                        .height(3.dp)
                                        .clip(RoundedCornerShape(99.dp))
                                        .background(if (selected) palette.tabIndicatorColor else Color.Transparent)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // 右侧「卡片 / 紧凑」展示形式切换（仅保留形象直观的视图切换 Icon，去除多余文字）
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onToggleCompactMode() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isCompact) LinDoIcons.LayoutCompact else LinDoIcons.LayoutCards,
                            contentDescription = "切换列表展示形式",
                            modifier = Modifier.size(17.5.dp),
                            tint = if (isCompact) palette.activeChipText else palette.inkSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // 4. 纯文字分类筛选条（无底色块，直接通过字重与主题文字色区分）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GoogleM3FilterPill(
                        label = "全部",
                        dotColor = null,
                        selected = selectedCategoryId == null,
                        onClick = { onSelectCategory(null) }
                    )

                    LinuxDoRepository.categories.forEach { category ->
                        val (_, catTextColor) = resolveMorandiCategoryColors(category.name, palette.isDark)
                        GoogleM3FilterPill(
                            label = category.name,
                            dotColor = catTextColor,
                            selected = selectedCategoryId == category.id,
                            onClick = {
                                onSelectCategory(
                                    if (selectedCategoryId == category.id) null else category.id
                                )
                            }
                        )
                    }
                }

                // 官方 Top 热门榜时间维度切换器 + 当前激活的 #Tag 筛选条（无底色纯文字）
                if (currentSort == FeedSortFilter.TOP || selectedTag != null) {
                    Spacer(modifier = Modifier.height(5.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (selectedTag != null) {
                            Text(
                                text = "#$selectedTag ×",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = palette.activeChipText,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier
                                    .clickable { onSelectTag(null) }
                                    .padding(vertical = 2.dp)
                            )
                        }

                        if (currentSort == FeedSortFilter.TOP) {
                            TopPeriodFilter.entries.forEach { period ->
                                val selected = currentTopPeriod == period
                                Text(
                                    text = period.label,
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) palette.activeChipText else palette.inkTertiary,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier
                                        .clickable { onSelectTopPeriod(period) }
                                        .padding(vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(palette.hairline)
            )

            AnimatedVisibility(visible = pendingNewCount > 0 && !isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        modifier = Modifier
                            .height(32.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(palette.activeChipBg)
                            .clickable { onApplyPendingNewTopics() }
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.5.dp)
                                .clip(CircleShape)
                                .background(palette.tabIndicatorColor)
                        )
                        Text(
                            text = "有 $pendingNewCount 条新帖子动态 · 点击查看最新 ↑",
                            fontSize = 11.8.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.activeChipText,
                            maxLines = 1
                        )
                    }
                }
            }

            // 5. 话题流（外包独立 Box 承载下拉刷新手势与顶部悬浮零度娘提示，绝不挤压首帖布局导致点击误触）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .nestedScroll(pullNestedScroll)
            ) {
                if (isLoading && topics.isEmpty()) {
                    LinearShimmerTopicSkeletonList(isCompact = isCompact)
                } else if (topics.isEmpty() && !isLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        LinDoStatePanel(
                            state = if (errorMessage != null) LinDoMascotState.NetworkError else LinDoMascotState.Empty,
                            title = if (errorMessage != null) "暂时没能加载讨论" else "这里还没有匹配的讨论",
                            detail = errorMessage ?: "换个关键词，或看看其他板块吧",
                            onRetry = onRefresh,
                            actionLabel = if (errorMessage != null) "重新加载" else "刷新看看"
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 12.dp,
                            end = 12.dp,
                            top = 6.dp,
                            bottom = 128.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(if (isCompact) 5.dp else 8.dp)
                    ) {
                        // QQ音乐级无框置顶公告条（纯色差圆角容器，无机械外框线）
                        if (pinnedTopics.isNotEmpty()) {
                            item(key = "pinned_banner_group") {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(palette.cardSurface)
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    pinnedTopics.take(2).forEach { pinned ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(6.dp))
                                                .clickable { onSelectTopic(pinned) }
                                                .padding(vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "置顶",
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = palette.brandGold,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = pinned.title,
                                                fontSize = 12.8.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = palette.inkPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Icon(
                                                imageVector = LinDoIcons.ChevronRight,
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

                        // 底部波浪呼吸脉冲分页加载栏（触底无更多时展示零度娘“没有更多了”）
                        if (regularTopics.isNotEmpty()) {
                            item(key = "load_more_footer") {
                                LinearPulseLoadingFooter(
                                    isLoadingMore = isLoadingMore,
                                    hasMore = hasMoreTopics,
                                    loadedCount = regularTopics.size,
                                    isError = loadMoreError,
                                    onLoadMore = onLoadMore
                                )
                            }
                        }
                    }
                }

                // 顶部悬浮下拉刷新指示胶囊 & “已是最新，没有更多新内容了”零度娘反馈胶囊（Overlay 悬浮，不挤推列表）
                val showPullOverlay = pullDownPx > pullDeadZonePx || (isLoading && topics.isNotEmpty())
                if (showPullOverlay || refreshUpToDateNotice) {
                    val ready = pullDownPx >= pullThresholdPx
                    val mascotState = when {
                        isLoading -> LinDoMascotState.Refreshing
                        showPullOverlay -> LinDoMascotState.Refreshing
                        else -> LinDoMascotState.End
                    }
                    val pillText = when {
                        isLoading -> "刷新中"
                        showPullOverlay && ready -> "松开刷新"
                        showPullOverlay -> "下拉刷新"
                        else -> "已是最新"
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 8.dp, start = 12.dp, end = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            modifier = Modifier
                                .height(38.dp)
                                .clip(RoundedCornerShape(19.dp))
                                .background(palette.cardSurface.copy(alpha = 0.96f))
                                .border(0.5.dp, palette.hairline, RoundedCornerShape(19.dp))
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            LinDoMascot(
                                state = mascotState,
                                compact = true,
                                size = 22.dp,
                                animate = isLoading || showPullOverlay
                            )
                            Text(
                                text = pillText,
                                fontSize = 11.8.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.inkPrimary,
                                maxLines = 1
                            )
                            if (isLoading) {
                                LinDoWaveDots(color = palette.inkSecondary, dotSize = 3.5.dp)
                            }
                        }
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
    val selectedColor = dotColor ?: palette.activeChipText

    Row(
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = if (selected) 13.2.sp else 12.8.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) selectedColor else palette.inkSecondary,
            maxLines = 1,
            softWrap = false
        )
    }
}

/**
 * 理想型社区（Warm Minimalist Editorial）双模式话题卡片：
 * - 模式 A（精巧卡片模式 `!isCompact`，高度 ~115dp）：
 *   第一行：24dp 头像 + 作者名（长名自动 `...`）+ 暖金 `Lv.x` 徽标 + 时间 + 右侧莫兰迪低饱和版块胶囊
 *   第二行：15.5sp 加粗标题（最多 2 行）+ 12.8sp 单行浅灰摘要预览
 *   第三行：莫兰迪分类胶囊 / `#Tag` 标签 + 右侧线性心形赞数、评论数与阅读量
 * - 模式 B（紧凑列表模式 `isCompact`，高度 ~68dp）：
 *   第一行：莫兰迪版块胶囊 + 14.5sp 单行标题
 *   第二行：16dp 微头像 + 作者名 + `Lv.x` + `#Tag` + 右侧单行互动指标
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
    val catDotColor = remember(topic.categoryColorHex) {
        LinuxDoSiteRules.parseHexColor(topic.categoryColorHex)
    }
    val (_, morandiFg) = remember(topic.categoryName, palette.isDark) {
        resolveMorandiCategoryColors(topic.categoryName, palette.isDark)
    }
    val cornerRadius = if (isCompact) 12.dp else 15.dp
    val replies = maxOf(0, topic.postsCount - 1).coerceAtLeast(topic.replyCount)
    val cardBorderModifier = if (isSelected) {
        Modifier.border(1.dp, palette.tabIndicatorColor, RoundedCornerShape(cornerRadius))
    } else {
        Modifier
    }

    if (isCompact) {
        // 模式 B：紧凑高密模式（~66dp，无框纯白/深色圆角卡片，■ 版块名 · 标题 + 底部头像/作者/#Tag/互动数）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(cornerRadius))
                .background(palette.cardSurface)
                .then(cardBorderModifier)
                .clickable(onClick = onClick)
                .padding(horizontal = 13.dp, vertical = 9.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(6.5.dp)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(catDotColor)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = topic.categoryName,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = morandiFg,
                    maxLines = 1,
                    softWrap = false
                )

                Text(
                    text = " · ",
                    fontSize = 12.sp,
                    color = palette.inkTertiary
                )

                Text(
                    text = topic.title,
                    fontSize = 14.2.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.inkPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(5.dp))

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
                    size = 16.dp,
                    enableAnimations = false
                )

                Spacer(modifier = Modifier.width(5.dp))

                Text(
                    text = authorDisplay,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.inkSecondary,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 100.dp)
                )

                if (topic.tags.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        topic.tags.take(2).forEach { tag ->
                            Text(
                                text = "#$tag",
                                fontSize = 11.2.sp,
                                color = palette.inkTertiary,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable { onSelectTag(tag) }
                            )
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                Spacer(modifier = Modifier.width(6.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    if (topic.likeCount > 0) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.5.dp)
                        ) {
                            Icon(
                                imageVector = LinDoIcons.Heart,
                                contentDescription = null,
                                modifier = Modifier.size(11.5.dp),
                                tint = palette.inkTertiary
                            )
                            Text(
                                text = "${topic.likeCount}",
                                fontSize = 11.sp,
                                color = palette.inkTertiary,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.5.dp)
                    ) {
                        Icon(
                            imageVector = LinDoIcons.MessageCircle,
                            contentDescription = null,
                            modifier = Modifier.size(11.5.dp),
                            tint = palette.inkTertiary
                        )
                        Text(
                            text = "$replies",
                            fontSize = 11.sp,
                            color = palette.inkTertiary,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    if (topic.views > 0) {
                        Text(
                            text = formatCompactNumber(topic.views),
                            fontSize = 11.sp,
                            color = palette.inkTertiary,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    if (topic.lastPostedAt.isNotBlank()) {
                        Text(
                            text = topic.lastPostedAt,
                            fontSize = 11.sp,
                            color = palette.inkTertiary,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
    } else {
        // 模式 A：精巧卡片模式（无框纯色差卡片，1:1 还原 Linux.do Web 视觉元素：■ 版块色块 + 纯文字 Tag）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(cornerRadius))
                .background(palette.cardSurface)
                .then(cardBorderModifier)
                .clickable(onClick = onClick)
                .padding(horizontal = 15.dp, vertical = 13.dp)
        ) {
            // 第一行：头像 + 作者名 + 发布时间 | 右侧 Linux.do Web 标志性 `■ 版块名`
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SmartAvatar(
                        username = op.username,
                        avatarTemplate = op.avatarTemplate,
                        animatedAvatar = op.animatedAvatar,
                        primaryGroupName = op.primaryGroupName,
                        flair = null,
                        size = 22.dp,
                        enableAnimations = enableAnimations
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = authorDisplay,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.inkPrimary,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    if (topic.lastPostedAt.isNotBlank()) {
                        Text(
                            text = " · ${topic.lastPostedAt}",
                            fontSize = 11.5.sp,
                            color = palette.inkTertiary,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(catDotColor)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = topic.categoryName,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = morandiFg,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            Spacer(modifier = Modifier.height(7.dp))

            // 第二行：加粗清晰标题
            Text(
                text = topic.title,
                fontSize = 15.5.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 21.5.sp,
                letterSpacing = (-0.2).sp,
                color = palette.inkPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // 单行精炼摘要预览
            val cleanExcerpt = topic.excerpt?.trim()?.takeIf { it.isNotEmpty() && it != topic.title }
            if (cleanExcerpt != null) {
                Spacer(modifier = Modifier.height(3.5.dp))
                Text(
                    text = cleanExcerpt,
                    fontSize = 12.8.sp,
                    lineHeight = 18.sp,
                    color = palette.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 第三行：左侧自适应不截断 #Tag 标签组（超出宽度预算显示 +N，绝不半截切断单个词） + 右侧线性互动指标
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (topic.tags.isNotEmpty()) {
                    val fittedTags = remember(topic.tags) {
                        val result = mutableListOf<String>()
                        var usedUnits = 0
                        for (tag in topic.tags) {
                            val tagUnits = 2 + tag.sumOf { ch -> if (ch.code > 127) 2 else 1 }
                            if (result.isEmpty() || (result.size < 3 && usedUnits + tagUnits <= 24)) {
                                result.add(tag)
                                usedUnits += tagUnits
                            } else {
                                break
                            }
                        }
                        result
                    }
                    val remainingCount = (topic.tags.size - fittedTags.size).coerceAtLeast(0)
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        fittedTags.forEach { tag ->
                            Text(
                                text = "#$tag",
                                fontSize = 11.8.sp,
                                fontWeight = FontWeight.Normal,
                                color = palette.inkSecondary,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.clickable { onSelectTag(tag) }
                            )
                        }
                        if (remainingCount > 0) {
                            Text(
                                text = "+$remainingCount",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = palette.inkTertiary,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                Spacer(modifier = Modifier.width(8.dp))

                // 线性心形点赞数 + 评论数 + 浏览量
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            imageVector = LinDoIcons.Heart,
                            contentDescription = null,
                            modifier = Modifier.size(13.5.dp),
                            tint = palette.inkTertiary
                        )
                        Text(
                            text = "${topic.likeCount}",
                            fontSize = 11.5.sp,
                            color = palette.inkTertiary,
                            maxLines = 1,
                            softWrap = false
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            imageVector = LinDoIcons.MessageCircle,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = palette.inkTertiary
                        )
                        Text(
                            text = "$replies",
                            fontSize = 11.5.sp,
                            color = palette.inkTertiary,
                            maxLines = 1,
                            softWrap = false
                        )
                    }

                    if (topic.views > 0) {
                        Text(
                            text = "${formatCompactNumber(topic.views)} 浏览",
                            fontSize = 11.2.sp,
                            color = palette.inkTertiary,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
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
    targetPostNumber: Int? = null,
    initialReplyDraft: CloudDraftItem? = null,
    onConsumeReplyDraft: () -> Unit = {},
    isLoading: Boolean,
    enableAnimations: Boolean,
    showBackButton: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onDetailUpdated: (TopicDetailModel) -> Unit,
    onInspectBadge: (GrantedBadge) -> Unit,
    onInspectUser: (String) -> Unit,
    onSelectTag: (String) -> Unit = {},
    onOpenWebUrl: (String) -> Unit = {},
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
    var loadMorePostsError by remember(summary.id) { mutableStateOf(false) }
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

    val catColor = remember(detail?.categoryColorHex ?: summary.categoryColorHex) {
        LinuxDoSiteRules.parseHexColor(detail?.categoryColorHex ?: summary.categoryColorHex)
    }
    val op = summary.originalPoster
    val posts = detail?.posts.orEmpty()
    val effectiveOpUsername = remember(posts, op.username) {
        posts.firstOrNull { it.postNumber == 1 }?.username?.takeIf { it.isNotBlank() } ?: op.username
    }
    val streamIds = detail?.streamIds.orEmpty()
    val loadedIds = remember(posts) { posts.map { it.id }.toSet() }
    val remainingPostIds = remember(streamIds, loadedIds) {
        streamIds.filterNot { it in loadedIds }
    }
    val totalFloorCount = maxOf(summary.postsCount, streamIds.size, posts.size)

    val readerListState = rememberLazyListState()

    // 若从云端草稿恢复话题回复，自动填充并展开回复抽屉
    LaunchedEffect(initialReplyDraft, summary.id) {
        val draft = initialReplyDraft ?: return@LaunchedEffect
        if (draft.topicId == summary.id) {
            replyTargetPostNumber = draft.replyToPostNumber
            replyTargetUsername = null
            replyInitialQuote = draft.raw.ifBlank { draft.excerpt }
            showReplySheet = true
            onConsumeReplyDraft()
        }
    }

    // 若从通知、搜索、书签或动态指定了目标楼层，加载完成后自动精准滚动到对应楼层（若目标楼层即当前返回窗口第 0 项，保留 index=0 话题标题头可见）
    var jumpedToTargetFloor by remember(summary.id, targetPostNumber) { mutableStateOf(false) }
    LaunchedEffect(summary.id, targetPostNumber, posts.size) {
        val targetFloor = targetPostNumber
        if (!jumpedToTargetFloor && targetFloor != null && targetFloor > 1 && posts.isNotEmpty()) {
            val targetIdx = posts.indexOfFirst { it.postNumber == targetFloor }
            if (targetIdx >= 0) {
                jumpedToTargetFloor = true
                if (targetIdx == 0) {
                    readerListState.scrollToItem(0)
                } else {
                    readerListState.scrollToItem(targetIdx + 1)
                }
            }
        }
    }

    fun loadMoreFloorBatch(batchSize: Int = 20) {
        val currentDetail = detail ?: return
        if (isLoadingMorePosts || remainingPostIds.isEmpty()) return
        scope.launch {
            isLoadingMorePosts = true
            loadMorePostsError = false
            try {
                val batchIds = remainingPostIds.take(batchSize)
                val morePosts = LinuxDoRepository.fetchMoreTopicPosts(summary.id, batchIds)
                if (morePosts.isNotEmpty()) {
                    val merged = (currentDetail.posts + morePosts).distinctBy { it.id }
                    onDetailUpdated(currentDetail.copy(posts = merged))
                } else {
                    loadMorePostsError = true
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Throwable) {
                loadMorePostsError = true
            } finally {
                isLoadingMorePosts = false
            }
        }
    }

    // 当滚动接近当前已加载楼层末尾时，自动拉取下一批 20 条评论楼层（若发生网络错误则停止自动重试，仅允许手动点击重试）
    val lastVisibleFloorIdx by remember {
        derivedStateOf {
            readerListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        }
    }
    LaunchedEffect(lastVisibleFloorIdx, posts.size, remainingPostIds.size, isLoadingMorePosts, loadMorePostsError) {
        if (posts.isNotEmpty() && remainingPostIds.isNotEmpty() && !isLoadingMorePosts && !loadMorePostsError && lastVisibleFloorIdx >= posts.size - 2) {
            loadMoreFloorBatch(20)
        }
    }

    val latestReaderPosts by androidx.compose.runtime.rememberUpdatedState(posts)
    LaunchedEffect(summary.id) {
        ScreenTrackEngine.onTopicEntered(topicId = summary.id, initialVisiblePosts = emptySet())
        while (true) {
            delay(1000L)
            val visiblePostNums = readerListState.layoutInfo.visibleItemsInfo.mapNotNull { item ->
                latestReaderPosts.getOrNull(item.index - 1)?.postNumber
            }.toSet()
            ScreenTrackEngine.updateVisiblePosts(visiblePostNums)
            ScreenTrackEngine.tick()?.let { batch ->
                ScreenTrackEngine.consolidateBatch(batch.topicId, batch.topicTimeMs, batch.timingsMs)
            }
        }
    }
    LaunchedEffect(summary.id, readerListState) {
        androidx.compose.runtime.snapshotFlow {
            readerListState.firstVisibleItemIndex to readerListState.firstVisibleItemScrollOffset
        }.collect { ScreenTrackEngine.onUserScrolled() }
    }
    DisposableEffect(summary.id) {
        onDispose { ScreenTrackEngine.onTopicExited(summary.id) }
    }

    var showTopMoreMenu by remember(summary.id) { mutableStateOf(false) }
    var expandTopBarTitle by remember(summary.id) { mutableStateOf(false) }
    val showCollapsedTitleInTopBar by remember {
        derivedStateOf {
            readerListState.firstVisibleItemIndex > 0 || readerListState.firstVisibleItemScrollOffset > 220
        }
    }
    val allTopicTags = remember(detail?.tags, summary.tags) {
        detail?.tags?.ifEmpty { summary.tags } ?: summary.tags
    }
    val resolvedTitle = detail?.title ?: summary.title
    val resolvedCategoryName = detail?.categoryName ?: summary.categoryName

    fun toggleOnlyOpMode() {
        val nextState = !onlyOpFilter
        onlyOpFilter = nextState
        scope.launch {
            val filtered = LinuxDoRepository.fetchTopicDetail(
                topicId = summary.id,
                summaryFallback = summary,
                trackVisit = false,
                filterUsername = if (nextState) effectiveOpUsername else null
            )
            onDetailUpdated(filtered)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.cardSurface)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        // 1. 融合官方 Discourse Sticky Header 的自适应顶栏：
        // - 首屏未滚出标题区时：展示版块徽标与操作图标，不与下方完整大标题重复堆砌；
        // - 向下滚动滚出标题区后：返回按钮、右侧收藏/更多按钮与标题首行严格顶部对齐 (Alignment.Top)，长标题折行清晰整洁！
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .background(palette.cardSurface)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = if (showCollapsedTitleInTopBar) Alignment.Top else Alignment.CenterVertically
        ) {
            if (showBackButton) {
                TonalCircleIconButton(
                    icon = LinDoIcons.ArrowLeft,
                    contentDescription = "返回",
                    tint = palette.inkPrimary,
                    onClick = onBack
                )
                Spacer(modifier = Modifier.width(6.dp))
            }

            if (showCollapsedTitleInTopBar) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { expandTopBarTitle = !expandTopBarTitle }
                        .padding(top = 6.dp, bottom = 4.dp)
                ) {
                    Text(
                        text = resolvedTitle,
                        fontSize = 14.8.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.inkPrimary,
                        maxLines = if (expandTopBarTitle) 6 else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(catColor)
                    )
                    Text(
                        text = resolvedCategoryName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.inkPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (onlyOpFilter) {
                        Text(
                            text = "· 只看楼主",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.brandGold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            // 收藏书签图标（与左侧返回及标题首行严格顶部对齐）
            TonalCircleIconButton(
                icon = if (isBookmarked) LinDoIcons.BookmarkFilled else LinDoIcons.Bookmark,
                contentDescription = "收藏",
                tint = if (isBookmarked) palette.brandGold else palette.inkSecondary,
                onClick = {
                    if (!UserApiKeyAuthManager.isAuthorized()) {
                        onRequestLogin()
                        return@TonalCircleIconButton
                    }
                    val firstPostId = posts.firstOrNull()?.id ?: return@TonalCircleIconButton
                    val previousBookmark = isBookmarked
                    isBookmarked = !previousBookmark
                    scope.launch {
                        val ok = LinuxDoRepository.toggleTopicBookmark(firstPostId, summary.title)
                        if (!ok) {
                            isBookmarked = previousBookmark
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.width(2.dp))

            // 更多操作图标 (`⋯`)：收纳只看楼主、订阅、复制链接、打开原帖、刷新
            TonalCircleIconButton(
                icon = LinDoIcons.MoreHoriz,
                contentDescription = "更多操作",
                tint = if (showTopMoreMenu) palette.brandGold else palette.inkSecondary,
                onClick = { showTopMoreMenu = !showTopMoreMenu }
            )
        }

        // 点击右上角 `⋯` 展开的二级操作条（采用纯矢量图标 + 文字，告别生硬 Emoji）
        AnimatedVisibility(visible = showTopMoreMenu) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.subtleSurface)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.clickable {
                        showTopMoreMenu = false
                        toggleOnlyOpMode()
                    },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = LinDoIcons.User,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = if (onlyOpFilter) palette.brandGold else palette.inkSecondary
                    )
                    Text(
                        text = if (onlyOpFilter) "查看全部楼层" else "只看楼主",
                        fontSize = 12.2.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (onlyOpFilter) palette.brandGold else palette.inkPrimary
                    )
                }

                Row(
                    modifier = Modifier.clickable {
                        val prevLevel = notificationLevel
                        val nextLevel = when (notificationLevel) {
                            1 -> 3
                            3 -> 0
                            else -> 1
                        }
                        notificationLevel = nextLevel
                        scope.launch {
                            val ok = LinuxDoRepository.setTopicNotificationLevel(summary.id, nextLevel)
                            if (!ok) {
                                notificationLevel = prevLevel
                            }
                        }
                    },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = if (notificationLevel == 3) LinDoIcons.BellSelected else LinDoIcons.Bell,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = if (notificationLevel == 3) palette.brandGold else palette.inkSecondary
                    )
                    Text(
                        text = when (notificationLevel) {
                            3 -> "已关注话题"
                            0 -> "已免扰"
                            else -> "订阅话题"
                        },
                        fontSize = 12.2.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (notificationLevel == 3) palette.brandGold else palette.inkPrimary
                    )
                }

                Row(
                    modifier = Modifier.clickable {
                        clipboardManager.setText(AnnotatedString("https://linux.do/t/topic/${summary.id}"))
                        copiedShareLink = true
                    },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = LinDoIcons.Link,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = if (copiedShareLink) palette.brandGold else palette.inkSecondary
                    )
                    Text(
                        text = if (copiedShareLink) "已复制链接" else "复制链接",
                        fontSize = 12.2.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (copiedShareLink) palette.brandGold else palette.inkPrimary
                    )
                }

                Row(
                    modifier = Modifier.clickable {
                        showTopMoreMenu = false
                        onOpenWebUrl("https://linux.do/t/topic/${summary.id}?web=1")
                    },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = LinDoIcons.Globe,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = palette.inkSecondary
                    )
                    Text(
                        text = "网页原帖",
                        fontSize = 12.2.sp,
                        fontWeight = FontWeight.Medium,
                        color = palette.inkPrimary
                    )
                }

                Row(
                    modifier = Modifier.clickable {
                        showTopMoreMenu = false
                        onRefresh()
                    },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = LinDoIcons.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = palette.inkSecondary
                    )
                    Text(
                        text = "刷新",
                        fontSize = 12.2.sp,
                        fontWeight = FontWeight.Medium,
                        color = palette.inkPrimary
                    )
                }
            }
        }

        HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)

        LazyColumn(
            state = readerListState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(palette.cardSurface),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // 话题标题与版块/全量 Tag 头部区域（融合官方 Linux.do Web `#topic-title` 折行排版与话题筛选栏）
            item(key = "header_${summary.id}") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(palette.cardSurface)
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    // 完整多行话题大标题（不截断，字号与行高贴合官方 Discourse 移动端阅读比例）
                    Text(
                        text = resolvedTitle,
                        fontSize = 18.8f.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 26.2f.sp,
                        letterSpacing = (-0.25).sp,
                        color = palette.inkPrimary
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Linux.do 官方折行风格：`■ 版块名` + 自动折行 `#Tag` 标签组（无需横滑即可一眼看全所有板块与标签）
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(catColor.copy(alpha = if (palette.isDark) 0.16f else 0.10f))
                                .padding(horizontal = 7.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(RoundedCornerShape(1.8.dp))
                                    .background(catColor)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = resolvedCategoryName,
                                fontSize = 12.2.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = catColor,
                                maxLines = 1,
                                softWrap = false
                            )
                        }

                        allTopicTags.forEach { tag ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(palette.subtleSurface)
                                    .clickable { onSelectTag(tag) }
                                    .padding(horizontal = 7.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = "#$tag",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = palette.inkSecondary,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(11.dp))

                    // 话题统计概览与筛选栏（楼层数 · 浏览 · 点赞 | 只看楼主 · 直达末楼）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val replies = maxOf(0, totalFloorCount - 1)
                        Text(
                            text = "$replies 条回复 · ${formatCompactNumber(detail?.views ?: summary.views)} 次浏览 · ${detail?.likeCount ?: summary.likeCount} 个赞",
                            fontSize = 12.sp,
                            color = palette.inkTertiary,
                            modifier = Modifier.weight(1f)
                        )

                        Text(
                            text = if (onlyOpFilter) "全部楼层" else "只看楼主",
                            fontSize = 12.sp,
                            fontWeight = if (onlyOpFilter) FontWeight.Bold else FontWeight.Medium,
                            color = if (onlyOpFilter) palette.brandGold else palette.inkSecondary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { toggleOnlyOpMode() }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )

                        if (posts.size > 3) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "↓ 直达末楼",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = palette.inkSecondary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        scope.launch {
                                            readerListState.animateScrollToItem(posts.size)
                                        }
                                    }
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                HorizontalDivider(thickness = 0.6.dp, color = palette.hairline)
            }

            // 楼层卡片列表（#1 楼主 + #2..#N 全部评论楼层）
            if (isLoading && posts.isEmpty()) {
                item(key = "topic_loading_center") {
                    Box(
                        modifier = Modifier
                            .fillParentMaxHeight(0.72f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        LinDoLoadingIndicator(
                            color = palette.inkSecondary,
                            label = "加载中"
                        )
                    }
                }
            } else if (!isLoading && detail?.loadError != null) {
                item(key = "topic_load_error") {
                    Box(
                        modifier = Modifier
                            .fillParentMaxHeight(0.70f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        LinDoStatePanel(LinDoMascotState.NetworkError,
                            title = "暂时没能加载完整话题", detail = detail.loadError,
                            onRetry = {
                                scope.launch {
                                    onDetailUpdated(LinuxDoRepository.fetchTopicDetail(summary.id, summary))
                                }
                            })
                    }
                }
            }

            itemsIndexed(posts, key = { _, post -> post.id }) { _, post ->
                val repliedPost = remember(post.replyToPostNumber, posts) {
                    val targetNum = post.replyToPostNumber
                    if (targetNum != null && targetNum > 0) {
                        posts.firstOrNull { it.postNumber == targetNum }
                    } else null
                }
                CommunityPostFloor(
                    post = post,
                    repliedPost = repliedPost,
                    onOpenWebUrl = onOpenWebUrl,
                    originalUrl = "https://linux.do/t/topic/${summary.id}/${post.postNumber}",
                    opUsername = effectiveOpUsername,
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
                        val fallbackText = targetPost.raw?.takeIf { it.isNotBlank() } ?: targetPost.cookedHtml
                            .replace(Regex("<br\\s*/?>"), "\n")
                            .replace(Regex("</p>"), "\n\n")
                            .replace(Regex("<[^>]+>"), "")
                            .trim()
                        editPostRawText = fallbackText
                        if (targetPost.raw.isNullOrBlank()) {
                            scope.launch {
                                val trueRaw = LinuxDoRepository.fetchPostRawMarkdown(
                                    postId = targetPost.id,
                                    topicId = summary.id,
                                    postNumber = targetPost.postNumber
                                )
                                if (!trueRaw.isNullOrBlank() && editingPost?.id == targetPost.id) {
                                    editPostRawText = trueRaw
                                }
                            }
                        }
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

            // 剩余未加载楼层 / 已读完全部楼层的零度娘底部收尾条
            if (posts.isNotEmpty()) {
                item(key = "load_more_posts_${remainingPostIds.size}") {
                    LinearPulseLoadingFooter(
                        isLoadingMore = isLoadingMorePosts,
                        hasMore = remainingPostIds.isNotEmpty(),
                        loadedCount = posts.size,
                        isError = loadMorePostsError,
                        loadingText = "正在同步后续楼层 (${posts.size}/$totalFloorCount)",
                        moreText = "加载后续评论 (剩余 ${remainingPostIds.size} 楼 · 已载入 ${posts.size}/$totalFloorCount)",
                        errorText = "评论加载异常 · 点击手动重试",
                        doneText = "没有更多了 · 已看完本帖全部 ${posts.size} 楼",
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

        // 底部 QQ音乐级无框灵动回复栏 + 纯线性楼层电梯
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.cardSurface)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            HorizontalDivider(thickness = 0.4.dp, color = palette.hairline.copy(alpha = 0.6f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 全圆角胶囊回复框（无描边柔和底色）
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(palette.subtleSurface)
                        .clickable {
                            replyTargetPostNumber = null
                            replyTargetUsername = null
                            replyInitialQuote = ""
                            showReplySheet = true
                        }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = LinDoIcons.Edit,
                        contentDescription = "写回复",
                        modifier = Modifier.size(14.5.dp),
                        tint = palette.inkSecondary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (UserApiKeyAuthManager.isAuthorized()) {
                            "写下你的想法，参与讨论..."
                        } else {
                            "登录后参与讨论与回复..."
                        },
                        fontSize = 12.8.sp,
                        color = palette.inkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                // 纯文字+线性图标楼层电梯（不套底色块，遵循极简无底色设计语言）
                val currentVisibleNum = remember(lastVisibleFloorIdx, posts) {
                    val idx = (lastVisibleFloorIdx - 1).coerceIn(0, (posts.size - 1).coerceAtLeast(0))
                    posts.getOrNull(idx)?.postNumber ?: 1
                }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            showFloorJumpBar = !showFloorJumpBar
                        }
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$currentVisibleNum / $totalFloorCount",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (showFloorJumpBar) palette.brandGold else palette.inkPrimary,
                        maxLines = 1,
                        softWrap = false
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Icon(
                        imageVector = if (showFloorJumpBar) LinDoIcons.ChevronDown else LinDoIcons.ChevronUp,
                        contentDescription = "楼层电梯",
                        modifier = Modifier.size(15.dp),
                        tint = if (showFloorJumpBar) palette.brandGold else palette.inkSecondary
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
                LinDoMarkdownWorkbench(
                    rawValue = editPostRawText,
                    onRawValueChange = { editPostRawText = it },
                    placeholder = "编辑评论内容...",
                    draftKey = "edit_post_${target.id}",
                    replyToPostNumber = target.postNumber,
                    defaultEditorHeight = 165.dp
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
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(19.dp),
            tint = tint
        )
    }
}

/**
 * 1:1 还原 Linux.do Web 帖子/评论楼层 (`article.onscreen-post`)：
 * - 头部：左侧 40dp 原生圆形头像 (`SmartAvatar`) + 中间用户名/楼主/Web 行内徽章 + 右侧 `↰ #N` 回复跳转、相对时间与 `#楼号`
 * - 正文：干净通透的 AST 渲染（引用块、代码高亮与一键复制、配图、折叠块）
 * - 底部操作栏 (`nav.post-controls`)：
 *   - 左侧：**仅展示该楼层真实收到的表情回应 (`post.reactions`)**，绝不默认塞入未发生的假表情按钮！
 *   - 右侧：Web 风格清爽线性图标操作（`♡` 点赞、`😊+` 展开表情选择器、复制楼层链接、引用 `❝`、回复 `↩`）
 */
@Composable
private fun CommunityPostFloor(
    post: PostItemModel,
    repliedPost: PostItemModel? = null,
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
    onRequestLogin: () -> Unit,
    onOpenWebUrl: (String) -> Unit = {},
    originalUrl: String? = null
) {
    val palette = LocalPalette.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var myReactionId by remember(post.id, post.myReactionId, post.likedByMe) {
        mutableStateOf(post.myReactionId ?: if (post.likedByMe) "heart" else null)
    }
    var localReactions by remember(post.id, post.reactions, post.likeCount) {
        mutableStateOf(
            if (post.reactions.isNotEmpty()) {
                post.reactions
            } else if (post.likeCount > 0) {
                listOf(PostReactionItem(id = "heart", emoji = "❤️", count = post.likeCount))
            } else {
                emptyList()
            }
        )
    }
    var showReactionPopover by remember(post.id) { mutableStateOf(false) }
    var showFloorMoreMenu by remember(post.id) { mutableStateOf(false) }

    fun toggleReaction(reactionId: String, emojiChar: String) {
        if (!UserApiKeyAuthManager.isAuthorized()) {
            onRequestLogin()
            return
        }
        val prevReaction = myReactionId
        val currentList = localReactions.toMutableList()

        // 若之前已点过某个表情，先扣减旧表情计数
        if (prevReaction != null) {
            val idx = currentList.indexOfFirst { it.id == prevReaction }
            if (idx >= 0) {
                val item = currentList[idx]
                if (item.count <= 1) currentList.removeAt(idx)
                else currentList[idx] = item.copy(count = item.count - 1)
            }
        }

        if (prevReaction == reactionId) {
            // 取消当前表情
            myReactionId = null
        } else {
            // 新增或切换为新表情
            myReactionId = reactionId
            val idx = currentList.indexOfFirst { it.id == reactionId }
            if (idx >= 0) {
                val item = currentList[idx]
                currentList[idx] = item.copy(count = item.count + 1)
            } else {
                currentList.add(PostReactionItem(id = reactionId, emoji = emojiChar, count = 1))
            }
        }
        val prevReactionsSnapshot = localReactions.toList()
        localReactions = currentList
        showReactionPopover = false

        scope.launch {
            val ok = LinuxDoRepository.togglePostReaction(post.id, reactionId)
            if (!ok) {
                myReactionId = prevReaction
                localReactions = prevReactionsSnapshot
            }
        }
    }

    val isOp = post.isTopicOwner || post.username.equals(opUsername, ignoreCase = true)
    val isOwnPost = remember(post.username) {
        val me = UserApiKeyAuthManager.currentUser?.username
        !me.isNullOrBlank() && post.username.equals(me, ignoreCase = true)
    }
    val canDeleteThisPost = remember(isOwnPost, post.postNumber, post.canDelete) {
        LinuxDoSiteRules.canDeletePost(
            isOwnPost = isOwnPost,
            postNumber = post.postNumber,
            serverCanDelete = post.canDelete
        )
    }

    val targetReplyFloorNum = post.replyToPostNumber?.takeIf { it > 0 }
    val targetReplyUsername = remember(post.replyToUsername, repliedPost?.username) {
        post.replyToUsername?.takeIf { it.isNotBlank() } ?: repliedPost?.username
    }
    val targetReplyDisplayName = remember(repliedPost?.displayName, targetReplyUsername) {
        repliedPost?.displayName?.takeIf { it.isNotBlank() } ?: targetReplyUsername
    }
    val repliedSnippetText = remember(repliedPost?.cookedHtml) {
        repliedPost?.cookedHtml
            ?.replace(Regex("<aside[\\s\\S]*?</aside>"), "")
            ?.replace(Regex("<[^>]+>"), " ")
            ?.replace("&nbsp;", " ")
            ?.replace("&quot;", "\"")
            ?.replace("&amp;", "&")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()
    }

    val astNodes by androidx.compose.runtime.produceState(
        initialValue = DiscourseAstParser.getCached(post.id, post.cookedHtml).orEmpty(),
        post.id, post.cookedHtml
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            DiscourseAstParser.parseOrGetCached(post.id, post.cookedHtml)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.cardSurface)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        // 1. 楼层头部（1:1 对齐 Linux.do Web `.topic-meta-data`：40dp 头像 + 昵称/徽章/头衔 + 右侧回复指向/时间/#楼号）
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
                    size = 38.dp,
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
                        fontSize = 14.2.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.inkPrimary,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    if (isOp) {
                        Text(
                            text = "楼主",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.brandGold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }

                    if (post.badgesGranted.isNotEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            post.badgesGranted.take(3).forEach { badge ->
                                PostGrantedBadgeIcon(
                                    badge = badge,
                                    onClick = { onInspectBadge(badge) }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = "@${post.username}",
                        fontSize = 11.8.sp,
                        color = palette.inkTertiary,
                        maxLines = 1,
                        softWrap = false
                    )

                    if (!post.userTitle.isNullOrBlank()) {
                        Text(
                            text = "·",
                            fontSize = 11.5.sp,
                            color = palette.inkTertiary
                        )
                        UserTitleTag(
                            title = post.userTitle,
                            enableAnimations = enableAnimations
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 右上角：被回复对象与楼层跳转 + 时间 + `#楼号`
            Column(horizontalAlignment = Alignment.End) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (targetReplyFloorNum != null) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onJumpToFloorNumber(targetReplyFloorNum) }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                imageVector = LinDoIcons.Reply,
                                contentDescription = "跳转被回复楼层",
                                modifier = Modifier.size(12.5.dp),
                                tint = palette.brandGold
                            )
                            Text(
                                text = buildString {
                                    append("回复 #")
                                    append(targetReplyFloorNum)
                                    if (!targetReplyUsername.isNullOrBlank()) {
                                        append(" @")
                                        append(targetReplyUsername)
                                    }
                                },
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.inkSecondary,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    Text(
                        text = post.createdAtText,
                        fontSize = 11.8.sp,
                        color = palette.inkTertiary,
                        maxLines = 1,
                        softWrap = false
                    )

                    Text(
                        text = "#${post.postNumber}",
                        fontSize = 11.8.sp,
                        fontWeight = FontWeight.Medium,
                        color = palette.inkTertiary,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }

        // 1.5 清晰展现「谁回复谁 / 回复哪条评论」的归属上下文条（点击直达原楼层）
        if (targetReplyFloorNum != null && astNodes.firstOrNull() !is PostBlockNode.QuoteBlock) {
            Spacer(modifier = Modifier.height(9.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.subtleSurface)
                    .clickable { onJumpToFloorNumber(targetReplyFloorNum) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(2.5.dp)
                        .height(14.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(palette.brandGold)
                )
                Spacer(modifier = Modifier.width(7.dp))
                Text(
                    text = buildString {
                        append("回复 #")
                        append(targetReplyFloorNum)
                        if (!targetReplyDisplayName.isNullOrBlank()) {
                            append(" @")
                            append(targetReplyDisplayName)
                        }
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.inkPrimary,
                    maxLines = 1,
                    softWrap = false
                )
                if (repliedSnippetText.isNotBlank()) {
                    Text(
                        text = "：$repliedSnippetText",
                        fontSize = 12.sp,
                        color = palette.inkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }

        Spacer(modifier = Modifier.height(11.dp))

        PostRichContent(astNodes, palette, onOpenWebUrl, originalUrl)

        Spacer(modifier = Modifier.height(12.dp))

        // 按需展开的 Discourse 官方表情回应选择面板（放大尺寸，便于拇指点选 Boost/心心/火苗）
        AnimatedVisibility(visible = showReactionPopover) {
            Row(
                modifier = Modifier
                    .padding(bottom = 10.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(palette.subtleSurface)
                    .border(0.5.dp, palette.hairline, RoundedCornerShape(24.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val officialReactions = listOf(
                    "heart" to "❤️",
                    "rocket" to "🚀",
                    "+1" to "👍",
                    "confetti_ball" to "🎉",
                    "fire" to "🔥",
                    "laughing" to "😂",
                    "clap" to "👏",
                    "hugs" to "🤗"
                )
                officialReactions.forEach { (rId, emo) ->
                    val isMine = myReactionId == rId
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(if (isMine) palette.activeChipBg else Color.Transparent)
                            .clickable { toggleReaction(rId, emo) },
                        contentAlignment = Alignment.Center
                    ) {
                        PostReactionGlyph(
                            reactionId = rId,
                            emojiFallback = emo,
                            isSelected = isMine,
                            size = 22.dp
                        )
                    }
                }
            }
        }

        // 3. 楼层底部控制栏（放大表情回应胶囊与核心互动按钮，低频操作如编辑/删除/引用收纳进 `⋯` 更多菜单）
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：展示该楼层真实收到的表情回应（放大胶囊与图标尺寸）
            if (localReactions.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    localReactions.forEach { rItem ->
                        val isMine = myReactionId == rItem.id
                        Row(
                            modifier = Modifier
                                .height(32.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    if (isMine) palette.activeChipBg.copy(alpha = 0.72f)
                                    else palette.subtleSurface.copy(alpha = 0.78f)
                                )
                                .border(
                                    width = 0.6.dp,
                                    color = if (isMine) palette.brandGold else palette.hairline,
                                    shape = RoundedCornerShape(16.dp)
                                )
                                .clickable { toggleReaction(rItem.id, rItem.emoji) }
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            PostReactionGlyph(
                                reactionId = rItem.id,
                                emojiFallback = rItem.emoji,
                                isSelected = isMine,
                                size = 17.dp
                            )
                            Text(
                                text = "${rItem.count}",
                                fontSize = 13.sp,
                                fontWeight = if (isMine) FontWeight.Bold else FontWeight.SemiBold,
                                color = if (isMine) palette.brandGold else palette.inkSecondary
                            )
                        }
                    }
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }

            // 右侧：清爽大尺寸互动栏（点赞心形 · 表情/Boost · 回复 · ⋯更多操作收纳）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 点赞/取消点赞 (Heart)
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .clickable { toggleReaction("heart", "❤️") },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (myReactionId != null) LinDoIcons.HeartFilled else LinDoIcons.Heart,
                        contentDescription = "点赞",
                        modifier = Modifier.size(20.dp),
                        tint = if (myReactionId != null) Color(0xFFE11D48) else palette.inkSecondary
                    )
                }

                // 唤起多表情/Boost 选择器
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .clickable { showReactionPopover = !showReactionPopover },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = LinDoIcons.Smile,
                        contentDescription = "选择回应表情",
                        modifier = Modifier.size(20.dp),
                        tint = if (showReactionPopover) palette.brandGold else palette.inkSecondary
                    )
                }

                // 回复本楼
                Row(
                    modifier = Modifier
                        .height(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onReplyFloor(post) }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = LinDoIcons.Reply,
                        contentDescription = "回复",
                        modifier = Modifier.size(17.dp),
                        tint = palette.inkSecondary
                    )
                    Text(
                        text = "回复",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.inkSecondary
                    )
                }

                // 更多操作按钮 (`⋯`)：收纳引用、复制链接、编辑、删除等低频操作，不再平铺挤占底栏
                Box {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .clickable { showFloorMoreMenu = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = LinDoIcons.MoreHoriz,
                            contentDescription = "更多楼层操作",
                            modifier = Modifier.size(19.dp),
                            tint = if (showFloorMoreMenu) palette.brandGold else palette.inkSecondary
                        )
                    }

                    DropdownMenu(
                        expanded = showFloorMoreMenu,
                        onDismissRequest = { showFloorMoreMenu = false },
                        modifier = Modifier.background(palette.cardSurface)
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "引用此楼",
                                    fontSize = 13.5.sp,
                                    color = palette.inkPrimary
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = LinDoIcons.Quote,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = palette.inkSecondary
                                )
                            },
                            onClick = {
                                showFloorMoreMenu = false
                                onQuoteFloor(post)
                            }
                        )

                        if (!originalUrl.isNullOrBlank()) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "复制楼层链接",
                                        fontSize = 13.5.sp,
                                        color = palette.inkPrimary
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = LinDoIcons.Link,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = palette.inkSecondary
                                    )
                                },
                                onClick = {
                                    showFloorMoreMenu = false
                                    clipboardManager.setText(AnnotatedString(originalUrl))
                                }
                            )
                        }

                        if (isOwnPost && post.canEdit) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "编辑评论",
                                        fontSize = 13.5.sp,
                                        color = palette.inkPrimary
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = LinDoIcons.Edit,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = palette.inkSecondary
                                    )
                                },
                                onClick = {
                                    showFloorMoreMenu = false
                                    onEditPost(post)
                                }
                            )
                        }

                        if (canDeleteThisPost) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "删除评论",
                                        fontSize = 13.5.sp,
                                        color = Color(0xFFEF4444)
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = LinDoIcons.Trash,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = Color(0xFFEF4444)
                                    )
                                },
                                onClick = {
                                    showFloorMoreMenu = false
                                    onDeletePost(post)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
    HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
}

/**
 * 回复楼层表态图标渲染器：
 * 对高频核心表态（心心 `heart`、小火箭 `rocket`、点赞 `+1`、火苗 `fire`、礼花 `confetti_ball`）
 * 采用 1:1 比例精密矢量图标渲染，粗细均匀、色彩纯正，彻底解决系统字体 Emoji 比例失调或拉伸粗糙问题。
 */
@Composable
private fun PostReactionGlyph(
    reactionId: String,
    emojiFallback: String,
    isSelected: Boolean,
    size: Dp = 15.dp
) {
    val normalized = reactionId.lowercase().trim()
    when {
        normalized == "heart" || normalized == "love" || emojiFallback == "❤️" || emojiFallback == "❤" -> {
            Icon(
                imageVector = LinDoIcons.HeartFilled,
                contentDescription = "心心",
                modifier = Modifier.size(size),
                tint = Color(0xFFE11D48)
            )
        }
        normalized == "rocket" || normalized == "launch" || emojiFallback == "🚀" -> {
            Icon(
                imageVector = LinDoIcons.Rocket,
                contentDescription = "小火箭",
                modifier = Modifier.size(size),
                tint = if (isSelected) Color(0xFF2563EB) else Color(0xFF3B82F6)
            )
        }
        normalized == "+1" || normalized == "thumbsup" || normalized == "thumb_up" || emojiFallback == "👍" -> {
            Icon(
                imageVector = LinDoIcons.ThumbUp,
                contentDescription = "赞同",
                modifier = Modifier.size(size),
                tint = Color(0xFFF59E0B)
            )
        }
        normalized == "fire" || normalized == "flame" || emojiFallback == "🔥" -> {
            Icon(
                imageVector = LinDoIcons.Flame,
                contentDescription = "火热",
                modifier = Modifier.size(size),
                tint = Color(0xFFF97316)
            )
        }
        normalized == "confetti_ball" || normalized == "tada" || normalized == "party_popper" || emojiFallback == "🎉" || emojiFallback == "🎊" -> {
            Icon(
                imageVector = LinDoIcons.Sparkles,
                contentDescription = "庆祝",
                modifier = Modifier.size(size),
                tint = Color(0xFFEC4899)
            )
        }
        else -> {
            Text(
                text = emojiFallback,
                fontSize = (size.value * 0.88f).sp,
                lineHeight = size.value.sp
            )
        }
    }
}

/**
 * 理想型社区（Warm Minimalist Editorial）「发现」页：
 * - 顶栏：左侧「发现」大标题 + 右侧线性搜索图标
 * - 二级 Tab：`版块` | `标签` | `生态`（带 2.5dp 主题色短下划线）
 * - 「找到你的同好」：双列莫兰迪低饱和度版块卡片（浅蓝/浅绿/暖米圆角图标盒 + 标题 + 副标题）
 * - 「不止于讨论」：4×2 浅灰圆角方块（`#F4F5F7`）线性图标矩阵
 */
@Composable
private fun CommunityDiscoverScreen(
    hotTopics: List<TopicSummary>,
    onSelectCategory: (Int) -> Unit,
    onSelectTag: (String) -> Unit = {},
    onSelectTopic: (TopicSummary) -> Unit,
    onOpenGlobalSearch: () -> Unit = {},
    onOpenWebUrl: (String) -> Unit = {}
) {
    val palette = LocalPalette.current
    var subTab by remember { mutableStateOf(0) } // 0=版块, 1=标签, 2=生态
    val categoryRows = remember(LinuxDoRepository.categories.size) {
        LinuxDoRepository.categories.chunked(3)
    }

    val beyondDiscussionItems = remember {
        listOf(
            Triple("应用广场", LinDoIcons.AppsGrid, "https://linux.do/apps"),
            Triple("共建看板", LinDoIcons.KanbanBoard, "https://linux.do/boards"),
            Triple("近期活动", LinDoIcons.Calendar, "https://linux.do/upcoming-events"),
            Triple("社区 AI", LinDoIcons.Sparkles, "https://linux.do/discourse-ai/ai-bot/conversations"),
            Triple("群组虫洞", LinDoIcons.Users, "https://linux.do/g"),
            Triple("全站徽章", LinDoIcons.AwardBadge, "https://linux.do/badges"),
            Triple("积分排行", LinDoIcons.BarChartPoll, "https://linux.do/leaderboard"),
            Triple("福利抽奖", LinDoIcons.Gift, "https://cdk.linux.do/")
        )
    }

    val popularTags = remember {
        listOf(
            "人工智能" to "AI 大模型、Agent 与前沿探讨",
            "OpenAI" to "ChatGPT / GPT-4o / API 开发",
            "Claude" to "Anthropic Claude 编程与写作",
            "Docker" to "容器化部署与开发环境编排",
            "Linux" to "内核、发行版与服务器运维",
            "开源项目" to "GitHub 优质小而美开源推荐",
            "Cursor" to "AI 辅助编程 IDE 实践技巧",
            "DeepSeek" to "国产开源推理模型部署与应用",
            "VPS" to "全球云主机测评与线路优化",
            "自建服务" to "NAS / Homelab / 私有云搭建",
            "福利" to "社区公益分享与限免资源",
            "抽奖" to "L 站日常活跃抽奖与回馈"
        ).chunked(2)
    }

    val ecoPortalPairs = remember {
        listOf(
            Triple("Connect 中心", "保级 / 邮箱 / DeepLX", "https://connect.linux.do/"),
            Triple("Credit 积分", "积分流转与服务认证", "https://credit.linux.do/"),
            Triple("CDK 分发", "社区福利领取与发布", "https://cdk.linux.do/"),
            Triple("Lottery 抽奖", "官方楼层开奖工具", "https://lottery.linux.do/"),
            Triple("LD Hub", "生态 AI 与聚合中心", "https://hub.linux.do/"),
            Triple("社区 Wiki", "共建指南与文档库", "https://wiki.linux.do/"),
            Triple("IDC Flare", "官方云与主机生态站", "https://idcflare.com/"),
            Triple("关于与准则", "管理人员 / 条款 / 统计", "https://linux.do/about"),
            Triple("即时聊天", "频道 / 群聊 / 消息串", "https://linux.do/chat"),
            Triple("独立筛选器", "多条件表达式过滤", "https://linux.do/filter")
        ).chunked(2)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.canvas)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        // 顶栏：发现 + 搜索图标 + 二级 Tab (版块 | 标签 | 生态)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.cardSurface)
                .padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 0.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "发现",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.4).sp,
                    color = palette.inkPrimary,
                    modifier = Modifier.weight(1f)
                )

                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .clickable { onOpenGlobalSearch() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = LinDoIcons.Search,
                        contentDescription = "搜索",
                        modifier = Modifier.size(20.dp),
                        tint = palette.inkPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                val tabs = listOf("版块", "标签", "生态")
                tabs.forEachIndexed { idx, label ->
                    val selected = subTab == idx
                    Column(
                        modifier = Modifier
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { subTab = idx }
                            .padding(bottom = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = label,
                            fontSize = if (selected) 18.sp else 14.5.sp,
                            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium,
                            letterSpacing = if (selected) (-0.3).sp else 0.sp,
                            color = if (selected) palette.inkPrimary else palette.inkSecondary
                        )
                        Spacer(modifier = Modifier.height(5.dp))
                        Box(
                            modifier = Modifier
                                .width(12.dp)
                                .height(3.dp)
                                .clip(RoundedCornerShape(99.dp))
                                .background(if (selected) palette.tabIndicatorColor else Color.Transparent)
                        )
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 128.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (subTab == 0) {
                // 1. 正在热议置顶展示（打开「发现」第一眼即可看到全站实时热议话题）
                if (hotTopics.isNotEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(palette.cardSurface)
                                .padding(vertical = 12.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = LinDoIcons.Flame,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = palette.brandGold
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "正在热议",
                                    fontSize = 15.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = palette.inkPrimary,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "HOT",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = palette.brandGold
                                )
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
                                    Text(
                                        text = "${idx + 1}",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = if (idx < 3) palette.brandGold else palette.inkTertiary,
                                        modifier = Modifier.width(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
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
                                        fontSize = 11.2.sp,
                                        color = palette.inkTertiary
                                    )
                                }
                            }
                        }
                    }
                }

                // 2. 标题栏：社区版块导航 + 全部标签 >
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "社区版块",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.inkPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { subTab = 1 }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "全部标签",
                                fontSize = 12.2.sp,
                                color = palette.inkSecondary
                            )
                            Icon(
                                imageVector = LinDoIcons.ChevronRight,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = palette.inkTertiary
                            )
                        }
                    }
                }

                // 3. 紧凑三列版块网格（一行三个，轻巧克制不占屏）
                itemsIndexed(categoryRows) { _, rowCats ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowCats.forEach { category ->
                            CategoryBentoCard(
                                category = category,
                                modifier = Modifier.weight(1f),
                                onClick = { onSelectCategory(category.id) }
                            )
                        }
                        repeat(3 - rowCats.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }

                // 4. 不止于讨论（4x2 无框柔和圆角方块矩阵）
                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "不止于讨论",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.inkPrimary
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(palette.cardSurface)
                            .padding(vertical = 15.dp, horizontal = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        beyondDiscussionItems.chunked(4).forEach { rowItems ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                rowItems.forEach { (label, iconVec, url) ->
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { onOpenWebUrl(url) }
                                            .padding(vertical = 4.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(
                                            imageVector = iconVec,
                                            contentDescription = label,
                                            modifier = Modifier.size(22.dp),
                                            tint = palette.inkPrimary
                                        )
                                        Spacer(modifier = Modifier.height(7.dp))
                                        Text(
                                            text = label,
                                            fontSize = 11.8.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = palette.inkPrimary,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (subTab == 1) {
                // 标签页：点击任一标签直接筛选首页流
                item {
                    Text(
                        text = "热门社区标签",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.inkPrimary
                    )
                }

                itemsIndexed(popularTags) { _, pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        pair.forEach { (tag, desc) ->
                            DiscoverPortalCard(
                                title = "# $tag",
                                subtitle = desc,
                                modifier = Modifier.weight(1f),
                                onClick = { onSelectTag(tag) }
                            )
                        }
                        if (pair.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            } else {
                // 生态页：Linux.do 官方周边与服务
                item {
                    Text(
                        text = "LINUX DO 官方生态矩阵",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.inkPrimary
                    )
                }

                itemsIndexed(ecoPortalPairs) { _, pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        pair.forEach { (title, subtitle, url) ->
                            DiscoverPortalCard(
                                title = title,
                                subtitle = subtitle,
                                modifier = Modifier.weight(1f),
                                onClick = { onOpenWebUrl(url) }
                            )
                        }
                        if (pair.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoverPortalCard(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val palette = LocalPalette.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(palette.cardSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Bold,
                color = palette.inkPrimary,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = palette.inkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            imageVector = LinDoIcons.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = palette.inkTertiary
        )
    }
}

@Composable
private fun CategoryBentoCard(
    category: LinuxDoCategoryMeta,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val palette = LocalPalette.current
    val (_, morandiFg) = remember(category.name, palette.isDark) {
        resolveMorandiCategoryColors(category.name, palette.isDark)
    }
    val categoryIcon = remember(category.id) {
        when (category.id) {
            4 -> LinDoIcons.Code                   // 开发调优
            14 -> LinDoIcons.Layers                // 资源荟萃
            42 -> LinDoIcons.BookOpen              // 文档共建
            11 -> LinDoIcons.Coffee                // 搞七捻三
            36 -> LinDoIcons.Gift                  // 福利羊毛
            34 -> LinDoIcons.Sparkles              // 前沿快讯
            46 -> LinDoIcons.Globe                 // 人工智能 / 网络
            27 -> LinDoIcons.Users                 // 非我莫属
            32 -> LinDoIcons.BookOpen              // 读书成诗
            10 -> LinDoIcons.AppsGrid              // 跳蚤市场
            else -> LinDoIcons.Compass             // 其它动态版块
        }
    }

    Row(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.cardSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = categoryIcon,
            contentDescription = category.name,
            modifier = Modifier.size(16.dp),
            tint = morandiFg
        )

        Spacer(modifier = Modifier.width(7.dp))

        Text(
            text = category.name,
            fontSize = 12.8.sp,
            fontWeight = FontWeight.SemiBold,
            color = palette.inkPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 理想型社区（Warm Minimalist Editorial）「消息」页（对齐参考图 3）：
 * - 顶栏：左侧「消息」大标题 + 右侧「✓ 全部已读」按钮
 * - 二级 Tab：`通知` | `私信` | `聊天`（带 2.5dp 主题色短下划线）
 * - 4 大莫兰迪圆角方形快捷入口：`回复我的`（莫兰迪蓝）、`收到的赞`（莫兰迪粉）、`@ 我的`（莫兰迪暖金）、`系统通知`（莫兰迪森绿）
 * - 消息流列表：左侧莫兰迪色块头像 + 用户名与操作 + 右侧未读圆点 + `#F4F5F7` 浅灰圆角引用内容框 + 时间
 */
@Composable
private fun CommunityMessagesScreen(
    isAuthorized: Boolean,
    username: String,
    onRequestLogin: () -> Unit,
    onSelectTopicId: (Int, String, Int?) -> Unit,
    onSelectMessageTopic: (TopicSummary) -> Unit,
    onInspectUser: (String) -> Unit = {},
    onInspectBadge: (GrantedBadge) -> Unit = {},
    onUnreadCountSynced: (Int) -> Unit = {},
    onOpenNewPrivateMessage: () -> Unit,
    onOpenWebUrl: (String) -> Unit,
    onMarkedAllRead: () -> Unit
) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    var subTab by remember { mutableStateOf(0) } // 0=通知, 1=私信, 2=聊天
    var categoryFilter by remember { mutableStateOf(0) } // 0=全部, 1=回复我的, 2=收到的赞, 3=@我的, 4=系统通知
    var notifications by remember { mutableStateOf<List<UserNotificationItem>>(emptyList()) }
    var privateMessages by remember { mutableStateOf<List<TopicSummary>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reloadToken by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(false) }
    val notifListState = rememberLazyListState()
    val pmListState = rememberLazyListState()

    LaunchedEffect(isAuthorized, subTab, username, reloadToken) {
        if (!isAuthorized) return@LaunchedEffect
        isLoading = true
        loadError = null
        try {
            if (subTab == 0) {
                val loaded = LinuxDoRepository.fetchUserNotifications()
                notifications = loaded
                onUnreadCountSynced(loaded.count { !it.read })
                notifListState.scrollToItem(0)
            } else if (subTab == 1) {
                val effectiveUsername = username.ifBlank {
                    LinuxDoRepository.fetchCurrentUserProfile()?.username.orEmpty()
                }
                if (effectiveUsername.isNotBlank()) {
                    privateMessages = LinuxDoRepository.fetchPrivateMessages(effectiveUsername, sent = false)
                    pmListState.scrollToItem(0)
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            loadError = error.message ?: "加载失败，请重试"
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(categoryFilter, subTab) {
        if (subTab == 0) {
            notifListState.scrollToItem(0)
        }
    }

    val filteredNotifications = remember(notifications, categoryFilter) {
        when (categoryFilter) {
            1 -> notifications.filter { it.notificationType in setOf(2, 3, 9, 11, 38, 802) } // 回复/引用/链接
            2 -> notifications.filter { it.notificationType in setOf(5, 19, 25) }            // 点赞/合并赞/回应
            3 -> notifications.filter { it.notificationType in setOf(1, 15, 29, 32, 33) }    // @提及
            4 -> notifications.filter { it.notificationType !in setOf(1, 2, 3, 5, 9, 11, 15, 19, 25, 29, 32, 33, 38, 802) }
            else -> notifications
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.canvas)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        // 顶栏：消息 + 一键已读 ✓ + 二级 Tab (通知 | 私信 | 聊天)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.cardSurface)
                .padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 0.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "消息",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.4).sp,
                    color = palette.inkPrimary,
                    modifier = Modifier.weight(1f)
                )

                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .clickable {
                            if (!isAuthorized) {
                                onRequestLogin()
                            } else {
                                scope.launch {
                                    val ok = LinuxDoRepository.markNotificationsRead()
                                    if (ok) {
                                        notifications = notifications.map { it.copy(read = true) }
                                        onMarkedAllRead()
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = LinDoIcons.Check,
                        contentDescription = "全部标为已读",
                        modifier = Modifier.size(20.dp),
                        tint = palette.inkPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                val tabs = listOf("通知", "私信", "聊天")
                tabs.forEachIndexed { idx, label ->
                    val selected = subTab == idx
                    Column(
                        modifier = Modifier
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                subTab = idx
                                if (idx == 2) onOpenWebUrl("https://linux.do/chat")
                            }
                            .padding(bottom = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = label,
                            fontSize = if (selected) 18.sp else 14.5.sp,
                            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium,
                            letterSpacing = if (selected) (-0.3).sp else 0.sp,
                            color = if (selected) palette.inkPrimary else palette.inkSecondary
                        )
                        Spacer(modifier = Modifier.height(5.dp))
                        Box(
                            modifier = Modifier
                                .width(12.dp)
                                .height(3.dp)
                                .clip(RoundedCornerShape(99.dp))
                                .background(if (selected) palette.tabIndicatorColor else Color.Transparent)
                        )
                    }
                }
            }
        }

        // 4 大分类快捷筛选入口：回复我的 / 收到的赞 / @ 我的 / 系统通知（支持实时分类未读角标与激活态指示）
        val replyTypes = remember { setOf(2, 3, 9, 11, 38, 802) }
        val likeTypes = remember { setOf(5, 19, 25) }
        val mentionTypes = remember { setOf(1, 15, 29, 32, 33) }
        val allKnownFilterTypes = remember { replyTypes + likeTypes + mentionTypes }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.cardSurface)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val shortcutConfigs = listOf(
                Triple(1, "回复我的", LinDoIcons.MessageCircle) to (if (palette.isDark) Color(0xFF1E293B) to Color(0xFF93C5FD) else Color(0xFFEEF4FF) to Color(0xFF3B68B5)),
                Triple(2, "收到的赞", LinDoIcons.Heart) to (if (palette.isDark) Color(0xFF3B1D24) to Color(0xFFFCA5A5) else Color(0xFFFDF0F0) to Color(0xFFC94A4A)),
                Triple(3, "@ 我的", LinDoIcons.AtSign) to (if (palette.isDark) Color(0xFF3A2E16) to Color(0xFFFCD34D) else Color(0xFFFDF6E9) to Color(0xFFB88228)),
                Triple(4, "系统通知", LinDoIcons.Bell) to (if (palette.isDark) Color(0xFF143326) to Color(0xFF86EFAC) else Color(0xFFEDF7F2) to Color(0xFF2E7D5B))
            )

            shortcutConfigs.forEach { (meta, colors) ->
                val (code, label, iconVec) = meta
                val (bgTint, fg) = colors
                val active = categoryFilter == code
                val unreadInCat = remember(notifications, code) {
                    notifications.count { n ->
                        !n.read && when (code) {
                            1 -> n.notificationType in replyTypes
                            2 -> n.notificationType in likeTypes
                            3 -> n.notificationType in mentionTypes
                            else -> n.notificationType !in allKnownFilterTypes
                        }
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (active) bgTint else Color.Transparent)
                        .clickable {
                            subTab = 0
                            categoryFilter = if (categoryFilter == code) 0 else code
                        }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = iconVec,
                            contentDescription = label,
                            modifier = Modifier.size(22.dp),
                            tint = fg
                        )
                        if (unreadInCat > 0) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 9.dp, y = (-5).dp)
                                    .defaultMinSize(minWidth = 15.dp, minHeight = 15.dp)
                                    .height(15.dp)
                                    .clip(CircleShape)
                                    .background(palette.tabIndicatorColor)
                                    .padding(horizontal = if (unreadInCat > 9) 3.5.dp else 0.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (unreadInCat > 99) "99+" else unreadInCat.toString(),
                                    style = TextStyle(
                                        fontSize = 9.sp,
                                        lineHeight = 9.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFF1F1A12)
                                    ),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        color = if (active) fg else palette.inkPrimary,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(palette.subtleSurface)
        )

        if (subTab == 1) {
            // 私信列表与写私信入口
            if (isLoading && privateMessages.isEmpty()) {
                LinearShimmerTopicSkeletonList(isCompact = true)
            } else {
                LazyColumn(
                    state = pmListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 128.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item(key = "pm_header") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "近期私信会话",
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = palette.inkPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(palette.activeChipBg)
                                    .clickable { onOpenNewPrivateMessage() }
                                    .padding(horizontal = 11.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = "+ 写私信 / 全部",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = palette.activeChipText
                                )
                            }
                        }
                    }

                    if (loadError != null && privateMessages.isEmpty()) {
                        item(key = "pm_error") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                LinDoStatePanel(
                                    state = LinDoMascotState.NetworkError,
                                    title = "私信列表暂时无法加载",
                                    detail = loadError!!,
                                    onRetry = { reloadToken++ },
                                    actionLabel = "重新加载"
                                )
                            }
                        }
                    } else if (privateMessages.isEmpty() && !isLoading) {
                        item(key = "pm_empty") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 48.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                LinDoEmptyState(message = "暂无私信记录，点击右上角可发起私信")
                            }
                        }
                    } else if (privateMessages.isNotEmpty()) {
                        itemsIndexed(privateMessages, key = { _, m -> m.id }) { _, msg ->
                            CommunityTopicItem(
                                topic = msg,
                                isSelected = false,
                                isCompact = true,
                                enableAnimations = false,
                                onClick = { onSelectMessageTopic(msg) }
                            )
                        }
                        item(key = "pm_end_footer") {
                            LinearPulseLoadingFooter(
                                isLoadingMore = false,
                                hasMore = false,
                                loadedCount = privateMessages.size,
                                doneText = "没有更多私信了 · 零度娘已经翻到底啦",
                                onLoadMore = {}
                            )
                        }
                    }
                }
            }
        } else {
            // 通知流列表（顶部当前筛选概览栏 + 直观三层结构：谁做了什么 -> 回复内容 -> 原帖与楼层）
            val activeCategoryTitle = when (categoryFilter) {
                1 -> "回复我的"
                2 -> "收到的赞"
                3 -> "@ 我的"
                4 -> "系统通知"
                else -> "全部通知"
            }
            val unreadFilteredCount = remember(filteredNotifications) {
                filteredNotifications.count { !it.read }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.cardSurface)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = buildString {
                        append(activeCategoryTitle)
                        if (filteredNotifications.isNotEmpty()) {
                            append(" · ")
                            append(filteredNotifications.size)
                            append(" 条")
                        }
                        if (unreadFilteredCount > 0) {
                            append(" (")
                            append(unreadFilteredCount)
                            append(" 未读)")
                        }
                    },
                    fontSize = 12.2.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.inkSecondary,
                    modifier = Modifier.weight(1f)
                )

                if (categoryFilter != 0) {
                    Text(
                        text = "查看全部",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.brandGold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { categoryFilter = 0 }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { reloadToken++ }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        imageVector = LinDoIcons.Refresh,
                        contentDescription = "刷新通知",
                        modifier = Modifier.size(13.dp),
                        tint = palette.inkSecondary
                    )
                    Text(
                        text = "刷新",
                        fontSize = 11.8.sp,
                        color = palette.inkSecondary
                    )
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)

            if (isLoading && notifications.isEmpty()) {
                LinearShimmerTopicSkeletonList(isCompact = true)
            } else {
                LazyColumn(
                    state = notifListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(palette.cardSurface),
                    contentPadding = PaddingValues(top = 2.dp, bottom = 128.dp)
                ) {
                    if (loadError != null && notifications.isEmpty()) {
                        item(key = "notif_error") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                LinDoStatePanel(
                                    state = LinDoMascotState.NetworkError,
                                    title = "通知消息暂时无法加载",
                                    detail = loadError!!,
                                    onRetry = { reloadToken++ },
                                    actionLabel = "重新同步"
                                )
                            }
                        }
                    } else if (filteredNotifications.isEmpty() && !isLoading) {
                        item(key = "notif_empty") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 56.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                LinDoEmptyState(message = "暂无相关通知消息")
                            }
                        }
                    } else if (filteredNotifications.isNotEmpty()) {
                        itemsIndexed(filteredNotifications, key = { _, item -> item.id }) { _, item ->
                            val primaryActor = item.actingUsername.substringBefore("、").trim()
                            val (badgeIcon, badgeColor) = when (item.notificationType) {
                                5, 19, 25 -> LinDoIcons.HeartFilled to Color(0xFFE11D48)
                                2, 3, 6, 7, 9, 11, 38, 802 -> LinDoIcons.MessageCircle to Color(0xFF0E76BD)
                                1, 15, 29, 30, 31, 32, 33 -> LinDoIcons.AtSign to Color(0xFFD97706)
                                12 -> LinDoIcons.Sparkles to Color(0xFF059669)
                                else -> LinDoIcons.Bell to Color(0xFF059669)
                            }
                            val hasDistinctExcerpt = item.excerpt.isNotBlank() &&
                                !item.excerpt.equals(item.title, ignoreCase = true)
                            val contextTypeTag = when {
                                item.topicId > 0 -> "原帖"
                                item.badgeId > 0 -> "徽章"
                                item.notificationType in 29..33 -> "聊天"
                                else -> "详情"
                            }

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (!item.read) {
                                            palette.brandGold.copy(alpha = if (palette.isDark) 0.07f else 0.045f)
                                        } else {
                                            Color.Transparent
                                        }
                                    )
                                    .clickable {
                                        if (!item.read) {
                                            val updated = notifications.map {
                                                if (it.id == item.id) it.copy(read = true) else it
                                            }
                                            notifications = updated
                                            onUnreadCountSynced(updated.count { !it.read })
                                        }
                                        when {
                                            item.topicId > 0 -> {
                                                onSelectTopicId(item.topicId, item.title, item.postNumber)
                                            }
                                            item.badgeId > 0 -> {
                                                onInspectBadge(
                                                    GrantedBadge(
                                                        id = item.badgeId,
                                                        name = item.badgeName.ifBlank { item.title },
                                                        description = "LINUX DO 社区授予徽章"
                                                    )
                                                )
                                            }
                                            item.notificationType in 29..33 -> {
                                                val chatUrl = if (item.chatChannelId > 0) {
                                                    "https://linux.do/chat/c/-/${item.chatChannelId}"
                                                } else {
                                                    "https://linux.do/chat"
                                                }
                                                onOpenWebUrl(chatUrl)
                                            }
                                            primaryActor.isNotBlank() && primaryActor != "系统" -> {
                                                onInspectUser(primaryActor)
                                            }
                                        }
                                    }
                                    .padding(horizontal = 16.dp, vertical = 13.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    // 头像 + 右下角语义动作类型小角标（点赞红心 / 回复气泡 / @ 提及 / 徽章星标）
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clickable(
                                                enabled = primaryActor.isNotBlank() && primaryActor != "系统"
                                            ) {
                                                onInspectUser(primaryActor)
                                            }
                                    ) {
                                        SmartAvatar(
                                            username = item.actingUsername.ifBlank { "L" },
                                            avatarTemplate = item.actingAvatarTemplate,
                                            animatedAvatar = null,
                                            primaryGroupName = null,
                                            flair = null,
                                            size = 38.dp,
                                            enableAnimations = false
                                        )
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .size(16.dp)
                                                .clip(CircleShape)
                                                .background(badgeColor)
                                                .border(1.2.dp, palette.cardSurface, CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = badgeIcon,
                                                contentDescription = null,
                                                modifier = Modifier.size(9.5.dp),
                                                tint = Color.White
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        // 第一行：触发用户 + 具体楼层动作标签 | 右侧相对时间 + 未读圆点
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                modifier = Modifier.weight(1f),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = item.actingUsername,
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = palette.inkPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f, fill = false)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = item.typeLabel,
                                                    fontSize = 12.8.sp,
                                                    fontWeight = if (!item.read) FontWeight.SemiBold else FontWeight.Medium,
                                                    color = badgeColor,
                                                    maxLines = 1,
                                                    softWrap = false
                                                )
                                            }

                                            Spacer(modifier = Modifier.width(8.dp))

                                            if (item.createdAtText.isNotBlank()) {
                                                Text(
                                                    text = item.createdAtText,
                                                    fontSize = 11.5.sp,
                                                    color = palette.inkTertiary,
                                                    maxLines = 1,
                                                    softWrap = false
                                                )
                                            }
                                            if (!item.read) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Box(
                                                    modifier = Modifier
                                                        .size(7.dp)
                                                        .clip(CircleShape)
                                                        .background(palette.tabIndicatorColor)
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(7.dp))

                                        if (hasDistinctExcerpt) {
                                            // 有具体回复/提及正文：带左竖线突出显示对方说的话，再在下方展示「原帖」来源条
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .width(2.5.dp)
                                                        .height(18.dp)
                                                        .clip(RoundedCornerShape(2.dp))
                                                        .background(badgeColor.copy(alpha = 0.65f))
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = item.excerpt,
                                                    fontSize = 13.8.sp,
                                                    lineHeight = 19.5.sp,
                                                    fontWeight = if (!item.read) FontWeight.Medium else FontWeight.Normal,
                                                    color = palette.inkPrimary,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f)
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(7.dp))
                                        }

                                        // 目标话题 / 详情行（明确带有「原帖 / 徽章」前置标签与右侧 `#楼层 >`，一目了然）
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(palette.subtleSurface)
                                                .padding(horizontal = 10.dp, vertical = 7.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(palette.cardSurface)
                                                    .padding(horizontal = 5.dp, vertical = 1.5.dp)
                                            ) {
                                                Text(
                                                    text = contextTypeTag,
                                                    fontSize = 10.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = palette.inkTertiary,
                                                    maxLines = 1,
                                                    softWrap = false
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(7.dp))
                                            Text(
                                                text = item.title,
                                                fontSize = 12.8.sp,
                                                lineHeight = 18.sp,
                                                fontWeight = if (!item.read && !hasDistinctExcerpt) FontWeight.SemiBold else FontWeight.Normal,
                                                color = if (!item.read) palette.inkPrimary else palette.inkSecondary,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            if (item.topicId > 0) {
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = "#${item.postNumber}楼",
                                                    fontSize = 11.2.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = palette.inkTertiary,
                                                    maxLines = 1,
                                                    softWrap = false
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Icon(
                                                imageVector = LinDoIcons.ChevronRight,
                                                contentDescription = null,
                                                modifier = Modifier.size(13.dp),
                                                tint = palette.inkTertiary
                                            )
                                        }
                                    }
                                }
                            }

                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                thickness = 0.5.dp,
                                color = palette.hairline
                            )
                        }
                        item(key = "notifications_end_footer") {
                            LinearPulseLoadingFooter(
                                isLoadingMore = false,
                                hasMore = false,
                                loadedCount = filteredNotifications.size,
                                doneText = "没有更多通知了 · 零度娘已经翻到底啦",
                                onLoadMore = {}
                            )
                        }
                    }
                }
            }
        }
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
 * 理想型社区（Warm Minimalist Editorial）「我的」页（对齐参考图 4 + 主题色选择器）：
 * 1. 顶栏：「我的」大标题 + 右侧设置/刷新图标
 * 2. 紧凑个人身份卡片（无 Linux.do 外圈头像 + 真实等级 + `@username` + 编辑/切换/退出）
 * 3. 顶部 4 项线性图标快捷栏：`我的收藏` | `阅读历史` | `草稿箱` | `我的活动`
 * 4. 暖米金成长激励横幅卡：「每一次分享，都有回响 · 查看等级、徽章与成长记录」
 * 5. 主题色与双模式布局卡片：支持 `官方黑白黄（默认）` / `理想绯红` / `极客海蓝` / `竹林青绿` 一键切换，以及 `精巧卡片 / 紧凑列表` 密度切换
 * 6. 极简双行功能菜单列表：`我的社区`、`关注与粉丝`、`社区积分`、`订阅与权益`、`生态服务`、`创作与管理`、`关于社区`
 */
@Composable
private fun CommunityProfileScreen(
    isDarkTheme: Boolean,
    themeOption: BrandThemeOption = BrandThemeOption.OFFICIAL_GOLD,
    isCompactFeed: Boolean = false,
    fontScale: Float,
    enableAnimations: Boolean,
    isAuthorized: Boolean,
    currentUser: AuthenticatedUserProfile?,
    fullProfile: UserFullProfileSummary?,
    onRefreshProfile: () -> Unit,
    onSelectThemeOption: (BrandThemeOption) -> Unit = {},
    onToggleCompactFeed: () -> Unit = {},
    onToggleDark: () -> Unit,
    onChangeFontScale: (Float) -> Unit,
    onToggleAnimations: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenActivities: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenReadHistory: () -> Unit = {},
    onOpenCloudDrafts: () -> Unit = {},
    onOpenPrivateMessages: () -> Unit,
    onOpenEditProfile: () -> Unit,
    onInspectBadge: (GrantedBadge) -> Unit,
    onOpenConnectSheet: () -> Unit,
    onOpenNetworkSheet: () -> Unit,
    onOpenAuthSheet: () -> Unit,
    onOpenWebUrl: (String) -> Unit = {},
    onPrimaryWebLogin: () -> Unit,
    onLogout: () -> Unit
) {
    val palette = LocalPalette.current

    // 进入个人中心自动拉取最新服务端个人资料
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
        ?: displayUsername.ifBlank { "正在同步资料..." }
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.canvas)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        // 顶栏：我的 + 右上角同步资料 + 昼夜主题切换（小太阳/月亮）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.cardSurface)
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "我的",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.4).sp,
                color = palette.inkPrimary,
                modifier = Modifier.weight(1f)
            )

            if (isAuthorized) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .clickable { onRefreshProfile() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = LinDoIcons.Refresh,
                        contentDescription = "同步资料",
                        modifier = Modifier.size(18.dp),
                        tint = palette.inkSecondary
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
            }

            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .clickable { onToggleDark() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isDarkTheme) LinDoIcons.Sun else LinDoIcons.Moon,
                    contentDescription = if (isDarkTheme) "切换浅色模式" else "切换深色模式",
                    modifier = Modifier.size(19.dp),
                    tint = palette.inkPrimary
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 128.dp)
        ) {
            // 1. 个人信息（右侧仅保留无底色纯净编辑图标）与顶部 4 项线性图标快捷栏
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(palette.cardSurface)
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GoogleAccountRingAvatar(
                            letter = displayName.ifBlank { "L" },
                            avatarUrl = avatarUrl,
                            animatedAvatar = fullProfile?.animatedAvatar ?: currentUser?.animatedAvatar,
                            avatarSize = 54.dp,
                            onClick = if (isAuthorized) onOpenEditProfile else onPrimaryWebLogin
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = displayName,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = palette.inkPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (isAuthorized) {
                                    Text(
                                        text = trustLabel,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = palette.brandGold,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(3.dp))

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = if (displayUsername.isNotBlank()) "@$displayUsername" else "@linux.do",
                                    fontSize = 12.2.sp,
                                    color = palette.inkSecondary,
                                    maxLines = 1
                                )
                                if (!userTitle.isNullOrBlank()) {
                                    UserTitleTag(title = userTitle, enableAnimations = enableAnimations)
                                }
                            }
                        }

                        // 右侧无底色纯净编辑图标
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .clickable {
                                    if (isAuthorized) onOpenEditProfile() else onPrimaryWebLogin()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = LinDoIcons.Edit,
                                contentDescription = "编辑资料",
                                modifier = Modifier.size(18.dp),
                                tint = palette.inkSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 顶部 4 项线性图标快捷栏（我的收藏 | 阅读历史 | 草稿箱 | 我的活动）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        val topActions = listOf(
                            Triple("我的收藏", LinDoIcons.Bookmark) {
                                if (isAuthorized) onOpenBookmarks() else onPrimaryWebLogin()
                            },
                            Triple("阅读历史", LinDoIcons.HistoryClock) {
                                onOpenReadHistory()
                            },
                            Triple("草稿箱", LinDoIcons.FileText) {
                                if (isAuthorized) onOpenCloudDrafts() else onPrimaryWebLogin()
                            },
                            Triple("我的活动", LinDoIcons.Calendar) {
                                if (isAuthorized) onOpenActivities() else onPrimaryWebLogin()
                            }
                        )

                        topActions.forEach { (label, iconVec, action) ->
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { action() }
                                    .padding(vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = iconVec,
                                    contentDescription = label,
                                    modifier = Modifier.size(22.dp),
                                    tint = palette.inkPrimary
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = label,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = palette.inkPrimary,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .background(palette.subtleSurface)
                )
            }

            // 2. 社区功能与 Connect 看板分组菜单列表（专业克制、告别杂乱突兀卡片）
            item {
                val connectSub = if (isAuthorized && fullProfile != null) {
                    "访问 ${fullProfile.daysVisited} 天 · 获赞 ${fullProfile.likesReceived} · 保级与信任等级进度"
                } else {
                    "查看信任等级周期要求与活跃度指标"
                }
                val menuRows = listOf(
                    Triple("Connect 看板", connectSub, LinDoIcons.AwardBadge) to {
                        onOpenConnectSheet()
                    },
                    Triple("我的社区", "话题、回复、投票与互动记录", LinDoIcons.MessageCircle) to {
                        if (isAuthorized) onOpenActivities() else onPrimaryWebLogin()
                    },
                    Triple("关注与粉丝", "找到持续交流的同好", LinDoIcons.Users) to {
                        if (isAuthorized && displayUsername.isNotBlank()) {
                            onOpenWebUrl("https://linux.do/u/$displayUsername/follow")
                        } else {
                            onPrimaryWebLogin()
                        }
                    },
                    Triple("社区积分", "社区底板分与积分流转记录", LinDoIcons.BarChartPoll) to {
                        onOpenWebUrl("https://credit.linux.do/")
                    },
                    Triple("订阅与权益", "偏好设置、安全与账号管理", LinDoIcons.ShieldCheck) to {
                        if (isAuthorized) onOpenWebUrl("https://linux.do/my/preferences/account") else onPrimaryWebLogin()
                    },
                    Triple("创作与管理", "微应用开发、审核与管理入口", LinDoIcons.AppsGrid) to {
                        onOpenWebUrl("https://linux.do/apps")
                    }
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(palette.cardSurface)
                ) {
                    menuRows.forEachIndexed { idx, (meta, action) ->
                        val (title, subtitle, iconVec) = meta
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { action() }
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = iconVec,
                                contentDescription = title,
                                modifier = Modifier.size(20.dp),
                                tint = if (idx == 0) palette.brandGold else palette.inkSecondary
                            )

                            Spacer(modifier = Modifier.width(16.dp))

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

                            Icon(
                                imageVector = LinDoIcons.ChevronRight,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = palette.inkTertiary
                            )
                        }

                        if (idx < menuRows.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 20.dp),
                                thickness = 0.5.dp,
                                color = palette.hairline
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .background(palette.subtleSurface)
                )
            }

            // 3. 外观偏好与网络设置分组列表（将主题色、视图密度、字号、节点优选统一收敛为标准设置行）
            item {
                val nextScale = if (fontScale < 0.96f) 1.0f else if (fontScale < 1.05f) 1.10f else 0.92f
                val scaleLabel = if (fontScale < 0.96f) "小字号" else if (fontScale < 1.05f) "标准" else "大字号"

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(palette.cardSurface)
                ) {
                    // 3.1 主题配色（内联 4 色圆点选择器）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = LinDoIcons.Palette,
                            contentDescription = "主题配色",
                            modifier = Modifier.size(20.dp),
                            tint = palette.inkSecondary
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "主题配色",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.inkPrimary
                            )
                            Text(
                                text = themeOption.label,
                                fontSize = 11.8.sp,
                                color = palette.inkSecondary
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BrandThemeOption.entries.forEach { option ->
                                val selected = themeOption == option
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(option.swatchColor.copy(alpha = if (selected) 1f else 0.35f))
                                        .border(
                                            width = if (selected) 2.dp else 0.5.dp,
                                            color = if (selected) palette.inkPrimary else palette.hairline,
                                            shape = RoundedCornerShape(99.dp)
                                        )
                                        .clickable { onSelectThemeOption(option) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (selected) {
                                        Box(
                                            modifier = Modifier
                                                .size(7.dp)
                                                .clip(CircleShape)
                                                .background(Color.White)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 20.dp),
                        thickness = 0.5.dp,
                        color = palette.hairline
                    )

                    // 3.2 首页列表视图（精巧卡片 / 紧凑列表）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleCompactFeed() }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isCompactFeed) LinDoIcons.LayoutCompact else LinDoIcons.LayoutCards,
                            contentDescription = "列表布局",
                            modifier = Modifier.size(20.dp),
                            tint = palette.inkSecondary
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = "首页列表视图",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.inkPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = if (isCompactFeed) "紧凑列表" else "精巧卡片",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = palette.inkSecondary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = LinDoIcons.ChevronRight,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = palette.inkTertiary
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 20.dp),
                        thickness = 0.5.dp,
                        color = palette.hairline
                    )

                    // 3.3 阅读字号
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onChangeFontScale(nextScale) }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = LinDoIcons.Sliders,
                            contentDescription = "阅读字号",
                            modifier = Modifier.size(20.dp),
                            tint = palette.inkSecondary
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = "阅读字号",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.inkPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = scaleLabel,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = palette.inkSecondary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = LinDoIcons.ChevronRight,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = palette.inkTertiary
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 20.dp),
                        thickness = 0.5.dp,
                        color = palette.hairline
                    )

                    // 3.4 线路与节点优选
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenNetworkSheet() }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = LinDoIcons.WifiSpeed,
                            contentDescription = "线路与节点优选",
                            modifier = Modifier.size(20.dp),
                            tint = palette.inkSecondary
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "线路与节点优选",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.inkPrimary
                            )
                            Text(
                                text = "Cloudflare 边缘节点测速与连接诊断",
                                fontSize = 11.8.sp,
                                color = palette.inkSecondary
                            )
                        }
                        Icon(
                            imageVector = LinDoIcons.ChevronRight,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = palette.inkTertiary
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 20.dp),
                        thickness = 0.5.dp,
                        color = palette.hairline
                    )

                    // 3.5 关于社区
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenWebUrl("https://linux.do/about") }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = LinDoIcons.InfoCircle,
                            contentDescription = "关于社区",
                            modifier = Modifier.size(20.dp),
                            tint = palette.inkSecondary
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "关于社区",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.inkPrimary
                            )
                            Text(
                                text = "社区准则、隐私政策与服务条款",
                                fontSize = 11.8.sp,
                                color = palette.inkSecondary
                            )
                        }
                        Icon(
                            imageVector = LinDoIcons.ChevronRight,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = palette.inkTertiary
                        )
                    }
                }
            }

            // 4. 列表最底部：退出登录（符合专业移动端个人中心交互习惯）
            if (isAuthorized) {
                item {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(palette.cardSurface)
                            .clickable { onLogout() }
                            .padding(horizontal = 20.dp, vertical = 15.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = LinDoIcons.LogOut,
                            contentDescription = "退出登录",
                            modifier = Modifier.size(17.dp),
                            tint = Color(0xFFEF4444)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "退出当前账号",
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFEF4444)
                        )
                    }
                }
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
            Icon(
                imageVector = icon,
                contentDescription = title,
                modifier = Modifier.size(18.dp),
                tint = if (highlightBadge) palette.brandGold else palette.inkPrimary
            )
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                imageVector = LinDoIcons.ChevronRight,
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
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = palette.accent
        )

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
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = palette.accent
        )

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
                imageVector = LinDoIcons.ChevronRight,
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
private fun ConnectTrustLevelSheet(onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<ConnectTrustLevelReport?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    suspend fun refresh() {
        isLoading = true
        try { report = LinuxDoRepository.fetchConnectTrustLevelReport() }
        finally { isLoading = false }
    }
    LaunchedEffect(Unit) { refresh() }
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = palette.cardSurface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Connect", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    color = palette.inkPrimary, modifier = Modifier.weight(1f))
                TextButton(enabled = !isLoading, onClick = { scope.launch { refresh() } }) { Text("刷新") }
            }
            Text(report?.subtitle ?: "正在查询 Connect 官方页面", color = palette.inkSecondary,
                fontSize = 12.sp, lineHeight = 19.sp)
            Spacer(Modifier.height(12.dp))
            if (isLoading) LinDoLoadingIndicator(label = "零度娘正在同步 Connect")
            val current = report
            if (!isLoading && current?.metrics.isNullOrEmpty()) {
                LinDoStatePanel(LinDoMascotState.NetworkError, title = "暂未取得 Connect 数据",
                    detail = "当前未获得可核实的周期统计，请稍后重试。",
                    onRetry = { scope.launch { refresh() } })
            }
            current?.metrics?.forEach { metric ->
                Column(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(metric.label, modifier = Modifier.weight(1f), color = palette.inkPrimary, fontSize = 13.sp)
                        Spacer(Modifier.width(12.dp))
                        Text(if (current.isLiveConnectData && metric.targetValue > 0)
                            "${metric.displayCurrent} / ${metric.displayTarget}" else metric.displayCurrent,
                            color = palette.inkSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (current.isLiveConnectData && metric.targetValue > 0) {
                        Spacer(Modifier.height(7.dp))
                        Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(palette.subtleSurface)) {
                            Box(Modifier.fillMaxWidth(metric.progress).height(5.dp).background(palette.brandGold))
                        }
                    }
                }
            }
            if (current != null && current.metrics.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(if (current.isLiveConnectData) "来源：connect.linux.do · ${current.timePeriodDays} 天周期"
                    else "来源：LINUX DO 个人档案 · 累计数据，不能用于判断 Connect 保级",
                    color = palette.inkSecondary, fontSize = 11.sp, lineHeight = 18.sp)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

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
                CloudflareEdgeRacer.defaultCandidates.map { (ip, label) ->
                    EdgeCandidateResult(
                        ip = ip,
                        providerLabel = label,
                        rttMs = -1L,
                        traceVerified = false,
                        colo = ""
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
                            text = if (node.colo.isBlank()) node.providerLabel else "${node.providerLabel} (${node.colo})",
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
                            text = if (node.rttMs < 0) "未测试" else if (!node.traceVerified) "不可用" else "${node.rttMs} ms",
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
 * QQ音乐级极简无框账号登录与切换抽屉（全圆角胶囊按钮 + 纯线性图标）
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
    val primaryCapsuleBg = if (palette.isDark) palette.publishBtnBg else Color(0xFF111827)
    val primaryCapsuleFg = if (palette.isDark) palette.publishBtnContent else Color.White

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.cardSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            LinDoGlassAppIcon(tileSize = 56.dp)

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = if (isAuthorized) "Linux.do 账号会话" else "欢迎来到 LINUX DO",
                fontSize = 19.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.3).sp,
                color = palette.inkPrimary,
                maxLines = 1
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = if (isAuthorized && currentUser != null) {
                    "@${currentUser.username} · Lv.${currentUser.trustLevel} 连登与阅读时长自动同步中"
                } else {
                    "登录后自动同步 Connect 保级、阅读进度与专属分区"
                },
                fontSize = 12.5.sp,
                color = palette.inkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(22.dp))

            // 主力登录全圆角胶囊按钮（无杂乱内嵌框，居中纯粹排版）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(primaryCapsuleBg)
                    .clickable(onClick = onPrimaryWebLogin)
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(palette.brandGold)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isAuthorized) "重新验证或切换账号" else "账号一键登录",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = primaryCapsuleFg,
                    maxLines = 1
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 次级登录全圆角胶囊按钮（无描边柔和浅灰底）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(palette.subtleSurface)
                    .clickable(onClick = onBrowserOneClickAuth)
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = LinDoIcons.Globe,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = palette.inkPrimary
                )
                Spacer(modifier = Modifier.width(7.dp))
                Text(
                    text = "通过系统浏览器免密授权",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.inkPrimary,
                    maxLines = 1
                )
            }

            if (isAuthorized) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "退出当前账号",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.inkTertiary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onLogout)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun GoogleWelcomeLoginScreen(
    onPrimaryWebLogin: () -> Unit,
    onBrowserOneClickAuth: () -> Unit,
    onToggleDark: () -> Unit = {}
) {
    LinDoWelcomeScreen(
        isDark = LocalPalette.current.isDark,
        onPrimaryWebLogin = onPrimaryWebLogin,
        onBrowserOneClickAuth = onBrowserOneClickAuth,
        onToggleDark = onToggleDark
    )
}

/**
 * 官方功能 1：话题回复与楼层引用全屏级编辑器抽屉 (`POST /posts.json`)
 * 移动端优先设计：默认 94% 视口高度 (`skipPartiallyExpanded = true`)、大画布编辑区置顶、拇指区矢量图标工具栏置底
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var content by remember(initialRaw) { mutableStateOf(initialRaw) }
    var isSubmitting by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,
        containerColor = palette.cardSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.ime)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // 顶栏：左侧关闭 + 中部回复对象与话题标题 + 右侧发布按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .clickable { onDismiss() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = LinDoIcons.Close,
                        contentDescription = "取消",
                        modifier = Modifier.size(18.dp),
                        tint = palette.inkPrimary
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (replyToPostNumber != null) {
                            "回复 #$replyToPostNumber ${replyToUsername?.let { "@$it" } ?: ""}"
                        } else {
                            "发表回复"
                        },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.inkPrimary,
                        maxLines = 1
                    )
                    Text(
                        text = topicTitle,
                        fontSize = 11.5.sp,
                        color = palette.inkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                val canSend = content.trim().length >= LinuxDoSiteRules.MIN_POST_RAW_LENGTH && !isSubmitting
                Box(
                    modifier = Modifier
                        .height(34.dp)
                        .clip(RoundedCornerShape(17.dp))
                        .background(if (canSend) palette.publishBtnBg else palette.subtleSurface)
                        .clickable(enabled = canSend) {
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
                                    LinuxDoRepository.deleteCloudDraft("topic_$topicId", 0)
                                    onPostCreated(created)
                                } else {
                                    errorMsg = "发送失败，请确认已登录且回复内容不少于 ${LinuxDoSiteRules.MIN_POST_RAW_LENGTH} 个字符"
                                }
                            }
                        }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    LinDoBusyButtonLabel(busy = isSubmitting, busyLabel = "发送中...", label = "发布回复", color = if (canSend) palette.publishBtnContent else palette.inkTertiary)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
            Spacer(modifier = Modifier.height(10.dp))

            LinDoMarkdownWorkbench(
                rawValue = content,
                onRawValueChange = { content = it },
                placeholder = "写下你的回复...",
                draftKey = "topic_$topicId",
                replyToPostNumber = replyToPostNumber,
                defaultEditorHeight = 250.dp
            )

            if (errorMsg != null) {
                Spacer(modifier = Modifier.height(8.dp))
                LinDoFeedbackRow(message = errorMsg!!, state = LinDoMascotState.NetworkError)
            }

            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}

/**
 * 官方功能 2：发布全新话题全屏级工作台抽屉 (`POST /posts.json`)
 * 移动端优先重构：
 * 1. 默认 95% 视口全高展开 (`skipPartiallyExpanded = true`)，彻底告别局促小弹窗
 * 2. 顶部集成「版块选择 + 套用模板 + #标签快选/输入」，在标题上方一站式完成分类与标签配置
 * 3. 无边框沉浸式大字号标题输入 + 超大正文编辑画布在上方，矢量图标工具栏在底部拇指区
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateTopicModalSheet(
    initialCategoryId: Int,
    initialDraft: CloudDraftItem? = null,
    onDismiss: () -> Unit,
    onTopicCreated: (Int, String) -> Unit
) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var title by remember(initialDraft?.draftKey) {
        mutableStateOf(
            initialDraft?.title?.takeUnless { it == "未命名草稿" }.orEmpty()
        )
    }
    var rawContent by remember(initialDraft?.draftKey) {
        mutableStateOf(initialDraft?.raw?.ifBlank { initialDraft.excerpt }.orEmpty())
    }
    var appliedTemplate by remember { mutableStateOf<String?>(null) }
    var selectedTags by remember(initialDraft?.draftKey) {
        mutableStateOf(initialDraft?.tags.orEmpty())
    }
    var availableTags by remember {
        mutableStateOf(listOf("人工智能", "快问快答", "纯水", "病友", "抽奖"))
    }
    var categoryId by remember(initialDraft?.draftKey, initialCategoryId) {
        mutableStateOf(initialDraft?.categoryId ?: initialCategoryId)
    }
    var isSubmitting by remember { mutableStateOf(false) }
    var showLotteryCostConfirm by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    fun submitNewTopic() {
        scope.launch {
            isSubmitting = true
            errorMsg = null
            val tid = LinuxDoRepository.createTopic(
                title = title,
                raw = rawContent,
                categoryId = categoryId,
                tags = selectedTags
            )
            isSubmitting = false
            if (tid != null) {
                LinuxDoRepository.deleteCloudDraft(initialDraft?.draftKey ?: "new_topic", initialDraft?.sequence ?: 0)
                onTopicCreated(tid, title.trim())
            } else {
                errorMsg = "发布失败：标题或正文不少于 ${LinuxDoSiteRules.MIN_TOPIC_TITLE_LENGTH} 个字符，并请确认已登录"
            }
        }
    }

    if (showLotteryCostConfirm) {
        AlertDialog(
            onDismissRequest = { showLotteryCostConfirm = false },
            containerColor = palette.cardSurface,
            title = {
                Text(
                    text = "确认发布抽奖话题",
                    fontSize = 16.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.inkPrimary
                )
            },
            text = {
                Text(
                    text = "根据社区规则，携带「#${LinuxDoSiteRules.LOTTERY_TAG}」标签发布新话题将扣除 ${LinuxDoSiteRules.LOTTERY_TOPIC_COST_POINTS} 社区积分。是否确认立即发布？",
                    fontSize = 13.5.sp,
                    lineHeight = 20.sp,
                    color = palette.inkSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLotteryCostConfirm = false
                        submitNewTopic()
                    }
                ) {
                    Text("确认扣除并发布", fontWeight = FontWeight.Bold, color = palette.brandGold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLotteryCostConfirm = false }) {
                    Text("取消", color = palette.inkSecondary)
                }
            }
        )
    }

    // 切换版块时：
    // 1. 对齐 Discourse 官方逻辑，从 `/site.json` 自动拉取该版块真实 `topic_template`（若正文仍为空或为旧模板则自动填入，无需多余的手动「套用模板」按钮）
    // 2. 调用 `/tags/filter/search.json?filterForInput=true&categoryId=...` 拉取该版块允许选择的官方标签（Linux.do 禁止普通用户自造不存在的自定义标签）
    LaunchedEffect(categoryId) {
        val fetchedTags = LinuxDoRepository.fetchCategoryAllowedTags(categoryId)
        if (fetchedTags.isNotEmpty()) {
            availableTags = fetchedTags
        }
        val serverTpl = LinuxDoRepository.fetchCategoryTopicTemplate(categoryId)
        val trimmedCurrent = rawContent.trim()
        if (trimmedCurrent.isEmpty() || (appliedTemplate != null && trimmedCurrent == appliedTemplate!!.trim())) {
            if (!serverTpl.isNullOrBlank()) {
                rawContent = serverTpl
                appliedTemplate = serverTpl
            } else if (appliedTemplate != null && trimmedCurrent == appliedTemplate!!.trim()) {
                rawContent = ""
                appliedTemplate = null
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,
        containerColor = palette.cardSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.ime)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // 1. 顶部导航栏：取消按钮 + 标题 + 立即发布按钮（去除杂乱字数副标题）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .clickable { onDismiss() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = LinDoIcons.Close,
                        contentDescription = "关闭",
                        modifier = Modifier.size(18.dp),
                        tint = palette.inkPrimary
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Text(
                    text = "发布新话题",
                    fontSize = 16.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.inkPrimary,
                    modifier = Modifier.weight(1f)
                )

                val canPublish =
                    title.trim().length >= LinuxDoSiteRules.MIN_TOPIC_TITLE_LENGTH &&
                        rawContent.trim().length >= LinuxDoSiteRules.MIN_POST_RAW_LENGTH &&
                        !isSubmitting
                Box(
                    modifier = Modifier
                        .height(34.dp)
                        .clip(RoundedCornerShape(17.dp))
                        .background(if (canPublish) palette.publishBtnBg else palette.subtleSurface)
                        .clickable(enabled = canPublish) {
                            if (LinuxDoSiteRules.requiresTopicCostConfirmation(selectedTags)) {
                                showLotteryCostConfirm = true
                            } else {
                                submitNewTopic()
                            }
                        }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    LinDoBusyButtonLabel(busy = isSubmitting, busyLabel = "发布中...", label = "立即发布", color = if (canPublish) palette.publishBtnContent else palette.inkTertiary)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 2. 顶部元数据区（版块选择 + 官方允许标签横滑条）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "版块",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.inkTertiary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
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
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "标签",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.inkTertiary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        selectedTags.forEach { tag ->
                            Text(
                                text = "#$tag ×",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = palette.brandGold,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier
                                    .clickable { selectedTags = selectedTags - tag }
                                    .padding(vertical = 2.dp)
                            )
                        }

                        availableTags.filter { it !in selectedTags }.forEach { preset ->
                            Text(
                                text = "#$preset",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = palette.inkSecondary,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier
                                    .clickable {
                                        if (selectedTags.size < 5) {
                                            selectedTags = selectedTags + preset
                                        }
                                    }
                                    .padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 3. 沉浸式无边框大字号标题输入区（光标与 Placeholder 同轴对齐，文案极简清爽）
            val titleTextStyle = TextStyle(
                fontSize = 17.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.Bold,
                color = palette.inkPrimary
            )
            BasicTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                textStyle = titleTextStyle,
                cursorBrush = SolidColor(palette.brandGold),
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 2.dp, vertical = 6.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (title.isEmpty()) {
                            Text(
                                text = "输入话题标题...",
                                style = titleTextStyle.copy(color = palette.inkTertiary),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        innerTextField()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(6.dp))
            HorizontalDivider(thickness = 0.6.dp, color = palette.hairline)
            Spacer(modifier = Modifier.height(10.dp))

            // 4. 核心全功能 Markdown 工作台（结构规整的编辑画布在上，6×2 整齐矢量图标工具栏在下）
            LinDoMarkdownWorkbench(
                rawValue = rawContent,
                onRawValueChange = { rawContent = it },
                placeholder = "写下话题正文...",
                draftKey = "new_topic",
                draftTitle = title,
                draftCategoryId = categoryId,
                draftTags = selectedTags,
                defaultEditorHeight = 270.dp
            )

            if (errorMsg != null) {
                Spacer(modifier = Modifier.height(8.dp))
                LinDoFeedbackRow(message = errorMsg!!, state = LinDoMascotState.NetworkError)
            }

            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}

/**
 * ============================================================================
 * LinDo 全功能 Markdown & Discourse 扩展语法创作工作台 (`LinDoMarkdownWorkbench`)
 * 移动端优先重构：
 * 1. 顶部：极简操作栏（`编辑 / 预览` 切换 + 撤销/重做/中英空格）
 * 2. 中部核心：圆角规整正文编辑画布 (`defaultEditorHeight`)，单行清爽 Placeholder
 * 3. 底部拇指区：对称 6×2 等宽矢量图标工具栏（包含「链接 / 搜索社区帖子插入链接」）
 * ============================================================================
 */
private enum class WorkbenchViewMode(val label: String) {
    SOURCE("编辑"),
    PREVIEW("预览")
}

private enum class WorkbenchSubPanel {
    NONE,
    MORE_TOOLS,
    HEADINGS,
    CODE_LANG,
    CALLOUT,
    LINK,
    DETAILS_SPOILER,
    TABLE,
    POLL,
    EMOJI,
    MENTION
}

@Composable
private fun LinDoMarkdownWorkbench(
    rawValue: String,
    onRawValueChange: (String) -> Unit,
    placeholder: String,
    draftKey: String = "new_topic",
    draftTitle: String = "",
    draftCategoryId: Int? = null,
    draftTags: List<String> = emptyList(),
    replyToPostNumber: Int? = null,
    defaultEditorHeight: Dp = 260.dp
) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()

    // TextFieldValue 维护真实光标位置与选区
    var tfv by remember {
        mutableStateOf(TextFieldValue(text = rawValue, selection = TextRange(rawValue.length)))
    }
    LaunchedEffect(rawValue) {
        if (rawValue != tfv.text) {
            tfv = TextFieldValue(
                text = rawValue,
                selection = TextRange(rawValue.length.coerceAtLeast(0))
            )
        }
    }

    // Undo / Redo 历史栈
    val undoStack = remember { mutableStateListOf<TextFieldValue>() }
    val redoStack = remember { mutableStateListOf<TextFieldValue>() }

    fun updateTfv(newVal: TextFieldValue, recordHistory: Boolean = true) {
        if (recordHistory && newVal.text != tfv.text) {
            if (undoStack.size >= 40) undoStack.removeAt(0)
            undoStack.add(tfv)
            redoStack.clear()
        }
        tfv = newVal
        if (newVal.text != rawValue) {
            onRawValueChange(newVal.text)
        }
    }

    // 选区包裹辅助函数：若有选区则包裹选区，否则插入并将光标放在中间
    fun wrapSelection(prefix: String, suffix: String, placeholderText: String = "") {
        val text = tfv.text
        val start = tfv.selection.min.coerceIn(0, text.length)
        val end = tfv.selection.max.coerceIn(0, text.length)
        val selected = text.substring(start, end)
        val inner = selected.ifEmpty { placeholderText }
        val replacement = "$prefix$inner$suffix"
        val newText = text.replaceRange(start, end, replacement)
        val newCursorStart = start + prefix.length
        val newCursorEnd = newCursorStart + inner.length
        updateTfv(
            TextFieldValue(
                text = newText,
                selection = TextRange(newCursorStart, newCursorEnd)
            )
        )
    }

    // 行首前缀切换（用于标题 H1~H5、列表、引用）
    fun toggleLinePrefix(prefix: String) {
        val text = tfv.text
        val pos = tfv.selection.min.coerceIn(0, text.length)
        val lineStart = text.lastIndexOf('\n', (pos - 1).coerceAtLeast(0)).let { if (it == -1) 0 else it + 1 }
        val lineEnd = text.indexOf('\n', pos).let { if (it == -1) text.length else it }
        val currentLine = text.substring(lineStart, lineEnd)
        val updatedLine = if (currentLine.startsWith(prefix)) {
            currentLine.removePrefix(prefix)
        } else {
            "$prefix$currentLine"
        }
        val newText = text.replaceRange(lineStart, lineEnd, updatedLine)
        val newCursor = (lineStart + updatedLine.length).coerceIn(0, newText.length)
        updateTfv(TextFieldValue(text = newText, selection = TextRange(newCursor)))
    }

    // 在当前光标处直接插入块或短语
    fun insertAtCursor(snippet: String) {
        val text = tfv.text
        val start = tfv.selection.min.coerceIn(0, text.length)
        val end = tfv.selection.max.coerceIn(0, text.length)
        val newText = text.replaceRange(start, end, snippet)
        val newCursor = (start + snippet.length).coerceIn(0, newText.length)
        updateTfv(TextFieldValue(text = newText, selection = TextRange(newCursor)))
    }

    // 盘古之白：中英文/数字混排自动补齐空格
    fun optimizePanguSpacing() {
        val old = tfv.text
        if (old.isBlank()) return
        val optimized = old
            .replace(Regex("([\\u4e00-\\u9fa5])([A-Za-z0-9])"), "$1 $2")
            .replace(Regex("([A-Za-z0-9])([\\u4e00-\\u9fa5])"), "$1 $2")
        if (optimized != old) {
            updateTfv(TextFieldValue(text = optimized, selection = TextRange(optimized.length)))
        }
    }

    var viewMode by remember { mutableStateOf(WorkbenchViewMode.SOURCE) }
    var activeSubPanel by remember { mutableStateOf(WorkbenchSubPanel.NONE) }

    // 媒体上传状态与最近上传的图片（支持一键调整 100% / 75% / 50% 缩放）
    var isUploadingMedia by remember { mutableStateOf(false) }
    var uploadStatusText by remember { mutableStateOf<String?>(null) }
    var lastUploadedImage by remember { mutableStateOf<UploadedMediaResult?>(null) }

    // 云端草稿静默防抖保存 (`POST /drafts.json`)
    var draftSeq by remember { mutableStateOf(0) }
    LaunchedEffect(tfv.text, draftTitle, draftCategoryId) {
        if (tfv.text.trim().length < 4 && draftTitle.trim().length < 4) return@LaunchedEffect
        delay(2200L)
        val nextSeq = LinuxDoRepository.saveCloudDraft(
            draftKey = draftKey,
            sequence = draftSeq,
            replyRaw = tfv.text,
            title = draftTitle,
            categoryId = draftCategoryId,
            tags = draftTags,
            replyToPostNumber = replyToPostNumber
        )
        if (nextSeq != null) {
            draftSeq = nextSeq
        }
    }

    // 检测光标前是否正在输入 `@xxx` 自动唤起提及搜索
    var mentionSearchTerm by remember { mutableStateOf("") }
    var mentionCandidates by remember { mutableStateOf<List<MentionCandidate>>(emptyList()) }
    var isSearchingMentions by remember { mutableStateOf(false) }

    var mentionError by remember { mutableStateOf<String?>(null) }
    var mentionReloadToken by remember { mutableIntStateOf(0) }
    LaunchedEffect(mentionSearchTerm, activeSubPanel, mentionReloadToken) {
        if (activeSubPanel != WorkbenchSubPanel.MENTION) return@LaunchedEffect
        isSearchingMentions = true
        mentionError = null
        mentionCandidates = emptyList()
        try {
            delay(220L)
            mentionCandidates = LinuxDoRepository.searchMentionCandidates(mentionSearchTerm.ifBlank { "linux" })
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mentionError = error.message ?: "搜索失败，请重试"
        } finally {
            isSearchingMentions = false
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. 顶部清爽模式与辅助操作栏（仅保留「编辑 / 预览」切换，右侧展示字数、撤销重做与中英空格）
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            WorkbenchViewMode.entries.forEach { mode ->
                val selected = viewMode == mode
                Column(
                    modifier = Modifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { viewMode = mode }
                        .padding(vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = mode.label,
                        fontSize = if (selected) 14.2.sp else 13.sp,
                        fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium,
                        color = if (selected) palette.inkPrimary else palette.inkTertiary
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Box(
                        modifier = Modifier
                            .width(12.dp)
                            .height(2.5.dp)
                            .clip(RoundedCornerShape(99.dp))
                            .background(if (selected) palette.tabIndicatorColor else Color.Transparent)
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            if (tfv.text.isNotEmpty()) {
                Text(
                    text = "${tfv.text.length} 字",
                    fontSize = 11.5.sp,
                    color = palette.inkTertiary,
                    maxLines = 1
                )
            }

            // 撤销 / 重做（无边框纯图标）
            Icon(
                imageVector = LinDoIcons.Undo,
                contentDescription = "撤销",
                modifier = Modifier
                    .size(16.dp)
                    .clickable(enabled = undoStack.isNotEmpty()) {
                        if (undoStack.isNotEmpty()) {
                            val prev = undoStack.removeAt(undoStack.lastIndex)
                            redoStack.add(tfv)
                            updateTfv(prev, recordHistory = false)
                        }
                    },
                tint = if (undoStack.isNotEmpty()) palette.inkPrimary else palette.inkTertiary.copy(alpha = 0.35f)
            )

            Icon(
                imageVector = LinDoIcons.Redo,
                contentDescription = "重做",
                modifier = Modifier
                    .size(16.dp)
                    .clickable(enabled = redoStack.isNotEmpty()) {
                        if (redoStack.isNotEmpty()) {
                            val next = redoStack.removeAt(redoStack.lastIndex)
                            undoStack.add(tfv)
                            updateTfv(next, recordHistory = false)
                        }
                    },
                tint = if (redoStack.isNotEmpty()) palette.inkPrimary else palette.inkTertiary.copy(alpha = 0.35f)
            )

            if (tfv.text.isNotEmpty()) {
                Text(
                    text = "中英空格",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.inkSecondary,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.clickable { optimizePanguSpacing() }
                )
            }
        }

        // 2. 规整圆角编辑画布 / 实时 AST 预览区
        when (viewMode) {
            WorkbenchViewMode.SOURCE -> {
                WorkbenchSourceEditorBox(
                    tfv = tfv,
                    onValueChange = { newVal ->
                        updateTfv(newVal)
                        val cursor = newVal.selection.min
                        if (cursor > 0 && cursor <= newVal.text.length && newVal.text[cursor - 1] == '@') {
                            activeSubPanel = WorkbenchSubPanel.MENTION
                            mentionSearchTerm = ""
                        }
                    },
                    placeholder = placeholder,
                    height = defaultEditorHeight
                )
            }
            WorkbenchViewMode.PREVIEW -> {
                WorkbenchAstLivePreviewBox(
                    rawMarkdown = tfv.text,
                    height = defaultEditorHeight
                )
            }
        }

        // 3. 若刚刚上传了图片，提供 Discourse 原生图片缩放比例快捷条 (100% / 75% / 50%)
        val latestImg = lastUploadedImage
        if (latestImg != null || uploadStatusText != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.subtleSurface)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                LinDoFeedbackRow(
                    message = uploadStatusText ?: "图片已插入",
                    state = when {
                        isUploadingMedia -> LinDoMascotState.Loading
                        uploadStatusText?.contains("失败") == true -> LinDoMascotState.NetworkError
                        else -> LinDoMascotState.Success
                    }, modifier = Modifier.weight(1f)
                )
                if (latestImg != null && latestImg.isImage && latestImg.width > 0 && latestImg.height > 0) {
                    listOf("100%", "75%", "50%").forEach { pct ->
                        WorkbenchMiniActionChip(
                            label = "缩放 $pct",
                            onClick = {
                                val scaledTag = "![${latestImg.originalFilename}|${latestImg.width}x${latestImg.height}, $pct](${latestImg.shortUrl})"
                                val replaced = tfv.text.replace(latestImg.markdownSnippet, scaledTag)
                                updateTfv(tfv.copy(text = replaced))
                            }
                        )
                    }
                }
            }
        }

        // 4. 动态展开的子工具面板（紧贴底部工具栏上方）
        AnimatedVisibility(visible = activeSubPanel != WorkbenchSubPanel.NONE) {
            WorkbenchInteractiveSubPanel(
                subPanel = activeSubPanel,
                mentionSearchTerm = mentionSearchTerm,
                onMentionSearchTermChange = { mentionSearchTerm = it },
                mentionCandidates = mentionCandidates,
                isSearchingMentions = isSearchingMentions,
                mentionError = mentionError,
                onRetryMention = { mentionReloadToken++ },
                onToggleLinePrefix = { prefix ->
                    toggleLinePrefix(prefix)
                    activeSubPanel = WorkbenchSubPanel.NONE
                },
                onWrapSelection = { prefix, suffix, ph ->
                    wrapSelection(prefix, suffix, ph)
                    activeSubPanel = WorkbenchSubPanel.NONE
                },
                onInsertSnippet = { snippet ->
                    insertAtCursor(snippet)
                    activeSubPanel = WorkbenchSubPanel.NONE
                },
                onDismiss = { activeSubPanel = WorkbenchSubPanel.NONE }
            )
        }

        // 5. 底部拇指区：6×2 严整对称等宽工具栏（全 2 字标签对齐，内置「链接 / 搜索社区帖子插入链接」）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(palette.subtleSurface.copy(alpha = if (palette.isDark) 0.68f else 0.78f))
                .padding(horizontal = 6.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 第一排 6 等分：图片 · 链接(支持搜帖链接) · 提及 · 表情 · 粗体 · 代码
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                WorkbenchVectorToolButton(
                    icon = LinDoIcons.Image,
                    label = if (isUploadingMedia) "上传" else "图片",
                    highlighted = true,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val picker = PlatformMediaPicker.launchPicker
                        if (picker != null && !isUploadingMedia) {
                            picker("image/*") { fileName, mimeType, bytes ->
                                scope.launch {
                                    isUploadingMedia = true
                                    uploadStatusText = "正在上传图片 $fileName..."
                                    val res = LinuxDoRepository.uploadMediaToLinuxDo(fileName, mimeType, bytes)
                                    isUploadingMedia = false
                                    if (res != null) {
                                        lastUploadedImage = res
                                        uploadStatusText = "已上传 ${res.originalFilename}"
                                        insertAtCursor("\n${res.markdownSnippet}\n")
                                    } else {
                                        uploadStatusText = "上传失败，请确认已登录并重试"
                                    }
                                }
                            }
                        } else {
                            insertAtCursor("\n![图片描述|100%](https://)\n")
                        }
                    }
                )
                WorkbenchVectorToolButton(
                    icon = LinDoIcons.Link,
                    label = "链接",
                    active = activeSubPanel == WorkbenchSubPanel.LINK,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        activeSubPanel = if (activeSubPanel == WorkbenchSubPanel.LINK) WorkbenchSubPanel.NONE else WorkbenchSubPanel.LINK
                    }
                )
                WorkbenchVectorToolButton(
                    icon = LinDoIcons.AtSign,
                    label = "提及",
                    active = activeSubPanel == WorkbenchSubPanel.MENTION,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        activeSubPanel = if (activeSubPanel == WorkbenchSubPanel.MENTION) WorkbenchSubPanel.NONE else WorkbenchSubPanel.MENTION
                    }
                )
                WorkbenchVectorToolButton(
                    icon = LinDoIcons.Smile,
                    label = "表情",
                    active = activeSubPanel == WorkbenchSubPanel.EMOJI,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        activeSubPanel = if (activeSubPanel == WorkbenchSubPanel.EMOJI) WorkbenchSubPanel.NONE else WorkbenchSubPanel.EMOJI
                    }
                )
                WorkbenchVectorToolButton(
                    icon = LinDoIcons.Bold,
                    label = "粗体",
                    modifier = Modifier.weight(1f),
                    onClick = { wrapSelection("**", "**", "粗体文本") }
                )
                WorkbenchVectorToolButton(
                    icon = LinDoIcons.Terminal,
                    label = "代码",
                    active = activeSubPanel == WorkbenchSubPanel.CODE_LANG,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        activeSubPanel = if (activeSubPanel == WorkbenchSubPanel.CODE_LANG) WorkbenchSubPanel.NONE else WorkbenchSubPanel.CODE_LANG
                    }
                )
            }

            // 第二排 6 等分：引用 · 标题 · 列表 · 剧透 · 投票 · 附件
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                WorkbenchVectorToolButton(
                    icon = LinDoIcons.Quote,
                    label = "引用",
                    modifier = Modifier.weight(1f),
                    onClick = { toggleLinePrefix("> ") }
                )
                WorkbenchVectorToolButton(
                    icon = LinDoIcons.Heading,
                    label = "标题",
                    active = activeSubPanel == WorkbenchSubPanel.HEADINGS,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        activeSubPanel = if (activeSubPanel == WorkbenchSubPanel.HEADINGS) WorkbenchSubPanel.NONE else WorkbenchSubPanel.HEADINGS
                    }
                )
                WorkbenchVectorToolButton(
                    icon = LinDoIcons.ListBullet,
                    label = "列表",
                    modifier = Modifier.weight(1f),
                    onClick = { toggleLinePrefix("- ") }
                )
                WorkbenchVectorToolButton(
                    icon = LinDoIcons.EyeOff,
                    label = "剧透",
                    active = activeSubPanel == WorkbenchSubPanel.DETAILS_SPOILER,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        activeSubPanel = if (activeSubPanel == WorkbenchSubPanel.DETAILS_SPOILER) WorkbenchSubPanel.NONE else WorkbenchSubPanel.DETAILS_SPOILER
                    }
                )
                WorkbenchVectorToolButton(
                    icon = LinDoIcons.BarChartPoll,
                    label = "投票",
                    active = activeSubPanel == WorkbenchSubPanel.POLL,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        activeSubPanel = if (activeSubPanel == WorkbenchSubPanel.POLL) WorkbenchSubPanel.NONE else WorkbenchSubPanel.POLL
                    }
                )
                WorkbenchVectorToolButton(
                    icon = LinDoIcons.Paperclip,
                    label = "附件",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val picker = PlatformMediaPicker.launchPicker
                        if (picker != null && !isUploadingMedia) {
                            picker("*/*") { fileName, mimeType, bytes ->
                                scope.launch {
                                    isUploadingMedia = true
                                    uploadStatusText = "正在上传附件 $fileName..."
                                    val res = LinuxDoRepository.uploadMediaToLinuxDo(fileName, mimeType, bytes)
                                    isUploadingMedia = false
                                    if (res != null) {
                                        uploadStatusText = "已上传附件 ${res.originalFilename}"
                                        insertAtCursor("\n${res.markdownSnippet}\n")
                                    } else {
                                        uploadStatusText = "附件上传失败，请检查文件格式与登录态"
                                    }
                                }
                            }
                        } else {
                            insertAtCursor("\n[附件名称|attachment](upload://)\n")
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun WorkbenchVectorToolButton(
    icon: ImageVector,
    label: String,
    active: Boolean = false,
    highlighted: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val palette = LocalPalette.current
    val fg = when {
        active || highlighted -> palette.brandGold
        else -> palette.inkPrimary
    }
    Row(
        modifier = modifier
            .height(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) palette.activeChipBg.copy(alpha = 0.55f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(14.5.dp),
            tint = fg
        )
        Spacer(modifier = Modifier.width(3.5.dp))
        Text(
            text = label,
            fontSize = 11.5.sp,
            fontWeight = if (active || highlighted) FontWeight.Bold else FontWeight.Medium,
            color = fg,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun WorkbenchSourceEditorBox(
    tfv: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    height: Dp
) {
    val palette = LocalPalette.current
    val editorTextStyle = TextStyle(
        fontSize = 15.sp,
        lineHeight = 22.5.sp,
        color = palette.inkPrimary
    )
    BasicTextField(
        value = tfv,
        onValueChange = onValueChange,
        textStyle = editorTextStyle,
        cursorBrush = SolidColor(palette.brandGold),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.subtleSurface.copy(alpha = if (palette.isDark) 0.38f else 0.55f))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                contentAlignment = Alignment.TopStart
            ) {
                if (tfv.text.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = editorTextStyle.copy(color = palette.inkTertiary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                innerTextField()
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
    )
}

/**
 * 编辑器内置 Markdown + Discourse BBCode (`[poll]`, `[details]`, `[spoiler]`, `[grid]`, `> [!NOTE]`) 实时 AST 预览器
 */
@Composable
private fun WorkbenchAstLivePreviewBox(
    rawMarkdown: String,
    height: Dp
) {
    val palette = LocalPalette.current
    val previewHtml = remember(rawMarkdown) {
        compileMarkdownAndBbcodeToPreviewHtml(rawMarkdown)
    }
    val astNodes by androidx.compose.runtime.produceState<List<PostBlockNode>>(emptyList(), previewHtml) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            DiscourseAstParser.parseOrGetCached(-999999, previewHtml)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.subtleSurface.copy(alpha = 0.5f))
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "实时渲染预览 (Linux.do AST)",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = palette.inkTertiary
            )
            Text(
                text = "与发帖后展示效果一致",
                fontSize = 10.5.sp,
                color = palette.inkTertiary
            )
        }
        HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)

        if (rawMarkdown.isBlank()) {
            LinDoEmptyState("还没有预览内容，试着写点什么吧")
        } else {
            val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
            PostRichContent(astNodes, palette, onOpenLink = { uriHandler.openUri(it) })
        }
    }
}

/**
 * 将本地编辑中的 Markdown + Discourse BBCode 源码实时编译为可由 `DiscourseAstParser` 解析的 HTML
 */
private fun compileMarkdownAndBbcodeToPreviewHtml(raw: String): String {
    if (raw.isBlank()) return ""
    var working = raw

    // 1. [poll ...] ... [/poll]
    working = Regex("""\[poll([^\]]*)\]([\s\S]*?)\[/poll\]""", RegexOption.IGNORE_CASE).replace(working) { m ->
        val body = m.groupValues[2].trim()
        val lines = body.lines().map { it.trim() }.filter { it.isNotBlank() }
        val title = lines.firstOrNull { it.startsWith("#") }?.removePrefix("#")?.trim() ?: "社区投票"
        val opts = lines.filter { it.startsWith("-") || it.startsWith("*") }
            .map { it.removePrefix("-").removePrefix("*").trim() }
        val liHtml = opts.joinToString("") { "<li>$it</li>" }
        "<div class=\"poll\" data-poll-title=\"$title\"><ul>$liHtml</ul></div>"
    }

    // 2. [details="摘要"] ... [/details]
    working = Regex("""\[details=["']?([^\]"']+)["']?\]([\s\S]*?)\[/details\]""", RegexOption.IGNORE_CASE).replace(working) { m ->
        "<details><summary>${m.groupValues[1]}</summary><p>${m.groupValues[2].trim()}</p></details>"
    }

    // 3. [spoiler] ... [/spoiler]
    working = Regex("""\[spoiler\]([\s\S]*?)\[/spoiler\]""", RegexOption.IGNORE_CASE).replace(working) { m ->
        "<div class=\"spoilered\">${m.groupValues[1].trim()}</div>"
    }

    // 4. 代码块 ```lang ... ```
    working = Regex("""```([a-zA-Z0-9_-]*)\n([\s\S]*?)```""").replace(working) { m ->
        val lang = m.groupValues[1].ifBlank { "text" }
        "<pre><code class=\"lang-$lang\">${m.groupValues[2]}</code></pre>"
    }

    // 5. 逐行处理标题、Callout、引用、列表与普通段落
    val out = StringBuilder()
    val lines = working.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        when {
            trimmed.startsWith("<div") || trimmed.startsWith("<details") || trimmed.startsWith("<pre>") -> {
                out.append(line).append("\n")
            }
            trimmed == "---" || trimmed == "***" -> {
                out.append("<hr/>")
            }
            trimmed.startsWith("### ") -> out.append("<h3>${trimmed.removePrefix("### ")}</h3>")
            trimmed.startsWith("## ") -> out.append("<h2>${trimmed.removePrefix("## ")}</h2>")
            trimmed.startsWith("# ") -> out.append("<h1>${trimmed.removePrefix("# ")}</h1>")
            trimmed.startsWith("> [!") -> {
                val kind = trimmed.substringAfter("[!").substringBefore("]").ifBlank { "NOTE" }
                val bodyLines = mutableListOf<String>()
                var j = i + 1
                while (j < lines.size && lines[j].trim().startsWith(">")) {
                    bodyLines.add(lines[j].trim().removePrefix(">").trim())
                    j++
                }
                out.append("<blockquote><p>[!$kind] ${bodyLines.joinToString(" ")}</p></blockquote>")
                i = j - 1
            }
            trimmed.startsWith("> ") -> {
                out.append("<blockquote><p>${trimmed.removePrefix("> ")}</p></blockquote>")
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                val items = mutableListOf<String>()
                var j = i
                while (j < lines.size && (lines[j].trim().startsWith("- ") || lines[j].trim().startsWith("* "))) {
                    items.add("<li>${lines[j].trim().drop(2)}</li>")
                    j++
                }
                out.append("<ul>${items.joinToString("")}</ul>")
                i = j - 1
            }
            trimmed.isNotBlank() -> {
                out.append("<p>$trimmed</p>")
            }
        }
        i++
    }
    return out.toString()
}

@Composable
private fun WorkbenchInteractiveSubPanel(
    subPanel: WorkbenchSubPanel,
    mentionSearchTerm: String,
    onMentionSearchTermChange: (String) -> Unit,
    mentionCandidates: List<MentionCandidate>,
    isSearchingMentions: Boolean,
    mentionError: String?,
    onRetryMention: () -> Unit,
    onToggleLinePrefix: (String) -> Unit,
    onWrapSelection: (String, String, String) -> Unit,
    onInsertSnippet: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalPalette.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(palette.cardSurface)
            .border(0.6.dp, palette.hairline, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when (subPanel) {
            WorkbenchSubPanel.HEADINGS -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        "H1 一级" to "# ",
                        "H2 二级" to "## ",
                        "H3 三级" to "### ",
                        "H4 四级" to "#### ",
                        "H5 五级" to "##### "
                    ).forEach { (label, prefix) ->
                        WorkbenchToolChip(
                            label = label,
                            onClick = { onToggleLinePrefix(prefix) }
                        )
                    }
                }
            }

            WorkbenchSubPanel.CODE_LANG -> {
                Text("选择代码块高亮语言", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = palette.inkSecondary)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        "kotlin", "python", "bash", "javascript", "typescript",
                        "json", "yaml", "rust", "go", "sql", "html", "dockerfile", "mermaid"
                    ).forEach { lang ->
                        WorkbenchToolChip(
                            label = lang,
                            onClick = {
                                onWrapSelection("\n```$lang\n", "\n```\n", "// 在此输入 $lang 代码")
                            }
                        )
                    }
                }
            }

            WorkbenchSubPanel.CALLOUT -> {
                Text("插入 GitHub / Discourse 风格 Callout 提示框", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = palette.inkSecondary)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        "ℹ️ NOTE 说明" to "NOTE",
                        "💡 TIP 技巧" to "TIP",
                        "📌 IMPORTANT 重要" to "IMPORTANT",
                        "⚠️ WARNING 警告" to "WARNING",
                        "🚨 CAUTION 危险" to "CAUTION"
                    ).forEach { (label, kind) ->
                        WorkbenchToolChip(
                            label = label,
                            onClick = {
                                onInsertSnippet("\n> [!$kind]\n> 在此输入提示内容...\n")
                            }
                        )
                    }
                }
            }

            WorkbenchSubPanel.LINK -> {
                var linkTab by remember { mutableIntStateOf(0) } // 0 = 搜索引用帖子, 1 = 自定义链接
                var topicSearchQuery by remember { mutableStateOf("") }
                var topicCandidates by remember { mutableStateOf<List<CommunitySearchResult>>(emptyList()) }
                var isSearchingTopics by remember { mutableStateOf(false) }
                var linkTitle by remember { mutableStateOf("") }
                var linkUrl by remember { mutableStateOf("https://") }

                LaunchedEffect(topicSearchQuery, linkTab) {
                    if (linkTab != 0) return@LaunchedEffect
                    val q = topicSearchQuery.trim()
                    if (q.isBlank()) {
                        isSearchingTopics = true
                        val latest = runCatching { LinuxDoRepository.fetchTopics("latest.json") }.getOrDefault(emptyList())
                        topicCandidates = latest.take(8).map { item ->
                            CommunitySearchResult(
                                topicId = item.id,
                                postNumber = 1,
                                title = item.title,
                                blurb = item.excerpt.orEmpty(),
                                username = item.originalPoster.username,
                                avatarTemplate = item.originalPoster.avatarTemplate,
                                createdAtText = item.lastPostedAt,
                                categoryName = item.categoryName,
                                categoryColorHex = item.categoryColorHex,
                                likeCount = item.likeCount,
                                postsCount = item.postsCount
                            )
                        }
                        isSearchingTopics = false
                    } else {
                        delay(350L)
                        isSearchingTopics = true
                        topicCandidates = runCatching { LinuxDoRepository.searchCommunity(q) }
                            .getOrDefault(emptyList())
                            .distinctBy { it.topicId }
                            .take(10)
                        isSearchingTopics = false
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    WorkbenchToolChip(
                        label = "🔍 搜索社区帖子链接",
                        active = linkTab == 0,
                        onClick = { linkTab = 0 }
                    )
                    WorkbenchToolChip(
                        label = "🔗 自定义 URL 链接",
                        active = linkTab == 1,
                        onClick = { linkTab = 1 }
                    )
                }

                if (linkTab == 0) {
                    WorkbenchCompactInputBox(
                        value = topicSearchQuery,
                        onValueChange = { topicSearchQuery = it },
                        placeholder = "搜索帖子标题或关键词，点击即可插入帖子链接...",
                        leadingIcon = LinDoIcons.Search,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (isSearchingTopics) {
                        LinDoLoadingIndicator(label = "搜索帖子中")
                    } else if (topicCandidates.isEmpty()) {
                        Text(
                            text = "未找到匹配的社区帖子",
                            fontSize = 11.5.sp,
                            color = palette.inkTertiary,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 156.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            topicCandidates.forEach { item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(palette.cardSurface)
                                        .border(0.5.dp, palette.hairline, RoundedCornerShape(8.dp))
                                        .clickable {
                                            val suffix = if (item.postNumber > 1) "/${item.postNumber}" else ""
                                            val url = "https://linux.do/t/topic/${item.topicId}$suffix"
                                            onInsertSnippet("[${item.title}]($url) ")
                                        }
                                        .padding(horizontal = 10.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = LinDoIcons.Link,
                                        contentDescription = null,
                                        modifier = Modifier.size(13.5.dp),
                                        tint = palette.brandGold
                                    )
                                    Spacer(modifier = Modifier.width(7.dp))
                                    Text(
                                        text = item.title,
                                        fontSize = 12.2.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = palette.inkPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (item.categoryName.isNotBlank()) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = item.categoryName,
                                            fontSize = 10.5.sp,
                                            color = palette.inkTertiary,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        WorkbenchCompactInputBox(
                            value = linkTitle,
                            onValueChange = { linkTitle = it },
                            placeholder = "链接显示文字",
                            modifier = Modifier.weight(0.95f)
                        )
                        WorkbenchCompactInputBox(
                            value = linkUrl,
                            onValueChange = { linkUrl = it },
                            placeholder = "https://...",
                            modifier = Modifier.weight(1.25f)
                        )
                        WorkbenchToolChip(
                            label = "插入",
                            highlighted = true,
                            onClick = {
                                val u = linkUrl.trim().ifBlank { "https://linux.do" }
                                val t = linkTitle.trim().ifBlank { u }
                                onInsertSnippet("[$t]($u) ")
                            }
                        )
                    }
                }
            }

            WorkbenchSubPanel.DETAILS_SPOILER -> {
                var summaryTitle by remember { mutableStateOf("点击展开详情") }
                Text("插入折叠面板 [details]、剧透模糊 [spoiler] 或提示框", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = palette.inkSecondary)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    WorkbenchCompactInputBox(
                        value = summaryTitle,
                        onValueChange = { summaryTitle = it },
                        placeholder = "折叠块摘要标题",
                        modifier = Modifier.weight(1f)
                    )
                    WorkbenchToolChip(
                        label = "折叠块",
                        highlighted = true,
                        onClick = {
                            onWrapSelection(
                                "\n[details=\"${summaryTitle.ifBlank { "详情" }}\"]\n",
                                "\n[/details]\n",
                                "折叠隐藏的详细内容..."
                            )
                        }
                    )
                    WorkbenchToolChip(
                        label = "剧透模糊",
                        onClick = {
                            onWrapSelection("[spoiler]", "[/spoiler]", "剧透隐藏文字")
                        }
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        "ℹ️ 说明" to "NOTE",
                        "💡 技巧" to "TIP",
                        "📌 重要" to "IMPORTANT",
                        "⚠️ 警告" to "WARNING"
                    ).forEach { (label, kind) ->
                        WorkbenchToolChip(
                            label = label,
                            onClick = {
                                onInsertSnippet("\n> [!$kind]\n> 在此输入提示内容...\n")
                            }
                        )
                    }
                }
            }

            WorkbenchSubPanel.TABLE -> {
                var cols by remember { mutableStateOf(3) }
                var rows by remember { mutableStateOf(3) }
                Text("可视化表格构建与行列快捷操作", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = palette.inkSecondary)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    WorkbenchToolChip(
                        label = "列数: $cols (+)",
                        onClick = { cols = if (cols >= 6) 2 else cols + 1 }
                    )
                    WorkbenchToolChip(
                        label = "行数: $rows (+)",
                        onClick = { rows = if (rows >= 8) 2 else rows + 1 }
                    )
                    WorkbenchToolChip(
                        label = "生成 ${rows}×${cols} 表格",
                        highlighted = true,
                        onClick = {
                            val header = (1..cols).joinToString(" | ", prefix = "| ", postfix = " |") { "列 $it" }
                            val divider = (1..cols).joinToString(" | ", prefix = "| ", postfix = " |") { "---" }
                            val body = (1..rows).joinToString("\n") { r ->
                                (1..cols).joinToString(" | ", prefix = "| ", postfix = " |") { c -> "内容 ${r}-${c}" }
                            }
                            onInsertSnippet("\n$header\n$divider\n$body\n")
                        }
                    )
                    WorkbenchToolChip(
                        label = "+ 追加一行",
                        onClick = {
                            val rowStr = (1..cols).joinToString(" | ", prefix = "| ", postfix = " |") { "新单元格" }
                            onInsertSnippet("\n$rowStr")
                        }
                    )
                }
            }

            WorkbenchSubPanel.POLL -> {
                var pollTitle by remember { mutableStateOf("") }
                var pollType by remember { mutableStateOf("regular") } // regular / multiple / number
                var pollResults by remember { mutableStateOf("always") } // always / on_vote / on_close
                var pollPublic by remember { mutableStateOf(true) }
                var pollOptionsText by remember { mutableStateOf("选项一\n选项二\n选项三") }

                Text("可视化投票构建器 (Discourse [poll])", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = palette.inkSecondary)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("regular" to "单选投票", "multiple" to "多选投票", "number" to "数字评分").forEach { (k, lbl) ->
                        WorkbenchToolChip(
                            label = lbl,
                            active = pollType == k,
                            onClick = { pollType = k }
                        )
                    }
                    listOf("always" to "始终可见结果", "on_vote" to "投票后可见", "on_close" to "结束后可见").forEach { (k, lbl) ->
                        WorkbenchToolChip(
                            label = lbl,
                            active = pollResults == k,
                            onClick = { pollResults = k }
                        )
                    }
                    WorkbenchToolChip(
                        label = if (pollPublic) "✓ 公开投票人" else "匿名投票",
                        active = pollPublic,
                        onClick = { pollPublic = !pollPublic }
                    )
                }
                OutlinedTextField(
                    value = pollTitle,
                    onValueChange = { pollTitle = it },
                    singleLine = true,
                    placeholder = { Text("投票标题（可选，如：你最常用的开发环境是？）", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
                OutlinedTextField(
                    value = pollOptionsText,
                    onValueChange = { pollOptionsText = it },
                    placeholder = { Text("每行一个投票选项...", fontSize = 12.sp) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(78.dp),
                    shape = RoundedCornerShape(8.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    WorkbenchToolChip(
                        label = "插入投票组件",
                        highlighted = true,
                        onClick = {
                            val opts = pollOptionsText.lines().map { it.trim() }.filter { it.isNotBlank() }
                            val extraAttrs = buildString {
                                append(" type=$pollType results=$pollResults")
                                if (pollType == "multiple") append(" min=1 max=${opts.size.coerceAtLeast(2)}")
                                if (pollPublic) append(" public=true")
                                append(" chartType=bar")
                            }
                            val headerLine = if (pollTitle.isNotBlank()) "# ${pollTitle.trim()}\n" else ""
                            val optLines = opts.joinToString("\n") { "- $it" }
                            onInsertSnippet("\n[poll$extraAttrs]\n$headerLine$optLines\n[/poll]\n")
                        }
                    )
                }
            }

            WorkbenchSubPanel.EMOJI -> {
                Text("常用表情与 Linux.do 社区 Shortcode", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = palette.inkSecondary)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        "👍", "🎉", "🔥", "❤️", "🚀", "😂", "🤔", "👀", "🙏", "✨", "💯", "😭",
                        ":thumbsup:", ":tada:", ":rocket:", ":heart:", ":fire:", ":thinking:", ":100:", ":sparkles:"
                    ).forEach { emo ->
                        WorkbenchToolChip(
                            label = emo,
                            onClick = { onInsertSnippet("$emo ") }
                        )
                    }
                }
            }

            WorkbenchSubPanel.MENTION -> {
                Text("搜索并 @提及 社区用户或群组", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = palette.inkSecondary)
                WorkbenchCompactInputBox(
                    value = mentionSearchTerm,
                    onValueChange = onMentionSearchTermChange,
                    placeholder = "输入用户名或昵称关键字搜索...",
                    leadingIcon = LinDoIcons.AtSign,
                    modifier = Modifier.fillMaxWidth()
                )
                if (mentionError != null) {
                    LinDoLoadFailure(mentionError, onRetryMention)
                } else if (isSearchingMentions) {
                    LinDoLoadingIndicator(label = "搜索用户中")
                } else if (mentionCandidates.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        mentionCandidates.forEach { cand ->
                            WorkbenchToolChip(
                                label = "@${cand.handle} (${cand.displayName})",
                                onClick = { onInsertSnippet("@${cand.handle} ") }
                            )
                        }
                    }
                }
            }

            WorkbenchSubPanel.MORE_TOOLS -> {
                Text("Discourse 进阶语法与模板工具", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = palette.inkSecondary)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    WorkbenchToolChip(
                        label = "📑 目录 [toc]",
                        onClick = { onInsertSnippet("\n[toc]\n") }
                    )
                    WorkbenchToolChip(
                        label = "∑ 数学公式 $$",
                        onClick = { onWrapSelection("\n$$\n", "\n$$\n", "E = mc^2") }
                    )
                    WorkbenchToolChip(
                        label = "📈 Mermaid 流程图",
                        onClick = {
                            onInsertSnippet("\n```mermaid\ngraph TD\n    A[开始] --> B[处理]\n    B --> C[完成]\n```\n")
                        }
                    )
                    WorkbenchToolChip(
                        label = "🕒 动态时间 [date]",
                        onClick = {
                            val today = java.time.LocalDate.now().toString()
                            onInsertSnippet("[date=$today timezone=\"Asia/Shanghai\"] ")
                        }
                    )
                    WorkbenchToolChip(
                        label = "― 分割线 ---",
                        onClick = { onInsertSnippet("\n\n---\n\n") }
                    )
                    WorkbenchToolChip(
                        label = "⌨ 键盘按键 <kbd>",
                        onClick = { onWrapSelection("<kbd>", "</kbd>", "Ctrl+C") }
                    )
                }
            }

            WorkbenchSubPanel.NONE -> {}
        }
    }
}

@Composable
private fun WorkbenchCompactInputBox(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    modifier: Modifier = Modifier
) {
    val palette = LocalPalette.current
    val inputStyle = TextStyle(
        fontSize = 12.5.sp,
        lineHeight = 16.sp,
        color = palette.inkPrimary,
        fontWeight = FontWeight.Medium
    )
    Row(
        modifier = modifier
            .height(35.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(palette.cardSurface)
            .border(0.5.dp, palette.hairline, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = palette.inkTertiary
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = inputStyle,
            cursorBrush = SolidColor(palette.brandGold),
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = inputStyle.copy(
                                color = palette.inkTertiary,
                                fontWeight = FontWeight.Normal
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    innerTextField()
                }
            }
        )
        if (value.isNotEmpty()) {
            Spacer(modifier = Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .clickable { onValueChange("") },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = LinDoIcons.Close,
                    contentDescription = "清空",
                    modifier = Modifier.size(12.dp),
                    tint = palette.inkTertiary
                )
            }
        }
    }
}

@Composable
private fun WorkbenchToolChip(
    label: String,
    active: Boolean = false,
    highlighted: Boolean = false,
    onClick: () -> Unit
) {
    val palette = LocalPalette.current
    val bg = when {
        highlighted -> palette.publishBtnBg
        active -> palette.activeChipBg
        else -> palette.cardSurface
    }
    val fg = when {
        highlighted -> palette.publishBtnContent
        active -> palette.activeChipText
        else -> palette.inkSecondary
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(
                width = 0.5.dp,
                color = if (active || highlighted) palette.brandGold else palette.hairline,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 11.5.sp,
            fontWeight = if (active || highlighted) FontWeight.Bold else FontWeight.Medium,
            color = fg,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun WorkbenchMiniActionChip(
    label: String,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    onClick: () -> Unit
) {
    val palette = LocalPalette.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (highlighted) palette.tonalPill else palette.cardSurface)
            .border(0.5.dp, palette.hairline, RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.5.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (!enabled) palette.inkTertiary.copy(alpha = 0.45f)
            else if (highlighted) palette.brandGold
            else palette.inkSecondary
        )
    }
}

/**
 * 官方功能 3：全站实时搜索抽屉（联动 `/search/query.json` + `/search.json` + 自动防抖与热门搜索词）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GlobalSearchModalSheet(
    initialQuery: String,
    onDismiss: () -> Unit,
    onSelectResult: (Int, String, Int?) -> Unit
) {
    val palette = LocalPalette.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var query by remember { mutableStateOf(initialQuery) }
    var order by remember { mutableStateOf<String?>(null) } // null=相关性, "latest"=最新, "likes"=最多赞
    var results by remember { mutableStateOf<List<CommunitySearchResult>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reloadToken by remember { mutableIntStateOf(0) }
    var isSearching by remember { mutableStateOf(false) }

    var submittedQuery by remember { mutableStateOf(initialQuery.trim()) }
    fun performSearch(targetQuery: String = query) {
        keyboardController?.hide()
        submittedQuery = targetQuery.trim()
        reloadToken++
    }

    // 输入防抖自动搜索：用户输入停止 420ms 后自动触发全站检索
    LaunchedEffect(query) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            submittedQuery = ""
            results = emptyList()
            loadError = null
            isSearching = false
            return@LaunchedEffect
        }
        if (trimmed != submittedQuery) {
            delay(420L)
            submittedQuery = trimmed
        }
    }

    LaunchedEffect(submittedQuery, order, reloadToken) {
        if (submittedQuery.isBlank()) return@LaunchedEffect
        isSearching = true
        loadError = null
        try {
            results = LinuxDoRepository.searchCommunity(submittedQuery, order)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            loadError = error.message ?: "搜索失败，请重试"
        } finally {
            isSearching = false
        }
    }

    LaunchedEffect(Unit) {
        if (initialQuery.isBlank()) {
            delay(120L)
            runCatching { focusRequester.requestFocus() }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = palette.cardSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 460.dp, max = 680.dp)
                .padding(horizontal = 18.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "全站搜索",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.inkPrimary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "关闭",
                    fontSize = 13.sp,
                    color = palette.inkSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onDismiss() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 全圆角胶囊搜索输入框（带键盘 ImeAction.Search、一键清空按钮与右侧金底搜索键，光标与 Placeholder 严格居中同轴）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val searchInputStyle = TextStyle(
                    fontSize = 13.5.sp,
                    lineHeight = 18.sp,
                    color = palette.inkPrimary,
                    fontWeight = FontWeight.Medium
                )
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(palette.subtleSurface)
                        .border(0.5.dp, palette.hairline, RoundedCornerShape(99.dp))
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = LinDoIcons.Search,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = palette.inkTertiary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = searchInputStyle,
                        cursorBrush = SolidColor(palette.brandGold),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { performSearch() }),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .focusRequester(focusRequester),
                        decorationBox = { innerTextField ->
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (query.isEmpty()) {
                                    Text(
                                        text = "搜索话题、标签或 @用户名...",
                                        style = searchInputStyle.copy(
                                            color = palette.inkTertiary,
                                            fontWeight = FontWeight.Normal
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                    if (query.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .clickable {
                                    query = ""
                                    submittedQuery = ""
                                    results = emptyList()
                                    loadError = null
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = LinDoIcons.Close,
                                contentDescription = "清空",
                                modifier = Modifier.size(14.dp),
                                tint = palette.inkSecondary
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .height(42.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(palette.brandGold)
                        .clickable { performSearch() }
                        .padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "搜索",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1F1A12)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 排序快筛胶囊
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val orders = listOf("相关性" to null, "最新发帖" to "latest", "最多获赞" to "likes")
                orders.forEach { (label, ord) ->
                    val selected = order == ord
                    GoogleM3FilterPill(
                        label = label,
                        dotColor = null,
                        selected = selected,
                        onClick = {
                            order = ord
                            if (query.isNotBlank()) performSearch()
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp)
            ) {
                if (query.isBlank() && results.isEmpty() && !isSearching) {
                    item {
                        Text(
                            text = "热门搜索",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.inkSecondary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        val hotKeywords = listOf(
                            "Claude", "DeepSeek", "MCP", "人工智能",
                            "Cursor", "开源", "Docker", "快问快答"
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            hotKeywords.chunked(4).forEach { rowWords ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    rowWords.forEach { kw ->
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(99.dp))
                                                .background(palette.subtleSurface)
                                                .border(0.5.dp, palette.hairline, RoundedCornerShape(99.dp))
                                                .clickable {
                                                    query = kw
                                                    performSearch(kw)
                                                }
                                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                        ) {
                                            Text(
                                                text = kw,
                                                fontSize = 12.2.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = palette.inkPrimary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            LinDoEmptyState("输入关键词或点击上方热词，探索全站讨论")
                        }
                    }
                } else if (isSearching && results.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            LinDoStatePanel(
                                state = LinDoMascotState.Loading,
                                title = "零度娘正在全站检索",
                                detail = "正在匹配「$submittedQuery」相关的话题与回复..."
                            )
                        }
                    }
                } else if (loadError != null && results.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            LinDoStatePanel(
                                state = LinDoMascotState.NetworkError,
                                title = "搜索暂时遇到问题",
                                detail = loadError!!,
                                onRetry = { performSearch() },
                                actionLabel = "重新搜索"
                            )
                        }
                    }
                } else if (results.isEmpty() && !isSearching) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            LinDoEmptyState("没有找到与「$submittedQuery」匹配的结果，换个关键词试试吧")
                        }
                    }
                } else {
                    itemsIndexed(results, key = { idx, item -> "${item.topicId}_${item.postNumber}_$idx" }) { _, item ->
                        val (catBg, catFg) = remember(item.categoryName, palette.isDark) {
                            resolveMorandiCategoryColors(item.categoryName, palette.isDark)
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(palette.canvas)
                                .border(0.5.dp, palette.hairline, RoundedCornerShape(14.dp))
                                .clickable { onSelectResult(item.topicId, item.title, item.postNumber) }
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SmartAvatar(
                                    username = item.username.ifBlank { "L" },
                                    avatarTemplate = item.avatarTemplate,
                                    animatedAvatar = null,
                                    primaryGroupName = null,
                                    flair = null,
                                    size = 20.dp,
                                    enableAnimations = false
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = item.username,
                                    fontSize = 11.8.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = palette.inkSecondary,
                                    maxLines = 1
                                )
                                Text(
                                    text = " · #${item.postNumber}楼 · ${item.createdAtText}",
                                    fontSize = 11.2.sp,
                                    color = palette.inkTertiary,
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f)
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(catBg)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = item.categoryName,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = catFg,
                                        maxLines = 1
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = item.title,
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = palette.inkPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (item.blurb.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = item.blurb,
                                    fontSize = 12.4.sp,
                                    color = palette.inkSecondary,
                                    lineHeight = 18.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (item.likeCount > 0 || item.postsCount > 1) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    if (item.likeCount > 0) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                                        ) {
                                            Icon(
                                                imageVector = LinDoIcons.Heart,
                                                contentDescription = null,
                                                modifier = Modifier.size(12.dp),
                                                tint = palette.inkTertiary
                                            )
                                            Text(
                                                text = "${item.likeCount}",
                                                fontSize = 11.sp,
                                                color = palette.inkTertiary
                                            )
                                        }
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Icon(
                                            imageVector = LinDoIcons.MessageCircle,
                                            contentDescription = null,
                                            modifier = Modifier.size(12.dp),
                                            tint = palette.inkTertiary
                                        )
                                        Text(
                                            text = "${item.postsCount} 条讨论",
                                            fontSize = 11.sp,
                                            color = palette.inkTertiary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (results.isNotEmpty()) {
                        item(key = "search_results_end_footer") {
                            LinearPulseLoadingFooter(
                                isLoadingMore = false,
                                hasMore = false,
                                loadedCount = results.size,
                                doneText = "没有更多了 · 共找到 ${results.size} 条相关结果",
                                onLoadMore = {}
                            )
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
    onSelectNotification: (Int, String, Int?) -> Unit,
    onInspectUser: (String) -> Unit = {}
) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    var notifications by remember { mutableStateOf<List<UserNotificationItem>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reloadToken by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var filterTab by remember { mutableStateOf(0) } // 0=全部, 1=未读, 2=回复/@, 3=获赞

    LaunchedEffect(reloadToken) {
        isLoading = true
        loadError = null
        try {
            notifications = LinuxDoRepository.fetchUserNotifications()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            loadError = error.message ?: "加载失败，请重试"
        } finally {
            isLoading = false
        }
    }

    val filtered = remember(notifications, filterTab) {
        when (filterTab) {
            1 -> notifications.filter { !it.read }
            2 -> notifications.filter { it.notificationType in setOf(1, 2, 3, 6, 9, 11, 15, 29, 32, 33, 38, 802) }
            3 -> notifications.filter { it.notificationType in setOf(5, 19, 25) }
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
                                val ok = LinuxDoRepository.markNotificationsRead()
                                if (ok) {
                                    notifications = notifications.map { it.copy(read = true) }
                                }
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
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    LinDoLoadingIndicator(label = "加载中")
                }
            } else {
                if (loadError != null) {
                    LinDoLoadFailure(loadError!!, onRetry = { reloadToken++ })
                }

                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                    contentPadding = PaddingValues(vertical = 6.dp)
                ) {
                    if (filtered.isEmpty() && loadError == null) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                LinDoEmptyState(message = "暂无相关通知消息")
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
                                    .clickable {
                                        if (item.topicId > 0) {
                                            onSelectNotification(item.topicId, item.title, item.postNumber)
                                        } else {
                                            val primaryActor = item.actingUsername.substringBefore("、").trim()
                                            if (primaryActor.isNotBlank() && primaryActor != "系统") {
                                                onInspectUser(primaryActor)
                                            }
                                        }
                                    }
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
                                    imageVector = LinDoIcons.ChevronRight,
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
}

/**
 * 官方功能 5：我的动态抽屉 (`/user_actions.json`：支持全部 / 主帖 / 回复 / 点赞筛选)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserActivitiesSheet(
    username: String,
    onDismiss: () -> Unit,
    onSelectActivity: (Int, String, Int?) -> Unit
) {
    val palette = LocalPalette.current
    var filterCode by remember { mutableStateOf<String?>(null) } // null=全部, "4"=主帖, "5"=回复, "1"=点赞
    var items by remember { mutableStateOf<List<UserActivityItem>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reloadToken by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(username, filterCode, reloadToken) {
        isLoading = true
        loadError = null
        try {
            items = LinuxDoRepository.fetchUserActivities(username, filterCode)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            loadError = error.message ?: "加载失败，请重试"
        } finally {
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
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    LinDoLoadingIndicator(label = "加载中")
                }
            } else {
                if (loadError != null) {
                    LinDoLoadFailure(loadError!!, onRetry = { reloadToken++ })
                }

                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 6.dp)
                ) {
                    if (items.isEmpty() && loadError == null) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                LinDoEmptyState(message = "暂无相关社区动态记录")
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
                                    .clickable { onSelectActivity(item.topicId, item.title, item.postNumber) }
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
}

/**
 * 官方功能 6：我的书签收藏抽屉 (`/u/{username}/bookmarks.json`)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserBookmarksSheet(
    username: String,
    onDismiss: () -> Unit,
    onSelectBookmark: (Int, String, Int?) -> Unit
) {
    val palette = LocalPalette.current
    var items by remember { mutableStateOf<List<UserActivityItem>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reloadToken by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(username, reloadToken) {
        isLoading = true
        loadError = null
        try {
            items = LinuxDoRepository.fetchUserBookmarks(username)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            loadError = error.message ?: "加载失败，请重试"
        } finally {
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
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    LinDoLoadingIndicator(label = "加载中")
                }
            } else {
                if (loadError != null) {
                    LinDoLoadFailure(loadError!!, onRetry = { reloadToken++ })
                }

                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    if (items.isEmpty() && loadError == null) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                LinDoEmptyState(message = "暂未收藏任何书签，阅读话题时点击右上角书签即可收藏")
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
                                    .clickable { onSelectBookmark(item.topicId, item.title, item.postNumber) }
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
    var loadError by remember { mutableStateOf<String?>(null) }
    var reloadToken by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(false) }

    var recipient by remember(initialRecipient) { mutableStateOf(initialRecipient.orEmpty()) }
    var pmTitle by remember { mutableStateOf("") }
    var pmBody by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(username, tab, reloadToken) {
        if (tab >= 2) { loadError = null; return@LaunchedEffect }
        isLoading = true
        loadError = null
        try {
            val effectiveUsername = username.ifBlank {
                LinuxDoRepository.fetchCurrentUserProfile()?.username.orEmpty()
            }
            messages = LinuxDoRepository.fetchPrivateMessages(effectiveUsername, sent = (tab == 1))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            loadError = error.message ?: "加载失败，请重试"
        } finally {
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
                    LinDoFeedbackRow(message = statusMsg!!, state = if (statusMsg!!.contains("失败")) LinDoMascotState.NetworkError else LinDoMascotState.Success)
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
                    LinDoBusyButtonLabel(busy = isSending, busyLabel = "正在发送", label = "发送私信", color = palette.brandGold)
                }
            } else {
                if (isLoading) {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        LinDoLoadingIndicator(label = "加载中")
                    }
                } else {
                    if (loadError != null) {
                        LinDoLoadFailure(loadError!!, onRetry = { reloadToken++ })
                    }
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        if (messages.isEmpty() && loadError == null) {
                            item {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    LinDoEmptyState(message = "暂无站内私信会话")
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
    var loadError by remember { mutableStateOf<String?>(null) }
    var reloadToken by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(username, reloadToken) {
        isLoading = true
        loadError = null
        try {
            cardProfile = LinuxDoRepository.fetchPublicUserCard(username)
                ?: throw do_.linux.lindo.core.network.ApiReadException(do_.linux.lindo.core.network.ApiFailureKind.Connection)
            recentActions = LinuxDoRepository.fetchUserActivities(username).take(5)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            loadError = error.message ?: "加载失败，请重试"
        } finally {
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
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            val p = cardProfile
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GoogleAccountRingAvatar(
                    letter = p?.displayName ?: username,
                    avatarUrl = p?.avatarTemplate,
                    animatedAvatar = p?.animatedAvatar,
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
                            Text(
                                text = p.trustLevelName,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = palette.brandGold,
                                maxLines = 1,
                                softWrap = false
                            )
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

            if (isLoading && cardProfile == null) {
                Spacer(modifier = Modifier.height(12.dp))
                LinDoLoadingIndicator(label = "加载中")
            } else if (loadError != null) {
                Spacer(modifier = Modifier.height(10.dp))
                LinDoLoadFailure(loadError!!, onRetry = { reloadToken++ })
            }

            if (p != null && p.bioExcerpt.isNotBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = p.bioExcerpt,
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
    var saveError by remember { mutableStateOf<String?>(null) }

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
                                saveError = null
                                val ok = LinuxDoRepository.updateUserProfileInfo(
                                    username = username,
                                    displayName = name,
                                    bioRaw = bio,
                                    title = title
                                )
                                isSaving = false
                                if (ok) {
                                    onSaved()
                                } else {
                                    saveError = "个人资料保存失败，请检查登录状态或头衔权限后重试"
                                }
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

            if (saveError != null) {
                Spacer(modifier = Modifier.height(8.dp))
                LinDoFeedbackRow(message = saveError!!, state = LinDoMascotState.NetworkError)
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

/**
 * 官方功能 10：阅读历史抽屉 (`GET /read.json`)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserReadHistorySheet(
    onDismiss: () -> Unit,
    onSelectTopic: (TopicSummary) -> Unit
) {
    val palette = LocalPalette.current
    var items by remember { mutableStateOf<List<TopicSummary>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reloadToken by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit, reloadToken) {
        isLoading = true
        loadError = null
        try {
            items = LinuxDoRepository.fetchReadHistoryTopics()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            loadError = error.message ?: "加载失败，请重试"
        } finally {
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
                .heightIn(min = 360.dp, max = 600.dp)
                .padding(horizontal = 18.dp, vertical = 8.dp)
        ) {
            Text(
                text = "阅读历史",
                fontSize = 17.5.sp,
                fontWeight = FontWeight.Bold,
                color = palette.inkPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (isLoading) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    LinDoLoadingIndicator(label = "加载中")
                }
            } else {
                if (loadError != null) {
                    LinDoLoadFailure(loadError!!, onRetry = { reloadToken++ })
                }

                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    if (items.isEmpty() && loadError == null) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                LinDoEmptyState(message = "暂无阅读历史记录")
                            }
                        }
                    } else {
                        itemsIndexed(items, key = { _, item -> item.id }) { _, topic ->
                            CommunityTopicItem(
                                topic = topic,
                                isSelected = false,
                                isCompact = true,
                                enableAnimations = false,
                                onClick = { onSelectTopic(topic) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 官方功能 11：云端草稿箱抽屉 (`GET /drafts.json` + `DELETE /drafts/{key}.json`)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserCloudDraftsSheet(
    onDismiss: () -> Unit,
    onOpenDraftTopic: (CloudDraftItem) -> Unit
) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    var drafts by remember { mutableStateOf<List<CloudDraftItem>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reloadToken by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit, reloadToken) {
        isLoading = true
        loadError = null
        try {
            drafts = LinuxDoRepository.fetchCloudDrafts()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            loadError = error.message ?: "加载失败，请重试"
        } finally {
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
                .heightIn(min = 340.dp, max = 580.dp)
                .padding(horizontal = 18.dp, vertical = 8.dp)
        ) {
            Text(
                text = "云端草稿箱",
                fontSize = 17.5.sp,
                fontWeight = FontWeight.Bold,
                color = palette.inkPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (isLoading) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    LinDoLoadingIndicator(label = "加载中")
                }
            } else {
                if (loadError != null) {
                    LinDoLoadFailure(loadError!!, onRetry = { reloadToken++ })
                }

                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    if (drafts.isEmpty() && loadError == null) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                LinDoEmptyState(message = "云端草稿箱为空，未发送的话题与回复会自动保存在此")
                            }
                        }
                    } else {
                        itemsIndexed(drafts, key = { idx, d -> "${d.draftKey}_$idx" }) { _, draft ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(palette.canvas)
                                    .border(0.5.dp, palette.hairline, RoundedCornerShape(12.dp))
                                    .clickable {
                                        onOpenDraftTopic(draft)
                                    }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = draft.title,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = palette.inkPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (draft.excerpt.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(3.dp))
                                        Text(
                                            text = draft.excerpt,
                                            fontSize = 12.5.sp,
                                            color = palette.inkSecondary,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = draft.createdAtText,
                                        fontSize = 11.sp,
                                        color = palette.inkTertiary
                                    )
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(palette.subtleSurface)
                                        .clickable {
                                            scope.launch {
                                                val ok = LinuxDoRepository.deleteCloudDraft(draft.draftKey, draft.sequence)
                                                if (ok) {
                                                    drafts = drafts.filterNot { it.draftKey == draft.draftKey }
                                                }
                                            }
                                        }
                                        .padding(horizontal = 9.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        text = "删除",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = palette.inkSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Debug host uses production sheets and renderer without logging out the current account. */
@Composable
fun LinDoReadRegressionPreview(scene: String, cooked: String = "", onClose: () -> Unit, onOpenLink: (String) -> Unit) {
    when (scene) {
        "search" -> GlobalSearchModalSheet("linux", onClose, { _, _, _ -> })
        "bookmarks" -> UserBookmarksSheet(UserApiKeyAuthManager.currentUser?.username.orEmpty(), onClose, { _, _, _ -> })
        "notifications" -> UserNotificationsSheet(onClose, { _, _, _ -> })
        "drafts" -> UserCloudDraftsSheet(onClose, { _ -> })
        "history" -> UserReadHistorySheet(onClose, {})
        "activity" -> UserActivitiesSheet(UserApiKeyAuthManager.currentUser?.username.orEmpty(), onClose, { _, _, _ -> })
        "messages" -> UserPrivateMessagesSheet(UserApiKeyAuthManager.currentUser?.username.orEmpty(), null, onClose, {})
        else -> {
            val nodes by androidx.compose.runtime.produceState<List<PostBlockNode>>(emptyList(), cooked) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { DiscourseAstParser.parseCookedHtml(cooked) }
            }
            Column(Modifier.fillMaxSize().background(LightPalette.canvas).windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text("富文本展示回归", fontWeight = FontWeight.Bold)
                PostRichContent(nodes, LightPalette, onOpenLink, "https://linux.do/")
            }
        }
    }
}
