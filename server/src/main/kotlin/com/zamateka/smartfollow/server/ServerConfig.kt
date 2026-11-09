package com.zamateka.smartfollow.server

import com.zamateka.smartfollow.share.ShareRegion
import java.nio.file.Path
import java.time.ZoneId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Server settings, read from environment variables (see `deploy/smartfollow.env.example`). */
data class ServerConfig(
    val dexcomUsername: String,
    val dexcomPassword: String,
    val dexcomRegion: ShareRegion,
    /** Gives full access to the API: Android app and web page. */
    val apiToken: String,
    /** Only allows posting actions; embedded in the notification buttons. */
    val actionToken: String,
    /** Public HTTPS address of the server, used by the notification buttons. */
    val publicUrl: String,
    val ntfyBaseUrl: String,
    /** `null` disables notifications. */
    val ntfyTopic: String?,
    val ntfyAccessToken: String?,
    /** How often a still active alarm is notified again... */
    val ntfyRepeat: Duration,
    /** ...except the urgent low, notified again more often. */
    val ntfyUrgentLowRepeat: Duration,
    val language: Language,
    /** Time zone of the times shown in notifications. */
    val zone: ZoneId,
    val dataDir: Path,
    val host: String,
    val port: Int,
) {
    companion object {
        private const val MIN_TOKEN_LENGTH = 24

        fun fromEnvironment(env: Map<String, String>): ServerConfig {
            fun required(name: String) = env[name]?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Missing environment variable $name")
            fun optional(name: String) = env[name]?.takeIf { it.isNotBlank() }
            fun token(name: String) = required(name).also {
                require(it.length >= MIN_TOKEN_LENGTH) { "$name must be at least $MIN_TOKEN_LENGTH characters long" }
            }

            val apiToken = token("SMARTFOLLOW_API_TOKEN")
            val actionToken = token("SMARTFOLLOW_ACTION_TOKEN")
            require(apiToken != actionToken) { "SMARTFOLLOW_API_TOKEN and SMARTFOLLOW_ACTION_TOKEN must differ" }
            return ServerConfig(
                dexcomUsername = required("DEXCOM_USERNAME"),
                dexcomPassword = required("DEXCOM_PASSWORD"),
                dexcomRegion = ShareRegion.valueOf(optional("DEXCOM_REGION") ?: ShareRegion.OUTSIDE_US.name),
                apiToken = apiToken,
                actionToken = actionToken,
                publicUrl = required("SMARTFOLLOW_PUBLIC_URL").trimEnd('/'),
                ntfyBaseUrl = (optional("NTFY_BASE_URL") ?: "https://ntfy.sh").trimEnd('/'),
                ntfyTopic = optional("NTFY_TOPIC"),
                ntfyAccessToken = optional("NTFY_ACCESS_TOKEN"),
                ntfyRepeat = (optional("NTFY_REPEAT_MINUTES")?.toInt() ?: 10).coerceAtLeast(1).minutes,
                ntfyUrgentLowRepeat = (optional("NTFY_URGENT_LOW_REPEAT_MINUTES")?.toInt() ?: 3).coerceAtLeast(1).minutes,
                language = Language.fromCode(optional("SMARTFOLLOW_LANGUAGE")),
                zone = ZoneId.of(optional("SMARTFOLLOW_TIMEZONE") ?: "Europe/Paris"),
                dataDir = Path.of(optional("SMARTFOLLOW_DATA_DIR") ?: "data"),
                host = optional("SMARTFOLLOW_HOST") ?: "127.0.0.1",
                port = optional("SMARTFOLLOW_PORT")?.toInt() ?: 8080,
            )
        }
    }
}
