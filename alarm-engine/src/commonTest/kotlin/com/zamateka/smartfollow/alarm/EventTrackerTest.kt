package com.zamateka.smartfollow.alarm

import com.zamateka.smartfollow.alarm.MonitorEvent.Kind
import com.zamateka.smartfollow.model.GlucoseReading
import com.zamateka.smartfollow.model.Trend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventTrackerTest {

    private val minute = 60_000L
    private val settings = AlarmSettings()
    private val engine = AlarmEngine(settings)
    private val tracker = EventTracker()
    private var now = 1_700_000_000_000L
    private var state = engine.initialState(now)
    private val readings = mutableListOf<GlucoseReading>()

    /** Adds one reading per value, 5 minutes apart, and returns the events of the last one. */
    private fun readings(vararg mgDl: Int): List<MonitorEvent> {
        var events = emptyList<MonitorEvent>()
        mgDl.forEach {
            now += 5 * minute
            readings += GlucoseReading(now, it, Trend.FLAT)
            events = evaluate()
        }
        return events
    }

    private fun advance(minutes: Int): List<MonitorEvent> {
        now += minutes * minute
        return evaluate()
    }

    private fun evaluate(): List<MonitorEvent> {
        val evaluation = engine.evaluate(state, readings, now)
        state = evaluation.state
        return tracker.track(evaluation, now, settings.resugaringSnooze.inWholeMilliseconds)
    }

    private fun List<MonitorEvent>.summary() = map { it.kind to it.alarmType }

    @Test
    fun lowStartsRingsThenEnds() {
        assertTrue(readings(100).isEmpty())
        val low = readings(68)
        assertEquals(listOf(Kind.CONDITION_STARTED to AlarmType.LOW, Kind.ALARM_RANG to AlarmType.LOW), low.summary())
        assertEquals(68, low.first().mgDl)
        assertTrue(readings(72).isEmpty(), "still in the low zone because of hysteresis")
        assertEquals(listOf(Kind.CONDITION_ENDED to AlarmType.LOW), readings(80).summary())
    }

    @Test
    fun dataGapIsNotLoggedAsRecovery() {
        readings(65)
        assertEquals(listOf(Kind.CONDITION_STARTED to AlarmType.STALE_DATA, Kind.ALARM_RANG to AlarmType.STALE_DATA), advance(20).summary())
        // Data comes back still low: only the data loss ends, the low goes on.
        assertEquals(listOf(Kind.CONDITION_ENDED to AlarmType.STALE_DATA, Kind.ALARM_RANG to AlarmType.LOW), readings(66).summary())
    }

    @Test
    fun snoozeRunningToItsEndIsLogged() {
        readings(65)
        state = engine.logResugaring(state, readings, now)
        assertTrue(readings(66, 68, 70, 72).none { it.kind == Kind.SNOOZE_ENDED })
        val end = readings(73)
        assertTrue(end.any { it.kind == Kind.SNOOZE_ENDED })
        assertTrue(end.any { it.kind == Kind.ALARM_RANG && it.alarmType == AlarmType.LOW })
    }

    @Test
    fun cancelledSnoozeIsNotLoggedAsEnded() {
        readings(65)
        state = engine.logResugaring(state, readings, now)
        readings(66)
        state = engine.cancelResugaring(state)
        assertTrue(evaluate().none { it.kind == Kind.SNOOZE_ENDED })
    }
}
