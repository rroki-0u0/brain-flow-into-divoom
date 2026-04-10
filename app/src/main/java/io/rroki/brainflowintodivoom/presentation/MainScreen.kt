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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.rroki.brainflowintodivoom.R
import io.rroki.brainflowintodivoom.domain.model.BfiWaveformParameter
import io.rroki.brainflowintodivoom.domain.model.DisplayMode
import io.rroki.brainflowintodivoom.domain.model.MusePowerMode
import io.rroki.brainflowintodivoom.ui.theme.cardBorder
import io.rroki.brainflowintodivoom.ui.theme.cardGlass
import io.rroki.brainflowintodivoom.ui.theme.chipContainer
import io.rroki.brainflowintodivoom.ui.theme.chipLabel
import io.rroki.brainflowintodivoom.ui.theme.defaultButton
import io.rroki.brainflowintodivoom.ui.theme.diagnosticText
import io.rroki.brainflowintodivoom.ui.theme.emptyState
import io.rroki.brainflowintodivoom.ui.theme.filterLabel
import io.rroki.brainflowintodivoom.ui.theme.heartHint
import io.rroki.brainflowintodivoom.ui.theme.oxygenHint
import io.rroki.brainflowintodivoom.ui.theme.permissionWarning
import io.rroki.brainflowintodivoom.ui.theme.powerSelected
import io.rroki.brainflowintodivoom.ui.theme.previewPanel
import io.rroki.brainflowintodivoom.ui.theme.readyText
import io.rroki.brainflowintodivoom.ui.theme.screenGradientBottom
import io.rroki.brainflowintodivoom.ui.theme.screenGradientMiddle
import io.rroki.brainflowintodivoom.ui.theme.screenGradientTop
import io.rroki.brainflowintodivoom.ui.theme.secondaryLabel
import io.rroki.brainflowintodivoom.ui.theme.selectedButton
import io.rroki.brainflowintodivoom.ui.theme.signalValue

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
    val colors = MaterialTheme.colorScheme
    val scrollState = rememberScrollState()
    val cardColors = CardDefaults.cardColors(containerColor = colors.cardGlass)

    Surface(modifier = Modifier.fillMaxSize(), color = colors.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(colors.screenGradientTop, colors.screenGradientMiddle, colors.screenGradientBottom)
                    )
                )
                .verticalScroll(scrollState)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Card(colors = cardColors, border = BorderStroke(1.dp, colors.cardBorder)) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.main_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = colors.onSurface,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.main_subtitle),
                        color = colors.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    stringResource(R.string.main_chip_muse, state.museConnectionStateText),
                                    color = colors.chipLabel
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = colors.chipContainer,
                                labelColor = colors.chipLabel
                            )
                        )
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    stringResource(R.string.main_chip_divoom, state.connectionStateText),
                                    color = colors.chipLabel
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = colors.chipContainer,
                                labelColor = colors.chipLabel
                            )
                        )
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    stringResource(R.string.main_chip_profile, state.effectiveMuseProfileLabel),
                                    color = colors.chipLabel
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = colors.chipContainer,
                                labelColor = colors.chipLabel
                            )
                        )
                    }
                }
            }

            Card(colors = cardColors) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.main_waveform_source),
                        color = colors.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.main_osc_path, state.selectedParameterOscPath),
                        color = colors.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(
                            R.string.main_current_value,
                            "%.3f".format(state.selectedParameterValue),
                            state.selectedParameterUnit
                        ),
                        color = colors.signalValue
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
                            text = stringResource(R.string.main_theme_label, state.selectedWaveformParameter.label),
                            color = colors.secondaryLabel
                        )
                    }

                    if (state.selectedWaveformParameter == BfiWaveformParameter.BIOMETRICS_HEART_BPM) {
                        Text(
                            text = stringResource(R.string.main_heart_mode_hint),
                            color = colors.heartHint
                        )
                    }

                    if (state.selectedWaveformParameter == BfiWaveformParameter.BIOMETRICS_OXYGEN) {
                        Text(
                            text = stringResource(R.string.main_oxygen_mode_hint),
                            color = colors.oxygenHint
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
                                    containerColor = colors.chipContainer,
                                    labelColor = colors.filterLabel
                                )
                            )
                        }
                    }
                }
            }

            Card(colors = cardColors) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.main_muse_power_mode),
                        color = colors.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = when {
                            state.selectedPowerMode == MusePowerMode.AUTO && state.selectedWaveformParameter.requiresOptics -> {
                                stringResource(R.string.main_auto_mode_full)
                            }

                            state.selectedPowerMode == MusePowerMode.AUTO -> {
                                stringResource(R.string.main_auto_mode_eeg)
                            }

                            else -> state.selectedPowerMode.description
                        },
                        color = colors.onSurfaceVariant
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
                                    selectedContainerColor = colors.powerSelected,
                                    selectedLabelColor = Color.White,
                                    containerColor = colors.chipContainer,
                                    labelColor = colors.filterLabel
                                )
                            )
                        }
                    }
                }
            }

            state.lastError?.let { error ->
                Text(text = stringResource(R.string.main_error, error), color = colors.error)
            }

            Card(colors = cardColors) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            stringResource(R.string.main_device_selection),
                            color = colors.onSurface,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = onScanDevicesClick,
                            enabled = state.hasBluetoothPermissions && !state.isScanningDivoomDevices
                        ) {
                            Text(
                                if (state.isScanningDivoomDevices) {
                                    stringResource(R.string.main_scanning)
                                } else {
                                    stringResource(R.string.main_scan_devices)
                                }
                            )
                        }
                    }

                    if (state.availableDivoomDevices.isEmpty()) {
                        Text(
                            text = stringResource(R.string.main_no_devices),
                            color = colors.emptyState
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
                                        containerColor = if (selected) colors.selectedButton else colors.defaultButton
                                    )
                                ) {
                                    val source = if (device.isBonded) {
                                        stringResource(R.string.main_device_source_paired)
                                    } else {
                                        stringResource(R.string.main_device_source_discovered)
                                    }
                                    val suffix = device.address.takeLast(5)
                                    Text(
                                        stringResource(
                                            R.string.main_device_item,
                                            device.name.orEmpty(),
                                            source,
                                            suffix
                                        )
                                    )
                                }
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = onConnectMuseClick,
                            enabled = state.hasBluetoothPermissions && !state.isMuseConnected
                        ) {
                            Text(
                                if (state.brainFlowRuntimeAvailable) {
                                    stringResource(R.string.main_connect_muse)
                                } else {
                                    stringResource(R.string.main_runtime_missing)
                                }
                            )
                        }
                        Button(
                            onClick = onConnectClick,
                            enabled = state.hasBluetoothPermissions && !state.isDivoomConnected
                        ) {
                            Text(stringResource(R.string.main_connect_divoom))
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = onDisconnectMuseClick,
                            enabled = state.isMuseConnected
                        ) {
                            Text(stringResource(R.string.main_disconnect_muse))
                        }
                        Button(
                            onClick = onDisconnectClick,
                            enabled = state.isDivoomConnected
                        ) {
                            Text(stringResource(R.string.main_disconnect_divoom))
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = onSendFrameClick,
                            enabled = state.isDivoomConnected
                        ) {
                            Text(stringResource(R.string.main_send_frame))
                        }
                        Button(onClick = onAutoSendToggle) {
                            Text(
                                if (state.autoSendEnabled) {
                                    stringResource(R.string.main_auto_on)
                                } else {
                                    stringResource(R.string.main_auto_off)
                                }
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { onSendIntervalSelected(100L) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (state.sendIntervalMs == 100L) colors.selectedButton else colors.defaultButton
                            )
                        ) {
                            Text(stringResource(R.string.main_interval_100ms))
                        }
                        Button(
                            onClick = { onSendIntervalSelected(150L) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (state.sendIntervalMs == 150L) colors.selectedButton else colors.defaultButton
                            )
                        ) {
                            Text(stringResource(R.string.main_interval_150ms))
                        }
                        Button(
                            onClick = { onSendIntervalSelected(200L) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (state.sendIntervalMs == 200L) colors.selectedButton else colors.defaultButton
                            )
                        ) {
                            Text(stringResource(R.string.main_interval_200ms))
                        }
                    }
                }
            }

            Card(colors = cardColors) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.main_visualization_style),
                        color = colors.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { onModeSelected(DisplayMode.OSCILLOSCOPE) }) {
                            Text(stringResource(R.string.main_mode_oscilloscope))
                        }
                        Button(onClick = { onModeSelected(DisplayMode.VRCHAT_LOGO) }) {
                            Text(stringResource(R.string.main_mode_bubble))
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.previewPanel)
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                LedMatrixPreview(frame = state.frame)
            }

            Card(colors = cardColors) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.main_diagnostics),
                        color = colors.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(
                            R.string.main_diagnostics_normalized,
                            "%.2f".format(state.normalizedValue),
                            state.packetSizeBytes
                        ),
                        color = colors.diagnosticText
                    )
                    Text(
                        text = stringResource(
                            R.string.main_diagnostics_muse_raw,
                            state.museNotificationCount,
                            state.museEegNotificationCount,
                            state.museOtherNotificationCount,
                            state.museLastPacketBytes,
                            state.museEegSampleCount
                        ),
                        color = colors.diagnosticText
                    )
                    Text(
                        text = stringResource(
                            R.string.main_diagnostics_muse_bands,
                            "%.4f".format(state.museAlphaRatio),
                            "%.4f".format(state.museDominantRatio),
                            "%.6f".format(state.museTotalPower)
                        ),
                        color = colors.diagnosticText
                    )
                    Text(
                        text = stringResource(
                            R.string.main_diagnostics_muse_bio,
                            state.musePpgSampleCount,
                            "%.1f".format(state.museHeartBpm),
                            "%.1f".format(state.museOxygenPercent)
                        ),
                        color = colors.diagnosticText
                    )
                    Text(
                        text = stringResource(
                            R.string.main_diagnostics_muse_activity,
                            "%.4f".format(state.museActivity),
                            state.reconnectAttempt
                        ),
                        color = colors.diagnosticText
                    )
                    Text(
                        text = stringResource(R.string.main_diagnostics_packet_head, state.musePacketPreviewHex),
                        color = colors.diagnosticText
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = if (state.hasBluetoothPermissions) {
                    stringResource(R.string.main_ready)
                } else {
                    stringResource(R.string.main_permissions_required)
                },
                color = if (state.hasBluetoothPermissions) colors.readyText else colors.permissionWarning
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
