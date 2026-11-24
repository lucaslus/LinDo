package do_.linux.lindo.core.network

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import do_.linux.lindo.core.runUiSafeStateWrite
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.math.max
import kotlin.math.min

/**
 * 4 档单调渐进式过盾打扰等级（与 PC Rust `VerifyPresentation` 完全同构）：
 * `SILENT(0) < PILL(1) < BLOCKING(2) < MANUAL(3)` — 并发合流只能升舱、绝不降级。
 */
enum class VerifyPresentation(val level: Int) {
    /** 后台静默预热 / 临期无感续期：绝不展示任何 UI，遇人工验证主动放弃且不计失败。 */
    SILENT(0),
    /** 轻量非侵入进度胶囊：后台验证超过 2s 仍未结束时浮现，点击可主动升舱展开验证面板。 */
    PILL(1),
    /** 前台等待态：先同实例隐身加载 `/challenge`，仅当 Turnstile 触发 `interactiveBegin` 或超时才原位升舱弹窗。 */
    BLOCKING(2),
    /** 用户手动点击验证：绕过一切自动冷却，直接展开验证面板。 */
    MANUAL(3);

    fun allowsPillUi(): Boolean = level >= PILL.level
    fun allowsPanelUi(): Boolean = level >= BLOCKING.level

    companion object {
        fun fromLevel(level: Int): VerifyPresentation = entries.firstOrNull { it.level == level } ?: MANUAL
    }
}

data class VerifyOutcome(
    val passed: Boolean,
    val round: Long,
    val ineffectiveBlocked: Boolean = false,
    val cooldownRemainMs: Long = 0L
)

data class VerifyHealthSnapshot(
    val active: Boolean = false,
    val round: Long = 0L,
    val consecutiveFailures: Int = 0,
    val cooldownRemainMs: Long = 0L,
    val cooldownIsIneffective: Boolean = false,
    val silentBackoffRemainMs: Long = 0L,
    val lastPassedAgeSecs: Long? = null
)

sealed interface VerifyJoin {
    class Leader(
        val round: Long,
        private val presentationAtom: AtomicInteger,
        val onEscalated: MutableList<(VerifyPresentation) -> Unit> = mutableListOf()
    ) : VerifyJoin {
        fun currentPresentation(): VerifyPresentation = VerifyPresentation.fromLevel(presentationAtom.get())
    }

    class Follower(
        val round: Long,
        val deferred: CompletableDeferred<VerifyOutcome>
    ) : VerifyJoin

    data class RecentPass(val round: Long) : VerifyJoin

    data class BlockedByCooldown(
        val remainMs: Long,
        val ineffective: Boolean
    ) : VerifyJoin
}

sealed interface IneffectiveClearanceDecision {
    /** 第 1 次命中无效票据：触发一次进程内净室网络自愈重置并干净重拉验证页，不立即冷冻 60s */
    data object SelfHealAndRetryOnce : IneffectiveClearanceDecision
    /** 净室重置重验后第 2 次仍撞盾：拉起 60s 无效票据熔断器阻断死循环 */
    data class TripBreaker(val cooldownRemainMs: Long) : IneffectiveClearanceDecision
}

/**
 * 权威单飞过盾协调器（对齐 PC Rust `VerifyCoordinator`）：
 * - 单飞合流 + 4 档单调升舱；
 * - 轮次号（round）去重记失败（并发 N 个请求同轮失败仅记 1 次）；
 * - 连续 3 轮失败进入 30s 冷却；
 * - 过盾后 15s 内立即复撞：第 1 次触发进程内净室自愈重验，连续第 2 次触发 60s 无效票据熔断；
 * - 静默验证失败仅 45s 静默退避，绝不计入用户失败；
 * - `MANUAL` 手动点击绕过所有自动冷却。
 */
class VerifyCoordinator {
    companion object {
        const val MAX_FAILURES_BEFORE_COOLDOWN = 3
        const val FAILURE_COOLDOWN_MS = 30_000L
        const val INEFFECTIVE_COOLDOWN_MS = 60_000L
        const val SILENT_BACKOFF_MS = 45_000L
        const val RECENT_PASS_COALESCE_MS = 5_000L
        const val INEFFECTIVE_WINDOW_MS = 15_000L
        const val PILL_DELAY_MS = 2_000L
    }

    private class ActiveFlight(
        val round: Long,
        val presentation: AtomicInteger,
        val deferred: CompletableDeferred<VerifyOutcome>,
        val listeners: MutableList<(VerifyPresentation) -> Unit> = mutableListOf()
    )

    private var round: Long = 0L
    private var active: ActiveFlight? = null
    private var consecutiveFailures: Int = 0
    private var failureCountedRound: Long? = null
    private var cooldownUntilMs: Long = 0L
    private var cooldownIsIneffective: Boolean = false
    private var silentBackoffUntilMs: Long = 0L
    private var lastPassedRound: Long = 0L
    private var lastPassedAtMs: Long = 0L
    private var ineffectiveHealUsedSinceHealthy: Boolean = false
    private var unresolvedIneffectiveClearance: Boolean = false

    @Synchronized
    fun isActive(): Boolean = active != null

    @Synchronized
    fun currentRound(): Long = round

    @Synchronized
    fun lastPassedRound(): Long = lastPassedRound

    @Synchronized
    fun hasUnresolvedIneffectiveClearance(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (cooldownUntilMs > 0L && nowMs >= cooldownUntilMs) {
            cooldownUntilMs = 0L
            cooldownIsIneffective = false
            unresolvedIneffectiveClearance = false
            consecutiveFailures = 0
            failureCountedRound = null
        }
        return unresolvedIneffectiveClearance || (cooldownIsIneffective && cooldownUntilMs > nowMs)
    }

