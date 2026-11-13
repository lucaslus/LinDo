package do_.linux.lindo.ui

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import do_.linux.lindo.core.compose.LocalComposerDrafts
import do_.linux.lindo.core.connect.ScreenTrackEngine
import do_.linux.lindo.core.connect.UserPresenceService
import do_.linux.lindo.core.network.CookieSentinel
import do_.linux.lindo.core.network.LinuxDoRepository
import do_.linux.lindo.core.network.PlatformMediaPicker
import do_.linux.lindo.core.network.UserApiKeyAuthManager
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Full Interactive Compose UI End-to-End Test Suite for Lindo (`v0.2.31` pre-launch).
 *
 * Mounts the real `LinDoApp()` root composable inside `runDesktopComposeUiTest` against a
 * deterministic, stateful in-memory `MockLinuxDoBackend` wired into
 * `UserApiKeyAuthManager.webViewHttpExecutor`, `UserApiKeyAuthManager.webViewBinaryFetcher`,
 * and `PlatformMediaPicker`.
 */
@OptIn(ExperimentalTestApi::class)
class PreLaunchFullAppUiE2ETest {

    private lateinit var backend: MockLinuxDoBackend
    private val openedExternalUrls = CopyOnWriteArrayList<String>()

    @BeforeTest
    fun setUp() {
        resetGlobalSingletons()
        openedExternalUrls.clear()
        backend = MockLinuxDoBackend()

        UserApiKeyAuthManager.requireBrowserTransportOnly = true
        UserApiKeyAuthManager.webViewHttpExecutor = { url, method, formParams, headers ->
            backend.handleHttp(url, method, formParams, headers)
        }
        UserApiKeyAuthManager.webViewBinaryFetcher = { url ->
            UserApiKeyAuthManager.BinaryHttpPayload(
                status = 200,
                contentType = "image/png",
                bytes = if (url.contains("e2eBanner")) BANNER_PNG_BYTES else ONE_BY_ONE_PNG_BYTES
            )
        }
        UserApiKeyAuthManager.openExternalUrlLauncher = { url ->
            openedExternalUrls.add(url)
        }
        PlatformMediaPicker.launchPicker = { _, onPicked ->
            onPicked("e2e_shot.png", "image/png", ONE_BY_ONE_PNG_BYTES)
        }
        PlatformMediaPicker.webViewBinaryUploadExecutor = { fileName, mimeType, bytes ->
            backend.handleBinaryUpload(fileName, mimeType, bytes)
        }
    }

    @AfterTest
    fun tearDown() {
        resetGlobalSingletons()
        openedExternalUrls.clear()
    }

    private fun resetGlobalSingletons() {
        PlatformWebLoginHost.webLoginRenderer = null
        PlatformMediaPicker.launchPicker = null
        PlatformMediaPicker.webViewBinaryUploadExecutor = null
        PlatformMediaPicker.webViewUploadExecutor = null

        UserApiKeyAuthManager.webViewHttpExecutor = null
        UserApiKeyAuthManager.webViewJsonFetcher = null
        UserApiKeyAuthManager.webViewBinaryFetcher = null
        UserApiKeyAuthManager.requireBrowserTransportOnly = false
        UserApiKeyAuthManager.openExternalUrlLauncher = null
        UserApiKeyAuthManager.onAuthStateChanged = null
        UserApiKeyAuthManager.onNetworkRestored = null
        UserApiKeyAuthManager.onPrepareManualRecovery = null
        UserApiKeyAuthManager.onLogoutPlatformClear = null
        UserApiKeyAuthManager.onPersistSession = null
        UserApiKeyAuthManager.onPersistPendingKey = null
        UserApiKeyAuthManager.onPersistUiPrefs = null
        UserApiKeyAuthManager.onPersistThemeAndDensity = null
        UserApiKeyAuthManager.onPersistSplitFraction = null
        UserApiKeyAuthManager.onPersistReadTopics = null
        UserApiKeyAuthManager.onSyncCookieToPlatform = null
        UserApiKeyAuthManager.customUserAgent = null

        AppAppearance.appearance = "light"
        AppAppearance.iconAppearance = "system"
        AppAppearance.onPersist = null
        AppAppearance.onApplyIconAppearance = null
        AppAppearance.onSystemAppearanceChanged = null
        PlatformSessionRecovery.loginSession = null
        PlatformSessionRecovery.verifySession = null
        PlatformSessionRecovery.verificationMessage = null
        UserApiKeyAuthManager.uiFontScale = 1f
        UserApiKeyAuthManager.uiDarkTheme = false
        UserApiKeyAuthManager.uiEnableAnimations = false
        UserApiKeyAuthManager.uiThemeAccent = "OFFICIAL_GOLD"
        UserApiKeyAuthManager.uiCompactFeed = false
        UserApiKeyAuthManager.uiSplitFraction = 0.42f

        LocalComposerDrafts.onPersist = null
        LocalComposerDrafts.clearAll()
        UserApiKeyAuthManager.logout()
        CookieSentinel.clearAll()
        LinuxDoRepository.clearUserSessionCaches()
        LinuxDoRepository.clearAllLocalCaches()
        LinuxDoRepository.clearTransientRateLimit()
        LinuxDoRepository.clearChallengeCooldown()
        ScreenTrackEngine.resetSessionState()
        UserPresenceService.setAppForeground(true)

        var safety = 0
        while (BackNavigationBridge.hasActiveInterceptors && safety++ < 20) {
            BackNavigationBridge.dispatchBack()
        }
        BackNavigationBridge.canGoBack = false
        BackNavigationBridge.onBack = null
    }

