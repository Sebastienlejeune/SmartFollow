package com.zamateka.smartfollow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.zamateka.smartfollow.R
import com.zamateka.smartfollow.sync.SyncServer
import kotlinx.coroutines.launch

/** The optional sharing server: its address and token, tested before they are saved. */
@Composable
fun SyncServerScreen(
    server: SyncServer?,
    onSave: suspend (SyncServer) -> Result<Unit>,
    onRemove: () -> Unit,
    onBack: () -> Unit,
) {
    var url by rememberSaveable(server) { mutableStateOf(server?.url ?: "https://") }
    var token by rememberSaveable(server) { mutableStateOf(server?.token.orEmpty()) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Result<Unit>?>(null) }
    val scope = rememberCoroutineScope()
    val secure = url.startsWith("https://")

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsTopBar(stringResource(R.string.settings_server), onBack)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.settings_sync_description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = url,
                onValueChange = { url = it.trim() },
                label = { Text(stringResource(R.string.settings_sync_url)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                isError = !secure,
                supportingText = if (secure) null else ({ Text(stringResource(R.string.settings_sync_https)) }),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it.trim() },
                label = { Text(stringResource(R.string.settings_sync_token)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    enabled = !testing && secure && token.isNotEmpty(),
                    onClick = {
                        testing = true
                        result = null
                        scope.launch {
                            result = onSave(SyncServer(url.trimEnd('/'), token))
                            testing = false
                        }
                    },
                ) { Text(stringResource(R.string.settings_sync_save)) }
                if (server != null) {
                    TextButton(onClick = {
                        result = null
                        onRemove()
                    }) { Text(stringResource(R.string.settings_sync_remove), color = MaterialTheme.colorScheme.error) }
                }
            }
            result?.let {
                Text(
                    text = it.fold(
                        onSuccess = { stringResource(R.string.settings_sync_saved) },
                        onFailure = { error -> stringResource(R.string.settings_sync_failed, error.message.orEmpty()) },
                    ),
                    color = if (it.isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
