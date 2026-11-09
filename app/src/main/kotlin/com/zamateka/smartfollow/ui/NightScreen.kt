package com.zamateka.smartfollow.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zamateka.smartfollow.R
import com.zamateka.smartfollow.alarm.AlarmPreferences
import com.zamateka.smartfollow.alarm.AlarmSounds
import com.zamateka.smartfollow.alarm.AlarmType

private val NightColor = Color(0xFF3F3A7A)
private val DayColor = Color(0xFFFFE9A8)
private const val NIGHT_TEST = "night"

/**
 * The night: its start and end hours, shown on a 24-hour bar, how much lower the alarms ring
 * during it, and the summary sent when it ends.
 */
@Composable
fun NightScreen(profileName: String, preferences: AlarmPreferences, onChange: (AlarmPreferences) -> Unit, tester: SoundTester, onBack: () -> Unit) {
    val context = LocalContext.current
    var choosing by remember { mutableStateOf<Boolean?>(null) } // true: start, false: end
    choosing?.let { start ->
        HourDialog(
            title = stringResource(if (start) R.string.settings_night_start else R.string.settings_night_end),
            hour = if (start) preferences.nightStartHour else preferences.nightEndHour,
            onDismiss = { choosing = null },
        ) { hour ->
            onChange(if (start) preferences.copy(nightStartHour = hour) else preferences.copy(nightEndHour = hour))
            choosing = null
        }
    }
    val start = preferences.nightStartHour
    val end = preferences.nightEndHour
    val hours = (end - start + 24) % 24

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        SettingsTopBar(stringResource(R.string.settings_night_title), onBack, subtitle = stringResource(R.string.settings_profile_name, profileName))

        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(24.dp), modifier = Modifier.padding(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HourField(stringResource(R.string.night_start), start, Modifier.weight(1f)) { choosing = true }
                    HourField(stringResource(R.string.night_end), end, Modifier.weight(1f)) { choosing = false }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Canvas(Modifier.fillMaxWidth().height(20.dp)) {
                        val radius = CornerRadius(size.height / 2)
                        drawRoundRect(DayColor, cornerRadius = radius)
                        fun span(from: Int, to: Int) = drawRect(
                            NightColor,
                            topLeft = Offset(size.width * from / 24f, 0f),
                            size = Size(size.width * (to - from) / 24f, size.height),
                        )
                        if (start < end) {
                            span(start, end)
                        } else {
                            span(start, 24)
                            span(0, end)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        listOf(0, 6, 12, 18, 24).forEach {
                            Text("%02dh".format(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Text(
                    stringResource(
                        if (start < end) R.string.night_duration_same_day else R.string.night_duration,
                        hours,
                        stringResource(R.string.hour_value, start),
                        stringResource(R.string.hour_value, end),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SettingsSection(stringResource(R.string.night_volume_section))
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.night_volume_label), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.percent_value, preferences.nightVolumeReductionPercent), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                PercentSlider(
                    value = preferences.nightVolumeReductionPercent,
                    range = AlarmPreferences.NIGHT_VOLUME_REDUCTION_RANGE,
                    step = AlarmPreferences.VOLUME_STEP,
                    onChange = { onChange(preferences.copy(nightVolumeReductionPercent = it)) },
                    modifier = Modifier.weight(1f),
                )
                TestSoundButton(tester.playing == NIGHT_TEST, label = stringResource(R.string.night_volume_test)) {
                    tester.toggle(NIGHT_TEST, AlarmSounds.uri(context, AlarmType.URGENT_LOW, preferences), vibrate = false, preferences.alarmVolumePercent(night = true))
                }
            }
            Text(
                stringResource(R.string.settings_night_volume_result, preferences.alarmVolumePercent(night = true)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsSection(stringResource(R.string.night_summary_section))
        SettingsRow(
            title = stringResource(R.string.night_summary_switch, stringResource(R.string.hour_value, end)),
            subtitle = stringResource(R.string.night_summary_description),
            onClick = { onChange(preferences.copy(nightSummary = !preferences.nightSummary)) },
            trailing = { Switch(checked = preferences.nightSummary, onCheckedChange = { onChange(preferences.copy(nightSummary = it)) }) },
        )
    }
}

/** "Start 22:00" as a large outlined field that opens the hours. */
@Composable
private fun HourField(label: String, hour: Int, modifier: Modifier, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.hour_value, hour), style = MaterialTheme.typography.displaySmall)
        }
    }
}

/** The 24 whole hours as chips: the night starts and ends on the hour. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HourDialog(title: String, hour: Int, onDismiss: () -> Unit, onSelect: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AlarmPreferences.HOUR_RANGE.forEach { value ->
                    FilterChip(
                        selected = value == hour,
                        onClick = { onSelect(value) },
                        label = { Text(stringResource(R.string.hour_value, value), Modifier.width(44.dp)) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
