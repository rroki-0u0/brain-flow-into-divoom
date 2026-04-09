package io.rroki.brainflowintodivoom.data.divoom

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameDispatchQueueTest {
    @Test
    fun `start and stop updates running state`() = runBlocking {
        val queue = FrameDispatchQueue(minIntervalMs = 1L) { }
        val scope = CoroutineScope(Dispatchers.Default)

        queue.start(scope)
        assertTrue(queue.running.value)

        queue.stop()
        delay(10)
        assertFalse(queue.running.value)

        scope.cancel()
    }

    @Test
    fun `enqueue sends latest frame`() = runBlocking {
        var lastSent: ByteArray? = null
        val queue = FrameDispatchQueue(minIntervalMs = 0L) { payload ->
            lastSent = payload
        }
        val scope = CoroutineScope(Dispatchers.Default)

        queue.start(scope)
        val producer = launch {
            queue.enqueue(byteArrayOf(0x01))
            queue.enqueue(byteArrayOf(0x02))
            queue.enqueue(byteArrayOf(0x03))
        }
        producer.join()
        delay(20)

        assertTrue(lastSent != null)
        assertTrue((lastSent?.first()?.toInt() ?: 0) in listOf(0x02, 0x03))

        queue.stop()
        scope.cancel()
    }
}
