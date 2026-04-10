package io.rroki.brainflowintodivoom.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.rroki.brainflowintodivoom.domain.model.BfiWaveformParameter
import io.rroki.brainflowintodivoom.domain.model.DisplayMode
import io.rroki.brainflowintodivoom.domain.model.MusePowerMode

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
        onSendIntervalSelected = mainViewModel::setSendIntervalMs,
        onConnectMuseClick = mainViewModel::connectToMuse,
        onDisconnectMuseClick = mainViewModel::disconnectFromMuse,
        onWaveformParameterSelected = mainViewModel::setWaveformParameter,
        onPowerModeSelected = mainViewModel::setMusePowerMode
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
    onSendIntervalSelected: (Long) -> Unit,
    onConnectMuseClick: () -> Unit,
    onDisconnectMuseClick: () -> Unit,
    onWaveformParameterSelected: (BfiWaveformParameter) -> Unit,
    onPowerModeSelected: (MusePowerMode) -> Unit
) {
    val scrollState = rememberScrollState()
    val cardColors = CardDefaults.cardColors(containerColor = Color(0x1AFFFFFF))

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF05070F)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0xFF0B1022), Color(0xFF070C17), Color(0xFF04060D))
                    )
                )
                .verticalScroll(scrollState)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Card(colors = cardColors, border = BorderStroke(1.dp, Color(0x33B4F2FF))) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Neuro Visual Studio",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color(0xFFE8EEFF),
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Divoom x Muse S Athena",
                        color = Color(0xFF9FB0D8)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = {},
                            label = { Text("Muse: ${state.museConnectionStateText}", color = Color(0xFFEAF0FF)) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = Color(0xFF1A2236),
                                labelColor = Color(0xFFEAF0FF)
                            )
                        )
                        AssistChip(
                            onClick = {},
                            label = { Text("Divoom: ${state.connectionStateText}", color = Color(0xFFEAF0FF)) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = Color(0xFF1A2236),
                                labelColor = Color(0xFFEAF0FF)
                            )
                        )
                        AssistChip(
                            onClick = {},
                            label = { Text("Profile: ${state.effectiveMuseProfileLabel}", color = Color(0xFFEAF0FF)) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = Color(0xFF1A2236),
                                labelColor = Color(0xFFEAF0FF)
                            )
                        )
                    }
                }
            }

            Card(colors = cardColors) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Waveform Source", color = Color(0xFFE8EEFF), fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "OSC Path: ${state.selectedParameterOscPath}",
                        color = Color(0xFF9FB0D8)
                    )
                    Text(
                        text = "Current: ${"%.3f".format(state.selectedParameterValue)} ${state.selectedParameterUnit}",
                        color = Color(0xFFB6FFD9)
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(Color(state.selectedWaveformParameter.colorArgb))
                        )
                        Text(
                            text = "Theme: ${state.selectedWaveformParameter.label}",
                            color = Color(0xFFD2DBF6)
                        )
                    }

                    if (state.selectedWaveformParameter == BfiWaveformParameter.BIOMETRICS_HEART_BPM) {
                        Text(
                            text = "Heart Rate mode: BPM-synced pulse animation",
                            color = Color(0xFFFDA4AF)
                        )
                    }

                    if (state.selectedWaveformParameter == BfiWaveformParameter.BIOMETRICS_OXYGEN) {
                        Text(
                            text = "Oxygen mode: intensity gradient visualization",
                            color = Color(0xFF67E8F9)
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        BfiWaveformParameter.entries.forEach { parameter ->
                            val parameterColor = Color(parameter.colorArgb)
                            FilterChip(
                                selected = state.selectedWaveformParameter == parameter,
                                onClick = { onWaveformParameterSelected(parameter) },
                                label = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .background(parameterColor)
                                        )
                                        Text(parameter.label)
                                    }
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = parameterColor.copy(alpha = 0.25f),
                                    selectedLabelColor = Color.White,
                                    containerColor = Color(0xFF1A2236),
                                    labelColor = Color(0xFFDCE6FF)
                                )
                            )
                        }
                    }
                }
            }

            Card(colors = cardColors) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Muse Power Mode", color = Color(0xFFE8EEFF), fontWeight = FontWeight.SemiBold)
                    Text(
                        text = when {
                            state.selectedPowerMode == MusePowerMode.AUTO && state.selectedWaveformParameter.requiresOptics -> {
                                "Auto mode selects Full Biometrics because this parameter requires Optics/PPG."
                            }

                            state.selectedPowerMode == MusePowerMode.AUTO -> {
                                "Auto mode selects EEG Only to reduce battery usage."
                            }

                            else -> state.selectedPowerMode.description
                        },
                        color = Color(0xFF9FB0D8)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MusePowerMode.entries.forEach { mode ->
                            FilterChip(
                                selected = state.selectedPowerMode == mode,
                                onClick = { onPowerModeSelected(mode) },
                                label = { Text(mode.label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFF264064),
                                    selectedLabelColor = Color.White,
                                    containerColor = Color(0xFF1A2236),
                                    labelColor = Color(0xFFDCE6FF)
                                )
                            )
                        }
                    }
                }
            }

            state.lastError?.let { error ->
                Text(text = "error=$error", color = Color(0xFFFF8A80))
            }

            Card(colors = cardColors) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Device Selection", color = Color(0xFFE8EEFF), fontWeight = FontWeight.SemiBold)
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
                            color = Color(0xFFB8C7E6)
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
                        Button(
                            onClick = onConnectMuseClick,
                            enabled = state.hasBluetoothPermissions && !state.isMuseConnected
                        ) {
                            Text(if (state.brainFlowRuntimeAvailable) "Connect as Muse" else "Runtime Missing")
                        }
                        Button(
                            onClick = onConnectClick,
                            enabled = state.hasBluetoothPermissions && !state.isDivoomConnected
                        ) {
                            Text("Connect as Divoom")
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = onDisconnectMuseClick,
                            enabled = state.isMuseConnected
                        ) {
                            Text("Disconnect Muse")
                        }
                        Button(
                            onClick = onDisconnectClick,
                            enabled = state.isDivoomConnected
                        ) {
                            Text("Disconnect Divoom")
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
                }
            }

            Card(colors = cardColors) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Visualization Style", color = Color(0xFFE8EEFF), fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { onModeSelected(DisplayMode.OSCILLOSCOPE) }) {
                            Text("Oscilloscope")
                        }
                        Button(onClick = { onModeSelected(DisplayMode.VRCHAT_LOGO) }) {
                            Text("Bubble")
                        }
                    }
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

            Card(colors = cardColors) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Diagnostics", color = Color(0xFFE8EEFF), fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "normalized=${"%.2f".format(state.normalizedValue)}  packet=${state.packetSizeBytes} bytes",
                        color = Color(0xFF9AA8C2)
                    )
                    Text(
                        text = "museRaw: notif=${state.museNotificationCount} (eeg=${state.museEegNotificationCount}/other=${state.museOtherNotificationCount})  lastPkt=${state.museLastPacketBytes} bytes  eegSamples=${state.museEegSampleCount}",
                        color = Color(0xFF9AA8C2)
                    )
                    Text(
                        text = "museBands: alphaRatio=${"%.4f".format(state.museAlphaRatio)}  dominantRatio=${"%.4f".format(state.museDominantRatio)}  totalPower=${"%.6f".format(state.museTotalPower)}",
                        color = Color(0xFF9AA8C2)
                    )
                    Text(
                        text = "museBio: ppgSamples=${state.musePpgSampleCount}  heart=${"%.1f".format(state.museHeartBpm)} bpm  oxygen=${"%.1f".format(state.museOxygenPercent)}%",
                        color = Color(0xFF9AA8C2)
                    )
                    Text(
                        text = "museActivity=${"%.4f".format(state.museActivity)}  reconnectAttempt=${state.reconnectAttempt}",
                        color = Color(0xFF9AA8C2)
                    )
                    Text(
                        text = "musePacketHead=${state.musePacketPreviewHex}",
                        color = Color(0xFF9AA8C2)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = if (state.hasBluetoothPermissions) {
                    "Ready: parameter-selectable Muse streaming with profile-aware power optimization."
                } else {
                    "Grant Bluetooth permissions to connect Muse and Divoom."
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
