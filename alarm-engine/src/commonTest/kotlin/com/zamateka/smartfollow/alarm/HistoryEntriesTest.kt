package com.zamateka.smartfollow.alarm

import com.zamateka.smartfollow.alarm.MonitorEvent.Kind
import com.zamateka.smartfollow.model.GlucoseReading
import com.zamateka.smartfollow.model.Trend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoryEntriesTest {
    private val t0 = 1_700_000_000_000L
    private val minute = 60_000L

    private fun at(minutes: Int, kind: Kind, type: AlarmType? = null, mgDl: Int? = null) =
        MonitorEvent(t0 + minutes * minute, kind, type, mgDl)

    private fun reading(minutes: Int, mgDl: Int) = GlucoseReading(t0 + minutes * minute, mgDl, Trend.FLAT)

    @Test
    fun conditionAndItsAlarmsMakeOneEpisode() {
        val entries = HistoryEntries.build(
            listOf(
                at(0, Kind.CONDITION_STARTED, AlarmType.URGENT_LOW, 54),
                at(0, Kind.ALARM_RANG, AlarmType.URGENT_LOW, 54),
                at(2, Kind.ACKNOWLEDGED, AlarmType.URGENT_LOW, 52),
                at(7, Kind.ALARM_RANG, AlarmType.URGENT_LOW, 50),
                at(30, Kind.CONDITION_ENDED, AlarmType.URGENT_LOW, 61),
            ),
            readings = listOf(reading(-5, 58), reading(0, 54), reading(10, 47), reading(30, 61), reading(35, 66)),
        )
        assertEquals(
            listOf(HistoryEntry.Episode(AlarmType.URGENT_LOW, t0, t0 + 30 * minute, alarmCount = 2, extremeMgDl = 47)),
            entries,
        )
    }

    @Test
    fun highEpisodeKeepsTheHighestValue() {
        val entries = HistoryEntries.build(
            listOf(at(0, Kind.CONDITION_STARTED, AlarmType.HIGH, 252), at(60, Kind.CONDITION_ENDED, AlarmType.HIGH, 244)),
            readings = listOf(reading(0, 252), reading(30, 281), reading(60, 244)),
        )
        assertEquals(281, (entries.single() as HistoryEntry.Episode).extremeMgDl)
        assertEquals(0, (entries.single() as HistoryEntry.Episode).alarmCount)
    }

    @Test
    fun withoutReadingsTheEventValuesGiveTheExtreme() {
        val entries = HistoryEntries.build(
            listOf(at(0, Kind.CONDITION_STARTED, AlarmType.LOW, 69), at(15, Kind.ALARM_RANG, AlarmType.LOW, 64)),
        )
        assertEquals(HistoryEntry.Episode(AlarmType.LOW, t0, endMillis = null, alarmCount = 1, extremeMgDl = 64), entries.single())
    }

    @Test
    fun silentLowSoonIsLeftOut() {
        val entries = HistoryEntries.build(
            listOf(
                at(0, Kind.CONDITION_STARTED, AlarmType.LOW_SOON, 90),
                at(10, Kind.CONDITION_ENDED, AlarmType.LOW_SOON, 95),
                at(20, Kind.CONDITION_STARTED, AlarmType.LOW_SOON, 85),
                at(20, Kind.ALARM_RANG, AlarmType.LOW_SOON, 85),
                at(30, Kind.CONDITION_ENDED, AlarmType.LOW_SOON, 80),
            ),
        )
        assertEquals(listOf(t0 + 20 * minute), entries.map { it.timestampMillis })
    }

    @Test
    fun technicalEventsAreHiddenAndOthersKept() {
        val resugaring = at(5, Kind.RESUGARING, mgDl = 60)
        val profile = MonitorEvent(t0 + 40 * minute, Kind.PROFILE_CHANGED, profile = "École")
        val entries = HistoryEntries.build(
            listOf(
                at(0, Kind.MONITORING_STARTED),
                resugaring,
                at(6, Kind.ACKNOWLEDGED),
                at(30, Kind.SNOOZE_ENDED),
                profile,
                at(50, Kind.MONITORING_STOPPED),
            ),
        )
        assertEquals(listOf(HistoryEntry.Single(profile), HistoryEntry.Single(resugaring)), entries)
    }

    @Test
    fun stoppingTheMonitoringEndsTheEpisodes() {
        val entries = HistoryEntries.build(
            listOf(
                at(0, Kind.CONDITION_STARTED, AlarmType.STALE_DATA),
                at(0, Kind.ALARM_RANG, AlarmType.STALE_DATA),
                at(10, Kind.MONITORING_STOPPED),
                at(20, Kind.MONITORING_STARTED),
                at(40, Kind.CONDITION_STARTED, AlarmType.STALE_DATA),
            ),
        )
        assertEquals(
            listOf(
                HistoryEntry.Episode(AlarmType.STALE_DATA, t0 + 40 * minute, endMillis = null, alarmCount = 0, extremeMgDl = null),
                HistoryEntry.Episode(AlarmType.STALE_DATA, t0, endMillis = t0 + 10 * minute, alarmCount = 1, extremeMgDl = null),
            ),
            entries,
        )
    }

    @Test
    fun repeatedStartJoinsTheOpenEpisode() {
        val entries = HistoryEntries.build(
            listOf(at(0, Kind.CONDITION_STARTED, AlarmType.LOW, 68), at(10, Kind.CONDITION_STARTED, AlarmType.LOW, 66)),
        )
        assertTrue(entries.single() is HistoryEntry.Episode)
    }

    @Test
    fun summaryCountsEpisodesAndStandingResugarings() {
        val summary = HistoryEntries.summarize(
            HistoryEntries.build(
                listOf(
                    at(0, Kind.CONDITION_STARTED, AlarmType.HIGH, 260),
                    at(20, Kind.CONDITION_ENDED, AlarmType.HIGH, 240),
                    at(100, Kind.CONDITION_STARTED, AlarmType.LOW, 66),
                    at(101, Kind.RESUGARING),
                    at(102, Kind.RESUGARING_CANCELLED),
                    at(103, Kind.RESUGARING),
                    at(130, Kind.CONDITION_ENDED, AlarmType.LOW, 75),
                    at(200, Kind.CONDITION_STARTED, AlarmType.LOW, 62),
                    at(210, Kind.CONDITION_ENDED, AlarmType.LOW, 72),
                    at(300, Kind.CONDITION_STARTED, AlarmType.HIGH, 290),
                    at(330, Kind.CONDITION_ENDED, AlarmType.HIGH, 255),
                    at(400, Kind.CONDITION_STARTED, AlarmType.STALE_DATA),
                    at(420, Kind.CONDITION_ENDED, AlarmType.STALE_DATA),
                    at(500, Kind.PROFILE_CHANGED),
                ),
            ),
        )
        assertEquals(
            HistorySummary(
                episodes = listOf(
                    HistorySummary.Count(AlarmType.LOW, 2, extremeMgDl = 62),
                    HistorySummary.Count(AlarmType.HIGH, 2, extremeMgDl = 290),
                    HistorySummary.Count(AlarmType.STALE_DATA, 1, extremeMgDl = null),
                ),
                resugarings = 1,
            ),
            summary,
        )
    }
}
