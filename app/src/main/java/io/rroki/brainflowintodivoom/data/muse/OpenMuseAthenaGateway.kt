package io.rroki.brainflowintodivoom.data.muse

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import io.rroki.brainflowintodivoom.domain.model.BrainBand
import java.io.Closeable
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@SuppressLint("MissingPermission")
class OpenMuseAthenaGateway(
    context: Context
) : MuseStreamGateway, Closeable {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)

    private val lock = Any()
    private var streamProfile: MuseStreamProfile = MuseStreamProfile.EEG_ONLY
    private var gatt: BluetoothGatt? = null
    private var controlCharacteristic: BluetoothGattCharacteristic? = null
    private var eegCharacteristic: BluetoothGattCharacteristic? = null
    private var otherCharacteristic: BluetoothGattCharacteristic? = null

    private val eegSignal = ArrayDeque<Double>(MAX_SIGNAL_BUFFER)
    private val ppgRedSignal = ArrayDeque<Double>(MAX_PPG_BUFFER)
    private val ppgIrSignal = ArrayDeque<Double>(MAX_PPG_BUFFER)
    private val ppgAmbientSignal = ArrayDeque<Double>(MAX_PPG_BUFFER)
    private val eegPendingBytes = ArrayList<Byte>()
    private val otherPendingBytes = ArrayList<Byte>()
    private var latestDominantBand: BrainBand = BrainBand.ALPHA
    private var latestNormalizedAlpha: Double = 0.0
    private var latestDeltaPower: Double = 0.0
    private var latestThetaPower: Double = 0.0
    private var latestAlphaPower: Double = 0.0
    private var latestBetaPower: Double = 0.0
    private var latestGammaPower: Double = 0.0
    private var latestFocusScorePos: Double = 0.5
    private var latestRelaxScorePos: Double = 0.5
    private var latestActivity: Double = 0.0
    private var latestAlphaRatio: Double = 0.0
    private var latestDominantRatio: Double = 0.0
    private var latestTotalPower: Double = 0.0
    private var latestHeartBpm: Double = 0.0
    private var latestOxygenPercent: Double = 0.0
    private var latestNirsIndex: Double = 0.0
    private var notificationCount: Long = 0L
    private var eegNotificationCount: Long = 0L
    private var otherNotificationCount: Long = 0L
    private var lastNotificationBytes: Int = 0
    private var lastPacketPreviewHex: String = "-"

    override fun isRuntimeAvailable(): Boolean {
        val adapter = bluetoothManager?.adapter
        return adapter != null &&
            appContext.packageManager.hasSystemFeature("android.hardware.bluetooth_le") &&
            adapter.bluetoothLeScanner != null
    }

    override fun isConnected(): Boolean = synchronized(lock) {
        gatt != null && controlCharacteristic != null && eegCharacteristic != null
    }

    override fun configureStreamProfile(profile: MuseStreamProfile) {
        synchronized(lock) {
            streamProfile = profile
        }
    }

    override suspend fun connect(deviceAddress: String?): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            check(isRuntimeAvailable()) { "Bluetooth LE is unavailable on this device" }
            val profile = synchronized(lock) { streamProfile }

            disconnectInternal()

            val adapter = bluetoothManager?.adapter ?: error("Bluetooth adapter unavailable")
            check(adapter.isEnabled) { "Bluetooth is disabled" }

            val device = resolveMuseDevice(adapter, deviceAddress)
                ?: error("Muse device not found. Set Muse MAC Address and retry")

            val connectedGatt = connectGatt(device)
            synchronized(lock) {
                gatt = connectedGatt
            }

            val resolved = resolveCharacteristicsWithRetry(connectedGatt)
                ?: error(buildCharacteristicMissingError(connectedGatt))

            val control = resolved.control
            val eeg = resolved.eeg
            val other = resolved.other

            synchronized(lock) {
                controlCharacteristic = control
                eegCharacteristic = eeg
                otherCharacteristic = other
                eegSignal.clear()
                ppgRedSignal.clear()
                ppgIrSignal.clear()
                ppgAmbientSignal.clear()
                eegPendingBytes.clear()
                otherPendingBytes.clear()
                latestDominantBand = BrainBand.ALPHA
                latestNormalizedAlpha = 0.0
                latestDeltaPower = 0.0
                latestThetaPower = 0.0
                latestAlphaPower = 0.0
                latestBetaPower = 0.0
                latestGammaPower = 0.0
                latestFocusScorePos = 0.5
                latestRelaxScorePos = 0.5
                latestActivity = 0.0
                latestAlphaRatio = 0.0
                latestDominantRatio = 0.0
                latestTotalPower = 0.0
                latestHeartBpm = 0.0
                latestOxygenPercent = 0.0
                latestNirsIndex = 0.0
                notificationCount = 0L
                eegNotificationCount = 0L
                otherNotificationCount = 0L
                lastNotificationBytes = 0
                lastPacketPreviewHex = "-"
            }

            enableNotification(connectedGatt, eeg)
            if (profile.enableOtherNotifications) {
                enableNotification(connectedGatt, other)
            }
            enableNotification(connectedGatt, control)

            // OpenMuse-inspired handshake sequence.
            sendControlToken("v6")
            delay(200)
            sendControlToken("s")
            delay(200)
            sendControlToken("h")
            delay(200)
            sendControlToken(profile.presetToken)
            delay(200)
            sendControlToken("s")
            delay(200)
            sendControlToken("dc001")
            delay(50)
            sendControlToken("dc001")
            delay(100)
            if (profile.enableLowLatency) {
                sendControlToken("L1")
                delay(300)
            }
            sendControlToken("s")
        }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            disconnectInternal()
        }
    }

    override fun streamReadings(pollIntervalMs: Long): Flow<MuseReading> = flow {
        val interval = max(40L, pollIntervalMs)
        while (currentCoroutineContext().isActive) {
            val reading = synchronized(lock) {
                recomputeBiometricsLocked()
                if (eegSignal.isEmpty()) {
                    MuseReading(
                        normalizedAlpha = 0.0,
                        dominantBand = latestDominantBand,
                        deltaPower = latestDeltaPower,
                        thetaPower = latestThetaPower,
                        alphaPower = latestAlphaPower,
                        betaPower = latestBetaPower,
                        gammaPower = latestGammaPower,
                        focusScorePos = latestFocusScorePos,
                        relaxScorePos = latestRelaxScorePos,
                        heartBpm = latestHeartBpm,
                        oxygenPercent = latestOxygenPercent,
                        nirsIndex = latestNirsIndex,
                        ppgSampleCount = ppgIrSignal.size,
                        eegSampleCount = 0,
                        notificationCount = notificationCount,
                        eegNotificationCount = eegNotificationCount,
                        otherNotificationCount = otherNotificationCount,
                        latestPacketBytes = lastNotificationBytes,
                        alphaRatio = latestAlphaRatio,
                        dominantRatio = latestDominantRatio,
                        totalPower = latestTotalPower,
                        activity = latestActivity,
                        packetPreviewHex = lastPacketPreviewHex
                    )
                } else {
                    recomputeBandsLocked()
                    MuseReading(
                        normalizedAlpha = latestNormalizedAlpha,
                        dominantBand = latestDominantBand,
                        deltaPower = latestDeltaPower,
                        thetaPower = latestThetaPower,
                        alphaPower = latestAlphaPower,
                        betaPower = latestBetaPower,
                        gammaPower = latestGammaPower,
                        focusScorePos = latestFocusScorePos,
                        relaxScorePos = latestRelaxScorePos,
                        heartBpm = latestHeartBpm,
                        oxygenPercent = latestOxygenPercent,
                        nirsIndex = latestNirsIndex,
                        ppgSampleCount = ppgIrSignal.size,
                        eegSampleCount = eegSignal.size,
                        notificationCount = notificationCount,
                        eegNotificationCount = eegNotificationCount,
                        otherNotificationCount = otherNotificationCount,
                        latestPacketBytes = lastNotificationBytes,
                        alphaRatio = latestAlphaRatio,
                        dominantRatio = latestDominantRatio,
                        totalPower = latestTotalPower,
                        activity = latestActivity,
                        packetPreviewHex = lastPacketPreviewHex
                    )
                }
            }
            emit(reading)
            delay(interval)
        }
    }

    override fun close() {
        disconnectInternal()
    }

    private suspend fun resolveMuseDevice(adapter: BluetoothAdapter, configuredAddress: String?): BluetoothDevice? {
        val candidates = scanMuseDevices(adapter, timeoutMs = 8_000)
        val trimmed = configuredAddress?.trim().orEmpty()

        if (trimmed.isNotEmpty()) {
            val byScan = candidates.firstOrNull { candidate ->
                val addressMatch = candidate.address.equals(trimmed, ignoreCase = true)
                val name = candidate.name.orEmpty()
                val nameMatch = name.equals(trimmed, ignoreCase = true) ||
                    name.contains(trimmed, ignoreCase = true)
                addressMatch || nameMatch
            }
            if (byScan != null) {
                return byScan
            }

            val byAddress = runCatching { adapter.getRemoteDevice(trimmed) }.getOrNull()
            if (byAddress != null) {
                return byAddress
            }
        }

        val fromScan = candidates.firstOrNull { candidate ->
            candidate.name?.contains("muse", ignoreCase = true) == true
        }
        if (fromScan != null) {
            return fromScan
        }

        return adapter.bondedDevices.firstOrNull { device ->
            val name = device.name ?: return@firstOrNull false
            name.contains("muse", ignoreCase = true)
        }
    }

    private suspend fun scanMuseDevices(
        adapter: BluetoothAdapter,
        timeoutMs: Long
    ): List<BluetoothDevice> = withContext(Dispatchers.IO) {
        val scanner = adapter.bluetoothLeScanner ?: return@withContext emptyList()
        val devicesByAddress = linkedMapOf<String, BluetoothDevice>()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                val device = result?.device ?: return
                val name = device.name ?: return
                if (!name.contains("muse", ignoreCase = true)) {
                    return
                }
                devicesByAddress[device.address] = device
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results.orEmpty().forEach { result ->
                    val device = result.device ?: return@forEach
                    val name = device.name ?: return@forEach
                    if (!name.contains("muse", ignoreCase = true)) {
                        return@forEach
                    }
                    devicesByAddress[device.address] = device
                }
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        runCatching {
            scanner.startScan(null, settings, callback)
            delay(timeoutMs)
        }

        runCatching { scanner.stopScan(callback) }

        return@withContext devicesByAddress.values.toList()
    }

    private suspend fun connectGatt(device: BluetoothDevice): BluetoothGatt {
        return suspendCancellableCoroutine { continuation ->
            var discovered = false

            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (!continuation.isActive) {
                        return
                    }

                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        runCatching { gatt.close() }
                        continuation.resumeWithException(
                            IllegalStateException("Muse GATT connection failed with status=$status")
                        )
                        return
                    }

                    if (newState == BluetoothGatt.STATE_CONNECTED) {
                        if (!gatt.discoverServices()) {
                            runCatching { gatt.close() }
                            continuation.resumeWithException(
                                IllegalStateException("Failed to start service discovery")
                            )
                        }
                    } else if (newState == BluetoothGatt.STATE_DISCONNECTED && !discovered) {
                        runCatching { gatt.close() }
                        continuation.resumeWithException(
                            IllegalStateException("Muse disconnected before service discovery")
                        )
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (!continuation.isActive) {
                        return
                    }
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        runCatching { gatt.close() }
                        continuation.resumeWithException(
                            IllegalStateException("Service discovery failed with status=$status")
                        )
                        return
                    }
                    discovered = true
                    continuation.resume(gatt)
                }

                @Deprecated("Deprecated in Java")
                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic
                ) {
                    @Suppress("DEPRECATION")
                    val uuid = characteristic.uuid
                    @Suppress("DEPRECATION")
                    val value = characteristic.value ?: return
                    onNotification(uuid, value)
                }

                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray
                ) {
                    onNotification(characteristic.uuid, value)
                }
            }

            val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
            } else {
                @Suppress("DEPRECATION")
                device.connectGatt(appContext, false, callback)
            }

            continuation.invokeOnCancellation {
                runCatching { gatt.close() }
            }
        }
    }

    private suspend fun resolveCharacteristicsWithRetry(gatt: BluetoothGatt): MuseCharacteristics? {
        repeat(3) { attempt ->
            val resolved = resolveCharacteristics(gatt)
            if (resolved != null) {
                return resolved
            }

            if (attempt < 2) {
                runCatching { gatt.discoverServices() }
                delay(300)
            }
        }

        return null
    }

    private fun resolveCharacteristics(gatt: BluetoothGatt): MuseCharacteristics? {
        var control: BluetoothGattCharacteristic? = null
        var eeg: BluetoothGattCharacteristic? = null
        var other: BluetoothGattCharacteristic? = null

        gatt.services?.forEach { service: BluetoothGattService ->
            service.characteristics?.forEach { characteristic ->
                when (characteristic.uuid) {
                    UUID_CONTROL -> control = control ?: characteristic
                    UUID_EEG -> eeg = eeg ?: characteristic
                    UUID_OTHER -> other = other ?: characteristic
                }
            }
        }

        return if (control != null && eeg != null && other != null) {
            MuseCharacteristics(control = control!!, eeg = eeg!!, other = other!!)
        } else {
            null
        }
    }

    private fun buildCharacteristicMissingError(gatt: BluetoothGatt): String {
        val serviceUuids = gatt.services
            ?.map { it.uuid.toString() }
            ?.sorted()
            ?.joinToString()
            ?: "none"

        val characteristicUuids = gatt.services
            ?.flatMap { service -> service.characteristics?.map { it.uuid.toString() } ?: emptyList() }
            ?.distinct()
            ?.sorted()
            ?.joinToString()
            ?: "none"

        return "Muse service/characteristics not found. services=[$serviceUuids], chars=[$characteristicUuids]"
    }

    private fun onNotification(uuid: UUID, value: ByteArray) {
        if (uuid != UUID_EEG && uuid != UUID_OTHER) {
            return
        }

        synchronized(lock) {
            notificationCount += 1
            if (uuid == UUID_EEG) {
                eegNotificationCount += 1
            } else if (uuid == UUID_OTHER) {
                otherNotificationCount += 1
            }
            lastNotificationBytes = value.size
            lastPacketPreviewHex = value
                .take(12)
                .joinToString(separator = " ") { byte -> "%02X".format(byte.toInt() and 0xFF) }
                .ifBlank { "-" }
        }

        val completePacketPayloads = synchronized(lock) {
            val pending = if (uuid == UUID_EEG) eegPendingBytes else otherPendingBytes
            pending.ensureCapacity(pending.size + value.size)
            value.forEach { pending.add(it) }
            drainCompletePacketsLocked(pending)
        }

        if (completePacketPayloads.isEmpty()) {
            return
        }
        val combined = completePacketPayloads.fold(ByteArray(0)) { acc, packet ->
            if (acc.isEmpty()) {
                packet
            } else {
                acc + packet
            }
        }

        val subpackets = parseSubpackets(combined)
        var appendedSamplesFromSubpackets = 0
        var handledStructuredPacket = false
        if (subpackets.isNotEmpty()) {
            synchronized(lock) {
                subpackets.forEach { subpacket ->
                    when (subpacket.tag) {
                        TAG_EEG_4,
                        TAG_EEG_8 -> {
                            val nChannels = if (subpacket.tag == TAG_EEG_4) 4 else 8
                            val decoded = decodeEegData(subpacket.data, nChannels) ?: return@forEach
                            appendedSamplesFromSubpackets += appendDecodedSamplesLocked(decoded)
                            handledStructuredPacket = true
                        }

                        TAG_OPTICS_4,
                        TAG_OPTICS_8,
                        TAG_OPTICS_16 -> {
                            val nChannels = when (subpacket.tag) {
                                TAG_OPTICS_4 -> 4
                                TAG_OPTICS_8 -> 8
                                else -> 16
                            }
                            val optics = decodeOpticsData(subpacket.data, nChannels) ?: return@forEach
                            appendOpticsSamplesLocked(optics, nChannels)
                            handledStructuredPacket = true
                        }
                    }
                }
            }
            if (handledStructuredPacket && appendedSamplesFromSubpackets > 0) {
                return
            }
            if (handledStructuredPacket && streamProfile.enableOtherNotifications) {
                return
            }
        }

        if (uuid == UUID_OTHER) {
            val decodedOptics = decodeOpticsFromRawPayload(combined)
            if (decodedOptics.isNotEmpty()) {
                synchronized(lock) {
                    decodedOptics.forEach { decoded ->
                        appendOpticsSamplesLocked(decoded.samples, decoded.nChannels)
                    }
                }
                return
            }
        }

        // Fallback for firmware variants where EEG payload comes unwrapped.
        val rawDecoded = decodeFromRawPayload(combined)
        if (rawDecoded.isEmpty()) {
            return
        }

        synchronized(lock) {
            rawDecoded.forEach { decoded ->
                appendDecodedSamplesLocked(decoded)
            }
        }
    }

    private fun appendDecodedSamplesLocked(decoded: Array<DoubleArray>): Int {
        var appended = 0
        for (sampleIdx in decoded.indices) {
            val row = decoded[sampleIdx]
            if (row.isEmpty()) {
                continue
            }
            val mean = row.average()
            if (eegSignal.size >= MAX_SIGNAL_BUFFER) {
                eegSignal.removeFirst()
            }
            eegSignal.addLast(mean)
            appended += 1
        }
        return appended
    }

    private fun decodeFromRawPayload(payload: ByteArray): List<Array<DoubleArray>> {
        if (payload.isEmpty()) {
            return emptyList()
        }

        val decoded = ArrayList<Array<DoubleArray>>()

        // Format A: [TAG][4-byte subheader][28-byte EEG], possibly repeated.
        if (payload.size >= SUBPACKET_HEADER_SIZE + 28) {
            var offset = 0
            while (offset + SUBPACKET_HEADER_SIZE + 28 <= payload.size) {
                val tag = payload[offset].toInt() and 0xFF
                if (tag != TAG_EEG_4 && tag != TAG_EEG_8) {
                    break
                }
                val nChannels = if (tag == TAG_EEG_4) 4 else 8
                val start = offset + SUBPACKET_HEADER_SIZE
                val end = start + 28
                decodeEegData(payload.copyOfRange(start, end), nChannels)?.let { decoded.add(it) }
                offset = end
            }
            if (decoded.isNotEmpty()) {
                return decoded
            }
        }

        // Format B: plain 28-byte EEG chunks with no tag/header.
        if (payload.size >= 28) {
            var offset = 0
            while (offset + 28 <= payload.size) {
                val chunk = payload.copyOfRange(offset, offset + 28)
                val eeg8 = decodeEegData(chunk, 8)
                if (eeg8 != null && hasSignalVariance(eeg8)) {
                    decoded.add(eeg8)
                } else {
                    val eeg4 = decodeEegData(chunk, 4)
                    if (eeg4 != null && hasSignalVariance(eeg4)) {
                        decoded.add(eeg4)
                    }
                }
                offset += 28
            }
        }

        return decoded
    }

    private fun decodeOpticsFromRawPayload(payload: ByteArray): List<DecodedOpticsPacket> {
        if (payload.isEmpty()) {
            return emptyList()
        }

        val decoded = ArrayList<DecodedOpticsPacket>()

        // Format A: [TAG][4-byte subheader][payload], repeated.
        if (payload.size >= SUBPACKET_HEADER_SIZE + 30) {
            var offset = 0
            while (offset + SUBPACKET_HEADER_SIZE + 30 <= payload.size) {
                val tag = payload[offset].toInt() and 0xFF
                val nChannels = when (tag) {
                    TAG_OPTICS_4 -> 4
                    TAG_OPTICS_8 -> 8
                    TAG_OPTICS_16 -> 16
                    else -> break
                }
                val bytesNeeded = if (nChannels == 4) 30 else 40
                if (offset + SUBPACKET_HEADER_SIZE + bytesNeeded > payload.size) {
                    break
                }

                val dataStart = offset + SUBPACKET_HEADER_SIZE
                val dataEnd = dataStart + bytesNeeded
                val data = payload.copyOfRange(dataStart, dataEnd)
                decodeOpticsData(data, nChannels)?.let { samples ->
                    if (hasOpticsVariance(samples)) {
                        decoded.add(DecodedOpticsPacket(samples = samples, nChannels = nChannels))
                    }
                }
                offset = dataEnd
            }

            if (decoded.isNotEmpty()) {
                return decoded
            }
        }

        // Format B: plain 30-byte (4ch) or 40-byte (8/16ch) chunks.
        var cursor = 0
        while (cursor + 30 <= payload.size) {
            var consumed = false

            decodeOpticsData(payload.copyOfRange(cursor, cursor + 30), 4)?.let { samples ->
                if (hasOpticsVariance(samples)) {
                    decoded.add(DecodedOpticsPacket(samples = samples, nChannels = 4))
                    cursor += 30
                    consumed = true
                }
            }
            if (consumed) {
                continue
            }

            if (cursor + 40 <= payload.size) {
                val chunk40 = payload.copyOfRange(cursor, cursor + 40)
                val decoded16 = decodeOpticsData(chunk40, 16)
                if (decoded16 != null && hasOpticsVariance(decoded16)) {
                    decoded.add(DecodedOpticsPacket(samples = decoded16, nChannels = 16))
                    cursor += 40
                    continue
                }

                val decoded8 = decodeOpticsData(chunk40, 8)
                if (decoded8 != null && hasOpticsVariance(decoded8)) {
                    decoded.add(DecodedOpticsPacket(samples = decoded8, nChannels = 8))
                    cursor += 40
                    continue
                }
            }

            cursor += 1
        }

        return decoded
    }

    private fun hasSignalVariance(decoded: Array<DoubleArray>): Boolean {
        var sum = 0.0
        var sumSq = 0.0
        var count = 0

        for (row in decoded) {
            for (value in row) {
                sum += value
                sumSq += value * value
                count += 1
            }
        }

        if (count <= 1) {
            return false
        }

        val mean = sum / count.toDouble()
        val variance = (sumSq / count.toDouble()) - (mean * mean)
        return variance > 1e-6
    }

    private fun hasOpticsVariance(decoded: Array<DoubleArray>): Boolean {
        var sum = 0.0
        var sumSq = 0.0
        var count = 0

        decoded.forEach { row ->
            row.forEach { value ->
                sum += value
                sumSq += value * value
                count += 1
            }
        }

        if (count <= 1) {
            return false
        }

        val mean = sum / count.toDouble()
        val variance = (sumSq / count.toDouble()) - (mean * mean)
        return variance > 1e-10
    }

    private fun drainCompletePacketsLocked(pending: MutableList<Byte>): List<ByteArray> {
        val packets = ArrayList<ByteArray>()

        if (pending.size > MAX_PENDING_BYTES) {
            val drop = pending.size - MAX_PENDING_BYTES
            repeat(drop) { pending.removeAt(0) }
        }

        while (pending.isNotEmpty()) {
            val packetLength = pending[0].toInt() and 0xFF
            if (packetLength < PACKET_HEADER_SIZE || packetLength > MAX_PACKET_LENGTH) {
                pending.removeAt(0)
                continue
            }

            if (pending.size < packetLength) {
                break
            }

            val packet = ByteArray(packetLength)
            for (index in 0 until packetLength) {
                packet[index] = pending[index]
            }
            repeat(packetLength) { pending.removeAt(0) }
            packets.add(packet)
        }

        return packets
    }

    private fun parseSubpackets(payload: ByteArray): List<MuseSubpacket> {
        val result = ArrayList<MuseSubpacket>()
        var offset = 0

        while (offset < payload.size) {
            if (offset + PACKET_HEADER_SIZE > payload.size) {
                break
            }

            val packetLength = payload[offset].toInt() and 0xFF
            if (packetLength <= PACKET_HEADER_SIZE || offset + packetLength > payload.size) {
                break
            }

            val packet = payload.copyOfRange(offset, offset + packetLength)
            val packetTag = packet[9].toInt() and 0xFF
            val packetData = packet.copyOfRange(PACKET_HEADER_SIZE, packet.size)
            val firstDataLength = tagDataLength(packetTag)

            var dataOffset = 0
            if (firstDataLength > 0 && dataOffset + firstDataLength <= packetData.size) {
                result.add(
                    MuseSubpacket(
                        tag = packetTag,
                        data = packetData.copyOfRange(dataOffset, dataOffset + firstDataLength)
                    )
                )
                dataOffset += firstDataLength
            }

            while (dataOffset + SUBPACKET_HEADER_SIZE <= packetData.size) {
                val subTag = packetData[dataOffset].toInt() and 0xFF
                val subDataLength = tagDataLength(subTag)
                if (subDataLength == 0 || dataOffset + SUBPACKET_HEADER_SIZE + subDataLength > packetData.size) {
                    break
                }

                val dataStart = dataOffset + SUBPACKET_HEADER_SIZE
                result.add(
                    MuseSubpacket(
                        tag = subTag,
                        data = packetData.copyOfRange(dataStart, dataStart + subDataLength)
                    )
                )

                dataOffset += SUBPACKET_HEADER_SIZE + subDataLength
            }

            offset += packetLength
        }

        return result
    }

    private fun tagDataLength(tag: Int): Int {
        return when (tag) {
            TAG_EEG_4, TAG_EEG_8 -> 28
            TAG_ACC_GYRO -> 36
            TAG_OPTICS_4 -> 30
            TAG_OPTICS_8, TAG_OPTICS_16 -> 40
            TAG_BATTERY_88 -> 188
            TAG_BATTERY_98 -> 20
            else -> 0
        }
    }

    private fun decodeEegData(dataBytes: ByteArray, nChannels: Int): Array<DoubleArray>? {
        if (dataBytes.size < 28) {
            return null
        }

        val nSamples = if (nChannels == 4) 4 else 2
        val bits = bytesToBits(dataBytes, 28)
        val decoded = Array(nSamples) { DoubleArray(nChannels) }

        for (sampleIndex in 0 until nSamples) {
            for (channelIndex in 0 until nChannels) {
                val bitStart = (sampleIndex * nChannels + channelIndex) * 14
                val intValue = extractPackedInt(bits, bitStart, 14)
                decoded[sampleIndex][channelIndex] = intValue * EEG_SCALE_UV
            }
        }

        return decoded
    }

    private fun decodeOpticsData(dataBytes: ByteArray, nChannels: Int): Array<DoubleArray>? {
        val nSamples: Int
        val bytesNeeded: Int
        when (nChannels) {
            4 -> {
                nSamples = 3
                bytesNeeded = 30
            }

            8 -> {
                nSamples = 2
                bytesNeeded = 40
            }

            16 -> {
                nSamples = 1
                bytesNeeded = 40
            }

            else -> return null
        }

        if (dataBytes.size < bytesNeeded) {
            return null
        }

        val bits = bytesToBits(dataBytes, bytesNeeded)
        val decoded = Array(nSamples) { DoubleArray(nChannels) }

        for (sampleIndex in 0 until nSamples) {
            for (channelIndex in 0 until nChannels) {
                val bitStart = (sampleIndex * nChannels + channelIndex) * 20
                val intValue = extractPackedInt(bits, bitStart, 20)
                decoded[sampleIndex][channelIndex] = intValue * OPTICS_SCALE
            }
        }

        return decoded
    }

    private fun appendOpticsSamplesLocked(samples: Array<DoubleArray>, nChannels: Int) {
        samples.forEach { row ->
            if (row.isEmpty()) {
                return@forEach
            }

            val ir = when (nChannels) {
                4 -> (row[2] + row[3]) / 2.0
                8 -> (row[2] + row[3] + row[6] + row[7]) / 4.0
                else -> {
                    var sum = 0.0
                    for (index in OPTICS_IR_INDICES) {
                        sum += row[index]
                    }
                    sum / OPTICS_IR_INDICES.size.toDouble()
                }
            }

            val red = if (nChannels >= 16) {
                var sum = 0.0
                for (index in OPTICS_RED_INDICES) {
                    sum += row[index]
                }
                sum / OPTICS_RED_INDICES.size.toDouble()
            } else {
                ir
            }

            val ambient = if (nChannels >= 16) {
                var sum = 0.0
                for (index in OPTICS_AMB_INDICES) {
                    sum += row[index]
                }
                sum / OPTICS_AMB_INDICES.size.toDouble()
            } else {
                0.0
            }

            if (ppgIrSignal.size >= MAX_PPG_BUFFER) {
                ppgIrSignal.removeFirst()
            }
            if (ppgRedSignal.size >= MAX_PPG_BUFFER) {
                ppgRedSignal.removeFirst()
            }
            if (ppgAmbientSignal.size >= MAX_PPG_BUFFER) {
                ppgAmbientSignal.removeFirst()
            }

            ppgIrSignal.addLast(ir)
            ppgRedSignal.addLast(red)
            ppgAmbientSignal.addLast(ambient)
        }
    }

    private fun recomputeBiometricsLocked() {
        if (ppgIrSignal.size < MIN_PPG_WINDOW) {
            return
        }

        val windowSize = min(ppgIrSignal.size, PPG_HEART_WINDOW)
        val irWindow = recentSamplesLocked(ppgIrSignal, windowSize)
        val redWindow = recentSamplesLocked(ppgRedSignal, windowSize)
        val ambWindow = recentSamplesLocked(ppgAmbientSignal, windowSize)

        val centeredIr = centerSignal(irWindow)
        val centeredRed = centerSignal(redWindow)
        val bpmEstimate = estimateHeartBpm(centeredIr)
        if (bpmEstimate > 0.0) {
            latestHeartBpm = if (latestHeartBpm <= 0.0) {
                bpmEstimate
            } else {
                (latestHeartBpm * 0.85) + (bpmEstimate * 0.15)
            }
        }

        val irMean = irWindow.average().coerceAtLeast(1e-9)
        val redMean = redWindow.average().coerceAtLeast(1e-9)
        val ambMean = if (ambWindow.isNotEmpty()) ambWindow.average() else 0.0

        val irDc = irWindow.map { sample -> abs(sample) }.average().coerceAtLeast(1e-9)
        val redDc = redWindow.map { sample -> abs(sample) }.average().coerceAtLeast(1e-9)
        val irAc = signalStd(centeredIr).coerceAtLeast(1e-9)
        val redAc = signalStd(centeredRed).coerceAtLeast(1e-9)

        val ratioR = ((redAc / redDc) / (irAc / irDc)).coerceIn(0.2, 3.0)
        val spo2Percent = (110.0 - (25.0 * ratioR)).coerceIn(75.0, 100.0)

        val contrastProxy = (1.05 - (redMean / irMean)).coerceIn(0.0, 1.0)
        val oxygenProxy = if (spo2Percent.isFinite()) {
            (spo2Percent / 100.0).coerceIn(0.0, 1.0)
        } else {
            contrastProxy
        }
        latestOxygenPercent = (latestOxygenPercent * 0.85) + (oxygenProxy * 0.15)

        val hemoContrast = ((irMean - ambMean) / (irMean + ambMean + 1e-9)).coerceIn(-1.0, 1.0)
        val nirs = ((hemoContrast + 1.0) / 2.0).coerceIn(0.0, 1.0)
        latestNirsIndex = (latestNirsIndex * 0.85) + (nirs * 0.15)
    }

    private fun recentSamplesLocked(source: ArrayDeque<Double>, count: Int): DoubleArray {
        if (count <= 0 || source.isEmpty()) {
            return DoubleArray(0)
        }

        val start = (source.size - count).coerceAtLeast(0)
        val out = DoubleArray(source.size - start)
        var readIndex = 0
        var writeIndex = 0
        source.forEach { value ->
            if (readIndex >= start) {
                out[writeIndex++] = value
            }
            readIndex += 1
        }
        return out
    }

    private fun estimateHeartBpm(centeredIrSignal: DoubleArray): Double {
        if (centeredIrSignal.size < MIN_PPG_WINDOW) {
            return 0.0
        }

        val mean = centeredIrSignal.average()
        var varianceSum = 0.0
        centeredIrSignal.forEach { value ->
            val diff = value - mean
            varianceSum += diff * diff
        }
        val std = sqrt(varianceSum / centeredIrSignal.size.toDouble()).coerceAtLeast(1e-9)
        val threshold = mean + (std * 0.2)
        val minPeakDistance = max(1, (PPG_SAMPLE_RATE * 60 / MAX_HEART_BPM).toInt())

        var lastPeak = -minPeakDistance
        var peakCount = 0
        for (index in 1 until centeredIrSignal.size - 1) {
            val current = centeredIrSignal[index]
            if (current > threshold &&
                current >= centeredIrSignal[index - 1] &&
                current > centeredIrSignal[index + 1] &&
                index - lastPeak >= minPeakDistance
            ) {
                peakCount += 1
                lastPeak = index
            }
        }

        val seconds = centeredIrSignal.size.toDouble() / PPG_SAMPLE_RATE.toDouble()
        if (seconds <= 0.0) {
            return 0.0
        }

        return (peakCount * 60.0 / seconds).coerceIn(MIN_HEART_BPM, MAX_HEART_BPM)
    }

    private fun bytesToBits(dataBytes: ByteArray, nBytes: Int): IntArray {
        val actual = min(nBytes, dataBytes.size)
        val bits = IntArray(actual * 8)
        var cursor = 0
        for (i in 0 until actual) {
            val value = dataBytes[i].toInt() and 0xFF
            for (bitPos in 0 until 8) {
                bits[cursor++] = (value shr bitPos) and 0x01
            }
        }
        return bits
    }

    private fun extractPackedInt(bits: IntArray, bitStart: Int, bitWidth: Int): Int {
        var value = 0
        for (bitIndex in 0 until bitWidth) {
            val sourceIndex = bitStart + bitIndex
            if (sourceIndex in bits.indices && bits[sourceIndex] == 1) {
                value = value or (1 shl bitIndex)
            }
        }
        return value
    }

    private fun recomputeBandsLocked() {
        val signal = eegSignal.toDoubleArray()
        if (signal.size < MIN_SIGNAL_WINDOW) {
            latestNormalizedAlpha = 0.0
            latestDominantBand = BrainBand.ALPHA
            latestDeltaPower = 0.0
            latestThetaPower = 0.0
            latestAlphaPower = 0.0
            latestBetaPower = 0.0
            latestGammaPower = 0.0
            latestFocusScorePos = 0.5
            latestRelaxScorePos = 0.5
            return
        }

        val centered = centerSignal(signal)
        val bandPowers = mapOf(
            BrainBand.DELTA to calculateBandPower(centered, EEG_SAMPLE_RATE, 1.0, 4.0),
            BrainBand.THETA to calculateBandPower(centered, EEG_SAMPLE_RATE, 4.0, 8.0),
            BrainBand.ALPHA to calculateBandPower(centered, EEG_SAMPLE_RATE, 8.0, 12.0),
            BrainBand.BETA to calculateBandPower(centered, EEG_SAMPLE_RATE, 12.0, 30.0),
            BrainBand.GAMMA to calculateBandPower(centered, EEG_SAMPLE_RATE, 30.0, 45.0)
        )

        val alpha = bandPowers[BrainBand.ALPHA] ?: 0.0
        val theta = bandPowers[BrainBand.THETA] ?: 0.0
        val beta = bandPowers[BrainBand.BETA] ?: 0.0
        val dominantEntry = bandPowers.maxByOrNull { entry -> entry.value }
        latestDominantBand = dominantEntry?.key ?: BrainBand.ALPHA
        val dominantPower = dominantEntry?.value ?: 0.0

        val totalPower = bandPowers.values.sum()
        latestTotalPower = totalPower
        if (totalPower > 1e-9) {
            latestDeltaPower = (bandPowers[BrainBand.DELTA] ?: 0.0) / totalPower
            latestThetaPower = theta / totalPower
            latestAlphaPower = alpha / totalPower
            latestBetaPower = beta / totalPower
            latestGammaPower = (bandPowers[BrainBand.GAMMA] ?: 0.0) / totalPower

            val alphaRatio = (alpha / totalPower).coerceIn(0.0, 1.0)
            val dominantRatio = (dominantPower / totalPower).coerceIn(0.0, 1.0)
            latestAlphaRatio = alphaRatio
            latestDominantRatio = dominantRatio
            latestNormalizedAlpha = ((alphaRatio * 0.55) + (dominantRatio * 0.45)).coerceIn(0.0, 1.0)

            val focusSigned = tanhLogRatio(beta, theta, 1.1)
            val relaxSigned = tanhLogRatio(alpha, theta, 1.1)
            latestFocusScorePos = ((focusSigned + 1.0) / 2.0).coerceIn(0.0, 1.0)
            latestRelaxScorePos = ((relaxSigned + 1.0) / 2.0).coerceIn(0.0, 1.0)
            return
        }

        // Spectral fallback: derive activity from RMS and slope when FFT bins collapse.
        val rms = sqrt(centered.map { sample -> sample * sample }.average())
        val slope = if (centered.size <= 1) {
            0.0
        } else {
            var sumDiff = 0.0
            for (index in 1 until centered.size) {
                sumDiff += abs(centered[index] - centered[index - 1])
            }
            sumDiff / (centered.size - 1).toDouble()
        }
        val instantActivity = rms + (slope * 0.7)
        latestActivity = (latestActivity * 0.85) + (instantActivity * 0.15)
        latestDeltaPower = 0.0
        latestThetaPower = 0.0
        latestAlphaPower = 0.0
        latestBetaPower = 0.0
        latestGammaPower = 0.0
        latestFocusScorePos = 0.5
        latestRelaxScorePos = 0.5
        latestAlphaRatio = 0.0
        latestDominantRatio = 0.0
        latestNormalizedAlpha = (latestActivity / (latestActivity + 35.0)).coerceIn(0.0, 1.0)
    }

    private fun tanhLogRatio(numerator: Double, denominator: Double, scale: Double): Double {
        val safeDenominator = denominator.coerceAtLeast(1e-9)
        val safeRatio = (numerator / safeDenominator).coerceAtLeast(1e-9)
        return kotlin.math.tanh(scale * kotlin.math.ln(safeRatio))
    }

    private fun centerSignal(signal: DoubleArray): DoubleArray {
        if (signal.isEmpty()) {
            return signal
        }

        val mean = signal.sum() / signal.size
        return DoubleArray(signal.size) { index -> signal[index] - mean }
    }

    private fun signalStd(signal: DoubleArray): Double {
        if (signal.isEmpty()) {
            return 0.0
        }
        val mean = signal.average()
        var variance = 0.0
        signal.forEach { value ->
            val diff = value - mean
            variance += diff * diff
        }
        return sqrt(variance / signal.size.toDouble())
    }

    private fun calculateBandPower(
        signal: DoubleArray,
        sampleRate: Int,
        lowHz: Double,
        highHz: Double
    ): Double {
        val n = signal.size
        if (n <= 1 || sampleRate <= 0) {
            return 0.0
        }

        val frequencyResolution = sampleRate.toDouble() / n.toDouble()
        val startBin = max(1, ceil(lowHz / frequencyResolution).toInt())
        val maxNyquistBin = n / 2
        val endBin = min(maxNyquistBin, floor(highHz / frequencyResolution).toInt())
        if (endBin < startBin) {
            return 0.0
        }

        var power = 0.0
        for (k in startBin..endBin) {
            var real = 0.0
            var imaginary = 0.0
            for (t in signal.indices) {
                val angle = (2.0 * PI * k * t) / n
                val sample = signal[t]
                real += sample * cos(angle)
                imaginary -= sample * sin(angle)
            }
            power += real * real + imaginary * imaginary
        }

        return power
    }

    private suspend fun sendControlToken(token: String) {
        val characteristic = synchronized(lock) { controlCharacteristic }
            ?: error("Muse control characteristic unavailable")
        val currentGatt = synchronized(lock) { gatt }
            ?: error("Muse GATT is disconnected")

        val ascii = (token + "\n").encodeToByteArray()
        require(ascii.size <= 255) { "Muse control token is too long" }

        val payload = ByteArray(1 + ascii.size)
        payload[0] = ascii.size.toByte()
        System.arraycopy(ascii, 0, payload, 1, ascii.size)

        writeCharacteristic(currentGatt, characteristic, payload)
    }

    private suspend fun enableNotification(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        val notificationSet = gatt.setCharacteristicNotification(characteristic, true)
        check(notificationSet) { "Failed to enable notification for ${characteristic.uuid}" }

        val descriptor = characteristic.getDescriptor(UUID_CLIENT_CHARACTERISTIC_CONFIG)
            ?: return

        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val started = gatt.writeDescriptor(descriptor, value)
            check(started == BluetoothGatt.GATT_SUCCESS) {
                "Failed to write CCC descriptor for ${characteristic.uuid}"
            }
        } else {
            @Suppress("DEPRECATION")
            run {
                descriptor.value = value
                val started = gatt.writeDescriptor(descriptor)
                check(started) { "Failed to write CCC descriptor for ${characteristic.uuid}" }
            }
        }

        delay(50)
    }

    private suspend fun writeCharacteristic(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        payload: ByteArray
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = gatt.writeCharacteristic(
                characteristic,
                payload,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            )
            check(status == BluetoothGatt.GATT_SUCCESS) {
                "Failed to write Muse command status=$status"
            }
            return
        }

        @Suppress("DEPRECATION")
        run {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            characteristic.value = payload
            check(gatt.writeCharacteristic(characteristic)) {
                "Failed to write Muse command"
            }
        }
    }

    private fun disconnectInternal() {
        val previousGatt = synchronized(lock) {
            val current = gatt
            gatt = null
            controlCharacteristic = null
            eegCharacteristic = null
            otherCharacteristic = null
            eegSignal.clear()
            ppgRedSignal.clear()
            ppgIrSignal.clear()
            ppgAmbientSignal.clear()
            eegPendingBytes.clear()
            otherPendingBytes.clear()
            latestDominantBand = BrainBand.ALPHA
            latestNormalizedAlpha = 0.0
            latestDeltaPower = 0.0
            latestThetaPower = 0.0
            latestAlphaPower = 0.0
            latestBetaPower = 0.0
            latestGammaPower = 0.0
            latestFocusScorePos = 0.5
            latestRelaxScorePos = 0.5
            latestActivity = 0.0
            latestAlphaRatio = 0.0
            latestDominantRatio = 0.0
            latestTotalPower = 0.0
            latestHeartBpm = 0.0
            latestOxygenPercent = 0.0
            latestNirsIndex = 0.0
            notificationCount = 0L
            eegNotificationCount = 0L
            otherNotificationCount = 0L
            lastNotificationBytes = 0
            lastPacketPreviewHex = "-"
            current
        } ?: return

        runCatching { previousGatt.disconnect() }
        runCatching { previousGatt.close() }
    }

    private data class MuseSubpacket(
        val tag: Int,
        val data: ByteArray
    )

    private data class MuseCharacteristics(
        val control: BluetoothGattCharacteristic,
        val eeg: BluetoothGattCharacteristic,
        val other: BluetoothGattCharacteristic
    )

    private data class DecodedOpticsPacket(
        val samples: Array<DoubleArray>,
        val nChannels: Int
    )

    private companion object {
        private const val DEFAULT_PRESET = "p1041"

        private const val PACKET_HEADER_SIZE = 14
        private const val SUBPACKET_HEADER_SIZE = 5
        private const val MIN_SIGNAL_WINDOW = 64
        private const val MAX_SIGNAL_BUFFER = 512
        private const val EEG_SAMPLE_RATE = 256
        private const val EEG_SCALE_UV = 1450.0 / 16383.0
        private const val OPTICS_SCALE = 1.0 / 32768.0
        private const val PPG_SAMPLE_RATE = 64
        private const val MIN_PPG_WINDOW = 96
        private const val PPG_HEART_WINDOW = PPG_SAMPLE_RATE * 6
        private const val MAX_PPG_BUFFER = PPG_SAMPLE_RATE * 120
        private const val MIN_HEART_BPM = 40.0
        private const val MAX_HEART_BPM = 180.0

        private const val TAG_EEG_4 = 0x11
        private const val TAG_EEG_8 = 0x12
        private const val TAG_OPTICS_4 = 0x34
        private const val TAG_OPTICS_8 = 0x35
        private const val TAG_OPTICS_16 = 0x36
        private const val TAG_ACC_GYRO = 0x47
        private const val TAG_BATTERY_88 = 0x88
        private const val TAG_BATTERY_98 = 0x98
        private const val MAX_PACKET_LENGTH = 255
        private const val MAX_PENDING_BYTES = 4096

        private val OPTICS_IR_INDICES = intArrayOf(2, 3, 6, 7)
        private val OPTICS_RED_INDICES = intArrayOf(8, 9, 12, 13)
        private val OPTICS_AMB_INDICES = intArrayOf(10, 11, 14, 15)

        private val UUID_MUSE_SERVICE: UUID = UUID.fromString("273e0000-4c4d-454d-96be-f03bac821358")
        private val UUID_CONTROL: UUID = UUID.fromString("273e0001-4c4d-454d-96be-f03bac821358")
        private val UUID_EEG: UUID = UUID.fromString("273e0013-4c4d-454d-96be-f03bac821358")
        private val UUID_OTHER: UUID = UUID.fromString("273e0014-4c4d-454d-96be-f03bac821358")
        private val UUID_CLIENT_CHARACTERISTIC_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
