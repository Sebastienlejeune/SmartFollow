package com.zamateka.smartfollow.share

import com.zamateka.smartfollow.model.GlucoseReading
import com.zamateka.smartfollow.model.Trend
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Client for the unofficial Dexcom Share API, following the protocol documented by pydexcom.
 *
 * [username] and [password] are those of the Dexcom account of the sensor wearer (the
 * publisher), not of a Dexcom Follow account. Sharing must be enabled in the Dexcom app,
 * with at least one follower.
 */
class DexcomShareClient(
    private val httpClient: HttpClient,
    private val region: ShareRegion,
    private val username: String,
    private val password: String,
) {
    private val sessionMutex = Mutex()
    private var accountId: String? = null
    private var sessionId: String? = null

    /** Latest readings over the last [minutes], oldest first. The Share server caps both arguments. */
    suspend fun latestReadings(minutes: Int = MAX_MINUTES, maxCount: Int = MAX_COUNT): List<GlucoseReading> {
        require(minutes in 1..MAX_MINUTES) { "minutes must be in 1..$MAX_MINUTES" }
        require(maxCount in 1..MAX_COUNT) { "maxCount must be in 1..$MAX_COUNT" }
        val session = session(expired = null)
        return try {
            readGlucose(session, minutes, maxCount)
        } catch (e: DexcomShareException.SessionExpired) {
            readGlucose(session(expired = session), minutes, maxCount)
        }
    }

    /** Returns the current session, logging in when there is none or when it is [expired]. */
    private suspend fun session(expired: String?): String = sessionMutex.withLock {
        sessionId?.takeIf { it != expired }?.let { return it }
        val account = accountId ?: post(
            "General/AuthenticatePublisherAccount",
            body = buildJsonObject {
                put("accountName", username)
                put("password", password)
                put("applicationId", region.applicationId)
            },
        ).asIdentifier().also { accountId = it }
        post(
            "General/LoginPublisherAccountById",
            body = buildJsonObject {
                put("accountId", account)
                put("password", password)
                put("applicationId", region.applicationId)
            },
        ).asIdentifier().also { sessionId = it }
    }

    private suspend fun readGlucose(session: String, minutes: Int, maxCount: Int): List<GlucoseReading> =
        post(
            "Publisher/ReadPublisherLatestGlucoseValues",
            body = JsonObject(emptyMap()),
            parameters = mapOf("sessionId" to session, "minutes" to minutes, "maxCount" to maxCount),
        ).jsonArray.map { parseReading(it.jsonObject) }.sortedBy { it.timestampMillis }

    private suspend fun post(
        endpoint: String,
        body: JsonElement,
        parameters: Map<String, Any> = emptyMap(),
    ): JsonElement {
        val response: HttpResponse = httpClient.post("${region.baseUrl}/$endpoint") {
            parameters.forEach { (name, value) -> parameter(name, value) }
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(body.toString())
        }
        val text = response.bodyAsText()
        val json = runCatching { Json.parseToJsonElement(text) }.getOrNull()
        if (!response.status.isSuccess() || json == null) throw toException(json, "HTTP ${response.status.value}: $text")
        return json
    }

    private fun JsonElement.asIdentifier(): String {
        val id = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw DexcomShareException.ServerError(null, "Unexpected login response: $this")
        // Share answers with the null UUID instead of an error for some invalid credentials.
        if (id == NULL_UUID) throw DexcomShareException.InvalidCredentials("Invalid username or password")
        return id
    }

    companion object {
        const val MAX_MINUTES = 1440
        const val MAX_COUNT = 288
        private const val NULL_UUID = "00000000-0000-0000-0000-000000000000"
        private val DATE_PATTERN = Regex("""Date\((\d+)""")

        internal fun parseReading(entry: JsonObject): GlucoseReading {
            val wallTime = entry.getValue("WT").jsonPrimitive.content
            val timestamp = DATE_PATTERN.find(wallTime)?.groupValues?.get(1)?.toLong()
                ?: throw DexcomShareException.ServerError(null, "Unexpected timestamp: $wallTime")
            return GlucoseReading(
                timestampMillis = timestamp,
                mgDl = entry.getValue("Value").jsonPrimitive.int,
                trend = parseTrend(entry["Trend"]),
            )
        }

        /** Share reports the trend either by name ("Flat") or, on older servers, by numeric code. */
        internal fun parseTrend(element: JsonElement?): Trend {
            val primitive = element as? JsonPrimitive ?: return Trend.NONE
            primitive.intOrNull?.let { return Trend.entries.getOrElse(it) { Trend.NONE } }
            return TREND_NAMES[primitive.content] ?: Trend.NONE
        }

        private val TREND_NAMES = mapOf(
            "None" to Trend.NONE,
            "DoubleUp" to Trend.DOUBLE_UP,
            "SingleUp" to Trend.SINGLE_UP,
            "FortyFiveUp" to Trend.FORTY_FIVE_UP,
            "Flat" to Trend.FLAT,
            "FortyFiveDown" to Trend.FORTY_FIVE_DOWN,
            "SingleDown" to Trend.SINGLE_DOWN,
            "DoubleDown" to Trend.DOUBLE_DOWN,
            "NotComputable" to Trend.NOT_COMPUTABLE,
            "RateOutOfRange" to Trend.RATE_OUT_OF_RANGE,
        )

        private fun toException(json: JsonElement?, fallback: String): DexcomShareException {
            val error = json as? JsonObject
            val code = (error?.get("Code") as? JsonPrimitive)?.content
            val message = (error?.get("Message") as? JsonPrimitive)?.content ?: fallback
            return when {
                code == null -> DexcomShareException.ServerError(null, message)
                "MaxAttempts" in code -> DexcomShareException.TooManyAttempts(message)
                "PasswordInvalid" in code || "AccountNotFound" in code -> DexcomShareException.InvalidCredentials(message)
                code == "SessionNotValid" || code == "SessionIdNotFound" -> DexcomShareException.SessionExpired(message)
                else -> DexcomShareException.ServerError(code, message)
            }
        }
    }
}
