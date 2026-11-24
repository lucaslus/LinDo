package do_.linux.lindo.core.network

enum class VerifyTicketOutcome {
    /** `/challenge` 成功签发了与旧值不同的新 `cf_clearance` */
    ROTATED,
    /** `cf_clearance` 未变，且此前未被实测挑战证伪（如临期预热/恢复检查） */
    EXISTING_VALID,
    /** `cf_clearance` 未变，但该值刚被实测 403/429 挑战拒绝（`/challenge` 未触发全站盾直达源站 404，严禁洗白旧票据） */
    STALE_UNCHALLENGED_404,
    /** 浏览器中不存在任何 `cf_clearance` */
    ABSENT
}

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

    /**
     * 基于实测证据判定 `/challenge` 结束后的票据状态：
     * - 仅当 `freshClearance != previousClearance` 时才算真正换新（`ROTATED`）；
     * - 若旧票据此前已被真实 403/429 挑战证伪（`previousWasChallenged == true`），而 `/challenge` 因局部 WAF 规则未拦截直达源站 404，
     *   必须返回 `STALE_UNCHALLENGED_404`，绝不能把刚被拒的死票据重新洗白为 `verified=true`；
     * - 切网 / 路由栈翻转 / Profile 变更时统一调用 [reset]，杜绝残留旧 `incumbent` 造成假阳性。
     */
    fun evaluateVerifyOutcome(
        freshClearance: String?,
        previousClearance: String?,
        previousWasChallenged: Boolean
    ): VerifyTicketOutcome {
        val cleanFresh = freshClearance?.trim().orEmpty()
        if (cleanFresh.isEmpty()) return VerifyTicketOutcome.ABSENT
        val cleanPrev = previousClearance?.trim().orEmpty()
        if (cleanPrev.isEmpty() || cleanFresh != cleanPrev) {
            return VerifyTicketOutcome.ROTATED
        }
        return if (previousWasChallenged) {
            VerifyTicketOutcome.STALE_UNCHALLENGED_404
        } else {
            VerifyTicketOutcome.EXISTING_VALID
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
