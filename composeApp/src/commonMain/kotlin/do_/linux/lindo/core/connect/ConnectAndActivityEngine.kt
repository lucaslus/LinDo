package do_.linux.lindo.core.connect

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlin.math.roundToInt

/**
 * Discourse 在线与每日访问（`days_visited` / 连续登录）保活服务 (`UserPresenceService`)
 *
 * 核心原理解析：
 * Discourse 后端 (`ApplicationController#update_last_seen!`) 仅在以下条件满足时才会更新
 * `user.last_seen_at` 并写入 `user_visits` 表（即计入连续登录天数与 Connect 访问天数）：
 * 1. 请求未携带 `Discourse-Background: true`；
 * 2. 当请求为 XHR (`X-Requested-With: XMLHttpRequest`) 时，必须携带 `Discourse-Present: true`。
 *
 * 早期第三方客户端之所以出现“刷了半天帖子却断签/不计访问天数”，正是因为未在用户交互期间携带
 * `Discourse-Present: true` 头，或所有请求都误带了后台静默标记。
 */
object UserPresenceService {
    private const val USER_UNSEEN_TIMEOUT_MS = 60_000L
    private const val ACTIVITY_DEBOUNCE_MS = 1_000L

    @Volatile
    var isForeground: Boolean = true
        private set

    var isForegroundState by mutableStateOf(true)
        private set

    @Volatile
    var lastUserActivityEpochMs: Long = System.currentTimeMillis()
        private set

    var todayVisitConfirmed by mutableStateOf(false)
        private set

    var lastVisitSyncTimeText by mutableStateOf("尚未确认")
        private set

    fun setAppForeground(foreground: Boolean, countAsActivity: Boolean = true) {
        isForeground = foreground
        Snapshot.withMutableSnapshot {
            isForegroundState = foreground
        }
        if (foreground && countAsActivity) {
            markUserActivity()
        }
    }

    fun markUserActivity(nowMs: Long = System.currentTimeMillis()) {
        if (!isForeground) return
        if (kotlin.math.abs(nowMs - lastUserActivityEpochMs) < ACTIVITY_DEBOUNCE_MS) return
        lastUserActivityEpochMs = nowMs
    }

    fun isPresent(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!isForeground) return false
        return (nowMs - lastUserActivityEpochMs) < USER_UNSEEN_TIMEOUT_MS
    }

    fun recordSuccessfulForegroundRequest() {
        Snapshot.withMutableSnapshot {
            todayVisitConfirmed = true
            lastVisitSyncTimeText = "刚刚"
        }
    }

    /**
     * 生成对齐 Discourse 官方前端的在岗请求头
     */
    fun buildPresenceHeaders(
        isBackgroundTimingRequest: Boolean = false,
        isLoggedIn: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
        silenceLogger: Boolean = isBackgroundTimingRequest,
        includeOrigin: Boolean = true
    ): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        headers["X-Requested-With"] = "XMLHttpRequest"
        if (includeOrigin) {
            headers["Origin"] = "https://linux.do"
        }
        headers["Referer"] = "https://linux.do/"
        headers["Sec-Fetch-Dest"] = "empty"
        headers["Sec-Fetch-Mode"] = "cors"
        headers["Sec-Fetch-Site"] = "same-origin"

        if (isLoggedIn) {
            headers["Discourse-Logged-In"] = "true"
        }

        if (isBackgroundTimingRequest) {
            // /topics/timings 属于高频阅读心跳，带静默日志头；普通后台只读请求不附加 X-SILENCE-LOGGER
            if (silenceLogger) {
                headers["X-SILENCE-LOGGER"] = "true"
            }
            headers["Discourse-Background"] = "true"
        } else if (isPresent(nowMs)) {
            // 用户前台交互请求携带 Discourse-Present: true，触发后端写入 user_visits 记录连登天数
            headers["Discourse-Present"] = "true"
        }
        return headers
    }
}

/**
 * 待上报的合并阅读时长批次
 */
data class ConsolidatedTimingBatch(
    val topicId: Int,
    var topicTimeMs: Int,
    val timingsMs: MutableMap<Int, Int>
)

/**
 * Discourse 帖子阅读时长与已读楼层追踪器 (`ScreenTrackEngine`)
 *
 * 对齐 Discourse 前端 `screen-track.js` 与 FluxDO `ScreenTrack` 状态机：
 * 1. 打开话题时结合 `GET /t/{id}.json?track_visit=true` + `Discourse-Track-View: 1` 记录「浏览话题 (`topics_entered`)」；
 * 2. 阅读过程中每秒 (`tick`) 为当前视口内可见楼层 (`onscreenPostNumbers`) 累加毫秒时长；
 * 3. 当有新楼层首次进入视野达到阈值（`rush`）或离开话题时，立即合并上报 `POST /topics/timings`：
 *    `topic_id={id}&topic_time={ms}&timings[{postNumber}]={ms}`；
 * 4. 临时网络失败会合并回内存队列并指数退避重传；进程被杀时尚未持久化的队列仍可能丢失。
 */
