package do_.linux.lindo.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import do_.linux.lindo.core.connect.ConnectTrustLevelReport
import do_.linux.lindo.core.connect.ScreenTrackEngine
import do_.linux.lindo.core.connect.UserPresenceService
import do_.linux.lindo.core.model.GrantedBadge
import do_.linux.lindo.core.model.PostItemModel
import do_.linux.lindo.core.model.TopicDetailModel
import do_.linux.lindo.core.model.TopicSummary
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
 * Google Gemini & Google Account (Material 3 Expressive) Tonal Surface 色彩系统：
 * - 摒弃生硬的分割线与描边，采用 Google 标志性的冷瓷灰底 (`#F8FAFD`) 与纯白高阶容器 (`#FFFFFF`) 形成纯净的色阶层次
 * - 选中态胶囊统一采用 Google Tonal Blue (`#D3E3FD` / `#041E49`)
 * - 品牌主色采用 Google Primary Blue (`#0B57D0` / `#A8C7FA`)
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
    val successContainer: Color,
    val onSuccessContainer: Color
)

private val LightPalette = LinDoThemePalette(
    isDark = false,
    canvas = Color(0xFFF8FAFD),
    groupedCanvas = Color(0xFFF0F4F9),
    cardSurface = Color(0xFFFFFFFF),
    subtleSurface = Color(0xFFEDF2FA),
    navBarSurface = Color(0xFFF0F4F9),
    tonalPill = Color(0xFFD3E3FD),
    onTonalPill = Color(0xFF041E49),
    inkPrimary = Color(0xFF1F1F1F),
    inkSecondary = Color(0xFF444746),
    inkTertiary = Color(0xFF747775),
    hairline = Color(0xFFE1E6EF),
    accent = Color(0xFF0B57D0),
    successContainer = Color(0xFFE6F4EA),
    onSuccessContainer = Color(0xFF137333)
)

private val DarkPalette = LinDoThemePalette(
    isDark = true,
    canvas = Color(0xFF131314),
    groupedCanvas = Color(0xFF0E0E0F),
    cardSurface = Color(0xFF1E1F20),
    subtleSurface = Color(0xFF282A2C),
    navBarSurface = Color(0xFF1E1F20),
    tonalPill = Color(0xFF004A77),
    onTonalPill = Color(0xFFC2E7FF),
    inkPrimary = Color(0xFFE3E3E3),
    inkSecondary = Color(0xFFC4C7C5),
    inkTertiary = Color(0xFF8E918F),
    hairline = Color(0xFF333537),
    accent = Color(0xFFA8C7FA),
    successContainer = Color(0xFF0F5223),
    onSuccessContainer = Color(0xFF6DD58C)
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
    onPrimary = Color(0xFF062E6F),
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
    TOP("热门", "top.json"),
    NEW("新帖", "new.json")
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
 * Gemini 标志性四芒星矢量图标（纯 Canvas 渲染，无锯齿）
 */
@Composable
private fun GeminiSparkleIcon(
    size: Dp = 18.dp,
    modifier: Modifier = Modifier
) {
    val brush = remember {
        Brush.linearGradient(
            colors = listOf(
                Color(0xFF4285F4),
                Color(0xFF9B72CB),
                Color(0xFFD96570)
            )
        )
    }
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val cx = w / 2f
        val cy = h / 2f
        val path = Path().apply {
            moveTo(cx, 0f)
            quadraticTo(cx, cy, w, cy)
            quadraticTo(cx, cy, cx, h)
            quadraticTo(cx, cy, 0f, cy)
            quadraticTo(cx, cy, cx, 0f)
            close()
        }
        drawPath(path = path, brush = brush)
    }
}

/**
 * Google Account / Google One 标志性四色环绕头像组件
 */
@Composable
private fun GoogleAccountRingAvatar(
    letter: String = "L",
    avatarUrl: String? = null,
    avatarSize: Dp = 76.dp,
    strokeWidth: Dp = 3.dp,
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
            // Google 经典四色分段圆弧
            drawArc(
                color = Color(0xFFEA4335),
                startAngle = -135f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )
            drawArc(
                color = Color(0xFF4285F4),
                startAngle = -45f,
                sweepAngle = 110f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )
            drawArc(
                color = Color(0xFF34A853),
                startAngle = 65f,
                sweepAngle = 95f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )
            drawArc(
                color = Color(0xFFFBBC04),
                startAngle = 160f,
                sweepAngle = 65f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )
        }

        Box(
            modifier = Modifier
                .size(avatarSize - strokeWidth * 4f)
                .clip(CircleShape)
                .background(palette.tonalPill),
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
                    color = palette.onTonalPill
                )
            }
        }
    }
}

