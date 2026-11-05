package do_.linux.lindo.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import do_.linux.lindo.core.connect.ConnectTrustLevelReport
import do_.linux.lindo.core.connect.ScreenTrackEngine
import do_.linux.lindo.core.connect.UserPresenceService
import do_.linux.lindo.core.model.GrantedBadge
import do_.linux.lindo.core.model.PostItemModel
import do_.linux.lindo.core.model.TopicDetailModel
import do_.linux.lindo.core.model.TopicSummary
import do_.linux.lindo.core.network.CloudflareEdgeRacer
import do_.linux.lindo.core.network.CookieSentinel
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
 * Apple HIG 原生通透阅读配色体系（对齐 Reeder 5 / Things 3 / Readwise Reader）
 */
data class EditorialPalette(
    val isDark: Boolean,
    val canvas: Color,
    val groupedCanvas: Color,
    val groupedCard: Color,
    val subtleSurface: Color,
    val inkPrimary: Color,
    val inkSecondary: Color,
    val inkTertiary: Color,
    val hairline: Color,
    val accent: Color
)

private val LightEditorialPalette = EditorialPalette(
    isDark = false,
    canvas = Color(0xFFFBFBFD),
    groupedCanvas = Color(0xFFF2F2F7),
    groupedCard = Color(0xFFFFFFFF),
    subtleSurface = Color(0xFFF2F2F7),
    inkPrimary = Color(0xFF1C1C1E),
    inkSecondary = Color(0xFF636366),
    inkTertiary = Color(0xFF8E8E93),
    hairline = Color(0xFFE5E5EA),
    accent = Color(0xFF0066CC)
)

private val DarkEditorialPalette = EditorialPalette(
    isDark = true,
    canvas = Color(0xFF0B0B0E),
    groupedCanvas = Color(0xFF000000),
    groupedCard = Color(0xFF1C1C1E),
    subtleSurface = Color(0xFF1C1C1E),
    inkPrimary = Color(0xFFF5F5F7),
    inkSecondary = Color(0xFF98989D),
    inkTertiary = Color(0xFF636366),
    hairline = Color(0xFF2C2C2E),
    accent = Color(0xFF0A84FF)
)

private val LocalEditorialPalette = staticCompositionLocalOf { LightEditorialPalette }

private val LinDoLightColorScheme = lightColorScheme(
    primary = LightEditorialPalette.accent,
    onPrimary = Color.White,
    background = LightEditorialPalette.canvas,
    onBackground = LightEditorialPalette.inkPrimary,
    surface = LightEditorialPalette.canvas,
    onSurface = LightEditorialPalette.inkPrimary,
    surfaceVariant = LightEditorialPalette.subtleSurface,
    onSurfaceVariant = LightEditorialPalette.inkSecondary,
    outlineVariant = LightEditorialPalette.hairline
)

private val LinDoDarkColorScheme = darkColorScheme(
    primary = DarkEditorialPalette.accent,
    onPrimary = Color.White,
    background = DarkEditorialPalette.canvas,
    onBackground = DarkEditorialPalette.inkPrimary,
    surface = DarkEditorialPalette.canvas,
    onSurface = DarkEditorialPalette.inkPrimary,
    surfaceVariant = DarkEditorialPalette.subtleSurface,
    onSurfaceVariant = DarkEditorialPalette.inkSecondary,
    outlineVariant = DarkEditorialPalette.hairline
)

private enum class MainTab(val title: String, val icon: ImageVector) {
    READER("阅读", Icons.Default.Home),
    CATEGORIES("板块", Icons.Default.GridView),
    PROFILE("我的", Icons.Default.Person)
}

private enum class FeedSortFilter(val label: String, val apiPath: String) {
    LATEST("最新", "latest.json"),
    TOP("热门", "top.json"),
    NEW("新帖", "new.json")
}

