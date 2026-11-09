package com.zamateka.smartfollow.sync

import com.zamateka.smartfollow.alarm.AlarmType
import com.zamateka.smartfollow.model.GlucoseReading
import com.zamateka.smartfollow.model.GlucoseStatistics
import com.zamateka.smartfollow.monitor.UserAction
import com.zamateka.smartfollow.monitor.UserActionRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * HTTP API of the SmartFollow server. Every endpoint needs `Authorization: Bearer <token>`;
 * the action token is only accepted by `POST /api/actions`.
 *
 * - `GET  /api/status`                current glucose and alarm state ([StatusResponse])
 * - `GET  /api/readings?days=N`       readings of the last 24 hours, or of the last N days
 *                                     (1 to 90, kept by the server), oldest first
 * - `GET  /api/statistics?days=N`     CGM metrics over the last N days ([StatisticsResponse])
 * - `POST /api/refresh`               downloads from Dexcom Share now
 * - `GET  /api/events?limit=N`        history, newest first
 * - `GET  /api/actions?after=N`       shared actions after sequence N ([ActionsResponse]); with
 *   `&epoch=E&wait=S`, waits up to S seconds (at most 300) for one if the server's epoch is E
 * - `POST /api/actions`               performs or relays an action ([ActionRequest])
 * - `GET  /api/settings`              alarm preferences
 * - `PUT  /api/settings`              replaces the alarm preferences
 * - `GET  /api/ntfy-filter`           notifications sent to ntfy ([NtfyFilter])
 * - `PUT  /api/ntfy-filter`           replaces them
 */
object SyncApi {
    const val STATUS = "/api/status"
    const val READINGS = "/api/readings"
    const val EVENTS = "/api/events"
    const val ACTIONS = "/api/actions"
    const val SETTINGS = "/api/settings"
    const val STATISTICS = "/api/statistics"
    const val REFRESH = "/api/refresh"
    const val NTFY_FILTER = "/api/ntfy-filter"

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}

@Serializable
data class StatusResponse(
    val serverTimeMillis: Long,
    val running: Boolean,
    val latest: GlucoseReading?,
    /** Whether [latest] is recent enough to be trusted. */
    val fresh: Boolean,
    val rateMgDlPerMin: Double?,
    val conditions: List<AlarmType>,
    /** The alarm that is sounding, or `null`. */
    val alarm: AlarmType?,
    val snoozeEndsAtMillis: Long?,
    val lastFetchMillis: Long?,
    val lastError: String?,
)

/**
 * A shared action. Devices that keep their own alarm state (the Android app) send the [id]
 * and [timestampMillis] of the action they already applied; simple clients (web page,
 * notification buttons) send only [action] and let the server perform it.
 */
@Serializable
data class ActionRequest(
    val action: UserAction,
    val id: String? = null,
    val timestampMillis: Long? = null,
    val source: String? = null,
)

/**
 * Which notifications the server sends to ntfy, chosen from the web page by the parent who
 * follows the ntfy topic. The Android app's own alarms are not concerned.
 */
@Serializable
data class NtfyFilter(
    /** Alarms not sent to ntfy. */
    val mutedAlarms: Set<AlarmType> = emptySet(),
    /** Resugarings, and their cancellations, made on another device. */
    val actions: Boolean = true,
    val nightSummary: Boolean = true,
)

/** `statistics` is `null` when no reading was kept over the period. */
@Serializable
data class StatisticsResponse(val statistics: GlucoseStatistics?)

@Serializable
data class SequencedAction(val sequence: Long, val record: UserActionRecord)

@Serializable
data class ActionsResponse(
    /** Changes when the server restarts; sequences of different epochs are not comparable. */
    val epoch: String,
    val lastSequence: Long,
    val actions: List<SequencedAction>,
)
