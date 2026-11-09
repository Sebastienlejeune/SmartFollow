package com.zamateka.smartfollow.alarm

import com.zamateka.smartfollow.model.GlucoseReading

/**
 * Pure alarm logic: no clock, no I/O. Every call takes the current state and time
 * and returns a new state, so the whole behaviour can be replayed in tests.
 *
 * Call [evaluate] on every new batch of readings and on a periodic tick (at least
 * every minute), so that stale data and snooze expiry are detected without new readings.
 */
class AlarmEngine(private val settings: AlarmSettings = AlarmSettings()) {

    fun initialState(nowMillis: Long): AlarmState = AlarmState(monitoringStartedAtMillis = nowMillis)

    fun evaluate(state: AlarmState, readings: List<GlucoseReading>, nowMillis: Long): Evaluation {
        val fresh = freshReading(readings, nowMillis)
        val conditions = mutableSetOf<AlarmType>()
        val suppressed = mutableSetOf<AlarmType>()
        var next = state
        var rate: Double? = null

        if (fresh == null) {
            val lastKnownMillis = readings.maxOfOrNull { it.timestampMillis } ?: state.monitoringStartedAtMillis
            if (nowMillis - lastKnownMillis > settings.staleDataAfter.inWholeMilliseconds) {
                conditions += AlarmType.STALE_DATA
            }
        } else {
            rate = glucoseRateMgDlPerMin(readings)
            next = next.withZones(fresh.mgDl).withLowSoon(fresh, rate, readings)
            if (next.inUrgentLowZone) conditions += AlarmType.URGENT_LOW
            if (next.inLowZone) conditions += AlarmType.LOW
            if (next.lowSoon) conditions += AlarmType.LOW_SOON
            if (next.inHighZone) conditions += AlarmType.HIGH
            if (next.inUrgentHighZone) conditions += AlarmType.URGENT_HIGH
        }

        val resugaring = next.resugaring
        if (resugaring != null) {
            val elapsedMillis = nowMillis - resugaring.startedAtMillis
            if (elapsedMillis >= settings.resugaringSnooze.inWholeMilliseconds) {
                next = next.copy(resugaring = null)
            } else {
                suppressed += AlarmType.LOW
                suppressed += AlarmType.LOW_SOON
                // Right after a severe low the sensor often reports errors while glucose is already
                // rising: a data loss only rings if it outlasts the snooze.
                suppressed += AlarmType.STALE_DATA
                if (rate != null && rate <= -settings.fastDropMgDlPerMin) {
                    conditions += AlarmType.FAST_DROP
                }
                if (fresh != null && hasNotRecovered(resugaring, fresh, elapsedMillis)) {
                    conditions += AlarmType.NO_RECOVERY
                }
            }
        }

        // A silence only lasts while its condition does: a new occurrence must sound immediately.
        var silences = next.silencedUntilMillis.filter { (type, untilMillis) ->
            type in conditions && nowMillis < untilMillis
        }

        val ringing = conditions
            .filter { it !in suppressed && it !in silences && it !in settings.disabledAlarms }
            .sortedBy { it.ordinal }
        val alarm = ringing.firstOrNull { it in settings.continuousAlarms }
        val oneShots = ringing.filter { it !in settings.continuousAlarms }
        // A non-continuous alarm rings once, unless a higher continuous one is already ringing,
        // then stays silent for its repeat interval (or its whole episode) as if acknowledged.
        val alert = oneShots.firstOrNull()?.takeIf { alarm == null || it.ordinal < alarm.ordinal }
        if (alert != null) {
            silences = silences + oneShots.associateWith { silenceEnd(it, nowMillis) }
        }
        next = next.copy(silencedUntilMillis = silences)

        return Evaluation(
            state = next,
            conditions = conditions,
            alarm = alarm,
            alert = alert,
            freshReading = fresh,
            rateMgDlPerMin = rate,
        )
    }