    @Synchronized
    fun joinOrStart(
        requested: VerifyPresentation,
        nowMs: Long = System.currentTimeMillis(),
        isPostPassReplay: Boolean = false
    ): VerifyJoin {
        val currentFlight = active
        if (currentFlight != null) {
            val prev = currentFlight.presentation.getAndAccumulate(requested.level) { a, b -> max(a, b) }
            if (requested.level > prev) {
                val upgraded = VerifyPresentation.fromLevel(requested.level)
                currentFlight.listeners.forEach { runCatching { it(upgraded) } }
            }
            return VerifyJoin.Follower(currentFlight.round, currentFlight.deferred)
        }

        if (cooldownUntilMs > 0L && nowMs >= cooldownUntilMs) {
            cooldownUntilMs = 0L
            cooldownIsIneffective = false
            unresolvedIneffectiveClearance = false
            consecutiveFailures = 0
            failureCountedRound = null
        }
        if (silentBackoffUntilMs > 0L && nowMs >= silentBackoffUntilMs) {
            silentBackoffUntilMs = 0L
        }

        if (requested != VerifyPresentation.MANUAL) {
            if (cooldownUntilMs > nowMs) {
                return VerifyJoin.BlockedByCooldown(
                    remainMs = cooldownUntilMs - nowMs,
                    ineffective = cooldownIsIneffective
                )
            }
            if (requested == VerifyPresentation.SILENT && silentBackoffUntilMs > nowMs) {
                return VerifyJoin.BlockedByCooldown(
                    remainMs = silentBackoffUntilMs - nowMs,
                    ineffective = false
                )
            }
            if (!isPostPassReplay && lastPassedAtMs > 0L && (nowMs - lastPassedAtMs) <= RECENT_PASS_COALESCE_MS) {
                return VerifyJoin.RecentPass(lastPassedRound)
            }
        }

        round += 1L
        val r = round
        val pres = AtomicInteger(requested.level)
        val deferred = CompletableDeferred<VerifyOutcome>()
        val flight = ActiveFlight(r, pres, deferred)
        active = flight
        return VerifyJoin.Leader(r, pres, flight.listeners)
    }

    @Synchronized
    fun finishFlight(
        round: Long,
        passed: Boolean,
        finalPresentation: VerifyPresentation,
        nowMs: Long = System.currentTimeMillis(),
        abandoned: Boolean = false
    ): VerifyOutcome {
        val flight = active?.takeIf { it.round == round }?.also { active = null }

        if (!passed && abandoned) {
            // 被动中止（App 切后台 / WebView 被系统回收 / 交棒给前台验证面板）不是一次真实失败：
            // 绝不计失败、也不进入 45s 静默退避，否则用户回到前台后的第一次真实挑战将无法静默续期。
        } else if (passed) {
            consecutiveFailures = 0
            failureCountedRound = null
            cooldownUntilMs = 0L
            cooldownIsIneffective = false
            silentBackoffUntilMs = 0L
            lastPassedRound = round
            lastPassedAtMs = nowMs
        } else if (finalPresentation == VerifyPresentation.SILENT) {
            silentBackoffUntilMs = nowMs + SILENT_BACKOFF_MS
        } else {
            recordRoundFailureLocked(round, nowMs)
        }

        val remain = (cooldownUntilMs - nowMs).coerceAtLeast(0L)
        val outcome = VerifyOutcome(
            passed = passed,
            round = round,
            ineffectiveBlocked = cooldownIsIneffective && remain > 0L,
            cooldownRemainMs = remain
        )
        flight?.deferred?.complete(outcome)
        return outcome
    }

    @Synchronized
    fun recordRoundFailure(round: Long?, nowMs: Long = System.currentTimeMillis()): Long {
        recordRoundFailureLocked(round, nowMs)
        return (cooldownUntilMs - nowMs).coerceAtLeast(0L)
    }

    private fun recordRoundFailureLocked(round: Long?, nowMs: Long) {
        if (round != null) {
            if (failureCountedRound == round) return
            failureCountedRound = round
        }
        consecutiveFailures += 1
        if (consecutiveFailures >= MAX_FAILURES_BEFORE_COOLDOWN) {
            cooldownUntilMs = nowMs + FAILURE_COOLDOWN_MS
            cooldownIsIneffective = false
        }
    }

    @Synchronized
    fun onPostVerifyReplayChallenged(
        round: Long,
        nowMs: Long = System.currentTimeMillis()
    ): IneffectiveClearanceDecision {
        unresolvedIneffectiveClearance = true
        return if (!ineffectiveHealUsedSinceHealthy) {
            ineffectiveHealUsedSinceHealthy = true
            lastPassedAtMs = 0L
            IneffectiveClearanceDecision.SelfHealAndRetryOnce
        } else {
            val remain = recordIneffectiveClearance(round, nowMs)
            IneffectiveClearanceDecision.TripBreaker(remain)
        }
    }

    @Synchronized
    fun recordIneffectiveClearance(round: Long, nowMs: Long = System.currentTimeMillis()): Long {
        unresolvedIneffectiveClearance = true
        val withinWindow = lastPassedRound == round &&
            (lastPassedAtMs <= 0L || (nowMs - lastPassedAtMs) <= INEFFECTIVE_WINDOW_MS)
        if (withinWindow || failureCountedRound != round) {
            failureCountedRound = round
            consecutiveFailures = MAX_FAILURES_BEFORE_COOLDOWN
            cooldownUntilMs = nowMs + INEFFECTIVE_COOLDOWN_MS
            cooldownIsIneffective = true
            lastPassedAtMs = 0L
        }
        return (cooldownUntilMs - nowMs).coerceAtLeast(0L)
    }

    @Synchronized
    fun clearCooldownsForCleanReset(preserveAutoHealGuard: Boolean = false) {
        consecutiveFailures = 0
        failureCountedRound = null
        cooldownUntilMs = 0L
        cooldownIsIneffective = false
        silentBackoffUntilMs = 0L
        lastPassedAtMs = 0L
        if (!preserveAutoHealGuard) {
            ineffectiveHealUsedSinceHealthy = false
            unresolvedIneffectiveClearance = false
        }
    }

    @Synchronized
    fun resetOnHealthyResponse() {
        consecutiveFailures = 0
        failureCountedRound = null
        cooldownUntilMs = 0L
        cooldownIsIneffective = false
        silentBackoffUntilMs = 0L
        ineffectiveHealUsedSinceHealthy = false
        unresolvedIneffectiveClearance = false
    }

    @Synchronized
    fun resetOnSessionChange() {
        resetOnHealthyResponse()
        lastPassedAtMs = 0L
    }

    @Synchronized
    fun resetForTesting() {
        active?.deferred?.cancel()
        active = null
        round = 0L
        lastPassedRound = 0L
        resetOnSessionChange()
    }

    @Synchronized
    fun snapshot(nowMs: Long = System.currentTimeMillis()): VerifyHealthSnapshot = VerifyHealthSnapshot(
        active = active != null,
        round = round,
        consecutiveFailures = consecutiveFailures,
        cooldownRemainMs = (cooldownUntilMs - nowMs).coerceAtLeast(0L),
        cooldownIsIneffective = cooldownIsIneffective && cooldownUntilMs > nowMs,
        silentBackoffRemainMs = (silentBackoffUntilMs - nowMs).coerceAtLeast(0L),
        lastPassedAgeSecs = if (lastPassedAtMs > 0L) ((nowMs - lastPassedAtMs).coerceAtLeast(0L)) / 1000L else null
    )
}

