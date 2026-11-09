package com.zamateka.smartfollow.alarm

import com.zamateka.smartfollow.model.GlucoseReading
import com.zamateka.smartfollow.model.Trend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GlucoseRateTest {

    private fun series(vararg mgDl: Int) = mgDl.mapIndexed { i, value ->
        GlucoseReading(timestampMillis = i * 5 * 60_000L, mgDl = value, trend = Trend.FLAT)
    }

    @Test
    fun linearDropGivesExactSlope() {
        assertEquals(-2.0, glucoseRateMgDlPerMin(series(100, 90, 80))!!, 1e-9)
    }

    @Test
    fun tooFewReadingsGiveNoRate() {
        assertNull(glucoseRateMgDlPerMin(series(100, 90)))
    }

    @Test
    fun readingsOutsideWindowAreIgnored() {
        // The first reading is 20 minutes before the latest one, outside the 15-minute window.
        assertEquals(1.0, glucoseRateMgDlPerMin(series(300, 100, 105, 110, 115))!!, 1e-9)
    }
}
