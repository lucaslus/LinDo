package do_.linux.lindo

import do_.linux.lindo.core.copy.ReaderFontScalePreset
import do_.linux.lindo.core.model.PostItemModel
import do_.linux.lindo.core.model.TopicDetailModel
import do_.linux.lindo.core.model.TopicSummary
import do_.linux.lindo.core.model.TopicTailRefresh
import do_.linux.lindo.core.model.TopicUser
import do_.linux.lindo.core.site.ReadingHistorySearch
import do_.linux.lindo.ui.AppAppearance
import do_.linux.lindo.ui.LinDoTokens
import do_.linux.lindo.ui.TypographyScalePolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UxPolishParityTddTest {

    private fun samplePost(id: Int, postNumber: Int, username: String = "u$postNumber", userId: Int = postNumber) = PostItemModel(
        id = id,
        topicId = 500,
        postNumber = postNumber,
        userId = userId,
        username = username,
        displayName = username,
        avatarTemplate = "/a.png",
        cookedHtml = "<p>post $postNumber</p>",
        createdAtText = "1m",
        trustLevel = 2,
        likeCount = 0,
        likedByMe = false,
        bookmarkedByMe = false,
        replyCount = 0
    )

    private fun sampleTopic(
        id: Int,
        title: String,
        category: String = "开发调优",
        author: String = "alice",
        tags: List<String> = emptyList()
    ) = TopicSummary(
        id = id,
        title = title,
        slug = "t-$id",
        postsCount = 5,
        replyCount = 4,
        views = 100,
        likeCount = 3,
        categoryId = 4,
        categoryName = category,
        tags = tags,
        lastPostedAt = "1m",
        originalPoster = TopicUser(
            id = 1,
            username = author,
            name = author,
            avatarTemplate = "/a.png"
        )
    )

    @Test
    fun t1_unifiedFiveStepTypographyScaleAndNonInvertedBaselines() {
        assertEquals(listOf(0.88f, 0.94f, 1.0f, 1.12f, 1.28f), AppAppearance.fontScales)
        assertEquals(ReaderFontScalePreset.STANDARD, ReaderFontScalePreset.fromScale(1.0f))
        assertEquals(ReaderFontScalePreset.MAXIMUM, ReaderFontScalePreset.fromScale(1.30f))
        // Headline & compact feed title must not invert below reading body!
        assertTrue(LinDoTokens.Headline.value > LinDoTokens.Body.value, "Loose feed headline should exceed body")
        assertTrue(LinDoTokens.CompactHeadline.value >= 15.5f, "Compact feed headline must be >= 15.5sp")

        val atMax = TypographyScalePolicy.tiered(1.28f)
        assertEquals(1.28f, atMax.reading, 0.001f)
        assertTrue(atMax.meta <= 1.15f && atMax.meta > 1.10f)
        assertTrue(atMax.chrome <= 1.08f && atMax.chrome > 1.0f)

        val atMin = TypographyScalePolicy.tiered(0.88f)
        assertEquals(0.88f, atMin.reading, 0.001f)
        assertTrue(atMin.meta >= 0.94f)
        assertTrue(atMin.chrome >= 0.96f)
    }

    @Test
    fun t2_readingHistoryMultiTokenFilterAndCloudInSeenQuery() {
        val local = listOf(
            sampleTopic(3211140, "antigravity缓存大调查", "开发调优", "丁真珍珠", listOf("AI")),
            sampleTopic(3211101, "请教Codex子Agent并发上限", "开发调优", "QyQj", listOf("Codex"))
        )
        val cloud = listOf(
            sampleTopic(3211101, "请教Codex子Agent并发上限", "开发调优", "QyQj"),
            sampleTopic(2999001, "三个月前的 Codex MCP 实战笔记", "开发调优", "olddev")
        )

        val merged = ReadingHistorySearch.filterAndMerge(local, cloud, "codex")
        assertEquals(listOf(3211101, 2999001), merged.map { it.topic.id })
        assertEquals(false, merged[0].fromCloudSearch)
        assertEquals(true, merged[1].fromCloudSearch)

        val byId = ReadingHistorySearch.filterAndMerge(local, emptyList(), "#3211140")
        assertEquals(listOf(3211140), byId.map { it.topic.id })

        assertNull(ReadingHistorySearch.buildSeenQuery("   "))
        assertEquals("codex 并发 in:seen", ReadingHistorySearch.buildSeenQuery("codex  并发"))
        assertEquals("codex in:seen", ReadingHistorySearch.buildSeenQuery("codex in:seen"))
    }

    @Test
    fun t3_incrementalTopicTailRefreshAppendsOnlyNewPostsAndRespectsOnlyOp() {
        val base = TopicDetailModel(
            id = 500,
            title = "测试",
            categoryName = "开发调优",
            postsCount = 2,
            views = 10,
            likeCount = 1,
            streamIds = listOf(10, 11),
            posts = listOf(samplePost(10, 1, "op_user", userId = 1), samplePost(11, 2, "other", userId = 2)),
            originalPosterUsername = "op_user"
        )
        assertEquals(listOf(12, 13), TopicTailRefresh.extractNewTailIds(base.posts, listOf(11, 12, 13, 12)))

        val incoming = listOf(samplePost(12, 3, "alice", userId = 3), samplePost(13, 4, "op_user", userId = 1))
        val mergedAll = TopicTailRefresh.merge(base, listOf(12, 13), incoming, onlyOpFilter = false)
        assertEquals(2, mergedAll.addedCount)
        assertEquals(listOf(1, 2, 3, 4), mergedAll.detail.posts.map { it.postNumber })
        assertEquals(listOf(10, 11, 12, 13), mergedAll.detail.streamIds)
        assertEquals(4, mergedAll.detail.postsCount)

        val mergedOpOnly = TopicTailRefresh.merge(base, listOf(12, 13), incoming, onlyOpFilter = true)
        assertEquals(1, mergedOpOnly.addedCount)
        assertEquals(listOf(1, 2, 4), mergedOpOnly.detail.posts.map { it.postNumber })
    }
}
