package com.zamateka.smartfollow.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zamateka.smartfollow.R
import com.zamateka.smartfollow.alarm.AlarmPlayer
import com.zamateka.smartfollow.alarm.AlarmType
import kotlinx.coroutines.delay

/** How long a sound test plays. */
private const val TEST_DURATION_MILLIS = 5_000L

/** Back arrow, title and optional subtitle of a settings page. */
@Composable
fun SettingsTopBar(title: String, onBack: () -> Unit, subtitle: String? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
        }
        Column(Modifier.padding(start = 4.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** Title of a group of settings, in the primary colour. */
@Composable
fun SettingsSection(title: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Text(
        title,
        modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        color = color,
    )
}

/** Small capitals heading a few rows inside a section. */
@Composable
fun SettingsGroupLabel(title: String) {
    Text(
        title.uppercase(),
        Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * A line of the settings: optional [leading] content, a title and a subtitle, optional
 * [trailing] content; a chevron when it opens a page ([chevron]).
 */
@Composable
fun SettingsRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    chevron: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        trailing?.invoke()
        if (chevron) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Coloured dot of an alarm in the lists: red for the urgent ones, orange for the others. */
@Composable
fun AlarmDot(type: AlarmType) {
    val color = when (type) {
        AlarmType.URGENT_LOW, AlarmType.URGENT_HIGH -> Color(0xFFE53935)
        AlarmType.LOW_SOON, AlarmType.LOW, AlarmType.HIGH -> Color(0xFFFB8C00)
        AlarmType.FAST_DROP, AlarmType.NO_RECOVERY -> MaterialTheme.colorScheme.tertiary
        AlarmType.STALE_DATA -> MaterialTheme.colorScheme.outline
    }
    Box(Modifier.size(12.dp).background(color, CircleShape))
}

/** Speaker that plays a sound for a few seconds, or a square that stops it. */
@Composable
fun TestSoundButton(testing: Boolean, label: String = stringResource(R.string.settings_test), onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            painterResource(if (testing) R.drawable.ic_stop else R.drawable.ic_volume_up),
            contentDescription = if (testing) stringResource(R.string.settings_stop_test) else label,
        )
    }
}

/**
 * Plays one sound at a time for a few seconds, as the alarm would ring, for the settings pages.
 * [playing] is the id of the sound being played, so that its button shows "stop".
 */
@Stable
class SoundTester(private val player: AlarmPlayer) {
    var playing by mutableStateOf<String?>(null)
        private set

    /** Plays [uri] under [id], or stops it if it is already playing. */
    fun toggle(id: String, uri: Uri, vibrate: Boolean, volumePercent: Int) {
        if (playing == id) {
            stop()
        } else {
            player.start(uri, vibrate, volumePercent)
            playing = id
        }
    }

    fun stop() {
        player.stop()
        playing = null
    }
}

@Composable
fun rememberSoundTester(): SoundTester {
    val context = LocalContext.current
    val tester = remember { SoundTester(AlarmPlayer(context)) }
    LaunchedEffect(tester.playing) {
        if (tester.playing != null) {
            delay(TEST_DURATION_MILLIS)
            tester.stop()
        }
    }
    DisposableEffect(Unit) { onDispose { tester.stop() } }
    return tester
}
