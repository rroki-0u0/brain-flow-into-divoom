package io.rroki.brainflowintodivoom.domain.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OscilloscopeFrameGeneratorTest {
    @Test
    fun `normalize clamps into y range`() {
        val generator = OscilloscopeFrameGenerator()

        assertEquals(15, generator.normalizeToY(0.0))
        assertEquals(0, generator.normalizeToY(1.0))
        assertEquals(0, generator.normalizeToY(2.0))
        assertEquals(15, generator.normalizeToY(-1.0))
    }

    @Test
    fun `history remains fixed length 16`() {
        val generator = OscilloscopeFrameGenerator()
        repeat(64) { index ->
            generator.pushNormalized((index % 16) / 15.0)
        }

        assertEquals(16, generator.snapshotHistory().size)
    }

    @Test
    fun `frame always has 256 pixels`() {
        val generator = OscilloscopeFrameGenerator()
        val frame = generator.pushNormalized(0.5)

        assertEquals(256, frame.size)
        assertTrue(frame.isNotEmpty())
    }
}
