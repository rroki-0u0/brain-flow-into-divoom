package io.rroki.brainflowintodivoom.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.rroki.brainflowintodivoom.data.divoom.DivoomBluetoothClient
import io.rroki.brainflowintodivoom.data.divoom.DivoomConnectionState
import io.rroki.brainflowintodivoom.data.divoom.DivoomDiscoveredDevice
import io.rroki.brainflowintodivoom.data.divoom.DivoomPacketEncoder
import io.rroki.brainflowintodivoom.data.divoom.FrameDispatchQueue
import io.rroki.brainflowintodivoom.data.muse.MuseStreamGateway
import io.rroki.brainflowintodivoom.data.muse.MuseStreamProfile
import io.rroki.brainflowintodivoom.data.muse.OpenMuseAthenaGateway
import io.rroki.brainflowintodivoom.domain.model.BrainBand
import io.rroki.brainflowintodivoom.domain.model.BfiWaveformParameter
import io.rroki.brainflowintodivoom.domain.model.DisplayMode
import io.rroki.brainflowintodivoom.domain.model.MusePowerMode
import io.rroki.brainflowintodivoom.domain.processing.OscilloscopeFrameGenerator
import io.rroki.brainflowintodivoom.domain.processing.VrchatLogoFrameGenerator
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private data class ReconnectPolicy(
    val maxAttempts: Int,
    val initialDelayMs: Long,
    val maxDelayMs: Long,
    val multiplier: Double
) {
    fun delayForAttempt(attempt: Int): Long {
        val exponent = (attempt - 1).coerceAtLeast(0)
        val scaled = initialDelayMs * Math.pow(multiplier, exponent.toDouble())
        return scaled.toLong().coerceAtMost(maxDelayMs)
    }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val sendIntervalPresets = listOf(100L, 150L, 200L)
    private val reconnectPolicy = ReconnectPolicy(
        maxAttempts = 3,
        initialDelayMs = 250L,
        maxDelayMs = 2_000L,
        multiplier = 2.0
    )

    private val oscilloscopeGenerator = OscilloscopeFrameGenerator()
    private val logoGenerator = VrchatLogoFrameGenerator()
    private val encoder = DivoomPacketEncoder()
    private val divoomClient = DivoomBluetoothClient(application.applicationContext)
    private val museGateway: MuseStreamGateway = OpenMuseAthenaGateway(application.applicationContext)
    private var activeMuseProfile: MuseStreamProfile = MuseStreamProfile.EEG_ONLY
    private var connectionState = DivoomConnectionState.DISCONNECTED
    private var museAdaptiveMin = 0.35
    private var museAdaptiveMax = 0.65
    private var museRenderedValue = 0.5
    private var fakeStreamJob: Job? = null
    private var museStreamJob: Job? = null
    private val frameQueue = FrameDispatchQueue(minIntervalMs = 180L) { packet ->
        sendPacketWithRecovery(packet)
    }

    private val _uiState = MutableStateFlow(
        MainUiState(brainFlowRuntimeAvailable = museGateway.isRuntimeAvailable())
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        frameQueue.start(viewModelScope)
        val profile = resolveEffectiveProfile(_uiState.value)
        activeMuseProfile = profile
        museGateway.configureStreamProfile(profile)
        _uiState.value = _uiState.value.copy(effectiveMuseProfileLabel = profile.label)
        startFakeStream()
    }

    fun connectToMuse() {
        val current = _uiState.value
        if (!current.brainFlowRuntimeAvailable) {
            _uiState.value = current.copy(lastError = "Muse runtime is unavailable on this device")
            return
        }
        if (current.isMuseConnected || current.museConnectionStateText == "connecting") {
            return
        }

        _uiState.value = current.copy(
            museConnectionStateText = "connecting",
            lastError = null
        )

        val targetProfile = resolveEffectiveProfile(current)
        activeMuseProfile = targetProfile
        museGateway.configureStreamProfile(targetProfile)
        _uiState.value = _uiState.value.copy(
            effectiveMuseProfileLabel = targetProfile.label,
            selectedParameterOscPath = _uiState.value.selectedWaveformParameter.oscPath,
            selectedParameterUnit = _uiState.value.selectedWaveformParameter.unit
        )

        viewModelScope.launch {
            museGateway.connect(current.museDeviceAddress.takeIf { it.isNotBlank() })
                .onSuccess {
                    resetMuseWaveNormalizer()
                    _uiState.value = _uiState.value.copy(
                        isMuseConnected = true,
                        isUsingMuseStream = true,
                        museConnectionStateText = "connected",
                        autoSendEnabled = true,
                        lastError = null
                    )
                    stopFakeStream()
                    startMuseStreamCollection()
                }
                .onFailure { throwable ->
                    _uiState.value = _uiState.value.copy(
                        isMuseConnected = false,
                        isUsingMuseStream = false,
                        museConnectionStateText = "disconnected",
                        museEegSampleCount = 0,
                        museNotificationCount = 0L,
                        museEegNotificationCount = 0L,
                        museOtherNotificationCount = 0L,
                        museLastPacketBytes = 0,
                        museAlphaRatio = 0.0,
                        museDominantRatio = 0.0,
                        museTotalPower = 0.0,
                        museActivity = 0.0,
                        musePpgSampleCount = 0,
                        museHeartBpm = 0.0,
                        museOxygenPercent = 0.0,
                        musePacketPreviewHex = "-",
                        lastError = throwable.message ?: "Muse connection failed"
                    )
                    startFakeStream()
                }
        }
    }

    fun disconnectFromMuse() {
        museStreamJob?.cancel()
        museStreamJob = null
        resetMuseWaveNormalizer()

        viewModelScope.launch {
            museGateway.disconnect()
            _uiState.value = _uiState.value.copy(
                isMuseConnected = false,
                isUsingMuseStream = false,
                museConnectionStateText = "disconnected",
                museEegSampleCount = 0,
                museNotificationCount = 0L,
                museEegNotificationCount = 0L,
                museOtherNotificationCount = 0L,
                museLastPacketBytes = 0,
                museAlphaRatio = 0.0,
                museDominantRatio = 0.0,
                museTotalPower = 0.0,
                museActivity = 0.0,
                musePpgSampleCount = 0,
                museHeartBpm = 0.0,
                museOxygenPercent = 0.0,
                musePacketPreviewHex = "-"
            )
            startFakeStream()
        }
    }

    fun setMuseDeviceAddress(address: String) {
        _uiState.value = _uiState.value.copy(
            museDeviceAddress = address,
            lastError = null
        )
    }

    fun onBluetoothPermissionChanged(granted: Boolean) {
        _uiState.value = _uiState.value.copy(
            hasBluetoothPermissions = granted,
            lastError = if (granted) null else "Bluetooth permissions are required"
        )

        if (granted && _uiState.value.availableDivoomDevices.isEmpty()) {
            scanDivoomDevices()
        }
    }

    fun scanDivoomDevices() {
        val currentState = _uiState.value
        if (!currentState.hasBluetoothPermissions) {
            _uiState.value = currentState.copy(lastError = "Grant Bluetooth permissions first")
            return
        }
        if (currentState.isScanningDivoomDevices) {
            return
        }

        _uiState.value = currentState.copy(
            isScanningDivoomDevices = true,
            lastError = null
        )

        viewModelScope.launch {
            divoomClient.discoverDivoomDevices()
                .onSuccess { devices ->
                    updateDiscoveredDevices(devices)
                }
                .onFailure { throwable ->
                    _uiState.value = _uiState.value.copy(
                        isScanningDivoomDevices = false,
                        lastError = throwable.message ?: "Device scan failed"
                    )
                }
        }
    }

    fun selectDivoomDevice(address: String) {
        val selected = _uiState.value.availableDivoomDevices.firstOrNull { it.address == address } ?: return
        _uiState.value = _uiState.value.copy(
            divoomDeviceName = selected.name,
            selectedDivoomDeviceAddress = selected.address,
            lastError = null
        )
    }

    fun connectToDivoom() {
        if (!_uiState.value.hasBluetoothPermissions) {
            _uiState.value = _uiState.value.copy(lastError = "Grant Bluetooth permissions first")
            return
        }

        if (connectionState == DivoomConnectionState.CONNECTED || connectionState == DivoomConnectionState.CONNECTING) {
            return
        }

        updateConnectionState(DivoomConnectionState.CONNECTING)

        viewModelScope.launch {
            val currentState = _uiState.value
            divoomClient.connectBySelection(
                deviceName = currentState.divoomDeviceName,
                deviceAddress = currentState.selectedDivoomDeviceAddress
            )
                .onSuccess {
                    updateConnectionState(DivoomConnectionState.CONNECTED)
                }
                .onFailure { throwable ->
                    updateConnectionState(
                        state = DivoomConnectionState.DISCONNECTED,
                        error = throwable.message ?: "Connection failed"
                    )
                }
        }
    }

    fun disconnectFromDivoom() {
        viewModelScope.launch {
            divoomClient.disconnect()
            updateConnectionState(DivoomConnectionState.DISCONNECTED)
        }
    }

    fun toggleAutoSend() {
        _uiState.value = _uiState.value.copy(autoSendEnabled = !_uiState.value.autoSendEnabled)
    }

    fun setSendIntervalMs(intervalMs: Long) {
        val selected = sendIntervalPresets.minByOrNull { candidate ->
            kotlin.math.abs(candidate - intervalMs)
        } ?: 150L

        frameQueue.setIntervalMs(selected)
        _uiState.value = _uiState.value.copy(
            sendIntervalMs = selected,
            lastError = null
        )
    }

    fun sendCurrentFrame() {
        val currentState = _uiState.value
        if (!currentState.isDivoomConnected) {
            _uiState.value = currentState.copy(lastError = "Divoom is not connected")
            return
        }
        enqueueEncodedFrame(currentState.frame)
    }

    fun setMode(mode: DisplayMode) {
        updateState(
            mode = mode,
            value = _uiState.value.normalizedValue,
            band = _uiState.value.dominantBand
        )
    }

    fun setWaveformParameter(parameter: BfiWaveformParameter) {
        val updated = _uiState.value.copy(
            selectedWaveformParameter = parameter,
            selectedParameterOscPath = parameter.oscPath,
            selectedParameterUnit = parameter.unit,
            lastError = null
        )
        val effectiveProfile = resolveEffectiveProfile(updated)
        activeMuseProfile = effectiveProfile
        museGateway.configureStreamProfile(effectiveProfile)

        _uiState.value = updated.copy(
            effectiveMuseProfileLabel = effectiveProfile.label,
            lastError = null
        )

        if (updated.isMuseConnected) {
            reconnectMuseForProfileChange()
        }
    }

    fun setMusePowerMode(mode: MusePowerMode) {
        val updated = _uiState.value.copy(
            selectedPowerMode = mode,
            lastError = null
        )
        val effectiveProfile = resolveEffectiveProfile(updated)
        activeMuseProfile = effectiveProfile
        museGateway.configureStreamProfile(effectiveProfile)

        _uiState.value = updated.copy(
            effectiveMuseProfileLabel = effectiveProfile.label,
            lastError = null
        )

        if (updated.isMuseConnected) {
            reconnectMuseForProfileChange()
        }
    }

    private fun reconnectMuseForProfileChange() {
        val address = _uiState.value.museDeviceAddress.takeIf { it.isNotBlank() }
        museStreamJob?.cancel()
        museStreamJob = null

        _uiState.value = _uiState.value.copy(
            museConnectionStateText = "reconnecting",
            lastError = null
        )

        viewModelScope.launch {
            museGateway.disconnect()
            museGateway.connect(address)
                .onSuccess {
                    resetMuseWaveNormalizer()
                    _uiState.value = _uiState.value.copy(
                        isMuseConnected = true,
                        isUsingMuseStream = true,
                        museConnectionStateText = "connected",
                        autoSendEnabled = true,
                        lastError = null
                    )
                    stopFakeStream()
                    startMuseStreamCollection()
                }
                .onFailure { throwable ->
                    _uiState.value = _uiState.value.copy(
                        isMuseConnected = false,
                        isUsingMuseStream = false,
                        museConnectionStateText = "disconnected",
                        museEegSampleCount = 0,
                        museNotificationCount = 0L,
                        museEegNotificationCount = 0L,
                        museOtherNotificationCount = 0L,
                        museLastPacketBytes = 0,
                        museAlphaRatio = 0.0,
                        museDominantRatio = 0.0,
                        museTotalPower = 0.0,
                        museActivity = 0.0,
                        musePpgSampleCount = 0,
                        museHeartBpm = 0.0,
                        museOxygenPercent = 0.0,
                        musePacketPreviewHex = "-",
                        lastError = throwable.message ?: "Muse reconnect failed"
                    )
                    startFakeStream()
                }
        }
    }

    private fun startFakeStream() {
        if (fakeStreamJob?.isActive == true) {
            return
        }

        fakeStreamJob = viewModelScope.launch {
            var step = 0
            while (true) {
                val value = ((sin((step / 8.0) * PI / 2) + 1.0) / 2.0).coerceIn(0.0, 1.0)
                val band = when ((step / 20) % 5) {
                    0 -> BrainBand.DELTA
                    1 -> BrainBand.THETA
                    2 -> BrainBand.ALPHA
                    3 -> BrainBand.BETA
                    else -> BrainBand.GAMMA
                }

                updateState(
                    mode = _uiState.value.mode,
                    value = value,
                    band = band,
                    parameter = _uiState.value.selectedWaveformParameter,
                    rawValue = value
                )
                _uiState.value = _uiState.value.copy(selectedParameterValue = value)

                step++
                delay(120L)
            }
        }
    }

    private fun stopFakeStream() {
        fakeStreamJob?.cancel()
        fakeStreamJob = null
    }

    private fun startMuseStreamCollection() {
        museStreamJob?.cancel()
        resetMuseWaveNormalizer()
        museStreamJob = viewModelScope.launch {
            museGateway.streamReadings(pollIntervalMs = 120L)
                .catch { throwable ->
                    _uiState.value = _uiState.value.copy(
                        isMuseConnected = false,
                        isUsingMuseStream = false,
                        museConnectionStateText = "disconnected",
                        museEegSampleCount = 0,
                        museNotificationCount = 0L,
                        museEegNotificationCount = 0L,
                        museOtherNotificationCount = 0L,
                        museLastPacketBytes = 0,
                        museAlphaRatio = 0.0,
                        museDominantRatio = 0.0,
                        museTotalPower = 0.0,
                        museActivity = 0.0,
                        musePpgSampleCount = 0,
                        museHeartBpm = 0.0,
                        museOxygenPercent = 0.0,
                        musePacketPreviewHex = "-",
                        lastError = throwable.message ?: "Muse stream failed"
                    )
                    startFakeStream()
                }
                .collect { reading ->
                    val selectedParameter = _uiState.value.selectedWaveformParameter
                    val selection = mapSelectedWaveformValue(
                        reading = reading,
                        parameter = selectedParameter
                    )

                    _uiState.value = _uiState.value.copy(
                        selectedParameterValue = selection.rawValue,
                        museEegSampleCount = reading.eegSampleCount,
                        selectedParameterOscPath = selectedParameter.oscPath,
                        selectedParameterUnit = selectedParameter.unit,
                        museNotificationCount = reading.notificationCount,
                        museEegNotificationCount = reading.eegNotificationCount,
                        museOtherNotificationCount = reading.otherNotificationCount,
                        museLastPacketBytes = reading.latestPacketBytes,
                        museAlphaRatio = reading.alphaRatio,
                        museDominantRatio = reading.dominantRatio,
                        museTotalPower = reading.totalPower,
                        museActivity = reading.activity,
                        musePpgSampleCount = reading.ppgSampleCount,
                        museHeartBpm = reading.heartBpm,
                        museOxygenPercent = (reading.oxygenPercent * 100.0).coerceIn(0.0, 100.0),
                        musePacketPreviewHex = reading.packetPreviewHex
                    )

                    val normalizedForWave = when (selectedParameter) {
                        BfiWaveformParameter.BIOMETRICS_HEART_BPM -> ((selection.rawValue - 45.0) / 90.0).coerceIn(0.0, 1.0)
                        BfiWaveformParameter.BIOMETRICS_OXYGEN -> (selection.rawValue / 100.0).coerceIn(0.0, 1.0)
                        else -> normalizeMuseWaveValue(
                            rawValue = selection.normalized,
                            band = reading.dominantBand
                        )
                    }
                    updateState(
                        mode = _uiState.value.mode,
                        value = normalizedForWave,
                        band = reading.dominantBand,
                        parameter = selectedParameter,
                        rawValue = selection.rawValue
                    )
                }
        }
    }

    private data class WaveformValueSelection(
        val rawValue: Double,
        val normalized: Double
    )

    private fun mapSelectedWaveformValue(
        reading: io.rroki.brainflowintodivoom.data.muse.MuseReading,
        parameter: BfiWaveformParameter
    ): WaveformValueSelection {
        return when (parameter) {
            BfiWaveformParameter.PWR_AVG_DELTA -> WaveformValueSelection(reading.deltaPower, reading.deltaPower)
            BfiWaveformParameter.PWR_AVG_THETA -> WaveformValueSelection(reading.thetaPower, reading.thetaPower)
            BfiWaveformParameter.PWR_AVG_ALPHA -> WaveformValueSelection(reading.alphaPower, reading.alphaPower)
            BfiWaveformParameter.PWR_AVG_BETA -> WaveformValueSelection(reading.betaPower, reading.betaPower)
            BfiWaveformParameter.PWR_AVG_GAMMA -> WaveformValueSelection(reading.gammaPower, reading.gammaPower)
            BfiWaveformParameter.NEUROFB_FOCUS_AVG -> {
                WaveformValueSelection(reading.focusScorePos, reading.focusScorePos)
            }

            BfiWaveformParameter.NEUROFB_RELAX_AVG -> {
                WaveformValueSelection(reading.relaxScorePos, reading.relaxScorePos)
            }

            BfiWaveformParameter.BIOMETRICS_HEART_BPM -> {
                val normalized = ((reading.heartBpm - 45.0) / 90.0).coerceIn(0.0, 1.0)
                WaveformValueSelection(reading.heartBpm, normalized)
            }

            BfiWaveformParameter.BIOMETRICS_OXYGEN -> {
                val percent = (reading.oxygenPercent * 100.0).coerceIn(0.0, 100.0)
                WaveformValueSelection(percent, reading.oxygenPercent.coerceIn(0.0, 1.0))
            }
        }
    }

    private fun resolveEffectiveProfile(state: MainUiState): MuseStreamProfile {
        return when (state.selectedPowerMode) {
            MusePowerMode.EEG_ONLY -> MuseStreamProfile.EEG_ONLY
            MusePowerMode.FULL_BIOMETRICS -> MuseStreamProfile.FULL_BIOMETRICS
            MusePowerMode.AUTO -> {
                if (state.selectedWaveformParameter.requiresOptics) {
                    MuseStreamProfile.FULL_BIOMETRICS
                } else {
                    MuseStreamProfile.EEG_ONLY
                }
            }
        }
    }

    private fun normalizeMuseWaveValue(rawValue: Double, band: BrainBand): Double {
        val clamped = rawValue.coerceIn(0.0, 1.0)

        // Slowly track local min/max so even subtle EEG changes are visible on 16px height.
        museAdaptiveMin += (clamped - museAdaptiveMin) * 0.02
        museAdaptiveMax += (clamped - museAdaptiveMax) * 0.02
        museAdaptiveMin = min(museAdaptiveMin, clamped)
        museAdaptiveMax = max(museAdaptiveMax, clamped)

        if (museAdaptiveMax - museAdaptiveMin < 0.05) {
            val mid = (museAdaptiveMax + museAdaptiveMin) / 2.0
            museAdaptiveMin = (mid - 0.025).coerceAtLeast(0.0)
            museAdaptiveMax = (mid + 0.025).coerceAtMost(1.0)
        }

        val range = (museAdaptiveMax - museAdaptiveMin).coerceAtLeast(0.05)
        var stretched = ((clamped - museAdaptiveMin) / range).coerceIn(0.0, 1.0)

        val bandBias = when (band) {
            BrainBand.DELTA -> -0.10
            BrainBand.THETA -> -0.05
            BrainBand.ALPHA -> 0.0
            BrainBand.BETA -> 0.08
            BrainBand.GAMMA -> 0.04
        }
        stretched = (stretched + bandBias).coerceIn(0.0, 1.0)

        museRenderedValue = (museRenderedValue * 0.65) + (stretched * 0.35)
        return museRenderedValue
    }

    private fun resetMuseWaveNormalizer() {
        museAdaptiveMin = 0.35
        museAdaptiveMax = 0.65
        museRenderedValue = 0.5
    }

    private fun updateState(mode: DisplayMode, value: Double, band: BrainBand) {
        updateState(
            mode = mode,
            value = value,
            band = band,
            parameter = _uiState.value.selectedWaveformParameter,
            rawValue = _uiState.value.selectedParameterValue
        )
    }

    private fun updateState(
        mode: DisplayMode,
        value: Double,
        band: BrainBand,
        parameter: BfiWaveformParameter,
        rawValue: Double
    ) {
        val frame = when (mode) {
            DisplayMode.OSCILLOSCOPE -> {
                when (parameter) {
                    BfiWaveformParameter.BIOMETRICS_HEART_BPM -> buildHeartbeatFrame(rawValue)
                    BfiWaveformParameter.BIOMETRICS_OXYGEN -> buildOxygenFrame(value)
                    else -> tintOscilloscopeFrame(
                        frame = oscilloscopeGenerator.pushNormalized(value),
                        color = parameter.colorArgb
                    )
                }
            }

            DisplayMode.VRCHAT_LOGO -> logoGenerator.buildFrame(band)
        }
        val packet = encoder.encodeFrame(frame)
        val packetSize = packet.size

        _uiState.value = _uiState.value.copy(
            mode = mode,
            normalizedValue = value,
            dominantBand = band,
            frame = frame,
            packetSizeBytes = packetSize
        )

        if (_uiState.value.autoSendEnabled && _uiState.value.isDivoomConnected) {
            viewModelScope.launch {
                frameQueue.enqueue(packet)
            }
        }
    }

    private fun tintOscilloscopeFrame(frame: IntArray, color: Int): IntArray {
        val tinted = frame.copyOf()
        for (index in tinted.indices) {
            if (tinted[index] == OSC_LINE_COLOR) {
                tinted[index] = color
            }
        }
        return tinted
    }

    private fun buildHeartbeatFrame(rawBpm: Double): IntArray {
        val bpm = rawBpm.coerceIn(40.0, 180.0).takeIf { it > 0.0 } ?: 72.0
        val periodMs = (60_000.0 / bpm).toLong().coerceAtLeast(250L)
        val phase = System.currentTimeMillis() % periodMs
        val onBeat = phase < (periodMs * 0.18)

        val background = 0xFF08090F.toInt()
        val bodyColor = if (onBeat) 0xFFFF3B5C.toInt() else 0xFF4A1A29.toInt()
        val glowColor = if (onBeat) 0xFFFFA3B5.toInt() else 0xFF6D3142.toInt()
        val frame = IntArray(16 * 16) { background }

        HEART_MASK.forEachIndexed { y, row ->
            row.forEachIndexed { x, cell ->
                if (cell == '1') {
                    frame[(y * 16) + x] = bodyColor
                } else if (cell == '2') {
                    frame[(y * 16) + x] = glowColor
                }
            }
        }
        return frame
    }

    private fun buildOxygenFrame(level: Double): IntArray {
        val normalized = level.coerceIn(0.0, 1.0)
        val frame = IntArray(16 * 16) { 0xFF040913.toInt() }
        val centerX = 7.5
        val centerY = 7.5
        val radius = 7.4

        for (y in 0 until 16) {
            for (x in 0 until 16) {
                val dx = x - centerX
                val dy = y - centerY
                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                val radial = (1.0 - (dist / radius)).coerceIn(0.0, 1.0)
                val density = (radial * normalized).coerceIn(0.0, 1.0)
                val color = lerpColor(0xFF062437.toInt(), 0xFF22D3EE.toInt(), density)
                frame[(y * 16) + x] = color
            }
        }

        return frame
    }

    private fun lerpColor(from: Int, to: Int, t: Double): Int {
        val clamped = t.coerceIn(0.0, 1.0)
        val fa = (from ushr 24) and 0xFF
        val fr = (from ushr 16) and 0xFF
        val fg = (from ushr 8) and 0xFF
        val fb = from and 0xFF

        val ta = (to ushr 24) and 0xFF
        val tr = (to ushr 16) and 0xFF
        val tg = (to ushr 8) and 0xFF
        val tb = to and 0xFF

        val a = (fa + ((ta - fa) * clamped)).toInt().coerceIn(0, 255)
        val r = (fr + ((tr - fr) * clamped)).toInt().coerceIn(0, 255)
        val g = (fg + ((tg - fg) * clamped)).toInt().coerceIn(0, 255)
        val b = (fb + ((tb - fb) * clamped)).toInt().coerceIn(0, 255)

        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun enqueueEncodedFrame(frame: IntArray) {
        viewModelScope.launch {
            val packet = encoder.encodeFrame(frame)
            frameQueue.enqueue(packet)
        }
    }

    private suspend fun sendPacketWithRecovery(packet: ByteArray) {
        val firstError = divoomClient.send(packet).exceptionOrNull() ?: return

        val recoverable = firstError is IOException && isRecoverableConnectionDrop(firstError)
        if (!recoverable) {
            handleSendDisconnect(firstError.message ?: "Failed to send frame")
            return
        }

        for (attempt in 1..reconnectPolicy.maxAttempts) {
            if (attempt > 1) {
                delay(reconnectPolicy.delayForAttempt(attempt))
            }

            updateConnectionState(
                state = DivoomConnectionState.CONNECTING,
                error = "Connection dropped. Reconnecting ($attempt/${reconnectPolicy.maxAttempts})...",
                reconnectAttempt = attempt
            )

            val stateSnapshot = _uiState.value
            val reconnectResult = divoomClient.connectBySelection(
                deviceName = stateSnapshot.divoomDeviceName,
                deviceAddress = stateSnapshot.selectedDivoomDeviceAddress
            )
            if (reconnectResult.isFailure) {
                continue
            }

            val resendError = divoomClient.send(packet).exceptionOrNull()
            if (resendError == null) {
                updateConnectionState(
                    state = DivoomConnectionState.CONNECTED,
                    reconnectAttempt = 0
                )
                return
            }

            if (resendError !is IOException || !isRecoverableConnectionDrop(resendError)) {
                handleSendDisconnect(resendError.message ?: "Failed to send frame after reconnect")
                return
            }
        }

        handleSendDisconnect("Reconnect failed after ${reconnectPolicy.maxAttempts} attempts")
    }

    private fun handleSendDisconnect(error: String) {
        updateConnectionState(
            state = DivoomConnectionState.DISCONNECTED,
            error = error,
            reconnectAttempt = 0
        )
        _uiState.value = _uiState.value.copy(autoSendEnabled = false)
    }

    private fun isRecoverableConnectionDrop(throwable: IOException): Boolean {
        val message = throwable.message.orEmpty()
        return message.contains("broken pipe", ignoreCase = true) ||
            message.contains("socket closed", ignoreCase = true) ||
            message.contains("connection abort", ignoreCase = true) ||
            message.contains("connection reset", ignoreCase = true)
    }

    private fun updateDiscoveredDevices(devices: List<DivoomDiscoveredDevice>) {
        val options = devices.map {
            DivoomDeviceOption(
                name = it.name,
                address = it.address,
                isBonded = it.isBonded
            )
        }

        val currentState = _uiState.value
        val selectedAddress = when {
            currentState.selectedDivoomDeviceAddress != null &&
                options.any { it.address == currentState.selectedDivoomDeviceAddress } -> {
                currentState.selectedDivoomDeviceAddress
            }

            options.isNotEmpty() -> options.first().address
            else -> null
        }
        val selectedName = options.firstOrNull { it.address == selectedAddress }?.name ?: currentState.divoomDeviceName

        _uiState.value = currentState.copy(
            availableDivoomDevices = options,
            selectedDivoomDeviceAddress = selectedAddress,
            divoomDeviceName = selectedName,
            isScanningDivoomDevices = false,
            lastError = if (options.isEmpty()) {
                "No Divoom devices found. Keep the device discoverable and try scan again"
            } else {
                null
            }
        )
    }

    private fun updateConnectionState(
        state: DivoomConnectionState,
        error: String? = null,
        reconnectAttempt: Int? = null
    ) {
        connectionState = state
        val message = when (state) {
            DivoomConnectionState.DISCONNECTED -> "disconnected"
            DivoomConnectionState.CONNECTING -> "connecting"
            DivoomConnectionState.CONNECTED -> "connected"
        }

        val reconnectState = reconnectAttempt ?: when (state) {
            DivoomConnectionState.CONNECTING -> _uiState.value.reconnectAttempt
            DivoomConnectionState.DISCONNECTED,
            DivoomConnectionState.CONNECTED -> 0
        }

        _uiState.value = _uiState.value.copy(
            isDivoomConnected = state == DivoomConnectionState.CONNECTED,
            connectionStateText = message,
            reconnectAttempt = reconnectState,
            lastError = error
        )
    }

    fun setDivoomDeviceName(name: String) {
        _uiState.value = _uiState.value.copy(divoomDeviceName = name, lastError = null)
    }

    override fun onCleared() {
        super.onCleared()
        fakeStreamJob?.cancel()
        museStreamJob?.cancel()
        frameQueue.stop()
        viewModelScope.launch {
            museGateway.disconnect()
        }
        divoomClient.close()
    }

    private companion object {
        private val OSC_LINE_COLOR = 0xFF00E5FF.toInt()

        private val HEART_MASK = listOf(
            "0000000000000000",
            "0000222000222000",
            "0002111202111200",
            "0021111111111120",
            "0211111111111112",
            "0211111111111112",
            "0021111111111120",
            "0002111111111200",
            "0000211111112000",
            "0000021111120000",
            "0000002111200000",
            "0000000212000000",
            "0000000020000000",
            "0000000000000000",
            "0000000000000000",
            "0000000000000000"
        )
    }
}
