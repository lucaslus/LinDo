package do_.linux.lindo.ui

import kotlin.math.roundToInt

/**
 * 大厂最佳实践三层非线性字体缩放策略（与 PC 端 `typographyScale.ts` 1:1 同口径）：
 * - reading：核心阅读层（Feed 标题、详情标题、帖子正文、回复）100% 跟随用户档位（0.88x ~ 1.28x）
 * - meta：辅助信息层（版块、标签胶囊、时间、互动数）50% 温和缩放并锁安全边界（0.94x ~ 1.14x）
 * - chrome：固定导航骨架层（顶栏、底栏、悬浮操作按钮）25% 极小幅微调防爆版（0.96x ~ 1.08x）
 */
data class TieredFontScale(
    val reading: Float,
    val meta: Float,
    val chrome: Float
)

object TypographyScalePolicy {
    private fun round3(value: Float): Float = (value * 1000f).roundToInt() / 1000f

    fun tiered(rawScale: Float): TieredFontScale {
        val safe = if (rawScale.isFinite() && rawScale > 0f) rawScale else 1f
        val reading = safe.coerceIn(0.85f, 1.35f)
        val delta = reading - 1f
        return TieredFontScale(
            reading = round3(reading),
            meta = round3((1f + delta * 0.5f).coerceIn(0.94f, 1.14f)),
            chrome = round3((1f + delta * 0.25f).coerceIn(0.96f, 1.08f))
        )
    }
}
