package com.example.myoneproject

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var bleScanner: BluetoothLeScanner
    private lateinit var statusText: TextView

    private var bluetoothGatt: BluetoothGatt? = null
    private var cgmDevice: BluetoothDevice? = null

    // 📦 Список всех измерений
    private val measurements = mutableListOf<GlucoseMeasurement>()

    private val CGM_SERVICE_UUID =
        UUID.fromString("0000181f-0000-1000-8000-00805f9b34fb")

    private val CGM_MEASUREMENT_UUID =
        UUID.fromString("00002aa7-0000-1000-8000-00805f9b34fb")

    private val CCCD_UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // -------------------- DATA MODEL --------------------

    data class GlucoseMeasurement(
        val packetSize: Int,
        val flags: Int,
        val currentNA: Float,
        val temperature: Float,
        val timeOffset: Int,
        val alert: String?,
        val timestamp: Long
    )

    // -------------------- ACTIVITY --------------------

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) startScan()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)

        val bluetoothManager =
            getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

        bluetoothAdapter = bluetoothManager.adapter

        requestBlePermissions()

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    // -------------------- SCAN --------------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: return

            if (name.contains("MyCGM")) {
                cgmDevice = device
                bleScanner.stopScan(this)
                connectToDevice()
            }
        }
    }

    private fun startScan() {
        if (!bluetoothAdapter.isEnabled) {
            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(intent)
            return
        }

        if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN)
            != PackageManager.PERMISSION_GRANTED
        ) return

        bleScanner = bluetoothAdapter.bluetoothLeScanner
        bleScanner.startScan(scanCallback)
    }

    // -------------------- CONNECT --------------------

    private fun connectToDevice() {
        if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) return

        cgmDevice?.let {
            bluetoothGatt = it.connectGatt(this, false, gattCallback)
        }
    }

    // -------------------- GATT CALLBACK --------------------

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                gatt.close()
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return

            val service = gatt.getService(CGM_SERVICE_UUID) ?: return
            val measurementChar = service.getCharacteristic(CGM_MEASUREMENT_UUID) ?: return

            gatt.setCharacteristicNotification(measurementChar, true)

            val descriptor = measurementChar.getDescriptor(CCCD_UUID)
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {

            if (characteristic.uuid != CGM_MEASUREMENT_UUID) return

            val data = characteristic.value
            if (data.size < 8) return

            val packetSize = data[0].toInt() and 0xFF
            val flags = data[1].toInt() and 0xFF

            val currentRaw =
                (data[2].toInt() and 0xFF) or
                        ((data[3].toInt() and 0xFF) shl 8)

            val timeOffset =
                (data[4].toInt() and 0xFF) or
                        ((data[5].toInt() and 0xFF) shl 8)

            val tempRaw =
                (data[6].toInt() and 0xFF) or
                        ((data[7].toInt() and 0xFF) shl 8)

            val currentNA = decodeSFloat(currentRaw)
            val temperature = decodeSFloat(tempRaw)

            var alert: String? = null
            if (data.size > 8) {
                alert = when (data[8].toInt() and 0xFF) {
                    0x01 -> "LOW LEVEL"
                    0x02 -> "HIGH LEVEL"
                    else -> "UNKNOWN"
                }
            }

            val measurement = GlucoseMeasurement(
                packetSize = packetSize,
                flags = flags,
                currentNA = currentNA,
                temperature = temperature,
                timeOffset = timeOffset,
                alert = alert,
                timestamp = System.currentTimeMillis()
            )

            saveMeasurement(measurement)
            updateUi(measurement)
        }
    }

    // -------------------- STORAGE --------------------

    private fun saveMeasurement(measurement: GlucoseMeasurement) {
        measurements.add(measurement)
        Log.d("BLE", "Всего сохранено измерений: ${measurements.size}")
    }

    // -------------------- UI --------------------

    private fun updateUi(measurement: GlucoseMeasurement) {
        runOnUiThread {
            statusText.text = """
                Размер: ${measurement.packetSize}
                Флаги: ${measurement.flags}
                Ток: ${measurement.currentNA} nA
                Offset: ${measurement.timeOffset} мин
                Temp: ${measurement.temperature} °C
                Alert: ${measurement.alert}
            """.trimIndent()
        }
    }

    // -------------------- UTILS --------------------

    private fun decodeSFloat(value: Int): Float {
        val mantissa = value and 0x0FFF
        val exponent = value shr 12

        val signedMantissa =
            if (mantissa >= 0x0800) mantissa - 0x1000 else mantissa

        val signedExponent =
            if (exponent >= 0x0008) exponent - 0x0010 else exponent

        return (signedMantissa *
                Math.pow(10.0, signedExponent.toDouble())).toFloat()
    }

    // -------------------- PERMISSIONS --------------------

    private fun requestBlePermissions() {
        requestPermissions(
            arrayOf(
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ),
            100
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == 100) startScan()
    }
}