    private fun <T> onEdt(block: () -> T): T {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) return block()
        var result: Any? = null
        var failure: Throwable? = null
        javax.swing.SwingUtilities.invokeAndWait {
            try {
                result = block()
            } catch (t: Throwable) {
                failure = t
            }
        }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.edtCount(): Int =
        onEdt { fetchSemanticsNodes().size }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.edtClick() {
        onEdt { performClick() }
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.edtScrollClick() {
        onEdt { performScrollTo().edtClick() }
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.edtInput(text: String) {
        onEdt { performTextInput(text) }
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.edtScrollInput(text: String) {
        onEdt { performScrollTo().edtInput(text) }
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.edtAssertDisplayed() {
        onEdt { assertIsDisplayed() }
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.edtScrollToIndex(index: Int) {
        onEdt { performScrollToIndex(index) }
    }

    private fun ComposeUiTest.waitForText(
        text: String,
        substring: Boolean = false,
        timeoutMillis: Long = 5_000L
    ) {
        waitUntil(timeoutMillis = timeoutMillis) {
            onAllNodesWithText(text, substring = substring).edtCount() > 0
        }
    }

    private fun ComposeUiTest.waitForTextGone(
        text: String,
        substring: Boolean = false,
        timeoutMillis: Long = 5_000L
    ) {
        waitUntil(timeoutMillis = timeoutMillis) {
            onAllNodesWithText(text, substring = substring).edtCount() == 0
        }
    }

    private fun ComposeUiTest.waitForContentDescription(
        description: String,
        timeoutMillis: Long = 5_000L
    ) {
        waitUntil(timeoutMillis = timeoutMillis) {
            onAllNodesWithContentDescription(description).edtCount() > 0
        }
    }

    private fun ComposeUiTest.savePreviewSnapshotIfConfigured(fileName: String) {
        val rawDir = (System.getenv("LINDO_PREVIEW_DIR") ?: System.getProperty("lindo.preview.dir"))
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return
        waitForIdle()
        val outDir = File(rawDir).apply { mkdirs() }
        val awtImage = onEdt { onAllNodes(isRoot()).onLast().captureToImage().toAwtImage() }
        assertTrue(awtImage.width > 0 && awtImage.height > 0, "Captured snapshot $fileName must have non-zero dimensions")
        ImageIO.write(awtImage, "png", File(outDir, fileName))
    }

    @Test
    fun browserOtpSurvivesAuthNotificationAndClosesLoginSheet() = runDesktopComposeUiTest(width = 1000, height = 800) {
        val exchanges = AtomicInteger(0)
        val completed = AtomicBoolean(false)
        UserApiKeyAuthManager.webViewHttpExecutor = { url, method, form, headers ->
            if (url.contains("/session/otp/")) {
                exchanges.incrementAndGet()
                // Reproduce the notification that used to cancel LaunchedEffect(authVersion).
                UserApiKeyAuthManager.onAuthStateChanged?.invoke()
                kotlinx.coroutines.delay(150)
                CookieSentinel.ingestRawCookieString("_t=e2e_valid_session_token", domain = "linux.do")
                completed.set(true)
                302 to ""
            } else backend.handleHttp(url, method, form, headers)
        }
        setContent { LinDoApp(systemAnimationsEnabled = false) }
        waitForText("使用 LINUX DO 登录")
        onNodeWithText("使用 LINUX DO 登录").edtClick()
        waitForText("登录 Lindo")
        onEdt {
            UserApiKeyAuthManager.pendingOtpToken = "test_callback_otp"
            UserApiKeyAuthManager.onAuthStateChanged?.invoke()
        }
        waitUntil(timeoutMillis = 8_000L) { completed.get() && UserApiKeyAuthManager.currentUser?.username == "lucas" }
        waitForTextGone("登录 Lindo")
        waitForTextGone("你好，佬友。")
        assertEquals(1, exchanges.get())
    }

    @Test
    fun desktopWideSplitPaneFullFeatureJourneyE2E() = runDesktopComposeUiTest(
        width = 1280,
        height = 840
    ) {
        UserApiKeyAuthManager.logout()
        CookieSentinel.clearAll()
        assertFalse(UserApiKeyAuthManager.isAuthorized())

        setContent {
            LinDoApp(systemAnimationsEnabled = false)
        }
        waitForIdle()

        // =========================================================================
        // Step 1 — Pre-Consent Welcome Screen
        // =========================================================================
        onNodeWithText("你好，佬友。").edtAssertDisplayed()
        onNodeWithText("使用 LINUX DO 登录").edtAssertDisplayed()
        onNodeWithText("系统浏览器授权").edtAssertDisplayed()
        onAllNodesWithText("粘贴 Cookie / 回调链接登录").assertCountEquals(0)
        onNodeWithText("以游客模式浏览").edtAssertDisplayed()
        assertEquals(0, backend.feedRequestCount.get(), "Zero feed requests must fire before user consent")

        savePreviewSnapshotIfConfigured("e2e_1_welcome.png")

        onNodeWithContentDescription("切换至深色模式").edtClick()
        waitUntil(timeoutMillis = 5_000L) { UserApiKeyAuthManager.uiDarkTheme }
        onNodeWithContentDescription("切换至浅色模式").edtAssertDisplayed()
        onNodeWithContentDescription("切换至浅色模式").edtClick()
        waitUntil(timeoutMillis = 5_000L) { !UserApiKeyAuthManager.uiDarkTheme }
        onNodeWithContentDescription("切换至深色模式").edtAssertDisplayed()
        assertEquals(0, backend.feedRequestCount.get(), "Theme toggle on Welcome screen must not fire feed requests")

        // =========================================================================
        // Step 2 — Guest Mode, Feed Sort, Category Filter, Refresh & Guest Gates
        // =========================================================================
        onNodeWithText("以游客模式浏览").edtClick()
        waitForText("Lindo 预发版全功能自动化巡检主贴")
        waitForText("欢迎参与 Lindo v0.2.31 桌面双栏全链路自动化巡检", substring = true)
        assertTrue(backend.feedRequestCount.get() >= 1, "Entering guest mode must load initial feed")

        // Sort: Top -> Latest
        val topBefore = backend.topFeedRequestCount.get()
        onNodeWithText("热门").edtClick()
        waitUntil(timeoutMillis = 5_000L) { backend.topFeedRequestCount.get() > topBefore }

        val latestBefore = backend.latestFeedRequestCount.get()
        onNodeWithText("最新").edtClick()
        waitUntil(timeoutMillis = 5_000L) { backend.latestFeedRequestCount.get() > latestBefore }

        // Category Filter: Expand -> Resource (14) -> Clear filter
        val catBefore = backend.categoryFeedRequestCount.get()
        onNodeWithContentDescription("展开分类筛选").edtClick()
        waitForText("资源荟萃")
        onAllNodesWithText("资源荟萃").onFirst().edtClick()
        waitUntil(timeoutMillis = 5_000L) { backend.categoryFeedRequestCount.get() > catBefore }
        waitForContentDescription("清除分类筛选")
        onNodeWithContentDescription("清除分类筛选").edtClick()
        waitForText("Lindo 预发版全功能自动化巡检主贴")

        // Manual Feed Refresh
        val feedBeforeRefresh = backend.feedRequestCount.get()
        onNodeWithContentDescription("刷新列表").edtClick()
        waitUntil(timeoutMillis = 5_000L) { backend.feedRequestCount.get() > feedBeforeRefresh }

        // Split-Pane Topic Reader Rich Content: Code Copy, Spoiler Toggle & Fullscreen Image Lightbox
        waitForText("复制代码")
        onNodeWithText("复制代码").edtScrollClick()
        waitForText("已复制")

        waitForText("显示隐藏内容")
        onNodeWithText("显示隐藏内容").edtScrollClick()
        waitForText("预发版隐藏口令：LINDO-2026-READY", substring = true)

        // In-App Post Image Lightbox Viewer: click inline image -> verify fullscreen preview -> close
        waitForContentDescription("巡检配图预览")
        onNodeWithContentDescription("巡检配图预览").edtScrollClick()
        waitForContentDescription("关闭图片预览")
        onNodeWithContentDescription("关闭图片预览").edtClick()
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithContentDescription("关闭图片预览").edtCount() == 0
        }

        // Guest Gate -> Open Desktop Login Sheet
        onNodeWithContentDescription("发布新话题").edtClick()
        waitForText("登录后发布话题")
        PlatformSessionRecovery.loginSession = {
            UserApiKeyAuthManager.onSessionAuthenticated(
                do_.linux.lindo.core.network.AuthenticatedUserProfile(userId = 1, username = "lucas", displayName = "Lucas", trustLevel = 3),
                rawCookies = "_t=e2e_valid_session_token; cf_clearance=e2e_cf"
            )
            true
        }
        onAllNodesWithText("使用 LINUX DO 登录").onLast().edtClick()
        waitForText("登录 Lindo")
        onNodeWithText("继续登录").edtClick()
        waitUntil(timeoutMillis = 5_000L) {
            UserApiKeyAuthManager.isAuthorized() && UserApiKeyAuthManager.currentUser?.username == "lucas"
        }
        waitForTextGone("登录 Lindo")
        waitForText("回复话题")

        // =========================================================================
        // Step 4 — Topic Reader Interactive Actions
        // =========================================================================
        // Scroll Topic Reader LazyColumn back to header item 0 so AI Summary ("总结") is mounted
        onAllNodes(hasScrollToIndexAction()).onLast().edtScrollToIndex(0)
        // AI Summary
        waitForText("总结")
        onNodeWithText("总结").edtScrollClick()
        waitUntil(timeoutMillis = 5_000L) { backend.aiSummaryRequestCount.get() >= 1 }
        waitForText("这是预发版全功能巡检生成的 AI 智能总结", substring = true)

        // Bookmark & Post Reaction (Like)
        val bookmarkBefore = backend.bookmarkMutationCount.get()
        onNodeWithContentDescription("收藏").edtClick()
        waitUntil(timeoutMillis = 5_000L) { backend.bookmarkMutationCount.get() > bookmarkBefore }

        val reactionBefore = backend.reactionMutationCount.get()
        waitForContentDescription("点赞（长按选择表情表态）")
        onAllNodesWithContentDescription("点赞（长按选择表情表态）").onFirst().edtScrollClick()
        waitUntil(timeoutMillis = 5_000L) { backend.reactionMutationCount.get() > reactionBefore }

        onAllNodes(hasScrollToIndexAction()).onLast().edtScrollToIndex(0)
        savePreviewSnapshotIfConfigured("e2e_2_desktop_split_reader.png")

        // Floor Navigator Sheet
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodes(hasText("1 /", substring = true)).edtCount() > 0
        }
        onAllNodes(hasText("1 /", substring = true)).onFirst().edtClick()
        waitForContentDescription("关闭楼层导航")
        waitForText("末楼")
        onNodeWithContentDescription("关闭楼层导航").edtClick()
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithContentDescription("关闭楼层导航").edtCount() == 0
        }

        // =========================================================================
        // Step 5 — Cross-Tab Split-Pane Navigation (Discover + Search + Messages)
        // =========================================================================
        onNodeWithText("发现").edtClick()
        waitForText("社区版块")
        waitForContentDescription("搜索")
        onNodeWithContentDescription("搜索").edtClick()

        waitForText("全站搜索")
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodes(hasSetTextAction()).edtCount() > 0
        }
        onAllNodes(hasSetTextAction()).onLast().edtInput("KMP")
        waitForIdle()
        onAllNodesWithText("搜索").onLast().edtClick()

        waitForText("KMP 桌面端 144Hz 丝滑滚动架构")
        assertTrue(backend.searchRequestCount.get() >= 1, "Global search must query /search.json")

        savePreviewSnapshotIfConfigured("e2e_3_discover_search.png")

        onAllNodesWithText("KMP 桌面端 144Hz 丝滑滚动架构").onLast().edtClick()
        waitForTextGone("全站搜索")
        // Verify automatic return to HOME split-pane with Topic #102 loaded in Topic Reader AND left feed visible
        waitForText("基于 Compose Desktop Skia 帧调度与惰性列表预取优化方案", substring = true)
        waitForText("Lindo 预发版全功能自动化巡检主贴")

        // Messages -> Notifications -> Mark all read -> Private Messages tab
        onNodeWithText("消息").edtClick()
        waitForText("欢迎体验 Lindo v0.2.31 桌面预发版", substring = true)
        waitForText("全部标为已读")
        onNodeWithText("全部标为已读").edtClick()
        waitForText("要将通知全部标为已读吗？")
        onNodeWithText("标为已读").edtClick()
        waitUntil(timeoutMillis = 5_000L) { backend.markAllNotificationsReadCount.get() >= 1 }
        waitForTextGone("要将通知全部标为已读吗？")

        onNodeWithText("私信").edtClick()
        waitForText("Lindo 预发版内测反馈私信会话")

        // =========================================================================
        // Step 6 — Reply Composer + Media Upload + New Topic Composer
        // =========================================================================
        onNodeWithText("首页").edtClick()
        waitForText("回复话题")
        onNodeWithText("回复话题").edtClick()

        waitForText("发送")
        waitForContentDescription("图片")
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodes(hasSetTextAction()).edtCount() > 0
        }
        onAllNodes(hasSetTextAction()).onLast().edtInput("来自桌面端 E2E 自动化巡检的图文回复内容")
        onNodeWithContentDescription("图片").edtClick()

        waitForText("已插入 e2e_shot.png")
        assertEquals(1, backend.mediaUploadCount.get(), "Binary media upload executor must be invoked")
        waitUntil(timeoutMillis = 5_000L) {
            runCatching {
                onEdt { onAllNodesWithText("发送").onLast().assertIsEnabled() }
                true
            }.getOrDefault(false)
        }
        onAllNodesWithText("发送").onLast().edtClick()

        waitUntil(timeoutMillis = 5_000L) { backend.createdReplies.size == 1 }
        val submittedReply = backend.createdReplies.single()
        assertTrue(
            submittedReply.raw.contains("upload://e2eTestImg.png"),
            "Submitted reply raw markdown must contain uploaded short_url"
        )
        waitForTextGone("发送")

        // New Topic Composer via DesktopSideRail Publish button
        onNodeWithContentDescription("发布新话题").edtClick()
        waitForText("新话题")
        waitForContentDescription("打开版块抽屉")
        onNodeWithContentDescription("打开版块抽屉").edtClick()
        waitForText("选择发布版块")
        onAllNodesWithText("文档共建").onLast().edtClick()
        waitForTextGone("选择发布版块")

        waitUntil(timeoutMillis = 5_000L) {
            onAllNodes(hasSetTextAction()).edtCount() >= 2
        }
        val newTopicTitle = "Lindo v0.2.31 桌面新话题自动化发布验证"
        val newTopicBody = "验证桌面端新话题编辑器的标题输入、版块选择与正文提交完整闭环。"
        val composerTextFields = onAllNodes(hasSetTextAction())
        val tfCount = composerTextFields.edtCount()
        composerTextFields[tfCount - 2].edtInput(newTopicTitle)
        composerTextFields[tfCount - 1].edtInput(newTopicBody)

        waitUntil(timeoutMillis = 5_000L) {
            runCatching {
                onEdt { onAllNodesWithText("发布").onLast().assertIsEnabled() }
                true
            }.getOrDefault(false)
        }
        onAllNodesWithText("发布").onLast().edtClick()

        waitUntil(timeoutMillis = 5_000L) { backend.createdTopics.size == 1 }
        val createdTopic = backend.createdTopics.single()
        assertEquals(newTopicTitle, createdTopic.title)
        assertEquals(42, createdTopic.categoryId)
        assertTrue(createdTopic.raw.contains("完整闭环"))
        waitForTextGone("新话题")
        waitForText(newTopicTitle)

        // =========================================================================
        // Step 7 — Profile, Sub-Sheets, Settings, Cache Clear & Logout
        // =========================================================================
        onNodeWithText("我的").edtClick()
        waitForText("@lucas")

        // Drafts sub-sheet
        onNodeWithText("草稿").edtClick()
        waitForText("草稿箱")
        runOnIdle { assertTrue(BackNavigationBridge.dispatchBack()) }
        waitForTextGone("草稿箱")

        // Bookmarks sub-sheet
        onNodeWithText("收藏").edtClick()
        waitForText("我的书签与收藏")
        runOnIdle { assertTrue(BackNavigationBridge.dispatchBack()) }
        waitForTextGone("我的书签与收藏")

        // Read History sub-sheet
        onNodeWithText("历史").edtClick()
        waitForText("阅读历史")
        runOnIdle { assertTrue(BackNavigationBridge.dispatchBack()) }
        waitForTextGone("阅读历史")

        // Badges sub-sheet
        onNodeWithText("社区徽章墙").edtScrollClick()
        waitForText("搜索徽章")
        runOnIdle { assertTrue(BackNavigationBridge.dispatchBack()) }
        waitForTextGone("搜索徽章")

        // Connect external launcher
        onNodeWithText("Connect 保级与成长").edtScrollClick()
        waitUntil(timeoutMillis = 5_000L) {
            openedExternalUrls.any { it.startsWith("https://connect.linux.do") }
        }

        // Scroll Profile LazyColumn down to Settings / Cache Clear / Version v0.2.32
        onAllNodes(hasScrollToIndexAction()).onFirst().edtScrollToIndex(3)
        waitForText(do_.linux.lindo.core.diagnostics.LinDoDiagnosticLogger.APP_VERSION_DISPLAY)
        onAllNodesWithText(do_.linux.lindo.core.diagnostics.LinDoDiagnosticLogger.APP_VERSION_DISPLAY).onFirst().edtAssertDisplayed()

        onNodeWithText("清理本地缓存").edtScrollClick()
        waitForText("仅清理图片与数据缓存")
        onNodeWithText("仅清理图片与数据缓存").edtClick()
        waitForText("已清理图片与解析缓存")

        savePreviewSnapshotIfConfigured("e2e_4_profile_v0_2_31.png")

        // Scroll back to Account & Session Management ("切换账号") -> Logout
        onAllNodes(hasScrollToIndexAction()).onFirst().edtScrollToIndex(1)
        waitForText("账号与会话管理")
        onNodeWithText("切换账号").edtScrollClick()

        waitForText("Linux.do 账号会话")
        onAllNodesWithText("退出当前账号").onLast().edtClick()
        waitForText("要退出当前账号吗？")
        onNodeWithText("退出").edtClick()

        waitUntil(timeoutMillis = 5_000L) { !UserApiKeyAuthManager.isAuthorized() }
        waitForText("你好，佬友。")
    }

