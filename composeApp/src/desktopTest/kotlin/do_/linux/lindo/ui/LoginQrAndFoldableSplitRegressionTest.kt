package do_.linux.lindo.ui

import androidx.compose.ui.test.*
import do_.linux.lindo.core.compose.LocalComposerDrafts
import do_.linux.lindo.core.copy.LinDoAuthCopy
import do_.linux.lindo.core.copy.LinDoNetworkCopy
import do_.linux.lindo.core.copy.LinDoReaderCopy
import do_.linux.lindo.core.connect.ScreenTrackEngine
import do_.linux.lindo.core.connect.UserPresenceService
import do_.linux.lindo.core.network.AuthenticatedUserProfile
import do_.linux.lindo.core.network.CookieSentinel
import do_.linux.lindo.core.network.LinuxDoRepository
import do_.linux.lindo.core.network.QrLoginManager
import do_.linux.lindo.core.network.SavedAccounts
import do_.linux.lindo.core.network.UserApiKeyAuthManager
import java.net.URI
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * End-to-end UI regression tests for:
 * 1. Unified guest login flow (`GuestLoginGuideSheet` across Home / TopicReader / Messages / Profile / Expired session / Account Management with 0 saved accounts).
 * 2. Mobile <-> Desktop QR login parity (logged-in QR generation from Profile & Account Management, mode switching between Generate and Scan/Token, guest direct redeem mode).
 * 3. Android foldable wide-screen feed collapse (reader header toggle + repositioned edge ear clear of bottom reply bar + back navigation restore).
 */
@OptIn(ExperimentalTestApi::class)
class LoginQrAndFoldableSplitRegressionTest {

    private val openedWebUrls = CopyOnWriteArrayList<String>()

    @BeforeTest
    fun setUp() {
        resetGlobals()
        openedWebUrls.clear()
        UserApiKeyAuthManager.requireBrowserTransportOnly = true
        UserApiKeyAuthManager.webViewHttpExecutor = { url, method, formParams, _ ->
            handleHttp(url, method, formParams)
        }
        PlatformWebLoginHost.webLoginRenderer = { url, dismiss, _ ->
            androidx.compose.runtime.LaunchedEffect(url) { openedWebUrls.add(url) }
            androidx.compose.material3.TextButton(onClick = dismiss) {
                androidx.compose.material3.Text("测试关闭内嵌网页")
            }
        }
    }

    @AfterTest
    fun tearDown() {
        resetGlobals()
        openedWebUrls.clear()
    }

    private fun resetGlobals() {
        isAndroidRuntimeOverrideForTest = null
        PlatformQrScanner.scannerContent = null
        PlatformQrScanner.pickImageAndDecodeQr = null
        SavedAccounts.resetForTest()
        QrLoginManager.resetForTest()
        PlatformWebLoginHost.webLoginRenderer = null
        PlatformSessionRecovery.loginSession = null
        PlatformSessionRecovery.verifySession = null
        PlatformSessionRecovery.verificationMessage = null
        PlatformSessionRecovery.verifying = false
        PlatformSessionRecovery.cancelVerification = null
        UserApiKeyAuthManager.webViewHttpExecutor = null
        UserApiKeyAuthManager.webViewJsonFetcher = null
        UserApiKeyAuthManager.webViewBinaryFetcher = null
        UserApiKeyAuthManager.requireBrowserTransportOnly = false
        UserApiKeyAuthManager.openExternalUrlLauncher = null
        UserApiKeyAuthManager.onAuthStateChanged = null
        UserApiKeyAuthManager.onPersistSession = null
        UserApiKeyAuthManager.onPersistPendingKey = null
        UserApiKeyAuthManager.onPersistQrClientId = null
        UserApiKeyAuthManager.onPersistWideSplit = null
        UserApiKeyAuthManager.onPersistSplitFraction = null
        UserApiKeyAuthManager.uiWideSplitEnabled = true
        UserApiKeyAuthManager.uiSplitFraction = 0.5f
        UserApiKeyAuthManager.uiHomeSearchExpanded = false
        UserApiKeyAuthManager.uiDarkTheme = false
        UserApiKeyAuthManager.uiEnableAnimations = false
        UserApiKeyAuthManager.clearExpiredSessionNotice()
        UserApiKeyAuthManager.logout()
        CookieSentinel.clearAll()
        LocalComposerDrafts.clearAll()
        LinuxDoRepository.clearUserSessionCaches()
        LinuxDoRepository.clearAllLocalCaches()
        LinuxDoRepository.clearChallengeCooldown()
        LinuxDoRepository.clearTransientRateLimit()
        ScreenTrackEngine.resetSessionState()
        UserPresenceService.setAppForeground(true)

        var safety = 0
        while (BackNavigationBridge.hasActiveInterceptors && safety++ < 20) {
            BackNavigationBridge.dispatchBack()
        }
        BackNavigationBridge.canGoBack = false
        BackNavigationBridge.onBack = null
    }