object ScreenTrackEngine {
    const val STATUS_SITE_VERIFICATION_REQUIRED = -403
    private const val PAUSE_UNLESS_SCROLLED_MS = 3 * 60 * 1000L
    private const val MAX_TRACKING_TIME_PER_POST_MS = 6 * 60 * 1000
    private const val FLUSH_INTERVAL_MS = 15_000L
    private const val MAX_TOPIC_RETRIES = 3
    private val BACKOFF_DELAYS_MS = longArrayOf(5_000L, 10_000L, 20_000L, 40_000L)
    private val RETRYABLE_STATUS_CODES = setOf(403, 405, 429, 500, 501, 502, 503, 504)
    private val PERMANENT_DROP_STATUS_CODES = setOf(400, 401, 404, 410, 422)
    private val lock = Any()
    private val flushSignal = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    private var currentTopicId: Int? = null
    private var lastTickEpochMs: Long = 0L
    private var lastScrolledEpochMs: Long = 0L
    private var accumulatedSinceFlushMs: Long = 0L
    private var currentTopicTimeMs: Int = 0

    private val currentTimingsMs = LinkedHashMap<Int, Int>()
    private val totalTimingsMs = LinkedHashMap<Int, Int>()
    private val readPostsSet = LinkedHashSet<Pair<Int, Int>>()
    private var sessionReadingMilliseconds = 0L
    private val consolidatedQueue = ArrayList<ConsolidatedTimingBatch>()
    private val retryCounts = HashMap<Int, Int>()

    private var onscreenPosts: Set<Int> = emptySet()
    private var consecutiveFailures: Int = 0
    private var blockSendingUntilEpochMs: Long = 0L

    // UI 可观测的实时活跃度指标（让用户直观看到每次阅读与刷帖都在实时同步）
    var sessionTopicsEntered by mutableIntStateOf(0)
        private set

    var sessionPostsRead by mutableIntStateOf(0)
        private set

    var sessionReadingSeconds by mutableLongStateOf(0L)
        private set

    var pendingBatchesCount by mutableIntStateOf(0)
        private set

    private val serverConfirmedReadPosts = HashSet<Pair<Int, Int>>()

    var serverConfirmedReadVersion by mutableLongStateOf(0L)
        private set

    fun isPostConfirmedReadByServer(topicId: Int, postNumber: Int): Boolean = synchronized(lock) {
        serverConfirmedReadPosts.contains(topicId to postNumber)
    }

    fun clearServerConfirmedReadPosts() = synchronized(lock) {
        if (serverConfirmedReadPosts.isNotEmpty()) {
            serverConfirmedReadPosts.clear()
            Snapshot.withMutableSnapshot {
                serverConfirmedReadVersion += 1L
            }
        }
    }