    @Test
    fun compactMobileViewportAndSiteVerificationRecoveryE2E() = runDesktopComposeUiTest(
        width = 430,
        height = 820
    ) {
        UserApiKeyAuthManager.logout()
        CookieSentinel.clearAll()

        setContent {
            LinDoApp(systemAnimationsEnabled = false)
        }
        waitForIdle()

        // Enter Guest Mode on compact 430x820 phone-width viewport
        waitForText("你好，佬友。")
        onNodeWithText("以游客模式浏览").edtClick()
        waitForText("Lindo 预发版全功能自动化巡检主贴")
        waitForText("预发版归档历史公告锁定贴")

        // Verify single-column mobile BottomNavigationBar (no split-pane reader mounted yet)
        onNodeWithText("首页").edtAssertDisplayed()
        onNodeWithText("发现").edtAssertDisplayed()
        onNodeWithText("发布").edtAssertDisplayed()
        onNodeWithText("消息").edtAssertDisplayed()
        onNodeWithText("我的").edtAssertDisplayed()
        assertTrue(
            onAllNodesWithText("欢迎参与 Lindo v0.2.31 桌面双栏全链路自动化巡检", substring = true)
                .edtCount() == 0,
            "Compact 430dp viewport must use single-column layout rather than split-pane"
        )

        savePreviewSnapshotIfConfigured("e2e_5_compact_mobile_feed.png")

        // Switch compact bottom tabs and return to Home
        onNodeWithText("发现").edtClick()
        waitForText("社区版块")
        onNodeWithText("首页").edtClick()
        waitForText("预发版归档历史公告锁定贴")

        // Push mobileShowingDetail on a closed topic -> verify "话题已关闭" lock guard -> pop via BackNavigationBridge
        onNodeWithText("预发版归档历史公告锁定贴").edtClick()
        waitForText("该历史公告贴已完成归档锁定", substring = true)
        waitForText("话题已关闭")
        onNodeWithContentDescription("返回").edtAssertDisplayed()
        assertTrue(
            onAllNodesWithText("发现").edtCount() == 0,
            "BottomNavigationBar must hide while mobileShowingDetail full-screen reader is active"
        )
        assertTrue(
            onAllNodesWithText("登录后参与回复").edtCount() == 0,
            "Closed topic must display '话题已关闭' guard instead of reply composer prompt"
        )

        runOnIdle {
            assertTrue(BackNavigationBridge.dispatchBack(), "BackNavigationBridge must pop mobileShowingDetail")
        }
        waitForTextGone("话题已关闭")
        waitForText("Lindo 预发版全功能自动化巡检主贴")
        onNodeWithText("发现").edtAssertDisplayed()

        // Cloudflare Site Verification challenge -> "去验证" -> DesktopSiteVerificationSheet -> automatic recovery
        backend.siteVerificationBlocked.set(true)
        val countBeforeChallenge = backend.feedRequestCount.get()
        onNodeWithContentDescription("刷新列表").edtClick()
        waitUntil(timeoutMillis = 5_000L) { backend.feedRequestCount.get() > countBeforeChallenge }

        // Ensure top inline failure banner is scrolled into view on LazyColumn
        onAllNodes(hasScrollToIndexAction()).onFirst().edtScrollToIndex(0)
        waitForText("站点验证", substring = true)
        waitForText("去验证")

        onNodeWithText("去验证").edtClick()
        waitForText("还差一次确认")
        assertEquals(0, onAllNodes(hasSetTextAction()).edtCount(), "Recovery must not ask users to paste cookies")
        savePreviewSnapshotIfConfigured("shared_compact_verification.png")
        PlatformSessionRecovery.verifySession = {
            CookieSentinel.ingestRawCookieString("cf_clearance=e2e_cf_verified", domain = "linux.do")
            true
        }
        onNodeWithText("继续验证").edtClick()
        waitForTextGone("还差一次确认")
        waitForTextGone("去验证")
        waitForText("Cloudflare 验证恢复后实时同步测试贴")

    }

