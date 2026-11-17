package do_.linux.lindo.core.network

/**
 * `cf_clearance` 权威状态机（吸收 FluxDO `CfClearanceAuthority` 核心经验并结合单变体 `.linux.do` 纪律）：
 * 1. 当 `incumbent`（在任票据）处于健康未受挑战状态时，锁定保护该值不被旧变体覆盖；
 * 2. 一旦请求命中 `cf-mitigated: challenge` 或挑战 HTML，立即将该票据标记为 `challenged`（失效死票），瞬间打开轮换窗口；
 * 3. 当用户在内置验证页完成人机验证（`verified = true`）或浏览器自然轮换出新票据时，优先晋升非 `challenged` 的新票据并淘汰残留死票。
 */
object CfClearanceAuthority {
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
    fun confirmHealthy(value: String?, nowMs: Long = System.currentTimeMillis()) {
        val clean = value?.trim().orEmpty()
        if (clean.isEmpty() || clean == challenged) return
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
        }
    }

    @Synchronized
    fun selectWinner(
        observedValues: List<String>,
        verified: Boolean = false,
        nowMs: Long = System.currentTimeMillis()
    ): String? {
        val valid = observedValues
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != challenged }

        if (verified && valid.isNotEmpty()) {
            val rotated = valid.lastOrNull { it != incumbent } ?: valid.last()
            incumbent = rotated
            challenged = null
            updatedAtEpochMs = nowMs
            return rotated
        }

        val currentIncumbent = incumbent
        if (!currentIncumbent.isNullOrEmpty() && currentIncumbent != challenged) {
            if (currentIncumbent in valid) {
                if (updatedAtEpochMs == 0L) updatedAtEpochMs = nowMs
                return currentIncumbent
            }
        }

        val natural = valid.lastOrNull()
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
        updatedAtEpochMs = 0L
    }
}
