package com.hookah.platform.backend

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthTest {
    @Test
    fun `health endpoint returns ok`() =
        testApplication {
            environment {
                config = MapApplicationConfig("db.jdbcUrl" to "")
            }
            application { module() }

            val response = client.get("/health")
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("ok"))
        }

    @Test
    fun `metrics endpoint returns prometheus payload`() =
        testApplication {
            environment {
                config = MapApplicationConfig("db.jdbcUrl" to "")
            }
            application { module() }

            val response = client.get("/metrics")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("inbound_queue_depth"))
            assertTrue(body.contains("outbound_send_success_total"))
        }
}