    suspend fun awaitFlushSignalOrTimeout(timeoutMs: Long) {
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            flushSignal.receive()
        }
    }

    fun onTopicEntered(topicId: Int, initialVisiblePosts: Set<Int> = setOf(1), nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            if (currentTopicId != null && currentTopicId != topicId) {
                onTopicExitedLocked(currentTopicId!!, nowMs)
            }
            resetCurrentTopicStateLocked(nowMs)
            currentTopicId = topicId
            onscreenPosts = initialVisiblePosts
            Snapshot.withMutableSnapshot {
                sessionTopicsEntered += 1
            }
        }
        UserPresenceService.markUserActivity(nowMs)
    }

    fun updateVisiblePosts(visiblePostNumbers: Set<Int>, nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            onscreenPosts = visiblePostNumbers
        }
    }

    fun onUserScrolled(nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            lastScrolledEpochMs = nowMs
        }
        UserPresenceService.markUserActivity(nowMs)
    }

    fun isPostRead(topicId: Int, postNumber: Int): Boolean = synchronized(lock) {
        readPostsSet.contains(topicId to postNumber)
    }

    fun markPostReadLocally(topicId: Int, postNumber: Int) {
        if (topicId > 0 && postNumber > 0) {
            synchronized(lock) {
                if (readPostsSet.add(topicId to postNumber)) {
                    Snapshot.withMutableSnapshot {
                        sessionPostsRead += 1
                    }
                }
            }
        }
    }

    private fun accumulateTickAndCheckFlushLocked(nowMs: Long): Boolean {
        val topicId = currentTopicId ?: return false
        if (topicId <= 0) return false
        if (!UserPresenceService.isForeground) {
            lastTickEpochMs = nowMs
            return false
        }

        if (lastTickEpochMs == 0L) {
            lastTickEpochMs = nowMs
            lastScrolledEpochMs = nowMs
            return false
        }

        // 超过 3 分钟无滚动则暂停计时，避免被服务端判定为挂机无效时长
        if (nowMs - lastScrolledEpochMs > PAUSE_UNLESS_SCROLLED_MS) {
            lastTickEpochMs = nowMs
            return false
        }

        val diffMs = (nowMs - lastTickEpochMs).coerceIn(0L, 3_000L).toInt()
        lastTickEpochMs = nowMs
        accumulatedSinceFlushMs += diffMs
        currentTopicTimeMs += diffMs
        sessionReadingMilliseconds += diffMs
        val nextReadingSeconds = sessionReadingMilliseconds / 1000L
        if (nextReadingSeconds != sessionReadingSeconds) {
            Snapshot.withMutableSnapshot {
                sessionReadingSeconds = nextReadingSeconds
            }
        }

        for (postNumber in onscreenPosts) {
            currentTimingsMs[postNumber] = (currentTimingsMs[postNumber] ?: 0) + diffMs
        }

        // 检查是否有首次阅读的新楼层累积满 1 秒（rush 立即上报策略）
        val hasUnreadRush = currentTimingsMs.entries.any { (postNum, ms) ->
            ms >= 1_000 && !totalTimingsMs.containsKey(postNum)
        }

        return accumulatedSinceFlushMs >= FLUSH_INTERVAL_MS || hasUnreadRush
    }

    /**
     * 每秒调用一次，累加当前屏幕可见楼层的阅读毫秒数；若达到上报条件，直接入队唤醒发送器（不做出队再回塞）
     */
    fun tickAndEnqueue(nowMs: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        if (accumulateTickAndCheckFlushLocked(nowMs)) {
            return enqueueCurrentTopicTimingsLocked()
        }
        return false
    }

    /**
     * 每秒调用一次，累加当前屏幕可见楼层的阅读毫秒数
     * @return 若达到上报条件，返回待发送的批次
     */
    fun tick(nowMs: Long = System.currentTimeMillis()): ConsolidatedTimingBatch? = synchronized(lock) {
        if (accumulateTickAndCheckFlushLocked(nowMs)) {
            return flushCurrentTopicLocked(nowMs)
        }
        return null
    }

    fun flushCurrentTopic(nowMs: Long = System.currentTimeMillis()): ConsolidatedTimingBatch? = synchronized(lock) {
        flushCurrentTopicLocked(nowMs)
    }

    private fun enqueueCurrentTopicTimingsLocked(): Boolean {
        val topicId = currentTopicId ?: return false
        val newTimings = LinkedHashMap<Int, Int>()
        var newlyAddedPosts = 0

        for ((postNumber, timeMs) in currentTimingsMs) {
            val prevTotal = totalTimingsMs[postNumber] ?: 0
            if (timeMs > 0 && prevTotal < MAX_TRACKING_TIME_PER_POST_MS) {
                totalTimingsMs[postNumber] = prevTotal + timeMs
                newTimings[postNumber] = timeMs
                if (readPostsSet.add(topicId to postNumber)) {
                    newlyAddedPosts += 1
                }
            }
        }
        if (newlyAddedPosts > 0) {
            Snapshot.withMutableSnapshot {
                sessionPostsRead += newlyAddedPosts
            }
        }
        currentTimingsMs.clear()

        if (newTimings.isNotEmpty() && currentTopicTimeMs > 0) {
            consolidateBatchLocked(topicId, currentTopicTimeMs, newTimings)
            currentTopicTimeMs = 0
        }
        accumulatedSinceFlushMs = 0L
        return newTimings.isNotEmpty()
    }

    private fun flushCurrentTopicLocked(nowMs: Long): ConsolidatedTimingBatch? {
        if (!enqueueCurrentTopicTimingsLocked()) return null
        return popNextSendableBatchLocked(nowMs)
    }

    fun onTopicExited(topicId: Int, nowMs: Long = System.currentTimeMillis()) = synchronized(lock) {
        onTopicExitedLocked(topicId, nowMs)
    }

    private fun onTopicExitedLocked(topicId: Int, nowMs: Long) {
        if (currentTopicId != topicId) return
        if (currentTimingsMs.isEmpty() && onscreenPosts.isNotEmpty() && lastTickEpochMs > 0L && nowMs > lastTickEpochMs) {
            val remainderMs = (nowMs - lastTickEpochMs).coerceIn(150L, 3_000L).toInt()
            currentTopicTimeMs += remainderMs
            for (postNumber in onscreenPosts) {
                currentTimingsMs[postNumber] = (currentTimingsMs[postNumber] ?: 0) + remainderMs
            }
            lastTickEpochMs = nowMs
        }
        // Disposal must enqueue, never launch work into a scope that Compose is cancelling.
        enqueueCurrentTopicTimingsLocked()
        currentTopicId = null
        onscreenPosts = emptySet()
    }

    fun consolidateBatch(topicId: Int, topicTimeMs: Int, timingsMs: Map<Int, Int>) = synchronized(lock) {
        consolidateBatchLocked(topicId, topicTimeMs, timingsMs)
    }

    private fun consolidateBatchLocked(
        topicId: Int,
        topicTimeMs: Int,
        timingsMs: Map<Int, Int>,
        moveToTail: Boolean = false
    ) {
        if (timingsMs.isEmpty()) return
        val existingIdx = consolidatedQueue.indexOfFirst { it.topicId == topicId }
        if (existingIdx >= 0) {
            val existing = consolidatedQueue[existingIdx]
            existing.topicTimeMs += topicTimeMs
            for ((postNum, ms) in timingsMs) {
                existing.timingsMs[postNum] = (existing.timingsMs[postNum] ?: 0) + ms
            }
            if (moveToTail && existingIdx != consolidatedQueue.lastIndex) {
                consolidatedQueue.removeAt(existingIdx)
                consolidatedQueue.add(existing)
            }
        } else {
            consolidatedQueue.add(
                ConsolidatedTimingBatch(
                    topicId = topicId,
                    topicTimeMs = topicTimeMs,
                    timingsMs = LinkedHashMap(timingsMs)
                )
            )
        }
        Snapshot.withMutableSnapshot {
            pendingBatchesCount = consolidatedQueue.size
        }
        flushSignal.trySend(Unit)
    }

    fun hasPendingWork(): Boolean = synchronized(lock) {
        consolidatedQueue.isNotEmpty()
    }

    fun popNextSendableBatch(nowMs: Long = System.currentTimeMillis()): ConsolidatedTimingBatch? = synchronized(lock) {
        popNextSendableBatchLocked(nowMs)
    }

    private fun popNextSendableBatchLocked(nowMs: Long): ConsolidatedTimingBatch? {
        if (consolidatedQueue.isEmpty()) return null
        if (nowMs < blockSendingUntilEpochMs) return null
        val batch = consolidatedQueue.removeAt(0)
        Snapshot.withMutableSnapshot {
            pendingBatchesCount = consolidatedQueue.size
        }
        return batch
    }

    private fun releaseUnconfirmedFloorTrackingLocked(batch: ConsolidatedTimingBatch) {
        if (currentTopicId != batch.topicId) return
        for (postNum in batch.timingsMs.keys) {
            if (!serverConfirmedReadPosts.contains(batch.topicId to postNum)) {
                totalTimingsMs.remove(postNum)
                readPostsSet.remove(batch.topicId to postNum)
            }
        }
    }

    fun clearSendBlock() = synchronized(lock) {
        blockSendingUntilEpochMs = 0L
        consecutiveFailures = 0
    }

    fun onBatchSendResult(batch: ConsolidatedTimingBatch, statusCode: Int?, nowMs: Long = System.currentTimeMillis()) = synchronized(lock) {
        if (statusCode != null && statusCode in 200..299) {
            consecutiveFailures = 0
            blockSendingUntilEpochMs = 0L
            retryCounts.remove(batch.topicId)
            var added = false
            for (postNum in batch.timingsMs.keys) {
                if (serverConfirmedReadPosts.add(batch.topicId to postNum)) {
                    added = true
                }
            }
            if (added) {
                Snapshot.withMutableSnapshot {
                    serverConfirmedReadVersion += 1L
                }
            }
        } else if (statusCode == STATUS_SITE_VERIFICATION_REQUIRED) {
            // 遭遇 Cloudflare 站点验证挑战：挂起后台 timings 发送 90s（或直至用户完成验证手动恢复），
            // 严禁清空 readPostsSet（否则每秒 tick 触发 hasUnreadRush 进入 1s 无限风暴）！
            blockSendingUntilEpochMs = maxOf(blockSendingUntilEpochMs, nowMs + 90_000L)
            consolidateBatchLocked(batch.topicId, batch.topicTimeMs, batch.timingsMs, moveToTail = true)
        } else if (statusCode != null && statusCode in PERMANENT_DROP_STATUS_CODES) {
            retryCounts.remove(batch.topicId)
            releaseUnconfirmedFloorTrackingLocked(batch)
        } else if (statusCode == null || statusCode == 0 || statusCode in RETRYABLE_STATUS_CODES) {
            if (!UserPresenceService.isForeground) {
                blockSendingUntilEpochMs = nowMs + 15_000L
                consolidateBatchLocked(batch.topicId, batch.topicTimeMs, batch.timingsMs, moveToTail = true)
            } else {
                val retries = (retryCounts[batch.topicId] ?: 0) + 1
                if (retries >= MAX_TOPIC_RETRIES) {
                    retryCounts.remove(batch.topicId)
                    if (currentTopicId == batch.topicId) {
                        blockSendingUntilEpochMs = maxOf(blockSendingUntilEpochMs, nowMs + 30_000L)
                    }
                    releaseUnconfirmedFloorTrackingLocked(batch)
                } else {
                    retryCounts[batch.topicId] = retries
                    val delayIdx = consecutiveFailures.coerceIn(0, BACKOFF_DELAYS_MS.lastIndex)
                    consecutiveFailures += 1
                    blockSendingUntilEpochMs = nowMs + BACKOFF_DELAYS_MS[delayIdx]
                    // 失败不丢数据：重新合并回队列尾部等待退避后重发（FIFO 不阻塞其他话题）
                    consolidateBatchLocked(batch.topicId, batch.topicTimeMs, batch.timingsMs, moveToTail = true)
                }
            }
        } else {
            retryCounts.remove(batch.topicId)
            releaseUnconfirmedFloorTrackingLocked(batch)
        }
    }

    private fun resetCurrentTopicStateLocked(nowMs: Long) {
        lastTickEpochMs = nowMs
        lastScrolledEpochMs = nowMs
        accumulatedSinceFlushMs = 0L
        currentTopicTimeMs = 0
        currentTimingsMs.clear()
        totalTimingsMs.clear()
        onscreenPosts = emptySet()
    }

    fun resetSessionState() = synchronized(lock) {
        currentTopicId = null
        lastTickEpochMs = 0L
        lastScrolledEpochMs = 0L
        accumulatedSinceFlushMs = 0L
        currentTopicTimeMs = 0
        currentTimingsMs.clear()
        totalTimingsMs.clear()
        readPostsSet.clear()
        val hadConfirmed = serverConfirmedReadPosts.isNotEmpty()
        serverConfirmedReadPosts.clear()
        consolidatedQueue.clear()
        retryCounts.clear()
        onscreenPosts = emptySet()
        sessionReadingMilliseconds = 0L
        consecutiveFailures = 0
        blockSendingUntilEpochMs = 0L
        Snapshot.withMutableSnapshot {
            if (hadConfirmed) {
                serverConfirmedReadVersion += 1L
            }
            sessionTopicsEntered = 0
            sessionPostsRead = 0
            sessionReadingSeconds = 0L
            pendingBatchesCount = 0
        }
    }

    fun resetForTest() = synchronized(lock) {
        currentTopicId = null
        lastTickEpochMs = 0L
        lastScrolledEpochMs = 0L
        accumulatedSinceFlushMs = 0L
        currentTopicTimeMs = 0
        currentTimingsMs.clear()
        totalTimingsMs.clear()
        readPostsSet.clear()
        serverConfirmedReadPosts.clear()
        consolidatedQueue.clear()
        retryCounts.clear()
        onscreenPosts = emptySet()
        sessionReadingMilliseconds = 0L
        consecutiveFailures = 0
        blockSendingUntilEpochMs = 0L
        Snapshot.withMutableSnapshot {
            serverConfirmedReadVersion = 0L
            sessionTopicsEntered = 0
            sessionPostsRead = 0
            sessionReadingSeconds = 0L
            pendingBatchesCount = 0
        }
    }
}

