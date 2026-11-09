package com.zamateka.smartfollow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zamateka.smartfollow.R
import com.zamateka.smartfollow.alarm.AlarmPreferences
import com.zamateka.smartfollow.alarm.AlarmSettings
import com.zamateka.smartfollow.alarm.AlarmSound
import com.zamateka.smartfollow.alarm.AlarmSounds
import com.zamateka.smartfollow.alarm.AlarmType
import com.zamateka.smartfollow.alarm.AlarmTypePreferences
import com.zamateka.smartfollow.alarm.BuiltInSound
import com.zamateka.smartfollow.alarm.Notifications
import com.zamateka.smartfollow.alarm.SoundTheme
import com.zamateka.smartfollow.monitor.alarmVolumePercentAt

/** What an alarm does: rings, only notifies (muted), or is not detected at all (off). */
private enum class AlarmState { ON, MUTED, OFF }

private fun AlarmTypePreferences.state(): AlarmState = when {
    !enabled -> AlarmState.OFF
    muted -> AlarmState.MUTED
    else -> AlarmState.ON
}

/** Alarms whose muting deserves a red warning: the ones that can never be turned off, and urgent high. */
private val SAFETY_ALARMS = AlarmSettings.ALWAYS_ENABLED + AlarmType.URGENT_HIGH

/** Repeat intervals offered as chips; another value set before stays offered. */
private val REPEAT_CHOICES = listOf(5, 10, 15, 20, 30, 45, 60)

