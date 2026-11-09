package com.zamateka.smartfollow.sync

import com.zamateka.smartfollow.alarm.AlarmPreferences
import com.zamateka.smartfollow.monitor.UserActionRecord
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class SyncException(message: String) : Exception(message)

/** Client of the SmartFollow server, used by the Android app to share actions and settings. */
class SyncClient(
    private val httpClient: HttpClient,
    baseUrl: String,
    private val token: String,
) {
    private val baseUrl = baseUrl.trimEnd('/')

    suspend fun status(): StatusResponse = decode(httpClient.get("$baseUrl${SyncApi.STATUS}") { auth() })

    /**
     * Actions after sequence [after]. With the server's [epoch] and a [wait], the server holds the
     * request until an action arrives or the wait is over (long polling).
     */
    suspend fun actions(after: Long, epoch: String? = null, wait: Duration = Duration.ZERO): ActionsResponse = decode(
        httpClient.get("$baseUrl${SyncApi.ACTIONS}") {
            auth()
            parameter("after", after)
            if (epoch != null && wait.isPositive()) {
                parameter("epoch", epoch)
                parameter("wait", wait.inWholeSeconds)
                // The answer may only come at the end of the wait.
                val limit = (wait + LONG_POLL_MARGIN).inWholeMilliseconds
                timeout {
                    requestTimeoutMillis = limit
                    socketTimeoutMillis = limit
                }
            }
        },
    )

    /** Relays an action already applied on this device. */
    suspend fun post(record: UserActionRecord): UserActionRecord = decode(
        httpClient.post("$baseUrl${SyncApi.ACTIONS}") {
            auth()
            json(ActionRequest(record.action, record.id, record.timestampMillis, record.source))
        },
    )

    suspend fun putSettings(preferences: AlarmPreferences) {
        check(
            httpClient.put("$baseUrl${SyncApi.SETTINGS}") {
                auth()
                json(preferences)
            },
        )
    }

    private fun HttpRequestBuilder.auth() = bearerAuth(token)

    private inline fun <reified T> HttpRequestBuilder.json(body: T) {
        contentType(ContentType.Application.Json)
        setBody(SyncApi.json.encodeToString(body))
    }

    private suspend fun check(response: HttpResponse): String {
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) throw SyncException("HTTP ${response.status.value}: ${text.take(200)}")
        return text
    }

    private suspend inline fun <reified T> decode(response: HttpResponse): T = SyncApi.json.decodeFromString(check(response))

    private companion object {
        val LONG_POLL_MARGIN = 30.seconds
    }
}