@Composable
fun LinDoApp() {
    var isDarkTheme by remember { mutableStateOf(false) }
    val palette = if (isDarkTheme) DarkPalette else LightPalette

    var currentTab by remember { mutableStateOf(MainTab.HOME) }
    var selectedCategoryId by remember { mutableStateOf<Int?>(null) }
    var currentSort by remember { mutableStateOf(FeedSortFilter.LATEST) }
    var searchQuery by remember { mutableStateOf("") }
    var enableAnimations by remember { mutableStateOf(true) }

    var topics by remember { mutableStateOf<List<TopicSummary>>(emptyList()) }
    var isLoadingTopics by remember { mutableStateOf(true) }
    var feedError by remember { mutableStateOf<String?>(null) }

    var activeTopicSummary by remember { mutableStateOf<TopicSummary?>(null) }
    var activeTopicDetail by remember { mutableStateOf<TopicDetailModel?>(null) }
    var isLoadingDetail by remember { mutableStateOf(false) }
    var mobileShowingDetail by remember { mutableStateOf(false) }

    var inspectedBadge by remember { mutableStateOf<GrantedBadge?>(null) }
    var showNetworkSheet by remember { mutableStateOf(false) }
    var showAuthSheet by remember { mutableStateOf(false) }
    var showConnectSheet by remember { mutableStateOf(false) }

    // 认证状态与首屏登录门禁状态
    var authVersion by remember { mutableStateOf(0) }
    var guestPreviewMode by remember { mutableStateOf(false) }
    var activeWebLoginUrl by remember { mutableStateOf<String?>(null) }

    val isAuthorized = remember(authVersion) { UserApiKeyAuthManager.isAuthorized() }
    val currentUser = remember(authVersion) { UserApiKeyAuthManager.currentUser }

    val feedListState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    fun loadTopics(sort: FeedSortFilter = currentSort, categoryId: Int? = selectedCategoryId) {
        scope.launch {
            isLoadingTopics = true
            feedError = null
            val fetched = LinuxDoRepository.fetchTopics(
                path = sort.apiPath,
                categoryId = categoryId
            )
            if (fetched.isNotEmpty()) {
                topics = fetched
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
            }
            isLoadingTopics = false
        }
    }

    DisposableEffect(Unit) {
        val prevCallback = UserApiKeyAuthManager.onAuthStateChanged
        UserApiKeyAuthManager.onAuthStateChanged = {
            prevCallback?.invoke()
            authVersion++
            val otp = UserApiKeyAuthManager.pendingOtpToken
            if (!otp.isNullOrBlank()) {
                UserApiKeyAuthManager.pendingOtpToken = null
                activeWebLoginUrl = "https://linux.do/session/otp/$otp"
            }
            if (UserApiKeyAuthManager.isAuthorized()) {
                activeWebLoginUrl = null
                showAuthSheet = false
                loadTopics(currentSort, selectedCategoryId)
            }
        }
        onDispose {
            UserApiKeyAuthManager.onAuthStateChanged = prevCallback
        }
    }

    fun openTopic(topic: TopicSummary) {
        activeTopicSummary = topic
        mobileShowingDetail = true
        scope.launch {
            isLoadingDetail = true
            activeTopicDetail = LinuxDoRepository.fetchTopicDetail(topic.id, topic)
            isLoadingDetail = false
        }
    }

    LaunchedEffect(Unit) {
        loadTopics(FeedSortFilter.LATEST, null)
    }

    SideEffect {
        val canIntercept = activeWebLoginUrl != null ||
            inspectedBadge != null ||
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

    CompositionLocalProvider(LocalPalette provides palette) {
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
                                onToggleTheme = { isDarkTheme = !isDarkTheme }
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
                                            currentSort = currentSort,
                                            searchQuery = searchQuery,
                                            isLoading = isLoadingTopics,
                                            errorMessage = feedError,
                                            enableAnimations = enableAnimations,
                                            isAuthorized = isAuthorized,
                                            currentUser = currentUser,
                                            onRequestLogin = {
                                                activeWebLoginUrl = "https://linux.do/login"
                                            },
                                            onSearchChange = { searchQuery = it },
                                            onSelectCategory = { catId ->
                                                selectedCategoryId = catId
                                                loadTopics(currentSort, catId)
                                            },
                                            onSelectSort = { sort ->
                                                currentSort = sort
                                                loadTopics(sort, selectedCategoryId)
                                            },
                                            onRefresh = { loadTopics(currentSort, selectedCategoryId) },
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
                                            onInspectBadge = { inspectedBadge = it }
                                        )
                                    }
                                }

                                MainTab.DISCOVER -> {
                                    CommunityDiscoverScreen(
                                        hotTopics = topics.filterNot { it.pinned }.take(4),
                                        onSelectCategory = { catId ->
                                            selectedCategoryId = catId
                                            currentTab = MainTab.HOME
                                            loadTopics(currentSort, catId)
                                        },
                                        onSelectTopic = { topic ->
                                            currentTab = MainTab.HOME
                                            openTopic(topic)
                                        }
                                    )
                                }

                                MainTab.PROFILE -> {
                                    CommunityProfileScreen(
                                        isDarkTheme = isDarkTheme,
                                        enableAnimations = enableAnimations,
                                        isAuthorized = isAuthorized,
                                        currentUser = currentUser,
                                        onToggleDark = { isDarkTheme = !isDarkTheme },
                                        onToggleAnimations = { enableAnimations = !enableAnimations },
                                        onOpenConnectSheet = { showConnectSheet = true },
                                        onOpenNetworkSheet = { showNetworkSheet = true },
                                        onOpenAuthSheet = { showAuthSheet = true },
                                        onPrimaryWebLogin = {
                                            activeWebLoginUrl = "https://linux.do/login"
                                        },
                                        onLogout = {
                                            UserApiKeyAuthManager.logout()
                                            guestPreviewMode = false
                                        }
                                    )
                                }
                            }
                        }
                    } else {
                        // 移动端布局（沉浸式全屏阅读或底部 Google M3 Pill 导航）
                        if (currentTab == MainTab.HOME && mobileShowingDetail && activeTopicSummary != null) {
                            CommunityTopicReaderPane(
                                summary = activeTopicSummary,
                                detail = activeTopicDetail,
                                isLoading = isLoadingDetail,
                                enableAnimations = enableAnimations,
                                showBackButton = true,
                                onBack = { mobileShowingDetail = false },
                                onRefresh = { activeTopicSummary?.let { openTopic(it) } },
                                onInspectBadge = { inspectedBadge = it }
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
                                                currentSort = currentSort,
                                                searchQuery = searchQuery,
                                                isLoading = isLoadingTopics,
                                                errorMessage = feedError,
                                                enableAnimations = enableAnimations,
                                                isAuthorized = isAuthorized,
                                                currentUser = currentUser,
                                                onRequestLogin = {
                                                    activeWebLoginUrl = "https://linux.do/login"
                                                },
                                                onSearchChange = { searchQuery = it },
                                                onSelectCategory = { catId ->
                                                    selectedCategoryId = catId
                                                    loadTopics(currentSort, catId)
                                                },
                                                onSelectSort = { sort ->
                                                    currentSort = sort
                                                    loadTopics(sort, selectedCategoryId)
                                                },
                                                onRefresh = { loadTopics(currentSort, selectedCategoryId) },
                                                onSelectTopic = { openTopic(it) },
                                                onAvatarClick = { currentTab = MainTab.PROFILE }
                                            )
                                        }

                                        MainTab.DISCOVER -> {
                                            CommunityDiscoverScreen(
                                                hotTopics = topics.filterNot { it.pinned }.take(4),
                                                onSelectCategory = { catId ->
                                                    selectedCategoryId = catId
                                                    currentTab = MainTab.HOME
                                                    loadTopics(currentSort, catId)
                                                },
                                                onSelectTopic = { topic ->
                                                    currentTab = MainTab.HOME
                                                    openTopic(topic)
                                                }
                                            )
                                        }

                                        MainTab.PROFILE -> {
                                            CommunityProfileScreen(
                                                isDarkTheme = isDarkTheme,
                                                enableAnimations = enableAnimations,
                                                isAuthorized = isAuthorized,
                                                currentUser = currentUser,
                                                onToggleDark = { isDarkTheme = !isDarkTheme },
                                                onToggleAnimations = { enableAnimations = !enableAnimations },
                                                onOpenConnectSheet = { showConnectSheet = true },
                                                onOpenNetworkSheet = { showNetworkSheet = true },
                                                onOpenAuthSheet = { showAuthSheet = true },
                                                onPrimaryWebLogin = {
                                                    activeWebLoginUrl = "https://linux.do/login"
                                                },
                                                onLogout = {
                                                    UserApiKeyAuthManager.logout()
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
 * Google Gemini / Google Account 标准 Material 3 底部导航栏：
 * - 采用 `#F0F4F9` Tonal Surface 底色，完全去除生硬的顶部分割线
 * - 选中态采用 `64x32dp` `#D3E3FD` 圆角胶囊指示器 + 实心图标
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(66.dp),
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
                            .width(64.dp)
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
        }
    }
}

/**
 * Gemini & Google Search 风格首页：
 * 1. 顶部 `52.dp` Google 搜索胶囊栏（内嵌 Gemini 四芒星、搜索输入、排序切换胶囊与 Google Account 环绕头像）
 * 2. Material 3 Tonal Filter Pills 频道筛选胶囊列（零下划线冲突，选中态 `#D3E3FD`）
 * 3. 柔和圆角置顶公告卡（`#EDF2FA`）
 * 4. 无边框纯白圆角社区卡片（`#FFFFFF` 悬浮于 `#F8FAFD` 瓷白画布之上）
 */
@Composable
private fun CommunityHomeFeedPane(
    topics: List<TopicSummary>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    selectedTopicId: Int?,
    selectedCategoryId: Int?,
    currentSort: FeedSortFilter,
    searchQuery: String,
    isLoading: Boolean,
    errorMessage: String?,
    enableAnimations: Boolean,
    isAuthorized: Boolean,
    currentUser: AuthenticatedUserProfile?,
    onRequestLogin: () -> Unit,
    onSearchChange: (String) -> Unit,
    onSelectCategory: (Int?) -> Unit,
    onSelectSort: (FeedSortFilter) -> Unit,
    onRefresh: () -> Unit,
    onSelectTopic: (TopicSummary) -> Unit,
    onAvatarClick: () -> Unit
) {
    val palette = LocalPalette.current
    val pinnedTopics = remember(topics, selectedCategoryId, searchQuery) {
        if (selectedCategoryId == null && searchQuery.isBlank()) {
            topics.filter { it.pinned }
        } else {
            emptyList()
        }
    }
    val regularTopics = remember(topics, pinnedTopics) {
        if (pinnedTopics.isEmpty()) topics else topics.filterNot { it.pinned }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.canvas)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        // 1. Google App Search Bar（内嵌 Gemini 四芒星 + 搜索框 + 排序快速切换 + Google Account 头像）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 6.dp)
                .height(50.dp)
                .clip(RoundedCornerShape(25.dp))
                .background(palette.subtleSurface)
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GeminiSparkleIcon(size = 18.dp)

            Spacer(modifier = Modifier.width(10.dp))

            Box(modifier = Modifier.weight(1f)) {
                if (searchQuery.isEmpty()) {
                    Text(
                        text = "搜索话题或标签",
                        fontSize = 14.5.sp,
                        color = palette.inkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = searchQuery,
                    onValueChange = onSearchChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        fontSize = 14.5.sp,
                        color = palette.inkPrimary
                    ),
                    cursorBrush = SolidColor(palette.accent),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (searchQuery.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .clickable { onSearchChange("") },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "清除",
                        modifier = Modifier.size(16.dp),
                        tint = palette.inkSecondary
                    )
                }
            }

            // 内嵌轻量排序切换器
            Row(
                modifier = Modifier
                    .height(32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(palette.cardSurface.copy(alpha = 0.85f))
                    .padding(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FeedSortFilter.entries.forEach { sort ->
                    val selected = currentSort == sort
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(14.dp))
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
                            fontSize = 11.5.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (selected) palette.onTonalPill else palette.inkSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            // 右侧 Google Account 标志性四色环微缩头像（点击直达账号中心）
            GoogleAccountRingAvatar(
                letter = currentUser?.displayName?.takeIf { it.isNotBlank() }
                    ?: currentUser?.username
                    ?: "L",
                avatarUrl = currentUser?.avatarTemplate,
                avatarSize = 34.dp,
                strokeWidth = 2.dp,
                onClick = onAvatarClick
            )
        }

        // 游客模式温馨提醒胶囊条
        if (!isAuthorized) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.tonalPill.copy(alpha = 0.65f))
                    .clickable(onClick = onRequestLogin)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GeminiSparkleIcon(size = 14.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "当前为游客预览，浏览不计入 Connect 连登",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.onTonalPill,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "立即登录",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.accent
                )
            }
        }

        // 2. Material 3 Tonal Filter Chips（无下划线，纯净胶囊筛选）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GoogleM3FilterPill(
                label = "全部",
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

        if (isLoading) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp)),
                color = palette.accent,
                trackColor = Color.Transparent
            )
        }

        // 3. 话题流（圆角置顶胶囊卡 + Google Discover 风格无边框圆角卡片）
        if (topics.isEmpty() && !isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = errorMessage ?: "暂无相关话题",
                        fontSize = 14.5.sp,
                        color = palette.inkSecondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(palette.tonalPill)
                            .clickable { onRefresh() }
                            .padding(horizontal = 18.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "重新加载",
                            fontSize = 13.5.sp,
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
                    bottom = 20.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 置顶公告容器（Gemini Tonal Banner 风格）
                if (pinnedTopics.isNotEmpty()) {
                    item(key = "pinned_banner_group") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(20.dp))
                                .background(palette.subtleSurface)
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            pinnedTopics.take(2).forEach { pinned ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { onSelectTopic(pinned) }
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(palette.tonalPill)
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "置顶",
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = palette.onTonalPill
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(9.dp))
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
                                        modifier = Modifier.size(15.dp),
                                        tint = palette.inkTertiary
                                    )
                                }
                            }
                        }
                    }
                }

                // 社区话题卡片流（无生硬分割线，采用纯白圆角卡片色阶对比）
                itemsIndexed(regularTopics, key = { _, item -> item.id }) { _, topic ->
                    CommunityTopicItem(
                        topic = topic,
                        isSelected = topic.id == selectedTopicId,
                        enableAnimations = enableAnimations,
                        onClick = { onSelectTopic(topic) }
                    )
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
            .height(34.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(if (selected) palette.tonalPill else palette.cardSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (dotColor != null) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) palette.onTonalPill else palette.inkSecondary
        )
    }
}

