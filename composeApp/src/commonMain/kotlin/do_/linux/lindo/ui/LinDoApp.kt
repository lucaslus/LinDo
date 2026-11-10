package do_.linux.lindo.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import do_.linux.lindo.core.connect.ConnectTrustLevelReport
import do_.linux.lindo.core.connect.ScreenTrackEngine
import do_.linux.lindo.core.connect.UserPresenceService
import do_.linux.lindo.core.model.BadgeInjector
import do_.linux.lindo.core.model.BadgeType
import do_.linux.lindo.core.model.BoostInputStats
import do_.linux.lindo.core.model.CommunitySearchResult
import do_.linux.lindo.core.model.FlairInfo
import do_.linux.lindo.core.model.GrantedBadge
import do_.linux.lindo.core.model.mergeTopicPosts
import do_.linux.lindo.core.model.previousUnloadedStreamIds
import do_.linux.lindo.core.model.remainingUnloadedStreamIds
import do_.linux.lindo.core.model.resolveJumpPostIndex
import do_.linux.lindo.core.model.PostBoostItem
import do_.linux.lindo.core.model.PostItemModel
import do_.linux.lindo.core.model.PostReactionItem
import do_.linux.lindo.core.model.TopicDetailModel
import do_.linux.lindo.core.model.TopicSummary
import do_.linux.lindo.core.model.TopicUser
import do_.linux.lindo.core.model.UserActivityItem
import do_.linux.lindo.core.model.UserFullProfileSummary
import do_.linux.lindo.core.model.UserNotificationItem
import do_.linux.lindo.core.network.ApiFailureKind
import do_.linux.lindo.core.network.ApiReadException
import do_.linux.lindo.core.network.AuthenticatedUserProfile
import do_.linux.lindo.core.network.CloudDraftItem
import do_.linux.lindo.core.network.CommunityAboutData
import do_.linux.lindo.core.network.FollowUserEntry
import do_.linux.lindo.core.network.LeaderboardUserEntry
import do_.linux.lindo.core.network.LinuxDoCategoryMeta
import do_.linux.lindo.core.network.LinuxDoRepository
import do_.linux.lindo.core.network.MentionCandidate
import do_.linux.lindo.core.network.PlatformMediaPicker
import do_.linux.lindo.core.network.UploadedMediaResult
import do_.linux.lindo.core.network.UserAccountPreferencesSnapshot
import do_.linux.lindo.core.network.UserApiKeyAuthManager
import do_.linux.lindo.core.compose.*
import do_.linux.lindo.core.badge.UniversalBadgeEngine
import do_.linux.lindo.core.render.postPreviewText
import do_.linux.lindo.core.render.postQuoteMarkup
import do_.linux.lindo.core.render.DiscourseAstParser
import do_.linux.lindo.core.render.PostBlockNode
import do_.linux.lindo.core.site.LinuxDoEmojiCatalog
import do_.linux.lindo.core.site.LinuxDoSiteRules
import do_.linux.lindo.ui.components.LinDoIcons
import do_.linux.lindo.ui.components.OfficialBadgeArtwork
import do_.linux.lindo.ui.components.PostGrantedBadgeIcon
import lindo.composeapp.generated.resources.*
import org.jetbrains.compose.resources.painterResource
import do_.linux.lindo.ui.components.SmartAvatar
import do_.linux.lindo.ui.components.UniversalBadgeChip
import do_.linux.lindo.ui.components.UserTitleTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
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

internal fun buildLinDoPalette(
    isDark: Boolean,
    option: BrandThemeOption = BrandThemeOption.OFFICIAL_GOLD
): LinDoThemePalette {
    return if (!isDark) {
        LinDoThemePalette(
            isDark = false,
            themeOption = option,
            canvas = Color(0xFFFFFFFF),
            groupedCanvas = Color(0xFFFFFFFF),
            cardSurface = Color(0xFFFFFFFF),
            subtleSurface = Color(0xFFF3F4F6),
            navBarSurface = Color(0xFFFFFFFF),
            tonalPill = Color(0xFFEEF1F5),
            onTonalPill = Color(0xFF111827),
            inkPrimary = Color(0xFF111827),
            inkSecondary = Color(0xFF525866),
            inkTertiary = Color(0xFF6B7280),
            hairline = Color(0xFFEBEEF2),
            accent = if (option == BrandThemeOption.OFFICIAL_GOLD) Color(0xFF18181B) else option.logoDoColor,
            brandGold = if (option == BrandThemeOption.OFFICIAL_GOLD) Color(0xFF936000) else option.lightChipText,
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
            canvas = Color(0xFF101217),
            groupedCanvas = Color(0xFF101217),
            cardSurface = Color(0xFF101217),
            subtleSurface = Color(0xFF181B22),
            navBarSurface = Color(0xFF11141B),
            tonalPill = Color(0xFF212631),
            onTonalPill = Color(0xFFF3F4F6),
            inkPrimary = Color(0xFFF3F4F6),
            inkSecondary = Color(0xFFA1A8B5),
            inkTertiary = Color(0xFF7D8594),
            hairline = Color(0xFF1E222B),
            accent = option.logoDoColor,
            brandGold = option.darkChipText,
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

internal val LocalPalette = staticCompositionLocalOf { LightPalette }

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

internal val DarkMaterialScheme = darkColorScheme(
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

internal enum class MainTab(
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    HOME("首页", LinDoIcons.HomeSelected, LinDoIcons.Home),
    DISCOVER("发现", LinDoIcons.CompassSelected, LinDoIcons.Compass),
    MESSAGES("消息", LinDoIcons.BellSelected, LinDoIcons.Bell),
    PROFILE("我的", LinDoIcons.UserSelected, LinDoIcons.User)
}

internal enum class FeedSortFilter(val label: String, val apiPath: String) {
    LATEST("最新", "latest.json"),
    TOP("热门", "top.json"),
    NEW("新帖", "new.json"),
    UNREAD("未读", "unread.json"),
    READ("关注", "read.json")
}

internal enum class TopPeriodFilter(val label: String, val apiPeriod: String) {
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
    useIconArtwork: Boolean = false,
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
            .clickable(
                enabled = !isLoadingMore && (hasMore || isError),
                onClickLabel = if (isError) "重试" else null,
                onClick = onLoadMore
            )
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
        LinDoMascot(state = state, compact = true, size = 22.dp, animate = isLoadingMore, useIconArtwork = useIconArtwork)
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
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = palette.inkSecondary
                )
                Text(
                    text = "重试",
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
private fun LinearShimmerTopicSkeletonList(useIconArtwork: Boolean = false) {
    val palette = LocalPalette.current
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        LinDoLoadingIndicator(
            color = palette.inkSecondary,
            label = "加载中",
            useIconArtwork = useIconArtwork
        )
    }
}

object BackNavigationBridge {
    private val interceptors = mutableListOf<() -> Unit>()
    var canGoBack: Boolean = false
    var onBack: (() -> Boolean)? = null

    fun pushInterceptor(handler: () -> Unit): () -> Unit {
        interceptors.add(handler)
        return { interceptors.remove(handler) }
    }

    fun dispatchBack(): Boolean {
        val top = interceptors.lastOrNull()
        if (top != null) {
            top.invoke()
            return true
        }
        return onBack?.invoke() ?: false
    }
}

@Composable
internal fun LinDoFullScreenPage(
    onBack: () -> Unit,
    containerColor: Color = LocalPalette.current.cardSurface,
    imePadding: Boolean = true,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    val latestOnBack by androidx.compose.runtime.rememberUpdatedState(onBack)
    DisposableEffect(Unit) {
        val remove = BackNavigationBridge.pushInterceptor { latestOnBack() }
        onDispose { remove() }
    }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = containerColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .then(if (imePadding) Modifier.windowInsetsPadding(WindowInsets.ime) else Modifier)
                .windowInsetsPadding(WindowInsets.navigationBars),
            content = content
        )
    }
}

/**
 * LinDo 全局统一设计规范令牌（7 级字阶 · 4 级圆角 · 4 级图标 · 16dp 统一页面水平边距）
 */
object LinDoTokens {
    val Display = 22.sp
    val Title = 17.sp
    val Headline = 16.sp
    val Body = 15.sp
    val Sub = 14.sp
    val Meta = 12.sp
    val Caption = 11.sp

    val RadiusXS = 6.dp
    val RadiusS = 10.dp
    val RadiusM = 14.dp
    val ShapeXS = RoundedCornerShape(RadiusXS)
    val ShapeS = RoundedCornerShape(RadiusS)
    val ShapeM = RoundedCornerShape(RadiusM)
    val ShapeFull = CircleShape

    val IconXS = 14.dp
    val IconS = 16.dp
    val IconM = 20.dp
    val IconL = 22.dp

    val PageHorizontalPadding = 16.dp
    val PageHeaderHeight = 48.dp
    val SettingRowHeight = 46.dp
}

@Composable
internal fun LinDoPageHeader(
    title: String,
    trailingContent: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}
) {
    val palette = LocalPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(LinDoTokens.PageHeaderHeight)
            .background(palette.cardSurface)
            .padding(horizontal = LinDoTokens.PageHorizontalPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = LinDoTokens.Display,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.4).sp,
            color = palette.inkPrimary,
            modifier = Modifier.weight(1f)
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            content = trailingContent
        )
    }
    HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
}

@Composable
internal fun LinDoSectionHeader(
    title: String,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null
) {
    val palette = LocalPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = LinDoTokens.PageHorizontalPadding,
                end = LinDoTokens.PageHorizontalPadding,
                top = 18.dp,
                bottom = 8.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = LinDoTokens.Title,
            fontWeight = FontWeight.Bold,
            color = palette.inkPrimary,
            modifier = Modifier.weight(1f)
        )
        if (!actionLabel.isNullOrBlank()) {
            Row(
                modifier = if (onActionClick != null) Modifier.clickable(onClick = onActionClick) else Modifier,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = actionLabel,
                    fontSize = LinDoTokens.Meta,
                    color = palette.inkTertiary
                )
                Icon(
                    imageVector = LinDoIcons.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(LinDoTokens.IconXS),
                    tint = palette.inkTertiary
                )
            }
        }
    }
}

