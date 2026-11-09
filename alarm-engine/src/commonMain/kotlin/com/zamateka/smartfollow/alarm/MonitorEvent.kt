package com.zamateka.smartfollow.alarm

import kotlinx.serialization.Serializable

/** An entry of the monitoring history. */
@Serializable
data class MonitorEvent(
    val timestampMillis: Long,
    val kind: Kind,
    /** The alarm concerned, for the condition and alarm kinds. */
    val alarmType: AlarmType? = null,
    /** Fresh glucose at the time of the event, if any. */
    val mgDl: Int? = null,
    /** Where a user action was made ("android", "web"...), when it came from another device. */
    val source: String? = null,
    /** Name of the profile activated, for [Kind.PROFILE_CHANGED]. */
    val profile: String? = null,
) {
    enum class Kind {
        /** An alarm condition became true: threshold crossed, fast drop, data loss... */
        CONDITION_STARTED,

        /** An alarm condition is over: back past the threshold, data back... */
        CONDITION_ENDED,

        /** An alarm started to sound. */
        ALARM_RANG,
        ACKNOWLEDGED,
        RESUGARING,
        RESUGARING_CANCELLED,

        /** The resugaring snooze ran to its end. */
        SNOOZE_ENDED,
        MONITORING_STARTED,
        MONITORING_STOPPED,

        /** Another profile of alarm settings became active. */
        PROFILE_CHANGED,
    }
}
