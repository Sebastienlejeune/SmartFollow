package com.zamateka.smartfollow.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zamateka.smartfollow.R
import com.zamateka.smartfollow.alarm.AlarmProfiles
import com.zamateka.smartfollow.sync.ActionSharing
import java.text.DateFormat
import java.util.Date

private val SharingOkColor = Color(0xFF43A047)

// The theme's error colour is pale pink in dark mode: a clear red reads better on a small icon.
private val SharingFailingColor = Color(0xFFE53935)

/**
 * Sign out on the left, the active profile in the middle, settings on the right. Signing out
 * stops every alarm, so it asks for confirmation.
 */
@Composable
fun SmartFollowTopBar(
    profiles: AlarmProfiles,
    profileActions: ProfileActions,
    onSignOut: () -> Unit,
    onShowSettings: () -> Unit,
) {
    var confirmingSignOut by remember { mutableStateOf(false) }

    if (confirmingSignOut) {
        AlertDialog(
            onDismissRequest = { confirmingSignOut = false },
            title = { Text(stringResource(R.string.sign_out_confirm_title)) },
            text = { Text(stringResource(R.string.sign_out_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingSignOut = false
                    onSignOut()
                }) { Text(stringResource(R.string.sign_out), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingSignOut = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        IconButton(onClick = { confirmingSignOut = true }, modifier = Modifier.align(Alignment.CenterStart)) {
            Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = stringResource(R.string.sign_out))
        }
        ProfileMenuButton(profiles, profileActions, Modifier.align(Alignment.Center))
        IconButton(onClick = onShowSettings, modifier = Modifier.align(Alignment.CenterEnd)) {
            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title))
        }
    }
}

/** Green or red cloud telling whether sharing with the server works; nothing without a server. */
@Composable
fun SharingButton(sharing: ActionSharing.State) {
    if (sharing == ActionSharing.State.Disabled) return
    var showing by remember { mutableStateOf(false) }
    if (showing) {
        AlertDialog(
            onDismissRequest = { showing = false },
            title = { Text(stringResource(R.string.settings_sync_title)) },
            text = { Text(sharingText(sharing)) },
            confirmButton = { TextButton(onClick = { showing = false }) { Text(stringResource(R.string.acknowledge)) } },
        )
    }
    IconButton(onClick = { showing = true }) {
        Icon(
            painterResource(R.drawable.ic_cloud),
            contentDescription = stringResource(R.string.settings_sync_title),
            tint = if (sharing is ActionSharing.State.Connected) SharingOkColor else SharingFailingColor,
        )
    }
}

@Composable
private fun sharingText(sharing: ActionSharing.State): String {
    val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
    return when (sharing) {
        ActionSharing.State.Disabled -> ""
        is ActionSharing.State.Connected -> stringResource(R.string.sharing_ok, timeFormat.format(Date(sharing.lastSyncMillis)))
        is ActionSharing.State.Failing -> stringResource(R.string.sharing_failing, timeFormat.format(Date(sharing.sinceMillis)), sharing.message)
    }
}
