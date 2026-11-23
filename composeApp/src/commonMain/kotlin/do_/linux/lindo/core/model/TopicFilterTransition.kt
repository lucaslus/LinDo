package do_.linux.lindo.core.model

/**
 * Replies filter mode inside the topic reader (全部 / 热门回复 / 只看楼主).
 */
enum class TopicRepliesFilterMode {
    ALL,
    SUMMARY,
    ONLY_OP
}

data class MobileRepliesFilterBarInfo(
    val label: String,
    val filteredReplyCount: Int
)

/**
 * Pure transition helper for seamless in-place switching between 全部 / 热门回复 / 只看楼主:
 * - Preserves Post #1's exact object reference when unchanged so Compose LazyColumn keeps item #1
 *   stable with zero flicker, zero image reload, and zero scroll reset back to top.
 * - Strictly replaces replies (postNumber > 1) and streamIds from `fresh` so stale unfiltered
 *   replies never leak into a filtered stream.
 */
object TopicFilterTransition {
    private fun samePostVisualSnapshot(a: PostItemModel, b: PostItemModel): Boolean =
        a.id == b.id &&
            a.postNumber == b.postNumber &&
            a.cookedHtml == b.cookedHtml &&
            a.likeCount == b.likeCount &&
            a.likedByMe == b.likedByMe &&
            a.bookmarkedByMe == b.bookmarkedByMe &&
            a.replyCount == b.replyCount &&
            a.reactions.size == b.reactions.size &&
            a.boosts.size == b.boosts.size &&
            a.version == b.version

    fun applyFilterResult(prev: TopicDetailModel?, fresh: TopicDetailModel): TopicDetailModel {
        if (prev == null || prev.id != fresh.id) return fresh
        val prevPost1 = prev.posts.firstOrNull { it.postNumber == 1 }
        val freshPost1 = fresh.posts.firstOrNull { it.postNumber == 1 }
        val nextReplies = fresh.posts
            .filter { it.postNumber != 1 }
            .sortedBy { it.postNumber }

        val mergedPost1 = when {
            prevPost1 != null && freshPost1 != null && samePostVisualSnapshot(prevPost1, freshPost1) -> prevPost1
            prevPost1 != null && freshPost1 != null -> freshPost1.copy(
                authorBadges = freshPost1.authorBadges.ifEmpty { prevPost1.authorBadges },
                badgesGranted = freshPost1.badgesGranted.ifEmpty { prevPost1.badgesGranted }
            )
            else -> freshPost1 ?: prevPost1
        }
        val nextPosts = if (mergedPost1 != null) listOf(mergedPost1) + nextReplies else nextReplies
        return fresh.copy(
            hasSummary = fresh.hasSummary || prev.hasSummary,
            posts = nextPosts
        )
    }

    fun describeFilterBar(
        detail: TopicDetailModel?,
        mode: TopicRepliesFilterMode,
        fallbackPostsCount: Int = 1
    ): MobileRepliesFilterBarInfo {
        val totalReplies = ((detail?.postsCount ?: fallbackPostsCount) - 1).coerceAtLeast(0)
        if (mode == TopicRepliesFilterMode.ALL || detail == null) {
            return MobileRepliesFilterBarInfo(
                label = if (totalReplies > 0) "$totalReplies 条回复" else "回复",
                filteredReplyCount = totalReplies
            )
        }
        val post1Id = detail.posts.firstOrNull { it.postNumber == 1 }?.id
        val streamReplyCount = if (detail.streamIds.isNotEmpty()) {
            detail.streamIds.count { it != post1Id }
        } else {
            detail.posts.count { it.postNumber > 1 }
        }
        val loadedReplyCount = detail.posts.count { it.postNumber > 1 }
        val filteredCount = maxOf(streamReplyCount, loadedReplyCount)
        return when (mode) {
            TopicRepliesFilterMode.SUMMARY -> MobileRepliesFilterBarInfo(
                label = if (filteredCount > 0) "热门精选 · $filteredCount 条" else "热门精选回复",
                filteredReplyCount = filteredCount
            )
            TopicRepliesFilterMode.ONLY_OP -> MobileRepliesFilterBarInfo(
                label = if (filteredCount > 0) "楼主发言 · $filteredCount 条" else "楼主回复",
                filteredReplyCount = filteredCount
            )
            TopicRepliesFilterMode.ALL -> MobileRepliesFilterBarInfo(
                label = "$totalReplies 条回复",
                filteredReplyCount = totalReplies
            )
        }
    }
}
