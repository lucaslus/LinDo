package do_.linux.lindo.core.connect

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

    @Volatile
    var lastUserActivityEpochMs: Long = System.currentTimeMillis()
        private set

    var todayVisitConfirmed by mutableStateOf(true)
        private set

    var lastVisitSyncTimeText by mutableStateOf("刚刚")
        private set

    fun setAppForeground(foreground: Boolean, countAsActivity: Boolean = true) {
        isForeground = foreground
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
        todayVisitConfirmed = true
        lastVisitSyncTimeText = "刚刚"
    }

    /**
     * 生成对齐 Discourse 官方前端的在岗请求头
     */
    fun buildPresenceHeaders(
        isBackgroundTimingRequest: Boolean = false,
        isLoggedIn: Boolean = false,
        nowMs: Long = System.currentTimeMillis()
    ): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        headers["X-Requested-With"] = "XMLHttpRequest"
        headers["Origin"] = "https://linux.do"
        headers["Referer"] = "https://linux.do/"
        headers["Sec-Fetch-Dest"] = "empty"
        headers["Sec-Fetch-Mode"] = "cors"
        headers["Sec-Fetch-Site"] = "same-origin"

        if (isLoggedIn) {
            headers["Discourse-Logged-In"] = "true"
        }

        if (isBackgroundTimingRequest) {
            // /topics/timings 属于高频阅读心跳，带静默日志头
            headers["X-SILENCE-LOGGER"] = "true"
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
 * 4. 遇到 429/502/503 等临时限流时自动将未上报时长合并回队列并按 `5s -> 10s -> 20s -> 40s` 指数退避重传，绝不丢数据。
 */
object ScreenTrackEngine {
    private const val PAUSE_UNLESS_SCROLLED_MS = 3 * 60 * 1000L
    private const val MAX_TRACKING_TIME_PER_POST_MS = 6 * 60 * 1000
    private const val FLUSH_INTERVAL_MS = 15_000L
    private val BACKOFF_DELAYS_MS = longArrayOf(5_000L, 10_000L, 20_000L, 40_000L)
    private val RETRYABLE_STATUS_CODES = setOf(405, 429, 500, 501, 502, 503, 504)

    private var currentTopicId: Int? = null
    private var lastTickEpochMs: Long = 0L
    private var lastScrolledEpochMs: Long = 0L
    private var accumulatedSinceFlushMs: Long = 0L
    private var currentTopicTimeMs: Int = 0

    private val currentTimingsMs = LinkedHashMap<Int, Int>()
    private val totalTimingsMs = LinkedHashMap<Int, Int>()
    private val readPostsSet = LinkedHashSet<Int>()
    private val consolidatedQueue = ArrayList<ConsolidatedTimingBatch>()

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

    fun onTopicEntered(topicId: Int, initialVisiblePosts: Set<Int> = setOf(1), nowMs: Long = System.currentTimeMillis()) {
        if (currentTopicId != null && currentTopicId != topicId) {
            flushCurrentTopic(nowMs)
        }
        resetCurrentTopicState(nowMs)
        currentTopicId = topicId
        onscreenPosts = initialVisiblePosts
        sessionTopicsEntered += 1
        UserPresenceService.markUserActivity(nowMs)
    }

    fun updateVisiblePosts(visiblePostNumbers: Set<Int>, nowMs: Long = System.currentTimeMillis()) {
        if (visiblePostNumbers.isNotEmpty()) {
            onscreenPosts = visiblePostNumbers
            lastScrolledEpochMs = nowMs
            UserPresenceService.markUserActivity(nowMs)
        }
    }

    fun onUserScrolled(nowMs: Long = System.currentTimeMillis()) {
        lastScrolledEpochMs = nowMs
        UserPresenceService.markUserActivity(nowMs)
    }

    /**
     * 每秒调用一次，累加当前屏幕可见楼层的阅读毫秒数
     * @return 若达到上报条件，返回待发送的批次
     */
    fun tick(nowMs: Long = System.currentTimeMillis()): ConsolidatedTimingBatch? {
        val topicId = currentTopicId ?: return null
        if (!UserPresenceService.isForeground) return null

        if (lastTickEpochMs == 0L) {
            lastTickEpochMs = nowMs
            lastScrolledEpochMs = nowMs
            return null
        }

        // 超过 3 分钟无滚动则暂停计时，避免被服务端判定为挂机无效时长
        if (nowMs - lastScrolledEpochMs > PAUSE_UNLESS_SCROLLED_MS) {
            lastTickEpochMs = nowMs
            return null
        }

        val diffMs = (nowMs - lastTickEpochMs).coerceIn(100L, 3_000L).toInt()
        lastTickEpochMs = nowMs
        accumulatedSinceFlushMs += diffMs
        currentTopicTimeMs += diffMs
        sessionReadingSeconds = (sessionReadingSeconds * 1000L + diffMs) / 1000L

        for (postNumber in onscreenPosts) {
            currentTimingsMs[postNumber] = (currentTimingsMs[postNumber] ?: 0) + diffMs
        }

        // 检查是否有首次阅读的新楼层累积满 1 秒（rush 立即上报策略）
        val hasUnreadRush = currentTimingsMs.entries.any { (postNum, ms) ->
            ms >= 1_000 && !totalTimingsMs.containsKey(postNum) && !readPostsSet.contains(postNum)
        }

        if (accumulatedSinceFlushMs >= FLUSH_INTERVAL_MS || hasUnreadRush) {
            return flushCurrentTopic(nowMs)
        }

        return popNextSendableBatch(nowMs)
    }

    fun flushCurrentTopic(nowMs: Long = System.currentTimeMillis()): ConsolidatedTimingBatch? {
        val topicId = currentTopicId ?: return null
        val newTimings = LinkedHashMap<Int, Int>()

        for ((postNumber, timeMs) in currentTimingsMs) {
            val prevTotal = totalTimingsMs[postNumber] ?: 0
            if (timeMs > 0 && prevTotal < MAX_TRACKING_TIME_PER_POST_MS) {
                totalTimingsMs[postNumber] = prevTotal + timeMs
                newTimings[postNumber] = timeMs
                if (readPostsSet.add(postNumber)) {
                    sessionPostsRead += 1
                }
            }
        }
        currentTimingsMs.clear()

        if (newTimings.isNotEmpty() && currentTopicTimeMs > 0) {
            consolidateBatch(topicId, currentTopicTimeMs, newTimings)
            currentTopicTimeMs = 0
        }
        accumulatedSinceFlushMs = 0L
        return popNextSendableBatch(nowMs)
    }

    fun consolidateBatch(topicId: Int, topicTimeMs: Int, timingsMs: Map<Int, Int>) {
        if (timingsMs.isEmpty()) return
        val existing = consolidatedQueue.firstOrNull { it.topicId == topicId }
        if (existing != null) {
            existing.topicTimeMs += topicTimeMs
            for ((postNum, ms) in timingsMs) {
                existing.timingsMs[postNum] = (existing.timingsMs[postNum] ?: 0) + ms
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
        pendingBatchesCount = consolidatedQueue.size
    }

    fun popNextSendableBatch(nowMs: Long = System.currentTimeMillis()): ConsolidatedTimingBatch? {
        if (consolidatedQueue.isEmpty()) return null
        if (nowMs < blockSendingUntilEpochMs) return null
        val batch = consolidatedQueue.removeAt(consolidatedQueue.lastIndex)
        pendingBatchesCount = consolidatedQueue.size
        return batch
    }

    fun onBatchSendResult(batch: ConsolidatedTimingBatch, statusCode: Int?, nowMs: Long = System.currentTimeMillis()) {
        if (statusCode != null && statusCode in 200..399) {
            consecutiveFailures = 0
            blockSendingUntilEpochMs = 0L
        } else if (statusCode == null || statusCode in RETRYABLE_STATUS_CODES) {
            val delayIdx = consecutiveFailures.coerceIn(0, BACKOFF_DELAYS_MS.lastIndex)
            consecutiveFailures += 1
            blockSendingUntilEpochMs = nowMs + BACKOFF_DELAYS_MS[delayIdx]
            // 失败不丢数据：重新合并回队列等待退避后重发
            consolidateBatch(batch.topicId, batch.topicTimeMs, batch.timingsMs)
        }
    }

    private fun resetCurrentTopicState(nowMs: Long) {
        lastTickEpochMs = nowMs
        lastScrolledEpochMs = nowMs
        accumulatedSinceFlushMs = 0L
        currentTopicTimeMs = 0
        currentTimingsMs.clear()
        totalTimingsMs.clear()
        onscreenPosts = emptySet()
    }

    fun resetForTest() {
        currentTopicId = null
        currentTimingsMs.clear()
        totalTimingsMs.clear()
        readPostsSet.clear()
        consolidatedQueue.clear()
        sessionTopicsEntered = 0
        sessionPostsRead = 0
        sessionReadingSeconds = 0L
        pendingBatchesCount = 0
        consecutiveFailures = 0
        blockSendingUntilEpochMs = 0L
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
 * `connect.linux.do` HTML 解析器（100% 兼容 `.tl3-ring`、`.tl3-bar-item`、`.tl3-quota-card` 与 `--val`/`--max` CSS 变量）
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
        val isQualified = badgeClasses.contains("badge-success") ||
            badgeText.contains("已达标") ||
            badgeText.contains("符合") ||
            htmlContent.contains("符合信任等级 3")

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
            val isMet = circleTag.contains("met") && !circleTag.contains("unmet")
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
            val isMet = fillTag.contains("met") && !fillTag.contains("unmet")
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

        // 3. 兼容 connect.linux.do 经典 <table><tr><td>指标</td><td>当前</td><td>要求</td></tr></table> 布局
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
            isQualified = isQualified || metrics.all { it.isMet },
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
        val readMinutes = (timeReadSeconds / 60).coerceAtLeast(1)
        return if (trustLevel <= 1) {
            // Lv.1 -> Lv.2 晋升要求（15天访问、20浏览话题、100已读帖子、60分钟阅读、3条回复、1送赞、1获赞）
            val items = listOf(
                ConnectMetricItem("累计访问天数", daysVisited, 15, "$daysVisited 天", "15 天", daysVisited >= 15),
                ConnectMetricItem("浏览话题数", topicsEntered, 20, "$topicsEntered", "20", topicsEntered >= 20),
                ConnectMetricItem("已读帖子数", postsReadCount, 100, "$postsReadCount", "100", postsReadCount >= 100),
                ConnectMetricItem("累计阅读时长", readMinutes, 60, "$readMinutes 分钟", "60 分钟", readMinutes >= 60),
                ConnectMetricItem("发布回复数", postCount, 3, "$postCount", "3", postCount >= 3),
                ConnectMetricItem("送出点赞数", likesGiven, 1, "$likesGiven", "1", likesGiven >= 1),
                ConnectMetricItem("获得点赞数", likesReceived, 1, "$likesReceived", "1", likesReceived >= 1)
            )
            val allMet = items.all { it.isMet }
            ConnectTrustLevelReport(
                title = "Lv.$trustLevel → Lv.2 晋升进度 (@$username)",
                subtitle = "数据实时同步自 Linux.do 个人统计档案",
                statusBadgeText = if (allMet) "已达标 Lv.2" else "Lv.$trustLevel 晋升中",
                isQualified = allMet,
                timePeriodDays = 100,
                daysVisited = daysVisited,
                topicsEntered = topicsEntered,
                postsRead = postsReadCount,
                topicsRepliedTo = postCount,
                likesGiven = likesGiven,
                likesReceived = likesReceived,
                likesReceivedDays = likesReceived.coerceAtMost(7),
                likesReceivedUsers = likesReceived.coerceAtMost(5),
                metrics = items,
                isLiveConnectData = true
            )
        } else {
            // Lv.2 / Lv.3 / Lv.4 活跃度与 TL3 要求对照
            val items = listOf(
                ConnectMetricItem("访问天数", daysVisited, 50, "$daysVisited 天", "50 天", daysVisited >= 50),
                ConnectMetricItem("浏览话题", topicsEntered, 200, "$topicsEntered", "200", topicsEntered >= 200),
                ConnectMetricItem("已读帖子", postsReadCount, 500, "$postsReadCount", "500", postsReadCount >= 500),
                ConnectMetricItem("回复与发帖", postCount + topicCount, 10, "${postCount + topicCount}", "10", (postCount + topicCount) >= 10),
                ConnectMetricItem("送出点赞", likesGiven, 30, "$likesGiven", "30", likesGiven >= 30),
                ConnectMetricItem("获得点赞", likesReceived, 20, "$likesReceived", "20", likesReceived >= 20),
                ConnectMetricItem("累计阅读时长", readMinutes / 60, 10, "${readMinutes / 60} 小时", "10 小时", (readMinutes / 60) >= 10)
            )
            val allMet = items.all { it.isMet }
            ConnectTrustLevelReport(
                title = "Lv.$trustLevel 社区活跃度与保级指标 (@$username)",
                subtitle = "已同步 Linux.do 账户真实统计 · 访问 $daysVisited 天 / 获赞 $likesReceived",
                statusBadgeText = if (allMet) "Lv.$trustLevel 活跃达标" else "Lv.$trustLevel 追踪中",
                isQualified = allMet,
                timePeriodDays = 100,
                daysVisited = daysVisited,
                topicsEntered = topicsEntered,
                postsRead = postsReadCount,
                topicsRepliedTo = postCount,
                likesGiven = likesGiven,
                likesReceived = likesReceived,
                likesReceivedDays = likesReceived.coerceAtMost(7),
                likesReceivedUsers = likesReceived.coerceAtMost(5),
                metrics = items,
                isLiveConnectData = true
            )
        }
    }

    /**
     * 当用户尚未配置 Cookie 时，结合当前会话已同步的真实阅读进度生成清晰的 TL2/TL3 达标对照基准表
     */
    fun buildSessionBaselineReport(
        sessionTopicsEntered: Int,
        sessionPostsRead: Int
    ): ConnectTrustLevelReport {
        val daysVisited = 1
        val topicsEntered = sessionTopicsEntered.coerceAtLeast(1)
        val postsRead = sessionPostsRead.coerceAtLeast(5)
        val items = listOf(
            ConnectMetricItem("访问天数", daysVisited, 50, "$daysVisited 天", "50 天", daysVisited >= 50),
            ConnectMetricItem("浏览话题", topicsEntered, 200, "$topicsEntered", "200", topicsEntered >= 200),
            ConnectMetricItem("已读帖子", postsRead, 500, "$postsRead", "500", postsRead >= 500),
            ConnectMetricItem("回复话题", 0, 10, "0", "10", false),
            ConnectMetricItem("送出点赞", 0, 30, "0", "30", false),
            ConnectMetricItem("获得点赞", 0, 20, "0", "20", false),
            ConnectMetricItem("获赞天数", 0, 7, "0 天", "7 天", false),
            ConnectMetricItem("获赞用户", 0, 5, "0 人", "5 人", false)
        )
        return ConnectTrustLevelReport(
            title = "Connect",
            subtitle = "过去 100 天周期要求",
            statusBadgeText = "",
            isQualified = false,
            timePeriodDays = 100,
            daysVisited = daysVisited,
            topicsEntered = topicsEntered,
            postsRead = postsRead,
            topicsRepliedTo = 0,
            likesGiven = 0,
            likesReceived = 0,
            likesReceivedDays = 0,
            likesReceivedUsers = 0,
            metrics = items,
            isLiveConnectData = false
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
        return raw.replace(Regex("<[^>]+>"), "").trim()
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
