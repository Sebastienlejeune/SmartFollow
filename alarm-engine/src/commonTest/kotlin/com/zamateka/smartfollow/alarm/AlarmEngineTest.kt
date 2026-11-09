package com.zamateka.smartfollow.alarm

import com.zamateka.smartfollow.model.GlucoseReading
import com.zamateka.smartfollow.model.Trend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val T0 = 1_700_000_000_000L
private const val MINUTE = 60_000L

/** Every alarm rings until acknowledged, as with the original settings. */
private val CONTINUOUS = AlarmSettings(continuousAlarms = AlarmType.entries.toSet())

/** Replays a monitoring session: a CGM reading every 5 minutes, evaluated as the app would. */
private class Session(settings: AlarmSettings = CONTINUOUS) {
    val engine = AlarmEngine(settings)
    var now = T0
    var state = engine.initialState(now)
    val readings = mutableListOf<GlucoseReading>()
    lateinit var last: Evaluation

    /** Adds one reading per value, 5 minutes apart, and returns the last evaluation. */
    fun readings(vararg mgDl: Int): Evaluation {
        mgDl.forEach {
            now += 5 * MINUTE
            readings += GlucoseReading(now, it, Trend.FLAT)
            evaluate()
        }
        return last
    }

    /** Lets time pass without any new reading. */
    fun advance(minutes: Int): Evaluation {
        now += minutes * MINUTE
        return evaluate()
    }

    fun evaluate(): Evaluation {
        last = engine.evaluate(state, readings, now)
        state = last.state
        return last
    }

    fun resugar() {
        state = engine.logResugaring(state, readings, now)
    }

    fun acknowledge() {
        state = engine.acknowledge(state, last.conditions, now)
    }
}

class AlarmEngineTest {

    @Test
    fun normalGlucoseIsSilent() {
        val evaluation = Session().readings(120, 118, 121)
        assertNull(evaluation.alarm)
        assertTrue(evaluation.conditions.isEmpty())
    }

    @Test
    fun lowGlucoseRingsLow() {
        assertEquals(AlarmType.LOW, Session().readings(80, 72, 68).alarm)
    }

    @Test
    fun urgentLowTakesPriorityOverLow() {
        val evaluation = Session().readings(60, 52)
        assertEquals(AlarmType.URGENT_LOW, evaluation.alarm)
        assertTrue(AlarmType.LOW in evaluation.conditions)
    }

    @Test
    fun lowZoneIsLeftOnlyPastHysteresis() {
        val session = Session()
        assertEquals(AlarmType.LOW, session.readings(68).alarm)
        assertEquals(AlarmType.LOW, session.readings(72).alarm)
        assertNull(session.readings(75).alarm)
        assertNull(session.readings(72).alarm)
    }

    @Test
    fun highGlucoseRingsHigh() {
        assertEquals(AlarmType.HIGH, Session().readings(260).alarm)
    }

    @Test
    fun highAlarmCanBeDisabled() {
        assertNull(Session(CONTINUOUS.copy(highMgDl = null, urgentHighMgDl = null)).readings(320).alarm)
    }

    @Test
    fun resugaringSilencesLowUntilSnoozeEnds() {
        val session = Session()
        session.readings(65)
        session.resugar()
        listOf(64, 66, 68, 69).forEach {
            val evaluation = session.readings(it)
            assertNull(evaluation.alarm, "unexpected alarm at $it mg/dL")
            assertTrue(AlarmType.LOW in evaluation.conditions)
        }
        val afterSnooze = session.readings(69)
        assertEquals(AlarmType.LOW, afterSnooze.alarm)
        assertNull(afterSnooze.state.resugaring)
    }

    @Test
    fun resugaringDoesNotHideUrgentLow() {
        val session = Session()
        session.readings(65)
        session.resugar()
        assertNull(session.readings(58).alarm)
        assertEquals(AlarmType.URGENT_LOW, session.readings(52).alarm)
    }

    @Test
    fun fastDropDuringSnoozeForcesAlarm() {
        val session = Session()
        assertEquals(AlarmType.LOW, session.readings(90, 80, 69).alarm, "fast drop only applies during a snooze")
        session.resugar()
        val evaluation = session.readings(58)
        assertEquals(AlarmType.FAST_DROP, evaluation.alarm)
        assertTrue(evaluation.rateMgDlPerMin!! <= -2.0)
    }

    @Test
    fun noRecoveryAfterCheckDelayRings() {
        val session = Session()
        session.readings(65)
        session.resugar()
        assertNull(session.readings(64, 63).alarm)
        assertEquals(AlarmType.NO_RECOVERY, session.readings(64).alarm)
    }

