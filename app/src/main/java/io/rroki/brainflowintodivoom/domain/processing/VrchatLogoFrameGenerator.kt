package io.rroki.brainflowintodivoom.domain.processing

import io.rroki.brainflowintodivoom.domain.model.BrainBand

class VrchatLogoFrameGenerator {
    private val mask = listOf(
        "0000000000000000",
        "0000011111110000",
        "0000111111111000",
        "0001110000011100",
        "0011100000001110",
        "0011001111000110",
        "0110011111100011",
        "0110011111100011",
        "0110011111100011",
        "0110001111000011",
        "0011000000000110",
        "0011100000001110",
        "0001110000011100",
        "0000111111111000",
        "0000011111110000",
        "0000000000000000"
    )

    fun buildFrame(band: BrainBand): IntArray {
        val color = colorForBand(band)
        val background = 0xFF05080F.toInt()
        val frame = IntArray(16 * 16) { background }

        for (y in 0 until 16) {
            for (x in 0 until 16) {
                if (mask[y][x] == '1') {
                    frame[(y * 16) + x] = color
                }
            }
        }
        return frame
    }

    private fun colorForBand(band: BrainBand): Int {
        return when (band) {
            BrainBand.DELTA -> 0xFF38BDF8.toInt()
            BrainBand.THETA -> 0xFF60A5FA.toInt()
            BrainBand.ALPHA -> 0xFF10B981.toInt()
            BrainBand.BETA -> 0xFFEF4444.toInt()
            BrainBand.GAMMA -> 0xFFF59E0B.toInt()
        }
    }
}