/**
 * 自适应两段式 CF 429 分类器（双端统一 · 超越 FluxDO 与旧版 Android）：
 * - 存在健康在位票据且首次突发 `429 + cf-mitigated: challenge`：先执行短冷却退让（保护健康票据不被突发速率误杀）；
 * - 12 秒窗内连续第 2 次命中，或当前无健康票据：立即判定票据失效，升级进入单飞 `VerifyCoordinator` 轮换！
 */
sealed interface Cf429Decision {
    data class ShortCooldown(val cooldownMs: Long) : Cf429Decision
    data object EscalateVerify : Cf429Decision
}

class Cf429AdaptiveClassifier(
    private val burstWindowMs: Long = 12_000L,
    private val firstHitCooldownMs: Long = 1_800L
) {
    private var consecutiveHits: Int = 0
    private var lastHitAtMs: Long = 0L

    @Synchronized
    fun classify(hasHealthyIncumbent: Boolean, nowMs: Long = System.currentTimeMillis()): Cf429Decision {
        if (lastHitAtMs > 0L && (nowMs - lastHitAtMs) > burstWindowMs) {
            consecutiveHits = 0
        }
        lastHitAtMs = nowMs
        consecutiveHits += 1
        return if (hasHealthyIncumbent && consecutiveHits == 1) {
            Cf429Decision.ShortCooldown(firstHitCooldownMs)
        } else {
            Cf429Decision.EscalateVerify
        }
    }

    @Synchronized
    fun noteHealthy2xx() {
        consecutiveHits = 0
        lastHitAtMs = 0L
    }

    @Synchronized
    fun reset() {
        consecutiveHits = 0
        lastHitAtMs = 0L
    }
}

/**
 * AIMD 自适应拥塞控制令牌桶（对齐 PC Rust `AdaptiveTokenBucket`）：
 * - 真 `429`：乘性降速 `×0.55` 并清空突发放行余额；
 * - 正常 `2xx`：加性恢复 `+0.12 req/s` 直至上限。
 */
class AdaptiveTokenBucket(
    private val initialRate: Double = 3.4,
    private val minRate: Double = 1.0,
    private val maxRate: Double = 4.2,
    private val maxBurst: Double = 4.5
) {
    private var rate: Double = initialRate.coerceIn(minRate, maxRate)
    private var tokens: Double = maxBurst
    private var lastRefillMs: Long = System.currentTimeMillis()
    private var total429Count: Int = 0

    @Synchronized
    fun currentRate(): Double = rate

    @Synchronized
    fun total429s(): Int = total429Count

    @Synchronized
    fun reserveDelayMs(nowMs: Long = System.currentTimeMillis(), cost: Double = 1.0): Long {
        val elapsedSec = ((nowMs - lastRefillMs).coerceAtLeast(0L)) / 1000.0
        tokens = min(maxBurst, tokens + elapsedSec * rate)
        lastRefillMs = nowMs
        tokens -= cost
        return if (tokens >= 0.0) {
            0L
        } else {
            val waitSec = (-tokens) / rate.coerceAtLeast(0.2)
            (waitSec * 1000.0).toLong().coerceIn(0L, 4_000L)
        }
    }

    @Synchronized
    fun onTrue429(nowMs: Long = System.currentTimeMillis()) {
        total429Count += 1
        rate = max(minRate, rate * 0.55)
        tokens = min(tokens, 0.0)
        lastRefillMs = nowMs
    }

    @Synchronized
    fun onSuccess2xx() {
        rate = min(maxRate, rate + 0.12)
    }

    @Synchronized
    fun reset() {
        rate = initialRate.coerceIn(minRate, maxRate)
        tokens = maxBurst
        lastRefillMs = System.currentTimeMillis()
        total429Count = 0
    }
}

enum class RequestPriority(val weight: Int) {
    LOW(0),
    NORMAL(1),
    HIGH(2)
}

/**
 * 3 级优先级并发门控（含 4s Aging 防饥饿 + 后台配额上限，对齐 PC Rust `PriorityGate`）。
 */
class PriorityGate(
    private val maxInflight: Int = 4,
    private val maxLowInflight: Int = 1,
    private val agingBoostAfterMs: Long = 4_000L
) {
    private class Waiter(
        val seq: Long,
        val priority: RequestPriority,
        val enqueuedAtMs: Long,
        val deferred: CompletableDeferred<Permit>
    )

    inner class Permit internal constructor(private val isLow: Boolean) {
        private var released = false
        fun release() {
            synchronized(lock) {
                if (released) return
                released = true
                releasePermitLocked(isLow)
            }
        }
    }

    private val lock = Any()
    private val seqGen = AtomicLong(0L)
    private var inflight = 0
    private var lowInflight = 0
    private val waiters = mutableListOf<Waiter>()

    suspend fun acquire(priority: RequestPriority, nowMs: Long = System.currentTimeMillis()): Permit {
        val waiter: Waiter = synchronized(lock) {
            val isLow = priority == RequestPriority.LOW
            val canEnter = inflight < maxInflight && (!isLow || lowInflight < maxLowInflight) && waiters.isEmpty()
            if (canEnter) {
                inflight += 1
                if (isLow) lowInflight += 1
                return Permit(isLow)
            }
            val w = Waiter(seqGen.incrementAndGet(), priority, nowMs, CompletableDeferred())
            waiters.add(w)
            w
        }
        return try {
            waiter.deferred.await()
        } catch (cancelled: Throwable) {
            synchronized(lock) { waiters.remove(waiter) }
            if (waiter.deferred.isCompleted && !waiter.deferred.isCancelled) {
                runCatching {
                    @OptIn(ExperimentalCoroutinesApi::class)
                    waiter.deferred.getCompleted().release()
                }
            }
            throw cancelled
        }
    }

    private fun releasePermitLocked(wasLow: Boolean) {
        inflight = (inflight - 1).coerceAtLeast(0)
        if (wasLow) lowInflight = (lowInflight - 1).coerceAtLeast(0)
        val nowMs = System.currentTimeMillis()
        while (inflight < maxInflight && waiters.isNotEmpty()) {
            val eligibleIdx = pickNextEligibleIndexLocked(nowMs)
            if (eligibleIdx < 0) break
            val next = waiters.removeAt(eligibleIdx)
            if (next.deferred.isCancelled) continue
            val isLow = next.priority == RequestPriority.LOW
            inflight += 1
            if (isLow) lowInflight += 1
            if (!next.deferred.complete(Permit(isLow))) {
                inflight = (inflight - 1).coerceAtLeast(0)
                if (isLow) lowInflight = (lowInflight - 1).coerceAtLeast(0)
            }
        }
    }

    fun resetForTesting() {
        val toCancel: List<Waiter>
        synchronized(lock) {
            toCancel = waiters.toList()
            waiters.clear()
            inflight = 0
            lowInflight = 0
        }
        toCancel.forEach { it.deferred.cancel() }
    }

    private fun pickNextEligibleIndexLocked(nowMs: Long): Int {
        var bestIdx = -1
        var bestScore = Int.MIN_VALUE
        var bestSeq = Long.MAX_VALUE
        for (i in waiters.indices) {
            val w = waiters[i]
            if (w.priority == RequestPriority.LOW && lowInflight >= maxLowInflight) continue
            val aged = if ((nowMs - w.enqueuedAtMs) >= agingBoostAfterMs) 1 else 0
            val score = (w.priority.weight + aged).coerceAtMost(RequestPriority.HIGH.weight)
            if (score > bestScore || (score == bestScore && w.seq < bestSeq)) {
                bestScore = score
                bestSeq = w.seq
                bestIdx = i
            }
        }
        return bestIdx
    }
}

