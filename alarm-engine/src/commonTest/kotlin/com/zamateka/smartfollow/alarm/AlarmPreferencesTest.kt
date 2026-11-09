package com.zamateka.smartfollow.alarm

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class AlarmPreferencesTest {

    @Test
    fun defaultsMatchEngineDefaults() {
        assertEquals(AlarmSettings(), AlarmPreferences().toAlarmSettings())
    }

    @Test
    fun urgentLowStaysBelowLow() {
        val sanitized = AlarmPreferences(urgentLowMgDl = 65, lowMgDl = 60).sanitized()
        assertEquals(60, sanitized.lowMgDl)
        assertEquals(55, sanitized.urgentLowMgDl)
    }

    @Test
    fun valuesAreClampedToTheirRanges() {
        val sanitized = AlarmPreferences(
            lowMgDl = 200,
            highMgDl = 1000,
            snoozeMinutes = 5,
            staleMinutes = 90,
            volumePercent = 0,
        ).withType(AlarmType.LOW) { it.copy(repeatMinutes = 0) }.sanitized()
        assertEquals(100, sanitized.lowMgDl)
        assertEquals(350, sanitized.highMgDl)
        assertEquals(20, sanitized.snoozeMinutes)
        assertEquals(30, sanitized.staleMinutes)
        assertEquals(10, sanitized.volumePercent)
        assertEquals(5, sanitized.forType(AlarmType.LOW).repeatMinutes)
    }

    @Test
    fun safetyAlarmsCannotBeDisabled() {
        val sanitized = AlarmType.entries
            .fold(AlarmPreferences()) { preferences, type -> preferences.withType(type) { it.copy(enabled = false) } }
            .sanitized()
        assertEquals(AlarmSettings.ALWAYS_ENABLED, AlarmType.entries.filter { sanitized.forType(it).enabled }.toSet())
        assertEquals(AlarmType.entries.toSet() - AlarmSettings.ALWAYS_ENABLED, sanitized.toAlarmSettings().disabledAlarms)
    }

    @Test
    fun preferencesMapToEngineSettings() {
        val settings = AlarmPreferences(snoozeMinutes = 30, staleMinutes = 20)
            .withType(AlarmType.HIGH) { it.copy(enabled = false, repeatMinutes = 45) }
            .toAlarmSettings()
        assertEquals(30.minutes, settings.resugaringSnooze)
        assertEquals(20.minutes, settings.staleDataAfter)
        assertEquals(45.minutes, settings.repeatIntervals.getValue(AlarmType.HIGH))
        assertTrue(AlarmType.HIGH in settings.disabledAlarms)
    }

    @Test
    fun predictionMapsToEngineSettings() {
        assertEquals(15.minutes, AlarmPreferences(lowSoonMinutes = 15).toAlarmSettings().lowSoonHorizon)
    }

    @Test
    fun nightLowersTheVolumeButNotBelowTheMinimum() {
        val preferences = AlarmPreferences(volumePercent = 80, nightVolumeReductionPercent = 50)
        assertEquals(80, preferences.alarmVolumePercent(night = false))
        assertEquals(40, preferences.alarmVolumePercent(night = true))
        assertEquals(10, AlarmPreferences(volumePercent = 10, nightVolumeReductionPercent = 90).alarmVolumePercent(night = true))
        assertEquals(100, AlarmPreferences().alarmVolumePercent(night = true), "no reduction by default")
        assertEquals(90, AlarmPreferences(nightVolumeReductionPercent = 100).sanitized().nightVolumeReductionPercent)
    }

    @Test
    fun alarmsRingByDefaultAndAnyCanBeMuted() {
        assertTrue(AlarmType.entries.none { AlarmPreferences().forType(it).muted })
        val muted = AlarmPreferences().withType(AlarmType.URGENT_LOW) { it.copy(muted = true) }.sanitized()
        assertTrue(muted.forType(AlarmType.URGENT_LOW).muted, "the user may mute even a safety alarm")
        assertTrue(muted.forType(AlarmType.URGENT_LOW).enabled, "muted, it is still detected")
    }

    @Test
    fun preferencesStoredBeforeMutingStillLoad() {
        val json = """{"types":{"LOW":{"enabled":true,"vibrate":false,"repeatMinutes":10}}}"""
        val low = Json { ignoreUnknownKeys = true }.decodeFromString(AlarmPreferences.serializer(), json).forType(AlarmType.LOW)
        assertFalse(low.muted)
        assertFalse(low.vibrate)
    }

    @Test
    fun discreetMutesEverythingButTheLows() {
        val discreet = AlarmPreferences(lowMgDl = 75)
            .withType(AlarmType.LOW_SOON) { it.copy(enabled = false, muted = true) }
            .discreet()
        assertEquals(75, discreet.lowMgDl, "thresholds are kept")
        assertEquals(AlarmPreferences.DISCREET_AUDIBLE, AlarmType.entries.filter { !discreet.forType(it).muted }.toSet())
        assertTrue(AlarmPreferences.DISCREET_AUDIBLE.all { discreet.forType(it).enabled })
    }

    @Test
    fun nightLastsAtLeastOneHour() {
        val sanitized = AlarmPreferences(nightStartHour = 23, nightEndHour = 23).sanitized()
        assertEquals(23, sanitized.nightStartHour)
        assertEquals(0, sanitized.nightEndHour)
        assertEquals(23, AlarmPreferences(nightStartHour = 30, nightEndHour = -1).sanitized().nightStartHour)
    }
}
