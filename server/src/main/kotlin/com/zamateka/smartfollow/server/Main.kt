package com.zamateka.smartfollow.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import io.ktor.server.cio.CIO
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import org.slf4j.LoggerFactory

fun main() {
    val config = ServerConfig.fromEnvironment(System.getenv())
    val httpClient = HttpClient(Java) {
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 30_000
        }
    }
    val server = SmartFollowServer(config, httpClient)
    server.start()
    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
    LoggerFactory.getLogger("Main").info(
        "SmartFollow server listening on {}:{}, notifications {}",
        config.host,
        config.port,
        if (config.ntfyTopic != null) "on" else "off",
    )
    embeddedServer(
        CIO,
        configure = {
            connector {
                host = config.host
                port = config.port
            }
            // Long polling requests for actions stay silent for up to 5 minutes.
            connectionIdleTimeoutSeconds = 6 * 60
        },
    ) { smartFollowModule(server) }.start(wait = true)
}