    @Test fun sharedAppearanceCompactViewport() = runDesktopComposeUiTest(width = 430, height = 900) {
        verifySharedAppearance("shared_compact_settings.png")
    }

    @Test fun sharedAppearanceWideViewport() = runDesktopComposeUiTest(width = 1280, height = 900) {
        verifySharedAppearance("shared_wide_settings.png")
    }

    private fun ComposeUiTest.verifySharedAppearance(snapshot: String) {
        setContent { LinDoApp(systemAnimationsEnabled = false, appIcon = LinDoAppIcon.Default, onApplyAppIcon = { true }) }
        waitForText("以游客模式浏览")
        onNodeWithText("以游客模式浏览").edtClick()
        waitForText("我的")
        onNodeWithText("我的").edtClick()
        waitForText("外观")
        onAllNodesWithText("外观").onLast().edtScrollToVisible()
        onNodeWithText("图标底色").edtScrollToVisible()
        assertTrue(onAllNodesWithText("跟随系统").edtCount() >= 2)
        onNodeWithText("深色").edtClick()
        waitUntil { AppAppearance.dark }
        savePreviewSnapshotIfConfigured(snapshot.replace(".png", "_dark.png"))
        onNodeWithText("浅色").edtClick()
        waitUntil { !AppAppearance.dark }
        savePreviewSnapshotIfConfigured(snapshot)
    }

    private fun SemanticsNodeInteraction.edtScrollToVisible() { onEdt { performScrollTo() } }

    /**
     * Stateful in-memory Discourse + Linux.do backend supporting Guest mode, cURL/Cookie session auth,
     * Cloudflare Turnstile challenge simulation & clearance recovery, AI summaries, reactions, bookmarks,
     * search, notifications, private messages, media uploads, replies, new topics, drafts, and profile endpoints.
     */
    private class MockLinuxDoBackend {
        data class CreatedReply(
            val postId: Int,
            val topicId: Int,
            val postNumber: Int,
            val raw: String
        )

        data class CreatedTopic(
            val topicId: Int,
            val title: String,
            val categoryId: Int,
            val raw: String
        )

        val feedRequestCount = AtomicInteger(0)
        val latestFeedRequestCount = AtomicInteger(0)
        val topFeedRequestCount = AtomicInteger(0)
        val categoryFeedRequestCount = AtomicInteger(0)
        val aiSummaryRequestCount = AtomicInteger(0)
        val bookmarkMutationCount = AtomicInteger(0)
        val reactionMutationCount = AtomicInteger(0)
        val searchRequestCount = AtomicInteger(0)
        val markAllNotificationsReadCount = AtomicInteger(0)
        val mediaUploadCount = AtomicInteger(0)
        val siteVerificationBlocked = AtomicBoolean(false)

        private val notificationsMarkedRead = AtomicBoolean(false)
        private val bookmarkedTopicIds = Collections.synchronizedSet(mutableSetOf<Int>())
        private val likedPostIds = Collections.synchronizedSet(mutableSetOf<Int>())
        private val nextPostId = AtomicInteger(3000)
        private val nextTopicId = AtomicInteger(201)

        val createdReplies = CopyOnWriteArrayList<CreatedReply>()
        val createdTopics = CopyOnWriteArrayList<CreatedTopic>()

        private fun hasValidSessionCookie(): Boolean {
            val token = CookieSentinel.resolveWinningCookies(targetHost = "linux.do")["_t"]?.value.orEmpty()
            return token == "e2e_valid_session_token"
        }

        private fun hasRecoveredClearance(): Boolean {
            val clearance = CookieSentinel.resolveWinningCookies(targetHost = "linux.do")["cf_clearance"]?.value.orEmpty()
            return clearance == "e2e_cf_verified"
        }

        fun handleBinaryUpload(fileName: String, mimeType: String, bytes: ByteArray): Pair<Int, String> {
            assertTrue(mimeType.startsWith("image/"))
            assertTrue(bytes.isNotEmpty())
            mediaUploadCount.incrementAndGet()
            val body = buildJsonObject {
                put("id", 901)
                put("url", "https://linux.do/uploads/default/original/1X/e2eTestImg.png")
                put("short_url", "upload://e2eTestImg.png")
                put("original_filename", fileName)
                put("width", 800)
                put("height", 600)
                put("human_filesize", "42 KB")
                put("extension", "png")
            }.toString()
            return 200 to body
        }