/**
 * 结构性重试预算（对齐 PC Rust `AttemptBudget`）：
 * 5xx / 网络瞬断仅允许幂等方法（GET/HEAD/OPTIONS）退避重放，严禁自动重放 POST/PUT/DELETE 导致重复发帖。
 */
class AttemptBudget(
    method: String,
    private val maxTransientRetries: Int = 2,
    private val maxCfReplays: Int = 1
) {
    val isIdempotent: Boolean = method.uppercase() in setOf("GET", "HEAD", "OPTIONS")
    private var transientUsed: Int = 0
    private var cfUsed: Int = 0

    fun canRetryTransient(): Boolean = isIdempotent && transientUsed < maxTransientRetries

    fun consumeTransient(): Boolean {
        if (!canRetryTransient()) return false
        transientUsed += 1
        return true
    }

    fun consumeCfReplay(): Boolean {
        if (cfUsed >= maxCfReplays) return false
        cfUsed += 1
        return true
    }
}

/**
 * 端到端逻辑总时限（对齐 PC Rust `CallDeadline`）：
 * 支持在 Cloudflare 人工验证交互期 `pause()` / `resume()` 挂起计时，防止刚勾完框请求就被误杀。
 */
class CallDeadline(
    private val budgetMs: Long,
    private val startedAtMs: Long = System.currentTimeMillis()
) {
    private var pausedAtMs: Long? = null
    private var pausedTotalMs: Long = 0L

    @Synchronized
    fun pause(nowMs: Long = System.currentTimeMillis()) {
        if (pausedAtMs == null) pausedAtMs = nowMs
    }

    @Synchronized
    fun resume(nowMs: Long = System.currentTimeMillis()) {
        val p = pausedAtMs ?: return
        pausedTotalMs += (nowMs - p).coerceAtLeast(0L)
        pausedAtMs = null
    }

    @Synchronized
    fun elapsedActiveMs(nowMs: Long = System.currentTimeMillis()): Long {
        val wall = (nowMs - startedAtMs).coerceAtLeast(0L)
        val livePause = pausedAtMs?.let { (nowMs - it).coerceAtLeast(0L) } ?: 0L
        return (wall - pausedTotalMs - livePause).coerceAtLeast(0L)
    }

    @Synchronized
    fun remainingMs(nowMs: Long = System.currentTimeMillis()): Long =
        (budgetMs - elapsedActiveMs(nowMs)).coerceAtLeast(0L)

    @Synchronized
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
        elapsedActiveMs(nowMs) >= budgetMs

    companion object {
        fun forRequest(isRead: Boolean, isBackground: Boolean, nowMs: Long = System.currentTimeMillis()): CallDeadline {
            val budget = when {
                isBackground -> 14_000L
                isRead -> 28_000L
                else -> 35_000L
            }
            return CallDeadline(budget, nowMs)
        }
    }
}

/**
 * 协程上下文传递的当前请求 `CallDeadline`：
 * 使平台层 `silentRevalidateChallenge` / `verifyInteractiveChallengeSession` 在等待安全验证完成期间
 * 自动调用 `pause()` / `resume()` 冻结外层调用时限，杜绝人工勾框刚通过外层请求却因预算耗尽被误杀。
 */
class ActiveCallDeadlineContext(
    val deadline: CallDeadline
) : AbstractCoroutineContextElement(ActiveCallDeadlineContext) {
    companion object Key : CoroutineContext.Key<ActiveCallDeadlineContext>
}

data class BackgroundChallengeDecision(
    val invalidateClearance: Boolean,
    val requestPrewarm: Boolean,
    val streak: Int,
    val writeStreak: Int
)

/**
 * 后台请求挑战实测证伪追踪器：
 * - 第 1 次后台撞盾且持有在位 `cf_clearance`：不毒化票据、不触发预热（防止瞬时毛刺引发死循环）；
 * - 连续 >= 2 次后台撞盾（如 VPN 后台切出口 IP 后，`GET` 仍放行但 `POST /topics/timings`、`PUT /notifications/mark-read.json` 持续 403）：
 *   立即证伪旧 IP 残留票据并触发单飞零 UI 预热，彻底终结长达数小时的 `hasCf=true, prewarm=false` 静默死锁；
 * - 注意：普通 `GET 2xx` 仅清零纯读抖动计数，绝不掩盖持续失败的后台写挑战计数（`consecutiveWriteHits` 仅在真实写 `2xx` 或新票据过盾后清零）。
 */
