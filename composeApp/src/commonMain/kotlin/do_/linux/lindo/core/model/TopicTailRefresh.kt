package do_.linux.lindo.core.model

data class TopicTailMergeResult(
    val detail: TopicDetailModel,
    val addedCount: Int
)

/**
 * 帖子底部原地增量尾刷新合并器：只追加尚未加载的新楼层，绝不重建已有楼层或跳动滚动条。
 */
object TopicTailRefresh {
    fun extractNewTailIds(
        loadedPosts: List<PostItemModel>,
        candidatePostIds: List<Int>,
        batchLimit: Int = 20
    ): List<Int> {
        val loadedIds = loadedPosts.mapTo(HashSet()) { it.id }
        val out = ArrayList<Int>()
        val seen = HashSet<Int>()
        for (id in candidatePostIds) {
            if (id <= 0 || id in loadedIds || !seen.add(id)) continue
            out.add(id)
            if (out.size >= batchLimit) break
        }
        return out
    }

    fun merge(
        prevDetail: TopicDetailModel,
        incomingStreamTailIds: List<Int>,
        fetchedPosts: List<PostItemModel>,
        onlyOpFilter: Boolean
    ): TopicTailMergeResult {
        val opPost = prevDetail.posts.firstOrNull { it.postNumber == 1 }
        val opUserId = opPost?.userId?.takeIf { it > 0 }
        val opUsername = (prevDetail.originalPosterUsername ?: opPost?.username)?.lowercase()

        val streamSet = LinkedHashSet<Int>(prevDetail.streamIds)
        for (id in incomingStreamTailIds) {
            if (id > 0) streamSet.add(id)
        }

        val existingPostIds = prevDetail.posts.mapTo(HashSet()) { it.id }
        val eligibleNew = ArrayList<PostItemModel>()
        for (post in fetchedPosts) {
            if (post.id in existingPostIds) continue
            if (onlyOpFilter) {
                val isOp = (opUserId != null && post.userId > 0 && post.userId == opUserId) ||
                    (!opUsername.isNullOrBlank() && post.username.equals(opUsername, ignoreCase = true))
                if (!isOp) continue
            }
            existingPostIds.add(post.id)
            streamSet.add(post.id)
            eligibleNew.add(post)
        }

        val mergedStreamIds = streamSet.toList()
        if (eligibleNew.isEmpty()) {
            val streamUpdated = if (mergedStreamIds.size > prevDetail.streamIds.size) {
                prevDetail.copy(
                    streamIds = mergedStreamIds,
                    postsCount = maxOf(prevDetail.postsCount, mergedStreamIds.size)
                )
            } else {
                prevDetail
            }
            return TopicTailMergeResult(detail = streamUpdated, addedCount = 0)
        }

        val mergedPosts = (prevDetail.posts + eligibleNew).sortedBy { it.postNumber }
        val highestFloor = mergedPosts.maxOfOrNull { it.postNumber } ?: prevDetail.postsCount
        val nextPostsCount = maxOf(prevDetail.postsCount, mergedStreamIds.size, highestFloor)
        val nextHighest = maxOf(prevDetail.highestPostNumber ?: 0, highestFloor)

        return TopicTailMergeResult(
            detail = prevDetail.copy(
                streamIds = mergedStreamIds,
                posts = mergedPosts,
                postsCount = nextPostsCount,
                highestPostNumber = nextHighest
            ),
            addedCount = eligibleNew.size
        )
    }
}
