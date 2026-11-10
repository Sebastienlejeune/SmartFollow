package com.zamateka.smartfollow.monitor

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import android.os.LocaleList
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.zamateka.smartfollow.alarm.AlarmPlayer
import com.zamateka.smartfollow.alarm.AlarmSounds
import com.zamateka.smartfollow.alarm.Notifications
import com.zamateka.smartfollow.container
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

/**
 * Foreground service that keeps [Monitor] running, mirrors its status in the persistent
 * notification and rings while an alarm is active. It also tells who took care of an alarm on
 * another device, and sends the night summary.
 */
class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var player: AlarmPlayer
    private lateinit var notificationManager: NotificationManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var locales: LocaleList? = null

    override fun onBind(intent: Intent?): IBinder? = null

    /** A new app language (settings) rewrites the persistent notification at once. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.locales == locales) return
        locales = newConfig.locales
        val status = container.monitor.status.value
        notificationManager.notify(Notifications.ID_MONITORING, Notifications.monitoring(this, status, container.profiles.value.active.name))
    }

    // The wake lock is held for as long as monitoring runs: without it, polling and alarm
    // timers would stall while the phone sleeps. It is released in onDestroy.
    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        val monitor = container.monitor
        player = AlarmPlayer(this)
        notificationManager = getSystemService(NotificationManager::class.java)
        locales = resources.configuration.locales
        ServiceCompat.startForeground(
            this,
            Notifications.ID_MONITORING,
            Notifications.monitoring(this, monitor.status.value, container.profiles.value.active.name),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmartFollow:monitor")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
        monitor.start()
        // Sharing runs off the main thread; it does nothing while no server is set.
        container.actionSharing.start(scope + Dispatchers.Default)

        scope.launch {
            combine(monitor.status, container.profiles) { status, profiles -> status to profiles.active.name }.collect { (status, profile) ->
                notificationManager.notify(Notifications.ID_MONITORING, Notifications.monitoring(this@MonitorService, status, profile))
            }
        }
        scope.launch {
            monitor.status.map { it.evaluation?.alarm }.distinctUntilChanged().collect { alarm ->
                if (alarm == null) {
                    player.stop()
                    notificationManager.cancel(Notifications.ID_ALARM)
                } else {
                    val preferences = container.settings.alarmPreferences
                    val muted = preferences.forType(alarm).muted
                    notificationManager.cancel(Notifications.ID_ALERT)
                    notificationManager.notify(Notifications.ID_ALARM, Notifications.alarm(this@MonitorService, alarm, monitor.status.value, muted))
                    // Muted: only the notification, without the full screen.
                    if (muted) player.stop() else player.start(
                        sound = AlarmSounds.uri(this@MonitorService, alarm, preferences),
                        vibrate = preferences.forType(alarm).vibrate,
                        volumePercent = preferences.alarmVolumePercentAt(),
                    )
                }
            }
        }
        scope.launch {
            monitor.alerts.collect { alert ->
                notificationManager.notify(Notifications.ID_ALERT, Notifications.alert(this@MonitorService, alert, monitor.status.value))
                val preferences = container.settings.alarmPreferences
                // A continuous alarm already ringing keeps the speaker; a muted one does not ring.
                val ringing = monitor.status.value.evaluation?.alarm?.takeUnless { preferences.forType(it).muted }
                if (ringing == null && !preferences.forType(alert).muted) {
                    player.start(
                        sound = AlarmSounds.uri(this@MonitorService, alert, preferences),
                        vibrate = preferences.forType(alert).vibrate,
                        volumePercent = preferences.alarmVolumePercentAt(),
                        // A non-continuous alarm plays its sound once.
                        loop = false,
                    )
                }
            }
        }
        scope.launch {
            monitor.actions.collect { action ->
                // Someone took care of the alarm, here or on another device: silence this phone,
                // unless a continuous alarm still rings (an urgent low goes on after a resugaring).
                if (action.action != UserAction.RESUGARING_CANCELLED) {
                    if (monitor.status.value.evaluation?.alarm == null) player.stop()
                    notificationManager.cancel(Notifications.ID_ALERT)
                }
                // Only a resugaring made elsewhere is worth telling; an OK is not.
                if (action.source != monitor.localSource && action.action != UserAction.ACKNOWLEDGED) {
                    Notifications.notify(this@MonitorService, action)
                }
            }
        }
        scope.launch(Dispatchers.Default) {
            runNightSummaries(preferences = { container.settings.alarmPreferences }) { night ->
                val summary = NightSummary.compute(
                    night,
                    container.readingStore.since(night.fromMillis),
                    container.eventStore.events.value,
                    monitor.settings,
                )
                Notifications.notify(this@MonitorService, summary)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // After the system restarts a killed service, only resume if monitoring is still wanted.
        if (!container.settings.monitoringEnabled) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        container.monitor.stop()
        player.stop()
        notificationManager.cancel(Notifications.ID_ALARM)
        wakeLock?.release()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            context.container.settings.monitoringEnabled = true
            ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
        }

        fun stop(context: Context) {
            context.container.settings.monitoringEnabled = false
            context.stopService(Intent(context, MonitorService::class.java))
        }
    }
}
