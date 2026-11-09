package com.zamateka.smartfollow.probe

import com.zamateka.smartfollow.alarm.AlarmEngine
import com.zamateka.smartfollow.share.DexcomShareClient
import com.zamateka.smartfollow.share.ShareRegion
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

/**
 * Fetches the last 3 hours of readings from Dexcom Share with a real account and runs
 * the alarm engine on them. Credentials are read from the environment, never from arguments:
 *
 *   DEXCOM_USERNAME, DEXCOM_PASSWORD   Dexcom account of the sensor wearer
 *   DEXCOM_REGION                      US, OUTSIDE_US (default) or JAPAN
 */
fun main() {
    val username = System.getenv("DEXCOM_USERNAME")
    val password = System.getenv("DEXCOM_PASSWORD")
    if (username.isNullOrBlank() || password.isNullOrBlank()) {
        System.err.println("Set DEXCOM_USERNAME and DEXCOM_PASSWORD (Dexcom account of the sensor wearer).")
        exitProcess(2)
    }
    val region = ShareRegion.valueOf(System.getenv("DEXCOM_REGION") ?: ShareRegion.OUTSIDE_US.name)
    val timeFormat = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

    HttpClient(Java).use { httpClient ->
        val readings = runBlocking {
            DexcomShareClient(httpClient, region, username, password).latestReadings(minutes = 180, maxCount = 36)
        }
        println("${readings.size} readings over the last 3 hours ($region):")
        readings.forEach {
            println("  ${timeFormat.format(Instant.ofEpochMilli(it.timestampMillis))}  ${it.mgDl.toString().padStart(3)} mg/dL  ${it.trend}")
        }

        val engine = AlarmEngine()
        val now = System.currentTimeMillis()
        val evaluation = engine.evaluate(engine.initialState(now), readings, now)
        val latestAge = readings.lastOrNull()?.let { (now - it.timestampMillis) / 60_000 }
        println("Latest reading age: ${latestAge?.let { "$it min" } ?: "n/a"}")
        println("Rate: ${evaluation.rateMgDlPerMin?.let { "%.2f mg/dL/min".format(it) } ?: "n/a"}")
        println("Conditions: ${evaluation.conditions.ifEmpty { "none" }}")
        println("Alarm: ${evaluation.alarm ?: "none"}")
    }
}
