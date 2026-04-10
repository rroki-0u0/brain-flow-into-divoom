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
        assertEquals(0x44, packet[3].toInt() and 0xFF)
        assertEquals(0x00, packet[4].toInt() and 0xFF)
        assertEquals(0x0A, packet[5].toInt() and 0xFF)
        assertEquals(0x0A, packet[6].toInt() and 0xFF)
        assertEquals(0x04, packet[7].toInt() and 0xFF)
        assertTrue(packet.size > 16)
    }

    @Test
    fun `length field includes payload and crc`() {
        val frame = IntArray(256) { 0xFF000000.toInt() }
        val packet = encoder.encodeFrame(frame)

        val length = (packet[1].toInt() and 0xFF) or ((packet[2].toInt() and 0xFF) shl 8)
        assertEquals(packet.size - 4, length)
    }

    @Test
    fun `crc field matches sum of length and payload`() {
        val frame = IntArray(256) { 0xFF102030.toInt() }
        val packet = encoder.encodeFrame(frame)

        val length = (packet[1].toInt() and 0xFF) or ((packet[2].toInt() and 0xFF) shl 8)
        var expected = (packet[1].toInt() and 0xFF) + (packet[2].toInt() and 0xFF)
        val payloadSize = length - 2

        val payloadStart = 3
        val payloadEndExclusive = payloadStart + payloadSize
        for (i in payloadStart until payloadEndExclusive) {
            expected += packet[i].toInt() and 0xFF
        }
        expected = expected and 0xFFFF

        val crcIndex = payloadEndExclusive
        val actual = (packet[crcIndex].toInt() and 0xFF) or
            ((packet[crcIndex + 1].toInt() and 0xFF) shl 8)

        assertEquals(expected, actual)
    }

    @Test
    fun `image data encodes palette and pixel section`() {
        val frame = IntArray(256) { index ->
            if (index % 2 == 0) 0xFFFF0000.toInt() else 0xFF00FF00.toInt()
        }
        val packet = encoder.encodeFrame(frame)

        val imageDataStart = 8
        assertEquals(0xAA, packet[imageDataStart].toInt() and 0xFF)

        val imageDataLength = (packet[imageDataStart + 1].toInt() and 0xFF) or
            ((packet[imageDataStart + 2].toInt() and 0xFF) shl 8)
        assertTrue(imageDataLength > 10)

        val paletteCount = packet[imageDataStart + 6].toInt() and 0xFF
        assertEquals(2, paletteCount)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid frame size throws`() {
        encoder.encodeFrame(IntArray(10) { 0 })
    }
}
