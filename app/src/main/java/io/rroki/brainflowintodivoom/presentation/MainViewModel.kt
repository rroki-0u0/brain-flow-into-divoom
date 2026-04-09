package io.rroki.brainflowintodivoom.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.rroki.brainflowintodivoom.data.divoom.DivoomBluetoothClient
import io.rroki.brainflowintodivoom.data.divoom.DivoomConnectionState
import io.rroki.brainflowintodivoom.data.divoom.DivoomPacketEncoder
import io.rroki.brainflowintodivoom.data.divoom.FrameDispatchQueue
import io.rroki.brainflowintodivoom.domain.model.BrainBand
import io.rroki.brainflowintodivoom.domain.model.DisplayMode
import io.rroki.brainflowintodivoom.domain.processing.OscilloscopeFrameGenerator
import io.rroki.brainflowintodivoom.domain.processing.VrchatLogoFrameGenerator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val oscilloscopeGenerator = OscilloscopeFrameGenerator()
    private val logoGenerator = VrchatLogoFrameGenerator()
    private val encoder = DivoomPacketEncoder()
    private val divoomClient = DivoomBluetoothClient(application.applicationContext)
    private var connectionState = DivoomConnectionState.DISCONNECTED
    private val frameQueue = FrameDispatchQueue(minIntervalMs = 120L) { packet ->
        divoomClient.send(packet).onFailure { throwable ->
            updateConnectionState(
                state = DivoomConnectionState.DISCONNECTED,
                error = throwable.message ?: "Failed to send frame"
            )
        }
    }

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        frameQueue.start(viewModelScope)
        startFakeStream()
    }

    fun onBluetoothPermissionChanged(granted: Boolean) {
        _uiState.value = _uiState.value.copy(
            hasBluetoothPermissions = granted,
            lastError = if (granted) null else "Bluetooth permissions are required"
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
            divoomClient.connectByName(_uiState.value.divoomDeviceName)
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
        viewModelScope.launch {
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

    private fun updateConnectionState(state: DivoomConnectionState, error: String? = null) {
        connectionState = state
        val message = when (state) {
            DivoomConnectionState.DISCONNECTED -> "disconnected"
            DivoomConnectionState.CONNECTING -> "connecting"
            DivoomConnectionState.CONNECTED -> "connected"
        }

        _uiState.value = _uiState.value.copy(
            isDivoomConnected = state == DivoomConnectionState.CONNECTED,
            connectionStateText = message,
            lastError = error
        )
    }

    override fun onCleared() {
        super.onCleared()
        frameQueue.stop()
        divoomClient.close()
    }
}
