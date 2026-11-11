package do_.linux.lindo

import do_.linux.lindo.core.render.*
import do_.linux.lindo.core.network.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

class RichContentAndLoadingTest {
    @Test fun preservesNestedInlineStylesAndSafeLinks() {
        val p = DiscourseAstParser.parseCookedHtml("<p>before <strong><em><a href='/t/12'>linked</a></em></strong> after</p>").single() as PostBlockNode.Paragraph
        assertEquals("before linked after", p.text)
        assertTrue(p.rich.runs.any { it.bold && it.italic && it.href == "https://linux.do/t/12" && it.text == "linked" })
    }
    @Test fun imagesOutsideParagraphsAndNestedLightboxesRetainOrder() {
        val nodes = DiscourseAstParser.parseCookedHtml(
            "<p>before<img src='//example.test/a.png' alt='caption'>after</p>" +
                "<div class='lightbox-wrapper'><a class='lightbox' href='https://linux.do/uploads/default/original/4X/hd.jpeg'>" +
                "<img src='https://linux.do/uploads/default/optimized/4X/thumb_2_345x500.jpeg' srcset='https://linux.do/uploads/default/optimized/4X/thumb_2_345x500.jpeg, https://linux.do/uploads/default/optimized/4X/thumb_2_690x1000.jpeg 2x'>" +
                "</a></div>"
        )
        assertEquals(listOf("before", "image", "after", "image"), nodes.map { if (it is PostBlockNode.Paragraph) it.text else "image" })
        val images = nodes.filterIsInstance<PostBlockNode.ImageGallery>()
        assertEquals("https://example.test/a.png", images[0].urls.single())
        assertEquals("caption", images[0].captions.single())
        assertEquals("https://linux.do/uploads/default/optimized/4X/thumb_2_690x1000.jpeg", images[1].urls.single())
        assertEquals("https://linux.do/uploads/default/original/4X/hd.jpeg", images[1].fullUrls.single())
    }
    @Test fun nestedListsAndOrderedOffsetsSurvive() {
        val list = DiscourseAstParser.parseCookedHtml("<ol start='3'><li>one<ul><li>nested</li></ul></li><li>two</li></ol>").single() as PostBlockNode.BulletList
        assertEquals(3, list.start)
        assertEquals(2, list.items.size)
        assertIs<PostBlockNode.BulletList>(list.itemBlocks.first().last())
    }
    @Test fun quotesAndDetailsKeepRichChildren() {
        val nodes = DiscourseAstParser.parseCookedHtml("<aside class='quote' data-username='sample'><blockquote><p><b>quoted</b></p></blockquote></aside><details><summary>title</summary><ul><li>body</li></ul></details>")
        val quote = nodes[0] as PostBlockNode.QuoteBlock
        assertTrue((quote.children.single() as PostBlockNode.Paragraph).rich.runs.single().bold)
        val detail = nodes[1] as PostBlockNode.DetailsSpoilerBlock
        assertEquals("title", detail.title)
        assertIs<PostBlockNode.BulletList>(detail.children.single())
    }
    @Test fun tablesAndMergedCellFallbackKeepAllContent() {
        val nodes = DiscourseAstParser.parseCookedHtml("<table><tr><th colspan='2'>head</th></tr><tr><td>A</td><td><a href='/'>B</a></td></tr></table>")
        val table = nodes.first() as PostBlockNode.Table
        assertEquals(setOf(0), table.headerRows)
        assertEquals(2, table.rows.size)
        assertEquals("B", (table.rows[1][1].single() as PostBlockNode.Paragraph).text)
        assertIs<PostBlockNode.Unsupported>(nodes.last())
    }
    @Test fun entitiesEmojiAndCodeWhitespaceArePreserved() {
        val nodes = DiscourseAstParser.parseCookedHtml("<p>&#x1F680; &copy; <img class='emoji' alt=':custom:' src='/emoji/x.png'></p><pre><code class='language-kotlin'>a\n    b &lt;c&gt;</code></pre>")
        assertEquals("🚀 © :custom:", (nodes.first() as PostBlockNode.Paragraph).text)
        assertEquals("a\n    b <c>", (nodes.last() as PostBlockNode.CodeBlock).code)
    }
    @Test fun unsupportedInteractiveContentGetsAnExplicitNode() {
        val nodes = DiscourseAstParser.parseCookedHtml("<div class='poll'>choice A, choice B</div><video><source src='/video.mp4'></video><span class='math'>x^2</span><script>secret()</script>")
        assertEquals(3, nodes.size)
        assertTrue((nodes[0] as PostBlockNode.Unsupported).text.contains("choice B"))
        assertEquals("https://linux.do/video.mp4", (nodes[1] as PostBlockNode.Media).url)
    }
    @Test fun unsafeUrlsCannotBecomeClickableOrLoadable() {
        assertEquals("mailto:sample@example.test", DiscourseAstParser.safeUrl("mailto:sample@example.test"))
        assertNull(DiscourseAstParser.safeUrl("mailto:sample@example.test", allowMailto = false))
        for (url in listOf("javascript:alert(1)", "data:text/html,x", "file:///tmp/x", "content://x")) assertNull(DiscourseAstParser.safeUrl(url))
        val paragraph = DiscourseAstParser.parseCookedHtml("<p><a href='javascript:alert(1)'>visible text</a></p>").single() as PostBlockNode.Paragraph
        assertEquals("visible text", paragraph.text)
        assertNull(paragraph.rich.runs.single().href)
    }
    @Test fun invalidResponsesNeverBecomeSuccessfulEmptyResults() {
        val cases = listOf(null to ApiFailureKind.Connection, (0 to "") to ApiFailureKind.Connection,
            (401 to "{}") to ApiFailureKind.Authentication, (403 to "<html>verify</html>") to ApiFailureKind.Verification,
            (429 to "{}") to ApiFailureKind.RateLimited, (503 to "{}") to ApiFailureKind.Server,
            (200 to "<html>challenge</html>") to ApiFailureKind.InvalidPayload,
            (200 to "{\"errors\":[\"failure\"]}") to ApiFailureKind.InvalidPayload)
        for ((response, kind) in cases) assertEquals(kind, assertFailsWith<ApiReadException> { readApiObject(response) }.kind)
    }
    @Test fun onlyExplicitEmptyArraysCountAsEmptySuccess() {
        assertTrue(readApiObject(200 to "{\"notifications\":[],\"errors\":[]}").requireArray("notifications").isEmpty())
        assertFailsWith<ApiReadException> { Json.parseToJsonElement("{}").jsonObject.requireArray("notifications") }
        assertFailsWith<ApiReadException> { readApiObject(200 to "[]") }
    }
    @Test fun realisticCookedFixtureContainsAllImportantBlocks() {
        val nodes = DiscourseAstParser.parseCookedHtml(javaClass.getResource("/rich-content.html")!!.readText())
        assertTrue(nodes.any { it is PostBlockNode.Table })
        assertTrue(nodes.any { it is PostBlockNode.ImageGallery })
        assertTrue(nodes.any { it is PostBlockNode.Unsupported })
        assertTrue(nodes.any { it is PostBlockNode.Media })
    }
    @Test fun inlineSvgHashtagsAndCheckboxesNeverEmitUnsupportedPlaceholder() {
        val html = """
            <ul>
              <li>我的帖子已经打上 <a class="hashtag-cooked" href="/tag/开源推广"><svg class="fa d-icon d-icon-tag svg-icon svg-node"><use href="#tag"></use></svg><span>开源推广</span></a> 标签：是</li>
              <li><span class="chckbx-box"><svg class="fa d-icon d-icon-far-check-square svg-icon"><use href="#far-check-square"></use></svg></span>已完成开源验证</li>
            </ul>
        """.trimIndent()
        val list = DiscourseAstParser.parseCookedHtml(html).single() as PostBlockNode.BulletList
        assertTrue(list.itemBlocks.flatten().none { it is PostBlockNode.Unsupported })
        val firstItemText = (list.itemBlocks[0].single() as PostBlockNode.Paragraph).text
        assertEquals("我的帖子已经打上 #开源推广 标签：是", firstItemText)
    }
}
