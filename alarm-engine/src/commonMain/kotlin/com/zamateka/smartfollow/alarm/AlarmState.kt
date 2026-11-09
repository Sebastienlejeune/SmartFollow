package com.zamateka.smartfollow.alarm

import com.zamateka.smartfollow.model.GlucoseReading
import kotlinx.serialization.Serializable

/** Everything the engine remembers between two evaluations. Persist it to survive process restarts. */
@Serializable
data class AlarmState(
    /** Start of monitoring; used to raise [AlarmType.STALE_DATA] when no reading was ever received. */
    val monitoringStartedAtMillis: Long,
    val inUrgentLowZone: Boolean = false,
    val inLowZone: Boolean = false,
    /** The trend predicts a low; see [AlarmType.LOW_SOON]. */
    val lowSoon: Boolean = false,
    val inHighZone: Boolean = false,
    val inUrgentHighZone: Boolean = false,
    val resugaring: Resugaring? = null,
    /** Acknowledged alarms and the time until which they stay silent. */
    val silencedUntilMillis: Map<AlarmType, Long> = emptyMap(),
)

/** A resugaring logged by the user, which opens the snooze window. */
@Serializable
data class Resugaring(
    val startedAtMillis: Long,
    /** Latest fresh glucose when the resugaring was logged, or `null` if none was available. */
    val glucoseAtStartMgDl: Int?,
)

/** Result of [AlarmEngine.evaluate]. */
data class Evaluation(
    val state: AlarmState,
    /** Every condition currently true, including silenced or snoozed ones (for display). */
    val conditions: Set<AlarmType>,
    /** The continuous alarm that must sound until acknowledged, or `null`. */
    val alarm: AlarmType?,
    /** A non-continuous alarm to ring once now, or `null`; it is already silenced for its repeat interval. */
    val alert: AlarmType? = null,
    /** Latest reading, if it is fresh enough to be trusted. */
    val freshReading: GlucoseReading?,
    /** Current glucose rate of change in mg/dL per minute, if computable. */
    val rateMgDlPerMin: Double?,
)