@Composable
internal fun LinDoCategoryLabel(
    name: String,
    dotColor: Color,
    dotSize: Dp = 7.dp
) {
    val palette = LocalPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(dotSize)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = name,
            fontSize = LinDoTokens.Meta,
            fontWeight = FontWeight.Normal,
            color = palette.inkSecondary,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
internal fun LinDoStatItem(
    icon: ImageVector,
    text: String
) {
    val palette = LocalPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(LinDoTokens.IconXS),
            tint = palette.inkTertiary
        )
        Text(
            text = text,
            fontSize = LinDoTokens.Meta,
            fontWeight = FontWeight.Normal,
            color = palette.inkTertiary,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
internal fun LinDoTagChip(
    name: String,
    onClick: () -> Unit
) {
    val palette = LocalPalette.current
    Box(
        modifier = Modifier
            .clip(LinDoTokens.ShapeXS)
            .background(palette.subtleSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = "#$name",
            fontSize = 13.sp,
            fontWeight = FontWeight.Normal,
            color = palette.inkSecondary,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
internal fun LinDoGroupHeader(text: String) {
    val palette = LocalPalette.current
    Text(
        text = text,
        fontSize = LinDoTokens.Meta,
        fontWeight = FontWeight.Medium,
        color = palette.inkTertiary,
        modifier = Modifier.padding(
            start = LinDoTokens.PageHorizontalPadding,
            end = LinDoTokens.PageHorizontalPadding,
            top = 10.dp,
            bottom = 4.dp
        )
    )
}

@Composable
internal fun LinDoSettingRow(
    icon: ImageVector,
    title: String,
    value: String? = null,
    showDivider: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val palette = LocalPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(LinDoTokens.SettingRowHeight)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = LinDoTokens.PageHorizontalPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            modifier = Modifier.size(LinDoTokens.IconM),
            tint = palette.inkSecondary
        )
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = title,
            fontSize = LinDoTokens.Body,
            fontWeight = FontWeight.Normal,
            color = palette.inkPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (trailing != null) {
            trailing()
        } else {
            if (!value.isNullOrBlank()) {
                Text(
                    text = value,
                    fontSize = LinDoTokens.Sub,
                    color = palette.inkTertiary,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Icon(
                imageVector = LinDoIcons.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(LinDoTokens.IconXS),
                tint = palette.inkTertiary
            )
        }
    }
    if (showDivider) {
        HorizontalDivider(
            modifier = Modifier.padding(start = 50.dp, end = LinDoTokens.PageHorizontalPadding),
            thickness = 0.5.dp,
            color = palette.hairline
        )
    }
}

@Composable
internal fun LinDoPageTopBar(
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null,
    navigationIcon: ImageVector = LinDoIcons.ArrowLeft,
    trailingContent: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}
) {
    val palette = LocalPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.cardSurface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(start = 4.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = navigationIcon,
                    contentDescription = "返回",
                    modifier = Modifier.size(20.dp),
                    tint = palette.inkPrimary
                )
            }
            Spacer(modifier = Modifier.width(2.dp))
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.inkPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        fontSize = 12.sp,
                        color = palette.inkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                content = trailingContent
            )
        }
        HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
    }
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
internal fun GoogleAccountRingAvatar(
    letter: String = "L",
    avatarUrl: String? = null,
    avatarSize: Dp = 76.dp,
    strokeWidth: Dp = 0.5.dp,
    onClick: (() -> Unit)? = null,
    animatedAvatar: String? = null,
    flair: FlairInfo? = null
) {
    Box(Modifier.size(avatarSize).let {
        if (onClick != null) it.clickable(onClick = onClick) else it
    }, contentAlignment = Alignment.Center) {
        SmartAvatar(username = letter, avatarTemplate = avatarUrl, animatedAvatar = animatedAvatar,
            size = avatarSize, flair = flair, enableAnimations = LocalLinDoAnimations.current)
    }
}

@Composable
internal fun LinDoApp(
    onThemeChanged: (Boolean) -> Unit = {},
    systemAnimationsEnabled: Boolean = true,
    appIcon: LinDoAppIcon? = null,
    onApplyAppIcon: ((LinDoAppIcon) -> Boolean)? = null,
    addPlatformImageDecoders: coil3.ComponentRegistry.Builder.() -> Unit = {}
) {
    coil3.compose.setSingletonImageLoaderFactory { context ->
        coil3.ImageLoader.Builder(context)
            .components {
                add(coil3.network.ktor3.KtorNetworkFetcherFactory(httpClient = { LinuxDoRepository.imageHttpClient }))
                add(coil3.svg.SvgDecoder.Factory())
                addPlatformImageDecoders()
            }
            .build()
    }
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
    var pendingEditDraft by remember { mutableStateOf<CloudDraftItem?>(null) }
    var isLoadingDetail by remember { mutableStateOf(false) }
    var mobileShowingDetail by remember { mutableStateOf(false) }

    // 全局官方功能抽屉状态
    var inspectedBadge by remember { mutableStateOf<GrantedBadge?>(null) }
    var inspectedUsername by remember { mutableStateOf<String?>(null) }
    var showBadgesCenterSheet by remember { mutableStateOf(false) }
    var badgesCenterUsername by remember { mutableStateOf<String?>(null) }
    var showAppIconPage by remember { mutableStateOf(false) }
    val openAppIconPage: (() -> Unit)? = onApplyAppIcon?.let { { showAppIconPage = true } }
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
    var showFollowSheet by remember { mutableStateOf(false) }
    var showLeaderboardSheet by remember { mutableStateOf(false) }
    var showAccountPreferencesSheet by remember { mutableStateOf(false) }
    var showAboutSheet by remember { mutableStateOf(false) }
    var discoverHotTopics by remember { mutableStateOf<List<TopicSummary>>(emptyList()) }
    var isLoadingDiscoverHot by remember { mutableStateOf(false) }
    var showLogoutConfirm by remember { mutableStateOf(false) }

    // 认证状态与真实个人资料状态
    var authVersion by remember { mutableStateOf(0) }
    var wasAuthorized by remember { mutableStateOf(UserApiKeyAuthManager.isAuthorized()) }
    var activeWebLoginUrl by remember { mutableStateOf<String?>(null) }
    var fullUserProfile by remember { mutableStateOf<UserFullProfileSummary?>(UserApiKeyAuthManager.fullProfileCache) }

    val isAuthorized = remember(authVersion) { UserApiKeyAuthManager.isAuthorized() }
    val currentUser = remember(authVersion, fullUserProfile) { UserApiKeyAuthManager.currentUser }

    val feedListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val locallyReadTopicPosts = remember {
        androidx.compose.runtime.mutableStateMapOf<Int, Int>().apply {
            putAll(LinuxDoRepository.getReadTopicSnapshot())
        }
    }

    fun normalizeReadTopics(list: List<TopicSummary>): List<TopicSummary> =
        list.map { item ->
            val seenPosts = locallyReadTopicPosts[item.id]
                ?: if (LinuxDoRepository.isTopicRead(item.id)) item.postsCount else null
            if (seenPosts != null && (item.unseen || item.unreadPosts > 0)) {
                item.copy(unseen = false, unreadPosts = 0)
            } else {
                item
            }
        }

    fun markTopicReadLocally(topicId: Int, seenPostsCount: Int, syncServer: Boolean = false) {
        if (topicId <= 0) return
        val effectiveCount = maxOf(locallyReadTopicPosts[topicId] ?: 0, seenPostsCount, 1)
        LinuxDoRepository.markTopicRead(topicId, effectiveCount)
        locallyReadTopicPosts[topicId] = effectiveCount
        if (syncServer) {
            val timings = LinkedHashMap<Int, Int>().apply {
                put(1, 1000)
                if (effectiveCount > 1) {
                    put(effectiveCount, 1000)
                }
            }
            ScreenTrackEngine.consolidateBatch(topicId, 1000, timings)
            scope.launch {
                val batch = ScreenTrackEngine.popNextSendableBatch() ?: return@launch
                var status: Int? = null
                try {
                    status = LinuxDoRepository.sendTopicTimings(batch)
                } finally {
                    ScreenTrackEngine.onBatchSendResult(batch, status)
                }
            }
        }
        topics = topics.map {
            if (it.id == topicId) it.copy(unseen = false, unreadPosts = 0, postsCount = maxOf(it.postsCount, seenPostsCount)) else it
        }
        discoverHotTopics = discoverHotTopics.map {
            if (it.id == topicId) it.copy(unseen = false, unreadPosts = 0, postsCount = maxOf(it.postsCount, seenPostsCount)) else it
        }
        pendingNewTopics = pendingNewTopics.map {
            if (it.id == topicId) it.copy(unseen = false, unreadPosts = 0, postsCount = maxOf(it.postsCount, seenPostsCount)) else it
        }
    }

    fun syncUserProfile(background: Boolean = false) {
        scope.launch {
            val profile = LinuxDoRepository.fetchCurrentUserProfile(background = background)
            if (profile != null) {
                fullUserProfile = profile
                launch { runCatching { LinuxDoRepository.fetchUserBadges(profile.username) } }
            }
            launch { runCatching { LinuxDoRepository.fetchAllSiteBadges() } }
            delay(1800L)
            launch { runCatching { LinuxDoRepository.syncSiteEmojis() } }
        }
    }

    fun applyPendingNewTopics() {
        if (pendingNewTopics.isNotEmpty()) {
            topics = normalizeReadTopics(pendingNewTopics)
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
        isManualRefresh: Boolean = false,
        excludedTopicId: Int? = null
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
                val fetched = kotlinx.coroutines.withTimeoutOrNull(24_000L) {
                    LinuxDoRepository.fetchTopics(
                        path = sort.apiPath,
                        categoryId = categoryId,
                        page = 0,
                        tag = tag,
                        topPeriod = if (sort == FeedSortFilter.TOP) topPeriod.apiPeriod else null
                    )
                } ?: throw ApiReadException(ApiFailureKind.Connection)
                if (fetched.isNotEmpty()) {
                    val isUnchanged = isManualRefresh &&
                        previousTopId != null &&
                        fetched.first().id == previousTopId &&
                        fetched.first().postsCount == previousTopPostsCount
                    val filtered = if (excludedTopicId == null) fetched else fetched.filterNot { it.id == excludedTopicId }
                    topics = normalizeReadTopics(filtered)
                    hasMoreTopics = fetched.size >= 15
                    if (isUnchanged) {
                        refreshUpToDateNotice = true
                        scope.launch {
                            delay(2200L)
                            refreshUpToDateNotice = false
                        }
                    }
                    feedListState.scrollToItem(0)
                } else {
                    topics = emptyList()
                    hasMoreTopics = false
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (error: Exception) {
                if (topics.isEmpty()) {
                    feedError = (error as? ApiReadException)?.message ?: "加载失败"
                    hasMoreTopics = false
                }
            } finally {
                if (isActive) isLoadingTopics = false
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
                    val uniqueNew = normalizeReadTopics(more.filterNot { it.id in existingIds })
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

    fun refreshDiscoverHotTopics() {
        if (isLoadingDiscoverHot) return
        scope.launch {
            isLoadingDiscoverHot = true
            try {
                val hot = LinuxDoRepository.fetchDiscoverHotTopics(limit = 5)
                if (hot.isNotEmpty()) {
                    discoverHotTopics = normalizeReadTopics(hot)
                }
            } finally {
                isLoadingDiscoverHot = false
            }
        }
    }

    LaunchedEffect(currentTab) {
        if (currentTab == MainTab.DISCOVER && discoverHotTopics.isEmpty()) {
            refreshDiscoverHotTopics()
        }
    }

    fun openOfficialProfileEditor() {
        if (isAuthorized) {
            showEditProfileSheet = true
        } else {
            activeWebLoginUrl = "https://linux.do/login"
        }
    }

    fun closeWebPage() {
        val wasEditingProfile = activeWebLoginUrl?.endsWith("/preferences/profile") == true
        activeWebLoginUrl = null
        if (wasEditingProfile) syncUserProfile()
    }

    var detailLoadJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun openTopic(topic: TopicSummary, targetPostNumber: Int? = null) {
        if (topic.id <= 0) return
        detailLoadJob?.cancel()
        val clearedTopic = topic.copy(unseen = false, unreadPosts = 0)
        markTopicReadLocally(topic.id, topic.postsCount, syncServer = true)
        activeTopicDetail = null
        activeTopicSummary = clearedTopic
        activeTargetPostNumber = targetPostNumber
        mobileShowingDetail = true
        detailLoadJob = scope.launch {
            isLoadingDetail = true
            try {
                val result = LinuxDoRepository.fetchTopicDetail(
                    topicId = topic.id,
                    summaryFallback = clearedTopic,
                    targetPostNumber = targetPostNumber
                )
                val loaded = if (result.unavailable && targetPostNumber != null && targetPostNumber > 1) {
                    LinuxDoRepository.fetchTopicDetail(topic.id, clearedTopic)
                } else result
                if (!loaded.unavailable) {
                    markTopicReadLocally(topic.id, maxOf(topic.postsCount, loaded.postsCount))
                }
                if (activeTopicSummary?.id == topic.id) activeTopicDetail = loaded
            } finally {
                if (activeTopicSummary?.id == topic.id) isLoadingDetail = false
            }
        }
    }

    fun returnFromUnavailableTopic() {
        val unavailableId = activeTopicSummary?.id
        detailLoadJob?.cancel()
        mobileShowingDetail = false
        activeTopicSummary = null
        activeTopicDetail = null
        activeTargetPostNumber = null
        pendingReplyDraft = null
        topics = topics.filterNot { it.id == unavailableId }
        loadTopics(isManualRefresh = true, excludedTopicId = unavailableId)
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
        val badgeMatch = Regex(
            """^https?://(?:www\.)?linux\.do/badges(?:/(\d+)(?:/([^/?#]+))?)?(?:[/?#].*)?$""",
            RegexOption.IGNORE_CASE
        ).find(trimmed)
        if (badgeMatch != null) {
            val bid = badgeMatch.groupValues.getOrNull(1)?.toIntOrNull()
            val bSlug = badgeMatch.groupValues.getOrNull(2)
            if (bid != null && bid > 0) {
                val resolved = BadgeInjector.lookupBadgeById(bid)
                    ?: GrantedBadge(id = bid, name = bSlug?.replace("-", " ") ?: "徽章 #$bid", slug = bSlug.orEmpty())
                inspectedBadge = resolved
            } else {
                badgesCenterUsername = fullUserProfile?.username ?: currentUser?.username
                showBadgesCenterSheet = true
            }
            return
        }
        val userBadgesMatch = Regex(
            """^https?://(?:www\.)?linux\.do/u/([^/?#]+)/badges(?:[/?#].*)?$""",
            RegexOption.IGNORE_CASE
        ).find(trimmed)
        if (userBadgesMatch != null) {
            val uname = userBadgesMatch.groupValues[1]
            if (uname.isNotBlank()) {
                badgesCenterUsername = uname
                showBadgesCenterSheet = true
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
        if (Regex("""^https?://connect\.linux\.do(?:/.*)?$""", RegexOption.IGNORE_CASE).matches(trimmed)) {
            activeWebLoginUrl = trimmed
            return
        }
        if (Regex("""^https?://(?:www\.)?linux\.do/about(?:[/?#].*)?$""", RegexOption.IGNORE_CASE).matches(trimmed)) {
            showAboutSheet = true
            return
        }
        if (Regex("""^https?://(?:www\.)?linux\.do/(?:leaderboard|u\?period=.*)(?:[/?#].*)?$""", RegexOption.IGNORE_CASE).matches(trimmed)) {
            showLeaderboardSheet = true
            return
        }
        if (Regex("""^https?://(?:www\.)?linux\.do/u/[^/?#]+/follow(?:[/?#].*)?$""", RegexOption.IGNORE_CASE).matches(trimmed)) {
            if (isAuthorized) showFollowSheet = true else activeWebLoginUrl = "https://linux.do/login"
            return
        }
        if (Regex("""^https?://(?:www\.)?linux\.do/(?:my|u/[^/?#]+)/preferences/profile(?:[/?#].*)?$""", RegexOption.IGNORE_CASE).matches(trimmed)) {
            if (isAuthorized) showEditProfileSheet = true else activeWebLoginUrl = "https://linux.do/login"
            return
        }
        if (Regex("""^https?://(?:www\.)?linux\.do/(?:my|u/[^/?#]+)/preferences(?:/.*)?$""", RegexOption.IGNORE_CASE).matches(trimmed)) {
            if (isAuthorized) showAccountPreferencesSheet = true else activeWebLoginUrl = "https://linux.do/login"
            return
        }
        activeWebLoginUrl = trimmed
    }

    LaunchedEffect(Unit) {
        launch { LinuxDoRepository.syncSiteCategories() }
        launch { refreshDiscoverHotTopics() }
        loadTopics(FeedSortFilter.LATEST, null)
        syncUserProfile(background = true)
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
                        topPeriod = if (currentSort == FeedSortFilter.TOP) currentTopPeriod.apiPeriod else null,
                        background = true
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
                        val merged = normalizeReadTopics(fresh + topics.filterNot { it.id in freshIds })
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
            showBadgesCenterSheet ||
            showGlobalSearchSheet ||
            showCreateTopicSheet ||
            showNotificationsSheet ||
            showActivitiesSheet ||
            showBookmarksSheet ||
            showReadHistorySheet ||
            showCloudDraftsSheet ||
            showPrivateMessagesSheet ||
            showEditProfileSheet ||
            showFollowSheet ||
            showLeaderboardSheet ||
            showAccountPreferencesSheet ||
            showAboutSheet ||
            showConnectSheet ||
            showAuthSheet ||
            mobileShowingDetail ||
            currentTab != MainTab.HOME
        BackNavigationBridge.canGoBack = canIntercept
        BackNavigationBridge.onBack = {
            when {
                activeWebLoginUrl != null -> {
                    closeWebPage()
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
                showBadgesCenterSheet -> {
                    showBadgesCenterSheet = false
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
                showFollowSheet -> {
                    showFollowSheet = false
                    true
                }
                showLeaderboardSheet -> {
                    showLeaderboardSheet = false
                    true
                }
                showAccountPreferencesSheet -> {
                    showAccountPreferencesSheet = false
                    true
                }
                showAboutSheet -> {
                    showAboutSheet = false
                    true
                }
                showConnectSheet -> {
                    showConnectSheet = false
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
        LocalLinDoAppIcon provides appIcon,
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
                    LaunchedEffect(isDesktop, isAuthorized, topics.isNotEmpty()) {
                        if (!isDesktop || !isAuthorized || activeTopicSummary != null) return@LaunchedEffect
                        val first = topics.firstOrNull { !it.pinned } ?: topics.firstOrNull() ?: return@LaunchedEffect
                        activeTopicSummary = first
                        detailLoadJob = scope.launch {
                            isLoadingDetail = true
                            try {
                                val loaded = LinuxDoRepository.fetchTopicDetail(first.id, first, trackVisit = false)
                                if (activeTopicSummary == first && !mobileShowingDetail) activeTopicDetail = loaded
                            } finally {
                                if (activeTopicSummary == first && !mobileShowingDetail) isLoadingDetail = false
                            }
                        }
                    }

                    if (!isAuthorized) {
                        GoogleWelcomeLoginScreen(
                            onOpenAppIcon = openAppIconPage,
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
                                            onUnavailable = ::returnFromUnavailableTopic,
                                            onDetailUpdated = { if (activeTopicSummary?.id == it.id) activeTopicDetail = it },
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
                                        hotTopics = discoverHotTopics.ifEmpty { topics.filterNot { it.pinned }.take(5) },
                                        isLoadingHot = isLoadingDiscoverHot,
                                        onRefreshHot = ::refreshDiscoverHotTopics,
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
                                        onManagePrivateMessages = {
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
                                        onOpenAppIcon = openAppIconPage,
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
                                        onOpenEditProfile = ::openOfficialProfileEditor,
                                        onOpenFollowSheet = { showFollowSheet = true },
                                        onOpenLeaderboardSheet = { showLeaderboardSheet = true },
                                        onOpenAccountPreferences = { showAccountPreferencesSheet = true },
                                        onOpenAboutSheet = { showAboutSheet = true },
                                        onInspectBadge = { inspectedBadge = it },
                                        onOpenBadgesCenter = {
                                            badgesCenterUsername = fullUserProfile?.username ?: currentUser?.username
                                            showBadgesCenterSheet = true
                                        },
                                        onOpenConnectSheet = { activeWebLoginUrl = "https://connect.linux.do/" },
                                        onOpenAuthSheet = { showAuthSheet = true },
                                        onOpenWebUrl = { openSmartUrl(it) },
                                        onPrimaryWebLogin = {
                                            activeWebLoginUrl = "https://linux.do/login"
                                        },
                                        onLogout = {
                                            showLogoutConfirm = true
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
                                onUnavailable = ::returnFromUnavailableTopic,
                                onDetailUpdated = { if (activeTopicSummary?.id == it.id) activeTopicDetail = it },
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
                                                hotTopics = discoverHotTopics.ifEmpty { topics.filterNot { it.pinned }.take(5) },
                                                isLoadingHot = isLoadingDiscoverHot,
                                                onRefreshHot = ::refreshDiscoverHotTopics,
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
                                                onManagePrivateMessages = {
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
                                                onOpenAppIcon = openAppIconPage,
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
                                                onOpenEditProfile = ::openOfficialProfileEditor,
                                                onOpenFollowSheet = { showFollowSheet = true },
                                                onOpenLeaderboardSheet = { showLeaderboardSheet = true },
                                                onOpenAccountPreferences = { showAccountPreferencesSheet = true },
                                                onOpenAboutSheet = { showAboutSheet = true },
                                                onInspectBadge = { inspectedBadge = it },
                                                onOpenBadgesCenter = {
                                                    badgesCenterUsername = fullUserProfile?.username ?: currentUser?.username
                                                    showBadgesCenterSheet = true
                                                },
                                                onOpenConnectSheet = { activeWebLoginUrl = "https://connect.linux.do/" },
                                                onOpenAuthSheet = { showAuthSheet = true },
                                                onOpenWebUrl = { openSmartUrl(it) },
                                                onPrimaryWebLogin = {
                                                    activeWebLoginUrl = "https://linux.do/login"
                                                },
                                                onLogout = {
                                                    showLogoutConfirm = true
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
                                ::closeWebPage,
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

                if (showAppIconPage && appIcon != null && onApplyAppIcon != null) {
                    LinDoAppIconPage(
                        current = appIcon,
                        onDismiss = { showAppIconPage = false },
                        onApply = onApplyAppIcon
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
                            if (draft.action.startsWith("edit") || draft.draftKey.startsWith("edit_post_")) {
                                pendingEditDraft = draft
                            } else if (draft.topicId > 0) {
                                pendingReplyDraft = draft
                                openTopicById(draft.topicId, draft.title, draft.replyToPostNumber)
                            } else {
                                pendingNewTopicDraft = draft
                                showCreateTopicSheet = true
                            }
                        }
                    )
                }

                if (showConnectSheet) {
                    ConnectTrustLevelSheet(
                        onDismiss = { showConnectSheet = false }
                    )
                }

                if (showEditProfileSheet) {
                    val uname = fullUserProfile?.username ?: currentUser?.username.orEmpty()
                    EditProfileModalSheet(
                        username = uname,
                        fullProfile = fullUserProfile,
                        onDismiss = { showEditProfileSheet = false },
                        onSaved = {
                            showEditProfileSheet = false
                            syncUserProfile()
                        }
                    )
                }

                if (showFollowSheet) {
                    val uname = fullUserProfile?.username ?: currentUser?.username.orEmpty()
                    UserFollowNetworkSheet(
                        username = uname,
                        onDismiss = { showFollowSheet = false },
                        onInspectUser = { targetUser -> inspectedUsername = targetUser },
                        onSelectActivity = { tid, title, postNum ->
                            showFollowSheet = false
                            openTopicById(tid, title, postNum)
                        }
                    )
                }

                if (showLeaderboardSheet) {
                    CommunityLeaderboardSheet(
                        onDismiss = { showLeaderboardSheet = false },
                        onInspectUser = { targetUser -> inspectedUsername = targetUser }
                    )
                }

                if (showAccountPreferencesSheet) {
                    val uname = fullUserProfile?.username ?: currentUser?.username.orEmpty()
                    UserAccountPreferencesSheet(
                        username = uname,
                        onDismiss = { showAccountPreferencesSheet = false },
                        onOpenEditProfile = {
                            showAccountPreferencesSheet = false
                            showEditProfileSheet = true
                        },
                        onUpdated = { syncUserProfile() }
                    )
                }

                if (showAboutSheet) {
                    CommunityAboutSheet(
                        onDismiss = { showAboutSheet = false },
                        onInspectUser = { targetUser -> inspectedUsername = targetUser }
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
                            showLogoutConfirm = true
                            showAuthSheet = false
                        },
                        onDismiss = { showAuthSheet = false }
                    )
                }

                inspectedUsername?.let { uname ->
                    PublicUserCardSheet(
                        username = uname,
                        onDismiss = { inspectedUsername = null },
                        onSendPrivateMessage = { targetUser ->
                            pmInitialRecipient = targetUser
                            showPrivateMessagesSheet = true
                        },
                        onSelectTopicId = { tid, title, postNum ->
                            inspectedUsername = null
                            openTopicById(tid, title, postNum)
                        },
                        onInspectBadge = { badge ->
                            inspectedBadge = badge
                        },
                        onOpenUserBadges = { targetUser ->
                            badgesCenterUsername = targetUser
                            showBadgesCenterSheet = true
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
                            inspectedUsername = null
                            openTopic(topic)
                        }
                    )
                }

                if (showBadgesCenterSheet) {
                    val isSelfBadges = badgesCenterUsername == null || badgesCenterUsername.equals(currentUser?.username, true)
                    CommunityBadgesSheet(
                        targetUsername = badgesCenterUsername ?: fullUserProfile?.username ?: currentUser?.username.orEmpty(),
                        currentUserTitle = if (isSelfBadges) fullUserProfile?.title ?: currentUser?.userTitle.orEmpty() else "",
                        initialEarnedBadges = if (isSelfBadges) fullUserProfile?.badges.orEmpty() else emptyList(),
                        initialEarnedCount = if (isSelfBadges) (fullUserProfile?.badgeCount?.coerceAtLeast(fullUserProfile?.badges?.size ?: 0) ?: 0) else 0,
                        onDismiss = { showBadgesCenterSheet = false },
                        onInspectBadge = { badge ->
                            inspectedBadge = badge
                        }
                    )
                }

                inspectedBadge?.let { badge ->
                    BadgeDetailBottomSheet(
                        badge = badge,
                        currentUsername = fullUserProfile?.username ?: currentUser?.username.orEmpty(),
                        currentUserTitle = fullUserProfile?.title ?: currentUser?.userTitle.orEmpty(),
                        isAuthorized = isAuthorized,
                        onDismiss = { inspectedBadge = null },
                        onTitleUpdated = { syncUserProfile() }
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

                pendingEditDraft?.let { draft ->
                    ComposerSheet(ComposerAction.EDIT, topicId = draft.topicId.takeIf { it > 0 }, topicTitle = draft.title,
                        initialDraft = draft, onDismiss = { pendingEditDraft = null }, onPublished = { result ->
                            result.post?.let { updated -> activeTopicDetail = activeTopicDetail?.let { detail ->
                                detail.copy(posts = detail.posts.map { if (it.id == updated.id) it.copy(raw = updated.raw, cookedHtml = updated.cookedHtml) else it })
                            } }
                            pendingEditDraft = null
                        })
                }

                if (showLogoutConfirm) LinDoConfirmDialog(title = "要退出当前账号吗？", confirmLabel = "退出",
                    onDismiss = { showLogoutConfirm = false }, onConfirm = {
                        showLogoutConfirm = false
                        UserApiKeyAuthManager.logout()
                        fullUserProfile = null
                        topics = emptyList()
                        activeTopicDetail = null
                        activeTopicSummary = null
                        mobileShowingDetail = false
                        currentTab = MainTab.HOME
                    })
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
internal fun GoogleM3NavigationBar(
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
internal fun CommunityHomeFeedPane(
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
    val isCompact = isCompactFeed
    val pinnedTopics = remember(topics, selectedCategoryId, selectedTag, searchQuery, currentSort) {
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
    val refreshAction by androidx.compose.runtime.rememberUpdatedState(onRefresh)
    LaunchedEffect(listState, isLoading, pullThresholdPx) {
        androidx.compose.runtime.snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling && !isLoading && pullDownPx > 0f) {
                val refresh = pullDownPx >= pullThresholdPx
                pullDownPx = 0f
                if (refresh) refreshAction()
            }
        }
    }

    val pullNestedScroll = remember(listState, isLoading, pullThresholdPx, maxPullPx) {
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
                    refreshAction()
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
            if (isLoading || isLoadingMore || !hasMoreTopics || loadMoreError || searchQuery.isNotBlank()) {
                return@derivedStateOf false
            }
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            val lastIdx = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            total > 4 && lastIdx >= total - 3
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
            // 1. 理想型社区品牌顶栏：Lindo + 真诚 · 友善 · 团结 · 专业 + 右侧刷新/通知/头像（整体高度与字号呼吸感升级）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.cardSurface)
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 9.dp)
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
                            fontSizeSp = 24.5f,
                            iconSize = 36.dp,
                            showMotto = true
                        )
                    }

                    // 顶部通知铃铛（带主题色未读小红点/琥珀点）
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .clickable { onOpenNotifications() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = LinDoIcons.Bell,
                            contentDescription = "消息通知",
                            modifier = Modifier.size(19.5.dp),
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

                    // 纯净圆形个人头像（与左侧零度娘 icon 严格同尺寸 36.dp）
                    GoogleAccountRingAvatar(
                        letter = currentUser?.displayName?.takeIf { it.isNotBlank() }
                            ?: currentUser?.username
                            ?: "L",
                        avatarUrl = currentUser?.avatarTemplate,
                        animatedAvatar = currentUser?.animatedAvatar,
                        avatarSize = 36.dp,
                        onClick = onAvatarClick
                    )
                }

                Spacer(modifier = Modifier.height(11.dp))

                // 2. 统一大圆角胶囊搜索入口（与新动态胶囊、底栏胶囊圆角体系严格统一，点击唤起全站实时搜索）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .clip(CircleShape)
                        .background(palette.subtleSurface)
                        .border(0.6.dp, palette.hairline, CircleShape)
                        .clickable { onOpenGlobalSearch() }
                        .padding(horizontal = 15.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = LinDoIcons.Search,
                        contentDescription = "全站搜索",
                        modifier = Modifier.size(15.5.dp),
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
                                fontSize = 13.2.sp,
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

                Spacer(modifier = Modifier.height(12.dp))

                var isCategoryBarExpanded by remember { mutableStateOf(false) }
                val activeCategoryMeta = remember(selectedCategoryId, LinuxDoRepository.categories.size) {
                    LinuxDoRepository.categories.firstOrNull { it.id == selectedCategoryId }
                }
                LaunchedEffect(isCategoryBarExpanded, listState) {
                    if (!isCategoryBarExpanded) return@LaunchedEffect
                    androidx.compose.runtime.snapshotFlow { listState.firstVisibleItemIndex > 0 }
                        .collect { scrolledDown ->
                            if (scrolledDown) {
                                isCategoryBarExpanded = false
                            }
                        }
                }

                // 3. 统一字阶频道 Tab（选中 17sp Bold，未选中 15sp Normal，底部 14x2.5dp 胶囊横条）+ 右侧「分类 ⌄」与布局切换严格底部对齐
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(22.dp),
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
                                    fontSize = if (selected) LinDoTokens.Title else LinDoTokens.Body,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) palette.inkPrimary else palette.inkSecondary,
                                    maxLines = 1,
                                    softWrap = false
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Box(
                                    modifier = Modifier
                                        .width(14.dp)
                                        .height(2.5.dp)
                                        .clip(CircleShape)
                                        .background(if (selected) palette.tabIndicatorColor else Color.Transparent)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // 右侧「分类 ⌄」与布局切换图标：与左侧 Tab 严格同基线
                    Row(
                        modifier = Modifier.padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .clip(LinDoTokens.ShapeXS)
                                .clickable { isCategoryBarExpanded = !isCategoryBarExpanded },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "分类",
                                fontSize = LinDoTokens.Sub,
                                fontWeight = if (isCategoryBarExpanded || activeCategoryMeta != null) FontWeight.Bold else FontWeight.Normal,
                                color = if (isCategoryBarExpanded || activeCategoryMeta != null) palette.activeChipText else palette.inkSecondary,
                                maxLines = 1,
                                softWrap = false
                            )
                            Icon(
                                imageVector = if (isCategoryBarExpanded) LinDoIcons.ChevronUp else LinDoIcons.ChevronDown,
                                contentDescription = if (isCategoryBarExpanded) "收起分类筛选" else "展开分类筛选",
                                modifier = Modifier.size(LinDoTokens.IconXS),
                                tint = if (isCategoryBarExpanded || activeCategoryMeta != null) palette.activeChipText else palette.inkSecondary
                            )
                        }

                        // 右侧「宽松 / 紧凑」展示形式切换（统一 16dp IconS）
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(LinDoTokens.ShapeXS)
                                .clickable { onToggleCompactMode() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isCompact) LinDoIcons.LayoutCompact else LinDoIcons.LayoutCards,
                                contentDescription = "切换列表展示形式",
                                modifier = Modifier.size(LinDoTokens.IconS),
                                tint = if (isCompact) palette.activeChipText else palette.inkSecondary
                            )
                        }
                    }
                }

                // 4. 可折叠纯文字分类筛选条（点击「分类」展开，选择版块或下滑列表时自动平滑收起）
                AnimatedVisibility(visible = isCategoryBarExpanded) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GoogleM3FilterPill(
                            label = "全部",
                            dotColor = null,
                            selected = selectedCategoryId == null,
                            modifier = Modifier.heightIn(min = 36.dp),
                            onClick = {
                                onSelectCategory(null)
                                isCategoryBarExpanded = false
                            }
                        )

                        LinuxDoRepository.categories.forEach { category ->
                            val (_, catTextColor) = resolveMorandiCategoryColors(category.name, palette.isDark)
                            GoogleM3FilterPill(
                                label = category.name,
                                dotColor = catTextColor,
                                selected = selectedCategoryId == category.id,
                                modifier = Modifier.heightIn(min = 36.dp),
                                onClick = {
                                    onSelectCategory(
                                        if (selectedCategoryId == category.id) null else category.id
                                    )
                                    isCategoryBarExpanded = false
                                }
                            )
                        }
                    }
                }

                // 4.5 已选分类 / 已选 #Tag / 热门时间维度：单独一行展示，绝不挤占上方频道 Tab 栏
                if (activeCategoryMeta != null || selectedTag != null || currentSort == FeedSortFilter.TOP) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (activeCategoryMeta != null) {
                            val activeCatColor = resolveMorandiCategoryColors(activeCategoryMeta.name, palette.isDark).second
                            Row(
                                modifier = Modifier
                                    .height(26.dp)
                                    .clip(RoundedCornerShape(99.dp))
                                    .background(palette.subtleSurface)
                                    .clickable(role = Role.Button, onClickLabel = "清除分类筛选") { onSelectCategory(null) }
                                    .padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(activeCatColor)
                                )
                                Text(
                                    text = activeCategoryMeta.name,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = activeCatColor,
                                    maxLines = 1,
                                    softWrap = false
                                )
                                Icon(
                                    imageVector = LinDoIcons.Close,
                                    contentDescription = "清除分类筛选",
                                    modifier = Modifier.size(12.5.dp),
                                    tint = palette.inkSecondary
                                )
                            }
                        }

                        if (selectedTag != null) {
                            Row(
                                modifier = Modifier
                                    .widthIn(max = 220.dp)
                                    .height(26.dp)
                                    .clip(RoundedCornerShape(99.dp))
                                    .background(palette.subtleSurface)
                                    .clickable(role = Role.Button, onClickLabel = "清除标签筛选") { onSelectTag(null) }
                                    .padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Text(
                                    text = "#$selectedTag",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = palette.activeChipText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Icon(LinDoIcons.Close, null, Modifier.size(12.5.dp), tint = palette.inkSecondary)
                            }
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

            // 4.8 独立的新动态提示条（占据独立 40dp 高度，绝不遮挡第一条帖子的作者或标题）
            if (pendingNewCount > 0 && !isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .background(palette.cardSurface),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        modifier = Modifier
                            .height(28.dp)
                            .clip(CircleShape)
                            .background(palette.subtleSurface)
                            .border(
                                width = 0.5.dp,
                                color = palette.brandGold.copy(alpha = 0.45f),
                                shape = CircleShape
                            )
                            .clickable(onClick = onApplyPendingNewTopics)
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = LinDoIcons.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = palette.brandGold
                        )
                        Text(
                            text = "$pendingNewCount 条新动态",
                            color = palette.brandGold,
                            fontSize = LinDoTokens.Meta,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }
                HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
            }

            // 5. 话题流（统一采用通栏「轻分割线」风格，宽松与紧凑模式仅区分展示内容丰富度与排版密度）
            val feedContainerBg = palette.cardSurface
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(feedContainerBg)
                    .nestedScroll(pullNestedScroll)
            ) {
                if (isLoading && topics.isEmpty()) {
                    LinearShimmerTopicSkeletonList(useIconArtwork = true)
                } else if (topics.isEmpty() && !isLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (errorMessage != null) {
                            LinDoStatePanel(
                                LinDoMascotState.NetworkError,
                                detail = errorMessage,
                                onRetry = onRefresh,
                                useIconArtwork = true
                            )
                        } else {
                            LinDoStatePanel(
                                LinDoMascotState.Empty,
                                title = "暂无话题",
                                detail = null,
                                onRetry = onRefresh,
                                useIconArtwork = true
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(feedContainerBg),
                        contentPadding = PaddingValues(
                            top = 2.dp,
                            bottom = 128.dp
                        )
                    ) {
                        // 置顶公告条（统一轻分割线通栏样式，告别生硬边框盒子）
                        if (pinnedTopics.isNotEmpty()) {
                            item(key = "pinned_banner_group", contentType = "pinned") {
                                val displayedPinned = pinnedTopics.take(2)
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(palette.cardSurface)
                                ) {
                                    displayedPinned.forEach { pinned ->
                                        val isPinnedUnread = (pinned.unseen || pinned.unreadPosts > 0) && !LinuxDoRepository.isTopicRead(pinned.id)
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { onSelectTopic(pinned) }
                                                .padding(
                                                    horizontal = 16.dp,
                                                    vertical = if (isCompact) 7.5.dp else 9.5.dp
                                                ),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "置顶",
                                                fontSize = 11.2.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = palette.brandGold,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = pinned.title,
                                                fontSize = if (isCompact) 13.2.sp else 13.8.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = palette.inkPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            if (isPinnedUnread) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Box(
                                                    modifier = Modifier
                                                        .size(6.dp)
                                                        .clip(CircleShape)
                                                        .background(if (palette.isDark) Color(0xFF38BDF8) else Color(0xFF0088CC))
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Icon(
                                                imageVector = LinDoIcons.ChevronRight,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp),
                                                tint = palette.inkTertiary
                                            )
                                        }
                                        HorizontalDivider(
                                            modifier = Modifier.padding(horizontal = 16.dp),
                                            thickness = 0.5.dp,
                                            color = palette.hairline
                                        )
                                    }
                                }
                            }
                        }

                        itemsIndexed(regularTopics, key = { _, item -> item.id }, contentType = { _, _ -> isCompact }) { idx, topic ->
                            CommunityTopicItem(
                                topic = topic,
                                isSelected = topic.id == selectedTopicId,
                                isCompact = isCompact,
                                enableAnimations = enableAnimations,
                                onClick = { onSelectTopic(topic) }
                            )
                            if (idx < regularTopics.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    thickness = 0.5.dp,
                                    color = palette.hairline
                                )
                            }
                        }

                        // 底部波浪呼吸脉冲分页加载栏（触底无更多时展示零度娘“没有更多了”）
                        if (regularTopics.isNotEmpty()) {
                            item(key = "load_more_footer", contentType = "footer") {
                                LinearPulseLoadingFooter(
                                    isLoadingMore = isLoadingMore,
                                    hasMore = hasMoreTopics,
                                    loadedCount = regularTopics.size,
                                    isError = loadMoreError,
                                    useIconArtwork = true,
                                    onLoadMore = onLoadMore
                                )
                            }
                        }
                    }
                }

                // 顶部悬浮下拉刷新指示胶囊 & “已是最新，没有更多新内容了”零度娘反馈胶囊（Overlay 悬浮，不挤推列表）
                val pullStage by remember(pullDeadZonePx, pullThresholdPx) {
                    derivedStateOf {
                        when {
                            pullDownPx >= pullThresholdPx -> 2
                            pullDownPx > pullDeadZonePx -> 1
                            else -> 0
                        }
                    }
                }
                val showPullOverlay = pullStage > 0 || (isLoading && topics.isNotEmpty())
                if (showPullOverlay || refreshUpToDateNotice) {
                    val ready = pullStage == 2
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
                                animate = isLoading || showPullOverlay,
                                useIconArtwork = true
                            )
                            Text(
                                text = pillText,
                                fontSize = 12.sp,
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
private fun LinDoSectionTabs(
    labels: List<String>,
    selectedIndex: Int,
    fontSize: TextUnit = 15.sp,
    onSelect: (Int) -> Unit
) {
    val palette = LocalPalette.current
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        labels.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val interactionSource = remember { MutableInteractionSource() }
            val focused by interactionSource.collectIsFocusedAsState()
            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .selectable(
                        selected = selected,
                        interactionSource = interactionSource,
                        indication = null,
                        role = Role.Tab,
                        onClick = { onSelect(index) }
                    )
                    .padding(horizontal = 3.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    fontSize = fontSize,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = when {
                        selected -> palette.inkPrimary
                        focused -> palette.brandGold
                        else -> palette.inkSecondary
                    },
                    maxLines = 1,
                    softWrap = false
                )
                if (selected) {
                    Box(Modifier.align(Alignment.BottomCenter).width(14.dp).height(3.dp)
                        .clip(CircleShape).background(palette.tabIndicatorColor))
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
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val palette = LocalPalette.current
    val selectedColor = dotColor ?: palette.activeChipText
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab,
                onClick = onClick
            )
            .padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = when {
                selected -> selectedColor
                focused -> palette.inkPrimary
                else -> palette.inkSecondary
            },
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
    val replies = maxOf(0, topic.postsCount - 1).coerceAtLeast(topic.replyCount)
    val rowBgColor = if (isSelected) palette.subtleSurface else palette.cardSurface
    val itemInteractionSource = remember { MutableInteractionSource() }
    val isItemPressed by itemInteractionSource.collectIsPressedAsState()
    val pressBackground = Modifier.drawBehind {
        drawRect(if (isItemPressed) palette.subtleSurface else rowBgColor)
    }
    val isTopicUnread = (topic.unseen || topic.unreadPosts > 0) && !LinuxDoRepository.isTopicRead(topic.id)
    val unreadDotAlpha = if (isTopicUnread) 1f else 0f
    val formattedViews = remember(topic.views) {
        if (topic.views > 0) formatCompactNumber(topic.views) else ""
    }

    if (isCompact) {
        // 模式 B：紧凑列表模式（首行 15sp 单行标题 + 未读蓝点 + 12sp 评论数；次行 16dp Tonal 头像 + 作者 + ● 版块名 + #Tag + 时间）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(pressBackground)
                .clickable(
                    interactionSource = itemInteractionSource,
                    indication = null,
                    onClick = onClick
                )
                .padding(horizontal = LinDoTokens.PageHorizontalPadding, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = topic.title,
                    fontSize = LinDoTokens.Body,
                    fontWeight = FontWeight.Medium,
                    color = palette.inkPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .graphicsLayer { alpha = if (isTopicUnread) 1f else 0f }
                        .clip(CircleShape)
                        .background(if (palette.isDark) Color(0xFF38BDF8) else Color(0xFF0088CC))
                )

                Spacer(modifier = Modifier.width(8.dp))

                LinDoStatItem(
                    icon = LinDoIcons.MessageCircle,
                    text = "$replies"
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

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

                Spacer(modifier = Modifier.width(6.dp))

                Text(
                    text = authorDisplay,
                    fontSize = LinDoTokens.Meta,
                    fontWeight = FontWeight.Normal,
                    color = palette.inkSecondary,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 88.dp)
                )

                Spacer(modifier = Modifier.width(8.dp))

                LinDoCategoryLabel(
                    name = topic.categoryName,
                    dotColor = catDotColor,
                    dotSize = 6.dp
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
                                fontSize = LinDoTokens.Meta,
                                color = palette.inkTertiary,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                if (topic.lastPostedAt.isNotBlank()) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = topic.lastPostedAt,
                        fontSize = LinDoTokens.Meta,
                        color = palette.inkTertiary,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    } else {
        // 模式 A：宽松列表模式（20dp Tonal 头像 + 作者 · 时间 | 右侧 ● 版块名；16sp SemiBold 标题；14sp 摘要；#Tag | ♡ 💬 👁 统一 12sp 次级色）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(pressBackground)
                .clickable(
                    interactionSource = itemInteractionSource,
                    indication = null,
                    onClick = onClick
                )
                .padding(horizontal = LinDoTokens.PageHorizontalPadding, vertical = 12.dp)
        ) {
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
                        size = 20.dp,
                        enableAnimations = false
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = authorDisplay,
                        fontSize = LinDoTokens.Meta,
                        fontWeight = FontWeight.Medium,
                        color = palette.inkSecondary,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    if (topic.lastPostedAt.isNotBlank()) {
                        Text(
                            text = " · ${topic.lastPostedAt}",
                            fontSize = LinDoTokens.Meta,
                            color = palette.inkTertiary,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                LinDoCategoryLabel(
                    name = topic.categoryName,
                    dotColor = catDotColor,
                    dotSize = 7.dp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = topic.title,
                    fontSize = LinDoTokens.Headline,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 22.sp,
                    color = palette.inkPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .padding(top = 7.dp)
                        .size(6.dp)
                        .graphicsLayer { alpha = if (isTopicUnread) 1f else 0f }
                        .clip(CircleShape)
                        .background(if (palette.isDark) Color(0xFF38BDF8) else Color(0xFF0088CC))
                )
            }

            val initialCachedExcerpt = remember(topic.id, topic.excerpt) {
                topic.excerpt?.trim()?.takeIf { it.isNotEmpty() }
                    ?: LinuxDoRepository.getCachedTopicExcerpt(topic.id)
            }
            var resolvedExcerpt by remember(topic.id, initialCachedExcerpt) {
                mutableStateOf(initialCachedExcerpt)
            }
            var excerptFetchDone by remember(topic.id, initialCachedExcerpt) {
                mutableStateOf(initialCachedExcerpt != null || LinuxDoRepository.hasCachedTopicExcerpt(topic.id))
            }
            if (!excerptFetchDone && topic.id > 0 && !topic.pinned) {
                LaunchedEffect(topic.id) {
                    val fetched = LinuxDoRepository.fetchTopicExcerpt(topic.id)
                    if (!fetched.isNullOrBlank()) {
                        resolvedExcerpt = fetched
                    }
                    excerptFetchDone = true
                }
            }
            val cleanExcerpt = resolvedExcerpt?.takeIf { it.isNotBlank() }
                ?: if (excerptFetchDone && !topic.pinned) topic.title else null
            if (!topic.pinned || cleanExcerpt != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Box(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = cleanExcerpt ?: " \n ",
                        fontSize = LinDoTokens.Sub,
                        lineHeight = 20.sp,
                        color = if (cleanExcerpt != null) palette.inkSecondary else Color.Transparent,
                        minLines = 2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (cleanExcerpt == null) {
                        TopicExcerptSkeleton(
                            modifier = Modifier.matchParentSize()
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

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
                            if (result.isEmpty() || (result.size < 3 && usedUnits + tagUnits <= 26)) {
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
                                fontSize = LinDoTokens.Meta,
                                fontWeight = FontWeight.Normal,
                                color = palette.inkTertiary,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (remainingCount > 0) {
                            Text(
                                text = "+$remainingCount",
                                fontSize = LinDoTokens.Caption,
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

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    LinDoStatItem(
                        icon = LinDoIcons.Heart,
                        text = "${topic.likeCount}"
                    )
                    LinDoStatItem(
                        icon = LinDoIcons.MessageCircle,
                        text = "$replies"
                    )
                    if (topic.views > 0) {
                        LinDoStatItem(
                            icon = LinDoIcons.Eye,
                            text = formattedViews
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

@Composable
private fun TopicExcerptSkeleton(
    modifier: Modifier = Modifier
) {
    val palette = LocalPalette.current
    val transition = rememberInfiniteTransition(label = "excerptSkeleton")
    val pulseAlpha by transition.animateFloat(
        initialValue = if (palette.isDark) 0.06f else 0.05f,
        targetValue = if (palette.isDark) 0.13f else 0.11f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 750, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "excerptSkeletonAlpha"
    )
    val barColor = palette.inkSecondary.copy(alpha = pulseAlpha)
    Column(
        modifier = modifier.padding(vertical = 3.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .height(13.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(barColor)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.62f)
                .height(13.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(barColor)
        )
    }
}

/**
 * Gemini 风格帖子详情阅读器：
 * - 顶栏：Tonal 圆形按钮 + 楼主身份胶囊
 * - 卡片式阅读流：主帖与回复均位于柔和的 `#FFFFFF` 圆角卡片中，告别杂乱分割线
 * - 底栏：Gemini 标志性悬浮胶囊输入栏（Floating Prompt Pill）
 */
@Composable
internal fun CommunityTopicReaderPane(
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
    onUnavailable: () -> Unit = onBack,
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
    var showRemoveBookmark by remember(summary.id) { mutableStateOf(false) }
    var bookmarkBusy by remember(summary.id) { mutableStateOf(false) }
    var bookmarkError by remember(summary.id) { mutableStateOf<String?>(null) }
    fun setBookmark(value: Boolean) {
        if (bookmarkBusy) return
        bookmarkBusy = true
        bookmarkError = null
        scope.launch {
            try {
                if (LinuxDoRepository.setTopicBookmark(summary.id, value)) {
                    isBookmarked = value
                    showRemoveBookmark = false
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { bookmarkError = error.message ?: "收藏操作失败" }
            finally { bookmarkBusy = false }
        }
    }
    var onlyOpFilter by remember(summary.id) { mutableStateOf(false) }
    var changingReaderFilter by remember(summary.id) { mutableStateOf(false) }
    var notificationLevel by remember(summary.id) { mutableStateOf(1) } // 1=常规, 2=跟踪, 3=关注, 0=免扰
    var isLoadingMorePosts by remember(summary.id) { mutableStateOf(false) }
    var loadMorePostsError by remember(summary.id) { mutableStateOf(false) }
    var copiedShareLink by remember(summary.id) { mutableStateOf(false) }
    var showFloorNavigator by remember(summary.id) { mutableStateOf(false) }

    // 回复编辑器状态
    var showReplySheet by remember(summary.id) { mutableStateOf(false) }
    var replyTargetPostNumber by remember(summary.id) { mutableStateOf<Int?>(null) }
    var replyTargetUsername by remember(summary.id) { mutableStateOf<String?>(null) }
    var replyInitialQuote by remember(summary.id) { mutableStateOf("") }
    var replyCloudDraft by remember(summary.id) { mutableStateOf<CloudDraftItem?>(null) }

    var pendingDeletePost by remember(summary.id) { mutableStateOf<PostItemModel?>(null) }
    var deletingPost by remember(summary.id) { mutableStateOf(false) }
    var deletePostError by remember(summary.id) { mutableStateOf<String?>(null) }

    // 编辑本人已发楼层状态
    var editingPost by remember(summary.id) { mutableStateOf<PostItemModel?>(null) }

    val catColor = remember(detail?.categoryColorHex ?: summary.categoryColorHex) {
        LinuxDoSiteRules.parseHexColor(detail?.categoryColorHex ?: summary.categoryColorHex)
    }
    val op = summary.originalPoster
    val posts = detail?.posts.orEmpty()
    val postsByNumber = remember(posts) { posts.associateBy { it.postNumber } }
    val repliesByParent = remember(posts) {
        posts.filter { it.replyToPostNumber != null }.groupBy { it.replyToPostNumber }
    }
    val effectiveOpUsername = remember(posts, op.username, detail?.originalPosterUsername) {
        detail?.originalPosterUsername ?: posts.firstOrNull { it.postNumber == 1 }?.username?.takeIf { it.isNotBlank() } ?: op.username
    }
    val streamIds = detail?.streamIds.orEmpty()
    val loadedIds = remember(posts) { posts.map { it.id }.toSet() }
    val latestDetail by androidx.compose.runtime.rememberUpdatedState(detail)
    val remainingPostIds = remember(streamIds, loadedIds) { remainingUnloadedStreamIds(streamIds, loadedIds) }
    val previousPostIds = remember(streamIds, loadedIds) { previousUnloadedStreamIds(streamIds, loadedIds) }
    var pendingJumpFloor by remember(summary.id) { mutableStateOf<Int?>(null) }
    var jumpFetchedWindow by remember(summary.id) { mutableStateOf(false) }
    var fetchingJumpWindow by remember(summary.id) { mutableStateOf(false) }
    var navigationError by remember(summary.id) { mutableStateOf<String?>(null) }
    var filterError by remember(summary.id) { mutableStateOf<String?>(null) }
    var pendingReturnFloor by remember(summary.id) { mutableStateOf<Int?>(null) }
    var replyReturnFloor by remember(summary.id) { mutableStateOf<Int?>(null) }
    var highlightedFloor by remember(summary.id) { mutableStateOf<Int?>(null) }
    var pendingJumpRequiresExact by remember(summary.id) { mutableStateOf(false) }
    fun jumpToFloor(number: Int, returnTo: Int? = null, exact: Boolean = false) {
        if (changingReaderFilter || fetchingJumpWindow) return
        pendingJumpFloor = number
        pendingReturnFloor = returnTo
        pendingJumpRequiresExact = exact
        jumpFetchedWindow = false
        navigationError = null
        if (posts.any { it.postNumber == number }) return
        fetchingJumpWindow = true
        scope.launch {
            try {
                val window = LinuxDoRepository.fetchTopicDetail(
                    summary.id, summary, trackVisit = false,
                    filterUsername = effectiveOpUsername.takeIf { onlyOpFilter },
                    targetPostNumber = number
                )
                if (window.loadError != null) {
                    navigationError = window.loadError
                } else if ((exact || returnTo != null) && window.posts.none { it.postNumber == number }) {
                    navigationError = if (onlyOpFilter) "此楼层不在只看楼主范围内" else "该楼层暂不可见"
                } else {
                    jumpFetchedWindow = true
                    latestDetail?.let { onDetailUpdated(it.copy(posts = mergeTopicPosts(it.posts, window.posts))) }
                }
            } finally {
                fetchingJumpWindow = false
            }
        }
    }
    val postCount = detail?.postsCount ?: summary.postsCount
    val totalFloorCount = maxOf(1, detail?.highestPostNumber ?: postCount, posts.maxOfOrNull { it.postNumber } ?: 1)

    val readerListState = rememberLazyListState()
    val floorByPostId = remember(posts) { posts.associate { it.id to it.postNumber } }
    val visibleFloorState = remember(readerListState, floorByPostId) {
        derivedStateOf {
            readerListState.layoutInfo.visibleItemsInfo.asReversed().firstNotNullOfOrNull {
                floorByPostId[it.key as? Int]
            } ?: 1
        }
    }
    LaunchedEffect(pendingJumpFloor, jumpFetchedWindow, posts) {
        val number = pendingJumpFloor ?: return@LaunchedEffect
        val index = resolveJumpPostIndex(posts, number, allowNearestFallback = jumpFetchedWindow)
        if (index != null) {
            val itemIndex = index + 1
            if (kotlin.math.abs(itemIndex - readerListState.firstVisibleItemIndex) <= 4) {
                readerListState.animateScrollToItem(itemIndex)
            } else {
                readerListState.scrollToItem(itemIndex)
            }
            replyReturnFloor = pendingReturnFloor
            highlightedFloor = posts[index].postNumber
            pendingJumpFloor = null
            pendingReturnFloor = null
            jumpFetchedWindow = false
        }
    }
    LaunchedEffect(highlightedFloor) {
        if (highlightedFloor != null) {
            delay(1600)
            highlightedFloor = null
        }
    }

    // 若从云端草稿恢复话题回复，自动填充并展开回复抽屉
    LaunchedEffect(initialReplyDraft, summary.id) {
        val draft = initialReplyDraft ?: return@LaunchedEffect
        if (draft.topicId == summary.id) {
            replyTargetPostNumber = draft.replyToPostNumber
            replyTargetUsername = null
            replyInitialQuote = draft.raw.ifBlank { draft.excerpt }
            replyCloudDraft = draft
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

    fun loadMoreFloorBatch(batchSize: Int = 20, earlier: Boolean = false) {
        val currentDetail = detail ?: return
        if (changingReaderFilter || isLoadingMorePosts || (if (earlier) previousPostIds else remainingPostIds).isEmpty()) return
        scope.launch {
            isLoadingMorePosts = true
            loadMorePostsError = false
            try {
                val batchIds = if (earlier) previousPostIds.takeLast(batchSize) else remainingPostIds.take(batchSize)
                val knownAuthorBadges = currentDetail.posts.associate { it.userId to it.authorBadges }
                val morePosts = LinuxDoRepository.fetchMoreTopicPosts(summary.id, batchIds, knownAuthorBadges)
                if (morePosts.isNotEmpty()) {
                    val merged = mergeTopicPosts(latestDetail?.posts.orEmpty(), morePosts)
                    latestDetail?.let { onDetailUpdated(it.copy(posts = merged)) }
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
    val shouldLoadMoreFloors by remember(
        readerListState, posts.size, remainingPostIds.size, isLoadingMorePosts, loadMorePostsError, changingReaderFilter
    ) {
        derivedStateOf {
            posts.isNotEmpty() && remainingPostIds.isNotEmpty() && !isLoadingMorePosts &&
                !loadMorePostsError && !changingReaderFilter &&
                (readerListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= posts.size - 2
        }
    }
    LaunchedEffect(shouldLoadMoreFloors, posts.size) {
        if (shouldLoadMoreFloors) loadMoreFloorBatch(20)
    }

    LaunchedEffect(readerListState, posts) {
        if (posts.isEmpty()) return@LaunchedEffect
        // Prepare a small window around the viewport off the main thread.
        androidx.compose.runtime.snapshotFlow { readerListState.firstVisibleItemIndex / 4 }.collectLatest { page ->
            withContext(Dispatchers.Default) {
                val first = (page * 4 - 4).coerceAtLeast(0)
                val end = (page * 4 + 12).coerceAtMost(posts.size)
                for (index in first until end) {
                    ensureActive()
                    val post = posts[index]
                    if (post.cookedHtml.length <= 32_000) {
                        DiscourseAstParser.parseOrGetCached(post.id, post.cookedHtml)
                    }
                }
            }
        }
    }

    val latestReaderPosts by androidx.compose.runtime.rememberUpdatedState(posts)
    val postFirstSeenAtMs = remember(summary.id) { mutableMapOf<Int, Long>() }
    val locallyReadPostNumbers = remember(summary.id) { androidx.compose.runtime.mutableStateMapOf<Int, Boolean>() }

    LaunchedEffect(summary.id) {
        ScreenTrackEngine.onTopicEntered(topicId = summary.id, initialVisiblePosts = emptySet())
        var tickAccumulatorMs = 0L
        while (true) {
            delay(400L)
            val nowMs = System.currentTimeMillis()
            if (readerListState.isScrollInProgress) ScreenTrackEngine.onUserScrolled(nowMs)
            val visiblePostNums = readerListState.layoutInfo.visibleItemsInfo.mapNotNull { item ->
                latestReaderPosts.getOrNull(item.index - 1)?.postNumber
            }.toSet()
            for (postNum in visiblePostNums) {
                val firstSeen = postFirstSeenAtMs.getOrPut(postNum) { nowMs }
                if (nowMs - firstSeen >= 1100L && locallyReadPostNumbers[postNum] != true) {
                    locallyReadPostNumbers[postNum] = true
                    ScreenTrackEngine.markPostReadLocally(summary.id, postNum)
                }
            }
            ScreenTrackEngine.updateVisiblePosts(visiblePostNums, nowMs)
            tickAccumulatorMs += 400L
            if (tickAccumulatorMs >= 1000L) {
                tickAccumulatorMs = 0L
                ScreenTrackEngine.tick(nowMs)?.let { batch ->
                    ScreenTrackEngine.consolidateBatch(batch.topicId, batch.topicTimeMs, batch.timingsMs)
                }
            }
        }
    }
    LaunchedEffect(summary.id, readerListState) {
        var wasScrolling = false
        androidx.compose.runtime.snapshotFlow { readerListState.isScrollInProgress }.collect { scrolling ->
            if (scrolling || wasScrolling) ScreenTrackEngine.onUserScrolled()
            wasScrolling = scrolling
        }
    }
    DisposableEffect(summary.id) {
        onDispose { ScreenTrackEngine.onTopicExited(summary.id) }
    }

    var showTopMoreMenu by remember(summary.id) { mutableStateOf(false) }
    var expandTopBarTitle by remember(summary.id) { mutableStateOf(false) }
    var showAiSummaryPanel by remember(summary.id) { mutableStateOf(false) }
    var isLoadingAiSummary by remember(summary.id) { mutableStateOf(false) }
    var remoteAiSummary by remember(summary.id) { mutableStateOf<String?>(null) }
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

    val localSmartDigest = remember(showAiSummaryPanel, posts, resolvedTitle) {
        if (!showAiSummaryPanel || posts.isEmpty()) {
            ""
        } else {
            fun cleanHtmlSnippet(html: String, maxChars: Int): String {
                return html
                    .replace(Regex("<aside[\\s\\S]*?</aside>"), "")
                    .replace(Regex("<[^>]+>"), " ")
                    .replace("&nbsp;", " ")
                    .replace("&quot;", "\"")
                    .replace("&amp;", "&")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(maxChars)
            }
            val opPost = posts.firstOrNull { it.postNumber == 1 } ?: return@remember ""
            val opText = cleanHtmlSnippet(opPost.cookedHtml, 180)
            val topReplies = posts.filter { it.postNumber != 1 }
                .sortedByDescending { p -> p.likeCount + p.reactions.sumOf { it.count } }
                .take(3)
            buildString {
                if (opText.isNotBlank()) {
                    append("• 楼主 @")
                    append(opPost.displayName?.takeIf { it.isNotBlank() } ?: opPost.username)
                    append("：")
                    append(opText)
                }
                topReplies.forEach { reply ->
                    val rText = cleanHtmlSnippet(reply.cookedHtml, 110)
                    if (rText.isNotBlank()) {
                        if (isNotEmpty()) append("\n\n")
                        append("• #")
                        append(reply.postNumber)
                        append(" @")
                        append(reply.displayName?.takeIf { it.isNotBlank() } ?: reply.username)
                        append("：")
                        append(rText)
                    }
                }
            }
        }
    }

    fun toggleOnlyOpMode() {
        if (changingReaderFilter || fetchingJumpWindow || isLoadingMorePosts) return
        val nextState = !onlyOpFilter
        changingReaderFilter = true
        filterError = null
        scope.launch {
            try {
                val filtered = LinuxDoRepository.fetchTopicDetail(
                    topicId = summary.id, summaryFallback = summary, trackVisit = false,
                    filterUsername = if (nextState) effectiveOpUsername else null
                )
                val error = filtered.loadError
                if (error == null) {
                    navigationError = null
                    pendingJumpFloor = null
                    pendingReturnFloor = null
                    replyReturnFloor = null
                    jumpFetchedWindow = false
                    loadMorePostsError = false
                    onlyOpFilter = nextState
                    onDetailUpdated(filtered)
                } else {
                    filterError = error
                }
            } finally {
                changingReaderFilter = false
            }
        }
    }

    val isTopicImeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.cardSurface)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.ime)
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
                        maxLines = if (expandTopBarTitle) 6 else 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                Text(
                    if (onlyOpFilter) "只看楼主" else "话题",
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.inkPrimary,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
                )
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
                    if (!bookmarkBusy) {
                        if (isBookmarked) { bookmarkError = null; showRemoveBookmark = true } else setBookmark(true)
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

        if (!showRemoveBookmark) bookmarkError?.let { LinDoFeedbackRow(it, LinDoMascotState.NetworkError) }
        navigationError?.let { message ->
            LinDoLoadFailure(message) {
                pendingJumpFloor?.let { jumpToFloor(it, pendingReturnFloor, pendingJumpRequiresExact) }
            }
        }
        filterError?.let { message -> LinDoLoadFailure(message, ::toggleOnlyOpMode) }
        LazyColumn(
            state = readerListState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(palette.cardSurface),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // 话题标题与版块/全量 Tag 头部区域（对标官方 Linux.do 移动 Web `#topic-title` 排版，直接衔接 #1 主帖）
            item(key = "header_${summary.id}", contentType = "header") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(palette.cardSurface)
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    if (previousPostIds.isNotEmpty()) {
                        TextButton(enabled = !isLoadingMorePosts, onClick = { loadMoreFloorBatch(20, earlier = true) }) {
                            Text("加载更早的楼层", color = palette.inkSecondary)
                        }
                    }
                    // 完整多行话题大标题（不截断，字号与行高贴合官方 Discourse 移动端阅读比例）
                    Text(
                        text = resolvedTitle,
                        fontSize = 19.6f.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 27.0f.sp,
                        letterSpacing = (-0.25).sp,
                        color = palette.inkPrimary
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Linux.do 官方折行风格：`● 版块名` + 自动折行 `#Tag` 标签组（纯圆点与纯文字标签，无底色块）
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.5.dp)
                                    .clip(CircleShape)
                                    .background(catColor)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = resolvedCategoryName,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = catColor,
                                maxLines = 1,
                                softWrap = false
                            )
                        }

                        allTopicTags.forEach { tag ->
                            Text(
                                text = "#$tag",
                                fontSize = 12.2.sp,
                                fontWeight = FontWeight.Normal,
                                color = palette.inkSecondary,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier
                                    .clickable { onSelectTag(tag) }
                                    .padding(vertical = 2.dp)
                            )
                        }
                    }

                    if (detail?.posts?.isNotEmpty() == true) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${formatCompactNumber(detail.views)} 次浏览 · ${formatCompactNumber((postCount - 1).coerceAtLeast(0))} 条回复",
                                modifier = Modifier.weight(1f).padding(end = 8.dp),
                                fontSize = 12.sp,
                                color = palette.inkSecondary
                            )
                            TextButton(
                                contentPadding = PaddingValues(horizontal = 8.dp),
                                onClick = {
                                    showAiSummaryPanel = !showAiSummaryPanel
                                    if (showAiSummaryPanel && remoteAiSummary == null && !isLoadingAiSummary) {
                                        isLoadingAiSummary = true
                                        scope.launch {
                                            try {
                                                remoteAiSummary = LinuxDoRepository.fetchTopicAiSummary(summary.id)
                                            } finally {
                                                isLoadingAiSummary = false
                                            }
                                        }
                                    }
                                }
                            ) {
                                val tint = if (showAiSummaryPanel) palette.activeChipText else palette.inkSecondary
                                Icon(LinDoIcons.Sparkles, null, Modifier.size(15.dp), tint = tint)
                                Spacer(Modifier.width(5.dp))
                                Text(if (showAiSummaryPanel) "收起总结" else "总结", fontSize = 12.sp, color = tint)
                            }
                        }
                        AnimatedVisibility(visible = showAiSummaryPanel) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 6.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(palette.cardSurface)
                                    .border(0.6.dp, palette.hairline, RoundedCornerShape(10.dp))
                                    .padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = LinDoIcons.Sparkles,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = palette.brandGold
                                    )
                                    Text(
                                        text = if (!remoteAiSummary.isNullOrBlank()) "AI 总结" else "话题摘要",
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = palette.inkPrimary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (isLoadingAiSummary) {
                                        Text(
                                            text = "生成中",
                                            fontSize = 11.sp,
                                            color = palette.inkTertiary
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                val summaryBody = remoteAiSummary?.takeIf { it.isNotBlank() } ?: localSmartDigest
                                Text(
                                    text = summaryBody.ifBlank { "暂无可提炼的话题摘要内容。" },
                                    fontSize = 13.sp,
                                    lineHeight = 19.5.sp,
                                    color = palette.inkSecondary
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(thickness = 0.6.dp, color = palette.hairline)
            }

            // 主帖之后直接进入回复区。
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
                        LinDoStatePanel(
                            LinDoMascotState.NetworkError,
                            detail = detail.loadError,
                            onRetry = if (detail.unavailable) onUnavailable else onRefresh,
                            actionLabel = if (detail.unavailable) "返回列表并刷新" else "重试"
                        )
                    }
                }
            }

            items(
                posts, key = { it.id },
                contentType = { post -> if (post.postNumber == 1) "original-post" else "reply" }
            ) { post ->
                val repliedPost = postsByNumber[post.replyToPostNumber]
                val childReplies = repliesByParent[post.postNumber].orEmpty()
                val isFloorUnread = !post.isRead && locallyReadPostNumbers[post.postNumber] != true
                CommunityPostFloor(
                    post = post,
                    repliedPost = repliedPost,
                    childReplies = childReplies,
                    isUnread = isFloorUnread,
                    isJumpTarget = post.postNumber == highlightedFloor,
                    loadingReplyTarget = fetchingJumpWindow && pendingReturnFloor == post.postNumber,
                    onOpenWebUrl = onOpenWebUrl,
                    originalUrl = "https://linux.do/t/topic/${summary.id}/${post.postNumber}",
                    opUsername = effectiveOpUsername,
                    fontScale = fontScale,
                    enableAnimations = enableAnimations,
                    onInspectBadge = onInspectBadge,
                    onInspectUser = onInspectUser,
                    onJumpToFloorNumber = { floor -> jumpToFloor(floor, returnTo = post.postNumber) },
                    onEditPost = { editingPost = it },
                    onDeletePost = { targetPost -> pendingDeletePost = targetPost; deletePostError = null },
                    onReplyFloor = { targetPost ->
                        if (!UserApiKeyAuthManager.isAuthorized()) {
                            onRequestLogin()
                        } else {
                            replyTargetPostNumber = targetPost.postNumber
                            replyTargetUsername = targetPost.username
                            replyInitialQuote = ""
                            replyCloudDraft = null
                            showReplySheet = true
                        }
                    },
                    onQuoteFloor = { targetPost, selection ->
                        if (!UserApiKeyAuthManager.isAuthorized()) {
                            onRequestLogin()
                        } else {
                            val quote = selection ?: postPreviewText(targetPost.cookedHtml).take(160)
                            replyTargetPostNumber = targetPost.postNumber
                            replyTargetUsername = targetPost.username
                            replyInitialQuote = if (quote.isNotBlank()) {
                                postQuoteMarkup(targetPost.username, targetPost.postNumber, summary.id, quote)
                            } else ""
                            replyCloudDraft = null
                            showReplySheet = true
                        }
                    },
                    onRequestLogin = onRequestLogin
                )

                if (post.postNumber == 1 && (postCount > 1 || onlyOpFilter)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (onlyOpFilter) "楼主回复" else "回复",
                            modifier = Modifier.weight(1f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.inkPrimary
                        )
                        TextButton(
                            enabled = !changingReaderFilter && !fetchingJumpWindow && !isLoadingMorePosts,
                            onClick = ::toggleOnlyOpMode
                        ) {
                            Text(
                                if (changingReaderFilter) "切换中" else if (onlyOpFilter) "查看全部" else "只看楼主",
                                fontSize = 12.sp,
                                color = if (onlyOpFilter) palette.activeChipText else palette.inkSecondary
                            )
                        }
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
                }
            }

            // 剩余未加载楼层 / 已读完全部楼层的零度娘底部收尾条
            if (posts.isNotEmpty()) {
                item(key = "load_more_posts", contentType = "footer") {
                    LinearPulseLoadingFooter(
                        isLoadingMore = isLoadingMorePosts,
                        hasMore = remainingPostIds.isNotEmpty(),
                        loadedCount = posts.size,
                        isError = loadMorePostsError,
                        loadingText = "正在同步后续楼层 (${posts.size}/$totalFloorCount)",
                        moreText = "加载后续楼层",
                        errorText = "楼层加载失败",
                        doneText = "已到话题末尾",
                        onLoadMore = { loadMoreFloorBatch(20) }
                    )
                }
            }
        }

        // Keep actions hidden when the topic itself is unavailable or when inline floor keyboard (e.g. Boost) is open.
        if (detail?.unavailable != true && !isTopicImeVisible) Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.cardSurface)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            replyReturnFloor?.let { floor ->
                TextButton(
                    enabled = !fetchingJumpWindow && !changingReaderFilter,
                    onClick = { jumpToFloor(floor) },
                    modifier = Modifier.align(Alignment.End).padding(end = 10.dp)
                ) { Text("返回 $floor 楼", color = palette.activeChipText) }
            }
            HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                        .background(palette.subtleSurface.copy(alpha = 0.65f))
                        .clickable {
                            if (!UserApiKeyAuthManager.isAuthorized()) {
                                onRequestLogin()
                            } else {
                                replyTargetPostNumber = null
                                replyTargetUsername = null
                                replyInitialQuote = ""
                                replyCloudDraft = null
                                showReplySheet = true
                            }
                        }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(LinDoIcons.Edit, null, Modifier.size(17.dp), tint = palette.inkSecondary)
                    Text(
                        if (UserApiKeyAuthManager.isAuthorized()) "回复话题" else "登录后回复",
                        fontSize = 13.sp, color = palette.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))
                TopicFloorCounterButton(
                    enabled = posts.isNotEmpty() && !changingReaderFilter && !fetchingJumpWindow,
                    fetchingJumpWindow = fetchingJumpWindow,
                    visibleFloorProvider = { visibleFloorState.value },
                    totalFloorCount = totalFloorCount,
                    onClick = { showFloorNavigator = true }
                )
            }
        }
    }

    if (showFloorNavigator) {
        TopicFloorNavigator(
            currentFloor = visibleFloorState.value,
            lastFloor = totalFloorCount,
            onDismiss = { showFloorNavigator = false },
            onJump = { number, exact ->
                showFloorNavigator = false
                jumpToFloor(number, exact = exact)
            }
        )
    }

    if (showRemoveBookmark) LinDoConfirmDialog(
        title = "要移除这个话题的全部书签吗？",
        confirmLabel = "移除",
        busy = bookmarkBusy,
        error = bookmarkError,
        onDismiss = { showRemoveBookmark = false },
        onConfirm = { setBookmark(false) }
    )
    pendingDeletePost?.let { target ->
        LinDoConfirmDialog(
            title = "要删除第 ${target.postNumber} 楼的回复吗？",
            confirmLabel = "删除",
            busy = deletingPost,
            error = deletePostError,
            destructive = true,
            onDismiss = { pendingDeletePost = null }, onConfirm = {
                if (!deletingPost) {
                    deletingPost = true
                    scope.launch {
                        try {
                            if (LinuxDoRepository.deletePost(target.id)) {
                                latestDetail?.let { current -> onDetailUpdated(current.copy(
                                    posts = current.posts.filterNot { it.id == target.id },
                                    streamIds = current.streamIds.filterNot { it == target.id },
                                    postsCount = (current.postsCount - 1).coerceAtLeast(1))) }
                                pendingDeletePost = null
                            } else deletePostError = "删除失败"
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (error: Exception) { deletePostError = error.message ?: "删除失败" }
                        finally { deletingPost = false }
                    }
                }
            })
    }

    editingPost?.let { target ->
        ComposerSheet(ComposerAction.EDIT, topicId = summary.id, topicTitle = detail?.title ?: summary.title,
            post = target, onDismiss = { editingPost = null }, onPublished = { result ->
                result.post?.let { updated ->
                    detail?.let { current -> onDetailUpdated(current.copy(posts = current.posts.map {
                        if (it.id == target.id) it.copy(cookedHtml = updated.cookedHtml, raw = updated.raw) else it
                    })) }
                }
                editingPost = null
            })
    }

    if (showReplySheet) {
        val targetFloorNum = replyTargetPostNumber
        val targetPost = remember(targetFloorNum, posts) {
            if (targetFloorNum != null && targetFloorNum > 1) {
                posts.firstOrNull { it.postNumber == targetFloorNum }
            } else {
                posts.firstOrNull { it.postNumber == 1 } ?: posts.firstOrNull()
            }
        }
        val effectiveTopicTitle = detail?.title ?: summary.title
        val replyPreviewSnippet = remember(targetPost, targetFloorNum, effectiveTopicTitle, summary.excerpt) {
            val postBodyText = targetPost?.let { postPreviewText(it.cookedHtml) }.orEmpty()
            postBodyText.ifBlank { if (targetFloorNum == null) effectiveTopicTitle else "" }
        }
        val replyAvatarUrl = if (targetFloorNum != null && targetFloorNum > 1) {
            targetPost?.avatarTemplate
        } else {
            targetPost?.avatarTemplate ?: op.avatarTemplate
        }
        val replyAnimatedAvatar = if (targetFloorNum != null && targetFloorNum > 1) {
            targetPost?.animatedAvatar
        } else {
            targetPost?.animatedAvatar ?: op.animatedAvatar
        }
        val replyDisplayName = if (targetFloorNum != null && targetFloorNum > 1) {
            targetPost?.displayName?.ifBlank { targetPost.username } ?: replyTargetUsername
        } else {
            targetPost?.displayName?.ifBlank { targetPost.username }
                ?: op.name?.takeIf { it.isNotBlank() }
                ?: op.username
        }
        val resolvedReplyUsername = if (targetFloorNum != null && targetFloorNum > 1) {
            replyTargetUsername ?: targetPost?.username
        } else {
            targetPost?.username?.ifBlank { op.username } ?: op.username
        }

        TopicReplyComposerSheet(
            topicId = summary.id,
            topicTitle = effectiveTopicTitle,
            replyToPostNumber = replyTargetPostNumber,
            replyToUsername = resolvedReplyUsername,
            replyToDisplayName = replyDisplayName,
            replyToAvatarUrl = replyAvatarUrl,
            replyToAnimatedAvatar = replyAnimatedAvatar,
            replyToContentSnippet = replyPreviewSnippet,
            initialRaw = replyInitialQuote,
            initialDraft = replyCloudDraft,
            onDismiss = { showReplySheet = false },
            onPostCreated = { newPost ->
                showReplySheet = false
                val currentDetail = detail
                if (currentDetail != null) {
                    val updatedPosts = mergeTopicPosts(currentDetail.posts, listOf(newPost))
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
}

@Composable
private fun TopicFloorCounterButton(
    enabled: Boolean,
    fetchingJumpWindow: Boolean,
    visibleFloorProvider: () -> Int,
    totalFloorCount: Int,
    onClick: () -> Unit
) {
    val palette = LocalPalette.current
    val currentFloor = visibleFloorProvider()
    TextButton(
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 8.dp),
        onClick = onClick
    ) {
        Text(
            text = if (fetchingJumpWindow) "跳转中…" else "$currentFloor / $totalFloorCount 楼",
            fontSize = 12.sp,
            color = palette.inkSecondary,
            maxLines = 1
        )
        Icon(
            imageVector = LinDoIcons.ChevronUp,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = palette.inkSecondary
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

private const val OfficialModeratorIcon = "https://cdn.ldstatic.com/svg-sprite/linux.do/icon/shield-halved.svg"

/**
 * Linux.do Web 帖子/评论楼层 (`article.onscreen-post`)：
 * - 头部：作者、身份信息、相对时间与当前楼层。
 * - 正文：干净通透的 AST 渲染（引用块、代码高亮与一键复制、配图、折叠块）
 * - 底部操作栏 (`nav.post-controls`)：
 *   - 左侧：**仅展示该楼层真实收到的表情回应 (`post.reactions`)**，绝不默认塞入未发生的假表情按钮！
 *   - 右侧：Web 风格清爽线性图标操作（`♡` 点赞、`😊+` 展开表情选择器、复制楼层链接、引用 `❝`、回复 `↩`）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CommunityPostFloor(
    post: PostItemModel,
    repliedPost: PostItemModel? = null,
    childReplies: List<PostItemModel> = emptyList(),
    isUnread: Boolean = false,
    isJumpTarget: Boolean = false,
    loadingReplyTarget: Boolean = false,
    opUsername: String,
    fontScale: Float,
    enableAnimations: Boolean,
    onInspectBadge: (GrantedBadge) -> Unit,
    onInspectUser: (String) -> Unit,
    onJumpToFloorNumber: (Int) -> Unit = {},
    onEditPost: (PostItemModel) -> Unit = {},
    onDeletePost: (PostItemModel) -> Unit = {},
    onReplyFloor: (PostItemModel) -> Unit,
    onQuoteFloor: (PostItemModel, String?) -> Unit,
    onRequestLogin: () -> Unit,
    onOpenWebUrl: (String) -> Unit = {},
    originalUrl: String? = null
) {
    val palette = LocalPalette.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    val isOp = post.isTopicOwner || post.username.equals(opUsername, ignoreCase = true)
    val account = UserApiKeyAuthManager.currentUser
    val accountUsername = LocalCurrentAccountUsername.current ?: account?.username
    val accountId = account?.takeIf { it.username.equals(accountUsername, ignoreCase = true) }
        ?.userId?.takeIf { it > 0 }
    val isOwnPost = if (accountId != null && post.userId > 0) post.userId == accountId
        else !accountUsername.isNullOrBlank() && post.username.equals(accountUsername, ignoreCase = true)
    val isDeletedPost = remember(post.cookedHtml) {
        post.cookedHtml.contains("帖子已被作者删除") ||
            post.cookedHtml.contains("(post deleted by author)", ignoreCase = true)
    }

    val initialReactionSnap = LinuxDoRepository.getPostReactionSnapshot(post.id)
    var myReactionId by remember(post.id, post.myReactionId, post.likedByMe) {
        mutableStateOf(
            if (initialReactionSnap != null) initialReactionSnap.myReactionId
            else (post.myReactionId ?: if (post.likedByMe) "heart" else null)
        )
    }
    var localReactions by remember(post.id, post.reactions, post.likeCount) {
        mutableStateOf(
            initialReactionSnap?.reactions ?: if (post.reactions.isNotEmpty()) {
                post.reactions
            } else if (post.likeCount > 0) {
                listOf(PostReactionItem(id = "heart", emoji = "❤️", count = post.likeCount))
            } else {
                emptyList()
            }
        )
    }

    val initialBoostSnap = LinuxDoRepository.getPostBoostSnapshot(post.id)
    var localBoosts by remember(post.id, post.boosts, accountUsername) {
        mutableStateOf(initialBoostSnap?.boosts ?: post.boosts)
    }
    var canBoostLocal by remember(post.id, post.canBoost, post.boosts, accountUsername) {
        mutableStateOf(initialBoostSnap?.canBoost ?: post.canBoost)
    }
    val myBoost = localBoosts.firstOrNull { it.isBy(accountId, accountUsername) }
    val canComposeBoost = !isOwnPost && !isDeletedPost && canBoostLocal && myBoost == null
    var expandedBoostId by remember(post.id) { mutableStateOf<Long?>(null) }
    var boostInputText by remember(post.id) { mutableStateOf("") }
    var showBoostEmojiPicker by remember(post.id) { mutableStateOf(false) }
    var selectedBoostEmojiGroupIdx by remember(post.id) { mutableIntStateOf(0) }
    var syncedEmojiGroupsVersion by remember { mutableIntStateOf(0) }
    var isSubmittingBoost by remember(post.id) { mutableStateOf(false) }
    var isDeletingBoostId by remember(post.id) { mutableStateOf<Long?>(null) }
    var isTogglingReaction by remember(post.id) { mutableStateOf(false) }
    var floorActionError by remember(post.id) { mutableStateOf<String?>(null) }

    var showReactionPopover by remember(post.id) { mutableStateOf(false) }
    var showBoostComposer by remember(post.id) { mutableStateOf(false) }
    var showFloorMoreMenu by remember(post.id) { mutableStateOf(false) }
    var showInlineReplies by remember(post.id) { mutableStateOf(false) }
    var copiedFloorLink by remember(post.id) { mutableStateOf(false) }

    LaunchedEffect(floorActionError) {
        if (floorActionError != null) {
            delay(4200L)
            floorActionError = null
        }
    }

    LaunchedEffect(showReactionPopover, showBoostComposer, showBoostEmojiPicker) {
        if ((showReactionPopover || showBoostComposer || showBoostEmojiPicker) && !LinuxDoEmojiCatalog.hasSyncedServerEmojis()) {
            if (LinuxDoRepository.syncSiteEmojis()) {
                syncedEmojiGroupsVersion++
            }
        }
    }

    fun toggleReaction(reactionId: String, emojiChar: String) {
        if (!UserApiKeyAuthManager.isAuthorized()) {
            onRequestLogin()
            return
        }
        if (isOwnPost) {
            floorActionError = "不能给自己的帖子点赞或表态"
            return
        }
        if (isDeletedPost) {
            floorActionError = "该楼层已被作者删除，无法点赞或表态"
            return
        }
        if (isTogglingReaction) return
        floorActionError = null

        val prevReaction = myReactionId
        val prevReactionsSnapshot = localReactions.toList()
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
            myReactionId = null
        } else {
            myReactionId = reactionId
            val idx = currentList.indexOfFirst { it.id == reactionId }
            if (idx >= 0) {
                val item = currentList[idx]
                currentList[idx] = item.copy(count = item.count + 1)
            } else {
                currentList.add(PostReactionItem(id = reactionId, emoji = emojiChar, count = 1))
            }
        }
        localReactions = currentList
        showReactionPopover = false
        isTogglingReaction = true

        scope.launch {
            try {
                val res = LinuxDoRepository.togglePostReactionDetailed(post.id, reactionId)
                if (res.ok) {
                    if (res.reactions != null) {
                        myReactionId = res.myReactionId
                        localReactions = res.reactions
                    }
                } else {
                    myReactionId = prevReaction
                    localReactions = prevReactionsSnapshot
                    floorActionError = res.errorMessage ?: "点赞/表态未生效，请稍后重试"
                }
            } finally {
                isTogglingReaction = false
            }
        }
    }

    fun submitBoost(rawText: String = boostInputText) {
        if (!UserApiKeyAuthManager.isAuthorized()) {
            onRequestLogin()
            return
        }
        if (!canComposeBoost || isSubmittingBoost) return
        val trimmed = rawText.trim()
        val stats = BoostInputStats.compute(trimmed)
        if (stats.isEmpty) {
            floorActionError = "请输入小火箭短评或选择表情（限 ${BoostInputStats.MAX_BOOST_VISIBLE_LENGTH} 字）"
            return
        }
        if (stats.exceedsLength) {
            floorActionError = "小火箭内容最多 ${BoostInputStats.MAX_BOOST_VISIBLE_LENGTH} 个字（当前 ${stats.visibleLength} 字）"
            return
        }
        if (stats.exceedsEmojiLimit) {
            floorActionError = "单条小火箭最多包含 ${BoostInputStats.MAX_BOOST_EMOJI_COUNT} 个表情（当前 ${stats.emojiCount} 个）"
            return
        }
        floorActionError = null
        isSubmittingBoost = true

        scope.launch {
            try {
                val res = LinuxDoRepository.createPostBoost(post.id, trimmed)
                if (res.ok && res.boost != null) {
                    localBoosts = localBoosts.filterNot { it.id == res.boost.id } + res.boost
                    canBoostLocal = false
                    boostInputText = ""
                    showBoostEmojiPicker = false
                    showBoostComposer = false
                    showReactionPopover = false
                } else {
                    floorActionError = res.errorMessage ?: "发送小火箭 Boost 失败，请稍后重试"
                }
            } finally {
                isSubmittingBoost = false
            }
        }
    }

    var pendingBoostDelete by remember(post.id) { mutableStateOf<Long?>(null) }

    fun deleteBoost(boostId: Long) {
        if (isDeletingBoostId != null) return
        floorActionError = null
        isDeletingBoostId = boostId
        scope.launch {
            try {
                val res = LinuxDoRepository.deletePostBoost(post.id, boostId)
                if (res.ok) {
                    localBoosts = localBoosts.filterNot { it.id == boostId }
                    if (myBoost?.id == boostId) canBoostLocal = true
                    if (expandedBoostId == boostId) expandedBoostId = null
                    pendingBoostDelete = null
                } else {
                    floorActionError = res.errorMessage ?: "删除助力失败"
                }
            } finally {
                isDeletingBoostId = null
            }
        }
    }

    fun appendEmojiToBoostInput(token: String) {
        val cleanToken = token.trim()
        if (cleanToken.isEmpty()) return
        val needsLeadingSpace = cleanToken.startsWith(":") &&
            boostInputText.isNotEmpty() &&
            !boostInputText.endsWith(" ")
        val needsTrailingSpace = cleanToken.startsWith(":")
        val candidate = buildString {
            append(boostInputText)
            if (needsLeadingSpace) append(' ')
            append(cleanToken)
            if (needsTrailingSpace) append(' ')
        }
        val candidateStats = BoostInputStats.compute(candidate)
        if (candidateStats.exceedsLength) {
            floorActionError = "已达小火箭 ${BoostInputStats.MAX_BOOST_VISIBLE_LENGTH} 字上限"
            return
        }
        if (candidateStats.exceedsEmojiLimit) {
            floorActionError = "单条小火箭最多插入 ${BoostInputStats.MAX_BOOST_EMOJI_COUNT} 个表情"
            return
        }
        floorActionError = null
        boostInputText = candidate
    }
    val canDeleteThisPost = remember(isOwnPost, post.postNumber, post.canDelete) {
        LinuxDoSiteRules.canDeletePost(
            isOwnPost = isOwnPost,
            postNumber = post.postNumber,
            serverCanDelete = post.canDelete
        )
    }

    var selectedQuote by remember(post.id, post.cookedHtml) { mutableStateOf<String?>(null) }
    val targetReplyFloorNum = post.replyToPostNumber?.takeIf { it > 0 }
    val targetReplyUsername = remember(post.replyToUsername, repliedPost?.username) {
        post.replyToUsername?.takeIf { it.isNotBlank() } ?: repliedPost?.username
    }
    val targetReplyDisplayName = remember(repliedPost?.displayName, post.replyToDisplayName, targetReplyUsername) {
        repliedPost?.displayName?.takeIf { it.isNotBlank() }
            ?: post.replyToDisplayName?.takeIf { it.isNotBlank() }
            ?: targetReplyUsername
    }
    val targetReplyAvatarTemplate = remember(post.replyToAvatarTemplate, repliedPost?.avatarTemplate) {
        post.replyToAvatarTemplate?.takeIf { it.isNotBlank() } ?: repliedPost?.avatarTemplate.orEmpty()
    }
    val targetReplyAnimatedAvatar = repliedPost?.animatedAvatar
    val floorAwards = remember(post.badgesGranted) { post.badgesGranted.filter { it.isFirstTimeGrant } }
    val inlineBadges = post.authorBadges
    val primaryName = post.displayName?.takeIf { it.isNotBlank() } ?: post.username
    val hasDistinctHandle = remember(post.displayName, post.username) {
        fun normalizeName(value: String) = value.lowercase().filterNot { it.isWhitespace() || it in "._-" }
        !post.displayName.isNullOrBlank() && normalizeName(post.displayName) != normalizeName(post.username)
    }
    val hasUserTitle = !post.userTitle.isNullOrBlank()
    val hasIosDevice = !post.iosDeviceName.isNullOrBlank()
    val statusEmojiRaw = post.userStatus?.emoji?.trim()?.takeIf { it.isNotEmpty() }
    val statusDescText = post.userStatus?.description?.trim()?.takeIf { it.isNotEmpty() }
    var showStatusDescription by remember(post.id) { mutableStateOf(false) }

    // 针对「(帖子已被作者删除)」墓碑楼层：自动折叠为单行静默提示条，隐藏无效的点赞/小火箭/回复底栏
    if (isDeletedPost) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.cardSurface)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .graphicsLayer { alpha = 0.55f }
                    .clickable { onInspectUser(post.username) }
            ) {
                SmartAvatar(
                    username = post.username,
                    avatarTemplate = post.avatarTemplate,
                    animatedAvatar = null,
                    primaryGroupName = post.primaryGroupName,
                    flair = null,
                    size = 20.dp,
                    enableAnimations = false
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = primaryName,
                fontSize = 12.2.sp,
                fontWeight = FontWeight.Medium,
                color = palette.inkTertiary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { onInspectUser(post.username) }
            )
            Text(
                text = " · 帖子已被作者删除",
                fontSize = 12.sp,
                color = palette.inkTertiary,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.weight(1f)
            )
            if (isOwnPost && post.canEdit) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable { onEditPost(post) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = LinDoIcons.Edit,
                        contentDescription = "编辑",
                        modifier = Modifier.size(14.dp),
                        tint = palette.inkSecondary
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = buildString {
                    if (post.createdAtText.isNotBlank()) {
                        append(post.createdAtText)
                        append(" ")
                    }
                    if (post.postNumber > 1) {
                        append(post.postNumber)
                        append(" 楼")
                    }
                },
                fontSize = 11.2.sp,
                color = palette.inkTertiary,
                maxLines = 1,
                softWrap = false
            )
        }
        HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isJumpTarget) palette.accent.copy(alpha = 0.09f) else palette.cardSurface)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        // 昵称和楼层信息在首行，账号、头衔及设备合并为次行。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clickable { onInspectUser(post.username) }
            ) {
                SmartAvatar(
                    username = post.username,
                    avatarTemplate = post.avatarTemplate,
                    animatedAvatar = post.animatedAvatar,
                    primaryGroupName = post.primaryGroupName,
                    flair = post.flair,
                    size = 42.dp,
                    enableAnimations = enableAnimations
                )
            }

            Spacer(modifier = Modifier.width(11.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                // 第一行：左侧昵称 + 管理盾牌 + 个人状态 Emoji + 楼主 + 佩戴徽章 | 右侧被回复楼层 + 时间 + #楼号 + 未读蓝点（100% 同行基线居中对齐）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onInspectUser(post.username) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.5.dp)
                    ) {
                        Text(
                            text = primaryName,
                            fontSize = 14.6.sp,
                            lineHeight = 16.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.inkPrimary,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )

                        if (post.moderator || post.groupModerator) {
                            OfficialBadgeArtwork(
                                url = OfficialModeratorIcon,
                                description = "版主",
                                colorFilter = ColorFilter.tint(palette.inkSecondary),
                                modifier = Modifier.size(13.5.dp)
                            )
                        }

                        if (inlineBadges.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                inlineBadges.forEach { badge ->
                                    PostGrantedBadgeIcon(
                                        badge = badge, size = 13.dp,
                                        onClick = onInspectBadge
                                    )
                                }
                            }
                        }

                        if (statusEmojiRaw != null) {
                            val cleanShortcode = statusEmojiRaw.removeSurrounding(":")
                            val unicodeEmoji = remember(cleanShortcode) {
                                LinuxDoEmojiCatalog.shortcodeToUnicode(cleanShortcode)
                                    ?: if (!statusEmojiRaw.startsWith(":") && statusEmojiRaw.any { it.code > 127 }) statusEmojiRaw else null
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable(enabled = statusDescText != null) {
                                        showStatusDescription = !showStatusDescription
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                PostReactionGlyph(
                                    reactionId = cleanShortcode,
                                    emojiFallback = unicodeEmoji ?: "💬",
                                    isSelected = false,
                                    size = 15.dp
                                )
                            }
                        }

                        if (isOp) {
                            Text(
                                text = "楼主",
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = palette.brandGold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }


                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (targetReplyFloorNum != null) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable(enabled = !loadingReplyTarget) {
                                        onJumpToFloorNumber(targetReplyFloorNum)
                                    }
                                    .padding(horizontal = 3.dp, vertical = 1.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.5.dp)
                            ) {
                                Icon(
                                    imageVector = LinDoIcons.Reply,
                                    contentDescription = "跳转被回复楼层",
                                    modifier = Modifier
                                        .size(14.5.dp)
                                        .graphicsLayer { scaleX = -1f },
                                    tint = palette.inkSecondary
                                )
                                SmartAvatar(
                                    username = targetReplyUsername ?: "L",
                                    avatarTemplate = targetReplyAvatarTemplate,
                                    animatedAvatar = targetReplyAnimatedAvatar,
                                    size = 20.dp,
                                    enableAnimations = enableAnimations
                                )
                            }
                        }

                        val floorMeta = listOfNotNull(
                            post.postNumber.takeIf { it > 1 }?.let { "$it 楼" },
                            post.createdAtText.takeIf { it.isNotBlank() }
                        ).joinToString(" · ")
                        if (floorMeta.isNotEmpty()) {
                            Text(
                                text = floorMeta,
                                fontSize = 11.2.sp,
                                lineHeight = 14.sp,
                                color = palette.inkTertiary,
                                maxLines = 1,
                                softWrap = false
                            )
                        }

                        val unreadDotAlpha by animateFloatAsState(
                            targetValue = if (isUnread) 1f else 0f,
                            animationSpec = tween(durationMillis = if (enableAnimations) 200 else 0),
                            label = "unreadFloorDotAlpha"
                        )
                        Box(
                            modifier = Modifier
                                .size(6.5.dp)
                                .graphicsLayer {
                                    alpha = unreadDotAlpha
                                    val scale = 0.65f + 0.35f * unreadDotAlpha
                                    scaleX = scale
                                    scaleY = scale
                                }
                                .clip(CircleShape)
                                .background(
                                    if (palette.isDark) Color(0xFF38BDF8) else Color(0xFF0088CC)
                                )
                        )
                    }
                }

                if (hasUserTitle || hasIosDevice || floorAwards.isNotEmpty() ||
                    post.isTodayBirthday || post.isTodayCakeday
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 1.5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (hasUserTitle) {
                            UserTitleTag(
                                title = post.userTitle.orEmpty(),
                                animatePostTitle = enableAnimations,
                                merchantGroupTitle = post.titleIsGroup &&
                                    (post.primaryGroupName == "g-merchant" || post.primaryGroupName == "g-merchant2"),
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }
                        if (hasIosDevice) {
                            if (hasUserTitle) Text("·", color = palette.inkTertiary, fontSize = 11.sp, lineHeight = 14.sp)
                            Row(
                                modifier = Modifier.weight(1.25f, fill = false),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(
                                    imageVector = LinDoIcons.AppleLogo,
                                    contentDescription = null,
                                    modifier = Modifier.size(10.dp),
                                    tint = palette.inkSecondary
                                )
                                Text(
                                    text = post.iosDeviceName.orEmpty(),
                                    fontSize = 11.sp,
                                    lineHeight = 14.sp,
                                    color = palette.inkSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        floorAwards.forEach { badge ->
                            PostGrantedBadgeIcon(
                                badge = badge,
                                onClick = onInspectBadge,
                                description = "${post.username} 因此楼获授 ${badge.name}"
                            )
                        }
                        if (post.isTodayBirthday) Text("🎂", fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1)
                        if (post.isTodayCakeday) Text("🍰", fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1)
                    }
                }
                if (showStatusDescription && statusDescText != null) {
                    Text(
                        text = statusDescText,
                        modifier = Modifier.padding(top = 3.dp),
                        fontSize = 11.sp,
                        color = palette.inkSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        CommunityPostContent(
            postId = post.id, cookedHtml = post.cookedHtml, originalUrl = originalUrl,
            targetReplyFloorNum = targetReplyFloorNum, targetReplyDisplayName = targetReplyDisplayName,
            repliedHtml = repliedPost?.cookedHtml, onJumpToFloorNumber = onJumpToFloorNumber,
            loadingReplyTarget = loadingReplyTarget,
            onSelectionChange = { selectedQuote = it },
            onOpenWebUrl = onOpenWebUrl
        )

        // 2.2 官方小火箭 Boost 气泡列表 (`discourse-boosts`：气泡与 `+🚀` 统一 28dp 同高且严格垂直居中对齐)
        if (localBoosts.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                localBoosts.forEach { boost ->
                    val isMyBoost = boost.id == myBoost?.id
                    val isExpanded = expandedBoostId == boost.id
                    Row(
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .height(28.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (isMyBoost) palette.activeChipBg.copy(alpha = 0.55f)
                                else palette.subtleSurface
                            )
                            .border(
                                width = 0.6.dp,
                                color = if (isMyBoost) palette.brandGold.copy(alpha = 0.45f) else palette.hairline,
                                shape = RoundedCornerShape(14.dp)
                            )
                            .clickable {
                                expandedBoostId = if (isExpanded) null else boost.id
                            }
                            .padding(horizontal = 8.5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        SmartAvatar(
                            username = boost.username,
                            avatarTemplate = boost.avatarTemplate,
                            animatedAvatar = null,
                            size = 16.5.dp,
                            enableAnimations = enableAnimations
                        )
                        BoostInlineContent(
                            plainText = boost.plainText,
                            fontSize = 12.4.sp,
                            emojiSize = 15.dp,
                            textColor = palette.inkPrimary
                        )
                        if (isExpanded) {
                            Text(
                                text = "@${boost.username}",
                                fontSize = 11.sp,
                                color = palette.inkTertiary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable { onInspectUser(boost.username) }
                                    .padding(horizontal = 2.dp)
                            )
                            if (boost.canDelete) {
                                Text(
                                    text = if (isDeletingBoostId == boost.id) "删除中…" else "删除",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFEF4444),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0xFFEF4444).copy(alpha = 0.12f))
                                        .clickable(enabled = isDeletingBoostId == null) {
                                            floorActionError = null
                                            pendingBoostDelete = boost.id
                                        }
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                }


            }
        }

        Spacer(modifier = Modifier.height(3.dp))

        // 2.5 点赞/表态/小火箭操作反馈提示条（避免静默失败回退）
        AnimatedVisibility(visible = floorActionError != null) {
            val errMsg = floorActionError.orEmpty()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFEF4444).copy(alpha = if (palette.isDark) 0.18f else 0.10f))
                    .border(0.6.dp, Color(0xFFEF4444).copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                    .clickable { floorActionError = null }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = errMsg,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (palette.isDark) Color(0xFFFCA5A5) else Color(0xFFDC2626),
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = LinDoIcons.Close,
                    contentDescription = "关闭提示",
                    modifier = Modifier.padding(start = 8.dp).size(13.dp),
                    tint = palette.inkTertiary
                )
            }
        }

        // 2.7 独立单行「表情点赞」选择条 (`discourse-reactions`：与小火箭彻底分离，纯粹单行胶囊)
        AnimatedVisibility(visible = showReactionPopover) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
                    .height(42.dp)
                    .clip(RoundedCornerShape(21.dp))
                    .background(palette.subtleSurface)
                    .border(0.6.dp, palette.hairline, RoundedCornerShape(21.dp))
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LinuxDoEmojiCatalog.officialReactions.forEach { reactionItem ->
                        val rId = reactionItem.shortcode
                        val emo = reactionItem.unicode ?: reactionItem.markdownCode
                        val isMine = myReactionId == rId
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(if (isMine) palette.activeChipBg else Color.Transparent)
                                .then(
                                    if (isMine) Modifier.border(0.6.dp, palette.brandGold.copy(alpha = 0.45f), CircleShape)
                                    else Modifier
                                )
                                .clickable { toggleReaction(rId, emo) },
                            contentAlignment = Alignment.Center
                        ) {
                            PostReactionGlyph(
                                reactionId = rId,
                                emojiFallback = emo,
                                isSelected = isMine,
                                size = 19.5.dp
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .clickable { showReactionPopover = false },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = LinDoIcons.Close,
                        contentDescription = "收起表情表态",
                        modifier = Modifier.size(14.dp),
                        tint = palette.inkTertiary
                    )
                }
            }
        }

        // 2.8 独立极简「小火箭 Boost」单行输入条 (`discourse-boosts`：与表情点赞彻底解耦，仅保留单行清爽输入 + 按需展开表情网格)
        AnimatedVisibility(visible = showBoostComposer && canComposeBoost) {
            val boostStats = remember(boostInputText) { BoostInputStats.compute(boostInputText) }
            val pickerGroups = remember(syncedEmojiGroupsVersion) { LinuxDoEmojiCatalog.getPickerGroups() }
            val safeGroupIdx = selectedBoostEmojiGroupIdx.coerceIn(0, (pickerGroups.size - 1).coerceAtLeast(0))
            val boostBringIntoViewRequester = remember { BringIntoViewRequester() }
            val boostFocusRequester = remember { FocusRequester() }
            val imeBottomPx = WindowInsets.ime.getBottom(LocalDensity.current)

            LaunchedEffect(Unit) {
                delay(60L)
                runCatching { boostFocusRequester.requestFocus() }
                delay(100L)
                runCatching { boostBringIntoViewRequester.bringIntoView() }
            }

            LaunchedEffect(imeBottomPx, showBoostEmojiPicker) {
                if (imeBottomPx > 0 || showBoostEmojiPicker) {
                    runCatching { boostBringIntoViewRequester.bringIntoView() }
                    delay(120L)
                    runCatching { boostBringIntoViewRequester.bringIntoView() }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(boostBringIntoViewRequester)
                    .padding(bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val counterOverLimit = boostStats.exceedsLength || boostStats.exceedsEmojiLimit
                val canSendNow = boostStats.isValid && !isSubmittingBoost
                Text(
                    "给 @${post.username} 助力", fontSize = 11.5.sp,
                    color = palette.inkSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (counterOverLimit) Text(
                    if (boostStats.exceedsEmojiLimit) "最多 ${BoostInputStats.MAX_BOOST_EMOJI_COUNT} 个表情"
                    else "最多 ${BoostInputStats.MAX_BOOST_VISIBLE_LENGTH} 个字",
                    color = MaterialTheme.colorScheme.error, fontSize = 11.5.sp
                )

                // 一体化单行小火箭输入条（左侧纯矢量表情按钮 + 中部无框输入 + 右侧字数与发送/关闭图标）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.subtleSurface)
                        .border(
                            width = 0.6.dp,
                            color = if (counterOverLimit) Color(0xFFEF4444) else palette.hairline,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // 左侧：纯矢量表情图标按钮
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (showBoostEmojiPicker) palette.activeChipBg else Color.Transparent)
                            .clickable { showBoostEmojiPicker = !showBoostEmojiPicker },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = LinDoIcons.Smile,
                            contentDescription = "选择表情",
                            modifier = Modifier.size(17.5.dp),
                            tint = if (showBoostEmojiPicker) palette.brandGold else palette.inkSecondary
                        )
                    }

                    val boostInputStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, color = palette.inkPrimary)
                    BasicTextField(
                        value = boostInputText,
                        onValueChange = { next -> boostInputText = next.replace("\n", " ") },
                        singleLine = true,
                        textStyle = boostInputStyle,
                        cursorBrush = SolidColor(palette.brandGold),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (canSendNow) submitBoost(boostInputText)
                        }),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp)
                            .focusRequester(boostFocusRequester),
                        decorationBox = { inner ->
                            LinDoInputDecoration(
                                boostInputText.isEmpty(), "写一句助力", boostInputStyle, palette.inkSecondary,
                                modifier = Modifier.fillMaxWidth(),
                                innerTextField = inner
                            )
                        }
                    )

                    // 右侧：精简字数提示 + 纯图标发送按钮 + 关闭按钮
                    Text(
                        text = "${boostStats.visibleLength}/${BoostInputStats.MAX_BOOST_VISIBLE_LENGTH}",
                        fontSize = 11.sp,
                        fontWeight = if (counterOverLimit) FontWeight.Bold else FontWeight.Medium,
                        color = if (counterOverLimit) Color(0xFFEF4444) else palette.inkTertiary,
                        maxLines = 1
                    )

                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (canSendNow) palette.activeChipBg else Color.Transparent)
                            .clickable(enabled = canSendNow) {
                                submitBoost(boostInputText)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = LinDoIcons.Send,
                            contentDescription = "发送小火箭",
                            modifier = Modifier.size(16.dp),
                            tint = if (canSendNow) palette.brandGold else palette.inkTertiary.copy(alpha = 0.38f)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .clickable {
                                showBoostEmojiPicker = false
                                showBoostComposer = false
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = LinDoIcons.Close,
                            contentDescription = "收起小火箭",
                            modifier = Modifier.size(14.dp),
                            tint = palette.inkTertiary
                        )
                    }
                }

                // 如果输入框中已包含 `:shortcode:`，实时预览渲染效果
                if (boostInputText.contains(":")) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier.padding(horizontal = 6.dp)
                    ) {
                        Text(
                            text = "预览：",
                            fontSize = 11.sp,
                            color = palette.inkTertiary
                        )
                        BoostInlineContent(
                            plainText = boostInputText.trim(),
                            fontSize = 12.2.sp,
                            emojiSize = 15.dp,
                            textColor = palette.inkPrimary
                        )
                    }
                }

                // 按需展开的全量表情包选择面板
                AnimatedVisibility(visible = showBoostEmojiPicker && pickerGroups.isNotEmpty()) {
                    val activeGroup = pickerGroups[safeGroupIdx]
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(palette.subtleSurface)
                            .border(0.5.dp, palette.hairline, RoundedCornerShape(12.dp))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        LinDoSectionTabs(pickerGroups.map { it.labelZh }, safeGroupIdx, fontSize = 12.sp) {
                            selectedBoostEmojiGroupIdx = it
                        }

                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 132.dp)
                                .verticalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            activeGroup.items.take(60).forEach { emojiItem ->
                                val token = emojiItem.unicode ?: emojiItem.markdownCode
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .clickable { appendEmojiToBoostInput(token) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    PostReactionGlyph(
                                        reactionId = emojiItem.shortcode,
                                        emojiFallback = emojiItem.unicode ?: emojiItem.markdownCode,
                                        isSelected = false,
                                        size = 19.dp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. 楼层底部控制栏（对齐官方移动 Web：左侧无框表态计数 + `N ⌄` 子回复展开；右侧纯矢量图标组 `♡` `🚀` `⋯` `↩`）
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：无边框清爽表态计数 + `N ⌄` 展开本楼回复
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                localReactions.forEach { rItem ->
                    val isMine = myReactionId == rItem.id
                    Row(
                        modifier = Modifier
                            .height(30.dp)
                            .clip(RoundedCornerShape(15.dp))
                            .background(
                                if (isMine) palette.activeChipBg.copy(alpha = 0.65f)
                                else Color.Transparent
                            )
                            .clickable { toggleReaction(rItem.id, rItem.emoji) }
                            .padding(horizontal = if (isMine) 8.dp else 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        PostReactionGlyph(
                            reactionId = rItem.id,
                            emojiFallback = rItem.emoji,
                            isSelected = isMine,
                            size = 18.dp
                        )
                        Text(
                            text = "${rItem.count}",
                            fontSize = 13.5.sp,
                            fontWeight = if (isMine) FontWeight.Bold else FontWeight.Medium,
                            color = if (isMine) palette.brandGold else palette.inkSecondary
                        )
                    }
                }

                if (childReplies.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .height(30.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (showInlineReplies) palette.subtleSurface else Color.Transparent
                            )
                            .clickable { showInlineReplies = !showInlineReplies }
                            .padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            text = if (showInlineReplies) "收起回复" else "${childReplies.size} 条回复",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (showInlineReplies) palette.inkPrimary else palette.inkSecondary
                        )
                        Icon(
                            imageVector = if (showInlineReplies) LinDoIcons.ChevronUp else LinDoIcons.ChevronDown,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = if (showInlineReplies) palette.inkPrimary else palette.inkSecondary
                        )
                    }
                }
            }

            // 右侧：4 项高频动作（♡ 点赞/长按表情表态 · 🚀 小火箭 Boost · ⋯ 更多 · ↩ 回复）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                // 1. 点赞 / 表情表态（单击红心点赞，长按展开单行表情表态栏）
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .combinedClickable(
                            onClick = {
                                if (showReactionPopover) {
                                    showReactionPopover = false
                                } else {
                                    toggleReaction("heart", "❤️")
                                }
                            },
                            onLongClick = {
                                showReactionPopover = !showReactionPopover
                                if (showReactionPopover) showBoostComposer = false
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (myReactionId != null) LinDoIcons.HeartFilled else LinDoIcons.Heart,
                        contentDescription = "点赞（长按选择表情表态）",
                        modifier = Modifier.size(19.dp),
                        tint = when {
                            myReactionId != null -> Color(0xFFE11D48)
                            showReactionPopover -> palette.brandGold
                            else -> palette.inkSecondary
                        }
                    )
                }

                if (!isOwnPost && (canComposeBoost || myBoost != null)) {
                    IconButton(onClick = {
                        if (!UserApiKeyAuthManager.isAuthorized()) {
                            onRequestLogin()
                        } else if (myBoost != null) {
                            expandedBoostId = if (expandedBoostId == myBoost.id) null else myBoost.id
                        } else {
                            showBoostComposer = !showBoostComposer
                            if (showBoostComposer) showReactionPopover = false
                            else showBoostEmojiPicker = false
                        }
                    }) {
                        Icon(
                            LinDoIcons.BoostRocket,
                            if (myBoost != null) "查看我的助力" else "小火箭助力",
                            Modifier.size(20.dp),
                            tint = if (showBoostComposer || myBoost != null) palette.accent else palette.inkSecondary
                        )
                    }
                }

                // 3. 更多操作按钮 (`⋯`)：收纳表情表态、引用、复制链接、编辑、删除等操作
                Box {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .clickable { showFloorMoreMenu = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (copiedFloorLink) LinDoIcons.Check else LinDoIcons.MoreHoriz,
                            contentDescription = "更多楼层操作",
                            modifier = Modifier.size(19.dp),
                            tint = if (showFloorMoreMenu || copiedFloorLink) palette.brandGold else palette.inkSecondary
                        )
                    }

                    DropdownMenu(
                        expanded = showFloorMoreMenu,
                        onDismissRequest = { showFloorMoreMenu = false },
                        modifier = Modifier.background(palette.cardSurface)
                    ) {
                        if (!isOwnPost && !isDeletedPost) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "表情表态",
                                        fontSize = 13.5.sp,
                                        color = palette.inkPrimary
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = LinDoIcons.Smile,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = palette.inkSecondary
                                    )
                                },
                                onClick = {
                                    showFloorMoreMenu = false
                                    showReactionPopover = true
                                    showBoostComposer = false
                                }
                            )
                        }

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
                                onQuoteFloor(post, selectedQuote)
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
                                    copiedFloorLink = true
                                    scope.launch {
                                        delay(1500L)
                                        copiedFloorLink = false
                                    }
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

                // 4. 回复本楼 (`↩` 纯图标)
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable {
                            val selection = selectedQuote
                            if (selection == null) onReplyFloor(post) else onQuoteFloor(post, selection)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = LinDoIcons.Reply,
                        contentDescription = if (selectedQuote == null) "回复本楼" else "引用所选文字并回复",
                        modifier = Modifier.size(18.5.dp),
                        tint = palette.inkSecondary
                    )
                }
            }
        }

        // 4. 点击左下角 `N ⌄` 展开的本楼嵌套子回复列表（还原官方 Discourse 展开下属回复体验）
        AnimatedVisibility(visible = showInlineReplies && childReplies.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(palette.subtleSurface.copy(alpha = if (palette.isDark) 0.55f else 0.7f))
                    .border(0.5.dp, palette.hairline, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                childReplies.forEachIndexed { cIdx, child ->
                    if (cIdx > 0) {
                        HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
                    }
                    val childCleanText = remember(child.cookedHtml) { postPreviewText(child.cookedHtml) }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onJumpToFloorNumber(child.postNumber) }
                            .padding(vertical = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            SmartAvatar(
                                username = child.username,
                                avatarTemplate = child.avatarTemplate,
                                animatedAvatar = child.animatedAvatar,
                                size = 22.dp,
                                enableAnimations = enableAnimations
                            )
                            Text(
                                text = child.displayName?.takeIf { it.isNotBlank() } ?: child.username,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = palette.inkPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "${child.createdAtText} · ${child.postNumber} 楼",
                                fontSize = 11.2.sp,
                                color = palette.inkTertiary
                            )
                        }
                        if (childCleanText.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = childCleanText,
                                fontSize = 13.sp,
                                lineHeight = 18.8.sp,
                                color = palette.inkSecondary,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
    pendingBoostDelete?.let { boostId ->
        LinDoConfirmDialog(
            title = "要删除这条助力吗？",
            confirmLabel = "删除",
            busy = isDeletingBoostId != null,
            error = floorActionError,
            destructive = true,
            onDismiss = { pendingBoostDelete = null; floorActionError = null },
            onConfirm = { deleteBoost(boostId) }
        )
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
    val normalized = reactionId.lowercase().trim().removeSurrounding(":")
    val resolvedUnicode = remember(normalized, emojiFallback) {
        LinuxDoEmojiCatalog.shortcodeToUnicode(normalized)
            ?: emojiFallback.takeUnless { it.startsWith(":") }
    }
    when {
        normalized == "heart" || normalized == "love" -> {
            Icon(
                imageVector = LinDoIcons.HeartFilled,
                contentDescription = "心心",
                modifier = Modifier.size(size),
                tint = Color(0xFFE11D48)
            )
        }
        normalized == "rocket" || normalized == "launch" -> {
            Icon(
                imageVector = LinDoIcons.Rocket,
                contentDescription = "小火箭",
                modifier = Modifier.size(size),
                tint = if (isSelected) Color(0xFF2563EB) else Color(0xFF3B82F6)
            )
        }
        normalized == "+1" || normalized == "thumbsup" || normalized == "thumb_up" -> {
            Icon(
                imageVector = LinDoIcons.ThumbUp,
                contentDescription = "赞同",
                modifier = Modifier.size(size),
                tint = Color(0xFFF59E0B)
            )
        }
        normalized == "fire" || normalized == "flame" -> {
            Icon(
                imageVector = LinDoIcons.Flame,
                contentDescription = "火热",
                modifier = Modifier.size(size),
                tint = Color(0xFFF97316)
            )
        }
        normalized == "confetti_ball" || normalized == "tada" || normalized == "party_popper" -> {
            Icon(
                imageVector = LinDoIcons.Sparkles,
                contentDescription = "庆祝",
                modifier = Modifier.size(size),
                tint = Color(0xFFEC4899)
            )
        }
        LinuxDoEmojiCatalog.isCustomImageEmoji(normalized) || resolvedUnicode == null -> {
            val imageUrl = remember(normalized) { LinuxDoEmojiCatalog.resolveReactionImageUrl(normalized) }
            var imgError by remember(imageUrl) { mutableStateOf(false) }
            if (imgError) {
                Text(
                    text = resolvedUnicode ?: "✨",
                    fontSize = (size.value * 0.85f).sp,
                    lineHeight = size.value.sp
                )
            } else {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = normalized,
                    contentScale = ContentScale.Fit,
                    onError = { imgError = true },
                    modifier = Modifier.size(size)
                )
            }
        }
        else -> {
            Text(
                text = resolvedUnicode,
                fontSize = (size.value * 0.88f).sp,
                lineHeight = size.value.sp
            )
        }
    }
}

private sealed interface BoostInlineSegment {
    data class TextRun(val value: String) : BoostInlineSegment
    data class EmojiRun(val shortcode: String) : BoostInlineSegment
}

@Composable
private fun CommunityPostContent(
    postId: Int,
    cookedHtml: String,
    originalUrl: String?,
    targetReplyFloorNum: Int?,
    targetReplyDisplayName: String?,
    repliedHtml: String?,
    onJumpToFloorNumber: (Int) -> Unit,
    loadingReplyTarget: Boolean,
    onSelectionChange: (String?) -> Unit,
    onOpenWebUrl: (String) -> Unit
) {
    val palette = LocalPalette.current
    androidx.compose.runtime.key(postId, cookedHtml) {
        val cached = remember { DiscourseAstParser.getCached(postId, cookedHtml) }
        val astNodes by androidx.compose.runtime.produceState(cached.orEmpty()) {
            if (cached == null) value = withContext(Dispatchers.Default) {
                DiscourseAstParser.parseOrGetCached(postId, cookedHtml)
            }
        }
        if (targetReplyFloorNum != null) {
            val repliedSnippet = remember(repliedHtml, astNodes.firstOrNull()) {
                if (astNodes.firstOrNull() is PostBlockNode.QuoteBlock) ""
                else repliedHtml?.let(::postPreviewText)?.take(200).orEmpty()
            }
            val target = if (targetReplyDisplayName.isNullOrBlank()) "$targetReplyFloorNum 楼"
            else "$targetReplyDisplayName · $targetReplyFloorNum 楼"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .clickable(
                        enabled = !loadingReplyTarget,
                        onClickLabel = "跳转到 $targetReplyFloorNum 楼",
                        onClick = { onJumpToFloorNumber(targetReplyFloorNum) }
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Icon(LinDoIcons.Reply, null, Modifier.size(14.dp), tint = palette.inkSecondary)
                Column(Modifier.weight(1f)) {
                    Text(
                        if (loadingReplyTarget) "加载 $targetReplyFloorNum 楼…" else target,
                        fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold, color = palette.inkSecondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    if (repliedSnippet.isNotBlank() && !loadingReplyTarget) {
                        Spacer(modifier = Modifier.height(1.dp))
                        Text(
                            repliedSnippet, fontSize = 11.5.sp, lineHeight = 14.5.sp, color = palette.inkTertiary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Icon(LinDoIcons.ChevronRight, null, Modifier.size(13.dp), tint = palette.inkTertiary)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        PostRichContent(astNodes, palette, onOpenWebUrl, originalUrl, postId, onSelectionChange)

    }
}

@Composable
private fun BoostInlineContent(
    plainText: String,
    fontSize: androidx.compose.ui.unit.TextUnit = 12.6.sp,
    emojiSize: Dp = 15.5.dp,
    textColor: Color
) {
    val segments = remember(plainText) {
        val list = mutableListOf<BoostInlineSegment>()
        val regex = Regex(":([a-zA-Z0-9_+\\-]+):")
        var cursor = 0
        for (match in regex.findAll(plainText)) {
            if (match.range.first > cursor) {
                val textPart = plainText.substring(cursor, match.range.first)
                if (textPart.isNotEmpty()) {
                    list.add(BoostInlineSegment.TextRun(textPart))
                }
            }
            val code = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (code.isNotEmpty()) {
                list.add(BoostInlineSegment.EmojiRun(code))
            }
            cursor = match.range.last + 1
        }
        if (cursor < plainText.length) {
            val tail = plainText.substring(cursor)
            if (tail.isNotEmpty()) {
                list.add(BoostInlineSegment.TextRun(tail))
            }
        }
        list
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        segments.forEach { seg ->
            when (seg) {
                is BoostInlineSegment.TextRun -> {
                    Text(
                        text = seg.value,
                        fontSize = fontSize,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                is BoostInlineSegment.EmojiRun -> {
                    PostReactionGlyph(
                        reactionId = seg.shortcode,
                        emojiFallback = LinuxDoEmojiCatalog.shortcodeToUnicode(seg.shortcode) ?: ":${seg.shortcode}:",
                        isSelected = false,
                        size = emojiSize
                    )
                }
            }
        }
    }
}

/**
 * 理想型社区（Warm Minimalist Editorial）「发现」页：
 * - 顶栏：左侧「发现」大标题 + 右侧线性搜索图标（无二级 Tab，版块/标签/生态一页流式完整呈现）
 * - 「正在热议」：拉取官方 `/top.json?period=daily` 真实今日热榜数据，支持手动刷新
 * - 「社区版块」：3 列莫兰迪低饱和度版块卡片
 * - 「热门社区标签」：双列热门 #Tag 快捷筛选入口
 * - 「LINUX DO 官方生态矩阵」：双列生态与社区服务入口（已移除「不止于讨论」板块）
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CommunityDiscoverScreen(
    hotTopics: List<TopicSummary>,
    isLoadingHot: Boolean = false,
    onRefreshHot: () -> Unit = {},
    onSelectCategory: (Int) -> Unit,
    onSelectTag: (String) -> Unit = {},
    onSelectTopic: (TopicSummary) -> Unit,
    onOpenGlobalSearch: () -> Unit = {},
    onOpenWebUrl: (String) -> Unit = {}
) {
    val palette = LocalPalette.current
    val categoryRows = remember(LinuxDoRepository.categories.size) {
        LinuxDoRepository.categories.chunked(3)
    }

    val popularTags = remember {
        listOf(
            "人工智能", "OpenAI", "Claude", "Docker",
            "Linux", "开源项目", "Cursor", "DeepSeek",
            "VPS", "自建服务", "福利", "抽奖"
        )
    }

    val ecoPortalRows = remember {
        listOf(
            DiscoverBoardEntry("Connect 中心", "保级 / 邮箱 / DeepLX", LinDoIcons.ShieldCheck, Color(0xFF3B82F6), "https://connect.linux.do/"),
            DiscoverBoardEntry("积分与活跃榜", "全站获赞与活跃排行", LinDoIcons.Trophy, Color(0xFFF59E0B), "https://linux.do/leaderboard"),
            DiscoverBoardEntry("全站徽章墙", "荣誉徽章与头衔规则", LinDoIcons.AwardBadge, Color(0xFFEAB308), "https://linux.do/badges"),
            DiscoverBoardEntry("关于与准则", "管理团队 / 统计 / 准则", LinDoIcons.InfoCircle, Color(0xFF6366F1), "https://linux.do/about"),
            DiscoverBoardEntry("Credit 积分", "积分流转与服务认证", LinDoIcons.Coins, Color(0xFFF59E0B), "https://credit.linux.do/"),
            DiscoverBoardEntry("CDK 分发", "社区福利领取与发布", LinDoIcons.Gift, Color(0xFFEC4899), "https://cdk.linux.do/"),
            DiscoverBoardEntry("LD Hub", "生态 AI 与聚合中心", LinDoIcons.Sparkles, Color(0xFF8B5CF6), "https://hub.linux.do/"),
            DiscoverBoardEntry("社区 Wiki", "共建指南与文档库", LinDoIcons.BookOpen, Color(0xFF10B981), "https://wiki.linux.do/"),
            DiscoverBoardEntry("IDC Flare", "官方云与主机生态站", LinDoIcons.CloudServer, Color(0xFF06B6D4), "https://idcflare.com/"),
            DiscoverBoardEntry("Lottery 抽奖", "官方楼层开奖工具", LinDoIcons.Trophy, Color(0xFFF97316), "https://lottery.linux.do/")
        ).chunked(2)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.canvas)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        LinDoPageHeader(title = "发现") {
            Icon(
                imageVector = LinDoIcons.Search,
                contentDescription = "搜索",
                modifier = Modifier
                    .size(LinDoTokens.IconL)
                    .clickable(onClick = onOpenGlobalSearch),
                tint = palette.inkPrimary
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.cardSurface),
            contentPadding = PaddingValues(bottom = 128.dp)
        ) {
            // 1. 社区版块（3 列平铺，仅保留水平分割线，无垂直切线）
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(palette.cardSurface)
                ) {
                    LinDoSectionHeader(title = "社区版块")

                    categoryRows.forEachIndexed { rowIdx, rowCats ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = LinDoTokens.PageHorizontalPadding),
                            verticalAlignment = Alignment.CenterVertically
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
                        if (rowIdx < categoryRows.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = LinDoTokens.PageHorizontalPadding),
                                thickness = 0.5.dp,
                                color = palette.hairline
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
                }
            }

            // 2. 热门标签（轻量芯片组 FlowRow，与版块/生态形成清晰视觉区分）
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(palette.cardSurface)
                ) {
                    LinDoSectionHeader(title = "热门标签")

                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = LinDoTokens.PageHorizontalPadding, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        popularTags.forEach { tag ->
                            LinDoTagChip(
                                name = tag,
                                onClick = { onSelectTag(tag) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
                }
            }

            // 3. 官方生态（双列平铺，仅保留水平分割线，无垂直切线）
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(palette.cardSurface)
                ) {
                    LinDoSectionHeader(title = "官方生态")

                    ecoPortalRows.forEachIndexed { rowIdx, pair ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = LinDoTokens.PageHorizontalPadding),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            pair.forEach { entry ->
                                DiscoverPortalCard(
                                    title = entry.title,
                                    subtitle = entry.subtitle,
                                    icon = entry.icon,
                                    iconTint = entry.tint,
                                    modifier = Modifier.weight(1f),
                                    onClick = { onOpenWebUrl(entry.url) }
                                )
                            }
                            if (pair.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                        if (rowIdx < ecoPortalRows.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = LinDoTokens.PageHorizontalPadding),
                                thickness = 0.5.dp,
                                color = palette.hairline
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class DiscoverBoardEntry(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val tint: Color,
    val url: String = ""
)

@Composable
private fun DiscoverPortalCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconTint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val palette = LocalPalette.current
    Row(
        modifier = modifier
            .height(44.dp)
            .clickable(onClick = onClick)
            .padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            modifier = Modifier.size(18.dp),
            tint = iconTint
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            fontSize = LinDoTokens.Sub,
            fontWeight = FontWeight.Medium,
            color = palette.inkPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun resolveDistinctCategoryIcon(category: LinuxDoCategoryMeta): ImageVector {
    val name = category.name.trim()
    return when {
        name.contains("开发") || category.id == 4 -> LinDoIcons.Code
        name.contains("资源") || category.id == 14 -> LinDoIcons.Layers
        name.contains("文档") || category.id == 42 -> LinDoIcons.FileText
        name.contains("非我") || name.contains("招聘") || category.id == 27 -> LinDoIcons.Users
        name.contains("读书") || category.id == 32 -> LinDoIcons.BookOpen
        name.contains("快讯") || name.contains("前沿") || category.id == 34 -> LinDoIcons.Bolt
        name.contains("记忆") || name.contains("历史") -> LinDoIcons.HistoryClock
        name.contains("福利") || name.contains("羊毛") || category.id == 36 -> LinDoIcons.Gift
        name.contains("搞七") || name.contains("日常") || category.id == 11 -> LinDoIcons.Coffee
        name.contains("孵化") || name.contains("创新") -> LinDoIcons.Seedling
        name.contains("运营") || name.contains("反馈") || name.contains("公告") -> LinDoIcons.Megaphone
        name.contains("深海") || name.contains("幽域") -> LinDoIcons.Anchor
        name.contains("扬帆") || name.contains("起航") -> LinDoIcons.Rocket
        name.contains("跳蚤") || name.contains("市场") || name.contains("交易") || category.id == 10 -> LinDoIcons.ShoppingBag
        name.contains("国产") || name.contains("替代") || name.contains("硬件") -> LinDoIcons.CpuChip
        name.contains("积分") || name.contains("乐园") -> LinDoIcons.Coins
        name.contains("虫洞") || name.contains("广场") -> LinDoIcons.Globe
        name.contains("智能") || name.contains("AI") || category.id == 46 -> LinDoIcons.Sparkles
        name.contains("问答") || name.contains("讨论") -> LinDoIcons.MessageCircle
        name.contains("安全") || name.contains("认证") -> LinDoIcons.ShieldCheck
        name.contains("主机") || name.contains("云") -> LinDoIcons.CloudServer
        else -> {
            val fallbackPool = listOf(
                LinDoIcons.Terminal,
                LinDoIcons.KanbanBoard,
                LinDoIcons.AwardBadge,
                LinDoIcons.BarChartPoll,
                LinDoIcons.AppsGrid,
                LinDoIcons.Trophy,
                LinDoIcons.WifiSpeed,
                LinDoIcons.Sliders
            )
            fallbackPool[(category.id.coerceAtLeast(0)) % fallbackPool.size]
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
    val (_, morandiFg) = remember(category.name, palette.isDark) {
        resolveMorandiCategoryColors(category.name, palette.isDark)
    }
    val categoryIcon = remember(category.id, category.name) {
        resolveDistinctCategoryIcon(category)
    }

    Row(
        modifier = modifier
            .height(44.dp)
            .clickable(onClick = onClick)
            .padding(end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = categoryIcon,
            contentDescription = category.name,
            modifier = Modifier.size(18.dp),
            tint = morandiFg
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = category.name,
            fontSize = LinDoTokens.Sub,
            fontWeight = FontWeight.Medium,
            color = palette.inkPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun notificationCategory(type: Int): Int = when (type) {
    2, 3, 9, 11, 38, 802 -> 1
    5, 19, 25 -> 2
    1, 15, 29, 32, 33 -> 3
    else -> 4
}

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
    onManagePrivateMessages: () -> Unit,
    onOpenWebUrl: (String) -> Unit,
    onMarkedAllRead: () -> Unit
) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    var showMarkAllConfirm by remember { mutableStateOf(false) }
    var markingAll by remember { mutableStateOf(false) }
    var markAllError by remember { mutableStateOf<String?>(null) }
    var subTab by remember { mutableStateOf(0) } // 0=通知, 1=私信
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
            loadError = error.message ?: "加载失败"
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
        if (categoryFilter == 0) notifications
        else notifications.filter { notificationCategory(it.notificationType) == categoryFilter }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.canvas)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.cardSurface)
                .padding(
                    start = LinDoTokens.PageHorizontalPadding,
                    end = LinDoTokens.PageHorizontalPadding,
                    top = 0.dp,
                    bottom = 0.dp
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(LinDoTokens.PageHeaderHeight),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "消息",
                    fontSize = LinDoTokens.Display,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.4).sp,
                    color = palette.inkPrimary,
                    modifier = Modifier.weight(1f)
                )

                when {
                    !isAuthorized -> TextButton(onClick = onRequestLogin) { Text("登录") }
                    subTab == 0 -> LinDoNotificationActions(
                        canMarkAll = notifications.isNotEmpty(),
                        enabled = !isLoading && !markingAll,
                        onMarkAll = { showMarkAllConfirm = true },
                        onRefresh = { reloadToken++ }
                    )
                    else -> TextButton(onClick = onManagePrivateMessages) { Text("管理私信") }
                }
            }
            LinDoSectionTabs(listOf("通知", "私信", "聊天"), subTab) {
                if (it == 2) onOpenWebUrl("https://linux.do/chat") else subTab = it
            }
        }

        if (subTab == 0 && isAuthorized) {
            val labels = listOf("全部", "回复", "表态", "提及", "系统")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.cardSurface)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp)
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                labels.forEachIndexed { index, label ->
                    GoogleM3FilterPill(label, null, categoryFilter == index, Modifier.widthIn(min = 48.dp)) {
                        categoryFilter = index
                    }
                }
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)

        if (subTab == 1) {
            // 私信列表与写私信入口
            if (isLoading && privateMessages.isEmpty()) {
                LinearShimmerTopicSkeletonList()
            } else {
                LazyColumn(
                    state = pmListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(palette.cardSurface),
                    contentPadding = PaddingValues(top = 2.dp, bottom = 128.dp)
                ) {
                    if (loadError != null && privateMessages.isEmpty()) {
                        item(key = "pm_error") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                LinDoStatePanel(
                                    state = LinDoMascotState.NetworkError,
                                    detail = loadError!!,
                                    onRetry = { reloadToken++ }
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
                                LinDoEmptyState(message = "暂无私信")
                            }
                        }
                    } else if (privateMessages.isNotEmpty()) {
                        itemsIndexed(privateMessages, key = { _, m -> m.id }, contentType = { _, _ -> "topic" }) { idx, msg ->
                            CommunityTopicItem(
                                topic = msg,
                                isSelected = false,
                                isCompact = true,
                                enableAnimations = false,
                                onClick = { onSelectMessageTopic(msg) }
                            )
                            if (idx < privateMessages.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 14.dp),
                                    thickness = 0.5.dp,
                                    color = palette.hairline
                                )
                            }
                        }
                        item(key = "pm_end_footer") {
                            LinearPulseLoadingFooter(
                                isLoadingMore = false,
                                hasMore = false,
                                loadedCount = privateMessages.size,
                                onLoadMore = {}
                            )
                        }
                    }
                }
            }
        } else {
            if (isLoading && notifications.isEmpty()) {
                LinearShimmerTopicSkeletonList()
            } else {
                LazyColumn(
                    state = notifListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(palette.cardSurface),
                    contentPadding = PaddingValues(top = 2.dp, bottom = 128.dp)
                ) {
                    val error = loadError
                    if (error != null && notifications.isNotEmpty()) {
                        item(key = "notif_refresh_error") { LinDoLoadFailure(error) { reloadToken++ } }
                    }
                    if (error != null && notifications.isEmpty()) {
                        item(key = "notif_error") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                LinDoStatePanel(
                                    state = LinDoMascotState.NetworkError,
                                    detail = error,
                                    onRetry = { reloadToken++ }
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
                                LinDoEmptyState(message = "暂无通知")
                            }
                        }
                    } else if (filteredNotifications.isNotEmpty()) {
                        itemsIndexed(filteredNotifications, key = { _, item -> item.id }, contentType = { _, _ -> "notification" }) { _, item ->
                            val actor = item.actorUsername
                            LinDoNotificationRow(
                                item = item,
                                onInspectUser = onInspectUser,
                                onClick = {
                                    if (!item.read) {
                                        val updated = notifications.map {
                                            if (it.id == item.id) it.copy(read = true) else it
                                        }
                                        notifications = updated
                                        onUnreadCountSynced(updated.count { !it.read })
                                    }
                                    when {
                                        item.topicId > 0 -> onSelectTopicId(item.topicId, item.title, item.postNumber)
                                        item.badgeId > 0 -> onInspectBadge(
                                            GrantedBadge(
                                                id = item.badgeId,
                                                name = item.badgeName.ifBlank { item.title },
                                                description = "LINUX DO 社区授予徽章"
                                            )
                                        )
                                        item.notificationType in 29..33 -> {
                                            val url = if (item.chatChannelId > 0) {
                                                "https://linux.do/chat/c/-/${item.chatChannelId}"
                                            } else {
                                                "https://linux.do/chat"
                                            }
                                            onOpenWebUrl(url)
                                        }
                                        actor != null -> onInspectUser(actor)
                                    }
                                }
                            )
                        }
                        item(key = "notifications_end_footer") {
                            LinearPulseLoadingFooter(
                                isLoadingMore = false,
                                hasMore = false,
                                loadedCount = filteredNotifications.size,
                                onLoadMore = {}
                            )
                        }
                    }
                }
            }
        }
    }
    if (showMarkAllConfirm) LinDoConfirmDialog(title = "要将通知全部标为已读吗？", confirmLabel = "标为已读", busy = markingAll, error = markAllError,
        onDismiss = { showMarkAllConfirm = false; markAllError = null }, onConfirm = {
            if (!markingAll) {
                markingAll = true
                markAllError = null
                scope.launch {
                    try {
                        val ok = LinuxDoRepository.markNotificationsRead()
                        if (ok) {
                            showMarkAllConfirm = false
                            notifications = notifications.map { it.copy(read = true) }
                            onMarkedAllRead()
                        } else {
                            markAllError = "标记失败"
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (error: Exception) { markAllError = error.message ?: "标记失败" }
                    finally { markingAll = false }
                }
            }
        })

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
internal fun CommunityProfileScreen(
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
    onOpenAppIcon: (() -> Unit)? = null,
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
    onOpenFollowSheet: () -> Unit = {},
    onOpenLeaderboardSheet: () -> Unit = {},
    onOpenAccountPreferences: () -> Unit = {},
    onOpenAboutSheet: () -> Unit = {},
    onInspectBadge: (GrantedBadge) -> Unit,
    onOpenBadgesCenter: () -> Unit = {},
    onOpenConnectSheet: () -> Unit,
    onOpenAuthSheet: () -> Unit,
    onOpenWebUrl: (String) -> Unit = {},
    onPrimaryWebLogin: () -> Unit,
    onLogout: () -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState()
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
        // 顶栏：我的 + 右上角同步资料 + 昼夜主题切换（小太阳/月亮），严格对齐 16dp 边距与 22dp 图标
        LinDoPageHeader(title = "我的") {
            if (isAuthorized) {
                Icon(
                    imageVector = LinDoIcons.Refresh,
                    contentDescription = "同步资料",
                    modifier = Modifier
                        .size(LinDoTokens.IconL)
                        .clickable(onClick = onRefreshProfile),
                    tint = palette.inkSecondary
                )
            }
            Icon(
                imageVector = if (isDarkTheme) LinDoIcons.Sun else LinDoIcons.Moon,
                contentDescription = if (isDarkTheme) "切换浅色模式" else "切换深色模式",
                modifier = Modifier
                    .size(LinDoTokens.IconL)
                    .clickable(onClick = onToggleDark),
                tint = palette.inkPrimary
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(palette.cardSurface),
            contentPadding = PaddingValues(bottom = 128.dp)
        ) {
            // 1. 个人信息与顶部 4 项线性图标快捷栏（统一 16dp 左右基准线）
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(palette.cardSurface)
                        .padding(horizontal = LinDoTokens.PageHorizontalPadding, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GoogleAccountRingAvatar(
                            letter = displayName.ifBlank { "L" },
                            avatarUrl = avatarUrl,
                            animatedAvatar = fullProfile?.animatedAvatar ?: currentUser?.animatedAvatar,
                            flair = fullProfile?.flair,
                            avatarSize = 52.dp,
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
                                    fontSize = 18.sp,
                                    lineHeight = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = palette.inkPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Text(
                                    text = if (displayUsername.isNotBlank()) "@" + displayUsername else "@linux.do",
                                    fontSize = LinDoTokens.Meta,
                                    lineHeight = 16.sp,
                                    color = palette.inkSecondary,
                                    maxLines = 1
                                )
                            }

                            if (isAuthorized || !userTitle.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(3.dp))

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (isAuthorized) {
                                        Text(
                                            text = trustLabel,
                                            fontSize = LinDoTokens.Meta,
                                            lineHeight = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = palette.brandGold,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                    if (!userTitle.isNullOrBlank()) {
                                        UserTitleTag(title = userTitle)
                                    }
                                }
                            }
                        }

                        Icon(
                            imageVector = LinDoIcons.Edit,
                            contentDescription = "编辑资料",
                            modifier = Modifier
                                .size(LinDoTokens.IconM)
                                .clickable {
                                    if (isAuthorized) onOpenEditProfile() else onPrimaryWebLogin()
                                },
                            tint = palette.inkSecondary
                        )
                    }

                    if (isAuthorized && fullProfile != null && fullProfile.badges.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            fullProfile.badges.forEach { badge ->
                                UniversalBadgeChip(
                                    badge = badge,
                                    compact = true,
                                    onClick = onInspectBadge
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(palette.subtleSurface)
                                    .clickable { onOpenBadgesCenter() }
                                    .padding(horizontal = 8.dp, vertical = 3.5.dp)
                            ) {
                                Text(
                                    text = "全部 ${fullProfile.badgeCount.coerceAtLeast(fullProfile.badges.size)} 枚",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = palette.inkSecondary,
                                    maxLines = 1
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Icon(
                                    imageVector = LinDoIcons.ChevronRight,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = palette.inkTertiary
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 顶部 4 项线性图标快捷栏（我的收藏 | 阅读历史 | 草稿箱 | 我的活动）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        val topActions = listOf(
                            Triple("收藏", LinDoIcons.Bookmark) {
                                if (isAuthorized) onOpenBookmarks() else onPrimaryWebLogin()
                            },
                            Triple("历史", LinDoIcons.HistoryClock) {
                                onOpenReadHistory()
                            },
                            Triple("草稿", LinDoIcons.FileText) {
                                if (isAuthorized) onOpenCloudDrafts() else onPrimaryWebLogin()
                            },
                            Triple("活动", LinDoIcons.Calendar) {
                                if (isAuthorized) onOpenActivities() else onPrimaryWebLogin()
                            }
                        )

                        topActions.forEach { (label, iconVec, action) ->
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(LinDoTokens.ShapeS)
                                    .clickable { action() }
                                    .padding(vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = iconVec,
                                    contentDescription = label,
                                    modifier = Modifier.size(LinDoTokens.IconL),
                                    tint = palette.inkPrimary
                                )
                                Spacer(modifier = Modifier.height(5.dp))
                                Text(
                                    text = label,
                                    fontSize = LinDoTokens.Meta,
                                    fontWeight = FontWeight.Normal,
                                    color = palette.inkSecondary,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
            }

            // 2. 分组单行设置列表（社区 & 账号）
            item {
                val badgeCountValue = if (isAuthorized && fullProfile != null) {
                    fullProfile.badgeCount.coerceAtLeast(fullProfile.badges.size).toString() + " 枚"
                } else {
                    null
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(palette.cardSurface)
                ) {
                    LinDoGroupHeader(text = "社区")

                    LinDoSettingRow(
                        icon = LinDoIcons.Trophy,
                        title = "徽章与头衔",
                        value = badgeCountValue,
                        onClick = onOpenBadgesCenter
                    )
                    LinDoSettingRow(
                        icon = LinDoIcons.MessageCircle,
                        title = "我的社区",
                        onClick = { if (isAuthorized) onOpenActivities() else onPrimaryWebLogin() }
                    )
                    LinDoSettingRow(
                        icon = LinDoIcons.Users,
                        title = "关注与粉丝",
                        onClick = { if (isAuthorized) onOpenFollowSheet() else onPrimaryWebLogin() }
                    )
                    LinDoSettingRow(
                        icon = LinDoIcons.Coins,
                        title = "积分与活跃榜",
                        onClick = onOpenLeaderboardSheet
                    )
                    LinDoSettingRow(
                        icon = LinDoIcons.Sparkles,
                        title = "Connect 保级与成长",
                        showDivider = false,
                        onClick = onOpenConnectSheet
                    )

                    HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)

                    LinDoGroupHeader(text = "账号")

                    LinDoSettingRow(
                        icon = LinDoIcons.ShieldCheck,
                        title = "账号与偏好设置",
                        onClick = { if (isAuthorized) onOpenAccountPreferences() else onPrimaryWebLogin() }
                    )
                    LinDoSettingRow(
                        icon = LinDoIcons.Send,
                        title = "站内私信",
                        showDivider = false,
                        onClick = { if (isAuthorized) onOpenPrivateMessages() else onPrimaryWebLogin() }
                    )

                    HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
                }
            }

            // 3. 外观分组（主题配色、应用图标、首页列表视图、阅读字号、关于社区）
            item {
                val nextScale = if (fontScale < 0.96f) 1.0f else if (fontScale < 1.05f) 1.10f else 0.92f
                val scaleLabel = if (fontScale < 0.96f) "小字号" else if (fontScale < 1.05f) "标准" else "大字号"

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(palette.cardSurface)
                ) {
                    LinDoGroupHeader(text = "外观")

                    // 3.1 主题配色（内联 4 色圆点选择器）
                    LinDoSettingRow(
                        icon = LinDoIcons.Palette,
                        title = "主题配色",
                        trailing = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                BrandThemeOption.entries.forEach { option ->
                                    val selected = themeOption == option
                                    Box(
                                        modifier = Modifier
                                            .size(20.dp)
                                            .clip(CircleShape)
                                            .background(option.swatchColor.copy(alpha = if (selected) 1f else 0.35f))
                                            .border(
                                                width = if (selected) 1.8.dp else 0.5.dp,
                                                color = if (selected) palette.inkPrimary else palette.hairline,
                                                shape = CircleShape
                                            )
                                            .clickable { onSelectThemeOption(option) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (selected) {
                                            Box(
                                                modifier = Modifier
                                                    .size(6.dp)
                                                    .clip(CircleShape)
                                                    .background(Color.White)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    )

                    if (onOpenAppIcon != null) {
                        val icon = LocalLinDoAppIcon.current ?: LinDoAppIcon.Classic
                        LinDoSettingRow(
                            icon = LinDoIcons.Image,
                            title = "应用图标",
                            value = "${icon.artwork.label} · ${icon.background.label}",
                            onClick = onOpenAppIcon
                        )
                    }

                    // 3.2 首页列表视图（精简紧凑单行分段切换）
                    LinDoSettingRow(
                        icon = if (isCompactFeed) LinDoIcons.LayoutCompact else LinDoIcons.LayoutCards,
                        title = "首页列表视图",
                        onClick = onToggleCompactFeed,
                        trailing = {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(palette.subtleSurface)
                                    .border(0.5.dp, palette.hairline, RoundedCornerShape(8.dp))
                                    .padding(2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                listOf("宽松" to false, "紧凑" to true).forEach { (label, compactMode) ->
                                    val selected = isCompactFeed == compactMode
                                    Box(
                                        modifier = Modifier
                                            .clip(LinDoTokens.ShapeXS)
                                            .background(if (selected) palette.cardSurface else Color.Transparent)
                                            .clickable {
                                                if (isCompactFeed != compactMode) onToggleCompactFeed()
                                            }
                                            .padding(horizontal = 10.dp, vertical = 4.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = label,
                                            fontSize = LinDoTokens.Meta,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (selected) palette.brandGold else palette.inkSecondary
                                        )
                                    }
                                }
                            }
                        }
                    )

                    // 3.3 阅读字号
                    LinDoSettingRow(
                        icon = LinDoIcons.Sliders,
                        title = "阅读字号",
                        value = scaleLabel,
                        onClick = { onChangeFontScale(nextScale) }
                    )

                    // 3.4 关于社区
                    LinDoSettingRow(
                        icon = LinDoIcons.InfoCircle,
                        title = "关于社区",
                        showDivider = false,
                        onClick = onOpenAboutSheet
                    )

                    HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
                }
            }

            // 4. 列表最底部：退出登录（全宽平铺轻分割线风格）
            if (isAuthorized) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(LinDoTokens.SettingRowHeight)
                            .background(palette.cardSurface)
                            .clickable { onLogout() }
                            .padding(horizontal = LinDoTokens.PageHorizontalPadding),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = LinDoIcons.LogOut,
                            contentDescription = "退出登录",
                            modifier = Modifier.size(LinDoTokens.IconS),
                            tint = Color(0xFFEF4444)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "退出当前账号",
                            fontSize = LinDoTokens.Body,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFEF4444)
                        )
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
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

/** 徽章定义、获授信息和可用的头衔操作。 */
@Composable
private fun BadgeDetailBottomSheet(
    badge: GrantedBadge,
    currentUsername: String = "",
    currentUserTitle: String = "",
    isAuthorized: Boolean = false,
    onDismiss: () -> Unit,
    onTitleUpdated: () -> Unit = {}
) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()

    var officialBadge by remember(badge.id) { mutableStateOf<GrantedBadge?>(null) }
    val effectiveBadge = officialBadge ?: badge

    val canSetTitle = isAuthorized && currentUsername.isNotBlank() &&
        officialBadge?.let { it.allowTitle && it.isEarned } == true

    var isEquippingTitle by remember(effectiveBadge.id) { mutableStateOf(false) }
    var titleEquipStatus by remember(effectiveBadge.id) { mutableStateOf<String?>(null) }
    var equippedNow by remember(effectiveBadge.name, currentUserTitle) {
        mutableStateOf(currentUserTitle.isNotBlank() && currentUserTitle.trim().equals(effectiveBadge.name.trim(), ignoreCase = true))
    }

    LaunchedEffect(badge.id, badge.grantedAtText, badge.topicId) {
        officialBadge = LinuxDoRepository.fetchBadgeDefinition(badge.id.takeIf { it > 0 } ?: return@LaunchedEffect)?.copy(
            isFirstTimeGrant = badge.isFirstTimeGrant,
            count = badge.count, grantedAtText = badge.grantedAtText,
            topicId = badge.topicId, topicTitle = badge.topicTitle, postNumber = badge.postNumber
        )
    }

    LinDoFullScreenPage(
        onBack = onDismiss,
        containerColor = palette.cardSurface
    ) {
        LinDoPageTopBar(
            title = "徽章详情",
            onBack = onDismiss
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(Modifier.width(40.dp), contentAlignment = Alignment.Center) {
                    PostGrantedBadgeIcon(effectiveBadge, size = 36.dp)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        effectiveBadge.name, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        color = palette.inkPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    val metadata = listOfNotNull(
                        effectiveBadge.badgeType.labelZh,
                        "已获得 ${effectiveBadge.count} 次".takeIf { effectiveBadge.count > 1 },
                        "从这条回复获得".takeIf { effectiveBadge.isFirstTimeGrant },
                        when {
                            equippedNow -> "当前头衔"
                            effectiveBadge.allowTitle && !canSetTitle -> "可设头衔"
                            else -> null
                        }
                    )
                    Text(metadata.joinToString(" · "), fontSize = 12.sp, color = palette.inkSecondary)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = effectiveBadge.plainDescription.ifBlank {
                        "暂无徽章说明"
                    },
                    fontSize = 13.8.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 20.sp,
                    color = palette.inkPrimary
                )

                val longDesc = effectiveBadge.plainLongDescription
                if (longDesc.isNotBlank() && !longDesc.equals(effectiveBadge.plainDescription, ignoreCase = true)) {
                    Text(
                        text = longDesc,
                        fontSize = 12.4.sp,
                        lineHeight = 18.5.sp,
                        color = palette.inkSecondary
                    )
                }

                val grantDate = effectiveBadge.grantedAtText.takeIf { it.isNotBlank() }
                val grantMetadata = listOfNotNull(
                    "全站授予 ${formatCompactNumber(effectiveBadge.grantCount)} 次".takeIf { effectiveBadge.grantCount > 0 },
                    grantDate?.let { "$it 获得" },
                    "已获得".takeIf { grantDate == null && officialBadge?.isEarned == true && !effectiveBadge.isFirstTimeGrant }
                )
                if (grantMetadata.isNotEmpty()) {
                    Text(
                        grantMetadata.joinToString(" · "),
                        fontSize = 11.5.sp, color = palette.inkTertiary
                    )
                }

                if (canSetTitle && !equippedNow) {
                    TextButton(
                        enabled = !isEquippingTitle,
                        onClick = {
                            scope.launch {
                                isEquippingTitle = true
                                titleEquipStatus = null
                                val ok = LinuxDoRepository.updateUserTitle(
                                    username = currentUsername,
                                    title = effectiveBadge.name
                                )
                                isEquippingTitle = false
                                if (ok) {
                                    equippedNow = true
                                    titleEquipStatus = "已设为头衔"
                                    onTitleUpdated()
                                } else {
                                    titleEquipStatus = "设置失败，请稍后重试"
                                }
                            }
                        }
                    ) {
                        Text(
                            if (isEquippingTitle) "设置中…" else "设为头衔",
                            color = if (isEquippingTitle) palette.inkSecondary else palette.accent
                        )
                    }
                }
            }

            if (titleEquipStatus != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = titleEquipStatus!!,
                    fontSize = 11.8.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (equippedNow) palette.brandGold else palette.inkSecondary
                )
            }
        }
    }
}

/**
 * 原生全站徽章墙与个人已获徽章中心独立页面 (`CommunityBadgesSheet`)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommunityBadgesSheet(
    targetUsername: String,
    currentUserTitle: String = "",
    initialEarnedBadges: List<GrantedBadge> = emptyList(),
    initialEarnedCount: Int = 0,
    onDismiss: () -> Unit,
    onInspectBadge: (GrantedBadge) -> Unit
) {
    val palette = LocalPalette.current
    val focusManager = LocalFocusManager.current

    var selectedTab by remember(targetUsername) { mutableIntStateOf(if (targetUsername.isBlank()) 1 else 0) }
    var tierFilter by remember { mutableIntStateOf(0) } // 0=全部, 1=金徽章, 2=银徽章, 3=铜徽章, 4=可设头衔
    var searchQuery by remember { mutableStateOf("") }

    var earnedBadges by remember(targetUsername) {
        val cached = LinuxDoRepository.getCachedUserBadges(targetUsername).orEmpty()
        val initialFull = if (cached.isNotEmpty()) {
            cached
        } else if (initialEarnedCount <= 0 || initialEarnedBadges.size >= initialEarnedCount) {
            initialEarnedBadges.distinctBy { it.id }
        } else {
            emptyList()
        }
        mutableStateOf(initialFull)
    }
    var allSiteBadges by remember { mutableStateOf(LinuxDoRepository.cachedSiteBadges) }
    var isLoading by remember { mutableStateOf(LinuxDoRepository.cachedSiteBadges.isEmpty()) }
    var loadingOwned by remember(targetUsername) {
        mutableStateOf(targetUsername.isNotBlank() && LinuxDoRepository.getCachedUserBadges(targetUsername).isNullOrEmpty())
    }
    var ownedError by remember(targetUsername) { mutableStateOf(false) }
    var catalogError by remember { mutableStateOf(false) }
    var retryCatalog by remember { mutableIntStateOf(0) }
    var retryOwned by remember(targetUsername) { mutableIntStateOf(0) }

    LaunchedEffect(targetUsername, retryOwned) {
        val cleanUser = targetUsername.trim().removePrefix("@")
        if (cleanUser.isBlank()) return@LaunchedEffect
        loadingOwned = true
        ownedError = false
        try {
            earnedBadges = LinuxDoRepository.fetchUserBadges(cleanUser)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (earnedBadges.isEmpty() && initialEarnedBadges.isNotEmpty()) {
                earnedBadges = initialEarnedBadges.distinctBy { it.id }
            } else {
                ownedError = true
            }
        } finally {
            loadingOwned = false
        }
    }
    LaunchedEffect(retryCatalog) {
        isLoading = true
        catalogError = false
        try {
            allSiteBadges = LinuxDoRepository.fetchAllSiteBadges()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            catalogError = allSiteBadges.isEmpty()
        } finally {
            isLoading = false
        }
    }

    val siteBadgesWithOwnership = remember(allSiteBadges, earnedBadges) {
        val ownedById = earnedBadges.associateBy { it.id }
        allSiteBadges.map { badge ->
            ownedById[badge.id]?.let { owned ->
                badge.copy(isEarned = true, count = owned.count, grantedAtText = owned.grantedAtText)
            } ?: badge
        }
    }
    val sourceList = if (selectedTab == 0) earnedBadges else siteBadgesWithOwnership
    val filteredBadges = remember(sourceList, tierFilter, searchQuery) {
        val q = searchQuery.trim()
        sourceList.filter { badge ->
            val matchesTier = when (tierFilter) {
                1 -> badge.badgeTypeId == 1
                2 -> badge.badgeTypeId == 2
                3 -> badge.badgeTypeId == 3
                4 -> badge.allowTitle
                else -> true
            }
            val matchesQuery = q.isEmpty() ||
                badge.name.contains(q, ignoreCase = true) ||
                badge.plainDescription.contains(q, ignoreCase = true) ||
                badge.plainLongDescription.contains(q, ignoreCase = true) ||
                badge.slug.contains(q, ignoreCase = true)
            matchesTier && matchesQuery
        }
    }

    val earnedCountDisplay = if (earnedBadges.isNotEmpty()) {
        maxOf(initialEarnedCount, earnedBadges.sumOf { it.count.coerceAtLeast(1) })
    } else {
        initialEarnedCount
    }
    val earnedTabLabel = if (earnedCountDisplay > 0 || !loadingOwned) "已获徽章 $earnedCountDisplay" else "已获徽章"
    val siteTabLabel = if (allSiteBadges.isNotEmpty() || !isLoading) "全站徽章 ${allSiteBadges.size}" else "全站徽章"

    LinDoFullScreenPage(
        onBack = onDismiss,
        containerColor = palette.cardSurface
    ) {
        LinDoPageTopBar(
            title = "徽章",
            onBack = onDismiss
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
        ) {
            if (targetUsername.isNotBlank()) {
                LinDoSectionTabs(
                    labels = listOf(earnedTabLabel, siteTabLabel),
                    selectedIndex = selectedTab
                ) { index ->
                    focusManager.clearFocus()
                    selectedTab = index
                }
            }

            Spacer(Modifier.height(12.dp))
            WorkbenchCompactInputBox(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = "搜索徽章",
                leadingIcon = LinDoIcons.Search,
                modifier = Modifier.fillMaxWidth(),
                onSearch = { focusManager.clearFocus() }
            )

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf("全部", "金", "银", "铜", "可设头衔").forEachIndexed { code, label ->
                    GoogleM3FilterPill(
                        label = label,
                        dotColor = null,
                        selected = tierFilter == code,
                        onClick = { tierFilter = code }
                    )
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)

            // 5. 徽章列表
            if ((if (selectedTab == 0) loadingOwned else isLoading) && sourceList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    LinDoLoadingIndicator(label = "正在同步社区徽章目录")
                }
            } else if (selectedTab == 0 && ownedError && sourceList.isEmpty()) {
                LinDoLoadFailure("已获徽章加载失败") { retryOwned++ }
            } else if (selectedTab == 1 && catalogError) {
                LinDoLoadFailure("徽章目录加载失败") { retryCatalog++ }
            } else if (filteredBadges.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LinDoEmptyState(
                            message = if (selectedTab == 0 && searchQuery.isBlank() && tierFilter == 0) {
                                "还没有获得徽章"
                            } else {
                                "没有匹配的徽章"
                            }
                        )
                        if (selectedTab == 0) {
                            Spacer(modifier = Modifier.height(10.dp))
                            TextButton(onClick = {
                                focusManager.clearFocus()
                                selectedTab = 1
                            }) {
                                Text("浏览全站徽章", color = palette.accent)
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 20.dp)
                ) {
                    itemsIndexed(filteredBadges, key = { _, badge -> badge.id }, contentType = { _, _ -> "badge" }) { index, badge ->
                        val startsGroup = badge.badgeGroupingName.isNotBlank() &&
                            filteredBadges.getOrNull(index - 1)?.badgeGroupingId != badge.badgeGroupingId
                        if (startsGroup) {
                            Text(
                                text = when (badge.badgeGroupingName) {
                                    "Getting Started" -> "入门"
                                    "Community" -> "社区"
                                    "Posting" -> "发帖"
                                    "Trust Level" -> "信任等级"
                                    "Other" -> "其他"
                                    else -> badge.badgeGroupingName
                                },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = palette.inkSecondary,
                                modifier = Modifier.padding(top = 15.dp, bottom = 4.dp)
                            )
                        }

                        val isCurrentTitle = badge.isEarned && badge.allowTitle && currentUserTitle.isNotBlank() &&
                            currentUserTitle.trim().equals(badge.name.trim(), ignoreCase = true)
                        val description = remember(badge.name, badge.description, badge.longDescription) {
                            badge.plainDescription.takeUnless { it == badge.name }
                                ?: badge.plainLongDescription
                        }
                        val status = when {
                            isCurrentTitle -> "当前头衔"
                            selectedTab == 1 && badge.isEarned && badge.grantedAtText.isNotBlank() ->
                                "${badge.grantedAtText} 获得"
                            else -> ""
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)
                                .clickable {
                                    focusManager.clearFocus()
                                    onInspectBadge(badge)
                                }
                                .padding(vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(Modifier.width(30.dp), contentAlignment = Alignment.Center) {
                                PostGrantedBadgeIcon(badge, size = 24.dp)
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    badge.name, color = palette.inkPrimary, fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (description.isNotBlank()) {
                                    Text(
                                        description, color = palette.inkSecondary, fontSize = 12.5.sp,
                                        lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (status.isNotEmpty()) {
                                    Text(status, fontSize = 11.sp, color = palette.inkTertiary)
                                }
                            }
                            Icon(
                                LinDoIcons.ChevronRight, contentDescription = null,
                                modifier = Modifier.size(14.dp), tint = palette.inkTertiary
                            )
                        }
                        if (index < filteredBadges.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 42.dp),
                                thickness = 0.5.dp, color = palette.hairline
                            )
                        }
                    }
                }
            }
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
    LinDoFullScreenPage(onBack = onDismiss, containerColor = palette.cardSurface) {
        LinDoPageTopBar(
            title = "Connect",
            onBack = onDismiss,
            trailingContent = {
                TextButton(enabled = !isLoading, onClick = { scope.launch { refresh() } }) { Text("刷新") }
            }
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp)) {
            Text(report?.subtitle ?: "正在查询 Connect 官方页面", color = palette.inkSecondary,
                fontSize = 12.sp, lineHeight = 19.sp)
            Spacer(Modifier.height(12.dp))
            if (isLoading) LinDoLoadingIndicator(label = "零度娘正在同步 Connect")
            val current = report
            if (!isLoading && current?.metrics.isNullOrEmpty()) {
                LinDoStatePanel(LinDoMascotState.NetworkError,
                    detail = "暂未取得 Connect 统计",
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

/**
 * 极简无框账号登录与切换独立页面
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

    LinDoFullScreenPage(
        onBack = onDismiss,
        containerColor = palette.cardSurface
    ) {
        LinDoPageTopBar(
            title = if (isAuthorized) "账号与会话管理" else "登录 LINUX DO",
            onBack = onDismiss
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 28.dp),
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
    onToggleDark: () -> Unit = {},
    onOpenAppIcon: (() -> Unit)? = null
) {
    LinDoWelcomeScreen(
        isDark = LocalPalette.current.isDark,
        onPrimaryWebLogin = onPrimaryWebLogin,
        onBrowserOneClickAuth = onBrowserOneClickAuth,
        onToggleDark = onToggleDark,
        onOpenAppIcon = onOpenAppIcon
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
    replyToDisplayName: String? = null,
    replyToAvatarUrl: String? = null,
    replyToAnimatedAvatar: String? = null,
    replyToContentSnippet: String? = null,
    initialRaw: String = "",
    initialDraft: CloudDraftItem? = null,
    onDismiss: () -> Unit,
    onPostCreated: (PostItemModel) -> Unit
) = ComposerSheet(
    ComposerAction.REPLY,
    topicId = topicId,
    topicTitle = topicTitle,
    replyToPostNumber = replyToPostNumber,
    replyToUsername = replyToUsername,
    replyToDisplayName = replyToDisplayName,
    replyToAvatarUrl = replyToAvatarUrl,
    replyToAnimatedAvatar = replyToAnimatedAvatar,
    replyToContentSnippet = replyToContentSnippet,
    initialRaw = initialRaw,
    initialDraft = initialDraft,
    onDismiss = onDismiss,
    onPublished = { it.post?.let(onPostCreated) }
)

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
    initialCategoryId: Int, initialDraft: CloudDraftItem? = null,
    onDismiss: () -> Unit, onTopicCreated: (Int, String) -> Unit
) = ComposerSheet(ComposerAction.NEW_TOPIC, initialCategoryId = initialCategoryId,
    initialDraft = initialDraft, onDismiss = onDismiss,
    onPublished = { onTopicCreated(it.topicId, it.title) })

/**
 * ============================================================================
 * LinDo 全功能 Markdown & Discourse 扩展语法创作工作台 (`LinDoMarkdownWorkbench`)
 * 移动端原生悬浮 Dock 架构：
 * 1. 支持外部顶栏受控视图切换 (`externalViewMode`) 与弹性全高画布 (`fillAvailableHeight`)
 * 2. 中部核心：无边框沉浸式正文编辑画布 / 实时 AST 渲染预览，彻底告别局促小黑框
 * 3. 底部拇指区：紧贴软键盘上方的单行灵动创作 Dock 栏（左滑矢量工具 + 右侧固定撤销/重做/字数）
 * 4. 抽屉式子面板：表情、链接、格式、引用/列表、代码块、折叠/剧透、投票/表格一键展开与收起
 * ============================================================================
 */
internal enum class WorkbenchViewMode(val label: String) {
    SOURCE("编辑"),
    PREVIEW("预览")
}

private enum class WorkbenchSubPanel(val title: String) {
    NONE(""),
    EMOJI("插入社区表情"),
    MENTION("提及社区用户 (@)"),
    LINK("插入帖子引用或链接"),
    FORMAT("文本排版与标题格式"),
    QUOTE_LIST("引用、列表与提示框"),
    CODE_LANG("插入语法高亮代码块"),
    DETAILS_SPOILER("折叠面板与剧透模糊"),
    POLL("投票、表格与扩展语法"),
    MORE_TOOLS("进阶语法工具"),
    HEADINGS("标题层级"),
    CALLOUT("提示框"),
    TABLE("构建表格")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LinDoMarkdownWorkbench(
    rawValue: String,
    onRawValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean = true,
    autofocus: Boolean = false,
    onUploadingChange: (Boolean) -> Unit = {},
    defaultEditorHeight: Dp = 260.dp,
    externalViewMode: WorkbenchViewMode? = null,
    restoreKeyboardFromPreview: Boolean = false,
    validationHint: String? = null,
    fillAvailableHeight: Boolean = false,
    modifier: Modifier = Modifier
) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    // TextFieldValue 维护真实光标位置与选区
    var tfv by remember {
        mutableStateOf(TextFieldValue(text = rawValue, selection = TextRange(rawValue.length)))
    }
    val emittedValues = remember { linkedSetOf<String>() }
    LaunchedEffect(rawValue) {
        if (!emittedValues.remove(rawValue) && rawValue != tfv.text) {
            tfv = TextFieldValue(rawValue, TextRange(rawValue.length))
        }
    }

    // Undo / Redo 历史栈
    val undoStack = remember { mutableStateListOf<TextFieldValue>() }
    val redoStack = remember { mutableStateListOf<TextFieldValue>() }

    fun updateTfv(newVal: TextFieldValue, recordHistory: Boolean = true) {
        if (!enabled) return
        if (recordHistory && newVal.text != tfv.text) {
            if (undoStack.size >= 40) undoStack.removeAt(0)
            undoStack.add(tfv)
            redoStack.clear()
        }
        tfv = newVal
        if (newVal.text != rawValue) {
            if (emittedValues.size >= 128) emittedValues.remove(emittedValues.first())
            emittedValues.add(newVal.text)
            onRawValueChange(newVal.text)
        }
    }

    // 选区包裹辅助函数：若有选区则包裹选区，否则插入并将光标放在中间
    fun wrapSelection(prefix: String, suffix: String) {
        val text = tfv.text
        val start = tfv.selection.min.coerceIn(0, text.length)
        val end = tfv.selection.max.coerceIn(0, text.length)
        val selected = text.substring(start, end)
        val inner = selected
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
        val lineStart = if (pos == 0) 0 else text.lastIndexOf('\n', pos - 1) + 1
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
    fun insertAtCursor(snippet: String, replaceMention: Boolean = false) {
        val text = tfv.text
        val cursor = tfv.selection.min.coerceIn(0, text.length)
        val mention = if (replaceMention) Regex("@[\\p{L}\\p{N}_-]*$").find(text.substring(0, cursor)) else null
        val start = mention?.range?.first ?: cursor
        val end = tfv.selection.max.coerceIn(0, text.length)
        val newText = text.replaceRange(start, end, snippet)
        val newCursor = (start + snippet.length).coerceIn(0, newText.length)
        updateTfv(TextFieldValue(text = newText, selection = TextRange(newCursor)))
    }

    // 盘古之白：中英文/数字混排自动补齐空格
    fun optimizePanguSpacing(): Boolean {
        val old = tfv.text
        if (old.isBlank()) return false
        val optimized = old
            .replace(Regex("([\\u4e00-\\u9fa5])([A-Za-z0-9])"), "$1 $2")
            .replace(Regex("([A-Za-z0-9])([\\u4e00-\\u9fa5])"), "$1 $2")
        if (optimized != old) {
            updateTfv(TextFieldValue(text = optimized, selection = TextRange(optimized.length)))
            return true
        }
        return false
    }

    var internalViewMode by remember { mutableStateOf(WorkbenchViewMode.SOURCE) }
    val activeViewMode = externalViewMode ?: internalViewMode
    var activeSubPanel by remember { mutableStateOf(WorkbenchSubPanel.NONE) }
    var showToolShelf by remember { mutableStateOf(false) }
    var restoreEditorKeyboard by remember { mutableStateOf(false) }
    val editorFocus = remember { FocusRequester() }
    var keyboardBeforePreview by remember { mutableStateOf(false) }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    fun restoreEditorFocus() {
        if (!restoreEditorKeyboard) return
        restoreEditorKeyboard = false
        editorFocus.requestFocus()
        keyboard?.show()
    }

    fun closeTools(keepShelf: Boolean = false) {
        if (!keepShelf) showToolShelf = false
        activeSubPanel = WorkbenchSubPanel.NONE
        restoreEditorFocus()
    }

    fun toggleSubPanel(target: WorkbenchSubPanel, hideKeyboardOnOpen: Boolean = false) {
        if (activeSubPanel == target) {
            closeTools(keepShelf = showToolShelf)
            return
        }
        if (hideKeyboardOnOpen) {
            restoreEditorKeyboard = restoreEditorKeyboard || imeVisible
            keyboard?.hide()
        } else {
            restoreEditorFocus()
        }
        activeSubPanel = target
    }

    LaunchedEffect(Unit) {
        if (autofocus && activeViewMode == WorkbenchViewMode.SOURCE) {
            delay(120)
            editorFocus.requestFocus()
        }
    }

    val toolsOpen = activeViewMode == WorkbenchViewMode.SOURCE &&
        (showToolShelf || activeSubPanel != WorkbenchSubPanel.NONE)
    val dismissTools by androidx.compose.runtime.rememberUpdatedState { closeTools() }
    if (toolsOpen) {
        DisposableEffect(Unit) {
            val remove = BackNavigationBridge.pushInterceptor { dismissTools() }
            onDispose { remove() }
        }
    }
    LaunchedEffect(activeViewMode) {
        if (activeViewMode == WorkbenchViewMode.PREVIEW) {
            showToolShelf = false
            activeSubPanel = WorkbenchSubPanel.NONE
            restoreEditorKeyboard = false
        } else if (keyboardBeforePreview || restoreKeyboardFromPreview) {
            keyboardBeforePreview = false
            editorFocus.requestFocus()
            keyboard?.show()
        }
    }

    // 媒体上传状态与最近上传的图片（支持一键调整 100% / 75% / 50% 缩放）
    var isUploadingMedia by remember { mutableStateOf(false) }
    var uploadStatusText by remember { mutableStateOf<String?>(null) }
    var lastUploadedImage by remember { mutableStateOf<UploadedMediaResult?>(null) }

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

    fun pickMedia(mimeFilter: String) {
        if (!enabled || isUploadingMedia) return
        val picker = PlatformMediaPicker.launchPicker
        if (picker == null) {
            uploadStatusText = "当前设备无法选取文件，可插入链接"
            return
        }
        picker(mimeFilter) { fileName, mimeType, bytes ->
            isUploadingMedia = true
            onUploadingChange(true)
            uploadStatusText = "正在上传 $fileName…"
            scope.launch {
                val result = try {
                    LinuxDoRepository.uploadMediaToLinuxDo(fileName, mimeType, bytes)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                } finally {
                    isUploadingMedia = false
                    onUploadingChange(false)
                }
                if (result == null) {
                    uploadStatusText = "上传失败，请重试"
                } else {
                    lastUploadedImage = result.takeIf { it.isImage }
                    uploadStatusText = "已插入 ${result.originalFilename}"
                    insertAtCursor("\n${result.markdownSnippet}\n")
                }
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = if (fillAvailableHeight) Arrangement.Top else Arrangement.spacedBy(6.dp)
    ) {
        // 3. 若刚刚上传了图片/附件，提供紧凑状态条与 Discourse 原生图片缩放比例快捷按钮 (100% / 75% / 50%)
        val latestImg = lastUploadedImage
        if (latestImg != null || uploadStatusText != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (fillAvailableHeight) 12.dp else 0.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(palette.subtleSurface)
                    .border(0.5.dp, palette.hairline, RoundedCornerShape(10.dp))
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
                    },
                    modifier = Modifier.weight(1f)
                )
                if (latestImg != null && latestImg.isImage && latestImg.width > 0 && latestImg.height > 0) {
                    listOf("100%", "75%", "50%").forEach { pct ->
                        WorkbenchToolChip(
                            label = pct,
                            onClick = {
                                val scaledTag = "![${latestImg.originalFilename}|${latestImg.width}x${latestImg.height}, $pct](${latestImg.shortUrl})"
                                val replaced = tfv.text.replace(latestImg.markdownSnippet, scaledTag)
                                updateTfv(tfv.copy(text = replaced))
                                lastUploadedImage = latestImg.copy(markdownSnippet = scaledTag)
                            }
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .clickable {
                            uploadStatusText = null
                            lastUploadedImage = null
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = LinDoIcons.Close,
                        contentDescription = "关闭提示",
                        modifier = Modifier.size(12.dp),
                        tint = palette.inkTertiary
                    )
                }
            }
        }

        // 4. 核心创作画布 / 实时 AST 预览区（顶部对齐，占据下方全部可用空间）
        val canvasModifier = if (fillAvailableHeight) {
            Modifier.fillMaxWidth().weight(1f)
        } else {
            Modifier.fillMaxWidth().height(defaultEditorHeight)
        }

        when (activeViewMode) {
            WorkbenchViewMode.SOURCE -> {
                WorkbenchSourceEditorBox(
                    tfv = tfv,
                    onValueChange = { newVal ->
                        updateTfv(newVal)
                        val cursor = newVal.selection.min.coerceIn(0, newVal.text.length)
                        val mention = Regex("(?:^|[\\s])@([\\p{L}\\p{N}_-]*)$").find(newVal.text.substring(0, cursor))
                        if (mention != null) {
                            activeSubPanel = WorkbenchSubPanel.MENTION
                            mentionSearchTerm = mention.groupValues[1]
                        }
                    },
                    placeholder = placeholder,
                    enabled = enabled,
                    immersiveCanvas = fillAvailableHeight,
                    focus = editorFocus,
                    modifier = canvasModifier
                )
            }
            WorkbenchViewMode.PREVIEW -> {
                WorkbenchAstLivePreviewBox(
                    rawMarkdown = tfv.text,
                    immersiveCanvas = fillAvailableHeight,
                    modifier = canvasModifier
                )
            }
        }

        val visibleHint = validationHint?.takeIf { tfv.text.isNotBlank() }
        if (activeViewMode == WorkbenchViewMode.SOURCE) {
            if (showToolShelf) {
                HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    WorkbenchDockIconButton(LinDoIcons.Undo, "撤销", enabled = enabled && undoStack.isNotEmpty()) {
                        val previous = undoStack.removeAt(undoStack.lastIndex)
                        redoStack.add(tfv)
                        updateTfv(previous, false)
                    }
                    WorkbenchDockIconButton(LinDoIcons.Redo, "重做", enabled = enabled && redoStack.isNotEmpty()) {
                        val next = redoStack.removeAt(redoStack.lastIndex)
                        undoStack.add(tfv)
                        updateTfv(next, false)
                    }
                    listOf(
                        "排版" to WorkbenchSubPanel.FORMAT,
                        "列表与引用" to WorkbenchSubPanel.QUOTE_LIST,
                        "代码块" to WorkbenchSubPanel.CODE_LANG,
                        "折叠与剧透" to WorkbenchSubPanel.DETAILS_SPOILER,
                        "投票与表格" to WorkbenchSubPanel.POLL
                    ).forEach { (label, panel) ->
                        TextButton(
                            enabled = enabled,
                            onClick = { toggleSubPanel(panel, hideKeyboardOnOpen = panel == WorkbenchSubPanel.POLL) },
                            contentPadding = PaddingValues(horizontal = 9.dp)
                        ) {
                            Text(
                                label, maxLines = 1,
                                color = if (activeSubPanel == panel) palette.activeChipText else palette.inkPrimary
                            )
                        }
                    }
                    TextButton(
                        enabled = enabled && !isUploadingMedia,
                        onClick = { pickMedia("*/*") },
                        contentPadding = PaddingValues(horizontal = 9.dp)
                    ) { Text("附件", color = palette.inkPrimary) }
                }
            }
            AnimatedVisibility(
                visible = activeSubPanel != WorkbenchSubPanel.NONE,
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
                exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically()
            ) {
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
                        closeTools()
                    },
                    onWrapSelection = { prefix, suffix ->
                        wrapSelection(prefix, suffix)
                        closeTools()
                    },
                    onInsertSnippet = { snippet ->
                        insertAtCursor(snippet, replaceMention = activeSubPanel == WorkbenchSubPanel.MENTION && snippet.startsWith("@"))
                        if (activeSubPanel != WorkbenchSubPanel.EMOJI) closeTools()
                    },
                    onOptimizePangu = {
                        val changed = optimizePanguSpacing()
                        closeTools()
                        uploadStatusText = if (changed) "已优化中英混排空格" else "当前已符合空格规范"
                    },
                    onDismiss = { closeTools(keepShelf = showToolShelf) },
                    modifier = Modifier.padding(
                        horizontal = if (fillAvailableHeight) 10.dp else 0.dp,
                        vertical = 4.dp
                    )
                )
            }
            HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    WorkbenchDockIconButton(LinDoIcons.Image, "图片", isUploadingMedia, enabled && !isUploadingMedia) {
                        pickMedia("image/*")
                    }
                    WorkbenchDockIconButton(LinDoIcons.Smile, "表情", activeSubPanel == WorkbenchSubPanel.EMOJI, enabled) {
                        showToolShelf = false
                        toggleSubPanel(WorkbenchSubPanel.EMOJI, hideKeyboardOnOpen = true)
                    }
                    WorkbenchDockIconButton(LinDoIcons.AtSign, "提及", activeSubPanel == WorkbenchSubPanel.MENTION, enabled) {
                        showToolShelf = false
                        toggleSubPanel(WorkbenchSubPanel.MENTION)
                    }
                    WorkbenchDockIconButton(LinDoIcons.Link, "链接", activeSubPanel == WorkbenchSubPanel.LINK, enabled) {
                        showToolShelf = false
                        toggleSubPanel(WorkbenchSubPanel.LINK, hideKeyboardOnOpen = true)
                    }
                    TextButton(
                        enabled = enabled,
                        onClick = {
                            if (showToolShelf) closeTools() else {
                                closeTools()
                                showToolShelf = true
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text(
                            "工具", maxLines = 1,
                            color = if (showToolShelf) palette.activeChipText else palette.inkSecondary
                        )
                    }
                }
                if (visibleHint != null) {
                    Text(
                        visibleHint, fontSize = 11.5.sp, color = palette.inkSecondary,
                        modifier = Modifier.widthIn(max = 105.dp).padding(end = 6.dp)
                    )
                }
                if (externalViewMode == null) {
                    WorkbenchDockIconButton(LinDoIcons.Eye, "预览", enabled = enabled) {
                        keyboardBeforePreview = imeVisible
                        internalViewMode = WorkbenchViewMode.PREVIEW
                        keyboard?.hide()
                    }
                }
            }
        } else if (externalViewMode == null) {
            TextButton(onClick = { internalViewMode = WorkbenchViewMode.SOURCE }) { Text("继续编辑") }
        }
    }
}

@Composable
private fun WorkbenchDockIconButton(
    icon: ImageVector,
    label: String,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val palette = LocalPalette.current
    val fg = when {
        !enabled -> palette.inkTertiary.copy(alpha = 0.4f)
        active -> palette.brandGold
        else -> palette.inkSecondary
    }
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(17.dp),
            tint = fg
        )
    }
}

@Composable
private fun WorkbenchSourceEditorBox(
    tfv: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    enabled: Boolean = true,
    immersiveCanvas: Boolean = false,
    focus: FocusRequester,
    modifier: Modifier = Modifier
) {
    val palette = LocalPalette.current
    val editorTextStyle = TextStyle(
        fontSize = 15.8.sp,
        lineHeight = 24.sp,
        color = palette.inkPrimary
    )
    BasicTextField(
        value = tfv,
        enabled = enabled,
        onValueChange = onValueChange,
        textStyle = editorTextStyle,
        cursorBrush = SolidColor(palette.brandGold),
        decorationBox = { innerTextField ->
            val boxMod = if (immersiveCanvas) {
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            } else {
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.subtleSurface.copy(alpha = if (palette.isDark) 0.42f else 0.6f))
                    .border(0.5.dp, palette.hairline, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            }
            LinDoInputDecoration(
                tfv.text.isEmpty(), placeholder, editorTextStyle, palette.inkSecondary,
                modifier = boxMod, alignment = Alignment.TopStart, placeholderLines = 2,
                innerTextField = innerTextField
            )
        },
        modifier = modifier.focusRequester(focus)
    )
}

/**
 * 编辑器内置 Markdown + Discourse BBCode (`[poll]`, `[details]`, `[spoiler]`, `[grid]`, `> [!NOTE]`) 实时 AST 预览器
 */
@Composable
private fun WorkbenchAstLivePreviewBox(
    rawMarkdown: String,
    immersiveCanvas: Boolean = false,
    modifier: Modifier = Modifier
) {
    val palette = LocalPalette.current
    val astNodes by androidx.compose.runtime.produceState<List<PostBlockNode>?>(null, rawMarkdown) {
        value = null
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val previewHtml = compileMarkdownAndBbcodeToPreviewHtml(rawMarkdown)
            DiscourseAstParser.parseOrGetCached(-999999, previewHtml)
        }
    }

    val containerMod = if (immersiveCanvas) {
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp)
    } else {
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(palette.subtleSurface.copy(alpha = 0.5f))
            .border(0.5.dp, palette.hairline, RoundedCornerShape(12.dp))
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
    }

    Column(
        modifier = containerMod,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (rawMarkdown.isBlank()) {
            LinDoEmptyState("暂无预览内容")
        } else {
            val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
            val nodes = astNodes
            if (nodes == null) LinDoFeedbackRow("正在生成预览", LinDoMascotState.Loading)
            else PostRichContent(nodes, palette, onOpenLink = { uriHandler.openUri(it) })
        }
    }
}

/**
 * 将本地编辑中的 Markdown + Discourse BBCode 源码实时编译为可由 `DiscourseAstParser` 解析的 HTML
 */
private fun compileMarkdownAndBbcodeToPreviewHtml(raw: String): String = do_.linux.lindo.core.render.MarkdownPreview.render(raw)

@OptIn(ExperimentalLayoutApi::class)
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
    onWrapSelection: (String, String) -> Unit,
    onInsertSnippet: (String) -> Unit,
    onOptimizePangu: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalPalette.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.cardSurface)
            .border(0.6.dp, palette.hairline, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 统一抽屉标题栏 + 纯图标关闭按钮
        if (subPanel != WorkbenchSubPanel.NONE) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = subPanel.title,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.inkSecondary,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(palette.subtleSurface)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = LinDoIcons.Close,
                        contentDescription = "收起面板",
                        modifier = Modifier.size(12.dp),
                        tint = palette.inkTertiary
                    )
                }
            }
        }

        when (subPanel) {
            WorkbenchSubPanel.FORMAT, WorkbenchSubPanel.HEADINGS -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    WorkbenchToolChip(
                        label = "B",
                        onClick = { onWrapSelection("**", "**") }
                    )
                    WorkbenchToolChip(
                        label = "I",
                        onClick = { onWrapSelection("*", "*") }
                    )
                    WorkbenchToolChip(
                        label = "S",
                        onClick = { onWrapSelection("~~", "~~") }
                    )
                    WorkbenchToolChip(
                        label = "<>",
                        onClick = { onWrapSelection("`", "`") }
                    )
                    WorkbenchToolChip(
                        label = "<kbd>",
                        onClick = { onWrapSelection("<kbd>", "</kbd>") }
                    )
                    listOf(
                        "H1" to "# ",
                        "H2" to "## ",
                        "H3" to "### ",
                        "H4" to "#### "
                    ).forEach { (label, prefix) ->
                        WorkbenchToolChip(
                            label = label,
                            onClick = { onToggleLinePrefix(prefix) }
                        )
                    }
                    WorkbenchToolChip(
                        label = "―",
                        onClick = { onInsertSnippet("\n\n---\n\n") }
                    )
                    WorkbenchToolChip(
                        label = "中英空格",
                        highlighted = true,
                        onClick = onOptimizePangu
                    )
                }
            }

            WorkbenchSubPanel.QUOTE_LIST, WorkbenchSubPanel.CALLOUT -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    WorkbenchToolChip(
                        label = "❝ 引用",
                        onClick = { onToggleLinePrefix("> ") }
                    )
                    WorkbenchToolChip(
                        label = "• 列表",
                        onClick = { onToggleLinePrefix("- ") }
                    )
                    WorkbenchToolChip(
                        label = "1. 编号",
                        onClick = { onToggleLinePrefix("1. ") }
                    )
                    WorkbenchToolChip(
                        label = "☑ 待办",
                        onClick = { onToggleLinePrefix("- [ ] ") }
                    )
                    listOf(
                        "NOTE" to "NOTE",
                        "TIP" to "TIP",
                        "IMPORTANT" to "IMPORTANT",
                        "WARNING" to "WARNING",
                        "CAUTION" to "CAUTION"
                    ).forEach { (label, kind) ->
                        WorkbenchToolChip(
                            label = label,
                            onClick = {
                                onWrapSelection("\n> [!$kind]\n> ", "\n")
                            }
                        )
                    }
                }
            }

            WorkbenchSubPanel.CODE_LANG -> {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    WorkbenchToolChip("通用代码块", onClick = { onWrapSelection("\n```\n", "\n```\n") })
                    listOf("kotlin", "python", "bash", "javascript", "typescript", "json", "yaml",
                        "rust", "go", "sql", "html", "dockerfile", "mermaid").forEach { language ->
                        WorkbenchToolChip(language, onClick = { onWrapSelection("\n```$language\n", "\n```\n") })
                    }
                }
            }

            WorkbenchSubPanel.LINK -> {
                var linkTab by remember { mutableIntStateOf(0) } // 0 = 搜索引用帖子, 1 = 自定义链接
                var topicSearchQuery by remember { mutableStateOf("") }
                var topicCandidates by remember { mutableStateOf<List<CommunitySearchResult>>(emptyList()) }
                var isSearchingTopics by remember { mutableStateOf(false) }
                var linkTitle by remember { mutableStateOf("") }
                var linkUrl by remember { mutableStateOf("") }

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
                                .heightIn(max = 148.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            topicCandidates.forEach { item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(palette.subtleSurface.copy(alpha = 0.55f))
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
                    var linkError by remember { mutableStateOf(false) }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        WorkbenchCompactInputBox(linkUrl, { linkUrl = it; linkError = false }, "https:// 链接地址")
                        WorkbenchCompactInputBox(linkTitle, { linkTitle = it }, "显示文字（可选）")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (linkError) Text(
                                "请输入有效的网页地址", color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f), fontSize = 12.sp
                            ) else Spacer(Modifier.weight(1f))
                            TextButton(onClick = {
                                val address = linkUrl.trim()
                                val uri = runCatching { java.net.URI(address) }.getOrNull()
                                val valid = uri != null && !uri.host.isNullOrBlank() &&
                                    (uri.scheme.equals("https", true) || uri.scheme.equals("http", true))
                                if (!valid) {
                                    linkError = true
                                } else {
                                    val title = linkTitle.trim().ifBlank { address }
                                        .replace("[", "\\[").replace("]", "\\]")
                                    onInsertSnippet("[$title](<$address>) ")
                                }
                            }) { Text("插入链接") }
                        }
                    }
                }
            }

            WorkbenchSubPanel.DETAILS_SPOILER -> {
                var summaryTitle by remember { mutableStateOf("") }
                WorkbenchCompactInputBox(summaryTitle, { summaryTitle = it }, "折叠块标题（可选）")
                Row {
                    TextButton(onClick = {
                        val title = summaryTitle.trim().ifBlank { "详情" }.replace('"', '”')
                        onWrapSelection("\n[details=\"$title\"]\n", "\n[/details]\n")
                    }) { Text("插入折叠块") }
                    TextButton(onClick = { onWrapSelection("[spoiler]", "[/spoiler]") }) { Text("添加剧透") }
                }
            }

            WorkbenchSubPanel.POLL, WorkbenchSubPanel.TABLE, WorkbenchSubPanel.MORE_TOOLS -> {
                var advTab by remember(subPanel) {
                    mutableIntStateOf(
                        when (subPanel) {
                            WorkbenchSubPanel.TABLE -> 1
                            WorkbenchSubPanel.MORE_TOOLS -> 2
                            else -> 0
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    WorkbenchToolChip(
                        label = "📊 发起投票",
                        active = advTab == 0,
                        onClick = { advTab = 0 }
                    )
                    WorkbenchToolChip(
                        label = "📅 插入表格",
                        active = advTab == 1,
                        onClick = { advTab = 1 }
                    )
                    WorkbenchToolChip(
                        label = "🧩 进阶语法",
                        active = advTab == 2,
                        onClick = { advTab = 2 }
                    )
                }

                when (advTab) {
                    0 -> {
                        var pollTitle by remember { mutableStateOf("") }
                        var pollType by remember { mutableStateOf("regular") }
                        var pollResults by remember { mutableStateOf("always") }
                        var pollPublic by remember { mutableStateOf(true) }
                        var pollOptionsText by remember { mutableStateOf("选项一\n选项二\n选项三") }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf("regular" to "单选", "multiple" to "多选", "number" to "评分").forEach { (k, lbl) ->
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
                        WorkbenchCompactInputBox(
                            value = pollTitle,
                            onValueChange = { pollTitle = it },
                            placeholder = "投票标题（可选，如：你最常用的开发环境是？）",
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = pollOptionsText,
                            onValueChange = { pollOptionsText = it },
                            placeholder = { Text("每行一个投票选项") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(72.dp),
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

                    1 -> {
                        var cols by remember { mutableStateOf(3) }
                        var rows by remember { mutableStateOf(3) }
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

                    2 -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            WorkbenchToolChip(
                                label = "📑 自动目录 [toc]",
                                onClick = { onInsertSnippet("\n[toc]\n") }
                            )
                            WorkbenchToolChip(
                                label = "∑ 数学公式 $$",
                                onClick = { onWrapSelection("\n$$\n", "\n$$\n") }
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
                        }
                    }
                }
            }

            WorkbenchSubPanel.EMOJI -> {
                var emojiQuery by remember { mutableStateOf("") }
                var selectedGroupIdx by remember { mutableIntStateOf(0) }
                var syncTrigger by remember { mutableIntStateOf(0) }

                LaunchedEffect(Unit) {
                    if (LinuxDoRepository.syncSiteEmojis()) {
                        syncTrigger++
                    }
                }

                val groups = remember(syncTrigger) { LinuxDoEmojiCatalog.getPickerGroups() }
                val activeItems = remember(emojiQuery, selectedGroupIdx, groups) {
                    if (emojiQuery.isNotBlank()) {
                        LinuxDoEmojiCatalog.searchEmojis(emojiQuery)
                    } else {
                        groups.getOrNull(selectedGroupIdx.coerceIn(0, (groups.size - 1).coerceAtLeast(0)))?.items.orEmpty()
                    }
                }

                WorkbenchCompactInputBox(
                    value = emojiQuery,
                    onValueChange = { emojiQuery = it },
                    placeholder = "搜索官方表情或热梗（如：滑稽、融化、哭、火箭...）",
                    leadingIcon = LinDoIcons.Search,
                    modifier = Modifier.fillMaxWidth()
                )

                if (emojiQuery.isBlank() && groups.isNotEmpty()) {
                    LinDoSectionTabs(groups.map { it.labelZh }, selectedGroupIdx.coerceIn(groups.indices), fontSize = 12.sp) {
                        selectedGroupIdx = it
                    }
                }

                if (activeItems.isEmpty()) {
                    Text(
                        text = "未找到匹配的表情",
                        fontSize = 11.5.sp,
                        color = palette.inkTertiary,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                } else {
                    val rows = remember(activeItems) { activeItems.chunked(7) }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 188.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        rows.forEach { rowItems ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                rowItems.forEach { item ->
                                    val isCustomImage = !item.customUrl.isNullOrBlank() || item.unicode == null
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(36.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(palette.subtleSurface.copy(alpha = 0.45f))
                                            .clickable {
                                                val snippet = if (isCustomImage) {
                                                    ":${item.shortcode}: "
                                                } else {
                                                    "${item.unicode} "
                                                }
                                                onInsertSnippet(snippet)
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isCustomImage) {
                                            var imgFailed by remember(item.resolvedImageUrl) { mutableStateOf(false) }
                                            if (imgFailed) {
                                                Text(
                                                    text = item.unicode ?: item.shortcode.take(3),
                                                    fontSize = 11.sp,
                                                    color = palette.inkSecondary,
                                                    maxLines = 1
                                                )
                                            } else {
                                                AsyncImage(
                                                    model = item.resolvedImageUrl,
                                                    contentDescription = item.shortcode,
                                                    contentScale = ContentScale.Fit,
                                                    onError = { imgFailed = true },
                                                    modifier = Modifier.size(23.dp)
                                                )
                                            }
                                        } else {
                                            Text(
                                                text = item.unicode.orEmpty(),
                                                fontSize = 19.sp
                                            )
                                        }
                                    }
                                }
                                repeat((7 - rowItems.size).coerceAtLeast(0)) {
                                    Spacer(modifier = Modifier.weight(1f).height(36.dp))
                                }
                            }
                        }
                    }
                }
            }

            WorkbenchSubPanel.MENTION -> {
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
                                label = "@${cand.handle}${if (cand.displayName.isNotBlank() && cand.displayName != cand.handle) " (${cand.displayName})" else ""}",
                                onClick = { onInsertSnippet("@${cand.handle} ") }
                            )
                        }
                    }
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
    modifier: Modifier = Modifier,
    onSearch: (() -> Unit)? = null
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
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(palette.subtleSurface.copy(alpha = 0.7f))
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
            keyboardOptions = if (onSearch == null) KeyboardOptions.Default else KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = if (onSearch == null) KeyboardActions.Default else KeyboardActions(onSearch = { onSearch() }),
            textStyle = inputStyle,
            cursorBrush = SolidColor(palette.brandGold),
            modifier = Modifier
                .weight(1f),
            decorationBox = { inner ->
                LinDoInputDecoration(
                    value.isEmpty(), placeholder, inputStyle, palette.inkSecondary,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    innerTextField = inner
                )
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
    val isEmphasized = active || highlighted
    val bg = when {
        highlighted -> palette.brandGold.copy(alpha = if (palette.isDark) 0.22f else 0.16f)
        active -> palette.activeChipBg
        else -> palette.subtleSurface
    }
    val borderCol = when {
        isEmphasized -> palette.brandGold.copy(alpha = 0.55f)
        else -> palette.hairline
    }
    val textCol = when {
        isEmphasized -> palette.brandGold
        else -> palette.inkSecondary
    }
    Box(
        modifier = Modifier
            .height(29.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(0.5.dp, borderCol, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = textCol,
            fontSize = 11.8.sp,
            fontWeight = if (isEmphasized) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1
        )
    }
}

/**
 * 官方功能 3：全站实时搜索抽屉（联动 `/search/query.json` + `/search.json` + 自动防抖与热门搜索词）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GlobalSearchModalSheet(
    initialQuery: String,
    onDismiss: () -> Unit,
    onSelectResult: (Int, String, Int?) -> Unit,
    initialResults: List<CommunitySearchResult> = emptyList()
) {
    val palette = LocalPalette.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    var query by remember { mutableStateOf(initialQuery) }
    var order by remember { mutableStateOf<String?>(null) } // null=相关性, "latest"=最新, "likes"=最多赞
    var results by remember { mutableStateOf(initialResults) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reloadToken by remember { mutableIntStateOf(0) }
    var isSearching by remember { mutableStateOf(false) }
    var currentPage by remember { mutableIntStateOf(1) }
    var hasMoreResults by remember { mutableStateOf(false) }
    var isLoadingMore by remember { mutableStateOf(false) }
    val searchScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var submittedQuery by remember { mutableStateOf(initialQuery.trim()) }
    fun performSearch(targetQuery: String = query) {
        keyboardController?.hide()
        submittedQuery = targetQuery.trim()
        reloadToken++
    }

    fun loadMoreSearchResults() {
        val q = submittedQuery.trim()
        if (q.isBlank() || isSearching || isLoadingMore || !hasMoreResults) return
        val nextPage = currentPage + 1
        isLoadingMore = true
        searchScope.launch {
            try {
                val nextItems = LinuxDoRepository.searchCommunity(q, order, page = nextPage)
                if (nextItems.isEmpty()) {
                    hasMoreResults = false
                } else {
                    val existingKeys = results.asSequence().map { "${it.topicId}_${it.postNumber}" }.toHashSet()
                    val appended = nextItems.filter { "${it.topicId}_${it.postNumber}" !in existingKeys }
                    if (appended.isEmpty()) {
                        hasMoreResults = false
                    } else {
                        results = results + appended
                        currentPage = nextPage
                        hasMoreResults = nextItems.size >= 20
                    }
                }
            } catch (_: Exception) {
                hasMoreResults = false
            } finally {
                isLoadingMore = false
            }
        }
    }

    // 输入防抖自动搜索：用户输入停止 420ms 后自动触发全站检索
    LaunchedEffect(query) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            submittedQuery = ""
            results = emptyList()
            loadError = null
            isSearching = false
            currentPage = 1
            hasMoreResults = false
            isLoadingMore = false
            return@LaunchedEffect
        }
        if (trimmed != submittedQuery) {
            delay(420L)
            submittedQuery = trimmed
        }
    }

    LaunchedEffect(submittedQuery, order, reloadToken) {
        if (submittedQuery.isBlank()) return@LaunchedEffect
        if (initialResults.isNotEmpty() && reloadToken == 0 && order == null && submittedQuery == initialQuery.trim()) {
            return@LaunchedEffect
        }
        isSearching = true
        isLoadingMore = false
        currentPage = 1
        hasMoreResults = false
        loadError = null
        results = emptyList()
        try {
            val firstPage = LinuxDoRepository.searchCommunity(submittedQuery, order, page = 1)
            results = firstPage
            hasMoreResults = firstPage.size >= 20
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            loadError = error.message ?: "搜索失败，请重试"
        } finally {
            isSearching = false
        }
    }

    val shouldTriggerLoadMore by remember {
        derivedStateOf {
            val total = listState.layoutInfo.totalItemsCount
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            total > 0 && lastVisible >= total - 4
        }
    }

    LaunchedEffect(shouldTriggerLoadMore, hasMoreResults, isSearching, isLoadingMore) {
        if (shouldTriggerLoadMore && hasMoreResults && !isSearching && !isLoadingMore) {
            loadMoreSearchResults()
        }
    }

    LaunchedEffect(Unit) {
        if (initialQuery.isBlank()) {
            delay(120L)
            runCatching { focusRequester.requestFocus() }
        }
    }

    LinDoFullScreenPage(
        onBack = onDismiss,
        containerColor = palette.canvas
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.cardSurface)
        ) {
            LinDoPageTopBar(
                title = "全站搜索",
                onBack = onDismiss
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val searchInputStyle = TextStyle(
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = palette.inkPrimary,
                        fontWeight = FontWeight.Medium
                    )
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 42.dp)
                            .clip(CircleShape)
                            .background(palette.subtleSurface)
                            .border(0.6.dp, palette.hairline, CircleShape)
                            .padding(start = 15.dp, end = if (query.isEmpty()) 15.dp else 4.dp),
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
                                .padding(vertical = 9.dp)
                                .focusRequester(focusRequester),
                            decorationBox = { inner ->
                                LinDoInputDecoration(
                                    query.isEmpty(), "搜索话题、标签或用户", searchInputStyle, palette.inkSecondary,
                                    modifier = Modifier.fillMaxWidth(),
                                    innerTextField = inner
                                )
                            }
                        )
                        if (query.isNotEmpty()) {
                            IconButton(onClick = {
                                query = ""
                                submittedQuery = ""
                                results = emptyList()
                                loadError = null
                                currentPage = 1
                                hasMoreResults = false
                                isLoadingMore = false
                                focusRequester.requestFocus()
                            }) {
                                Icon(
                                    imageVector = LinDoIcons.Close,
                                    contentDescription = "清空搜索",
                                    modifier = Modifier.size(17.dp),
                                    tint = palette.inkSecondary
                                )
                            }
                        }
                    }

                    TextButton(
                        enabled = query.isNotBlank(),
                        onClick = { performSearch() },
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text(
                            text = "搜索",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 排序快筛胶囊
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
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
                                if (order != ord) {
                                    order = ord
                                    if (query.isNotBlank()) performSearch()
                                }
                            }
                        )
                    }
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(palette.cardSurface),
            contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp)
        ) {
            if (query.isBlank() && results.isEmpty() && !isSearching) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = "热门搜索关键词",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.inkSecondary
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        val hotKeywords = listOf(
                            "Claude", "DeepSeek", "MCP", "人工智能",
                            "Cursor", "开源", "Docker", "快问快答"
                        )
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            hotKeywords.forEach { kw ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(palette.subtleSurface)
                                        .clickable {
                                            query = kw
                                            performSearch(kw)
                                        }
                                        .padding(horizontal = 11.dp, vertical = 5.5.dp)
                                ) {
                                    Text(
                                        text = kw,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = palette.inkPrimary,
                                        maxLines = 1,
                                        softWrap = false
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
            } else if (isSearching && results.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        LinDoLoadingIndicator(label = "搜索中")
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
                            detail = loadError!!,
                            onRetry = { performSearch() }
                        )
                    }
                }
            } else if (results.isEmpty() && !isSearching) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        LinDoEmptyState("未找到结果，试试其他关键词")
                    }
                }
            } else {
                itemsIndexed(results, key = { idx, item -> "${item.topicId}_${item.postNumber}_$idx" }, contentType = { _, _ -> "search-result" }) { _, item ->
                    val catDotColor = remember(item.categoryColorHex) {
                        LinuxDoSiteRules.parseHexColor(item.categoryColorHex)
                    }
                    val (_, catFg) = remember(item.categoryName, palette.isDark) {
                        resolveMorandiCategoryColors(item.categoryName, palette.isDark)
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(palette.cardSurface)
                            .clickable { onSelectResult(item.topicId, item.title, item.postNumber) }
                            .padding(horizontal = 16.dp, vertical = 13.dp)
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
                            Spacer(modifier = Modifier.width(7.dp))
                            Text(
                                text = item.username,
                                fontSize = 12.2.sp,
                                fontWeight = FontWeight.Medium,
                                color = palette.inkSecondary,
                                maxLines = 1
                            )
                            val resultMeta = listOfNotNull(
                                item.postNumber.takeIf { it > 1 }?.let { "${it} 楼" },
                                item.createdAtText.takeIf { it.isNotBlank() }
                            )
                            if (resultMeta.isNotEmpty()) Text(
                                text = resultMeta.joinToString(" · ", prefix = " · "),
                                fontSize = 11.2.sp,
                                color = palette.inkTertiary,
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                            ) else Spacer(Modifier.weight(1f))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.5.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(5.5.dp)
                                        .clip(CircleShape)
                                        .background(catDotColor)
                                )
                                Text(
                                    text = item.categoryName,
                                    fontSize = 11.2.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = catFg,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(7.dp))
                        Text(
                            text = item.title,
                            fontSize = 15.2.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.inkPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (item.blurb.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = item.blurb,
                                fontSize = 12.6.sp,
                                color = palette.inkSecondary,
                                lineHeight = 18.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (item.likeCount > 0 || item.postsCount > 1) {
                            Spacer(modifier = Modifier.height(7.dp))
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
                                            fontSize = 11.2.sp,
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
                                        fontSize = 11.2.sp,
                                        color = palette.inkTertiary
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

                if (results.isNotEmpty()) {
                    item(key = "search_results_end_footer") {
                        LinearPulseLoadingFooter(
                            isLoadingMore = isLoadingMore,
                            hasMore = hasMoreResults,
                            loadedCount = results.size,
                            doneText = "没有更多了 · 共找到 ${results.size} 条相关结果",
                            onLoadMore = ::loadMoreSearchResults
                        )
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
    var showMarkAllConfirm by remember { mutableStateOf(false) }
    var markingAll by remember { mutableStateOf(false) }
    var markAllError by remember { mutableStateOf<String?>(null) }
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
            loadError = error.message ?: "加载失败"
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

    LinDoFullScreenPage(
        onBack = onDismiss,
        containerColor = palette.cardSurface
    ) {
        LinDoPageTopBar(
            title = "通知",
            onBack = onDismiss,
            trailingContent = {
                LinDoNotificationActions(
                    canMarkAll = notifications.isNotEmpty(),
                    enabled = !isLoading && !markingAll,
                    onMarkAll = { showMarkAllConfirm = true },
                    onRefresh = { reloadToken++ }
                )
            }
        )
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp).selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val tabs = listOf("全部", "未读", "回复与提及", "表态")
                tabs.forEachIndexed { idx, label ->
                    GoogleM3FilterPill(
                        label = label,
                        dotColor = null,
                        selected = filterTab == idx,
                        modifier = Modifier.widthIn(min = 48.dp),
                        onClick = { filterTab = idx }
                    )
                }
            }

            HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)

            if (isLoading && notifications.isEmpty()) {
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
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    if (filtered.isEmpty() && loadError == null) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                LinDoEmptyState(message = if (filterTab == 1) "暂无未读通知" else "暂无通知")
                            }
                        }
                    } else {
                        items(filtered, key = { it.id }, contentType = { "notification" }) { item ->
                            val actor = item.actorUsername
                            LinDoNotificationRow(
                                item = item,
                                onClick = when {
                                    item.topicId > 0 -> {
                                        { onSelectNotification(item.topicId, item.title, item.postNumber) }
                                    }
                                    actor != null -> { { onInspectUser(actor) } }
                                    else -> null
                                }
                            )
                        }
                    }
                }
            }
        }
    }
    if (showMarkAllConfirm) LinDoConfirmDialog(title = "要将通知全部标为已读吗？", confirmLabel = "标为已读", busy = markingAll, error = markAllError,
        onDismiss = { showMarkAllConfirm = false; markAllError = null }, onConfirm = {
            if (!markingAll) {
                markingAll = true
                markAllError = null
                scope.launch {
                    try {
                        val ok = LinuxDoRepository.markNotificationsRead()
                        if (ok) {
                            showMarkAllConfirm = false
                            notifications = notifications.map { it.copy(read = true) }
                        } else {
                            markAllError = "标记失败"
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (error: Exception) { markAllError = error.message ?: "标记失败" }
                    finally { markingAll = false }
                }
            }
        })

}

/**
 * 官方功能 5：我的动态抽屉 (`/user_actions.json`：支持全部 / 主帖 / 回复 / 点赞筛选，与全站列表及用户名片动态流视觉完全统一)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserActivitiesSheet(
    username: String,
    onDismiss: () -> Unit,
    onSelectActivity: (Int, String, Int?) -> Unit
) {
    val palette = LocalPalette.current
    val filters = remember {
        listOf("全部动态" to null, "发布主帖" to "4", "我的回复" to "5", "送出的赞" to "1")
    }
    var selectedFilterIdx by remember { mutableIntStateOf(0) }
    val filterCode = filters.getOrNull(selectedFilterIdx)?.second
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
            loadError = error.message ?: "加载失败"
        } finally {
            isLoading = false
        }
    }

    LinDoFullScreenPage(
        onBack = onDismiss,
        containerColor = palette.cardSurface
    ) {
        LinDoPageTopBar(
            title = "我的活动与社区互动",
            onBack = onDismiss,
            trailingContent = {
                IconButton(onClick = { reloadToken++ }) {
                    Icon(LinDoIcons.Refresh, "刷新", Modifier.size(19.dp), tint = palette.inkSecondary)
                }
            }
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.cardSurface)
                .padding(horizontal = 16.dp)
        ) {
            LinDoSectionTabs(
                labels = filters.map { it.first },
                selectedIndex = selectedFilterIdx,
                fontSize = 14.sp
            ) { selectedFilterIdx = it }
        }
        HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)

        if (isLoading) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                LinDoLoadingIndicator(label = "加载中")
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
            ) {
                if (loadError != null) {
                    item(key = "error") {
                        LinDoLoadFailure(loadError!!, onRetry = { reloadToken++ })
                    }
                }
                if (items.isEmpty() && loadError == null) {
                    item(key = "empty") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            LinDoEmptyState(message = "暂无相关社区动态")
                        }
                    }
                } else {
                    itemsIndexed(items, key = { idx, item -> "${item.id}_${item.actionType}_$idx" }, contentType = { _, _ -> "activity" }) { _, item ->
                        ProfileActivityRow(item) {
                            onSelectActivity(item.topicId, item.title, item.postNumber)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 官方功能 6：我的书签收藏独立页面 (`/u/{username}/bookmarks.json`，统一无框分割线列表风格)
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
            loadError = error.message ?: "加载失败"
        } finally {
            isLoading = false
        }
    }

    LinDoFullScreenPage(
        onBack = onDismiss,
        containerColor = palette.cardSurface
    ) {
        LinDoPageTopBar(
            title = "我的书签与收藏",
            onBack = onDismiss,
            trailingContent = {
                IconButton(onClick = { reloadToken++ }) {
                    Icon(LinDoIcons.Refresh, "刷新", Modifier.size(19.dp), tint = palette.inkSecondary)
                }
            }
        )
        HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)

        if (isLoading) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                LinDoLoadingIndicator(label = "加载中")
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
            ) {
                if (loadError != null) {
                    item(key = "error") {
                        LinDoLoadFailure(loadError!!, onRetry = { reloadToken++ })
                    }
                }
                if (items.isEmpty() && loadError == null) {
                    item(key = "empty") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            LinDoEmptyState(message = "暂无收藏的书签")
                        }
                    }
                } else {
                    itemsIndexed(items, key = { idx, item -> "${item.id}_$idx" }, contentType = { _, _ -> "activity" }) { _, item ->
                        ProfileActivityRow(item) {
                            onSelectBookmark(item.topicId, item.title, item.postNumber)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 官方功能 7：站内私信箱与发送私信独立页面 (`/topics/private-messages/{username}.json` + `archetype=private_message`)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UserPrivateMessagesSheet(
    username: String,
    initialRecipient: String?,
    onDismiss: () -> Unit,
    onSelectMessageTopic: (TopicSummary) -> Unit
) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    var pendingArchive by remember { mutableStateOf<TopicSummary?>(null) }
    var archiving by remember { mutableStateOf(false) }
    var archiveError by remember { mutableStateOf<String?>(null) }
    var tab by remember(initialRecipient) { mutableIntStateOf(if (!initialRecipient.isNullOrBlank()) 2 else 0) }
    var messages by remember { mutableStateOf<List<TopicSummary>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reloadToken by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(false) }

    var recipient by remember(initialRecipient) { mutableStateOf(initialRecipient.orEmpty()) }
    var pmTitle by remember { mutableStateOf("") }
    var pmBody by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    var isUploading by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(username, tab, reloadToken) {
        if (tab == 2) return@LaunchedEffect
        isLoading = true
        loadError = null
        messages = emptyList()
        try {
            val effectiveUsername = username.ifBlank {
                LinuxDoRepository.fetchCurrentUserProfile()?.username.orEmpty()
            }
            messages = LinuxDoRepository.fetchPrivateMessages(effectiveUsername, sent = tab == 1, archived = tab == 3)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            loadError = error.message ?: "加载失败"
        } finally {
            isLoading = false
        }
    }

    LinDoFullScreenPage(
        onBack = onDismiss,
        containerColor = palette.cardSurface
    ) {
        LinDoPageTopBar(
            title = "站内私信中心",
            onBack = onDismiss
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 10.dp)
        ) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val tabs = listOf("收件箱" to 0, "已发送" to 1, "归档" to 3, "写私信" to 2)
                tabs.forEach { (label, idx) ->
                    GoogleM3FilterPill(
                        label = label,
                        dotColor = null,
                        selected = tab == idx,
                        onClick = { if (!isSending && !isUploading) tab = idx }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (tab == 2) {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                ComposerTextInput(recipient, { recipient = it }, "接收人用户名，多人用逗号分隔", enabled = !isSending)
                ComposerTextInput(pmTitle, { pmTitle = it }, "输入私信主题", enabled = !isSending, title = true)
                LinDoMarkdownWorkbench(
                    pmBody, { pmBody = it }, "写下私信内容", enabled = !isSending,
                    onUploadingChange = { isUploading = it }, defaultEditorHeight = 180.dp
                )
                if (statusMsg != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    LinDoFeedbackRow(message = statusMsg!!, state = if (statusMsg!!.contains("失败")) LinDoMascotState.NetworkError else LinDoMascotState.Success)
                }
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Transparent)
                        .clickable(enabled = !isSending && !isUploading && recipient.isNotBlank() && pmTitle.isNotBlank() && pmBody.isNotBlank()) {
                            if (!isSending) {
                                isSending = true
                                scope.launch {
                                    try {
                                        val tid = LinuxDoRepository.sendPrivateMessage(recipient, pmTitle, pmBody)
                                        if (tid != null) { statusMsg = "私信已发送"; pmTitle = ""; pmBody = ""; tab = 1 }
                                        else statusMsg = "发送失败，内容已保留"
                                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                    catch (error: Exception) { statusMsg = error.message ?: "发送失败，内容已保留" }
                                    finally { isSending = false }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    LinDoBusyButtonLabel(busy = isSending, busyLabel = "正在发送", label = "发送私信", color = palette.brandGold)
                }

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
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        if (messages.isEmpty() && loadError == null) {
                            item {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    LinDoEmptyState(message = if (tab == 3) "暂无归档会话" else "暂无私信")
                                }
                            }
                        } else {
                            itemsIndexed(messages, key = { _, m -> m.id }, contentType = { _, _ -> "topic" }) { _, msg ->
                                CommunityTopicItem(
                                    topic = msg,
                                    isSelected = false,
                                    isCompact = true,
                                    enableAnimations = false,
                                    onClick = { onSelectMessageTopic(msg) }
                                )
                                if (tab == 0 || tab == 3) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    TextButton(onClick = { pendingArchive = msg; archiveError = null }) {
                                        Text(if (tab == 3) "移回收件箱" else "归档会话", color = palette.inkSecondary, fontSize = 12.sp)
                                    }
                                }
                                HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
                            }
                        }
                    }
                }
            }
        }
    }
    pendingArchive?.let { message ->
        val movingToInbox = tab == 3
        LinDoConfirmDialog(
            title = if (movingToInbox) "要把会话移回收件箱吗？" else "要归档这段会话吗？",
            confirmLabel = if (movingToInbox) "移回" else "归档",
            busy = archiving, error = archiveError,
            onDismiss = { pendingArchive = null },
            onConfirm = {
                if (!archiving) {
                    archiving = true
                    archiveError = null
                    scope.launch {
                        try {
                            LinuxDoRepository.archivePrivateMessage(message.id, archived = !movingToInbox)
                            messages = messages.filterNot { it.id == message.id }
                            pendingArchive = null
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            archiveError = error.message ?: if (movingToInbox) "移回失败" else "归档失败"
                        } finally {
                            archiving = false
                        }
                    }
                }
            }
        )
    }
}

/**
 * 官方功能 8：社区用户公开名片抽屉（点击任意帖子楼层头像/昵称即可弹出查看对方等级、简介、统计与发私信）
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun PublicUserCardSheet(
    username: String,
    onDismiss: () -> Unit,
    onSendPrivateMessage: (String) -> Unit,
    onSelectTopicId: (Int, String, Int) -> Unit,
    onInspectBadge: (GrantedBadge) -> Unit = {},
    onOpenUserBadges: (String) -> Unit = {}
) {
    val palette = LocalPalette.current
    var cardProfile by remember(username) { mutableStateOf<UserFullProfileSummary?>(null) }
    var allActions by remember(username) { mutableStateOf<List<UserActivityItem>>(emptyList()) }
    var topicActions by remember(username) { mutableStateOf<List<UserActivityItem>?>(null) }
    var replyActions by remember(username) { mutableStateOf<List<UserActivityItem>?>(null) }
    var profileBadges by remember(username) { mutableStateOf<List<GrantedBadge>?>(null) }
    var profileBadgeError by remember(username) { mutableStateOf(false) }
    var retryProfileBadges by remember(username) { mutableIntStateOf(0) }
    var activeTab by remember(username) { mutableIntStateOf(0) } // 0=全部动态, 1=主帖, 2=回复, 3=徽章
    var tabLoading by remember(username) { mutableStateOf(false) }
    var loadError by remember(username) { mutableStateOf<String?>(null) }
    var reloadToken by remember(username) { mutableIntStateOf(0) }
    var isLoading by remember(username) { mutableStateOf(true) }
    var showProfileStats by remember(username) { mutableStateOf(false) }

    LaunchedEffect(username, reloadToken) {
        isLoading = true
        loadError = null
        try {
            cardProfile = LinuxDoRepository.fetchPublicUserCard(username)
                ?: throw do_.linux.lindo.core.network.ApiReadException(do_.linux.lindo.core.network.ApiFailureKind.Connection)
            allActions = LinuxDoRepository.fetchUserActivities(username).take(25)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            loadError = error.message ?: "加载失败"
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(username, activeTab, retryProfileBadges) {
        if (activeTab == 3 && profileBadges == null) {
            profileBadgeError = false
            try {
                profileBadges = LinuxDoRepository.fetchUserBadges(username)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                profileBadgeError = true
            }
        } else if (activeTab == 1 && topicActions == null) {
            tabLoading = true
            try {
                topicActions = LinuxDoRepository.fetchUserActivities(username, filter = "4").take(25)
            } catch (_: Exception) {
                topicActions = allActions.filter { it.actionType == 4 }
            } finally {
                tabLoading = false
            }
        } else if (activeTab == 2 && replyActions == null) {
            tabLoading = true
            try {
                replyActions = LinuxDoRepository.fetchUserActivities(username, filter = "5").take(25)
            } catch (_: Exception) {
                replyActions = allActions.filter { it.actionType == 5 }
            } finally {
                tabLoading = false
            }
        }
    }

    fun formatReadTime(seconds: Int): String {
        val hours = seconds / 3600
        return when {
            hours > 0 -> "$hours 小时"
            seconds > 0 -> "${(seconds / 60).coerceAtLeast(1)} 分钟"
            else -> "0 分钟"
        }
    }

    LinDoFullScreenPage(
        onBack = onDismiss,
        containerColor = palette.cardSurface
    ) {
        val p = cardProfile
        LinDoPageTopBar(
            title = "用户主页",
            onBack = onDismiss,
            trailingContent = {
                TextButton(onClick = { onSendPrivateMessage(username) }) {
                    Text("私信", color = palette.inkPrimary)
                }
            }
        )
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
        ) {
            item(key = "profile_identity") {
                val displayName = p?.displayName?.ifBlank { username } ?: username
                val distinctHandle = !displayName.equals(username, ignoreCase = true)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    GoogleAccountRingAvatar(
                        letter = displayName,
                        avatarUrl = p?.avatarTemplate,
                        animatedAvatar = p?.animatedAvatar,
                        flair = p?.flair,
                        avatarSize = 58.dp,
                        strokeWidth = 2.2.dp
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.5.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                displayName, fontSize = 20.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold, color = palette.inkPrimary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                            )
                            if (distinctHandle) {
                                Text(
                                    "@$username", fontSize = 12.5.sp, lineHeight = 15.5.sp, color = palette.inkSecondary,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (p?.moderator == true) {
                                OfficialBadgeArtwork(
                                    url = OfficialModeratorIcon, description = "版主",
                                    colorFilter = ColorFilter.tint(palette.inkSecondary), modifier = Modifier.size(13.5.dp)
                                )
                            }
                        }
                        p?.title?.takeIf { it.isNotBlank() }?.let { UserTitleTag(title = it) }
                    }
                }
            }
            if (isLoading && cardProfile == null) {
                item(key = "loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 36.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        LinDoLoadingIndicator(label = "加载中")
                    }
                }
            } else if (loadError != null && cardProfile == null) {
                item(key = "error") {
                    LinDoLoadFailure(loadError!!, onRetry = { reloadToken++ })
                }
            }

            if (p != null) {
                // 1. 个人简介、自定义状态与加入/活跃时间等元数据
                val hasBioOrStatus = p.bioExcerpt.isNotBlank() || p.statusText.isNotBlank()
                val hasMetaTags = p.createdAtText.isNotBlank() || p.lastSeenText.isNotBlank() ||
                    !p.primaryGroupName.isNullOrBlank() || p.location.isNotBlank() ||
                    p.websiteName.isNotBlank() || p.solvedCount > 0

                if (hasBioOrStatus || hasMetaTags) {
                    item(key = "bio_and_meta") {
                        Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            if (p.statusText.isNotBlank()) {
                                Text(
                                    text = p.statusText,
                                    fontSize = 12.2.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = palette.inkSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (p.bioExcerpt.isNotBlank()) {
                                Text(
                                    text = p.bioExcerpt,
                                    fontSize = 13.sp,
                                    lineHeight = 19.sp,
                                    color = palette.inkSecondary,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (hasMetaTags) {
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    if (p.createdAtText.isNotBlank()) {
                                        Text(
                                            text = "加入于 ${p.createdAtText}",
                                            fontSize = 11.5.sp,
                                            color = palette.inkTertiary
                                        )
                                    }
                                    if (p.lastSeenText.isNotBlank()) {
                                        Text(
                                            text = "近期活跃 ${p.lastSeenText}",
                                            fontSize = 11.5.sp,
                                            color = palette.inkTertiary
                                        )
                                    }
                                    if (!p.primaryGroupName.isNullOrBlank()) {
                                        Text(
                                            text = "群组 ${p.primaryGroupName}",
                                            fontSize = 11.5.sp,
                                            color = palette.inkTertiary
                                        )
                                    }
                                    if (p.location.isNotBlank()) {
                                        Text(
                                            text = "地区 ${p.location}",
                                            fontSize = 11.5.sp,
                                            color = palette.inkTertiary
                                        )
                                    }
                                    if (p.websiteName.isNotBlank()) {
                                        Text(
                                            text = p.websiteName,
                                            fontSize = 11.5.sp,
                                            color = palette.inkTertiary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (p.solvedCount > 0) {
                                        Text(
                                            text = "已解决 ${p.solvedCount} 个问题",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = palette.brandGold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (p.badges.isNotEmpty()) {
                    item(key = "badges_strip") {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            p.badges.forEach { badge ->
                                IconButton(onClick = { onInspectBadge(badge) }) {
                                    PostGrantedBadgeIcon(badge, size = 23.dp)
                                }
                            }
                        }
                    }
                }

                item(key = "profile_stats") {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        ProfileMetric(formatCompactNumber(p.likesReceived), "收到赞", Modifier.weight(1f))
                        ProfileMetric("${p.daysVisited}", "访问天数", Modifier.weight(1f))
                        ProfileMetric(formatReadTime(p.timeReadSeconds), "阅读时长", Modifier.weight(1f))
                    }
                    TextButton(onClick = { showProfileStats = !showProfileStats }) {
                        Text(if (showProfileStats) "收起社区数据" else "社区数据", color = palette.inkSecondary)
                        Icon(
                            if (showProfileStats) LinDoIcons.ChevronUp else LinDoIcons.ChevronDown,
                            null, Modifier.size(15.dp), tint = palette.inkSecondary
                        )
                    }
                    if (showProfileStats) {
                        Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(Modifier.fillMaxWidth()) {
                                ProfileMetric(formatCompactNumber(p.topicsEntered), "浏览话题", Modifier.weight(1f))
                                ProfileMetric(formatCompactNumber(p.postsReadCount), "阅读帖子", Modifier.weight(1f))
                                ProfileMetric(formatCompactNumber(p.likesGiven), "送出赞", Modifier.weight(1f))
                            }
                            Row(Modifier.fillMaxWidth()) {
                                ProfileMetric(formatCompactNumber(p.topicCount), "发布主帖", Modifier.weight(1f))
                                ProfileMetric(formatCompactNumber(p.postCount), "发表回复", Modifier.weight(1f))
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }

                stickyHeader(key = "activity_tabs") {
                    Column(Modifier.fillMaxWidth().background(palette.cardSurface)) {
                        LinDoSectionTabs(
                            listOf("动态", "主帖 ${p.topicCount}", "回复 ${p.postCount}",
                                "徽章 ${p.badgeCount.coerceAtLeast(p.badges.size)}"),
                            activeTab, fontSize = 13.sp
                        ) { activeTab = it }
                        HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
                    }
                }

                // 5. 当前选中分栏的内容列表
                if (activeTab == 3) {
                    val ownedBadges = profileBadges
                    if (profileBadgeError) {
                        item(key = "error_badges") { LinDoLoadFailure("徽章加载失败") { retryProfileBadges++ } }
                    } else if (ownedBadges == null) {
                        item(key = "loading_badges") { LinDoLoadingIndicator(label = "加载徽章") }
                    } else if (ownedBadges.isEmpty()) {
                        item(key = "empty_badges") {
                            Text(
                                text = "暂无公开展示的徽章",
                                fontSize = 12.5.sp,
                                color = palette.inkTertiary,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        }
                    } else {
                        items(ownedBadges, key = { "badge_${it.id}" }, contentType = { "badge" }) { badge ->
                            val tierColor = UniversalBadgeEngine.badgeTierColor(badge.badgeType)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onInspectBadge(badge) }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                PostGrantedBadgeIcon(badge, size = 24.dp)

                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = badge.name,
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = palette.inkPrimary
                                        )
                                        if (badge.count > 1) {
                                            Text(
                                                text = "×${badge.count}",
                                                fontSize = 11.2.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = tierColor
                                            )
                                        }
                                    }
                                    if (badge.description.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = badge.description,
                                            fontSize = 12.sp,
                                            lineHeight = 17.sp,
                                            color = palette.inkSecondary,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                if (badge.grantedAtText.isNotBlank()) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = badge.grantedAtText,
                                        fontSize = 11.sp,
                                        color = palette.inkTertiary
                                    )
                                }
                            }
                            HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
                        }
                        item(key = "open_all_badges_btn") {
                            TextButton(onClick = { onOpenUserBadges(username) }) {
                                Text("查看徽章墙", color = palette.inkPrimary)
                            }
                        }
                    }
                } else {
                    val activeList = when (activeTab) {
                        1 -> topicActions ?: allActions.filter { it.actionType == 4 }
                        2 -> replyActions ?: allActions.filter { it.actionType == 5 }
                        else -> allActions
                    }
                    if (tabLoading && activeList.isEmpty()) {
                        item(key = "tab_loading") {
                            LinDoFeedbackRow("正在加载动态…", LinDoMascotState.Loading)
                        }
                    } else if (activeList.isEmpty()) {
                        item(key = "empty_actions") {
                            Text(
                                text = "暂无相关公开社区动态",
                                fontSize = 12.5.sp,
                                color = palette.inkTertiary,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        }
                    } else {
                        itemsIndexed(activeList, key = { idx, act -> "act_${act.id}_${act.actionType}_$idx" }) { _, act ->
                            ProfileActivityRow(
                                activity = act,
                                showActionLabel = activeTab == 0
                            ) { onSelectTopicId(act.topicId, act.title, act.postNumber) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileMetric(value: String, label: String, modifier: Modifier) {
    val palette = LocalPalette.current
    Column(modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, color = palette.inkPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(label, color = palette.inkSecondary, fontSize = 11.5.sp)
    }
}

private val profileImagePlaceholder = Regex("\\[(?:IMG|PXL|DSC|Screenshot)[-_ ][^\\]\\r\\n]+\\]", RegexOption.IGNORE_CASE)

@Composable
internal fun ProfileActivityRow(
    activity: UserActivityItem,
    showActionLabel: Boolean = true,
    onClick: () -> Unit
) {
    val palette = LocalPalette.current
    val excerpt = remember(activity.excerpt) { activity.excerpt.replace(profileImagePlaceholder, "图片").trim() }
    val (_, morandiFg) = remember(activity.categoryName, palette.isDark) {
        resolveMorandiCategoryColors(activity.categoryName, palette.isDark)
    }
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (activity.categoryName.isNotBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(5.5.dp)
                            .clip(CircleShape)
                            .background(morandiFg)
                    )
                    Text(
                        text = activity.categoryName,
                        fontSize = 11.8.sp,
                        fontWeight = FontWeight.Medium,
                        color = morandiFg,
                        maxLines = 1
                    )
                }
            }
            if (showActionLabel && activity.actionLabel.isNotBlank()) {
                Text(
                    text = if (activity.categoryName.isNotBlank()) "· ${activity.actionLabel}" else activity.actionLabel,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Normal,
                    color = palette.inkTertiary,
                    maxLines = 1
                )
            }
            if (activity.postNumber > 1) {
                Text(
                    text = "· ${activity.postNumber} 楼",
                    fontSize = 11.5.sp,
                    color = palette.inkTertiary
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            if (activity.createdAtText.isNotBlank()) {
                Text(
                    text = activity.createdAtText,
                    color = palette.inkTertiary,
                    fontSize = 11.5.sp,
                    maxLines = 1
                )
            }
        }
        Text(
            activity.title,
            color = palette.inkPrimary,
            fontSize = 15.sp,
            lineHeight = 21.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (excerpt.isNotBlank() && excerpt != activity.title) {
            Text(
                excerpt,
                color = palette.inkSecondary,
                fontSize = 12.6.sp,
                lineHeight = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
}

/**
 * 官方功能 10：阅读历史独立页面 (`GET /read.json`)
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
            loadError = error.message ?: "加载失败"
        } finally {
            isLoading = false
        }
    }

    LinDoFullScreenPage(
        onBack = onDismiss,
        containerColor = palette.cardSurface
    ) {
        LinDoPageTopBar(
            title = "阅读历史",
            onBack = onDismiss,
            trailingContent = {
                IconButton(onClick = { reloadToken++ }) {
                    Icon(LinDoIcons.Refresh, "刷新", Modifier.size(19.dp), tint = palette.inkSecondary)
                }
            }
        )
        HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
        if (isLoading) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                LinDoLoadingIndicator(label = "加载中")
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                if (loadError != null) {
                    item(key = "error") {
                        Box(Modifier.padding(horizontal = 16.dp)) {
                            LinDoLoadFailure(loadError!!, onRetry = { reloadToken++ })
                        }
                    }
                }
                if (items.isEmpty() && loadError == null) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            LinDoEmptyState(message = "暂无阅读记录")
                        }
                    }
                } else {
                    itemsIndexed(items, key = { _, item -> item.id }, contentType = { _, _ -> "topic" }) { _, topic ->
                        CommunityTopicItem(
                            topic = topic,
                            isSelected = false,
                            isCompact = true,
                            enableAnimations = false,
                            onClick = { onSelectTopic(topic) }
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
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
private fun UserCloudDraftsSheet(onDismiss: () -> Unit, onOpenDraftTopic: (CloudDraftItem) -> Unit) =
    UserDraftsSheet(onDismiss, onOpenDraftTopic)

/**
 * 官方功能 12：原生个人资料编辑页面 (`GET /u/{username}.json` + `PUT /u/{username}.json`)
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun EditProfileModalSheet(
    username: String,
    fullProfile: UserFullProfileSummary?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    var displayName by remember { mutableStateOf(fullProfile?.displayName.orEmpty()) }
    var userTitle by remember { mutableStateOf(fullProfile?.title.orEmpty()) }
    var location by remember { mutableStateOf(fullProfile?.location.orEmpty()) }
    var website by remember { mutableStateOf(fullProfile?.websiteName.orEmpty()) }
    var bioRaw by remember { mutableStateOf(fullProfile?.bioExcerpt.orEmpty()) }
    var availableTitles by remember {
        mutableStateOf(
            fullProfile?.badges.orEmpty().filter { it.allowTitle }.map { it.name }.distinct()
        )
    }
    var titleDropdownExpanded by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(username) {
        isLoading = true
        try {
            val snap = LinuxDoRepository.fetchUserAccountPreferences(username)
            if (snap != null) {
                displayName = snap.displayName
                userTitle = snap.title
                location = snap.location
                website = snap.website
                bioRaw = snap.bioRaw
                if (snap.availableTitles.isNotEmpty()) {
                    availableTitles = snap.availableTitles
                }
            }
        } finally {
            isLoading = false
        }
    }

    val titleOptions = remember(availableTitles, userTitle) {
        val merged = buildList {
            add("不佩戴头衔" to "")
            if (userTitle.isNotBlank() && userTitle !in availableTitles) {
                add(userTitle to userTitle)
            }
            availableTitles.forEach { t ->
                if (t.isNotBlank()) add(t to t)
            }
        }
        merged.distinctBy { it.second }
    }

    LinDoFullScreenPage(
        onBack = onDismiss,
        containerColor = palette.cardSurface
    ) {
        LinDoPageTopBar(
            title = "编辑个人资料",
            onBack = onDismiss,
            trailingContent = {
                TextButton(
                    enabled = !isSaving && !isLoading,
                    onClick = {
                        isSaving = true
                        statusMessage = null
                        scope.launch {
                            val ok = LinuxDoRepository.updateUserProfileInfo(
                                username = username,
                                displayName = displayName,
                                bioRaw = bioRaw,
                                title = userTitle,
                                location = location,
                                website = website
                            )
                            isSaving = false
                            if (ok) {
                                onSaved()
                            } else {
                                statusMessage = "保存失败，请稍后重试"
                            }
                        }
                    }
                ) {
                    Text(
                        text = if (isSaving) "保存中…" else "保存",
                        fontWeight = FontWeight.Bold,
                        color = palette.brandGold
                    )
                }
            }
        )
        HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)

        if (isLoading) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                LinDoLoadingIndicator(label = "同步资料中")
            }
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // 顶部原生用户身份栏（与 PublicUserCardSheet 保持一致）
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    GoogleAccountRingAvatar(
                        letter = displayName.ifBlank { username.ifBlank { "L" } },
                        avatarUrl = fullProfile?.avatarTemplate,
                        animatedAvatar = fullProfile?.animatedAvatar,
                        flair = fullProfile?.flair,
                        avatarSize = 56.dp,
                        strokeWidth = 2.dp
                    )
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = displayName.ifBlank { username },
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = palette.inkPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Text(
                                text = "@$username",
                                fontSize = 12.2.sp,
                                color = palette.inkSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = fullProfile?.trustLevelName ?: "Linux.do 社区成员",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = palette.brandGold,
                                maxLines = 1,
                                softWrap = false
                            )
                            if (userTitle.isNotBlank()) {
                                UserTitleTag(title = userTitle)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)

                // 1. 昵称 / 显示名称（原生无框单行输入行）
                NativeProfileInputRow(
                    label = "显示昵称",
                    value = displayName,
                    onValueChange = { displayName = it },
                    placeholder = "设置对外展示的昵称",
                    enabled = !isSaving
                )
                HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)

                // 2. 个人头衔（原生下拉选择框 DropdownMenu）
                Box(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .clickable(enabled = !isSaving) { titleDropdownExpanded = true }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "个人头衔",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = palette.inkSecondary,
                            modifier = Modifier.width(88.dp)
                        )
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(palette.subtleSurface)
                                .border(0.6.dp, palette.hairline, RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = userTitle.ifBlank { "不佩戴头衔" },
                                fontSize = 13.8.sp,
                                fontWeight = if (userTitle.isNotBlank()) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (userTitle.isNotBlank()) palette.inkPrimary else palette.inkSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = if (titleDropdownExpanded) LinDoIcons.ChevronUp else LinDoIcons.ChevronDown,
                                contentDescription = "选择个人头衔",
                                modifier = Modifier.size(15.dp),
                                tint = palette.inkSecondary
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = titleDropdownExpanded,
                        onDismissRequest = { titleDropdownExpanded = false },
                        modifier = Modifier
                            .background(palette.cardSurface)
                            .widthIn(min = 220.dp, max = 320.dp)
                    ) {
                        titleOptions.forEach { (label, value) ->
                            val selected = userTitle == value
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = label,
                                        fontSize = 13.8.sp,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (selected) palette.brandGold else palette.inkPrimary
                                    )
                                },
                                trailingIcon = if (selected) {
                                    {
                                        Icon(
                                            imageVector = LinDoIcons.Check,
                                            contentDescription = "已选中",
                                            modifier = Modifier.size(16.dp),
                                            tint = palette.brandGold
                                        )
                                    }
                                } else null,
                                onClick = {
                                    userTitle = value
                                    titleDropdownExpanded = false
                                }
                            )
                        }
                    }
                }
                HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)

                // 3. 所在地
                NativeProfileInputRow(
                    label = "所在地",
                    value = location,
                    onValueChange = { location = it },
                    placeholder = "填写城市或地区（选填）",
                    enabled = !isSaving
                )
                HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)

                // 4. 个人网站
                NativeProfileInputRow(
                    label = "个人网站",
                    value = website,
                    onValueChange = { website = it },
                    placeholder = "https://example.com（选填）",
                    enabled = !isSaving
                )
                HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)

                // 5. 个人简介（多行无框原生文本区）
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "个人简介",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = palette.inkSecondary
                    )
                    val bioStyle = TextStyle(
                        color = palette.inkPrimary,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                    BasicTextField(
                        value = bioRaw,
                        onValueChange = { bioRaw = it },
                        enabled = !isSaving,
                        minLines = 4,
                        maxLines = 8,
                        textStyle = bioStyle,
                        cursorBrush = SolidColor(palette.brandGold),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 96.dp),
                        decorationBox = { inner ->
                            LinDoInputDecoration(
                                empty = bioRaw.isEmpty(),
                                placeholder = "介绍一下你自己（支持 Markdown）",
                                textStyle = bioStyle,
                                placeholderColor = palette.inkTertiary,
                                modifier = Modifier.fillMaxWidth(),
                                alignment = Alignment.TopStart,
                                innerTextField = inner
                            )
                        }
                    )
                }
                HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)

                statusMessage?.let { msg ->
                    Spacer(modifier = Modifier.height(12.dp))
                    LinDoFeedbackRow(message = msg, state = LinDoMascotState.NetworkError)
                }
            }
        }
    }
}

@Composable
private fun NativeProfileInputRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean
) {
    val palette = LocalPalette.current
    val textStyle = TextStyle(
        color = palette.inkPrimary,
        fontSize = 14.2.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = palette.inkSecondary,
            modifier = Modifier.width(88.dp)
        )
        BasicTextField(
            value = value,
            onValueChange = { next -> onValueChange(next.replace(Regex("[\\r\\n]+"), " ")) },
            enabled = enabled,
            singleLine = true,
            maxLines = 1,
            textStyle = textStyle,
            cursorBrush = SolidColor(palette.brandGold),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                LinDoInputDecoration(
                    empty = value.isEmpty(),
                    placeholder = placeholder,
                    textStyle = textStyle,
                    placeholderColor = palette.inkTertiary,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    alignment = Alignment.CenterStart,
                    innerTextField = inner
                )
            }
        )
    }
}

/**
 * 官方功能 13：原生关注与粉丝网络页面 (`GET /u/{username}/follow/following.json` | `followers.json` | `feed.json`)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserFollowNetworkSheet(
    username: String,
    onDismiss: () -> Unit,
    onInspectUser: (String) -> Unit,
    onSelectActivity: (Int, String, Int?) -> Unit
) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    var activeTab by remember { mutableIntStateOf(0) } // 0=正在关注, 1=我的粉丝, 2=关注动态
    var users by remember { mutableStateOf<List<FollowUserEntry>>(emptyList()) }
    var feedItems by remember { mutableStateOf<List<UserActivityItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var reloadToken by remember { mutableIntStateOf(0) }

    LaunchedEffect(username, activeTab, reloadToken) {
        isLoading = true
        try {
            if (activeTab == 2) {
                feedItems = LinuxDoRepository.fetchUserFollowFeed(username)
            } else {
                users = LinuxDoRepository.fetchUserFollowList(
                    username = username,
                    followers = activeTab == 1
                )
            }
        } finally {
            isLoading = false
        }
    }

    LinDoFullScreenPage(
        onBack = onDismiss,
        containerColor = palette.cardSurface
    ) {
        LinDoPageTopBar(
            title = "关注与粉丝",
            onBack = onDismiss,
            trailingContent = {
                IconButton(onClick = { reloadToken++ }) {
                    Icon(LinDoIcons.Refresh, "刷新", Modifier.size(19.dp), tint = palette.inkSecondary)
                }
            }
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.cardSurface)
                .padding(horizontal = 16.dp)
        ) {
            LinDoSectionTabs(
                labels = listOf("正在关注", "我的粉丝", "同好动态"),
                selectedIndex = activeTab,
                fontSize = 14.sp
            ) { activeTab = it }
        }
        HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)

        if (isLoading) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                LinDoLoadingIndicator(label = "加载中")
            }
        } else if (activeTab == 2) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
            ) {
                if (feedItems.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            LinDoEmptyState(message = "暂无关注同好的最新动态")
                        }
                    }
                } else {
                    itemsIndexed(feedItems, key = { idx, it -> "${it.id}_$idx" }) { _, item ->
                        ProfileActivityRow(item) {
                            onSelectActivity(item.topicId, item.title, item.postNumber)
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
            ) {
                if (users.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            LinDoEmptyState(
                                message = if (activeTab == 0) "还没有关注任何社区同好" else "暂无粉丝记录"
                            )
                        }
                    }
                } else {
                    itemsIndexed(users, key = { _, u -> u.username }) { _, user ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onInspectUser(user.username) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            GoogleAccountRingAvatar(
                                letter = user.displayName,
                                avatarUrl = user.avatarUrl,
                                animatedAvatar = user.animatedAvatar,
                                avatarSize = 42.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = user.displayName,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = palette.inkPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (!user.displayName.equals(user.username, ignoreCase = true)) {
                                        Text(
                                            text = "@${user.username}",
                                            fontSize = 12.sp,
                                            color = palette.inkSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Text(
                                        text = "Lv.${user.trustLevel}",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = palette.brandGold
                                    )
                                }
                                if (user.title.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(3.dp))
                                    UserTitleTag(title = user.title)
                                }
                            }
                            TextButton(
                                onClick = {
                                    val targetFollow = !user.isFollowing
                                    users = users.map {
                                        if (it.username == user.username) it.copy(isFollowing = targetFollow) else it
                                    }
                                    scope.launch {
                                        LinuxDoRepository.toggleFollowUser(user.username, follow = targetFollow)
                                    }
                                }
                            ) {
                                Text(
                                    text = if (user.isFollowing) "已关注" else "+ 关注",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (user.isFollowing) palette.inkSecondary else palette.brandGold
                                )
                            }
                        }
                        HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
                    }
                }
            }
        }
    }
}

/**
 * 官方功能 14：原生社区积分与活跃排行榜 (`GET /directory_items.json?period={period}&order=likes_received`)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommunityLeaderboardSheet(
    onDismiss: () -> Unit,
    onInspectUser: (String) -> Unit
) {
    val palette = LocalPalette.current
    val periods = remember {
        listOf("今日榜" to "daily", "本周榜" to "weekly", "本月榜" to "monthly", "总榜" to "all")
    }
    var selectedPeriodIdx by remember { mutableIntStateOf(1) }
    var entries by remember { mutableStateOf<List<LeaderboardUserEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var reloadToken by remember { mutableIntStateOf(0) }

    LaunchedEffect(selectedPeriodIdx, reloadToken) {
        isLoading = true
        try {
            val periodKey = periods.getOrNull(selectedPeriodIdx)?.second ?: "weekly"
            entries = LinuxDoRepository.fetchCommunityLeaderboard(period = periodKey)
        } finally {
            isLoading = false
        }
    }

    LinDoFullScreenPage(
        onBack = onDismiss,
        containerColor = palette.cardSurface
    ) {
        LinDoPageTopBar(
            title = "社区积分与活跃榜",
            onBack = onDismiss,
            trailingContent = {
                IconButton(onClick = { reloadToken++ }) {
                    Icon(LinDoIcons.Refresh, "刷新", Modifier.size(19.dp), tint = palette.inkSecondary)
                }
            }
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.cardSurface)
                .padding(horizontal = 16.dp)
        ) {
            LinDoSectionTabs(
                labels = periods.map { it.first },
                selectedIndex = selectedPeriodIdx,
                fontSize = 14.sp
            ) { selectedPeriodIdx = it }
        }
        HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)

        if (isLoading) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                LinDoLoadingIndicator(label = "加载排行榜中")
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
            ) {
                if (entries.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            LinDoEmptyState(message = "暂无排行统计数据")
                        }
                    }
                } else {
                    itemsIndexed(entries, key = { _, e -> "${e.username}_${e.rank}" }) { _, entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onInspectUser(entry.username) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${entry.rank}",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (entry.rank <= 3) palette.brandGold else palette.inkTertiary,
                                modifier = Modifier.width(28.dp)
                            )
                            GoogleAccountRingAvatar(
                                letter = entry.displayName,
                                avatarUrl = entry.avatarUrl,
                                avatarSize = 40.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = entry.displayName,
                                        fontSize = 14.8.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = palette.inkPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (entry.title.isNotBlank()) {
                                        UserTitleTag(title = entry.title)
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "获赞 ${formatCompactNumber(entry.likesReceived)} · 回复 ${formatCompactNumber(entry.postCount)} · 主帖 ${entry.topicCount}",
                                    fontSize = 11.8.sp,
                                    color = palette.inkSecondary,
                                    maxLines = 1
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = formatCompactNumber(entry.score),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = palette.brandGold
                                )
                                Text(
                                    text = "活跃值",
                                    fontSize = 10.5.sp,
                                    color = palette.inkTertiary
                                )
                            }
                        }
                        HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
                    }
                }
            }
        }
    }
}

/**
 * 官方功能 15：原生账号与偏好设置页面 (`GET /u/{username}.json` + `PUT /u/{username}.json`)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserAccountPreferencesSheet(
    username: String,
    onDismiss: () -> Unit,
    onOpenEditProfile: () -> Unit,
    onUpdated: () -> Unit
) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<UserAccountPreferencesSnapshot?>(null) }
    var hidePresence by remember { mutableStateOf(false) }
    var allowPm by remember { mutableStateOf(true) }
    var isLoading by remember { mutableStateOf(true) }
    var isSavingToggle by remember { mutableStateOf(false) }

    LaunchedEffect(username) {
        isLoading = true
        try {
            val loaded = LinuxDoRepository.fetchUserAccountPreferences(username)
            snapshot = loaded
            if (loaded != null) {
                hidePresence = loaded.hideProfileAndPresence
                allowPm = loaded.allowPrivateMessages
            }
        } finally {
            isLoading = false
        }
    }

    fun persistPrivacyPrefs(newHidePresence: Boolean, newAllowPm: Boolean) {
        val snap = snapshot ?: return
        hidePresence = newHidePresence
        allowPm = newAllowPm
        isSavingToggle = true
        scope.launch {
            LinuxDoRepository.updateUserProfileInfo(
                username = snap.username,
                displayName = snap.displayName,
                bioRaw = snap.bioRaw,
                title = snap.title,
                location = snap.location,
                website = snap.website,
                hidePresence = newHidePresence,
                allowPrivateMessages = newAllowPm
            )
            isSavingToggle = false
            onUpdated()
        }
    }

    LinDoFullScreenPage(
        onBack = onDismiss,
        containerColor = palette.cardSurface
    ) {
        LinDoPageTopBar(
            title = "账号与偏好设置",
            onBack = onDismiss,
            trailingContent = {
                TextButton(onClick = onOpenEditProfile) {
                    Text("编辑资料", color = palette.brandGold, fontWeight = FontWeight.SemiBold)
                }
            }
        )
        HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)

        if (isLoading && snapshot == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                LinDoLoadingIndicator(label = "加载账号信息")
            }
        } else {
            val s = snapshot
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                item(key = "section_account") {
                    Text(
                        text = "账号信息",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.inkSecondary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )
                }
                item(key = "row_username") {
                    PreferenceInfoRow("用户名", "@${s?.username ?: username}")
                    HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
                }
                item(key = "row_display_name") {
                    PreferenceInfoRow("显示昵称", s?.displayName?.ifBlank { "未设置" } ?: "未设置", onClick = onOpenEditProfile)
                    HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
                }
                if (!s?.email.isNullOrBlank()) {
                    item(key = "row_email") {
                        PreferenceInfoRow("绑定邮箱", s?.email.orEmpty())
                        HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
                    }
                }
                item(key = "row_title") {
                    PreferenceInfoRow("个人头衔", s?.title?.ifBlank { "未佩戴" } ?: "未佩戴", onClick = onOpenEditProfile)
                    HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
                }
                item(key = "row_2fa") {
                    PreferenceInfoRow("两步验证 (2FA)", if (s?.twoFactorEnabled == true) "已开启保护" else "标准登录保护")
                    HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
                }
                item(key = "row_devices") {
                    PreferenceInfoRow("已授权在线设备", "${s?.activeDeviceCount ?: 1} 台设备")
                    HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
                }

                if (!s?.associatedAccounts.isNullOrEmpty()) {
                    item(key = "section_assoc") {
                        Text(
                            text = "已关联第三方账号",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.inkSecondary,
                            modifier = Modifier.padding(top = 18.dp, bottom = 4.dp)
                        )
                    }
                    itemsIndexed(s?.associatedAccounts.orEmpty(), key = { idx, acc -> "assoc_$idx$acc" }) { _, acc ->
                        PreferenceInfoRow("互联绑定", acc)
                        HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
                    }
                }

                item(key = "section_privacy") {
                    Text(
                        text = "隐私与互动偏好",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.inkSecondary,
                        modifier = Modifier.padding(top = 18.dp, bottom = 4.dp)
                    )
                }
                item(key = "toggle_presence") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isSavingToggle && s != null) {
                                persistPrivacyPrefs(!hidePresence, allowPm)
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("隐藏我的公开资料与在线状态", fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = palette.inkPrimary)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text("开启后其他用户无法查看你的在线状态", fontSize = 12.sp, color = palette.inkSecondary)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = hidePresence,
                            enabled = !isSavingToggle && s != null,
                            onCheckedChange = { persistPrivacyPrefs(it, allowPm) }
                        )
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
                }
                item(key = "toggle_pm") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isSavingToggle && s != null) {
                                persistPrivacyPrefs(hidePresence, !allowPm)
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("允许其他成员向我发送站内私信", fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = palette.inkPrimary)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text("关闭后仅管理员可向你发起私信", fontSize = 12.sp, color = palette.inkSecondary)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = allowPm,
                            enabled = !isSavingToggle && s != null,
                            onCheckedChange = { persistPrivacyPrefs(hidePresence, it) }
                        )
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
                }
            }
        }
    }
}

@Composable
private fun PreferenceInfoRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    val palette = LocalPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp, color = palette.inkSecondary)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = palette.inkPrimary)
            if (onClick != null) {
                Icon(LinDoIcons.ChevronRight, null, Modifier.size(15.dp), tint = palette.inkTertiary)
            }
        }
    }
}

/**
 * 官方功能 16：原生关于社区与管理团队页面 (`GET /about.json`)
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun CommunityAboutSheet(
    onDismiss: () -> Unit,
    onInspectUser: (String) -> Unit
) {
    val palette = LocalPalette.current
    var aboutData by remember { mutableStateOf<CommunityAboutData?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var activeTeamTab by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        isLoading = true
        try {
            aboutData = LinuxDoRepository.fetchCommunityAboutInfo()
        } finally {
            isLoading = false
        }
    }

    LinDoFullScreenPage(
        onBack = onDismiss,
        containerColor = palette.cardSurface
    ) {
        LinDoPageTopBar(
            title = "关于 LINUX DO 社区",
            onBack = onDismiss
        )
        HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)

        if (isLoading && aboutData == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                LinDoLoadingIndicator(label = "加载中")
            }
        } else {
            val info = aboutData
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
            ) {
                item(key = "about_intro") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = info?.title ?: "LINUX DO · 新的理想型社区",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.inkPrimary
                        )
                        Text(
                            text = info?.description ?: "真诚、友善、团结、专业，共建你我引以为荣之社区。",
                            fontSize = 13.2.sp,
                            lineHeight = 19.sp,
                            color = palette.inkSecondary
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
                }

                if (info != null) {
                    item(key = "about_stats") {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(Modifier.fillMaxWidth()) {
                                ProfileMetric(formatCompactNumber(info.topicCount), "累计话题", Modifier.weight(1f))
                                ProfileMetric(formatCompactNumber(info.postCount), "累计帖子", Modifier.weight(1f))
                                ProfileMetric(formatCompactNumber(info.userCount), "注册成员", Modifier.weight(1f))
                            }
                            Row(Modifier.fillMaxWidth()) {
                                ProfileMetric(formatCompactNumber(info.activeUsers7Days), "7日活跃", Modifier.weight(1f))
                                ProfileMetric(formatCompactNumber(info.activeUsers30Days), "30日活跃", Modifier.weight(1f))
                                ProfileMetric(formatCompactNumber(info.likeCount), "全站获赞", Modifier.weight(1f))
                            }
                        }
                        HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
                    }

                    stickyHeader(key = "team_tabs") {
                        Column(Modifier.fillMaxWidth().background(palette.cardSurface)) {
                            LinDoSectionTabs(
                                labels = listOf("管理员 ${info.admins.size}", "版主团队 ${info.moderators.size}"),
                                selectedIndex = activeTeamTab,
                                fontSize = 13.8.sp
                            ) { activeTeamTab = it }
                            HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
                        }
                    }

                    val activeMembers = if (activeTeamTab == 0) info.admins else info.moderators
                    itemsIndexed(activeMembers, key = { _, u -> "${activeTeamTab}_${u.username}" }) { _, member ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onInspectUser(member.username) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            GoogleAccountRingAvatar(
                                letter = member.displayName,
                                avatarUrl = member.avatarUrl,
                                animatedAvatar = member.animatedAvatar,
                                avatarSize = 40.dp
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = member.displayName,
                                        fontSize = 14.8.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = palette.inkPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (!member.displayName.equals(member.username, ignoreCase = true)) {
                                        Text(
                                            text = "@${member.username}",
                                            fontSize = 12.sp,
                                            color = palette.inkSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (member.title.isNotBlank()) {
                                        UserTitleTag(title = member.title)
                                    }
                                }
                            }
                            Icon(LinDoIcons.ChevronRight, null, Modifier.size(15.dp), tint = palette.inkTertiary)
                        }
                        HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
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
