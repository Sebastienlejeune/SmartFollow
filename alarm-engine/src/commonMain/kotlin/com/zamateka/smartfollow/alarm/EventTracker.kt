package com.zamateka.smartfollow.alarm

import com.zamateka.smartfollow.alarm.MonitorEvent.Kind

/**
 * Turns successive evaluations into history events: conditions starting and ending, alarms
 * starting to sound, and the end of a resugaring snooze. User actions (resugaring,
 * acknowledgement...) are recorded by the caller. Not thread-safe.
 */
class EventTracker {
    private var previous: Evaluation? = null

    /** Glucose conditions of the last evaluation that had a fresh reading. */
    private var glucoseConditions: Set<AlarmType> = emptySet()

    /** Forgets the past, for instance when monitoring restarts. */
    fun reset() {
        previous = null
        glucoseConditions = emptySet()
    }

    fun track(current: Evaluation, nowMillis: Long, snoozeMillis: Long): List<MonitorEvent> {
        val events = mutableListOf<MonitorEvent>()
        val mgDl = current.freshReading?.mgDl
        fun event(kind: Kind, type: AlarmType? = null) {
            events += MonitorEvent(nowMillis, kind, type, mgDl)
        }
        val last = previous

        val wasStale = last?.conditions?.contains(AlarmType.STALE_DATA) == true
        val isStale = AlarmType.STALE_DATA in current.conditions
        if (isStale && !wasStale) event(Kind.CONDITION_STARTED, AlarmType.STALE_DATA)
        if (!isStale && wasStale) event(Kind.CONDITION_ENDED, AlarmType.STALE_DATA)

        // Glucose conditions are only judged on fresh readings, so that a data gap is not
        // logged as a return to normal, then as a new low once data comes back.
        if (current.freshReading != null) {
            val conditions = current.conditions - AlarmType.STALE_DATA
            (conditions - glucoseConditions).sortedBy { it.ordinal }.forEach { event(Kind.CONDITION_STARTED, it) }
            (glucoseConditions - conditions).sortedBy { it.ordinal }.forEach { event(Kind.CONDITION_ENDED, it) }
            glucoseConditions = conditions
        }

        val alarm = current.alarm
        if (alarm != null && alarm != last?.alarm) event(Kind.ALARM_RANG, alarm)
        current.alert?.let { event(Kind.ALARM_RANG, it) }

        val endedResugaring = last?.state?.resugaring?.takeIf { current.state.resugaring == null }
        if (endedResugaring != null && nowMillis >= endedResugaring.startedAtMillis + snoozeMillis) {
            event(Kind.SNOOZE_ENDED)
        }

        previous = current
        return events
    }
}
