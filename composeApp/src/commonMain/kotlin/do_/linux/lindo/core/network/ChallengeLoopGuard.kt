package do_.linux.lindo.core.network

import java.net.URI

/**
 * 防「无限刷盾」熔断器。
 *
 * v1.1.10–v1.1.14 的现场日志证明：验证窗口在 `/challenge` 上「通过」后（该地址往往根本不弹盾），
 * 真正被拦的业务接口（如 `/t/{id}.json`、`boosts.json`，常见为 `429 + cf-mitigated: challenge` 限流规则）
 * 在几秒内再次被拦，于是 App 立刻再验证、再重试，形成循环。
 *
 * 规则：
 * 1. 记录最近一次前台被拦的请求（[Target]），验证窗口与静默验证优先打开这个地址，
 *    让 Cloudflare 把真正的挑战放到窗口里（[verifyNavigationUrl]）。
 * 2. 一次验证完成后 [recurrenceWindowMs] 内同类前台请求又被拦，说明这次验证没有改变站点的判定：
 *    不再自动验证，按 20s → 60s → 180s 退避（[onForegroundChallenge] 返回 [Decision.Backoff]）。
 * 3. 退避期间后台请求与同一路径族的前台请求直接本地失败，减少继续撞限流（[shouldFailFast]）。
 * 4. 被拦路径恢复正常响应、或连续 [strikeDecayMs] 没有再触发，熔断计数清零。
 */
