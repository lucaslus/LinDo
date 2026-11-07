package do_.linux.lindo

import do_.linux.lindo.core.badge.ResolvedBadgeVisual
import do_.linux.lindo.core.badge.UniversalBadgeEngine
import do_.linux.lindo.core.connect.ConnectStatsParser
import do_.linux.lindo.core.connect.ScreenTrackEngine
import do_.linux.lindo.core.connect.UserPresenceService
import do_.linux.lindo.core.model.BadgeInjector
import do_.linux.lindo.core.model.GrantedBadge
import do_.linux.lindo.core.model.PostItemModel
import do_.linux.lindo.core.network.CookieSentinel
import do_.linux.lindo.core.network.CookieVariant
import do_.linux.lindo.core.render.DiscourseAstParser
import do_.linux.lindo.core.render.PostBlockNode
import do_.linux.lindo.core.site.LinuxDoEmojiCatalog
import do_.linux.lindo.core.site.LinuxDoSiteRules
import do_.linux.lindo.ui.components.LinDoIcons
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LinDoCoreTest {

    @Test
    fun testSvgBadgeAndDynamicSpriteNeverDrop() {
        // 1. .svg URL 徽章必须识别为 SvgImage 而非普通位图
        val svgBadge = GrantedBadge(
            id = 10,
            name = "2026 SVG 徽章",
            imageUrl = "/uploads/default/original/3X/badge_new.svg"
        )
        val resolvedSvg = UniversalBadgeEngine.resolveBadgeVisual(svgBadge)
        assertIs<ResolvedBadgeVisual.SvgImage>(resolvedSvg)
        assertEquals("https://linux.do/uploads/default/original/3X/badge_new.svg", resolvedSvg.url)

        // 2. 动态注册 /svg-sprite/linux.do/ 自定义 <symbol> 后立即可解析
        val count = UniversalBadgeEngine.registerDiscourseSpriteBundle(
            """<symbol id="d-icon-custom-linuxdo-2026" viewBox="0 0 24 24"><path d="M0 0h24v24H0z"/></symbol>"""
        )
        assertEquals(1, count)

        val spriteBadge = GrantedBadge(
            id = 11,
            name = "社区动态图标徽章",
            icon = "d-icon-custom-linuxdo-2026"
        )
        val resolvedSprite = UniversalBadgeEngine.resolveBadgeVisual(spriteBadge)
        assertIs<ResolvedBadgeVisual.DynamicSpriteSvg>(resolvedSprite)

        // 3. 未知未来新徽章决不能丢失，必须回退为 MedalFallback
        val futureBadge = GrantedBadge(
            id = 12,
            name = "量子先锋",
            icon = "fa-unknown-2099-icon"
        )
        val resolvedFallback = UniversalBadgeEngine.resolveBadgeVisual(futureBadge)
        assertIs<ResolvedBadgeVisual.MedalFallback>(resolvedFallback)
        assertEquals("量", resolvedFallback.badgeInitial)
    }

    @Test
    fun testSvgMagicByteSniffing() {
        val svgBytes = """   <?xml version="1.0"?><svg viewBox="0 0 10 10"></svg>""".encodeToByteArray()
        val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        assertTrue(UniversalBadgeEngine.isSvgBytes(svgBytes))
        assertTrue(!UniversalBadgeEngine.isSvgBytes(pngBytes))
    }

    @Test
    fun testTopLevelUserBadgesInjectionSupportsBothMapAndList() {
        val sampleJson = Json.parseToJsonElement(
            """
            {
              "user_badges": {
                "badges": {
                  "1": {"id": 1, "name": "迷恋", "badge_type_id": 1, "icon": "fa-crown"},
                  "2": {"id": 2, "name": "SVG新章", "badge_type_id": 2, "image_url": "/images/new.svg"}
                },
                "users": {
                  "99": {"badge_ids": [1, 2]}
                }
              }
            }
            """.trimIndent()
        ).jsonObject

        BadgeInjector.parseAndCacheUserBadges(sampleJson)
        val user99Badges = BadgeInjector.getBadgesForUser(99)
        assertEquals(2, user99Badges.size)
        assertEquals("迷恋", user99Badges[0].name)
        assertEquals("/images/new.svg", user99Badges[1].imageUrl)
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
    fun testOnlyAppleCanDoBadgeAndEmojiCatalogAlignment() {
        // 1. Only Apple Can Do 徽章（无论来自 name、slug、fab-apple icon 还是 .svg URL）均识别为 AppleLogo 矢量图标
        val appleBadge = GrantedBadge(
            id = 205,
            name = "Only Apple Can Do",
            icon = "fab-apple",
            imageUrl = "/uploads/default/original/3X/only-apple-can-do.svg",
            slug = "only-apple-can-do",
            isFirstTimeGrant = true
        )
        assertTrue(UniversalBadgeEngine.isAppleBadge(appleBadge))
        val visual = UniversalBadgeEngine.resolveBadgeVisual(appleBadge, isDark = true)
        assertIs<ResolvedBadgeVisual.VectorGlyph>(visual)
        assertEquals(LinDoIcons.AppleLogo, visual.icon)

        // 2. 首次获取徽章在楼层注入时优先置于首位（紧跟用户名展示苹果 Logo）
        val rawPost = PostItemModel(
            id = 1001,
            postNumber = 5,
            userId = 99,
            username = "apple_fan",
            displayName = "Apple 佬友",
            userTitle = "Only Apple Can Do",
            badgesGranted = listOf(appleBadge)
        )
        val rootJson = Json.parseToJsonElement("""{"user_badges": {}}""").jsonObject
        val enriched = BadgeInjector.injectTopLevelUserBadges(listOf(rawPost), rootJson).single()
        assertTrue(enriched.badgesGranted.isNotEmpty())
        assertTrue(UniversalBadgeEngine.isAppleBadge(enriched.badgesGranted.first()))

        // 3. 官方表情包与表态映射校验
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
    fun testOfficialBadgeCatalogAndSpecialTitlesAlignment() {
        // 1. 验证官方核心荣誉与纪念徽章（种子用户、全年不落、不二之选、一元复始、骐骥驰骋、岂曰无衣?、Only Apple Can Do、活跃用户、狂热、迷恋等）
        val requiredTitles = listOf(
            "种子用户",
            "全年不落",
            "不二之选",
            "一元复始",
            "骐骥驰骋",
            "岂曰无衣?",
            "破界者",
            "非我莫属",
            "社区领袖",
            "万人迷",
            "点赞狂魔",
            "Only Apple Can Do",
            "狂热",
            "活跃用户",
            "一周年",
            "博学",
            "领航员",
            "深得人心",
            "迷恋",
            "维基编辑"
        )
        for (title in requiredTitles) {
            val resolved = UniversalBadgeEngine.resolveBadgeForUserTitle(title)
            assertNotNull(resolved, "Expected badge resolution for title: $title")
            assertTrue(resolved.allowTitle, "Expected allowTitle=true for title badge: $title")
        }

        // 2. 验证头衔视觉层级分类（全息流光 / 黄金荣誉 / Apple 专属 / 银质活跃）
        assertEquals(LinuxDoSiteRules.UserTitleVisualStyle.HOLOGRAPHIC, LinuxDoSiteRules.resolveTitleVisualStyle("种子用户"))
        assertEquals(LinuxDoSiteRules.UserTitleVisualStyle.HOLOGRAPHIC, LinuxDoSiteRules.resolveTitleVisualStyle("破界者"))
        assertEquals(LinuxDoSiteRules.UserTitleVisualStyle.GOLD_HONOR, LinuxDoSiteRules.resolveTitleVisualStyle("全年不落"))
        assertEquals(LinuxDoSiteRules.UserTitleVisualStyle.GOLD_HONOR, LinuxDoSiteRules.resolveTitleVisualStyle("不二之选"))
        assertEquals(LinuxDoSiteRules.UserTitleVisualStyle.GOLD_HONOR, LinuxDoSiteRules.resolveTitleVisualStyle("一元复始"))
        assertEquals(LinuxDoSiteRules.UserTitleVisualStyle.GOLD_HONOR, LinuxDoSiteRules.resolveTitleVisualStyle("骐骥驰骋"))
        assertEquals(LinuxDoSiteRules.UserTitleVisualStyle.GOLD_HONOR, LinuxDoSiteRules.resolveTitleVisualStyle("岂曰无衣?"))
        assertEquals(LinuxDoSiteRules.UserTitleVisualStyle.APPLE_SPECIAL, LinuxDoSiteRules.resolveTitleVisualStyle("Only Apple Can Do"))
        assertEquals(LinuxDoSiteRules.UserTitleVisualStyle.SILVER_ACTIVE, LinuxDoSiteRules.resolveTitleVisualStyle("活跃用户"))
        assertEquals(LinuxDoSiteRules.UserTitleVisualStyle.SILVER_ACTIVE, LinuxDoSiteRules.resolveTitleVisualStyle("狂热"))

        // 3. 验证官方佩戴分离规则：
        // - user_title 仅在第二行作为头衔展示（通过 resolveBadgeForUserTitle 关联徽章详情），绝不重复注入第一行 badgesGranted
        // - user_badges 中区分 user_badge.id（授予记录 ID）与 badge_id（真实徽章 ID），绝不误把授予记录 ID 当作徽章 ID
        val seedPost = PostItemModel(
            id = 2001,
            postNumber = 1,
            userId = 777,
            username = "seed_member",
            displayName = "元老佬友",
            userTitle = "全年不落"
        )
        val rootJson = Json.parseToJsonElement(
            """
            {
              "title": "Only Apple can do (徽章)",
              "user_badges": {
                "badges": [
                  {
                    "id": 146,
                    "name": "Only Apple Can Do",
                    "description": "通过 iOS / iPadOS 回复活动帖解锁",
                    "icon": "fab fa-apple",
                    "badge_type_id": 3,
                    "allow_title": true
                  }
                ],
                "user_badges": [
                  {
                    "id": 3891204,
                    "badge_id": 146,
                    "user_id": 777,
                    "post_number": 1,
                    "is_favorite": true
                  }
                ]
              }
            }
            """.trimIndent()
        ).jsonObject
        val enriched = BadgeInjector.injectTopLevelUserBadges(listOf(seedPost), rootJson).single()
        assertEquals(1, enriched.badgesGranted.size)
        assertEquals("Only Apple Can Do", enriched.badgesGranted.single().name)
        assertEquals(146, enriched.badgesGranted.single().id)
        assertTrue(enriched.badgesGranted.none { it.name == "全年不落" })
    }
}