/**
 * `connect.linux.do` 单个指标项模型
 */
data class ConnectMetricItem(
    val label: String,
    val currentValue: Int,
    val targetValue: Int,
    val displayCurrent: String,
    val displayTarget: String,
    val isMet: Boolean
) {
    val progress: Float
        get() = if (targetValue > 0) {
            (currentValue.toFloat() / targetValue.toFloat()).coerceIn(0f, 1f)
        } else {
            if (isMet) 1f else 0f
        }
}

/**
 * `connect.linux.do` 信任等级与活跃度完整报告
 */
data class ConnectTrustLevelReport(
    val title: String,
    val subtitle: String,
    val statusBadgeText: String,
    val isQualified: Boolean,
    val timePeriodDays: Int,
    val daysVisited: Int,
    val topicsEntered: Int,
    val postsRead: Int,
    val topicsRepliedTo: Int,
    val likesGiven: Int,
    val likesReceived: Int,
    val likesReceivedDays: Int,
    val likesReceivedUsers: Int,
    val metrics: List<ConnectMetricItem>,
    val isLiveConnectData: Boolean
)

/**
 * `connect.linux.do` 当前环形、条形、举报配额及经典表格布局解析器。
 */
object ConnectStatsParser {

    fun parseConnectHtml(htmlContent: String): ConnectTrustLevelReport? {
        if (
            !htmlContent.contains("tl3-ring") &&
            !htmlContent.contains("tl3-bar-item") &&
            !htmlContent.contains("card-title") &&
            !htmlContent.contains("<table", ignoreCase = true)
        ) {
            return null
        }

        val title = Regex("""<h2[^>]*class=["'][^"']*card-title[^"']*["'][^>]*>(.*?)</h2>""", RegexOption.DOT_MATCHES_ALL)
            .find(htmlContent)?.groupValues?.getOrNull(1)?.let { cleanText(it) }
            ?: Regex("""<h[123][^>]*>(.*?信任等级.*?)</h[123]>""", RegexOption.DOT_MATCHES_ALL)
                .find(htmlContent)?.groupValues?.getOrNull(1)?.let { cleanText(it) }
            ?.ifBlank { "信任等级 3 要求" }
            ?: "信任等级 3 要求"

        val subtitle = Regex("""<[^>]*class=["'][^"']*card-subtitle[^"']*["'][^>]*>(.*?)</[^>]+>""", RegexOption.DOT_MATCHES_ALL)
            .find(htmlContent)?.groupValues?.getOrNull(1)?.let { cleanText(it) }
            ?: "过去 100 天内社区活跃度统计 (connect.linux.do)"

        val badgeMatch = Regex("""<span[^>]*class=["']([^"']*badge[^"']*)["'][^>]*>(.*?)</span>""", RegexOption.DOT_MATCHES_ALL)
            .find(htmlContent)
        val badgeClasses = badgeMatch?.groupValues?.getOrNull(1).orEmpty()
        val badgeText = badgeMatch?.groupValues?.getOrNull(2)?.let { cleanText(it) }
            ?: if (htmlContent.contains("符合信任等级") || htmlContent.contains("已达标")) "已达标" else "实时数据"
        val hasNegativeBadge = listOf("不符合", "未达标", "未达到", "不满足").any { badgeText.contains(it) }
        val isQualified = !hasNegativeBadge && (badgeClasses.contains("badge-success") ||
            badgeText.contains("已达标") ||
            badgeText.contains("符合") ||
            htmlContent.contains("符合信任等级 3"))

        val periodDays = Regex("""(\d+)\s*(?:天|days)""", RegexOption.IGNORE_CASE)
            .find(subtitle)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 100

        val metrics = mutableListOf<ConnectMetricItem>()

        // 1. 解析环形指标 (.tl3-ring)
        val ringRegex = Regex(
            """<div[^>]*class=["'][^"']*tl3-ring(?![-a-zA-Z0-9_])[^"']*["'][^>]*>(.*?)(?=<div[^>]*class=["'][^"']*tl3-ring(?![-a-zA-Z0-9_])|<div[^>]*class=["'][^"']*tl3-bar|$)""",
            RegexOption.DOT_MATCHES_ALL
        )
        for (match in ringRegex.findAll(htmlContent)) {
            val block = match.value
            val label = Regex("""class=["'][^"']*tl3-ring-label[^"']*["'][^>]*>(.*?)<""", RegexOption.DOT_MATCHES_ALL)
                .find(block)?.groupValues?.getOrNull(1)?.let { cleanText(it) }.orEmpty()
            val circleTag = Regex("""<[^>]*class=["'][^"']*tl3-ring-circle[^"']*["'][^>]*>""")
                .find(block)?.value.orEmpty()
            val isMet = Regex("""class=["']([^"']*)["']""").find(circleTag)?.groupValues?.get(1)?.split(' ', '\t', '\n')?.contains("met") == true
            val cur = parseCssVar(circleTag, "--val")
            val max = parseCssVar(circleTag, "--max").coerceAtLeast(1)
            if (label.isNotEmpty()) {
                metrics += ConnectMetricItem(
                    label = label,
                    currentValue = cur,
                    targetValue = max,
                    displayCurrent = cur.toString(),
                    displayTarget = max.toString(),
                    isMet = isMet || cur >= max
                )
            }
        }

        // 2. 解析条形指标 (.tl3-bar-item)
        val barRegex = Regex(
            """<div[^>]*class=["'][^"']*tl3-bar-item(?![-a-zA-Z0-9_])[^"']*["'][^>]*>(.*?)(?=<div[^>]*class=["'][^"']*tl3-bar-item(?![-a-zA-Z0-9_])|</div>\s*</div>\s*<div|$)""",
            RegexOption.DOT_MATCHES_ALL
        )
        for (match in barRegex.findAll(htmlContent)) {
            val block = match.value
            val label = Regex("""class=["'][^"']*tl3-bar-label[^"']*["'][^>]*>(.*?)<""", RegexOption.DOT_MATCHES_ALL)
                .find(block)?.groupValues?.getOrNull(1)?.let { cleanText(it) }.orEmpty()
            val numsText = Regex("""class=["'][^"']*tl3-bar-nums[^"']*["'][^>]*>(.*?)<""", RegexOption.DOT_MATCHES_ALL)
                .find(block)?.groupValues?.getOrNull(1)?.let { cleanText(it) }.orEmpty()
            val fillTag = Regex("""<[^>]*class=["'][^"']*tl3-bar-fill[^"']*["'][^>]*>""")
                .find(block)?.value.orEmpty()
            val isMet = Regex("""class=["']([^"']*)["']""").find(fillTag)?.groupValues?.get(1)?.split(' ', '\t', '\n')?.contains("met") == true
            val cssVal = parseCssVar(fillTag, "--val")
            val cssMax = parseCssVar(fillTag, "--max").coerceAtLeast(1)
            val parsedNum = parseFirstInt(numsText)
            val cur = if (parsedNum > 0) parsedNum else cssVal
            if (label.isNotEmpty()) {
                metrics += ConnectMetricItem(
                    label = label,
                    currentValue = cur,
                    targetValue = cssMax,
                    displayCurrent = cur.toString(),
                    displayTarget = cssMax.toString(),
                    isMet = isMet || cur >= cssMax
                )
            }
        }

        // Quotas are upper bounds. Never treat an excessive report count as positive progress.
        val quotaRegex = Regex(
            """<div[^>]*class=["']([^"']*\btl3-quota-card\b[^"']*)["'][^>]*>(.*?)(?=<div[^>]*class=["'][^"']*\btl3-quota-card\b|$)""",
            RegexOption.DOT_MATCHES_ALL
        )
        for (match in quotaRegex.findAll(htmlContent)) {
            val block = match.groupValues[2]
            val label = Regex("""class=["'][^"']*tl3-quota-label[^"']*["'][^>]*>(.*?)<""", RegexOption.DOT_MATCHES_ALL)
                .find(block)?.groupValues?.get(1)?.let(::cleanText).orEmpty()
            val numbers = Regex("""class=["'][^"']*tl3-quota-nums[^"']*["'][^>]*>(.*?)<""", RegexOption.DOT_MATCHES_ALL)
                .find(block)?.groupValues?.get(1)?.let(::cleanText).orEmpty()
            val values = Regex("""(\d+)\s*/\s*(\d+)""").find(numbers) ?: continue
            val current = values.groupValues[1].toInt()
            val limit = values.groupValues[2].toInt()
            if (label.isNotBlank()) metrics += ConnectMetricItem(label, current, limit,
                current.toString(), limit.toString(), current <= limit)
        }

        // 兼容 connect.linux.do 经典 <table><tr><td>指标</td><td>当前</td><td>要求</td></tr></table> 布局
        if (metrics.isEmpty() && htmlContent.contains("<tr", ignoreCase = true)) {
            val trRegex = Regex("""<tr[^>]*>(.*?)</tr>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            val tdRegex = Regex("""<td[^>]*>(.*?)</td>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            for (trMatch in trRegex.findAll(htmlContent)) {
                val trBlock = trMatch.groupValues[1]
                val tds = tdRegex.findAll(trBlock).map { it.groupValues[1] }.toList()
                if (tds.size >= 3) {
                    val rawLabel = cleanText(tds[0])
                    val rawCurrent = cleanText(tds[1])
                    val rawTarget = cleanText(tds[2])
                    val curVal = parseFirstInt(rawCurrent)
                    val targetVal = parseFirstInt(rawTarget)
                    if (
                        rawLabel.isNotBlank() &&
                        targetVal > 0 &&
                        !rawLabel.contains("项目") &&
                        !rawLabel.contains("禁言") &&
                        !rawLabel.contains("封禁")
                    ) {
                        val isUnmet = tds[1].contains("red", ignoreCase = true) || trBlock.contains("unmet", ignoreCase = true)
                        val isMet = if (tds[1].contains("green", ignoreCase = true)) true else if (isUnmet) false else curVal >= targetVal
                        metrics += ConnectMetricItem(
                            label = rawLabel,
                            currentValue = curVal,
                            targetValue = targetVal,
                            displayCurrent = rawCurrent.ifBlank { curVal.toString() },
                            displayTarget = rawTarget.ifBlank { targetVal.toString() },
                            isMet = isMet
                        )
                    }
                }
            }
        }

        if (metrics.isEmpty()) return null

        val mapByLabel = metrics.associate { it.label to it.currentValue }

        return ConnectTrustLevelReport(
            title = title,
            subtitle = subtitle,
            statusBadgeText = badgeText,
            isQualified = !hasNegativeBadge && (isQualified || metrics.all { it.isMet }),
            timePeriodDays = periodDays,
            daysVisited = matchMetric(mapByLabel, listOf("访问天数", "days visited")),
            topicsEntered = matchMetric(mapByLabel, listOf("浏览话题", "浏览的话题", "浏览主题", "topics viewed")),
            postsRead = matchMetric(mapByLabel, listOf("浏览帖子", "浏览的帖子", "已读帖子", "posts read")),
            topicsRepliedTo = matchMetric(mapByLabel, listOf("回复话题", "回复的话题", "回复主题", "topics replied")),
            likesGiven = matchMetricExclude(mapByLabel, listOf("点赞", "送赞", "likes given"), listOf("获", "received")),
            likesReceived = matchMetricExclude(mapByLabel, listOf("获赞", "likes received"), listOf("天数", "用户", "人数", "单日", "days", "users")),
            likesReceivedDays = matchMetric(mapByLabel, listOf("获赞天数", "单日最高", "liked days")),
            likesReceivedUsers = matchMetric(mapByLabel, listOf("获赞用户", "不同用户", "获赞人数", "liked users")),
            metrics = metrics,
            isLiveConnectData = true
        )
    }

    /**
     * 基于 `/u/{username}.json` + `/u/{username}/summary.json` 真实账户数据生成精准的晋升/保级进度报告
     * （当用户为 Lv.1/Lv.2 尚未展示 TL3 表格、或 connect.linux.do 尚未完成 OAuth 跳转时，100% 展示真实个人统计数据）
     */
    fun buildRealProfileConnectReport(
        username: String,
        trustLevel: Int,
        daysVisited: Int,
        topicsEntered: Int,
        postsReadCount: Int,
        topicCount: Int,
        postCount: Int,
        likesGiven: Int,
        likesReceived: Int,
        timeReadSeconds: Int
    ): ConnectTrustLevelReport {
        fun metric(label: String, value: Int, suffix: String = "") = ConnectMetricItem(
            label, value, 0, "$value$suffix", "未提供", false
        )
        return ConnectTrustLevelReport(
            title = "Lv.$trustLevel 账户累计统计 (@$username)",
            subtitle = "Connect 周期数据暂未取得；以下为账户累计统计",
            statusBadgeText = "累计统计，非 Connect 周期数据",
            isQualified = false, timePeriodDays = 0,
            daysVisited = daysVisited, topicsEntered = topicsEntered, postsRead = postsReadCount,
            topicsRepliedTo = postCount, likesGiven = likesGiven, likesReceived = likesReceived,
            likesReceivedDays = 0, likesReceivedUsers = 0,
            metrics = listOf(
                metric("累计访问天数", daysVisited, " 天"), metric("累计浏览话题", topicsEntered),
                metric("累计已读帖子", postsReadCount), metric("发布话题", topicCount),
                metric("发布回复", postCount), metric("送出点赞", likesGiven), metric("获得点赞", likesReceived),
                metric("累计阅读时长", timeReadSeconds / 60, " 分钟")
            ),
            isLiveConnectData = false
        )
    }

    fun buildSessionBaselineReport(sessionTopicsEntered: Int, sessionPostsRead: Int): ConnectTrustLevelReport {
        return ConnectTrustLevelReport(
            title = "Connect", subtitle = "尚未取得 Connect 数据", statusBadgeText = "尚未同步",
            isQualified = false, timePeriodDays = 0, daysVisited = 0, topicsEntered = 0, postsRead = 0,
            topicsRepliedTo = 0, likesGiven = 0, likesReceived = 0, likesReceivedDays = 0, likesReceivedUsers = 0,
            metrics = emptyList(), isLiveConnectData = false
        )
    }

    internal fun parseCssVar(text: String, varName: String): Int {
        val match = Regex("""$varName\s*:\s*([0-9.]+)""").find(text) ?: return 0
        return match.groupValues.getOrNull(1)?.toDoubleOrNull()?.roundToInt() ?: 0
    }

    private fun parseFirstInt(text: String): Int {
        val cleaned = text.replace(",", "").replace("，", "").trim()
        val match = Regex("""-?\d+""").find(cleaned) ?: return 0
        return match.value.toIntOrNull() ?: 0
    }

    private fun cleanText(raw: String): String {
        return do_.linux.lindo.core.model.decodeDiscourseHtmlSnippet(raw)
    }

    private fun matchMetric(data: Map<String, Int>, keywords: List<String>): Int {
        for ((k, v) in data) {
            val lower = k.lowercase()
            if (keywords.any { lower.contains(it.lowercase()) }) return v
        }
        return 0
    }

    private fun matchMetricExclude(data: Map<String, Int>, keywords: List<String>, exclude: List<String>): Int {
        for ((k, v) in data) {
            val lower = k.lowercase()
            if (keywords.any { lower.contains(it.lowercase()) } && exclude.none { lower.contains(it.lowercase()) }) {
                return v
            }
        }
        return 0
    }
}
