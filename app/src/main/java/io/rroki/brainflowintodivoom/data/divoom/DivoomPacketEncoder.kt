package io.rroki.brainflowintodivoom.data.divoom

class DivoomPacketEncoder(
    private val command: Byte = 0x44,
    private val width: Int = 16,
    private val height: Int = 16
) {
    fun encodeFrame(argbFrame: IntArray): ByteArray {
        require(argbFrame.size == width * height) {
            "Expected ${width * height} pixels, but got ${argbFrame.size}"
        }

        val payload = ByteArray(1 + (argbFrame.size * 3))
        payload[0] = command

        var cursor = 1
        for (pixel in argbFrame) {
            payload[cursor++] = ((pixel shr 16) and 0xFF).toByte()
            payload[cursor++] = ((pixel shr 8) and 0xFF).toByte()
            payload[cursor++] = (pixel and 0xFF).toByte()
        }

        val length = payload.size
        val lengthLsb = (length and 0xFF).toByte()
        val lengthMsb = ((length shr 8) and 0xFF).toByte()

        val crc = checksum(lengthLsb, lengthMsb, payload)
        val crcLsb = (crc and 0xFF).toByte()
        val crcMsb = ((crc shr 8) and 0xFF).toByte()

        val packet = ByteArray(1 + 2 + payload.size + 2 + 1)
        var i = 0
        packet[i++] = 0x01
        packet[i++] = lengthLsb
        packet[i++] = lengthMsb
        payload.forEach { packet[i++] = it }
        packet[i++] = crcLsb
        packet[i++] = crcMsb
        packet[i] = 0x02

        return packet
    }

    private fun checksum(lengthLsb: Byte, lengthMsb: Byte, payload: ByteArray): Int {
        var sum = (lengthLsb.toInt() and 0xFF) + (lengthMsb.toInt() and 0xFF)
        payload.forEach { sum += it.toInt() and 0xFF }
        return sum and 0xFFFF
    }
}
