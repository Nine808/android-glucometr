package com.example.myoneproject

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.ParcelUuid
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
    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                startScan()
            } else {
                Toast.makeText(this, "Bluetooth не включён", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)

        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, "BLE не поддерживается", Toast.LENGTH_SHORT).show()
            finish()
        }
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Toast.makeText(this, "Bluetooth выключен", Toast.LENGTH_SHORT).show()
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        requestBlePermissions()
    }

    private fun enableBluetooth() {
        val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        enableBluetoothLauncher.launch(enableBtIntent)
    }

    private var cgmDevice: BluetoothDevice? = null

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

        override fun onScanFailed(errorCode: Int) {
            Log.d("BLE", "Scan failed: $errorCode")
        }
    }

        private fun startScan() {

            Log.d("BLE", ">>> startScan вызван")

            if (!bluetoothAdapter.isEnabled) {
                enableBluetooth()
                return
            }

            if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Log.d("BLE", "Нет BLUETOOTH_SCAN")
                return
            }

            bleScanner = bluetoothAdapter.bluetoothLeScanner

            Log.d("BLE", "Scanner = $bleScanner")

            bleScanner.startScan(scanCallback)

            Log.d("BLE", "Сканирование запущено")
        }

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
                if (grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
                ) {
                    startScan()
                }
            }
        }

        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            super.onActivityResult(requestCode, resultCode, data)

            if (requestCode == 200 && resultCode == RESULT_OK) {
                startScan()
            }
        }

        private var bluetoothGatt: BluetoothGatt? = null

        private fun connectToDevice() {

            if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }

            cgmDevice?.let { device ->
                bluetoothGatt = device.connectGatt(this, false, gattCallback)
            }
        }

        private val gattCallback = object : BluetoothGattCallback() {

            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int
            ) {

                Log.d("BLE", "status=$status newState=$newState")

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.d("BLE", "Ошибка подключения, статус = $status")
                    runOnUiThread {
                        statusText.text = "Ошибка подключения: $status"
                    }
                    gatt.close()
                    return
                }

                if (newState == BluetoothProfile.STATE_CONNECTED) {

                    Log.d("BLE", "Подключено к CGM")

                    runOnUiThread {
                        statusText.text = "Подключено к CGM"
                    }

                    gatt.discoverServices()

                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {

                    Log.d("BLE", "Отключено")

                    runOnUiThread {
                        statusText.text = "Отключено"
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.d("BLE", "Ошибка поиска сервисов: $status")
                    return
                }

                Log.d("BLE", "===== СПИСОК СЕРВИСОВ =====")

                for (service in gatt.services) {
                    Log.d("BLE", "Service UUID: ${service.uuid}")

                    for (characteristic in service.characteristics) {
                        Log.d("BLE", "  └─ Characteristic UUID: ${characteristic.uuid}")
                    }
                }
            }
        }
    }



