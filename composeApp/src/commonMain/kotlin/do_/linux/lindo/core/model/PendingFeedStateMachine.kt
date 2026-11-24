package do_.linux.lindo.core.model

import androidx.compose.runtime.Immutable

/**
 * 新动态快照保鲜窗口（90 秒）。
 * 后台轮询周期为 75 秒：
 * - `< 90s` 内点击提示条：直接 0ms 应用内存快照；
 * - `>= 90s`（如锁屏、切后台、断网或长时间未刷新后刚唤醒即点击）：强制发起实时 `page = 0` 网络拉取，杜绝展示旧快照。
 */
const val STALE_PENDING_SNAPSHOT_MS: Long = 90_000L

/**
 * 跨轮询周期去重累计的最大话题水位数量（展示上限为 `99+`，保留 150 条水位余量供分屏单帖消解扣除）。
 */
const val MAX_TRACKED_PENDING_TOPICS: Int = 150

private const val TOP_PAGE_ANCHOR_WINDOW: Int = 30

@Immutable
data class PendingTopicWatermark(
    val highestPostNumber: Int,
    val postsCount: Int
)

@Immutable
data class PendingFeedSnapshot(
    val freshPage0: List<TopicSummary> = emptyList(),
    val seenChanges: Map<Int, PendingTopicWatermark> = emptyMap(),
    val fetchedAtMs: Long = 0L
) {
    fun mapTopics(transform: (TopicSummary) -> TopicSummary): PendingFeedSnapshot {
        if (freshPage0.isEmpty()) return this
        return copy(freshPage0 = freshPage0.map(transform))
    }
}

sealed interface PendingFeedApplyDecision {
    data object FetchLivePageZero : PendingFeedApplyDecision

    data class ApplyInstant(
        val nextTopics: List<TopicSummary>,
        val resetPageToZero: Boolean
    ) : PendingFeedApplyDecision
}

/**
 * 格式化新动态提示条数字：
 * - `<= 0` -> `""`
 * - `1..99` -> 精确数字（消除旧逻辑 `24 -> 20+` 的倒挂跳变）
 * - `>= 100` -> `"99+"`
 */
fun formatPendingNewCount(count: Int): String = when {
    count <= 0 -> ""
    count > 99 -> "99+"
    else -> count.toString()
}

private fun isWatermarkAheadOfTopic(
    watermark: PendingTopicWatermark,
    current: TopicSummary?
): Boolean {
    if (current == null) return true
    return watermark.highestPostNumber > current.highestPostNumber ||
        watermark.postsCount > current.postsCount
}

private fun isTopicAheadOfCurrent(
    fresh: TopicSummary,
    current: TopicSummary?
): Boolean {
    if (current == null) return true
    return fresh.highestPostNumber > current.highestPostNumber ||
        fresh.postsCount > current.postsCount
}

/**
 * 结合当前列表实时计算仍未消解的新动态话题数。
 * 若用户在分屏或详情中已直接点开某条有更新的帖子并同步了最新楼层，数字会立即扣减，归零时横幅自动消失。
 */
fun countEffectivePendingTopics(
    snapshot: PendingFeedSnapshot,
    currentTopics: List<TopicSummary>
): Int {
    if (currentTopics.isEmpty() || snapshot.freshPage0.isEmpty() || snapshot.seenChanges.isEmpty()) {
        return 0
    }
    val currentById = currentTopics.associateBy { it.id }
    return snapshot.seenChanges.count { (id, watermark) ->
        isWatermarkAheadOfTopic(watermark, currentById[id])
    }
}

/**
 * 评估一次后台静默轮询 `page = 0` 的结果，按唯一 `topic.id` 跨轮次去重累加活跃水位。
 */
