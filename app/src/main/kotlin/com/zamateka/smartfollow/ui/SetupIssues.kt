package com.zamateka.smartfollow.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.core.net.toUri
import com.zamateka.smartfollow.R

/** A system setting that must be granted for alarms to be reliable, with the screen that grants it. */
data class SetupIssue(@param:StringRes val title: Int, @param:StringRes val description: Int, val intent: Intent)

// Battery optimization exemption is requested directly: without it, Doze would delay polling and alarms.
@SuppressLint("BatteryLife")
fun setupIssues(context: Context): List<SetupIssue> {
    val packageUri = "package:${context.packageName}".toUri()
    val notificationManager = context.getSystemService(NotificationManager::class.java)
    val powerManager = context.getSystemService(PowerManager::class.java)
    return buildList {
        val notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!notificationsGranted || !notificationManager.areNotificationsEnabled()) {
            add(
                SetupIssue(
                    R.string.issue_notifications_title,
                    R.string.issue_notifications_description,
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                ),
            )
        }
        if (!powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
            add(
                SetupIssue(
                    R.string.issue_battery_title,
                    R.string.issue_battery_description,
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri),
                ),
            )
        }
        if (!notificationManager.isNotificationPolicyAccessGranted) {
            add(
                SetupIssue(
                    R.string.issue_dnd_title,
                    R.string.issue_dnd_description,
                    Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS),
                ),
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !notificationManager.canUseFullScreenIntent()) {
            add(
                SetupIssue(
                    R.string.issue_full_screen_title,
                    R.string.issue_full_screen_description,
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri),
                ),
            )
        }
    }
}
