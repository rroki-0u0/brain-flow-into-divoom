package io.rroki.brainflowintodivoom.domain.processing

import kotlin.math.roundToInt

class OscilloscopeFrameGenerator(
    private val width: Int = 16,
    private val height: Int = 16,
    private val lineColor: Int = 0xFF00E5FF.toInt(),
    private val backgroundColor: Int = 0xFF04070D.toInt()
) {
    private val history = ArrayDeque<Int>(width)

    init {
        repeat(width) { history.addLast(height / 2) }
    }

    fun pushNormalized(value: Double): IntArray {
        val y = normalizeToY(value)
        if (history.size == width) {
            history.removeFirst()
        }
        history.addLast(y)
        return buildFrame()
    }

    fun normalizeToY(value: Double): Int {
        val clamped = value.coerceIn(0.0, 1.0)
        val raw = ((height - 1) * (1.0 - clamped)).roundToInt()
        return raw.coerceIn(0, height - 1)
    }

    fun snapshotHistory(): List<Int> = history.toList()

    private fun buildFrame(): IntArray {
        val frame = IntArray(width * height) { backgroundColor }
        history.forEachIndexed { x, y ->
            val index = (y * width) + x
            if (index in frame.indices) {
                frame[index] = lineColor
            }
        }
        return frame
    }
}