    @Test
    fun cancellingResugaringRestoresLowAlarm() {
        val session = Session()
        session.readings(65)
        session.resugar()
        assertNull(session.readings(64).alarm)
        session.state = session.engine.cancelResugaring(session.state)
        assertEquals(AlarmType.LOW, session.evaluate().alarm)
    }

    @Test
    fun missingReadingsRaiseStaleData() {
        val session = Session()
        session.readings(120)
        assertNull(session.advance(15).alarm)
        val evaluation = session.advance(1)
        assertEquals(AlarmType.STALE_DATA, evaluation.alarm)
        assertNull(evaluation.freshReading)
    }

    @Test
    fun staleLowReadingIsNotTrusted() {
        val session = Session()
        session.readings(60)
        val evaluation = session.advance(20)
        assertEquals(AlarmType.STALE_DATA, evaluation.alarm)
        assertEquals(setOf(AlarmType.STALE_DATA), evaluation.conditions)
    }

    @Test
    fun noReadingSinceStartupRaisesStaleData() {
        val session = Session()
        assertNull(session.advance(15).alarm)
        assertEquals(AlarmType.STALE_DATA, session.advance(1).alarm)
    }

    @Test
    fun acknowledgedAlarmRepeatsAfterInterval() {
        val session = Session()
        session.readings(68)
        session.acknowledge()
        assertNull(session.readings(67, 66).alarm)
        assertEquals(AlarmType.LOW, session.readings(67).alarm)
    }

    @Test
    fun newOccurrenceRingsDespiteEarlierAcknowledgement() {
        val session = Session()
        session.readings(68)
        session.acknowledge()
        assertNull(session.readings(80).alarm)
        assertEquals(AlarmType.LOW, session.readings(68).alarm)
    }

    @Test
    fun acknowledgingUrgentLowAlsoSilencesLow() {
        val session = Session()
        session.readings(52)
        session.acknowledge()
        val evaluation = session.readings(61)
        assertEquals(setOf(AlarmType.LOW), evaluation.conditions)
        assertNull(evaluation.alarm)
    }

    @Test
    fun resugaringRecordsGlucoseAtStart() {
        val session = Session()
        session.readings(66)
        session.resugar()
        assertEquals(66, assertNotNull(session.state.resugaring).glucoseAtStartMgDl)
    }

    @Test
    fun disabledAlarmIsReportedButSilent() {
        val evaluation = Session(CONTINUOUS.copy(disabledAlarms = setOf(AlarmType.HIGH))).readings(300)
        assertEquals(setOf(AlarmType.HIGH), evaluation.conditions)
        assertNull(evaluation.alarm)
    }

    @Test
    fun disabledLowLetsUrgentLowRing() {
        val session = Session(CONTINUOUS.copy(disabledAlarms = setOf(AlarmType.LOW)))
        assertNull(session.readings(65).alarm)
        assertEquals(AlarmType.URGENT_LOW, session.readings(50).alarm)
    }

    @Test
    fun urgentHighRingsAboveItsThreshold() {
        val session = Session()
        assertEquals(AlarmType.HIGH, session.readings(260).alarm)
        val evaluation = session.readings(310)
        assertEquals(AlarmType.URGENT_HIGH, evaluation.alarm)
        assertEquals(setOf(AlarmType.HIGH, AlarmType.URGENT_HIGH), evaluation.conditions)
    }

    @Test
    fun nonContinuousAlarmRingsOnceThenAfterRepeatInterval() {
        val session = Session(AlarmSettings())
        val first = session.readings(68)
        assertEquals(AlarmType.LOW, first.alert)
        assertNull(first.alarm, "a non-continuous alarm does not keep ringing")
        assertNull(session.readings(67).alert)
        assertNull(session.readings(66).alert)
        assertEquals(AlarmType.LOW, session.readings(66).alert, "rings again after the 15-minute repeat interval")
    }

    @Test
    fun continuousAlarmsByDefaultAreUrgentLowAndHighs() {
        assertEquals(AlarmType.URGENT_LOW, Session(AlarmSettings()).readings(50).alarm)
        assertEquals(AlarmType.HIGH, Session(AlarmSettings()).readings(260).alarm)
        assertEquals(AlarmType.URGENT_HIGH, Session(AlarmSettings()).readings(320).alarm)
    }

    @Test
    fun oneShotBelowContinuousAlarmDoesNotRing() {
        val evaluation = Session(AlarmSettings()).readings(50)
        assertEquals(AlarmType.URGENT_LOW, evaluation.alarm)
        assertNull(evaluation.alert, "the low alert is covered by the urgent low alarm")
    }

