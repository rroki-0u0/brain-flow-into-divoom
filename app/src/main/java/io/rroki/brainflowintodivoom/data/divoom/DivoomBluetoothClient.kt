package io.rroki.brainflowintodivoom.data.divoom

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

class DivoomBluetoothClient(
    context: Context,
    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
) {
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private var socket: BluetoothSocket? = null

    @SuppressLint("MissingPermission")
    suspend fun connectByName(deviceName: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val adapter = bluetoothManager?.adapter ?: error("Bluetooth adapter is unavailable")
            check(adapter.isEnabled) { "Bluetooth is disabled" }

            val device = adapter.bondedDevices.firstOrNull { it.name.equals(deviceName, ignoreCase = true) }
                ?: error("Paired device not found: $deviceName")

            adapter.cancelDiscovery()
            val targetSocket = device.createRfcommSocketToServiceRecord(sppUuid)
            targetSocket.connect()
            socket = targetSocket
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
