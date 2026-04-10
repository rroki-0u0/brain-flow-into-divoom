package io.rroki.brainflowintodivoom.presentation

import io.rroki.brainflowintodivoom.domain.model.BrainBand
import io.rroki.brainflowintodivoom.domain.model.DisplayMode

data class DivoomDeviceOption(
    val name: String,
    val address: String,
    val isBonded: Boolean
)

data class MainUiState(
    val mode: DisplayMode = DisplayMode.OSCILLOSCOPE,
    val dominantBand: BrainBand = BrainBand.ALPHA,
    val normalizedValue: Double = 0.5,
    val frame: IntArray = IntArray(16 * 16) { 0xFF04070D.toInt() },
    val packetSizeBytes: Int = 0,
    val isDivoomConnected: Boolean = false,
    val hasBluetoothPermissions: Boolean = false,
    val autoSendEnabled: Boolean = false,
    val isUsingMuseStream: Boolean = false,
    val isMuseConnected: Boolean = false,
    val museConnectionStateText: String = "disconnected",
    val museDeviceAddress: String = "",
    val museEegSampleCount: Int = 0,
    val museNotificationCount: Long = 0L,
    val museEegNotificationCount: Long = 0L,
    val museOtherNotificationCount: Long = 0L,
    val museLastPacketBytes: Int = 0,
    val museAlphaRatio: Double = 0.0,
    val museDominantRatio: Double = 0.0,
    val museTotalPower: Double = 0.0,
    val museActivity: Double = 0.0,
    val musePacketPreviewHex: String = "-",
    val brainFlowRuntimeAvailable: Boolean = false,
    val sendIntervalMs: Long = 180L,
    val reconnectAttempt: Int = 0,
    val divoomDeviceName: String = "Pixoo-SlingBag",
    val selectedDivoomDeviceAddress: String? = null,
    val availableDivoomDevices: List<DivoomDeviceOption> = emptyList(),
    val isScanningDivoomDevices: Boolean = false,
    val connectionStateText: String = "disconnected",
    val lastError: String? = null
)
