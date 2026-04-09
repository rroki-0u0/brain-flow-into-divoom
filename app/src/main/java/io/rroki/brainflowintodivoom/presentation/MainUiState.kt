package io.rroki.brainflowintodivoom.presentation

import io.rroki.brainflowintodivoom.domain.model.BrainBand
import io.rroki.brainflowintodivoom.domain.model.DisplayMode

data class MainUiState(
    val mode: DisplayMode = DisplayMode.OSCILLOSCOPE,
    val dominantBand: BrainBand = BrainBand.ALPHA,
    val normalizedValue: Double = 0.5,
    val frame: IntArray = IntArray(16 * 16) { 0xFF04070D.toInt() },
    val packetSizeBytes: Int = 0,
    val isDivoomConnected: Boolean = false,
    val hasBluetoothPermissions: Boolean = false,
    val autoSendEnabled: Boolean = false,
    val divoomDeviceName: String = "Pixoo Backpack",
    val connectionStateText: String = "disconnected",
    val lastError: String? = null
)
