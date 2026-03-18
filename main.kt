/**
 * main.kt — ToricCode Skimming Detector (BLE版)
 *
 * SerialPortManager を BleDeviceManager に完全置き換え。
 * Android の BluetoothLeScanner / BluetoothGatt API を想定した設計。
 *
 * 必要な Android パーミッション (AndroidManifest.xml):
 *   <uses-permission android:name="android.permission.BLUETOOTH_SCAN"
 *                    android:usesPermissionFlags="neverForLocation" />
 *   <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
 *   <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />  ← API 30 以下
 *
 * build.gradle:
 *   implementation "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3"
 */

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.Channel
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

// ─────────────────────────────────────────────────────────────────────────────
// BLE 関連定数
// ─────────────────────────────────────────────────────────────────────────────

object BleConstants {
    // スキミング検出センサー用カスタム GATT UUID（独自定義）
    val SERVICE_UUID: UUID        = UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB")
    val CHAR_SENSOR_UUID: UUID    = UUID.fromString("0000FFF1-0000-1000-8000-00805F9B34FB")
    val CHAR_BATTERY_UUID: UUID   = UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB")
    val DESCRIPTOR_CCC_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    const val SCAN_PERIOD_MS        = 10_000L  // スキャン継続時間
    const val RSSI_THRESHOLD        = -80      // これ以下のデバイスは無視（dBm）
    const val RECONNECT_DELAY_MS    = 3_000L   // 切断後の再接続待機時間
    const val MAX_CONNECTED_DEVICES = 16       // 最大同時接続数（4×4グリッド分）
}

// ─────────────────────────────────────────────────────────────────────────────
// データモデル
// ─────────────────────────────────────────────────────────────────────────────

data class SensorReading(
    val sensorId: String,
    val timestamp: Long,
    val value: Int,          // 0 or 1
    val batteryLevel: Int,
    val rssi: Int
)

data class GridPosition(val x: Int, val y: Int)

data class SensorNode(
    val position: GridPosition,
    val sensorId: String,
    @Volatile var lastReading: SensorReading? = null,
    @Volatile var isActive: Boolean = true,
    val neighbors: List<GridPosition> = emptyList()
)

