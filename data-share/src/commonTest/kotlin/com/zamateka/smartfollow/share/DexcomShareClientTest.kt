package com.zamateka.smartfollow.share

import com.zamateka.smartfollow.model.GlucoseReading
import com.zamateka.smartfollow.model.Trend
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val ACCOUNT_ID = "11111111-2222-3333-4444-555555555555"
private const val SESSION_1 = "aaaaaaaa-0000-0000-0000-000000000001"
private const val SESSION_2 = "aaaaaaaa-0000-0000-0000-000000000002"

private const val GLUCOSE_JSON = """[
  {"WT":"Date(1700000600000)","ST":"Date(1700000600000)","DT":"Date(1700000600000+0100)","Value":95,"Trend":"FortyFiveDown"},
  {"WT":"Date(1700000300000)","ST":"Date(1700000300000)","DT":"Date(1700000300000+0100)","Value":101,"Trend":"Flat"}
]"""

class DexcomShareClientTest {

    private val requests = mutableListOf<HttpRequestData>()

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        DexcomShareClient(
            httpClient = HttpClient(MockEngine { request ->
                requests += request
                handler(request)
            }),
            region = ShareRegion.OUTSIDE_US,
            username = "wearer@example.com",
            password = "secret",
        )

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private val HttpRequestData.endpoint get() = url.encodedPath.substringAfterLast("/")

    @Test
    fun logsInThenReadsGlucoseOldestFirst() = runTest {
        val client = client { request ->
            when (request.endpoint) {
                "AuthenticatePublisherAccount" -> json("\"$ACCOUNT_ID\"")
                "LoginPublisherAccountById" -> json("\"$SESSION_1\"")
                else -> json(GLUCOSE_JSON)
            }
        }

        val readings = client.latestReadings(minutes = 30, maxCount = 6)

        assertEquals(
            listOf(
                GlucoseReading(1_700_000_300_000, 101, Trend.FLAT),
                GlucoseReading(1_700_000_600_000, 95, Trend.FORTY_FIVE_DOWN),
            ),
            readings,
        )
        val read = requests.last()
        assertTrue(read.url.toString().startsWith(ShareRegion.OUTSIDE_US.baseUrl))
        assertEquals(SESSION_1, read.url.parameters["sessionId"])
        assertEquals("30", read.url.parameters["minutes"])
        assertEquals("6", read.url.parameters["maxCount"])
        val login = (requests.first().body as TextContent).text
        assertTrue("\"accountName\":\"wearer@example.com\"" in login)
    }

    @Test
    fun reusesSessionAcrossCalls() = runTest {
        val client = client { request ->
            when (request.endpoint) {
                "AuthenticatePublisherAccount" -> json("\"$ACCOUNT_ID\"")
                "LoginPublisherAccountById" -> json("\"$SESSION_1\"")
                else -> json(GLUCOSE_JSON)
            }
        }

        client.latestReadings()
        client.latestReadings()

        assertEquals(
            listOf("AuthenticatePublisherAccount", "LoginPublisherAccountById", "ReadPublisherLatestGlucoseValues", "ReadPublisherLatestGlucoseValues"),
            requests.map { it.endpoint },
        )
    }

    @Test
    fun logsInAgainWhenSessionExpires() = runTest {
        var logins = 0
        val client = client { request ->
            when (request.endpoint) {
                "AuthenticatePublisherAccount" -> json("\"$ACCOUNT_ID\"")
                "LoginPublisherAccountById" -> json(if (++logins == 1) "\"$SESSION_1\"" else "\"$SESSION_2\"")
                else -> if (request.url.parameters["sessionId"] == SESSION_1) {
                    json("""{"Code":"SessionNotValid","Message":"Session expired"}""", HttpStatusCode.InternalServerError)
                } else {
                    json(GLUCOSE_JSON)
                }
            }
        }

        assertEquals(2, client.latestReadings().size)
        assertEquals(SESSION_2, requests.last().url.parameters["sessionId"])
        assertEquals(1, requests.count { it.endpoint == "AuthenticatePublisherAccount" })
    }

    @Test
    fun invalidPasswordIsReported() = runTest {
        val client = client {
            json("""{"Code":"AccountPasswordInvalid","Message":"Bad password"}""", HttpStatusCode.InternalServerError)
        }
        assertFailsWith<DexcomShareException.InvalidCredentials> { client.latestReadings() }
    }

    @Test
    fun nullSessionIdIsReportedAsInvalidCredentials() = runTest {
        val client = client { request ->
            when (request.endpoint) {
                "AuthenticatePublisherAccount" -> json("\"$ACCOUNT_ID\"")
                else -> json("\"00000000-0000-0000-0000-000000000000\"")
            }
        }
        assertFailsWith<DexcomShareException.InvalidCredentials> { client.latestReadings() }
    }

    @Test
    fun lockedAccountIsReported() = runTest {
        val client = client {
            json("""{"Code":"SSO_AuthenticateMaxAttemptsExceeded","Message":"Locked"}""", HttpStatusCode.InternalServerError)
        }
        assertFailsWith<DexcomShareException.TooManyAttempts> { client.latestReadings() }
    }

    @Test
    fun numericTrendCodesAreSupported() {
        assertEquals(Trend.SINGLE_DOWN, DexcomShareClient.parseTrend(kotlinx.serialization.json.JsonPrimitive(6)))
        assertEquals(Trend.NONE, DexcomShareClient.parseTrend(kotlinx.serialization.json.JsonPrimitive(42)))
    }
}
