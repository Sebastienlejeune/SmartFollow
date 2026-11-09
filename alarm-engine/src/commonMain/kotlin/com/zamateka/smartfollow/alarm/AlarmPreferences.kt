package com.zamateka.smartfollow.alarm

import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.minutes

@Serializable
data class AlarmTypePreferences(
    val enabled: Boolean = true,
    /** Ringtone chosen by the user, or `null` for the built-in sound of the alarm type. */
    val soundUri: String? = null,
    val vibrate: Boolean = true,
    /** How long the alarm stays silent while its condition lasts: after "OK", or after it rang once. */
    val repeatMinutes: Int,
    /** Rings until "OK" is pressed; otherwise rings once, then again after [repeatMinutes]. */
    val continuous: Boolean = false,
    /**
     * Neither sound nor vibration on this phone: the alarm is still detected, logged and shown
     * as a notification. Any alarm can be muted, safety ones included (the user's decision).
     * Does not change what the server notifies through ntfy.
     */
    val muted: Boolean = false,
)

/** Alarm settings editable in the app. [sanitized] keeps every value within its allowed range. */
@Serializable
data class AlarmPreferences(
    val urgentLowMgDl: Int = 55,
    val lowMgDl: Int = 70,
    val targetHighMgDl: Int = 180,
    val highMgDl: Int = 250,
    val urgentHighMgDl: Int = 300,
    val snoozeMinutes: Int = 25,
    val staleMinutes: Int = 15,
    /** Prediction horizon of the low soon alarm. */
    val lowSoonMinutes: Int = 20,
    /** Alarm stream volume while ringing, in percent of the maximum. */
    val volumePercent: Int = 100,
    /**
     * Set of built-in sounds, by id ("private", "default"); the app falls back to its default
     * theme for an unknown id, or a private theme ("private") whose files are not in the
     * build. A string, so that a new theme never breaks an older server.
     */
    val soundTheme: String = "private",
    /** Sends a summary of the night when it ends, at [nightEndHour]. */
    val nightSummary: Boolean = true,
    /** How much lower, in percent, the alarms ring during the night. */
    val nightVolumeReductionPercent: Int = 0,
    /** The night runs from this hour (0-23)... */
    val nightStartHour: Int = 22,
    /** ...to this one, the next day if it is earlier, in the local time zone. */
    val nightEndHour: Int = 7,
    val types: Map<AlarmType, AlarmTypePreferences> = DEFAULT_TYPES,
    /**
     * Id and name of the profile these preferences come from ([AlarmProfiles]), set by the app
     * when it replicates them to the server, which logs a change of profile.
     */
    val profileId: String? = null,
    val profileName: String? = null,
) {
    fun forType(type: AlarmType): AlarmTypePreferences = types.getValue(type)

    /** Alarms detected but muted on the phone. */
    val mutedAlarms: Set<AlarmType> get() = types.filterValues { it.enabled && it.muted }.keys

    /** Alarms that ring on the phone. */
    val audibleAlarms: Set<AlarmType> get() = types.filterValues { it.enabled && !it.muted }.keys

    /** Alarm volume in percent, lowered during the night but never below the minimum volume. */
    fun alarmVolumePercent(night: Boolean): Int =
        if (!night) volumePercent else (volumePercent * (100 - nightVolumeReductionPercent) / 100).coerceAtLeast(VOLUME_RANGE.first)

    fun withType(type: AlarmType, change: (AlarmTypePreferences) -> AlarmTypePreferences): AlarmPreferences =
        copy(types = types + (type to change(forType(type))))

    /** These settings with every alarm muted but [DISCREET_AUDIBLE], which ring. */
    fun discreet(): AlarmPreferences = copy(
        types = types.mapValues { (type, preferences) ->
            if (type in DISCREET_AUDIBLE) preferences.copy(enabled = true, muted = false) else preferences.copy(muted = true)
        },
    )

    fun sanitized(): AlarmPreferences {
        val low = lowMgDl.coerceIn(LOW_RANGE)
        val high = highMgDl.coerceIn(HIGH_RANGE)
        val nightStart = nightStartHour.coerceIn(HOUR_RANGE)
        val nightEnd = nightEndHour.coerceIn(HOUR_RANGE)
        return copy(
            lowMgDl = low,
            // Each zone must stay strictly inside the next one.
            urgentLowMgDl = urgentLowMgDl.coerceIn(URGENT_LOW_RANGE.first, minOf(URGENT_LOW_RANGE.last, low - THRESHOLD_STEP)),
            highMgDl = high,
            // The target range ends between the low and the high thresholds.
            targetHighMgDl = targetHighMgDl.coerceIn(
                maxOf(TARGET_HIGH_RANGE.first, low + HIGH_STEP),
                minOf(TARGET_HIGH_RANGE.last, high - HIGH_STEP),
            ),
            urgentHighMgDl = urgentHighMgDl.coerceIn(maxOf(URGENT_HIGH_RANGE.first, high + HIGH_STEP), URGENT_HIGH_RANGE.last),
            snoozeMinutes = snoozeMinutes.coerceIn(SNOOZE_RANGE),
            staleMinutes = staleMinutes.coerceIn(STALE_RANGE),
            lowSoonMinutes = lowSoonMinutes.coerceIn(LOW_SOON_RANGE),
            volumePercent = volumePercent.coerceIn(VOLUME_RANGE),
            nightVolumeReductionPercent = nightVolumeReductionPercent.coerceIn(NIGHT_VOLUME_REDUCTION_RANGE),
            nightStartHour = nightStart,
            // A night lasts at least one hour.
            nightEndHour = if (nightEnd == nightStart) (nightStart + 1) % 24 else nightEnd,
            types = AlarmType.entries.associateWith { type ->
                val preferences = types[type] ?: DEFAULT_TYPES.getValue(type)
                preferences.copy(
                    enabled = preferences.enabled || type in AlarmSettings.ALWAYS_ENABLED,
                    repeatMinutes = preferences.repeatMinutes.coerceIn(REPEAT_RANGE),
                )
            },
        )
    }

    fun toAlarmSettings(): AlarmSettings = AlarmSettings(
        urgentLowMgDl = urgentLowMgDl,
        lowMgDl = lowMgDl,
        targetHighMgDl = targetHighMgDl,
        highMgDl = highMgDl,
        urgentHighMgDl = urgentHighMgDl,
        resugaringSnooze = snoozeMinutes.minutes,
        staleDataAfter = staleMinutes.minutes,
        lowSoonHorizon = lowSoonMinutes.minutes,
        repeatIntervals = types.mapValues { it.value.repeatMinutes.minutes },
        disabledAlarms = types.filterValues { !it.enabled }.keys,
        continuousAlarms = types.filterValues { it.continuous }.keys,
    )

    companion object {
        const val THRESHOLD_STEP = 5
        const val HIGH_STEP = 10
        const val MINUTES_STEP = 5
        const val VOLUME_STEP = 10

        val URGENT_LOW_RANGE = 40..65
        val LOW_RANGE = 60..100
        val TARGET_HIGH_RANGE = 120..250
        val HIGH_RANGE = 150..350
        val URGENT_HIGH_RANGE = 200..400

        /** The snooze must outlast the 15-minute recovery check of the alarm engine. */
        val SNOOZE_RANGE = 20..60
        val STALE_RANGE = 10..30
        val LOW_SOON_RANGE = 10..30

        val REPEAT_RANGE = 5..60
        val VOLUME_RANGE = 10..100
        val NIGHT_VOLUME_REDUCTION_RANGE = 0..90
        val HOUR_RANGE = 0..23

        /** The alarms that still ring in the "Discreet" profile. */
        val DISCREET_AUDIBLE: Set<AlarmType> = setOf(AlarmType.LOW_SOON, AlarmType.LOW, AlarmType.URGENT_LOW)

        val DEFAULT_TYPES: Map<AlarmType, AlarmTypePreferences> = AlarmType.entries.associateWith { type ->
            AlarmTypePreferences(
                repeatMinutes = AlarmSettings.DEFAULT_REPEAT_INTERVALS.getValue(type).inWholeMinutes.toInt(),
                continuous = type in AlarmSettings.DEFAULT_CONTINUOUS_ALARMS,
            )
        }
    }
}
