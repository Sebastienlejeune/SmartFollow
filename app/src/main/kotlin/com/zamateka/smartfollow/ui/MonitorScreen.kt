package com.zamateka.smartfollow.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zamateka.smartfollow.R
import com.zamateka.smartfollow.alarm.AlarmSettings
import com.zamateka.smartfollow.alarm.AlarmType
import com.zamateka.smartfollow.alarm.HistoryEntries
import com.zamateka.smartfollow.sync.ActionSharing
import com.zamateka.smartfollow.alarm.offersResugaring
import com.zamateka.smartfollow.monitor.Monitor
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Alarms muted on this phone by the active profile, shown as a banner so that a muted profile
 * ("Discreet") is never forgotten; [previousProfile] is offered to go back to.
 */
class MutedAlarms(
    val muted: Set<AlarmType>,
    val audible: Set<AlarmType>,
    val previousProfile: String?,
    val onBackToPrevious: () -> Unit,
)

/** Actions offered by [MonitorScreen]. */
class MonitorActions(
    val onResugaring: () -> Unit,
    val onCancelResugaring: () -> Unit,
    val onAcknowledge: () -> Unit,
    val onRefresh: () -> Unit,
    val onStart: () -> Unit,
    val onStop: () -> Unit,
    /** Opens a system settings screen to fix a [SetupIssue]. */
    val onOpenSettings: (Intent) -> Unit,
)

@Composable
fun MonitorScreen(
    status: Monitor.Status,
    settings: AlarmSettings,
    resugaringTimesMillis: List<Long>,
    issues: List<SetupIssue>,
    /** Shown in the About section, for example "0.3.0". */
    version: String,
    /** Sharing with the server, shown as a cloud next to the monitoring buttons. */
    sharing: ActionSharing.State,
    /** `null` when the active profile mutes no alarm. */
    mutedAlarms: MutedAlarms?,
    actions: MonitorActions,
) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(15_000)
            value = System.currentTimeMillis()
        }
    }
    val evaluation = status.evaluation
    val latest = status.readings.lastOrNull()
    var chartRange by rememberSaveable { mutableStateOf(ChartRange.THREE_HOURS) }
    var confirmingStop by remember { mutableStateOf(false) }

    // Stopping silences every alarm, urgent low included: it must never happen by mistake.
    if (confirmingStop) {
        AlertDialog(
            onDismissRequest = { confirmingStop = false },
            title = { Text(stringResource(R.string.stop_confirm_title)) },
            text = { Text(stringResource(R.string.stop_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingStop = false
                    actions.onStop()
                }) { Text(stringResource(R.string.stop_confirm_yes), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingStop = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!status.running) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.monitoring_stopped_banner),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Button(onClick = actions.onStart) { Text(stringResource(R.string.start)) }
                }
            }
        }

        mutedAlarms?.let { MutedBanner(it) }

        CurrentGlucose(status, settings, now)

        evaluation?.alarm?.let { alarm ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(alarm.labelRes()), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Button(onClick = actions.onAcknowledge) { Text(stringResource(R.string.acknowledge)) }
                }
            }
        }

        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChartRangeSelector(selected = chartRange, onSelect = { chartRange = it })
                GlucoseChart(
                    readings = status.readings,
                    nowMillis = now,
                    range = chartRange,
                    settings = settings,
                    resugaringTimesMillis = resugaringTimesMillis,
                )
            }
        }

        if (status.running) {
            val snoozeEnd = status.snoozeEndsAtMillis
            if (snoozeEnd != null) {
                Card {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        val minutesLeft = ((snoozeEnd - now).coerceAtLeast(0) + 59_999) / 60_000
                        Text(stringResource(R.string.snooze_remaining, minutesLeft), Modifier.weight(1f))
                        OutlinedButton(onClick = actions.onCancelResugaring) { Text(stringResource(R.string.cancel)) }
                    }
                }
            } else if (evaluation?.conditions.orEmpty().any { it.offersResugaring() }) {
                // Only while a low (or a low soon) is going on, when a resugaring is the answer.
                Button(onClick = actions.onResugaring, modifier = Modifier.fillMaxWidth().height(72.dp)) {
                    Text(stringResource(R.string.resugaring_done), fontSize = 20.sp)
                }
            }
        }

        issues.forEach { issue ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(issue.title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(issue.description), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { actions.onOpenSettings(issue.intent) }) { Text(stringResource(R.string.fix)) }
                }
            }
        }

        status.lastError?.let {
            Text(errorText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        status.lastFetchMillis?.let {
            Text(
                stringResource(R.string.last_fetch, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (status.running) {
                OutlinedButton(onClick = actions.onRefresh) { Text(stringResource(R.string.refresh)) }
                OutlinedButton(onClick = { confirmingStop = true }) { Text(stringResource(R.string.stop_monitoring)) }
            }
            Spacer(Modifier.weight(1f))
            SharingButton(sharing)
        }
        if (latest == null && status.running && status.lastError == null) {
            Text(stringResource(R.string.waiting_for_data), style = MaterialTheme.typography.bodySmall)
        }

        Column(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.about_title), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
            Text(stringResource(R.string.app_version, version), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun MutedBanner(mutedAlarms: MutedAlarms) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(R.drawable.ic_notifications_off), contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Column(Modifier.weight(1f)) {
                val count = mutedAlarms.muted.size
                Text(
                    pluralStringResource(R.plurals.muted_banner_title, count, count),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                val audible = HistoryEntries.SUMMARY_ORDER.filter { it in mutedAlarms.audible }
                Text(
                    if (audible.isEmpty()) {
                        stringResource(R.string.muted_banner_none)
                    } else {
                        stringResource(R.string.muted_banner_audible, audible.map { stringResource(it.labelRes()).lowercase() }.joinToString(", "))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                val previous = mutedAlarms.previousProfile
                if (previous != null) {
                    TextButton(onClick = mutedAlarms.onBackToPrevious, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.muted_banner_back, previous), color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun CurrentGlucose(status: Monitor.Status, settings: AlarmSettings, nowMillis: Long) {
    val latest = status.readings.lastOrNull()
    val fresh = status.evaluation?.freshReading
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        GlucoseValue(
            reading = latest,
            // A stale value is greyed out so that it is never mistaken for the current glucose.
            color = if (fresh != null) glucoseColor(fresh.mgDl, settings) else MaterialTheme.colorScheme.outline,
        )
        if (latest != null) {
            val minutes = ((nowMillis - latest.timestampMillis) / 60_000).coerceAtLeast(0)
            Text(stringResource(R.string.reading_age, minutes), style = MaterialTheme.typography.bodyMedium)
        }
        status.evaluation?.rateMgDlPerMin?.let {
            Text(
                stringResource(R.string.rate, String.format(Locale.getDefault(), "%+.1f", it)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
