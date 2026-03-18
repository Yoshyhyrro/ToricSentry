package com.yoshyhyrro.toricsentry.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

object BleConstants {
    val SERVICE_UUID: UUID = UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB")
    val CHAR_SENSOR_UUID: UUID = UUID.fromString("0000FFF1-0000-1000-8000-00805F9B34FB")
    val CHAR_BATTERY_UUID: UUID = UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB")
    val DESCRIPTOR_CCC_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    const val SCAN_PERIOD_MS = 10_000L
    const val RSSI_THRESHOLD = -80
    const val RECONNECT_DELAY_MS = 3_000L
    const val MAX_CONNECTED_DEVICES = 16
}

data class SensorReading(
    val sensorId: String,
    val timestamp: Long,
    val value: Int,
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
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

enum class EventStatus {
    DETECTED,
    INVESTIGATING,
    CONFIRMED,
    FALSE_ALARM,
    RESOLVED
}

data class BleDeviceInfo(
    val macAddress: String,
    val deviceName: String,
    val sensorId: String,
    var connectionState: BleConnectionState = BleConnectionState.DISCONNECTED,
    var lastRssi: Int = 0,
    var lastSeenMs: Long = 0L
)

enum class BleConnectionState {
    SCANNING,
    CONNECTING,
    CONNECTED,
    SUBSCRIBING,
    READY,
    DISCONNECTED,
    ERROR
}

class BleDeviceManager(
    private val onReadingReceived: (SensorReading) -> Unit,
    private val scope: CoroutineScope,
    private val logger: (String) -> Unit = {}
) {
    private val devices = ConcurrentHashMap<String, BleDeviceInfo>()

    fun startScan() {
        logger("BLE scan start: ${BleConstants.SERVICE_UUID}")
    }

    fun stopScan() {
        logger("BLE scan stop")
    }

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

class ToricCodeSkimmingDetector(
    private val gridWidth: Int,
    private val gridHeight: Int,
    private val detectionWindowMs: Long = 5_000L,
    private val historyMaxSize: Int = 1_000,
    private val logger: (String) -> Unit = {}
) {
    private val sensorGrid: Array<Array<SensorNode?>> =
        Array(gridWidth) { Array(gridHeight) { null } }

    private val sensorIndex = ConcurrentHashMap<String, GridPosition>()
    private val parityCheckHistory = CopyOnWriteArrayList<ParityCheck>()
    private val eventList = CopyOnWriteArrayList<SkimmingEvent>()

    private val _eventFlow = MutableSharedFlow<SkimmingEvent>(extraBufferCapacity = 64)
    val eventFlow: SharedFlow<SkimmingEvent> = _eventFlow.asSharedFlow()

    private val readingChannel = Channel<SensorReading>(capacity = Channel.BUFFERED)
    private val processingScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
        processingScope.launch {
            for (reading in readingChannel) {
                processReading(reading)
            }
        }
    }

    fun registerSensor(sensorId: String, position: GridPosition) {
        val (x, y) = position
        require(x in 0 until gridWidth && y in 0 until gridHeight)
        sensorGrid[x][y] = SensorNode(position, sensorId, neighbors = calculateNeighbors(position))
        sensorIndex[sensorId] = position
    }

    private fun calculateNeighbors(pos: GridPosition): List<GridPosition> = buildList {
        for (dx in -1..1) {
            for (dy in -1..1) {
                if (dx == 0 && dy == 0) {
                    continue
                }
                add(
                    GridPosition(
                        (pos.x + dx + gridWidth) % gridWidth,
                        (pos.y + dy + gridHeight) % gridHeight
                    )
                )
            }
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
        for (dx in 0..1) {
            for (dy in 0..1) {
                checkParityAt(
                    (pos.x - dx + gridWidth) % gridWidth,
                    (pos.y - dy + gridHeight) % gridHeight
                )
            }
        }
    }

    private fun checkParityAt(ox: Int, oy: Int) {
        val corners = listOf(
            GridPosition(ox % gridWidth, oy % gridHeight),
            GridPosition((ox + 1) % gridWidth, oy % gridHeight),
            GridPosition(ox % gridWidth, (oy + 1) % gridHeight),
            GridPosition((ox + 1) % gridWidth, (oy + 1) % gridHeight)
        )

        val nodes = corners.map { sensorGrid[it.x][it.y] }
        if (nodes.any { it == null || !it.isActive || it.lastReading == null }) {
            return
        }

        val xor = nodes.fold(0) { acc, node -> acc xor node!!.lastReading!!.value }
        val check = ParityCheck(
            topLeft = corners[0],
            topRight = corners[1],
            bottomLeft = corners[2],
            bottomRight = corners[3],
            actualParity = xor,
            isViolated = xor != 0
        )

        if (parityCheckHistory.size >= historyMaxSize) {
            parityCheckHistory.removeAt(0)
        }
        parityCheckHistory.add(check)

        if (check.isViolated) {
            analyzeViolation(check)
        }
    }

    private fun analyzeViolation(triggerCheck: ParityCheck) {
        val now = System.currentTimeMillis()
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
            else -> SeverityLevel.LOW
        }

        val event = SkimmingEvent(
            eventId = "SKIM_${UUID.randomUUID()}",
            detectedAt = now,
            violatedParityChecks = recent,
            affectedSensors = positions,
            severity = severity,
            status = EventStatus.DETECTED
        )
        eventList.add(event)

        if (severity >= SeverityLevel.MEDIUM) {
            triggerAlert(event)
        }

        processingScope.launch { _eventFlow.emit(event) }
    }

    private fun triggerAlert(event: SkimmingEvent) {
        logger("[ALERT] Skimming detected")
        logger("Severity: ${event.severity}")
        logger("Sensors: ${event.affectedSensors}")
        logger("Checks: ${event.violatedParityChecks.size} violations in window")
    }

    fun getStatistics(): Map<String, Any> = mapOf(
        "gridSize" to "${gridWidth}x${gridHeight}",
        "registeredSensors" to sensorGrid.flatten().count { it != null },
        "activeSensors" to sensorGrid.flatten().count { it?.isActive == true },
        "parityChecksPerformed" to parityCheckHistory.size,
        "violationsDetected" to parityCheckHistory.count { it.isViolated },
        "eventsGenerated" to eventList.size,
        "latestSeverity" to (eventList.lastOrNull()?.severity ?: SeverityLevel.LOW)
    )

    fun shutdown() {
        readingChannel.close()
        processingScope.coroutineContext.cancel()
    }
}

suspend fun runDetectorDemo(log: (String) -> Unit) {
    log("ToricCode Skimming Detector (Android) start")

    val detector = ToricCodeSkimmingDetector(
        gridWidth = 4,
        gridHeight = 4,
        detectionWindowMs = 3_000L,
        logger = log
    )

    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    val eventJob = scope.launch {
        detector.eventFlow.collect { event ->
            log("Event: ${event.severity}, affected=${event.affectedSensors.size}")
        }
    }

    for (x in 0 until 4) {
        for (y in 0 until 4) {
            detector.registerSensor("S_${x}_${y}", GridPosition(x, y))
        }
    }
    log("16 sensors registered")

    val bleManager = BleDeviceManager(
        onReadingReceived = { reading -> detector.updateSensorReading(reading) },
        scope = scope,
        logger = log
    )

    bleManager.startScan()
    for (i in 0 until 16) {
        val mac = "AA:BB:CC:DD:EE:%02X".format(i)
        bleManager.onDeviceFound(mac, "SkimSensor_$i", rssi = -45 - i)
        bleManager.onConnected(mac)
        bleManager.onSubscribed(mac)
    }

    delay(100)

    log("Test 1: parity matched")
    for (i in 0 until 16) {
        bleManager.simulateReading(
            macAddress = "AA:BB:CC:DD:EE:%02X".format(i),
            value = 1,
            battery = 90,
            rssi = -50
        )
    }

    delay(300)
    log("Stats: ${detector.getStatistics()}")

    log("Test 2: tampered values")
    bleManager.simulateReading("AA:BB:CC:DD:EE:00", value = 0, battery = 85, rssi = -75)
    delay(100)
    listOf("AA:BB:CC:DD:EE:02", "AA:BB:CC:DD:EE:04", "AA:BB:CC:DD:EE:06").forEach { mac ->
        bleManager.simulateReading(mac, value = 0, battery = 80, rssi = -78)
        delay(50)
    }

    delay(300)
    log("Stats: ${detector.getStatistics()}")

    log("Test 3: weak RSSI ignored")
    bleManager.onDeviceFound("FF:FF:FF:FF:FF:FF", "WeakDevice", rssi = -95)

    log("Test 4: disconnect simulation")
    bleManager.onDisconnected("AA:BB:CC:DD:EE:00")

    bleManager.getAllDevices().sortedBy { it.sensorId }.forEach { d ->
        log("${d.sensorId} ${d.macAddress} RSSI=${d.lastRssi} ${d.connectionState}")
    }

    log("Final stats: ${detector.getStatistics()}")

    eventJob.cancel()
    detector.shutdown()
    scope.coroutineContext.cancel()
    log("Shutdown completed")
}
