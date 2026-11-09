package com.zamateka.smartfollow.alarm

/**
 * Alarm conditions, declared from highest to lowest priority: when several
 * conditions are active, the first one in this order is the one that sounds.
 */
enum class AlarmType {
    /** Glucose below the urgent low threshold. Never suppressed by a resugaring snooze. */
    URGENT_LOW,

    /** Glucose dropping fast while a resugaring snooze is active. */
    FAST_DROP,

    /** Glucose has not risen since the resugaring, once the recovery check delay has elapsed. */
    NO_RECOVERY,

    /** Glucose below the low threshold. Suppressed while a resugaring snooze is active. */
    LOW,

    /**
     * Glucose still above the low threshold, but its trend crosses it within the prediction
     * horizon. Suppressed while a resugaring snooze is active, like [LOW].
     */
    LOW_SOON,

    /**
     * No fresh reading: sensor signal loss, network failure or Share outage. Suppressed while a
     * resugaring snooze is active, since the sensor often struggles right after a severe low.
     */
    STALE_DATA,

    /** Glucose above the urgent high threshold. */
    URGENT_HIGH,

    /** Glucose above the high threshold. */
    HIGH,
}

/** Alarms for which "resugaring done" is a sensible answer. */
fun AlarmType.offersResugaring(): Boolean =
    this == AlarmType.URGENT_LOW || this == AlarmType.FAST_DROP || this == AlarmType.NO_RECOVERY ||
        this == AlarmType.LOW || this == AlarmType.LOW_SOON