class BackgroundChallengeTracker(
    private val staleResetWindowMs: Long = 30L * 60L * 1000L,
    private val readWaveWindowMs: Long = 5_000L
) {
    private var readWaves: Int = 0
    private var lastReadWaveAtMs: Long = 0L
    private var consecutiveWriteHits: Int = 0
    private var lastHitAtMs: Long = 0L

    @Synchronized
    fun onBackgroundChallenge(
        isWrite: Boolean,
        hasHealthyClearance: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): BackgroundChallengeDecision {
        if (lastHitAtMs > 0L && (nowMs - lastHitAtMs) > staleResetWindowMs) {
            readWaves = 0
            lastReadWaveAtMs = 0L
            consecutiveWriteHits = 0
        }
        lastHitAtMs = nowMs
        if (isWrite) {
            consecutiveWriteHits += 1
        } else if (lastReadWaveAtMs <= 0L || (nowMs - lastReadWaveAtMs) >= readWaveWindowMs) {
            readWaves += 1
            lastReadWaveAtMs = nowMs
        }
        if (!hasHealthyClearance) {
            return BackgroundChallengeDecision(
                invalidateClearance = false,
                requestPrewarm = true,
                streak = readWaves + consecutiveWriteHits,
                writeStreak = consecutiveWriteHits
            )
        }
        val shouldEscalate = consecutiveWriteHits >= 2 || readWaves >= 3
        return BackgroundChallengeDecision(
            invalidateClearance = shouldEscalate,
            requestPrewarm = shouldEscalate,
            streak = readWaves + consecutiveWriteHits,
            writeStreak = consecutiveWriteHits
        )
    }

    @Synchronized
    fun noteHealthyResponse(isWrite: Boolean) {
        if (isWrite) {
            readWaves = 0
            lastReadWaveAtMs = 0L
            consecutiveWriteHits = 0
            lastHitAtMs = 0L
        } else {
            readWaves = 0
            lastReadWaveAtMs = 0L
            if (consecutiveWriteHits == 0) {
                lastHitAtMs = 0L
            }
        }
    }

    @Synchronized
    fun hasUnresolvedWriteChallenges(): Boolean = consecutiveWriteHits > 0

    @Synchronized
    fun reset() {
        readWaves = 0
        lastReadWaveAtMs = 0L
        consecutiveWriteHits = 0
        lastHitAtMs = 0L
    }
}

/**
 * 统一只读网络健康快照（P2：对齐 PC 端 `DiagRuntimeSnapshot`）。
 */
data class NetworkHealthSnapshot(
    val profileName: String,
    val webViewGeneration: Int,
    val networkSignature: String,
    val hasHealthyCfClearance: Boolean,
    val cfClearanceAgeSecs: Long?,
    val cfTotalRotations: Int,
    val verifyActive: Boolean,
    val verifyRound: Long,
    val verifyConsecutiveFailures: Int,
    val verifyCooldownRemainMs: Long,
    val verifyIneffectiveBreaker: Boolean,
    val apiRatePerSec: Double,
    val api429Total: Int,
    val mediaRatePerSec: Double,
    val media429Total: Int,
    val csrfInCooldown: Boolean
) {
    fun toDiagnosticLine(): String =
        "profile=$profileName gen=$webViewGeneration net=$networkSignature cfHealthy=$hasHealthyCfClearance cfAge=${cfClearanceAgeSecs ?: -1}s cfRot=$cfTotalRotations verifyActive=$verifyActive round=$verifyRound fail=$verifyConsecutiveFailures cool=${verifyCooldownRemainMs}ms breaker=$verifyIneffectiveBreaker apiRate=${"%.2f".format(apiRatePerSec)} api429=$api429Total mediaRate=${"%.2f".format(mediaRatePerSec)} csrfCool=$csrfInCooldown"
}

/**
 * 波次去重式连接池黑洞检测器（遵循 Google SRE / FluxDO 6c188979 熔断去重与 OkHttp 阶段分离原则）：
 * 1. `http_fetch_body` / `*_body` 说明 TCP/TLS/QUIC 已建连且响应头已回传，绝不计入连接池黑洞；
 * 2. 同一时间窗（默认 4s）内并发超时的多条请求归并为「1 波」，杜绝单次点击并发 2 个 GET 瞬间误判黑洞；
 * 3. 仅在至少 2 个独立时间波次均发生建连阶段超时、且距上次 Profile 轮换超过 5 分钟冷却时，才允许作为最后逃生舱轮换 Profile。
 */
class TimeoutBlackholeDetector(
    private val waveWindowMs: Long = 4_000L,
    private val requiredWaves: Int = 2,
    private val rotateCooldownMs: Long = 300_000L
) {
    private var waves: Int = 0
    private var lastWaveAtMs: Long = 0L
    private var lastRotatedAtMs: Long = 0L

    @Synchronized
    fun waveCount(): Int = waves

    @Synchronized
    fun noteHealthyResponse() {
        waves = 0
        lastWaveAtMs = 0L
    }

    @Synchronized
    fun markProfileRotated(nowMs: Long = System.currentTimeMillis()) {
        waves = 0
        lastWaveAtMs = 0L
        lastRotatedAtMs = nowMs
    }

    @Synchronized
    fun shouldRotateProfile(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (waves < requiredWaves) return false
        return lastRotatedAtMs <= 0L || (nowMs - lastRotatedAtMs) >= rotateCooldownMs
    }

    @Synchronized
    fun recordConnectTimeout(phase: String?, nowMs: Long = System.currentTimeMillis()): Boolean {
        val cleanPhase = phase?.trim()?.lowercase().orEmpty()
        // 响应头已到达、正在读正文 (http_fetch_body / csrf_fetch_body) 证明连接池健康，绝不判为黑洞
        if (cleanPhase.endsWith("_body")) {
            return false
        }
        if (lastWaveAtMs <= 0L || (nowMs - lastWaveAtMs) >= waveWindowMs) {
            waves += 1
            lastWaveAtMs = nowMs
        }
        return shouldRotateProfile(nowMs)
    }

    @Synchronized
    fun resetForTesting() {
        waves = 0
        lastWaveAtMs = 0L
        lastRotatedAtMs = 0L
    }
}

/**
 * 统一网络策略单例引擎（Android / KMP 共享，对齐 PC Rust `net_policy.rs`）。
 */
/**
 * 静默过盾触发来源：
 * - [FOREGROUND]：用户正在等的前台请求撞上挑战（可显示胶囊；确需人工时原位升舱弹出验证面板）；
 * - [PREWARM]：冷启动 / 登录完成 / 回前台 / 后台请求撞盾后的预热续期（全程零 UI，需人工时直接放弃）。
 */
enum class SilentVerifyTrigger { FOREGROUND, PREWARM }

/** 单次静默探测观察到的页面状态（由平台 WebView 探针映射而来）。 */
enum class SilentProbeSignal {
    /** 页面仍在加载 / 普通 Cloudflare 托管挑战页（含 Turnstile 组件但尚未要求交互）。 */
    PENDING,
    /** 新 `cf_clearance` 已签发，或页面已落到源站（`/challenge` 404 / Discourse 页面）。 */
    PASSED,
    /** Turnstile `interactiveBegin`：确实需要用户亲手点一下。 */
    INTERACTIVE_REQUIRED,
    /** WebView 已失效（渲染进程被杀 / 主框架加载错误）。 */
    GONE
}

