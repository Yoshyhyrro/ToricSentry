/**
 * main.kt — ToricCode Skimming Detector (修正版)
 *
 * 修正点:
 *  1. VirtualSerialPort.buffer の型修正 (MutableList<Byte>)
 *  2. ParityCheck にタイムスタンプ追加 → 時間窓フィルタリング
 *  3. 周期境界条件 (真のトーリックコード)
 *  4. sensorIndex マップ導入 → findSensorById O(1)化
 *  5. analyzeViolation を時間窓ベースに変更
 *  6. Coroutines + Channel によるスレッドセーフな設計
 *  7. parityCheckHistory のメモリ上限管理
 */

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.Channel
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

// ─────────────────────────────────────────────────────────────────────────────
// データモデル
// ─────────────────────────────────────────────────────────────────────────────

/** センサーの1回分の読み取りデータ */
data class SensorReading(
    val sensorId: String,       // センサー固有ID
    val timestamp: Long,         // エポックミリ秒
    val value: Int,              // センサー値（0 or 1 に量子化）
    val batteryLevel: Int,       // バッテリー残量(%)
    val rssi: Int                // 受信強度
)

/** 格子点の位置（トーラス上） */
data class GridPosition(
    val x: Int,
    val y: Int
)

/** 1つのセンサーノードの状態 */
data class SensorNode(
    val position: GridPosition,
    val sensorId: String,
    @Volatile var lastReading: SensorReading? = null,
    @Volatile var isActive: Boolean = true,
    val neighbors: List<GridPosition> = emptyList()
)

/**
 * プラケット（四角形）のパリティ検査結果
 * ★ Fix #2: timestamp フィールドを追加
 */
data class ParityCheck(
    val topLeft: GridPosition,
    val topRight: GridPosition,
    val bottomLeft: GridPosition,
    val bottomRight: GridPosition,
    val expectedParity: Int,     // 期待値（正常時は0）
    val actualParity: Int,       // 実際のXOR結果
    val isViolated: Boolean,     // 違反しているか
    val timestamp: Long = System.currentTimeMillis()  // ★追加
)

/** 異常イベント */
data class SkimmingEvent(
    val eventId: String,
    val detectedAt: Long,
    val violatedParityChecks: List<ParityCheck>,
    val affectedSensors: List<GridPosition>,
    val severity: SeverityLevel,
    var status: EventStatus
)

enum class SeverityLevel {
    LOW,       // 単発のパリティ違反
    MEDIUM,    // 局所的な違反集中
    HIGH,      // 広範囲な違反
    CRITICAL;  // システム全体の異常

    operator fun compareTo(other: SeverityLevel): Int =
        this.ordinal - other.ordinal
}

enum class EventStatus {
    DETECTED,
    INVESTIGATING,
    CONFIRMED,
    FALSE_ALARM,
    RESOLVED
}

// ─────────────────────────────────────────────────────────────────────────────
// 検出エンジン
// ─────────────────────────────────────────────────────────────────────────────

/**
 * ToricCodeSkimmingDetector
 *
 * ★ Fix #3: グリッド境界を周期境界条件（トーラス）で扱う
 * ★ Fix #4: sensorIndex で O(1) 検索
 * ★ Fix #6: Coroutines + SharedFlow でスレッドセーフ
 */
