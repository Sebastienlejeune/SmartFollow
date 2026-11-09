package com.zamateka.smartfollow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.zamateka.smartfollow.R
import com.zamateka.smartfollow.settings.ShareAccount
import com.zamateka.smartfollow.share.ShareRegion
import kotlinx.coroutines.launch

/**
 * Asks for the Dexcom account of the sensor wearer and checks it against Share
 * with [onTest] before handing it to [onConnected].
 */
@Composable
fun SetupScreen(
    onTest: suspend (ShareAccount) -> Result<Unit>,
    onConnected: (ShareAccount) -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var region by remember { mutableStateOf(ShareRegion.OUTSIDE_US) }
    var testing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.setup_explanation), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = username,
            onValueChange = { username = it.trim() },
            label = { Text(stringResource(R.string.setup_username)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.setup_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.setup_region), style = MaterialTheme.typography.titleSmall)
        Column {
            listOf(
                ShareRegion.OUTSIDE_US to R.string.region_outside_us,
                ShareRegion.US to R.string.region_us,
                ShareRegion.JAPAN to R.string.region_japan,
            ).forEach { (value, label) ->
                Row(
                    Modifier.fillMaxWidth().selectable(selected = region == value, onClick = { region = value }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = region == value, onClick = null)
                    Text(stringResource(label), Modifier.padding(start = 8.dp))
                }
            }
        }
        error?.let { Text(errorText(it), color = MaterialTheme.colorScheme.error) }
        Button(
            enabled = !testing && username.isNotBlank() && password.isNotEmpty(),
            onClick = {
                val account = ShareAccount(username, password, region)
                testing = true
                error = null
                scope.launch {
                    onTest(account)
                        .onSuccess { onConnected(account) }
                        .onFailure { error = it }
                    testing = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (testing) CircularProgressIndicator(Modifier.padding(4.dp)) else Text(stringResource(R.string.setup_connect))
        }
    }
}