    private fun <T> edt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun ComposeUiTest.waitForText(text: String, substring: Boolean = false, timeoutMillis: Long = 5_000L) {
        waitUntil(timeoutMillis = timeoutMillis) {
            edt { onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    private fun ComposeUiTest.waitForTextGone(text: String, substring: Boolean = false, timeoutMillis: Long = 5_000L) {
        waitUntil(timeoutMillis = timeoutMillis) {
            edt { onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isEmpty() }
        }
    }

    private fun ComposeUiTest.waitForContentDescription(description: String, timeoutMillis: Long = 5_000L) {
        waitUntil(timeoutMillis = timeoutMillis) {
            edt { onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    private fun ComposeUiTest.assertGuideSheetWithThreeOptionsAndDismiss() {
        waitForText("使用 Linuxdo 登录")
        edt {
            onAllNodesWithText("使用 Linuxdo 登录").onLast().assertIsDisplayed()
            onAllNodesWithText("应用内网页登录").assertCountEquals(0)
            onAllNodesWithText("浏览器授权").onLast().assertIsDisplayed()
            onAllNodesWithText("扫码 / 口令登录").onLast().assertIsDisplayed()
            onAllNodesWithText("Passkey", substring = true, ignoreCase = true).assertCountEquals(0)
            onAllNodesWithText("手动登录", substring = true).assertCountEquals(0)
            onAllNodesWithText("cURL", substring = true, ignoreCase = true).assertCountEquals(0)
        }
        assertTrue(openedWebUrls.isEmpty(), "Generic guest login trigger must open GuideSheet first, not WebView directly")
        edt { onAllNodesWithText("继续以游客浏览").onLast().performClick() }
        waitForTextGone("继续以游客浏览")
    }

    @Test
    fun allGenericGuestLoginEntriesOpenGuideSheetWhileQrShortcutOpensScanDirectly() =
        runDesktopComposeUiTest(width = 390, height = 840) {
            setContent { LinDoApp(systemAnimationsEnabled = false) }
            waitForText("使用 Linuxdo 登录")
            edt { onNodeWithText("先逛逛").performClick() }
            waitForText("折叠屏沉浸阅读测试主帖")

            // 1. Home top-bar "登录"
            edt { onNodeWithText("登录").performClick() }
            assertGuideSheetWithThreeOptionsAndDismiss()

            // 2. TopicReader bottom reply bar
            edt { onNodeWithText("折叠屏沉浸阅读测试主帖").performClick() }
            waitForText("登录后参与回复")
            edt { onNodeWithText("登录后参与回复").performClick() }
            assertGuideSheetWithThreeOptionsAndDismiss()
            edt { onNodeWithContentDescription("返回").performClick() }
            waitForText("消息")

            // 3. Messages tab "去登录"
            edt { onNodeWithText("消息").performClick() }
            waitForText("去登录")
            edt { onNodeWithText("去登录").performClick() }
            assertGuideSheetWithThreeOptionsAndDismiss()

            // 4. Profile top "登录"
            edt { onNodeWithText("我的").performClick() }
            waitForText("登录")
            edt { onNodeWithText("登录").performClick() }
            assertGuideSheetWithThreeOptionsAndDismiss()

            // 5. Profile "账号管理" with 0 saved accounts -> opens GuideSheet
            edt { onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToIndex(1) }
            waitForText("账号管理")
            edt { onNodeWithText("账号管理").performScrollTo().performClick() }
            assertGuideSheetWithThreeOptionsAndDismiss()

            // 6. Explicit Profile QR shortcut ("登录二维码" / "扫码 / 口令登录") jumps directly to redeem mode
            waitForText("登录二维码")
            edt { onNodeWithText("登录二维码").performScrollTo().performClick() }
            waitForText("粘贴口令")
            edt {
                onNodeWithText("确认登录").assertIsDisplayed()
                onAllNodesWithText("生成登录码").assertCountEquals(0)
            }
            assertTrue(openedWebUrls.isEmpty())
            edt { onNodeWithContentDescription("返回").performClick() }
            waitForTextGone("确认登录")
        }

    @Test
    fun expiredSessionPromptOpensUnifiedLoginGuideSheet() =
        runDesktopComposeUiTest(width = 390, height = 840) {
            SavedAccounts.configure(null) {}
            UserApiKeyAuthManager.onSessionAuthenticated(
                AuthenticatedUserProfile(userId = 42, username = "lucas", displayName = "Lucas", trustLevel = 3),
                rawCookies = "_t=valid_session_cookie"
            )
            setContent { LinDoApp(systemAnimationsEnabled = false) }
            waitForText("折叠屏沉浸阅读测试主帖")

            edt { UserApiKeyAuthManager.handleServerAuthInvalidated() }
            waitForText("登录已过期")
            edt { onNodeWithText("重新登录").performClick() }
            waitForText("重新登录 LINUX DO")
            assertGuideSheetWithThreeOptionsAndDismiss()
        }

    @Test
    fun loggedInUserCanGenerateQrAndSwitchModesFromProfileAndAccountSettings() =
        runDesktopComposeUiTest(width = 390, height = 840) {
            SavedAccounts.configure(null) {}
            UserApiKeyAuthManager.onSessionAuthenticated(
                AuthenticatedUserProfile(userId = 42, username = "lucas", displayName = "Lucas", trustLevel = 3),
                rawCookies = "_t=valid_session_cookie"
            )
            setContent { LinDoApp(systemAnimationsEnabled = false) }
            waitForText("折叠屏沉浸阅读测试主帖")

            // Entry 1: Profile -> 登录二维码
            edt { onNodeWithText("我的").performClick() }
            waitForText("@lucas")
            edt { onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToIndex(1) }
            waitForText("登录二维码")
            edt { onNodeWithText("登录二维码").performScrollTo().performClick() }

            waitForContentDescription("Lindo 跨设备登录二维码")
            edt {
                onAllNodesWithText("生成登录码").onLast().assertIsDisplayed()
                onNodeWithText("扫码 / 口令").assertIsDisplayed()
                onNodeWithText("复制登录口令").assertIsDisplayed()
                onNodeWithText("重新生成").assertIsDisplayed()
                onNodeWithText("复制登录口令").performClick()
            }
            waitForText("已复制口令")

            // Switch to Scan / Token mode inside unified sheet
            edt { onNodeWithText("扫码 / 口令").performClick() }
            waitForText("粘贴口令")
            edt { onNodeWithText("确认登录").assertIsDisplayed() }
            // Switch back to Generate mode cleanly
            edt { onAllNodesWithText("生成登录码").onLast().performClick() }
            waitForContentDescription("Lindo 跨设备登录二维码")
            edt { onNodeWithContentDescription("返回").performClick() }
            waitForTextGone("重新生成")

            // Entry 2: Account Management -> 登录二维码
            edt { onNodeWithText("账号管理").performScrollTo().performClick() }
            waitForText("添加账号")
            waitForText("扫码或复制口令登录其他设备")
            edt { onAllNodesWithText("登录二维码").onLast().performScrollTo().performClick() }
            waitForContentDescription("Lindo 跨设备登录二维码")
        }

    @Test
    fun androidFoldableWideSplitProvidesHeaderAndNonOverlappingEdgeCollapseControls() =
        runDesktopComposeUiTest(width = 760, height = 840) {
            isAndroidRuntimeOverrideForTest = true
            setContent { LinDoApp(systemAnimationsEnabled = false) }
            waitForText("使用 Linuxdo 登录")
            edt { onNodeWithText("先逛逛").performClick() }

            waitForText("折叠屏沉浸阅读测试主帖")
            waitForText("登录后参与回复")
            waitForContentDescription("收起话题列表")
            waitForContentDescription("收起侧边话题列表")

            // Verify edge handle sits cleanly above the bottom reply bar (no overlap!)
            val earBounds = edt { onNodeWithContentDescription("收起侧边话题列表").fetchSemanticsNode().boundsInRoot }
            val replyBounds = edt { onNodeWithText("登录后参与回复").fetchSemanticsNode().boundsInRoot }
            assertTrue(
                earBounds.bottom < replyBounds.top - 8f,
                "Edge collapse ear (bottom=${earBounds.bottom}) must stay cleanly above bottom reply bar (top=${replyBounds.top})"
            )

            // 1. Collapse via reader header button
            edt { onNodeWithContentDescription("收起话题列表").performClick() }
            waitForContentDescription("展开话题列表")
            waitForContentDescription("展开侧边话题列表")
            edt { onNodeWithText("列表").assertIsDisplayed() }

            // 2. Expand back via reader header button
            edt { onNodeWithContentDescription("展开话题列表").performClick() }
            waitForContentDescription("收起话题列表")
            waitForContentDescription("收起侧边话题列表")

            // 3. Collapse via edge ear and restore via Android back navigation
            edt { onNodeWithContentDescription("收起侧边话题列表").performClick() }
            waitForContentDescription("展开话题列表")
            assertTrue(edt { BackNavigationBridge.dispatchBack() }, "Back navigation must expand collapsed feed pane")
            waitForContentDescription("收起话题列表")
        }

    @Test
    fun phase3CopyCleanupHasNoPasskeyManualLoginOrAwkwardBoostWording() {
        val allAuthCopy = LinDoAuthCopy.entries.map { it.text }
        assertTrue(allAuthCopy.none { it.contains("Passkey", ignoreCase = true) }, "Passkey footnote must be removed from auth copy")
        assertTrue(allAuthCopy.none { it.contains("手动登录") || it.contains("cURL", ignoreCase = true) || it.contains("F12") }, "Manual login / cURL UI copy must be removed")
        assertEquals("使用 Linuxdo 登录", LinDoAuthCopy.IN_APP_WEB_LOGIN.text)
        assertTrue(allAuthCopy.none { it.contains("应用内网页登录") }, "Legacy '应用内网页登录' copy must be replaced with '使用 Linuxdo 登录'")

        assertEquals("说点什么…", LinDoReaderCopy.BOOST_INPUT_PLACEHOLDER.text)
        assertEquals("发短评", LinDoReaderCopy.ROCKET_BOOST_DESC.text)
        assertEquals("查看我的短评", LinDoReaderCopy.VIEW_MY_BOOST_DESC.text)
        assertEquals("发送短评", LinDoReaderCopy.SEND_ROCKET_DESC.text)
        assertEquals("收起短评", LinDoReaderCopy.COLLAPSE_ROCKET_DESC.text)
        assertEquals("删除这条短评？", LinDoReaderCopy.CONFIRM_DELETE_BOOST.text)
        assertEquals("删除短评失败", LinDoReaderCopy.BOOST_DELETE_FAILED_SHORT.text)
        assertEquals("登录后发短评", LinDoAuthCopy.GUEST_BOOST_TITLE.text)
        assertEquals("给帖子发一条简短回应", LinDoAuthCopy.GUEST_BOOST_SUBTITLE.text)
        assertEquals("给你的帖子发了短评", LinDoNetworkCopy.NOTIF_BOOSTED_POST.text)
        assertEquals("发送短评失败，请重试", LinDoNetworkCopy.BOOST_SEND_FAILED.text)
        assertEquals("删除短评失败，请重试", LinDoNetworkCopy.BOOST_DELETE_FAILED.text)

        val allReaderAndNetworkCopy = LinDoReaderCopy.entries.map { it.text } + LinDoNetworkCopy.entries.map { it.text }
        assertTrue(allReaderAndNetworkCopy.none { it.contains("助力") }, "Awkward '助力' wording must be removed from UI copy")
    }

    @Test
    fun qrRedeemThreeTabLayoutDefaultsToTokenWithoutActivatingCameraUntilSelected() =
        runDesktopComposeUiTest(width = 760, height = 840) {
            var scannerComposeCount = 0
            var scannerCurrentlyActive = false
            var pickerInvocations = 0

            PlatformQrScanner.scannerContent = { _, _ ->
                androidx.compose.runtime.DisposableEffect(Unit) {
                    scannerComposeCount++
                    scannerCurrentlyActive = true
                    onDispose {
                        scannerCurrentlyActive = false
                    }
                }
                androidx.compose.material3.Text("测试相机取景器")
            }
            PlatformQrScanner.pickImageAndDecodeQr = { onResult ->
                pickerInvocations++
                onResult(Result.failure(IllegalStateException("测试取消选图")))
            }

            setContent { LinDoApp(systemAnimationsEnabled = false) }
            waitForText("使用 Linuxdo 登录")
            edt { onNodeWithText("扫码 / 口令").performClick() }

            // 1. Default tab MUST be 「口令登录」 with token input immediately visible, ZERO camera startup,
            //    and centered compact width on foldable/wide screens (widthIn before fillMaxWidth).
            waitForText("口令登录")
            val tokenCardBounds = edt {
                onNodeWithText("口令登录").assertIsDisplayed().assertIsSelected()
                onNodeWithText("从相册识图").assertIsDisplayed().assertIsNotSelected()
                onNodeWithText("扫码登录").assertIsDisplayed().assertIsNotSelected()
                onNodeWithText("粘贴口令").assertIsDisplayed()
                onNodeWithText("确认登录").assertIsDisplayed()
                onAllNodesWithText("测试相机取景器").assertCountEquals(0)
                val tokenTabBounds = onNodeWithText("口令登录").fetchSemanticsNode().boundsInRoot
                val scanTabBounds = onNodeWithText("扫码登录").fetchSemanticsNode().boundsInRoot
                val totalTabSpan = scanTabBounds.right - tokenTabBounds.left
                assertTrue(
                    totalTabSpan <= 390f,
                    "3-tab Redeem bar must stay capped near 380dp on wide foldable screens, got $totalTabSpan"
                )
                val card = onNodeWithTag("qr_redeem_content_card").fetchSemanticsNode().boundsInRoot
                assertTrue(card.width <= 385f, "Shared Redeem card width must stay capped at <=380dp, got ${card.width}")
                card
            }
            assertEquals(0, scannerComposeCount, "Camera must NEVER initialize when opening QR redeem sheet")
            assertFalse(scannerCurrentlyActive)

            // 2. Switch to 「从相册识图」 — shared content shows gallery picker card, camera STILL off, 0dp height jump
            edt { onNodeWithText("从相册识图").performClick() }
            waitForText("从相册选择二维码截图")
            val albumCardBounds = edt {
                onNodeWithText("从相册识图").assertIsSelected()
                onNodeWithText("选择二维码图片").assertIsDisplayed().performClick()
                onAllNodesWithText("粘贴口令").assertCountEquals(0)
                onAllNodesWithText("测试相机取景器").assertCountEquals(0)
                onNodeWithTag("qr_redeem_content_card").fetchSemanticsNode().boundsInRoot
            }
            assertTrue(
                kotlin.math.abs(albumCardBounds.height - tokenCardBounds.height) <= 1f,
                "Redeem card height must stay identical (0dp jump) between 口令登录 (${tokenCardBounds.height}) and 从相册识图 (${albumCardBounds.height})"
            )
            assertEquals(1, pickerInvocations)
            assertEquals(0, scannerComposeCount)
            assertFalse(scannerCurrentlyActive)

            // 3. Switch to 「扫码登录」 — ONLY NOW does camera compose and activate, 0dp height jump
            edt { onNodeWithText("扫码登录").performClick() }
            waitForText("测试相机取景器")
            val scanCardBounds = edt {
                onNodeWithText("扫码登录").assertIsSelected()
                onNodeWithText("将镜头对准已登录设备的登录二维码").assertIsDisplayed()
                onAllNodesWithText("从相册选择二维码截图").assertCountEquals(0)
                val previewBounds = onNodeWithText("测试相机取景器").fetchSemanticsNode().boundsInRoot
                assertTrue(
                    previewBounds.width <= 245f && previewBounds.height <= 245f,
                    "Camera viewfinder must stay capped at <=240dp on wide foldable screens, got $previewBounds"
                )
                onNodeWithTag("qr_redeem_content_card").fetchSemanticsNode().boundsInRoot
            }
            assertTrue(
                kotlin.math.abs(scanCardBounds.height - tokenCardBounds.height) <= 1f,
                "Redeem card height must stay identical (0dp jump) between 口令登录 (${tokenCardBounds.height}) and 扫码登录 (${scanCardBounds.height})"
            )
            assertEquals(1, scannerComposeCount)
            assertTrue(scannerCurrentlyActive, "Camera must be active only while on 扫码登录 tab")

            // 4. Switching back to 「口令登录」 MUST immediately dispose & release camera
            edt { onNodeWithText("口令登录").performClick() }
            waitForText("粘贴口令")
            waitUntil(timeoutMillis = 3_000L) { !scannerCurrentlyActive }
            edt {
                onNodeWithText("口令登录").assertIsSelected()
                onAllNodesWithText("测试相机取景器").assertCountEquals(0)
            }
            assertFalse(scannerCurrentlyActive, "Camera must be disposed immediately upon leaving 扫码登录 tab")
        }

    private fun handleHttp(
        rawUrl: String,
        method: String,
        formParams: Map<String, String>?
    ): Pair<Int, String> {
        val path = URI(rawUrl).path ?: "/"
        val nowIso = "2026-11-25T10:00:00.000Z"
        return when {
            path == "/user-api-key.json" && method == "POST" -> {
                val pem = formParams?.get("public_key").orEmpty()
                val nonce = formParams?.get("nonce").orEmpty()
                val body = pem
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replace(Regex("\\s+"), "")
                val pubKey = KeyFactory.getInstance("RSA")
                    .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(body)))
                fun encrypt(plain: String): String {
                    val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
                    cipher.init(Cipher.ENCRYPT_MODE, pubKey)
                    return Base64.getEncoder().encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
                }
                val encPayload = encrypt("""{"key":"qr_test_key_01","nonce":"$nonce"}""")
                val encOtp = encrypt("qr_test_otp_01")
                val redirectUrl = "discourse://auth_redirect?payload=${
                    URLEncoder.encode(encPayload, "UTF-8")
                }&oneTimePassword=${URLEncoder.encode(encOtp, "UTF-8")}"
                200 to """{"redirect_url":"$redirectUrl"}"""
            }

            path == "/session/current.json" -> 200 to buildJsonObject {
                putJsonObject("current_user") {
                    put("id", 42)
                    put("username", "lucas")
                    put("name", "Lucas")
                    put("trust_level", 3)
                    put("unread_notifications", 0)
                }
            }.toString()

            path == "/categories.json" || path == "/site.json" -> 200 to buildJsonObject {
                putJsonObject("category_list") {
                    putJsonArray("categories") {
                        add(buildJsonObject {
                            put("id", 4)
                            put("name", "开发调优")
                            put("slug", "dev")
                            put("color", "2563EB")
                            put("read_restricted", false)
                        })
                    }
                }
            }.toString()

            path == "/latest.json" -> 200 to buildJsonObject {
                putJsonArray("users") {
                    add(buildJsonObject {
                        put("id", 7)
                        put("username", "neo")
                        put("name", "Neo")
                        put("avatar_template", "/letter_avatar_proxy/v4/letter/n/3b82f6/{size}.png")
                    })
                }
                putJsonObject("topic_list") {
                    putJsonArray("topics") {
                        add(buildJsonObject {
                            put("id", 901)
                            put("title", "折叠屏沉浸阅读测试主帖")
                            put("fancy_title", "折叠屏沉浸阅读测试主帖")
                            put("category_id", 4)
                            put("posts_count", 2)
                            put("highest_post_number", 2)
                            put("reply_count", 1)
                            put("views", 120)
                            put("like_count", 8)
                            put("created_at", nowIso)
                            put("last_posted_at", nowIso)
                            putJsonArray("posters") {
                                add(buildJsonObject {
                                    put("extras", "latest")
                                    put("user_id", 7)
                                })
                            }
                        })
                    }
                }
            }.toString()

            path.startsWith("/t/901") -> 200 to buildJsonObject {
                put("id", 901)
                put("title", "折叠屏沉浸阅读测试主帖")
                put("category_id", 4)
                put("posts_count", 2)
                put("highest_post_number", 2)
                put("views", 120)
                put("like_count", 8)
                putJsonObject("details") {
                    put("notification_level", 1)
                    putJsonObject("created_by") {
                        put("id", 7)
                        put("username", "neo")
                    }
                }
                putJsonObject("post_stream") {
                    putJsonArray("stream") {
                        add(JsonPrimitive(9001))
                        add(JsonPrimitive(9002))
                    }
                    putJsonArray("posts") {
                        add(buildJsonObject {
                            put("id", 9001)
                            put("topic_id", 901)
                            put("post_number", 1)
                            put("user_id", 7)
                            put("username", "neo")
                            put("name", "Neo")
                            put("cooked", "<p>这是用于验证宽屏分栏与沉浸阅读收折控制的正文内容。</p>")
                            put("post_type", 1)
                            put("created_at", nowIso)
                        })
                        add(buildJsonObject {
                            put("id", 9002)
                            put("topic_id", 901)
                            put("post_number", 2)
                            put("user_id", 8)
                            put("username", "alice")
                            put("name", "Alice")
                            put("cooked", "<p>折叠屏展开后可以一键收起左侧列表专注看帖。</p>")
                            put("post_type", 1)
                            put("created_at", nowIso)
                        })
                    }
                }
            }.toString()

            else -> 200 to "{}"
        }
    }
}
