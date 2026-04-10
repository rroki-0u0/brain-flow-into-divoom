package io.rroki.brainflowintodivoom.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.rroki.brainflowintodivoom.domain.model.DisplayMode

@Composable
fun MainRoute(
    hasBluetoothPermissions: Boolean,
    mainViewModel: MainViewModel = viewModel()
) {
    val state by mainViewModel.uiState.collectAsState()

    LaunchedEffect(hasBluetoothPermissions) {
        mainViewModel.onBluetoothPermissionChanged(hasBluetoothPermissions)
    }

    MainScreen(
        state = state,
        onModeSelected = mainViewModel::setMode,
        onScanDevicesClick = mainViewModel::scanDivoomDevices,
        onSelectDivoomDevice = mainViewModel::selectDivoomDevice,
        onConnectClick = mainViewModel::connectToDivoom,
        onDisconnectClick = mainViewModel::disconnectFromDivoom,
        onSendFrameClick = mainViewModel::sendCurrentFrame,
        onAutoSendToggle = mainViewModel::toggleAutoSend,
        onSendIntervalSelected = mainViewModel::setSendIntervalMs
    )
}

@Composable
fun MainScreen(
    state: MainUiState,
    onModeSelected: (DisplayMode) -> Unit,
    onScanDevicesClick: () -> Unit,
    onSelectDivoomDevice: (String) -> Unit,
    onConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onSendFrameClick: () -> Unit,
    onAutoSendToggle: () -> Unit,
    onSendIntervalSelected: (Long) -> Unit
) {
    val scrollState = rememberScrollState()

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF02030A)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Divoom x Muse S",
                style = MaterialTheme.typography.headlineSmall,
                color = Color(0xFFB4F2FF),
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "mode=${state.mode}  dominant=${state.dominantBand}",
                color = Color(0xFF9AA8C2)
            )
            Text(
                text = "normalized=${"%.2f".format(state.normalizedValue)}  packet=${state.packetSizeBytes} bytes",
                color = Color(0xFF9AA8C2)
            )
            Text(
                text = "divoom=${state.connectionStateText}  autoSend=${state.autoSendEnabled}  interval=${state.sendIntervalMs}ms",
                color = if (state.isDivoomConnected) Color(0xFF6EE7B7) else Color(0xFFFCA5A5)
            )
            Text(
                text = "reconnectAttempt=${state.reconnectAttempt}",
                color = Color(0xFF9AA8C2)
            )
            Text(
                text = "selected=${state.divoomDeviceName}",
                color = Color(0xFF9AA8C2)
            )

            state.lastError?.let { error ->
                Text(text = "error=$error", color = Color(0xFFFF8A80))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onScanDevicesClick,
                    enabled = state.hasBluetoothPermissions && !state.isScanningDivoomDevices
                ) {
                    Text(if (state.isScanningDivoomDevices) "Scanning..." else "Scan Devices")
                }
            }

            if (state.availableDivoomDevices.isEmpty()) {
                Text(
                    text = "No discovered devices yet",
                    color = Color(0xFF9AA8C2)
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.availableDivoomDevices.forEach { device ->
                        val selected = device.address == state.selectedDivoomDeviceAddress
                        Button(
                            onClick = { onSelectDivoomDevice(device.address) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.isScanningDivoomDevices,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selected) Color(0xFF14532D) else Color(0xFF1F2937)
                            )
                        ) {
                            val source = if (device.isBonded) "paired" else "discovered"
                            val suffix = device.address.takeLast(5)
                            Text("${device.name} [$source • ..$suffix]")
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { onModeSelected(DisplayMode.OSCILLOSCOPE) }) {
                    Text("Mode A")
                }
                Button(onClick = { onModeSelected(DisplayMode.VRCHAT_LOGO) }) {
                    Text("Mode B")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onConnectClick,
                    enabled = state.hasBluetoothPermissions && !state.isDivoomConnected
                ) {
                    Text("Connect")
                }
                Button(
                    onClick = onDisconnectClick,
                    enabled = state.isDivoomConnected
                ) {
                    Text("Disconnect")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onSendFrameClick,
                    enabled = state.isDivoomConnected
                ) {
                    Text("Send Frame")
                }
                Button(onClick = onAutoSendToggle) {
                    Text(if (state.autoSendEnabled) "Auto: ON" else "Auto: OFF")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { onSendIntervalSelected(100L) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.sendIntervalMs == 100L) Color(0xFF14532D) else Color(0xFF1F2937)
                    )
                ) {
                    Text("100ms")
                }
                Button(
                    onClick = { onSendIntervalSelected(150L) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.sendIntervalMs == 150L) Color(0xFF14532D) else Color(0xFF1F2937)
                    )
                ) {
                    Text("150ms")
                }
                Button(
                    onClick = { onSendIntervalSelected(200L) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.sendIntervalMs == 200L) Color(0xFF14532D) else Color(0xFF1F2937)
                    )
                ) {
                    Text("200ms")
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF04070D))
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                LedMatrixPreview(frame = state.frame)
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = if (state.hasBluetoothPermissions) {
                    "MVP status: simulated EEG stream running (120ms), Divoom send interval is adjustable."
                } else {
                    "MVP status: grant Bluetooth permissions to connect Divoom."
                },
                color = if (state.hasBluetoothPermissions) Color(0xFF6EE7B7) else Color(0xFFFFB74D)
            )
        }
    }
}

@Composable
private fun LedMatrixPreview(frame: IntArray, cellSize: Int = 16) {
    val panelSize = (16 * cellSize).dp
    Canvas(modifier = Modifier.size(panelSize)) {
        val pixelSize = size.width / 16f
        for (y in 0 until 16) {
            for (x in 0 until 16) {
                val index = y * 16 + x
                val color = Color(frame[index])
                drawRect(
                    color = color,
                    topLeft = Offset(x * pixelSize, y * pixelSize),
                    size = Size(pixelSize - 1f, pixelSize - 1f)
                )
            }
        }
    }
}
