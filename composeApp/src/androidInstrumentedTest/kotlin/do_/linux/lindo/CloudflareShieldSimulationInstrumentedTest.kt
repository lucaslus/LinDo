package do_.linux.lindo

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import do_.linux.lindo.core.diagnostics.LinDoDiagnosticLogger
import do_.linux.lindo.core.network.CfClearanceAuthority
import do_.linux.lindo.core.network.ChallengeLoopGuard
import do_.linux.lindo.core.network.CookieSentinel
import do_.linux.lindo.core.network.LinuxDoRepository
import do_.linux.lindo.core.network.NetworkPolicyEngine
import do_.linux.lindo.core.network.NetworkRecoveryPolicy
import do_.linux.lindo.core.network.UserApiKeyAuthManager
import do_.linux.lindo.ui.PlatformSessionRecovery
import do_.linux.lindo.ui.VerificationDialogResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class CloudflareShieldSimulationInstrumentedTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    internal data class InterceptedHit(
        val role: MainActivity.WebViewHostRole,
        val method: String,
        val path: String,
        val url: String,
        val cfClearance: String?,
        val isMainFrame: Boolean
    )

    private val hits = CopyOnWriteArrayList<InterceptedHit>()

    @Before
    fun setUp() {
        hits.clear()
        MainActivity.webViewTestInterceptor = null
        LinDoDiagnosticLogger.clearAllLogs()
        NetworkPolicyEngine.resetForTesting()
        CfClearanceAuthority.reset()
        CookieSentinel.clearAll()
        LinuxDoRepository.clearTransientRateLimit()
        LinuxDoRepository.clearChallengeCooldown()
        NetworkRecoveryPolicy.androidVerifying = false
        PlatformSessionRecovery.verifying = false
        PlatformSessionRecovery.verifyPillVisible = false
        PlatformSessionRecovery.forumConnection.reset()
    }

    @After
    fun tearDown() {
        MainActivity.webViewTestInterceptor = null
        scenario?.close()
        scenario = null
        NetworkPolicyEngine.resetForTesting()
        CfClearanceAuthority.reset()
        LinuxDoRepository.clearTransientRateLimit()
        LinuxDoRepository.clearChallengeCooldown()
    }

    private fun seedLoggedInPrefsAndCookies(initialCf: String? = null) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("lindo_auth_prefs", Context.MODE_PRIVATE)
        val nativeUa = WebSettings.getDefaultUserAgent(context)
        prefs.edit()
            .clear()
            .putString("ech_routing_mode", "system_default")
            .putString("user_agent", nativeUa)
            .putString("username", "jedilus")
            .putString("display_name", "jedilus")
            .putInt("user_id", 42)
            .putInt("trust_level", 3)
            .putString("raw_cookies", "_t=sim_session_t_token; _forum_session=sim_forum_sess")
            .putBoolean("ui_enable_animations", false)
            .commit()

        val latch = CountDownLatch(1)
        mainHandler.post {
            runCatching {
                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
                cm.removeAllCookies {
                    cm.setCookie("https://linux.do", "_t=sim_session_t_token; Path=/; Max-Age=5184000; Secure; HttpOnly; SameSite=Lax")
                    cm.setCookie("https://linux.do", "_forum_session=sim_forum_sess; Path=/; Secure; HttpOnly; SameSite=Lax")
                    if (!initialCf.isNullOrBlank()) {
                        cm.setCookie("https://linux.do", "cf_clearance=$initialCf; Domain=.linux.do; Path=/; Secure; HttpOnly")
                    }
                    cm.flush()
                    latch.countDown()
                }
            }.onFailure { latch.countDown() }
        }
        assertTrue("Cookie setup timed out", latch.await(5, TimeUnit.SECONDS))
    }

    private fun mintCfClearanceDelayed(token: String, delayMs: Long = 250L) {
        mainHandler.postDelayed({
            runCatching {
                val cm = MainActivity.currentWebViewCookieManager()
                cm.setCookie("https://linux.do", "cf_clearance=$token; Domain=.linux.do; Path=/; Secure; HttpOnly")
                cm.flush()
            }
        }, delayMs)
    }

    private fun readLiveCfClearance(): String? {
        val raw = runCatching { MainActivity.currentWebViewCookieManager().getCookie("https://linux.do").orEmpty() }.getOrDefault("")
        return raw.split(';')
            .map { it.trim() }
            .lastOrNull { it.startsWith("cf_clearance=") }
            ?.substringAfter("cf_clearance=")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun htmlResp(
        status: Int,
        reason: String,
        html: String,
        headers: Map<String, String> = emptyMap()
    ): WebResourceResponse = WebResourceResponse(
        "text/html",
        "utf-8",
        status,
        reason,
        mapOf("Cache-Control" to "no-store") + headers,
        ByteArrayInputStream(html.toByteArray(Charsets.UTF_8))
    )

    private fun jsonResp(
        status: Int,
        reason: String,
        json: String,
        headers: Map<String, String> = emptyMap()
    ): WebResourceResponse = WebResourceResponse(
        "application/json",
        "utf-8",
        status,
        reason,
        mapOf("Cache-Control" to "no-store") + headers,
        ByteArrayInputStream(json.toByteArray(Charsets.UTF_8))
    )

    private fun challengeResp(status: Int = 429, interactive: Boolean = true): WebResourceResponse = htmlResp(
        status = status,
        reason = if (status == 429) "Too Many Requests" else "Forbidden",
        headers = mapOf("cf-mitigated" to "challenge", "cf-ray" to "sim-ray-1"),
        html = """
            <!doctype html><html dir="ltr"><head><title>Just a moment...</title></head>
            <body>
              <div id="challenge-running">Checking your browser...</div>
              <div id="challenge-stage"><div class="cf-turnstile"></div></div>
              ${if (interactive) """<script>console.log("__lindo_cf_widget_ready__");</script>""" else ""}
            </body></html>
        """.trimIndent()
    )

    private fun origin404Resp(): WebResourceResponse = htmlResp(
        status = 404,
        reason = "Not Found",
        html = """
            <!doctype html><html><head><title>Not Found</title><meta name="csrf-token" content="sim_csrf_404"></head>
            <body><div class="page-not-found">Oops! That page doesn't exist or is private.</div></body></html>
        """.trimIndent()
    )

    private fun bridgeBootstrap200Resp(): WebResourceResponse = htmlResp(
        status = 200,
        reason = "OK",
        html = """
            <!doctype html><html><head><title>Linux.do</title><meta name="csrf-token" content="sim_csrf_bridge"></head>
            <body><header class="d-header"></header><div id="main-outlet"><div class="container"></div></div></body></html>
        """.trimIndent()
    )

    private fun empty204(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "utf-8",
        204,
        "No Content",
        emptyMap(),
        ByteArrayInputStream(ByteArray(0))
    )

    private fun countVerifyCompletions(): Int =
        LinDoDiagnosticLogger.snapshotEntries().count { entry ->
            (entry.tag == "SiteVerify" && entry.summary.startsWith("Cloudflare verification completed")) ||
                (entry.tag == "SilentVerify" && entry.summary.startsWith("Cloudflare verification completed") && !entry.summary.contains("outcome=skipped:"))
        }

    private fun launchAndWaitReady() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitUntil(timeoutMillis = 15_000L) {
            UserApiKeyAuthManager.webViewHttpExecutor != null &&
                PlatformSessionRecovery.openEmbeddedVerifyModal != null &&
                hits.any { it.path == "/latest.json" }
        }
        composeRule.waitForIdle()
        LinDoDiagnosticLogger.clearAllLogs()
        LinuxDoRepository.clearChallengeCooldown()
        LinuxDoRepository.clearTransientRateLimit()
    }

    private fun executeViaBridgeSync(
        url: String,
        method: String = "GET",
        formParams: Map<String, String>? = null,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 15_000L
    ): Pair<Int, String>? {
        val exec = UserApiKeyAuthManager.webViewHttpExecutor!!
        val resRef = AtomicReference<Pair<Int, String>?>(null)
        val done = AtomicBoolean(false)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                resRef.set(exec(url, method, formParams, headers))
            } finally {
                done.set(true)
            }
        }
        composeRule.waitUntil(timeoutMillis = timeoutMs) { done.get() }
        return resRef.get()
    }

    /**
     * S1（复现并根治 v1.1.14 19:45–19:46 现场）：
     * - `/challenge` 永远 404 不弹盾；
     * - 业务接口 `/t/3262685.json` 持续 429 + cf-mitigated: challenge；
     * - 顶层打开被拦地址也直接返回源站 JSON（不弹盾）；
     * 验证熔断器在首次复发后即进入 20s 退避，停止循环并隐藏胶囊，不再反复开窗。
     */
    @Test
    fun s1_unchallengedVerifyPageWithPersistent429Challenge_mustNotLoop() {
        seedLoggedInPrefsAndCookies(initialCf = "sim_cf_stale_v1114")
        val inject429Fault = AtomicBoolean(false)

        MainActivity.webViewTestInterceptor = MainActivity.WebViewTestInterceptor { role, req ->
            val host = req.url?.host.orEmpty()
            val path = req.url?.path.orEmpty()
            val method = req.method.orEmpty().uppercase()
            val liveCf = readLiveCfClearance()
            hits.add(InterceptedHit(role, method, path, req.url.toString(), liveCf, req.isForMainFrame))
            if (!host.equals("linux.do", ignoreCase = true)) return@WebViewTestInterceptor empty204()
            when {
                path == "/about" -> bridgeBootstrap200Resp()
                path.startsWith("/challenge") -> origin404Resp()
                path == "/session/csrf.json" -> jsonResp(200, "OK", """{"csrf":"sim_csrf_bridge"}""")
                path == "/site.json" -> jsonResp(200, "OK", """{"categories":[]}""")
                path == "/session/current.json" -> jsonResp(200, "OK", """{"current_user":{"id":42,"username":"jedilus","name":"jedilus","trust_level":3}}""")
                path == "/notifications.json" -> jsonResp(200, "OK", """{"notifications":[]}""")
                path.startsWith("/message-bus/") -> jsonResp(404, "Not Found", "Not Found.")
                !inject429Fault.get() && path == "/latest.json" -> jsonResp(200, "OK", """{"topic_list":{"topics":[]}}""")
                inject429Fault.get() && role == MainActivity.WebViewHostRole.API_BRIDGE &&
                    (path == "/latest.json" || path.startsWith("/t/") || path.contains("/boosts.json")) -> challengeResp(429)
                path == "/latest.json" -> jsonResp(200, "OK", """{"topic_list":{"topics":[]}}""")
                path.startsWith("/t/") -> jsonResp(200, "OK", """{"id":3262685,"post_stream":{"posts":[]}}""")
                else -> jsonResp(200, "OK", "{}")
            }
        }

        launchAndWaitReady()
        inject429Fault.set(true)

        val modalSessionStart = MainActivity.currentModalSessionNonceForTest()
        var sawBackoffResponse = false
        repeat(6) { idx ->
            val res = executeViaBridgeSync("https://linux.do/t/3262685.json?track_visit=true")
            val body = res?.second.orEmpty()
            if (body.contains("站点暂时限制访问")) sawBackoffResponse = true
            val looksLikeVerifyError = res != null && (
                body.contains("<!--lindo-cf-challenge-->") ||
                    body.contains("Just a moment") ||
                    body.contains("需要完成站点验证")
                )
            Log.i("ShieldSimS1", "round=${idx + 1} status=${res?.first} verifyErr=$looksLikeVerifyError backoff=${NetworkPolicyEngine.challengeLoopGuard.isBackingOff()} body=${body.take(90)}")
            if (NetworkPolicyEngine.verifyAttentionNeeded || PlatformSessionRecovery.verifyPillVisible || looksLikeVerifyError) {
                val before = MainActivity.currentModalSessionNonceForTest()
                PlatformSessionRecovery.onExpandVerifyPill?.let { expand -> mainHandler.post { expand() } }
                val tapDeadline = System.currentTimeMillis() + 5_000L
                composeRule.waitUntil(timeoutMillis = 5_500L) {
                    val opened = MainActivity.currentModalSessionNonceForTest() > before
                    (opened && !NetworkRecoveryPolicy.androidVerifying && !NetworkPolicyEngine.verifyCoordinator.isActive()) ||
                        System.currentTimeMillis() >= tapDeadline
                }
            }
            val pauseUntil = System.currentTimeMillis() + 450L
            composeRule.waitUntil(timeoutMillis = 1_500L) { System.currentTimeMillis() >= pauseUntil }
        }

        val modalSessionsOpened = MainActivity.currentModalSessionNonceForTest() - modalSessionStart
        val completions = countVerifyCompletions()
        val guardLogged = LinDoDiagnosticLogger.snapshotEntries().any { it.tag == "ChallengeLoopGuard" }
        val logs = LinDoDiagnosticLogger.snapshotEntries()
            .filter { it.tag in setOf("SiteVerify", "SilentVerify", "ChallengeLoopGuard") }
            .joinToString("\n") { "[${it.tag}] ${it.summary}" }
        Log.i("ShieldSimS1", "SUMMARY: completions=$completions modalSessionsOpened=$modalSessionsOpened guardLogged=$guardLogged\n$logs")
        assertTrue("Expected <= 2 completions, got $completions\n$logs", completions <= 2)
        assertTrue("Expected <= 1 modal session, got $modalSessionsOpened\n$logs", modalSessionsOpened <= 1)
        assertTrue("Expected ChallengeLoopGuard backoff armed\n$logs", NetworkPolicyEngine.challengeLoopGuard.isBackingOff())
        assertTrue("Expected backoff message in API response\n$logs", sawBackoffResponse)
        assertTrue("Expected ChallengeLoopGuard diagnostic log entry\n$logs", guardLogged)
        assertFalse("Attention pill must stay hidden during backoff", NetworkPolicyEngine.verifyAttentionNeeded)
        assertFalse("Verify pill must stay hidden during backoff", PlatformSessionRecovery.verifyPillVisible)
    }

    /**
     * S2（可解的路径级挑战）：
     * - `/challenge` 永远 404 不弹盾；
     * - `/t/3262685.json` 仅在无 `sim_cf_s2_solved` 时 403 challenge；
     * - 顶层打开 `/t/3262685.json` 返回挑战页，250ms 后签发新票据 `sim_cf_s2_solved`；
     * 断言：验证流程实际打开被拦路径（非 `/challenge`），自动恢复 200 JSON，且熔断器清零。
     */
    @Test
    fun s2_pathSpecificChallenge_opensBlockedUrlAndRecovers() {
        seedLoggedInPrefsAndCookies(initialCf = "sim_cf_s2_stale")
        val targetSolvedMinted = AtomicBoolean(false)

        MainActivity.webViewTestInterceptor = MainActivity.WebViewTestInterceptor { role, req ->
            val host = req.url?.host.orEmpty()
            val path = req.url?.path.orEmpty()
            val method = req.method.orEmpty().uppercase()
            val liveCf = readLiveCfClearance()
            hits.add(InterceptedHit(role, method, path, req.url.toString(), liveCf, req.isForMainFrame))
            if (!host.equals("linux.do", ignoreCase = true)) return@WebViewTestInterceptor empty204()
            when {
                path == "/about" -> bridgeBootstrap200Resp()
                path.startsWith("/challenge") -> origin404Resp()
                path == "/session/csrf.json" -> jsonResp(200, "OK", """{"csrf":"sim_csrf_s2"}""")
                path == "/site.json" -> jsonResp(200, "OK", """{"categories":[]}""")
                path == "/session/current.json" -> jsonResp(200, "OK", """{"current_user":{"id":42,"username":"jedilus","name":"jedilus","trust_level":3}}""")
                path == "/notifications.json" -> jsonResp(200, "OK", """{"notifications":[]}""")
                path == "/latest.json" -> jsonResp(200, "OK", """{"topic_list":{"topics":[]}}""")
                path.startsWith("/message-bus/") -> jsonResp(404, "Not Found", "Not Found.")
                path == "/t/3262685.json" && liveCf == "sim_cf_s2_solved" ->
                    jsonResp(200, "OK", """{"id":3262685,"post_stream":{"posts":[{"id":1,"cooked":"<p>ok</p>"}]}}""")
                path == "/t/3262685.json" && role == MainActivity.WebViewHostRole.API_BRIDGE ->
                    challengeResp(403)
                path == "/t/3262685.json" && (role == MainActivity.WebViewHostRole.SILENT_VERIFY || role == MainActivity.WebViewHostRole.INTERACTIVE_MODAL) -> {
                    if (targetSolvedMinted.compareAndSet(false, true)) {
                        mintCfClearanceDelayed("sim_cf_s2_solved", delayMs = 250L)
                    }
                    challengeResp(403, interactive = false)
                }
                else -> jsonResp(200, "OK", "{}")
            }
        }

        launchAndWaitReady()
        hits.clear()

        val res = executeViaBridgeSync("https://linux.do/t/3262685.json?track_visit=true")
        assertEquals("Expected recovered 200 JSON on /t/3262685.json", 200, res?.first)
        assertTrue("Expected post_stream in recovered body", res?.second?.contains("post_stream") == true)

        val verifyHits = hits.filter {
            it.role == MainActivity.WebViewHostRole.SILENT_VERIFY || it.role == MainActivity.WebViewHostRole.INTERACTIVE_MODAL
        }
        assertTrue(
            "Expected verification WebView to navigate to /t/3262685.json?track_visit=false, actual=${verifyHits.map { it.url }}",
            verifyHits.any { it.path == "/t/3262685.json" && it.url.contains("track_visit=false") }
        )
        assertFalse("Must not fall back to /challenge when target is known", verifyHits.any { it.path.startsWith("/challenge") })
        assertFalse("Must not be backing off after recovery", NetworkPolicyEngine.challengeLoopGuard.isBackingOff())
        assertNull("Healthy response on /t/* must clear lastTarget", NetworkPolicyEngine.challengeLoopGuard.currentTarget())
    }

    /**
     * S3（回归保护：只有 `/challenge` 弹盾）：
     * 无被拦目标时，手动触发验证仍打开 `/challenge`，签发新票据 `sim_cf_s3_solved` 后自动关窗放行。
     */
    @Test
    fun s3_challengeEndpointIssuesTicket_regressionProtection() {
        seedLoggedInPrefsAndCookies(initialCf = null)
        val minted = AtomicBoolean(false)

        MainActivity.webViewTestInterceptor = MainActivity.WebViewTestInterceptor { role, req ->
            val host = req.url?.host.orEmpty()
            val path = req.url?.path.orEmpty()
            val method = req.method.orEmpty().uppercase()
            val liveCf = readLiveCfClearance()
            hits.add(InterceptedHit(role, method, path, req.url.toString(), liveCf, req.isForMainFrame))
            if (!host.equals("linux.do", ignoreCase = true)) return@WebViewTestInterceptor empty204()
            when {
                path == "/about" -> bridgeBootstrap200Resp()
                path == "/session/csrf.json" -> jsonResp(200, "OK", """{"csrf":"sim_csrf_s3"}""")
                path == "/site.json" -> jsonResp(200, "OK", """{"categories":[]}""")
                path == "/session/current.json" -> jsonResp(200, "OK", """{"current_user":{"id":42,"username":"jedilus","name":"jedilus","trust_level":3}}""")
                path == "/notifications.json" -> jsonResp(200, "OK", """{"notifications":[]}""")
                path.startsWith("/message-bus/") -> jsonResp(404, "Not Found", "Not Found.")
                path.startsWith("/challenge") && role == MainActivity.WebViewHostRole.INTERACTIVE_MODAL -> {
                    if (minted.compareAndSet(false, true)) {
                        mintCfClearanceDelayed("sim_cf_s3_solved", delayMs = 250L)
                    }
                    challengeResp(403, interactive = true)
                }
                path == "/latest.json" -> jsonResp(200, "OK", """{"topic_list":{"topics":[]}}""")
                else -> jsonResp(200, "OK", "{}")
            }
        }

        launchAndWaitReady()
        hits.clear()

        val before = MainActivity.currentModalSessionNonceForTest()
        PlatformSessionRecovery.onExpandVerifyPill?.let { expand -> mainHandler.post { expand() } }
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            MainActivity.currentModalSessionNonceForTest() > before &&
                !NetworkRecoveryPolicy.androidVerifying &&
                !NetworkPolicyEngine.verifyCoordinator.isActive()
        }

        val modalHits = hits.filter { it.role == MainActivity.WebViewHostRole.INTERACTIVE_MODAL }
        assertTrue("Expected modal to open /challenge, got ${modalHits.map { it.path }}", modalHits.any { it.path.startsWith("/challenge") })
        assertEquals("sim_cf_s3_solved", readLiveCfClearance())
        assertTrue(
            "Expected SiteVerify completed log with effective=true",
            LinDoDiagnosticLogger.snapshotEntries().any { it.tag == "SiteVerify" && it.summary.contains("effective=true") }
        )
        assertFalse(NetworkPolicyEngine.challengeLoopGuard.isBackingOff())
    }

    /**
     * S4（退避期间所有前端与回调开窗入口均被门禁拦截）：
     * 进入退避后，依次尝试点击设置页「站点安全验证」、调用 `onExpandVerifyPill`、调用 `openEmbeddedVerifyModal`，
     * 断言 0 新弹窗且显示倒计时提示「站点暂时限制访问」。
     */
    @Test
    fun s4_manualSettingsAndPillBlockedDuringBackoff() {
        seedLoggedInPrefsAndCookies(initialCf = "sim_cf_s4")
        MainActivity.webViewTestInterceptor = MainActivity.WebViewTestInterceptor { role, req ->
            val host = req.url?.host.orEmpty()
            val path = req.url?.path.orEmpty()
            val method = req.method.orEmpty().uppercase()
            hits.add(InterceptedHit(role, method, path, req.url.toString(), readLiveCfClearance(), req.isForMainFrame))
            if (!host.equals("linux.do", ignoreCase = true)) return@WebViewTestInterceptor empty204()
            when {
                path == "/about" -> bridgeBootstrap200Resp()
                path == "/session/csrf.json" -> jsonResp(200, "OK", """{"csrf":"sim_csrf_s4"}""")
                path == "/site.json" -> jsonResp(200, "OK", """{"categories":[]}""")
                path == "/session/current.json" -> jsonResp(200, "OK", """{"current_user":{"id":42,"username":"jedilus","name":"jedilus","trust_level":3}}""")
                path == "/notifications.json" -> jsonResp(200, "OK", """{"notifications":[]}""")
                path == "/latest.json" -> jsonResp(200, "OK", """{"topic_list":{"topics":[]}}""")
                path.startsWith("/message-bus/") -> jsonResp(404, "Not Found", "Not Found.")
                else -> jsonResp(200, "OK", "{}")
            }
        }

        launchAndWaitReady()

        // 触发真实熔断退避（20s）
        val now = System.currentTimeMillis()
        NetworkPolicyEngine.challengeLoopGuard.onForegroundChallenge("https://linux.do/t/3262685.json", "GET", 429, nowMs = now - 5_000L)
        NetworkPolicyEngine.challengeLoopGuard.onVerifyCompleted(effective = false, nowMs = now - 3_000L)
        val decision = NetworkPolicyEngine.challengeLoopGuard.onForegroundChallenge("https://linux.do/t/3262685.json", "GET", 429, nowMs = now)
        assertTrue(decision is ChallengeLoopGuard.Decision.Backoff)
        NetworkPolicyEngine.syncChallengeLoopBackoffUi(now)

        val modalBefore = MainActivity.currentModalSessionNonceForTest()

        // 1. 切到「我的」并点击「站点安全验证」
        composeRule.onNodeWithText("我的").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodes(hasScrollToNodeAction()).onLast().performScrollToNode(hasText("站点安全验证"))
        composeRule.onNodeWithTag("settings_site_verify_row").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("站点暂时限制访问", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        // 2. 调用 onExpandVerifyPill
        PlatformSessionRecovery.onExpandVerifyPill?.let { expand -> mainHandler.post { expand() } }
        composeRule.waitForIdle()

        // 3. 调用 openEmbeddedVerifyModal -> 必须立即回调 Unavailable 且不开窗
        val embeddedResult = AtomicReference<VerificationDialogResult?>(null)
        mainHandler.post {
            PlatformSessionRecovery.openEmbeddedVerifyModal?.invoke { embeddedResult.set(it) }
        }
        composeRule.waitUntil(timeoutMillis = 5_000L) { embeddedResult.get() != null }

        assertEquals(VerificationDialogResult.Unavailable, embeddedResult.get())
        assertEquals("No verification modal may open during backoff", modalBefore, MainActivity.currentModalSessionNonceForTest())
        assertFalse(NetworkRecoveryPolicy.androidVerifying)
    }

    /**
     * S5（后台 timings 常驻 403 不误触发熔断与弹窗）：
     * 前台正常，后台 `POST /topics/timings` 连续 5 次返回 403 challenge，
     * 断言不触发退避、不弹窗、不毁健康票据。
     */
    @Test
    fun s5_backgroundTimings403Storm_doesNotTriggerBackoffOrModal() {
        seedLoggedInPrefsAndCookies(initialCf = "sim_cf_s5_healthy")
        MainActivity.webViewTestInterceptor = MainActivity.WebViewTestInterceptor { role, req ->
            val host = req.url?.host.orEmpty()
            val path = req.url?.path.orEmpty()
            val method = req.method.orEmpty().uppercase()
            hits.add(InterceptedHit(role, method, path, req.url.toString(), readLiveCfClearance(), req.isForMainFrame))
            if (!host.equals("linux.do", ignoreCase = true)) return@WebViewTestInterceptor empty204()
            when {
                path == "/about" -> bridgeBootstrap200Resp()
                path == "/session/csrf.json" -> jsonResp(200, "OK", """{"csrf":"sim_csrf_s5"}""")
                path == "/site.json" -> jsonResp(200, "OK", """{"categories":[]}""")
                path == "/session/current.json" -> jsonResp(200, "OK", """{"current_user":{"id":42,"username":"jedilus","name":"jedilus","trust_level":3}}""")
                path == "/notifications.json" -> jsonResp(200, "OK", """{"notifications":[]}""")
                path == "/latest.json" -> jsonResp(200, "OK", """{"topic_list":{"topics":[]}}""")
                path.startsWith("/message-bus/") -> jsonResp(404, "Not Found", "Not Found.")
                path.contains("/topics/timings") -> challengeResp(403)
                else -> jsonResp(200, "OK", "{}")
            }
        }

        launchAndWaitReady()
        CfClearanceAuthority.selectWinner(listOf("sim_cf_s5_healthy"), verified = true)

        val modalBefore = MainActivity.currentModalSessionNonceForTest()
        repeat(5) {
            executeViaBridgeSync(
                url = "https://linux.do/topics/timings",
                method = "POST",
                formParams = mapOf("topic_id" to "1", "topic_time" to "1000"),
                headers = mapOf("Discourse-Background" to "true")
            )
        }
        val fgRes = executeViaBridgeSync("https://linux.do/latest.json?no_definitions=true")
        assertEquals(200, fgRes?.first)
        assertFalse("Background timings 403 must never trigger loop-guard backoff", NetworkPolicyEngine.challengeLoopGuard.isBackingOff())
        assertEquals("Background timings 403 must never open verify modal", modalBefore, MainActivity.currentModalSessionNonceForTest())
        assertTrue("Healthy cf_clearance must remain intact", CfClearanceAuthority.hasHealthyIncumbent())
    }
}
