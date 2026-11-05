package do_.linux.lindo

import do_.linux.lindo.core.badge.ResolvedBadgeVisual
import do_.linux.lindo.core.badge.UniversalBadgeEngine
import do_.linux.lindo.core.model.BadgeInjector
import do_.linux.lindo.core.model.GrantedBadge
import do_.linux.lindo.core.network.CookieSentinel
import do_.linux.lindo.core.network.CookieVariant
import do_.linux.lindo.core.render.DiscourseAstParser
import do_.linux.lindo.core.render.PostBlockNode
import do_.linux.lindo.core.site.LinuxDoSiteRules
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
}
