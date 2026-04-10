package io.rroki.brainflowintodivoom.data.muse

import io.rroki.brainflowintodivoom.domain.model.BrainBand
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

data class MuseReading(
    val normalizedAlpha: Double,
    val dominantBand: BrainBand,
    val eegSampleCount: Int = 0,
    val notificationCount: Long = 0L,
    val eegNotificationCount: Long = 0L,
    val otherNotificationCount: Long = 0L,
    val latestPacketBytes: Int = 0,
    val alphaRatio: Double = 0.0,
    val dominantRatio: Double = 0.0,
    val totalPower: Double = 0.0,
    val activity: Double = 0.0,
    val packetPreviewHex: String = "-"
)

interface MuseStreamGateway {
    fun isRuntimeAvailable(): Boolean
    fun isConnected(): Boolean
    suspend fun connect(deviceAddress: String? = null): Result<Unit>
    suspend fun disconnect()
    fun streamReadings(pollIntervalMs: Long = 120L): Flow<MuseReading>
}

class PlaceholderMuseStreamGateway : MuseStreamGateway {
    override fun isRuntimeAvailable(): Boolean = false

    override fun isConnected(): Boolean = false

    override suspend fun connect(deviceAddress: String?): Result<Unit> {
        return Result.failure(IllegalStateException("BrainFlow runtime is unavailable"))
    }

    override suspend fun disconnect() {
        // no-op
    }

    override fun streamReadings(pollIntervalMs: Long): Flow<MuseReading> = emptyFlow()
}
