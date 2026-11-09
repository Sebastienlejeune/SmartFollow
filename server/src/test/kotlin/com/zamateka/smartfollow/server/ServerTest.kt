package com.zamateka.smartfollow.server

import com.zamateka.smartfollow.alarm.AlarmPreferences
import com.zamateka.smartfollow.alarm.AlarmType
import com.zamateka.smartfollow.alarm.MonitorEvent
import com.zamateka.smartfollow.model.GlucoseReading
import com.zamateka.smartfollow.model.Trend
import com.zamateka.smartfollow.monitor.NightSummary
import com.zamateka.smartfollow.monitor.NightWindow
import com.zamateka.smartfollow.monitor.UserAction
import com.zamateka.smartfollow.monitor.UserActionRecord
import com.zamateka.smartfollow.share.ShareRegion
import com.zamateka.smartfollow.sync.ActionRequest
import com.zamateka.smartfollow.sync.ActionsResponse
import com.zamateka.smartfollow.sync.NtfyFilter
import com.zamateka.smartfollow.sync.StatisticsResponse
import com.zamateka.smartfollow.sync.StatusResponse
import com.zamateka.smartfollow.sync.SyncApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.time.ZoneId
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ServerTest {

    private val apiToken = "a".repeat(32)
    private val actionToken = "b".repeat(32)
    private val minute = 60_000L
    private val now = System.currentTimeMillis()

    private val config = ServerConfig(
        dexcomUsername = "wearer@example.com",
        dexcomPassword = "secret",
        dexcomRegion = ShareRegion.OUTSIDE_US,
        apiToken = apiToken,
        actionToken = actionToken,
        publicUrl = "https://follow.example.com",
        ntfyBaseUrl = "https://ntfy.example.com",
        ntfyTopic = "smartfollow-test",
        ntfyAccessToken = null,
        ntfyRepeat = 10.minutes,
        ntfyUrgentLowRepeat = 3.minutes,
        language = Language.FR,
        zone = ZoneId.of("Europe/Paris"),
        dataDir = Files.createTempDirectory("smartfollow-test"),
        host = "127.0.0.1",
        port = 0,
    )

    /** Requests sent to ntfy; Dexcom Share requests fail, as without network. */
    private val ntfyRequests: MutableList<HttpRequestData> = Collections.synchronizedList(mutableListOf())
    private val httpClient = HttpClient(MockEngine { request ->
        if (request.url.host == "ntfy.example.com") {
            ntfyRequests += request
            respond("{}")
        } else {
            respondError(HttpStatusCode.ServiceUnavailable)
        }
    })
    private val server = SmartFollowServer(config, httpClient).also { it.start() }

    @AfterTest
    fun stop() = server.stop()

    private fun serverTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { smartFollowModule(server) }
        block()
    }

    private suspend fun ApplicationTestBuilder.postAction(request: ActionRequest, token: String = actionToken) =
        client.post(SyncApi.ACTIONS) {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(SyncApi.json.encodeToString(ActionRequest.serializer(), request))
        }

    private suspend fun ApplicationTestBuilder.status(): StatusResponse =
        SyncApi.json.decodeFromString(client.get(SyncApi.STATUS) { bearerAuth(apiToken) }.bodyAsText())

    private suspend fun ApplicationTestBuilder.actions(): ActionsResponse =
        SyncApi.json.decodeFromString(client.get(SyncApi.ACTIONS) { bearerAuth(apiToken) }.bodyAsText())

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(condition(), "condition not met in time")
    }

    @Test
    fun apiNeedsTheRightToken() = serverTest {
        assertEquals(HttpStatusCode.Unauthorized, client.get(SyncApi.STATUS).status)
        assertEquals(HttpStatusCode.Unauthorized, client.get(SyncApi.STATUS) { bearerAuth("wrong") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get(SyncApi.STATUS) { bearerAuth(actionToken) }.status, "action token is limited to actions")
        assertEquals(HttpStatusCode.OK, client.get(SyncApi.STATUS) { bearerAuth(apiToken) }.status)
    }

    @Test
    fun webPageIsPublic() = serverTest {
        val page = client.get("/")
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue("SmartFollow" in page.bodyAsText())
    }

    @Test
    fun bareActionIsPerformedSharedAndAnnounced() = serverTest {
        val response = postAction(ActionRequest(UserAction.RESUGARING, source = "ntfy"))
        assertEquals(HttpStatusCode.OK, response.status)
        assertNotNull(status().snoozeEndsAtMillis)
        val logged = actions().actions.single().record
        assertEquals(UserAction.RESUGARING, logged.action)
        assertEquals("ntfy", logged.source)
        waitFor { ntfyRequests.isNotEmpty() }
        val announcement = (ntfyRequests.first().body as TextContent).text
        assertTrue("Resucrage enregistré" in announcement, announcement)
    }

    @Test
    fun relayedActionKeepsItsTimeAndIsLoggedOnce() = serverTest {
        val request = ActionRequest(UserAction.RESUGARING, id = "phone-1", timestampMillis = now - 5 * minute, source = "android")
        postAction(request, token = apiToken)
        postAction(request, token = apiToken)
        assertEquals(1, actions().actions.size)
        val snoozeEnd = assertNotNull(status().snoozeEndsAtMillis)
        assertEquals(now + 20 * minute, snoozeEnd, "snooze runs from the time of the action on the phone")
    }

    @Test
    fun actionsAreListedAfterASequence() = serverTest {
        postAction(ActionRequest(UserAction.RESUGARING))
        postAction(ActionRequest(UserAction.RESUGARING_CANCELLED))
        val after = client.get(SyncApi.ACTIONS) {
            bearerAuth(apiToken)
            url.parameters.append("after", "1")
        }
        val response: ActionsResponse = SyncApi.json.decodeFromString(after.bodyAsText())
        assertEquals(listOf(UserAction.RESUGARING_CANCELLED), response.actions.map { it.record.action })
        assertNull(status().snoozeEndsAtMillis)
    }

    @Test
    fun longPollingAnswersAsSoonAsAnActionArrives() = serverTest {
        val epoch = actions().epoch
        coroutineScope {
            val waiting = async {
                client.get(SyncApi.ACTIONS) {
                    bearerAuth(apiToken)
                    url.parameters.append("after", "0")
                    url.parameters.append("epoch", epoch)
                    url.parameters.append("wait", "300")
                }
            }
            postAction(ActionRequest(UserAction.RESUGARING))
            val response: ActionsResponse = SyncApi.json.decodeFromString(withTimeout(10.seconds) { waiting.await() }.bodyAsText())
            assertEquals(listOf(UserAction.RESUGARING), response.actions.map { it.record.action })
        }
    }

    @Test
    fun longPollingOfAnotherEpochAnswersAtOnce() = runTest {
        val log = ActionLog()
        val response = withTimeout(10.seconds) { log.await(5, "old-epoch", 5.minutes) }
        assertEquals(log.epoch, response.epoch)
        assertEquals(0, response.lastSequence)
    }

    @Test
    fun keptReadingsGiveStatisticsAndHistory() = serverTest {
        val readings = listOf(100, 150, 200).mapIndexed { index, mgDl -> GlucoseReading(now - (3 - index) * 5 * minute, mgDl, Trend.FLAT) }
        server.readings.append(readings)
        server.readings.append(readings.take(1))
        val kept: List<GlucoseReading> = SyncApi.json.decodeFromString(
            client.get(SyncApi.READINGS) {
                bearerAuth(apiToken)
                url.parameters.append("days", "7")
            }.bodyAsText(),
        )
        assertEquals(readings, kept, "a reading already kept is not added twice")
        val response: StatisticsResponse = SyncApi.json.decodeFromString(
            client.get(SyncApi.STATISTICS) {
                bearerAuth(apiToken)
                url.parameters.append("days", "1")
            }.bodyAsText(),
        )
        val statistics = assertNotNull(response.statistics)
        assertEquals(3, statistics.readingCount)
        assertEquals(150.0, statistics.meanMgDl)
    }

    @Test
    fun readingsAreKeptAcrossRestarts() {
        val reading = GlucoseReading(now, 123, Trend.FLAT)
        server.readings.append(listOf(reading))
        assertEquals(listOf(reading), ReadingLogFile(config.dataDir).since(now - minute))
        val old = GlucoseReading(now - 100L * 24 * 60 * minute, 90, Trend.FLAT)
        server.readings.append(listOf(old))
        assertTrue(ReadingLogFile(config.dataDir).since(0).none { it == old }, "readings older than 90 days are dropped at startup")
    }

    @Test
    fun aChangeOfProfileIsLoggedOnce() {
        server.monitor.start()
        server.updatePreferences(AlarmPreferences(profileId = "home", profileName = "Maison"))
        server.updatePreferences(AlarmPreferences(profileId = "home", profileName = "Maison", lowMgDl = 75))
        server.updatePreferences(AlarmPreferences(profileId = "school", profileName = "Ecole"))
        val changes = server.events.recent(100).filter { it.kind == MonitorEvent.Kind.PROFILE_CHANGED }.map { it.profile }
        assertEquals(listOf("Ecole", "Maison"), changes, "newest first, not again for the same profile")
    }

    @Test
    fun settingsAreReplicatedAndApplied() = serverTest {
        val preferences = AlarmPreferences(snoozeMinutes = 40, lowMgDl = 75)
        val saved = client.put(SyncApi.SETTINGS) {
            bearerAuth(apiToken)
            contentType(ContentType.Application.Json)
            setBody(SyncApi.json.encodeToString(AlarmPreferences.serializer(), preferences))
        }
        assertEquals(HttpStatusCode.OK, saved.status)
        assertEquals(75, server.preferences.load().lowMgDl)
        assertEquals(40.minutes, server.monitor.settings.resugaringSnooze)
        postAction(ActionRequest(UserAction.RESUGARING))
        val snoozeLength = assertNotNull(status().snoozeEndsAtMillis) - System.currentTimeMillis()
        assertTrue(snoozeLength in (39 * minute)..(40 * minute), "snooze uses the replicated duration")
    }

    @Test
    fun actionTokenCannotChangeSettings() = serverTest {
        val response = client.put(SyncApi.SETTINGS) {
            bearerAuth(actionToken)
            contentType(ContentType.Application.Json)
            setBody(SyncApi.json.encodeToString(AlarmPreferences.serializer(), AlarmPreferences()))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun lowAlarmNotificationOffersResugaring() {
        val notifier = NtfyNotifier(httpClient, config)
        val message = notifier.alarmMessage("topic", AlarmType.LOW, GlucoseReading(now, 64, Trend.FORTY_FIVE_DOWN))
        assertEquals("Hypo", message.title)
        assertEquals("64 mg/dL ↘", message.message)
        assertEquals(4, message.priority)
        assertEquals(listOf("Resucrage effectué", "OK"), message.actions.map { it.label })
        val button = message.actions.first()
        assertEquals("https://follow.example.com/api/actions", button.url)
        assertEquals("Bearer $actionToken", button.headers["Authorization"])
        assertEquals(UserAction.RESUGARING, SyncApi.json.decodeFromString(ActionRequest.serializer(), assertNotNull(button.body)).action)
    }

    @Test
    fun highAndStaleNotificationsOnlyOfferOk() {
        val notifier = NtfyNotifier(httpClient, config)
        assertEquals(listOf("OK"), notifier.alarmMessage("topic", AlarmType.HIGH, null).actions.map { it.label })
        val stale = notifier.alarmMessage("topic", AlarmType.STALE_DATA, null)
        assertTrue("téléphone du porteur" in stale.message)
        assertEquals(5, notifier.alarmMessage("topic", AlarmType.URGENT_LOW, null).priority)
    }

    @Test
    fun lowSoonOffersResugaring() {
        val notifier = NtfyNotifier(httpClient, config)
        val lowSoon = notifier.alarmMessage("topic", AlarmType.LOW_SOON, GlucoseReading(now, 85, Trend.SINGLE_DOWN))
        assertEquals("Hypo imminente", lowSoon.title)
        assertEquals(listOf("Resucrage effectué", "OK"), lowSoon.actions.map { it.label })
    }

    @Test
    fun acknowledgementIsNotAnnounced() = serverTest {
        postAction(ActionRequest(UserAction.ACKNOWLEDGED, source = "android"))
        postAction(ActionRequest(UserAction.RESUGARING, source = "android"))
        waitFor { ntfyRequests.isNotEmpty() }
        Thread.sleep(200)
        val announcements = ntfyRequests.map { (it.body as TextContent).text }
        assertEquals(1, announcements.size, announcements.toString())
        assertTrue("Resucrage enregistré" in announcements.single())
    }

    @Test
    fun ntfyFilterIsSavedAndMutesActions() = serverTest {
        val filter = NtfyFilter(mutedAlarms = setOf(AlarmType.LOW_SOON, AlarmType.HIGH), actions = false)
        val saved = client.put(SyncApi.NTFY_FILTER) {
            bearerAuth(apiToken)
            contentType(ContentType.Application.Json)
            setBody(SyncApi.json.encodeToString(NtfyFilter.serializer(), filter))
        }
        assertEquals(HttpStatusCode.OK, saved.status)
        val read = client.get(SyncApi.NTFY_FILTER) { bearerAuth(apiToken) }.bodyAsText()
        assertEquals(filter, SyncApi.json.decodeFromString(NtfyFilter.serializer(), read))
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.put(SyncApi.NTFY_FILTER) { bearerAuth(actionToken) }.status,
            "the action token cannot change the filter",
        )
        postAction(ActionRequest(UserAction.RESUGARING, source = "android"))
        Thread.sleep(300)
        assertTrue(ntfyRequests.isEmpty(), "resugarings are muted")
    }

    @Test
    fun nightSummaryIsQuietAndReadable() {
        val notifier = NtfyNotifier(httpClient, config)
        val night = NightWindow(1_700_000_000_000, 1_700_000_000_000 + 9 * 60 * minute)
        val readings = listOf(65, 60, 120, 200).mapIndexed { index, mgDl -> GlucoseReading(night.fromMillis + index * 5 * minute, mgDl, Trend.FLAT) }
        val summary = NightSummary.compute(night, readings, emptyList(), AlarmPreferences().toAlarmSettings())
        val message = notifier.nightSummaryMessage("topic", summary)
        assertEquals(2, message.priority)
        assertEquals("Résumé de la nuit (23:13–08:13)", message.title)
        assertEquals(
            "Dans la cible : 25 % · sous 70 : 10 min · au-dessus de 180 : 5 min\n" +
                "Min 60 · moyenne 111 · max 200 mg/dL\n" +
                "0 alarme · 0 resucrage",
            message.message,
        )
    }

    @Test
    fun actionAnnouncementIsQuiet() {
        val notifier = NtfyNotifier(httpClient, config)
        val record = UserActionRecord("id", UserAction.RESUGARING, 1_700_000_000_000, "android")
        val message = notifier.actionMessage("topic", record)
        assertEquals(2, message.priority)
        assertTrue("l'app Android" in message.message, message.message)
    }

    @Test
    fun configNeedsDistinctLongTokens() {
        val env = mapOf(
            "DEXCOM_USERNAME" to "u",
            "DEXCOM_PASSWORD" to "p",
            "SMARTFOLLOW_PUBLIC_URL" to "https://x/",
            "SMARTFOLLOW_API_TOKEN" to apiToken,
            "SMARTFOLLOW_ACTION_TOKEN" to actionToken,
        )
        assertEquals("https://x", ServerConfig.fromEnvironment(env).publicUrl)
        assertTrue(runCatching { ServerConfig.fromEnvironment(env + ("SMARTFOLLOW_ACTION_TOKEN" to apiToken)) }.isFailure)
        assertTrue(runCatching { ServerConfig.fromEnvironment(env + ("SMARTFOLLOW_API_TOKEN" to "short")) }.isFailure)
    }
}
