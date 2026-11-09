package com.zamateka.smartfollow.monitor

import com.zamateka.smartfollow.alarm.AlarmState
import kotlinx.serialization.Serializable

/** Actions a parent can make, shared between devices. */
@Serializable
enum class UserAction {
    RESUGARING,
    RESUGARING_CANCELLED,
    ACKNOWLEDGED,
}

@Serializable
data class UserActionRecord(
    /** Unique id, used to apply an action only once on each device. */
    val id: String,
    val action: UserAction,
    val timestampMillis: Long,
    /** Device the action was made on: "android", "web", "ntfy"... */
    val source: String,
)

/** Where [Monitor] keeps its alarm state between process restarts. */
interface AlarmStateRepository {
    fun load(): AlarmState?
    fun save(state: AlarmState)
}