class ChallengeLoopGuard(
    private val recurrenceWindowMs: Long = 90_000L,
    private val targetTtlMs: Long = 180_000L,
    private val backoffStepsMs: LongArray = longArrayOf(20_000L, 60_000L, 180_000L),
    private val strikeDecayMs: Long = 300_000L
) {
    data class Target(val url: String, val method: String, val status: Int, val atMs: Long) {
        val pathFamily: String get() = pathFamilyOf(url)
    }

    sealed interface Decision {
        data object Verify : Decision
        data class Backoff(
            val remainMs: Long,
            val strikes: Int,
            val newlyArmed: Boolean,
            val elapsedSinceVerifyMs: Long = 0L,
            val lastVerifyEffective: Boolean = false
        ) : Decision
    }

    private var lastTarget: Target? = null
    private var lastVerifyCompletedAtMs: Long = 0L
    private var lastVerifyEffective: Boolean = false
    private var strikes: Int = 0
    private var lastStrikeAtMs: Long = 0L
    private var backoffUntilMs: Long = 0L
    private var backoffFamily: String? = null

    @Synchronized
    fun onForegroundChallenge(url: String, method: String, status: Int, nowMs: Long = System.currentTimeMillis()): Decision {
        if (!ENABLED) return Decision.Verify
        lastTarget = Target(url, method.uppercase(), status, nowMs)
        if (backoffUntilMs > nowMs) {
            return Decision.Backoff(
                remainMs = backoffUntilMs - nowMs,
                strikes = strikes,
                newlyArmed = false,
                elapsedSinceVerifyMs = 0L,
                lastVerifyEffective = lastVerifyEffective
            )
        }
        val elapsedSinceVerify = if (lastVerifyCompletedAtMs > 0L) (nowMs - lastVerifyCompletedAtMs).coerceAtLeast(0L) else Long.MAX_VALUE
        val verifiedRecently = lastVerifyCompletedAtMs > 0L && elapsedSinceVerify <= recurrenceWindowMs
        if (!verifiedRecently) return Decision.Verify
        if (lastStrikeAtMs > 0L && (nowMs - lastStrikeAtMs) > strikeDecayMs) strikes = 0
        strikes += 1
        lastStrikeAtMs = nowMs
        val stepMs = backoffStepsMs[(strikes - 1).coerceIn(0, backoffStepsMs.lastIndex)]
        backoffUntilMs = nowMs + stepMs
        backoffFamily = pathFamilyOf(url)
        val effectiveFlag = lastVerifyEffective
        // 每个退避周期结束后只允许重新验证一次；再失败则进入下一档更长的退避。
        lastVerifyCompletedAtMs = 0L
        return Decision.Backoff(
            remainMs = stepMs,
            strikes = strikes,
            newlyArmed = true,
            elapsedSinceVerifyMs = elapsedSinceVerify,
            lastVerifyEffective = effectiveFlag
        )
    }

    @Synchronized
    fun onVerifyCompleted(effective: Boolean, nowMs: Long = System.currentTimeMillis()) {
        if (!ENABLED) return
        lastVerifyCompletedAtMs = nowMs
        lastVerifyEffective = effective
    }

    @Synchronized
    fun lastVerifyWasEffective(): Boolean = lastVerifyEffective

    @Synchronized
    fun onHealthyResponse(url: String, nowMs: Long = System.currentTimeMillis()) {
        if (!ENABLED) return
        val target = lastTarget ?: return
        if (backoffUntilMs > nowMs) return
        if (pathFamilyOf(url) == target.pathFamily) {
            strikes = 0
            lastStrikeAtMs = 0L
            lastTarget = null
            lastVerifyCompletedAtMs = 0L
        }
    }

    @Synchronized
    fun backoffRemainMs(nowMs: Long = System.currentTimeMillis()): Long =
        if (!ENABLED) 0L else (backoffUntilMs - nowMs).coerceAtLeast(0L)

    @Synchronized
    fun isBackingOff(nowMs: Long = System.currentTimeMillis()): Boolean =
        ENABLED && backoffUntilMs > nowMs

    /** 退避期间：后台请求一律本地失败；前台只拦截与被拦请求同一路径族的请求，其余页面照常加载。 */
    @Synchronized
    fun shouldFailFast(url: String, isBackground: Boolean, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!ENABLED || backoffUntilMs <= nowMs) return false
        if (isBackground) return true
        val family = backoffFamily ?: return false
        return pathFamilyOf(url) == family
    }

    @Synchronized
    fun currentTarget(nowMs: Long = System.currentTimeMillis()): Target? =
        if (!ENABLED) null else lastTarget?.takeIf { nowMs - it.atMs <= targetTtlMs }

    /** 验证窗口应打开的地址：最近被拦的请求（写请求换成同路径 GET），没有则返回 null（回落 `/challenge`）。 */
    @Synchronized
    fun verifyNavigationUrl(nowMs: Long = System.currentTimeMillis()): String? =
        if (!ENABLED) null else currentTarget(nowMs)?.let { navigationUrlFor(it.url, it.method) }

    @Synchronized
    fun snapshot(nowMs: Long = System.currentTimeMillis()): String {
        val target = currentTarget(nowMs)
        return "strikes=$strikes backoff=${backoffRemainMs(nowMs) / 1000}s target=${target?.let { "${it.method} ${it.pathFamily} ${it.status}" } ?: "none"}"
    }

    @Synchronized
    fun reset() {
        lastTarget = null
        lastVerifyCompletedAtMs = 0L
        lastVerifyEffective = false
        strikes = 0
        lastStrikeAtMs = 0L
        backoffUntilMs = 0L
        backoffFamily = null
    }

    companion object {
        /** 总开关：置为 false 时所有熔断与定向验证退化回 v1.1.14 行为。 */
        const val ENABLED: Boolean = true

        fun backoffMessage(remainMs: Long): String {
            val secs = ((remainMs + 999L) / 1000L).coerceAtLeast(1L)
            return "站点暂时限制访问，约 $secs 秒后自动恢复"
        }

        fun navigationUrlFor(url: String, method: String): String? {
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            if (!uri.scheme.equals("https", ignoreCase = true) || !uri.host.equals("linux.do", ignoreCase = true)) return null
            val path = uri.rawPath?.takeIf { it.isNotBlank() } ?: return null
            if (path == "/" || path.startsWith("/challenge") || path.startsWith("/message-bus/")) return null
            val isRead = method.equals("GET", ignoreCase = true) || method.equals("HEAD", ignoreCase = true)
            val query = if (isRead) {
                uri.rawQuery?.takeIf { it.isNotBlank() }?.let { rawQ ->
                    val normalizedQ = if (topicJsonPath.matches(path)) {
                        rawQ.replace(Regex("""(^|&)track_visit=true(?=&|$)""", RegexOption.IGNORE_CASE), "$1track_visit=false")
                    } else {
                        rawQ
                    }
                    "?$normalizedQ"
                }.orEmpty()
            } else {
                ""
            }
            return "https://linux.do$path$query#lindo-verify"
        }

        private val topicJsonPath = Regex("""^/t/(?:[^/]+/)?\d+(?:/\d+)?(?:\.json)?$""")

        fun pathFamilyOf(url: String): String {
            val path = runCatching { URI(url).rawPath }.getOrNull()?.takeIf { it.isNotBlank() } ?: return "/"
            if (topicJsonPath.matches(path)) return "/t/*"
            val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
            return when {
                segments.isEmpty() -> "/"
                segments.size == 1 -> "/" + segments[0]
                else -> "/" + segments[0] + "/*"
            }
        }
    }
}
