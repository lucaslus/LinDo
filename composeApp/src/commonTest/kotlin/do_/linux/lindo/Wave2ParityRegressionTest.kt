package do_.linux.lindo

import androidx.compose.ui.graphics.Color
import do_.linux.lindo.core.copy.NotificationCategoryFilter
import do_.linux.lindo.core.copy.SearchSortOrder
import do_.linux.lindo.core.copy.TopicNotificationLevel
import do_.linux.lindo.core.network.CookieSentinel
import do_.linux.lindo.core.network.DiscourseMessageBusClient
import do_.linux.lindo.core.network.ForceRefreshEval
import do_.linux.lindo.core.network.LinuxDoRepository
import do_.linux.lindo.core.network.ManualForceRefreshPolicy
import do_.linux.lindo.core.network.NetworkPolicyEngine
import do_.linux.lindo.core.network.SilentVerifyPolicy
import do_.linux.lindo.core.network.UserApiKeyAuthManager
import do_.linux.lindo.core.network.VerifyPresentation
import do_.linux.lindo.ui.PlatformWebLoginHost
import do_.linux.lindo.ui.formatSiteVerifyStatusLabel
import do_.linux.lindo.core.render.DiscourseAstParser
import do_.linux.lindo.core.site.AdvancedSearchFilter
import do_.linux.lindo.core.site.buildAdvancedSearchQuery
import do_.linux.lindo.core.model.PostItemModel
import do_.linux.lindo.core.model.TopicDetailModel
import do_.linux.lindo.ui.buildRevisionDiffAnnotatedString
import do_.linux.lindo.ui.collectAllImagesFromPostNodes
import do_.linux.lindo.ui.extractFootnotesFromHtml
import do_.linux.lindo.ui.extractTopicTocHeadings
import do_.linux.lindo.ui.formatMathForNativeDisplay
import do_.linux.lindo.ui.mergeMessageBusTopicRefresh
import do_.linux.lindo.ui.resolveFootnoteForAnchor
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Wave2ParityRegressionTest {

    @BeforeTest
    fun setUp() {
        UserApiKeyAuthManager.webViewHttpExecutor = null
        UserApiKeyAuthManager.requireBrowserTransportOnly = false
        UserApiKeyAuthManager.logout()
        CookieSentinel.clearAll()
        LinuxDoRepository.clearUserSessionCaches()
        LinuxDoRepository.clearAllLocalCaches()
        LinuxDoRepository.clearChallengeCooldown()
        LinuxDoRepository.clearTransientRateLimit()
        NetworkPolicyEngine.resetForTesting()
    }

    @AfterTest
    fun tearDown() {
        UserApiKeyAuthManager.webViewHttpExecutor = null
        UserApiKeyAuthManager.requireBrowserTransportOnly = false
        UserApiKeyAuthManager.logout()
        CookieSentinel.clearAll()
        LinuxDoRepository.clearUserSessionCaches()
        LinuxDoRepository.clearAllLocalCaches()
        LinuxDoRepository.clearTransientRateLimit()
        NetworkPolicyEngine.resetForTesting()
    }

    @Test
    fun p1_13_messageBusDisciplinedForegroundSyncAndBackoff() = runTest {
        var foreground = true
        var globalRateLimitMs = 0L
        val pollLog = CopyOnWriteArrayList<Map<String, Long>>()
        var nextResp: Pair<Int, String>? = 200 to """
            [
              {"channel":"/__status","data":{"/notification/42":105,"/topic/900":8}},
              {"channel":"/notification/42","message_id":106,"data":{"unread_notifications":3}},
              {"channel":"/topic/900","message_id":9,"data":{"type":"created","id":9002}}
            ]
        """.trimIndent()

        val client = DiscourseMessageBusClient(
            pollTransport = { _, cursors ->
                pollLog.add(cursors.toMap())
                nextResp
            },
            isForegroundProvider = { foreground },
            rateLimitRemainingMsProvider = { globalRateLimitMs },
            initialDelayMs = 60_000L,
            steadyIntervalMs = 12_000L,
            backgroundSleepMs = 18_000L
        )
        val notifEvents = CopyOnWriteArrayList<Pair<Long, JsonElement?>>()
        val topicEvents = CopyOnWriteArrayList<Pair<Long, JsonElement?>>()
        val unsubNotif = client.subscribe("/notification/42") { data, id -> notifEvents.add(id to data) }
        val unsubTopic = client.subscribe("/topic/900") { data, id -> topicEvents.add(id to data) }

        try {
            // 1. Foreground poll syncs /__status + dispatches events and advances cursors
            val d1 = client.pollOnceInternal()
            assertEquals(12_000L, d1)
            assertEquals(1, pollLog.size)
            assertEquals(-1L, pollLog[0]["/notification/42"])
            assertEquals(-1L, pollLog[0]["/topic/900"])
            assertEquals(106L, client.cursorFor("/notification/42"))
            assertEquals(9L, client.cursorFor("/topic/900"))
            assertEquals(1, notifEvents.size)
            assertEquals(1, topicEvents.size)
            assertEquals(0, client.consecutiveFailures)

            // 2. Background pause: skips transport completely and sleeps 18s
            foreground = false
            val dBg = client.pollOnceInternal()
            assertEquals(18_000L, dBg)
            assertEquals(1, pollLog.size, "Must NOT poll network while backgrounded")

            // 3. Respects global 429 rate-limit cooldown without firing transport
            foreground = true
            globalRateLimitMs = 25_000L
            val dRl = client.pollOnceInternal()
            assertEquals(25_000L, dRl)
            assertEquals(1, pollLog.size, "Must respect global rate-limit cooldown")

            // 4. 429 / 403 exponential backoff (45s -> 90s -> capped 180s)
            globalRateLimitMs = 0L
            nextResp = 429 to "Too Many Requests"
            val d429First = client.pollOnceInternal()
            assertEquals(45_000L, d429First)
            assertEquals(1, client.consecutiveFailures)
            val d429Second = client.pollOnceInternal()
            assertEquals(90_000L, d429Second)
            assertEquals(2, client.consecutiveFailures)
            nextResp = 403 to "Forbidden"
            val d403Third = client.pollOnceInternal()
            assertEquals(180_000L, d403Third)
            assertEquals(3, client.consecutiveFailures)
            val d403Fourth = client.pollOnceInternal()
            assertEquals(180_000L, d403Fourth, "Backoff must cap at 180s")

            // 5. Recovery resets consecutiveFailures to 0
            nextResp = 200 to "[]"
            val dRecovered = client.pollOnceInternal()
            assertEquals(12_000L, dRecovered)
            assertEquals(0, client.consecutiveFailures)
        } finally {
            unsubNotif()
            unsubTopic()
            client.stop()
        }
        assertTrue(client.activeChannels().isEmpty())
        assertFalse(client.isRunning)
    }

    @Test
    fun p1_13_messageBusTopicRefreshPreservesLoadedFloorsBeyondPage1() {
        val current = TopicDetailModel(
            id = 900,
            title = "长贴",
            categoryName = "开发调优",
            postsCount = 25,
            posts = listOf(
                PostItemModel(1, 1, 10, "alice", "Alice", cookedHtml = "<p>1楼旧版本</p>"),
                PostItemModel(21, 21, 20, "bob", "Bob", cookedHtml = "<p>21楼（第2页已加载）</p>"),
                PostItemModel(22, 22, 30, "charlie", "Charlie", cookedHtml = "<p>22楼已删除</p>")
            ),
            streamIds = listOf(1, 2, 21, 22)
        )
        val refreshedPage1 = TopicDetailModel(
            id = 900,
            title = "长贴（更新）",
            categoryName = "开发调优",
            postsCount = 26,
            posts = listOf(
                PostItemModel(1, 1, 10, "alice", "Alice", cookedHtml = "<p>1楼新修订</p>"),
                PostItemModel(2, 2, 11, "dave", "Dave", cookedHtml = "<p>2楼</p>")
            ),
            streamIds = listOf(1, 2, 21, 26)
        )
        val tailPosts = listOf(
            PostItemModel(26, 26, 40, "eve", "Eve", cookedHtml = "<p>新回复 26 楼</p>")
        )
        val merged = mergeMessageBusTopicRefresh(current, refreshedPage1, tailPosts)
        assertEquals(listOf(1, 2, 21, 26), merged.posts.map { it.id })
        assertTrue(merged.posts.first { it.id == 1 }.cookedHtml.contains("1楼新修订"))
        assertTrue(merged.posts.any { it.id == 21 && it.cookedHtml.contains("第2页已加载") })
        assertTrue(merged.posts.any { it.id == 26 && it.cookedHtml.contains("新回复 26 楼") })
        assertFalse(merged.posts.any { it.id == 22 }, "Destroyed post removed from streamIds must be pruned")
    }

    @Test
    fun p1_15_discoverHotTopicsPeriodsAndSearchSortOrderAndFilters() = runTest {
        val requestedUrls = CopyOnWriteArrayList<String>()
        UserApiKeyAuthManager.requireBrowserTransportOnly = true
        UserApiKeyAuthManager.webViewHttpExecutor = { url, _, _, _ ->
            requestedUrls.add(url)
            val tag = if (url.contains("weekly")) "weekly" else "daily"
            200 to """
                {
                  "users": [{"id": 1, "username": "alice", "name": "Alice", "avatar_template": "/user_avatar/linux.do/alice/{size}/1.png"}],
                  "topic_list": {
                    "topics": [
                      {"id": 101, "title": "Hot $tag #1", "slug": "hot-1", "posts_count": 20, "views": 1200, "like_count": 88, "posters": [{"user_id": 1}]},
                      {"id": 102, "title": "Hot $tag #2", "slug": "hot-2", "posts_count": 15, "views": 950, "like_count": 64, "posters": [{"user_id": 1}]}
                    ]
                  }
                }
            """.trimIndent()
        }

        val daily = LinuxDoRepository.fetchDiscoverHotTopics(limit = 10, period = "daily")
        val weekly = LinuxDoRepository.fetchDiscoverHotTopics(limit = 10, period = "weekly")
        assertEquals(2, daily.size)
        assertEquals("Hot daily #1", daily.first().title)
        assertEquals(2, weekly.size)
        assertEquals("Hot weekly #1", weekly.first().title)
        assertTrue(requestedUrls.any { it.contains("period=daily") })
        assertTrue(requestedUrls.any { it.contains("period=weekly") })

        // Verify 4 search sort orders including VIEWS
        assertEquals(4, SearchSortOrder.entries.size)
        assertEquals("views", SearchSortOrder.VIEWS.wireOrder)
        assertEquals("最多浏览", SearchSortOrder.VIEWS.labelCopy.text)

        // Verify advanced search query construction
        val q = buildAdvancedSearchQuery(
            keyword = "compose",
            filter = AdvancedSearchFilter(
                categorySlug = "#develop",
                tag = "android",
                author = "@alice",
                inTitle = true,
                solved = true,
                afterDateIso = "2025-11-26"
            )
        )
        assertEquals("compose #develop tags:android @alice in:title status:solved after:2025-11-26", q)
    }

    @Test
    fun p1_17_tocHeadingsAndFourNotificationLevels() {
        val htmlTwo = "<h2>第一部分</h2><p>正文</p><h3>子标题</h3>"
        assertEquals(2, extractTopicTocHeadings(htmlTwo).size)

        val htmlThree = "<h2>概述</h2><p>正文</p><h3>配置步骤</h3><blockquote><p>引用</p></blockquote><h4>常见问题</h4>"
        val headings = extractTopicTocHeadings(htmlThree)
        assertEquals(3, headings.size)
        assertEquals(2 to "概述", headings[0])
        assertEquals(3 to "配置步骤", headings[1])
        assertEquals(4 to "常见问题", headings[2])

        // All 4 Discourse notification levels present
        assertEquals(
            listOf(3, 2, 1, 0),
            listOf(
                TopicNotificationLevel.WATCHING.code,
                TopicNotificationLevel.TRACKING.code,
                TopicNotificationLevel.REGULAR.code,
                TopicNotificationLevel.MUTED.code
            )
        )
        assertTrue(TopicNotificationLevel.TRACKING.shortTitle.contains("追踪"))
        assertTrue(TopicNotificationLevel.TRACKING.detailHint.isNotBlank())
    }

    @Test
    fun p1_18_fetchPostRepliesAndRevisionDiffColoring() = runTest {
        UserApiKeyAuthManager.requireBrowserTransportOnly = true
        UserApiKeyAuthManager.webViewHttpExecutor = { url, _, _, _ ->
            if (url.contains("/posts/555/replies.json")) {
                200 to """
                    [
                      {
                        "id": 556,
                        "post_number": 9,
                        "user_id": 22,
                        "username": "bob",
                        "name": "Bob",
                        "cooked": "<p>远程拉取的子回复内容</p>",
                        "reply_to_post_number": 2,
                        "like_count": 5
                      }
                    ]
                """.trimIndent()
            } else 404 to "[]"
        }

        val replies = LinuxDoRepository.fetchPostReplies(postId = 555, topicId = 900)
        assertEquals(1, replies.size)
        assertEquals(556, replies[0].id)
        assertEquals(9, replies[0].postNumber)
        assertEquals("bob", replies[0].username)
        assertTrue(replies[0].cookedHtml.contains("远程拉取的子回复内容"))

        // Colored <ins>/<del> AnnotatedString diff
        val diff = buildRevisionDiffAnnotatedString(
            titleChangesHtml = null,
            bodyChangesHtml = "<p>旧文本 <del>已删除内容</del><ins>新增替换内容</ins> 尾段</p>",
            isDark = false,
            baseColor = Color(0xFF1F2937)
        )
        assertTrue(diff.text.contains("已删除内容"))
        assertTrue(diff.text.contains("新增替换内容"))
        assertTrue(diff.spanStyles.any { it.item.color == Color(0xFF047857) })
        assertTrue(diff.spanStyles.any { it.item.color == Color(0xFFDC2626) })
    }

    @Test
    fun p1_19_footnotesPostWideImagesAndMathFormatting() {
        val html = """
            <p>这是一段带有脚注的正文<sup class="footnote-ref"><a href="#fn1" id="fnref1">[1]</a></sup>。</p>
            <section class="footnotes">
              <ol class="footnotes-list">
                <li id="fn1" class="footnote-item"><p>第一条脚注详细解释内容 <a href="#fnref1" class="footnote-backref">↩︎</a></p></li>
                <li id="fn2" class="footnote-item"><p>第二条脚注说明</p></li>
              </ol>
            </section>
        """.trimIndent()
        val footnotes = extractFootnotesFromHtml(html)
        assertTrue(footnotes.containsKey("fn1"))
        assertTrue(footnotes.containsKey("fn2"))
        val resolved = resolveFootnoteForAnchor(footnotes, "#fn1")
        assertNotNull(resolved)
        assertTrue(resolved.label.contains("1"))
        assertTrue(resolved.text.contains("第一条脚注详细解释内容"))
        assertFalse(resolved.text.contains("↩"))

        val multiImgHtml = """
            <p><img src="https://linux.do/uploads/a.png" alt="图一"></p>
            <blockquote><p><img src="https://linux.do/uploads/b.png" alt="图二"></p></blockquote>
            <p><img src="https://linux.do/uploads/a.png" alt="图一重复"></p>
        """.trimIndent()
        val gallery = collectAllImagesFromPostNodes(DiscourseAstParser.parseCookedHtml(multiImgHtml))
        assertNotNull(gallery)
        assertEquals(2, gallery.urls.size)
        assertEquals("https://linux.do/uploads/a.png", gallery.urls[0])
        assertEquals("https://linux.do/uploads/b.png", gallery.urls[1])

        val formattedMath = formatMathForNativeDisplay("""\frac{\alpha + \beta}{\sqrt{x^2 + 1}} \leq \infty""")
        assertTrue(formattedMath.contains("α"))
        assertTrue(formattedMath.contains("β"))
        assertTrue(formattedMath.contains("√"))
        assertTrue(formattedMath.contains("≤"))
        assertTrue(formattedMath.contains("∞"))
        assertTrue(formattedMath.contains("²"))
    }

    @Test
    fun p1_20_unreadFilterAndBookmarkMetadataParsing() = runTest {
        assertTrue(NotificationCategoryFilter.entries.contains(NotificationCategoryFilter.UNREAD))
        assertEquals("未读", NotificationCategoryFilter.UNREAD.labelCopy.text)

        UserApiKeyAuthManager.requireBrowserTransportOnly = true
        UserApiKeyAuthManager.recordWebSessionLogin("alice", "Alice", "", trustLevel = 3, userId = 10)
        UserApiKeyAuthManager.webViewHttpExecutor = { url, _, _, _ ->
            if (url.contains("/u/alice/bookmarks.json")) {
                200 to """
                    {
                      "user_bookmark_list": {
                        "bookmarks": [
                          {
                            "id": 901,
                            "topic_id": 777,
                            "linked_post_number": 4,
                            "title": "深度配置指南",
                            "excerpt": "重点参考第 4 楼",
                            "name": "周末复盘必看",
                            "reminder_at": "2026-12-01T09:30:00.000Z",
                            "created_at": "2026-11-26T01:00:00.000Z",
                            "category_id": 4
                          }
                        ]
                      }
                    }
                """.trimIndent()
            } else 404 to "{}"
        }

        val bookmarks = LinuxDoRepository.fetchUserBookmarks("alice")
        assertEquals(1, bookmarks.size)
        assertEquals("周末复盘必看", bookmarks[0].bookmarkName)
        assertEquals("2026-12-01T09:30:00.000Z", bookmarks[0].bookmarkReminderAt)
    }

    @Test
    fun postImageAstExtractsAspectRatioAndSkipsTinyIconsAndEmojis() {
        val html = """
            <p>表情 <img class="emoji" src="https://linux.do/images/emoji/twemoji/smile.png" width="20" height="20" alt=":smile:"></p>
            <p><img src="https://linux.do/uploads/hero.png" width="690" height="388" alt="封面图"></p>
            <p><img src="https://linux.do/uploads/tiny.png" width="24" height="24" alt="小图标"></p>
            <p><img src="https://linux.do/uploads/default/optimized/4X/a/b/c/abc_2_500x300.jpeg" alt="优化图"></p>
            <div class="lightbox-wrapper"><a class="lightbox" href="https://linux.do/uploads/full.png"><img src="https://linux.do/uploads/full.png" alt="灯箱图"><div class="meta"><span class="informations">1080×2400 320 KB</span></div></a></div>
        """.trimIndent()
        val nodes = DiscourseAstParser.parseCookedHtml(html)
        val gallery = nodes.filterIsInstance<do_.linux.lindo.core.render.PostBlockNode.ImageGallery>().single()
        assertEquals(4, gallery.urls.size, "Emoji must stay inline and NOT enter ImageGallery")
        val heroRatio = gallery.aspectRatios[0]
        assertNotNull(heroRatio)
        assertTrue(heroRatio in 1.75f..1.80f, "Expected ~1.778 for 690x388, got $heroRatio")
        assertEquals(690, gallery.widths[0])
        assertEquals(388, gallery.heights[0])
        assertEquals(null, gallery.aspectRatios[1], "Tiny <48px images should not reserve large hero aspect ratio")
        // Optimized URL `_2_500x300.jpeg` extraction
        assertEquals(500, gallery.widths[2])
        assertEquals(300, gallery.heights[2])
        // Lightbox `.meta .informations` extraction (`1080×2400`)
        assertEquals(1080, gallery.widths[3])
        assertEquals(2400, gallery.heights[3])
        val portraitRatio = gallery.aspectRatios[3]
        assertNotNull(portraitRatio)
        assertTrue(portraitRatio in 0.44f..0.46f, "Expected ~0.45 for 1080x2400, got $portraitRatio")
    }

    @Test
    fun unifiedImageBoxSizeMatchesDesktopCssFormulaAcrossPortraitSmallWideAndLandscape() {
        // 1. Portrait 1080x2400 (ar=0.45) on 360dp phone -> capped at 360dp height, width shrinks to 162dp (NOT 360x360 square!)
        val portrait = do_.linux.lindo.ui.computeUnifiedImageBoxSize(360f, 1080, 2400, 0.45f, 360f)
        assertNotNull(portrait)
        assertTrue(portrait.widthDp in 161f..163f, "Portrait width expected ~162dp, got ${portrait.widthDp}")
        assertTrue(portrait.heightDp in 359f..360.1f, "Portrait height expected 360dp, got ${portrait.heightDp}")

        // 2. Small image 200x120 -> preserves 200x120dp instead of stretching to 360dp full width!
        val small = do_.linux.lindo.ui.computeUnifiedImageBoxSize(360f, 200, 120, 200f / 120f, 360f)
        assertNotNull(small)
        assertTrue(small.widthDp in 199.5f..200.5f, "Small width expected 200dp, got ${small.widthDp}")
        assertTrue(small.heightDp in 119.5f..120.5f, "Small height expected 120dp, got ${small.heightDp}")

        // 3. Wide banner 1200x180 -> 360x54dp instead of forcing min 120dp height!
        val banner = do_.linux.lindo.ui.computeUnifiedImageBoxSize(360f, 1200, 180, 1200f / 180f, 360f)
        assertNotNull(banner)
        assertTrue(banner.widthDp in 359.5f..360.5f)
        assertTrue(banner.heightDp in 53.5f..54.5f, "Wide banner height expected 54dp, got ${banner.heightDp}")

        // 4. Unknown image (all null) -> returns null for fallback placeholder
        assertEquals(null, do_.linux.lindo.ui.computeUnifiedImageBoxSize(360f, null, null, null, 360f))
    }

    @Test
    fun forceRefreshPolicyEnforcesSingleShotPurgeCleanIpExitAndPreservesLoginSessionCookie() {
        // Invariant 1: strictly manual + explicit Settings trigger + foreground (non-sticky!)
        assertTrue(
            ManualForceRefreshPolicy.allowForceRefresh(
                requestedForceRefresh = true,
                presentation = VerifyPresentation.MANUAL,
                isExplicitSettingsTrigger = true,
                appForeground = true
            )
        )
        assertFalse(
            ManualForceRefreshPolicy.allowForceRefresh(
                requestedForceRefresh = true,
                presentation = VerifyPresentation.SILENT,
                isExplicitSettingsTrigger = true,
                appForeground = true
            )
        )
        assertFalse(
            ManualForceRefreshPolicy.allowForceRefresh(
                requestedForceRefresh = true,
                presentation = VerifyPresentation.BLOCKING,
                isExplicitSettingsTrigger = false,
                appForeground = true
            ),
            "Reactive blocking verify must NEVER force-purge"
        )
        assertFalse(
            ManualForceRefreshPolicy.allowForceRefresh(
                requestedForceRefresh = true,
                presentation = VerifyPresentation.MANUAL,
                isExplicitSettingsTrigger = true,
                appForeground = false
            ),
            "Background must NEVER force-refresh"
        )
        assertFalse(
            SilentVerifyPolicy.shouldAllowProactivePrewarm(isAuthorized = false, reason = "cold_start"),
            "Welcome unauthenticated prewarm guard must remain intact"
        )

        // One-shot consume resets immediately to false (never sticky across modals!)
        PlatformWebLoginHost.resetForTest()
        PlatformWebLoginHost.armOneShotForceRefresh(true)
        assertTrue(PlatformWebLoginHost.consumeOneShotForceRefresh(isVerificationFlow = true))
        assertFalse(PlatformWebLoginHost.consumeOneShotForceRefresh(isVerificationFlow = true), "Must be non-sticky one-shot")
        PlatformWebLoginHost.armOneShotForceRefresh(true)
        assertFalse(PlatformWebLoginHost.consumeOneShotForceRefresh(isVerificationFlow = false), "Non-verification flow must consume & clamp to false")
        assertFalse(PlatformWebLoginHost.peekOneShotForceRefreshForTest())

        // Invariant 2: single purge only before initial load, NEVER a second time in same modal
        assertTrue(ManualForceRefreshPolicy.shouldPurgeBeforeInitialLoad(forceRefresh = true, alreadyPurgedInModal = false, isVerificationFlow = true))
        assertFalse(ManualForceRefreshPolicy.shouldPurgeBeforeInitialLoad(forceRefresh = true, alreadyPurgedInModal = true, isVerificationFlow = true))
        assertFalse(ManualForceRefreshPolicy.shouldPurgeBeforeInitialLoad(forceRefresh = false, alreadyPurgedInModal = false, isVerificationFlow = true))

        // Invariant 2b: purging CF cookies NEVER deletes _t or _forum_session login cookies!
        CookieSentinel.ingestRawCookieString("_t=secret_login_token; _forum_session=sess_123; cf_clearance=old_cf_abc; __cf_bm=bm_1", domain = "linux.do")
        CookieSentinel.purgeCfCookiesOnly(domain = "linux.do")
        val remaining = CookieSentinel.resolveWinningCookies(targetHost = "linux.do")
        assertEquals("secret_login_token", remaining["_t"]?.value, "_t login cookie MUST NEVER be purged")
        assertEquals("sess_123", remaining["_forum_session"]?.value, "_forum_session MUST NEVER be purged")
        assertEquals(null, remaining["cf_clearance"], "cf_clearance must be purged")
        assertEquals(null, remaining["__cf_bm"], "__cf_bm must be purged")

        // Invariant 3: outcome evaluation (new ticket pass, clean-IP 200 zero-loop pass, stale rejection, challenge wait)
        val staleSet = setOf("old_cf_abc")
        // Brand-new cf_clearance + origin reached -> PASS_NEW_TICKET
        assertEquals(
            ForceRefreshEval.PASS_NEW_TICKET,
            ManualForceRefreshPolicy.evaluateOutcome(
                forceRefresh = true,
                freshClearance = "brand_new_cf_xyz",
                purgedStaleClearances = staleSet,
                originReached = true,
                hasChallengeOrWidget = false,
                csrfProbeStatus = null,
                csrfProbeChallenged = false
            )
        )
        // Clean IP: cf_clearance empty after purge, origin 404 reached -> first requests single /session/csrf.json probe
        assertEquals(
            ForceRefreshEval.PROBE_CSRF_ONCE,
            ManualForceRefreshPolicy.evaluateOutcome(
                forceRefresh = true,
                freshClearance = null,
                purgedStaleClearances = staleSet,
                originReached = true,
                hasChallengeOrWidget = false,
                csrfProbeStatus = null,
                csrfProbeChallenged = false
            )
        )
        // Clean IP: /session/csrf.json returns 200 OK without cf-mitigated: challenge -> PASS cleanly (ZERO loop!)
        assertEquals(
            ForceRefreshEval.PASS_CLEAN_IP_NO_COOKIE,
            ManualForceRefreshPolicy.evaluateOutcome(
                forceRefresh = true,
                freshClearance = "",
                purgedStaleClearances = staleSet,
                originReached = true,
                hasChallengeOrWidget = false,
                csrfProbeStatus = 200,
                csrfProbeChallenged = false
            )
        )
        // Stale unchanged cf_clearance is STRICTLY rejected (NEVER accepted as passed, NEVER triggers reload loop!)
        assertEquals(
            ForceRefreshEval.REJECT_STALE_UNCHANGED,
            ManualForceRefreshPolicy.evaluateOutcome(
                forceRefresh = true,
                freshClearance = "old_cf_abc",
                purgedStaleClearances = staleSet,
                originReached = true,
                hasChallengeOrWidget = false,
                csrfProbeStatus = 200,
                csrfProbeChallenged = false
            )
        )
        // Rejected stale cf_clearance MUST be stripped before syncing to CookieSentinel so CfClearanceAuthority NEVER re-adopts it
        do_.linux.lindo.core.network.CfClearanceAuthority.reset()
        CookieSentinel.purgeCfCookiesOnly(domain = "linux.do")
        val rawWithStale = "_t=secret_login_token; _forum_session=sess_123; cf_clearance=old_cf_abc; __cf_bm=bm_1"
        val strippedStale = ManualForceRefreshPolicy.stripRejectedStaleClearance(rawWithStale, staleSet)
        assertFalse(strippedStale.contains("old_cf_abc"), "Rejected stale cf_clearance must be stripped")
        assertFalse(strippedStale.contains("cf_clearance"), "Stale cf_clearance entry must be removed")
        assertTrue(strippedStale.contains("_t=secret_login_token"), "_t login cookie must be preserved by stripRejectedStaleClearance")
        assertTrue(strippedStale.contains("_forum_session=sess_123"), "_forum_session must be preserved by stripRejectedStaleClearance")
        CookieSentinel.syncFromAuthoritativeBrowserCookieString(strippedStale, domain = "linux.do", verified = false)
        assertFalse(
            do_.linux.lindo.core.network.CfClearanceAuthority.hasHealthyIncumbent(),
            "Rejected stale cf_clearance must NEVER be whitewashed back into CfClearanceAuthority"
        )
        assertEquals(null, CookieSentinel.resolveWinningCookies(targetHost = "linux.do")["cf_clearance"])
        assertEquals("secret_login_token", CookieSentinel.resolveWinningCookies(targetHost = "linux.do")["_t"]?.value)
        assertEquals("sess_123", CookieSentinel.resolveWinningCookies(targetHost = "linux.do")["_forum_session"]?.value)

        // When both stale and brand-new cf_clearance coexist, strip ONLY stale and keep brand-new + _t
        val rawMixed = "_t=secret_login_token; cf_clearance=old_cf_abc; cf_clearance=brand_new_cf_xyz"
        val strippedMixed = ManualForceRefreshPolicy.stripRejectedStaleClearance(rawMixed, staleSet)
        assertEquals("_t=secret_login_token; cf_clearance=brand_new_cf_xyz", strippedMixed)
        CookieSentinel.syncFromAuthoritativeBrowserCookieString(strippedMixed, domain = "linux.do", verified = true)
        assertTrue(do_.linux.lindo.core.network.CfClearanceAuthority.hasHealthyIncumbent())
        assertEquals("brand_new_cf_xyz", CookieSentinel.resolveWinningCookies(targetHost = "linux.do")["cf_clearance"]?.value)
        do_.linux.lindo.core.network.CfClearanceAuthority.reset()

        // Absent cookie + /session/csrf.json challenged -> REJECT cleanly (NO infinite reload!)
        assertEquals(
            ForceRefreshEval.REJECT_CHALLENGED_OR_FAILED,
            ManualForceRefreshPolicy.evaluateOutcome(
                forceRefresh = true,
                freshClearance = "",
                purgedStaleClearances = staleSet,
                originReached = true,
                hasChallengeOrWidget = false,
                csrfProbeStatus = 403,
                csrfProbeChallenged = true
            )
        )
        // Turnstile widget mounted -> WAIT_FOR_CHALLENGE (never bypass/auto-click)
        assertEquals(
            ForceRefreshEval.WAIT_FOR_CHALLENGE,
            ManualForceRefreshPolicy.evaluateOutcome(
                forceRefresh = true,
                freshClearance = null,
                purgedStaleClearances = staleSet,
                originReached = false,
                hasChallengeOrWidget = true,
                csrfProbeStatus = null,
                csrfProbeChallenged = false
            )
        )

        // Status label formatting parity
        val now = 1_700_000_000_000L
        assertEquals("校验中…", formatSiteVerifyStatusLabel(isVerifying = true, attentionNeeded = true, lastVerifiedAtMs = now, nowMs = now))
        assertEquals("待验证", formatSiteVerifyStatusLabel(isVerifying = false, attentionNeeded = true, lastVerifiedAtMs = now, nowMs = now))
        assertEquals("刚刚已验证", formatSiteVerifyStatusLabel(isVerifying = false, attentionNeeded = false, lastVerifiedAtMs = now - 15_000L, nowMs = now))
        assertEquals("5 分钟前已验证", formatSiteVerifyStatusLabel(isVerifying = false, attentionNeeded = false, lastVerifiedAtMs = now - 5 * 60_000L, nowMs = now))
        assertEquals("刷新验证票据", formatSiteVerifyStatusLabel(isVerifying = false, attentionNeeded = false, lastVerifiedAtMs = now - 65 * 60_000L, nowMs = now))
        assertEquals("刷新验证票据", formatSiteVerifyStatusLabel(isVerifying = false, attentionNeeded = false, lastVerifiedAtMs = null, nowMs = now))
    }
}