sealed interface SilentVerifyStep {
    data object Continue : SilentVerifyStep
    data object Pass : SilentVerifyStep
    /** 用户正在连续打字（距上次输入 `< 2.5s`）：暂缓收起键盘弹模态窗，先展示右下角安全确认小提示框并保活 WebView */
    data object HoldForTyping : SilentVerifyStep
    /** 原位交棒同一 WebView 给前台验证面板（零二次加载）。 */
    data class Escalate(val reason: String) : SilentVerifyStep
    /** 放弃但不计失败（不进入静默退避）。 */
    data class Abandon(val reason: String) : SilentVerifyStep
    /** 真实失败（进入静默退避）。 */
    data class Fail(val reason: String) : SilentVerifyStep
}

/**
 * 静默过盾与前台主动优雅升舱策略：
 * - 非交互挑战自动放行：全程 0 胶囊、0 弹窗；
 * - 一旦确认需要人工交互（`interactiveBegin` 或 `widget == true` 满 3.5s 未自动通过）：
 *   1. 若用户在 180s 点 `×` 免打扰期内且非主动写操作：不自动硬弹大窗，保留右下角「需安全确认」小提示框；
 *   2. 若用户正在输入栏连续打字（`< 2.5s`）且尚未点击发送：暂不抢键盘，先展示右下角小提示框，停笔 `>= 2.5s` 或点击小提示框 / 发送立即弹出；
 *   3. 其余前台常规状态（冷启动、唤醒恢复、切网、刷帖、后台 `timings`/`notifications` 实测撞盾）：直接原位升舱主动弹出「安全确认」！
 */
object SilentVerifyPolicy {
    const val FOREGROUND_BUDGET_MS = 9_000L
    const val PREWARM_BUDGET_MS = 20_000L
    const val PILL_AFTER_MS = 2_000L
    const val WIDGET_INTERACTIVE_CONFIRM_MS = 3_500L
    const val USER_DISMISS_SNOOZE_MS = 180_000L
    const val ACTIVE_TYPING_GUARD_MS = 2_500L

    fun budgetMs(trigger: SilentVerifyTrigger): Long = when (trigger) {
        SilentVerifyTrigger.FOREGROUND -> FOREGROUND_BUDGET_MS
        SilentVerifyTrigger.PREWARM -> PREWARM_BUDGET_MS
    }

    fun isInteractiveConfirmed(interactiveBegin: Boolean, widgetMountedDurationMs: Long): Boolean =
        interactiveBegin || widgetMountedDurationMs >= WIDGET_INTERACTIVE_CONFIRM_MS

    fun shouldShowPill(trigger: SilentVerifyTrigger, elapsedMs: Long): Boolean =
        trigger == SilentVerifyTrigger.FOREGROUND && elapsedMs >= PILL_AFTER_MS

    fun decide(
        trigger: SilentVerifyTrigger,
        signal: SilentProbeSignal,
        elapsedMs: Long,
        appForeground: Boolean,
        userRequestedPanel: Boolean = false,
        isForegroundWrite: Boolean = false,
        isUserDismissSnoozed: Boolean = false,
        isUserActivelyTyping: Boolean = false
    ): SilentVerifyStep {
        if (signal == SilentProbeSignal.PASSED) return SilentVerifyStep.Pass
        if (signal == SilentProbeSignal.GONE) return SilentVerifyStep.Fail("webview_gone")
        if (!appForeground) return SilentVerifyStep.Abandon("app_backgrounded")
        if (userRequestedPanel) {
            return SilentVerifyStep.Escalate("user_expanded_pill")
        }
        if (signal == SilentProbeSignal.INTERACTIVE_REQUIRED) {
            if (isForegroundWrite) {
                return SilentVerifyStep.Escalate("interactive_required")
            }
            if (isUserDismissSnoozed) {
                return SilentVerifyStep.Abandon("snoozed_by_user_dismiss")
            }
            if (isUserActivelyTyping) {
                return SilentVerifyStep.HoldForTyping
            }
            return SilentVerifyStep.Escalate(
                if (trigger == SilentVerifyTrigger.FOREGROUND) "interactive_required" else "interactive_required_proactive"
            )
        }
        if (elapsedMs >= budgetMs(trigger)) {
            return if (trigger == SilentVerifyTrigger.FOREGROUND) {
                if (!isForegroundWrite && isUserDismissSnoozed) {
                    SilentVerifyStep.Abandon("snoozed_by_user_dismiss")
                } else if (!isForegroundWrite && isUserActivelyTyping) {
                    SilentVerifyStep.HoldForTyping
                } else {
                    SilentVerifyStep.Escalate("foreground_budget_exhausted")
                }
            } else {
                SilentVerifyStep.Abandon("prewarm_timeout")
            }
        }
        return SilentVerifyStep.Continue
    }

    /**
     * 后台请求（`Discourse-Background: true`，如 `/topics/timings`、通知轮询）撞到挑战时，
     * 绝不能拉起全局 12s 熔断、也不能把当前 `cf_clearance` 标记为失效——那会让用户下一次点开帖子
     * 不发请求直接被短路成「需要完成站点验证」。后台请求只负责触发一次零 UI 预热。
     */
    fun backgroundChallengeArmsGlobalPause(isBackgroundRequest: Boolean): Boolean = !isBackgroundRequest
}

object NetworkPolicyEngine {
    const val CF_NEAR_EXPIRY_SILENT_REFRESH_SECS: Long = 10L * 3600L + 1800L // 10.5h

    val verifyCoordinator = VerifyCoordinator()
    val cf429Classifier = Cf429AdaptiveClassifier()
    val apiTokenBucket = AdaptiveTokenBucket(initialRate = 3.4, minRate = 1.0, maxRate = 4.2, maxBurst = 4.5)
    val mediaTokenBucket = AdaptiveTokenBucket(initialRate = 5.0, minRate = 1.2, maxRate = 6.5, maxBurst = 6.0)
    val priorityGate = PriorityGate(maxInflight = 4, maxLowInflight = 1)
    val timeoutBlackholeDetector = TimeoutBlackholeDetector()
    val backgroundChallengeTracker = BackgroundChallengeTracker()

    /** 由平台层（如 MainActivity）挂载的只读实时运行态元数据提供器：(profileName, webViewGen, netSignature) */
    @Volatile
    var liveRuntimeMetaProvider: (() -> Triple<String, Int, String>)? = null

