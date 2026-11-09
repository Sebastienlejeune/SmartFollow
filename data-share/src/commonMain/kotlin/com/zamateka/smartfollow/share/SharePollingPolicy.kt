package com.zamateka.smartfollow.share

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * When to poll Share next. The G7 uploads a reading every 5 minutes, so polling just after
 * the next expected upload keeps the delay low without hammering the server.
 */
object SharePollingPolicy {
    val READING_INTERVAL: Duration = 5.minutes

    /** Time for a new reading to travel from the wearer's phone to the Share server. */
    val UPLOAD_MARGIN: Duration = 20.seconds

    /** Retry delay when the expected reading has not arrived yet. */
    val LATE_RETRY: Duration = 1.minutes

    /** Retry delays after consecutive network or server errors; the last one repeats. */
    val ERROR_BACKOFF: List<Duration> = listOf(30.seconds, 1.minutes, 2.minutes)

    /** Retry delay after a credentials error: retrying faster could lock the Dexcom account. */
    val AUTH_ERROR_RETRY: Duration = 15.minutes

    fun afterSuccess(latestReadingMillis: Long?, nowMillis: Long): Duration {
        if (latestReadingMillis == null) return LATE_RETRY
        val cycle = READING_INTERVAL + UPLOAD_MARGIN
        val wait = (latestReadingMillis + cycle.inWholeMilliseconds - nowMillis).milliseconds
        return if (wait <= Duration.ZERO) LATE_RETRY else wait.coerceAtMost(cycle)
    }

    fun afterError(error: Throwable, consecutiveErrors: Int): Duration = when (error) {
        is DexcomShareException.InvalidCredentials, is DexcomShareException.TooManyAttempts -> AUTH_ERROR_RETRY
        else -> ERROR_BACKOFF[(consecutiveErrors - 1).coerceIn(0, ERROR_BACKOFF.lastIndex)]
    }
}