/**
 * Google Discover / Gemini 风格圆角话题卡片：
 * - 采用 `22.dp` 圆角纯白表面悬浮于 `#F8FAFD` 画布之上，零生硬描边
 * - 顶栏：24dp 头像 + 作者名 + 时间 ····· 右侧柔和频道胶囊
 * - 中栏：16.5sp 高可读标题 + 可选摘要
 * - 底栏：Tonal 标签胶囊 + 白色描边叠放参与者头像 + 评论/阅读统计
 */
@Composable
private fun CommunityTopicItem(
    topic: TopicSummary,
    isSelected: Boolean,
    enableAnimations: Boolean,
    onClick: () -> Unit
) {
    val palette = LocalPalette.current
    val op = topic.originalPoster
    val authorDisplay = op.name?.takeIf { it.isNotBlank() } ?: op.username
    val catColor = remember(topic.categoryColorHex) {
        LinuxDoSiteRules.parseHexColor(topic.categoryColorHex)
    }
    val cardBg = if (isSelected) palette.tonalPill.copy(alpha = 0.45f) else palette.cardSurface

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(cardBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp)
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
                size = 24.dp,
                enableAnimations = enableAnimations
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = authorDisplay,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
                color = palette.inkPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (topic.lastPostedAt.isNotBlank()) {
                Text(
                    text = " · ${topic.lastPostedAt}",
                    fontSize = 12.5.sp,
                    color = palette.inkTertiary,
                    maxLines = 1
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(catColor.copy(alpha = 0.11f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(5.5.dp)
                        .clip(CircleShape)
                        .background(catColor)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = topic.categoryName,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = catColor
                )
            }
        }

        Spacer(modifier = Modifier.height(9.dp))

        // 第二行：标题
        Text(
            text = topic.title,
            fontSize = 16.5.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 23.5.sp,
            letterSpacing = (-0.2).sp,
            color = palette.inkPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        // 若存在摘要则展示单行预览
        val cleanExcerpt = topic.excerpt?.trim()?.takeIf { it.isNotEmpty() && it != topic.title }
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

        Spacer(modifier = Modifier.height(11.dp))

        // 第三行：Tonal 标签胶囊 + 参与者微缩头像叠放 + 评论/阅读数
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (topic.tags.isNotEmpty()) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    topic.tags.take(3).forEach { tag ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(palette.canvas)
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "#$tag",
                                fontSize = 11.5.sp,
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
            val participants = topic.participantAvatars.take(3)
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
                                size = 17.dp,
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
                fontSize = 12.sp,
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
    onInspectBadge: (GrantedBadge) -> Unit
) {
    val palette = LocalPalette.current

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

    var isBookmarked by remember(summary.id) { mutableStateOf(false) }
    val catColor = remember(summary.categoryColorHex) {
        LinuxDoSiteRules.parseHexColor(summary.categoryColorHex)
    }
    val op = summary.originalPoster
    val opName = op.name?.takeIf { it.isNotBlank() } ?: op.username

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.canvas)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        // 1. Gemini 风格无分割线 Tonal 顶栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
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
            } else {
                Spacer(modifier = Modifier.width(4.dp))
            }

            SmartAvatar(
                username = op.username,
                avatarTemplate = op.avatarTemplate,
                animatedAvatar = op.animatedAvatar,
                primaryGroupName = op.primaryGroupName,
                size = 26.dp,
                enableAnimations = enableAnimations
            )

            Spacer(modifier = Modifier.width(9.dp))

            Text(
                text = opName,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.inkPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.width(8.dp))

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(catColor.copy(alpha = 0.12f))
                    .padding(horizontal = 8.dp, vertical = 2.5.dp)
            ) {
                Text(
                    text = summary.categoryName,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = catColor
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            TonalCircleIconButton(
                icon = if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                contentDescription = "收藏",
                tint = if (isBookmarked) palette.accent else palette.inkSecondary,
                onClick = { isBookmarked = !isBookmarked }
            )
            Spacer(modifier = Modifier.width(6.dp))
            TonalCircleIconButton(
                icon = Icons.Default.Refresh,
                contentDescription = "刷新",
                tint = palette.inkSecondary,
                onClick = onRefresh
            )
        }

        if (isLoading) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp)),
                color = palette.accent,
                trackColor = Color.Transparent
            )
        }

        val posts = detail?.posts.orEmpty()
        val readerListState = rememberLazyListState()
        val scope = rememberCoroutineScope()

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
                        .clip(RoundedCornerShape(24.dp))
                        .background(palette.cardSurface)
                        .padding(horizontal = 18.dp, vertical = 18.dp)
                ) {
                    Text(
                        text = detail?.title ?: summary.title,
                        fontSize = 20.5.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 29.sp,
                        letterSpacing = (-0.3).sp,
                        color = palette.inkPrimary
                    )

                    if (summary.tags.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            summary.tags.forEach { tag ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(palette.tonalPill.copy(alpha = 0.55f))
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = "#$tag",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = palette.onTonalPill
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    val replies = maxOf(0, maxOf(summary.postsCount, posts.size) - 1)
                    Text(
                        text = "$replies 条回复 · ${summary.views} 次阅读",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = palette.inkTertiary
                    )
                }
            }

            // 楼层卡片列表
            itemsIndexed(posts, key = { _, post -> post.id }) { _, post ->
                CommunityPostFloor(
                    post = post,
                    opUsername = op.username,
                    enableAnimations = enableAnimations,
                    onInspectBadge = onInspectBadge
                )
            }
        }

        // 底部 Gemini 标志性悬浮胶囊输入栏（Floating Composer Pill）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.canvas)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(RoundedCornerShape(25.dp))
                    .background(palette.subtleSurface)
                    .padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GeminiSparkleIcon(size = 16.dp)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "参与讨论，输入你的见解...",
                    fontSize = 14.sp,
                    color = palette.inkSecondary,
                    modifier = Modifier.weight(1f)
                )

                // 楼层胶囊指示器
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(palette.cardSurface)
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = "${posts.size} / ${maxOf(summary.postsCount, posts.size)} 楼",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.inkSecondary
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                // 发送圆形按钮
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(palette.tonalPill),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "发送",
                        modifier = Modifier.size(16.dp),
                        tint = palette.onTonalPill
                    )
                }
            }
        }
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
            .size(36.dp)
            .clip(CircleShape)
            .background(palette.subtleSurface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(18.dp),
            tint = tint
        )
    }
}

