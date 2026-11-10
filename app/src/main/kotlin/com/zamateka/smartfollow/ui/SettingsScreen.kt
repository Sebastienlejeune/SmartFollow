package com.zamateka.smartfollow.ui

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import com.zamateka.smartfollow.R
import com.zamateka.smartfollow.alarm.AlarmPreferences
import com.zamateka.smartfollow.alarm.AlarmProfiles
import com.zamateka.smartfollow.alarm.AlarmSound
import com.zamateka.smartfollow.alarm.AlarmSounds
import com.zamateka.smartfollow.alarm.AlarmType
import com.zamateka.smartfollow.alarm.SoundTheme
import com.zamateka.smartfollow.settings.AppLanguage
import com.zamateka.smartfollow.settings.ShareAccount
import com.zamateka.smartfollow.share.ShareRegion
import com.zamateka.smartfollow.sync.ActionSharing
import com.zamateka.smartfollow.sync.SyncServer
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Everything the settings pages need from outside, besides the edited preferences. */
class SettingsContext(
    val profiles: AlarmProfiles,
    /** The profile whose settings are shown, the active one at first. */
    val editedProfileId: String,
    val onEditProfile: (String) -> Unit,
    val onActivateProfile: (String) -> Unit,
    val syncServer: SyncServer?,
    val sharing: ActionSharing.State,
    /** Checks the server, then saves it; the failure carries the reason. */
    val onSaveSyncServer: suspend (SyncServer) -> Result<Unit>,
    val onRemoveSyncServer: () -> Unit,
    val account: ShareAccount?,
    val version: String,
)

/** Pages of the settings, saved as a string so that they survive the activity being recreated. */
private object SettingsPage {
    const val ROOT = "root"
    const val THRESHOLDS = "thresholds"
    const val NIGHT = "night"
    const val SERVER = "server"
    private const val ALARM = "alarm:"

    fun alarm(type: AlarmType) = ALARM + type.name
    fun alarmType(page: String): AlarmType? = if (page.startsWith(ALARM)) AlarmType.valueOf(page.removePrefix(ALARM)) else null
}

/**
 * Settings: a list of sections for the edited profile (alarms, glucose ranges, sound, night and
 * resugaring), then the settings of the whole app (sharing server, Dexcom account). Each alarm,
 * the ranges, the night and the server open their own page.
 */
@Composable
fun SettingsScreen(
    settings: SettingsContext,
    preferences: AlarmPreferences,
    onChange: (AlarmPreferences) -> Unit,
    onBack: () -> Unit,
) {
    val current by rememberUpdatedState(preferences)
    val tester = rememberSoundTester()
    var page by rememberSaveable { mutableStateOf(SettingsPage.ROOT) }
    val editedName = (settings.profiles[settings.editedProfileId] ?: settings.profiles.active).name

    // Saveable, so that the choice survives the activity being recreated while the picker is open.
    var pickingSoundFor by rememberSaveable { mutableStateOf<AlarmType?>(null) }
    val ringtonePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val type = pickingSoundFor ?: return@rememberLauncherForActivityResult
        pickingSoundFor = null
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) return@rememberLauncherForActivityResult
        val uri = IntentCompat.getParcelableExtra(data, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java) ?: return@rememberLauncherForActivityResult
        onChange(current.withType(type) { it.copy(soundUri = AlarmSound.Phone(uri.toString()).toSoundUri()) })
    }
    val pickPhoneSound = { type: AlarmType ->
        pickingSoundFor = type
        val existing = (AlarmSound.of(current.forType(type).soundUri) as? AlarmSound.Phone)?.uri?.toUri()
        ringtonePicker.launch(
            Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existing),
        )
    }

    val toRoot = {
        tester.stop()
        page = SettingsPage.ROOT
    }
    if (page != SettingsPage.ROOT) BackHandler(onBack = toRoot)

    val alarmType = SettingsPage.alarmType(page)
    when {
        alarmType != null -> AlarmSettingsScreen(
            type = alarmType,
            profileName = editedName,
            preferences = preferences,
            onChange = onChange,
            tester = tester,
            onPickPhoneSound = { pickPhoneSound(alarmType) },
            onOpenThresholds = { page = SettingsPage.THRESHOLDS },
            onBack = toRoot,
        )
        page == SettingsPage.THRESHOLDS -> ThresholdsScreen(editedName, preferences, onChange, onBack = toRoot)
        page == SettingsPage.NIGHT -> NightScreen(editedName, preferences, onChange, tester, onBack = toRoot)
        page == SettingsPage.SERVER -> SyncServerScreen(settings.syncServer, settings.onSaveSyncServer, settings.onRemoveSyncServer, onBack = toRoot)
        else -> SettingsRoot(settings, editedName, preferences, onChange, tester, onOpen = { page = it }, onBack = onBack)
    }
}

