package com.zamateka.smartfollow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zamateka.smartfollow.R
import com.zamateka.smartfollow.alarm.AlarmProfile
import com.zamateka.smartfollow.alarm.AlarmProfiles

/** What can be done with the profiles; the functions returning a boolean refuse an empty or taken name. */
class ProfileActions(
    val onActivate: (String) -> Unit,
    val onCreate: (String) -> Boolean,
    val onRename: (String, String) -> Boolean,
    val onDelete: (String) -> Unit,
)

/**
 * Name of the active profile with "▾": touching it lists the profiles to switch to, and opens
 * their management.
 */
@Composable
fun ProfileMenuButton(profiles: AlarmProfiles, actions: ProfileActions, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    var managing by rememberSaveable { mutableStateOf(false) }
    if (managing) ProfilesDialog(profiles, actions, onDismiss = { managing = false })

    // A profile muting alarms stands out, so that it is not left on by mistake.
    val muting = profiles.active.preferences.mutedAlarms.isNotEmpty()
    Box(modifier) {
        TextButton(
            onClick = { open = true },
            colors = if (muting) {
                ButtonDefaults.textButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            } else {
                ButtonDefaults.textButtonColors()
            },
        ) {
            if (muting) {
                Icon(painterResource(R.drawable.ic_notifications_off), contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                profiles.active.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = MaxNameWidth),
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = stringResource(R.string.profiles_choose))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            profiles.profiles.forEach { profile ->
                DropdownMenuItem(
                    text = { Text(profile.name) },
                    leadingIcon = { CheckMark(profile.id == profiles.activeId) },
                    onClick = {
                        open = false
                        actions.onActivate(profile.id)
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.profiles_manage)) },
                onClick = {
                    open = false
                    managing = true
                },
            )
        }
    }
}

/** Upper bound of the profile name in the top bar, which keeps room for the icons on both sides. */
private val MaxNameWidth = 160.dp

@Composable
private fun CheckMark(checked: Boolean) {
    if (checked) Icon(Icons.Filled.Check, contentDescription = null) else Box(Modifier.size(24.dp))
}

/** Lists the profiles, to create, rename or delete them. The last profile cannot be deleted. */
@Composable
private fun ProfilesDialog(profiles: AlarmProfiles, actions: ProfileActions, onDismiss: () -> Unit) {
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<AlarmProfile?>(null) }
    var deleting by remember { mutableStateOf<AlarmProfile?>(null) }

    if (creating) {
        NameDialog(
            title = stringResource(R.string.profiles_new),
            initial = "",
            isAvailable = { profiles.isAvailable(it) },
            onConfirm = { creating = !actions.onCreate(it) },
            onDismiss = { creating = false },
        )
    }
    renaming?.let { profile ->
        NameDialog(
            title = stringResource(R.string.profiles_rename),
            initial = profile.name,
            isAvailable = { profiles.isAvailable(it, except = profile.id) },
            onConfirm = { if (actions.onRename(profile.id, it)) renaming = null },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.profiles_delete_title, profile.name)) },
            text = {
                Text(
                    stringResource(
                        if (profile.id == profiles.activeId) R.string.profiles_delete_active_text else R.string.profiles_delete_text,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    actions.onDelete(profile.id)
                    deleting = null
                }) { Text(stringResource(R.string.profiles_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profiles_title)) },
        text = {
            Column {
                profiles.profiles.forEach { profile ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        CheckMark(profile.id == profiles.activeId)
                        Text(
                            profile.name,
                            Modifier.weight(1f).padding(start = 8.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(onClick = { renaming = profile }) {
                            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.profiles_rename))
                        }
                        IconButton(onClick = { deleting = profile }, enabled = profiles.profiles.size > 1) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.profiles_delete))
                        }
                    }
                }
                Text(
                    stringResource(R.string.profiles_explanation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        },
        confirmButton = { TextButton(onClick = { creating = true }) { Text(stringResource(R.string.profiles_new)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

/** Asks for a profile name, refused while empty or taken. */
@Composable
private fun NameDialog(
    title: String,
    initial: String,
    isAvailable: (String) -> Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial) }
    val valid = isAvailable(name)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.profiles_name)) },
                    singleLine = true,
                    isError = name.isNotBlank() && !valid,
                )
                if (name.isNotBlank() && !valid) {
                    Text(stringResource(R.string.profiles_name_taken), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = valid) { Text(stringResource(R.string.acknowledge)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
