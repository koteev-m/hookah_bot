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
    fun `mini app mutation preflight allows actual mutation routes from dev tunnel`() =
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

            val cases =
                listOf(
                    MutationPreflightCase(
                        path = "/api/venue/menu/categories/10?venueId=1",
                        method = HttpMethod.Patch,
                        label = "venue menu category update",
                    ),
                    MutationPreflightCase(
                        path = "/api/venue/menu/options/20?venueId=1",
                        method = HttpMethod.Delete,
                        label = "venue menu option delete",
                    ),
                    MutationPreflightCase(
                        path = "/api/venue/1/staff/456",
                        method = HttpMethod.Patch,
                        label = "venue staff role update",
                    ),
                    MutationPreflightCase(
                        path = "/api/venue/1/staff/456",
                        method = HttpMethod.Delete,
                        label = "venue staff remove",
                    ),
                    MutationPreflightCase(
                        path = "/api/venue/1/schedule/weekly/1",
                        method = HttpMethod.Put,
                        label = "venue schedule update",
                    ),
                    MutationPreflightCase(
                        path = "/api/platform/venues/1/subscription",
                        method = HttpMethod.Put,
                        label = "platform subscription update",
                    ),
                )

            cases.forEach { case ->
                val response =
                    client.options(case.path) {
                        headers {
                            append(HttpHeaders.Origin, devTunnelOrigin)
                            append("Access-Control-Request-Method", case.method.value)
                            append(
                                "Access-Control-Request-Headers",
                                "${HttpHeaders.ContentType},${HttpHeaders.Authorization}",
                            )
                        }
                    }

                assertEquals(
                    HttpStatusCode.OK,
                    response.status,
                    "preflight failed for ${case.label}",
                )
                assertEquals(
                    devTunnelOrigin,
                    response.headers[HttpHeaders.AccessControlAllowOrigin],
                )
                assertHeaderContains(
                    response.headers[HttpHeaders.AccessControlAllowMethods],
                    case.method.value,
                    "${case.label} allowed methods",
                )
                assertHeaderContains(
                    response.headers[HttpHeaders.AccessControlAllowHeaders],
                    HttpHeaders.ContentType,
                    "${case.label} allowed headers",
                )
                assertHeaderContains(
                    response.headers[HttpHeaders.AccessControlAllowHeaders],
                    HttpHeaders.Authorization,
                    "${case.label} allowed headers",
                )
            }
        }

    private fun assertHeaderContains(
        header: String?,
        expected: String,
        messagePrefix: String,
    ) {
        assertTrue(
            header.orEmpty()
                .split(",")
                .map { it.trim() }
                .any { it.equals(expected, ignoreCase = true) },
            "$messagePrefix should include $expected, was ${header.orEmpty()}",
        )
    }

    private data class MutationPreflightCase(
        val path: String,
        val method: HttpMethod,
        val label: String,
    )
}
