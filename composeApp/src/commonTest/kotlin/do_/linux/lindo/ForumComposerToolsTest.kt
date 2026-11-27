package do_.linux.lindo

import do_.linux.lindo.core.compose.*
import kotlin.test.*

class ForumComposerToolsTest {
    @Test fun templatesExpandOnlyDiscourseVariablesAndRetainBothSidesOfSelection() {
        val text = "%{my_username} / %{reply_to_name,fallback:各位} / %{topic_url} / %{my_custom}"
        assertEquals("Neo / 各位 /  / %{my_custom}", expandForumTemplate(text, mapOf("my_username" to "Neo")))
        val template = ForumTemplate(7, "周报", "内容", listOf("weekly"), 4)
        assertTrue(template.matches(" WEEK "))
        assertFalse(template.matches("其他"))
        val original = "前文旧后文"
        val result = original.replaceRange(2, 3, separatedForumBlock(original, 2, 3, "模板正文"))
        assertEquals("前文\n\n模板正文\n\n后文", result)
    }

    @Test fun aiPreviewLabelsAllThreeKindsAndLeavesCodeContentsLiteral() {
        val source = listOf(ForumMarkup.AiQuote, ForumMarkup.AiDetails, ForumMarkup.AiCode).joinToString("\n\n") { forumMarkup(it, "原文") }
        val preview = do_.linux.lindo.core.render.MarkdownPreview.render(source)
        val doc = org.jsoup.Jsoup.parse(preview)
        assertEquals("AI 生成", doc.selectFirst(".markdown-alert")?.attr("data-callout-title"))
        assertEquals("摘要 · AI 生成", doc.selectFirst("summary")?.text())
        assertTrue(doc.text().contains("AI 生成"))
        assertEquals("原文", doc.selectFirst("pre code")?.text()?.trim())
    }

    @Test fun insertionUsesDiscoursePluginMarkupWithoutLosingSelectedText() {
        assertEquals("<div data-theme-toc=\"true\"> </div>", forumMarkup(ForumMarkup.Toc, ""))
        assertEquals("<div data-theme-scrollable=\"true\">\n\n甲\n乙\n\n</div>", forumMarkup(ForumMarkup.Scroll, "甲\n乙"))
        assertEquals("> [!AI]\n> 甲\n> 乙", forumMarkup(ForumMarkup.AiQuote, "甲\n乙"))
        assertEquals("```` text ai\nA```B\n````", forumMarkup(ForumMarkup.AiCode, "A```B"))
        assertEquals("^[注释]", forumMarkup(ForumMarkup.Footnote, "注释"))
        assertEquals("[graphviz]\ndigraph { A -> B }\n[/graphviz]", forumMarkup(ForumMarkup.Graphviz, ""))
        assertEquals("[chart type='horizontalBar' title='统计' xAxisTitle='人数']\n安卓 | 0\n桌面 | 2.5\n[/chart]", chartMarkup("统计", "人数", listOf(ChartRow("安卓", "0"), ChartRow("桌面", "2.5"))))
        assertNull(chartMarkup("", "", listOf(ChartRow("未填写数值", ""))))
        assertEquals("[wrap=button id=\"test id\"]\n现有内容\n[/wrap]", wrapMarkup("button", "id=test id", "现有内容"))
        assertNull(wrapMarkup("bad]", "", "现有内容"))
        assertEquals("[date=2026-11-27 time=23:15:00 timezone=\"Asia/Shanghai\"]", dateMarkup("2026-11-27", "23:15", "Asia/Shanghai"))
        assertNull(dateMarkup("2026-02-30", "", "UTC"))
        assertNull(dateMarkup("2026-11-27", "24:00", "UTC"))
    }
}