/**
 * Gemini 风格单层楼卡片（支持楼主标识、被回复楼层提示、16dp 圆角图片渲染、代码块一键复制）
 */
@Composable
private fun CommunityPostFloor(
    post: PostItemModel,
    opUsername: String,
    enableAnimations: Boolean,
    onInspectBadge: (GrantedBadge) -> Unit
) {
    val palette = LocalPalette.current
    var liked by remember(post.id) { mutableStateOf(false) }
    var likeCount by remember(post.id) { mutableStateOf(post.likeCount) }
    val isOp = post.isTopicOwner || post.username.equals(opUsername, ignoreCase = true)

    val astNodes = remember(post.id, post.cookedHtml) {
        DiscourseAstParser.parseOrGetCached(post.id, post.cookedHtml)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(palette.cardSurface)
            .padding(horizontal = 18.dp, vertical = 18.dp)
    ) {
        // 1. 楼层头部：头像 + 昵称 + 楼主标签 + 头衔 + 徽章 + 楼号胶囊
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SmartAvatar(
                username = post.username,
                avatarTemplate = post.avatarTemplate,
                animatedAvatar = post.animatedAvatar,
                primaryGroupName = post.primaryGroupName,
                flair = post.flair,
                size = 38.dp,
                enableAnimations = enableAnimations
            )

            Spacer(modifier = Modifier.width(11.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = post.displayName?.takeIf { it.isNotBlank() } ?: post.username,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.inkPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (isOp) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(palette.tonalPill)
                                .padding(horizontal = 6.dp, vertical = 1.5.dp)
                        ) {
                            Text(
                                text = "楼主",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.onTonalPill
                            )
                        }
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
                        fontSize = 12.sp,
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
                    .clip(RoundedCornerShape(10.dp))
                    .background(palette.canvas)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "#${post.postNumber}",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.inkSecondary
                )
            }
        }

        // 被回复楼层提示
        if (post.replyToPostNumber != null && post.replyToPostNumber > 0) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.subtleSurface)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "回复 #${post.replyToPostNumber}" +
                        (post.replyToUsername?.let { " @$it" } ?: ""),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.onTonalPill
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 2. AST 正文块渲染（含 16dp 圆角图片与 Gemini 风格代码块）
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            astNodes.forEach { node ->
                when (node) {
                    is PostBlockNode.Heading -> {
                        Text(
                            text = node.text,
                            fontSize = if (node.level <= 2) 17.5.sp else 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = 25.sp,
                            color = palette.inkPrimary
                        )
                    }

                    is PostBlockNode.Paragraph -> {
                        Text(
                            text = node.text,
                            fontSize = 15.5.sp,
                            lineHeight = 25.5.sp,
                            color = palette.inkPrimary
                        )
                    }

                    is PostBlockNode.QuoteBlock -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(palette.canvas)
                                .padding(14.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.5.dp)
                                    .height(36.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(palette.accent)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                if (!node.username.isNullOrBlank()) {
                                    Text(
                                        text = "@${node.username}",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = palette.accent
                                    )
                                    Spacer(modifier = Modifier.height(3.dp))
                                }
                                Text(
                                    text = node.quotedText,
                                    fontSize = 14.sp,
                                    lineHeight = 21.5.sp,
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
                                .clip(RoundedCornerShape(16.dp))
                                .background(palette.canvas)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(palette.subtleSurface)
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = node.language.ifBlank { "code" },
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Monospace,
                                    color = palette.inkSecondary,
                                    modifier = Modifier.weight(1f)
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (copied) palette.tonalPill else palette.cardSurface)
                                        .clickable { copied = true }
                                        .padding(horizontal = 10.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = if (copied) "已复制" else "复制代码",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (copied) palette.onTonalPill else palette.accent
                                    )
                                }
                            }
                            Text(
                                text = node.code,
                                fontSize = 13.sp,
                                lineHeight = 19.5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = palette.inkPrimary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(14.dp)
                            )
                        }
                    }

                    is PostBlockNode.BulletList -> {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            node.items.forEachIndexed { idx, item ->
                                Row(verticalAlignment = Alignment.Top) {
                                    Text(
                                        text = if (node.ordered) "${idx + 1}." else "•",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = palette.accent,
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                    Text(
                                        text = item,
                                        fontSize = 15.sp,
                                        lineHeight = 23.5.sp,
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
                                .clip(RoundedCornerShape(14.dp))
                                .background(palette.subtleSurface)
                                .clickable { expanded = !expanded }
                                .padding(14.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = node.title,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = palette.inkPrimary,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = if (expanded) "收起" else "展开",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = palette.accent
                                )
                            }
                            AnimatedVisibility(visible = expanded && node.innerText.isNotBlank()) {
                                Column {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = node.innerText,
                                        fontSize = 14.sp,
                                        lineHeight = 21.5.sp,
                                        color = palette.inkSecondary
                                    )
                                }
                            }
                        }
                    }

                    is PostBlockNode.ImageGallery -> {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            node.urls.forEach { imageUrl ->
                                AsyncImage(
                                    model = imageUrl,
                                    contentDescription = "帖子配图",
                                    contentScale = ContentScale.FillWidth,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 120.dp, max = 380.dp)
                                        .clip(RoundedCornerShape(16.dp))
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

        Spacer(modifier = Modifier.height(16.dp))

        // 3. 楼层底部互动栏（Google Tonal Pill 胶囊按钮）
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (liked) palette.tonalPill else palette.subtleSurface)
                    .clickable {
                        liked = !liked
                        likeCount += if (liked) 1 else -1
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (liked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                    contentDescription = "点赞",
                    modifier = Modifier.size(14.dp),
                    tint = if (liked) palette.onTonalPill else palette.inkSecondary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (likeCount > 0) "赞 $likeCount" else "赞",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (liked) palette.onTonalPill else palette.inkSecondary
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(palette.canvas)
                    .clickable { }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "回复",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.inkSecondary
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
    onSelectTopic: (TopicSummary) -> Unit
) {
    val palette = LocalPalette.current
    val categoryPairs = remember { LinuxDoRepository.categories.chunked(2) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.canvas)
            .windowInsetsPadding(WindowInsets.statusBars),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
                Text(
                    text = "探索社区",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.4).sp,
                    color = palette.inkPrimary
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "实时热门讨论与全站主题频道",
                    fontSize = 13.5.sp,
                    color = palette.inkSecondary
                )
            }
        }

        // 1. Gemini 风格社区实时热议榜
        if (hotTopics.isNotEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(palette.cardSurface)
                        .padding(vertical = 16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GeminiSparkleIcon(size = 18.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "正在热议",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.inkPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(palette.tonalPill)
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "实时趋势",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.onTonalPill
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    hotTopics.forEachIndexed { idx, topic ->
                        val badgeBg = when (idx) {
                            0 -> Color(0xFFFCE8E6)
                            1 -> Color(0xFFFEF7E0)
                            2 -> Color(0xFFE8F0FE)
                            else -> palette.subtleSurface
                        }
                        val badgeFg = when (idx) {
                            0 -> Color(0xFFC5221F)
                            1 -> Color(0xFFB06000)
                            2 -> Color(0xFF0B57D0)
                            else -> palette.inkSecondary
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectTopic(topic) }
                                .padding(horizontal = 18.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(badgeBg),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${idx + 1}",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = badgeFg
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = topic.title,
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = palette.inkPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(palette.canvas)
                                    .padding(horizontal = 7.dp, vertical = 2.5.dp)
                            ) {
                                Text(
                                    text = "${maxOf(0, topic.postsCount - 1)} 评",
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

        // 2. 双列 Google Bento 频道网格
        item {
            Text(
                text = "全站频道",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = palette.inkPrimary,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
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
            .clip(RoundedCornerShape(22.dp))
            .background(palette.cardSurface)
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(catColor.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(catColor)
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = palette.inkTertiary
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = category.name,
            fontSize = 15.5.sp,
            fontWeight = FontWeight.Bold,
            color = palette.inkPrimary
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = category.description,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = palette.inkSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Google Account / Google One 风格「个人与账号中心」：
 * - 居中式 Google One 四色环绕头像 + 账号标题 + Tonal 胶囊操作按钮
 * - Connect 与活跃度 Bento 四宫格数据卡（内嵌圆角瓷片，实时展示今日连登、浏览话题、已读楼层、阅读时长）
 * - Google Settings 标志性分段圆角列表组（外侧 `22.dp` 圆角、内侧 `4.dp` 微圆角、行间距 `2.dp`，无分割线）
 */
@Composable
private fun CommunityProfileScreen(
    isDarkTheme: Boolean,
    enableAnimations: Boolean,
    isAuthorized: Boolean,
    currentUser: AuthenticatedUserProfile?,
    onToggleDark: () -> Unit,
    onToggleAnimations: () -> Unit,
    onOpenConnectSheet: () -> Unit,
    onOpenNetworkSheet: () -> Unit,
    onOpenAuthSheet: () -> Unit,
    onPrimaryWebLogin: () -> Unit,
    onLogout: () -> Unit
) {
    val palette = LocalPalette.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.canvas)
            .windowInsetsPadding(WindowInsets.statusBars),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // 1. Google Account 居中式头部
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                GoogleAccountRingAvatar(
                    letter = currentUser?.displayName?.takeIf { it.isNotBlank() }
                        ?: currentUser?.username
                        ?: "L",
                    avatarUrl = currentUser?.avatarTemplate,
                    avatarSize = 80.dp,
                    strokeWidth = 3.2.dp,
                    onClick = onOpenAuthSheet
                )

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = when {
                        currentUser != null -> currentUser.displayName.ifBlank { currentUser.username }
                        isAuthorized -> "Linux.do 已登录账号"
                        else -> "未登录（游客预览）"
                    },
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.3).sp,
                    color = palette.inkPrimary
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = when {
                        currentUser != null -> "@${currentUser.username} · Lv.${currentUser.trustLevel} 信任等级 · 连登同步已开启"
                        isAuthorized -> "连登打卡与阅读进度已开启实时同步"
                        else -> "登录以开启 Connect 连续登录打卡与阅读时长同步"
                    },
                    fontSize = 13.5.sp,
                    color = palette.inkSecondary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Google Account 标志性胶囊管理按钮
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (isAuthorized) palette.tonalPill else palette.accent)
                            .clickable(
                                onClick = if (isAuthorized) onOpenAuthSheet else onPrimaryWebLogin
                            )
                            .padding(horizontal = 20.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isAuthorized) "账号与登录设置" else "立即一键登录",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isAuthorized) palette.onTonalPill else Color.White
                        )
                    }

                    if (isAuthorized) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(palette.subtleSurface)
                                .clickable(onClick = onLogout)
                                .padding(horizontal = 16.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "退出",
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = palette.inkSecondary
                            )
                        }
                    }
                }
            }
        }

        // 2. Google One 风格 Connect 活跃度与连登看板 Bento 卡
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(palette.cardSurface)
                    .padding(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GeminiSparkleIcon(size = 18.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Connect 活跃与连登状态",
                        fontSize = 15.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.inkPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(palette.successContainer)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = if (UserPresenceService.todayVisitConfirmed) "今日已打卡" else "实时追踪",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.onSuccessContainer
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 4 列内嵌式 Tonal 指标瓷片
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GoogleBentoMetricTile(
                        value = if (UserPresenceService.todayVisitConfirmed) "已同步" else "待同步",
                        label = "今日连登",
                        modifier = Modifier.weight(1f)
                    )
                    GoogleBentoMetricTile(
                        value = "${ScreenTrackEngine.sessionTopicsEntered}",
                        label = "浏览话题",
                        modifier = Modifier.weight(1f)
                    )
                    GoogleBentoMetricTile(
                        value = "${ScreenTrackEngine.sessionPostsRead}",
                        label = "已读楼层",
                        modifier = Modifier.weight(1f)
                    )
                    GoogleBentoMetricTile(
                        value = "${ScreenTrackEngine.sessionReadingSeconds}s",
                        label = "阅读时长",
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Connect 周期进度入口胶囊条
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(palette.subtleSurface)
                        .clickable(onClick = onOpenConnectSheet)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Connect 信任等级与 100 天周期要求",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = palette.inkPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "查看进度",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.accent
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = palette.accent
                    )
                }
            }
        }

        // 3. Google Settings 标志性分段圆角列表组：阅读与显示
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "阅读与显示",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.accent,
                    modifier = Modifier.padding(start = 8.dp, bottom = 6.dp)
                )

                GoogleSegmentedSwitchTile(
                    icon = if (isDarkTheme) Icons.Default.DarkMode else Icons.Default.LightMode,
                    title = "深色主题",
                    subtitle = "遵循 Google Material 3 低眩光暗色对比度",
                    checked = isDarkTheme,
                    shape = RoundedCornerShape(
                        topStart = 22.dp,
                        topEnd = 22.dp,
                        bottomStart = 4.dp,
                        bottomEnd = 4.dp
                    ),
                    onCheckedChange = { onToggleDark() }
                )

                GoogleSegmentedSwitchTile(
                    icon = Icons.Default.AutoAwesome,
                    title = "动态头像与徽章光效",
                    subtitle = "自动渲染 GIF 头像与特殊用户组流光头衔",
                    checked = enableAnimations,
                    shape = RoundedCornerShape(
                        topStart = 4.dp,
                        topEnd = 4.dp,
                        bottomStart = 22.dp,
                        bottomEnd = 22.dp
                    ),
                    onCheckedChange = { onToggleAnimations() }
                )
            }
        }

        // 4. Google Settings 标志性分段圆角列表组：连接与关于
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "连接与系统",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.accent,
                    modifier = Modifier.padding(start = 8.dp, bottom = 6.dp)
                )

                GoogleSegmentedActionTile(
                    icon = Icons.Default.Speed,
                    title = "网络加速与节点优选",
                    value = "智能优选",
                    shape = RoundedCornerShape(
                        topStart = 22.dp,
                        topEnd = 22.dp,
                        bottomStart = 4.dp,
                        bottomEnd = 4.dp
                    ),
                    onClick = onOpenNetworkSheet
                )

                GoogleSegmentedActionTile(
                    icon = Icons.Default.Info,
                    title = "应用版本",
                    value = "LinDo 1.0.0",
                    showChevron = false,
                    shape = RoundedCornerShape(
                        topStart = 4.dp,
                        topEnd = 4.dp,
                        bottomStart = 22.dp,
                        bottomEnd = 22.dp
                    ),
                    onClick = {}
                )
            }
        }
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
 * Google Account 风格账号登录与切换抽屉（支持双主通道一键登录）
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
                .padding(horizontal = 24.dp, vertical = 12.dp)
        ) {
            Text(
                text = if (isAuthorized) "Linux.do 账号会话" else "登录 Linux.do 账号",
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = palette.inkPrimary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (isAuthorized && currentUser != null) {
                    "当前已登录 @${currentUser.username}（Lv.${currentUser.trustLevel}），Connect 连登与阅读时长自动同步中。"
                } else {
                    "请选择最顺手的方式完成验证，登录后自动同步 Connect 连登、阅读进度与受保护分区。"
                },
                fontSize = 13.5.sp,
                lineHeight = 20.sp,
                color = palette.inkSecondary
            )

            Spacer(modifier = Modifier.height(18.dp))

            // 方式一（主力推荐）：内置一键登录（支持密码 / GitHub / Passkey / 2FA）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(palette.accent)
                    .clickable(onClick = onPrimaryWebLogin)
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                GeminiSparkleIcon(size = 18.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isAuthorized) "重新验证或切换账号" else "账号一键登录（密码 / GitHub / Passkey）",
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 方式二（官方浏览器授权跳回）：通过系统浏览器一键授权
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(RoundedCornerShape(25.dp))
                    .background(palette.tonalPill)
                    .clickable(onClick = onBrowserOneClickAuth)
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "通过系统浏览器一键授权",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.onTonalPill
                )
            }

            if (isAuthorized) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .clip(RoundedCornerShape(23.dp))
                        .background(palette.subtleSurface)
                        .clickable(onClick = onLogout)
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "退出当前账号并返回登录页",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = palette.inkSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(26.dp))
        }
    }
}

