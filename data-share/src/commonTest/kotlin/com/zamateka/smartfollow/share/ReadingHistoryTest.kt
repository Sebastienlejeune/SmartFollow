package com.zamateka.smartfollow.share

import com.zamateka.smartfollow.model.GlucoseReading
import com.zamateka.smartfollow.model.Trend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReadingHistoryTest {

    private val minute = 60_000L
    private val day = 24 * 60 * minute

    private fun reading(atMinutes: Long, mgDl: Int = 100) = GlucoseReading(atMinutes * minute, mgDl, Trend.FLAT)

    @Test
    fun firstPollDownloadsTheWholeDay() {
        val request = ReadingHistory.nextRequest(emptyList(), nowMillis = day, pollsSinceFullRefresh = 0)
        assertEquals(ReadingHistory.Request(minutes = 1440, maxCount = 288), request)
        assertTrue(ReadingHistory.isFullRefresh(request))
    }

    @Test
    fun laterPollsOnlyDownloadRecentMinutes() {
        val existing = listOf(reading(atMinutes = 1000))
        val request = ReadingHistory.nextRequest(existing, nowMillis = 1005 * minute, pollsSinceFullRefresh = 1)
        assertEquals(ReadingHistory.Request(minutes = 15, maxCount = 5), request)
    }

    @Test
    fun fullRefreshComesBackPeriodically() {
        val existing = listOf(reading(atMinutes = 1000))
        val request = ReadingHistory.nextRequest(existing, nowMillis = 1005 * minute, pollsSinceFullRefresh = ReadingHistory.FULL_REFRESH_EVERY)
        assertTrue(ReadingHistory.isFullRefresh(request))
    }

    @Test
    fun longGapIsCappedToOneDay() {
        val existing = listOf(reading(atMinutes = 0))
        val request = ReadingHistory.nextRequest(existing, nowMillis = 3 * day, pollsSinceFullRefresh = 1)
        assertEquals(1440, request.minutes)
    }

    @Test
    fun mergeKeepsOneReadingPerTimeAndDropsOldOnes() {
        val now = day + 100 * minute
        val merged = ReadingHistory.merge(
            existing = listOf(reading(50), reading(200, mgDl = 90), reading(205)),
            fetched = listOf(reading(205, mgDl = 120), reading(210)),
            nowMillis = now,
        )
        assertEquals(listOf(200L, 205L, 210L), merged.map { it.timestampMillis / minute })
        assertEquals(120, merged[1].mgDl)
    }
}
