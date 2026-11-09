package com.zamateka.smartfollow.monitor

import com.zamateka.smartfollow.alarm.AlarmSettings
import com.zamateka.smartfollow.alarm.AlarmState
import com.zamateka.smartfollow.alarm.MonitorEvent
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MonitorTest {

    private val minute = 60_000L
    private var now = 1_700_000_000_000L
    private val shared = mutableListOf<UserActionRecord>()
    private val events = mutableListOf<MonitorEvent>()

    private val memoryStore = object : AlarmStateRepository {
        var saved: AlarmState? = null
        override fun load() = saved
        override fun save(state: AlarmState) {
            saved = state
        }
    }

    private fun TestScope.monitor() = Monitor(
        initialSettings = AlarmSettings(),
        stateStore = memoryStore,
        clientProvider = { null },
        localSource = "android",
        eventLog = { events += it },
        onUserAction = { shared += it },
        clock = { now },
        scope = backgroundScope,
    ).also { it.start() }

    private fun remote(id: String, action: UserAction, atMillis: Long) = UserActionRecord(id, action, atMillis, source = "web")

    @Test
    fun localActionIsAppliedAndShared() = runTest {
        val monitor = monitor()
        val record = monitor.logResugaring()
        assertEquals(listOf(record), shared)
        assertEquals("android", record.source)
        assertEquals(now + 25 * minute, monitor.status.value.snoozeEndsAtMillis)
    }

    @Test
    fun remoteResugaringStartsAtItsOwnTime() = runTest {
        val monitor = monitor()
        assertTrue(monitor.applyRemote(remote("a", UserAction.RESUGARING, now - 5 * minute)))
        assertEquals(now + 20 * minute, monitor.status.value.snoozeEndsAtMillis)
        assertTrue(shared.isEmpty(), "remote actions are not shared again")
        assertEquals("web", events.last { it.kind == MonitorEvent.Kind.RESUGARING }.source)
    }

    @Test
    fun remoteActionIsAppliedOnce() = runTest {
        val monitor = monitor()
        assertTrue(monitor.applyRemote(remote("a", UserAction.RESUGARING, now)))
        monitor.cancelResugaring()
        assertFalse(monitor.applyRemote(remote("a", UserAction.RESUGARING, now)))
        assertNull(monitor.status.value.snoozeEndsAtMillis)
    }

    @Test
    fun ownActionComingBackFromServerIsIgnored() = runTest {
        val monitor = monitor()
        val record = monitor.logResugaring()
        assertFalse(monitor.applyRemote(record))
    }

    @Test
    fun outdatedRemoteActionsAreIgnored() = runTest {
        val monitor = monitor()
        assertFalse(monitor.applyRemote(remote("a", UserAction.RESUGARING, now - 30 * minute)))
        assertFalse(monitor.applyRemote(remote("b", UserAction.ACKNOWLEDGED, now - 15 * minute)))
        assertNull(monitor.status.value.snoozeEndsAtMillis)
    }

    @Test
    fun localEventsHaveNoSource() = runTest {
        val monitor = monitor()
        monitor.logResugaring()
        assertNull(events.last { it.kind == MonitorEvent.Kind.RESUGARING }.source)
    }
}
