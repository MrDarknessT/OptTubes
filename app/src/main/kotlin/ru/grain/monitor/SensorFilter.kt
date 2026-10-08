package ru.grain.monitor

import java.util.ArrayDeque

/** Independent histories for 96 tubes. Call from one sequential thread, using monotonic time. */
class SensorFilter(
    val windowMs: Long,
    val thresholdPercent: Int,
    val mode: Mode,
) {
    enum class Mode { RESET_ON_GREEN, ROLLING_PERCENT }

    data class Diagnosis(
        val raw: Int,
        val status: Int,
        val samples: Int,
        val badFraction: Double,
        val failedForMs: Long,
        val windowReady: Boolean,
        val lastGreenMs: Long,
    )

    private data class Sample(val timeMs: Long, val state: Int)
    private class Tube {
        val history = ArrayDeque<Sample>()
        val counts = IntArray(4)
        var startMs = -1L
        var failureStartMs = -1L
        var lastGreenMs = -1L
        var status = UNKNOWN
        var raw = UNKNOWN
        fun clearHistory() {
            history.clear()
            counts.fill(0)
            startMs = -1L
            failureStartMs = -1L
        }
    }

    private val tubes = Array(COUNT) { Tube() }
    private var lastFrameMs = -1L
    private val staleMs = maxOf(300L, windowMs)

    init {
        require(windowMs >= SAMPLE_MS && thresholdPercent in 1..100) { "Invalid filter settings" }
    }

    fun reset() {
        lastFrameMs = -1L
        tubes.forEach {
            it.clearHistory()
            it.raw = UNKNOWN
            it.status = UNKNOWN
            it.lastGreenMs = -1L
        }
    }

    fun accept(frame: IntArray, nowMs: Long) {
        // Validate the whole frame first: a malformed final element must not update earlier tubes.
        require(frame.size == COUNT) { "Expected 96 values" }
        require(frame.all { it in 0..3 }) { "Expected states 0..3" }
        require(nowMs >= 0 && (lastFrameMs < 0 || nowMs > lastFrameMs)) { "Timestamp must increase" }
        if (lastFrameMs >= 0 && nowMs - lastFrameMs > staleMs) reset()
        lastFrameMs = nowMs

        frame.forEachIndexed { i, code ->
            val tube = tubes[i]
            tube.raw = code
            if (code == 1) {
                tube.status = 1
                tube.lastGreenMs = nowMs
                if (mode == Mode.RESET_ON_GREEN) {
                    tube.clearHistory()
                    return@forEachIndexed
                }
                tube.failureStartMs = -1L
            } else if (tube.failureStartMs < 0) {
                tube.failureStartMs = nowMs
            }

            if (tube.startMs < 0) tube.startMs = nowMs
            while (tube.history.isNotEmpty() && tube.history.first.timeMs <= nowMs - windowMs) {
                tube.counts[tube.history.removeFirst().state]--
            }
            tube.history.addLast(Sample(nowMs, code))
            tube.counts[code]++

            val mature = nowMs - tube.startMs >= windowMs
            val bad = tube.history.size - tube.counts[1]
            val continuous = tube.failureStartMs >= 0 && nowMs - tube.failureStartMs >= windowMs
            val percent = mode == Mode.ROLLING_PERCENT && mature &&
                bad * 100L >= thresholdPercent * tube.history.size.toLong()
            // A working frame always wins right now, even when the previous window was bad.
            if (code != 1 && (continuous || percent)) {
                var dominant = code
                for (candidate in BAD_CODES) {
                    if (tube.counts[candidate] > tube.counts[dominant]) dominant = candidate
                }
                tube.status = dominant
            }
        }
    }

    fun checkStale(nowMs: Long) {
        if (lastFrameMs >= 0 && nowMs - lastFrameMs > staleMs) {
            tubes.forEach {
                it.status = STALE
                it.lastGreenMs = -1L
                it.clearHistory()
            }
        }
    }

    fun statuses(): IntArray = IntArray(COUNT) { tubes[it].status }

    fun diagnosis(i: Int, nowMs: Long): Diagnosis {
        val t = tubes[i]
        return Diagnosis(
            t.raw, t.status, t.history.size,
            if (t.history.isEmpty()) 0.0 else 1.0 - t.counts[1].toDouble() / t.history.size,
            if (t.failureStartMs < 0) 0L else maxOf(0L, nowMs - t.failureStartMs),
            t.startMs >= 0 && nowMs - t.startMs >= windowMs,
            t.lastGreenMs,
        )
    }

    companion object {
        const val COUNT = 96
        const val SAMPLE_MS = 30L
        const val UNKNOWN = -1
        const val STALE = -2
        private val BAD_CODES = intArrayOf(0, 2, 3)
    }
}
