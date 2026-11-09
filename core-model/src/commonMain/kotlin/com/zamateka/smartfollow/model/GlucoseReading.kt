package com.zamateka.smartfollow.model

import kotlinx.serialization.Serializable

/** A single estimated glucose value (EGV) reported by the CGM. */
@Serializable
data class GlucoseReading(
    /** Wall-clock time of the measurement, in epoch milliseconds. */
    val timestampMillis: Long,
    val mgDl: Int,
    val trend: Trend,
)

/** Trend arrow as reported by Dexcom, in the order used by the Share API numeric codes. */
enum class Trend {
    NONE,
    DOUBLE_UP,
    SINGLE_UP,
    FORTY_FIVE_UP,
    FLAT,
    FORTY_FIVE_DOWN,
    SINGLE_DOWN,
    DOUBLE_DOWN,
    NOT_COMPUTABLE,
    RATE_OUT_OF_RANGE,
}

/** Arrow shown next to the glucose value; empty when Dexcom gives no trend. */
fun Trend.arrow(): String = when (this) {
    Trend.DOUBLE_UP -> "⇈"
    Trend.SINGLE_UP -> "↑"
    Trend.FORTY_FIVE_UP -> "↗"
    Trend.FLAT -> "→"
    Trend.FORTY_FIVE_DOWN -> "↘"
    Trend.SINGLE_DOWN -> "↓"
    Trend.DOUBLE_DOWN -> "⇊"
    Trend.NONE, Trend.NOT_COMPUTABLE, Trend.RATE_OUT_OF_RANGE -> ""
}
