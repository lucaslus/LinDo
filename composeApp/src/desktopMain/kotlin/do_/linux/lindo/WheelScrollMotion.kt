package do_.linux.lindo

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

/** Finite wheel interpolation, advanced only by the window's frame clock. */
internal class WheelScrollMotion(
    private val initialLeadUnits: Double,
    private val durationSeconds: Double = 0.16
) {
    var remaining: Double = 0.0
        private set
    private var secondsLeft = 0.0
    private var velocity = 0.0
    val isActive: Boolean get() = remaining != 0.0

    fun reset() {
        remaining = 0.0
        secondsLeft = 0.0
        velocity = 0.0
    }

    /** Respond immediately at the start/reversal, then interpolate the rest without losing distance. */
    fun add(delta: Double, limit: Double): Double {
        if (!delta.isFinite() || delta == 0.0) return 0.0
        if (remaining != 0.0 && sign(remaining) != sign(delta)) reset()
        val starting = !isActive
        remaining = (remaining + delta).coerceIn(-limit, limit)
        secondsLeft = durationSeconds
        val lead = if (starting) sign(remaining) * min(abs(remaining), initialLeadUnits) else 0.0
        remaining -= lead
        // Preserve velocity between notches. Restarting an ease-out curve makes
        // every new input jump back to peak speed, even when the hand is steady.
        if (starting) velocity = remaining * 2.0 / durationSeconds
        return lead
    }

    fun advance(elapsedSeconds: Double): Double {
        if (!isActive || !elapsedSeconds.isFinite() || elapsedSeconds <= 0.0) return 0.0
        val dt = min(elapsedSeconds, secondsLeft)
        val duration = secondsLeft
        val t = (dt / duration).coerceIn(0.0, 1.0)
        val distance = remaining
        // Monotone cubic Hermite: current speed -> zero at the finite endpoint.
        val v = sign(distance) * abs(velocity).coerceAtMost(3.0 * abs(distance) / duration)
        val step = distance * (3 * t * t - 2 * t * t * t) + v * duration * (t * t * t - 2 * t * t + t)
        velocity = distance * (6 * t - 6 * t * t) / duration + v * (3 * t * t - 4 * t + 1)
        remaining = distance - step
        secondsLeft -= dt
        if (dt >= duration || abs(remaining) <= 0.05) {
            reset()
            return distance
        }
        return step
    }
}
