package com.zamateka.smartfollow.alarm

import com.zamateka.smartfollow.alarm.MonitorEvent.Kind
import com.zamateka.smartfollow.model.GlucoseReading

/** A line of the history page. */
sealed interface HistoryEntry {
    val timestampMillis: Long

    /** An event shown as it is: resugaring, profile change... */
    data class Single(val event: MonitorEvent) : HistoryEntry {
        override val timestampMillis: Long get() = event.timestampMillis
    }

    /** An alarm condition from its start to its end, with the alarms it rang. */
    data class Episode(
        val type: AlarmType,
        override val timestampMillis: Long,
        /** `null` while the condition goes on. */
        val endMillis: Long?,
        val alarmCount: Int,
        /** Lowest glucose of a low, highest of a high, over the episode; `null` for a data loss. */
        val extremeMgDl: Int?,
    ) : HistoryEntry
}

/** See [HistoryEntries.summarize]. */
data class HistorySummary(val episodes: List<Count>, val resugarings: Int) {
    /** [count] episodes of [type]; the lowest glucose of a low, the highest of a high. */
    data class Count(val type: AlarmType, val count: Int, val extremeMgDl: Int?)
}

/**
 * Turns the history events into the lines of the history page, newest first. Each alarm
 * condition becomes one [HistoryEntry.Episode] instead of its start, end and repeated alarms;
 * a low soon that never rang is left out, as are technical events ("OK" presses, monitoring
 * started or stopped, end of the resugaring snooze). Mirrored by the web page.
 */
object HistoryEntries {
    private val HIDDEN = setOf(Kind.ACKNOWLEDGED, Kind.MONITORING_STARTED, Kind.MONITORING_STOPPED, Kind.SNOOZE_ENDED)
    /** The alarms of a low: their episodes keep the lowest glucose, the others the highest. */
    val LOWS = setOf(AlarmType.URGENT_LOW, AlarmType.FAST_DROP, AlarmType.NO_RECOVERY, AlarmType.LOW, AlarmType.LOW_SOON)

    /** [readings] give the extreme glucose of each episode; any order. */
    fun build(events: List<MonitorEvent>, readings: List<GlucoseReading> = emptyList()): List<HistoryEntry> {
        val sortedReadings = readings.sortedBy { it.timestampMillis }
        val entries = mutableListOf<HistoryEntry>()
        val open = mutableMapOf<AlarmType, OpenEpisode>()

        fun close(type: AlarmType, endMillis: Long?) {
            val episode = open.remove(type) ?: return
            // A low soon that stayed silent tells nothing: the low that may follow has its own line.
            if (type == AlarmType.LOW_SOON && episode.alarmCount == 0) return
            entries += HistoryEntry.Episode(
                type = type,
                timestampMillis = episode.startMillis,
                endMillis = endMillis,
                alarmCount = episode.alarmCount,
                extremeMgDl = extreme(type, episode, endMillis, sortedReadings),
            )
        }

        events.sortedBy { it.timestampMillis }.forEach { event ->
            val type = event.alarmType
            when {
                // The conditions are not followed while monitoring is off: an episode ends with it.
                event.kind == Kind.MONITORING_STOPPED || event.kind == Kind.MONITORING_STARTED ->
                    open.keys.toList().forEach { close(it, event.timestampMillis) }
                event.kind in HIDDEN -> Unit
                type != null && event.kind == Kind.CONDITION_STARTED ->
                    open.getOrPut(type) { OpenEpisode(event.timestampMillis) }.values += listOfNotNull(event.mgDl)
                type != null && event.kind == Kind.CONDITION_ENDED -> close(type, event.timestampMillis)
                type != null && event.kind == Kind.ALARM_RANG -> open.getOrPut(type) { OpenEpisode(event.timestampMillis) }.run {
                    alarmCount++
                    values += listOfNotNull(event.mgDl)
                }
                else -> entries += HistoryEntry.Single(event)
            }
        }
        open.keys.toList().forEach { close(it, null) }
        return entries.sortedByDescending { it.timestampMillis }
    }

    /**
     * What a period of the history (a week, past the last days shown in detail) sums up to:
     * its episodes counted by type, in [SUMMARY_ORDER], and its resugarings not cancelled.
     */
    fun summarize(entries: List<HistoryEntry>): HistorySummary {
        val episodes = entries.filterIsInstance<HistoryEntry.Episode>().groupBy { it.type }
        val kinds = entries.filterIsInstance<HistoryEntry.Single>().map { it.event.kind }
        return HistorySummary(
            episodes = SUMMARY_ORDER.mapNotNull { type ->
                val ofType = episodes[type] ?: return@mapNotNull null
                val extremes = ofType.mapNotNull { it.extremeMgDl }
                HistorySummary.Count(type, ofType.size, if (type in LOWS) extremes.minOrNull() else extremes.maxOrNull())
            },
            resugarings = (kinds.count { it == Kind.RESUGARING } - kinds.count { it == Kind.RESUGARING_CANCELLED }).coerceAtLeast(0),
        )
    }

    /** The parents' order, as on the web page's notification choices. */
    val SUMMARY_ORDER: List<AlarmType> = listOf(
        AlarmType.LOW_SOON, AlarmType.LOW, AlarmType.URGENT_LOW, AlarmType.HIGH,
        AlarmType.URGENT_HIGH, AlarmType.FAST_DROP, AlarmType.STALE_DATA, AlarmType.NO_RECOVERY,
    )

    private class OpenEpisode(val startMillis: Long) {
        var alarmCount = 0
        val values = mutableListOf<Int>()
    }

    private fun extreme(type: AlarmType, episode: OpenEpisode, endMillis: Long?, readings: List<GlucoseReading>): Int? {
        if (type == AlarmType.STALE_DATA) return null
        val from = lowerBound(readings, episode.startMillis)
        val to = if (endMillis == null) readings.size else lowerBound(readings, endMillis + 1)
        val values = episode.values + readings.subList(from, maxOf(from, to)).map { it.mgDl }
        return if (type in LOWS) values.minOrNull() else values.maxOrNull()
    }

    /** Index of the first reading at or after [millis]. */
    private fun lowerBound(readings: List<GlucoseReading>, millis: Long): Int {
        var low = 0
        var high = readings.size
        while (low < high) {
            val middle = (low + high) / 2
            if (readings[middle].timestampMillis < millis) low = middle + 1 else high = middle
        }
        return low
    }
}
