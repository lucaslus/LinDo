package do_.linux.lindo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WheelScrollMotionTest {
    @Test fun startsImmediatelyAndDeliversTheSameDistanceAtEveryRefreshRate() {
        for (hz in listOf(60, 120, 144, 240)) {
            val motion = WheelScrollMotion(0.6)
            var delivered = motion.add(5.4, 140.0)
            assertEquals(0.6, delivered, 1e-9, "first input should not wait for another frame")
            var frames = 0
            while (motion.isActive && frames < hz) {
                val step = motion.advance(1.0 / hz)
                assertTrue(step >= 0.0)
                delivered += step
                frames++
            }
            assertFalse(motion.isActive)
            assertEquals(5.4, delivered, 1e-9)
            assertTrue(frames.toDouble() / hz <= 0.16 + 1.0 / hz, "long wheel tail at $hz Hz")
        }
    }

    @Test fun unevenFramesAndNewNotchesKeepTheirDistance() {
        val motion = WheelScrollMotion(0.6)
        var delivered = motion.add(5.4, 140.0)
        delivered += motion.advance(0.008)
        delivered += motion.add(6.912, 140.0)
        for (dt in listOf(0.006, 0.025, 0.009, 0.04, 0.08)) delivered += motion.advance(dt)
        assertEquals(12.312, delivered, 1e-9)
        assertFalse(motion.isActive)
    }

    @Test fun reversalCancelsOnlyTheOldUndeliveredMotion() {
        val motion = WheelScrollMotion(0.6)
        motion.add(5.4, 140.0)
        motion.advance(0.016)
        var reverse = motion.add(-5.4, 140.0)
        while (motion.isActive) {
            val step = motion.advance(0.008)
            assertTrue(step <= 0.0, "must not keep drifting in the previous direction")
            reverse += step
        }
        assertEquals(-5.4, reverse, 1e-9)
    }

    @Test fun aStalledFrameDoesNotExtendTheScrollOrProduceExtraMovement() {
        val motion = WheelScrollMotion(0.6)
        val lead = motion.add(5.4, 140.0)
        assertEquals(5.4, lead + motion.advance(0.3), 1e-9)
        assertFalse(motion.isActive)
        assertEquals(0.0, motion.advance(0.016))
    }
    @Test fun anotherNotchDoesNotRestartAtPeakSpeed() {
        val motion = WheelScrollMotion(0.2)
        motion.add(5.4, 140.0)
        motion.advance(0.04)
        val speedBefore = motion.advance(0.001) / 0.001
        motion.add(5.4, 140.0)
        val speedAfter = motion.advance(0.001) / 0.001
        assertTrue(kotlin.math.abs(speedAfter - speedBefore) < 3.0,
            "steady wheel input must not cause a speed spike: $speedBefore -> $speedAfter")
    }

}