/**
 * Settings of one alarm: its state (rings, muted, off), what triggers it, whether it rings once
 * or until OK and how often it comes back, its sound and vibration, and a test.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AlarmSettingsScreen(
    type: AlarmType,
    profileName: String,
    preferences: AlarmPreferences,
    onChange: (AlarmPreferences) -> Unit,
    tester: SoundTester,
    onPickPhoneSound: () -> Unit,
    onOpenThresholds: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val typePreferences = preferences.forType(type)
    val state = typePreferences.state()
    val update: ((AlarmTypePreferences) -> AlarmTypePreferences) -> Unit = { change -> onChange(preferences.withType(type, change)) }
    var choosingSound by remember { mutableStateOf(false) }
    if (choosingSound) {
        SoundSheet(type, preferences, tester, onSelect = { sound -> update { it.copy(soundUri = sound.toSoundUri()) } }, onPickPhoneSound = {
            choosingSound = false
            onPickPhoneSound()
        }, onDismiss = { choosingSound = false })
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        SettingsTopBar(stringResource(type.labelRes()), onBack, subtitle = stringResource(R.string.settings_profile_name, profileName))

        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(type.descriptionRes()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val alwaysOn = type in AlarmSettings.ALWAYS_ENABLED
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                AlarmState.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = state == option,
                        onClick = {
                            update {
                                when (option) {
                                    AlarmState.ON -> it.copy(enabled = true, muted = false)
                                    AlarmState.MUTED -> it.copy(enabled = true, muted = true)
                                    AlarmState.OFF -> it.copy(enabled = false)
                                }
                            }
                        },
                        enabled = !(option == AlarmState.OFF && alwaysOn),
                        shape = SegmentedButtonDefaults.itemShape(index, AlarmState.entries.size),
                        colors = if (option == AlarmState.MUTED && type in SAFETY_ALARMS) {
                            SegmentedButtonDefaults.colors(
                                activeContainerColor = MaterialTheme.colorScheme.errorContainer,
                                activeContentColor = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        } else {
                            SegmentedButtonDefaults.colors()
                        },
                        icon = {
                            when {
                                state == option -> Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp))
                                option == AlarmState.OFF && alwaysOn -> Icon(Icons.Filled.Lock, contentDescription = null, Modifier.size(16.dp))
                                option == AlarmState.ON -> Icon(Icons.Filled.Notifications, contentDescription = null, Modifier.size(18.dp))
                                option == AlarmState.MUTED -> Icon(painterResource(R.drawable.ic_notifications_off), contentDescription = null, Modifier.size(18.dp))
                            }
                        },
                    ) {
                        Text(
                            stringResource(
                                when (option) {
                                    AlarmState.ON -> R.string.alarm_state_on
                                    AlarmState.MUTED -> R.string.alarm_state_muted
                                    AlarmState.OFF -> R.string.alarm_state_off
                                },
                            ),
                            maxLines = 1,
                        )
                    }
                }
            }
            if (alwaysOn) {
                Text(stringResource(R.string.alarm_off_locked), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when (state) {
                AlarmState.MUTED -> MutedWarning(safety = type in SAFETY_ALARMS)
                AlarmState.OFF -> Text(stringResource(R.string.alarm_off_text), style = MaterialTheme.typography.bodyMedium)
                AlarmState.ON -> Unit
            }
        }
        if (state == AlarmState.OFF) return@Column

        TriggerSection(type, preferences, onChange, onOpenThresholds)

        SettingsSection(stringResource(R.string.settings_section_behaviour))
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(false, true).forEachIndexed { index, continuous ->
                    SegmentedButton(
                        selected = typePreferences.continuous == continuous,
                        onClick = { update { it.copy(continuous = continuous) } },
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                    ) { Text(stringResource(if (continuous) R.string.settings_until_ok else R.string.settings_once)) }
                }
            }
            Text(
                stringResource(
                    when {
                        typePreferences.continuous -> R.string.settings_continuous_on
                        type in AlarmSettings.ONCE_PER_EPISODE -> R.string.settings_continuous_off_once
                        else -> R.string.settings_continuous_off
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Rings once per episode: no repeat to choose.
            if (type !in AlarmSettings.ONCE_PER_EPISODE) {
                Text(stringResource(R.string.settings_repeat_every), style = MaterialTheme.typography.bodyLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (REPEAT_CHOICES + typePreferences.repeatMinutes).distinct().sorted().forEach { minutes ->
                        FilterChip(
                            selected = typePreferences.repeatMinutes == minutes,
                            onClick = { update { it.copy(repeatMinutes = minutes) } },
                            label = { Text(stringResource(R.string.minutes_value, minutes)) },
                        )
                    }
                }
            }
        }

        val muted = state == AlarmState.MUTED
        SettingsSection(stringResource(R.string.settings_section_sound))
        if (muted) {
            Text(
                stringResource(R.string.settings_sound_muted_note),
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(Modifier.alpha(if (muted) 0.45f else 1f)) {
            SettingsRow(
                title = stringResource(R.string.settings_sound),
                subtitle = soundName(type, preferences),
                onClick = if (muted) null else ({ choosingSound = true }),
                chevron = true,
            )
            SettingsRow(
                title = stringResource(R.string.settings_vibrate),
                trailing = {
                    Switch(checked = typePreferences.vibrate, enabled = !muted, onCheckedChange = { vibrate -> update { it.copy(vibrate = vibrate) } })
                },
            )
        }

        Spacer(Modifier.height(16.dp))
        val testId = "alarm:$type"
        Button(
            onClick = {
                if (muted) {
                    Notifications.test(context, type)
                } else {
                    tester.toggle(testId, AlarmSounds.uri(context, type, preferences), typePreferences.vibrate, preferences.alarmVolumePercentAt())
                }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(56.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Icon(
                if (tester.playing == testId) painterResource(R.drawable.ic_stop) else rememberVectorPainter(Icons.Filled.PlayArrow),
                contentDescription = null,
            )
            Text(
                stringResource(if (tester.playing == testId) R.string.settings_stop_test else R.string.settings_test_alarm),
                Modifier.padding(start = 10.dp),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Text(
            stringResource(if (muted) R.string.settings_test_muted_note else R.string.settings_test_note),
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Red for a safety alarm, neutral for the others: no sound nor vibration, the notification stays. */
@Composable
private fun MutedWarning(safety: Boolean) {
    Surface(
        color = if (safety) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val color = if (safety) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
            Icon(
                if (safety) rememberVectorPainter(Icons.Filled.Warning) else painterResource(R.drawable.ic_notifications_off),
                contentDescription = null,
                tint = if (safety) MaterialTheme.colorScheme.error else color,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(if (safety) R.string.settings_muted_safety_title else R.string.settings_muted_title),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = color,
                )
                Text(stringResource(R.string.settings_muted_on), style = MaterialTheme.typography.bodyMedium, color = color)
            }
        }
    }
}

/** The threshold (set on the ranges page), the stale data delay or the prediction horizon. */
@Composable
private fun TriggerSection(type: AlarmType, preferences: AlarmPreferences, onChange: (AlarmPreferences) -> Unit, onOpenThresholds: () -> Unit) {
    when (type) {
        AlarmType.URGENT_LOW, AlarmType.LOW, AlarmType.HIGH, AlarmType.URGENT_HIGH -> {
            SettingsSection(stringResource(R.string.settings_section_trigger))
            SettingsRow(
                title = stringResource(R.string.settings_threshold),
                subtitle = stringResource(R.string.settings_threshold_sub, alarmSubtitle(type, preferences.withType(type) { it.copy(continuous = false) })),
                onClick = onOpenThresholds,
                chevron = true,
            )
        }
        AlarmType.STALE_DATA -> {
            SettingsSection(stringResource(R.string.settings_section_trigger))
            MinutesChoice(
                label = stringResource(R.string.settings_stale_delay),
                value = preferences.staleMinutes,
                range = AlarmPreferences.STALE_RANGE,
                onChange = { onChange(preferences.copy(staleMinutes = it)) },
            )
        }
        AlarmType.LOW_SOON -> {
            SettingsSection(stringResource(R.string.settings_section_trigger))
            MinutesChoice(
                label = stringResource(R.string.settings_low_soon_horizon),
                value = preferences.lowSoonMinutes,
                range = AlarmPreferences.LOW_SOON_RANGE,
                onChange = { onChange(preferences.copy(lowSoonMinutes = it)) },
            )
        }
        AlarmType.FAST_DROP, AlarmType.NO_RECOVERY -> Unit
    }
}