    /** 右下角常驻轻量提醒（「需安全确认」）：在打字保护期或用户点 `×` 关闭后的免打扰期展示，点击立即打开验证面板 */
    var verifyAttentionNeeded by mutableStateOf(false)
        private set

    private val snoozeLock = Any()
    private var userDismissedUntilMs: Long = 0L
    private var userDismissedNetSig: String = ""

    @Volatile
    private var lastUserTypingAtMs: Long = 0L
    @Volatile
    private var imeComposingActive: Boolean = false
    @Volatile
    private var activeComposerOpenedAtMs: Long = 0L
    @Volatile
    var pendingInteractiveVerifyFromBackground: Boolean = false

    fun updateVerifyAttentionNeeded(needed: Boolean) {
        runUiSafeStateWrite {
            verifyAttentionNeeded = needed
        }
    }

    fun currentEffectiveNetworkSignature(): String =
        normalizeEffectiveNetworkSignature(liveRuntimeMetaProvider?.invoke()?.third.orEmpty()).ifBlank { "default" }

    fun recordUserDismissedVerify(
        networkSignature: String = currentEffectiveNetworkSignature(),
        nowMs: Long = System.currentTimeMillis()
    ) {
        val normSig = normalizeEffectiveNetworkSignature(networkSignature).ifBlank { "default" }
        synchronized(snoozeLock) {
            userDismissedUntilMs = nowMs + SilentVerifyPolicy.USER_DISMISS_SNOOZE_MS
            userDismissedNetSig = normSig
        }
        updateVerifyAttentionNeeded(true)
    }

    fun isProactiveVerifySnoozed(
        networkSignature: String = currentEffectiveNetworkSignature(),
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val normSig = normalizeEffectiveNetworkSignature(networkSignature).ifBlank { "default" }
        synchronized(snoozeLock) {
            if (userDismissedUntilMs <= 0L) return false
            if (nowMs >= userDismissedUntilMs || normSig != userDismissedNetSig) {
                userDismissedUntilMs = 0L
                userDismissedNetSig = ""
                return false
            }
            return true
        }
    }

    fun clearUserDismissedVerify() {
        synchronized(snoozeLock) {
            userDismissedUntilMs = 0L
            userDismissedNetSig = ""
        }
    }

    fun clearVerifyAttentionAndSnooze() {
        clearUserDismissedVerify()
        pendingInteractiveVerifyFromBackground = false
        updateVerifyAttentionNeeded(false)
    }

    fun noteUserTyping(nowMs: Long = System.currentTimeMillis(), imeComposing: Boolean = false) {
        lastUserTypingAtMs = nowMs
        imeComposingActive = imeComposing
    }

    fun setComposerOpenState(open: Boolean, nowMs: Long = System.currentTimeMillis()) {
        if (open) {
            if (activeComposerOpenedAtMs <= 0L) activeComposerOpenedAtMs = nowMs
        } else {
            activeComposerOpenedAtMs = 0L
            imeComposingActive = false
        }
    }

    fun isUserActivelyTyping(
        nowMs: Long = System.currentTimeMillis(),
        latestDraftUpdatedAtMs: Long = 0L
    ): Boolean {
        val lastDirect = lastUserTypingAtMs
        if (lastDirect > 0L && (nowMs - lastDirect).coerceAtLeast(0L) < SilentVerifyPolicy.ACTIVE_TYPING_GUARD_MS) {
            return true
        }
        if (imeComposingActive && lastDirect > 0L && (nowMs - lastDirect).coerceAtLeast(0L) < 8_000L) {
            return true
        }
        val openedAt = activeComposerOpenedAtMs
        if (openedAt > 0L &&
            latestDraftUpdatedAtMs > openedAt + 250L &&
            (nowMs - latestDraftUpdatedAtMs).coerceAtLeast(0L) < SilentVerifyPolicy.ACTIVE_TYPING_GUARD_MS
        ) {
            return true
        }
        return false
    }

    fun consumePendingInteractiveVerify(): Boolean {
        val had = pendingInteractiveVerifyFromBackground
        pendingInteractiveVerifyFromBackground = false
        return had
    }

    private val profileMigratedSessionCookies = setOf("_t", "_forum_session", "authentication_data")

    /**
     * 跨 Profile 迁移 Cookie 白名单：仅允许迁移 Discourse 账号会话 Cookie，
     * 严禁手工合成或回写 `cf_clearance` / `__cf_bm` / `_cfuvid`（避免丢弃 CHIPS `Partitioned` 属性造出非分区假副本导致持续 403）。
     */
    fun isSessionCookieAllowedForProfileMigration(cookieName: String): Boolean =
        cookieName.trim() in profileMigratedSessionCookies

    /**
     * 规范化有效网络签名：当活跃 VPN 隧道接口（如 `tun0`）与 DNS 均未变化时，
     * 将底层 `wifi,vpn|tun0|198.18.0.2` 与 `cell,vpn|tun0|198.18.0.2` 折叠为同一有效签名 `vpn|tun0|198.18.0.2`，
     * 避免 Android/三星双通道在 VPN 下 77ms 内连发十余次无意义切网抖动风暴。
     */
    fun normalizeEffectiveNetworkSignature(rawSig: String): String {
        val clean = rawSig.trim()
        if (clean.isEmpty()) return ""
        val parts = clean.split('|')
        if (parts.size == 3) {
            val transports = parts[0].split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val iface = parts[1].trim()
            val dns = parts[2].trim()
            if ("vpn" in transports && iface.isNotEmpty() && dns.isNotEmpty()) {
                return "vpn|$iface|$dns"
            }
        }
        return clean
    }

    /**
     * 提取非 VPN 的底层物理网络承载集合（如 `"wifi"`、`"cell"`、`"eth"`）：
     * 用于在 VPN 隧道接口保持不断开（`vpn|tun0|198.18.0.2`）时，经 2.0s 稳态去抖精准识别真实的「Wi-Fi ↔ 蜂窝 5G」物理链路迁移，
     * 既完全免疫 77ms 双通道瞬态抖动，又能在真切网后及时回收挂死的 H2 连接池并放开 `cf_clearance` 轮换窗口。
     */
    fun extractPhysicalTransportSet(rawSig: String): String {
        val clean = rawSig.trim()
        if (clean.isEmpty()) return ""
        val transportPart = clean.substringBefore('|')
        return transportPart
            .split(',')
            .map { it.trim().lowercase() }
            .filter { it == "wifi" || it == "cell" || it == "eth" }
            .distinct()
            .sorted()
            .joinToString(",")
    }

    /** CSRF 刷新失败冷却截止时间（P1 统一收口，30s 冷却避免 403/429 风暴） */
    @Volatile
    private var csrfCooldownUntilMs: Long = 0L

