package com.zamateka.smartfollow.server

import com.zamateka.smartfollow.alarm.AlarmPreferences
import com.zamateka.smartfollow.sync.ActionRequest
import com.zamateka.smartfollow.sync.NtfyFilter
import com.zamateka.smartfollow.sync.SyncApi
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import java.security.MessageDigest
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private const val FULL_ACCESS = "full"
private const val ACTION_ACCESS = "action"

/** Readings are kept 90 days by the server. */
private const val MAX_DAYS = 90

/** Longest wait of a long polling request for actions. */
private val MAX_ACTIONS_WAIT = 5.minutes

fun Application.smartFollowModule(server: SmartFollowServer) {
    val config = server.config
    install(ContentNegotiation) { json(SyncApi.json) }
    install(StatusPages) {
        exception<BadRequestException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "Bad request")))
        }
    }
    install(Authentication) {
        bearer(FULL_ACCESS) {
            authenticate { if (sameToken(it.token, config.apiToken)) UserIdPrincipal(FULL_ACCESS) else null }
        }
        bearer(ACTION_ACCESS) {
            authenticate {
                when {
                    sameToken(it.token, config.apiToken) -> UserIdPrincipal(FULL_ACCESS)
                    sameToken(it.token, config.actionToken) -> UserIdPrincipal(ACTION_ACCESS)
                    else -> null
                }
            }
        }
    }

    routing {
        // The web page: public files only, every data request needs the token.
        staticResources("/", "web")

        authenticate(FULL_ACCESS) {
            get(SyncApi.STATUS) { call.respond(server.status()) }
            get(SyncApi.READINGS) {
                val days = call.request.queryParameters["days"]?.toIntOrNull()?.coerceIn(1, MAX_DAYS)
                call.respond(if (days == null) server.monitor.status.value.readings else server.readingsOfLastDays(days))
            }
            get(SyncApi.STATISTICS) {
                val days = call.request.queryParameters["days"]?.toIntOrNull()?.coerceIn(1, MAX_DAYS) ?: 14
                call.respond(server.statistics(days))
            }
            post(SyncApi.REFRESH) {
                server.monitor.refresh()
                call.respond(mapOf("refreshing" to true))
            }
            get(SyncApi.EVENTS) {
                // The whole history (90 days, a few thousand events a month) fits in one answer.
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 50_000) ?: 200
                call.respond(server.events.recent(limit))
            }
            get(SyncApi.ACTIONS) {
                val parameters = call.request.queryParameters
                val after = parameters["after"]?.toLongOrNull() ?: 0
                val wait = (parameters["wait"]?.toLongOrNull() ?: 0).coerceIn(0, MAX_ACTIONS_WAIT.inWholeSeconds).seconds
                call.respond(server.actions.await(after, parameters["epoch"], wait))
            }
            get(SyncApi.SETTINGS) { call.respond(server.preferences.load()) }
            put(SyncApi.SETTINGS) { call.respond(server.updatePreferences(call.receive<AlarmPreferences>())) }
            get(SyncApi.NTFY_FILTER) { call.respond(server.ntfyFilter.load()) }
            put(SyncApi.NTFY_FILTER) {
                server.ntfyFilter.save(call.receive<NtfyFilter>())
                call.respond(server.ntfyFilter.load())
            }
        }
        authenticate(ACTION_ACCESS) {
            post(SyncApi.ACTIONS) { call.respond(server.handle(call.receive<ActionRequest>())) }
        }
    }
}

/** Constant-time comparison, so that response times do not leak the token. */
private fun sameToken(candidate: String, expected: String): Boolean =
    MessageDigest.isEqual(candidate.toByteArray(), expected.toByteArray())
