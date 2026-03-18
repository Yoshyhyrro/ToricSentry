import kotlin.math.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 複素数クラス: 位相回転と振幅を扱う
 */
data class Complex(val re: Double, val im: Double) {
    operator fun plus(other: Complex) = Complex(re + other.re, im + other.im)
    operator fun times(other: Complex) = Complex(
        re * other.re - im * other.im,
        re * other.im + im * other.re
    )
    fun abs() = sqrt(re * re + im * im)
    fun angle() = atan2(im, re)

    companion object {
        fun fromPolar(r: Double, theta: Double) = Complex(r * cos(theta), r * sin(theta))
        val ONE = Complex(1.0, 0.0)
        val ZERO = Complex(0.0, 0.0)
    }
}

/**
 * Q-Deformed Pauli Operators
 * XZ = qZX の関係を持つ作用素のシミュレーション
 */
class QPauliOperator(val q: Complex) {
    // 状態を複素ユニタリ行列（位相シフト）として表現
    // Z: 振幅の減衰に関連 (RSSI)
    // X: 位相のシフトに関連 (CSI Phase)
    
    fun measureZ(rssiDelta: Double): Complex {
        // RSSIの偏差を Z-error の位相回転に写像
        val theta = rssiDelta * PI / 20.0 // 20dBの差で180度回転
        return Complex.fromPolar(1.0, theta)
    }

    fun measureX(phaseDelta: Double): Complex {
        // CSI位相の偏差を X-error の位相回転に写像
        return Complex.fromPolar(1.0, phaseDelta)
    }
}

/**
 * センサー読み取りデータ (Hybrid BLE + Wi-Fi)
 */
data class HybridReading(
    val sensorId: String,
    val rssi: Double,      // BLE RSSI (for Z-check)
    val csiPhase: Double,  // Wi-Fi CSI Phase (for X-check)
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * プラケット（面）のパリティチェック
 * Q-変形では積 (Product) が 1 (Identity) からどれだけズレるかを測定する
 */
data class QParityCheck(
    val pos: Pair<Int, Int>,
    val syndrome: Complex, // 1.0 (複素平面の右端) なら正常
    val deviation: Double,  // 理想状態からの乖離度
    val isViolated: Boolean
)

class QToricDetector(
    private val width: Int,
    private val height: Int,
    private val qFactor: Complex = Complex.fromPolar(1.0, PI / 18.0) // 10度の「ねじれ」
) {
    private val grid = Array(width) { Array<HybridReading?>(height) { null } }
    private val sensorMap = ConcurrentHashMap<String, Pair<Int, Int>>()
    private val op = QPauliOperator(qFactor)

    fun register(id: String, x: Int, y: Int) {
        sensorMap[id] = x to y
    }

    fun update(reading: HybridReading) {
        val (x, y) = sensorMap[reading.sensorId] ?: return
        grid[x][y] = reading
        evaluatePlacket(x, y)
    }

    private fun evaluatePlacket(x: Int, y: Int) {
        // 4つの隣接ノードを取得
        val nodes = listOf(
            x to y,
            (x + 1) % width to y,
            x to (y + 1) % height,
            (x + 1) % width to (y + 1) % height
        ).map { grid[it.first][it.second] }

        if (nodes.any { it == null }) return

        // Q-変形パリティ計算: 
        // 通常の積に q-factor による非可換な補正をシミュレート
        var totalSyndrome = Complex.ONE
        
        nodes.forEachIndexed { index, reading ->
            val z = op.measureZ(reading!!.rssi + 50.0) // -50dBを基準
            val xOp = op.measureX(reading.csiPhase)
            
            // XとZの非可換な結合を q で表現
            // 簡易的に index に応じて q のべき乗を掛けることで「ねじれ」を模写
            val twistedNode = z * xOp * Complex.fromPolar(1.0, index * qFactor.angle())
            totalSyndrome *= twistedNode
        }

        val deviation = sqrt((totalSyndrome.re - 1.0).pow(2) + totalSyndrome.im.pow(2))
        
        if (deviation > 0.5) {
            println("⚠️ [Q-Anomaly] Placket($x, $y) Deviation: ${"%.3f".format(deviation)}")
            println("   Syndrome Phase: ${"%.2f".format(totalSyndrome.angle() * 180 / PI)} deg")
        }
    }

    fun simulateSkimmer(x: Int, y: Int) {
        println("\n--- [Simulation] 物理的スキマー配置 (誘電体 + 金属) at ($x, $y) ---")
        val ids = listOf(
            "S_${x}_${y}", "S_${(x+1)%width}_${y}", 
            "S_${x}_${(y+1)%height}", "S_${(x+1)%width}_${(y+1)%height}"
        )
        
        ids.forEach { id ->
            // スキマーによる RSSI 低下と 位相のねじれ
            update(HybridReading(id, -75.0, PI / 4)) 
        }
    }
}

fun main() {
    val detector = QToricDetector(4, 4)

    // センサー登録
    for (i in 0..3) for (j in 0..3) {
        detector.register("S_${i}_${j}", i, j)
    }

    println("=== Q-Deformed Toric Code Detector Initialized ===")
    
    // 1. 基底状態 (正常な電波環境)
    println("\n--- [Test 1] 基底状態 (正常) ---")
    for (i in 0..3) for (j in 0..3) {
        detector.update(HybridReading("S_${i}_${j}", -50.0, 0.0))
    }

    // 2. スキミング発生 (Q-変形による位相のズレが顕在化)
    detector.simulateSkimmer(1, 1)
    
    println("\n検知完了。")
}
