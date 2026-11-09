package com.zamateka.smartfollow.alarm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zamateka.smartfollow.R
import com.zamateka.smartfollow.container
import com.zamateka.smartfollow.model.GlucoseReading
import com.zamateka.smartfollow.ui.GlucoseValue
import com.zamateka.smartfollow.ui.glucoseColor
import com.zamateka.smartfollow.ui.labelRes
import com.zamateka.smartfollow.ui.theme.SmartFollowTheme

/** Full-screen alarm shown over the lock screen. Closes itself once no alarm is sounding. */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val monitor = container.monitor
        setContent {
            SmartFollowTheme {
                val status by monitor.status.collectAsStateWithLifecycle()
                val alarm = status.evaluation?.alarm
                LaunchedEffect(alarm) { if (alarm == null) finish() }
                if (alarm != null) {
                    val reading = status.evaluation?.freshReading
                    AlarmScreen(
                        alarm = alarm,
                        reading = reading,
                        readingColor = reading?.let { glucoseColor(it.mgDl, monitor.settings) },
                        onAcknowledge = monitor::acknowledge,
                        onResugaring = { monitor.logResugaring() },
                    )
                }
            }
        }
    }
}

@Composable
private fun AlarmScreen(
    alarm: AlarmType,
    reading: GlucoseReading?,
    readingColor: Color?,
    onAcknowledge: () -> Unit,
    onResugaring: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(stringResource(alarm.labelRes()), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(24.dp))
            if (reading != null) {
                GlucoseValue(reading = reading, color = readingColor ?: MaterialTheme.colorScheme.onBackground)
            } else {
                Text(stringResource(R.string.no_recent_data), style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(Modifier.height(48.dp))
            if (alarm.offersResugaring()) {
                Button(onClick = onResugaring, modifier = Modifier.fillMaxWidth().height(72.dp)) {
                    Text(stringResource(R.string.resugaring_done), fontSize = 20.sp)
                }
                Spacer(Modifier.height(16.dp))
            }
            OutlinedButton(onClick = onAcknowledge, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                Text(stringResource(R.string.acknowledge), fontSize = 18.sp)
            }
        }
    }
}