    /** Opens the resugaring snooze: the low alarm stays silent, safety overrides stay armed. */
    fun logResugaring(state: AlarmState, readings: List<GlucoseReading>, nowMillis: Long): AlarmState = state.copy(
        resugaring = Resugaring(
            startedAtMillis = nowMillis,
            glucoseAtStartMgDl = freshReading(readings, nowMillis)?.mgDl,
        ),
        // The snooze replaces any acknowledgement, so the low alarm sounds again as soon as it ends.
        silencedUntilMillis = state.silencedUntilMillis - AlarmType.LOW,
    )

    fun cancelResugaring(state: AlarmState): AlarmState = state.copy(resugaring = null)

    /**
     * Silences every currently active condition for its repeat interval, or until it ends for the
     * [AlarmSettings.ONCE_PER_EPISODE] alarms. Silencing them all,
     * not only the sounding one, keeps a lower-priority alarm from taking over right away.
     */
    fun acknowledge(state: AlarmState, conditions: Set<AlarmType>, nowMillis: Long): AlarmState =
        state.copy(
            silencedUntilMillis = state.silencedUntilMillis + conditions.associateWith { silenceEnd(it, nowMillis) },
        )

    /** A silence is dropped as soon as its condition ends, so [Long.MAX_VALUE] lasts for the episode. */
    private fun silenceEnd(type: AlarmType, nowMillis: Long): Long =
        if (type in AlarmSettings.ONCE_PER_EPISODE) Long.MAX_VALUE else nowMillis + settings.repeatIntervals.getValue(type).inWholeMilliseconds

    private fun freshReading(readings: List<GlucoseReading>, nowMillis: Long): GlucoseReading? =
        readings.maxByOrNull { it.timestampMillis }
            ?.takeIf { nowMillis - it.timestampMillis <= settings.staleDataAfter.inWholeMilliseconds }

    private fun hasNotRecovered(resugaring: Resugaring, fresh: GlucoseReading, elapsedMillis: Long): Boolean {
        val glucoseAtStart = resugaring.glucoseAtStartMgDl ?: return false
        return elapsedMillis >= settings.recoveryCheckDelay.inWholeMilliseconds &&
            fresh.timestampMillis > resugaring.startedAtMillis &&
            fresh.mgDl <= glucoseAtStart
    }

    private fun AlarmState.withZones(mgDl: Int): AlarmState {
        val h = settings.hysteresisMgDl
        return copy(
            inUrgentLowZone = if (inUrgentLowZone) mgDl < settings.urgentLowMgDl + h else mgDl < settings.urgentLowMgDl,
            inLowZone = if (inLowZone) mgDl < settings.lowMgDl + h else mgDl < settings.lowMgDl,
            inHighZone = aboveZone(inHighZone, mgDl, settings.highMgDl),
            inUrgentHighZone = aboveZone(inUrgentHighZone, mgDl, settings.urgentHighMgDl),
        )
    }

    /**
     * Extrapolates the trend over [AlarmSettings.lowSoonHorizon]. A low already reached is
     * [AlarmType.LOW]; a prediction ends once it clears the low threshold by the hysteresis.
     * Glucose rising since the previous reading predicts nothing: right after the bottom of a
     * dip, the slope over the whole window is still negative.
     */
    private fun AlarmState.withLowSoon(fresh: GlucoseReading, rate: Double?, readings: List<GlucoseReading>): AlarmState {
        val previous = readings.filter { it.timestampMillis < fresh.timestampMillis }.maxByOrNull { it.timestampMillis }
        val rising = previous != null && fresh.mgDl > previous.mgDl
        val predicted = rate?.let { fresh.mgDl + it * settings.lowSoonHorizon.inWholeMinutes }
        val limit = settings.lowMgDl + if (lowSoon) settings.hysteresisMgDl else 0
        return copy(lowSoon = !inLowZone && !rising && predicted != null && predicted < limit)
    }

    private fun aboveZone(inZone: Boolean, mgDl: Int, threshold: Int?): Boolean = when {
        threshold == null -> false
        inZone -> mgDl > threshold - settings.hysteresisMgDl
        else -> mgDl > threshold
    }
}
