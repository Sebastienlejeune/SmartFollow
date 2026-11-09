package com.zamateka.smartfollow.monitor

import com.zamateka.smartfollow.alarm.AlarmPreferences
import com.zamateka.smartfollow.alarm.AlarmSettings
import com.zamateka.smartfollow.alarm.AlarmType
import com.zamateka.smartfollow.alarm.MonitorEvent
import com.zamateka.smartfollow.alarm.MonitorEvent.Kind
import com.zamateka.smartfollow.model.GlucoseReading
import com.zamateka.smartfollow.model.Trend
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NightSummaryTest {

    private val zone = ZoneId.of("Europe/Paris")
    private val minute = 60_000L

    private fun at(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun lastNightEndsThisMorningOnceTheEndHourIsPast() {
        val night = NightWindow.lastEnded(at("2026-10-20T08:30"), 22, 7, zone)
        assertEquals(NightWindow(at("2026-10-19T22:00"), at("2026-10-20T07:00")), night)
    }

    @Test
    fun beforeTheEndHourTheLastNightIsThePreviousOne() {
        val night = NightWindow.lastEnded(at("2026-10-20T06:59"), 22, 7, zone)
        assertEquals(NightWindow(at("2026-10-18T22:00"), at("2026-10-19T07:00")), night)
    }

    @Test
    fun nightStartingAfterMidnightStaysOnTheSameDay() {
        val night = NightWindow.lastEnded(at("2026-10-20T12:00"), 1, 6, zone)
        assertEquals(NightWindow(at("2026-10-20T01:00"), at("2026-10-20T06:00")), night)
    }

    @Test
    fun nightAcrossTheDaylightSavingChangeIsTenHoursLong() {
        // Clocks go back one hour on 2026-10-25 at 03:00 in Paris.
        val night = NightWindow.lastEnded(at("2026-10-25T08:00"), 22, 7, zone)
        assertEquals(10 * 60 * minute, night.toMillis - night.fromMillis)
    }

    @Test
    fun nightIncludesItsStartHourAndExcludesItsEndHour() {
        assertTrue(NightWindow.isNight(at("2026-10-20T22:00"), 22, 7, zone))
        assertTrue(NightWindow.isNight(at("2026-10-21T03:30"), 22, 7, zone))
        assertFalse(NightWindow.isNight(at("2026-10-21T07:00"), 22, 7, zone))
        assertFalse(NightWindow.isNight(at("2026-10-20T21:59"), 22, 7, zone))
        assertTrue(NightWindow.isNight(at("2026-10-20T02:00"), 1, 6, zone))
        assertFalse(NightWindow.isNight(at("2026-10-20T23:00"), 1, 6, zone))
    }

    @Test
    fun alarmVolumeIsLoweredAtNightOnly() {
        val preferences = AlarmPreferences(nightVolumeReductionPercent = 30)
        assertEquals(70, preferences.alarmVolumePercentAt(at("2026-10-20T23:00"), zone))
        assertEquals(100, preferences.alarmVolumePercentAt(at("2026-10-20T15:00"), zone))
    }

    @Test
    fun summaryCountsReadingsAndEventsOfTheNightOnly() {
        val night = NightWindow(at("2026-10-19T22:00"), at("2026-10-20T07:00"))
        val readings = listOf(200, 150, 100, 65, 60, 90).mapIndexed { index, mgDl ->
            GlucoseReading(night.fromMillis + index * 5 * minute, mgDl, Trend.FLAT)
        } + GlucoseReading(night.fromMillis - 5 * minute, 40, Trend.FLAT)
        val events = listOf(
            MonitorEvent(night.fromMillis + 15 * minute, Kind.ALARM_RANG, AlarmType.LOW),
            MonitorEvent(night.fromMillis + 16 * minute, Kind.RESUGARING),
            MonitorEvent(night.fromMillis + 17 * minute, Kind.RESUGARING),
            MonitorEvent(night.fromMillis + 18 * minute, Kind.RESUGARING_CANCELLED),
            MonitorEvent(night.toMillis + minute, Kind.ALARM_RANG, AlarmType.HIGH),
        )

        val summary = NightSummary.compute(night, readings, events, AlarmSettings())

        assertEquals(6, summary.readingCount)
        assertEquals(60, summary.minMgDl)
        assertEquals(200, summary.maxMgDl)
        assertEquals(111, summary.meanMgDl)
        assertEquals(0.5, summary.inTarget)
        assertEquals(10, summary.belowMinutes)
        assertEquals(5, summary.aboveMinutes)
        assertEquals(1, summary.alarms)
        assertEquals(1, summary.resugarings)
    }

    @Test
    fun nightWithoutReadingsHasNoGlucose() {
        val summary = NightSummary.compute(NightWindow(0, 1000), emptyList(), emptyList(), AlarmSettings())
        assertEquals(0, summary.readingCount)
        assertNull(summary.minMgDl)
        assertNull(summary.meanMgDl)
    }
}
