package com.zamateka.smartfollow.server

import com.zamateka.smartfollow.alarm.AlarmPreferences
import com.zamateka.smartfollow.model.GlucoseReading
import com.zamateka.smartfollow.model.GlucoseStatistics
import com.zamateka.smartfollow.monitor.Monitor
import com.zamateka.smartfollow.monitor.NightSummary
import com.zamateka.smartfollow.monitor.UserActionRecord
import com.zamateka.smartfollow.monitor.runNightSummaries
import com.zamateka.smartfollow.share.DexcomShareClient
import com.zamateka.smartfollow.sync.ActionRequest
import com.zamateka.smartfollow.sync.StatisticsResponse
import com.zamateka.smartfollow.sync.StatusResponse
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The server's monitoring: the same [Monitor] as the Android app, fed by the server's Dexcom
 * account and the alarm preferences replicated from the app. Alarms go to ntfy; actions made
 * on any device are logged in [actions] for the others to pick up.
 */
class SmartFollowServer(
    val config: ServerConfig,
    httpClient: HttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    val preferences = PreferencesStore(config.dataDir)
    val events = EventLogFile(config.dataDir, clock = clock)
    val readings = ReadingLogFile(config.dataDir, clock = clock)
    val actions = ActionLog()
    val ntfyFilter = NtfyFilterStore(config.dataDir)
    private val notifier = NtfyNotifier(httpClient, config, filter = ntfyFilter::load)
    private val shareClient = DexcomShareClient(httpClient, config.dexcomRegion, config.dexcomUsername, config.dexcomPassword)

    val monitor = Monitor(
        initialSettings = preferences.load().toAlarmSettings(),
        stateStore = FileAlarmStateRepository(config.dataDir),
        clientProvider = { shareClient },
        localSource = LOCAL_SOURCE,
        eventLog = events::append,
        onReadings = readings::append,
        onUserAction = ::share,
        clock = clock,
        scope = scope,
    )

    fun start() {
        monitor.start()
        notifier.watch(scope, monitor)
        scope.launch {
            runNightSummaries(preferences = preferences::load, zone = { config.zone }, clock = clock) { night ->
                val summary = NightSummary.compute(
                    night,
                    monitor.status.value.readings,
                    events.between(night.fromMillis, night.toMillis),
                    monitor.settings,
                )
                notifier.notifyNightSummary(summary)
            }
        }
    }

    fun stop() {
        monitor.stop()
        scope.cancel()
    }

    /**
     * Handles an action from a device. An action the device already applied (with an id and
     * a time, from the Android app) is relayed; a bare action (web page, notification button)
     * is performed here.
     */
    fun handle(request: ActionRequest): UserActionRecord {
        val id = request.id
        val timestamp = request.timestampMillis
        if (id == null || timestamp == null) return monitor.perform(request.action, request.source ?: "web")
        val record = UserActionRecord(id, request.action, timestamp, request.source ?: "android")
        if (actions.append(record)) {
            monitor.applyRemote(record)
            notifier.notifyAction(scope, record)
        }
        return record
    }

    /** Statistics over the last [days] days, from the readings kept by the server. */
    fun statistics(days: Int): StatisticsResponse {
        val now = clock()
        val from = now - days * DAY_MILLIS
        return StatisticsResponse(GlucoseStatistics.compute(readings.since(from), from, now))
    }

    fun readingsOfLastDays(days: Int): List<GlucoseReading> = readings.since(clock() - days * DAY_MILLIS)

    /** Applies the preferences replicated by the app, and logs a change of profile. */
    fun updatePreferences(newPreferences: AlarmPreferences): AlarmPreferences {
        val previousProfileId = preferences.load().profileId
        preferences.save(newPreferences)
        val saved = preferences.load()
        monitor.updateSettings(saved.toAlarmSettings())
        if (saved.profileId != null && saved.profileId != previousProfileId) monitor.logProfileChange(saved.profileName.orEmpty())
        return saved
    }

    fun status(): StatusResponse {
        val status = monitor.status.value
        val evaluation = status.evaluation
        return StatusResponse(
            serverTimeMillis = clock(),
            running = status.running,
            latest = status.readings.lastOrNull(),
            fresh = evaluation?.freshReading != null,
            rateMgDlPerMin = evaluation?.rateMgDlPerMin,
            conditions = evaluation?.conditions.orEmpty().sortedBy { it.ordinal },
            alarm = evaluation?.alarm,
            snoozeEndsAtMillis = status.snoozeEndsAtMillis,
            lastFetchMillis = status.lastFetchMillis,
            lastError = status.lastError?.let { it.message ?: it::class.simpleName },
        )
    }

    /** Actions performed here are logged for the other devices and announced on ntfy. */
    private fun share(record: UserActionRecord) {
        actions.append(record)
        notifier.notifyAction(scope, record)
    }

    private companion object {
        const val LOCAL_SOURCE = "server"
        const val DAY_MILLIS = 24 * 60 * 60_000L
    }
}
