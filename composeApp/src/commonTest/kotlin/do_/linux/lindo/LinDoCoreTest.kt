package do_.linux.lindo

import do_.linux.lindo.core.badge.UniversalBadgeEngine
import do_.linux.lindo.core.connect.ConnectStatsParser
import do_.linux.lindo.core.connect.ScreenTrackEngine
import do_.linux.lindo.core.connect.UserPresenceService
import do_.linux.lindo.core.model.BadgeInjector
import do_.linux.lindo.core.model.GrantedBadge
import do_.linux.lindo.core.model.PostItemModel
import do_.linux.lindo.core.model.afterTailUnloadedStreamIds
import do_.linux.lindo.core.model.decodeDiscourseHtmlSnippet
import do_.linux.lindo.core.model.middleGapStreamIdsBetween
import do_.linux.lindo.core.network.CookieSentinel
import do_.linux.lindo.core.network.CookieVariant
import do_.linux.lindo.core.network.LinuxDoRepository
import do_.linux.lindo.core.network.UserApiKeyAuthManager
import do_.linux.lindo.core.render.DiscourseAstParser
import do_.linux.lindo.core.render.PostBlockNode
import do_.linux.lindo.core.site.LinuxDoEmojiCatalog
import do_.linux.lindo.core.site.LinuxDoSiteRules
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LinDoCoreTest {

    @Test
    fun officialBadgeArtworkUsesTheSiteImageOrExactSpriteId() {
        val badge = GrantedBadge(
            id = 47, name = "Devotee", icon = "far-eye", imageUrl = "/uploads/badge.svg"
        )
        assertEquals("https://linux.do/uploads/badge.svg", UniversalBadgeEngine.artworkUrl(badge))
        assertEquals(
            "https://cdn.ldstatic.com/svg-sprite/linux.do/icon/far-eye.svg",
            UniversalBadgeEngine.artworkUrl(badge.copy(imageUrl = null))
        )
        assertEquals(
            "https://cdn.ldstatic.com/svg-sprite/linux.do/icon/fab-apple.svg",
            UniversalBadgeEngine.iconUrl("fab fa-apple")
        )
        assertEquals(null, UniversalBadgeEngine.artworkUrl(badge.copy(imageUrl = null, icon = "")))
    }

    @Test
    fun topicAuthorBadgesUseTheThemePayloadWithoutCarryingPreviousOwnership() {
        val root = Json.parseToJsonElement(
            """
            {
              "user_badges": {
                "badges": {
                  "102": {"id": 102, "name": "种子用户", "badge_grouping_id": 6, "icon": "seedling"},
                  "47": {"id": 47, "name": "Devotee", "badge_grouping_id": 2, "icon": "far-eye"},
                  "1": {"id": 1, "name": "Basic", "badge_grouping_id": 4},
                  "3": {"id": 3, "name": "Regular", "badge_grouping_id": 4}
                },
                "users": {
                  "1599": {"id": 1599, "badge_ids": [47, 102, 1, 3]},
                  "1687": {"id": 1687, "badge_ids": [102]}
                }
              }
            }
            """.trimIndent()
        ).jsonObject

        val badges = assertNotNull(BadgeInjector.readTopicAuthorBadges(root))
        assertEquals(listOf(47, 102, 3), badges[1599]?.map { it.id })
        assertEquals(listOf(102), badges[1687]?.map { it.id })
        assertEquals(null, BadgeInjector.readTopicAuthorBadges(Json.parseToJsonElement("{}").jsonObject))
        assertEquals(
            emptyMap(),
            BadgeInjector.readTopicAuthorBadges(Json.parseToJsonElement("""{"user_badges":{}}""").jsonObject)
        )
    }

    @Test
    fun profileUsesFeaturedGrantIdsInTheServerOrder() {
        val root = Json.parseToJsonElement(
            """
            {
              "user": {"featured_user_badge_ids":[9002, 9001]},
              "user_badges":[
                {"id":9001,"badge_id":102,"granted_at":"2024-02-01T00:00:00Z"},
                {"id":9002,"badge_id":47,"count":2},
                {"id":9003,"badge_id":120}
              ],
              "badges":[
                {"id":102,"name":"种子用户","icon":"seedling"},
                {"id":47,"name":"Devotee","icon":"far-eye"},
                {"id":120,"name":"元气满满","icon":"angles-up"}
              ]
            }
            """.trimIndent()
        ).jsonObject
        val featured = BadgeInjector.readFeaturedBadges(root, root.getValue("user").jsonObject)
        assertEquals(listOf(47, 102), featured.map { it.id })
        assertEquals(2, featured.first().count)
        assertEquals("2024-02-01", featured.last().grantedAtText)
    }

    @Test
    fun testSiteRulesNeoSquareAvatarAndHolographicTitle() {
        assertTrue(LinuxDoSiteRules.isSquareAvatar(username = "neo"))
        assertTrue(LinuxDoSiteRules.isSquareAvatar(username = null, avatarUrl = "https://linux.do/user_avatar/linux.do/neo/120/1.png"))
        assertNotNull(LinuxDoSiteRules.matchAvatarGlowColor(username = "neo", primaryGroupName = null))
        assertNotNull(LinuxDoSiteRules.matchAvatarGlowColor(username = "shop", primaryGroupName = "g-merchant"))
        assertTrue(LinuxDoSiteRules.isHolographicTitle("种子用户"))
        assertTrue(LinuxDoSiteRules.isHolographicTitle("Premium"))
    }

    @Test
    fun testCookieSentinelDeduplicatesCriticalSessionCookies() {
        CookieSentinel.clear()
        val deduped = CookieSentinel.ingestAndDeduplicate(
            listOf(
                CookieVariant(name = "_t", value = "old_expired_t", domain = ".linux.do", hostOnly = false, updatedAtEpochMs = 100L),
                CookieVariant(name = "_t", value = "new_valid_t", domain = "linux.do", hostOnly = true, updatedAtEpochMs = 200L)
            )
        )
        val tCookies = deduped.filter { it.name == "_t" }
        assertEquals(1, tCookies.size)
        assertEquals("new_valid_t", tCookies.single().value)
    }

    @Test
    fun testDiscourseAstParserExtractsBlocksAndCaches() {
        val html = """
            <h2>标题测试</h2>
            <aside class="quote" data-username="neo" data-post="1" data-topic="9001"><blockquote>引用正文</blockquote></aside>
            <pre><code class="lang-kotlin">val x = 42</code></pre>
            <p>普通段落</p>
        """.trimIndent()

        val nodes = DiscourseAstParser.parseOrGetCached(postId = 1, cookedHtml = html)
        assertTrue(nodes.any { it is PostBlockNode.Heading })
        assertTrue(nodes.any { it is PostBlockNode.QuoteBlock })
        assertTrue(nodes.any { it is PostBlockNode.CodeBlock })
        assertTrue(nodes.any { it is PostBlockNode.Paragraph })
    }

    @Test
    fun testUserPresenceAndScreenTrackAndConnectStats() {
        // 1. UserPresenceService 在前台活跃时必须生成 Discourse-Present: true，保护连续登录天数
        UserPresenceService.setAppForeground(true, countAsActivity = true)
        val fgHeaders = UserPresenceService.buildPresenceHeaders(isBackgroundTimingRequest = false, isLoggedIn = true)
        assertEquals("true", fgHeaders["Discourse-Present"])
        assertEquals("true", fgHeaders["Discourse-Logged-In"])
        assertEquals("XMLHttpRequest", fgHeaders["X-Requested-With"])

        // 2. ScreenTrackEngine 追踪楼层阅读时长并在退避失败时重合并，绝不丢数据
        ScreenTrackEngine.resetForTest()
        val t0 = 1_000_000L
        ScreenTrackEngine.onTopicEntered(topicId = 42001, initialVisiblePosts = setOf(1, 2), nowMs = t0)
        assertEquals(1, ScreenTrackEngine.sessionTopicsEntered)

        val batch = ScreenTrackEngine.tick(nowMs = t0 + 1_200L)
        assertNotNull(batch)
        assertEquals(42001, batch.topicId)
        assertEquals(1200, batch.timingsMs[1])
        assertEquals(1200, batch.timingsMs[2])
        assertEquals(2, ScreenTrackEngine.sessionPostsRead)

        // 模拟 429 限流，验证未上报时长不会丢失而是合并回队列
        ScreenTrackEngine.onBatchSendResult(batch, statusCode = 429, nowMs = t0 + 1_200L)
        assertEquals(1, ScreenTrackEngine.pendingBatchesCount)

        // 3. ConnectStatsParser 解析 connect.linux.do HTML 卡片
        val sampleConnectHtml = """
            <div class="card">
              <h2 class="card-title">信任等级 3 要求</h2>
              <span class="badge badge-success">已达标</span>
              <p class="card-subtitle">过去 100 天内社区活跃度统计</p>
              <div class="tl3-rings">
                <div class="tl3-ring">
                  <div class="tl3-ring-circle met" style="--val: 68; --max: 50;"></div>
                  <div class="tl3-ring-label">访问天数</div>
                </div>
              </div>
              <div class="tl3-bars">
                <div class="tl3-bar-item">
                  <span class="tl3-bar-label">浏览话题</span>
                  <span class="tl3-bar-nums">312 / 200</span>
                  <div class="tl3-bar-fill met" style="--val: 312; --max: 200;"></div>
                </div>
                <div class="tl3-bar-item">
                  <span class="tl3-bar-label">已读帖子</span>
                  <span class="tl3-bar-nums">1,420 / 500</span>
                  <div class="tl3-bar-fill met" style="--val: 1420; --max: 500;"></div>
                </div>
              </div>
            </div>
        """.trimIndent()
        val report = ConnectStatsParser.parseConnectHtml(sampleConnectHtml)
        assertNotNull(report)
        assertTrue(report.isQualified)
        assertEquals(68, report.daysVisited)
        assertEquals(312, report.topicsEntered)
        assertEquals(1420, report.postsRead)
    }

    @Test
    fun testEmojiCatalogAlignment() {
        assertEquals("🫠", LinuxDoEmojiCatalog.shortcodeToUnicode("distorted_face"))
        assertEquals("🫡", LinuxDoEmojiCatalog.shortcodeToUnicode(":saluting_face:"))
        assertEquals("😮", LinuxDoEmojiCatalog.shortcodeToUnicode("open_mouth"))
        LinuxDoEmojiCatalog.registerDynamicServerEmojis(
            mapOf("bili" to listOf("bili_057" to "/uploads/default/original/3X/bili_057.png"))
        )
        assertTrue(LinuxDoEmojiCatalog.isCustomImageEmoji("bili_057"))
        assertEquals(
            "https://linux.do/uploads/default/original/3X/bili_057.png",
            LinuxDoEmojiCatalog.resolveReactionImageUrl("bili_057")
        )
    }

    @Test
    fun grantRecordIdIsDistinctFromBadgeIdAndExplicitNullClearsOldArtwork() {
        fun badge(raw: String, isGrant: Boolean = false) =
            BadgeInjector.parseGrantedBadge(Json.parseToJsonElement(raw).jsonObject, isFirstGrant = isGrant)

        badge("""{"id":146,"name":"Only Apple Can Do","icon":"fab-apple","image_url":"/previous.svg"}""")
        val current = badge("""{"id":146,"name":"Only Apple Can Do","icon":"fab-apple","image_url":null}""")
        assertEquals(null, current.imageUrl)
        val grant = badge(
            """{"id":3891204,"badge":{"id":146,"name":"Only Apple Can Do","icon":"fab-apple"},"count":2}""",
            isGrant = true
        )
        assertEquals(146, grant.id)
        assertEquals(2, grant.count)
        assertTrue(grant.isFirstTimeGrant)

        val trustBadge = badge(
            """{"id":1,"name":"基本用户","description":"<a href=\"https://blog.discourse.org/2018/06/understanding-discourse-trust-levels/\">已授予</a>所有基本社区功能"}"""
        )
        assertEquals("已授予所有基本社区功能", trustBadge.description)
        assertEquals("已授予所有基本社区功能", trustBadge.plainDescription)
    }

    @Test
    fun binaryImageFetcherUsesWebViewBridgeAndCachesBytes() = runTest {
        val prev = UserApiKeyAuthManager.webViewBinaryFetcher
        var calls = 0
        try {
            UserApiKeyAuthManager.webViewBinaryFetcher = { url ->
                calls++
                assertEquals("https://linux.do/user_avatar/linux.do/tester/120/99_2.png", url)
                UserApiKeyAuthManager.BinaryHttpPayload(
                    status = 200,
                    contentType = "image/png",
                    bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
                )
            }
            val resp1 = LinuxDoRepository.imageHttpClient.get("https://linux.do/user_avatar/linux.do/tester/120/99_2.png")
            val resp2 = LinuxDoRepository.imageHttpClient.get("https://linux.do/user_avatar/linux.do/tester/120/99_2.png?_r=1")
            assertEquals(200, resp1.status.value)
            assertEquals(200, resp2.status.value)
            assertEquals(1, calls)
            assertEquals(4, resp1.readRawBytes().size)
            assertEquals(4, resp2.readRawBytes().size)
        } finally {
            UserApiKeyAuthManager.webViewBinaryFetcher = prev
        }
    }

    @Test
    fun emojiPickerGroupsUseDistinctOfficialIconsAndNeverSparklePrefix() {
        LinuxDoEmojiCatalog.registerDynamicServerEmojis(
            linkedMapOf(
                "smileys_&_emotion" to listOf("grinning" to "/images/emoji/twemoji/grinning.png"),
                "people_&_body" to listOf("wave" to "/images/emoji/twemoji/wave.png"),
                "animals_&_nature" to listOf("bear" to "/images/emoji/twemoji/bear.png"),
                "food_&_drink" to listOf("hamburger" to "/images/emoji/twemoji/hamburger.png"),
                "feishu" to listOf("feishu_001" to "/uploads/default/original/feishu_001.png"),
                "bilibili" to listOf("bili_057" to "/uploads/default/original/bili_057.png"),
                "tieba" to listOf("tieba_087" to "/uploads/default/original/tieba_087.png")
            )
        )
        val groups = LinuxDoEmojiCatalog.getPickerGroups()
        assertTrue(groups.none { it.labelZh.contains("✨") })
        val iconKeysByLabel = groups.associate { it.labelZh to it.iconKey }
        assertEquals("feishu", iconKeysByLabel["飞书"])
        assertEquals("bilibili", iconKeysByLabel["B站"])
        assertEquals("tieba", iconKeysByLabel["贴吧"])
        assertEquals("smileys", iconKeysByLabel["笑脸"])
        assertEquals("people", iconKeysByLabel["人物与手势"])
        assertEquals("animals", iconKeysByLabel["动物与自然"])
        assertEquals("food", iconKeysByLabel["食物与饮品"])
    }

    @Test
    fun categoryHierarchySupportsLv1Lv2Lv3SubCategories() {
        val welfareSubs = LinuxDoRepository.getSubCategoriesForParent(36)
        assertEquals(listOf("Lv1", "Lv2", "Lv3"), welfareSubs.map { it.subLabel })
        val lv2 = LinuxDoRepository.findCategoryById(68)
        assertNotNull(lv2)
        assertEquals(36, lv2.parentId)
        assertEquals(36, LinuxDoRepository.resolveParentCategoryId(68))
        val resourceSubs = LinuxDoRepository.getSubCategoriesForParent(14)
        assertTrue(resourceSubs.any { it.subLabel == "网盘" })
        assertTrue(resourceSubs.any { it.subLabel == "Lv1" })
        assertEquals("福利羊毛 · Lv2", lv2.displayName)
    }

    @Test
    fun decodeDiscourseHtmlSnippetDecodesNumericHexNamedEntitiesAndEmojis() {
        val raw = """It&#39;s &ldquo;Lindo&rdquo; &#x2014; <a href="https://linux.do">社区</a> <img class="emoji" alt=":tieba_087:"> &amp; more"""
        val decoded = decodeDiscourseHtmlSnippet(raw)
        assertEquals("""It's “Lindo” — 社区 :tieba_087: & more""", decoded)
    }

    @Test
    fun topicStreamTailAndMiddleGapPreventJumpCascade() {
        val streamIds = (1..600).toList()
        fun stubPost(id: Int) = PostItemModel(
            id = id,
            postNumber = id,
            userId = 1,
            username = "tester",
            displayName = "Tester",
            avatarTemplate = "",
            createdAtText = "1h",
            cookedHtml = "<p>$id</p>"
        )
        val loadedPosts = listOf(stubPost(1), stubPost(2), stubPost(499), stubPost(500))
        val loadedIds = loadedPosts.map { it.id }.toSet()

        val afterTail = afterTailUnloadedStreamIds(streamIds, loadedPosts, loadedIds)
        assertEquals((501..600).toList(), afterTail)

        val gapBetween2And499 = middleGapStreamIdsBetween(streamIds, upperPostId = 2, lowerPostId = 499, loadedIds = loadedIds)
        assertEquals((3..498).toList(), gapBetween2And499)

        val noGap = middleGapStreamIdsBetween(streamIds, upperPostId = 499, lowerPostId = 500, loadedIds = loadedIds)
        assertEquals(emptyList(), noGap)
    }

    @Test
    fun skinToneAndGrinningTwemojiUrlsAreValid() {
        assertEquals(
            "https://linux.do/images/emoji/twemoji/wave/2.png?v=12",
            LinuxDoEmojiCatalog.resolveReactionImageUrl("wave:t2")
        )
        assertEquals(
            "https://linux.do/images/emoji/twemoji/grinning.png?v=12",
            LinuxDoEmojiCatalog.resolveReactionImageUrl("Grinning")
        )
        kotlin.test.assertFalse(LinuxDoEmojiCatalog.isCustomImageEmoji("wave:t2"))
        kotlin.test.assertNotNull(LinuxDoEmojiCatalog.shortcodeToUnicode(":wave:t2:"))
    }

    @Test
    fun binaryImageFetcherPreservesAdjacentQueryParamsWhenStrippingRetryParam() = runTest {
        val prev = UserApiKeyAuthManager.webViewBinaryFetcher
        val seenUrls = mutableListOf<String>()
        try {
            UserApiKeyAuthManager.webViewBinaryFetcher = { url ->
                seenUrls += url
                UserApiKeyAuthManager.BinaryHttpPayload(
                    status = 200,
                    contentType = "image/png",
                    bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
                )
            }
            val resp = LinuxDoRepository.imageHttpClient.get("https://linux.do/uploads/default/1.png?size=120&_r=2&v=15")
            assertEquals(200, resp.status.value)
            assertEquals(listOf("https://linux.do/uploads/default/1.png?size=120&v=15"), seenUrls)
        } finally {
            UserApiKeyAuthManager.webViewBinaryFetcher = prev
        }
    }

    @Test
    fun api403DistinguishesPermissionDeniedAuthAndCloudflareChallenge() {
        val permErr = kotlin.test.assertFailsWith<do_.linux.lindo.core.network.ApiReadException> {
            do_.linux.lindo.core.network.readApiObject(
                403 to """{"errors":["当前信任等级不足以查看该话题"],"error_type":"invalid_access"}"""
            )
        }
        assertEquals(do_.linux.lindo.core.network.ApiFailureKind.PermissionDenied, permErr.kind)
        assertEquals("当前信任等级不足以查看该话题", permErr.message)

        val authErr = kotlin.test.assertFailsWith<do_.linux.lindo.core.network.ApiReadException> {
            do_.linux.lindo.core.network.readApiObject(
                403 to """{"errors":["需要登录"],"error_type":"not_logged_in"}"""
            )
        }
        assertEquals(do_.linux.lindo.core.network.ApiFailureKind.Authentication, authErr.kind)

        val cfErr = kotlin.test.assertFailsWith<do_.linux.lindo.core.network.ApiReadException> {
            do_.linux.lindo.core.network.readApiObject(
                403 to "${do_.linux.lindo.core.network.CF_MITIGATED_CHALLENGE_PREFIX}<!DOCTYPE html><title>Just a moment...</title>"
            )
        }
        assertEquals(do_.linux.lindo.core.network.ApiFailureKind.Verification, cfErr.kind)
    }

    @Test
    fun cookieSentinelRemovesRevokedAuthCookieAndStripsCfCookiesForNonWebView() {
        CookieSentinel.clearAll()
        CookieSentinel.ingestRawCookieString("_t=live_token; _forum_session=sess1; cf_clearance=cf_ok")
        assertTrue(CookieSentinel.resolveWinningCookies().containsKey("_t"))

        val strippedHeader = CookieSentinel.buildCookieHeader(includeCloudflareCookies = false)
        assertTrue(strippedHeader.contains("_t=live_token"))
        kotlin.test.assertFalse(strippedHeader.contains("cf_clearance"))

        // Transient partial CookieManager snapshot (e.g. only cf_clearance during WebKit init) must preserve _t,
        // while explicit clearAuthCookiesOnly() removes _t and keeps cf_clearance!
        CookieSentinel.syncFromAuthoritativeBrowserCookieString("cf_clearance=cf_refreshed")
        assertTrue(CookieSentinel.resolveWinningCookies().containsKey("_t"))
        assertEquals("cf_refreshed", CookieSentinel.resolveWinningCookies()["cf_clearance"]?.value)

        CookieSentinel.clearAuthCookiesOnly()
        kotlin.test.assertFalse(CookieSentinel.resolveWinningCookies().containsKey("_t"))
        assertTrue(CookieSentinel.resolveWinningCookies().containsKey("cf_clearance"))
        CookieSentinel.clearAll()
    }

    @Test
    fun unreadDotOnlyClearsAfterServerConfirmsHighestFloor() = runTest {
        val prevHttp = UserApiKeyAuthManager.webViewHttpExecutor
        LinuxDoRepository.clearReadTopics()
        CookieSentinel.clearAll()
        CookieSentinel.ingestRawCookieString("_t=valid_test_session")
        val confirmedEvents = mutableListOf<Pair<Int, Int>>()
        val prevListener = LinuxDoRepository.onTopicReadFloorConfirmed
        LinuxDoRepository.onTopicReadFloorConfirmed = { tid, floor -> confirmedEvents += tid to floor }
        try {
            var timingsStatus = 500
            UserApiKeyAuthManager.webViewHttpExecutor = { url, method, _, _ ->
                when {
                    url.contains("/t/9001.json") && method == "GET" -> 200 to """
                        {
                          "id": 9001,
                          "title": "Server Sync Unread Test",
                          "posts_count": 10,
                          "highest_post_number": 10,
                          "last_read_post_number": 3,
                          "post_stream": {
                            "stream": [1,2,3,4,5,6,7,8,9,10],
                            "posts": [{"id": 101, "post_number": 1, "username": "alice", "cooked": "<p>Hi</p>"}]
                          }
                        }
                    """.trimIndent()
                    url.contains("/topics/timings") && method == "POST" -> timingsStatus to "{}"
                    else -> 404 to "{}"
                }
            }

            // 1. Opening topic syncs server's last_read_post_number = 3 (still < 10 -> NOT fully read!)
            val detail = LinuxDoRepository.fetchTopicDetail(9001)
            assertEquals(3, detail.lastReadPostNumber)
            assertEquals(3, LinuxDoRepository.getServerConfirmedReadFloor(9001))
            kotlin.test.assertFalse(LinuxDoRepository.isTopicRead(9001, highestPostNumber = 10))

            // 2. Failed timings POST (500) must NOT mark floor 10 as read
            val batch10 = do_.linux.lindo.core.connect.ConsolidatedTimingBatch(9001, 2000, mutableMapOf(10 to 2000))
            assertEquals(500, LinuxDoRepository.sendTopicTimings(batch10))
            assertEquals(3, LinuxDoRepository.getServerConfirmedReadFloor(9001))
            kotlin.test.assertFalse(LinuxDoRepository.isTopicRead(9001, highestPostNumber = 10))

            // 3. Confirmed 200 OK timings POST for floor 10 marks topic fully read
            timingsStatus = 200
            assertEquals(200, LinuxDoRepository.sendTopicTimings(batch10))
            assertEquals(10, LinuxDoRepository.getServerConfirmedReadFloor(9001))
            assertTrue(LinuxDoRepository.isTopicRead(9001, highestPostNumber = 10))
            assertTrue(confirmedEvents.contains(9001 to 10))
        } finally {
            LinuxDoRepository.onTopicReadFloorConfirmed = prevListener
            UserApiKeyAuthManager.webViewHttpExecutor = prevHttp
            LinuxDoRepository.clearReadTopics()
            CookieSentinel.clearAll()
        }
    }

    @Test
    fun markdownPreviewPreservesTimestampsAndBoostAllows16thEmoji() {
        val html = do_.linux.lindo.core.render.MarkdownPreview.render("构建时间 09:13:45 完成")
        assertTrue(html.contains("09:13:45"))
        kotlin.test.assertFalse(html.contains("<img"))

        val stats15 = do_.linux.lindo.core.model.BoostInputStats.compute("123456789012345")
        assertEquals(15, stats15.visibleLength)
        assertTrue(stats15.canAddEmoji)

        val blocks = do_.linux.lindo.core.render.DiscourseAstParser.parseCookedHtml(
            "<details><summary>展开详情</summary><p>隐藏正文内容</p></details>"
        )
        val detailsBlock = blocks.filterIsInstance<do_.linux.lindo.core.render.PostBlockNode.DetailsSpoilerBlock>().firstOrNull()
        assertNotNull(detailsBlock)
        assertEquals("展开详情", detailsBlock.title)
        assertEquals("隐藏正文内容", detailsBlock.innerText)

        val escapedBadge = do_.linux.lindo.core.model.GrantedBadge(
            id = 1,
            name = "基本用户",
            description = "&lt;a href=&quot;https://blog.discourse.org/2018/06/understanding-discourse-trust-levels&quot;&gt;已授予&lt;/a&gt;所有基本社区功能"
        )
        assertEquals("已授予所有基本社区功能", escapedBadge.plainDescription)
    }
}
