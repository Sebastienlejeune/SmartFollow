package com.zamateka.smartfollow.share

import com.zamateka.smartfollow.model.GlucoseReading
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Keeps a rolling history of readings without downloading it all at every poll: a full
 * download of the last [RETENTION] every [FULL_REFRESH_EVERY] polls, which also picks up
 * readings backfilled by the sensor after a signal loss, and only the last minutes otherwise.
 */
object ReadingHistory {
    val RETENTION: Duration = 24.hours

    /** Extra minutes fetched before the latest known reading, to absorb upload delays. */
    val INCREMENTAL_MARGIN: Duration = 10.minutes

    const val FULL_REFRESH_EVERY = 6

    data class Request(val minutes: Int, val maxCount: Int)

    fun nextRequest(existing: List<GlucoseReading>, nowMillis: Long, pollsSinceFullRefresh: Int): Request {
        val latest = existing.maxOfOrNull { it.timestampMillis }
        val minutes = if (latest == null || pollsSinceFullRefresh >= FULL_REFRESH_EVERY) {
            RETENTION.inWholeMinutes.toInt()
        } else {
            ((nowMillis - latest) / 60_000 + INCREMENTAL_MARGIN.inWholeMinutes).toInt()
        }.coerceIn(INCREMENTAL_MARGIN.inWholeMinutes.toInt(), DexcomShareClient.MAX_MINUTES)
        // One reading every 5 minutes, plus a little slack.
        val maxCount = (minutes / 5 + 2).coerceAtMost(DexcomShareClient.MAX_COUNT)
        return Request(minutes, maxCount)
    }

    fun isFullRefresh(request: Request): Boolean = request.minutes >= RETENTION.inWholeMinutes

    /** Merges [fetched] into [existing], fetched values winning, and drops readings older than [RETENTION]. Oldest first. */
    fun merge(existing: List<GlucoseReading>, fetched: List<GlucoseReading>, nowMillis: Long): List<GlucoseReading> {
        val oldest = nowMillis - RETENTION.inWholeMilliseconds
        return (existing + fetched)
            .associateBy { it.timestampMillis }
            .values
            .filter { it.timestampMillis >= oldest }
            .sortedBy { it.timestampMillis }
    }
}
