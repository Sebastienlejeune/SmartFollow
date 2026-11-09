package com.zamateka.smartfollow.sync

import com.zamateka.smartfollow.alarm.AlarmPreferences
import com.zamateka.smartfollow.alarm.AlarmSettings
import com.zamateka.smartfollow.alarm.AlarmState
import com.zamateka.smartfollow.monitor.AlarmStateRepository
import com.zamateka.smartfollow.monitor.Monitor
import com.zamateka.smartfollow.monitor.UserAction
import com.zamateka.smartfollow.monitor.UserActionRecord
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class ActionSharingTest {

    private val minute = 60_000L
    private var now = 1_700_000_000_000L
    private var server: SyncServer? = SyncServer("https://follow.example.com/", "token")

    /** Fake SmartFollow server. */
    private var available = true
    private var epoch = "epoch-1"
    private val serverActions = mutableListOf<SequencedAction>()
    private val received = mutableListOf<ActionRequest>()
    private var settingsPuts = 0

    /** Long polling: completed when a request starts waiting, and to let it answer. */
    private val waiting = CompletableDeferred<Map<String, String>>()
    private val release = CompletableDeferred<Unit>()

    private val httpClient = HttpClient(MockEngine { request ->
        if (!available) return@MockEngine respondError(HttpStatusCode.ServiceUnavailable)
        assertEquals("Bearer token", request.headers["Authorization"])
        when {
            request.method == HttpMethod.Post && request.url.encodedPath == SyncApi.ACTIONS -> {
                val action = SyncApi.json.decodeFromString(ActionRequest.serializer(), (request.body as TextContent).text)
                received += action
                val record = UserActionRecord(action.id!!, action.action, action.timestampMillis!!, action.source!!)
                serverActions += SequencedAction(serverActions.size + 1L, record)
                respond(SyncApi.json.encodeToString(UserActionRecord.serializer(), record))
            }
            request.method == HttpMethod.Put && request.url.encodedPath == SyncApi.SETTINGS -> {
                settingsPuts++
                respond("{}")
            }
            request.url.encodedPath == SyncApi.ACTIONS -> {
                if (request.url.parameters["wait"] != null) {
                    waiting.complete(request.url.parameters.names().associateWith { request.url.parameters[it]!! })
                    release.await()
                }
                val after = request.url.parameters["after"]!!.toLong()
                val response = ActionsResponse(epoch, serverActions.size.toLong(), serverActions.filter { it.sequence > after })
                respond(SyncApi.json.encodeToString(ActionsResponse.serializer(), response))
            }
            else -> respondError(HttpStatusCode.NotFound)
        }
    }) {
        install(HttpTimeout)
    }

    private val store = object : AlarmStateRepository {
        var saved: AlarmState? = null
        override fun load() = saved
        override fun save(state: AlarmState) {
            saved = state
        }
    }

    private lateinit var sharing: ActionSharing

    private fun TestScope.setUp(): Monitor {
        lateinit var monitor: Monitor
        sharing = ActionSharing(httpClient, { server }, { AlarmPreferences() }, { monitor }, clock = { now })
        monitor = Monitor(
            initialSettings = AlarmSettings(),
            stateStore = store,
            clientProvider = { null },
            localSource = "android",
            onUserAction = sharing::share,
            clock = { now },
            scope = backgroundScope,
        )
        monitor.start()
        return monitor
    }

    private fun remote(id: String, action: UserAction, atMillis: Long = now) =
        SequencedAction(serverActions.size + 1L, UserActionRecord(id, action, atMillis, "web"))

    @Test
    fun localActionIsSentToTheServer() = runTest {
        val monitor = setUp()
        val record = monitor.logResugaring()
        sharing.sync()
        assertEquals(listOf(record.id), received.map { it.id })
        assertEquals(record.timestampMillis, received.single().timestampMillis)
        assertIs<ActionSharing.State.Connected>(sharing.state.value)
    }

    @Test
    fun ownActionComingBackIsNotReapplied() = runTest {
        val monitor = setUp()
        monitor.logResugaring()
        monitor.cancelResugaring()
        sharing.sync()
        assertNull(monitor.status.value.snoozeEndsAtMillis, "the echo of the resugaring must not restart the snooze")
    }

    @Test
    fun otherDevicesActionsAreApplied() = runTest {
        val monitor = setUp()
        serverActions += remote("web-1", UserAction.RESUGARING, now - 2 * minute)
        sharing.sync()
        assertEquals(now + 23 * minute, monitor.status.value.snoozeEndsAtMillis)
        assertTrue(received.isEmpty(), "remote actions are not sent back")
    }

    @Test
    fun settingsAreSentOnceThenOnChange() = runTest {
        setUp()
        sharing.sync()
        sharing.sync()
        assertEquals(1, settingsPuts)
        sharing.settingsChanged()
        sharing.sync()
        assertEquals(2, settingsPuts)
    }

    @Test
    fun unreachableServerKeepsActionsForLater() = runTest {
        val monitor = setUp()
        available = false
        monitor.logResugaring()
        sharing.sync()
        val failing = assertIs<ActionSharing.State.Failing>(sharing.state.value)
        now += minute
        sharing.sync()
        assertEquals(failing.sinceMillis, (sharing.state.value as ActionSharing.State.Failing).sinceMillis)
        assertNotNull(monitor.status.value.snoozeEndsAtMillis, "the device keeps working on its own")
        available = true
        sharing.sync()
        assertEquals(1, received.size)
        assertIs<ActionSharing.State.Connected>(sharing.state.value)
    }

    @Test
    fun serverRestartIsDetected() = runTest {
        val monitor = setUp()
        serverActions += remote("web-1", UserAction.RESUGARING)
        sharing.sync()
        monitor.cancelResugaring()
        sharing.sync()
        // The server restarts: new epoch, sequences start over.
        epoch = "epoch-2"
        serverActions.clear()
        serverActions += remote("web-2", UserAction.RESUGARING)
        sharing.sync()
        assertNotNull(monitor.status.value.snoozeEndsAtMillis)
    }

    @Test
    fun actionsAreAwaitedOnceTheEpochIsKnown() = runTest {
        setUp()
        sharing.sync(4.minutes)
        assertFalse(waiting.isCompleted, "the first exchange learns the epoch without waiting")
        val result = async { sharing.sync(4.minutes) }
        val parameters = waiting.await()
        assertEquals("epoch-1", parameters["epoch"])
        assertEquals("240", parameters["wait"])
        serverActions += remote("web-1", UserAction.RESUGARING)
        release.complete(Unit)
        assertTrue(result.await())
        assertIs<ActionSharing.State.Connected>(sharing.state.value)
    }

    @Test
    fun localActionInterruptsTheWait() = runTest {
        val monitor = setUp()
        sharing.sync()
        val result = async { sharing.sync(4.minutes) }
        waiting.await()
        val record = monitor.logResugaring()
        assertFalse(result.await(), "the wait is given up to send the action")
        sharing.sync()
        assertEquals(listOf(record.id), received.map { it.id })
    }

    @Test
    fun noServerMeansNoSharing() = runTest {
        server = null
        val monitor = setUp()
        monitor.logResugaring()
        sharing.sync()
        assertEquals(ActionSharing.State.Disabled, sharing.state.value)
        assertTrue(received.isEmpty())
    }
}