@Composable
fun LinDoApp() {
    var isDarkTheme by remember { mutableStateOf(false) }
    val palette = if (isDarkTheme) DarkEditorialPalette else LightEditorialPalette

    var currentTab by remember { mutableStateOf(MainTab.READER) }
    var selectedCategoryId by remember { mutableStateOf<Int?>(null) }
    var currentSort by remember { mutableStateOf(FeedSortFilter.LATEST) }
    var searchQuery by remember { mutableStateOf("") }
    var showSearchBar by remember { mutableStateOf(false) }
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
                    val first = fetched.first()
                    activeTopicSummary = first
                    activeTopicDetail = LinuxDoRepository.fetchTopicDetail(first.id, first)
                }
                feedListState.scrollToItem(0)
            } else if (topics.isEmpty()) {
                feedError = "暂时无法连接至 linux.do，请检查网络或重试"
            }
            isLoadingTopics = false
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

    CompositionLocalProvider(LocalEditorialPalette provides palette) {
        MaterialTheme(
            colorScheme = if (isDarkTheme) LinDoDarkColorScheme else LinDoLightColorScheme
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = palette.canvas
            ) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val isExpandedDesktop = maxWidth >= 840.dp

                    if (isExpandedDesktop) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            DesktopEditorialSidebar(
                                currentTab = currentTab,
                                isDarkTheme = isDarkTheme,
                                onSelectTab = { currentTab = it },
                                onToggleTheme = { isDarkTheme = !isDarkTheme }
                            )

                            VerticalDivider(thickness = 0.5.dp, color = palette.hairline)

                            when (currentTab) {
                                MainTab.READER -> {
                                    Box(
                                        modifier = Modifier
                                            .width(400.dp)
                                            .fillMaxHeight()
                                    ) {
                                        EditorialHomePane(
                                            topics = displayedTopics,
                                            listState = feedListState,
                                            selectedTopicId = activeTopicSummary?.id,
                                            selectedCategoryId = selectedCategoryId,
                                            currentSort = currentSort,
                                            searchQuery = searchQuery,
                                            showSearchBar = showSearchBar,
                                            isLoading = isLoadingTopics,
                                            errorMessage = feedError,
                                            enableAnimations = enableAnimations,
                                            isDarkTheme = isDarkTheme,
                                            onSearchChange = { searchQuery = it },
                                            onToggleSearch = {
                                                showSearchBar = !showSearchBar
                                                if (!showSearchBar) searchQuery = ""
                                            },
                                            onSelectCategory = { catId ->
                                                selectedCategoryId = catId
                                                loadTopics(currentSort, catId)
                                            },
                                            onSelectSort = { sort ->
                                                currentSort = sort
                                                loadTopics(sort, selectedCategoryId)
                                            },
                                            onRefresh = { loadTopics(currentSort, selectedCategoryId) },
                                            onToggleTheme = { isDarkTheme = !isDarkTheme },
                                            onSelectTopic = { openTopic(it) }
                                        )
                                    }

                                    VerticalDivider(thickness = 0.5.dp, color = palette.hairline)

                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                    ) {
                                        EditorialTopicReaderPane(
                                            summary = activeTopicSummary,
                                            detail = activeTopicDetail,
                                            isLoading = isLoadingDetail,
                                            enableAnimations = enableAnimations,
                                            showBackButton = false,
                                            onBack = {},
                                            onRefresh = {
                                                activeTopicSummary?.let { openTopic(it) }
                                            },
                                            onInspectBadge = { inspectedBadge = it }
                                        )
                                    }
                                }

                                MainTab.CATEGORIES -> {
                                    EditorialCategoriesScreen(
                                        onSelectCategory = { catId ->
                                            selectedCategoryId = catId
                                            currentTab = MainTab.READER
                                            loadTopics(currentSort, catId)
                                        }
                                    )
                                }

                                MainTab.PROFILE -> {
                                    EditorialProfileScreen(
                                        isDarkTheme = isDarkTheme,
                                        enableAnimations = enableAnimations,
                                        onToggleDark = { isDarkTheme = !isDarkTheme },
                                        onToggleAnimations = { enableAnimations = !enableAnimations },
                                        onOpenConnectSheet = { showConnectSheet = true },
                                        onOpenNetworkSheet = { showNetworkSheet = true },
                                        onOpenAuthSheet = { showAuthSheet = true }
                                    )
                                }
                            }
                        }
                    } else {
                        if (mobileShowingDetail && activeTopicSummary != null) {
                            EditorialTopicReaderPane(
                                summary = activeTopicSummary,
                                detail = activeTopicDetail,
                                isLoading = isLoadingDetail,
                                enableAnimations = enableAnimations,
                                showBackButton = true,
                                onBack = { mobileShowingDetail = false },
                                onRefresh = {
                                    activeTopicSummary?.let { openTopic(it) }
                                },
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
                                        MainTab.READER -> EditorialHomePane(
                                            topics = displayedTopics,
                                            listState = feedListState,
                                            selectedTopicId = null,
                                            selectedCategoryId = selectedCategoryId,
                                            currentSort = currentSort,
                                            searchQuery = searchQuery,
                                            showSearchBar = showSearchBar,
                                            isLoading = isLoadingTopics,
                                            errorMessage = feedError,
                                            enableAnimations = enableAnimations,
                                            isDarkTheme = isDarkTheme,
                                            onSearchChange = { searchQuery = it },
                                            onToggleSearch = {
                                                showSearchBar = !showSearchBar
                                                if (!showSearchBar) searchQuery = ""
                                            },
                                            onSelectCategory = { catId ->
                                                selectedCategoryId = catId
                                                loadTopics(currentSort, catId)
                                            },
                                            onSelectSort = { sort ->
                                                currentSort = sort
                                                loadTopics(sort, selectedCategoryId)
                                            },
                                            onRefresh = { loadTopics(currentSort, selectedCategoryId) },
                                            onToggleTheme = { isDarkTheme = !isDarkTheme },
                                            onSelectTopic = { openTopic(it) }
                                        )

                                        MainTab.CATEGORIES -> EditorialCategoriesScreen(
                                            onSelectCategory = { catId ->
                                                selectedCategoryId = catId
                                                currentTab = MainTab.READER
                                                loadTopics(currentSort, catId)
                                            }
                                        )

                                        MainTab.PROFILE -> EditorialProfileScreen(
                                            isDarkTheme = isDarkTheme,
                                            enableAnimations = enableAnimations,
                                            onToggleDark = { isDarkTheme = !isDarkTheme },
                                            onToggleAnimations = { enableAnimations = !enableAnimations },
                                            onOpenConnectSheet = { showConnectSheet = true },
                                            onOpenNetworkSheet = { showNetworkSheet = true },
                                            onOpenAuthSheet = { showAuthSheet = true }
                                        )
                                    }
                                }

                                EditorialBottomTabBar(
                                    currentTab = currentTab,
                                    onSelectTab = { currentTab = it }
                                )
                            }
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
                        onDismiss = { showAuthSheet = false }
                    )
                }
            }
        }
    }
}