/**
 * Google Account / Gemini 风格首屏欢迎与登录门禁页（Welcome & Login Gate）：
 * - 启动即达，确保用户先完成身份挂载再进入浏览，避免因未登录丢失 Connect 连续登录与阅读时长
 * - 仅保留最高频、最顺手的两种登录方式：
 *   1. 账号一键登录（内置安全登录，支持密码 / GitHub / Passkey / 2FA，自动接管 Cookie 会话）
 *   2. 通过系统浏览器一键授权（复用系统浏览器已登录状态，一键点击「批准」自动跳回）
 * - 底部提供轻量级「暂不登录，仅以游客身份预览公开内容」入口
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
            .background(palette.canvas)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.weight(0.8f))

        // 1. 顶部 Google Account 四色环绕徽标 + Gemini 品牌标识
        GoogleAccountRingAvatar(
            letter = "L",
            avatarSize = 86.dp,
            strokeWidth = 3.5.dp
        )

        Spacer(modifier = Modifier.height(20.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            GeminiSparkleIcon(size = 20.dp)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "欢迎来到 LinDo",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp,
                color = palette.inkPrimary
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "真诚、友善、团结、专业的 Linux.do 社区客户端",
            fontSize = 14.sp,
            color = palette.inkSecondary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(26.dp))

        // 2. 登录后同步的 3 项核心权益卡片（Material 3 Tonal Card）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(palette.cardSurface)
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            WelcomeFeatureRow(
                badgeText = "连登",
                title = "Connect 100 天活跃与连登自动打卡",
                subtitle = "每次打开自动上报在线心跳，防止断签掉级"
            )
            WelcomeFeatureRow(
                badgeText = "阅读",
                title = "楼层阅读时长与进度精准同步",
                subtitle = "基于 Discourse Timings 协议实时记录已读帖子"
            )
            WelcomeFeatureRow(
                badgeText = "分区",
                title = "解锁 Lv.1 ~ Lv.3 专属频道与福利专区",
                subtitle = "完整访问受信任等级保护的话题、水龙与通知"
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // 3. 方式一（主力推荐）：内置一键安全登录胶囊按钮（Google Primary Blue `#0B57D0`）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .clip(RoundedCornerShape(27.dp))
                .background(palette.accent)
                .clickable(onClick = onPrimaryWebLogin)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            GeminiSparkleIcon(size = 18.dp)
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "账号一键登录（密码 / GitHub / Passkey）",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 4. 方式二（官方浏览器跳回）：系统浏览器一键授权胶囊按钮（Tonal Pill `#D3E3FD`）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(palette.tonalPill)
                .clickable(onClick = onBrowserOneClickAuth)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = "通过系统浏览器一键授权（已登录免输密码）",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.onTonalPill
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 5. 底部轻量级游客预览入口
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClick = onEnterGuestPreview)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "暂不登录，仅以游客身份预览公开内容",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = palette.inkTertiary
            )
        }
    }
}

@Composable
private fun WelcomeFeatureRow(
    badgeText: String,
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
                .size(40.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(palette.tonalPill),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = badgeText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = palette.onTonalPill
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.inkPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = palette.inkSecondary
            )
        }
    }
}
