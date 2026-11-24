package do_.linux.lindo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import do_.linux.lindo.core.connect.ScreenTrackEngine
import do_.linux.lindo.core.model.PostItemModel
import do_.linux.lindo.core.model.TopicSummary
import do_.linux.lindo.core.network.LinuxDoRepository

internal data class TopicReadResolution(
    val effectiveUnseen: Boolean,
    val effectiveUnreadPosts: Int,
    val isUnread: Boolean
)

/**
 * 严格对齐 Discourse 官方话题列表已读/未读模型（移动端与 PC 端 1:1 同一数学定义）：
 * 1. `unseen`（从未访问过的新话题）：只要用户已访问过该话题（`visitedLocally` 或 `effectiveReadFloor >= 1`），`unseen` 立即清除；
 * 2. `unreadPosts`（正在跟踪的话题产生的新回复）：`remaining = min(unreadPosts, max(0, highest - effectiveReadFloor))`，读完所有可见楼层后清零；
 * 3. `isUnread = effectiveUnseen || effectiveUnreadPosts > 0`。
 */
internal fun resolveDiscourseTopicUnread(
    rawUnseen: Boolean,
    rawUnreadPosts: Int,
    highestPostNumber: Int,
    postsCount: Int,
    visitedLocally: Boolean,
    effectiveReadFloor: Int
): TopicReadResolution {
    val hasVisited = visitedLocally || effectiveReadFloor >= 1
    val effectiveUnseen = rawUnseen && !hasVisited
    val highest = maxOf(highestPostNumber, postsCount, 1)
    val effectiveUnreadPosts = if (rawUnreadPosts > 0) {
        minOf(rawUnreadPosts, (highest - effectiveReadFloor.coerceAtLeast(0)).coerceAtLeast(0))
    } else {
        0
    }
    return TopicReadResolution(
        effectiveUnseen = effectiveUnseen,
        effectiveUnreadPosts = effectiveUnreadPosts,
        isUnread = effectiveUnseen || effectiveUnreadPosts > 0
    )
}

@Composable
internal fun rememberTopicUnreadInFeed(topic: TopicSummary): Boolean {
    val localVersion = ScreenTrackEngine.localReadVersion
    val repositoryVersion = LinuxDoRepository.readTopicsVersion
    return remember(
        topic.id,
        topic.unseen,
        topic.unreadPosts,
        topic.highestPostNumber,
        topic.postsCount,
        localVersion,
        repositoryVersion
    ) {
        val visited = ScreenTrackEngine.isTopicVisitedLocally(topic.id)
        val floor = maxOf(
            ScreenTrackEngine.getLocalViewedMaxFloor(topic.id),
            LinuxDoRepository.getServerConfirmedReadFloor(topic.id) ?: 0
        )
        resolveDiscourseTopicUnread(
            rawUnseen = topic.unseen,
            rawUnreadPosts = topic.unreadPosts,
            highestPostNumber = topic.highestPostNumber,
            postsCount = topic.postsCount,
            visitedLocally = visited,
            effectiveReadFloor = floor
        ).isUnread
    }
}

/** Only a viewed floor fades locally; server timings continue syncing asynchronously in the background. */
@Composable
internal fun rememberFloorUnread(topicId: Int, post: PostItemModel, isAuthorized: Boolean): Boolean {
    val localVersion = ScreenTrackEngine.localReadVersion
    val confirmedVersion = ScreenTrackEngine.serverConfirmedReadVersion
    val repositoryVersion = LinuxDoRepository.readTopicsVersion
    return isAuthorized && !post.isRead && remember(topicId, post.postNumber, localVersion, confirmedVersion, repositoryVersion) {
        !ScreenTrackEngine.isPostViewedLocally(topicId, post.postNumber) &&
            !ScreenTrackEngine.isPostConfirmedReadByServer(topicId, post.postNumber) &&
            (LinuxDoRepository.getServerConfirmedReadFloor(topicId) ?: 0) < post.postNumber
    }
}