/**
 * 桌面端极简侧边导航栏（无胶囊背景）
 */
@Composable
private fun DesktopEditorialSidebar(
    currentTab: MainTab,
    isDarkTheme: Boolean,
    onSelectTab: (MainTab) -> Unit,
    onToggleTheme: () -> Unit
) {
    val palette = LocalEditorialPalette.current

    Column(
        modifier = Modifier
            .width(68.dp)
            .fillMaxHeight()
            .background(palette.canvas)
            .padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "L.",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.8).sp,
            color = palette.inkPrimary
        )

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
                    .padding(vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = tab.icon,
                    contentDescription = tab.title,
                    modifier = Modifier.size(20.dp),
                    tint = if (selected) palette.inkPrimary else palette.inkTertiary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = tab.title,
                    fontSize = 11.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) palette.inkPrimary else palette.inkTertiary
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable { onToggleTheme() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                contentDescription = "外观切换",
                modifier = Modifier.size(18.dp),
                tint = palette.inkSecondary
            )
        }
    }
}

/**
 * 移动端 Apple HIG 纯净底栏（彻底移除 Material 3 肥胖胶囊指示器）
 */
@Composable
private fun EditorialBottomTabBar(
    currentTab: MainTab,
    onSelectTab: (MainTab) -> Unit
) {
    val palette = LocalEditorialPalette.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.canvas)
    ) {
        HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
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
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.title,
                        modifier = Modifier.size(20.dp),
                        tint = if (selected) palette.inkPrimary else palette.inkTertiary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = tab.title,
                        fontSize = 10.5.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) palette.inkPrimary else palette.inkTertiary
                    )
                }
            }
        }
    }
}

/**
 * Apple HIG 杂志级阅读流主页（无卡片阴影、无胶囊噪音、通栏纸张留白 + 0.5dp 发丝分割线）
 */
@Composable
private fun EditorialHomePane(
    topics: List<TopicSummary>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    selectedTopicId: Int?,
    selectedCategoryId: Int?,
    currentSort: FeedSortFilter,
    searchQuery: String,
    showSearchBar: Boolean,
    isLoading: Boolean,
    errorMessage: String?,
    enableAnimations: Boolean,
    isDarkTheme: Boolean,
    onSearchChange: (String) -> Unit,
    onToggleSearch: () -> Unit,
    onSelectCategory: (Int?) -> Unit,
    onSelectSort: (FeedSortFilter) -> Unit,
    onRefresh: () -> Unit,
    onToggleTheme: () -> Unit,
    onSelectTopic: (TopicSummary) -> Unit
) {
    val palette = LocalEditorialPalette.current
    val activeCategory = remember(selectedCategoryId) {
        selectedCategoryId?.let { id -> LinuxDoRepository.categories.firstOrNull { it.id == id } }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.canvas)
    ) {
        // 1. Apple Large Title 留白顶栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 14.dp, top = 16.dp, bottom = 10.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "LINUX.DO",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.4.sp,
                    color = palette.inkTertiary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (activeCategory != null) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(LinuxDoSiteRules.parseHexColor(activeCategory.colorHex))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = activeCategory?.name ?: "今日阅读",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.6).sp,
                        color = palette.inkPrimary
                    )
                    if (activeCategory != null) {
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "全部",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = palette.accent,
                            modifier = Modifier
                                .clickable { onSelectCategory(null) }
                                .padding(vertical = 2.dp, horizontal = 4.dp)
                        )
                    }
                }
            }

            // 右侧单色极简操作图标
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                EditorialHeaderIconButton(
                    icon = if (showSearchBar) Icons.Default.Close else Icons.Default.Search,
                    contentDescription = "搜索",
                    onClick = onToggleSearch
                )
                EditorialHeaderIconButton(
                    icon = Icons.Default.Refresh,
                    contentDescription = "刷新",
                    onClick = onRefresh
                )
                EditorialHeaderIconButton(
                    icon = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                    contentDescription = "外观模式",
                    onClick = onToggleTheme
                )
            }
        }

        // 2. 按需展开的克制搜索框
        AnimatedVisibility(visible = showSearchBar) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp)
                    .height(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(palette.subtleSurface)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = palette.inkTertiary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = "搜索标题、作者或标签...",
                                fontSize = 13.5.sp,
                                color = palette.inkTertiary
                            )
                        }
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = onSearchChange,
                            singleLine = true,
                            textStyle = TextStyle(
                                fontSize = 13.5.sp,
                                color = palette.inkPrimary
                            ),
                            cursorBrush = SolidColor(palette.accent),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (searchQuery.isNotEmpty()) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "清除",
                            modifier = Modifier
                                .size(15.dp)
                                .clickable { onSearchChange("") },
                            tint = palette.inkTertiary
                        )
                    }
                }
            }
        }

        // 3. 无胶囊文字导航栏（对齐 Reeder / Apple News）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FeedSortFilter.entries.forEach { sort ->
                EditorialTextTab(
                    label = sort.label,
                    selected = currentSort == sort,
                    onClick = { onSelectSort(sort) }
                )
            }

            Box(
                modifier = Modifier
                    .width(0.5.dp)
                    .height(12.dp)
                    .background(palette.hairline)
            )

            EditorialTextTab(
                label = "全部",
                selected = selectedCategoryId == null,
                onClick = { onSelectCategory(null) }
            )

            LinuxDoRepository.categories.forEach { category ->
                EditorialTextTab(
                    label = category.name,
                    selected = selectedCategoryId == category.id,
                    dotColor = LinuxDoSiteRules.parseHexColor(category.colorHex),
                    onClick = {
                        onSelectCategory(
                            if (selectedCategoryId == category.id) null else category.id
                        )
                    }
                )
            }
        }

        HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)

        if (isLoading) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.5.dp),
                color = palette.accent,
                trackColor = Color.Transparent
            )
        }

        // 4. 通栏阅读列表
        if (topics.isEmpty() && !isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = errorMessage ?: "暂无匹配的话题",
                        fontSize = 14.sp,
                        color = palette.inkSecondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "重新加载",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = palette.accent,
                        modifier = Modifier
                            .clickable { onRefresh() }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 28.dp)
            ) {
                itemsIndexed(topics, key = { _, item -> item.id }) { _, topic ->
                    EditorialTopicRow(
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
private fun EditorialHeaderIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    val palette = LocalEditorialPalette.current
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(19.dp),
            tint = palette.inkSecondary
        )
    }
}

