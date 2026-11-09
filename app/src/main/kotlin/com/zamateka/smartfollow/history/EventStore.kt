package com.zamateka.smartfollow.history

import android.content.ContentValues
import android.util.Log
import androidx.core.database.sqlite.transaction
import com.zamateka.smartfollow.alarm.AlarmType
import com.zamateka.smartfollow.alarm.MonitorEvent
import com.zamateka.smartfollow.history.HistoryDatabase.Companion.EVENTS
import com.zamateka.smartfollow.history.HistoryDatabase.Companion.EVENT_ALARM_TYPE
import com.zamateka.smartfollow.history.HistoryDatabase.Companion.EVENT_ID
import com.zamateka.smartfollow.history.HistoryDatabase.Companion.EVENT_KIND
import com.zamateka.smartfollow.history.HistoryDatabase.Companion.EVENT_MG_DL
import com.zamateka.smartfollow.history.HistoryDatabase.Companion.EVENT_PROFILE
import com.zamateka.smartfollow.history.HistoryDatabase.Companion.EVENT_SOURCE
import com.zamateka.smartfollow.history.HistoryDatabase.Companion.EVENT_TIMESTAMP
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Monitoring history, kept on the phone for [RETENTION_DAYS] days. Writes run on a single
 * background thread; [events] exposes the history, newest first.
 */
class EventStore(private val database: HistoryDatabase) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val _events = MutableStateFlow<List<MonitorEvent>>(emptyList())
    val events: StateFlow<List<MonitorEvent>> = _events.asStateFlow()

    init {
        scope.launch { reload() }
    }

    fun record(events: List<MonitorEvent>) {
        if (events.isEmpty()) return
        scope.launch {
            runCatching {
                database.writableDatabase.transaction {
                    events.forEach { insert(EVENTS, null, it.toValues()) }
                    delete(EVENTS, "$EVENT_TIMESTAMP < ?", arrayOf((System.currentTimeMillis() - RETENTION_MILLIS).toString()))
                }
                reload()
            }.onFailure { Log.e(TAG, "Cannot record events", it) }
        }
    }

    private fun reload() {
        val loaded = mutableListOf<MonitorEvent>()
        database.readableDatabase.query(
            EVENTS,
            arrayOf(EVENT_TIMESTAMP, EVENT_KIND, EVENT_ALARM_TYPE, EVENT_MG_DL, EVENT_SOURCE, EVENT_PROFILE),
            null, null, null, null,
            "$EVENT_TIMESTAMP DESC, $EVENT_ID DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                // Rows written by a version with other kinds are skipped.
                val kind = runCatching { MonitorEvent.Kind.valueOf(cursor.getString(1)) }.getOrNull()
                if (kind != null) {
                    loaded += MonitorEvent(
                        timestampMillis = cursor.getLong(0),
                        kind = kind,
                        alarmType = cursor.getString(2)?.let { runCatching { AlarmType.valueOf(it) }.getOrNull() },
                        mgDl = if (cursor.isNull(3)) null else cursor.getInt(3),
                        source = cursor.getString(4),
                        profile = cursor.getString(5),
                    )
                }
            }
        }
        _events.value = loaded
    }

    private fun MonitorEvent.toValues() = ContentValues().apply {
        put(EVENT_TIMESTAMP, timestampMillis)
        put(EVENT_KIND, kind.name)
        put(EVENT_ALARM_TYPE, alarmType?.name)
        put(EVENT_MG_DL, mgDl)
        put(EVENT_SOURCE, source)
        put(EVENT_PROFILE, profile)
    }

    companion object {
        /** The whole history is reloaded in memory after each write: keep it to a few months. */
        const val RETENTION_DAYS = 90
        private const val RETENTION_MILLIS = RETENTION_DAYS * 24 * 60 * 60_000L
        private const val TAG = "EventStore"
    }
}
