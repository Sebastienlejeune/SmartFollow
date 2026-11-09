package com.zamateka.smartfollow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zamateka.smartfollow.R
import com.zamateka.smartfollow.alarm.AlarmSettings
import com.zamateka.smartfollow.alarm.AlarmType
import com.zamateka.smartfollow.alarm.HistoryEntries
import com.zamateka.smartfollow.alarm.HistoryEntry
import com.zamateka.smartfollow.alarm.HistorySummary
import com.zamateka.smartfollow.alarm.MonitorEvent
import com.zamateka.smartfollow.alarm.MonitorEvent.Kind
import com.zamateka.smartfollow.history.ReadingStore
import com.zamateka.smartfollow.model.GlucoseReading
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.TemporalAdjusters

/** Days shown in detail, today included; older entries are grouped by week. */
private const val DETAILED_DAYS = 7L

/**
 * Monitoring history, newest first: each alarm condition on one line from its start to its end
 * (see [HistoryEntries]). The last [DETAILED_DAYS] days are grouped by day; older entries by
 * week (Monday to Sunday), summed up under the week's title ([HistoryEntries.summarize]). Each
 * day and week folds under its title; only today is unfolded at first. Mirrored by the web page.
 */
@Composable
fun HistoryScreen(events: List<MonitorEvent>, readingStore: ReadingStore, settings: AlarmSettings) {
    val zone = ZoneId.systemDefault()
    // The readings give the lowest or highest glucose of each episode.
    val readings by produceState(emptyList<GlucoseReading>(), events) {
        value = events.minOfOrNull { it.timestampMillis }?.let { readingStore.since(it) }.orEmpty()
    }
    val shown = remember(events, readings) { HistoryEntries.build(events, readings) }
    val today = LocalDate.now(zone)
    val dateOf = { entry: HistoryEntry -> Instant.ofEpochMilli(entry.timestampMillis).atZone(zone).toLocalDate() }
    val (byDay, byWeek) = remember(shown, today) {
        val (recent, older) = shown.partition { dateOf(it) > today.minusDays(DETAILED_DAYS) }
        recent.groupBy(dateOf) to older.groupBy { dateOf(it).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
    }
    val timeFormat = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(zone) }
    val dayFormat = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL) }
    val weekFormat = remember { DateTimeFormatter.ofPattern("d MMMM yyyy") }
    // Days and weeks whose folding differs from the default (today unfolded, the others folded).
    var toggled by rememberSaveable { mutableStateOf(setOf<String>()) }
    val toggle = { key: String -> toggled = if (key in toggled) toggled - key else toggled + key }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        if (shown.isEmpty()) {
            item {
                Text(stringResource(R.string.history_empty), Modifier.padding(vertical = 24.dp))
            }
        }
        byDay.forEach { (day, dayEntries) ->
            val key = "day-$day"
            val unfolded = (day == today) != (key in toggled)
            item(key = key) {
                SectionHeader(
                    title = when (day) {
                        today -> stringResource(R.string.today)
                        today.minusDays(1) -> stringResource(R.string.yesterday)
                        else -> dayFormat.format(day)
                    },
                    summary = null,
                    count = dayEntries.size,
                    unfolded = unfolded,
                    onClick = { toggle(key) },
                )
            }
            if (unfolded) entryItems(dayEntries, timeFormat, settings)
        }
        byWeek.forEach { (monday, weekEntries) ->
            val key = "week-$monday"
            val unfolded = key in toggled
            item(key = key) {
                SectionHeader(
                    title = stringResource(R.string.history_week, weekFormat.format(monday)),
                    summary = summaryText(HistoryEntries.summarize(weekEntries)),
                    count = weekEntries.size,
                    unfolded = unfolded,
                    onClick = { toggle(key) },
                )
            }
            if (unfolded) {
                weekEntries.groupBy(dateOf).forEach { (day, dayEntries) ->
                    item(key = "$key-$day") {
                        Text(
                            dayFormat.format(day),
                            Modifier.padding(top = 12.dp, bottom = 4.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    entryItems(dayEntries, timeFormat, settings)
                }
            }
        }
    }
}

private fun LazyListScope.entryItems(entries: List<HistoryEntry>, timeFormat: DateTimeFormatter, settings: AlarmSettings) {
    items(entries) { entry ->
        val time = timeFormat.format(Instant.ofEpochMilli(entry.timestampMillis))
        when (entry) {
            is HistoryEntry.Single -> EventRow(entry.event, time, settings)
            is HistoryEntry.Episode -> EpisodeRow(entry, time, timeFormat, settings)
        }
    }
}

/** Title of a day or a week, which folds or unfolds its entries; a week also shows its [summary]. */
@Composable
private fun SectionHeader(title: String, summary: String?, count: Int, unfolded: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(top = 16.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (unfolded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            Text(
                count.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        summary?.let {
            Text(
                it,
                Modifier.padding(start = 32.dp, top = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
    HorizontalDivider()
}

/** "Low ×2 (min 62) · High ×1 (max 290) · Resugaring ×1". */
@Composable
private fun summaryText(summary: HistorySummary): String {
    val episodes = summary.episodes.map { count ->
        val label = if (count.type == AlarmType.STALE_DATA) stringResource(R.string.event_data_lost) else stringResource(count.type.labelRes())
        val counted = stringResource(R.string.history_week_count, label, count.count)
        val extreme = count.extremeMgDl
        when {
            extreme == null -> counted
            count.type in HistoryEntries.LOWS -> stringResource(R.string.history_week_min, counted, extreme)
            else -> stringResource(R.string.history_week_max, counted, extreme)
        }
    }
    val resugarings = if (summary.resugarings > 0) {
        listOf(stringResource(R.string.history_week_count, stringResource(R.string.event_resugaring), summary.resugarings))
    } else {
        emptyList()
    }
    val parts = episodes + resugarings
    return if (parts.isEmpty()) stringResource(R.string.history_week_empty) else parts.joinToString(" · ")
}

@Composable
private fun EventRow(event: MonitorEvent, time: String, settings: AlarmSettings) {
    EntryRow(
        time = time,
        color = eventColor(event),
        label = eventLabel(event) + sourceSuffix(event.source),
        detail = null,
        bold = event.kind == Kind.RESUGARING,
        mgDl = event.mgDl,
        settings = settings,
    )
}

/** "Low", then "until 21:40 (37 min) · 3 alarms", with the lowest glucose of the episode. */
@Composable
private fun EpisodeRow(episode: HistoryEntry.Episode, time: String, timeFormat: DateTimeFormatter, settings: AlarmSettings) {
    val end = episode.endMillis
    val period = if (end == null) {
        stringResource(R.string.history_ongoing)
    } else {
        stringResource(R.string.history_until, timeFormat.format(Instant.ofEpochMilli(end)), durationText(end - episode.timestampMillis))
    }
    val alarms = if (episode.alarmCount > 0) {
        " · " + pluralStringResource(R.plurals.night_summary_alarms, episode.alarmCount, episode.alarmCount)
    } else {
        ""
    }
    EntryRow(
        time = time,
        color = if (episode.alarmCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
        label = if (episode.type == AlarmType.STALE_DATA) stringResource(R.string.event_data_lost) else stringResource(episode.type.labelRes()),
        detail = period + alarms,
        bold = episode.alarmCount > 0,
        mgDl = episode.extremeMgDl,
        settings = settings,
    )
}

@Composable
private fun durationText(millis: Long): String {
    val minutes = (millis / 60_000).toInt()
    return if (minutes < 60) stringResource(R.string.minutes_value, minutes) else stringResource(R.string.hours_minutes, minutes / 60, minutes % 60)
}

@Composable
private fun EntryRow(time: String, color: Color, label: String, detail: String?, bold: Boolean, mgDl: Int?, settings: AlarmSettings) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(time, Modifier.width(56.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
            detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }
        mgDl?.let {
            Text(
                stringResource(R.string.glucose_mg_dl, it),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = glucoseColor(it, settings),
            )
        }
    }
}

@Composable
private fun eventColor(event: MonitorEvent): Color = when (event.kind) {
    Kind.ALARM_RANG, Kind.CONDITION_STARTED -> MaterialTheme.colorScheme.error
    Kind.CONDITION_ENDED -> Color(0xFF43A047)
    Kind.RESUGARING, Kind.RESUGARING_CANCELLED, Kind.SNOOZE_ENDED -> MaterialTheme.colorScheme.tertiary
    Kind.ACKNOWLEDGED, Kind.MONITORING_STARTED, Kind.MONITORING_STOPPED, Kind.PROFILE_CHANGED -> MaterialTheme.colorScheme.outline
}

@Composable
private fun eventLabel(event: MonitorEvent): String {
    val type = event.alarmType
    val typeLabel = type?.let { stringResource(it.labelRes()) }.orEmpty()
    return when (event.kind) {
        Kind.CONDITION_STARTED ->
            if (type == AlarmType.STALE_DATA) stringResource(R.string.event_data_lost) else stringResource(R.string.event_condition_started, typeLabel)
        Kind.CONDITION_ENDED ->
            if (type == AlarmType.STALE_DATA) stringResource(R.string.event_data_back) else stringResource(R.string.event_condition_ended, typeLabel)
        Kind.ALARM_RANG -> stringResource(R.string.event_alarm_rang, typeLabel)
        Kind.ACKNOWLEDGED ->
            if (type != null) stringResource(R.string.event_acknowledged_type, typeLabel) else stringResource(R.string.event_acknowledged)
        Kind.RESUGARING -> stringResource(R.string.event_resugaring)
        Kind.RESUGARING_CANCELLED -> stringResource(R.string.event_resugaring_cancelled)
        Kind.SNOOZE_ENDED -> stringResource(R.string.event_snooze_ended)
        Kind.MONITORING_STARTED -> stringResource(R.string.event_monitoring_started)
        Kind.MONITORING_STOPPED -> stringResource(R.string.event_monitoring_stopped)
        Kind.PROFILE_CHANGED -> stringResource(R.string.event_profile_changed, event.profile.orEmpty())
    }
}

/** Resugarings still standing (not cancelled right after), oldest first, for the chart. */
fun resugaringTimes(events: List<MonitorEvent>): List<Long> {
    val times = ArrayDeque<Long>()
    events.sortedBy { it.timestampMillis }.forEach {
        when (it.kind) {
            Kind.RESUGARING -> times.addLast(it.timestampMillis)
            Kind.RESUGARING_CANCELLED -> times.removeLastOrNull()
            else -> Unit
        }
    }
    return times.toList()
}

/** " (web page)" for an action made on another device, empty otherwise. */
@Composable
private fun sourceSuffix(source: String?): String {
    if (source == null) return ""
    val label = sourceLabelRes(source)?.let { stringResource(it) } ?: source
    return " ($label)"
}
