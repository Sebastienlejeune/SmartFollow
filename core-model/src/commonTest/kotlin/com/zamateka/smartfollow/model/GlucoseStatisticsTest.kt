package com.zamateka.smartfollow.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GlucoseStatisticsTest {

    private val interval = 5 * 60_000L

    /** One reading every 5 minutes from time 0. */
    private fun series(vararg mgDl: Int) = mgDl.mapIndexed { i, value -> GlucoseReading(i * interval, value, Trend.FLAT) }

    @Test
    fun rangesFollowTheConsensusBoundaries() {
        val stats = assertNotNull(
            GlucoseStatistics.compute(series(50, 54, 69, 70, 180, 181, 250, 251, 100, 120), 0, 10 * interval),
        )
        assertEquals(0.1, stats.veryLow, 1e-9)
        assertEquals(0.2, stats.low, 1e-9)
        assertEquals(0.4, stats.inRange, 1e-9)
        assertEquals(0.2, stats.high, 1e-9)
        assertEquals(0.1, stats.veryHigh, 1e-9)
        assertEquals(1.0, stats.veryLow + stats.low + stats.inRange + stats.high + stats.veryHigh, 1e-9)
    }

    @Test
    fun meanVariabilityAndGmi() {
        val stats = assertNotNull(GlucoseStatistics.compute(series(100, 200), 0, interval))
        assertEquals(150.0, stats.meanMgDl, 1e-9)
        assertEquals(50.0, stats.standardDeviationMgDl, 1e-9)
        assertEquals(33.333, stats.coefficientOfVariationPercent, 1e-3)
        assertEquals(3.31 + 0.02392 * 150, stats.gmiPercent, 1e-9)
    }

    @Test
    fun coverageCountsMissingReadings() {
        // 6 readings over a 1-hour period that should hold 12.
        val stats = assertNotNull(GlucoseStatistics.compute(series(100, 100, 100, 100, 100, 100), 0, 12 * interval))
        assertEquals(0.5, stats.coverage, 1e-9)
        assertEquals(0L, stats.firstReadingMillis)
    }

    @Test
    fun readingsOutsideThePeriodAreIgnored() {
        assertNull(GlucoseStatistics.compute(series(100, 100), 10 * interval, 20 * interval))
        assertEquals(1, GlucoseStatistics.compute(series(40, 100), interval, 2 * interval)?.readingCount)
    }
}
