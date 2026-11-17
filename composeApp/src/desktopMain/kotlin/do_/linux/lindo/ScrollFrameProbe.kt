package do_.linux.lindo

import kotlinx.coroutines.*
import java.awt.event.MouseWheelEvent
import java.io.File

/** Opt-in local input/frame-clock trace. No URLs, content or account data. */
internal object ScrollFrameProbe {
    val enabled = System.getProperty("lindo.scroll.trace") == "true"
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastInput = 0L
    private var previousFrame = 0L
    private val intervals = mutableListOf<Double>()
    private val inputs = linkedMapOf<String, Int>()
    private var worstQueueDelay = 0L

    fun input(event: MouseWheelEvent, smoothed: Boolean) {
        if (!enabled) return
        lastInput = System.nanoTime()
        val key = "int=${event.wheelRotation} precise=${event.preciseWheelRotation} smooth=$smoothed"
        if (inputs.size < 64 || key in inputs) inputs[key] = (inputs[key] ?: 0) + 1
        worstQueueDelay = maxOf(worstQueueDelay, (System.currentTimeMillis() - event.`when`).coerceAtLeast(0))
    }

    fun frame(time: Long): Boolean {
        if (!enabled || lastInput == 0L) return false
        if (previousFrame != 0L && intervals.size < 2000) intervals += (time - previousFrame) / 1_000_000.0
        previousFrame = time
        if (System.nanoTime() - lastInput < 250_000_000L) return true
        val values = intervals.sorted()
        fun percentile(p: Double) = values.getOrNull(((values.size - 1) * p).toInt()) ?: 0.0
        val report = "${System.currentTimeMillis()} frames=${values.size} median=${percentile(.5)} p95=${percentile(.95)} max=${values.lastOrNull()} over25ms=${values.count { it > 25 }} queueMaxMs=$worstQueueDelay inputs=$inputs\n"
        writer.launch { File(System.getProperty("java.io.tmpdir"), "lindo-scroll-trace.txt").appendText(report) }
        intervals.clear(); inputs.clear(); previousFrame = 0; lastInput = 0; worstQueueDelay = 0
        return false
    }
}