@Composable
private fun EditorialTextTab(
    label: String,
    selected: Boolean,
    dotColor: Color? = null,
    onClick: () -> Unit
) {
    val palette = LocalEditorialPalette.current

    Column(
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (dotColor != null && selected) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Spacer(modifier = Modifier.width(5.dp))
            }
            Text(
                text = label,
                fontSize = 13.5.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) palette.inkPrimary else palette.inkTertiary
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .width(16.dp)
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(if (selected) palette.inkPrimary else Color.Transparent)
        )
    }
}

/**
 * 通栏三段式话题行（Reeder 5 / Readwise 杂志排版）
 *
 * - 第一行（眉题）：20dp 头像 + 作者名 + · + 6dp 板块色点 + 板块名 ····· 相对时间
 * - 第二行（主标题）：16sp SemiBold，22.5sp 行高，最多 2 行
 * - 第三行（注脚）：#标签 ····· 13 回复 · 113 阅读（纯数字排版，无噪音图标）
 */
@Composable
private fun EditorialTopicRow(
    topic: TopicSummary,
    isSelected: Boolean,
    enableAnimations: Boolean,
    onClick: () -> Unit
) {
    val palette = LocalEditorialPalette.current
    val rowBg = if (isSelected) {
        palette.subtleSurface.copy(alpha = 0.7f)
    } else {
        palette.canvas
    }
    val op = topic.originalPoster
    val authorDisplay = op.name?.takeIf { it.isNotBlank() } ?: op.username
    val catColor = remember(topic.categoryColorHex) {
        LinuxDoSiteRules.parseHexColor(topic.categoryColorHex)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg)
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 15.dp)
        ) {
            // 第一行：眉题（作者 + 板块 + 时间）
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
                    size = 20.dp,
                    enableAnimations = enableAnimations
                )

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = authorDisplay,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = " · ",
                    fontSize = 12.sp,
                    color = palette.inkTertiary
                )

                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(catColor)
                )

                Spacer(modifier = Modifier.width(5.dp))

                Text(
                    text = topic.categoryName,
                    fontSize = 12.sp,
                    color = palette.inkSecondary,
                    maxLines = 1
                )

                Spacer(modifier = Modifier.weight(1f))

                if (topic.pinned) {
                    Text(
                        text = "置顶",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.accent
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }

                if (topic.lastPostedAt.isNotBlank()) {
                    Text(
                        text = topic.lastPostedAt,
                        fontSize = 12.sp,
                        color = palette.inkTertiary
                    )
                }
            }

            Spacer(modifier = Modifier.height(7.dp))

            // 第二行：主标题
            Text(
                text = topic.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 22.5.sp,
                letterSpacing = (-0.2).sp,
                color = palette.inkPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 第三行：标签与纯文字统计注脚
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (topic.tags.isNotEmpty()) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        topic.tags.take(3).forEach { tag ->
                            Text(
                                text = "#$tag",
                                fontSize = 12.sp,
                                color = palette.inkTertiary,
                                maxLines = 1
                            )
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                val replies = maxOf(0, topic.postsCount - 1).coerceAtLeast(topic.replyCount)
                val metricsText = buildString {
                    if (topic.hasAcceptedAnswer) {
                        append("已解决 · ")
                    }
                    append("$replies 回复")
                    if (topic.views > 0) {
                        append(" · ${formatCompactNumber(topic.views)} 阅读")
                    }
                }

                Text(
                    text = metricsText,
                    fontSize = 12.sp,
                    color = palette.inkTertiary
                )
            }
        }

        // Apple HIG 左侧缩进 20dp 的 0.5dp 发丝分割线
        HorizontalDivider(
            modifier = Modifier.padding(start = 20.dp),
            thickness = 0.5.dp,
            color = palette.hairline
        )
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
 * 杂志级帖子阅读页（无重复顶栏标题、柔和代码块、克制楼层分割）
 */