        fun handleHttp(
            url: String,
            method: String,
            formParams: Map<String, String>?,
            headers: Map<String, String>
        ): Pair<Int, String> {
            val uri = URI(url)
            val path = uri.path.orEmpty()
            val query = parseQueryParams(uri.rawQuery)
            val upperMethod = method.uppercase()

            if (siteVerificationBlocked.get() && !hasRecoveredClearance()) {
                if (isFeedEndpoint(path)) {
                    feedRequestCount.incrementAndGet()
                }
                return 403 to CLOUDFLARE_CHALLENGE_HTML
            }

            return when {
                // 1. Feed Endpoints (/latest.json, /top.json, /c/..., /tag/..., /new.json, /unread.json)
                upperMethod == "GET" && isFeedEndpoint(path) -> {
                    if (headers["Discourse-Background"] != "true") {
                        feedRequestCount.incrementAndGet()
                    }
                    if (path.contains("top.json")) {
                        topFeedRequestCount.incrementAndGet()
                    }
                    if (path == "/latest.json") {
                        latestFeedRequestCount.incrementAndGet()
                    }
                    if (path.startsWith("/c/")) {
                        categoryFeedRequestCount.incrementAndGet()
                    }
                    200 to buildFeedJson(path)
                }

                // 2. Current Session Verification (/session/current.json & /session/csrf.json)
                upperMethod == "GET" && path == "/session/current.json" -> {
                    if (hasValidSessionCookie()) {
                        200 to buildJsonObject {
                            putJsonObject("current_user") {
                                put("id", 888)
                                put("username", "lucas")
                                put("name", "Lucas")
                                put("avatar_template", "/letter_avatar_proxy/v4/letter/l/f05b48/{size}.png")
                                put("trust_level", 3)
                                put("title", "高级佬友")
                                put("unread_notifications", if (notificationsMarkedRead.get()) 0 else 1)
                            }
                        }.toString()
                    } else {
                        404 to """{"errors":["未登录"],"error_type":"not_logged_in"}"""
                    }
                }

                upperMethod == "GET" && path == "/session/csrf.json" -> {
                    200 to """{"csrf":"e2e_csrf_token_2026"}"""
                }

                // 3. User Profile & Summary (/u/lucas.json, /u/lucas/summary.json, /u/{name}/card.json)
                upperMethod == "GET" && path.matches(Regex("^/u/[^/]+/summary\\.json$")) -> {
                    200 to buildJsonObject {
                        putJsonObject("user_summary") {
                            put("days_visited", 128)
                            put("time_read", 86_400)
                            put("recent_time_read", 7_200)
                            put("topics_entered", 320)
                            put("posts_read_count", 4_500)
                            put("likes_received", 256)
                            put("likes_given", 512)
                            put("topic_count", 12 + createdTopics.size)
                            put("post_count", 168 + createdReplies.size)
                        }
                        putJsonArray("badges") {}
                        putJsonArray("user_badges") {}
                    }.toString()
                }

                upperMethod == "GET" && (
                    path.matches(Regex("^/u/[^/]+\\.json$")) ||
                        path.matches(Regex("^/u/[^/]+/card\\.json$"))
                    ) -> {
                    val requestedUser = path.removePrefix("/u/")
                        .removeSuffix("/card.json")
                        .removeSuffix(".json")
                        .ifBlank { "lucas" }
                    200 to buildUserProfileJson(requestedUser)
                }

                // 4. AI Topic Summary (/discourse-ai/summarization/t/{id}.json)
                upperMethod == "GET" && path.startsWith("/discourse-ai/summarization/t/") -> {
                    aiSummaryRequestCount.incrementAndGet()
                    200 to buildJsonObject {
                        putJsonObject("ai_topic_summary") {
                            put("summarized_text", "这是预发版全功能巡检生成的 AI 智能总结：涵盖双栏阅读、代码块复制与剧透折叠。")
                            put("algorithm", "gemma-27b")
                            put("outdated", false)
                            put("can_regenerate", false)
                            put("new_posts_since_summary", 0)
                        }
                    }.toString()
                }

                // 5. Topic Bookmark & Post Reaction Mutations
                (upperMethod == "PUT" || upperMethod == "POST") &&
                    (path.matches(Regex("^/t/\\d+/bookmark\\.json$")) || path == "/bookmarks.json") -> {
                    bookmarkMutationCount.incrementAndGet()
                    val tid = Regex("/t/(\\d+)/").find(path)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 101
                    bookmarkedTopicIds.add(tid)
                    200 to """{"id":701,"success":"OK"}"""
                }

                (upperMethod == "PUT" || upperMethod == "POST") &&
                    path.contains("/discourse-reactions/posts/") -> {
                    reactionMutationCount.incrementAndGet()
                    val pid = Regex("/posts/(\\d+)/").find(path)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1011
                    likedPostIds.add(pid)
                    200 to buildJsonObject {
                        put("id", pid)
                        putJsonArray("reactions") {
                            add(
                                buildJsonObject {
                                    put("id", "heart")
                                    put("type", "emoji")
                                    put("count", 10)
                                }
                            )
                        }
                        putJsonObject("current_user_reaction") {
                            put("id", "heart")
                            put("type", "emoji")
                            put("can_undo", true)
                        }
                    }.toString()
                }

                // 6. Global Search (/search.json)
                upperMethod == "GET" && path == "/search.json" -> {
                    searchRequestCount.incrementAndGet()
                    val q = query["q"].orEmpty()
                    200 to buildSearchJson(q)
                }

                // 7. Notifications & Mark-All-Read
                upperMethod == "GET" && path == "/notifications.json" -> {
                    val isRead = notificationsMarkedRead.get()
                    200 to buildJsonObject {
                        putJsonArray("notifications") {
                            add(
                                buildJsonObject {
                                    put("id", 5001)
                                    put("user_id", 888)
                                    put("notification_type", 2)
                                    put("read", isRead)
                                    put("created_at", "2026-11-12T06:30:00.000Z")
                                    put("post_number", 2)
                                    put("topic_id", 101)
                                    put("slug", "lindo-prelaunch-e2e")
                                    putJsonObject("data") {
                                        put("topic_title", "Lindo 预发版全功能自动化巡检主贴")
                                        put("original_post_id", 1012)
                                        put("original_post_type", 1)
                                        put("original_username", "neo")
                                        put("display_username", "neo")
                                        put("excerpt", "欢迎体验 Lindo v0.2.31 桌面预发版！")
                                    }
                                }
                            )
                        }
                        put("total_rows_notifications", 1)
                    }.toString()
                }

                upperMethod == "PUT" && path == "/notifications/mark-read.json" -> {
                    markAllNotificationsReadCount.incrementAndGet()
                    notificationsMarkedRead.set(true)
                    200 to """{"success":"OK"}"""
                }

                // 8. Private Messages Inbox & Sent
                upperMethod == "GET" && path.startsWith("/topics/private-messages") -> {
                    200 to buildPrivateMessagesJson()
                }

                // 9. Create Reply / New Topic (POST /posts.json)
                upperMethod == "POST" && path == "/posts.json" -> {
                    val params = formParams.orEmpty()
                    val raw = params["raw"].orEmpty()
                    val replyTopicId = params["topic_id"]?.toIntOrNull()
                    val newTitle = params["title"]?.trim()
                    if (replyTopicId != null && replyTopicId > 0) {
                        val postId = nextPostId.incrementAndGet()
                        val postNum = 3 + createdReplies.count { it.topicId == replyTopicId }
                        createdReplies.add(CreatedReply(postId, replyTopicId, postNum, raw))
                        200 to buildSinglePostResponseJson(
                            postId = postId,
                            topicId = replyTopicId,
                            postNumber = postNum,
                            raw = raw
                        )
                    } else {
                        val topicId = nextTopicId.getAndIncrement()
                        val postId = nextPostId.incrementAndGet()
                        val catId = params["category"]?.toIntOrNull() ?: 4
                        val safeTitle = newTitle.takeUnless { it.isNullOrBlank() } ?: "新建测试话题 #$topicId"
                        createdTopics.add(CreatedTopic(topicId, safeTitle, catId, raw))
                        200 to buildSinglePostResponseJson(
                            postId = postId,
                            topicId = topicId,
                            postNumber = 1,
                            raw = raw
                        )
                    }
                }

                // 10. Topic Detail & Posts (/t/{id}.json, /t/{id}/{floor}.json, /posts/...)
                upperMethod == "GET" && path.matches(Regex("^/t/(?:[^/]+/)?(\\d+)(?:/\\d+)?\\.json$")) -> {
                    val tid = Regex("^/t/(?:[^/]+/)?(\\d+)").find(path)!!.groupValues[1].toInt()
                    200 to buildTopicDetailJson(tid)
                }

                upperMethod == "GET" && path.matches(Regex("^/posts/by_number/(\\d+)/(\\d+)\\.json$")) -> {
                    val m = Regex("^/posts/by_number/(\\d+)/(\\d+)\\.json$").find(path)!!
                    val tid = m.groupValues[1].toInt()
                    val pnum = m.groupValues[2].toInt()
                    200 to buildSinglePostResponseJson(
                        postId = tid * 10 + pnum,
                        topicId = tid,
                        postNumber = pnum,
                        raw = "摘要楼层内容 #$tid-$pnum"
                    )
                }

                // 11. Drafts (/drafts.json, /drafts/{key}.json)
                upperMethod == "GET" && path == "/drafts.json" -> {
                    200 to buildJsonObject {
                        putJsonArray("drafts") {
                            add(
                                buildJsonObject {
                                    put("draft_key", "topic_101")
                                    put("sequence", 1)
                                    put("title", "Lindo 预发版全功能自动化巡检主贴")
                                    put("topic_id", 101)
                                    put("category_id", 4)
                                    put("created_at", "2026-11-12T06:40:00.000Z")
                                    putJsonObject("data") {
                                        put("reply", "云端同步草稿内容预览")
                                        put("action", "reply")
                                        put("topicId", 101)
                                    }
                                }
                            )
                        }
                    }.toString()
                }

                upperMethod == "GET" && path.startsWith("/drafts/") -> {
                    200 to buildJsonObject {
                        put("draft", JsonNull)
                        put("draft_sequence", 0)
                    }.toString()
                }

                upperMethod == "POST" && path == "/drafts.json" -> {
                    200 to """{"draft_sequence":1}"""
                }

                upperMethod == "DELETE" && path.startsWith("/drafts/") -> {
                    200 to """{"success":"OK"}"""
                }

                // 12. Bookmarks, Read History, Badges, Tags, Site Metadata, Timings
                upperMethod == "GET" && path.matches(Regex("^/u/[^/]+/bookmarks\\.json$")) -> {
                    200 to buildJsonObject {
                        putJsonObject("user_bookmark_list") {
                            putJsonArray("bookmarks") {
                                add(
                                    buildJsonObject {
                                        put("id", 701)
                                        put("topic_id", 101)
                                        put("linked_post_number", 1)
                                        put("name", "巡检主贴书签")
                                        put("title", "Lindo 预发版全功能自动化巡检主贴")
                                        put("excerpt", "欢迎参与 Lindo v0.2.31 桌面双栏全链路自动化巡检")
                                        put("category_id", 4)
                                        put("created_at", "2026-11-12T06:20:00.000Z")
                                    }
                                )
                            }
                        }
                    }.toString()
                }

                upperMethod == "GET" && path == "/read.json" -> {
                    200 to buildFeedJson("/latest.json")
                }

                upperMethod == "GET" && path == "/badges.json" -> {
                    200 to buildBadgesCatalogJson()
                }

                upperMethod == "GET" && path.startsWith("/user-badges/") -> {
                    200 to buildUserBadgesJson()
                }

                upperMethod == "GET" && path == "/tags/filter/search.json" -> {
                    200 to buildJsonObject {
                        putJsonArray("results") {
                            add(
                                buildJsonObject {
                                    put("id", 1)
                                    put("name", "人工智能")
                                    put("text", "人工智能")
                                    put("count", 128)
                                }
                            )
                            add(
                                buildJsonObject {
                                    put("id", 2)
                                    put("name", "开源项目")
                                    put("text", "开源项目")
                                    put("count", 96)
                                }
                            )
                        }
                    }.toString()
                }

                upperMethod == "GET" && path == "/site.json" -> {
                    200 to buildSiteJson()
                }

                upperMethod == "GET" && path == "/emojis.json" -> {
                    200 to "{}"
                }

                upperMethod == "GET" && path == "/u/search/users.json" -> {
                    200 to """{"users":[{"username":"neo","name":"Neo","avatar_template":"/letter_avatar_proxy/v4/letter/n/3b82f6/{size}.png"}],"groups":[]}"""
                }

                upperMethod == "POST" && path == "/topics/timings" -> {
                    200 to "{}"
                }

                upperMethod == "PUT" && path.matches(Regex("^/t/\\d+/notifications$")) -> {
                    200 to """{"success":"OK"}"""
                }

                upperMethod == "GET" && path == "/about.json" -> {
                    200 to """{"about":{"description":"LINUX DO 社区","title":"LINUX DO","stats":{"topics_count":100000,"posts_count":2000000,"users_count":500000,"active_users_7_days":60000,"active_users_30_days":180000,"likes_count":5000000},"moderator_ids":[],"admin_ids":[]},"users":[]}"""
                }

                else -> {
                    200 to "{}"
                }
            }
        }

