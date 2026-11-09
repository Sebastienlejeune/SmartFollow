package com.zamateka.smartfollow.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.zamateka.smartfollow.R
import com.zamateka.smartfollow.monitor.AlarmActionReceiver
import com.zamateka.smartfollow.monitor.Monitor
import com.zamateka.smartfollow.monitor.NightSummary
import com.zamateka.smartfollow.monitor.UserAction
import com.zamateka.smartfollow.monitor.UserActionRecord
import com.zamateka.smartfollow.ui.MainActivity
import com.zamateka.smartfollow.ui.glucoseText
import com.zamateka.smartfollow.ui.labelRes
import com.zamateka.smartfollow.ui.sourceLabelRes
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

object Notifications {
    const val ID_MONITORING = 1
    const val ID_ALARM = 2
    const val ID_ALERT = 3
    private const val ID_SHARED_ACTION = 4
    private const val ID_NIGHT_SUMMARY = 5
    private const val ID_TEST = 6
    private const val CHANNEL_MONITORING = "monitoring"
    private const val CHANNEL_ALARMS = "alarms"
    private const val CHANNEL_INFO = "info"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MONITORING,
                context.getString(R.string.channel_monitoring),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALARMS,
                context.getString(R.string.channel_alarms),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                // AlarmPlayer plays the sound on the alarm stream and vibrates, not the notification.
                setSound(null, null)
                enableVibration(false)
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        // What the other parent did and the night summaries: worth reading, not waking up for.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_INFO,
                context.getString(R.string.channel_info),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    /** Persistent notification of the foreground service, showing the latest glucose and the active profile. */
    fun monitoring(context: Context, status: Monitor.Status, profileName: String): Notification {
        val evaluation = status.evaluation
        val reading = evaluation?.freshReading
        val snoozeEnd = status.snoozeEndsAtMillis
        val text = when {
            !status.running -> context.getString(R.string.monitoring_stopped)
            snoozeEnd != null -> context.getString(
                R.string.snooze_until,
                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(snoozeEnd)),
            )
            evaluation?.conditions?.isNotEmpty() == true ->
                evaluation.conditions.sortedBy { it.ordinal }.joinToString(" · ") { context.getString(it.labelRes()) }
            else -> context.getString(R.string.in_range)
        }
        return NotificationCompat.Builder(context, CHANNEL_MONITORING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(reading?.let { glucoseText(context, it) } ?: context.getString(R.string.no_recent_data))
            .setContentText(text)
            .setSubText(profileName)
            .setShowWhen(reading != null)
            .setWhen(reading?.timestampMillis ?: 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(activityIntent(context, MainActivity::class.java))
            .build()
    }

    /** Notification of a continuous alarm; a [muted] one does not open the full-screen alarm. */
    fun alarm(context: Context, type: AlarmType, status: Monitor.Status, muted: Boolean): Notification {
        val reading = status.evaluation?.freshReading
        return NotificationCompat.Builder(context, CHANNEL_ALARMS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(type.labelRes()))
            .setContentText(reading?.let { glucoseText(context, it) } ?: context.getString(R.string.no_recent_data))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            // Touching the notification stops the sound; the full screen keeps its own buttons.
            .setContentIntent(acknowledgeIntent(context))
            .apply { if (!muted) setFullScreenIntent(activityIntent(context, AlarmActivity::class.java), true) }
            .addAction(0, context.getString(R.string.acknowledge), AlarmActionReceiver.pendingIntent(context, AlarmActionReceiver.ACTION_ACKNOWLEDGE))
            .apply {
                if (type.offersResugaring()) {
                    addAction(0, context.getString(R.string.resugaring_done), AlarmActionReceiver.pendingIntent(context, AlarmActionReceiver.ACTION_RESUGARING))
                }
            }
            .build()
    }

    /**
     * Notification of a non-continuous alarm: its sound plays once, and it rings again after its
     * repeat interval while the condition lasts. Touching it, like "OK", stops the sound.
     */
    fun alert(context: Context, type: AlarmType, status: Monitor.Status): Notification {
        val reading = status.evaluation?.freshReading
        return NotificationCompat.Builder(context, CHANNEL_ALARMS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(type.labelRes()))
            .setContentText(reading?.let { glucoseText(context, it) } ?: context.getString(R.string.no_recent_data))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(acknowledgeIntent(context))
            .addAction(0, context.getString(R.string.acknowledge), AlarmActionReceiver.pendingIntent(context, AlarmActionReceiver.ACTION_ACKNOWLEDGE))
            .apply {
                if (type.offersResugaring()) {
                    addAction(0, context.getString(R.string.resugaring_done), AlarmActionReceiver.pendingIntent(context, AlarmActionReceiver.ACTION_RESUGARING))
                }
            }
            .build()
    }

    /** Tells who took care of an alarm on another device, for example "OK pressed at 02:11, from a notification". */
    fun sharedAction(context: Context, record: UserActionRecord): Notification {
        val title = when (record.action) {
            UserAction.ACKNOWLEDGED -> R.string.event_acknowledged
            UserAction.RESUGARING -> R.string.event_resugaring
            UserAction.RESUGARING_CANCELLED -> R.string.event_resugaring_cancelled
        }
        val source = sourceLabelRes(record.source)?.let(context::getString) ?: record.source
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(record.timestampMillis))
        return NotificationCompat.Builder(context, CHANNEL_INFO)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(title))
            .setContentText(context.getString(R.string.shared_action_text, time, source))
            .setAutoCancel(true)
            .setContentIntent(activityIntent(context, MainActivity::class.java))
            .build()
    }

    fun nightSummary(context: Context, summary: NightSummary): Notification {
        val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
        val text = nightSummaryText(context, summary)
        return NotificationCompat.Builder(context, CHANNEL_INFO)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                context.getString(
                    R.string.night_summary_title,
                    timeFormat.format(Date(summary.night.fromMillis)),
                    timeFormat.format(Date(summary.night.toMillis)),
                ),
            )
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(activityIntent(context, MainActivity::class.java))
            .build()
    }

    fun notify(context: Context, action: UserActionRecord) =
        context.getSystemService(NotificationManager::class.java).notify(ID_SHARED_ACTION, sharedAction(context, action))

    fun notify(context: Context, summary: NightSummary) =
        context.getSystemService(NotificationManager::class.java).notify(ID_NIGHT_SUMMARY, nightSummary(context, summary))

    /** How an alarm of [type] shows when it is muted: a silent notification, from the settings' test button. */
    fun test(context: Context, type: AlarmType) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ALARMS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.test_notification_title, context.getString(type.labelRes())))
            .setContentText(context.getString(R.string.test_notification_text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter(TEST_TIMEOUT_MILLIS)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID_TEST, notification)
    }

    private const val TEST_TIMEOUT_MILLIS = 15_000L

    private fun nightSummaryText(context: Context, summary: NightSummary): String = buildList {
        if (summary.readingCount == 0) {
            add(context.getString(R.string.night_summary_no_data))
        } else {
            add(
                context.getString(
                    R.string.night_summary_ranges,
                    (summary.inTarget * 100).roundToInt(),
                    summary.lowMgDl,
                    durationText(context, summary.belowMinutes),
                    summary.targetHighMgDl,
                    durationText(context, summary.aboveMinutes),
                ),
            )
            add(context.getString(R.string.night_summary_glucose, summary.minMgDl, summary.meanMgDl, summary.maxMgDl))
        }
        val resources = context.resources
        add(
            resources.getQuantityString(R.plurals.night_summary_alarms, summary.alarms, summary.alarms) + " · " +
                resources.getQuantityString(R.plurals.night_summary_resugarings, summary.resugarings, summary.resugarings),
        )
    }.joinToString("\n")

    private fun durationText(context: Context, minutes: Int): String =
        if (minutes < 60) context.getString(R.string.minutes_value, minutes) else context.getString(R.string.hours_minutes, minutes / 60, minutes % 60)

    /** Opens the app and counts as "OK", for every type of alarm. */
    private fun acknowledgeIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            MainActivity.EXTRA_ACKNOWLEDGE.hashCode(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_ACKNOWLEDGE, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun activityIntent(context: Context, activity: Class<*>): PendingIntent =
        PendingIntent.getActivity(
            context,
            activity.name.hashCode(),
            Intent(context, activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