fun evaluatePendingFeedPoll(
    prev: PendingFeedSnapshot,
    currentTopics: List<TopicSummary>,
    freshPage0: List<TopicSummary>,
    nowMs: Long
): PendingFeedSnapshot {
    if (currentTopics.isEmpty() || freshPage0.isEmpty()) {
        return PendingFeedSnapshot()
    }
    val currentById = currentTopics.associateBy { it.id }
    val freshById = freshPage0.associateBy { it.id }
    val changedInFresh = freshPage0.filter { item ->
        !item.pinned && isTopicAheadOfCurrent(item, currentById[item.id])
    }
    if (changedInFresh.isEmpty()) {
        return PendingFeedSnapshot()
    }

    val nextSeen = LinkedHashMap<Int, PendingTopicWatermark>()
    for ((id, prevWatermark) in prev.seenChanges) {
        if (id !in freshById && isWatermarkAheadOfTopic(prevWatermark, currentById[id])) {
            nextSeen[id] = prevWatermark
        }
    }
    for (item in changedInFresh) {
        nextSeen.remove(item.id)
        nextSeen[item.id] = PendingTopicWatermark(
            highestPostNumber = item.highestPostNumber,
            postsCount = item.postsCount
        )
    }
    while (nextSeen.size > MAX_TRACKED_PENDING_TOPICS) {
        val eldestKey = nextSeen.keys.firstOrNull() ?: break
        nextSeen.remove(eldestKey)
    }

    return PendingFeedSnapshot(
        freshPage0 = freshPage0,
        seenChanges = nextSeen,
        fetchedAtMs = nowMs
    )
}

private fun mergeFreshTopicWithCurrent(
    fresh: TopicSummary,
    existing: TopicSummary?
): TopicSummary {
    if (existing == null) return fresh
    val resolvedUnseen = if (!existing.unseen) false else fresh.unseen
    val resolvedHighest = maxOf(fresh.highestPostNumber, existing.highestPostNumber)
    val resolvedPostsCount = maxOf(fresh.postsCount, existing.postsCount)
    val resolvedReplyCount = maxOf(fresh.replyCount, existing.replyCount)
    val resolvedViews = maxOf(fresh.views, existing.views)
    val resolvedLikeCount = maxOf(fresh.likeCount, existing.likeCount)
    val resolvedUnreadPosts = if (
        !existing.unseen &&
        existing.unreadPosts == 0 &&
        fresh.highestPostNumber <= existing.highestPostNumber &&
        fresh.postsCount <= existing.postsCount
    ) {
        0
    } else {
        fresh.unreadPosts
    }
    return fresh.copy(
        unseen = resolvedUnseen,
        unreadPosts = resolvedUnreadPosts,
        highestPostNumber = resolvedHighest,
        postsCount = resolvedPostsCount,
        replyCount = resolvedReplyCount,
        views = resolvedViews,
        likeCount = resolvedLikeCount
    )
}

/**
 * 用户点击「N 条新动态」时的决策：
 * 1. 快照为空或已超过 90 秒保鲜期 -> `FetchLivePageZero`（实时从服务端拉取最新 `page = 0`）；
 * 2. 快照新鲜且与当前首屏未顶起锚点话题连续重叠 -> 无缝头插合并，保留当前分页进度；
 * 3. 快照新鲜但新帖已完全洗掉首屏（超过 1 页新帖，无连续锚点） -> 直接替换为 `freshPage0` 并重置 `page = 0`，避免时间断层与分页跳页。
 */
fun resolvePendingFeedApply(
    snapshot: PendingFeedSnapshot,
    currentTopics: List<TopicSummary>,
    nowMs: Long
): PendingFeedApplyDecision {
    if (
        snapshot.freshPage0.isEmpty() ||
        snapshot.fetchedAtMs <= 0L ||
        nowMs < snapshot.fetchedAtMs ||
        nowMs - snapshot.fetchedAtMs >= STALE_PENDING_SNAPSHOT_MS
    ) {
        return PendingFeedApplyDecision.FetchLivePageZero
    }

    val currentById = currentTopics.associateBy { it.id }
    val mergedFreshPage0 = snapshot.freshPage0.map { fresh ->
        mergeFreshTopicWithCurrent(fresh, currentById[fresh.id])
    }

    val topCurrentRegularIds = currentTopics.asSequence()
        .filterNot { it.pinned }
        .take(TOP_PAGE_ANCHOR_WINDOW)
        .map { it.id }
        .toSet()

    val hasUnchangedAnchorInTopPage = snapshot.freshPage0.any { fresh ->
        !fresh.pinned &&
            fresh.id in topCurrentRegularIds &&
            fresh.id !in snapshot.seenChanges &&
            !isTopicAheadOfCurrent(fresh, currentById[fresh.id])
    }

    return if (hasUnchangedAnchorInTopPage) {
        val freshIds = mergedFreshPage0.map { it.id }.toSet()
        PendingFeedApplyDecision.ApplyInstant(
            nextTopics = mergedFreshPage0 + currentTopics.filterNot { it.id in freshIds },
            resetPageToZero = false
        )
    } else {
        PendingFeedApplyDecision.ApplyInstant(
            nextTopics = mergedFreshPage0,
            resetPageToZero = true
        )
    }
}