    private val waitSecondsJsonRegex = Regex("""\"(?:wait_seconds|time_left)\"\s*:\s*\"?(\d+)\"?""", RegexOption.IGNORE_CASE)
    private val cnWaitRegex = Regex("""(?:等待|请稍候|再试|间隔)\s*(\d+)\s*(秒|分钟|分)""")
    private val enWaitRegex = Regex("""(?:wait|try again in|retry in)\s+(\d+)\s*(seconds?|secs?|minutes?|mins?)""", RegexOption.IGNORE_CASE)

    fun isCsrfInCooldown(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs < csrfCooldownUntilMs

    fun noteCsrfRefreshFailed(nowMs: Long = System.currentTimeMillis(), cooldownMs: Long = 30_000L) {
        csrfCooldownUntilMs = nowMs + cooldownMs
    }

    fun clearCsrfCooldown() {
        csrfCooldownUntilMs = 0L
    }

    fun isLogoutExemptFromSessionRecovery(method: String, url: String): Boolean =
        method.equals("DELETE", ignoreCase = true) && url.contains("/session/", ignoreCase = true)

    fun shouldTriggerNearExpirySilentRefresh(
        ageSecs: Long?,
        activeFgRequests: Int,
        isVerifying: Boolean
    ): Boolean {
        if (activeFgRequests > 0 || isVerifying) return false
        return ageSecs != null && ageSecs >= CF_NEAR_EXPIRY_SILENT_REFRESH_SECS
    }

    fun buildHealthSnapshot(
        profileName: String = "default",
        webViewGeneration: Int = 0,
        networkSignature: String = "unknown",
        nowMs: Long = System.currentTimeMillis()
    ): NetworkHealthSnapshot {
        val vSnap = verifyCoordinator.snapshot(nowMs)
        return NetworkHealthSnapshot(
            profileName = profileName,
            webViewGeneration = webViewGeneration,
            networkSignature = networkSignature,
            hasHealthyCfClearance = CfClearanceAuthority.hasHealthyIncumbent(),
            cfClearanceAgeSecs = CfClearanceAuthority.ageSecs(nowMs),
            cfTotalRotations = CfClearanceAuthority.totalRotations,
            verifyActive = vSnap.active,
            verifyRound = vSnap.round,
            verifyConsecutiveFailures = vSnap.consecutiveFailures,
            verifyCooldownRemainMs = vSnap.cooldownRemainMs,
            verifyIneffectiveBreaker = vSnap.cooldownIsIneffective,
            apiRatePerSec = apiTokenBucket.currentRate(),
            api429Total = apiTokenBucket.total429s(),
            mediaRatePerSec = mediaTokenBucket.currentRate(),
            media429Total = mediaTokenBucket.total429s(),
            csrfInCooldown = isCsrfInCooldown(nowMs)
        )
    }

    /**
     * 完整 4 来源 `Retry-After` 解析（与 PC Rust `parse_retry_after_secs` 完全对齐）：
     * 1. `Retry-After` 响应头（秒数或 RFC-7231 HTTP-Date）；
     * 2. `X-RateLimit-Reset` 响应头（Unix epoch 秒或相对秒数）；
     * 3. Discourse JSON `extras.wait_seconds` / `extras.time_left`；
     * 4. 中英文错误提示文本（如 `"请等待 2 分钟"` / `"try again in 15 seconds"`）。
     */
    fun parseRetryAfterMillis(
        retryAfterHeader: String? = null,
        rateLimitResetHeader: String? = null,
        bodyText: String? = null,
        nowMs: Long = System.currentTimeMillis()
    ): Long {
        val nowSec = nowMs / 1000L

        // 1. Retry-After header
        val ra = retryAfterHeader?.trim().orEmpty()
        if (ra.isNotEmpty()) {
            ra.toLongOrNull()?.let { secs ->
                return (secs.coerceIn(1L, 604_800L) * 1000L)
            }
            runCatching {
                val targetMs = ZonedDateTime.parse(ra, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
                val diff = (targetMs - nowMs).coerceIn(1_000L, 604_800_000L)
                return diff
            }
        }

        // 2. X-RateLimit-Reset header (Unix epoch or delta seconds)
        val rl = rateLimitResetHeader?.trim().orEmpty()
        if (rl.isNotEmpty()) {
            rl.toLongOrNull()?.let { raw ->
                val secs = if (raw > 1_000_000_000L) (raw - nowSec) else raw
                if (secs > 0L) return secs.coerceIn(1L, 600L) * 1000L
            }
        }

        // 3. JSON extras.wait_seconds / extras.time_left
        val body = bodyText.orEmpty()
        if (body.isNotBlank()) {
            waitSecondsJsonRegex.find(body)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { secs ->
                if (secs > 0L) return secs.coerceIn(1L, 600L) * 1000L
            }
            // 4. CN / EN text hints
            cnWaitRegex.find(body)?.let { m ->
                val n = m.groupValues[1].toLongOrNull() ?: 0L
                val unit = m.groupValues[2]
                if (n > 0L) {
                    val secs = if (unit.startsWith("分")) n * 60L else n
                    return secs.coerceIn(1L, 600L) * 1000L
                }
            }
            enWaitRegex.find(body)?.let { m ->
                val n = m.groupValues[1].toLongOrNull() ?: 0L
                val unit = m.groupValues[2].lowercase()
                if (n > 0L) {
                    val secs = if (unit.startsWith("min")) n * 60L else n
                    return secs.coerceIn(1L, 600L) * 1000L
                }
            }
        }

        return 2_000L
    }

    fun resetOnNetworkChange() {
        verifyCoordinator.resetOnHealthyResponse()
        cf429Classifier.reset()
        timeoutBlackholeDetector.noteHealthyResponse()
        backgroundChallengeTracker.reset()
        clearCsrfCooldown()
        clearVerifyAttentionAndSnooze()
        CfClearanceAuthority.reset()
    }

    fun resetForTesting() {
        verifyCoordinator.resetForTesting()
        cf429Classifier.reset()
        apiTokenBucket.reset()
        mediaTokenBucket.reset()
        priorityGate.resetForTesting()
        timeoutBlackholeDetector.resetForTesting()
        backgroundChallengeTracker.reset()
        clearCsrfCooldown()
        clearVerifyAttentionAndSnooze()
        lastUserTypingAtMs = 0L
        imeComposingActive = false
        activeComposerOpenedAtMs = 0L
        pendingInteractiveVerifyFromBackground = false
    }
}