@Composable
private fun SettingsRoot(
    settings: SettingsContext,
    editedName: String,
    preferences: AlarmPreferences,
    onChange: (AlarmPreferences) -> Unit,
    tester: SoundTester,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confirmingRestore by remember { mutableStateOf(false) }
    var choosingTheme by remember { mutableStateOf(false) }
    var choosingSnooze by remember { mutableStateOf(false) }
    var choosingLanguage by remember { mutableStateOf(false) }
    var showingAbout by remember { mutableStateOf(false) }

    if (choosingLanguage) LanguageDialog(onDismiss = { choosingLanguage = false })
    if (showingAbout) AboutDialog(settings.version, onDismiss = { showingAbout = false })
    if (confirmingRestore) {
        val restoredMessage = stringResource(R.string.settings_restore_done, editedName)
        val undo = stringResource(R.string.undo)
        AlertDialog(
            onDismissRequest = { confirmingRestore = false },
            title = { Text(stringResource(R.string.settings_restore_title, editedName)) },
            text = { Text(stringResource(R.string.settings_restore_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingRestore = false
                    val before = preferences
                    onChange(AlarmPreferences())
                    scope.launch {
                        if (snackbar.showSnackbar(restoredMessage, actionLabel = undo, withDismissAction = true) == SnackbarResult.ActionPerformed) {
                            onChange(before)
                        }
                    }
                }) { Text(stringResource(R.string.settings_restore_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmingRestore = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (choosingTheme) SoundThemeDialog(preferences, onChange, onDismiss = { choosingTheme = false })
    if (choosingSnooze) SnoozeDialog(preferences, onChange, onDismiss = { choosingSnooze = false })

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, contentWindowInsets = WindowInsets(0)) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SettingsTopBar(stringResource(R.string.settings_title), onBack)
            EditedProfileHeader(settings)

            SettingsSection(stringResource(R.string.settings_section_alarms))
            ALARM_GROUPS.forEach { (label, types) ->
                SettingsGroupLabel(stringResource(label))
                types.forEach { type ->
                    SettingsRow(
                        title = stringResource(type.labelRes()),
                        subtitle = alarmSubtitle(type, preferences),
                        onClick = { onOpen(SettingsPage.alarm(type)) },
                        leading = { AlarmDot(type) },
                        trailing = { AlarmStateChip(type, preferences) },
                    )
                }
            }

            HorizontalDivider(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
            SettingsSection(stringResource(R.string.settings_section_glucose))
            Column(
                Modifier.fillMaxWidth().clickable { onOpen(SettingsPage.THRESHOLDS) },
            ) {
                SettingsRow(
                    title = stringResource(R.string.settings_thresholds_title),
                    subtitle = stringResource(
                        R.string.settings_ranges_summary,
                        preferences.lowMgDl,
                        preferences.targetHighMgDl,
                        preferences.highMgDl,
                        preferences.urgentHighMgDl,
                    ),
                    chevron = true,
                )
                RangesBar(preferences, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
            }

            SettingsSection(stringResource(R.string.settings_section_sound))
            val themes = remember { SoundTheme.available(context) }
            if (themes.size > 1) {
                SettingsRow(
                    title = stringResource(R.string.settings_sound_theme_title),
                    subtitle = stringResource(SoundTheme.of(context, preferences.soundTheme).label),
                    onClick = { choosingTheme = true },
                    chevron = true,
                )
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_alarm_volume), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.percent_value, preferences.volumePercent), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PercentSlider(
                        value = preferences.volumePercent,
                        range = AlarmPreferences.VOLUME_RANGE,
                        step = AlarmPreferences.VOLUME_STEP,
                        onChange = { onChange(preferences.copy(volumePercent = it)) },
                        modifier = Modifier.weight(1f),
                    )
                    // The urgent low sound, the one that matters most at that volume.
                    TestSoundButton(tester.playing == VOLUME_TEST) {
                        tester.toggle(VOLUME_TEST, AlarmSounds.uri(context, AlarmType.URGENT_LOW, preferences), vibrate = false, preferences.volumePercent)
                    }
                }
            }

            SettingsSection(stringResource(R.string.settings_section_night_resugaring))
            SettingsRow(
                title = stringResource(R.string.settings_night_title),
                subtitle = nightSummaryLine(preferences),
                onClick = { onOpen(SettingsPage.NIGHT) },
                chevron = true,
            )
            SettingsRow(
                title = stringResource(R.string.settings_snooze_title),
                subtitle = stringResource(R.string.settings_snooze_sub),
                onClick = { choosingSnooze = true },
                trailing = { ValueChip(stringResource(R.string.minutes_value, preferences.snoozeMinutes)) },
            )

            Spacer(Modifier.fillMaxWidth().padding(top = 16.dp).height(8.dp).background(MaterialTheme.colorScheme.surfaceContainer))
            SettingsSection(stringResource(R.string.settings_section_app), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                stringResource(R.string.settings_section_app_sub),
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SettingsRow(
                title = stringResource(R.string.settings_server),
                subtitle = serverSubtitle(settings.sharing),
                onClick = { onOpen(SettingsPage.SERVER) },
                chevron = true,
                leading = {
                    Icon(
                        painterResource(R.drawable.ic_cloud),
                        contentDescription = null,
                        tint = when (settings.sharing) {
                            ActionSharing.State.Disabled -> MaterialTheme.colorScheme.onSurfaceVariant
                            is ActionSharing.State.Connected -> Color(0xFF43A047)
                            is ActionSharing.State.Failing -> Color(0xFFE53935)
                        },
                    )
                },
            )
            if (AppLanguage.isSupported()) {
                SettingsRow(
                    title = stringResource(R.string.settings_language),
                    subtitle = AppLanguage.current(context)?.let { AppLanguage.NAMES[it] }
                        ?: stringResource(R.string.settings_language_system),
                    onClick = { choosingLanguage = true },
                    leading = { Icon(painterResource(R.drawable.ic_language), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
            }
            settings.account?.let { account ->
                SettingsRow(
                    title = stringResource(R.string.settings_account),
                    subtitle = "${account.username} · ${stringResource(regionLabel(account.region))}",
                    leading = { Icon(Icons.Filled.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
            }
            SettingsRow(
                title = stringResource(R.string.settings_restore, editedName),
                titleColor = MaterialTheme.colorScheme.error,
                onClick = { confirmingRestore = true },
                leading = { Icon(Icons.Filled.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            )
            SettingsRow(
                title = stringResource(R.string.about_title),
                subtitle = stringResource(R.string.app_version, settings.version),
                onClick = { showingAbout = true },
                leading ={ Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            )
        }
    }
}

private const val VOLUME_TEST = "volume"

/** The alarms of the root page, in the parents' groups. */
private val ALARM_GROUPS = listOf(
    R.string.settings_group_lows to listOf(AlarmType.LOW_SOON, AlarmType.LOW, AlarmType.URGENT_LOW),
    R.string.settings_group_resugaring to listOf(AlarmType.FAST_DROP, AlarmType.NO_RECOVERY),
    R.string.settings_group_highs to listOf(AlarmType.HIGH, AlarmType.URGENT_HIGH),
    R.string.settings_group_data to listOf(AlarmType.STALE_DATA),
)

/**
 * "Profile edited: School ▾", and when it is not the active profile, a banner saying so with a
 * button that activates it.
 */
@Composable
private fun EditedProfileHeader(settings: SettingsContext) {
    val profiles = settings.profiles
    val edited = profiles[settings.editedProfileId] ?: profiles.active
    var choosing by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.settings_profile_edited), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box {
                OutlinedButton(onClick = { choosing = true }, shape = RoundedCornerShape(8.dp)) {
                    Text(edited.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = stringResource(R.string.profiles_choose))
                }
                DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                    profiles.profiles.forEach { profile ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (profile.id == profiles.activeId) stringResource(R.string.profiles_active_name, profile.name) else profile.name,
                                )
                            },
                            onClick = {
                                choosing = false
                                settings.onEditProfile(profile.id)
                            },
                        )
                    }
                }
            }
        }
        if (edited.id != profiles.activeId) {
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp)) {
                    Text(
                        stringResource(R.string.settings_inactive_banner, edited.name, profiles.active.name),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    TextButton(onClick = { settings.onActivateProfile(edited.id) }, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.settings_activate_profile, edited.name))
                    }
                }
            }
        }
    }
}

/** "Rings", "Muted" or "Off" at the end of an alarm's line. */
@Composable
private fun AlarmStateChip(type: AlarmType, preferences: AlarmPreferences) {
    val typePreferences = preferences.forType(type)
    when {
        !typePreferences.enabled -> Text(
            stringResource(R.string.alarm_state_off),
            Modifier.padding(horizontal = 10.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        typePreferences.muted -> Surface(
            shape = RoundedCornerShape(8.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            color = Color.Transparent,
        ) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_notifications_off), contentDescription = null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.alarm_state_muted), Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        else -> Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Notifications, contentDescription = null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(stringResource(R.string.alarm_state_on), Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

/** What triggers the alarm, "· until OK" for a continuous one. */
@Composable
fun alarmSubtitle(type: AlarmType, preferences: AlarmPreferences): String {
    val trigger = when (type) {
        AlarmType.LOW_SOON -> stringResource(R.string.alarm_sub_low_soon, preferences.lowSoonMinutes)
        AlarmType.LOW -> stringResource(R.string.alarm_sub_below, preferences.lowMgDl)
        AlarmType.URGENT_LOW -> stringResource(R.string.alarm_sub_below, preferences.urgentLowMgDl)
        AlarmType.HIGH -> stringResource(R.string.alarm_sub_above, preferences.highMgDl)
        AlarmType.URGENT_HIGH -> stringResource(R.string.alarm_sub_above, preferences.urgentHighMgDl)
        AlarmType.FAST_DROP -> stringResource(R.string.alarm_sub_fast_drop)
        AlarmType.NO_RECOVERY -> stringResource(R.string.alarm_sub_no_recovery)
        AlarmType.STALE_DATA -> stringResource(R.string.alarm_sub_stale, preferences.staleMinutes)
    }
    val typePreferences = preferences.forType(type)
    return if (typePreferences.enabled && typePreferences.continuous) stringResource(R.string.alarm_sub_until_ok, trigger) else trigger
}

@Composable
private fun nightSummaryLine(preferences: AlarmPreferences): String = stringResource(
    R.string.settings_night_line,
    stringResource(R.string.hour_value, preferences.nightStartHour),
    stringResource(R.string.hour_value, preferences.nightEndHour),
    preferences.nightVolumeReductionPercent,
    stringResource(if (preferences.nightSummary) R.string.settings_night_summary_on else R.string.settings_night_summary_off),
)

@Composable
private fun serverSubtitle(sharing: ActionSharing.State): String {
    val timeFormat = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    return when (sharing) {
        ActionSharing.State.Disabled -> stringResource(R.string.settings_server_off)
        is ActionSharing.State.Connected -> stringResource(R.string.settings_server_ok, timeFormat.format(Date(sharing.lastSyncMillis)))
        is ActionSharing.State.Failing -> stringResource(R.string.settings_server_failing, timeFormat.format(Date(sharing.sinceMillis)))
    }
}

private fun regionLabel(region: ShareRegion): Int = when (region) {
    ShareRegion.OUTSIDE_US -> R.string.region_outside_us
    ShareRegion.US -> R.string.region_us
    ShareRegion.JAPAN -> R.string.region_japan
}

/** A value shown at the end of a line, which opens its choices. */
@Composable
fun ValueChip(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = Color.Transparent,
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** Slider over [range] in steps of [step], reporting whole values. */
@Composable
fun PercentSlider(value: Int, range: IntRange, step: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    Slider(
        value = value.toFloat(),
        onValueChange = { onChange((Math.round(it / step) * step).coerceIn(range)) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = (range.last - range.first) / step - 1,
        modifier = modifier,
    )
}

/** Coloured bar of the ranges from 40 to 400 mg/dL. */
@Composable
private fun RangesBar(preferences: AlarmPreferences, modifier: Modifier = Modifier) {
    val settings = preferences.toAlarmSettings()
    val bounds = listOf(40, preferences.urgentLowMgDl, preferences.lowMgDl, preferences.targetHighMgDl, preferences.highMgDl, 400)
    Row(modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))) {
        bounds.zipWithNext().forEach { (from, to) ->
            Box(Modifier.weight((to - from).toFloat()).fillMaxHeight().background(glucoseColor((from + to) / 2, settings)))
        }
    }
}

/** The built-in themes, as radio buttons. */
@Composable
private fun SoundThemeDialog(preferences: AlarmPreferences, onChange: (AlarmPreferences) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val themes = remember { SoundTheme.available(context) }
    val selected = SoundTheme.of(context, preferences.soundTheme)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_sound_theme_title)) },
        text = {
            Column {
                Text(stringResource(R.string.settings_sound_theme_description), style = MaterialTheme.typography.bodyMedium)
                themes.forEach { theme ->
                    RadioLine(stringResource(theme.label), selected = theme == selected) {
                        onChange(preferences.copy(soundTheme = theme.id))
                        onDismiss()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

/** Snooze durations as chips, every 5 minutes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SnoozeDialog(preferences: AlarmPreferences, onChange: (AlarmPreferences) -> Unit, onDismiss: () -> Unit) {
    var minutes by remember { mutableIntStateOf(preferences.snoozeMinutes) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_snooze_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.settings_snooze_description), style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AlarmPreferences.SNOOZE_RANGE.step(AlarmPreferences.MINUTES_STEP).forEach { value ->
                        FilterChip(
                            selected = value == minutes,
                            onClick = { minutes = value },
                            label = { Text(stringResource(R.string.minutes_value, value)) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onChange(preferences.copy(snoozeMinutes = minutes))
                onDismiss()
            }) { Text(stringResource(R.string.acknowledge)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private const val AUTHOR = "Sébastien Lejeune"
private const val FEEDBACK_EMAIL = "slejeune@zamateka.fr"
private const val FEEDBACK_SUBJECT = "Feedback SmartFollow"

/** About: version, author, a dedication and a button writing the author an e-mail. */
@Composable
private fun AboutDialog(version: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_version, version)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.about_author, AUTHOR), style = MaterialTheme.typography.bodyLarge)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.about_quote),
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic,
                    )
                    Text(
                        stringResource(R.string.about_dedication),
                        Modifier.align(Alignment.End),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // Address and subject in the mailto URI (RFC 6068), and the subject as an extra
                // too for the mail apps that only read extras.
                val uri = "mailto:$FEEDBACK_EMAIL?subject=${Uri.encode(FEEDBACK_SUBJECT)}".toUri()
                val intent = Intent(Intent.ACTION_SENDTO, uri).putExtra(Intent.EXTRA_SUBJECT, FEEDBACK_SUBJECT)
                runCatching { context.startActivity(intent) }.onFailure {
                    Toast.makeText(context, R.string.about_no_mail_app, Toast.LENGTH_LONG).show()
                }
            }) { Text(stringResource(R.string.about_feedback)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

/** Choice of the app language; applied at once (Android then recreates the screen). */
@Composable
private fun LanguageDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val current = AppLanguage.current(context)
    val choose = { tag: String? ->
        onDismiss()
        if (tag != current) AppLanguage.set(context, tag)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_language)) },
        text = {
            Column {
                RadioLine(stringResource(R.string.settings_language_system), selected = current == null) { choose(null) }
                AppLanguage.TAGS.forEach { tag ->
                    RadioLine(AppLanguage.NAMES.getValue(tag), selected = current == tag) { choose(tag) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun RadioLine(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
    }
}
