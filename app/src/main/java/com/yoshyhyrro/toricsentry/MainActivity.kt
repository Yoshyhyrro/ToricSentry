package com.yoshyhyrro.toricsentry

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.yoshyhyrro.toricsentry.core.runDetectorDemo
import com.yoshyhyrro.toricsentry.core.runQDeformedDetectorDemo
import com.yoshyhyrro.toricsentry.filter.DeviceFilter
import com.yoshyhyrro.toricsentry.sensor.RealBleScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private var realScanner: RealBleScanner? = null
    private var isScanning = false

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val allGranted = grants.values.all { it }
            if (allGranted) startRealScan()
            else appendLog("権限が拒否されました。スキャンできません。")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val runButton        = findViewById<Button>(R.id.runDemoButton)
        val runQDeformedButton = findViewById<Button>(R.id.runQDeformedDemoButton)
        val scanButton       = findViewById<Button>(R.id.scanRealBleButton)
        val logText          = findViewById<TextView>(R.id.logText)

        runButton.setOnClickListener {
            runDemo(
                primaryButton = runButton,
                secondaryButton = runQDeformedButton,
                logText = logText,
                startMessage = "Running detector demo..."
            ) { logs -> runDetectorDemo { line -> logs.add(line) } }
        }

        runQDeformedButton.setOnClickListener {
            runDemo(
                primaryButton = runQDeformedButton,
                secondaryButton = runButton,
                logText = logText,
                startMessage = "Running Q-Deformed detector demo..."
            ) { logs -> runQDeformedDetectorDemo { line -> logs.add(line) } }
        }

        scanButton.setOnClickListener {
            if (isScanning) {
                stopRealScan(scanButton)
            } else {
                checkAndRequestBluetoothPermissions(scanButton)
            }
        }

        // logText 参照を保持してスキャン結果を追記できるようにする
        realScanner = RealBleScanner(
            context = this,
            filter  = DeviceFilter(),
            onResult = { line ->
                runOnUiThread {
                    logText.append("$line\n")
                }
            }
        )
    }

    // --- BLE 実スキャン ---

    private fun checkAndRequestBluetoothPermissions(scanButton: Button) {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN))
                    add(Manifest.permission.BLUETOOTH_SCAN)
                if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT))
                    add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION))
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        if (needed.isEmpty()) {
            startRealScan()
            updateScanButton(scanButton, scanning = true)
        } else {
            requestPermissions.launch(needed.toTypedArray())
        }
    }

    private fun startRealScan() {
        isScanning = true
        findViewById<Button>(R.id.scanRealBleButton).also {
            updateScanButton(it, scanning = true)
        }
        findViewById<TextView>(R.id.logText).text = "BLE スキャン中...\n"
        realScanner?.startScan()
    }

    private fun stopRealScan(scanButton: Button) {
        isScanning = false
        updateScanButton(scanButton, scanning = false)
        realScanner?.stopScan()
    }

    private fun updateScanButton(btn: Button, scanning: Boolean) {
        if (scanning) {
            btn.text = "■ BLE スキャン停止"
            btn.backgroundTintList = getColorStateList(android.R.color.holo_red_dark)
        } else {
            btn.text = "▶ BLE スキャン開始"
            btn.backgroundTintList = getColorStateList(android.R.color.holo_green_dark)
        }
    }

    private fun appendLog(line: String) {
        runOnUiThread { findViewById<TextView>(R.id.logText).append("$line\n") }
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    // --- デモ実行 ---

    private fun runDemo(
        primaryButton: Button,
        secondaryButton: Button,
        logText: TextView,
        startMessage: String,
        block: suspend (MutableList<String>) -> Unit
    ) {
        primaryButton.isEnabled = false
        secondaryButton.isEnabled = false
        logText.text = "$startMessage\n"

        lifecycleScope.launch(Dispatchers.Default) {
            val logs = mutableListOf<String>()
            block(logs)

            withContext(Dispatchers.Main) {
                logText.text = logs.joinToString(separator = "\n")
                primaryButton.isEnabled = true
                secondaryButton.isEnabled = true
            }
        }
    }
}

