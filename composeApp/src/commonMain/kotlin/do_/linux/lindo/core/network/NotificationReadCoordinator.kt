package do_.linux.lindo.core.network

import do_.linux.lindo.core.model.UserNotificationItem

/**
 * 通知已读状态权威协调器：
 * - 维护当前登录会话内的本地已读账本 (`locallyReadIds`) 与待确认回写队列 (`pendingMarkReadIds`)，
 *   防止进入帖子跳转时序竞争或后台 `/notifications.json` 轮询把已点开的通知回滚为未读；
 * - 将话题实测已读楼层 (`confirmedFloorForTopic`) 自动映射到同一话题下楼层已覆盖的通知；
 * - 修正 60 条分页导致的 `maxOf` 未读数粘滞缺陷：只要第 0 页存在任何已读通知 (`pageUnread < items.size`)
 *   或总数不足一页，即以当前页精确未读数为准。
 */
object NotificationReadCoordinator {
    private const val MAX_TRACKED_IDS = 1_000
    private const val MAX_PENDING_IDS = 500
    private val POST_FLOOR_READABLE_TYPES = setOf(1, 2, 3, 6, 9, 15, 17, 36, 37, 38, 801, 802)

    fun isAutoReadableByTopicFloor(item: UserNotificationItem): Boolean =
        item.topicId > 0 &&
            item.postNumber > 0 &&
            item.count <= 1 &&
            item.notificationType in POST_FLOOR_READABLE_TYPES

    private var ownerUsername: String = ""
    private val locallyReadIds = LinkedHashSet<Int>()
    private val pendingMarkReadIds = LinkedHashSet<Int>()

    @Synchronized
    fun ensureOwner(username: String?) {
        val clean = username?.trim()?.lowercase().orEmpty()
        if (clean.isEmpty()) return
        if (ownerUsername.isNotEmpty() && ownerUsername != clean) {
            locallyReadIds.clear()
            pendingMarkReadIds.clear()
        }
        ownerUsername = clean
    }

    @Synchronized
    fun acknowledgeSingleRead(id: Int): Boolean {
        if (id <= 0) return false
        val added = addBounded(locallyReadIds, id, MAX_TRACKED_IDS)
        addBounded(pendingMarkReadIds, id, MAX_PENDING_IDS)
        return added
    }

    @Synchronized
    fun acknowledgeAllRead(knownIds: Collection<Int> = emptyList()) {
        for (id in knownIds) {
            if (id > 0) {
                addBounded(locallyReadIds, id, MAX_TRACKED_IDS)
            }
        }
        pendingMarkReadIds.clear()
    }

    @Synchronized
    fun onServerMarkReadConfirmed(id: Int) {
        if (id <= 0) return
        pendingMarkReadIds.remove(id)
        addBounded(locallyReadIds, id, MAX_TRACKED_IDS)
    }

    @Synchronized
    fun overlayNotifications(
        items: List<UserNotificationItem>,
        confirmedFloorForTopic: (Int) -> Int
    ): List<UserNotificationItem> {
        if (items.isEmpty()) return items
        return items.map { item ->
            if (item.read) {
                pendingMarkReadIds.remove(item.id)
                item
            } else {
                val readLocally = item.id in locallyReadIds
                val readByTopicFloor = isAutoReadableByTopicFloor(item) &&
                    confirmedFloorForTopic(item.topicId) >= item.postNumber
                if (readLocally || readByTopicFloor) {
                    addBounded(locallyReadIds, item.id, MAX_TRACKED_IDS)
                    if (readByTopicFloor) {
                        addBounded(pendingMarkReadIds, item.id, MAX_PENDING_IDS)
                    }
                    item.copy(read = true)
                } else {
                    item
                }
            }
        }
    }

    @Synchronized
    fun snapshotPendingIds(): List<Int> = pendingMarkReadIds.toList()

    @Synchronized
    fun isLocallyRead(id: Int): Boolean = id > 0 && id in locallyReadIds

    fun resolveAuthoritativeUnreadCount(
        pageZeroItems: List<UserNotificationItem>,
        previousCachedCount: Int,
        pageSizeLimit: Int = 60
    ): Int {
        val pageUnread = pageZeroItems.count { !it.read }
        return if (pageZeroItems.size < pageSizeLimit || pageUnread < pageZeroItems.size) {
            pageUnread
        } else {
            maxOf(previousCachedCount.coerceAtLeast(0), pageUnread)
        }
    }

    @Synchronized
    fun clear() {
        ownerUsername = ""
        locallyReadIds.clear()
        pendingMarkReadIds.clear()
    }

    private fun addBounded(set: LinkedHashSet<Int>, id: Int, maxEntries: Int): Boolean {
        if (set.contains(id)) return false
        set.add(id)
        while (set.size > maxEntries) {
            val iter = set.iterator()
            if (!iter.hasNext()) break
            iter.next()
            iter.remove()
        }
        return true
    }
}
