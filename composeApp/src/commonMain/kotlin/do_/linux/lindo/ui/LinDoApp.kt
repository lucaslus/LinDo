package do_.linux.lindo.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MilitaryTech
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import do_.linux.lindo.core.badge.ResolvedBadgeVisual
import do_.linux.lindo.core.badge.UniversalBadgeEngine
import do_.linux.lindo.core.model.FlairInfo
import do_.linux.lindo.core.model.GrantedBadge
import do_.linux.lindo.core.model.PostItemModel
import do_.linux.lindo.core.model.TopicDetailModel
import do_.linux.lindo.core.model.TopicSummary
import do_.linux.lindo.core.network.CloudflareEdgeRacer
import do_.linux.lindo.core.network.CookieSentinel
import do_.linux.lindo.core.network.CookieVariant
import do_.linux.lindo.core.network.EdgeCandidateResult
import do_.linux.lindo.core.network.LinuxDoRepository
import do_.linux.lindo.core.network.UserApiKeyAuthManager
import do_.linux.lindo.core.render.DiscourseAstParser
import do_.linux.lindo.core.render.PostBlockNode
import do_.linux.lindo.core.site.LinuxDoSiteRules
import do_.linux.lindo.ui.components.SmartAvatar
import do_.linux.lindo.ui.components.UniversalBadgeChip
import do_.linux.lindo.ui.components.UserTitleTag
import kotlinx.coroutines.launch

enum class LinDoTab(val title: String) {
    TOPICS("话题与长帖"),
    BADGES("新徽章引擎"),
    AVATARS("动态头像台"),
    NETWORK("防盾与认证")
}

