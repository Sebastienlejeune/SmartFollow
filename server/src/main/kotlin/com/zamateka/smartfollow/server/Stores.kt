package com.zamateka.smartfollow.server

import com.zamateka.smartfollow.alarm.AlarmPreferences
import com.zamateka.smartfollow.alarm.AlarmState
import com.zamateka.smartfollow.alarm.MonitorEvent
import com.zamateka.smartfollow.model.GlucoseReading
import com.zamateka.smartfollow.monitor.AlarmStateRepository
import com.zamateka.smartfollow.sync.NtfyFilter
import com.zamateka.smartfollow.sync.SyncApi
import kotlinx.serialization.KSerializer
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.TreeMap
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

private val log = LoggerFactory.getLogger("Stores")

/** A value kept in a JSON file, written atomically so that a crash never leaves it half-written. */
class JsonFile<T>(private val path: Path, private val serializer: KSerializer<T>) {
    fun read(): T? = runCatching {
        if (path.exists()) SyncApi.json.decodeFromString(serializer, path.readText()) else null
    }.onFailure { log.error("Cannot read {}", path, it) }.getOrNull()

    @Synchronized
    fun write(value: T) {
        Files.createDirectories(path.parent)
        val temporary = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(temporary, SyncApi.json.encodeToString(serializer, value))
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}

class FileAlarmStateRepository(dataDir: Path) : AlarmStateRepository {
    private val file = JsonFile(dataDir.resolve("alarm-state.json"), AlarmState.serializer())
    override fun load(): AlarmState? = file.read()
    override fun save(state: AlarmState) = file.write(state)
}

/** Alarm preferences, replicated from the Android app. */
class PreferencesStore(dataDir: Path) {
    private val file = JsonFile(dataDir.resolve("alarm-preferences.json"), AlarmPreferences.serializer())
    fun load(): AlarmPreferences = file.read()?.sanitized() ?: AlarmPreferences()
    fun save(preferences: AlarmPreferences) = file.write(preferences.sanitized())
}

/** Notifications sent to ntfy, chosen from the web page. */
class NtfyFilterStore(dataDir: Path) {
    private val file = JsonFile(dataDir.resolve("ntfy-filter.json"), NtfyFilter.serializer())
    fun load(): NtfyFilter = file.read() ?: NtfyFilter()
    fun save(filter: NtfyFilter) = file.write(filter)
}

/**
 * Monitoring history, one JSON event per line, kept for [retention]. The whole history fits in
 * memory (a few thousand events a month); the file is rewritten without old events at startup.
 */
class EventLogFile(
    dataDir: Path,
    private val retention: Duration = 90.days,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val path = dataDir.resolve("events.jsonl")
    private val events = ArrayList<MonitorEvent>()

    init {
        Files.createDirectories(dataDir)
        if (path.exists()) {
            path.readLines().filter { it.isNotBlank() }.mapNotNullTo(events) {
                runCatching { SyncApi.json.decodeFromString(MonitorEvent.serializer(), it) }.getOrNull()
            }
        }
        compact()
    }

    @Synchronized
    fun append(newEvents: List<MonitorEvent>) {
        if (newEvents.isEmpty()) return
        events += newEvents
        runCatching {
            Files.writeString(
                path,
                newEvents.joinToString("") { SyncApi.json.encodeToString(MonitorEvent.serializer(), it) + "\n" },
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        }.onFailure { log.error("Cannot append to {}", path, it) }
    }

    /** Newest first. */
    @Synchronized
    fun recent(limit: Int): List<MonitorEvent> = events.asReversed().take(limit)

    /** Events from [fromMillis] to [toMillis], oldest first. */
    @Synchronized
    fun between(fromMillis: Long, toMillis: Long): List<MonitorEvent> = events.filter { it.timestampMillis in fromMillis..toMillis }

    @Synchronized
    private fun compact() {
        val oldest = clock() - retention.inWholeMilliseconds
        events.removeAll { it.timestampMillis < oldest }
        val temporary = path.resolveSibling("events.jsonl.tmp")
        Files.writeString(temporary, events.joinToString("") { SyncApi.json.encodeToString(MonitorEvent.serializer(), it) + "\n" })
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}

/**
 * Glucose readings kept for [retention], one JSON reading per line, for the statistics and the
 * CSV export of the web page: Dexcom Share only returns the last 24 hours. Readings already
 * kept are ignored, so that the repeated full downloads do not grow the file. The file is
 * rewritten without old readings at startup.
 */
class ReadingLogFile(
    dataDir: Path,
    private val retention: Duration = 90.days,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val path = dataDir.resolve("readings.jsonl")
    private val readings = TreeMap<Long, GlucoseReading>()

    init {
        Files.createDirectories(dataDir)
        if (path.exists()) {
            path.readLines().filter { it.isNotBlank() }.forEach { line ->
                runCatching { SyncApi.json.decodeFromString(GlucoseReading.serializer(), line) }
                    .getOrNull()
                    ?.let { readings[it.timestampMillis] = it }
            }
        }
        compact()
    }

    @Synchronized
    fun append(newReadings: List<GlucoseReading>) {
        val added = newReadings.filter { readings.putIfAbsent(it.timestampMillis, it) == null }
        if (added.isEmpty()) return
        runCatching {
            Files.writeString(
                path,
                added.joinToString("") { SyncApi.json.encodeToString(GlucoseReading.serializer(), it) + "\n" },
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        }.onFailure { log.error("Cannot append to {}", path, it) }
    }

    /** Readings taken since [fromMillis], oldest first. */
    @Synchronized
    fun since(fromMillis: Long): List<GlucoseReading> = readings.tailMap(fromMillis, true).values.toList()

    @Synchronized
    private fun compact() {
        readings.headMap(clock() - retention.inWholeMilliseconds, false).clear()
        val temporary = path.resolveSibling("readings.jsonl.tmp")
        Files.writeString(temporary, readings.values.joinToString("") { SyncApi.json.encodeToString(GlucoseReading.serializer(), it) + "\n" })
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
