package do_.linux.lindo

import do_.linux.lindo.core.model.MAX_TRACKED_PENDING_TOPICS
import do_.linux.lindo.core.model.PendingFeedApplyDecision
import do_.linux.lindo.core.model.PendingFeedSnapshot
import do_.linux.lindo.core.model.STALE_PENDING_SNAPSHOT_MS
import do_.linux.lindo.core.model.TopicSummary
import do_.linux.lindo.core.model.TopicUser
import do_.linux.lindo.core.model.countEffectivePendingTopics
import do_.linux.lindo.core.model.evaluatePendingFeedPoll
import do_.linux.lindo.core.model.formatPendingNewCount
import do_.linux.lindo.core.model.resolvePendingFeedApply
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PendingFeedStateMachineTest {

    private fun makeTopic(
        id: Int,
        postsCount: Int = 1,
        highestPostNumber: Int = postsCount,
        pinned: Boolean = false,
        unseen: Boolean = false,
        unreadPosts: Int = 0
    ): TopicSummary = TopicSummary(
        id = id,
        title = "Topic $id",
        postsCount = postsCount,
        highestPostNumber = highestPostNumber,
        replyCount = (postsCount - 1).coerceAtLeast(0),
        pinned = pinned,
        unseen = unseen,
        unreadPosts = unreadPosts,
        originalPoster = TopicUser(id = 1, username = "alice")
    )

    @Test
    fun formatCountEliminatesInversionAndCapsAt99Plus() {
        assertEquals("", formatPendingNewCount(0))
        assertEquals("", formatPendingNewCount(-3))
        assertEquals("1", formatPendingNewCount(1))
        assertEquals("20", formatPendingNewCount(20))
        assertEquals("24", formatPendingNewCount(24))
        assertEquals("25", formatPendingNewCount(25))
        assertEquals("48", formatPendingNewCount(48))
        assertEquals("99", formatPendingNewCount(99))
        assertEquals("99+", formatPendingNewCount(100))
        assertEquals("99+", formatPendingNewCount(150))
    }

    @Test
    fun multiPollAccumulatesUniqueTopicsPastSinglePageAndCapsCleanly() {
        val currentTopics = (100 downTo 71).map { makeTopic(id = it, postsCount = 3) }
        var snapshot = PendingFeedSnapshot()

        // 第 1 轮（+15 条新帖 115..101，首屏剩余 100..86）
        val poll1 = (115 downTo 86).map { makeTopic(id = it, postsCount = if (it > 100) 1 else 3) }
        snapshot = evaluatePendingFeedPoll(snapshot, currentTopics, poll1, nowMs = 75_000L)
        assertEquals(15, countEffectivePendingTopics(snapshot, currentTopics))
        assertEquals("15", formatPendingNewCount(countEffectivePendingTopics(snapshot, currentTopics)))

        // 第 2 轮（再 +20 条新帖 135..116，此前 105..101 已滑出 page=0，但仍未合入 currentTopics）
        val poll2 = (135 downTo 106).map { makeTopic(id = it, postsCount = 1) }
        snapshot = evaluatePendingFeedPoll(snapshot, currentTopics, poll2, nowMs = 150_000L)
        assertEquals(35, countEffectivePendingTopics(snapshot, currentTopics))
        assertEquals("35", formatPendingNewCount(countEffectivePendingTopics(snapshot, currentTopics)))

        // 连续多轮继续涌入新帖直到超过 100 条与 MAX_TRACKED_PENDING_TOPICS (150)
        for (round in 0 until 5) {
            val topId = 165 + round * 30
            val page0 = (topId downTo (topId - 29)).map { makeTopic(id = it, postsCount = 1) }
            snapshot = evaluatePendingFeedPoll(
                prev = snapshot,
                currentTopics = currentTopics,
                freshPage0 = page0,
                nowMs = 225_000L + round * 75_000L
            )
        }
        val cappedCount = countEffectivePendingTopics(snapshot, currentTopics)
        assertEquals(MAX_TRACKED_PENDING_TOPICS, cappedCount)
        assertEquals("99+", formatPendingNewCount(cappedCount))
    }

    @Test
    fun repeatedBumpsDeduplicateByTopicIdAndIgnorePinnedTopics() {
        val pinned = makeTopic(id = 9999, postsCount = 10, pinned = true)
        val regular = (100 downTo 72).map { makeTopic(id = it, postsCount = 2) }
        val currentTopics = listOf(pinned) + regular

        // 仅置顶帖回帖增加：不应触发新动态横幅
        val onlyPinnedChanged = listOf(pinned.copy(postsCount = 25, highestPostNumber = 25)) + regular
        var snapshot = evaluatePendingFeedPoll(PendingFeedSnapshot(), currentTopics, onlyPinnedChanged, nowMs = 75_000L)
        assertEquals(0, countEffectivePendingTopics(snapshot, currentTopics))

        // 同一个普通热帖 #88 在 4 个轮询周期内被连续顶帖 4 次：唯一变更话题数始终保持为 1
        for (step in 1..4) {
            val bumped88 = makeTopic(id = 88, postsCount = 2 + step * 3, highestPostNumber = 2 + step * 3)
            val fresh = listOf(pinned, bumped88) + regular.filterNot { it.id == 88 }
            snapshot = evaluatePendingFeedPoll(snapshot, currentTopics, fresh, nowMs = 75_000L * step)
            assertEquals(1, countEffectivePendingTopics(snapshot, currentTopics))
        }
    }

    @Test
    fun directTopicOpenInSplitViewImmediatelyDeductsAndClearsPendingCount() {
        var currentTopics = (100 downTo 71).map { makeTopic(id = it, postsCount = 5, highestPostNumber = 5) }
        val bumped95 = makeTopic(id = 95, postsCount = 8, highestPostNumber = 9)
        val fresh = listOf(bumped95) + currentTopics.filterNot { it.id == 95 }

        val snapshot = evaluatePendingFeedPoll(PendingFeedSnapshot(), currentTopics, fresh, nowMs = 50_000L)
        assertEquals(1, countEffectivePendingTopics(snapshot, currentTopics))

        // 用户未点横幅，直接在分屏点开 #95 并同步到了最新 highestPostNumber=9 -> 计数立即归零
        currentTopics = currentTopics.map {
            if (it.id == 95) it.copy(postsCount = 8, highestPostNumber = 9) else it
        }
        assertEquals(0, countEffectivePendingTopics(snapshot, currentTopics))

        // 下一轮轮询若无其他变化，快照本身也被干净清空
        val reconciled = evaluatePendingFeedPoll(snapshot, currentTopics, fresh, nowMs = 125_000L)
        assertTrue(reconciled.freshPage0.isEmpty())
        assertTrue(reconciled.seenChanges.isEmpty())
    }

    @Test
    fun freshOverlappingSnapshotMergesInstantlyWithoutRegressingReadState() {
        val currentPage0And1 = (100 downTo 41).map { id ->
            if (id == 98) {
                // 用户在快照产生后刚刚读完了 #98（unseen=false, unreadPosts=0, highest=10）
                makeTopic(id = 98, postsCount = 10, highestPostNumber = 10, unseen = false, unreadPosts = 0)
            } else {
                makeTopic(id = id, postsCount = 3, highestPostNumber = 3)
            }
        }
        // 快照抓取于 20 秒前：新增 102、101，且快照里的 #98 仍是旧的 unseen=true, unreadPosts=2
        val freshPage0 = listOf(
            makeTopic(id = 102, postsCount = 1, unseen = true),
            makeTopic(id = 101, postsCount = 1, unseen = true),
            makeTopic(id = 100, postsCount = 3),
            makeTopic(id = 99, postsCount = 3),
            makeTopic(id = 98, postsCount = 10, highestPostNumber = 10, unseen = true, unreadPosts = 2)
        ) + (97 downTo 73).map { makeTopic(id = it, postsCount = 3) }

        val snapshot = evaluatePendingFeedPoll(
            prev = PendingFeedSnapshot(),
            currentTopics = (100 downTo 41).map {
                makeTopic(id = it, postsCount = if (it == 98) 10 else 3)
            },
            freshPage0 = freshPage0,
            nowMs = 100_000L
        )
        assertEquals(2, countEffectivePendingTopics(snapshot, currentPage0And1))

        val decision = resolvePendingFeedApply(snapshot, currentPage0And1, nowMs = 120_000L)
        val instant = assertIs<PendingFeedApplyDecision.ApplyInstant>(decision)
        assertFalse(instant.resetPageToZero)
        assertEquals(62, instant.nextTopics.size)
        assertEquals(102, instant.nextTopics.first().id)
        assertEquals(41, instant.nextTopics.last().id)

        val merged98 = instant.nextTopics.first { it.id == 98 }
        assertFalse(merged98.unseen, "已读话题不得因数秒前的后台快照回退为 unseen 未读蓝点")
        assertEquals(0, merged98.unreadPosts)
    }

    @Test
    fun freshDisjointTurnoverReplacesListAndResetsPageEvenWhenOldTopicWasBumped() {
        // 用户此前已翻到第 2 页（持有 100..41），挂机期间涌入 45 条新帖（145..101），且旧帖 #55 被顶回首屏
        val currentTopics = (100 downTo 41).map { makeTopic(id = it, postsCount = 2) }
        val freshPage0 = (145 downTo 117).map { makeTopic(id = it, postsCount = 1) } +
            listOf(makeTopic(id = 55, postsCount = 9, highestPostNumber = 9))

        val snapshot = evaluatePendingFeedPoll(
            prev = PendingFeedSnapshot(),
            currentTopics = currentTopics,
            freshPage0 = freshPage0,
            nowMs = 200_000L
        )
        val decision = resolvePendingFeedApply(snapshot, currentTopics, nowMs = 215_000L)
        val instant = assertIs<PendingFeedApplyDecision.ApplyInstant>(decision)

        assertTrue(instant.resetPageToZero, "首屏已无未变更锚点，必须重置分页到 page=0 防止漏掉 116..101 断层")
        assertEquals(30, instant.nextTopics.size)
        assertEquals(145, instant.nextTopics.first().id)
        assertEquals(55, instant.nextTopics.last().id)
    }

    @Test
    fun staleSnapshotAfterOneHourForcesLiveNetworkRefresh() {
        val currentTopics = (100 downTo 71).map { makeTopic(id = it, postsCount = 2) }
        val freshAtT0 = (120 downTo 91).map { makeTopic(id = it, postsCount = if (it > 100) 1 else 2) }
        val fetchedAt = 1_000_000L
        val snapshot = evaluatePendingFeedPoll(PendingFeedSnapshot(), currentTopics, freshAtT0, nowMs = fetchedAt)
        assertEquals(20, countEffectivePendingTopics(snapshot, currentTopics))

        // 89.999 秒内：仍属于保鲜窗口，瞬间应用
        val justFresh = resolvePendingFeedApply(
            snapshot = snapshot,
            currentTopics = currentTopics,
            nowMs = fetchedAt + STALE_PENDING_SNAPSHOT_MS - 1L
        )
        assertIs<PendingFeedApplyDecision.ApplyInstant>(justFresh)

        // 达到 90 秒阈值以及等待 1 小时（3,600,000 ms）后点击：必须丢弃 1 小时前旧快照并实时请求最新 page=0
        val atThreshold = resolvePendingFeedApply(
            snapshot = snapshot,
            currentTopics = currentTopics,
            nowMs = fetchedAt + STALE_PENDING_SNAPSHOT_MS
        )
        assertIs<PendingFeedApplyDecision.FetchLivePageZero>(atThreshold)

        val afterOneHour = resolvePendingFeedApply(
            snapshot = snapshot,
            currentTopics = currentTopics,
            nowMs = fetchedAt + 3_600_000L
        )
        assertIs<PendingFeedApplyDecision.FetchLivePageZero>(afterOneHour)
    }
}