private val LinDoDarkColorScheme = darkColorScheme(
    primary = Color(0xFF38BDF8),
    onPrimary = Color(0xFF082F49),
    primaryContainer = Color(0xFF0C4A6E),
    onPrimaryContainer = Color(0xFFE0F2FE),
    secondary = Color(0xFFA78BFA),
    tertiary = Color(0xFFF59E0B),
    background = Color(0xFF0B1120),
    surface = Color(0xFF111827),
    surfaceVariant = Color(0xFF1E293B),
    onSurface = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF94A3B8),
    outlineVariant = Color(0xFF334155)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinDoApp() {
    MaterialTheme(colorScheme = LinDoDarkColorScheme) {
        val scope = rememberCoroutineScope()
        var currentTab by remember { mutableStateOf(LinDoTab.TOPICS) }
        var topics by remember { mutableStateOf(LinuxDoRepository.showcaseTopics) }
        var statusBanner by remember { mutableStateOf("正在初始化 LinDo 高性能引擎...") }
        var selectedTopicId by remember { mutableIntStateOf(LinuxDoRepository.showcaseTopics.first().id) }
        var mobileViewingDetail by remember { mutableStateOf(false) }

        // 全局性能与动画开关：支持一键暂停/恢复动态头像与光晕 Ticker
        var enableAnimations by remember { mutableStateOf(true) }

        LaunchedEffect(Unit) {
            val (loaded, message) = LinuxDoRepository.fetchLatestTopics()
            topics = loaded
            statusBanner = message
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            val isDesktopWide = maxWidth >= 840.dp

            if (isDesktopWide) {
                // macOS / Windows 桌面端：左侧 NavigationRail + 主内容区（话题页自动开启左右双栏 Master-Detail）
                Row(modifier = Modifier.fillMaxSize()) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface,
                        header = {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = "LD",
                                            fontWeight = FontWeight.ExtraBold,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontSize = 16.sp
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "LinDo",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    ) {
                        LinDoTab.entries.forEach { tab ->
                            NavigationRailItem(
                                selected = currentTab == tab,
                                onClick = { currentTab = tab },
                                icon = { TabIcon(tab) },
                                label = { Text(tab.title, fontSize = 11.sp) }
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    )

                    Column(modifier = Modifier.weight(1f).fillMaxSize()) {
                        TopStatusHeader(
                            statusBanner = statusBanner,
                            enableAnimations = enableAnimations,
                            onToggleAnimations = { enableAnimations = it },
                            onRefresh = {
                                scope.launch {
                                    statusBanner = "正在刷新 Linux.do 话题流..."
                                    val (loaded, msg) = LinuxDoRepository.fetchLatestTopics()
                                    topics = loaded
                                    statusBanner = msg
                                }
                            }
                        )

                        when (currentTab) {
                            LinDoTab.TOPICS -> {
                                Row(modifier = Modifier.fillMaxSize()) {
                                    TopicListPane(
                                        topics = topics,
                                        selectedTopicId = selectedTopicId,
                                        enableAnimations = enableAnimations,
                                        onSelectTopic = { selectedTopicId = it },
                                        modifier = Modifier.weight(0.44f).fillMaxHeight()
                                    )
                                    Box(
                                        modifier = Modifier
                                            .width(1.dp)
                                            .fillMaxHeight()
                                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                    )
                                    TopicDetailPane(
                                        detail = LinuxDoRepository.getTopicDetail(selectedTopicId),
                                        enableAnimations = enableAnimations,
                                        showBackButton = false,
                                        onBack = {},
                                        modifier = Modifier.weight(0.56f).fillMaxHeight()
                                    )
                                }
                            }
                            LinDoTab.BADGES -> BadgesEngineScreen(modifier = Modifier.fillMaxSize())
                            LinDoTab.AVATARS -> AvatarStudioScreen(
                                enableAnimations = enableAnimations,
                                onToggleAnimations = { enableAnimations = it },
                                modifier = Modifier.fillMaxSize()
                            )
                            LinDoTab.NETWORK -> NetworkAuthScreen(modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            } else {
                // Android 移动端：紧凑单栏 + 底部导航栏
                Scaffold(
                    topBar = {
                        TopAppBar(
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            navigationIcon = {
                                if (currentTab == LinDoTab.TOPICS && mobileViewingDetail) {
                                    IconButton(onClick = { mobileViewingDetail = false }) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = "返回话题列表"
                                        )
                                    }
                                }
                            },
                            title = {
                                Column {
                                    Text(
                                        text = if (currentTab == LinDoTab.TOPICS && mobileViewingDetail) {
                                            "话题详情 #${selectedTopicId}"
                                        } else {
                                            "LinDo · ${currentTab.title}"
                                        },
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = statusBanner,
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            },
                            actions = {
                                IconButton(
                                    onClick = { enableAnimations = !enableAnimations }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Bolt,
                                        contentDescription = "动效开关",
                                        tint = if (enableAnimations) Color(0xFF38BDF8) else Color.Gray
                                    )
                                }
                            }
                        )
                    },
                    bottomBar = {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface
                        ) {
                            LinDoTab.entries.forEach { tab ->
                                NavigationBarItem(
                                    selected = currentTab == tab,
                                    onClick = {
                                        currentTab = tab
                                        if (tab != LinDoTab.TOPICS) mobileViewingDetail = false
                                    },
                                    icon = { TabIcon(tab) },
                                    label = { Text(tab.title, fontSize = 10.sp) }
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                        when (currentTab) {
                            LinDoTab.TOPICS -> {
                                if (mobileViewingDetail) {
                                    TopicDetailPane(
                                        detail = LinuxDoRepository.getTopicDetail(selectedTopicId),
                                        enableAnimations = enableAnimations,
                                        showBackButton = false,
                                        onBack = { mobileViewingDetail = false },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    TopicListPane(
                                        topics = topics,
                                        selectedTopicId = selectedTopicId,
                                        enableAnimations = enableAnimations,
                                        onSelectTopic = {
                                            selectedTopicId = it
                                            mobileViewingDetail = true
                                        },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                            LinDoTab.BADGES -> BadgesEngineScreen(modifier = Modifier.fillMaxSize())
                            LinDoTab.AVATARS -> AvatarStudioScreen(
                                enableAnimations = enableAnimations,
                                onToggleAnimations = { enableAnimations = it },
                                modifier = Modifier.fillMaxSize()
                            )
                            LinDoTab.NETWORK -> NetworkAuthScreen(modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabIcon(tab: LinDoTab) {
    val icon = when (tab) {
        LinDoTab.TOPICS -> Icons.Default.Forum
        LinDoTab.BADGES -> Icons.Default.MilitaryTech
        LinDoTab.AVATARS -> Icons.Default.Palette
        LinDoTab.NETWORK -> Icons.Default.Security
    }
    Icon(imageVector = icon, contentDescription = tab.title)
}

@Composable
private fun TopStatusHeader(
    statusBanner: String,
    enableAnimations: Boolean,
    onToggleAnimations: (Boolean) -> Unit,
    onRefresh: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(
                    imageVector = Icons.Default.Speed,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = statusBanner,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (enableAnimations) "60fps 动图/光晕已开启" else "省电静态模式",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(6.dp))
                Switch(
                    checked = enableAnimations,
                    onCheckedChange = onToggleAnimations
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = onRefresh) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = "刷新")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TopicListPane(
    topics: List<TopicSummary>,
    selectedTopicId: Int,
    enableAnimations: Boolean,
    onSelectTopic: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
    ) {
        items(topics, key = { it.id }) { topic ->
            val isSelected = topic.id == selectedTopicId
            val catColor = LinuxDoSiteRules.parseHexColor(topic.categoryColorHex)

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.surface
                    }
                ),
                border = if (isSelected) {
                    BorderStroke(1.2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                } else {
                    BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectTopic(topic.id) }
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SmartAvatar(
                            username = topic.originalPoster.username,
                            avatarTemplate = topic.originalPoster.avatarTemplate,
                            animatedAvatar = topic.originalPoster.animatedAvatar,
                            primaryGroupName = topic.originalPoster.primaryGroupName,
                            flair = topic.originalPoster.flair,
                            size = 40.dp,
                            enableAnimations = enableAnimations
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = topic.originalPoster.name ?: topic.originalPoster.username,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    color = catColor.copy(alpha = 0.18f),
                                    shape = RoundedCornerShape(5.dp)
                                ) {
                                    Text(
                                        text = topic.categoryName,
                                        color = catColor,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = "@${topic.originalPoster.username} · TL${topic.originalPoster.trustLevel}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (topic.unreadPosts > 0) {
                            Surface(
                                color = MaterialTheme.colorScheme.primary,
                                shape = CircleShape
                            ) {
                                Text(
                                    text = "+${topic.unreadPosts}",
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = topic.title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 20.sp
                    )

                    if (!topic.excerpt.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = topic.excerpt,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        topic.tags.forEach { tag ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "#$tag",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = "💬 ${topic.replyCount}   ❤️ ${topic.likeCount}   👁 ${topic.views}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TopicDetailPane(
    detail: TopicDetailModel,
    enableAnimations: Boolean,
    showBackButton: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
    ) {
        item(key = "topic_header_${detail.id}") {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    if (showBackButton) {
                        OutlinedButton(onClick = onBack) {
                            Text("返回话题列表")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Text(
                        text = detail.title,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (!detail.replyCostHint.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            color = Color(0x26F59E0B),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(0.8.dp, Color(0x80F59E0B))
                        ) {
                            Text(
                                text = "⚡ ${detail.replyCostHint} · 最少回复字数: ${detail.minWordCountRule ?: 10} 字",
                                color = Color(0xFFFBBF24),
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }

        items(detail.posts, key = { it.id }) { post ->
            PostFloorCard(post = post, enableAnimations = enableAnimations)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PostFloorCard(
    post: PostItemModel,
    enableAnimations: Boolean
) {
    val astNodes = remember(post.id, post.cookedHtml) {
        DiscourseAstParser.parseOrGetCached(post.id, post.cookedHtml)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(0.7.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // 楼层头部：SmartAvatar（静态打底+动图切换+光晕+Flair） + 用户名 + 全息头衔 + 生日/纪念日 + 顶部徽章列表
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmartAvatar(
                    username = post.username,
                    avatarTemplate = post.avatarTemplate,
                    animatedAvatar = post.animatedAvatar,
                    primaryGroupName = post.primaryGroupName,
                    flair = post.flair,
                    size = 44.dp,
                    enableAnimations = enableAnimations
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = post.displayName ?: post.username,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = if (post.admin || post.moderator) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                        if (post.admin || post.moderator) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = "🛡️", fontSize = 12.sp)
                        }
                        if (post.userStatus?.emoji != null) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = post.userStatus.emoji, fontSize = 12.sp)
                        }
                        if (post.isTopicOwner) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "楼主",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = "#${post.postNumber} · ${post.createdAtText}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        itemVerticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "@${post.username}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (!post.userTitle.isNullOrBlank()) {
                            UserTitleTag(
                                title = post.userTitle,
                                enableAnimations = enableAnimations
                            )
                        }
                        if (post.isTodayBirthday) {
                            Text(text = "🎂 生日", fontSize = 10.sp, color = Color(0xFFF472B6))
                        }
                        if (post.isTodayCakeday) {
                            Text(text = "🍰 纪念日", fontSize = 10.sp, color = Color(0xFF38BDF8))
                        }
                        post.badgesGranted.forEach { badge ->
                            UniversalBadgeChip(badge = badge, compact = true)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            Spacer(modifier = Modifier.height(10.dp))

            // 楼层正文：原生 AST Block 渲染（零 WebView、零主线程 HTML 解析）
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                astNodes.forEach { node ->
                    AstBlockRenderer(node = node)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Visibility,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "${post.readsCount} 阅读",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(14.dp))
                Icon(
                    imageVector = Icons.Default.ThumbUp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "${post.likeCount} 赞",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun AstBlockRenderer(node: PostBlockNode) {
    when (node) {
        is PostBlockNode.Heading -> {
            Text(
                text = node.text,
                fontWeight = FontWeight.ExtraBold,
                fontSize = (20 - node.level * 2).coerceIn(14, 18).sp,
                color = MaterialTheme.colorScheme.primary
            )
        }
        is PostBlockNode.Paragraph -> {
            Text(
                text = node.text,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        is PostBlockNode.CodeBlock -> {
            Surface(
                color = Color(0xFF090D16),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = node.language.uppercase(),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "AST 原生代码块",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = node.code,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = Color(0xFFE2E8F0)
                    )
                }
            }
        }
        is PostBlockNode.QuoteBlock -> {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        text = "↪ 引用 @${node.username ?: "匿名"} (#${node.postNumber ?: 1})：",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = node.quotedText,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        is PostBlockNode.DetailsSpoilerBlock -> {
            var expanded by remember { mutableStateOf(false) }
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        text = if (expanded) "▼ ${node.title}" else "▶ ${node.title}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    if (expanded) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = node.innerText,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
        is PostBlockNode.BulletList -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                node.items.forEachIndexed { index, item ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            text = if (node.ordered) "${index + 1}." else "•",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            modifier = Modifier.width(18.dp)
                        )
                        Text(
                            text = item,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
        is PostBlockNode.ImageGallery -> {
            Text(
                text = "🖼️ 包含 ${node.urls.size} 张高清附件图",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }
        PostBlockNode.Divider -> {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/**
 * Tab 2: 新徽章与站点动态 SVG Sprite 引擎验收页
 */
@Composable
private fun BadgesEngineScreen(modifier: Modifier = Modifier) {
    val badges = remember { mutableStateListOf<GrantedBadge>().apply { addAll(LinuxDoRepository.showcaseBadges) } }
    var customBadgeCounter by remember { mutableIntStateOf(1) }

    LazyColumn(
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "五合一全格式徽章引擎 (UniversalBadgeEngine)",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "已解决 FluxDO / CFluxDo 中 .svg 徽章裂图消失、新增自定义 Sprite 图标返回 null 被吞掉的问题。当前已注册动态 SVG Sprite 符号数：${UniversalBadgeEngine.getRegisteredSpriteCount()}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            val newSymbolId = "d-icon-linuxdo-live-$customBadgeCounter"
                            UniversalBadgeEngine.registerDiscourseSpriteBundle(
                                """<symbol id="$newSymbolId" viewBox="0 0 24 24"><path d="M12 2L15 8L22 9L17 14L18 21L12 18L6 21L7 14L2 9L9 8Z"/></symbol>"""
                            )
                            badges.add(
                                0,
                                GrantedBadge(
                                    id = 900 + customBadgeCounter,
                                    name = "动态热更徽章 #$customBadgeCounter",
                                    description = "通过 /svg-sprite/linux.do/ 运行时注入的新自定义图标 ($newSymbolId)，零发版立即显示",
                                    badgeTypeId = (customBadgeCounter % 3) + 1,
                                    icon = newSymbolId,
                                    grantCount = 1
                                )
                            )
                            customBadgeCounter++
                        }
                    ) {
                        Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("模拟社区上线新 Sprite 徽章（零发版热注入）")
                    }
                }
            }
        }

        items(badges, key = { it.id }) { badge ->
            val visual = UniversalBadgeEngine.resolveBadgeVisual(badge, isDark = true)
            val modeLabel = when (visual) {
                is ResolvedBadgeVisual.SvgImage -> "SVG 矢量直出 (.svg 嗅探)"
                is ResolvedBadgeVisual.DynamicSpriteSvg -> "站点动态 SVG Sprite (${visual.symbolId})"
                is ResolvedBadgeVisual.VectorGlyph -> "FA6 归一化矢量图标"
                is ResolvedBadgeVisual.BitmapImage -> "位图缓存通道"
                is ResolvedBadgeVisual.MedalFallback -> "零丢失等级首字勋章兜底"
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(12.dp)
                ) {
                    UniversalBadgeChip(badge = badge, compact = false)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = badge.plainDescription,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "渲染管线: $modeLabel · 已授予 ${badge.grantCount} 人",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

/**
 * Tab 3: 动态头像与站点身份特效工作台
 */
@Composable
private fun AvatarStudioScreen(
    enableAnimations: Boolean,
    onToggleAnimations: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "双栈动态头像与站点特效实验室",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 16.sp
                            )
                            Text(
                                text = "静态缩略图秒显打底 + 动图首帧就绪后自动隐藏底图（消除透明 GIF 重影）+ 视口节流开关",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = enableAnimations, onCheckedChange = onToggleAnimations)
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.padding(14.dp)
                ) {
                    AvatarShowcaseRow(
                        title = "1. 站长 @neo 专属：10% 圆角方形裁切 + #00AEFF 冰蓝呼吸光晕 + 动图双栈",
                        username = "neo",
                        primaryGroup = "admins",
                        userTitle = "种子用户",
                        flair = FlairInfo(url = "fa-solid fa-bolt", name = "创始人", bgColorHex = "00AEFF", fgColorHex = "FFFFFF"),
                        enableAnimations = enableAnimations
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    AvatarShowcaseRow(
                        title = "2. 认证商家 g-merchant：圆形裁切 + #F5BF03 鎏金呼吸光晕 + Premium 全息头衔",
                        username = "merchant_pro",
                        primaryGroup = "g-merchant",
                        userTitle = "Premium",
                        flair = FlairInfo(url = "fa-solid fa-crown", name = "认证商家", bgColorHex = "F5BF03", fgColorHex = "1E1E1E"),
                        enableAnimations = enableAnimations
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    AvatarShowcaseRow(
                        title = "3. 种子用户：绿芽 Flair 角标 + 种子用户全息渐变头衔",
                        username = "lucas_dev",
                        primaryGroup = "seed",
                        userTitle = "种子用户",
                        flair = FlairInfo(url = "fa-solid fa-seedling", name = "种子用户", bgColorHex = "10B981", fgColorHex = "FFFFFF"),
                        enableAnimations = enableAnimations
                    )
                }
            }
        }
    }
}

@Composable
private fun AvatarShowcaseRow(
    title: String,
    username: String,
    primaryGroup: String,
    userTitle: String,
    flair: FlairInfo,
    enableAnimations: Boolean
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SmartAvatar(
            username = username,
            avatarTemplate = "https://linux.do/user_avatar/linux.do/$username/{size}/1_2.png",
            animatedAvatar = "https://linux.do/user_avatar/linux.do/$username/120/1_2.gif",
            primaryGroupName = primaryGroup,
            flair = flair,
            size = 54.dp,
            enableAnimations = enableAnimations
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "@$username",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                UserTitleTag(title = userTitle, enableAnimations = enableAnimations)
            }
        }
    }
}

/**
 * Tab 4: 防盾网络竞速 (Cloudflare Edge Racer) 与免掉登录认证中心 (User API Key + Cookie 4元组哨兵)
 */
@Composable
private fun NetworkAuthScreen(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var racing by remember { mutableStateOf(false) }
    var raceResults by remember { mutableStateOf(CloudflareEdgeRacer.lastRaceResults) }
    var pinnedIp by remember { mutableStateOf(CloudflareEdgeRacer.pinnedEdgeIp ?: "自动优选") }
    var generatedAuthUrl by remember { mutableStateOf("") }
    var apiKeyInput by remember { mutableStateOf(UserApiKeyAuthManager.getApiKey().orEmpty()) }
    var cookieDedupeReport by remember { mutableStateOf("尚未触发多变体清理测试") }

    LazyColumn(
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
    ) {
        // 1. Cloudflare 节点竞速与固定出口网关
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "1. Cloudflare 边缘节点并发竞速与固定出口 (防频繁弹盾)",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "当前固定出口节点: $pinnedIp （锁定单一优胜 IP，防止多节点切换导致出口 IP 漂移触发 Cloudflare 验证）",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        enabled = !racing,
                        onClick = {
                            scope.launch {
                                racing = true
                                raceResults = CloudflareEdgeRacer.raceCandidates()
                                pinnedIp = CloudflareEdgeRacer.pinnedEdgeIp ?: "未锁定"
                                racing = false
                            }
                        }
                    ) {
                        Icon(imageVector = Icons.Default.NetworkCheck, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (racing) "正在并发测量 /cdn-cgi/trace..." else "立即并发竞速优选 Cloudflare 节点")
                    }

                    if (raceResults.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        raceResults.forEach { res ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Text(text = "${res.providerLabel} [${res.colo}]", fontSize = 12.sp)
                                Text(
                                    text = "${res.rttMs} ms",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (res.ip == pinnedIp) Color(0xFF4ADE80) else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. User API Key (RSA-2048) 免掉登录主通道
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "2. User API Key (RSA 2048) 免掉登录授权通道",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "通过 User-Api-Key 头部鉴权，彻底绕开 WebView _t Cookie 被重置或过期覆盖的问题。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = {
                            apiKeyInput = it
                            UserApiKeyAuthManager.setApiKey(it)
                        },
                        label = { Text("粘贴或配置 Linux.do User-Api-Key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            generatedAuthUrl = UserApiKeyAuthManager.generateAuthRequestUrl()
                        }
                    ) {
                        Icon(imageVector = Icons.Default.Key, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("生成 RSA-2048 密钥对与 /user-api-key/new 授权链接")
                    }
                    if (generatedAuthUrl.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "已生成 RSA 授权 URL (${generatedAuthUrl.length} 字符)：\n${generatedAuthUrl.take(140)}...",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF4ADE80)
                        )
                    }
                }
            }
        }

        // 3. RFC 6265bis Cookie 4元组哨兵验证
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "3. Cookie 4元组去重哨兵 (根治 _t 多变体假掉登录)",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = cookieDedupeReport,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                        onClick = {
                            CookieSentinel.clear()
                            val result = CookieSentinel.ingestAndDeduplicate(
                                listOf(
                                    CookieVariant(
                                        name = "_t",
                                        value = "expired_old_token_111",
                                        domain = ".linux.do",
                                        hostOnly = false,
                                        updatedAtEpochMs = 1000L
                                    ),
                                    CookieVariant(
                                        name = "_t",
                                        value = "fresh_valid_token_999",
                                        domain = "linux.do",
                                        hostOnly = true,
                                        updatedAtEpochMs = 2000L
                                    ),
                                    CookieVariant(
                                        name = "cf_clearance",
                                        value = "cf_passed_ok",
                                        domain = ".linux.do",
                                        hostOnly = false,
                                        updatedAtEpochMs = 2000L
                                    )
                                )
                            )
                            cookieDedupeReport = "输入 2 条冲突 _t 变体 (host-only 与 .linux.do) -> 哨兵归一化后仅保留 ${result.count { it.name == "_t" }} 条最新值：${CookieSentinel.buildCookieHeader()}"
                        }
                    ) {
                        Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("运行 _t 多变体冲突自愈测试")
                    }
                }
            }
        }
    }
}