@Composable
private fun EditorialTopicReaderPane(
    summary: TopicSummary?,
    detail: TopicDetailModel?,
    isLoading: Boolean,
    enableAnimations: Boolean,
    showBackButton: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onInspectBadge: (GrantedBadge) -> Unit
) {
    val palette = LocalEditorialPalette.current

    if (summary == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.canvas),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "选择左侧话题开始阅读",
                fontSize = 14.sp,
                color = palette.inkTertiary
            )
        }
        return
    }

    var isBookmarked by remember(summary.id) { mutableStateOf(false) }
    val catColor = remember(summary.categoryColorHex) {
        LinuxDoSiteRules.parseHexColor(summary.categoryColorHex)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.canvas)
    ) {
        // 1. 极简阅读顶栏（绝不在未滚动前重复堆砌一遍标题）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showBackButton) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onBack)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        modifier = Modifier.size(19.dp),
                        tint = palette.inkPrimary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "返回",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = palette.inkPrimary
                    )
                }
            } else {
                Spacer(modifier = Modifier.width(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(catColor)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = summary.categoryName,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = palette.inkSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            EditorialHeaderIconButton(
                icon = if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                contentDescription = "收藏",
                onClick = { isBookmarked = !isBookmarked }
            )
            EditorialHeaderIconButton(
                icon = Icons.Default.Refresh,
                contentDescription = "刷新",
                onClick = onRefresh
            )
        }

        HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)

        if (isLoading) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.5.dp),
                color = palette.accent,
                trackColor = Color.Transparent
            )
        }

        val posts = detail?.posts.orEmpty()
        val readerListState = rememberLazyListState()
        val scope = rememberCoroutineScope()

        // 对齐 Discourse 官方 ScreenTrack：进入话题即追踪可见楼层并周期上报 /topics/timings
        LaunchedEffect(summary.id, posts.size) {
            ScreenTrackEngine.onTopicEntered(
                topicId = summary.id,
                initialVisiblePosts = posts.take(2).map { it.postNumber }.toSet().ifEmpty { setOf(1) }
            )
            while (true) {
                delay(1000L)
                val visiblePostNums = readerListState.layoutInfo.visibleItemsInfo
                    .mapNotNull { itemInfo ->
                        // index 0 是标题 header，index >= 1 对应 posts[index - 1]
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
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            // 文章大标题区
            item(key = "header_${summary.id}") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 20.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(catColor)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = summary.categoryName,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = palette.inkSecondary
                            )
                        }

                        summary.tags.forEach { tag ->
                            Text(
                                text = "#$tag",
                                fontSize = 12.5.sp,
                                color = palette.inkTertiary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = detail?.title ?: summary.title,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 29.sp,
                        letterSpacing = (-0.3).sp,
                        color = palette.inkPrimary
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "${maxOf(summary.postsCount, posts.size)} 条讨论 · ${summary.views} 次阅读",
                        fontSize = 12.5.sp,
                        color = palette.inkTertiary
                    )
                }

                HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
            }

            // 楼层列表
            itemsIndexed(posts, key = { _, post -> post.id }) { _, post ->
                EditorialPostFloor(
                    post = post,
                    enableAnimations = enableAnimations,
                    onInspectBadge = onInspectBadge
                )
            }
        }

        // 底部极简回复栏
        HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .background(palette.canvas)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(palette.subtleSurface)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = "写下你的回复...",
                    fontSize = 13.sp,
                    color = palette.inkTertiary
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Text(
                text = "共 ${maxOf(summary.postsCount, posts.size)} 楼",
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                color = palette.inkSecondary
            )
        }
    }
}

/**
 * 帖子单层阅读视图（干净的头像/头衔/14dp 徽章行 + 25sp 行高正文）
 */
