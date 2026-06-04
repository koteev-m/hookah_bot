package com.hookah.platform.backend

import io.ktor.client.request.headers
import io.ktor.client.request.options
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CorsPreflightMiniAppRoutesTest {
    @Test
    fun `mini app mutation preflight allows used methods from dev tunnel`() =
        testApplication {
            val devTunnelOrigin = "https://miniapp-dev-tunnel.example.test"

            environment {
                config =
                    MapApplicationConfig(
                        "app.env" to "test",
                        "api.session.jwtSecret" to "test-secret",
                        "db.jdbcUrl" to "",
                        "cors.allowedHosts" to devTunnelOrigin,
                    )
            }
            application { module() }

            val methods =
                listOf(
                    HttpMethod.Patch,
                    HttpMethod.Put,
                    HttpMethod.Delete,
                    HttpMethod.Post,
                    HttpMethod.Get,
                )

            methods.forEach { method ->
                val response =
                    client.options("/api/guest/staff-call") {
                        headers {
                            append(HttpHeaders.Origin, devTunnelOrigin)
                            append("Access-Control-Request-Method", method.value)
                            append("Access-Control-Request-Headers", "content-type,authorization")
                        }
                    }

                assertEquals(HttpStatusCode.OK, response.status, "preflight failed for ${method.value}")
                assertEquals(
                    devTunnelOrigin,
                    response.headers[HttpHeaders.AccessControlAllowOrigin],
                )
                if (method in setOf(HttpMethod.Patch, HttpMethod.Put, HttpMethod.Delete)) {
                    assertTrue(
                        response.headers[HttpHeaders.AccessControlAllowMethods].orEmpty().contains(method.value),
                        "allowed methods should include ${method.value}",
                    )
                }
            }
        }
}
