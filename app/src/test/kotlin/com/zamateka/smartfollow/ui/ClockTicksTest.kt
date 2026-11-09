package com.zamateka.smartfollow.ui

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class ClockTicksTest {
    private val paris = ZoneId.of("Europe/Paris")

    private fun at(hour: Int, minute: Int) = ZonedDateTime.of(2026, 11, 8, hour, minute, 0, 0, paris).toInstant().toEpochMilli()

    private fun ticks(from: Long, to: Long, step: Int) =
        clockTicks(from, to, step, paris).map { ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(it), paris).run { "%02d:%02d".format(hour, minute) } }

    @Test
    fun threeHoursShowsEachRoundHour() {
        assertEquals(listOf("13:00", "14:00", "15:00"), ticks(at(12, 16), at(15, 16), 60))
    }

    @Test
    fun oneHourShowsQuarters() {
        assertEquals(listOf("14:30", "14:45", "15:00", "15:15"), ticks(at(14, 16), at(15, 16), 15))
    }

    @Test
    fun longerSpansAlignOnMidnight() {
        assertEquals(listOf("18:00", "00:00", "06:00", "12:00"), ticks(at(15, 16) - 24 * 3_600_000L, at(15, 16), 360))
        assertEquals(listOf("06:00", "09:00", "12:00", "15:00"), ticks(at(3, 16), at(15, 16), 180))
    }

    @Test
    fun theStartIsLeftOutAndTheEndKept() {
        assertEquals(listOf("14:00", "15:00"), ticks(at(13, 0), at(15, 0), 60))
    }
}