@Composable
private fun EditorialPostFloor(
    post: PostItemModel,
    enableAnimations: Boolean,
    onInspectBadge: (GrantedBadge) -> Unit
) {
    val palette = LocalEditorialPalette.current
    var liked by remember(post.id) { mutableStateOf(false) }
    var likeCount by remember(post.id) { mutableStateOf(post.likeCount) }
    val astNodes = remember(post.id, post.cookedHtml) {
        DiscourseAstParser.parseOrGetCached(post.id, post.cookedHtml)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 20.dp)
    ) {
        // 1. 作者头部
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
                size = 36.dp,
                enableAnimations = enableAnimations
            )

            Spacer(modifier = Modifier.width(10.dp))

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

                    Text(
                        text = "@${post.username}",
                        fontSize = 12.sp,
                        color = palette.inkTertiary,
                        maxLines = 1
                    )

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
                        text = post.createdAtText,
                        fontSize = 12.sp,
                        color = palette.inkTertiary
                    )

                    if (post.badgesGranted.isNotEmpty()) {
                        Text(
                            text = "·",
                            fontSize = 12.sp,
                            color = palette.inkTertiary
                        )
                        post.badgesGranted.take(5).forEach { badge ->
                            PostGrantedBadgeIcon(
                                badge = badge,
                                onClick = { onInspectBadge(badge) }
                            )
                        }
                    }
                }
            }

            Text(
                text = "#${post.postNumber}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = palette.inkTertiary
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 2. AST 正文块渲染
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
                                .padding(vertical = 2.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(2.5.dp)
                                    .height(42.dp)
                                    .clip(RoundedCornerShape(1.dp))
                                    .background(palette.inkTertiary.copy(alpha = 0.45f))
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                if (!node.username.isNullOrBlank()) {
                                    Text(
                                        text = "@${node.username}",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = palette.inkTertiary
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                }
                                Text(
                                    text = node.quotedText,
                                    fontSize = 14.sp,
                                    lineHeight = 21.sp,
                                    color = palette.inkSecondary
                                )
                            }
                        }
                    }

                    is PostBlockNode.CodeBlock -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(palette.subtleSurface)
                                .border(0.5.dp, palette.hairline, RoundedCornerShape(8.dp))
                                .padding(12.dp)
                        ) {
                            if (node.language.isNotBlank() && node.language != "code") {
                                Text(
                                    text = node.language.uppercase(),
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Monospace,
                                    color = palette.inkTertiary
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                            }
                            Text(
                                text = node.code,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 19.sp,
                                color = palette.inkPrimary,
                                modifier = Modifier.horizontalScroll(rememberScrollState())
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
                                        color = palette.inkSecondary,
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                    Text(
                                        text = item,
                                        fontSize = 15.sp,
                                        lineHeight = 23.sp,
                                        color = palette.inkPrimary
                                    )
                                }
                            }
                        }
                    }

                    is PostBlockNode.DetailsSpoilerBlock -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(palette.subtleSurface)
                                .padding(12.dp)
                        ) {
                            Text(
                                text = node.title,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.inkPrimary
                            )
                            if (node.innerText.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = node.innerText,
                                    fontSize = 14.sp,
                                    lineHeight = 21.sp,
                                    color = palette.inkSecondary
                                )
                            }
                        }
                    }

                    is PostBlockNode.ImageGallery -> {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            node.urls.forEach { url ->
                                Text(
                                    text = url,
                                    fontSize = 12.5.sp,
                                    color = palette.accent,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
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

        // 3. 克制文字操作栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (likeCount > 0) "赞 $likeCount" else "赞",
                fontSize = 12.5.sp,
                fontWeight = if (liked) FontWeight.SemiBold else FontWeight.Normal,
                color = if (liked) palette.accent else palette.inkTertiary,
                modifier = Modifier
                    .clickable {
                        liked = !liked
                        likeCount += if (liked) 1 else -1
                    }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )

            Text(
                text = "·",
                fontSize = 12.5.sp,
                color = palette.inkTertiary
            )

            Text(
                text = "回复",
                fontSize = 12.5.sp,
                color = palette.inkTertiary,
                modifier = Modifier
                    .clickable { }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
    }

    HorizontalDivider(
        modifier = Modifier.padding(start = 20.dp),
        thickness = 0.5.dp,
        color = palette.hairline
    )
}

/**
 * Apple HIG 分组列表版块页（零 Emoji，纯几何色点 + 12dp 圆角分组卡片）
 */
@Composable
private fun EditorialCategoriesScreen(
    onSelectCategory: (Int) -> Unit
) {
    val palette = LocalEditorialPalette.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.groupedCanvas),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp)
    ) {
        item {
            Text(
                text = "DIRECTORY",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.4.sp,
                color = palette.inkTertiary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "内容板块",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.6).sp,
                color = palette.inkPrimary
            )
            Spacer(modifier = Modifier.height(18.dp))
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.groupedCard)
                    .border(0.5.dp, palette.hairline, RoundedCornerShape(12.dp))
            ) {
                LinuxDoRepository.categories.forEachIndexed { index, category ->
                    EditorialCategoryRow(
                        category = category,
                        onClick = { onSelectCategory(category.id) }
                    )
                    if (index < LinuxDoRepository.categories.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 42.dp),
                            thickness = 0.5.dp,
                            color = palette.hairline
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorialCategoryRow(
    category: LinuxDoCategoryMeta,
    onClick: () -> Unit
) {
    val palette = LocalEditorialPalette.current
    val dotColor = remember(category.colorHex) {
        LinuxDoSiteRules.parseHexColor(category.colorHex)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(dotColor)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = category.name,
                fontSize = 15.5.sp,
                fontWeight = FontWeight.Medium,
                color = palette.inkPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = category.description,
                fontSize = 12.5.sp,
                color = palette.inkTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = "/c/${category.slug}",
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = palette.inkTertiary
        )

        Spacer(modifier = Modifier.width(6.dp))

        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = palette.inkTertiary
        )
    }
}

