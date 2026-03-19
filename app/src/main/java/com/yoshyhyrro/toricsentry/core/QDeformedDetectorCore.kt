package com.yoshyhyrro.toricsentry.core

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import java.util.concurrent.ConcurrentHashMap

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

class QPauliOperator(private val q: Complex) {
    fun measureZ(rssiDelta: Double): Complex {
        val theta = rssiDelta * PI / 20.0
        return Complex.fromPolar(1.0, theta)
    }

    fun measureX(phaseDelta: Double): Complex {
        return Complex.fromPolar(1.0, phaseDelta)
    }
}

data class HybridReading(
    val sensorId: String,
    val rssi: Double,
    val csiPhase: Double,
    val timestamp: Long = System.currentTimeMillis()
)

data class QParityCheck(
    val pos: Pair<Int, Int>,
    val syndrome: Complex,
    val deviation: Double,
    val isViolated: Boolean
)

class QToricDetector(
    private val width: Int,
    private val height: Int,
    private val qFactor: Complex = Complex.fromPolar(1.0, PI / 18.0),
    private val logger: (String) -> Unit = {}
) {
    private val grid = Array(width) { Array<HybridReading?>(height) { null } }
    private val sensorMap = ConcurrentHashMap<String, Pair<Int, Int>>()
    private val op = QPauliOperator(qFactor)

    fun register(id: String, x: Int, y: Int) {
        sensorMap[id] = x to y
    }

    fun update(reading: HybridReading) {
        val position = sensorMap[reading.sensorId] ?: return
        grid[position.first][position.second] = reading
        evaluatePlaquette(position.first, position.second)
    }

    private fun evaluatePlaquette(x: Int, y: Int) {
        val nodes = listOf(
            x to y,
            (x + 1) % width to y,
            x to (y + 1) % height,
            (x + 1) % width to (y + 1) % height
        ).map { grid[it.first][it.second] }

        if (nodes.any { it == null }) {
            return
        }

        var totalSyndrome = Complex.ONE

        nodes.forEachIndexed { index, reading ->
            val sample = requireNotNull(reading)
            val z = op.measureZ(sample.rssi + 50.0)
            val xOperator = op.measureX(sample.csiPhase)
            val twistedNode = z * xOperator * Complex.fromPolar(1.0, index * qFactor.angle())
            totalSyndrome *= twistedNode
        }

        val deviation = sqrt((totalSyndrome.re - 1.0).pow(2) + totalSyndrome.im.pow(2))
        val check = QParityCheck(
            pos = x to y,
            syndrome = totalSyndrome,
            deviation = deviation,
            isViolated = deviation > 0.5
        )

        if (check.isViolated) {
            logger(
                "[Q-Anomaly] Plaquette(${check.pos.first}, ${check.pos.second}) deviation=" +
                    "${"%.3f".format(check.deviation)} phase=${"%.2f".format(check.syndrome.angle() * 180 / PI)}deg"
            )
        }
    }

    fun simulateSkimmer(x: Int, y: Int) {
        logger("[Simulation] Physical skimmer at ($x, $y)")
        val ids = listOf(
            "S_${x}_${y}",
            "S_${(x + 1) % width}_${y}",
            "S_${x}_${(y + 1) % height}",
            "S_${(x + 1) % width}_${(y + 1) % height}"
        )

        ids.forEach { id ->
            update(HybridReading(id, -75.0, PI / 4))
        }
    }
}

suspend fun runQDeformedDetectorDemo(log: (String) -> Unit) {
    val detector = QToricDetector(4, 4, logger = log)

    for (x in 0..3) {
        for (y in 0..3) {
            detector.register("S_${x}_${y}", x, y)
        }
    }

    log("Q-Deformed Toric Code Detector initialized")
    log("Test 1: baseline state")

    for (x in 0..3) {
        for (y in 0..3) {
            detector.update(HybridReading("S_${x}_${y}", -50.0, 0.0))
        }
    }

    log("Test 2: skimmer simulation")
    detector.simulateSkimmer(1, 1)
    log("Q-Deformed detector completed")
}