        private fun isFeedEndpoint(path: String): Boolean {
            return path == "/latest.json" ||
                path == "/top.json" ||
                path == "/new.json" ||
                path == "/unread.json" ||
                path.endsWith("/l/top.json") ||
                path.endsWith("/l/new.json") ||
                path.endsWith("/l/unread.json") ||
                path.startsWith("/c/") ||
                path.startsWith("/tag/")
        }

        private fun buildFeedJson(path: String): String {
            val resourceOnly = path.contains("/14")
            return buildJsonObject {
                putJsonArray("users") {
                    add(userSummaryJson(id = 10, username = "neo", name = "Neo"))
                    add(userSummaryJson(id = 11, username = "alice", name = "Alice"))
                    add(userSummaryJson(id = 888, username = "lucas", name = "Lucas"))
                }
                putJsonObject("topic_list") {
                    putJsonArray("topics") {
                        if (resourceOnly) {
                            add(
                                topicListEntryJson(
                                    id = 105,
                                    title = "精选开源桌面工具包资源汇总",
                                    categoryId = 14,
                                    postsCount = 4,
                                    views = 520,
                                    likeCount = 26,
                                    authorId = 11,
                                    excerpt = "整理跨平台高帧率开发工具与字体资源"
                                )
                            )
                        } else {
                            createdTopics.reversed().forEach { created ->
                                add(
                                    topicListEntryJson(
                                        id = created.topicId,
                                        title = created.title,
                                        categoryId = created.categoryId,
                                        postsCount = 1,
                                        views = 12,
                                        likeCount = 1,
                                        authorId = 888,
                                        excerpt = created.raw.take(80)
                                    )
                                )
                            }
                            if (hasRecoveredClearance()) {
                                add(
                                    topicListEntryJson(
                                        id = 106,
                                        title = "Cloudflare 验证恢复后实时同步测试贴",
                                        categoryId = 4,
                                        postsCount = 2,
                                        views = 188,
                                        likeCount = 19,
                                        authorId = 10,
                                        excerpt = "站点安全验证已通过，列表已自动恢复同步"
                                    )
                                )
                            }
                            add(
                                topicListEntryJson(
                                    id = 101,
                                    title = "Lindo 预发版全功能自动化巡检主贴",
                                    categoryId = 4,
                                    postsCount = 2 + createdReplies.count { it.topicId == 101 },
                                    views = 1_440,
                                    likeCount = 88,
                                    authorId = 10,
                                    tags = listOf("开源项目", "人工智能"),
                                    excerpt = "欢迎参与 Lindo v0.2.31 桌面双栏全链路自动化巡检"
                                )
                            )
                            add(
                                topicListEntryJson(
                                    id = 103,
                                    title = "预发版归档历史公告锁定贴",
                                    categoryId = 42,
                                    postsCount = 2,
                                    views = 640,
                                    likeCount = 15,
                                    authorId = 11,
                                    closed = true,
                                    excerpt = "该历史公告贴已完成归档锁定"
                                )
                            )
                        }
                    }
                }
            }.toString()
        }

