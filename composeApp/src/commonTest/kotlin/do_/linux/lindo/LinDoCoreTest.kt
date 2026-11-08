package do_.linux.lindo

import do_.linux.lindo.core.badge.UniversalBadgeEngine
import do_.linux.lindo.core.connect.ConnectStatsParser
import do_.linux.lindo.core.connect.ScreenTrackEngine
import do_.linux.lindo.core.connect.UserPresenceService
import do_.linux.lindo.core.model.BadgeInjector
import do_.linux.lindo.core.model.GrantedBadge
import do_.linux.lindo.core.network.CookieSentinel
import do_.linux.lindo.core.network.CookieVariant
import do_.linux.lindo.core.render.DiscourseAstParser
import do_.linux.lindo.core.render.PostBlockNode
import do_.linux.lindo.core.site.LinuxDoEmojiCatalog
import do_.linux.lindo.core.site.LinuxDoSiteRules
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
    }
}
