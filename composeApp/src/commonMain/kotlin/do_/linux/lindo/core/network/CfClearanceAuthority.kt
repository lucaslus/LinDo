package do_.linux.lindo.core.network

/**
 * `cf_clearance` 权威状态机（吸收 FluxDO `CfClearanceAuthority` 核心经验并结合单变体 `.linux.do` 纪律）：
 * 1. 当 `incumbent`（在任票据）处于健康未受挑战状态时，锁定保护该值不被旧变体覆盖；
 * 2. 一旦请求命中 `cf-mitigated: challenge` 或挑战 HTML，立即将该票据标记为 `challenged`（失效死票），瞬间打开轮换窗口；
 * 3. 当用户在内置验证页完成人机验证（`verified = true`）或浏览器自然轮换出新票据时，优先晋升非 `challenged` 的新票据并淘汰残留死票。
 */
object CfClearanceAuthority {
    private const val MAX_CHALLENGED_HISTORY = 16
    private val challengedHistory = ArrayDeque<String>(MAX_CHALLENGED_HISTORY)

    @Volatile
    var incumbent: String? = null
        private set

    @Volatile
    var challenged: String? = null
        private set

    @Volatile
    var updatedAtEpochMs: Long = 0L
        private set

    @Synchronized
    fun isChallenged(value: String?): Boolean {
        val clean = value?.trim().orEmpty()
        if (clean.isEmpty()) return false
        return clean == challenged || challengedHistory.contains(clean)
    }

    private fun pushChallengedHistory(token: String) {
        challengedHistory.remove(token)
        while (challengedHistory.size >= MAX_CHALLENGED_HISTORY) {
            challengedHistory.removeFirst()
        }
        challengedHistory.addLast(token)
    }

    @Synchronized
    fun confirmHealthy(value: String?, nowMs: Long = System.currentTimeMillis()) {
        val clean = value?.trim().orEmpty()
        if (clean.isEmpty() || isChallenged(clean)) return
        incumbent = clean
        updatedAtEpochMs = nowMs
    }

    @Synchronized
    fun noteChallenged(carriedValue: String? = null) {
        val target = carriedValue?.trim()?.takeIf { it.isNotEmpty() } ?: incumbent
        if (!target.isNullOrEmpty()) {
            if (incumbent == target) {
                incumbent = null
            }
            challenged = target
            pushChallengedHistory(target)
        }
    }

    @Synchronized
    fun selectWinner(
        observedValues: List<String>,
        verified: Boolean = false,
        nowMs: Long = System.currentTimeMillis()
    ): String? {
        val cleaned = observedValues
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val unchallenged = cleaned.filterNot { isChallenged(it) }

        if (verified && cleaned.isNotEmpty()) {
            val rotated = unchallenged.lastOrNull { it != incumbent }
                ?: unchallenged.lastOrNull()
                ?: cleaned.lastOrNull { it != challenged }
            if (rotated != null) {
                challengedHistory.remove(rotated)
                incumbent = rotated
                challenged = null
                updatedAtEpochMs = nowMs
                return rotated
            }
        }

        val currentIncumbent = incumbent
        if (!currentIncumbent.isNullOrEmpty() && !isChallenged(currentIncumbent)) {
            if (currentIncumbent in unchallenged) {
                if (updatedAtEpochMs == 0L) updatedAtEpochMs = nowMs
                return currentIncumbent
            }
        }

        val natural = unchallenged.lastOrNull()
        if (natural != null) {
            incumbent = natural
            updatedAtEpochMs = nowMs
            return natural
        }

        return null
    }

    @Synchronized
    fun reset() {
        incumbent = null
        challenged = null
        challengedHistory.clear()
        updatedAtEpochMs = 0L
    }
}
