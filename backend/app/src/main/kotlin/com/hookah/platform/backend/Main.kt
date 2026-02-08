package com.hookah.platform.backend

import com.typesafe.config.ConfigFactory
import io.ktor.server.config.HoconApplicationConfig
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.EngineConnectorConfig
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main() {
    val hoconConfig = HoconApplicationConfig(ConfigFactory.load())
    val port =
        hoconConfig.propertyOrNull("ktor.deployment.port")?.getString()?.toIntOrNull()
            ?: System.getenv("APP_HTTP_PORT")?.toIntOrNull()
            ?: 8080
    val host = hoconConfig.propertyOrNull("ktor.deployment.host")?.getString() ?: "0.0.0.0"

    val environment =
        applicationEnvironment {
            config = hoconConfig
        }

    embeddedServer(
        factory = Netty,
        environment = environment,
        configure = {
            connectors =
                mutableListOf<EngineConnectorConfig>(
                    EngineConnectorBuilder().apply {
                        this.host = host
                        this.port = port
                    },
                )
        },
    ).start(wait = true)
}
