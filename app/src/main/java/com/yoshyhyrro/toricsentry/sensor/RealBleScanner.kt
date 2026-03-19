package com.yoshyhyrro.toricsentry.sensor

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import com.yoshyhyrro.toricsentry.filter.DeviceFilter

/**
 * 実際の Android BLE API を使って周囲のデバイスをスキャンする。
 *
 * 検出したデバイスを [DeviceFilter] に通し、
 * - 無視すべき既知機器 → TRUSTED と Logcat へ記録
 * - 不明・不審な機器   → SUSPICIOUS と Logcat へ記録
 *
 * 呼び出し側で BLUETOOTH_SCAN パーミッションが許可済みであることを確認してから
 * [startScan] を呼ぶこと。
 */
@SuppressLint("MissingPermission")
class RealBleScanner(
    private val context: Context,
    private val filter: DeviceFilter,
    private val onResult: (line: String) -> Unit = {}
) {
    companion object {
        const val TAG = "ToricSentry.BLE"
    }

    private val leScanner by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter?.bluetoothLeScanner
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val mac = result.device.address
            val name = result.device.name?.takeIf { it.isNotBlank() } ?: "(unknown)"
            val rssi = result.rssi
            // Manufacturer Specific Data の最初のキーがベンダー ID (Company Identifier)
            val vendorId = result.scanRecord?.manufacturerSpecificData
                ?.takeIf { it.size() > 0 }
                ?.keyAt(0)

            val ignored = filter.shouldIgnore(mac, vendorId)
            val status = if (ignored) "TRUSTED/IGNORED" else "*** SUSPICIOUS ***"
            val vendorStr = vendorId?.let { "0x%04X".format(it) } ?: "none"

            val line = "[$status] $name | MAC=$mac | RSSI=$rssi dBm | Vendor=$vendorStr"
            Log.i(TAG, line)
            onResult(line)
        }

        override fun onScanFailed(errorCode: Int) {
            val msg = "BLE scan failed: errorCode=$errorCode"
            Log.e(TAG, msg)
            onResult("[ERROR] $msg")
        }
    }

    fun startScan() {
        val scanner = leScanner
        if (scanner == null) {
            val msg = "BluetoothLeScanner unavailable (Bluetooth OFF?)"
            Log.e(TAG, msg)
            onResult("[ERROR] $msg")
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(null, settings, callback)
        Log.i(TAG, "Real BLE scan started")
        onResult("[BLE] Scan started — watching for nearby devices...")
    }

    fun stopScan() {
        leScanner?.stopScan(callback)
        Log.i(TAG, "Real BLE scan stopped")
        onResult("[BLE] Scan stopped")
    }
}