        private fun buildTopicDetailJson(topicId: Int): String {
            val dynamicTopic = createdTopics.firstOrNull { it.topicId == topicId }
            if (dynamicTopic != null) {
                return buildJsonObject {
                    put("id", dynamicTopic.topicId)
                    put("title", dynamicTopic.title)
                    put("category_id", dynamicTopic.categoryId)
                    put("posts_count", 1)
                    put("highest_post_number", 1)
                    put("views", 16)
                    put("like_count", 1)
                    put("bookmarked", bookmarkedTopicIds.contains(dynamicTopic.topicId))
                    putJsonObject("details") {
                        put("notification_level", 1)
                        put("can_edit", true)
                        putJsonObject("created_by") {
                            put("id", 888)
                            put("username", "lucas")
                        }
                    }
                    putJsonObject("post_stream") {
                        putJsonArray("stream") { add(JsonPrimitive(dynamicTopic.topicId * 10 + 1)) }
                        putJsonArray("posts") {
                            add(
                                postJson(
                                    id = dynamicTopic.topicId * 10 + 1,
                                    topicId = dynamicTopic.topicId,
                                    postNumber = 1,
                                    userId = 888,
                                    username = "lucas",
                                    displayName = "Lucas",
                                    cookedHtml = "<p>${escapeHtml(dynamicTopic.raw)}</p>",
                                    raw = dynamicTopic.raw
                                )
                            )
                        }
                    }
                }.toString()
            }

            return when (topicId) {
                102 -> buildJsonObject {
                    put("id", 102)
                    put("title", "KMP 桌面端 144Hz 丝滑滚动架构")
                    put("category_id", 4)
                    put("posts_count", 2)
                    put("highest_post_number", 2)
                    put("views", 2_048)
                    put("like_count", 64)
                    put("bookmarked", bookmarkedTopicIds.contains(102))
                    putJsonArray("tags") { add(JsonPrimitive("开源项目")) }
                    putJsonObject("details") {
                        put("notification_level", 1)
                        putJsonObject("created_by") {
                            put("id", 10)
                            put("username", "neo")
                        }
                    }
                    putJsonObject("post_stream") {
                        putJsonArray("stream") {
                            add(JsonPrimitive(1021))
                            add(JsonPrimitive(1022))
                        }
                        putJsonArray("posts") {
                            add(
                                postJson(
                                    id = 1021,
                                    topicId = 102,
                                    postNumber = 1,
                                    userId = 10,
                                    username = "neo",
                                    displayName = "Neo",
                                    cookedHtml = "<p>基于 Compose Desktop Skia 帧调度与惰性列表预取优化方案，桌面端分栏阅读帧率稳定 144Hz。</p>"
                                )
                            )
                            add(
                                postJson(
                                    id = 1022,
                                    topicId = 102,
                                    postNumber = 2,
                                    userId = 11,
                                    username = "alice",
                                    displayName = "Alice",
                                    cookedHtml = "<p>实测 Windows 与 macOS 双端分栏切换非常丝滑！</p>"
                                )
                            )
                        }
                    }
                }.toString()

                103 -> buildJsonObject {
                    put("id", 103)
                    put("title", "预发版归档历史公告锁定贴")
                    put("category_id", 42)
                    put("closed", true)
                    put("posts_count", 2)
                    put("highest_post_number", 2)
                    put("views", 640)
                    put("like_count", 15)
                    put("bookmarked", false)
                    putJsonObject("details") {
                        put("notification_level", 1)
                        putJsonObject("created_by") {
                            put("id", 11)
                            put("username", "alice")
                        }
                    }
                    putJsonObject("post_stream") {
                        putJsonArray("stream") {
                            add(JsonPrimitive(1031))
                            add(JsonPrimitive(1032))
                        }
                        putJsonArray("posts") {
                            add(
                                postJson(
                                    id = 1031,
                                    topicId = 103,
                                    postNumber = 1,
                                    userId = 11,
                                    username = "alice",
                                    displayName = "Alice",
                                    cookedHtml = "<p>该历史公告贴已完成归档锁定，仅供只读查阅。</p>"
                                )
                            )
                            add(
                                buildJsonObject {
                                    put("id", 1032)
                                    put("topic_id", 103)
                                    put("post_number", 2)
                                    put("user_id", -1)
                                    put("username", "system")
                                    put("name", "system")
                                    put("post_type", 3)
                                    put("action_code", "closed.enabled")
                                    put("cooked", "")
                                    put("created_at", "2026-11-12T05:00:00.000Z")
                                }
                            )
                        }
                    }
                }.toString()

                else -> {
                    val extraReplies = createdReplies.filter { it.topicId == topicId }
                    val totalPosts = 2 + extraReplies.size
                    buildJsonObject {
                        put("id", topicId)
                        put("title", if (topicId == 105) "精选开源桌面工具包资源汇总" else "Lindo 预发版全功能自动化巡检主贴")
                        put("category_id", if (topicId == 105) 14 else 4)
                        put("posts_count", totalPosts)
                        put("highest_post_number", totalPosts)
                        put("views", 1_440)
                        put("like_count", 88)
                        put("bookmarked", bookmarkedTopicIds.contains(topicId))
                        putJsonArray("tags") {
                            add(JsonPrimitive("开源项目"))
                            add(JsonPrimitive("人工智能"))
                        }
                        putJsonObject("details") {
                            put("notification_level", 1)
                            putJsonObject("created_by") {
                                put("id", 10)
                                put("username", "neo")
                            }
                        }
                        putJsonObject("post_stream") {
                            putJsonArray("stream") {
                                add(JsonPrimitive(1011))
                                add(JsonPrimitive(1012))
                                extraReplies.forEach { add(JsonPrimitive(it.postId)) }
                            }
                            putJsonArray("posts") {
                                add(
                                    postJson(
                                        id = 1011,
                                        topicId = topicId,
                                        postNumber = 1,
                                        userId = 10,
                                        username = "neo",
                                        displayName = "Neo",
                                        cookedHtml = """
                                            <p>欢迎参与 Lindo v0.2.31 桌面双栏全链路自动化巡检。</p>
                                            <pre><code class="lang-kotlin">val lindoVersion = "0.2.31"</code></pre>
                                            <div class="spoiled"><p>预发版隐藏口令：LINDO-2026-READY</p></div>
                                            <p><img src="https://linux.do/uploads/default/original/1X/e2eBanner.png" alt="巡检配图预览" width="360" height="120" /></p>
                                        """.trimIndent()
                                    )
                                )
                                add(
                                    postJson(
                                        id = 1012,
                                        topicId = topicId,
                                        postNumber = 2,
                                        userId = 11,
                                        username = "alice",
                                        displayName = "Alice",
                                        cookedHtml = "<p>二楼报到！双栏阅读器与楼层跳转交互一切就绪。</p>"
                                    )
                                )
                                extraReplies.forEach { reply ->
                                    add(
                                        postJson(
                                            id = reply.postId,
                                            topicId = topicId,
                                            postNumber = reply.postNumber,
                                            userId = 888,
                                            username = "lucas",
                                            displayName = "Lucas",
                                            cookedHtml = "<p>${escapeHtml(reply.raw)}</p>",
                                            raw = reply.raw
                                        )
                                    )
                                }
                            }
                        }
                    }.toString()
                }
            }
        }

        private fun buildSearchJson(queryText: String): String {
            return buildJsonObject {
                putJsonArray("posts") {
                    if (queryText.contains("KMP", ignoreCase = true) || queryText.isNotBlank()) {
                        add(
                            buildJsonObject {
                                put("id", 1021)
                                put("topic_id", 102)
                                put("post_number", 1)
                                put("username", "neo")
                                put("avatar_template", "/letter_avatar_proxy/v4/letter/n/3b82f6/{size}.png")
                                put("blurb", "基于 Compose Desktop Skia 帧调度与惰性列表预取优化方案")
                                put("like_count", 64)
                                put("created_at", "2026-11-12T06:10:00.000Z")
                            }
                        )
                    }
                }
                putJsonArray("topics") {
                    add(
                        buildJsonObject {
                            put("id", 102)
                            put("title", "KMP 桌面端 144Hz 丝滑滚动架构")
                            put("fancy_title", "KMP 桌面端 144Hz 丝滑滚动架构")
                            put("category_id", 4)
                            put("posts_count", 2)
                            put("like_count", 64)
                        }
                    )
                }
                putJsonObject("grouped_search_result") {
                    put("more_full_page_results", false)
                }
            }.toString()
        }

        private fun buildPrivateMessagesJson(): String {
            return buildJsonObject {
                putJsonArray("users") {
                    add(userSummaryJson(id = 10, username = "neo", name = "Neo"))
                    add(userSummaryJson(id = 888, username = "lucas", name = "Lucas"))
                }
                putJsonObject("topic_list") {
                    putJsonArray("topics") {
                        add(
                            topicListEntryJson(
                                id = 104,
                                title = "Lindo 预发版内测反馈私信会话",
                                categoryId = 0,
                                postsCount = 3,
                                views = 12,
                                likeCount = 2,
                                authorId = 10,
                                excerpt = "桌面端预发版体验反馈汇总"
                            )
                        )
                    }
                }
            }.toString()
        }

        private fun buildUserProfileJson(username: String): String {
            return buildJsonObject {
                putJsonObject("user") {
                    put("id", if (username.equals("lucas", ignoreCase = true)) 888 else 10)
                    put("username", username)
                    put("name", username.replaceFirstChar { it.uppercase() })
                    put("avatar_template", "/letter_avatar_proxy/v4/letter/l/f05b48/{size}.png")
                    put("trust_level", 3)
                    put("title", "高级佬友")
                    put("bio_raw", "Kotlin Multiplatform & Compose Desktop")
                    put("bio_excerpt", "Kotlin Multiplatform & Compose Desktop")
                    put("badge_count", 2)
                    put("unread_notifications", if (notificationsMarkedRead.get()) 0 else 1)
                    put("created_at", "2024-05-01T00:00:00.000Z")
                    putJsonObject("user_option") {
                        put("hide_profile_and_presence", false)
                        put("allow_private_messages", true)
                    }
                }
                putJsonArray("badges") {}
                putJsonArray("user_badges") {}
            }.toString()
        }

