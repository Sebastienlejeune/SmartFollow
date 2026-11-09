package com.zamateka.smartfollow.alarm

import com.zamateka.smartfollow.model.GlucoseReading
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Glucose rate of change in mg/dL per minute, as the least-squares slope over the readings
 * of the last [window]. Fitting several readings smooths out the sensor noise that a
 * two-point difference would amplify. Returns `null` with fewer than [minReadings] readings.
 */
fun glucoseRateMgDlPerMin(
    readings: List<GlucoseReading>,
    window: Duration = 15.minutes,
    minReadings: Int = 3,
): Double? {
    val latest = readings.maxByOrNull { it.timestampMillis } ?: return null
    val recent = readings
        .filter { it.timestampMillis >= latest.timestampMillis - window.inWholeMilliseconds }
        .distinctBy { it.timestampMillis }
    if (recent.size < minReadings) return null

    val xs = recent.map { (it.timestampMillis - latest.timestampMillis) / 60_000.0 }
    val ys = recent.map { it.mgDl.toDouble() }
    val meanX = xs.average()
    val meanY = ys.average()
    val covariance = xs.indices.sumOf { (xs[it] - meanX) * (ys[it] - meanY) }
    val variance = xs.sumOf { (it - meanX) * (it - meanX) }
    return if (variance == 0.0) null else covariance / variance
}
