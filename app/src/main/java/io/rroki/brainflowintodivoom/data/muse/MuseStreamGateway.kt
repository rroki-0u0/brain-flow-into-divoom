package io.rroki.brainflowintodivoom.data.muse

import io.rroki.brainflowintodivoom.domain.model.BrainBand
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

data class MuseReading(
    val normalizedAlpha: Double,
    val dominantBand: BrainBand,
    val deltaPower: Double = 0.0,
    val thetaPower: Double = 0.0,
    val alphaPower: Double = 0.0,
    val betaPower: Double = 0.0,
    val gammaPower: Double = 0.0,
    val focusScorePos: Double = 0.5,
    val relaxScorePos: Double = 0.5,
    val heartBpm: Double = 0.0,
    val oxygenPercent: Double = 0.0,
    val nirsIndex: Double = 0.0,
    val ppgSampleCount: Int = 0,
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

data class MuseStreamProfile(
    val key: String,
    val label: String,
    val presetToken: String,
    val enableOtherNotifications: Boolean,
    val enableLowLatency: Boolean
) {
    companion object {
        val EEG_ONLY = MuseStreamProfile(
            key = "eeg_only",
            label = "EEG Only",
            presetToken = "p50",
            enableOtherNotifications = false,
            enableLowLatency = true
        )

        val FULL_BIOMETRICS = MuseStreamProfile(
            key = "full_biometrics",
            label = "Full Biometrics",
            presetToken = "p1041",
            enableOtherNotifications = true,
            enableLowLatency = true
        )
    }
}

interface MuseStreamGateway {
    fun isRuntimeAvailable(): Boolean
    fun isConnected(): Boolean
    fun configureStreamProfile(profile: MuseStreamProfile) {}
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
