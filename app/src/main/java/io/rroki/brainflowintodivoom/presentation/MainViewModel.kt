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
import io.rroki.brainflowintodivoom.data.muse.OpenMuseAthenaGateway
import io.rroki.brainflowintodivoom.domain.model.BrainBand
import io.rroki.brainflowintodivoom.domain.model.DisplayMode
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
                    band = band
                )

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
                        musePacketPreviewHex = "-",
                        lastError = throwable.message ?: "Muse stream failed"
                    )
                    startFakeStream()
                }
                .collect { reading ->
                    _uiState.value = _uiState.value.copy(
                        museEegSampleCount = reading.eegSampleCount,
                        museNotificationCount = reading.notificationCount,
                        museEegNotificationCount = reading.eegNotificationCount,
                        museOtherNotificationCount = reading.otherNotificationCount,
                        museLastPacketBytes = reading.latestPacketBytes,
                        museAlphaRatio = reading.alphaRatio,
                        museDominantRatio = reading.dominantRatio,
                        museTotalPower = reading.totalPower,
                        museActivity = reading.activity,
                        musePacketPreviewHex = reading.packetPreviewHex
                    )

                    val normalizedForWave = normalizeMuseWaveValue(
                        rawValue = reading.normalizedAlpha,
                        band = reading.dominantBand
                    )
                    updateState(
                        mode = _uiState.value.mode,
                        value = normalizedForWave,
                        band = reading.dominantBand
                    )
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
        val frame = when (mode) {
            DisplayMode.OSCILLOSCOPE -> oscilloscopeGenerator.pushNormalized(value)
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
}
