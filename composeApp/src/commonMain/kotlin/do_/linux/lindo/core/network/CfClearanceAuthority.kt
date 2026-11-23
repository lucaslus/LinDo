package do_.linux.lindo.core.network

/**
 * `cf_clearance` v2 权威状态机（纯「在位值粘性」· 彻底废除墓碑黑名单）：
 *
 * 吸收 FluxDO 2026-08 生产事故教训并与 PC Rust `CfClearanceAuthority v2` 100% 同构：
 * 1. **绝不维护永久墓碑黑名单**：健康票据在瞬时速率波动下可能偶然撞上 429 挑战，
 *    永久拉黑会导致后续启动/恢复反复误拒健康票据并死循环弹盾；
 * 2. 仅跟踪单一在位值 `incumbent` 与单一 `challenged`：
 *    - `verified == true`（过盾/登录完成）立即晋升新票据并清除 `challenged`；
 *    - `incumbent` 健康且未超过 11h 自然轮换窗口、且仍在浏览器存储中 -> 锁定保留；
 *    - 超过 11h 自然轮换窗口或无在位值 -> 平滑采用最新有效票据；
 *    - 浏览器偶发空读（`observedValues.isEmpty()`）且在位值 12h 内健康 -> 保护不丢弃。
 */
object CfClearanceAuthority {
    const val CF_COLD_SEED_MAX_AGE_MS: Long = 12L * 3600L * 1000L
    const val CF_NATURAL_ROTATION_AGE_MS: Long = 11L * 3600L * 1000L

    @Volatile
    var incumbent: String? = null
        private set

    @Volatile
    var challenged: String? = null
        private set

    @Volatile
    var updatedAtEpochMs: Long = 0L
        private set

    @Volatile
    var totalRotations: Int = 0
        private set

    @Synchronized
    fun isChallenged(value: String?): Boolean {
        val clean = value?.trim().orEmpty()
        if (clean.isEmpty()) return false
        return clean == challenged
    }

    @Synchronized
    fun hasHealthyIncumbent(): Boolean {
        val inc = incumbent?.trim().orEmpty()
        return inc.isNotEmpty() && inc != challenged
    }

    @Synchronized
    fun ageSecs(nowMs: Long = System.currentTimeMillis()): Long? {
        if (incumbent.isNullOrEmpty() || updatedAtEpochMs <= 0L) return null
        return ((nowMs - updatedAtEpochMs).coerceAtLeast(0L)) / 1000L
    }

    @Synchronized
    fun confirmHealthy(value: String?, nowMs: Long = System.currentTimeMillis()) {
        val clean = value?.trim().orEmpty()
        if (clean.isEmpty() || isChallenged(clean)) return
        if (incumbent != clean) {
            incumbent = clean
            updatedAtEpochMs = nowMs
        } else if (updatedAtEpochMs <= 0L) {
            updatedAtEpochMs = nowMs
        }
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

    /** 切网 / VPN 出口变化时放开自然轮换窗口，但不拉黑当前值 */
    @Synchronized
    fun markRotationOpen() {
        updatedAtEpochMs = 0L
        challenged = null
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
                if (incumbent != rotated) totalRotations += 1
                incumbent = rotated
                challenged = null
                updatedAtEpochMs = nowMs
                return rotated
            }
        }

        val currentIncumbent = incumbent
        if (!currentIncumbent.isNullOrEmpty() && !isChallenged(currentIncumbent)) {
            val withinStickyTtl = updatedAtEpochMs <= 0L ||
                (nowMs - updatedAtEpochMs) < CF_NATURAL_ROTATION_AGE_MS
            if (withinStickyTtl && currentIncumbent in unchallenged) {
                if (updatedAtEpochMs <= 0L) updatedAtEpochMs = nowMs
                return currentIncumbent
            }
        }

        val natural = unchallenged.lastOrNull()
        if (natural != null) {
            if (incumbent != natural) totalRotations += 1
            incumbent = natural
            updatedAtEpochMs = nowMs
            return natural
        }

        // 瞬时浏览器 Cookie 空读保护：在位票据 12h 内健康时避免误判掉票
        if (observedValues.isEmpty() && !currentIncumbent.isNullOrEmpty() && !isChallenged(currentIncumbent)) {
            if (updatedAtEpochMs > 0L && (nowMs - updatedAtEpochMs) <= CF_COLD_SEED_MAX_AGE_MS) {
                return currentIncumbent
            }
        }

        return null
    }

    @Synchronized
    fun reset() {
        incumbent = null
        challenged = null
        updatedAtEpochMs = 0L
        totalRotations = 0
    }
}