class ToricCodeSkimmingDetector(
    private val gridWidth: Int,
    private val gridHeight: Int,
    private val detectionWindowMs: Long = 5_000L,   // 時間窓（5秒）
    private val historyMaxSize: Int = 1_000          // 履歴上限（★ Fix #7）
) {
    // センサーグリッド本体
    private val sensorGrid: Array<Array<SensorNode?>> =
        Array(gridWidth) { Array(gridHeight) { null } }

    // ★ Fix #4: ID → GridPosition の逆引きインデックス
    private val sensorIndex = ConcurrentHashMap<String, GridPosition>()

    // スレッドセーフなリスト
    private val parityCheckHistory = CopyOnWriteArrayList<ParityCheck>()
    private val eventList = CopyOnWriteArrayList<SkimmingEvent>()

    // ★ Fix #6: イベント通知用 SharedFlow
    private val _eventFlow = MutableSharedFlow<SkimmingEvent>(extraBufferCapacity = 64)
    val eventFlow: SharedFlow<SkimmingEvent> = _eventFlow.asSharedFlow()

    // ★ Fix #6: 処理用 Channel（バックプレッシャー対応）
    private val readingChannel = Channel<SensorReading>(capacity = Channel.BUFFERED)
    private val processingScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
        // 非同期でチャンネルを消費するワーカーを起動
        processingScope.launch {
            for (reading in readingChannel) {
                processReading(reading)
            }
        }
    }

    // ─── センサー登録 ──────────────────────────────────────────────────────────

    fun registerSensor(sensorId: String, position: GridPosition) {
        val (x, y) = position
        require(x in 0 until gridWidth && y in 0 until gridHeight) {
            "Position ($x, $y) is out of grid bounds"
        }
        val neighbors = calculateNeighbors(position)
        sensorGrid[x][y] = SensorNode(
            position = position,
            sensorId = sensorId,
            neighbors = neighbors
        )
        // ★ Fix #4
        sensorIndex[sensorId] = position
    }

    // ★ Fix #3: 周期境界で隣接計算
    private fun calculateNeighbors(pos: GridPosition): List<GridPosition> {
        val neighbors = mutableListOf<GridPosition>()
        for (dx in -1..1) {
            for (dy in -1..1) {
                if (dx == 0 && dy == 0) continue
                neighbors.add(
                    GridPosition(
                        x = (pos.x + dx + gridWidth) % gridWidth,
                        y = (pos.y + dy + gridHeight) % gridHeight
                    )
                )
            }
        }
        return neighbors
    }

    // ─── センサー更新（外部API） ───────────────────────────────────────────────

    /**
     * センサー読み取り値を更新する。
     * Channel 経由で非同期処理するのでスレッドセーフ。
     */
    fun updateSensorReading(reading: SensorReading) {
        processingScope.launch {
            readingChannel.send(reading)
        }
    }

    /** Channel から受け取った読み取りを処理（Default スレッドで動作） */
    private fun processReading(reading: SensorReading) {
        // ★ Fix #4: O(1) 検索
        val pos = sensorIndex[reading.sensorId] ?: return
        sensorGrid[pos.x][pos.y]?.lastReading = reading
        checkSurroundingParity(pos)
    }

    // ─── パリティチェック ──────────────────────────────────────────────────────

    private fun checkSurroundingParity(pos: GridPosition) {
        // このセンサーが右下隅になる全プラケットをチェック
        for (dx in 0..1) {
            for (dy in 0..1) {
                // ★ Fix #3: 周期境界でプラケット起点を計算
                val ox = (pos.x - dx + gridWidth) % gridWidth
                val oy = (pos.y - dy + gridHeight) % gridHeight
                checkParityAt(ox, oy)
            }
        }
    }

    /** プラケット (ox, oy) を起点とした 2×2 のパリティチェック */
    private fun checkParityAt(ox: Int, oy: Int) {
        // ★ Fix #3: 全座標を % で折り返す（真のトーリックコード）
        val tlPos = GridPosition(ox % gridWidth, oy % gridHeight)
        val trPos = GridPosition((ox + 1) % gridWidth, oy % gridHeight)
        val blPos = GridPosition(ox % gridWidth, (oy + 1) % gridHeight)
        val brPos = GridPosition((ox + 1) % gridWidth, (oy + 1) % gridHeight)

        val tl = sensorGrid[tlPos.x][tlPos.y]
        val tr = sensorGrid[trPos.x][trPos.y]
        val bl = sensorGrid[blPos.x][blPos.y]
        val br = sensorGrid[brPos.x][brPos.y]

        // 全センサーがアクティブかつ最新データを持つ場合のみ
        val readings = listOf(tl, tr, bl, br)
        if (readings.any { it == null || !it.isActive || it.lastReading == null }) return

        val xorResult = readings.fold(0) { acc, node ->
            acc xor node!!.lastReading!!.value
        }

        val check = ParityCheck(
            topLeft     = tlPos,
            topRight    = trPos,
            bottomLeft  = blPos,
            bottomRight = brPos,
            expectedParity = 0,
            actualParity   = xorResult,
            isViolated     = xorResult != 0
            // timestamp はデフォルト値 System.currentTimeMillis() が入る
        )

        // ★ Fix #7: 上限を超えたら古いものを削除
        if (parityCheckHistory.size >= historyMaxSize) {
            parityCheckHistory.removeAt(0)
        }
        parityCheckHistory.add(check)

        if (check.isViolated) {
            analyzeViolation(check)
        }
    }

    // ─── 違反解析 ─────────────────────────────────────────────────────────────

    /**
     * ★ Fix #5: 時間窓ベースのフィルタリング
     * 直近 detectionWindowMs 以内の違反のみを集計する
     */
    private fun analyzeViolation(triggerCheck: ParityCheck) {
        val now = System.currentTimeMillis()
        val recentViolations = parityCheckHistory.filter {
            it.isViolated && (now - it.timestamp) < detectionWindowMs
        }

        val violationPositions = recentViolations
            .flatMap { listOf(it.topLeft, it.topRight, it.bottomLeft, it.bottomRight) }
            .distinct()

        val totalSensors = gridWidth * gridHeight
        val severity = when {
            violationPositions.size > totalSensors / 4 -> SeverityLevel.CRITICAL
            violationPositions.size > 5                -> SeverityLevel.HIGH
            violationPositions.size > 2                -> SeverityLevel.MEDIUM
            else                                       -> SeverityLevel.LOW
        }

        val event = SkimmingEvent(
            eventId               = "SKIM_${UUID.randomUUID()}",
            detectedAt            = now,
            violatedParityChecks  = recentViolations.toList(),
            affectedSensors       = violationPositions,
            severity              = severity,
            status                = EventStatus.DETECTED
        )

        eventList.add(event)

        if (severity >= SeverityLevel.MEDIUM) {
            triggerAlert(event)
        }

        // ★ Fix #6: Flow でサブスクライバーに通知
        processingScope.launch {
            _eventFlow.emit(event)
        }
    }

    private fun triggerAlert(event: SkimmingEvent) {
        println("⚠️  [ALERT] スキミング検出!")
        println("    EventID  : ${event.eventId}")
        println("    Severity : ${event.severity}")
        println("    Sensors  : ${event.affectedSensors.joinToString()}")
        println("    Checks   : ${event.violatedParityChecks.size} violations in window")
    }

    // ─── 統計 ─────────────────────────────────────────────────────────────────

    fun getStatistics(): Map<String, Any> {
        val allNodes = sensorGrid.flatten()
        return mapOf(
            "gridSize"              to "${gridWidth}x${gridHeight}",
            "registeredSensors"     to allNodes.count { it != null },
            "activeSensors"         to allNodes.count { it?.isActive == true },
            "parityChecksPerformed" to parityCheckHistory.size,
            "violationsDetected"    to parityCheckHistory.count { it.isViolated },
            "eventsGenerated"       to eventList.size,
            "latestSeverity"        to (eventList.lastOrNull()?.severity ?: SeverityLevel.LOW),
            "latestEventStatus"     to (eventList.lastOrNull()?.status  ?: EventStatus.RESOLVED)
        )
    }

    /** センサーをオフライン状態にする */
    fun deactivateSensor(sensorId: String) {
        val pos = sensorIndex[sensorId] ?: return
        sensorGrid[pos.x][pos.y]?.isActive = false
    }

    /** Coroutines スコープを終了する（アプリ終了時に呼ぶ） */
    fun shutdown() {
        readingChannel.close()
        processingScope.cancel()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// シリアル通信マネージャー
// ─────────────────────────────────────────────────────────────────────────────

/**
 * SerialPortManager
 * ★ Fix #1: buffer の型を MutableList<Byte> に修正
 */
class SerialPortManager {
    private val activePorts = mutableMapOf<String, VirtualSerialPort>()

    /** 仮想シリアルポートの定義 */
    data class VirtualSerialPort(
        val portName: String,
        val baudRate: Int,
        val connectedSensorId: String,
        var isOpen: Boolean = false,
        val buffer: MutableList<Byte> = mutableListOf()  // ★ Fix #1
    )

    fun openPort(portName: String, sensorId: String, baudRate: Int = 9600): Boolean {
        if (activePorts.containsKey(portName)) {
            println("Port $portName is already open.")
            return false
        }
        activePorts[portName] = VirtualSerialPort(
            portName          = portName,
            baudRate          = baudRate,
            connectedSensorId = sensorId,
            isOpen            = true
        )
        println("✅ Port $portName opened for sensor $sensorId @ ${baudRate}bps")
        return true
    }

    fun closePort(portName: String) {
        activePorts[portName]?.isOpen = false
        activePorts.remove(portName)
        println("🔌 Port $portName closed.")
    }

    /**
     * ポートからデータを読み込んで SensorReading に変換する（スタブ実装）
     * 実際は usb-serial-for-android 等でバイト列をパースする
     */
    fun readFromPort(portName: String): SensorReading? {
        val port = activePorts[portName] ?: return null
        if (!port.isOpen || port.buffer.isEmpty()) return null

        // ダミー実装: buffer の先頭1バイトを value として使用
        val rawByte = port.buffer.removeAt(0)
        return SensorReading(
            sensorId     = port.connectedSensorId,
            timestamp    = System.currentTimeMillis(),
            value        = (rawByte.toInt() and 0x01),  // 最下位ビットのみ使用
            batteryLevel = 100,
            rssi         = -50
        )
    }

    /** テスト用: ポートのバッファに生データを注入する */
    fun injectRawBytes(portName: String, bytes: List<Byte>) {
        activePorts[portName]?.buffer?.addAll(bytes)
    }

    fun listPorts(): List<String> = activePorts.keys.toList()
}

// ─────────────────────────────────────────────────────────────────────────────
// エントリーポイント
// ─────────────────────────────────────────────────────────────────────────────

fun main(): Unit = runBlocking {
    println("=== ToricCode Skimming Detector 起動 ===\n")

    // 4×4 グリッド、検出窓 3 秒
    val detector = ToricCodeSkimmingDetector(
        gridWidth          = 4,
        gridHeight         = 4,
        detectionWindowMs  = 3_000L
    )

    // ─── イベントを非同期で受信して表示 ───────────────────────────────────────
    val eventJob = launch {
        detector.eventFlow.collect { event ->
            println("\n🔔 [Event Received]")
            println("   ID       : ${event.eventId}")
            println("   Severity : ${event.severity}")
            println("   Affected : ${event.affectedSensors}")
            println("   Status   : ${event.status}\n")
        }
    }

    // ─── センサー登録 (4×4 = 16センサー) ──────────────────────────────────────
    for (x in 0 until 4) {
        for (y in 0 until 4) {
            detector.registerSensor("S_${x}_${y}", GridPosition(x, y))
        }
    }
    println("✅ 16センサー登録完了\n")

    // ─── 正常系テスト（パリティが揃う） ───────────────────────────────────────
    println("--- [Test 1] 正常系（パリティ一致）---")
    val normalValues = mapOf(
        "S_0_0" to 1, "S_1_0" to 1, "S_0_1" to 1, "S_1_1" to 1  // XOR = 0
    )
    for ((id, v) in normalValues) {
        detector.updateSensorReading(
            SensorReading(id, System.currentTimeMillis(), v, 90, -40)
        )
    }
    delay(200)
    println(detector.getStatistics())

    // ─── 異常系テスト（パリティ違反） ─────────────────────────────────────────
    println("\n--- [Test 2] 異常系（パリティ違反: スキミング模擬）---")
    // S_0_0 だけ値が反転 → XOR = 1 → 違反
    detector.updateSensorReading(
        SensorReading("S_0_0", System.currentTimeMillis(), 0, 85, -60)
    )
    delay(200)

    // 違反を広げる（MEDIUM/HIGH 判定のため）
    for (id in listOf("S_2_0", "S_2_1", "S_3_1")) {
        detector.updateSensorReading(
            SensorReading(id, System.currentTimeMillis(), 1, 80, -55)
        )
    }
    delay(200)

    println(detector.getStatistics())

    // ─── 周期境界テスト（トーリックコード固有） ───────────────────────────────
    println("\n--- [Test 3] 周期境界（x=3 と x=0 がプラケットを共有）---")
    detector.updateSensorReading(
        SensorReading("S_3_3", System.currentTimeMillis(), 1, 75, -70)
    )
    detector.updateSensorReading(
        SensorReading("S_0_3", System.currentTimeMillis(), 0, 75, -70)  // 折り返し先
    )
    delay(200)

    // ─── シリアルポートマネージャーのテスト ───────────────────────────────────
    println("\n--- [Test 4] SerialPortManager ---")
    val serialMgr = SerialPortManager()
    serialMgr.openPort("COM3", "S_0_0", baudRate = 115200)
    serialMgr.injectRawBytes("COM3", listOf(0x01.toByte(), 0x00.toByte()))
    val r1 = serialMgr.readFromPort("COM3")
    val r2 = serialMgr.readFromPort("COM3")
    println("Read from COM3: $r1")
    println("Read from COM3: $r2")
    serialMgr.closePort("COM3")

    // ─── 最終統計 ────────────────────────────────────────────────────────────
    println("\n=== 最終統計 ===")
    detector.getStatistics().forEach { (k, v) -> println("  $k: $v") }

    // ─── クリーンアップ ───────────────────────────────────────────────────────
    eventJob.cancel()
    detector.shutdown()
    println("\n=== シャットダウン完了 ===")
}