/** Minutes as chips, every 5 minutes over [range]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MinutesChoice(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            range.step(AlarmPreferences.MINUTES_STEP).forEach { minutes ->
                FilterChip(selected = value == minutes, onClick = { onChange(minutes) }, label = { Text(stringResource(R.string.minutes_value, minutes)) })
            }
        }
    }
}

/** "Chimes · Urgent low (default)", "Chimes · High" or "Phone sound · Argon". */
@Composable
private fun soundName(type: AlarmType, preferences: AlarmPreferences): String {
    val context = LocalContext.current
    val theme = SoundTheme.of(context, preferences.soundTheme)
    val themeName = stringResource(theme.label)
    return when (val sound = AlarmSound.of(preferences.forType(type).soundUri)) {
        AlarmSound.Default -> "$themeName · " + stringResource(R.string.settings_sound_default_suffix, stringResource(BuiltInSound.defaultFor(type, theme).type.labelRes()))
        is AlarmSound.BuiltIn -> "$themeName · " + stringResource(sound.sound.type.labelRes())
        is AlarmSound.Phone -> stringResource(
            R.string.settings_sound_phone,
            AlarmSounds.ringtoneTitle(context, sound.uri) ?: stringResource(R.string.sound_unavailable),
        )
    }
}

/**
 * The built-in sounds of the theme, each with a button to hear it, and a line to choose one of
 * the phone's sounds. The choice is saved with OK.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SoundSheet(
    type: AlarmType,
    preferences: AlarmPreferences,
    tester: SoundTester,
    onSelect: (AlarmSound) -> Unit,
    onPickPhoneSound: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val theme = SoundTheme.of(context, preferences.soundTheme)
    val default = BuiltInSound.defaultFor(type, theme)
    val stored = AlarmSound.of(preferences.forType(type).soundUri)
    // A built-in sound, the default one included; a phone sound keeps its own line.
    var selected by remember { mutableStateOf(stored) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = {
        tester.stop()
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = close, sheetState = sheetState) {
        Column(Modifier.padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.sound_sheet_title, stringResource(type.labelRes())),
                Modifier.padding(horizontal = 24.dp),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                stringResource(R.string.sound_sheet_hint),
                Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.sound_sheet_theme, stringResource(theme.label)).uppercase(),
                Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BuiltInSound.of(theme).forEach { sound ->
                val choice = if (sound == default) AlarmSound.Default else AlarmSound.BuiltIn(sound)
                val id = "sheet:${sound.key}"
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f)) {
                        RadioLine(
                            label = stringResource(sound.type.labelRes()) +
                                if (sound == default) " · " + stringResource(R.string.sound_sheet_default) else "",
                            selected = selected == choice,
                        ) { selected = choice }
                    }
                    if (tester.playing == id) {
                        FilledTonalIconButton(onClick = { tester.stop() }) {
                            Icon(painterResource(R.drawable.ic_stop), contentDescription = stringResource(R.string.settings_stop_test))
                        }
                    } else {
                        IconButton(onClick = {
                            tester.toggle(id, AlarmSounds.builtInUri(context, sound, theme), vibrate = false, preferences.alarmVolumePercentAt())
                        }) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.sound_sheet_listen))
                        }
                    }
                }
            }
            if (stored is AlarmSound.Phone) {
                Row(Modifier.padding(horizontal = 12.dp)) {
                    RadioLine(
                        label = stringResource(R.string.settings_sound_phone, AlarmSounds.ringtoneTitle(context, stored.uri) ?: stringResource(R.string.sound_unavailable)),
                        selected = selected == stored,
                    ) { selected = stored }
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            SettingsRow(
                title = stringResource(R.string.sound_sheet_phone),
                onClick = {
                    tester.stop()
                    onPickPhoneSound()
                },
                chevron = true,
                leading = { Icon(painterResource(R.drawable.ic_smartphone), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = close) { Text(stringResource(R.string.cancel)) }
                Button(onClick = {
                    onSelect(selected)
                    close()
                }) { Text(stringResource(R.string.acknowledge)) }
            }
        }
    }
}
