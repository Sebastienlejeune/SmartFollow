package com.zamateka.smartfollow.monitor

import android.content.Context
import androidx.core.content.edit
import com.zamateka.smartfollow.alarm.AlarmState
import com.zamateka.smartfollow.alarm.AlarmType
import com.zamateka.smartfollow.alarm.Resugaring

/** Persists the alarm state so that a running snooze or acknowledgement survives a process restart. */
class AlarmStateStore(context: Context) : AlarmStateRepository {
    private val prefs = context.getSharedPreferences("alarm_state", Context.MODE_PRIVATE)

    override fun load(): AlarmState? {
        if (!prefs.contains(KEY_STARTED_AT)) return null
        val resugaring = if (prefs.contains(KEY_RESUGARING_AT)) {
            Resugaring(
                startedAtMillis = prefs.getLong(KEY_RESUGARING_AT, 0),
                glucoseAtStartMgDl = prefs.getInt(KEY_RESUGARING_GLUCOSE, -1).takeIf { it >= 0 },
            )
        } else {
            null
        }
        return AlarmState(
            monitoringStartedAtMillis = prefs.getLong(KEY_STARTED_AT, 0),
            inUrgentLowZone = prefs.getBoolean(KEY_URGENT_LOW_ZONE, false),
            inLowZone = prefs.getBoolean(KEY_LOW_ZONE, false),
            inHighZone = prefs.getBoolean(KEY_HIGH_ZONE, false),
            inUrgentHighZone = prefs.getBoolean(KEY_URGENT_HIGH_ZONE, false),
            resugaring = resugaring,
            silencedUntilMillis = decodeSilences(prefs.getString(KEY_SILENCES, null)),
        )
    }

    override fun save(state: AlarmState) = prefs.edit {
        putLong(KEY_STARTED_AT, state.monitoringStartedAtMillis)
        putBoolean(KEY_URGENT_LOW_ZONE, state.inUrgentLowZone)
        putBoolean(KEY_LOW_ZONE, state.inLowZone)
        putBoolean(KEY_HIGH_ZONE, state.inHighZone)
        putBoolean(KEY_URGENT_HIGH_ZONE, state.inUrgentHighZone)
        val resugaring = state.resugaring
        if (resugaring == null) {
            remove(KEY_RESUGARING_AT)
            remove(KEY_RESUGARING_GLUCOSE)
        } else {
            putLong(KEY_RESUGARING_AT, resugaring.startedAtMillis)
            putInt(KEY_RESUGARING_GLUCOSE, resugaring.glucoseAtStartMgDl ?: -1)
        }
        putString(KEY_SILENCES, state.silencedUntilMillis.entries.joinToString(";") { "${it.key.name}=${it.value}" })
    }

    private fun decodeSilences(encoded: String?): Map<AlarmType, Long> =
        encoded.orEmpty().split(";").filter { it.isNotBlank() }.mapNotNull { entry ->
            val (name, until) = entry.split("=").takeIf { it.size == 2 } ?: return@mapNotNull null
            val type = runCatching { AlarmType.valueOf(name) }.getOrNull() ?: return@mapNotNull null
            until.toLongOrNull()?.let { type to it }
        }.toMap()

    private companion object {
        const val KEY_STARTED_AT = "monitoring_started_at"
        const val KEY_URGENT_LOW_ZONE = "urgent_low_zone"
        const val KEY_LOW_ZONE = "low_zone"
        const val KEY_HIGH_ZONE = "high_zone"
        const val KEY_URGENT_HIGH_ZONE = "urgent_high_zone"
        const val KEY_RESUGARING_AT = "resugaring_at"
        const val KEY_RESUGARING_GLUCOSE = "resugaring_glucose"
        const val KEY_SILENCES = "silences"
    }
}
