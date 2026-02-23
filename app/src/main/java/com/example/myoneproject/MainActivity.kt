package com.example.myoneproject

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
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

    private val CGM_SERVICE_UUID =
        UUID.fromString("0000181f-0000-1000-8000-00805f9b34fb")

    private val CGM_MEASUREMENT_UUID =
        UUID.fromString("00002aa7-0000-1000-8000-00805f9b34fb")

    private val CCCD_UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                startScan()
            }
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

            Log.d("BLE", "Найдено: $name ${device.address}")

            if (name.contains("MyCGM")) {
                Log.d("BLE", "Это наш CGM!")
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

        Log.d("BLE", "Сканирование запущено")
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

            Log.d("BLE", "status=$status newState=$newState")

            if (status != BluetoothGatt.GATT_SUCCESS) {
                gatt.close()
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d("BLE", "Подключено")
                gatt.discoverServices()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {

            Log.d("BLE", "Services discovered status=$status")

            if (status != BluetoothGatt.GATT_SUCCESS) return

            val cgmService = gatt.getService(CGM_SERVICE_UUID)
            if (cgmService == null) {
                Log.d("BLE", "CGM сервис не найден")
                return
            }

            Log.d("BLE", "CGM сервис найден")

            val measurementChar =
                cgmService.getCharacteristic(CGM_MEASUREMENT_UUID)

            if (measurementChar == null) {
                Log.d("BLE", "CGM Measurement не найден")
                return
            }

            Log.d("BLE", "CGM Measurement найден")

            if (measurementChar.properties and
                BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            ) {

                gatt.setCharacteristicNotification(measurementChar, true)

                val descriptor =
                    measurementChar.getDescriptor(CCCD_UUID)

                descriptor.value =
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

                gatt.writeDescriptor(descriptor)

                Log.d("BLE", "Включаем Notify")
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            Log.d("BLE", "onDescriptorWrite status=$status")

            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("BLE", "Notify успешно включён")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {

            if (characteristic.uuid == CGM_MEASUREMENT_UUID) {

                val data = characteristic.value

                Log.d("BLE", "Получены данные: ${data.joinToString()}")

                runOnUiThread {
                    statusText.text =
                        "Данные: ${data.joinToString()}"
                }
            }
        }
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
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 100) {
            startScan()
        }
    }
}



