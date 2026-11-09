package com.zamateka.smartfollow.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class SharePollingPolicyTest {

    private val minute = 60_000L

    @Test
    fun pollsJustAfterNextExpectedReading() {
        assertEquals(3.minutes + 20.seconds, SharePollingPolicy.afterSuccess(latestReadingMillis = 0, nowMillis = 2 * minute))
    }

    @Test
    fun retriesSoonWhenReadingIsLate() {
        assertEquals(1.minutes, SharePollingPolicy.afterSuccess(latestReadingMillis = 0, nowMillis = 6 * minute))
        assertEquals(1.minutes, SharePollingPolicy.afterSuccess(latestReadingMillis = null, nowMillis = 0))
    }

    @Test
    fun neverWaitsMoreThanOneCycle() {
        // A reading timestamped in the future (clock skew) must not delay polling.
        assertEquals(5.minutes + 20.seconds, SharePollingPolicy.afterSuccess(latestReadingMillis = 10 * minute, nowMillis = 0))
    }

    @Test
    fun backsOffOnErrors() {
        val error = IllegalStateException("network")
        assertEquals(30.seconds, SharePollingPolicy.afterError(error, 1))
        assertEquals(1.minutes, SharePollingPolicy.afterError(error, 2))
        assertEquals(2.minutes, SharePollingPolicy.afterError(error, 3))
        assertEquals(2.minutes, SharePollingPolicy.afterError(error, 10))
    }

    @Test
    fun waitsLongAfterCredentialsErrors() {
        assertEquals(15.minutes, SharePollingPolicy.afterError(DexcomShareException.InvalidCredentials("bad"), 1))
        assertEquals(15.minutes, SharePollingPolicy.afterError(DexcomShareException.TooManyAttempts("locked"), 1))
    }
}
