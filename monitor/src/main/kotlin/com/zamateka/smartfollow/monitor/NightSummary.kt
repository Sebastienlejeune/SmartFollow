package com.zamateka.smartfollow.monitor

import com.zamateka.smartfollow.alarm.AlarmPreferences
import com.zamateka.smartfollow.alarm.AlarmSettings
import com.zamateka.smartfollow.alarm.MonitorEvent
import com.zamateka.smartfollow.model.GlucoseReading
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.minutes

/** A night, from its start hour to its end hour the next morning (or the same day). */
data class NightWindow(val fromMillis: Long, val toMillis: Long) {
    companion object {
        /** Whether [nowMillis] falls in the night, from [startHour] included to [endHour] excluded, in [zone]. */
        fun isNight(nowMillis: Long, startHour: Int, endHour: Int, zone: ZoneId): Boolean {
            val hour = Instant.ofEpochMilli(nowMillis).atZone(zone).hour
            return if (startHour < endHour) hour in startHour until endHour else hour >= startHour || hour < endHour
        }

        /** The last night that ended at or before [nowMillis], in [zone]. */
        fun lastEnded(nowMillis: Long, startHour: Int, endHour: Int, zone: ZoneId): NightWindow {
            val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
            var endDate = now.toLocalDate()
            if (endDate.atTime(endHour, 0).atZone(zone).isAfter(now)) endDate = endDate.minusDays(1)
            val startDate = if (startHour < endHour) endDate else endDate.minusDays(1)
            return NightWindow(
                fromMillis = startDate.atTime(startHour, 0).atZone(zone).toInstant().toEpochMilli(),
                toMillis = endDate.atTime(endHour, 0).atZone(zone).toInstant().toEpochMilli(),
            )
        }
    }
}

/** What happened during a night, for the parents waking up. Ranges follow the alarm thresholds. */
data class NightSummary(
    val night: NightWindow,
    val readingCount: Int,
    val minMgDl: Int?,
    val maxMgDl: Int?,
    val meanMgDl: Int?,
    /** Low threshold of the target range. */
    val lowMgDl: Int,
    /** Top of the target range. */
    val targetHighMgDl: Int,
    /** Share of the readings from [lowMgDl] to [targetHighMgDl], between 0 and 1. */
    val inTarget: Double,
    /** Time below [lowMgDl], counting 5 minutes per reading. */
    val belowMinutes: Int,
    /** Time above [targetHighMgDl], counting 5 minutes per reading. */
    val aboveMinutes: Int,
    /** Alarms that started to ring. */
    val alarms: Int,
    /** Resugarings logged and not cancelled. */
    val resugarings: Int,
) {
    companion object {
        private const val READING_MINUTES = 5

        fun compute(night: NightWindow, readings: List<GlucoseReading>, events: List<MonitorEvent>, settings: AlarmSettings): NightSummary {
            val values = readings
                .filter { it.timestampMillis in night.fromMillis..night.toMillis }
                .distinctBy { it.timestampMillis }
                .map { it.mgDl }
            val nightEvents = events.filter { it.timestampMillis in night.fromMillis..night.toMillis }
            fun count(kind: MonitorEvent.Kind) = nightEvents.count { it.kind == kind }
            return NightSummary(
                night = night,
                readingCount = values.size,
                minMgDl = values.minOrNull(),
                maxMgDl = values.maxOrNull(),
                meanMgDl = values.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
                lowMgDl = settings.lowMgDl,
                targetHighMgDl = settings.targetHighMgDl,
                inTarget = if (values.isEmpty()) 0.0 else values.count { it in settings.lowMgDl..settings.targetHighMgDl } / values.size.toDouble(),
                belowMinutes = values.count { it < settings.lowMgDl } * READING_MINUTES,
                aboveMinutes = values.count { it > settings.targetHighMgDl } * READING_MINUTES,
                alarms = count(MonitorEvent.Kind.ALARM_RANG),
                resugarings = (count(MonitorEvent.Kind.RESUGARING) - count(MonitorEvent.Kind.RESUGARING_CANCELLED)).coerceAtLeast(0),
            )
        }
    }
}

/** Volume the alarms ring at now, lowered during the night of the preferences. */
fun AlarmPreferences.alarmVolumePercentAt(
    nowMillis: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): Int = alarmVolumePercent(NightWindow.isNight(nowMillis, nightStartHour, nightEndHour, zone))

/**
 * Calls [send] at the end of each night, while the summary is enabled in the preferences. Only
 * nights ending after the start are sent, so that a restart does not send the last one again.
 * Checking every minute follows preference changes and clock or time zone changes.
 */
suspend fun runNightSummaries(
    preferences: () -> AlarmPreferences,
    zone: () -> ZoneId = ZoneId::systemDefault,
    clock: () -> Long = System::currentTimeMillis,
    send: suspend (NightWindow) -> Unit,
) {
    fun lastNight(): NightWindow = preferences().let {
        NightWindow.lastEnded(clock(), it.nightStartHour, it.nightEndHour, zone())
    }
    var handledUntil = lastNight().toMillis
    while (true) {
        delay(1.minutes)
        val night = lastNight()
        if (night.toMillis > handledUntil) {
            handledUntil = night.toMillis
            if (preferences().nightSummary) {
                // A failed summary must not stop the next ones.
                try {
                    send(night)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    System.err.println("Cannot send the night summary: $e")
                }
            }
        }
    }
}
