package io.rroki.brainflowintodivoom.data.divoom

class DivoomPacketEncoder(
    private val width: Int = 16,
    private val height: Int = 16
) {
    private val imageCommandHeader = byteArrayOf(
        0x44,
        0x00,
        0x0A,
        0x0A,
        0x04
    )

    fun encodeFrame(argbFrame: IntArray): ByteArray {
        require(argbFrame.size == width * height) {
            "Expected ${width * height} pixels, but got ${argbFrame.size}"
        }

        val imageData = buildImageData(argbFrame)
        val payload = ByteArray(imageCommandHeader.size + imageData.size)
        System.arraycopy(imageCommandHeader, 0, payload, 0, imageCommandHeader.size)
        System.arraycopy(imageData, 0, payload, imageCommandHeader.size, imageData.size)

        // Divoom message length is payload bytes + 2-byte CRC.
        val length = payload.size + 2
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

    private fun buildImageData(argbFrame: IntArray): ByteArray {
        val palette = ArrayList<Int>(256)
        val colorToIndex = HashMap<Int, Int>(256)
        val pixelIndexes = IntArray(argbFrame.size)

        for (i in argbFrame.indices) {
            val rgb = argbFrame[i] and 0x00FFFFFF
            val index = colorToIndex[rgb] ?: run {
                require(palette.size < 256) { "Divoom palette supports up to 256 colors" }
                val newIndex = palette.size
                palette.add(rgb)
                colorToIndex[rgb] = newIndex
                newIndex
            }
            pixelIndexes[i] = index
        }

        val colorsCount = palette.size
        val bitsPerPixel = when {
            colorsCount <= 1 -> 1
            else -> 32 - Integer.numberOfLeadingZeros(colorsCount - 1)
        }

        val colorData = ByteArray(colorsCount * 3)
        var colorCursor = 0
        for (rgb in palette) {
            colorData[colorCursor++] = ((rgb shr 16) and 0xFF).toByte()
            colorData[colorCursor++] = ((rgb shr 8) and 0xFF).toByte()
            colorData[colorCursor++] = (rgb and 0xFF).toByte()
        }

        val pixelDataLength = (pixelIndexes.size * bitsPerPixel + 7) / 8
        val pixelData = ByteArray(pixelDataLength)
        var bitCursor = 0
        for (index in pixelIndexes) {
            for (bit in 0 until bitsPerPixel) {
                if (((index shr bit) and 0x01) == 1) {
                    val byteIndex = bitCursor / 8
                    val bitOffset = bitCursor % 8
                    pixelData[byteIndex] = (pixelData[byteIndex].toInt() or (1 shl bitOffset)).toByte()
                }
                bitCursor++
            }
        }

        val bodyLength = 3 + 1 + colorData.size + pixelData.size
        val imageDataLength = 1 + 2 + bodyLength

        val imageData = ByteArray(imageDataLength)
        var cursor = 0
        imageData[cursor++] = 0xAA.toByte()
        imageData[cursor++] = (imageDataLength and 0xFF).toByte()
        imageData[cursor++] = ((imageDataLength shr 8) and 0xFF).toByte()
        imageData[cursor++] = 0x00
        imageData[cursor++] = 0x00
        imageData[cursor++] = 0x00
        imageData[cursor++] = if (colorsCount == 256) 0x00 else colorsCount.toByte()

        System.arraycopy(colorData, 0, imageData, cursor, colorData.size)
        cursor += colorData.size
        System.arraycopy(pixelData, 0, imageData, cursor, pixelData.size)

        return imageData
    }

    private fun checksum(lengthLsb: Byte, lengthMsb: Byte, payload: ByteArray): Int {
        var sum = (lengthLsb.toInt() and 0xFF) + (lengthMsb.toInt() and 0xFF)
        payload.forEach { sum += it.toInt() and 0xFF }
        return sum and 0xFFFF
    }
}
