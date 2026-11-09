package com.zamateka.smartfollow.sync

import com.zamateka.smartfollow.alarm.AlarmPreferences
import com.zamateka.smartfollow.monitor.Monitor
import com.zamateka.smartfollow.monitor.UserActionRecord
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Optional SmartFollow server used to share actions and settings with the other parent. */
data class SyncServer(val url: String, val token: String)

/**
 * Shares the actions made on this device with the SmartFollow server, applies those made
 * elsewhere, and replicates the alarm preferences to the server. The monitor never depends on
 * it: without a server, or while it is unreachable, the device keeps working on its own.
 */
class ActionSharing(
    private val httpClient: HttpClient,
    /** `null` disables sharing. */
    private val server: () -> SyncServer?,
    private val preferences: () -> AlarmPreferences,
    private val monitor: () -> Monitor,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Shortest time between two exchanges, unless a local change interrupts the wait. */
    private val interval: Duration = 15.seconds,
    /**
     * How long the server may hold a request for actions: it answers as soon as one arrives, so
     * the device learns of it at once while exchanging only every few minutes, which lets the
     * phone's radio sleep.
     */
    private val longPoll: Duration = 4.minutes,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    sealed interface State {
        data object Disabled : State
        data class Connected(val lastSyncMillis: Long) : State
        data class Failing(val message: String, val sinceMillis: Long) : State
    }

    private val _state = MutableStateFlow<State>(State.Disabled)
    val state: StateFlow<State> = _state.asStateFlow()

    private val pending = ConcurrentLinkedQueue<UserActionRecord>()
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var settingsChanged = true
    private var epoch: String? = null
    private var lastSequence = 0L

    /** Queues an action made on this device; non-blocking, so it can be the monitor's listener. */
    fun share(record: UserActionRecord) {
        if (server() == null) return
        pending += record
        while (pending.size > MAX_PENDING) pending.poll()
        wakeUp.trySend(Unit)
    }

    fun settingsChanged() {
        settingsChanged = true
        wakeUp.trySend(Unit)
    }

    fun start(scope: CoroutineScope): Job = scope.launch {
        while (true) {
            val started = timeSource.markNow()
            // A failure is retried after the interval; so is an answer that comes back early with
            // no action, as from a server without long polling, so that the loop never spins.
            if (!sync(longPoll)) continue
            val left = interval - started.elapsedNow()
            if (left.isPositive()) withTimeoutOrNull(left) { wakeUp.receive() }
        }
    }

    /**
     * One exchange with the server: pending actions and settings out, other devices' actions in.
     * The server may hold the request for actions up to [wait]. Returns `false` when a local
     * action or settings change interrupted that wait, to be sent at once.
     */
    suspend fun sync(wait: Duration = Duration.ZERO): Boolean {
        val target = server()
        if (target == null) {
            pending.clear()
            epoch = null
            _state.value = State.Disabled
            return true
        }
        val client = SyncClient(httpClient, target.url, target.token)
        try {
            while (true) {
                val record = pending.peek() ?: break
                client.post(record)
                pending.poll()
            }
            if (settingsChanged) {
                settingsChanged = false
                try {
                    client.putSettings(preferences())
                } catch (e: Exception) {
                    settingsChanged = true
                    throw e
                }
            }
            val knownEpoch = epoch
            var response = when {
                knownEpoch == null -> client.actions(after = 0)
                wait.isPositive() -> waitForActions(client, knownEpoch, wait) ?: return false
                else -> client.actions(after = lastSequence)
            }
            // After a server restart the sequence starts over: fetch the new epoch from the start.
            if (epoch != null && response.epoch != epoch) response = client.actions(after = 0)
            response.actions.forEach { monitor().applyRemote(it.record) }
            epoch = response.epoch
            lastSequence = response.lastSequence
            _state.value = State.Connected(clock())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val since = (_state.value as? State.Failing)?.sinceMillis ?: clock()
            _state.value = State.Failing(e.message ?: e::class.simpleName.orEmpty(), since)
        }
        return true
    }

    /** Long polling request for actions, `null` if a local change interrupted it. */
    private suspend fun waitForActions(client: SyncClient, knownEpoch: String, wait: Duration): ActionsResponse? = coroutineScope {
        val poll = async { client.actions(after = lastSequence, epoch = knownEpoch, wait = wait) }
        select {
            poll.onAwait { it }
            wakeUp.onReceive {
                poll.cancel()
                null
            }
        }
    }

    private companion object {
        const val MAX_PENDING = 100
    }
}
