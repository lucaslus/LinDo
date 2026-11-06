package do_.linux.lindo.core.model

/** Windowed topic responses may arrive in either direction; post number preserves reading order. */
fun mergeTopicPosts(existing: List<PostItemModel>, incoming: List<PostItemModel>): List<PostItemModel> =
    (existing + incoming).associateBy { it.id }.values.sortedBy { it.postNumber }

/** Stream IDs strictly before the earliest loaded post in reading order. */
fun previousUnloadedStreamIds(streamIds: List<Int>, loadedIds: Set<Int>): List<Int> =
    if (loadedIds.isEmpty()) emptyList() else streamIds.takeWhile { it !in loadedIds }

/** Stream IDs after the earliest loaded post that have not been loaded yet (including any middle gap after jumping). */
fun remainingUnloadedStreamIds(streamIds: List<Int>, loadedIds: Set<Int>): List<Int> =
    if (loadedIds.isEmpty()) streamIds else streamIds.dropWhile { it !in loadedIds }.filterNot { it in loadedIds }

/** Resolve the post list index for a target floor number, falling back to the nearest floor when a post number was deleted. */
fun resolveJumpPostIndex(posts: List<PostItemModel>, targetPostNumber: Int, allowNearestFallback: Boolean = false): Int? {
    if (posts.isEmpty()) return null
    val exact = posts.indexOfFirst { it.postNumber == targetPostNumber }
    if (exact >= 0 || !allowNearestFallback) return exact.takeIf { it >= 0 }
    return posts.indexOfFirst { it.postNumber >= targetPostNumber }.takeIf { it >= 0 } ?: posts.lastIndex
}
