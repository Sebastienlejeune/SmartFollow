package com.zamateka.smartfollow.history

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.core.database.sqlite.transaction
import com.zamateka.smartfollow.history.HistoryDatabase.Companion.READINGS
import com.zamateka.smartfollow.history.HistoryDatabase.Companion.READING_MG_DL
import com.zamateka.smartfollow.history.HistoryDatabase.Companion.READING_TIMESTAMP
import com.zamateka.smartfollow.history.HistoryDatabase.Companion.READING_TREND
import com.zamateka.smartfollow.model.GlucoseReading
import com.zamateka.smartfollow.model.Trend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Glucose readings kept on the phone for [RETENTION_YEARS] years, for the statistics: Dexcom
 * Share only returns the last 24 hours, so the longer history starts when the app is installed.
 */
class ReadingStore(private val database: HistoryDatabase) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    /** Non-blocking: called with the monitor's lock held. */
    fun record(readings: List<GlucoseReading>) {
        if (readings.isEmpty()) return
        scope.launch {
            runCatching {
                database.writableDatabase.transaction {
                    readings.forEach {
                        val values = ContentValues().apply {
                            put(READING_TIMESTAMP, it.timestampMillis)
                            put(READING_MG_DL, it.mgDl)
                            put(READING_TREND, it.trend.name)
                        }
                        insertWithOnConflict(READINGS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
                    }
                    delete(READINGS, "$READING_TIMESTAMP < ?", arrayOf((System.currentTimeMillis() - RETENTION_MILLIS).toString()))
                }
            }.onFailure { Log.e(TAG, "Cannot record readings", it) }
        }
    }

    /** Readings taken since [fromMillis], oldest first. */
    suspend fun since(fromMillis: Long): List<GlucoseReading> = withContext(Dispatchers.IO) {
        val readings = mutableListOf<GlucoseReading>()
        database.readableDatabase.query(
            READINGS,
            arrayOf(READING_TIMESTAMP, READING_MG_DL, READING_TREND),
            "$READING_TIMESTAMP >= ?",
            arrayOf(fromMillis.toString()),
            null, null,
            READING_TIMESTAMP,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val trend = runCatching { Trend.valueOf(cursor.getString(2)) }.getOrDefault(Trend.NONE)
                readings += GlucoseReading(cursor.getLong(0), cursor.getInt(1), trend)
            }
        }
        readings
    }

    /**
     * Writes every kept reading to [output] as CSV, oldest first, and returns how many. Times
     * are given in UTC (ISO 8601) and in the phone's time zone, for spreadsheets.
     */
    suspend fun exportCsv(output: OutputStream): Int {
        val readings = since(0)
        val localFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
        withContext(Dispatchers.IO) {
            output.bufferedWriter().use { writer ->
                writer.write("timestamp_utc,local_time,mg_dl,trend\n")
                readings.forEach {
                    val instant = Instant.ofEpochMilli(it.timestampMillis)
                    writer.write("$instant,${localFormat.format(instant)},${it.mgDl},${it.trend.name}\n")
                }
            }
        }
        return readings.size
    }

    companion object {
        /** About 5 MB, far more than the statistics need, but cheap to keep. */
        const val RETENTION_YEARS = 2
        private const val RETENTION_MILLIS = RETENTION_YEARS * 365 * 24 * 60 * 60_000L
        private const val TAG = "ReadingStore"
    }
}