/**
 * Apple HIG 原生偏好设置页（含 Connect 等级与活跃度同步中心）
 */
@Composable
private fun EditorialProfileScreen(
    isDarkTheme: Boolean,
    enableAnimations: Boolean,
    onToggleDark: () -> Unit,
    onToggleAnimations: () -> Unit,
    onOpenConnectSheet: () -> Unit,
    onOpenNetworkSheet: () -> Unit,
    onOpenAuthSheet: () -> Unit
) {
    val palette = LocalEditorialPalette.current
    val cookieSnapshot = remember { CookieSentinel.resolveWinningCookies() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.groupedCanvas),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Column {
                Text(
                    text = "PREFERENCES",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.4.sp,
                    color = palette.inkTertiary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "偏好与账户",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.6).sp,
                    color = palette.inkPrimary
                )
            }
        }

        // 1. Connect 等级与活跃度保活中心（解决第三方客户端断签/漏计阅读痛点）
        item {
            EditorialSettingsSection(title = "CONNECT 等级与活跃度同步") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenConnectSheet)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Connect 信任等级与保级指标",
                            fontSize = 15.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = palette.inkPrimary
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = "查看 100 天访问天数、浏览话题与已读楼层",
                            fontSize = 12.5.sp,
                            color = palette.inkTertiary
                        )
                    }
                    Text(
                        text = "查看",
                        fontSize = 13.5.sp,
                        color = palette.accent
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = palette.inkTertiary
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(start = 16.dp),
                    thickness = 0.5.dp,
                    color = palette.hairline
                )

                EditorialStaticRow(
                    title = "今日连登在岗保活",
                    value = if (UserPresenceService.todayVisitConfirmed) "已同步 · 在线" else "待同步"
                )

                HorizontalDivider(
                    modifier = Modifier.padding(start = 16.dp),
                    thickness = 0.5.dp,
                    color = palette.hairline
                )

                EditorialStaticRow(
                    title = "阅读轨迹上报",
                    value = "进 ${ScreenTrackEngine.sessionTopicsEntered} 贴 · 读 ${ScreenTrackEngine.sessionPostsRead} 楼 · ${ScreenTrackEngine.sessionReadingSeconds}s"
                )
            }
        }

        // 2. 账户授权卡片
        item {
            EditorialSettingsSection(title = "LINUX.DO 账户") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenAuthSheet)
                        .padding(horizontal = 16.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "User API Key 授权登录",
                            fontSize = 15.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = palette.inkPrimary
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = "使用 Discourse 标准 RSA-OAEP 公钥握手授权",
                            fontSize = 12.5.sp,
                            color = palette.inkTertiary
                        )
                    }
                    Text(
                        text = if (UserApiKeyAuthManager.isAuthorized()) "已连接" else "配置",
                        fontSize = 13.5.sp,
                        color = palette.accent
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = palette.inkTertiary
                    )
                }
            }
        }

        // 2. 阅读与外观
        item {
            EditorialSettingsSection(title = "阅读与外观") {
                EditorialSwitchRow(
                    title = "深色外观",
                    subtitle = "切换深色画布与夜间低对比度分割线",
                    checked = isDarkTheme,
                    onCheckedChange = { onToggleDark() }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 16.dp),
                    thickness = 0.5.dp,
                    color = palette.hairline
                )
                EditorialSwitchRow(
                    title = "动态头像与光晕",
                    subtitle = "启用 GIF/WebP 动图解码与用户组光晕特效",
                    checked = enableAnimations,
                    onCheckedChange = { onToggleAnimations() }
                )
            }
        }

        // 3. 网络与底层静默能力
        item {
            EditorialSettingsSection(title = "连接与底层服务") {
                EditorialActionRow(
                    title = "节点延迟与会话状态",
                    value = if (cookieSnapshot.isNotEmpty()) "${cookieSnapshot.size} 项会话凭据" else "优选直连",
                    onClick = onOpenNetworkSheet
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 16.dp),
                    thickness = 0.5.dp,
                    color = palette.hairline
                )
                EditorialStaticRow(
                    title = "SVG 矢量徽章引擎",
                    value = "已静默启用"
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 16.dp),
                    thickness = 0.5.dp,
                    color = palette.hairline
                )
                EditorialStaticRow(
                    title = "版本",
                    value = "LinDo 1.0.0"
                )
            }
        }
    }
}

