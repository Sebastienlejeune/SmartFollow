package com.zamateka.smartfollow.monitor

import com.zamateka.smartfollow.alarm.AlarmEngine
import com.zamateka.smartfollow.alarm.AlarmSettings
import com.zamateka.smartfollow.alarm.AlarmState
import com.zamateka.smartfollow.alarm.AlarmType
import com.zamateka.smartfollow.alarm.Evaluation
import com.zamateka.smartfollow.alarm.EventTracker
import com.zamateka.smartfollow.alarm.MonitorEvent
import com.zamateka.smartfollow.alarm.MonitorEvent.Kind
import com.zamateka.smartfollow.model.GlucoseReading
import com.zamateka.smartfollow.share.DexcomShareClient
import com.zamateka.smartfollow.share.ReadingHistory
import com.zamateka.smartfollow.share.SharePollingPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Polls Share and runs the alarm engine. Lives as long as the process: the Android service
 * or the server starts and stops it, and observes [status] to show and ring alarms.
 *
 * User actions made here go through [perform] and are reported to [onUserAction], so that
 * they can be shared; actions made on another device come in through [applyRemote].
 */
class Monitor(
    initialSettings: AlarmSettings,
    private val stateStore: AlarmStateRepository,
    /** Returns the client for the configured account, or `null` when no account is set. */
    private val clientProvider: () -> DexcomShareClient?,
    /** Source recorded for the actions made on this device. */
    val localSource: String,
    /** Receives the history events; called with the monitor's lock held, so it must not block. */
    private val eventLog: (List<MonitorEvent>) -> Unit = {},
    /** Receives the actions made on this device; called with the lock held, so it must not block. */
    private val onUserAction: (UserActionRecord) -> Unit = {},
    /** Receives each batch of downloaded readings, to keep a longer history; must not block. */
    private val onReadings: (List<GlucoseReading>) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    data class Status(
        val running: Boolean = false,
        /** Readings of the last 24 hours, oldest first. */
        val readings: List<GlucoseReading> = emptyList(),
        /** `null` while stopped, so that no alarm can sound. */
        val evaluation: Evaluation? = null,
        val lastFetchMillis: Long? = null,
        val lastError: Throwable? = null,
        /** End of the running resugaring snooze, if any. */
        val snoozeEndsAtMillis: Long? = null,
    )

    @Volatile
    var settings: AlarmSettings = initialSettings
        private set
    private var engine = AlarmEngine(initialSettings)
    private val eventTracker = EventTracker()
    private val lock = Any()
    private var jobs: List<Job> = emptyList()
    private var state: AlarmState = stateStore.load() ?: engine.initialState(clock())
    private val appliedActionIds = LinkedHashSet<String>()
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()
    private val _alerts = MutableSharedFlow<AlarmType>(extraBufferCapacity = 8)

    /** Non-continuous alarms, emitted once when they ring; continuous ones are in [status]. */
    val alerts: SharedFlow<AlarmType> = _alerts.asSharedFlow()
    private val _actions = MutableSharedFlow<UserActionRecord>(extraBufferCapacity = 8)

    /**
     * Every action applied here, made on this device ([localSource]) or on another one: to stop
     * a sound once someone took care of the alarm, and tell who did.
     */
    val actions: SharedFlow<UserActionRecord> = _actions.asSharedFlow()

    fun start(): Unit = synchronized(lock) {
        if (jobs.isNotEmpty()) return
        // Restart the stale data grace period, but keep any running snooze or acknowledgement.
        state = state.copy(monitoringStartedAtMillis = clock())
        _status.update { it.copy(running = true) }
        eventTracker.reset()
        log(Kind.MONITORING_STARTED)
        reevaluate()
        jobs = listOf(scope.launch { pollLoop() }, scope.launch { tickLoop() })
    }

    fun stop() = synchronized(lock) {
        if (jobs.isNotEmpty()) log(Kind.MONITORING_STOPPED)
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        _status.update { it.copy(running = false, evaluation = null) }
    }

    /** Stops monitoring and forgets the readings, for instance when the account changes. */
    fun reset() = synchronized(lock) {
        stop()
        _status.value = Status()
    }

    /** Fetches immediately instead of waiting for the next scheduled poll. */
    fun refresh(): Unit = synchronized(lock) {
        if (jobs.isEmpty()) return
        jobs.first().cancel()
        jobs = listOf(scope.launch { pollLoop() }) + jobs.drop(1)
    }

    /** Applies new settings immediately, including to a running snooze. */
    fun updateSettings(newSettings: AlarmSettings) = synchronized(lock) {
        settings = newSettings
        engine = AlarmEngine(newSettings)
        reevaluate()
    }

    /** Records in the history that the profile named [name] became active. */
    fun logProfileChange(name: String) = synchronized(lock) { log(Kind.PROFILE_CHANGED, profile = name) }

    fun logResugaring() = perform(UserAction.RESUGARING)

    fun cancelResugaring() = perform(UserAction.RESUGARING_CANCELLED)

    /** Silences every active condition for its repeat interval. */
    fun acknowledge() = perform(UserAction.ACKNOWLEDGED)

    /** Performs an action made on this device, and reports it to [onUserAction]. */
    fun perform(action: UserAction, source: String = localSource): UserActionRecord = synchronized(lock) {
        val record = UserActionRecord(UUID.randomUUID().toString(), action, clock(), source)
        apply(record)
        onUserAction(record)
        _actions.tryEmit(record)
        record
    }

    /**
     * Applies an action made on another device, at the time it was made. Returns `false` when
     * it was ignored: already applied, or too old to matter anymore.
     */
    fun applyRemote(record: UserActionRecord): Boolean = synchronized(lock) {
        if (record.id in appliedActionIds) return false
        val age = clock() - record.timestampMillis
        val maxAge = when (record.action) {
            UserAction.RESUGARING -> settings.resugaringSnooze.inWholeMilliseconds
            UserAction.RESUGARING_CANCELLED, UserAction.ACKNOWLEDGED -> REMOTE_ACTION_MAX_AGE.inWholeMilliseconds
        }
        if (age > maxAge) {
            remember(record.id)
            return false
        }
        apply(record)
        _actions.tryEmit(record)
        true
    }

    /** Must be called with [lock] held. */
    private fun apply(record: UserActionRecord) {
        remember(record.id)
        val kind = when (record.action) {
            UserAction.RESUGARING -> Kind.RESUGARING
            UserAction.RESUGARING_CANCELLED -> Kind.RESUGARING_CANCELLED
            UserAction.ACKNOWLEDGED -> Kind.ACKNOWLEDGED
        }
        val alarm = _status.value.evaluation?.alarm.takeIf { record.action == UserAction.ACKNOWLEDGED }
        log(kind, alarm, source = record.source.takeIf { it != localSource })
        val at = record.timestampMillis
        state = when (record.action) {
            UserAction.RESUGARING -> engine.logResugaring(state, _status.value.readings, at)
            UserAction.RESUGARING_CANCELLED -> engine.cancelResugaring(state)
            UserAction.ACKNOWLEDGED -> engine.acknowledge(state, _status.value.evaluation?.conditions.orEmpty(), at)
        }
        stateStore.save(state)
        reevaluate()
    }

    private fun remember(id: String) {
        appliedActionIds += id
        if (appliedActionIds.size > MAX_REMEMBERED_ACTIONS) appliedActionIds.remove(appliedActionIds.first())
    }

    private suspend fun pollLoop() {
        var consecutiveErrors = 0
        // Start with a full download, so that a manual refresh also restores the whole history.
        var pollsSinceFullRefresh = ReadingHistory.FULL_REFRESH_EVERY
        while (true) {
            val wait = try {
                val client = clientProvider() ?: throw IllegalStateException("No Dexcom account configured")
                val request = ReadingHistory.nextRequest(_status.value.readings, clock(), pollsSinceFullRefresh)
                val fetched = client.latestReadings(request.minutes, request.maxCount)
                onReadings(fetched)
                pollsSinceFullRefresh = if (ReadingHistory.isFullRefresh(request)) 1 else pollsSinceFullRefresh + 1
                consecutiveErrors = 0
                val readings = synchronized(lock) {
                    val merged = ReadingHistory.merge(_status.value.readings, fetched, clock())
                    _status.update { it.copy(readings = merged, lastFetchMillis = clock(), lastError = null) }
                    reevaluate()
                    merged
                }
                SharePollingPolicy.afterSuccess(readings.lastOrNull()?.timestampMillis, clock())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                consecutiveErrors++
                _status.update { it.copy(lastError = e) }
                SharePollingPolicy.afterError(e, consecutiveErrors)
            }
            delay(wait)
        }
    }

    /** Re-evaluates without new readings, so that stale data and snooze expiry are detected. */
    private suspend fun tickLoop() {
        while (true) {
            delay(TICK)
            synchronized(lock) { reevaluate() }
        }
    }

    /** Must be called with [lock] held. */
    private fun reevaluate() {
        if (!_status.value.running) return
        val now = clock()
        val evaluation = engine.evaluate(state, _status.value.readings, now)
        eventLog(eventTracker.track(evaluation, now, settings.resugaringSnooze.inWholeMilliseconds))
        evaluation.alert?.let { _alerts.tryEmit(it) }
        if (evaluation.state != state) {
            state = evaluation.state
            stateStore.save(state)
        }
        val snoozeEndsAtMillis = state.resugaring?.let {
            it.startedAtMillis + settings.resugaringSnooze.inWholeMilliseconds
        }
        _status.update { it.copy(evaluation = evaluation, snoozeEndsAtMillis = snoozeEndsAtMillis) }
    }

    /** Must be called with [lock] held. */
    private fun log(kind: Kind, alarmType: AlarmType? = null, source: String? = null, profile: String? = null) {
        val mgDl = _status.value.evaluation?.freshReading?.mgDl
        eventLog(listOf(MonitorEvent(clock(), kind, alarmType, mgDl, source, profile)))
    }

    private companion object {
        val TICK = 30.seconds

        /** A remote acknowledgement or cancellation older than this no longer reflects the situation. */
        val REMOTE_ACTION_MAX_AGE = 10.minutes
        const val MAX_REMEMBERED_ACTIONS = 500
    }
}
