package com.example.rfid

import android.Manifest
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
 
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.rfid.ui.theme.RFIDTheme
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RFIDTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    RfidScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

private val SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
private val UID_CHAR_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

@Composable
private fun RfidScreen(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as Activity

    var status by remember { mutableStateOf("Idle") }
    var uidText by remember { mutableStateOf("No UID") }
    var isScanning by remember { mutableStateOf(false) }
    val seenDevices = remember { mutableStateListOf<String>() }

    val bluetoothManager = remember {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }
    val bluetoothAdapter = remember { bluetoothManager.adapter }
    val scanner: BluetoothLeScanner? = remember { bluetoothAdapter?.bluetoothLeScanner }

    var gatt by remember { mutableStateOf<BluetoothGatt?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            try {
                gatt?.close()
            } catch (_: Throwable) {}
        }
    }

    fun hasBlePermissions(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return needed.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun requestBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
                ),
                100
            )
        } else {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                101
            )
        }
    }

    val scanCallback = remember {
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val record = result.scanRecord
                val advertisedName = record?.deviceName
                val name: String? = try {
                    // On Android 12+, device.name may require BLUETOOTH_CONNECT; prefer scanRecord name
                    advertisedName ?: device.name
                } catch (_: SecurityException) {
                    advertisedName
                }
                val label = (name ?: device.address ?: "unknown")
                if (label.isNotBlank()) {
                    if (seenDevices.size < 10 && !seenDevices.contains(label)) {
                        seenDevices.add(label)
                        status = "Scanning... Found: ${seenDevices.joinToString()}"
                    }
                }
                val uuids = record?.serviceUuids?.map { it.uuid } ?: emptyList()
                val matchesService = uuids.contains(SERVICE_UUID)
                val matchesName = (name ?: "").contains("ESP32-RFID", ignoreCase = true)
                if (matchesService || matchesName) {
                    status = "Device found: ${name ?: device.address}. Connecting..."
                    val connectPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.ACCESS_FINE_LOCATION
                    if (ContextCompat.checkSelfPermission(context, connectPerm) == PackageManager.PERMISSION_GRANTED) {
                        try { scanner?.stopScan(this) } catch (_: Exception) {}
                        isScanning = false
                        gatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
                            override fun onConnectionStateChange(gatt: BluetoothGatt, statusCode: Int, newState: Int) {
                                if (newState == BluetoothProfile.STATE_CONNECTED) {
                                    status = "Connected. Discovering services..."
                                    gatt.discoverServices()
                                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                                    status = "Disconnected"
                                }
                            }

                            override fun onServicesDiscovered(gatt: BluetoothGatt, statusCode: Int) {
                                val service = gatt.getService(SERVICE_UUID)
                                if (service == null) {
                                    status = "Service not found"
                                    return
                                }
                                val uidChar = service.getCharacteristic(UID_CHAR_UUID)
                                if (uidChar == null) {
                                    status = "UID characteristic not found"
                                    return
                                }
                                // Enable notifications
                                val ok = gatt.setCharacteristicNotification(uidChar, true)
                                val cccd = uidChar.getDescriptor(CCCD_UUID)
                                if (cccd != null) {
                                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                    gatt.writeDescriptor(cccd)
                                }
                                status = if (ok) "Subscribed. Waiting for UID..." else "Subscribe failed"
                                // Also try to read initial value
                                gatt.readCharacteristic(uidChar)
                            }

                            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                                if (characteristic.uuid == UID_CHAR_UUID) {
                                    val value = characteristic.getStringValue(0) ?: ""
                                    uidText = if (value.isNotBlank()) value else "No UID"
                                }
                            }

                            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, statusCode: Int) {
                                if (characteristic.uuid == UID_CHAR_UUID) {
                                    val value = characteristic.getStringValue(0) ?: ""
                                    uidText = if (value.isNotBlank()) value else "No UID"
                                }
                            }
                        })
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                status = "Scan failed: $errorCode"
                isScanning = false
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                for (r in results) {
                    onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, r)
                }
            }
        }
    }

    fun startScan() {
        if (!hasBlePermissions()) {
            requestBlePermissions()
            status = "Requesting permissions..."
            return
        }
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            status = "Bluetooth is off"
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        seenDevices.clear()
        status = "Scanning..."
        isScanning = true
        try {
            // Start an unfiltered scan like nRF Connect; filter in callback
            scanner?.startScan(null, settings, scanCallback)
        } catch (e: SecurityException) {
            status = "Missing permission"
            isScanning = false
            return
        }
        // Stop after 30 seconds if nothing found
        Handler(Looper.getMainLooper()).postDelayed({
            if (isScanning) {
                try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
                isScanning = false
                status = if (seenDevices.isEmpty()) "Not found. Tap to try again." else "Found: ${seenDevices.joinToString()} — No match yet"
            }
        }, 30_000)
    }

    

    

    val gradient = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
            MaterialTheme.colorScheme.surface
        )
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(gradient),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Status chip
                val chipColor = when {
                    status.contains("Connected", ignoreCase = true) -> MaterialTheme.colorScheme.secondaryContainer
                    status.contains("Scanning", ignoreCase = true) -> MaterialTheme.colorScheme.tertiaryContainer
                    status.contains("Not found", ignoreCase = true) -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
                Surface(
                    color = chipColor,
                    shape = RoundedCornerShape(50),
                    tonalElevation = 0.dp
                ) {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                Spacer(modifier = Modifier.padding(top = 16.dp))

                // UID heading
                Text(
                    text = uidText,
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontSize = 36.sp,
                        fontWeight = FontWeight.ExtraBold
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )

                Button(
                    onClick = { if (!isScanning) startScan() },
                    modifier = Modifier
                        .padding(top = 20.dp)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(text = if (isScanning) "Scanning..." else "Scan & Connect")
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewRfidScreen() {
    RFIDTheme { RfidScreen() }
}