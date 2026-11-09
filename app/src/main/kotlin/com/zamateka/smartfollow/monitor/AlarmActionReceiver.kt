package com.zamateka.smartfollow.monitor

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zamateka.smartfollow.alarm.Notifications
import com.zamateka.smartfollow.container

/** Handles the buttons of the alarm notifications. */
class AlarmActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val monitor = context.container.monitor
        when (intent.action) {
            ACTION_ACKNOWLEDGE -> monitor.acknowledge()
            ACTION_RESUGARING -> monitor.logResugaring()
        }
        // The alert is answered: its notification has nothing left to offer.
        context.getSystemService(NotificationManager::class.java).cancel(Notifications.ID_ALERT)
    }

    companion object {
        const val ACTION_ACKNOWLEDGE = "com.zamateka.smartfollow.action.ACKNOWLEDGE"
        const val ACTION_RESUGARING = "com.zamateka.smartfollow.action.RESUGARING"

        fun pendingIntent(context: Context, action: String): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                action.hashCode(),
                Intent(context, AlarmActionReceiver::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
