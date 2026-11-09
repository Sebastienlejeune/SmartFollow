package com.zamateka.smartfollow.settings

import android.content.Context
import androidx.core.content.edit
import com.zamateka.smartfollow.R
import com.zamateka.smartfollow.alarm.AlarmPreferences
import com.zamateka.smartfollow.alarm.AlarmProfiles
import com.zamateka.smartfollow.alarm.AlarmType
import com.zamateka.smartfollow.alarm.AlarmTypePreferences
import com.zamateka.smartfollow.share.ShareRegion
import com.zamateka.smartfollow.sync.SyncApi
import com.zamateka.smartfollow.sync.SyncServer

/** Dexcom account of the sensor wearer, used to read Share. */
data class ShareAccount(val username: String, val password: String, val region: ShareRegion)

class SettingsStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** The password is stored encrypted with a key that never leaves the Android Keystore. */
    var account: ShareAccount?
        get() {
            val username = prefs.getString(KEY_USERNAME, null) ?: return null
            val password = prefs.getString(KEY_PASSWORD, null)?.let(PasswordCipher::decrypt) ?: return null
            val region = prefs.getString(KEY_REGION, null)
                ?.let { runCatching { ShareRegion.valueOf(it) }.getOrNull() }
                ?: return null
            return ShareAccount(username, password, region)
        }
        set(value) = prefs.edit {
            if (value == null) {
                remove(KEY_USERNAME)
                remove(KEY_PASSWORD)
                remove(KEY_REGION)
            } else {
                putString(KEY_USERNAME, value.username)
                putString(KEY_PASSWORD, PasswordCipher.encrypt(value.password))
                putString(KEY_REGION, value.region.name)
            }
        }

    /** `null` when the app works on its own. The token is stored encrypted, like the password. */
    var syncServer: SyncServer?
        get() {
            val url = prefs.getString(KEY_SYNC_URL, null) ?: return null
            val token = prefs.getString(KEY_SYNC_TOKEN, null)?.let(PasswordCipher::decrypt) ?: return null
            return SyncServer(url, token)
        }
        set(value) = prefs.edit {
            if (value == null) {
                remove(KEY_SYNC_URL)
                remove(KEY_SYNC_TOKEN)
            } else {
                putString(KEY_SYNC_URL, value.url)
                putString(KEY_SYNC_TOKEN, PasswordCipher.encrypt(value.token))
            }
        }

    /** Whether monitoring should run, restored after a reboot or an app update. */
    var monitoringEnabled: Boolean
        get() = prefs.getBoolean(KEY_MONITORING_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_MONITORING_ENABLED, value) }

    /** Settings stored by the versions without profiles, kept to create the first profiles. */
    private val legacyAlarmPreferences: AlarmPreferences
        get() {
            val defaults = AlarmPreferences()
            return AlarmPreferences(
                urgentLowMgDl = prefs.getInt(KEY_URGENT_LOW, defaults.urgentLowMgDl),
                lowMgDl = prefs.getInt(KEY_LOW, defaults.lowMgDl),
                targetHighMgDl = prefs.getInt(KEY_TARGET_HIGH, defaults.targetHighMgDl),
                highMgDl = prefs.getInt(KEY_HIGH, defaults.highMgDl),
                urgentHighMgDl = prefs.getInt(KEY_URGENT_HIGH, defaults.urgentHighMgDl),
                snoozeMinutes = prefs.getInt(KEY_SNOOZE_MINUTES, defaults.snoozeMinutes),
                staleMinutes = prefs.getInt(KEY_STALE_MINUTES, defaults.staleMinutes),
                lowSoonMinutes = prefs.getInt(KEY_LOW_SOON_MINUTES, defaults.lowSoonMinutes),
                volumePercent = prefs.getInt(KEY_VOLUME_PERCENT, defaults.volumePercent),
                soundTheme = prefs.getString(KEY_SOUND_THEME, null) ?: defaults.soundTheme,
                nightSummary = prefs.getBoolean(KEY_NIGHT_SUMMARY, defaults.nightSummary),
                nightVolumeReductionPercent = prefs.getInt(KEY_NIGHT_VOLUME_REDUCTION, defaults.nightVolumeReductionPercent),
                nightStartHour = prefs.getInt(KEY_NIGHT_START_HOUR, defaults.nightStartHour),
                nightEndHour = prefs.getInt(KEY_NIGHT_END_HOUR, defaults.nightEndHour),
                types = AlarmType.entries.associateWith { type ->
                    val default = defaults.forType(type)
                    AlarmTypePreferences(
                        enabled = prefs.getBoolean(typeKey(type, "enabled"), default.enabled),
                        soundUri = prefs.getString(typeKey(type, "sound"), default.soundUri),
                        vibrate = prefs.getBoolean(typeKey(type, "vibrate"), default.vibrate),
                        repeatMinutes = prefs.getInt(typeKey(type, "repeat"), default.repeatMinutes),
                        continuous = prefs.getBoolean(typeKey(type, "continuous"), default.continuous),
                    )
                },
            ).sanitized()
        }

    /**
     * The profiles of alarm settings, kept as JSON. The first time, the settings stored by the
     * versions without profiles become "Home", copied into "School". The "Discreet" profile is
     * added once, to new and existing installs; once deleted, it does not come back.
     */
    var profiles: AlarmProfiles
        get() {
            val profiles = prefs.getString(KEY_PROFILES, null)
                ?.let { json -> runCatching { SyncApi.json.decodeFromString(AlarmProfiles.serializer(), json) }.getOrNull() }
                ?.let { stored -> stored.copy(profiles = stored.profiles.map { it.copy(preferences = it.preferences.sanitized()) }) }
                ?: AlarmProfiles.initial(
                    legacyAlarmPreferences,
                    homeName = context.getString(R.string.profile_home),
                    schoolName = context.getString(R.string.profile_school),
                )
            if (prefs.getBoolean(KEY_DISCREET_ADDED, false)) return profiles
            return profiles.withDiscreet(context.getString(R.string.profile_discreet)).also {
                this.profiles = it
                prefs.edit { putBoolean(KEY_DISCREET_ADDED, true) }
            }
        }
        set(value) = prefs.edit { putString(KEY_PROFILES, SyncApi.json.encodeToString(AlarmProfiles.serializer(), value)) }

    /** The profile active before the current one, where the "Discreet" switch goes back to. */
    var previousProfileId: String?
        get() = prefs.getString(KEY_PREVIOUS_PROFILE, null)
        set(value) = prefs.edit { putString(KEY_PREVIOUS_PROFILE, value) }

    /** Settings of the active profile. */
    var alarmPreferences: AlarmPreferences
        get() = profiles.active.preferences
        set(value) {
            val current = profiles
            profiles = current.update(current.activeId, value.sanitized())
        }

    private fun typeKey(type: AlarmType, field: String) = "alarm_${type.name.lowercase()}_$field"

    private companion object {
        const val KEY_PROFILES = "profiles"
        const val KEY_DISCREET_ADDED = "discreet_profile_added"
        const val KEY_PREVIOUS_PROFILE = "previous_profile_id"
        const val KEY_URGENT_LOW = "urgent_low_mg_dl"
        const val KEY_LOW = "low_mg_dl"
        const val KEY_TARGET_HIGH = "target_high_mg_dl"
        const val KEY_HIGH = "high_mg_dl"
        const val KEY_URGENT_HIGH = "urgent_high_mg_dl"
        const val KEY_SNOOZE_MINUTES = "snooze_minutes"
        const val KEY_STALE_MINUTES = "stale_minutes"
        const val KEY_LOW_SOON_MINUTES = "low_soon_minutes"
        const val KEY_VOLUME_PERCENT = "volume_percent"
        const val KEY_SOUND_THEME = "sound_theme"
        const val KEY_NIGHT_SUMMARY = "night_summary"
        const val KEY_NIGHT_VOLUME_REDUCTION = "night_volume_reduction_percent"
        const val KEY_NIGHT_START_HOUR = "night_start_hour"
        const val KEY_NIGHT_END_HOUR = "night_end_hour"
        const val KEY_USERNAME = "share_username"
        const val KEY_PASSWORD = "share_password"
        const val KEY_REGION = "share_region"
        const val KEY_MONITORING_ENABLED = "monitoring_enabled"
        const val KEY_SYNC_URL = "sync_url"
        const val KEY_SYNC_TOKEN = "sync_token"
    }
}
