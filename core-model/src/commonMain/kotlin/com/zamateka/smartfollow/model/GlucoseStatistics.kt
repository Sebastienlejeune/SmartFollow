package com.zamateka.smartfollow.model

import kotlinx.serialization.Serializable
import kotlin.math.sqrt

/**
 * Standard CGM metrics over a period, as defined by the international consensus on time in
 * range (Battelino et al., Diabetes Care 2019) and used by Dexcom Clarity. The ranges are the
 * consensus ones, independent of the alarm thresholds, so that they compare with medical reports.
 *
 * Range shares are fractions of the readings (one every 5 minutes), between 0 and 1.
 */
@Serializable
data class GlucoseStatistics(
    val readingCount: Int,
    /** Share of the expected readings (one every 5 minutes) actually received over the period. */
    val coverage: Double,
    val firstReadingMillis: Long,
    val meanMgDl: Double,
    val standardDeviationMgDl: Double,
    /** Coefficient of variation in percent; at most 36 % is considered stable. */
    val coefficientOfVariationPercent: Double,
    /** Glucose management indicator: HbA1c estimated from the mean glucose, in percent. */
    val gmiPercent: Double,
    /** Below [VERY_LOW_BELOW] mg/dL. */
    val veryLow: Double,
    /** From [VERY_LOW_BELOW] to [LOW_BELOW] - 1 mg/dL. */
    val low: Double,
    /** From [LOW_BELOW] to [HIGH_ABOVE] mg/dL. */
    val inRange: Double,
    /** From [HIGH_ABOVE] + 1 to [VERY_HIGH_ABOVE] mg/dL. */
    val high: Double,
    /** Above [VERY_HIGH_ABOVE] mg/dL. */
    val veryHigh: Double,
) {
    companion object {
        const val VERY_LOW_BELOW = 54
        const val LOW_BELOW = 70
        const val HIGH_ABOVE = 180
        const val VERY_HIGH_ABOVE = 250
        private const val READING_INTERVAL_MILLIS = 5 * 60_000L

        /** Statistics of the readings taken from [fromMillis] to [toMillis], or `null` if there are none. */
        fun compute(readings: List<GlucoseReading>, fromMillis: Long, toMillis: Long): GlucoseStatistics? {
            val values = readings
                .filter { it.timestampMillis in fromMillis..toMillis }
                .distinctBy { it.timestampMillis }
            if (values.isEmpty()) return null
            val count = values.size.toDouble()
            val glucose = values.map { it.mgDl.toDouble() }
            val mean = glucose.average()
            val deviation = sqrt(glucose.sumOf { (it - mean) * (it - mean) } / count)
            fun share(predicate: (Int) -> Boolean) = values.count { predicate(it.mgDl) } / count
            val expected = ((toMillis - fromMillis) / READING_INTERVAL_MILLIS).coerceAtLeast(1)
            return GlucoseStatistics(
                readingCount = values.size,
                coverage = (count / expected).coerceAtMost(1.0),
                firstReadingMillis = values.minOf { it.timestampMillis },
                meanMgDl = mean,
                standardDeviationMgDl = deviation,
                coefficientOfVariationPercent = 100 * deviation / mean,
                gmiPercent = 3.31 + 0.02392 * mean,
                veryLow = share { it < VERY_LOW_BELOW },
                low = share { it in VERY_LOW_BELOW until LOW_BELOW },
                inRange = share { it in LOW_BELOW..HIGH_ABOVE },
                high = share { it in (HIGH_ABOVE + 1)..VERY_HIGH_ABOVE },
                veryHigh = share { it > VERY_HIGH_ABOVE },
            )
        }
    }
}
