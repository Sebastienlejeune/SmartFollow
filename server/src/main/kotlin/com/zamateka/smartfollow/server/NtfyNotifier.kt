package com.zamateka.smartfollow.server

import com.zamateka.smartfollow.alarm.AlarmType
import com.zamateka.smartfollow.alarm.offersResugaring
import com.zamateka.smartfollow.model.GlucoseReading
import com.zamateka.smartfollow.model.arrow
import com.zamateka.smartfollow.monitor.Monitor
import com.zamateka.smartfollow.monitor.NightSummary
import com.zamateka.smartfollow.monitor.UserAction
import com.zamateka.smartfollow.monitor.UserActionRecord
import com.zamateka.smartfollow.sync.ActionRequest
import com.zamateka.smartfollow.sync.NtfyFilter
import com.zamateka.smartfollow.sync.SyncApi
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.format.DateTimeFormatter

/** A message in ntfy's JSON publishing format (https://docs.ntfy.sh/publish/#publish-as-json). */
@Serializable
data class NtfyMessage(
    val topic: String,
    val title: String,
    val message: String,
    /** 1 (min) to 5 (max). */
    val priority: Int,
    val tags: List<String> = emptyList(),
    val actions: List<NtfyAction> = emptyList(),
)

@Serializable
data class NtfyAction(
    val action: String = "http",
    val label: String,
    val url: String,
    val method: String = "POST",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    /** Dismisses the notification once the button is tapped. */
    val clear: Boolean = true,
)

/**
 * Sends the alarms to the ntfy topic followed by the iPhone, as push notifications with
 * "Resugaring done" and "OK" buttons that call the server back with the action token. Only the
 * notifications let through by [filter] are sent, read at each sending.
 */
class NtfyNotifier(
    private val httpClient: HttpClient,
    private val config: ServerConfig,
    private val messages: Messages = Messages(config.language),
    private val filter: () -> NtfyFilter = { NtfyFilter() },
) {
    private val log = LoggerFactory.getLogger(NtfyNotifier::class.java)
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm").withZone(config.zone)

    fun alarmMessage(topic: String, type: AlarmType, reading: GlucoseReading?): NtfyMessage {
        val glucose = reading?.let { "${it.mgDl} mg/dL ${it.trend.arrow()}".trim() } ?: messages.noRecentData()
        val body = if (type == AlarmType.STALE_DATA) "$glucose\n${messages.checkWearerPhone()}" else glucose
        return NtfyMessage(
            topic = topic,
            title = messages.alarmTitle(type),
            message = body,
            priority = priority(type),
            tags = listOf(tag(type)),
            actions = buildList {
                if (type.offersResugaring()) add(actionButton(messages.resugaringButton(), UserAction.RESUGARING))
                add(actionButton(messages.acknowledgeButton(), UserAction.ACKNOWLEDGED))
            },
        )
    }

    /** Quiet message telling the other parent that an action was made. */
    fun actionMessage(topic: String, record: UserActionRecord): NtfyMessage = NtfyMessage(
        topic = topic,
        title = messages.actionTitle(record.action),
        message = messages.actionBody(timeFormat.format(Instant.ofEpochMilli(record.timestampMillis)), record.source),
        priority = 2,
        tags = listOf("white_check_mark"),
    )

    /** Quiet message, read when waking up. */
    fun nightSummaryMessage(topic: String, summary: NightSummary): NtfyMessage = NtfyMessage(
        topic = topic,
        title = messages.nightSummaryTitle(
            timeFormat.format(Instant.ofEpochMilli(summary.night.fromMillis)),
            timeFormat.format(Instant.ofEpochMilli(summary.night.toMillis)),
        ),
        message = messages.nightSummaryBody(summary),
        priority = 2,
        tags = listOf("crescent_moon"),
    )

    suspend fun send(message: NtfyMessage) {
        try {
            val response = httpClient.post(config.ntfyBaseUrl) {
                config.ntfyAccessToken?.let { bearerAuth(it) }
                contentType(ContentType.Application.Json)
                setBody(SyncApi.json.encodeToString(NtfyMessage.serializer(), message))
            }
            if (!response.status.isSuccess()) log.warn("ntfy answered {}: {}", response.status, response.bodyAsText().take(200))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Cannot send ntfy notification", e)
        }
    }

    /**
     * Notifies each new continuous alarm, then again every [ServerConfig.ntfyRepeat] (every
     * [ServerConfig.ntfyUrgentLowRepeat] for the urgent low) until it is acknowledged, since a
     * notification only rings once. Non-continuous alarms are notified once; the alarm engine
     * raises them again after their repeat interval.
     */
    fun watch(scope: CoroutineScope, monitor: Monitor): Job? {
        val topic = config.ntfyTopic ?: return null
        return scope.launch {
            launch {
                monitor.alerts.collect { sendAlarm(topic, it, monitor) }
            }
            monitor.status.map { it.evaluation?.alarm }.distinctUntilChanged().collectLatest { alarm ->
                if (alarm == null) return@collectLatest
                // A muted alarm keeps its rhythm, so that it is notified again once let through.
                while (true) {
                    sendAlarm(topic, alarm, monitor)
                    delay(if (alarm == AlarmType.URGENT_LOW) config.ntfyUrgentLowRepeat else config.ntfyRepeat)
                }
            }
        }
    }

    private suspend fun sendAlarm(topic: String, type: AlarmType, monitor: Monitor) {
        if (type in filter().mutedAlarms) return
        send(alarmMessage(topic, type, monitor.status.value.evaluation?.freshReading))
    }

    suspend fun notifyNightSummary(summary: NightSummary) {
        if (!filter().nightSummary) return
        send(nightSummaryMessage(config.ntfyTopic ?: return, summary))
    }

    /**
     * Tells the other parent about a resugaring or its cancellation. An "OK" is not announced:
     * the alarm no longer repeating already says it.
     */
    fun notifyAction(scope: CoroutineScope, record: UserActionRecord) {
        val topic = config.ntfyTopic ?: return
        if (record.action == UserAction.ACKNOWLEDGED || !filter().actions) return
        scope.launch { send(actionMessage(topic, record)) }
    }

    private fun actionButton(label: String, action: UserAction) = NtfyAction(
        label = label,
        url = "${config.publicUrl}${SyncApi.ACTIONS}",
        headers = mapOf("Authorization" to "Bearer ${config.actionToken}", "Content-Type" to "application/json"),
        body = SyncApi.json.encodeToString(ActionRequest.serializer(), ActionRequest(action, source = "ntfy")),
    )

    private fun priority(type: AlarmType): Int = when (type) {
        AlarmType.URGENT_LOW, AlarmType.FAST_DROP, AlarmType.NO_RECOVERY, AlarmType.URGENT_HIGH -> 5
        AlarmType.LOW, AlarmType.LOW_SOON, AlarmType.STALE_DATA -> 4
        AlarmType.HIGH -> 3
    }

    private fun tag(type: AlarmType): String = when (type) {
        AlarmType.URGENT_LOW, AlarmType.FAST_DROP, AlarmType.NO_RECOVERY -> "rotating_light"
        AlarmType.LOW -> "warning"
        AlarmType.LOW_SOON -> "chart_with_downwards_trend"
        AlarmType.STALE_DATA -> "signal_strength"
        AlarmType.URGENT_HIGH -> "arrow_double_up"
        AlarmType.HIGH -> "arrow_up"
    }
}
