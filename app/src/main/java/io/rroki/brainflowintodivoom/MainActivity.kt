package io.rroki.brainflowintodivoom

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import io.rroki.brainflowintodivoom.data.bluetooth.BluetoothPermissionPolicy
import io.rroki.brainflowintodivoom.presentation.MainRoute

class MainActivity : ComponentActivity() {
    private var hasBluetoothPermissions by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasBluetoothPermissions = result.values.all { it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestBluetoothPermissionsIfNeeded()

        setContent {
            MainRoute(hasBluetoothPermissions = hasBluetoothPermissions)
        }
    }

    private fun requestBluetoothPermissionsIfNeeded() {
        val required = BluetoothPermissionPolicy.requiredPermissions()
        val allGranted = required.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            hasBluetoothPermissions = true
            return
        }

        permissionLauncher.launch(required)
    }
}
