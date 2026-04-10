package io.rroki.brainflowintodivoom.domain.processing

import kotlin.math.roundToInt

class VrchatLogoFrameGenerator {
    private val waveHistory = ArrayDeque<Int>(WAVE_WIDTH)

    init {
        repeat(WAVE_WIDTH) { waveHistory.addLast((WAVE_TOP + WAVE_BOTTOM) / 2) }
    }

    fun buildFrame(normalizedValue: Double, waveColor: Int): IntArray {
        pushWave(normalizedValue)

        val frame = IntArray(PANEL_SIZE * PANEL_SIZE) { BACKGROUND_COLOR }
        val shape = BUBBLE_SHAPE

        for (y in 0 until PANEL_SIZE) {
            for (x in 0 until PANEL_SIZE) {
                if (shape[y][x] != '1') {
                    continue
                }
                val border = isBorderPixel(shape, x, y)
                frame[(y * PANEL_SIZE) + x] = if (border) BORDER_COLOR else BUBBLE_COLOR
            }
        }

        drawWave(frame, waveColor)
        return frame
    }

    private fun pushWave(normalizedValue: Double) {
        val clamped = normalizedValue.coerceIn(0.0, 1.0)
        val y = (WAVE_BOTTOM - ((WAVE_BOTTOM - WAVE_TOP) * clamped)).roundToInt()
            .coerceIn(WAVE_TOP, WAVE_BOTTOM)
        if (waveHistory.size == WAVE_WIDTH) {
            waveHistory.removeFirst()
        }
        waveHistory.addLast(y)
    }

    private fun drawWave(frame: IntArray, waveColor: Int) {
        val points = waveHistory.toList()
        for (index in 1 until points.size) {
            val x0 = WAVE_LEFT + index - 1
            val y0 = points[index - 1]
            val x1 = WAVE_LEFT + index
            val y1 = points[index]
            drawLine(frame, x0, y0, x1, y1, waveColor)
        }
    }

    private fun drawLine(frame: IntArray, x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        var startX = x0
        var startY = y0
        val endX = x1
        val endY = y1

        val dx = kotlin.math.abs(endX - startX)
        val sx = if (startX < endX) 1 else -1
        val dy = -kotlin.math.abs(endY - startY)
        val sy = if (startY < endY) 1 else -1
        var error = dx + dy

        while (true) {
            paintIfInsideBubble(frame, startX, startY, color)
            if (startX == endX && startY == endY) {
                break
            }
            val error2 = 2 * error
            if (error2 >= dy) {
                error += dy
                startX += sx
            }
            if (error2 <= dx) {
                error += dx
                startY += sy
            }
        }
    }

    private fun paintIfInsideBubble(frame: IntArray, x: Int, y: Int, color: Int) {
        if (x !in 0 until PANEL_SIZE || y !in 0 until PANEL_SIZE) {
            return
        }
        val shape = BUBBLE_SHAPE
        if (shape[y][x] != '1' || isBorderPixel(shape, x, y)) {
            return
        }
        frame[(y * PANEL_SIZE) + x] = color
    }

    private fun isBorderPixel(shape: List<String>, x: Int, y: Int): Boolean {
        val neighbors = listOf(
            x - 1 to y,
            x + 1 to y,
            x to y - 1,
            x to y + 1
        )

        return neighbors.any { (nx, ny) ->
            nx !in 0 until PANEL_SIZE || ny !in 0 until PANEL_SIZE || shape[ny][nx] != '1'
        }
    }

    private companion object {
        private const val PANEL_SIZE = 16
        private const val WAVE_LEFT = 3
        private const val WAVE_WIDTH = 10
        private const val WAVE_TOP = 3
        private const val WAVE_BOTTOM = 8

        private val BACKGROUND_COLOR = 0xFF0089CF.toInt()
        private val BUBBLE_COLOR = 0xFFFFFFFF.toInt()
        private val BORDER_COLOR = 0xFF000000.toInt()

        private val BUBBLE_SHAPE = listOf(
            "0000000000000000",
            "0011111111111100",
            "0111111111111110",
            "0111111111111110",
            "0111111111111110",
            "0111111111111110",
            "0111111111111110",
            "0111111111111110",
            "0111111111111110",
            "0111111111111110",
            "0011111111111100",
            "0000000001111000",
            "0000000000111000",
            "0000000000011000",
            "0000000000001000",
            "0000000000000000"
        )
    }
}