    @Test
    fun dataLossDuringSnoozeWaitsForItsEnd() {
        val session = Session()
        session.readings(52)
        session.resugar()
        val gap = session.advance(20)
        assertTrue(AlarmType.STALE_DATA in gap.conditions)
        assertNull(gap.alarm, "no data loss alarm while the snooze runs")
        assertEquals(AlarmType.STALE_DATA, session.advance(6).alarm, "rings once the snooze is over")
    }

    @Test
    fun lowSoonRingsBeforeTheLow() {
        val session = Session(AlarmSettings())
        assertNull(session.readings(110, 100).alert)
        val evaluation = session.readings(90)
        assertEquals(setOf(AlarmType.LOW_SOON), evaluation.conditions, "90 dropping 2 mg/dL per minute reaches 50 in 20 minutes")
        assertEquals(AlarmType.LOW_SOON, evaluation.alert)
    }

    @Test
    fun lowSoonIgnoresStableGlucose() {
        assertTrue(Session(AlarmSettings()).readings(80, 80, 80).conditions.isEmpty())
    }

    @Test
    fun lowSoonGivesWayToTheLow() {
        val session = Session(AlarmSettings())
        session.readings(100, 90, 80)
        val evaluation = session.readings(68)
        assertTrue(AlarmType.LOW in evaluation.conditions)
        assertTrue(AlarmType.LOW_SOON !in evaluation.conditions, "the low is no longer a prediction")
    }

    @Test
    fun lowSoonEndsWithHysteresis() {
        val session = Session(AlarmSettings())
        assertTrue(AlarmType.LOW_SOON in session.readings(95, 90, 85).conditions, "85 - 1 x 20 = 65")
        assertTrue(AlarmType.LOW_SOON in session.readings(85).conditions, "predicts 71, within the hysteresis")
        assertTrue(AlarmType.LOW_SOON !in session.readings(88, 92).conditions, "rising again")
    }

    @Test
    fun lowSoonIgnoresTheRiseAfterADip() {
        val session = Session(AlarmSettings())
        session.readings(110, 95, 85)
        assertTrue(AlarmType.LOW_SOON !in session.readings(90).conditions, "the 15-minute slope is still negative")
    }

    @Test
    fun lowSoonRingsOncePerEpisode() {
        val session = Session(AlarmSettings())
        session.readings(110, 100)
        assertEquals(AlarmType.LOW_SOON, session.readings(90).alert)
        // Still predicted 40 minutes later, well past the repeat interval: no new alert.
        listOf(80, 74, 72, 71, 71, 71, 71, 71).forEach {
            val evaluation = session.readings(it)
            assertTrue(AlarmType.LOW_SOON in evaluation.conditions, "$it")
            assertNull(evaluation.alert)
        }
        // The prediction ends, then comes back: a new episode rings again.
        assertTrue(AlarmType.LOW_SOON !in session.readings(90, 110, 120).conditions)
        session.readings(110, 100)
        assertEquals(AlarmType.LOW_SOON, session.readings(90).alert)
    }

    @Test
    fun acknowledgedLowSoonStaysSilentForTheEpisode() {
        val session = Session(AlarmSettings(continuousAlarms = setOf(AlarmType.LOW_SOON)))
        session.readings(110, 100)
        assertEquals(AlarmType.LOW_SOON, session.readings(90).alarm)
        session.acknowledge()
        listOf(80, 74, 72, 71, 71, 71, 71, 71).forEach {
            val evaluation = session.readings(it)
            assertTrue(AlarmType.LOW_SOON in evaluation.conditions, "$it")
            assertNull(evaluation.alarm)
        }
    }

    @Test
    fun lowSoonIsSilentDuringSnooze() {
        val session = Session(AlarmSettings())
        session.readings(100, 94)
        session.resugar()
        val evaluation = session.readings(88)
        assertTrue(AlarmType.LOW_SOON in evaluation.conditions)
        assertNull(evaluation.alert)
        assertNull(evaluation.alarm)
    }

    @Test
    fun invalidSettingsAreRejected() {
        assertFailsWith<IllegalArgumentException> { AlarmSettings(urgentLowMgDl = 70, lowMgDl = 70) }
        assertFailsWith<IllegalArgumentException> { AlarmSettings(highMgDl = 60) }
        assertFailsWith<IllegalArgumentException> { AlarmSettings(disabledAlarms = setOf(AlarmType.URGENT_LOW)) }
        assertFailsWith<IllegalArgumentException> { AlarmSettings(disabledAlarms = setOf(AlarmType.STALE_DATA)) }
    }
}
