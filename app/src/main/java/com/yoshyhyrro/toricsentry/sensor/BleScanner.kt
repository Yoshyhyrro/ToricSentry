package com.yoshyhyrro.toricsentry.sensor

import com.yoshyhyrro.toricsentry.core.BleConstants
import com.yoshyhyrro.toricsentry.core.BleConnectionState
import com.yoshyhyrro.toricsentry.core.BleDeviceInfo
import com.yoshyhyrro.toricsentry.core.SensorReading
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * BLE スキャン層。
 * 生の電波を受け取り、チェックサム検証のみ行ってから
 * [onReadingReceived] コールバックへ渡す。
 *
 * フィルタリング（既知機器の除外など）は DeviceFilter が担当するため
 * このクラスは「受信して検証する」責務に専念する。
 */
class BleScanner(
    private val onReadingReceived: (SensorReading) -> Unit,
    private val scope: CoroutineScope,
    private val logger: (String) -> Unit = {}
) {
    private val devices = java.util.concurrent.ConcurrentHashMap<String, BleDeviceInfo>()

    fun startScan() {
        logger("BLE scan start: ${BleConstants.SERVICE_UUID}")
    }

    fun stopScan() {
        logger("BLE scan stop")
    }

    /**
     * BLE スキャン結果を受け取る。
     * RSSI が閾値を下回る場合は早期リターン（電波が弱すぎる）。
     */
    fun onDeviceFound(macAddress: String, deviceName: String, rssi: Int) {
        if (rssi < BleConstants.RSSI_THRESHOLD) {
            logger("[$macAddress] Skip weak RSSI: $rssi")
            return
        }
        if (devices.size >= BleConstants.MAX_CONNECTED_DEVICES) {
            logger("Max connected devices reached: ${BleConstants.MAX_CONNECTED_DEVICES}")
            return
        }
        if (!devices.containsKey(macAddress)) {
            val idx = devices.size
            val sensorId = "S_${idx / 4}_${idx % 4}"
            val info = BleDeviceInfo(
                macAddress = macAddress,
                deviceName = deviceName,
                sensorId = sensorId,
                connectionState = BleConnectionState.CONNECTING,
                lastRssi = rssi,
                lastSeenMs = System.currentTimeMillis()
            )
            devices[macAddress] = info
            logger("Found: $deviceName ($macAddress) -> $sensorId RSSI=$rssi")
            connectToDevice(macAddress)
        } else {
            devices[macAddress]?.apply {
                lastRssi = rssi
                lastSeenMs = System.currentTimeMillis()
            }
        }
    }

    private fun connectToDevice(macAddress: String) {
        logger("Connecting: $macAddress")
    }

    fun onConnected(macAddress: String) {
        devices[macAddress]?.connectionState = BleConnectionState.CONNECTED
        logger("Connected: $macAddress")
    }

    fun onSubscribed(macAddress: String) {
        devices[macAddress]?.connectionState = BleConnectionState.READY
        logger("Notification subscribed: $macAddress")
    }

    fun onDisconnected(macAddress: String) {
        devices[macAddress]?.connectionState = BleConnectionState.DISCONNECTED
        logger("Disconnected: $macAddress. Reconnect in ${BleConstants.RECONNECT_DELAY_MS}ms")
        scope.launch {
            delay(BleConstants.RECONNECT_DELAY_MS)
            connectToDevice(macAddress)
        }
    }

    /**
     * BLE GATT 通知から生バイト列を受け取りチェックサム検証、
     * 通過したものだけ [onReadingReceived] へ流す。
     */
    fun onRawDataReceived(macAddress: String, raw: ByteArray, rssi: Int) {
        val info = devices[macAddress] ?: return

        if (raw.size < 4) {
            logger("[$macAddress] Packet too short: ${raw.size}")
            return
        }

        val expected = (raw[0].toInt() xor raw[1].toInt() xor raw[2].toInt()) and 0xFF
        val actual = raw[3].toInt() and 0xFF
        if (expected != actual) {
            logger("[$macAddress] Checksum mismatch: expected=$expected actual=$actual")
            return
        }

        val reading = SensorReading(
            sensorId = info.sensorId,
            timestamp = System.currentTimeMillis(),
            value = raw[0].toInt() and 0x01,
            batteryLevel = raw[1].toInt() and 0xFF,
            rssi = rssi
        )

        info.lastRssi = rssi
        info.lastSeenMs = System.currentTimeMillis()
        info.connectionState = BleConnectionState.READY

        onReadingReceived(reading)
    }

    /** テスト・デモ用: 生バイト列を内部で組み立てて送出する */
    fun simulateReading(macAddress: String, value: Int, battery: Int = 90, rssi: Int = -50) {
        val checksum = (value xor battery xor 0x00) and 0xFF
        onRawDataReceived(
            macAddress = macAddress,
            raw = byteArrayOf(value.toByte(), battery.toByte(), 0x00, checksum.toByte()),
            rssi = rssi
        )
    }

    fun getReadyDevices(): List<BleDeviceInfo> =
        devices.values.filter { it.connectionState == BleConnectionState.READY }

    fun getAllDevices(): List<BleDeviceInfo> = devices.values.toList()
}