        private fun buildBadgesCatalogJson(): String {
            return buildJsonObject {
                putJsonArray("badge_groupings") {
                    add(
                        buildJsonObject {
                            put("id", 1)
                            put("name", "Getting Started")
                        }
                    )
                }
                putJsonArray("badges") {
                    add(
                        buildJsonObject {
                            put("id", 1)
                            put("name", "读者")
                            put("slug", "reader")
                            put("description", "阅读完整长贴超过 100 楼")
                            put("badge_type_id", 3)
                            put("badge_grouping_id", 1)
                            put("grant_count", 12_000)
                            put("allow_title", false)
                        }
                    )
                    add(
                        buildJsonObject {
                            put("id", 2)
                            put("name", "活跃贡献者")
                            put("slug", "contributor")
                            put("description", "积极参与开源技术讨论")
                            put("badge_type_id", 2)
                            put("badge_grouping_id", 1)
                            put("grant_count", 1_800)
                            put("allow_title", true)
                        }
                    )
                }
            }.toString()
        }

        private fun buildUserBadgesJson(): String {
            return buildJsonObject {
                putJsonArray("badge_groupings") {
                    add(
                        buildJsonObject {
                            put("id", 1)
                            put("name", "Getting Started")
                        }
                    )
                }
                putJsonArray("badges") {
                    add(
                        buildJsonObject {
                            put("id", 2)
                            put("name", "活跃贡献者")
                            put("slug", "contributor")
                            put("description", "积极参与开源技术讨论")
                            put("badge_type_id", 2)
                            put("badge_grouping_id", 1)
                            put("grant_count", 1_800)
                            put("allow_title", true)
                        }
                    )
                }
                putJsonArray("user_badges") {
                    add(
                        buildJsonObject {
                            put("id", 901)
                            put("badge_id", 2)
                            put("count", 1)
                            put("granted_at", "2026-06-01T00:00:00.000Z")
                        }
                    )
                }
            }.toString()
        }

        private fun buildSiteJson(): String {
            return buildJsonObject {
                put("can_create_tag", false)
                putJsonArray("categories") {
                    add(
                        buildJsonObject {
                            put("id", 4)
                            put("name", "开发调优")
                            put("slug", "develop")
                            put("color", "32C3C3")
                            put("description_text", "软件开发与开源实践")
                            put("read_restricted", false)
                        }
                    )
                    add(
                        buildJsonObject {
                            put("id", 14)
                            put("name", "资源荟萃")
                            put("slug", "resource")
                            put("color", "12A89D")
                            put("description_text", "软件工具与数字资源")
                            put("read_restricted", false)
                        }
                    )
                    add(
                        buildJsonObject {
                            put("id", 42)
                            put("name", "文档共建")
                            put("slug", "wiki")
                            put("color", "9CB6C4")
                            put("description_text", "技术沉淀与教程指南")
                            put("read_restricted", false)
                        }
                    )
                }
            }.toString()
        }

        private fun buildSinglePostResponseJson(
            postId: Int,
            topicId: Int,
            postNumber: Int,
            raw: String
        ): String {
            val postObj = postJson(
                id = postId,
                topicId = topicId,
                postNumber = postNumber,
                userId = 888,
                username = "lucas",
                displayName = "Lucas",
                cookedHtml = "<p>${escapeHtml(raw)}</p>",
                raw = raw
            )
            return buildJsonObject {
                put("id", postId)
                put("topic_id", topicId)
                put("post_number", postNumber)
                put("post", postObj)
            }.toString()
        }

        private fun userSummaryJson(id: Int, username: String, name: String): JsonObject = buildJsonObject {
            put("id", id)
            put("username", username)
            put("name", name)
            put("avatar_template", "/letter_avatar_proxy/v4/letter/${username.first().lowercase()}/3b82f6/{size}.png")
        }

        private fun topicListEntryJson(
            id: Int,
            title: String,
            categoryId: Int,
            postsCount: Int,
            views: Int,
            likeCount: Int,
            authorId: Int,
            tags: List<String> = emptyList(),
            closed: Boolean = false,
            excerpt: String = ""
        ): JsonObject = buildJsonObject {
            put("id", id)
            put("title", title)
            put("fancy_title", title)
            put("category_id", categoryId)
            put("posts_count", postsCount)
            put("highest_post_number", postsCount)
            put("reply_count", (postsCount - 1).coerceAtLeast(0))
            put("views", views)
            put("like_count", likeCount)
            put("closed", closed)
            put("archived", false)
            put("pinned", false)
            put("unseen", false)
            put("unread_posts", 0)
            put("excerpt", excerpt)
            put("last_posted_at", "2026-11-12T06:45:00.000Z")
            put("created_at", "2026-11-12T06:00:00.000Z")
            put("tags", JsonArray(tags.map { JsonPrimitive(it) }))
            putJsonArray("posters") {
                add(
                    buildJsonObject {
                        put("extras", "latest")
                        put("description", "Original Poster")
                        put("user_id", authorId)
                    }
                )
            }
        }

        private fun postJson(
            id: Int,
            topicId: Int,
            postNumber: Int,
            userId: Int,
            username: String,
            displayName: String,
            cookedHtml: String,
            raw: String? = null
        ): JsonObject = buildJsonObject {
            put("id", id)
            put("topic_id", topicId)
            put("post_number", postNumber)
            put("user_id", userId)
            put("username", username)
            put("name", displayName)
            put("avatar_template", "/letter_avatar_proxy/v4/letter/${username.first().lowercase()}/3b82f6/{size}.png")
            put("cooked", cookedHtml)
            if (raw != null) {
                put("raw", raw)
            }
            put("post_type", 1)
            put("created_at", "2026-11-12T06:00:00.000Z")
            put("reply_count", 0)
            put("reads", 42)
            put("score", 12.5)
            put("yours", userId == 888)
            putJsonArray("reactions") {
                if (likedPostIds.contains(id)) {
                    add(
                        buildJsonObject {
                            put("id", "heart")
                            put("type", "emoji")
                            put("count", 10)
                        }
                    )
                }
            }
        }

        private fun parseQueryParams(rawQuery: String?): Map<String, String> {
            if (rawQuery.isNullOrBlank()) return emptyMap()
            return rawQuery.split('&')
                .mapNotNull { segment ->
                    val idx = segment.indexOf('=')
                    if (idx <= 0) null
                    else {
                        val k = URLDecoder.decode(segment.substring(0, idx), StandardCharsets.UTF_8)
                        val v = URLDecoder.decode(segment.substring(idx + 1), StandardCharsets.UTF_8)
                        k to v
                    }
                }
                .toMap()
        }

        private fun escapeHtml(input: String): String {
            return input
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
        }
    }

    companion object {
        private const val CLOUDFLARE_CHALLENGE_HTML =
            "<!DOCTYPE html><html><head><title>Just a moment...</title></head>" +
                "<body><div id=\"cf-wrapper\">Checking if the site connection is secure (cf-browser-verification)</div></body></html>"

        private val ONE_BY_ONE_PNG_BYTES: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
        )

        private val BANNER_PNG_BYTES: ByteArray by lazy {
            val w = 520
            val h = 104
            val img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            val g = img.createGraphics()
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
            g.paint = java.awt.GradientPaint(
                0f, 0f, java.awt.Color(0x1E, 0x24, 0x33),
                w.toFloat(), h.toFloat(), java.awt.Color(0x2F, 0x3A, 0x52)
            )
            g.fillRoundRect(0, 0, w, h, 20, 20)
            g.color = java.awt.Color(0xF5, 0xB8, 0x2E)
            g.fillRoundRect(24, 30, 8, 44, 4, 4)
            g.color = java.awt.Color(0xF8, 0xFA, 0xFC)
            g.font = java.awt.Font("SansSerif", java.awt.Font.BOLD, 18)
            g.drawString("Lindo Cross-Platform Native Client", 46, 50)
            g.color = java.awt.Color(0xCB, 0xD5, 0xE1)
            g.font = java.awt.Font("SansSerif", java.awt.Font.PLAIN, 13)
            g.drawString("Android · macOS · Windows  |  144Hz High-Refresh Compose Skia", 46, 74)
            g.dispose()
            java.io.ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
        }
    }
}
