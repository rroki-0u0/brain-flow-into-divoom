package io.rroki.brainflowintodivoom.data.divoom

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class FrameDispatchQueue(
    private val minIntervalMs: Long = 120L,
    private val sender: suspend (ByteArray) -> Unit
) {
    private val queue = Channel<ByteArray>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    private var workerJob: Job? = null

    fun start(scope: CoroutineScope) {
        if (workerJob != null) return

        _running.value = true
        workerJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val frame = queue.receive()
                sender(frame)
                delay(minIntervalMs)
            }
        }
    }

    suspend fun enqueue(frame: ByteArray) {
        queue.send(frame)
    }

    fun stop() {
        workerJob?.cancel()
        workerJob = null
        _running.value = false
    }
}
