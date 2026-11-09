package com.zamateka.smartfollow

import com.zamateka.smartfollow.settings.ShareAccount
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Debug builds only: the Dexcom accounts named "demo" (normal day) or "demo-low" (a low going
 * on), with any password, get synthetic readings from an in-memory Share server, so that
 * screenshots never show real health data. Release builds have no demo account.
 */
object DemoShare {
    private const val READING_INTERVAL_MILLIS = 5 * 60_000L
    private val startMillis = System.currentTimeMillis()

    fun httpClientFor(account: ShareAccount): HttpClient? {
        val low = when (account.username.trim().lowercase()) {
            "demo" -> false
            "demo-low" -> true
            else -> return null
        }
        return HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    val path = request.url.encodedPath
                    val body = when {
                        path.endsWith("AuthenticatePublisherAccount") -> "\"11111111-1111-1111-1111-111111111111\""
                        path.endsWith("LoginPublisherAccountById") -> "\"22222222-2222-2222-2222-222222222222\""
                        else -> readings(
                            minutes = request.url.parameters["minutes"]?.toIntOrNull() ?: 1440,
                            maxCount = request.url.parameters["maxCount"]?.toIntOrNull() ?: 288,
                            low = low,
                        )
                    }
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
    }

    /** Share's JSON for the readings of the last [minutes], newest first. */
    private fun readings(minutes: Int, maxCount: Int, low: Boolean): String {
        val now = System.currentTimeMillis()
        val latest = now - now % READING_INTERVAL_MILLIS
        val times = generateSequence(latest) { it - READING_INTERVAL_MILLIS }
            .takeWhile { it > now - minutes * 60_000L }
            .take(maxCount)
        return times.joinToString(",", "[", "]") { time ->
            val value = glucose(time, low)
            val rate = (value - glucose(time - 15 * 60_000L, low)) / 15.0
            """{"WT":"Date($time)","ST":"Date($time)","DT":"Date($time+0000)","Value":$value,"Trend":"${trend(rate)}"}"""
        }
    }

    /** A plausible day: slow waves around 140 mg/dL; with [low], a drop to about 62 that began 25 minutes before the app started. */
    private fun glucose(time: Long, low: Boolean): Int {
        val hours = time / 3_600_000.0
        val day = 140 + 45 * sin(2 * PI * hours / 5.0) + 15 * sin(2 * PI * hours / 1.6)
        if (!low) return day.roundToInt().coerceIn(40, 400)
        val dropStart = startMillis - 25 * 60_000L
        val progress = ((time - dropStart) / (40 * 60_000.0)).coerceIn(0.0, 1.0)
        return (day + (62 - day) * progress).roundToInt().coerceIn(40, 400)
    }

    private fun trend(rate: Double): String = when {
        rate <= -3 -> "DoubleDown"
        rate <= -2 -> "SingleDown"
        rate <= -1 -> "FortyFiveDown"
        rate < 1 -> "Flat"
        rate < 2 -> "FortyFiveUp"
        rate < 3 -> "SingleUp"
        else -> "DoubleUp"
    }
}