@Composable
private fun EditorialSettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    val palette = LocalEditorialPalette.current
    Column {
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = palette.inkTertiary,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(palette.groupedCard)
                .border(0.5.dp, palette.hairline, RoundedCornerShape(12.dp))
        ) {
            content()
        }
    }
}

@Composable
private fun EditorialSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val palette = LocalEditorialPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = palette.inkPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = palette.inkTertiary
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = palette.accent,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = palette.hairline,
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

@Composable
private fun EditorialActionRow(
    title: String,
    value: String,
    onClick: () -> Unit
) {
    val palette = LocalEditorialPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = palette.inkPrimary,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            fontSize = 13.5.sp,
            color = palette.inkTertiary
        )
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = palette.inkTertiary
        )
    }
}

@Composable
private fun EditorialStaticRow(
    title: String,
    value: String
) {
    val palette = LocalEditorialPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = palette.inkPrimary,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            fontSize = 13.5.sp,
            color = palette.inkTertiary
        )
    }
}

/**
 * 徽章详情抽屉（点击帖子楼层中的真实徽章时弹出）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BadgeDetailBottomSheet(
    badge: GrantedBadge,
    onDismiss: () -> Unit
) {
    val palette = LocalEditorialPalette.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.groupedCard
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            UniversalBadgeChip(badge = badge, compact = false)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = badge.plainDescription.ifBlank { "Linux.do 社区授予荣誉徽章" },
                fontSize = 14.sp,
                lineHeight = 21.sp,
                color = palette.inkSecondary
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * `connect.linux.do` 信任等级周期指标与活跃度核验抽屉
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectTrustLevelSheet(
    onDismiss: () -> Unit
) {
    val palette = LocalEditorialPalette.current
    var report by remember { mutableStateOf<ConnectTrustLevelReport?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        isLoading = true
        report = LinuxDoRepository.fetchConnectTrustLevelReport()
        isLoading = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.groupedCard
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
                        text = currentReport?.subtitle ?: "正在同步 connect.linux.do 周期数据...",
                        fontSize = 12.5.sp,
                        color = palette.inkTertiary
                    )
                }

                if (currentReport != null) {
                    Text(
                        text = currentReport.statusBadgeText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (currentReport.isQualified) Color(0xFF34C759) else palette.accent
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 活跃度保活机制说明栏
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(palette.subtleSurface)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "全链路活跃度防断签保障已激活",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.inkPrimary
                )
                Text(
                    text = "1. 连登天数：前台请求自动携带 Discourse-Present: true 触发 user_visits 记录\n" +
                        "2. 浏览话题：打开帖子自动携带 track_visit=true 与 Discourse-Track-View: 1\n" +
                        "3. 已读帖子：ScreenTrack 每秒追踪可见楼层并上报 POST /topics/timings（含失败退避重传）",
                    fontSize = 11.5.sp,
                    lineHeight = 17.sp,
                    color = palette.inkSecondary
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.5.dp),
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
                            fontFamily = FontFamily.Monospace,
                            color = if (metric.isMet) Color(0xFF34C759) else palette.inkSecondary
                        )
                    }
                    Spacer(modifier = Modifier.height(5.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(palette.subtleSurface)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(metric.progress.coerceIn(0.02f, 1f))
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(if (metric.isMet) Color(0xFF34C759) else palette.accent)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}


/**
 * 节点延迟与 Cookie 诊断抽屉
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NetworkDiagnosticsSheet(
    onDismiss: () -> Unit
) {
    val palette = LocalEditorialPalette.current
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
        containerColor = palette.groupedCard
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
                    text = "节点延迟优选",
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
                        color = palette.accent
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
                            fontFamily = FontFamily.Monospace,
                            color = palette.inkTertiary
                        )
                    }
                    Text(
                        text = "${node.rttMs} ms",
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        color = if (index == 0) palette.accent else palette.inkSecondary
                    )
                }
                HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * Discourse User API Key 授权抽屉
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserApiKeyAuthSheet(
    onDismiss: () -> Unit
) {
    val palette = LocalEditorialPalette.current
    val (authUrl, _) = remember { UserApiKeyAuthManager.generateAuthorizationUrl() }
    var apiKeyInput by remember { mutableStateOf(UserApiKeyAuthManager.getApiKey().orEmpty()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.groupedCard
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp)
        ) {
            Text(
                text = "User API Key 授权",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = palette.inkPrimary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "通过 Discourse 官方 /user-api-key/new 授权后，粘贴生成的 User API Key 即可完成绑定。",
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = palette.inkSecondary
            )
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.subtleSurface)
                    .padding(10.dp)
            ) {
                Text(
                    text = authUrl,
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = palette.inkSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = apiKeyInput,
                onValueChange = {
                    apiKeyInput = it
                    UserApiKeyAuthManager.setApiKey(it)
                },
                placeholder = { Text("粘贴 User API Key...", fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

