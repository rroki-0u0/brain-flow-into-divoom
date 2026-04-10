package io.rroki.brainflowintodivoom.data.divoom

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class DivoomDiscoveredDevice(
    val name: String,
    val address: String,
    val isBonded: Boolean
)

class DivoomBluetoothClient(
    context: Context,
    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
) {
    private val appContext = context.applicationContext
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private var socket: BluetoothSocket? = null
    private val knownDivoomKeywords = listOf("divoom", "pixoo", "timebox", "ditoo", "slingbag", "backpack")

    @SuppressLint("MissingPermission")
    suspend fun discoverDivoomDevices(timeoutMs: Long = 15_000L): Result<List<DivoomDiscoveredDevice>> = withContext(Dispatchers.IO) {
        runCatching {
            val adapter = bluetoothManager?.adapter ?: error("Bluetooth adapter is unavailable")
            check(adapter.isEnabled) { "Bluetooth is disabled" }

            val devicesByAddress = linkedMapOf<String, DivoomDiscoveredDevice>()

            adapter.bondedDevices
                .forEach { device ->
                    val displayName = device.name?.takeIf { it.isNotBlank() } ?: "(unknown)"
                    devicesByAddress[device.address] = DivoomDiscoveredDevice(
                        name = displayName,
                        address = device.address,
                        isBonded = true
                    )
                }

            discoverDevices(adapter = adapter, discoveryTimeoutMs = timeoutMs) { foundDevice ->
                val displayName = foundDevice.name?.takeIf { it.isNotBlank() } ?: "(unknown)"
                devicesByAddress[foundDevice.address] = DivoomDiscoveredDevice(
                    name = displayName,
                    address = foundDevice.address,
                    isBonded = adapter.bondedDevices.any { it.address == foundDevice.address }
                )
            }

            devicesByAddress.values
                .sortedWith(
                    compareBy<DivoomDiscoveredDevice> { !isLikelyDivoomDevice(it.name) }
                        .thenBy { !it.isBonded }
                        .thenBy { it.name.lowercase() }
                )
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun connectByName(deviceName: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val adapter = bluetoothManager?.adapter ?: error("Bluetooth adapter is unavailable")
            check(adapter.isEnabled) { "Bluetooth is disabled" }

            val device = findDevice(adapter, deviceName = deviceName, deviceAddress = null)
                ?: error("Divoom device not found: $deviceName")

            adapter.cancelDiscovery()
            runCatching { socket?.close() }
            socket = null

            val targetSocket = connectWithFallback(device)
            socket = targetSocket
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun connectBySelection(deviceName: String, deviceAddress: String?): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val adapter = bluetoothManager?.adapter ?: error("Bluetooth adapter is unavailable")
            check(adapter.isEnabled) { "Bluetooth is disabled" }

            val device = findDevice(adapter, deviceName = deviceName, deviceAddress = deviceAddress)
                ?: error("Divoom device not found: $deviceName")

            adapter.cancelDiscovery()
            runCatching { socket?.close() }
            socket = null

            val targetSocket = connectWithFallback(device)
            socket = targetSocket
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun findDevice(
        adapter: BluetoothAdapter,
        deviceName: String,
        deviceAddress: String?
    ): BluetoothDevice? {
        val normalizedAddress = deviceAddress?.trim().orEmpty()
        if (normalizedAddress.isNotEmpty()) {
            val byAddress = runCatching { adapter.getRemoteDevice(normalizedAddress) }.getOrNull()
            if (byAddress != null) {
                return byAddress
            }
        }

        return findDeviceByName(adapter, deviceName)
    }

    @SuppressLint("MissingPermission")
    private suspend fun findDeviceByName(
        adapter: BluetoothAdapter,
        deviceName: String,
        discoveryTimeoutMs: Long = 15_000L
    ): BluetoothDevice? {
        val pairedMatch = adapter.bondedDevices.firstOrNull { matchesPreferredName(it.name, deviceName) }
            ?: adapter.bondedDevices.firstOrNull { isLikelyDivoomDevice(it.name) }
        if (pairedMatch != null) {
            return pairedMatch
        }

        var matched: BluetoothDevice? = null
        var fallbackDevice: BluetoothDevice? = null
        discoverDevices(adapter = adapter, discoveryTimeoutMs = discoveryTimeoutMs) { discovered ->
            if (matched == null && matchesPreferredName(discovered.name, deviceName)) {
                matched = discovered
                return@discoverDevices
            }
            if (fallbackDevice == null && isLikelyDivoomDevice(discovered.name)) {
                fallbackDevice = discovered
            }
        }

        return matched ?: fallbackDevice
    }

    @SuppressLint("MissingPermission")
    private suspend fun discoverDevices(
        adapter: BluetoothAdapter,
        discoveryTimeoutMs: Long,
        onFound: (BluetoothDevice) -> Unit
    ): Boolean = withTimeoutOrNull(discoveryTimeoutMs) {
        suspendCancellableCoroutine { continuation ->
            var receiver: BroadcastReceiver? = null
            var receiverRegistered = false
            var cleanedUp = false

            fun cleanup() {
                if (cleanedUp) {
                    return
                }
                cleanedUp = true
                if (receiverRegistered) {
                    runCatching { appContext.unregisterReceiver(receiver) }
                }
                runCatching { adapter.cancelDiscovery() }
            }

            receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        BluetoothDevice.ACTION_FOUND -> {
                            val device = intent.extractBluetoothDevice() ?: return
                            onFound(device)
                        }

                        BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                            cleanup()
                            if (continuation.isActive) {
                                continuation.resume(true)
                            }
                        }
                    }
                }
            }

            continuation.invokeOnCancellation {
                cleanup()
            }

            runCatching {
                val intentFilter = IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_FOUND)
                    addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                }
                registerReceiverCompat(receiver, intentFilter)
                receiverRegistered = true

                val discoveryStarted = when {
                    adapter.isDiscovering -> true
                    adapter.startDiscovery() -> true
                    else -> {
                        // Some devices occasionally fail the first attempt; retry once.
                        runCatching { adapter.cancelDiscovery() }
                        adapter.startDiscovery()
                    }
                }

                if (!discoveryStarted) {
                    cleanup()
                    if (continuation.isActive) {
                        continuation.resume(false)
                    }
                }
            }.onFailure { throwable ->
                cleanup()
                if (continuation.isActive) {
                    continuation.resumeWithException(throwable)
                }
            }
        }
    } ?: false

    @SuppressLint("MissingPermission")
    private fun connectWithFallback(device: BluetoothDevice): BluetoothSocket {
        val secureSocket = device.createRfcommSocketToServiceRecord(sppUuid)
        return runCatching {
            secureSocket.connect()
            secureSocket
        }.getOrElse { secureError ->
            runCatching { secureSocket.close() }

            val insecureSocket = device.createInsecureRfcommSocketToServiceRecord(sppUuid)
            runCatching {
                insecureSocket.connect()
                insecureSocket
            }.getOrElse { insecureError ->
                runCatching { insecureSocket.close() }
                throw IOException("Failed to connect to Divoom over RFCOMM", insecureError).apply {
                    addSuppressed(secureError)
                }
            }
        }
    }

    private fun registerReceiverCompat(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(receiver, filter)
        }
    }

    private fun matchesPreferredName(actualName: String?, expectedName: String): Boolean {
        if (actualName.isNullOrBlank() || expectedName.isBlank()) {
            return false
        }
        val normalizedActualName = actualName.trim()
        val normalizedExpectedName = expectedName.trim()
        if (
            normalizedActualName.equals(normalizedExpectedName, ignoreCase = true) ||
            normalizedActualName.contains(normalizedExpectedName, ignoreCase = true) ||
            normalizedExpectedName.contains(normalizedActualName, ignoreCase = true)
        ) {
            return true
        }

        val canonicalActual = canonicalizeDeviceName(normalizedActualName)
        val canonicalExpected = canonicalizeDeviceName(normalizedExpectedName)
        if (
            canonicalActual == canonicalExpected ||
            canonicalActual.contains(canonicalExpected) ||
            canonicalExpected.contains(canonicalActual)
        ) {
            return true
        }

        val actualTokens = tokenizeDeviceName(normalizedActualName)
        val expectedTokens = tokenizeDeviceName(normalizedExpectedName)
        return actualTokens.isNotEmpty() && expectedTokens.isNotEmpty() &&
            actualTokens.intersect(expectedTokens).isNotEmpty()
    }

    private fun canonicalizeDeviceName(name: String): String {
        return name.lowercase().filter { it.isLetterOrDigit() }
    }

    private fun tokenizeDeviceName(name: String): Set<String> {
        return name
            .lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 4 }
            .toSet()
    }

    private fun isLikelyDivoomDevice(deviceName: String?): Boolean {
        if (deviceName.isNullOrBlank()) {
            return false
        }
        return knownDivoomKeywords.any { keyword ->
            deviceName.contains(keyword, ignoreCase = true)
        }
    }

    private fun Intent.extractBluetoothDevice(): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    suspend fun disconnect(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            socket?.close()
            socket = null
        }
    }

    suspend fun send(payload: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val current = socket ?: error("Divoom socket is not connected")
            current.outputStream.write(payload)
            current.outputStream.flush()
        }.onFailure {
            if (it is IOException) {
                runCatching { socket?.close() }
                socket = null
            }
        }
    }

    fun isConnected(): Boolean = socket?.isConnected == true

    fun close() {
        runCatching { socket?.close() }
        socket = null
    }
}
