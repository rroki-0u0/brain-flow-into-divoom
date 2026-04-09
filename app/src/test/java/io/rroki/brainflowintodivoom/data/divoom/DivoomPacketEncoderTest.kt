package io.rroki.brainflowintodivoom.data.divoom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DivoomPacketEncoderTest {
    private val encoder = DivoomPacketEncoder()

    @Test
    fun `encode frame wraps payload with protocol envelope`() {
        val frame = IntArray(256) { 0xFF112233.toInt() }

        val packet = encoder.encodeFrame(frame)

        assertEquals(0x01, packet.first().toInt() and 0xFF)
        assertEquals(0x02, packet.last().toInt() and 0xFF)
        assertTrue(packet.size > frame.size)
    }

    @Test
    fun `length field matches payload size`() {
        val frame = IntArray(256) { 0xFF000000.toInt() }
        val packet = encoder.encodeFrame(frame)

        val length = (packet[1].toInt() and 0xFF) or ((packet[2].toInt() and 0xFF) shl 8)
        assertEquals(1 + 256 * 3, length)
    }

    @Test
    fun `crc field matches sum of length and payload`() {
        val frame = IntArray(256) { 0xFF102030.toInt() }
        val packet = encoder.encodeFrame(frame)

        val length = (packet[1].toInt() and 0xFF) or ((packet[2].toInt() and 0xFF) shl 8)
        var expected = (packet[1].toInt() and 0xFF) + (packet[2].toInt() and 0xFF)

        val payloadStart = 3
        val payloadEndExclusive = payloadStart + length
        for (i in payloadStart until payloadEndExclusive) {
            expected += packet[i].toInt() and 0xFF
        }
        expected = expected and 0xFFFF

        val crcIndex = payloadEndExclusive
        val actual = (packet[crcIndex].toInt() and 0xFF) or
            ((packet[crcIndex + 1].toInt() and 0xFF) shl 8)

        assertEquals(expected, actual)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid frame size throws`() {
        encoder.encodeFrame(IntArray(10) { 0 })
    }
}