data class ParityCheck(
    val topLeft: GridPosition,
    val topRight: GridPosition,
    val bottomLeft: GridPosition,
    val bottomRight: GridPosition,
    val expectedParity: Int = 0,
    val actualParity: Int,
    val isViolated: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

data class SkimmingEvent(
    val eventId: String,
    val detectedAt: Long,
    val violatedParityChecks: List<ParityCheck>,
    val affectedSensors: List<GridPosition>,
    val severity: SeverityLevel,
    var status: EventStatus
)

enum class SeverityLevel {
    LOW, MEDIUM, HIGH, CRITICAL;
    operator fun compareTo(other: SeverityLevel) = ordinal - other.ordinal
}

enum class EventStatus {
    DETECTED, INVESTIGATING, CONFIRMED, FALSE_ALARM, RESOLVED
}

// ─────────────────────────────────────────────────────────────────────────────
// BLE デバイス管理
// ─────────────────────────────────────────────────────────────────────────────

/** BLE デバイス1台の接続状態 */
data class BleDeviceInfo(
    val macAddress: String,         // BLE MAC アドレス（デバイスID）
    val deviceName: String,
    val sensorId: String,           // グリッド上のセンサーID にマッピング
    var connectionState: BleConnectionState = BleConnectionState.DISCONNECTED,
    var lastRssi: Int = 0,
    var lastSeenMs: Long = 0L
)

enum class BleConnectionState {
    SCANNING,
    CONNECTING,
    CONNECTED,
    SUBSCRIBING,   // Notification 有効化中
    READY,         // データ受信可能
    DISCONNECTED,
    ERROR
}

/**
 * BleDeviceManager
 *
 * Android の BluetoothLeScanner / BluetoothGatt を抽象化したマネージャー。
 * このファイルはJVMテスト可能なロジック層なので、
 * Android API 依存部分はインターフェース経由で差し込む設計にしている。
 */
class BleDeviceManager(
    private val onReadingReceived: (SensorReading) -> Unit,
    private val scope: CoroutineScope
) {
    private val devices = ConcurrentHashMap<String, BleDeviceInfo>()

    // ─── スキャン ─────────────────────────────────────────────────────────────

    /**
     * BLE スキャン開始（Android 実装では BluetoothLeScanner.startScan() を呼ぶ）
     *
     * Android 実装例:
     * ```kotlin
     * val scanner = bluetoothAdapter.bluetoothLeScanner
     * val filter = ScanFilter.Builder()
     *     .setServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
     *     .build()
     * val settings = ScanSettings.Builder()
     *     .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
     *     .build()
     * scanner.startScan(listOf(filter), settings, scanCallback)
     * ```
     */
    fun startScan() {
        println("🔍 BLE スキャン開始 (SERVICE_UUID=${BleConstants.SERVICE_UUID})")
        // Android 実装: BluetoothLeScanner.startScan() をここで呼ぶ
    }

    fun stopScan() {
        println("⏹ BLE スキャン停止")
        // Android 実装: BluetoothLeScanner.stopScan() をここで呼ぶ
    }

    // ─── デバイス発見コールバック（Android の ScanCallback から呼ばれる） ───────

    /**
     * Android 実装例 (Activity/Fragment で):
     * ```kotlin
     * private val scanCallback = object : ScanCallback() {
     *     override fun onScanResult(callbackType: Int, result: ScanResult) {
     *         bleDeviceManager.onDeviceFound(
     *             macAddress = result.device.address,
     *             deviceName = result.device.name ?: "Unknown",
     *             rssi       = result.rssi
     *         )
     *     }
     * }
     * ```
     */
    fun onDeviceFound(macAddress: String, deviceName: String, rssi: Int) {
        if (rssi < BleConstants.RSSI_THRESHOLD) {
            println("📡 [$macAddress] RSSI $rssi dBm — 弱すぎるためスキップ")
            return
        }
        if (devices.size >= BleConstants.MAX_CONNECTED_DEVICES) {
            println("⚠️ 最大接続数 (${BleConstants.MAX_CONNECTED_DEVICES}) に達しています")
            return
        }
        if (!devices.containsKey(macAddress)) {
            // MAC → sensorId のマッピング（実運用では設定ファイル等から読む）
            val idx      = devices.size
            val sensorId = "S_${idx / 4}_${idx % 4}"
            val info = BleDeviceInfo(
                macAddress      = macAddress,
                deviceName      = deviceName,
                sensorId        = sensorId,
                connectionState = BleConnectionState.CONNECTING,
                lastRssi        = rssi,
                lastSeenMs      = System.currentTimeMillis()
            )
            devices[macAddress] = info
            println("✅ デバイス発見: $deviceName ($macAddress) → $sensorId  RSSI=$rssi")
            connectToDevice(macAddress)
        } else {
            devices[macAddress]?.apply {
                lastRssi   = rssi
                lastSeenMs = System.currentTimeMillis()
            }
        }
    }

    // ─── 接続 ─────────────────────────────────────────────────────────────────

    /**
     * GATT 接続（Android 実装では device.connectGatt() を呼ぶ）
     *
     * Android 実装例:
     * ```kotlin
     * val gatt = device.connectGatt(context, false, gattCallback, TRANSPORT_LE)
     * ```
     */
    private fun connectToDevice(macAddress: String) {
        println("🔗 接続中: $macAddress")
        // Android 実装: BluetoothDevice.connectGatt() をここで呼ぶ
    }

    /**
     * Android GATT コールバック実装例:
     * ```kotlin
     * private val gattCallback = object : BluetoothGattCallback() {
     *
     *     override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
     *         when (newState) {
     *             BluetoothProfile.STATE_CONNECTED    -> {
     *                 bleDeviceManager.onConnected(gatt.device.address)
     *                 gatt.discoverServices()
     *             }
     *             BluetoothProfile.STATE_DISCONNECTED -> {
     *                 bleDeviceManager.onDisconnected(gatt.device.address)
     *             }
     *         }
     *     }
     *
     *     override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
     *         if (status == BluetoothGatt.GATT_SUCCESS) {
     *             val char = gatt
     *                 .getService(BleConstants.SERVICE_UUID)
     *                 ?.getCharacteristic(BleConstants.CHAR_SENSOR_UUID)
     *             char?.let {
     *                 gatt.setCharacteristicNotification(it, true)
     *                 val descriptor = it.getDescriptor(BleConstants.DESCRIPTOR_CCC_UUID)
     *                 descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
     *                 gatt.writeDescriptor(descriptor)
     *                 bleDeviceManager.onSubscribed(gatt.device.address)
     *             }
     *         }
     *     }
     *
     *     // Notification で値が来るたびに呼ばれる
     *     override fun onCharacteristicChanged(
     *         gatt: BluetoothGatt,
     *         characteristic: BluetoothGattCharacteristic
     *     ) {
     *         if (characteristic.uuid == BleConstants.CHAR_SENSOR_UUID) {
     *             bleDeviceManager.onRawDataReceived(
     *                 macAddress = gatt.device.address,
     *                 raw        = characteristic.value,
     *                 rssi       = /* 最後に取得した RSSI */ cachedRssi
     *             )
     *         }
     *     }
     * }
     * ```
     */
    fun onConnected(macAddress: String) {
        devices[macAddress]?.connectionState = BleConnectionState.CONNECTED
        println("🟢 接続完了: $macAddress → サービス探索中...")
        // Android 実装: ここで gatt.discoverServices() → onServicesDiscovered → Notification 登録
    }

    fun onSubscribed(macAddress: String) {
        devices[macAddress]?.connectionState = BleConnectionState.READY
        println("🔔 Notification 登録完了: $macAddress")
    }

    fun onDisconnected(macAddress: String) {
        devices[macAddress]?.connectionState = BleConnectionState.DISCONNECTED
        println("🔴 切断: $macAddress — ${BleConstants.RECONNECT_DELAY_MS}ms後に再接続")
        scope.launch {
            delay(BleConstants.RECONNECT_DELAY_MS)
            connectToDevice(macAddress)
        }
    }

    // ─── データ受信 ───────────────────────────────────────────────────────────

    /**
     * GATT Notification から生バイト列を受け取って SensorReading に変換する。
     *
     * バイト列プロトコル（独自定義、4バイト固定長）:
     *   Byte[0] : センサー値     (0x00 or 0x01)
     *   Byte[1] : バッテリー残量  (0–100)
     *   Byte[2] : フラグ         (将来拡張用)
     *   Byte[3] : チェックサム   (Byte[0] XOR Byte[1] XOR Byte[2])
     */
    fun onRawDataReceived(macAddress: String, raw: ByteArray, rssi: Int) {
        val info = devices[macAddress] ?: return

        if (raw.size < 4) {
            println("⚠️ [$macAddress] パケット短すぎ: ${raw.size} bytes")
            return
        }

        // チェックサム検証
        val expected = (raw[0].toInt() xor raw[1].toInt() xor raw[2].toInt()) and 0xFF
        val actual   = raw[3].toInt() and 0xFF
        if (expected != actual) {
            println("⚠️ [$macAddress] チェックサム不一致 (expected=$expected actual=$actual)")
            return
        }

        val reading = SensorReading(
            sensorId     = info.sensorId,
            timestamp    = System.currentTimeMillis(),
            value        = raw[0].toInt() and 0x01,
            batteryLevel = raw[1].toInt() and 0xFF,
            rssi         = rssi
        )

        info.lastRssi        = rssi
        info.lastSeenMs      = System.currentTimeMillis()
        info.connectionState = BleConnectionState.READY

        onReadingReceived(reading)
    }

    // ─── テスト用ヘルパー ─────────────────────────────────────────────────────

    /** テスト用: 仮想デバイスからのデータ受信をシミュレート */
    fun simulateReading(macAddress: String, value: Int, battery: Int = 90, rssi: Int = -50) {
        val checksum = (value xor battery xor 0x00) and 0xFF
        onRawDataReceived(
            macAddress = macAddress,
            raw        = byteArrayOf(value.toByte(), battery.toByte(), 0x00, checksum.toByte()),
            rssi       = rssi
        )
    }

    fun getReadyDevices(): List<BleDeviceInfo> =
        devices.values.filter { it.connectionState == BleConnectionState.READY }

    fun getAllDevices(): List<BleDeviceInfo> = devices.values.toList()
}

// ─────────────────────────────────────────────────────────────────────────────
// 検出エンジン（トーリックコード）
// ─────────────────────────────────────────────────────────────────────────────

class ToricCodeSkimmingDetector(
    private val gridWidth: Int,
    private val gridHeight: Int,
    private val detectionWindowMs: Long = 5_000L,
    private val historyMaxSize: Int = 1_000
) {
    private val sensorGrid: Array<Array<SensorNode?>> =
        Array(gridWidth) { Array(gridHeight) { null } }

    private val sensorIndex       = ConcurrentHashMap<String, GridPosition>()
    private val parityCheckHistory = CopyOnWriteArrayList<ParityCheck>()
    private val eventList          = CopyOnWriteArrayList<SkimmingEvent>()

    private val _eventFlow    = MutableSharedFlow<SkimmingEvent>(extraBufferCapacity = 64)
    val eventFlow: SharedFlow<SkimmingEvent> = _eventFlow.asSharedFlow()

    private val readingChannel  = Channel<SensorReading>(capacity = Channel.BUFFERED)
    private val processingScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
        processingScope.launch {
            for (reading in readingChannel) processReading(reading)
        }
    }

    fun registerSensor(sensorId: String, position: GridPosition) {
        val (x, y) = position
        require(x in 0 until gridWidth && y in 0 until gridHeight)
        sensorGrid[x][y] = SensorNode(position, sensorId,
            neighbors = calculateNeighbors(position))
        sensorIndex[sensorId] = position
    }

    private fun calculateNeighbors(pos: GridPosition) = buildList {
        for (dx in -1..1) for (dy in -1..1) {
            if (dx == 0 && dy == 0) continue
            add(GridPosition((pos.x + dx + gridWidth) % gridWidth,
                             (pos.y + dy + gridHeight) % gridHeight))
        }
    }

    fun updateSensorReading(reading: SensorReading) {
        processingScope.launch { readingChannel.send(reading) }
    }

    private fun processReading(reading: SensorReading) {
        val pos = sensorIndex[reading.sensorId] ?: return
        sensorGrid[pos.x][pos.y]?.lastReading = reading
        checkSurroundingParity(pos)
    }

    private fun checkSurroundingParity(pos: GridPosition) {
        for (dx in 0..1) for (dy in 0..1) {
            checkParityAt(
                (pos.x - dx + gridWidth)  % gridWidth,
                (pos.y - dy + gridHeight) % gridHeight
            )
        }
    }

    private fun checkParityAt(ox: Int, oy: Int) {
        val corners = listOf(
            GridPosition( ox            % gridWidth,  oy            % gridHeight),
            GridPosition((ox + 1)       % gridWidth,  oy            % gridHeight),
            GridPosition( ox            % gridWidth, (oy + 1)       % gridHeight),
            GridPosition((ox + 1)       % gridWidth, (oy + 1)       % gridHeight)
        )
        val nodes = corners.map { sensorGrid[it.x][it.y] }
        if (nodes.any { it == null || !it.isActive || it.lastReading == null }) return

        val xor   = nodes.fold(0) { acc, n -> acc xor n!!.lastReading!!.value }
        val check = ParityCheck(
            topLeft = corners[0], topRight    = corners[1],
            bottomLeft = corners[2], bottomRight = corners[3],
            actualParity = xor,
            isViolated   = xor != 0
        )

        if (parityCheckHistory.size >= historyMaxSize) parityCheckHistory.removeAt(0)
        parityCheckHistory.add(check)
        if (check.isViolated) analyzeViolation(check)
    }

    private fun analyzeViolation(triggerCheck: ParityCheck) {
        val now    = System.currentTimeMillis()
        val recent = parityCheckHistory.filter {
            it.isViolated && (now - it.timestamp) < detectionWindowMs
        }
        val positions = recent
            .flatMap { listOf(it.topLeft, it.topRight, it.bottomLeft, it.bottomRight) }
            .distinct()

        val severity = when {
            positions.size > gridWidth * gridHeight / 4 -> SeverityLevel.CRITICAL
            positions.size > 5 -> SeverityLevel.HIGH
            positions.size > 2 -> SeverityLevel.MEDIUM
            else               -> SeverityLevel.LOW
        }

        val event = SkimmingEvent(
            eventId              = "SKIM_${UUID.randomUUID()}",
            detectedAt           = now,
            violatedParityChecks = recent,
            affectedSensors      = positions,
            severity             = severity,
            status               = EventStatus.DETECTED
        )
        eventList.add(event)
        if (severity >= SeverityLevel.MEDIUM) triggerAlert(event)
        processingScope.launch { _eventFlow.emit(event) }
    }

    private fun triggerAlert(event: SkimmingEvent) {
        println("⚠️  [ALERT] スキミング検出!")
        println("    Severity : ${event.severity}")
        println("    Sensors  : ${event.affectedSensors}")
        println("    Checks   : ${event.violatedParityChecks.size} violations in window")
    }

    fun getStatistics() = mapOf(
        "gridSize"              to "${gridWidth}x${gridHeight}",
        "registeredSensors"     to sensorGrid.flatten().count { it != null },
        "activeSensors"         to sensorGrid.flatten().count { it?.isActive == true },
        "parityChecksPerformed" to parityCheckHistory.size,
        "violationsDetected"    to parityCheckHistory.count { it.isViolated },
        "eventsGenerated"       to eventList.size,
        "latestSeverity"        to (eventList.lastOrNull()?.severity ?: SeverityLevel.LOW)
    )

    fun shutdown() {
        readingChannel.close()
        processingScope.cancel()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// エントリーポイント
// ─────────────────────────────────────────────────────────────────────────────

fun main(): Unit = runBlocking {
    println("=== ToricCode Skimming Detector (BLE版) 起動 ===\n")

    val detector = ToricCodeSkimmingDetector(
        gridWidth         = 4,
        gridHeight        = 4,
        detectionWindowMs = 3_000L
    )

    // イベント非同期受信
    val eventJob = launch {
        detector.eventFlow.collect { event ->
            println("\n🔔 [Event] ${event.severity} — ${event.affectedSensors.size} sensors affected\n")
        }
    }

    // センサー登録 (4×4 = 16台)
    for (x in 0 until 4) for (y in 0 until 4) {
        detector.registerSensor("S_${x}_${y}", GridPosition(x, y))
    }
    println("✅ 16センサー登録完了\n")

    // BleDeviceManager 初期化
    val bleManager = BleDeviceManager(
        onReadingReceived = { reading -> detector.updateSensorReading(reading) },
        scope             = this
    )

    // ─── BLE スキャン & デバイス発見シミュレーション ──────────────────────────
    bleManager.startScan()
    println("--- BLEデバイス発見シミュレーション ---")
    for (i in 0 until 16) {
        val mac = "AA:BB:CC:DD:EE:%02X".format(i)
        bleManager.onDeviceFound(mac, "SkimSensor_$i", rssi = -45 - i)
        bleManager.onConnected(mac)
        bleManager.onSubscribed(mac)  // Notification 登録完了
    }
    delay(100)

    // ─── Test 1: 正常系 ────────────────────────────────────────────────────────
    println("\n--- [Test 1] 正常系（パリティ一致）---")
    for (i in 0 until 16) {
        bleManager.simulateReading("AA:BB:CC:DD:EE:%02X".format(i),
            value = 1, battery = 90, rssi = -50)
    }
    delay(300)
    println(detector.getStatistics())

    // ─── Test 2: 異常系（スキミングデバイスがセンサー値を反転）──────────────────
    println("\n--- [Test 2] 異常系（スキミングデバイスがBLE信号を改ざん）---")
    bleManager.simulateReading("AA:BB:CC:DD:EE:00", value = 0, battery = 85, rssi = -75)
    delay(100)
    for (mac in listOf("AA:BB:CC:DD:EE:02", "AA:BB:CC:DD:EE:04", "AA:BB:CC:DD:EE:06")) {
        bleManager.simulateReading(mac, value = 0, battery = 80, rssi = -78)
        delay(50)
    }
    delay(300)
    println(detector.getStatistics())

    // ─── Test 3: RSSI 閾値フィルタ ─────────────────────────────────────────────
    println("\n--- [Test 3] RSSI 閾値以下のデバイスは無視 ---")
    bleManager.onDeviceFound("FF:FF:FF:FF:FF:FF", "WeakDevice", rssi = -95)

    // ─── Test 4: 切断 & 再接続（ログ確認） ─────────────────────────────────────
    println("\n--- [Test 4] 切断シミュレーション ---")
    bleManager.onDisconnected("AA:BB:CC:DD:EE:00")

    // ─── 接続デバイス一覧 ─────────────────────────────────────────────────────
    println("\n--- 接続デバイス一覧 ---")
    bleManager.getAllDevices().sortedBy { it.sensorId }.forEach { d ->
        println("  ${d.sensorId}  ${d.macAddress}  RSSI=${d.lastRssi}  ${d.connectionState}")
    }

    // ─── 最終統計 ─────────────────────────────────────────────────────────────
    println("\n=== 最終統計 ===")
    detector.getStatistics().forEach { (k, v) -> println("  $k: $v") }

    eventJob.cancel()
    detector.shutdown()
    println("\n=== シャットダウン完了 ===")
}
